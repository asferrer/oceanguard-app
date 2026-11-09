"""Evaluate a built species pack on a labelled image set with the app chain.

Chain (as the app): centre-square crop, bilinear resize to the pack size, pack
mean/std, the pack ONNX on CPU, max-prototype cosine per species over the pack
index, softmax confidence at the pack temperature, decision
``>= tauHigh`` RAG_CORE, ``>= tauLow`` RAG_TENTATIVE, else UNIDENTIFIED.

A set is a jsonl with ``species_key``, ``role`` (catalog | negative) and the
image path (``path`` or ``file``) relative to ``--base``; ``--split`` keeps one
split of ``splits.jsonl`` (iNaturalist test). Metrics: top-1, top-5, macro
top-1, coverage and precision per decision, share of negatives per decision,
worst species and the most frequent confusions. ``--legacy`` runs the published
v1 pack (``clip_vitb32.onnx`` + ``species_index_v1.bin``, no manifest): only
top-1 / top-5 / macro are meaningful there.

Writes ``--out`` (json) with the metrics, per-species table and confusions.
"""

from __future__ import annotations

import argparse
import collections
import json
import sys
from pathlib import Path

import numpy as np
import onnxruntime as ort

sys.path.insert(0, str(Path(__file__).resolve().parent))
from biodex_common import l2n, read_spex, softmax_max, species_scores  # noqa: E402
from build_pack import embed_onnx  # noqa: E402

CLIP_NORM = ([0.48145466, 0.4578275, 0.40821073], [0.26862954, 0.26130258, 0.27577711])


def load_rows(path: Path, split: str | None) -> list[dict]:
    rows = [json.loads(x) for x in path.read_text(encoding="utf-8").splitlines() if x.strip()]
    return [r for r in rows if split is None or r.get("split") == split]


