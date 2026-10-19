"""How accurately does the engine find word boundaries? Compares against exact timings.

Usage: python3 boundary_test.py <data dir> [speaker ...]
"""
import glob
import json
import os
import sys

import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "engine"))
import aligner  # noqa: E402
import bank  # noqa: E402
import dsp  # noqa: E402


def run(data, spk, limit=20):
    fs = sorted(glob.glob(f"{data}/{spk}/train/*.wav"))[:limit]
    auds = [dsp.load(f) for f in fs]
    asrs = bank.transcribe(auds)
    err_asr, err_dtw, wer_n, wer_d = [], [], 0, 0
    for f, a, (txt, tok, ts) in zip(fs, auds, asrs):
        truth = json.load(open(f[:-4] + ".json"))["words"]
        tw = [w for w, _, _ in truth]
        hyp = txt.split()
        wer_d += len(tw)
        wer_n += sum(1 for x, y in zip(tw, hyp) if x != y) + abs(len(tw) - len(hyp))
        if hyp != tw:
            continue
        asr_starts = [t for _, t in bank._words_from_tokens(tok, ts)]
        if len(asr_starts) != len(tw):
            continue
        spans, _ = aligner.align(a, hyp)
        for k in range(1, len(tw)):
            t = truth[k][1] / dsp.SR
            err_asr.append(abs(asr_starts[k] - t))
            err_dtw.append(abs(spans[k][0] / dsp.SR - t))
    def rep(e):
        e = np.array(e) * 1000
        return f"median {np.median(e):.0f} ms, within 25 ms {np.mean(e < 25):.0%}, within 50 ms {np.mean(e < 50):.0%}"
    print(f"{spk}: recognizer word errors {wer_n}/{wer_d}; boundaries scored {len(err_dtw)}")
    print("   recognizer timings :", rep(err_asr))
    print("   aligner            :", rep(err_dtw))
    return err_asr, err_dtw


if __name__ == "__main__":
    data = sys.argv[1]
    for spk in sys.argv[2:] or ["heart", "michael", "george"]:
        run(data, spk)
