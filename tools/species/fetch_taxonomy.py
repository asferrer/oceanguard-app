"""Taxonomic lineage (kingdom..species) of every catalogue species from the GBIF backbone (for text prompts)."""
from __future__ import annotations

import argparse
import json
import time
from pathlib import Path

import requests

RANKS = ["kingdom", "phylum", "class", "order", "family", "genus", "species"]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--catalog", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    a = ap.parse_args()
    out = {}
    for s in json.loads(a.catalog.read_text(encoding="utf-8"))["species"]:
        r = requests.get("https://api.gbif.org/v1/species/match", params={"name": s["scientificName"]},
                         headers={"User-Agent": "oceanguard-species-eval/1.0"}, timeout=30)
        time.sleep(0.2)
        j = r.json() if r.status_code == 200 else {}
        out[s["speciesKey"]] = {k: j.get(k) for k in RANKS} | {"matchType": j.get("matchType")}
    a.out.write_text(json.dumps(out, indent=1), encoding="utf-8")
    print(len(out), sum(1 for v in out.values() if v["species"]), "with species rank")


if __name__ == "__main__":
    main()
