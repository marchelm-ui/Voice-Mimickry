"""Objective scores for synthesized speech.

 - wer:        word error rate when a speech recognizer listens (intelligibility)
 - spk_sim:    cosine similarity of speaker embeddings (WeSpeaker ResNet34) to the real
               speaker's voice print (does it sound like THEM?)
 - dnsmos_*:   Microsoft DNSMOS predicted MOS for signal quality / background / overall
               (catches buzz, clicks, robotic artifacts; not a full naturalness judge)
 - seam_spike: how much worse the largest frame-to-frame spectral jumps are than in the
               real recording (audible glitches at joins); 1.0 = as smooth as real speech
 - mcd:        mel-cepstral distortion to the ground-truth recording after time warping
               (how close to how the person would really have said it), lower is better
 - f0_corr:    correlation of the pitch contour with the ground truth (intonation match)
"""
import os
import sys

import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "engine"))
import dsp  # noqa: E402

SPK_MODEL = os.environ.get("KENKU_SPK", "/home/claude/dl/wespeaker_en_voxceleb_resnet34.onnx")
DNSMOS = os.environ.get("KENKU_DNSMOS", "/home/claude/dns/DNSMOS/DNSMOS/sig_bak_ovr.onnx")
_sess = {}


def _session(path):
    if path not in _sess:
        import onnxruntime as ort
        so = ort.SessionOptions()
        so.intra_op_num_threads = 2
        so.log_severity_level = 3
        _sess[path] = ort.InferenceSession(path, so)
    return _sess[path]


# ---------------------------------------------------------------- speaker similarity

def kaldi_fbank(x16, n_mels=80):
    x = x16.astype(np.float64) * 32768.0
    win, hop, nfft = 400, 160, 512
    if len(x) < win:
        x = np.pad(x, (0, win - len(x)))
    n = 1 + (len(x) - win) // hop
    idx = np.arange(win)[None, :] + hop * np.arange(n)[:, None]
    f = x[idx]
    f = f - f.mean(axis=1, keepdims=True)
    f = np.concatenate([f[:, :1], f[:, 1:] - 0.97 * f[:, :-1]], axis=1)
    f = f * (np.hanning(win + 1)[:-1] ** 0.85)       # povey window
    p = np.abs(np.fft.rfft(f, nfft)) ** 2
    fb = dsp.mel_filterbank(16000, nfft, n_mels, fmin=20.0)
    m = np.log(np.maximum(p @ fb.T, 1.19e-7))
    return (m - m.mean(axis=0)).astype(np.float32)


def embedding(x24):
    x16 = dsp.resample(x24, dsp.SR, 16000)
    feats = kaldi_fbank(x16)[None]
    e = _session(SPK_MODEL).run(None, {"feats": feats})[0][0]
    return e / (np.linalg.norm(e) + 1e-9)


def voice_print(audios):
    e = np.mean([embedding(a) for a in audios], axis=0)
    return e / (np.linalg.norm(e) + 1e-9)


def spk_sim(x24, vp):
    return float(np.dot(embedding(x24), vp))


# ---------------------------------------------------------------- DNSMOS

def dnsmos(x24):
    x = dsp.resample(x24, dsp.SR, 16000).astype(np.float32)
    L = int(9.01 * 16000)
    while len(x) < L:
        x = np.concatenate([x, x])
    hops = int(np.floor(len(x) / 16000) - 9.01) + 1
    sig, bak, ovr = [], [], []
    for i in range(hops):
        seg = x[i * 16000:i * 16000 + L]
        if len(seg) < L:
            continue
        r = _session(DNSMOS).run(None, {"input_1": seg[None]})[0][0]
        sig.append(np.poly1d([-0.08397278, 1.22083953, 0.0052439])(r[0]))
        bak.append(np.poly1d([-0.13166888, 1.60915514, -0.39604546])(r[1]))
        ovr.append(np.poly1d([-0.06766283, 1.11546468, 0.04602535])(r[2]))
    return float(np.mean(sig)), float(np.mean(bak)), float(np.mean(ovr))


# ---------------------------------------------------------------- smoothness & closeness

def spectral_jumps(x):
    lm = dsp.log_mel(x, n_mels=40)
    db = dsp.rms_db(x)[:len(lm)]
    act = db > np.max(db) - 30
    d = np.sqrt(((lm[1:] - lm[:-1]) ** 2).mean(axis=1))
    d = d[act[1:] & act[:-1]]
    return float(np.percentile(d, 99)) if len(d) else 0.0


def _speech(x):
    db = dsp.rms_db(x)
    v = np.where(db > np.max(db) - 35)[0]
    if len(v) == 0:
        return x
    return x[v[0] * dsp.HOP:(v[-1] + 1) * dsp.HOP]


def closeness(x, truth):
    """(mcd dB, f0 correlation) after DTW against the ground truth."""
    sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "engine"))
    import aligner
    a, b = _speech(x), _speech(truth)
    ma = dsp.mfcc(a, n=25, n_mels=40)
    mb = dsp.mfcc(b, n=25, n_mels=40)
    fa = (ma[:, 1:] - ma[:, 1:].mean(0)) / (ma[:, 1:].std(0) + 1e-6)
    fb = (mb[:, 1:] - mb[:, 1:].mean(0)) / (mb[:, 1:].std(0) + 1e-6)
    # subsample to keep DTW quick
    path, _ = aligner.dtw(fa[::2], fb[::2], band=0.3)
    ia = np.array([2 * i for i, _ in path])
    ib = np.array([2 * j for _, j in path])
    ia, ib = np.minimum(ia, len(ma) - 1), np.minimum(ib, len(mb) - 1)
    k = 10 * np.sqrt(2) / np.log(10)
    mcd = float(np.mean(k * np.sqrt(((ma[ia, 1:] - mb[ib, 1:]) ** 2).sum(axis=1))) / 2.0)
    f0a, _ = dsp.f0_yin(a)
    f0b, _ = dsp.f0_yin(b)
    pa = f0a[np.minimum(ia, len(f0a) - 1)]
    pb = f0b[np.minimum(ib, len(f0b) - 1)]
    v = (pa > 0) & (pb > 0)
    corr = float(np.corrcoef(np.log(pa[v]), np.log(pb[v]))[0, 1]) if v.sum() > 10 else 0.0
    return mcd, corr


# ---------------------------------------------------------------- intelligibility

def wer(ref_words, hyp_words):
    r, h = ref_words, hyp_words
    d = np.zeros((len(r) + 1, len(h) + 1), dtype=int)
    d[:, 0] = np.arange(len(r) + 1)
    d[0, :] = np.arange(len(h) + 1)
    for i in range(1, len(r) + 1):
        for j in range(1, len(h) + 1):
            d[i, j] = min(d[i - 1, j] + 1, d[i, j - 1] + 1, d[i - 1, j - 1] + (r[i - 1] != h[j - 1]))
    return d[len(r), len(h)], len(r)


def _join_compounds(words):
    # recognizers split some compounds ("TO NIGHT"); compare on letters, not spacing
    return "".join(words)


def wer_words(ref, hyp):
    """WER after collapsing spacing differences like TONIGHT vs TO NIGHT."""
    e, n = wer(ref, hyp)
    if e and _join_compounds(ref) == _join_compounds(hyp):
        return 0, n
    return e, n
