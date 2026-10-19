"""Quick A/B of engine settings: intelligibility and speaker similarity only (fast).

Usage: python3 ablate.py <data dir> <speaker> <lines> name=key:val,key:val ...
"""
import glob
import os
import pickle
import sys

import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "engine"))
import dsp  # noqa: E402
import metrics  # noqa: E402
import synth  # noqa: E402
import text as textmod  # noqa: E402
from bank import transcribe  # noqa: E402
from corpus import TEST  # noqa: E402
from benchmark import line_texts  # noqa: E402


def parse(spec):
    name, _, kv = spec.partition("=")
    o = synth.Opts()
    for pair in filter(None, kv.split(",")):
        k, v = pair.split(":")
        cur = getattr(o, k)
        setattr(o, k, type(cur)(v) if not isinstance(cur, bool) else v.lower() in ("1", "true", "yes"))
    return name, o


def main():
    data, spk, n = sys.argv[1], sys.argv[2], int(sys.argv[3])
    specs = [parse(s) for s in sys.argv[4:]] or [("default", synth.Opts())]
    bank = pickle.load(open(f"{data}/{spk}/bank.pkl", "rb"))
    vp = metrics.voice_print([dsp.load(f) for f in sorted(glob.glob(f"{data}/{spk}/train/*.wav"))[:20]])
    lines = [line_texts(data, spk)[int(k)] for k in os.environ['LINES'].split(',')] if os.environ.get('LINES') else line_texts(data, spk)[:n]
    for name, o in specs:
        outs = [synth.speak(bank, l, o)[0] for l in lines]
        hyps = transcribe(outs)
        err = tot = 0
        for l, (h, *_r) in zip(lines, hyps):
            e, t = metrics.wer_words(textmod.words_only(l), h.split())
            err += e
            tot += t
        sim = np.mean([metrics.spk_sim(x, vp) for x in outs])
        print(f"{name:<14} WER {err / tot:6.1%}  SpkSim {sim:.3f}", flush=True)
        if os.environ.get("SHOW"):
            for l, (h, *_r) in zip(lines, hyps):
                print("    ", l, "->", h)


if __name__ == "__main__":
    main()
