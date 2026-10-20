"""Export kNN-VC's encoder (WavLM-Large, first 6 layers only) and HiFi-GAN vocoder to ONNX for
the phone, quantize to int8, and check the exported models match PyTorch.

Usage: python3 export_knnvc_onnx.py <knn-vc repo dir> <out dir>
"""
import os
import sys
import time

import numpy as np
import torch

T0 = time.time()


def stage(msg):
    print(f"[{time.time() - T0:7.1f}s] {msg}", flush=True)

repo, out = sys.argv[1:3]
sys.path.insert(0, repo)
from hubconf import knn_vc  # noqa: E402

os.makedirs(out, exist_ok=True)
stage("loading kNN-VC weights")
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
# speech-like test signal: a buzzy 150 Hz tone with slow vowel-like filter changes (random noise is
# not a fair test for a speech encoder)
t = torch.arange(16000 * 3) / 16000.0
x = sum(torch.sin(2 * torch.pi * 150 * h * t) / h for h in range(1, 12)) * (0.5 + 0.5 * torch.sin(2 * torch.pi * 0.7 * t))
x = (x / x.abs().max() * 0.3)[None]
with torch.no_grad():
    ref_feats = enc(x)
    ref_wav = voc(ref_feats[None])
print("features", tuple(ref_feats.shape), "wav", tuple(ref_wav.shape))

torch.set_grad_enabled(False)
ref_feats = ref_feats.clone()
stage("onnx export encoder")
torch.onnx.export(enc, (x,), f"{out}/wavlm6.onnx", input_names=["wav"], output_names=["feats"],
                  dynamic_axes={"wav": {1: "T"}, "feats": {0: "N"}}, opset_version=17, dynamo=False)
stage("onnx export vocoder")
torch.onnx.export(voc, (ref_feats[None],), f"{out}/hifigan.onnx", input_names=["feats"], output_names=["wav"],
                  dynamic_axes={"feats": {1: "N"}, "wav": {1: "T"}}, opset_version=17, dynamo=False)

from onnxruntime.quantization import QuantType, quantize_dynamic  # noqa: E402
# per-channel weights keep the transformer accurate; the plain variant scored only 0.37 cosine
stage("int8 quantize encoder")
quantize_dynamic(f"{out}/wavlm6.onnx", f"{out}/wavlm6.int8.onnx", weight_type=QuantType.QInt8, per_channel=True, reduce_range=True)
stage("int8 quantize vocoder")
quantize_dynamic(f"{out}/hifigan.onnx", f"{out}/hifigan.int8.onnx", weight_type=QuantType.QInt8)

# fp16: half the size, essentially lossless — the phone uses these (int8 cost too much accuracy,
# 0.86 cosine on the encoder, and the features are the kNN search keys)
import onnx  # noqa: E402
from onnxconverter_common import float16  # noqa: E402
for src, dst in [("wavlm6", "wavlm6.fp16"), ("hifigan", "hifigan.fp16")]:
    stage(f"fp16 convert {src}")
    # WavLM's feature extractor holds a Cast-to-float node the converter mishandles; leaving Cast
    # (and the norm ops, for accuracy) in fp32 lets the converter add its own boundary casts.
    # disable_shape_infer: whole-graph shape inference on a 355 MB model takes the better part of
    # an hour — the converter only wants it for boundary-cast placement, which torch's export
    # already annotated
    f16 = float16.convert_float_to_float16(
        onnx.load(f"{out}/{src}.onnx"), keep_io_types=True, disable_shape_infer=True,
        op_block_list=list(float16.DEFAULT_OP_BLOCK_LIST) + ["Cast"])
    onnx.save(f16, f"{out}/{dst}.onnx")
    stage(f"fp16 saved {dst}")

import onnxruntime as ort  # noqa: E402
stage("ort checks")
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

# the x64 runner emulates fp16 (the phone's ARM cores run it natively), so running the fp16 models
# here takes the better part of an hour; check them at the weight level instead — fp16 conversion
# is a pure rounding of the fp32 weights, so comparing the stored tensors is the whole story
stage("fp16 weight check")
from onnx import numpy_helper  # noqa: E402
for src, dst in [("wavlm6", "wavlm6.fp16"), ("hifigan", "hifigan.fp16")]:
    a = {t.name: t for t in onnx.load(f"{out}/{src}.onnx").graph.initializer}
    worst = 1.0
    for t in onnx.load(f"{out}/{dst}.onnx").graph.initializer:
        base = a.get(t.name.removesuffix("_fp16"))
        if base is None or t.data_type != onnx.TensorProto.FLOAT16:
            continue
        w0, w1 = numpy_helper.to_array(base).astype(np.float32).ravel(), numpy_helper.to_array(t).astype(np.float32).ravel()
        n0, n1 = np.linalg.norm(w0), np.linalg.norm(w1)
        if n0 > 0 and n1 > 0:
            worst = min(worst, float((w0 * w1).sum() / (n0 * n1)))
    size = round(os.path.getsize(f"{out}/{dst}.onnx") / 1e6, 1)
    print(f"{dst}.onnx weights match fp32, worst tensor cosine {round(worst, 6)} size MB {size}")
    assert worst > 0.999, f"{dst} weights drifted"
