"""Clean the extra iNaturalist reference photos: drop near-duplicates (64-bit dHash <= 4 bits) of any val / test /
negative photo of the first download and of any photo of the independent set, and duplicates inside the set.

Output ``ext_rows.jsonl`` (same row layout as ``splits.jsonl``: file (absolute), species_key, split=train, role=catalog,
license, observer, observation, photo) plus a report with the counts.
"""
from __future__ import annotations

import argparse
import json
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from make_splits import bucket, dhash  # noqa: E402


def hashes(paths: list[Path]) -> np.ndarray:
    with ThreadPoolExecutor(8) as pool:
        return np.array(list(pool.map(dhash, paths)), dtype=np.uint64)


def min_dist(h: np.ndarray, ref: np.ndarray, chunk: int = 2000) -> np.ndarray:
    out = np.empty(len(h), np.int16)
    for i in range(0, len(h), chunk):
        x = h[i:i + chunk, None] ^ ref[None, :]
        out[i:i + chunk] = np.unpackbits(x.view(np.uint8).reshape(-1, 8), axis=1).reshape(x.shape[0], -1, 64).sum(2).min(1)
    return out


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--manifest", type=Path, required=True)
    ap.add_argument("--inat", type=Path, required=True)
    ap.add_argument("--indep", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--dist", type=int, default=4)
    a = ap.parse_args()
    ext = [json.loads(x) for x in a.manifest.read_text(encoding="utf-8").splitlines() if x.strip() and '"done"' not in x]
    ext = [r for r in ext if Path(r["file"]).exists()]
    assert all(bucket(r["user_id"]) < 0.60 for r in ext), "a non-train observer slipped in"
    old = [json.loads(x) for x in (a.inat / "splits.jsonl").read_text(encoding="utf-8").splitlines()]
    held = [a.inat / r["file"] for r in old if r["split"] != "train"]
    indep = [a.indep / (r.get("path") or r["file"]) for r in
             (json.loads(x) for x in (a.indep / "indep.jsonl").read_text(encoding="utf-8").splitlines())]
    print(f"extra {len(ext)}; held-out iNaturalist {len(held)}; independent {len(indep)}", flush=True)
    he = hashes([Path(r["file"]) for r in ext])
    d_held = min_dist(he, hashes(held))
    d_ind = min_dist(he, hashes(indep))
    drop = (d_held <= a.dist) | (d_ind <= a.dist)
    # duplicates inside the set (keep the first)
    seen, dup_inside = {}, np.zeros(len(ext), bool)
    for i, h in enumerate(he.tolist()):
        if h in seen:
            dup_inside[i] = True
        else:
            seen[h] = i
    keep = ~(drop | dup_inside)
    rep = {"extra_downloaded": len(ext), "dropped_vs_heldout_inat": int((d_held <= a.dist).sum()),
           "dropped_vs_independent": int((d_ind <= a.dist).sum()), "dropped_exact_dup_inside": int(dup_inside.sum()),
           "kept": int(keep.sum())}
    print(rep)
    with a.out.open("w", encoding="utf-8") as f:
        for r, k in zip(ext, keep):
            if k:
                f.write(json.dumps({"file": r["file"], "species_key": r["species_key"], "split": "train", "role": "catalog",
                                    "user_id": r["user_id"], "observation_id": r["observation_id"],
                                    "photo_id": r["photo_id"], "license": r["license"],
                                    "attribution": r["attribution"]}, ensure_ascii=False) + "\n")
    a.out.with_suffix(".report.json").write_text(json.dumps(rep, indent=1), encoding="utf-8")


if __name__ == "__main__":
    main()
