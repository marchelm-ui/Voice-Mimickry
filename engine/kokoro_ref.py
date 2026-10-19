"""Direct Kokoro inference used as a *reference reader* for alignment.

Run straight through onnxruntime so we also get the per-phoneme durations the model
predicts. That gives a reference reading with exact word and phoneme boundaries, which
the aligner then warps onto real recordings.
"""
import os

import numpy as np

KOKORO = os.environ.get("KENKU_KOKORO", "/home/claude/dl/kokoro-multi-lang-v1_0")
FRAME = 600   # samples per duration unit at 24 kHz

_state = {}

# crude letter fallback for words missing from the lexicon (rare: names, made-up words)
LETTER = {"a": "æ", "b": "b", "c": "k", "d": "d", "e": "ɛ", "f": "f", "g": "ɡ", "h": "h", "i": "ɪ", "j": "ʤ",
          "k": "k", "l": "l", "m": "m", "n": "n", "o": "ɑ", "p": "p", "q": "k", "r": "ɹ", "s": "s", "t": "t",
          "u": "ʌ", "v": "v", "w": "w", "x": "ks", "y": "j", "z": "z"}


def _load():
    if _state:
        return _state
    import onnxruntime as ort
    tok = {}
    for line in open(os.path.join(KOKORO, "tokens.txt"), encoding="utf8"):
        p = line.rstrip("\n").rsplit(" ", 1)
        if len(p) == 2:
            tok[p[0] if p[0] else " "] = int(p[1])
    lex = {}
    for line in open(os.path.join(KOKORO, "lexicon-us-en.txt"), encoding="utf8"):
        p = line.split()
        if p and p[0] not in lex:
            lex[p[0]] = p[1:]
    voices = np.fromfile(os.path.join(KOKORO, "voices.bin"), dtype=np.float32).reshape(54, -1, 256)
    so = ort.SessionOptions()
    so.intra_op_num_threads = 2
    sess = ort.InferenceSession(os.path.join(KOKORO, "model.onnx"), so)
    _state.update(tok=tok, lex=lex, voices=voices, sess=sess)
    return _state


def phonemes(word):
    st = _load()
    w = word.lower()
    if w in st["lex"]:
        return st["lex"][w]
    if w.replace("'", "") in st["lex"]:
        return st["lex"][w.replace("'", "")]
    out = []
    for ch in w:
        out.extend(list(LETTER.get(ch, "")))
    return out or ["ə"]


def read(words, sid=16, speed=1.0, end="."):
    """-> audio, word_spans [(s,e)], phone_spans [[(phone, s, e), ...] per word]."""
    st = _load()
    tok = st["tok"]
    ids = [0]
    owner = [None]            # which word each token belongs to (None = pad/space/punct)
    phs = [None]
    for i, w in enumerate(words):
        for ph in phonemes(w):
            if ph in tok:
                ids.append(tok[ph])
                owner.append(i)
                phs.append(ph)
        if i < len(words) - 1:
            ids.append(tok[" "])
            owner.append(None)
            phs.append(None)
    ids += [tok.get(end, tok["."]), 0]
    owner += [None, None]
    phs += [None, None]
    style = st["voices"][sid][min(len(ids) - 2, st["voices"].shape[1] - 1)][None, :]
    audio, dur = st["sess"].run(None, {"tokens": np.array([ids], dtype=np.int64), "style": style,
                                       "speed": np.array([speed], dtype=np.float32)})
    audio = audio.astype(np.float32)
    scale = len(audio) / max(1, int(dur.sum()))
    t = np.concatenate([[0], np.cumsum(dur)]) * scale
    spans = [[None, None] for _ in words]
    phones = [[] for _ in words]
    for k, o in enumerate(owner):
        if o is None:
            continue
        s, e = int(t[k]), int(t[k + 1])
        if spans[o][0] is None:
            spans[o][0] = s
        spans[o][1] = e
        phones[o].append((phs[k], s, e))
    spans = [(s or 0, e or 0) for s, e in spans]
    return audio, spans, phones
