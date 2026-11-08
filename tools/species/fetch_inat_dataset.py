"""Download a licensed, group-aware species image set from iNaturalist.

For every species in the BioDex catalogue (``species_catalog_v1.json``) the
script queries the official iNaturalist API v1 for research-grade
observations whose photo licence allows redistribution of derived data
(CC0, CC-BY, CC-BY-SA; CC-BY-NC and "all rights reserved" are excluded), keeps
**one photo per observation** and records the observation id, the observer id,
the licence and the attribution of every image. The observer id is what
``make_splits.py`` uses to build train/val/test partitions without
near-duplicate photos (same observer, same dive, same animal) across splits.

Optionally, ``--negatives`` downloads photos of marine taxa that are NOT in the
catalogue; they are used to calibrate the "species not identified" threshold.

Images are stored at 500 px ("medium" iNaturalist size). They stay outside the
repository; only derived embeddings may be shipped.

Usage::

    python -I tools/species/fetch_inat_dataset.py \
        --catalog C:/rerun_v9/runs/_species_2026-11-08/hf_pack/species_catalog_v1.json \
        --out C:/rerun_v9/runs/_species_2026-11-08/data/inat --per-species 60
"""

from __future__ import annotations

import argparse
import io
import json
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import requests
from PIL import Image

INAT_OBS = "https://api.inaturalist.org/v1/observations"
INAT_TAXA = "https://api.inaturalist.org/v1/taxa"
USER_AGENT = "OceanGuardAI-BioDex/1.1 (research; marine species reference set)"
LICENSES = "cc0,cc-by,cc-by-sa"
TIMEOUT = 30
API_PAUSE_S = 1.1  # iNaturalist asks for <= 60 requests per minute

# Marine taxa outside the catalogue, used as out-of-catalogue negatives. They
# are chosen from other oceans / groups so they are genuinely "not in BioDex".
DEFAULT_NEGATIVES = [
    "Zebrasoma flavescens", "Paracanthurus hepatus", "Pomacanthus imperator",
    "Naso lituratus", "Balistoides conspicillum", "Synchiropus splendidus",
    "Enteroctopus dofleini", "Pycnopodia helianthoides", "Strongylocentrotus purpuratus",
    "Hypsypops rubicundus", "Macrocystis pyrifera", "Phyllopteryx taeniolatus",
    "Carcharodon carcharias", "Megaptera novaeangliae", "Enhydra lutris",
    "Zalophus californianus", "Ostracion meleagris", "Chromodoris willani",
    "Linckia laevigata", "Heterodontus francisci", "Hermissenda crassicornis",
    "Metridium farcimen", "Anthopleura sola", "Pisaster ochraceus",
    "Lutjanus kasmira", "Holacanthus ciliaris", "Sphyrna lewini",
    "Manta birostris", "Dugong dugon", "Pomacentrus coelestis",
]


def _session() -> requests.Session:
    s = requests.Session()
    s.headers.update({"User-Agent": USER_AGENT})
    return s


def api_get(sess: requests.Session, url: str, params: dict, tries: int = 5):
    """GET with backoff on connection errors / 429 / 5xx; returns the response or None."""
    for attempt in range(tries):
        try:
            r = sess.get(url, params=params, timeout=TIMEOUT)
            time.sleep(API_PAUSE_S)
            if r.status_code == 200:
                return r
            if r.status_code not in (429, 500, 502, 503, 504):
                return None
        except requests.RequestException:
            pass
        time.sleep(5 * (attempt + 1))
    return None


def resolve_taxon(sess: requests.Session, sci: str) -> dict | None:
    """Exact iNaturalist taxon for ``sci`` (accepted name or synonym), or None.

    ``taxon_name=`` on /observations is NOT an exact filter: combined with
    ``order_by=votes`` it returns unrelated taxa (e.g. Liquidambar for
    "Mola mola"), so observations are always queried by ``taxon_id``.
    """
    r = api_get(sess, INAT_TAXA, {"q": sci, "per_page": 30})
    if r is None:
        return None
    res = [t for t in r.json().get("results", []) if t.get("rank") in ("species", "hybrid")]
    exact = [t for t in res if t.get("name", "").lower() == sci.lower()]
    syn = [t for t in res if (t.get("matched_term") or "").lower() == sci.lower()]
    return (exact or syn or [None])[0]


