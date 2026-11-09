"""Embed any labelled image list with one encoder on the GPU (app preprocessing).

``--rows`` is a jsonl whose lines carry ``path`` or ``file`` relative to ``--base``
(absolute paths also work). Output ``<out>.npy`` = L2-normalised float32 [n, dim] in
row order (optionally ``--flip`` embeds the horizontally flipped image, for TTA).
fp32 on the GPU (no autocast) so the numbers match the fp32 ONNX of the pack.
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gpu_guard  # noqa: E402
from biodex_common import center_square, l2n  # noqa: E402
from embed_all import SPECS, build_torch  # noqa: E402


def load(path: Path, size: int, norm, flip: bool, crop: str) -> np.ndarray:
    img = Image.open(path).convert("RGB")
    if flip:
        img = img.transpose(Image.FLIP_LEFT_RIGHT)
    w, h = img.size
    m = min(w, h)
    if crop == "full":  # whole photo squashed to a square (no crop)
        img = img.resize((size, size), Image.BILINEAR)
    elif crop == "start":  # square at the left / top edge
        img = img.crop((0, 0, m, m)).resize((size, size), Image.BILINEAR)
    elif crop == "end":  # square at the right / bottom edge
        img = img.crop((w - m, h - m, w, h)).resize((size, size), Image.BILINEAR)
    elif crop == "c80":  # central 80 % of the centre square
        k = int(m * 0.8)
        x, y = (w - k) // 2, (h - k) // 2
        img = img.crop((x, y, x + k, y + k)).resize((size, size), Image.BILINEAR)
    else:
        img = center_square(img).resize((size, size), Image.BILINEAR)
    a = (np.asarray(img, dtype=np.float32) / 255.0 - np.array(norm[0], np.float32)) / np.array(norm[1], np.float32)
    return a.transpose(2, 0, 1)


def embed_files(name: str, files: list[Path], flip=False, crop="center", bs=128, half=False) -> np.ndarray:
    import torch
    size, norm = SPECS[name]
    model = build_torch(name).cuda().eval()
    if half:
        model = model.half()
    out = []
    with torch.no_grad(), ThreadPoolExecutor(8) as pool:
        for i in range(0, len(files), bs):
            x = np.stack(list(pool.map(lambda p: load(p, size, norm, flip, crop), files[i:i + bs])))
            t = torch.from_numpy(x).cuda()
            out.append(model(t.half() if half else t).float().cpu().numpy())
    return l2n(np.concatenate(out)).astype(np.float32)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--rows", type=Path, required=True)
    ap.add_argument("--base", type=Path, required=True)
    ap.add_argument("--encoder", required=True)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--flip", action="store_true")
    ap.add_argument("--crop", default="center", choices=["center", "full", "start", "end", "c80"])
    ap.add_argument("--half", action="store_true")
    a = ap.parse_args()
    rows = [json.loads(x) for x in a.rows.read_text(encoding="utf-8").splitlines() if x.strip()]
    files = [a.base / (r.get("path") or r["file"]) for r in rows]
    a.out.parent.mkdir(parents=True, exist_ok=True)
    guard = gpu_guard.start(a.out.parent / "gpu_guard")
    try:
        t0 = time.time()
        e = embed_files(a.encoder, files, a.flip, a.crop, half=a.half)
        np.save(a.out, e)
        print(f"{a.encoder}: {e.shape} in {time.time() - t0:.0f}s -> {a.out}", flush=True)
    finally:
        guard.terminate()


if __name__ == "__main__":
    main()
