"""The kenku's memory: everything it has heard, cut into words with their sound and prosody.

capture(audio) -> Recording with word boundaries (from on-device speech recognition,
then refined on the waveform so cuts land in natural low-energy gaps).
"""
import json
import os
import re
import subprocess
import tempfile
from dataclasses import dataclass, field

import numpy as np

import aligner
import dsp

ASR_BIN = os.environ.get("KENKU_ASR_BIN", "/home/claude/dl/sherpa-onnx-v1.13.8-linux-x64-static/bin/sherpa-onnx-offline")
ASR_PAD = 0.4
ASR_DIR = os.environ.get("KENKU_ASR_DIR", "/home/claude/dl/sherpa-onnx-zipformer-en-2023-06-26")


@dataclass
class Word:
    text: str
    s: int              # start sample (24 kHz)
    e: int              # end sample
    pause_after: float  # seconds of silence after the word
    f0_start: float = 0.0
    f0_end: float = 0.0
    f0_mean: float = 0.0
    loud: float = -30.0
    mf_in: np.ndarray = None
    mf_out: np.ndarray = None
    final: bool = False     # ends a phrase (pause or end of recording follows)
    initial: bool = False
    question: bool = False  # utterance-final rise
    conf: float = 1.0
    phones: list = None


@dataclass
class Recording:
    rid: str
    audio: np.ndarray
    words: list = field(default_factory=list)
    f0: np.ndarray = None
    transcript: str = ""


# ---------------------------------------------------------------- speech recognition

def recognize(paths16k, batch=6):
    """Run the recognizer on 16 kHz wav files -> list of (text, tokens, token_start_times).

    Batched: the recognizer holds every file's attention state at once, so a long list of long
    readings gets the process OOM-killed (seen with LibriSpeech speaker 422: 28 files, rc -9).
    """
    out = []
    paths16k = list(paths16k)
    for s in range(0, len(paths16k), batch):
        chunk = paths16k[s:s + batch]
        cmd = [ASR_BIN, f"--tokens={ASR_DIR}/tokens.txt",
               f"--encoder={ASR_DIR}/encoder-epoch-99-avg-1.int8.onnx",
               f"--decoder={ASR_DIR}/decoder-epoch-99-avg-1.onnx",
               f"--joiner={ASR_DIR}/joiner-epoch-99-avg-1.int8.onnx",
               "--num-threads=2"] + chunk
        r = subprocess.run(cmd, capture_output=True, text=True)
        blob = r.stdout + r.stderr
        got = []
        for m in re.finditer(r'\{"lang".*?"words": \[[^\]]*\]\}', blob, re.S):
            j = json.loads(m.group(0))
            got.append((j["text"], j["tokens"], j["timestamps"], j.get("ys_log_probs", [])))
        if len(got) != len(chunk):
            raise RuntimeError(f"ASR returned {len(got)} results for {len(chunk)} files (rc {r.returncode})")
        out += got
    return out


def transcribe(audios):
    """24 kHz float arrays -> recognizer outputs (batched through temp files)."""
    with tempfile.TemporaryDirectory() as td:
        paths = []
        for i, a in enumerate(audios):
            p = os.path.join(td, f"{i}.wav")
            # Lead/trail silence: the recognizer tends to drop a first word that starts at t=0.
            pad = np.zeros(int(ASR_PAD * 16000), dtype=np.float32)
            dsp.save(p, np.concatenate([pad, dsp.resample(a, dsp.SR, 16000), pad]), 16000)
            paths.append(p)
        res = recognize(paths)
        return [(t, tok, [max(0.0, x - ASR_PAD) for x in ts], lp) for t, tok, ts, lp in res]


# ---------------------------------------------------------------- word cutting

def _words_from_tokens(tokens, times):
    words = []
    for tok, t in zip(tokens, times):
        if tok.startswith(" ") or not words:
            if tok.strip() == "":
                continue
            words.append([tok.strip(), t])
        else:
            words[-1][0] += tok
    return words


def _word_confidence(tokens, logps):
    """Per-word confidence = probability of the least certain token in the word."""
    conf = []
    for tok, lp in zip(tokens, logps or [0.0] * len(tokens)):
        if tok.startswith(" ") or not conf:
            if tok.strip() == "":
                continue
            conf.append(lp)
        else:
            conf[-1] = min(conf[-1], lp)
    return [float(np.exp(c)) for c in conf]


