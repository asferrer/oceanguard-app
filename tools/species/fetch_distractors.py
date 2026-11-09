"""Out-of-catalogue 'distractor' species for open-set rejection: accepted congeners of the catalogue species.

For every catalogue genus, up to ``--per-genus`` other accepted species of the genus (GBIF backbone, status ACCEPTED,
rank SPECIES) that are neither catalogue species nor one of the evaluation negatives (``DEFAULT_NEGATIVES``).
Output json {name: lineage}. They become text-only rows ("other:<name>") that the app must read as "not in the catalogue".
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

import requests

sys.path.insert(0, str(Path(__file__).resolve().parent))
from fetch_inat_dataset import DEFAULT_NEGATIVES  # noqa: E402

UA = {"User-Agent": "oceanguard-species-eval/1.0"}
RANKS = ["kingdom", "phylum", "class", "order", "family", "genus"]


def get(url, params):
    for a in range(4):
        try:
            r = requests.get(url, params=params, headers=UA, timeout=30)
            time.sleep(0.25)
            if r.status_code == 200:
                return r.json()
        except requests.RequestException:
            pass
        time.sleep(2 * (a + 1))
    return {}


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--catalog", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--per-genus", type=int, default=4)
    a = ap.parse_args()
    cat = [s["scientificName"] for s in json.loads(a.catalog.read_text(encoding="utf-8"))["species"]]
    banned = {n.lower() for n in cat} | {n.lower() for n in DEFAULT_NEGATIVES}
    out, seen_genus = {}, set()
    for sci in cat:
        m = get("https://api.gbif.org/v1/species/match", {"name": sci})
        gk = m.get("genusKey")
        if not gk or gk in seen_genus:
            continue
        seen_genus.add(gk)
        res = get("https://api.gbif.org/v1/species/search", {"highertaxonKey": gk, "rank": "SPECIES", "status": "ACCEPTED",
                                                              "limit": 40}).get("results", [])
        n = 0
        for r in res:
            name = r.get("canonicalName") or ""
            if len(name.split()) != 2 or name.lower() in banned or r.get("taxonomicStatus") != "ACCEPTED":
                continue
            out[name] = {k: r.get(k) for k in RANKS} | {"species": name}
            n += 1
            if n >= a.per_genus:
                break
    a.out.write_text(json.dumps(out, indent=1), encoding="utf-8")
    print(len(seen_genus), "genera ->", len(out), "distractors")


if __name__ == "__main__":
    main()
