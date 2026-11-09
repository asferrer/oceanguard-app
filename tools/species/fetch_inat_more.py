"""More iNaturalist reference photos per species, keeping the observer split of ``make_splits.py`` intact.

Only photos of observers that ``make_splits.bucket`` sends to the TRAIN split are downloaded, only
CC0 / CC-BY photos (publishable reference set), one photo per observation, at most ``--per-observer``
per observer and species, and never an observation already in the first download. Val / test
observers are never touched, so the val and test sets of the previous sections stay identical and
disjoint (by observer) from every new reference photo.

Usage::

    python -I tools/species/fetch_inat_more.py --catalog <species_catalog_v1.json> \
        --counts <inat_counts.json> --old <data/inat> --out <data/inat_more> --target 250
"""

from __future__ import annotations

import argparse
import collections
import json
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import fetch_inat_dataset as F  # noqa: E402
from make_splits import bucket  # noqa: E402

LICENSES = "cc0,cc-by"
TRAIN_FRAC = 0.60


def observations(sess, taxon_id: int, taxon: dict, have: set, n_old: int, target: int, per_observer: int,
                 max_pages: int) -> list[dict]:
    out, by_user = [], collections.Counter()
    last_id = None
    for _ in range(max_pages):
        params = {"taxon_id": taxon_id, "quality_grade": "research", "photos": "true", "photo_license": LICENSES,
                  "per_page": 200, "order_by": "id", "order": "desc"}
        if last_id is not None:
            params["id_below"] = last_id
        r = F.api_get(sess, F.INAT_OBS, params)
        if r is None:
            break
        res = r.json().get("results", [])
        if not res:
            break
        last_id = res[-1]["id"]
        for obs in res:
            uid = (obs.get("user") or {}).get("id")
            if uid is None or bucket(uid) >= TRAIN_FRAC or obs["id"] in have or by_user[uid] >= per_observer:
                continue
            ref = F._first_licensed_photo(obs, taxon)
            if ref:
                by_user[uid] += 1
                out.append(ref)
        if n_old + len(out) >= target or len(res) < 200:
            break
    return out[:max(0, target - n_old)]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--catalog", type=Path, required=True)
    ap.add_argument("--counts", type=Path, required=True, help="inat_counts.json from count_inat_available.py")
    ap.add_argument("--old", type=Path, required=True, help="first iNaturalist download (data/inat)")
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--target", type=int, default=250, help="train photos per species including the old ones")
    ap.add_argument("--per-observer", type=int, default=15)
    ap.add_argument("--max-pages", type=int, default=30)
    ap.add_argument("--workers", type=int, default=8)
    a = ap.parse_args()
    F.USER_AGENT = "oceanguard-species-eval/1.0"
    counts = json.loads(a.counts.read_text(encoding="utf-8"))
    old = [json.loads(x) for x in (a.old / "splits.jsonl").read_text(encoding="utf-8").splitlines()]
    have_obs = {r["observation_id"] for r in old}
    n_old = collections.Counter(r["species_key"] for r in old if r["split"] == "train" and r["role"] == "catalog"
                                and r["license"] in LICENSES.split(","))
    manifest = a.out / "manifest_more.jsonl"
    a.out.mkdir(parents=True, exist_ok=True)
    done = set()
    if manifest.exists():
        done = {json.loads(x)["species_key"] for x in manifest.read_text(encoding="utf-8").splitlines() if x}
    sess = F._session()
    with manifest.open("a", encoding="utf-8") as mf, ThreadPoolExecutor(a.workers) as pool:
        for s in json.loads(a.catalog.read_text(encoding="utf-8"))["species"]:
            k, sci = s["speciesKey"], s["scientificName"]
            c = counts.get(k)
            if k in done or not c:
                continue
            taxon = {"id": c["taxon_id"]}
            refs = observations(sess, c["taxon_id"], taxon, have_obs, n_old[k], a.target, a.per_observer, a.max_pages)
            sp = a.out / "catalog" / k
            sp.mkdir(parents=True, exist_ok=True)
            dests = [sp / f"obs{r['observation_id']}_p{r['photo_id']}.jpg" for r in refs]
            oks = list(pool.map(lambda t: F.download(sess, *t), zip(refs, dests)))
            n = 0
            for ref, dest, ok in zip(refs, dests, oks):
                if ok:
                    n += 1
                    mf.write(json.dumps({"species_key": k, "scientific_name": sci, "role": "catalog",
                                         "file": str(dest).replace("\\", "/"), **ref}, ensure_ascii=False) + "\n")
            mf.flush()
            print(f"{k:<34} old {n_old[k]:>3} + new {n:>3}/{len(refs)}", flush=True)


if __name__ == "__main__":
    main()
