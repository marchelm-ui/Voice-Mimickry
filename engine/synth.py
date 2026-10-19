"""Kenku synthesis: say new lines using only (or mostly) sounds the voice actually made.

Pipeline
 1. Text -> words + pauses + prosodic roles (text.py).
 2. Unit selection: cover each phrase with the fewest, best-fitting stretches of real
    recorded speech (multi-word spans keep natural coarticulation). Viterbi over target
    costs (prosodic position, question/statement, confidence) + join costs (spectral,
    pitch and loudness mismatch at the seam).
 3. Unheard words: splice them from phoneme pieces of the same voice ("phones"), or, if
    pieces are missing, borrow a neural clone of the voice ("neural").
 4. Prosody transplant: a reference reader predicts how the NEW sentence should rise,
    fall and pace; each unit is nudged (PSOLA) toward that contour in the speaker's own
    pitch range, and seams are pitch-matched so joins don't jump.
 5. Assembly: loudness matching with gentle declination, short equal-power crossfades,
    room-tone filled pauses, final-word lengthening.
"""
import os
import subprocess
import tempfile
from dataclasses import dataclass, field

import numpy as np

import dsp
import text as textmod

PAUSE_S = {",": 0.20, ".": 0.38, "!": 0.34, "?": 0.38}
XFADE = int(0.006 * dsp.SR)
MARGIN = int(0.004 * dsp.SR)
CONTEXT_WEIGHT = 0.25

POCKET = os.environ.get("KENKU_POCKET", "/home/claude/dl/sherpa-onnx-pocket-tts-int8-2026-01-26")
TTS_BIN = os.environ.get("KENKU_TTS_BIN", "/home/claude/dl/sherpa-onnx-v1.13.8-linux-x64-static/bin/sherpa-onnx-offline-tts")


@dataclass
class Opts:
    fallback: str = "auto"         # "phones", "neural", "auto"
    prosody: float = 0.7           # 0 = keep source intonation, 1 = fully follow reference
    timing: float = 0.5            # how strongly to follow reference word durations
    smooth_joins: bool = True
    room_tone: bool = True
    match_loudness: bool = True
    max_span: int = 8
    context: float = 0.25          # weight for matching neighbouring words
    diphone: bool = True           # join spliced sounds mid-phone


@dataclass
class Unit:
    words: list                     # target word tokens covered
    audio: np.ndarray = None
    src: tuple = None               # (rec idx, first word idx, last word idx) or None
    kind: str = "real"              # real | phones | neural
    f0_start: float = 0.0
    f0_end: float = 0.0
    mf_in: np.ndarray = None
    mf_out: np.ndarray = None
    word_lens: list = field(default_factory=list)   # sample length of each word inside


# ---------------------------------------------------------------- unit selection

def _phrases(tokens):
    ph, cur = [], []
    for t in tokens:
        if "w" in t:
            cur.append(t)
        else:
            if cur:
                ph.append((cur, t["pause"]))
            cur = []
    if cur:
        ph.append((cur, "."))
    return ph


def _candidates(bank, words, max_span):
    """cands[i] = list of (length, rec, wi) spans whose words match words[i:i+length]."""
    out = [[] for _ in words]
    for i, w in enumerate(words):
        for ri, wi in bank.index.get(w["w"], []):
            rec = bank.recs[ri]
            L = 1
            while (L < max_span and i + L < len(words) and wi + L < len(rec.words)
                   and rec.words[wi + L].text == words[i + L]["w"]
                   and rec.words[wi + L - 1].pause_after < 0.12):
                L += 1
            for l in range(1, L + 1):
                out[i].append((l, ri, wi))
    return out


