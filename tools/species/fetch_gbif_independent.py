"""Download an evaluation image set that does NOT come from iNaturalist (GBIF media).

For every catalogue species the script resolves the GBIF backbone species key,
lists the datasets that hold still images of it (occurrence facet), drops the
iNaturalist research-grade dataset (50c9509d-...) and downloads photos of the
remaining datasets (Observation.org, naturgucker, national portals, ...).
Only observation records (HUMAN_OBSERVATION / OBSERVATION) are used, so museum
specimens and fossils are excluded at the source; only licences CC0, CC-BY and
CC-BY-SA are kept; at most ``--per-photographer`` photos per photographer so one
person does not dominate a species. The photos are used only to evaluate (they
are not redistributed). Negatives = marine taxa outside the catalogue
(``fetch_inat_dataset.DEFAULT_NEGATIVES``), same sources.

Images are stored with the long side at most 800 px. Nothing from CleanSea or
JAMSTEC is used. ``filter_indep.py`` then removes near-duplicates and non-photos.

Usage::

    python -I tools/species/fetch_gbif_independent.py \
        --catalog <hf_pack>/species_catalog_v1.json --out <run>/data/indep --per-species 30 --negatives
"""

from __future__ import annotations

import argparse
import collections
import io
import json
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import requests
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))
from fetch_inat_dataset import DEFAULT_NEGATIVES  # noqa: E402

GBIF = "https://api.gbif.org/v1"
INAT_DATASET = "50c9509d-22c7-4a22-a47d-8c48425ef4a7"
USER_AGENT = "oceanguard-species-eval/1.0"
BASIS = ["HUMAN_OBSERVATION", "OBSERVATION"]
PAUSE_S = 0.4
TIMEOUT = 40
MAX_SIDE = 800


def licence_code(url: str | None) -> str | None:
    u = (url or "").lower()
    if "publicdomain/zero" in u:
        return "cc0"
    if "/by-sa/" in u:
        return "cc-by-sa"
    if "/by/" in u:
        return "cc-by"
    return None  # NC, ND, all rights reserved, unknown


def get(sess: requests.Session, path: str, params: dict, tries: int = 4):
    for attempt in range(tries):
        try:
            r = sess.get(f"{GBIF}/{path}", params=params, timeout=TIMEOUT)
            time.sleep(PAUSE_S)
            if r.status_code == 200:
                return r.json()
            if r.status_code not in (429, 500, 502, 503, 504):
                return None
        except requests.RequestException:
            pass
        time.sleep(5 * (attempt + 1))
    return None


def species_key(sess: requests.Session, sci: str) -> int | None:
    m = get(sess, "species/match", {"name": sci, "rank": "SPECIES", "strict": "true"})
    if not m or m.get("matchType") == "NONE" or m.get("rank") != "SPECIES":
        return None
    return m.get("speciesKey") or m.get("usageKey")


def other_datasets(sess: requests.Session, key: int) -> list[str]:
    r = get(sess, "occurrence/search", {"taxonKey": key, "mediaType": "StillImage", "basisOfRecord": BASIS,
                                         "limit": 0, "facet": "datasetKey", "facetLimit": 40})
    if not r or not r.get("facets"):
        return []
    return [c["name"] for c in r["facets"][0]["counts"] if c["name"] != INAT_DATASET]


def candidates(sess: requests.Session, key: int, datasets: list[str], want: int, per_photographer: int) -> list[dict]:
    out, seen_url = [], set()
    by_who: collections.Counter = collections.Counter()
    for offset in range(0, 900, 300):
        r = get(sess, "occurrence/search", {"taxonKey": key, "mediaType": "StillImage", "basisOfRecord": BASIS,
                                             "datasetKey": datasets, "limit": 300, "offset": offset})
        if not r:
            break
        for occ in r.get("results", []):
            if occ.get("datasetKey") == INAT_DATASET or occ.get("speciesKey") != key:
                continue
            for med in occ.get("media", []):
                lic = licence_code(med.get("license"))
                url = med.get("identifier")
                who = med.get("creator") or med.get("rightsHolder") or occ.get("recordedBy") or occ.get("key")
                if (med.get("type") != "StillImage" or not lic or not url or url in seen_url
                        or by_who[who] >= per_photographer):
                    continue
                seen_url.add(url)
                by_who[who] += 1
                out.append({"gbif_key": occ.get("key"), "dataset_key": occ.get("datasetKey"), "url": url,
                            "license": lic, "creator": med.get("creator") or med.get("rightsHolder") or "",
                            "references": med.get("references") or occ.get("references") or ""})
                break  # one photo per occurrence
        if r.get("endOfRecords") or len(out) >= want:
            break
    return out[:want]