def capture(rid, audio, asr=None, transcript=None):
    """Index one recording.
    asr: recognizer output if already run. transcript: a corrected transcript from the
    player (overrides the recognizer, and every word then counts as certain)."""
    audio = audio.astype(np.float32)
    peak = np.max(np.abs(audio)) + 1e-9
    audio = audio * min(1.0, 0.89 / peak)
    if transcript is None and asr is None:
        asr = transcribe([audio])[0]
    if transcript is not None:
        import text as textmod
        words = textmod.words_only(transcript)
        conf = [1.0] * len(words)
    else:
        text, tokens, times, logps = asr
        words = text.split()
        conf = _word_confidence(tokens, logps)
        if len(conf) != len(words):
            conf = [0.9] * len(words)
    rec = Recording(rid, audio, transcript=" ".join(words))
    if not words:
        return rec
    rec.f0, _ = dsp.f0_yin(audio)
    spans, phones = aligner.align(audio, words)
    db = dsp.rms_db(audio, win=240, hop=dsp.HOP)
    top = np.percentile(db, 98)
    silent = db < top - 35
    hop = dsp.HOP
    n = len(audio)

    def fr(sample):
        return int(np.clip(sample // hop, 0, len(db) - 1))

    for k, (w, (s, e)) in enumerate(zip(words, spans)):
        # Trim silence at the end of the word: that is a pause, not part of the word.
        fs_, fe = fr(s), fr(e)
        speech = np.where(~silent[fs_:fe])[0]
        e_trim = min(e, (fs_ + speech[-1] + 3) * hop) if len(speech) else e
        s_trim = s
        if k == 0 and len(speech):
            s_trim = max(s, (fs_ + speech[0] - 2) * hop)
        pause = (e - e_trim) / dsp.SR
        if e_trim - s_trim < int(0.03 * dsp.SR):
            continue
        wd = Word(w, int(s_trim), int(min(n, e_trim)), pause)
        wd.conf = conf[k] if k < len(conf) else 0.9
        wd.phones = phones[k]
        rec.words.append(wd)

    mf = dsp.mfcc(audio)
    for k, wd in enumerate(rec.words):
        a, b = fr(wd.s), fr(wd.e)
        seg = rec.f0[a:b]
        v = seg[seg > 0]
        wd.f0_mean = float(np.median(v)) if len(v) else 0.0
        head = seg[:max(1, len(seg) // 3)]
        tail = seg[-max(1, len(seg) // 3):]
        wd.f0_start = float(np.median(head[head > 0])) if np.any(head > 0) else wd.f0_mean
        wd.f0_end = float(np.median(tail[tail > 0])) if np.any(tail > 0) else wd.f0_mean
        wd.loud = dsp.loudness(audio[wd.s:wd.e])
        wd.mf_in = mf[min(len(mf) - 1, a):min(len(mf), a + 3)].mean(axis=0)
        wd.mf_out = mf[max(0, b - 3):max(1, b)].mean(axis=0)
        wd.final = wd.pause_after > 0.05 or k == len(rec.words) - 1
        wd.initial = k == 0 or rec.words[k - 1].pause_after > 0.05
    for wd in rec.words:
        if wd.final and wd.f0_start > 0 and wd.f0_end > 0:
            wd.question = wd.f0_end / wd.f0_start > 1.12
    return rec


class Bank:
    """All recordings of one voice (one 'friend' the kenku has listened to)."""

    def __init__(self, name):
        self.name = name
        self.recs = []
        self.index = {}   # word text -> [(rec idx, word idx)]
        self.f0_median = 0.0
        self.loud_median = -25.0

    def add(self, rec):
        ri = len(self.recs)
        self.recs.append(rec)
        for wi, w in enumerate(rec.words):
            self.index.setdefault(w.text, []).append((ri, wi))
        f0s = [w.f0_mean for r in self.recs for w in r.words if w.f0_mean > 0]
        self.f0_median = float(np.median(f0s)) if f0s else 0.0
        ls = [w.loud for r in self.recs for w in r.words]
        self.loud_median = float(np.median(ls)) if ls else -25.0

    def vocabulary(self):
        return set(self.index)

    def reference_clip(self, seconds=12.0):
        """A clean stretch of this voice for the neural fallback to copy timbre from."""
        recs = sorted(self.recs, key=lambda r: -len(r.audio))
        parts, total = [], 0
        for r in recs:
            if not r.words:
                continue
            seg = r.audio[r.words[0].s:r.words[-1].e]
            parts.append(seg)
            parts.append(np.zeros(int(0.25 * dsp.SR), dtype=np.float32))
            total += len(seg)
            if total > seconds * dsp.SR:
                break
        return np.concatenate(parts) if parts else np.zeros(dsp.SR, dtype=np.float32)


def build_bank(name, audios, ids=None, transcripts=None):
    ids = ids or [f"{name}-{i}" for i in range(len(audios))]
    bank = Bank(name)
    asrs = transcribe(audios) if transcripts is None else [None] * len(audios)
    for i, (rid, a, asr) in enumerate(zip(ids, audios, asrs)):
        bank.add(capture(rid, a, asr, transcripts[i] if transcripts else None))
    return bank