def query_observations(sess: requests.Session, sci: str, limit: int) -> list[dict]:
    """Return up to ``limit`` research-grade observations with a licensed photo."""
    taxon = resolve_taxon(sess, sci)
    if taxon is None:
        print(f"  taxon not found on iNaturalist: {sci}", flush=True)
        return []
    out: list[dict] = []
    page = 1
    while len(out) < limit and page <= 3:
        params = {
            "taxon_id": taxon["id"], "quality_grade": "research", "photos": "true",
            "photo_license": LICENSES, "per_page": 200, "page": page,
            "order_by": "votes", "order": "desc",
        }
        r = api_get(sess, INAT_OBS, params)
        if r is None:
            break
        results = r.json().get("results", [])
        if not results:
            break
        for obs in results:
            ref = _first_licensed_photo(obs, taxon)
            if ref:
                out.append(ref)
            if len(out) >= limit:
                break
        page += 1
    return out


def _first_licensed_photo(obs: dict, wanted: dict) -> dict | None:
    taxon = obs.get("taxon") or {}
    # taxon_id also returns descendants (subspecies); anything else is dropped.
    if taxon.get("id") != wanted["id"] and wanted["id"] not in (taxon.get("ancestor_ids") or []):
        return None
    allowed = set(LICENSES.split(","))
    for photo in obs.get("photos", []):
        lic = (photo.get("license_code") or "").lower()
        url = photo.get("url") or ""
        if lic in allowed and url:
            lat = lon = None
            if obs.get("location"):
                try:
                    lat, lon = (float(v) for v in obs["location"].split(","))
                except ValueError:
                    pass
            return {
                "observation_id": obs.get("id"),
                "user_id": (obs.get("user") or {}).get("id"),
                "photo_id": photo.get("id"),
                "url": url.replace("square", "medium"),
                "license": lic,
                "attribution": photo.get("attribution", ""),
                "taxon_name": taxon.get("name"),
                "lat": lat, "lon": lon,
            }
    return None


def download(sess: requests.Session, ref: dict, dest: Path) -> bool:
    if dest.exists():
        return True
    try:
        r = sess.get(ref["url"], timeout=TIMEOUT)
        if r.status_code != 200:
            return False
        img = Image.open(io.BytesIO(r.content)).convert("RGB")
        if min(img.size) < 96:
            return False
        img.save(dest, "JPEG", quality=92)
        return True
    except Exception:
        return False


def fetch_group(sess, items: list[tuple[str, str]], out_dir: Path, per: int,
                manifest: Path, role: str, workers: int) -> None:
    done = set()
    if manifest.exists():
        for line in manifest.read_text(encoding="utf-8").splitlines():
            done.add(json.loads(line)["species_key"])
    with manifest.open("a", encoding="utf-8") as mf, ThreadPoolExecutor(workers) as pool:
        for key, sci in items:
            if key in done:
                continue
            refs = query_observations(sess, sci, per)
            sp_dir = out_dir / key
            sp_dir.mkdir(parents=True, exist_ok=True)
            dests = [sp_dir / f"obs{r['observation_id']}_p{r['photo_id']}.jpg" for r in refs]
            oks = list(pool.map(lambda a: download(sess, *a), zip(refs, dests)))
            n_ok = 0
            for ref, dest, ok in zip(refs, dests, oks):
                if ok:
                    n_ok += 1
                    mf.write(json.dumps({"species_key": key, "scientific_name": sci, "role": role,
                                         "file": f"{key}/{dest.name}", **ref},
                                        ensure_ascii=False) + "\n")
            mf.flush()
            print(f"[{role}] {key:<32} {n_ok}/{len(refs)}", flush=True)


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--catalog", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--per-species", type=int, default=60)
    ap.add_argument("--negatives", action="store_true", help="Also fetch out-of-catalogue taxa.")
    ap.add_argument("--per-negative", type=int, default=25)
    ap.add_argument("--workers", type=int, default=6)
    ap.add_argument("--only", type=str, default="", help="Comma-separated species keys (debug).")
    args = ap.parse_args()

    cat = json.loads(args.catalog.read_text(encoding="utf-8"))
    species = [(s["speciesKey"], s["scientificName"]) for s in cat["species"]]
    if args.only:
        keep = set(args.only.split(","))
        species = [s for s in species if s[0] in keep]
    args.out.mkdir(parents=True, exist_ok=True)
    sess = _session()
    fetch_group(sess, species, args.out / "catalog", args.per_species,
                args.out / "manifest.jsonl", "catalog", args.workers)
    if args.negatives:
        cat_names = {s[1] for s in species}
        negs = [(f"neg_{n.lower().replace(' ', '_')}", n) for n in DEFAULT_NEGATIVES
                if n not in cat_names]
        fetch_group(sess, negs, args.out / "negatives", args.per_negative,
                    args.out / "manifest_negatives.jsonl", "negative", args.workers)


if __name__ == "__main__":
    main()
