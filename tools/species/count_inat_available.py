"""How many research-grade iNaturalist observations with a CC0 / CC-BY photo exist per catalogue species.

Uses ``per_page=0`` (only ``total_results``). Output: json {species_key: {taxon_id, cc0_ccby, cc_by_sa}}.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import fetch_inat_dataset as F  # noqa: E402


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--catalog", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    a = ap.parse_args()
    F.USER_AGENT = "oceanguard-species-eval/1.0"
    sess = F._session()
    res = json.loads(a.out.read_text()) if a.out.exists() else {}
    for s in json.loads(a.catalog.read_text(encoding="utf-8"))["species"]:
        k = s["speciesKey"]
        if k in res:
            continue
        t = F.resolve_taxon(sess, s["scientificName"])
        if t is None:
            res[k] = None
            continue
        out = {"taxon_id": t["id"]}
        for name, lic in (("cc0_ccby", "cc0,cc-by"), ("cc_by_sa", "cc-by-sa")):
            r = F.api_get(sess, F.INAT_OBS, {"taxon_id": t["id"], "quality_grade": "research", "photos": "true",
                                             "photo_license": lic, "per_page": 0})
            out[name] = r.json().get("total_results") if r is not None else None
        res[k] = out
        a.out.write_text(json.dumps(res), encoding="utf-8")
        print(k, out, flush=True)


if __name__ == "__main__":
    main()
