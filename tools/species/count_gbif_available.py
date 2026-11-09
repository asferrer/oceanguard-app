"""GBIF still-image occurrences per catalogue species by licence, split iNaturalist / other publishers.

Counts only (``limit=0``). Output json {species_key: {gbif_key, inat_cc0_ccby, other_cc0_ccby, top_other_datasets}}.
"""
from __future__ import annotations

import argparse
import json
import time
from pathlib import Path

import requests

GBIF = "https://api.gbif.org/v1/occurrence/search"
INAT = "50c9509d-22c7-4a22-a47d-8c48425ef4a7"
UA = {"User-Agent": "oceanguard-species-eval/1.0"}


def count(params) -> dict:
    for a in range(4):
        try:
            r = requests.get(GBIF, params=params, headers=UA, timeout=60)
            time.sleep(0.25)
            if r.status_code == 200:
                return r.json()
        except requests.RequestException:
            pass
        time.sleep(3 * (a + 1))
    return {}


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--indep", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    a = ap.parse_args()
    keys = {}
    for f in ("manifest.jsonl",):
        for x in (a.indep / f).read_text(encoding="utf-8").splitlines():
            m = json.loads(x)
            if m.get("gbif_species_key"):
                keys[m["species_key"]] = m["gbif_species_key"]
    out = {}
    base = {"mediaType": "StillImage", "basisOfRecord": ["HUMAN_OBSERVATION", "OBSERVATION"], "limit": 0}
    for sk, gk in keys.items():
        row = {"gbif_key": gk}
        row["inat_cc0_ccby"] = count({**base, "speciesKey": gk, "datasetKey": INAT,
                                      "license": ["CC0_1_0", "CC_BY_4_0"]}).get("count")
        row["all_cc0_ccby"] = count({**base, "speciesKey": gk, "license": ["CC0_1_0", "CC_BY_4_0"]}).get("count")
        row["all_cc_by_nc"] = count({**base, "speciesKey": gk,
                                        "license": ["CC_BY_NC_4_0"]}).get("count")
        out[sk] = row
        print(sk, row, flush=True)
    a.out.write_text(json.dumps(out), encoding="utf-8")


if __name__ == "__main__":
    main()
