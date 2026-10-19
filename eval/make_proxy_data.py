"""Make stand-in "friends at the table" with Kokoro voices, with exact word timings.

For each proxy speaker we record the TRAIN lines (what the kenku hears) and the TEST
lines (ground truth: that same person actually saying the new line). Ground truth lets
the scorer compare the engine against the real thing, and the exact timings let us
measure how accurately the engine cuts words.

Usage: python3 make_proxy_data.py <out dir>
"""
import json
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "engine"))
import dsp  # noqa: E402
import kokoro_ref  # noqa: E402
import text as textmod  # noqa: E402
from corpus import TEST, TRAIN  # noqa: E402

SPEAKERS = {"heart": 3, "michael": 16, "george": 26}


def say(sentence, sid):
    """Read a sentence phrase by phrase so punctuation gives real pauses and contours."""
    toks = textmod.normalize(sentence)
    audio, words = [], []
    t = 0
    phrase = []
    for tok in toks:
        if "w" in tok:
            phrase.append(tok["w"])
            continue
        if not phrase:
            continue
        a, spans, _ = kokoro_ref.read(phrase, sid=sid, end=tok["pause"] if tok["pause"] in ".!?," else ".")
        for w, (s, e) in zip(phrase, spans):
            words.append([w, t + s, t + e])
        audio.append(a)
        t += len(a)
        phrase = []
    import numpy as np
    return np.concatenate(audio), words


def main():
    out = sys.argv[1]
    for name, sid in SPEAKERS.items():
        for split, lines in (("train", TRAIN), ("truth", TEST)):
            d = os.path.join(out, name, split)
            os.makedirs(d, exist_ok=True)
            for i, sentence in enumerate(lines):
                a, words = say(sentence, sid)
                p = os.path.join(d, f"{i:03d}")
                dsp.save(p + ".wav", a)
                with open(p + ".json", "w") as f:
                    json.dump({"text": sentence, "words": words}, f)
            print(name, split, "done", flush=True)


if __name__ == "__main__":
    main()
