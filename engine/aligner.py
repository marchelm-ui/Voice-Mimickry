"""Precise word boundaries by dynamic time warping against a reference reading.

The recognizer tells us WHAT was said but its timings are coarse (40 ms grid and often
early/late by a word). So we synthesize the transcript one word at a time with a stock
voice (exact boundaries known), then warp that reference onto the real recording using
speaker-normalized spectral features. Each reference boundary maps to a recording time,
which we then snap to the nearest low-energy point.
"""
import os
import subprocess
import tempfile

import numpy as np

import dsp

TTS_BIN = os.environ.get("KENKU_TTS_BIN", "/home/claude/dl/sherpa-onnx-v1.13.8-linux-x64-static/bin/sherpa-onnx-offline-tts")
KOKORO = os.environ.get("KENKU_KOKORO", "/home/claude/dl/kokoro-multi-lang-v1_0")
REF_SID = 16   # stock reference voice
SNAP_RADIUS = 0   # measured: snapping to energy dips moves cuts AWAY from true boundaries
FEMALE_REFS = (2, 9)
MALE_REFS = (11, 13)


def _kokoro(text, sid=REF_SID, speed=1.0):
    with tempfile.TemporaryDirectory() as td:
        p = os.path.join(td, "r.wav")
        subprocess.run([TTS_BIN, f"--kokoro-model={KOKORO}/model.onnx", f"--kokoro-voices={KOKORO}/voices.bin",
                        f"--kokoro-tokens={KOKORO}/tokens.txt", f"--kokoro-data-dir={KOKORO}/espeak-ng-data",
                        f"--kokoro-lexicon={KOKORO}/lexicon-us-en.txt", "--num-threads=2", f"--sid={sid}",
                        f"--kokoro-length-scale={1/speed}", f"--output-filename={p}", text],
                       check=True, capture_output=True)
        return dsp.load(p)