def download(sess: requests.Session, ref: dict, dest: Path) -> bool:
    if dest.exists():
        return True
    try:
        r = sess.get(ref["url"], timeout=TIMEOUT)
        if r.status_code != 200:
            return False
        img = Image.open(io.BytesIO(r.content)).convert("RGB")
        if min(img.size) < 150:
            return False
        img.thumbnail((MAX_SIDE, MAX_SIDE))
        img.save(dest, "JPEG", quality=90)
        return True
    except Exception:
        return False


def fetch_group(sess, items, out_dir: Path, manifest: Path, role: str, per: int, per_photographer: int,
                workers: int) -> None:
    done = set()
    if manifest.exists():
        done = {json.loads(x)["species_key"] for x in manifest.read_text(encoding="utf-8").splitlines() if x}
    with manifest.open("a", encoding="utf-8") as mf, ThreadPoolExecutor(workers) as pool:
        for key, sci in items:
            if key in done:
                continue
            gk = species_key(sess, sci)
            ds = other_datasets(sess, gk) if gk else []
            refs = candidates(sess, gk, ds, per, per_photographer) if ds else []
            sp_dir = out_dir / key
            sp_dir.mkdir(parents=True, exist_ok=True)
            dests = [sp_dir / f"g{r['gbif_key']}.jpg" for r in refs]
            oks = list(pool.map(lambda a: download(sess, *a), zip(refs, dests)))
            n_ok = 0
            for ref, dest, ok in zip(refs, dests, oks):
                if ok:
                    n_ok += 1
                    mf.write(json.dumps({"species_key": key, "scientific_name": sci, "role": role,
                                         "gbif_species_key": gk, "file": f"{key}/{dest.name}", **ref},
                                        ensure_ascii=False) + "\n")
            if n_ok == 0:  # keep a marker so the species is not retried
                mf.write(json.dumps({"species_key": key, "scientific_name": sci, "role": role,
                                     "gbif_species_key": gk, "file": None}) + "\n")
            mf.flush()
            print(f"[{role}] {key:<32} gbif={gk} datasets={len(ds)} photos={n_ok}/{len(refs)}", flush=True)


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--catalog", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--per-species", type=int, default=30, help="candidates per species (before filtering)")
    ap.add_argument("--per-photographer", type=int, default=3)
    ap.add_argument("--negatives", action="store_true")
    ap.add_argument("--per-negative", type=int, default=12)
    ap.add_argument("--workers", type=int, default=4)
    args = ap.parse_args()
    sess = requests.Session()
    sess.headers.update({"User-Agent": USER_AGENT})
    cat = json.loads(args.catalog.read_text(encoding="utf-8"))["species"]
    (args.out / "catalog").mkdir(parents=True, exist_ok=True)
    fetch_group(sess, [(c["speciesKey"], c["scientificName"]) for c in cat], args.out / "catalog",
                args.out / "manifest.jsonl", "catalog", args.per_species, args.per_photographer, args.workers)
    if args.negatives:
        (args.out / "negatives").mkdir(parents=True, exist_ok=True)
        items = [("neg_" + n.lower().replace(" ", "_"), n) for n in DEFAULT_NEGATIVES]
        fetch_group(sess, items, args.out / "negatives", args.out / "manifest_negatives.jsonl", "negative",
                    args.per_negative, args.per_photographer, args.workers)


if __name__ == "__main__":
    main()
