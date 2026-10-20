"""Build the starter-voice packs that ship inside the APK.

Each pack is a folder of short 24 kHz mono wav utterances plus voice.json naming the voice and
giving the exact transcript of every utterance, so the phone can learn the voice without
guessing at words. Sources: real public-domain LibriVox readers (via LibriSpeech dev-clean) and
realistic synthetic characters (Kokoro-82M).

Usage: python3 make_presets.py <librispeech dir> <proxy data dir> <out dir>
"""
import glob
import json
import os
import subprocess
import sys
import wave

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "engine"))

SR = 24000
MAX_SECONDS = 120          # per voice: plenty of sounds for mimicry, light in the APK and in RAM

# (pack id, shown name, blurb, source kind, source key)
VOICES = [
    ("lethe", "Lethe", "Real reader · deep, steady", "librispeech", "422"),
    ("tonia", "Tonia", "Real reader · warm, clear", "librispeech", "1673"),
    ("michael", "Michael", "Deep storyteller", "proxy", "michael"),
    ("george", "George", "Crisp British", "proxy", "george"),
    ("harper", "Harper", "Bright and friendly", "proxy", "heart"),
]


def wav_seconds(path):
    with wave.open(path) as w:
        return w.getnframes() / w.getframerate()


def from_librispeech(src, spk, out):
    trans = {}
    for line in open(f"{src}/{spk}/trans.txt"):
        k, _, t = line.strip().partition(" ")
        trans[k] = t
    utts, total = [], 0.0
    for k in sorted(trans):
        f = f"{src}/{spk}/{k}.flac"
        if not os.path.exists(f) or total >= MAX_SECONDS:
            continue
        name = f"{len(utts):03d}.wav"
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", f, "-ac", "1", "-ar", str(SR), f"{out}/{name}"], check=True)
        sec = wav_seconds(f"{out}/{name}")
        if sec > 25:                       # very long readings drag the on-phone learn step
            os.remove(f"{out}/{name}")
            continue
        utts.append({"wav": name, "text": trans[k].lower()})
        total += sec
    return utts, total


def from_proxy(data, key, out):
    utts, total = [], 0.0
    for f in sorted(glob.glob(f"{data}/{key}/train/*.wav")):
        if total >= MAX_SECONDS:
            break
        meta = json.load(open(f[:-4] + ".json"))
        name = f"{len(utts):03d}.wav"
        subprocess.run(["cp", f, f"{out}/{name}"], check=True)
        utts.append({"wav": name, "text": meta["text"].lower()})
        total += wav_seconds(f)
    return utts, total


def main():
    src, data, out = sys.argv[1:4]
    index = []
    for pid, name, blurb, kind, key in VOICES:
        d = f"{out}/{pid}"
        os.makedirs(d, exist_ok=True)
        utts, total = (from_librispeech(src, key, d) if kind == "librispeech" else from_proxy(data, key, d))
        json.dump({"name": name, "blurb": blurb, "utts": utts}, open(f"{d}/voice.json", "w"))
        index.append({"id": pid, "name": name, "blurb": blurb, "seconds": round(total)})
        print(pid, len(utts), "utts", round(total), "s")
    json.dump(index, open(f"{out}/index.json", "w"))


if __name__ == "__main__":
    main()