def _target_cost(bank, ri, wi, l, words, i, end):
    rec = bank.recs[ri]
    first, last = rec.words[wi], rec.words[wi + l - 1]
    c = 1.0                                      # every unit = one more seam
    tl = words[i + l - 1]
    t_final = i + l == len(words)
    if t_final and not last.final:
        c += 0.9                                 # needs a phrase-final fall/lengthening
    if not t_final and last.final:
        c += 0.5                                 # source winds down where target continues
    if t_final and end == "?" and not last.question:
        c += 0.4
    if t_final and end != "?" and last.question:
        c += 0.8
    if i == 0 and not first.initial:
        c += 0.15
    if i > 0 and first.initial:
        c += 0.1
    c += 2.0 * sum(1 - rec.words[wi + k].conf for k in range(l))   # doubtful recognition
    # context: a word sounds different depending on its neighbours (coarticulation; "thuh" vs
    # "thee"). Prefer units whose recorded neighbours match the target's neighbours.
    if CONTEXT_WEIGHT:
        tprev = words[i - 1]["w"] if i > 0 else None
        tnext = words[i + l]["w"] if i + l < len(words) else None
        sprev = rec.words[wi - 1].text if wi > 0 and not rec.words[wi - 1].final else None
        snext = rec.words[wi + l].text if wi + l < len(rec.words) and not last.final else None
        if tprev != sprev:
            c += CONTEXT_WEIGHT * (0.5 if (tprev and sprev and tprev[0] == sprev[0]) else 1.0)
        if tnext != snext:
            c += CONTEXT_WEIGHT * (0.5 if (tnext and snext and tnext[0] == snext[0]) else 1.0)
    return c


def _join_cost(bank, a, b):
    """a, b = (l, ri, wi) consecutive candidates."""
    la, ra, wa = a
    lb, rb, wb = b
    if ra == rb and wa + la == wb:
        return 0.0                               # naturally adjacent in the recording
    A = bank.recs[ra].words[wa + la - 1]
    B = bank.recs[rb].words[wb]
    spec = float(np.linalg.norm(A.mf_out[1:] - B.mf_in[1:])) / 20.0
    pitch = abs(np.log2(max(A.f0_end, 1) / max(B.f0_start, 1))) * 3 if A.f0_end > 0 and B.f0_start > 0 else 0.3
    loud = abs(A.loud - B.loud) / 12.0
    return 0.4 + spec + pitch + loud


def select(bank, words, end, max_span=8):
    """Viterbi over spans. Returns list of (l, ri, wi) or (1, None, None) for unheard words."""
    n = len(words)
    cands = _candidates(bank, words, max_span)
    # state = candidate ending at position j; best[j] = {cand: (cost, back)}
    best = [dict() for _ in range(n + 1)]
    best[0][None] = (0.0, None)
    for i in range(n):
        if not best[i]:
            continue
        opts = cands[i] or [(1, None, None)]
        if cands[i] is not None and not cands[i]:
            opts = [(1, None, None)]
        for c in opts:
            l, ri, wi = c
            tc = 6.0 if ri is None else _target_cost(bank, ri, wi, l, words, i, end)
            for prev, (pc, _) in best[i].items():
                jc = 0.0 if (prev is None or prev[1] is None or ri is None) else _join_cost(bank, (prev[0], prev[1], prev[2]), c)
                tot = pc + tc + jc
                key = (l, ri, wi, i)
                cur = best[i + l].get(key)
                if cur is None or tot < cur[0]:
                    best[i + l][key] = (tot, (i, prev))
    # backtrack
    end_key = min(best[n], key=lambda k: best[n][k][0])
    path = []
    key, j = end_key, n
    while key is not None:
        path.append(key[:3])
        _, (i, prev) = best[j][key]
        key, j = prev, i
    return path[::-1]


# ---------------------------------------------------------------- unheard words

def _phone_units(bank):
    if getattr(bank, "_phone_index", None) is None:
        idx = {}
        for ri, rec in enumerate(bank.recs):
            for wi, w in enumerate(rec.words):
                ph = w.phones or []
                for a in range(len(ph)):
                    for b in range(a + 1, min(len(ph), a + 5) + 1):
                        key = tuple(p for p, _, _ in ph[a:b])
                        idx.setdefault(key, []).append((ri, wi, a, b))
        bank._phone_index = idx
    return bank._phone_index


