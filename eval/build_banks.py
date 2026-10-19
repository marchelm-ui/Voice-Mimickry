"""Build and cache the kenku's voice banks from each proxy speaker's TRAIN recordings.

Uses the recognizer's transcripts (as the phone would), not the true text.
Usage: python3 build_banks.py <data dir> [speaker ...]
"""
import glob
import os
import pickle
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "engine"))
import bank as bankmod  # noqa: E402
import dsp  # noqa: E402


def build(data, spk):
    fs = sorted(glob.glob(f"{data}/{spk}/train/*.wav"))
    t = time.time()
    b = bankmod.build_bank(spk, [dsp.load(f) for f in fs])
    with open(f"{data}/{spk}/bank.pkl", "wb") as f:
        pickle.dump(b, f)
    n = sum(len(r.words) for r in b.recs)
    print(f"{spk}: {len(fs)} recordings, {n} words, {len(b.index)} distinct, {time.time()-t:.0f}s", flush=True)


if __name__ == "__main__":
    for s in sys.argv[2:] or ["heart", "michael", "george"]:
        build(sys.argv[1], s)
