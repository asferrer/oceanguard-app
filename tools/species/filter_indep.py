"""Clean the independent (non-iNaturalist) evaluation set.

1. Near-duplicates against EVERY iNaturalist photo of the study (train, val,
   test and the ones dropped by ``make_splits.py``): 64-bit dHash within
   ``--dhash-dist`` bits (same function as ``make_splits.py``). The same photo
   cross-posted to GBIF by another portal is thus never a test query.
2. Near-duplicates inside the independent set (the first one is kept).
3. Not-a-photo filter (simple, on a 128 px thumbnail): drawings, plates, maps
   and diagrams are mostly white paper or have very few colours, so an image
   is dropped when more than ``--max-white`` of its pixels are near white, or
   when it has fewer than ``--min-colours`` distinct 4-bit colours, or when it
   is smaller than 150 px on the short side or wider than 3:1.
   Museum specimens are already excluded at the source (basisOfRecord).

Writes ``<indep>/indep.jsonl`` (kept images, same fields as the manifests) and
``<indep>/filter_report.json`` (counts per reason and the dropped files).
"""

from __future__ import annotations

import argparse
import collections
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))
from make_splits import dhash  # noqa: E402


def load(path: Path) -> list[dict]:
    if not path.exists():
        return []
    rows = [json.loads(x) for x in path.read_text(encoding="utf-8").splitlines() if x.strip()]
    return [r for r in rows if r.get("file")]


def popcount(x: np.ndarray) -> np.ndarray:
    return np.array([bin(int(v)).count("1") for v in x])


def not_a_photo(path: Path, max_white: float, min_colours: int) -> str | None:
    img = Image.open(path).convert("RGB")
    w, h = img.size
    if min(w, h) < 150:
        return "too_small"
    if max(w, h) / min(w, h) > 3:
        return "aspect"
    a = np.asarray(img.resize((128, 128), Image.BILINEAR))
    if np.mean(np.all(a > 235, axis=2)) > max_white:
        return "mostly_white"
    q = (a >> 4).astype(np.int32)
    if len(np.unique(q[..., 0] * 256 + q[..., 1] * 16 + q[..., 2])) < min_colours:
        return "few_colours"
    return None


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--indep", type=Path, required=True)
    ap.add_argument("--inat", type=Path, required=True)
    ap.add_argument("--dhash-dist", type=int, default=4)
    ap.add_argument("--max-white", type=float, default=0.45)
    ap.add_argument("--min-colours", type=int, default=60)
    args = ap.parse_args()

    ref_rows = [json.loads(x) for x in (args.inat / "splits.jsonl").read_text(encoding="utf-8").splitlines()]
    ref = np.array([dhash(args.inat / r["file"]) for r in ref_rows], dtype=np.uint64)
    print(f"iNaturalist reference hashes: {len(ref)}")
    rows = []
    for man, folder in (("manifest", "catalog"), ("manifest_negatives", "negatives"),
                        ("manifest_commons", "commons"), ("manifest_commons_negatives", "commons_negatives")):
        for r in load(args.indep / f"{man}.jsonl"):
            r["folder"] = folder
            rows.append(r)
    kept, dropped = [], collections.defaultdict(list)
    seen: list[int] = []
    for r in rows:
        p = args.indep / r["folder"] / r["file"]
        try:
            why = not_a_photo(p, args.max_white, args.min_colours)
            hsh = dhash(p)
        except Exception:
            dropped["unreadable"].append(r["file"])
            continue
        if why is None and np.min(popcount(ref ^ np.uint64(hsh))) <= args.dhash_dist:
            why = "dup_inat"
        if why is None and seen and np.min(popcount(np.array(seen, dtype=np.uint64) ^ np.uint64(hsh))) <= args.dhash_dist:
            why = "dup_indep"
        if why:
            dropped[why].append(r["file"])
            continue
        seen.append(hsh)
        r["path"] = str(p.relative_to(args.indep)).replace("\\", "/")
        kept.append(r)
    with (args.indep / "indep.jsonl").open("w", encoding="utf-8") as f:
        for r in kept:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    rep = {"input": len(rows), "kept": len(kept), "dropped": {k: len(v) for k, v in dropped.items()},
           "dropped_files": dropped,
           "kept_catalog": sum(r["role"] == "catalog" for r in kept),
           "kept_negative": sum(r["role"] == "negative" for r in kept),
           "species_with_photos": len({r["species_key"] for r in kept if r["role"] == "catalog"})}
    (args.indep / "filter_report.json").write_text(json.dumps(rep, indent=1), encoding="utf-8")
    print({k: v for k, v in rep.items() if k != "dropped_files"})


if __name__ == "__main__":
    main()