def metrics(rows, uniq, sc, conf, cal) -> dict:
    lab = np.array([r["species_key"] for r in rows])
    neg = np.array([r["role"] == "negative" for r in rows])
    cat = ~neg
    top1 = uniq[sc.argmax(1)]
    top5 = uniq[np.argsort(-sc, 1)[:, :5]]
    ok = top1 == lab
    res = {"n_catalog": int(cat.sum()), "n_negative": int(neg.sum()),
           "species": len(set(lab[cat])),
           "top1": float(ok[cat].mean()), "top5": float(np.mean([l in t for l, t in zip(lab[cat], top5[cat])])),
           "macro_top1": float(np.mean([ok[cat & (lab == s)].mean() for s in sorted(set(lab[cat]))]))}
    if cal:
        dec = np.where(conf >= cal["tauHigh"], "RAG_CORE", np.where(conf >= cal["tauLow"], "RAG_TENTATIVE", "UNIDENTIFIED"))
        res["decisions"] = {
            "catalog": {d: float(np.mean(dec[cat] == d)) for d in ("RAG_CORE", "RAG_TENTATIVE", "UNIDENTIFIED")},
            "negative": {d: float(np.mean(dec[neg] == d)) for d in ("RAG_CORE", "RAG_TENTATIVE", "UNIDENTIFIED")}
            if neg.any() else None,
            "precision": {d: float((ok & cat)[dec == d].sum() / max(1, (dec == d).sum()))
                          for d in ("RAG_CORE", "RAG_TENTATIVE")},
            "answered": {d: int((dec == d).sum()) for d in ("RAG_CORE", "RAG_TENTATIVE")}}
        res["tau"] = [cal["tauHigh"], cal["tauLow"]]
    per = []
    for s in sorted(set(lab[cat])):
        m = cat & (lab == s)
        per.append({"species_key": s, "n": int(m.sum()), "top1": float(ok[m].mean()),
                    "top5": float(np.mean([s in t for t in top5[m]])),
                    "wrong_as": collections.Counter(top1[m & ~ok]).most_common(2)})
    res["per_species"] = per
    conf_pairs = collections.Counter((a, b) for a, b in zip(lab[cat & ~ok], top1[cat & ~ok]))
    res["top_confusions"] = [[a, b, n] for (a, b), n in conf_pairs.most_common(15)]
    res["by_source"] = {}
    if "dataset_key" in rows[0]:
        for d in sorted({r.get("dataset_key") for r in rows if r["role"] == "catalog"}):
            m = cat & np.array([r.get("dataset_key") == d for r in rows])
            if m.sum() >= 20:
                res["by_source"][str(d)] = {"n": int(m.sum()), "top1": float(ok[m].mean())}
    return res


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--pack", type=Path, required=True)
    ap.add_argument("--rows", type=Path, required=True)
    ap.add_argument("--base", type=Path, required=True)
    ap.add_argument("--split")
    ap.add_argument("--legacy", action="store_true")
    ap.add_argument("--out", type=Path, required=True)
    args = ap.parse_args()
    if args.legacy:
        enc_file, idx_file, size, norm, cal = "clip_vitb32.onnx", "species_index_v1.bin", 224, CLIP_NORM, None
    else:
        m = json.loads((args.pack / "species_pack.json").read_text(encoding="utf-8"))
        e = m["encoder"]
        enc_file, idx_file, size, norm, cal = m["encoderFile"], m["indexFile"], e["size"], (e["mean"], e["std"]), m["calibration"]
    sess = ort.InferenceSession(str(args.pack / enc_file), providers=["CPUExecutionProvider"])
    protos, meta = read_spex(args.pack / idx_file)
    gkeys = np.array([x["speciesKey"] for x in meta])
    uniq = np.array(sorted(set(gkeys)))
    rows = load_rows(args.rows, args.split)
    q = embed_onnx(sess, [args.base / (r.get("path") or r["file"]) for r in rows], size, norm)
    sc = species_scores(q, protos, gkeys, uniq)
    conf = softmax_max(sc, cal["temperature"]) if cal else None
    res = metrics(rows, uniq, sc, conf, cal)
    if rows and "photo_ok" in rows[0]:  # independent set: also the subset the CLIP filter calls real photos
        keep = np.array([bool(r["photo_ok"]) for r in rows])
        res["photo_ok_subset"] = metrics([r for r, k in zip(rows, keep) if k], uniq, sc[keep],
                                         conf[keep] if cal else None, cal)
    res["pack"] = str(args.pack)
    res["top1_answers"] = collections.Counter(uniq[sc.argmax(1)]).most_common(3)
    wrong = [(float(s[i]), r, uniq[i], float(c)) for r, s, i, c in zip(rows, sc, sc.argmax(1), conf if cal else sc.max(1))
             if r["role"] == "catalog" and r["species_key"] != uniq[i]]
    wrong.sort(key=lambda t: -(t[3] if cal else t[0]))
    res["examples"] = [{"file": r.get("path") or r["file"], "true": r["species_key"], "pred": str(p),
                        "cos": cs, "conf": c if cal else None} for cs, r, p, c in wrong[:25]]
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(res, indent=1, ensure_ascii=False), encoding="utf-8")
    print(f"{args.pack.name}: {res['n_catalog']} photos / {res['species']} species, {res['n_negative']} negatives")
    print(f"  top-1 {res['top1']:.3f}  top-5 {res['top5']:.3f}  macro {res['macro_top1']:.3f}")
    if "photo_ok_subset" in res:
        sub = res["photo_ok_subset"]
        print(f"  photo_ok subset: {sub['n_catalog']} photos  top-1 {sub['top1']:.3f}  top-5 {sub['top5']:.3f}  "
              f"macro {sub['macro_top1']:.3f}")
    if cal:
        d = res["decisions"]
        print(f"  catalogue {d['catalog']}\n  negatives {d['negative']}\n  precision {d['precision']} n={d['answered']}")


if __name__ == "__main__":
    main()
