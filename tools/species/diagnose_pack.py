"""Reproduce, on the PC, what the app returns with a given species pack.

Runs the exact Android chain (centre-square crop -> 224 bilinear -> CLIP norm ->
ONNX encoder -> L2 -> max-prototype cosine -> Platt -> RAG_CORE / TENTATIVE /
unknown) over a folder of labelled images and prints:

* how often each species is the top-1 answer (a collapsed pipeline answers the
  same species for everything);
* top-1 / top-5 accuracy against the folder labels;
* cosine statistics, and optionally the agreement between the ONNX encoder and
  an open_clip reference model (to find encoder/index mismatches).

Usage::

    python -I tools/species/diagnose_pack.py --pack <dir with clip_vitb32.onnx + species_index_v1.bin>
        --images <dir>/<species_key>/*.jpg [--compare-openclip ViT-B-32:laion2b_s34b_b79k]
"""

from __future__ import annotations

import argparse
import collections
import sys
from pathlib import Path

import numpy as np
import onnxruntime as ort
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))
from biodex_common import center_square, clip_tensor, l2n, platt, read_spex, species_scores  # noqa: E402


def embed_onnx(sess: ort.InferenceSession, paths: list[Path]) -> np.ndarray:
    out = []
    for p in paths:
        x = clip_tensor(center_square(Image.open(p).convert("RGB")))
        out.append(sess.run(["image_features"], {"pixel_values": x})[0][0])
    return l2n(np.stack(out))


def embed_openclip(spec: str, paths: list[Path]) -> np.ndarray:
    import open_clip
    import torch
    name, pretrained = spec.split(":", 1)
    model, _, _ = open_clip.create_model_and_transforms(name, pretrained=pretrained or None)
    model.eval()
    feats = []
    with torch.no_grad():
        for p in paths:
            x = torch.from_numpy(clip_tensor(center_square(Image.open(p).convert("RGB"))))
            feats.append(model.encode_image(x)[0].numpy())
    return l2n(np.stack(feats))


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--pack", type=Path, required=True)
    ap.add_argument("--images", type=Path, required=True)
    ap.add_argument("--encoder", default="clip_vitb32.onnx")
    ap.add_argument("--index", default="species_index_v1.bin")
    ap.add_argument("--compare-openclip", default="")
    args = ap.parse_args()

    protos, meta = read_spex(args.pack / args.index)
    keys = np.array([m["speciesKey"] for m in meta])
    uniq = np.array(sorted(set(keys)))
    print(f"index: {protos.shape[0]} prototypes, {len(uniq)} species, dim {protos.shape[1]}")

    paths = sorted(args.images.glob("*/*.jpg"))
    labels = np.array([p.parent.name for p in paths])
    sess = ort.InferenceSession(str(args.pack / args.encoder), providers=["CPUExecutionProvider"])
    q = embed_onnx(sess, paths)

    sc = species_scores(q, protos, keys, uniq)
    order = np.argsort(-sc, axis=1)
    top1 = uniq[order[:, 0]]
    top5 = uniq[order[:, :5]]
    conf = platt(sc[np.arange(len(paths)), order[:, 0]])
    margin = sc[np.arange(len(paths)), order[:, 0]] - sc[np.arange(len(paths)), order[:, 1]]
    src = np.where((conf >= 0.6) & (margin >= 0.05), "RAG_CORE",
                   np.where(conf >= 0.3, "RAG_TENTATIVE", "UNKNOWN"))

    print(f"images: {len(paths)} from {len(set(labels))} species")
    print(f"top-1 accuracy: {np.mean(top1 == labels):.3f}")
    print(f"top-5 accuracy: {np.mean([l in t for l, t in zip(labels, top5)]):.3f}")
    print(f"top-1 cosine: mean {sc.max(1).mean():.3f}  min {sc.max(1).min():.3f}  max {sc.max(1).max():.3f}")
    print("decision:", dict(collections.Counter(src)))
    print("most frequent top-1 answers:", collections.Counter(top1).most_common(5))
    for lab in sorted(set(labels)):
        m = labels == lab
        print(f"  {lab:<26} -> {collections.Counter(top1[m]).most_common(3)}")

    if args.compare_openclip:
        ref = embed_openclip(args.compare_openclip, paths)
        cos = np.sum(ref * q, axis=1)
        print(f"ONNX vs open_clip {args.compare_openclip}: cosine mean {cos.mean():.4f} min {cos.min():.4f}")
        sc2 = species_scores(ref, protos, keys, uniq)
        print(f"top-1 with open_clip embeddings: {np.mean(uniq[sc2.argmax(1)] == labels):.3f}")


if __name__ == "__main__":
    main()
