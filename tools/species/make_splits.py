"""Group-aware train / val / test split of the BioDex image set.

The split unit is the **iNaturalist observer** (``user_id``), not the image:
one observer's photos of the same species often come from the same dive, the
same animal or the same camera, i.e. near-duplicates. An observer is assigned
to one split through a salted hash of its id, and that assignment is global:
the same observer never appears in two splits, for any species.

Then a perceptual-hash pass removes any test/val image whose 64-bit dHash is
within ``--dhash-dist`` bits of a train image (re-uploads across accounts).

Output: ``splits.jsonl`` with one line per image ``{file, species_key, split, role}``
(role = catalog | negative). Negatives (out-of-catalogue taxa) are only used in
val/test, to calibrate and to measure the "not identified" answer.

Usage::

    python -I tools/species/make_splits.py --data C:/rerun_v9/runs/_species_2026-11-08/data/inat
"""

from __future__ import annotations

import argparse
import collections
import hashlib
import json
from pathlib import Path

import numpy as np
from PIL import Image

SALT = "biodex-2026-11-08"


def bucket(user_id) -> float:
    h = hashlib.sha256(f"{SALT}:{user_id}".encode()).hexdigest()
    return int(h[:8], 16) / 0xFFFFFFFF


def dhash(path: Path) -> int:
    img = Image.open(path).convert("L").resize((9, 8), Image.BILINEAR)
    a = np.asarray(img, dtype=np.int16)
    bits = (a[:, 1:] > a[:, :-1]).flatten()
    return int("".join("1" if b else "0" for b in bits), 2)


def load(manifest: Path) -> list[dict]:
    if not manifest.exists():
        return []
    return [json.loads(x) for x in manifest.read_text(encoding="utf-8").splitlines() if x.strip()]


def assign(rows: list[dict], train: float, val: float, negatives: bool) -> None:
    for r in rows:
        b = bucket(r["user_id"])
        if negatives:  # negatives never enter the gallery / training set
            r["split"] = "val" if b < 0.5 else "test"
        else:
            r["split"] = "train" if b < train else ("val" if b < train + val else "test")


def drop_near_duplicates(rows: list[dict], root: Path, max_dist: int) -> int:
    hashes = {r["file"]: dhash(root / r["file"]) for r in rows}
    train_h = np.array([hashes[r["file"]] for r in rows if r["split"] == "train"], dtype=np.uint64)
    dropped = 0
    for r in rows:
        if r["split"] == "train":
            continue
        x = np.uint64(hashes[r["file"]]) ^ train_h
        dist = np.unpackbits(x.view(np.uint8)).reshape(-1, 64).sum(1) if len(x) else np.array([64])
        if dist.min() <= max_dist:
            r["split"] = "dropped_dup"
            dropped += 1
    return dropped


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", type=Path, required=True)
    ap.add_argument("--train", type=float, default=0.60)
    ap.add_argument("--val", type=float, default=0.15)
    ap.add_argument("--dhash-dist", type=int, default=4)
    args = ap.parse_args()

    cat = load(args.data / "manifest.jsonl")
    neg = load(args.data / "manifest_negatives.jsonl")
    for r in cat:
        r["file"] = "catalog/" + r["file"]
    for r in neg:
        r["file"] = "negatives/" + r["file"]
    assign(cat, args.train, args.val, negatives=False)
    assign(neg, args.train, args.val, negatives=True)
    dropped = drop_near_duplicates(cat + neg, args.data, args.dhash_dist)

    out = args.data / "splits.jsonl"
    with out.open("w", encoding="utf-8") as f:
        for r in cat + neg:
            f.write(json.dumps({k: r[k] for k in ("file", "species_key", "split", "role", "user_id",
                                                  "observation_id", "photo_id", "license", "attribution")},
                               ensure_ascii=False) + "\n")
    c = collections.Counter((r["role"], r["split"]) for r in cat + neg)
    users = collections.defaultdict(set)
    for r in cat:
        users[r["split"]].add(r["user_id"])
    overlap = (users["train"] & users["test"]) | (users["train"] & users["val"]) | (users["val"] & users["test"])
    print(dict(c))
    print(f"near-duplicates removed from val/test: {dropped}")
    print(f"observers shared between splits: {len(overlap)}")
    per_sp = collections.Counter(r["species_key"] for r in cat if r["split"] == "test")
    print(f"species with >=1 test image: {len(per_sp)} / {len({r['species_key'] for r in cat})}")
    print(f"written {out}")


if __name__ == "__main__":
    main()
