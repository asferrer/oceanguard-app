"""Download evaluation photos from Wikimedia Commons (second independent source).

For every catalogue species the files directly in ``Category:<scientific name>``
are listed with the MediaWiki API (``categorymembers`` + ``imageinfo``). Only
JPEG/PNG files whose licence short name is CC0, CC BY or CC BY-SA are kept
(public-domain marks, GFDL and NC are skipped), at most ``--per-author`` per
author. The photos are used only to evaluate (they are not redistributed).
Output has the same layout as ``fetch_gbif_independent.py`` (``manifest_commons.jsonl``,
``manifest_commons_negatives.jsonl``) so that ``filter_indep.py`` treats both alike.
Commons also holds drawings, plates and specimens: the filter removes the obvious
ones. Nothing from CleanSea or JAMSTEC is used.
"""

from __future__ import annotations

import argparse
import collections
import io
import json
import re
import sys
import time
from pathlib import Path

import requests
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))
from fetch_inat_dataset import DEFAULT_NEGATIVES  # noqa: E402

API = "https://commons.wikimedia.org/w/api.php"
USER_AGENT = "oceanguard-species-eval/1.0"
PAUSE_S = 1.0
MAX_SIDE = 800


def licence_code(short: str) -> str | None:
    s = re.sub(r"[\s\-]+", " ", (short or "").lower()).strip()
    if s in ("cc0", "cc0 1.0", "cc zero"):
        return "cc0"
    if re.match(r"^cc by sa( |$)", s):
        return "cc-by-sa"
    if re.match(r"^cc by( |$)", s) and "nc" not in s and "nd" not in s:
        return "cc-by"
    return None


def get(sess: requests.Session, params: dict, tries: int = 4):
    for attempt in range(tries):
        try:
            r = sess.get(API, params={"format": "json", **params}, timeout=40)
            time.sleep(PAUSE_S)
            if r.status_code == 200:
                return r.json()
        except requests.RequestException:
            pass
        time.sleep(5 * (attempt + 1))
    return None


def category_files(sess: requests.Session, sci: str, want: int, per_author: int) -> list[dict]:
    out, by_who = [], collections.Counter()
    params = {"action": "query", "generator": "categorymembers", "gcmtitle": f"Category:{sci}",
              "gcmtype": "file", "gcmlimit": 50, "prop": "imageinfo", "iiprop": "url|extmetadata|mime",
              "iiurlwidth": MAX_SIDE}
    while len(out) < want:
        r = get(sess, params)
        if not r:
            break
        for page in (r.get("query", {}).get("pages", {}) or {}).values():
            info = (page.get("imageinfo") or [{}])[0]
            meta = info.get("extmetadata", {})
            lic = licence_code((meta.get("LicenseShortName") or {}).get("value", ""))
            who = re.sub(r"<[^>]+>", "", (meta.get("Artist") or {}).get("value", "")).strip() or page["title"]
            if info.get("mime") not in ("image/jpeg", "image/png") or not lic or by_who[who] >= per_author:
                continue
            by_who[who] += 1
            out.append({"gbif_key": None, "dataset_key": "wikimedia-commons", "url": info.get("thumburl") or info["url"],
                        "license": lic, "creator": who, "references": info.get("descriptionurl", ""),
                        "commons_id": page["pageid"]})
        if "continue" not in r:
            break
        params.update(r["continue"])
    return out[:want]


def download(sess: requests.Session, url: str, dest: Path) -> bool:
    if dest.exists():
        return True
    try:
        r = sess.get(url, timeout=40)
        time.sleep(0.3)
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


def run_group(sess, items, out_dir: Path, manifest: Path, role: str, per: int, per_author: int) -> None:
    done = set()
    if manifest.exists():
        done = {json.loads(x)["species_key"] for x in manifest.read_text(encoding="utf-8").splitlines() if x}
    with manifest.open("a", encoding="utf-8") as mf:
        for key, sci in items:
            if key in done:
                continue
            refs = category_files(sess, sci, per, per_author)
            sp_dir = out_dir / key
            sp_dir.mkdir(parents=True, exist_ok=True)
            n_ok = 0
            for ref in refs:
                dest = sp_dir / f"c{ref['commons_id']}.jpg"
                if download(sess, ref["url"], dest):
                    n_ok += 1
                    mf.write(json.dumps({"species_key": key, "scientific_name": sci, "role": role,
                                         "file": f"{key}/{dest.name}", **ref}, ensure_ascii=False) + "\n")
            if n_ok == 0:
                mf.write(json.dumps({"species_key": key, "scientific_name": sci, "role": role, "file": None}) + "\n")
            mf.flush()
            print(f"[{role}] {key:<32} photos={n_ok}/{len(refs)}", flush=True)


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--catalog", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--per-species", type=int, default=25)
    ap.add_argument("--per-author", type=int, default=3)
    ap.add_argument("--negatives", action="store_true")
    ap.add_argument("--per-negative", type=int, default=12)
    args = ap.parse_args()
    sess = requests.Session()
    sess.headers.update({"User-Agent": USER_AGENT})
    cat = json.loads(args.catalog.read_text(encoding="utf-8"))["species"]
    (args.out / "commons").mkdir(parents=True, exist_ok=True)
    run_group(sess, [(c["speciesKey"], c["scientificName"]) for c in cat], args.out / "commons",
              args.out / "manifest_commons.jsonl", "catalog", args.per_species, args.per_author)
    if args.negatives:
        (args.out / "commons_negatives").mkdir(parents=True, exist_ok=True)
        run_group(sess, [("neg_" + n.lower().replace(" ", "_"), n) for n in DEFAULT_NEGATIVES],
                  args.out / "commons_negatives", args.out / "manifest_commons_negatives.jsonl", "negative",
                  args.per_negative, args.per_author)


if __name__ == "__main__":
    main()
