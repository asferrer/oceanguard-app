"""Shared helpers for the BioDex species tools.

Everything here mirrors the Android code on purpose, so that a number measured
on the PC is the number the app would produce:

* ``read_spex`` / ``write_spex``  <->  ``SpeciesReferenceIndex.kt`` (SPEX v1 file)
* ``center_square`` / ``rag_crop`` <->  ``OrganismLocator.centerSquareCrop`` and
  ``RagCropPreparer.computeCropRect``
* ``clip_tensor``                 <->  ``OnnxSpeciesEmbedder.preprocess``
"""

from __future__ import annotations

import json
import struct
from pathlib import Path

import numpy as np
from PIL import Image

SPEX_MAGIC = 0x53504558
CLIP_MEAN = np.array([0.48145466, 0.4578275, 0.40821073], dtype=np.float32)
CLIP_STD = np.array([0.26862954, 0.26130258, 0.27577711], dtype=np.float32)


# ---------------------------------------------------------------------------
# SPEX index (species_index_vN.bin)
# ---------------------------------------------------------------------------

def read_spex(path: Path) -> tuple[np.ndarray, list[dict]]:
    """Return (prototypes float32 [n, dim], metadata list) from a SPEX v1 file."""
    data = Path(path).read_bytes()
    magic, version, n, dim = struct.unpack_from("<IIII", data, 0)
    if magic != SPEX_MAGIC or version != 1:
        raise ValueError(f"not a SPEX v1 file: magic={magic:#x} version={version}")
    body = np.frombuffer(data, dtype="<f2", count=n * dim, offset=32).astype(np.float32)
    meta_txt = data[32 + n * dim * 2:].decode("utf-8").strip()
    meta = [json.loads(line) for line in meta_txt.splitlines()]
    if len(meta) != n:
        raise ValueError(f"metadata lines {len(meta)} != prototypes {n}")
    return body.reshape(n, dim), meta


def write_spex(path: Path, protos: np.ndarray, meta: list[dict]) -> None:
    """Write prototypes (L2-normalised rows) + metadata as a SPEX v1 file."""
    protos = np.asarray(protos, dtype=np.float32)
    n, dim = protos.shape
    header = struct.pack("<IIII", SPEX_MAGIC, 1, n, dim) + b"\x00" * 16
    body = protos.astype("<f2").tobytes()
    trailer = "\n".join(json.dumps(m, ensure_ascii=False, separators=(",", ":")) for m in meta)
    Path(path).write_bytes(header + body + trailer.encode("utf-8") + b"\n")


# ---------------------------------------------------------------------------
# Crops and preprocessing (Android parity)
# ---------------------------------------------------------------------------

def center_square(img: Image.Image) -> Image.Image:
    """OrganismLocator.centerSquareCrop: the fallback when no box is found."""
    w, h = img.size
    s = min(w, h)
    x, y = (w - s) // 2, (h - s) // 2
    return img.crop((x, y, x + s, y + s))


def clip_tensor(img: Image.Image, size: int = 224) -> np.ndarray:
    """OnnxSpeciesEmbedder.preprocess: centre-square, bilinear resize, CLIP norm, NCHW."""
    img = img.convert("RGB")
    if img.size != (size, size):
        img = center_square(img).resize((size, size), Image.BILINEAR)
    arr = np.asarray(img, dtype=np.float32) / 255.0
    arr = (arr - CLIP_MEAN) / CLIP_STD
    return arr.transpose(2, 0, 1)[None].astype(np.float32)


def l2n(v: np.ndarray) -> np.ndarray:
    n = np.linalg.norm(v, axis=-1, keepdims=True)
    return v / np.maximum(n, 1e-8)


# ---------------------------------------------------------------------------
# Search (SpeciesReferenceIndex.search without geo filter)
# ---------------------------------------------------------------------------

def species_scores(q: np.ndarray, protos: np.ndarray, keys: np.ndarray,
                   uniq: np.ndarray) -> np.ndarray:
    """Max-prototype cosine per species. q [m, dim] -> [m, n_species]."""
    sims = q @ protos.T
    out = np.full((q.shape[0], len(uniq)), -1.0, dtype=np.float32)
    for j, k in enumerate(uniq):
        out[:, j] = sims[:, keys == k].max(axis=1)
    return out


def softmax_max(sc: np.ndarray, t: float) -> np.ndarray:
    """Max softmax probability over species scores at temperature t
    (SpeciesReferenceIndex.confidenceFunction)."""
    z = sc / t
    z = z - z.max(1, keepdims=True)
    p = np.exp(z)
    return (p / p.sum(1, keepdims=True)).max(1)


def fit_temperature(sc: np.ndarray, lab_idx: np.ndarray) -> float:
    """Temperature maximising the log-likelihood of the true species (grid search)."""
    best = (0.0, -np.inf)
    for t in np.geomspace(0.003, 0.3, 60):
        z = sc / t
        z = z - z.max(1, keepdims=True)
        ll = (z[np.arange(len(z)), lab_idx] - np.log(np.exp(z).sum(1))).mean()
        if ll > best[1]:
            best = (float(t), ll)
    return best[0]


def platt(score: np.ndarray, a: float = 10.0, b: float = -3.5) -> np.ndarray:
    """Legacy v1 confidence (placeholder Platt sigmoid of the cosine)."""
    return 1.0 / (1.0 + np.exp(-(a * score + b)))
