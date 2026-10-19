"""Frame-level mimicry with kNN voice conversion (Baas, van Niekerk & Kamper 2023).

A natural-sounding source reading of the line is turned into WavLM features; each 20 ms frame is
replaced by the average of its k nearest frames from the target person's own recordings; a
HiFi-GAN vocoder turns the result back into sound. Prosody comes from the source reading, the
sound material from the person. Code and weights: github.com/bshall/knn-vc (MIT).
"""
import os
import sys

import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "engine"))
import dsp  # noqa: E402

KNN_DIR = os.environ.get("KENKU_KNNVC", "/home/claude/knnvc")
_model = None
_sets = {}


def model():
    global _model
    if _model is None:
        import torch
        sys.path.insert(0, KNN_DIR)
        from hubconf import knn_vc
        torch.set_num_threads(os.cpu_count() or 2)
        _model = knn_vc(pretrained=True, progress=False, prematched=True, device="cpu")
    return _model


def _tensor(x24):
    import torch
    return torch.from_numpy(dsp.resample(x24, dsp.SR, 16000).astype(np.float32))[None]


def matching_set(key, train_wavs):
    # pass waveforms as tensors (16 kHz), avoiding torchaudio's file loaders
    if key not in _sets:
        _sets[key] = model().get_matching_set([_tensor(dsp.load(p)) for p in train_wavs])
    return _sets[key]


def convert(source24, key, train_wavs, topk=4):
    import torch
    m = model()
    ms = matching_set(key, train_wavs)
    q = m.get_features(_tensor(source24))
    with torch.inference_mode():
        out = m.match(q, ms, topk=topk)
    return dsp.resample(out.numpy().astype(np.float32), 16000, dsp.SR)
