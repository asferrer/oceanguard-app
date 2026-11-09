"""Text embeddings (BioCLIP / BioCLIP 2 text tower) of every catalogue species, for zero-shot priors.

Prompts per species: ``sci`` = "a photo of <Genus species>", ``tax`` = "a photo of <kingdom ... species>" (GBIF lineage,
the BioCLIP training format) and ``taxcom`` = lineage + common name. Output ``<out>.npz`` (arrays [n_species, dim],
L2-normalised, order of ``keys``).
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gpu_guard  # noqa: E402


def main() -> None:
    import open_clip
    import torch
    ap = argparse.ArgumentParser()
    ap.add_argument("--catalog", type=Path, required=True)
    ap.add_argument("--taxonomy", type=Path, required=True)
    ap.add_argument("--distractors", type=Path, help="json from fetch_distractors.py: embed these names instead")
    ap.add_argument("--encoder", required=True, choices=["bioclip", "bioclip2"])
    ap.add_argument("--out", type=Path, required=True)
    a = ap.parse_args()
    hub = {"bioclip": "hf-hub:imageomics/bioclip", "bioclip2": "hf-hub:imageomics/bioclip-2"}[a.encoder]
    cat = json.loads(a.catalog.read_text(encoding="utf-8"))["species"]
    tax = json.loads(a.taxonomy.read_text(encoding="utf-8"))
    prompts = {"sci": [], "tax": [], "taxcom": []}
    if a.distractors:
        d = json.loads(a.distractors.read_text(encoding="utf-8"))
        cat = [{"speciesKey": f"other:{n}", "scientificName": n, "commonNames": {}} for n in d]
        tax = {f"other:{n}": v for n, v in d.items()}
    for s in cat:
        t = tax[s["speciesKey"]]
        lineage = " ".join(t[k] for k in ("kingdom", "phylum", "class", "order", "family", "genus", "species") if t.get(k))
        com = (s.get("commonNames") or {}).get("en", "")
        prompts["sci"].append(f"a photo of {s['scientificName']}")
        prompts["tax"].append(f"a photo of {lineage}")
        prompts["taxcom"].append(f"a photo of {lineage} with common name {com}." if com else f"a photo of {lineage}")
    guard = gpu_guard.start(a.out.parent / "gpu_guard")
    try:
        model, _, _ = open_clip.create_model_and_transforms(hub)
        model = model.cuda().eval()
        tok = open_clip.get_tokenizer(hub)
        out = {"keys": np.array([s["speciesKey"] for s in cat])}
        with torch.no_grad():
            for name, ps in prompts.items():
                e = model.encode_text(tok(ps).cuda()).float().cpu().numpy()
                out[name] = (e / np.linalg.norm(e, axis=1, keepdims=True)).astype(np.float32)
        np.savez(a.out, **out)
        print("saved", a.out, {k: v.shape for k, v in out.items()})
    finally:
        guard.terminate()


if __name__ == "__main__":
    main()