def splice_word(bank, word):
    """Build an unheard word from phoneme pieces of this voice. None if a sound is missing."""
    import kokoro_ref
    target = [p for p in kokoro_ref.phonemes(word) if p not in ("ˈ", "ˌ")]
    idx = _phone_units(bank)
    pieces, i = [], 0
    while i < len(target):
        got = None
        for L in range(min(5, len(target) - i), 0, -1):
            key = tuple(target[i:i + L])
            opts = idx.get(key)
            if opts:
                # prefer pieces at the same position in their word (initial/final)
                def score(o):
                    ri, wi, a, b = o
                    n = len(bank.recs[ri].words[wi].phones)
                    s = 0
                    s += 0 if (a == 0) == (i == 0) else 1
                    s += 0 if (b == n) == (i + L == len(target)) else 1
                    s += 2 * (1 - bank.recs[ri].words[wi].conf)
                    return s
                got = (L, min(opts, key=score))
                break
        if got is None:
            # stress marks are separate tokens in the lexicon; try without
            return None
        L, (ri, wi, a, b) = got
        ph = bank.recs[ri].words[wi].phones
        s, e = ph[a][1], ph[b - 1][2]
        pieces.append(bank.recs[ri].audio[max(0, s - MARGIN):e + MARGIN])
        i += L
    out = pieces[0]
    for p in pieces[1:]:
        out = dsp.crossfade_concat(out, p, min(XFADE, len(p) // 3, len(out) // 3))
    return out


def splice_word_diphone(bank, word):
    """Like splice_word, but consecutive pieces overlap by one sound and are joined in the MIDDLE
    of that shared sound, the steadiest point, as classic diphone synthesizers do. Pieces must
    cover at least two sounds so each one carries a real transition."""
    import kokoro_ref
    target = [p for p in kokoro_ref.phonemes(word) if p not in ("ˈ", "ˌ")]
    if len(target) < 2:
        return splice_word(bank, word)
    idx = _phone_units(bank)
    segs = []                     # (ri, wi, a, b, ti, tj) covering target[ti:tj]
    i = 0
    while i < len(target) - 1 or not segs:
        got = None
        for L in range(min(5, len(target) - i), 1, -1):
            opts = idx.get(tuple(target[i:i + L]))
            if opts:
                def score(o):
                    ri, wi, a, b = o
                    n = len(bank.recs[ri].words[wi].phones)
                    s = 0 if (a == 0) == (i == 0) else 1
                    s += 0 if (b == n) == (i + L == len(target)) else 1
                    return s + 2 * (1 - bank.recs[ri].words[wi].conf)
                got = (L, min(opts, key=score))
                break
        if got is None:
            return splice_word(bank, word)
        L, (ri, wi, a, b) = got
        segs.append((ri, wi, a, b, i, i + L))
        if i + L >= len(target):
            break
        i = i + L - 1             # overlap one sound
    out = None
    for k, (ri, wi, a, b, ti, tj) in enumerate(segs):
        ph = bank.recs[ri].words[wi].phones
        audio = bank.recs[ri].audio
        s = ph[a][1] if k == 0 else (ph[a][1] + ph[a][2]) // 2
        e = ph[b - 1][2] if k == len(segs) - 1 else (ph[b - 1][1] + ph[b - 1][2]) // 2
        piece = audio[max(0, s - (MARGIN if k == 0 else 0)):e + (MARGIN if k == len(segs) - 1 else 0)]
        if len(piece) < 2:
            return splice_word(bank, word)
        out = piece if out is None else dsp.crossfade_concat(out, piece, min(int(0.012 * dsp.SR), len(piece) // 3, len(out) // 3))
    return out


def neural_words(bank, phrase_words, need):
    """Neural clone of the voice says the whole phrase; we cut out just the needed words."""
    import aligner
    ref = bank.reference_clip()
    with tempfile.TemporaryDirectory() as td:
        rp, op = os.path.join(td, "ref.wav"), os.path.join(td, "out.wav")
        dsp.save(rp, ref)
        text = " ".join(w.lower() for w in phrase_words) + "."
        subprocess.run([TTS_BIN, f"--pocket-lm-flow={POCKET}/lm_flow.int8.onnx",
                        f"--pocket-lm-main={POCKET}/lm_main.int8.onnx", f"--pocket-encoder={POCKET}/encoder.onnx",
                        f"--pocket-decoder={POCKET}/decoder.int8.onnx",
                        f"--pocket-text-conditioner={POCKET}/text_conditioner.onnx",
                        f"--pocket-vocab-json={POCKET}/vocab.json", f"--pocket-token-scores-json={POCKET}/token_scores.json",
                        f"--reference-audio={rp}", "--num-threads=2", f"--output-filename={op}", text],
                       check=True, capture_output=True)
        x = dsp.load(op)
    spans, _ = aligner.align(x, phrase_words)
    return {k: x[spans[k][0]:spans[k][1]] for k in need}


# ---------------------------------------------------------------- prosody

def _semitones(f, ref):
    return 12 * np.log2(np.maximum(f, 1e-3) / ref)


def reference_prosody(words, end, sid):
    """Per-word (duration in samples, mean semitone, end semitone) from the reference reader,
    relative to the reader's own median pitch."""
    import kokoro_ref
    a, spans, _ = kokoro_ref.read(words, sid=sid, end=end if end in ".!?," else ".")
    f0, _ = dsp.f0_yin(a)
    v = f0[f0 > 0]
    med = np.median(v) if len(v) else 150.0
    out = []
    for s, e in spans:
        seg = f0[s // dsp.HOP:max(s // dsp.HOP + 1, e // dsp.HOP)]
        vv = seg[seg > 0]
        if len(vv) == 0:
            out.append((e - s, None, None, None))
            continue
        st = _semitones(vv, med)
        k = max(1, len(st) // 3)
        out.append((e - s, float(np.mean(st)), float(np.mean(st[:k])), float(np.mean(st[-k:]))))
    return out


# ---------------------------------------------------------------- rendering

def _unit_audio(bank, path, words, opts, phrase_words):
    units = []
    need_neural = []
    for k, (l, ri, wi) in enumerate(path):
        pos = sum(p[0] for p in path[:k])
        if ri is None:
            w = words[pos]["w"]
            u = Unit([words[pos]], kind="phones")
            x = None
            if opts.fallback in ("phones", "auto"):
                x = splice_word_diphone(bank, w) if opts.diphone else splice_word(bank, w)
            if x is None:
                u.kind = "neural"
                need_neural.append((len(units), pos))
            u.audio = x
            u.word_lens = [len(x)] if x is not None else []
            units.append(u)
            continue
        rec = bank.recs[ri]
        ws = rec.words[wi:wi + l]
        s, e = ws[0].s, ws[-1].e
        u = Unit(words[pos:pos + l], src=(ri, wi, wi + l - 1), kind="real")
        u.audio = rec.audio[max(0, s - MARGIN):min(len(rec.audio), e + MARGIN)].copy()
        u.word_lens = [w.e - w.s for w in ws]
        u.word_lens[0] += MARGIN
        u.word_lens[-1] += MARGIN
        units.append(u)
    if need_neural:
        if opts.fallback == "phones":
            for ui, _ in need_neural:
                units[ui].audio = np.zeros(int(0.25 * dsp.SR), dtype=np.float32)
                units[ui].word_lens = [len(units[ui].audio)]
        else:
            got = neural_words(bank, phrase_words, [pos for _, pos in need_neural])
            for ui, pos in need_neural:
                units[ui].audio = got[pos]
                units[ui].word_lens = [len(got[pos])]
    return units


def _apply_prosody(bank, units, ref, opts, end):
    """Nudge each unit toward the reference contour in this speaker's pitch range, and make
    pitch continuous across seams."""
    med = bank.f0_median or 150.0
    wi = 0
    plans = []
    for u in units:
        f0, _ = dsp.f0_yin(u.audio)
        n = len(f0)
        ratio = np.ones(n)
        tratio = np.ones(n)
        # per-word targets inside this unit
        t = 0
        for k, L in enumerate(u.word_lens):
            r = ref[wi + k] if wi + k < len(ref) else (L, None, None, None)
            a, b = t // dsp.HOP, min(n, (t + L) // dsp.HOP + 1)
            seg = f0[a:b]
            vv = seg[seg > 0]
            if r[1] is not None and len(vv) > 2:
                cur = _semitones(vv, med)
                kk = max(1, len(cur) // 3)
                cur_s, cur_e = float(np.mean(cur[:kk])), float(np.mean(cur[-kk:]))
                ds = (r[2] - cur_s) * opts.prosody
                de = (r[3] - cur_e) * opts.prosody
                ramp = np.linspace(ds, de, max(1, b - a))
                ratio[a:b] = 2 ** (np.clip(ramp, -5, 5) / 12)
            t += L
        # timing: follow reference word durations relative to this sentence's tempo
        plans.append((u, f0, ratio, tratio))
        wi += len(u.word_lens)
    # tempo normalisation: scale reference durations to the speaker's actual rate
    tot_src = sum(sum(u.word_lens) for u in units)
    tot_ref = sum(r[0] for r in ref) or 1
    scale = tot_src / tot_ref
    wi = 0
    for u, f0, ratio, tratio in plans:
        t = 0
        n = len(f0)
        for k, L in enumerate(u.word_lens):
            r = ref[wi + k] if wi + k < len(ref) else (L,)
            want = r[0] * scale
            tr = float(np.clip((want / max(L, 1)) ** opts.timing, 0.8, 1.25))
            a, b = t // dsp.HOP, min(n, (t + L) // dsp.HOP + 1)
            tratio[a:b] = tr
            t += L
        wi += len(u.word_lens)
    # final lengthening on the last word of the phrase
    u, f0, ratio, tratio = plans[-1]
    last = u.word_lens[-1] // dsp.HOP
    tratio[-last:] *= 1.08 if end in ".!?" else 1.03
    # seam pitch continuity: after the contour nudge, make adjacent ends meet halfway
    if opts.smooth_joins and len(plans) > 1:
        for k in range(len(plans) - 1):
            ua, fa, ra, _ = plans[k]
            ub, fb, rb, _ = plans[k + 1]
            ea = _edge_f0(fa * ra, tail=True)
            sb = _edge_f0(fb * rb, tail=False)
            if ea and sb:
                gap = 12 * np.log2(ea / sb)
                if abs(gap) > 0.3:
                    half = np.clip(gap / 2, -4, 4)
                    _ramp_edge(ra, -half, tail=True)
                    _ramp_edge(rb, half, tail=False)
    out = []
    for u, f0, ratio, tratio in plans:
        if np.allclose(ratio, 1, atol=0.004) and np.allclose(tratio, 1, atol=0.01):
            out.append(u.audio)
        else:
            out.append(dsp.psola(u.audio, f0, ratio, tratio))
    return out


def _edge_f0(f, tail, n=8):
    seg = f[-n * 3:] if tail else f[:n * 3]
    v = seg[seg > 0]
    if len(v) < 2:
        return None
    return float(np.median(v[-n:] if tail else v[:n]))


def _ramp_edge(ratio, semis, tail, n=24):
    n = min(n, len(ratio))
    ramp = 2 ** (np.linspace(0, semis, n) / 12)
    if tail:
        ratio[-n:] *= ramp
    else:
        ratio[:n] *= ramp[::-1]


def speak(bank, line, opts=None, ref_sid=None):
    """Say `line` in the voice of `bank`. Returns (audio, report)."""
    opts = opts or Opts()
    global CONTEXT_WEIGHT
    CONTEXT_WEIGHT = opts.context
    toks = textmod.normalize(line)
    if ref_sid is None:
        ref_sid = 2 if bank.f0_median > 165 else 11
    pieces = []
    report = {"units": 0, "words": 0, "real_words": 0, "phones_words": 0, "neural_words": 0, "seams": 0}
    target_loud = bank.loud_median
    phrases = _phrases(toks)
    all_audio = [r.audio for r in bank.recs]
    tone_src = np.concatenate(all_audio[:3]) if all_audio else np.zeros(dsp.SR, dtype=np.float32)
    for pi, (words, end) in enumerate(phrases):
        path = select(bank, words, end, opts.max_span)
        units = _unit_audio(bank, path, words, opts, [w["w"] for w in words])
        report["units"] += len(units)
        report["seams"] += max(0, len(units) - 1)
        for u in units:
            report["words"] += len(u.words)
            report[f"{u.kind}_words"] += len(u.words)
        ref = reference_prosody([w["w"] for w in words], end, ref_sid)
        audios = _apply_prosody(bank, units, ref, opts, end) if opts.prosody > 0 or opts.timing > 0 else [u.audio for u in units]
        # loudness: match to the voice's typical level with a slight fall across the line
        phrase = None
        for k, a in enumerate(audios):
            if opts.match_loudness:
                target = target_loud - 1.5 * (pi / max(1, len(phrases) - 1)) - 1.0 * (k / max(1, len(audios)))
                a = a * 10 ** ((target - dsp.loudness(a)) / 20)
            phrase = a if phrase is None else dsp.crossfade_concat(phrase, a, min(XFADE, len(a) // 4, len(phrase) // 4))
        # soften the very start/end of the phrase
        fade = min(len(phrase) // 4, int(0.005 * dsp.SR))
        phrase[:fade] *= np.linspace(0, 1, fade)
        phrase[-fade:] *= np.linspace(1, 0, fade)
        pieces.append(phrase)
        gap = PAUSE_S.get(end, 0.3) if pi < len(phrases) - 1 else 0.15
        pieces.append(dsp.room_tone(tone_src, gap, seed=pi) if opts.room_tone else np.zeros(int(gap * dsp.SR), np.float32))
    lead = dsp.room_tone(tone_src, 0.08, seed=99) if opts.room_tone else np.zeros(int(0.08 * dsp.SR), np.float32)
    out = np.concatenate([lead] + pieces).astype(np.float32)
    peak = np.max(np.abs(out)) + 1e-9
    if peak > 0.95:
        out *= 0.95 / peak
    return out, report
