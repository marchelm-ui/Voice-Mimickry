"""Build the blind A/B listening test (clips + trials.json) from benchmark output.

Usage: python3 make_ear_test.py <bench wav dir> <data dir> <out dir> [lines per voice]
"""
import glob
import json
import os
import random
import subprocess
import sys

sys.path.insert(0, os.path.dirname(__file__))
from corpus import TEST  # noqa: E402

VOICES = {"heart": "Voice 1", "michael": "Voice 2", "george": "Voice 3"}
PAIRS = [("kenku", "pocket"), ("kenku", "pocket"), ("kenku", "truth"), ("pocket", "truth")]


def mp3(src, dst):
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", src, "-ac", "1", "-b:a", "64k", dst], check=True)


def main():
    bench, data, out = sys.argv[1:4]
    os.makedirs(f"{out}/clips", exist_ok=True)
    rnd = random.Random(7)
    trials = []
    for spk, label in VOICES.items():
        have = sorted({int(os.path.basename(p).split("_")[1]) for p in glob.glob(f"{bench}/{spk}_*_kenku.wav")})
        if not have:
            continue
        ref = sorted(glob.glob(f"{data}/{spk}/train/*.wav"))[1]
        mp3(ref, f"{out}/clips/{spk}_ref.mp3")
        lines = rnd.sample(have, min(len(PAIRS), len(have)))
        for (s1, s2), i in zip(PAIRS, lines):
            if rnd.random() < 0.5:
                s1, s2 = s2, s1
            for s in (s1, s2):
                mp3(f"{bench}/{spk}_{i:03d}_{s}.wav", f"{out}/clips/{spk}_{i:03d}_{s}.mp3")
            trials.append({"id": f"{spk}-{i}-{s1}-{s2}", "voice": label, "line": TEST[i],
                           "ref": f"clips/{spk}_ref.mp3", "a": f"clips/{spk}_{i:03d}_{s1}.mp3",
                           "b": f"clips/{spk}_{i:03d}_{s2}.mp3", "sa": s1, "sb": s2})
    rnd.shuffle(trials)
    json.dump(trials, open(f"{out}/trials.json", "w"), indent=1)
    print(len(trials), "trials")


if __name__ == "__main__":
    main()
