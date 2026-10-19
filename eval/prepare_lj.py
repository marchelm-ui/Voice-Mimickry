"""LJSpeech sample (public domain, one real reader) from Coqui's test data: 24 recordings to learn
from, the 8 with known transcripts held out. Usage: python3 prepare_lj.py <ljspeech dir> <data dir>"""
import csv
import glob
import json
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "engine"))
import dsp  # noqa: E402

src, data = sys.argv[1:3]
meta = {r[0]: r[1] for r in csv.reader(open(f"{src}/metadata.csv"), delimiter="|")}
os.makedirs(f"{data}/lj/train", exist_ok=True)
os.makedirs(f"{data}/lj/truth", exist_ok=True)
train = [f for f in sorted(glob.glob(f"{src}/wavs/*.wav")) if os.path.basename(f)[:-4] not in meta]
for i, f in enumerate(train):
    dsp.save(f"{data}/lj/train/{i:03d}.wav", dsp.load(f))
for i, k in enumerate(sorted(meta)):
    dsp.save(f"{data}/lj/truth/{i:03d}.wav", dsp.load(f"{src}/wavs/{k}.wav"))
    json.dump({"text": meta[k], "words": []}, open(f"{data}/lj/truth/{i:03d}.json", "w"))
print(len(train), "train", len(meta), "held out")
