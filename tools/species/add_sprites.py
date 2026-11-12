"""Add species sprites to an existing BioDex pack and write the next pack version.

Reads the manifest of --pack (species_pack.json), the sprites of --sprites (<speciesKey>.webp) and the source
credits of --credits (TSV: species, photo_url, license, attribution). Writes to --out:
- sprites/<speciesKey>.webp  (only species of the pack catalogue),
- sprite_credits.json        ({speciesKey: {attribution, license, url}}),
- species_pack.json          (version + 1, "sprites" and "spriteCredits" set, their SHA-256 added to "sha256").

The encoder, index, catalogue and geographic files do not change: the app reuses them through their SHA-256, so
only the files written here need to be uploaded next to the existing ones.

Usage:
    python tools/species/add_sprites.py --pack pack_v4 --sprites export/sprites --credits export/ATRIBUCIONES.tsv \
        --out pack_v5
"""
import argparse
import csv
import hashlib
import json
import shutil
from pathlib import Path

CREDITS = "sprite_credits.json"
MAX_SPRITE_BYTES = 200_000


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def read_credits(path: Path) -> dict:
    with path.open(encoding="utf-8", newline="") as f:
        rows = csv.DictReader(f, delimiter="\t")
        return {r["species"]: {"attribution": r["attribution"], "license": r["license"], "url": r["photo_url"]}
                for r in rows}


def catalog_keys(pack: Path, manifest: dict) -> set:
    cat = json.loads((pack / manifest["catalogFile"]).read_text(encoding="utf-8"))
    return {s["speciesKey"] for s in cat["species"]}


def build(pack: Path, sprites: Path, credits_tsv: Path, out: Path) -> dict:
    manifest = json.loads((pack / "species_pack.json").read_text(encoding="utf-8"))
    keys = catalog_keys(pack, manifest)
    credits = read_credits(credits_tsv)
    (out / "sprites").mkdir(parents=True, exist_ok=True)
    names, used = [], {}
    for src in sorted(sprites.glob("*.webp")):
        key = src.stem
        if key not in keys:
            raise SystemExit(f"{key}: not in the pack catalogue")
        if key not in credits:
            raise SystemExit(f"{key}: no credit line")
        if src.stat().st_size > MAX_SPRITE_BYTES:
            raise SystemExit(f"{key}: {src.stat().st_size} bytes, more than {MAX_SPRITE_BYTES}")
        name = f"sprites/{key}.webp"
        shutil.copyfile(src, out / name)
        names.append(name)
        used[key] = credits[key]
    (out / CREDITS).write_text(json.dumps(used, indent=1, ensure_ascii=False, sort_keys=True) + "\n",
                               encoding="utf-8")
    sha = dict(manifest.get("sha256") or {})
    for name in names + [CREDITS]:
        sha[name] = sha256_file(out / name)
    manifest.update(version=manifest["version"] + 1, sprites=names, spriteCredits=CREDITS, sha256=sha)
    (out / "species_pack.json").write_text(json.dumps(manifest, indent=1, ensure_ascii=False) + "\n",
                                           encoding="utf-8")
    return manifest


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--pack", type=Path, required=True, help="installed pack dir with species_pack.json")
    ap.add_argument("--sprites", type=Path, required=True, help="dir with <speciesKey>.webp")
    ap.add_argument("--credits", type=Path, required=True, help="TSV species, photo_url, license, attribution")
    ap.add_argument("--out", type=Path, required=True)
    a = ap.parse_args()
    m = build(a.pack, a.sprites, a.credits, a.out)
    size = sum((a.out / n).stat().st_size for n in m["sprites"])
    print(f"version {m['version']}: {len(m['sprites'])} sprites, {size // 1024} KiB -> {a.out}")


if __name__ == "__main__":
    main()