def _split_on_silence(x, n):
    """Split audio of n words read with full stops between them into n trimmed chunks."""
    db = dsp.rms_db(x, win=240)
    thr = np.percentile(db, 95) - 35
    voiced = db > thr
    # find gaps >= 60 ms
    gaps = []
    i = 0
    while i < len(voiced):
        if not voiced[i]:
            j = i
            while j < len(voiced) and not voiced[j]:
                j += 1
            if j - i >= 12:
                gaps.append((i, j))
            i = j
        else:
            i += 1
    inner = [g for g in gaps if g[0] > 0 and g[1] < len(voiced)]
    if len(inner) < n - 1:
        return None
    inner = sorted(sorted(inner, key=lambda g: -(g[1] - g[0]))[:n - 1])
    cuts = [0] + [((a + b) // 2) for a, b in inner] + [len(voiced)]
    chunks = []
    for a, b in zip(cuts[:-1], cuts[1:]):
        v = np.where(voiced[a:b])[0]
        if len(v) == 0:
            return None
        s, e = (a + v[0]) * dsp.HOP, (a + v[-1] + 2) * dsp.HOP
        chunks.append(x[s:e])
    return chunks


def reference(words):
    """Reference audio for a word list -> (audio, boundaries in samples [(s,e)...])."""
    text = ". ".join(w.lower() for w in words) + "."
    x = _kokoro(text)
    chunks = _split_on_silence(x, len(words))
    if chunks is None:
        chunks = [_kokoro(w.lower() + ".") for w in words]
        chunks = [c[_trim(c)[0]:_trim(c)[1]] for c in chunks]
    parts, bounds, t = [], [], 0
    for c in chunks:
        parts.append(c)
        bounds.append((t, t + len(c)))
        t += len(c)
    return np.concatenate(parts), bounds


def _trim(x):
    db = dsp.rms_db(x, win=240)
    v = np.where(db > np.max(db) - 35)[0]
    if len(v) == 0:
        return 0, len(x)
    return v[0] * dsp.HOP, min(len(x), (v[-1] + 2) * dsp.HOP)


def features(x):
    m = dsp.mfcc(x, n=20, n_mels=40)[:, 1:]          # drop c0 (loudness)
    m = (m - m.mean(0)) / (m.std(0) + 1e-6)            # speaker / channel normalization
    d = np.gradient(m, axis=0)
    return np.hstack([m, 0.5 * d])


def dtw(a, b, band=0.25):
    """Path between feature sequences a (n) and b (m). Sakoe-Chiba band as a fraction."""
    n, m = len(a), len(b)
    cost = np.sqrt(((a[:, None, :] - b[None, :, :]) ** 2).sum(-1))
    w = max(int(band * max(n, m)), abs(n - m) + 10)
    D = np.full((n + 1, m + 1), np.inf)
    D[0, 0] = 0
    for i in range(1, n + 1):
        c = i * m / n
        lo, hi = max(1, int(c - w)), min(m, int(c + w))
        prev = D[i - 1]
        row = D[i]
        for j in range(lo, hi + 1):
            row[j] = cost[i - 1, j - 1] + min(prev[j - 1], prev[j] + 0.3, row[j - 1] + 0.3)
    # backtrack
    i, j = n, m
    path = [(i - 1, j - 1)]
    while i > 1 or j > 1:
        opts = [(D[i - 1, j - 1], i - 1, j - 1), (D[i - 1, j], i - 1, j), (D[i, j - 1], i, j - 1)]
        _, i, j = min(opts)
        i, j = max(1, i), max(1, j)
        path.append((i - 1, j - 1))
    return path[::-1], D[n, m] / (n + m)


def align(audio, words, sid=None, end="."):
    """-> (word spans [(s,e)], phone spans per word [[(ph,s,e)]]) in `audio` samples.
    With sid=None, tries two reference voices matched to the speaker's pitch range and
    keeps whichever warps onto the recording with the lower cost."""
    if sid is None:
        f0, _ = dsp.f0_yin(audio)
        v = f0[f0 > 0]
        refs = FEMALE_REFS if (len(v) and np.median(v) > 165) else MALE_REFS
        best = None
        for r in refs:
            res = _align_one(audio, words, r, end)
            if best is None or res[2] < best[2]:
                best = res
        return best[0], best[1]
    w, p, _ = _align_one(audio, words, sid, end)
    return w, p


def _align_one(audio, words, sid, end):
    import kokoro_ref
    s0, e0 = _trim(audio)
    rec = audio[s0:e0]
    ref, wspans, pspans = kokoro_ref.read(words, sid=sid, end=end)
    r0, r1 = _trim(ref)
    # match the reference's tempo to the recording so warping only fixes local timing
    speed = float(np.clip((r1 - r0) / max(1, len(rec)), 0.6, 1.7))
    if abs(speed - 1) > 0.08:
        ref, wspans, pspans = kokoro_ref.read(words, sid=sid, speed=speed, end=end)
        r0, r1 = _trim(ref)
    fa, fb = features(ref[r0:r1]), features(rec)
    path, cost = dtw(fa, fb)
    mp = {}
    for i, j in path:
        mp.setdefault(i, []).append(j)

    def frame(sample):
        return int(np.clip((sample - r0) // dsp.HOP, 0, len(fa) - 1))

    def to_rec(sample, how="median"):
        js = mp.get(frame(sample), [frame(sample)])
        j = {"median": np.median(js), "first": min(js), "last": max(js)}[how]
        return int(j) * dsp.HOP

    db = dsp.rms_db(rec, win=240)

    def snap(x, radius=None):
        radius = SNAP_RADIUS if radius is None else radius
        f = x // dsp.HOP
        lo, hi = max(0, f - radius), min(len(db) - 1, f + radius)
        if hi <= lo:
            return x
        return (lo + int(np.argmin(db[lo:hi + 1]))) * dsp.HOP

    # A word starts at the LAST recording frame its first reference frame maps to, and ends at
    # the FIRST frame its last reference frame maps to: where the speaker paused but the
    # reference didn't, the warp path runs flat across the pause, and these choices keep the
    # pause outside both words instead of splitting it (measured: neighbouring-word fragments
    # leaking into cuts was the main cause of garbled output).
    words_out, phones_out = [], []
    for k in range(len(words)):
        ws, we = wspans[k]
        a = 0 if k == 0 else to_rec(ws, "last")
        b = len(rec) if k == len(words) - 1 else to_rec(max(ws, we - 1), "first") + dsp.HOP
        words_out.append([s0 + a, s0 + max(a + dsp.HOP, b)])
        phones_out.append([(ph, s0 + to_rec(ps, "last"), s0 + to_rec(max(ps, pe - 1), "first") + dsp.HOP) for ph, ps, pe in pspans[k]])
    for k in range(1, len(words_out)):
        if words_out[k][0] < words_out[k - 1][0] + dsp.HOP:
            words_out[k][0] = words_out[k - 1][0] + dsp.HOP
        if words_out[k - 1][1] > words_out[k][0]:
            words_out[k - 1][1] = words_out[k][0]
    words_out = [tuple(w) for w in words_out]
    return words_out, phones_out, cost
