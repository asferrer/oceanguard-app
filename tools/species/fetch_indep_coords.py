"""Coordinates of the independent evaluation photos (for the geographic-prior experiment).

GBIF photos: ``GET /v1/occurrence/<gbif_key>`` -> decimalLatitude / decimalLongitude.
Commons photos: MediaWiki ``prop=coordinates`` (GeoData) by page id; most files have none.
Writes ``<indep>/coords.json`` = {"<path>": [lat, lon]}. Generic user agent, pauses, few threads.
"""
from __future__ import annotations

import argparse
import json
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import requests

UA = {"User-Agent": "oceanguard-species-eval/1.0"}
GBIF = "https://api.gbif.org/v1/occurrence/"
COMMONS = "https://commons.wikimedia.org/w/api.php"


def gbif_one(key: int):
    for attempt in range(4):
        try:
            r = requests.get(f"{GBIF}{key}", headers=UA, timeout=30)
            time.sleep(0.25)
            if r.status_code == 200:
                j = r.json()
                if j.get("decimalLatitude") is not None and j.get("decimalLongitude") is not None:
                    return key, [j["decimalLatitude"], j["decimalLongitude"]]
                return key, None
            if r.status_code not in (429, 500, 502, 503, 504):
                return key, None
        except requests.RequestException:
            pass
        time.sleep(3 * (attempt + 1))
    return key, None


def commons_batch(ids: list[int]) -> dict[int, list[float]]:
    out: dict[int, list[float]] = {}
    for i in range(0, len(ids), 50):
        chunk = ids[i:i + 50]
        for attempt in range(4):
            try:
                r = requests.get(COMMONS, headers=UA, timeout=30, params={
                    "action": "query", "format": "json", "prop": "coordinates", "colimit": "max",
                    "pageids": "|".join(map(str, chunk))})
                time.sleep(1.0)
                if r.status_code == 200:
                    for pid, pg in r.json().get("query", {}).get("pages", {}).items():
                        c = pg.get("coordinates")
                        if c:
                            out[int(pid)] = [c[0]["lat"], c[0]["lon"]]
                    break
            except requests.RequestException:
                pass
            time.sleep(3 * (attempt + 1))
    return out


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--indep", type=Path, required=True)
    a = ap.parse_args()
    rows = [json.loads(x) for x in (a.indep / "indep.jsonl").read_text(encoding="utf-8").splitlines() if x]
    gb = {r["gbif_key"]: r["path"] for r in rows if r.get("gbif_key") and not r.get("commons_id")}
    cm = {r["commons_id"]: r["path"] for r in rows if r.get("commons_id")}
    coords: dict[str, list[float]] = {}
    with ThreadPoolExecutor(4) as pool:
        for k, ll in pool.map(gbif_one, list(gb)):
            if ll:
                coords[gb[k]] = ll
    print(f"GBIF: {len(coords)}/{len(gb)} with coordinates", flush=True)
    n0 = len(coords)
    for pid, ll in commons_batch(list(cm)).items():
        coords[cm[pid]] = ll
    print(f"Commons: {len(coords) - n0}/{len(cm)} with coordinates", flush=True)
    (a.indep / "coords.json").write_text(json.dumps(coords), encoding="utf-8")


if __name__ == "__main__":
    main()
