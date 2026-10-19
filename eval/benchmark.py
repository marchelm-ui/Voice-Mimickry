"""Head-to-head benchmark: the Kenku engine vs a neural voice clone vs the real person.

For every proxy speaker and every TEST line, each system speaks the line; we score it and
save the audio for listening.

Systems
  truth        the speaker really saying the line (ceiling)
  kenku        our engine, default settings
  kenku_naive  plain cut-and-paste of the same words (no prosody, smoothing, loudness, room tone)
  pocket       Pocket TTS neural clone from 12 s of the same speaker (what most phone apps do)
  other_voice  a different stock voice saying the line (floor for speaker similarity)

Usage: python3 benchmark.py <data dir> <out dir> [speaker ...] [--systems a,b] [--lines N]
"""
import argparse
import glob
import json
import os
import pickle
import subprocess
import sys
import tempfile
import time

import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "engine"))
import dsp  # noqa: E402
import kokoro_ref  # noqa: E402
import metrics  # noqa: E402
import synth  # noqa: E402
import text as textmod  # noqa: E402
from bank import transcribe  # noqa: E402
from corpus import TEST  # noqa: E402

OTHER = {"heart": 9, "michael": 13, "george": 11}


def pocket(bank, line):
    ref = bank.reference_clip()
    P = synth.POCKET
    with tempfile.TemporaryDirectory() as td:
        rp, op = os.path.join(td, "r.wav"), os.path.join(td, "o.wav")
        dsp.save(rp, ref)
        subprocess.run([synth.TTS_BIN, f"--pocket-lm-flow={P}/lm_flow.int8.onnx", f"--pocket-lm-main={P}/lm_main.int8.onnx",
                        f"--pocket-encoder={P}/encoder.onnx", f"--pocket-decoder={P}/decoder.int8.onnx",
                        f"--pocket-text-conditioner={P}/text_conditioner.onnx", f"--pocket-vocab-json={P}/vocab.json",
                        f"--pocket-token-scores-json={P}/token_scores.json", f"--reference-audio={rp}",
                        "--num-threads=2", f"--output-filename={op}", line], check=True, capture_output=True)
        return dsp.load(op)


def other_voice(spk, line):
    sys.path.insert(0, os.path.dirname(__file__))
    from make_proxy_data import say
    return say(line, OTHER[spk])[0]


def run_system(name, spk, bank, line, i, data):
    if name == "truth":
        return dsp.load(f"{data}/{spk}/truth/{i:03d}.wav"), {}
    if name == "kenku":
        return synth.speak(bank, line)
    if name == "kenku_naive":
        o = synth.Opts(prosody=0, timing=0, smooth_joins=False, room_tone=False, match_loudness=False)
        return synth.speak(bank, line, o)
    if name == "pocket":
        return pocket(bank, line), {}
    if name == "other_voice":
        return other_voice(spk, line), {}
    raise ValueError(name)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("data")
    ap.add_argument("out")
    ap.add_argument("speakers", nargs="*", default=["heart", "michael", "george"])
    ap.add_argument("--systems", default="truth,kenku,kenku_naive,pocket,other_voice")
    ap.add_argument("--lines", type=int, default=len(TEST))
    ap.add_argument("--tag", default="")
    a = ap.parse_args()
    systems = a.systems.split(",")
    os.makedirs(a.out, exist_ok=True)
    rows = []
    for spk in a.speakers:
        bank = pickle.load(open(f"{a.data}/{spk}/bank.pkl", "rb"))
        trains = [dsp.load(f) for f in sorted(glob.glob(f"{a.data}/{spk}/train/*.wav"))]
        vp = metrics.voice_print(trains)
        outs = {}
        for i, line in enumerate(TEST[:a.lines]):
            truth = dsp.load(f"{a.data}/{spk}/truth/{i:03d}.wav")
            for s in systems:
                t = time.time()
                x, rep = run_system(s, spk, bank, line, i, a.data)
                dt = time.time() - t
                p = f"{a.out}/{spk}_{i:03d}_{s}{a.tag}.wav"
                dsp.save(p, x)
                outs[(i, s)] = (x, rep, dt, p)
        # batch recognition for WER
        keys = list(outs)
        hyps = transcribe([outs[k][0] for k in keys])
        for k, (txt, _, _, _) in zip(keys, hyps):
            i, s = k
            x, rep, dt, p = outs[k]
            ref_words = textmod.words_only(TEST[i])
            e, n = metrics.wer_words(ref_words, txt.split())
            sig, bak, ovr = metrics.dnsmos(x)
            truth = dsp.load(f"{a.data}/{spk}/truth/{i:03d}.wav")
            mcd, f0c = metrics.closeness(x, truth) if s != "truth" else (0.0, 1.0)
            row = dict(speaker=spk, line=i, system=s + a.tag, wer_err=e, wer_n=n, heard=txt,
                       spk_sim=metrics.spk_sim(x, vp), dnsmos_sig=sig, dnsmos_ovr=ovr,
                       seam=metrics.spectral_jumps(x), mcd=mcd, f0_corr=f0c,
                       secs=dt, dur=len(x) / dsp.SR, wav=os.path.basename(p), **{k2: v for k2, v in rep.items()})
            rows.append(row)
        print(f"{spk} done", flush=True)
    with open(f"{a.out}/results{a.tag}.json", "w") as f:
        json.dump(rows, f, indent=1)
    summarize(rows)


def summarize(rows):
    systems = []
    for r in rows:
        if r["system"] not in systems:
            systems.append(r["system"])
    truth_seam = {}
    for r in rows:
        if r["system"] == "truth":
            truth_seam[(r["speaker"], r["line"])] = r["seam"]
    print(f"{'system':<16}{'WER':>7}{'SpkSim':>8}{'SIG':>6}{'OVRL':>6}{'Seam':>7}{'MCD':>7}{'F0corr':>8}{'sec':>6}")
    for s in systems:
        rs = [r for r in rows if r["system"] == s]
        w = sum(r["wer_err"] for r in rs) / max(1, sum(r["wer_n"] for r in rs))
        seam = np.mean([r["seam"] / max(1e-6, truth_seam.get((r["speaker"], r["line"]), r["seam"])) for r in rs])
        print(f"{s:<16}{w:>7.1%}{np.mean([r['spk_sim'] for r in rs]):>8.3f}{np.mean([r['dnsmos_sig'] for r in rs]):>6.2f}"
              f"{np.mean([r['dnsmos_ovr'] for r in rs]):>6.2f}{seam:>7.2f}{np.mean([r['mcd'] for r in rs]):>7.2f}"
              f"{np.mean([r['f0_corr'] for r in rs]):>8.2f}{np.mean([r['secs'] for r in rs]):>6.1f}")


if __name__ == "__main__":
    main()
