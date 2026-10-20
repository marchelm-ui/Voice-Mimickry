"""Precompute the mimicry sound-pool features for the starter voices.

The phone names feature caches feat-<recording id>.f16 (float16 LE, frames x 1024) and installs
preset recordings with ids p<pack><index>, so shipping files under those exact names means
adding a starter voice never runs the encoder on the phone at all. The windowing here mirrors
FrameMimic.features: 10-second windows, half a second of context, the context's frames dropped.

Usage: python3 make_preset_feats.py <wavlm6.onnx> <presets dir>
"""
import json
import os
import sys

import numpy as np
import onnxruntime as ort

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "engine"))
import dsp  # noqa: E402

DIM = 1024
WIN = 16000 * 10
OV = 16000 // 2


def features(sess, audio24):
    x = dsp.resample(audio24, dsp.SR, 16000).astype(np.float32)
    if len(x) < 1600:
        return np.zeros((0, DIM), dtype=np.float32)
    def encode(seg):
        return sess.run(None, {"wav": seg[None]})[0]
    if len(x) <= WIN + OV:
        return encode(x)
    parts = []
    s = 0
    while s < len(x):
        frm = 0 if s == 0 else s - OV
        seg = encode(x[frm:min(len(x), s + WIN)])
        drop = 0 if s == 0 else OV // 320
        parts.append(seg[drop:])
        s += WIN
    return np.concatenate(parts)


def main():
    model, root = sys.argv[1:3]
    sess = ort.InferenceSession(model)
    index = json.load(open(f"{root}/index.json"))
    for voice in index:
        pid = voice["id"]
        meta = json.load(open(f"{root}/{pid}/voice.json"))
        for i, u in enumerate(meta["utts"]):
            audio = dsp.load(f"{root}/{pid}/{u['wav']}")
            f = features(sess, audio)
            out = f"{root}/{pid}/feat-p{pid}{i}.f16"
            f.astype("<f2").tofile(out)
            print(pid, i, f.shape[0], "frames ->", os.path.basename(out))


if __name__ == "__main__":
    main()
