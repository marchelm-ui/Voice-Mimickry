"""Turn LibriSpeech speakers (from the external-audio branch) into benchmark voices.

Each speaker: up to 60 utterances to learn from, 8 held out (the real person reading text
the engine never heard them say). Usage: python3 prepare_librispeech.py <librispeech dir> <data dir>
"""
import glob
import json
import os
import random
import subprocess
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "engine"))
import dsp  # noqa: E402


def main():
    src, data = sys.argv[1:3]
    for spk_dir in sorted(glob.glob(f"{src}/*/")):
        spk = os.path.basename(spk_dir.rstrip("/"))
        trans = {}
        for line in open(f"{spk_dir}/trans.txt"):
            k, _, t = line.strip().partition(" ")
            trans[k] = t
        ids = sorted(k for k in trans if os.path.exists(f"{spk_dir}/{k}.flac"))
        rnd = random.Random(1)
        # hold out medium-length sentences: easier to compare, like table talk
        mid = [k for k in ids if 4 <= len(trans[k].split()) <= 16]
        held = rnd.sample(mid, min(8, len(mid)))
        train = [k for k in ids if k not in held][:60]
        name = f"ls{spk}"
        for split, keys in (("train", train), ("truth", held)):
            os.makedirs(f"{data}/{name}/{split}", exist_ok=True)
            for i, k in enumerate(keys):
                out = f"{data}/{name}/{split}/{i:03d}.wav"
                subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", f"{spk_dir}/{k}.flac", "-ac", "1", "-ar", str(dsp.SR), out], check=True)
                if split == "truth":
                    json.dump({"text": trans[k].capitalize() + ".", "words": []}, open(out[:-4] + ".json", "w"))
        print(name, len(train), "train", len(held), "held out")


if __name__ == "__main__":
    main()
