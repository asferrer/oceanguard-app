"""Compute image embeddings of the whole split with several candidate encoders.

All encoders see the same app-parity input: centre-square crop of the photo,
bilinear resize to the encoder input, per-encoder mean/std. Embeddings are
L2-normalised and saved as ``<out>/<encoder>.npy`` in ``splits.jsonl`` order.

Encoders (licences): ``pack_onnx`` = the published BioDex ONNX (OpenCLIP
ViT-B/32 fine-tuned, MIT); ``openclip_b32`` (MIT); ``bioclip`` (imageomics,
MIT); ``dinov2_small`` / ``dinov2_base`` (Apache-2.0); ``siglip2_base``
(Apache-2.0); ``mnv3_large`` / ``effnet_lite0`` (timm ImageNet, Apache-2.0).

Usage::

    python -I tools/species/embed_all.py --data <data/inat> --out <emb dir> \
        --encoders bioclip,dinov2_small --pack <hf_pack dir>
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gpu_guard  # noqa: E402
from biodex_common import center_square, l2n  # noqa: E402

CLIP = ((0.48145466, 0.4578275, 0.40821073), (0.26862954, 0.26130258, 0.27577711))
IMNET = ((0.485, 0.456, 0.406), (0.229, 0.224, 0.225))
HALF = ((0.5, 0.5, 0.5), (0.5, 0.5, 0.5))

# name -> (input size, (mean, std))
SPECS = {
    "pack_onnx": (224, CLIP), "openclip_b32": (224, CLIP), "bioclip": (224, CLIP), "bioclip2": (224, CLIP),
    "dinov2_small": (224, IMNET), "dinov2_base": (224, IMNET), "siglip2_base": (224, HALF),
    "mnv3_large": (224, IMNET), "effnet_lite0": (224, IMNET),
}


def to_tensor(path: Path, size: int, norm) -> np.ndarray:
    img = center_square(Image.open(path).convert("RGB")).resize((size, size), Image.BILINEAR)
    a = (np.asarray(img, dtype=np.float32) / 255.0 - np.array(norm[0], np.float32)) / np.array(norm[1], np.float32)
    return a.transpose(2, 0, 1)


def build_torch(name: str):
    """Return an eval-mode nn.Module mapping pixel_values [N,3,H,W] -> features [N,D] (CPU)."""
    import torch

    class Wrap(torch.nn.Module):
        def __init__(self, m, fn):
            super().__init__()
            self.m, self.fn = m, fn

        def forward(self, pixel_values):
            return self.fn(self.m, pixel_values)

    if name in ("openclip_b32", "bioclip", "bioclip2"):
        import open_clip
        mid, pre = {"openclip_b32": ("ViT-B-32", "laion2b_s34b_b79k"), "bioclip": ("hf-hub:imageomics/bioclip", None),
                    "bioclip2": ("hf-hub:imageomics/bioclip-2", None)}[name]
        m = open_clip.create_model_and_transforms(mid, pretrained=pre)[0].visual
        return Wrap(m, lambda m, x: m(x)).eval()
    if name.startswith("dinov2"):
        from transformers import AutoModel
        m = AutoModel.from_pretrained(f"facebook/dinov2-{name.split('_')[1]}")
        return Wrap(m, lambda m, x: m(pixel_values=x).pooler_output).eval()
    if name == "siglip2_base":
        from transformers import AutoModel
        m = AutoModel.from_pretrained("google/siglip2-base-patch16-224").vision_model
        return Wrap(m, lambda m, x: m(pixel_values=x).pooler_output).eval()
    import timm
    tm = {"mnv3_large": "mobilenetv3_large_100.ra_in1k", "effnet_lite0": "tf_efficientnet_lite0.in1k"}[name]
    m = timm.create_model(tm, pretrained=True, num_classes=0)
    return Wrap(m, lambda m, x: m(x)).eval()


def embed(name: str, files: list[Path], pack: Path | None, bs: int = 64) -> np.ndarray:
    size, norm = SPECS[name]
    feats = []
    if name == "pack_onnx":
        import onnxruntime as ort
        sess = ort.InferenceSession(str(pack / "clip_vitb32.onnx"), providers=["CPUExecutionProvider"])
        for i in range(0, len(files), bs):
            x = np.stack([to_tensor(p, size, norm) for p in files[i:i + bs]])
            feats.append(sess.run(["image_features"], {"pixel_values": x})[0])
        return l2n(np.concatenate(feats))
    import torch
    fn = build_torch(name).cuda()
    with torch.no_grad(), torch.autocast("cuda", dtype=torch.bfloat16):
        for i in range(0, len(files), bs):
            x = torch.from_numpy(np.stack([to_tensor(p, size, norm) for p in files[i:i + bs]])).cuda()
            feats.append(fn(x).float().cpu().numpy())
    return l2n(np.concatenate(feats))


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--encoders", required=True)
    ap.add_argument("--pack", type=Path)
    args = ap.parse_args()

    rows = [json.loads(x) for x in (args.data / "splits.jsonl").read_text(encoding="utf-8").splitlines()]
    files = [args.data / r["file"] for r in rows]
    args.out.mkdir(parents=True, exist_ok=True)
    guard = gpu_guard.start(args.out / "gpu_guard")
    try:
        for name in args.encoders.split(","):
            t0 = time.time()
            e = embed(name, files, args.pack)
            np.save(args.out / f"{name}.npy", e.astype(np.float32))
            print(f"{name}: {e.shape} in {time.time() - t0:.0f}s", flush=True)
    finally:
        guard.terminate()


if __name__ == "__main__":
    main()
