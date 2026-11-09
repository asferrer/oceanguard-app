"""Check a built species pack end to end, as the app loads and decides.

1. Manifest and files: ``species_pack.json`` fields (encoder contract, calibration, optional ``scoring``, ``cropMode``,
   ``sessionOptimization``, ``merged``), every listed file exists, SHA-256 of all files.
2. Catalogue vs index: the species keys of the SPEX index (image prototypes and text rows) and of the catalogue file
   must be the same set (a key in only one of them is reported and fails the check).
3. ONNX Runtime: session with the manifest's optimisation level, self-test pattern against ``selfTest.expected``, then the
   app chain on the labelled photos of ``--data`` / ``--split`` (delegated to ``eval_pack_ort.py``: crop mode, per species
   max cosine + text weight, softmax at the pack temperature, thresholds, top-1 / top-5, coverage and precision per
   decision, decisions on the negatives, latency).

Run it with the same ONNX Runtime as the app (``onnxruntime==1.20.1``)::

    python -I tools/species/verify_pack.py --pack <pack dir> --data <data/inat> [--split test] [--limit N]
"""

from __future__ import annotations

import argparse
import hashlib
import json
import struct
import sys
from pathlib import Path


def sha256(p: Path) -> str:
    h = hashlib.sha256()
    with p.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def index_keys(path: Path) -> tuple[set[str], int, int]:
    d = path.read_bytes()
    magic, ver, n, dim = struct.unpack_from("<IIII", d, 0)
    assert magic == 0x53504558 and ver == 1, "not a SPEX v1 index"
    meta = [json.loads(x) for x in d[32 + n * dim * 2:].decode("utf-8").strip().splitlines()]
    return {m["speciesKey"] for m in meta}, n, dim


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--pack", type=Path, required=True)
    ap.add_argument("--data", type=Path, required=True)
    ap.add_argument("--split", default="test")
    ap.add_argument("--limit", type=int)
    ap.add_argument("--threads", type=int, default=8)
    a = ap.parse_args()
    m = json.loads((a.pack / "species_pack.json").read_text(encoding="utf-8"))
    ok = True
    for key in ("encoderFile", "indexFile", "catalogFile"):
        p = a.pack / m[key]
        print(f"{key:<12} {m[key]:<32} {'ok' if p.exists() else 'MISSING'}")
        ok &= p.exists()
    print("optional fields:", {k: m.get(k) for k in ("cropMode", "sessionOptimization", "scoring", "merged")})
    ik, n, dim = index_keys(a.pack / m["indexFile"])
    ck = {s["speciesKey"] for s in json.loads((a.pack / m["catalogFile"]).read_text(encoding="utf-8"))["species"]}
    print(f"index: {n} rows, dim {dim}, {len(ik)} species; catalogue: {len(ck)} species; "
          f"only in index {sorted(ik - ck)}; only in catalogue {sorted(ck - ik)}")
    ok &= ik == ck and dim == m["encoder"]["dim"]
    for p in sorted(a.pack.iterdir()):
        print(f"  {sha256(p)}  {p.name}  ({p.stat().st_size / 1e6:.2f} MB)")
    if not ok:
        sys.exit("pack inconsistent")
    import eval_pack_ort
    sys.argv = ["eval_pack_ort.py", "--pack", str(a.pack), "--rows", str(a.data / "splits.jsonl"), "--base", str(a.data),
                "--split", a.split, "--threads", str(a.threads)] + (["--limit", str(a.limit)] if a.limit else [])
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    eval_pack_ort.main()


if __name__ == "__main__":
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    main()
