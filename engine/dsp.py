"""Signal processing building blocks for the Kenku engine (reference implementation).

Everything here is plain numpy so it ports line-for-line to Kotlin on the phone.
"""
import numpy as np
from scipy.io import wavfile
from scipy.signal import resample_poly, lfilter, butter, sosfilt
from math import gcd

SR = 24000          # engine sample rate
HOP = 120           # 5 ms analysis hop at 24 kHz
F0_MIN, F0_MAX = 60.0, 420.0


# ---------------------------------------------------------------- I/O

def load(path, sr=SR):
    rate, x = wavfile.read(path)
    if x.dtype == np.int16:
        x = x.astype(np.float32) / 32768.0
    elif x.dtype == np.int32:
        x = x.astype(np.float32) / 2147483648.0
    else:
        x = x.astype(np.float32)
    if x.ndim > 1:
        x = x.mean(axis=1)
    return resample(x, rate, sr)


def save(path, x, sr=SR):
    y = np.clip(x, -1.0, 1.0)
    wavfile.write(path, sr, (y * 32767.0).astype(np.int16))


def resample(x, src, dst):
    if src == dst:
        return x.astype(np.float32)
    g = gcd(int(src), int(dst))
    return resample_poly(x, dst // g, src // g).astype(np.float32)


# ---------------------------------------------------------------- framing & features

def frames(x, win, hop=HOP):
    n = 1 + max(0, (len(x) - win)) // hop
    if len(x) < win:
        x = np.pad(x, (0, win - len(x)))
        n = 1
    idx = np.arange(win)[None, :] + hop * np.arange(n)[:, None]
    return x[idx]


def rms_db(x, win=480, hop=HOP):
    f = frames(x, win, hop)
    return 10 * np.log10(np.mean(f * f, axis=1) + 1e-10)


def mel_filterbank(sr, n_fft, n_mels, fmin=20.0, fmax=None):
    fmax = fmax or sr / 2
    def hz2mel(h): return 1127.0 * np.log(1 + h / 700.0)
    def mel2hz(m): return 700.0 * (np.exp(m / 1127.0) - 1)
    mels = np.linspace(hz2mel(fmin), hz2mel(fmax), n_mels + 2)
    hz = mel2hz(mels)
    bins = np.fft.rfftfreq(n_fft, 1 / sr)
    fb = np.zeros((n_mels, len(bins)), dtype=np.float32)
    for i in range(n_mels):
        lo, ce, hi = hz[i], hz[i + 1], hz[i + 2]
        up = (bins - lo) / (ce - lo)
        dn = (hi - bins) / (hi - ce)
        fb[i] = np.maximum(0, np.minimum(up, dn))
    return fb


_FB_CACHE = {}


def log_mel(x, sr=SR, n_fft=1024, win=960, hop=HOP, n_mels=40):
    key = (sr, n_fft, n_mels)
    if key not in _FB_CACHE:
        _FB_CACHE[key] = mel_filterbank(sr, n_fft, n_mels)
    f = frames(x, win, hop) * np.hanning(win)[None, :]
    spec = np.abs(np.fft.rfft(f, n_fft)) ** 2
    return np.log(spec @ _FB_CACHE[key].T + 1e-8)


def mfcc(x, n=13, **kw):
    lm = log_mel(x, **kw)
    k = lm.shape[1]
    dct = np.cos(np.pi / k * (np.arange(k)[None, :] + 0.5) * np.arange(n)[:, None])
    return lm @ dct.T


# ---------------------------------------------------------------- pitch (YIN)

def f0_yin(x, sr=SR, hop=HOP, thresh=0.15):
    """Per-hop F0 in Hz (0 = unvoiced) and a voicing confidence in [0,1]."""
    tmax = int(sr / F0_MIN)
    tmin = int(sr / F0_MAX)
    win = tmax * 2
    pad = np.pad(x, (win // 2, win))
    n = len(x) // hop + 1
    f0 = np.zeros(n, dtype=np.float32)
    conf = np.zeros(n, dtype=np.float32)
    # Difference function via FFT autocorrelation, all frames at once.
    idx = np.arange(win)[None, :] + hop * np.arange(n)[:, None]
    fr = pad[idx]
    nfft = 1 << int(np.ceil(np.log2(2 * win)))
    F = np.fft.rfft(fr, nfft)
    ac = np.fft.irfft(F * np.conj(F), nfft)[:, :tmax + 1]
    w = win - tmax
    sq = np.cumsum(fr * fr, axis=1)
    e0 = sq[:, w - 1]
    # energy of fr[t:t+w] for each lag t
    sq0 = np.concatenate([np.zeros((n, 1)), sq], axis=1)
    et = sq0[:, w:w + tmax + 1] - sq0[:, :tmax + 1]
    # ac computed over full window; approximate the windowed one (standard YIN shortcut)
    d = e0[:, None] + et - 2 * ac
    d[:, 0] = 0
    cm = np.cumsum(d[:, 1:], axis=1)
    lag = np.arange(1, tmax + 1)[None, :]
    dn = np.ones_like(d)
    dn[:, 1:] = d[:, 1:] * lag / np.maximum(cm, 1e-12)
    loud = rms_db(np.pad(x, (0, hop)), win=480, hop=hop)[:n] if len(x) else np.zeros(n)
    for i in range(n):
        row = dn[i]
        cand = np.where(row[tmin:tmax] < thresh)[0]
        if len(cand) == 0:
            continue
        t = cand[0] + tmin
        while t + 1 < tmax and row[t + 1] < row[t]:
            t += 1
        # parabolic interpolation
        if 1 <= t < tmax:
            a, b, c = row[t - 1], row[t], row[t + 1]
            den = a - 2 * b + c
            tt = t + 0.5 * (a - c) / den if abs(den) > 1e-12 else t
        else:
            tt = t
        if i < len(loud) and loud[i] < -50:
            continue
        f0[i] = sr / tt
        conf[i] = 1 - min(1.0, row[t])
    # remove single-frame octave blips with a short median over voiced runs
    f0 = _clean_f0(f0)
    return f0, conf


def _clean_f0(f0):
    out = f0.copy()
    v = f0 > 0
    for i in range(2, len(f0) - 2):
        if v[i]:
            nb = f0[i - 2:i + 3]
            nb = nb[nb > 0]
            if len(nb) >= 3:
                med = np.median(nb)
                if abs(np.log2(f0[i] / med)) > 0.6:
                    out[i] = med
    # drop voiced islands shorter than 3 frames (15 ms): usually noise
    i = 0
    while i < len(out):
        if out[i] > 0:
            j = i
            while j < len(out) and out[j] > 0:
                j += 1
            if j - i < 3:
                out[i:j] = 0
            i = j
        else:
            i += 1
    return out


# ---------------------------------------------------------------- pitch marks & PSOLA

def pitch_marks(x, f0, sr=SR, hop=HOP):
    """Glottal-ish epochs: one mark per period in voiced regions (at waveform peaks),
    evenly spaced 5 ms marks in unvoiced regions. Returns (marks, local_period)."""
    marks, periods = [], []
    t = 0
    n = len(x)
    while t < n:
        fi = min(len(f0) - 1, t // hop)
        if f0[fi] > 0:
            p = int(round(sr / f0[fi]))
            # snap to the strongest absolute peak within +-p/3 of the predicted mark
            lo, hi = max(0, t - p // 3), min(n, t + p // 3 + 1)
            if hi > lo:
                seg = np.abs(x[lo:hi])
                t2 = lo + int(np.argmax(seg))
                if marks and t2 <= marks[-1] + p // 2:
                    t2 = marks[-1] + p
                t = t2
            if t >= n:
                break
            marks.append(t)
            periods.append(p)
            t += p
        else:
            marks.append(t)
            periods.append(hop)
            t += hop
    return np.array(marks, dtype=np.int64), np.array(periods, dtype=np.int64)


def psola(x, f0, pitch_ratio=1.0, time_ratio=1.0, sr=SR, hop=HOP):
    """TD-PSOLA. pitch_ratio / time_ratio are scalars or per-hop arrays (len(f0)).
    pitch_ratio 1.1 = 10% higher; time_ratio 1.2 = 20% longer."""
    x = np.asarray(x, dtype=np.float64)
    nf = len(f0)
    if len(x) < 4 * hop or nf < 4:
        return x.astype(np.float32)
    marks, periods = pitch_marks(x, f0, sr, hop)
    if len(marks) < 3:
        return x.astype(np.float32)
    pr = np.full(nf, float(pitch_ratio)) if np.ndim(pitch_ratio) == 0 else np.asarray(pitch_ratio, float)
    tr = np.full(nf, float(time_ratio)) if np.ndim(time_ratio) == 0 else np.asarray(time_ratio, float)
    # input-time -> output-time map at hop resolution, and its inverse
    t_in = np.arange(nf + 1) * hop
    t_outmap = np.concatenate([[0.0], np.cumsum(tr * hop)])
    total_out = t_outmap[-1] * len(x) / max(1, nf * hop)
    out_len = int(total_out) + 4 * hop
    y = np.zeros(out_len)
    wsum = np.zeros(out_len)
    ts = float(np.interp(marks[0], t_in, t_outmap))
    voiced_of = lambda m: f0[min(nf - 1, int(m) // hop)] > 0
    while ts < total_out:
        ti = float(np.interp(ts, t_outmap, t_in))
        k = int(np.clip(np.searchsorted(marks, ti), 0, len(marks) - 1))
        if k > 0 and abs(marks[k - 1] - ti) < abs(marks[k] - ti):
            k -= 1
        m, p = int(marks[k]), int(periods[k])
        lo, hi = m - p, m + p
        seg = np.zeros(2 * p)
        a0, a1 = max(0, lo), min(len(x), hi)
        if a1 > a0:
            seg[a0 - lo:a1 - lo] = x[a0:a1]
        w = np.hanning(2 * p + 2)[1:-1]
        c = int(round(ts))
        s0, s1 = c - p, c + p
        if s0 >= 0 and s1 <= out_len:
            y[s0:s1] += seg * w
            wsum[s0:s1] += w
        fi = min(nf - 1, m // hop)
        ts += p / pr[fi] if voiced_of(m) else p
    nz = wsum > 1e-2
    y[nz] /= wsum[nz]
    return y[:int(total_out)].astype(np.float32)


# ---------------------------------------------------------------- joins, loudness, room tone

def zero_cross_near(x, i, radius=60):
    lo, hi = max(1, i - radius), min(len(x) - 1, i + radius)
    if hi <= lo:
        return i
    seg = x[lo - 1:hi]
    zc = np.where((seg[:-1] <= 0) & (seg[1:] > 0))[0]
    if len(zc) == 0:
        return i
    return int(lo + zc[np.argmin(np.abs(lo + zc - i))])


def crossfade_concat(a, b, n):
    """Equal-power crossfade of n samples (n may be 0)."""
    if n <= 0 or len(a) < n or len(b) < n:
        return np.concatenate([a, b])
    t = np.linspace(0, np.pi / 2, n)
    mid = a[-n:] * np.cos(t) + b[:n] * np.sin(t)
    return np.concatenate([a[:-n], mid, b[n:]])


def loudness(x):
    """Active-speech RMS in dB (ignores near-silent frames)."""
    db = rms_db(x)
    if len(db) == 0:
        return -60.0
    top = np.max(db)
    act = db[db > top - 25]
    return float(10 * np.log10(np.mean(10 ** (act / 10)) + 1e-12)) if len(act) else float(top)


def room_tone(x, seconds, seed=0):
    """Background noise for pauses: spectrally-shaped noise matched to the quietest frames
    of x, so silences don't sound like a dead line (a classic concatenation giveaway)."""
    n = int(seconds * SR)
    if n <= 0:
        return np.zeros(0, dtype=np.float32)
    db = rms_db(x)
    if len(db) < 4:
        return np.zeros(n, dtype=np.float32)
    q = np.argsort(db)[:max(2, len(db) // 10)]
    floor = np.concatenate([x[i * HOP:i * HOP + 480] for i in q])
    level = np.sqrt(np.mean(floor ** 2) + 1e-12)
    rng = np.random.default_rng(seed)
    noise = rng.standard_normal(n).astype(np.float32)
    # gentle low-pass so it reads as room hum/air rather than hiss
    sos = butter(2, 3000, fs=SR, output="sos")
    noise = sosfilt(sos, noise).astype(np.float32)
    noise *= level / (np.sqrt(np.mean(noise ** 2)) + 1e-12)
    return noise


def spectral_tilt(x):
    """Slope of the log spectrum (dB per octave-ish) used to match brightness across units."""
    lm = log_mel(x, n_mels=24)
    db = rms_db(x)
    m = min(len(lm), len(db))
    if m == 0:
        return 0.0
    act = db[:m] > np.max(db[:m]) - 20
    if not np.any(act):
        return 0.0
    s = lm[:m][act].mean(axis=0)
    return float(np.polyfit(np.arange(len(s)), s, 1)[0])


def tilt_filter(x, delta):
    """First-order shelving-ish pre/de-emphasis to move spectral tilt by roughly `delta`."""
    a = float(np.clip(delta * 3.0, -0.6, 0.6))
    if abs(a) < 0.02:
        return x
    if a > 0:   # brighten
        return lfilter([1, -a], [1], x).astype(np.float32) / (1 - a / 2)
    return lfilter([1], [1, a], x).astype(np.float32) * (1 + a / 2)
