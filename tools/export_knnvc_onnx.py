"""Export kNN-VC's encoder (WavLM-Large, first 6 layers only) and HiFi-GAN vocoder to ONNX for
the phone, quantize to int8, and check the exported models match PyTorch.

Usage: python3 export_knnvc_onnx.py <knn-vc repo dir> <out dir>
"""
import os
import sys

import numpy as np
import torch

repo, out = sys.argv[1:3]
sys.path.insert(0, repo)
from hubconf import knn_vc  # noqa: E402

os.makedirs(out, exist_ok=True)
m = knn_vc(pretrained=True, progress=False, prematched=True, device="cpu")
LAYER = 6


class Encoder(torch.nn.Module):
    def __init__(self, wavlm):
        super().__init__()
        self.w = wavlm
        # keep only the layers we use: the 6th layer's output carries the voice information
        self.w.encoder.layers = self.w.encoder.layers[:LAYER]

    def forward(self, wav):                       # wav: (1, T) at 16 kHz
        return self.w.extract_features(wav, output_layer=LAYER, ret_layer_results=False)[0][0]   # (N, 1024)


class Vocoder(torch.nn.Module):
    def __init__(self, g):
        super().__init__()
        self.g = g

    def forward(self, feats):                     # feats: (1, N, 1024)
        return self.g(feats).squeeze(1)           # (1, T)


enc = Encoder(m.wavlm).eval()
voc = Vocoder(m.hifigan).eval()
x = torch.randn(1, 16000 * 3) * 0.1
with torch.no_grad():
    ref_feats = enc(x)
    ref_wav = voc(ref_feats[None])
print("features", tuple(ref_feats.shape), "wav", tuple(ref_wav.shape))

torch.set_grad_enabled(False)
ref_feats = ref_feats.clone()
torch.onnx.export(enc, (x,), f"{out}/wavlm6.onnx", input_names=["wav"], output_names=["feats"],
                  dynamic_axes={"wav": {1: "T"}, "feats": {0: "N"}}, opset_version=17, dynamo=False)
torch.onnx.export(voc, (ref_feats[None],), f"{out}/hifigan.onnx", input_names=["feats"], output_names=["wav"],
                  dynamic_axes={"feats": {1: "N"}, "wav": {1: "T"}}, opset_version=17, dynamo=False)

from onnxruntime.quantization import QuantType, quantize_dynamic  # noqa: E402
quantize_dynamic(f"{out}/wavlm6.onnx", f"{out}/wavlm6.int8.onnx", weight_type=QuantType.QInt8)
quantize_dynamic(f"{out}/hifigan.onnx", f"{out}/hifigan.int8.onnx", weight_type=QuantType.QInt8)

import onnxruntime as ort  # noqa: E402
for name in ["wavlm6.onnx", "wavlm6.int8.onnx"]:
    s = ort.InferenceSession(f"{out}/{name}")
    y = s.run(None, {"wav": x.numpy()})[0]
    cos = float((y * ref_feats.numpy()).sum() / (np.linalg.norm(y) * np.linalg.norm(ref_feats.numpy())))
    print(name, "matches PyTorch, cosine", round(cos, 4), "size MB", round(os.path.getsize(f"{out}/{name}") / 1e6, 1))
for name in ["hifigan.onnx", "hifigan.int8.onnx"]:
    s = ort.InferenceSession(f"{out}/{name}")
    y = s.run(None, {"feats": ref_feats[None].numpy()})[0]
    r = ref_wav.numpy()
    n = min(y.shape[1], r.shape[1])
    corr = float(np.corrcoef(y[0, :n], r[0, :n])[0, 1])
    print(name, "matches PyTorch, correlation", round(corr, 4), "size MB", round(os.path.getsize(f"{out}/{name}") / 1e6, 1))
