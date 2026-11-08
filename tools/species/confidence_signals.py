"""Compare confidence signals for the "not identified" decision of the visual RAG.

For a given encoder + gallery, computes on val and test the species scores and
three candidate confidence signals of the top-1:

* ``cos``     the top-1 cosine (what the v1 app calibrated);
* ``margin``  top-1 minus top-2 cosine;
* ``softmax`` softmax over species cosines with temperature T fitted on val
              (max log-likelihood of the true species), i.e. relative evidence.

For each signal prints the val-chosen threshold for precision targets and the
resulting test coverage / precision / negatives rejected, plus the AUROC of
the signal for "top-1 correct" (negatives = incorrect) on test.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from biodex_common import fit_temperature, softmax_max  # noqa: E402
from eval_rag import build_gallery, open_set, score  # noqa: E402


def auroc(sig, y):
    order = np.argsort(sig)
    ranks = np.empty(len(sig))
    ranks[order] = np.arange(1, len(sig) + 1)
    pos = y.sum()
    neg = len(y) - pos
    return float((ranks[y].sum() - pos * (pos + 1) / 2) / max(1, pos * neg))


def signals(emb, rows, gallery, split, species, t=None, published=None):
    gal, gk = build_gallery(emb, rows, gallery, published=published)
    idx = np.array([i for i, r in enumerate(rows) if r["split"] == split])
    lab = np.array([rows[i]["species_key"] for i in idx])
    neg = np.array([rows[i]["role"] == "negative" for i in idx])
    sc = score(emb[idx], gal, gk, species)
    o = np.argsort(-sc, 1)
    s1, s2 = sc[np.arange(len(idx)), o[:, 0]], sc[np.arange(len(idx)), o[:, 1]]
    correct = (species[o[:, 0]] == lab) & ~neg
    if t is None:
        pos = {s: j for j, s in enumerate(species)}
        m = ~neg & np.isin(lab, species)
        t = fit_temperature(sc[m], np.array([pos[x] for x in lab[m]]))
    return {"cos": s1, "margin": s1 - s2, "softmax": softmax_max(sc, t)}, correct, neg, t


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", type=Path, required=True)
    ap.add_argument("--emb", type=Path, required=True)
    ap.add_argument("--encoder", required=True)
    ap.add_argument("--gallery", default="proto6")
    ap.add_argument("--targets", default="0.7,0.8,0.9")
    ap.add_argument("--published", type=Path, help="SPEX index for --gallery published")
    args = ap.parse_args()
    rows = [json.loads(x) for x in (args.data / "splits.jsonl").read_text(encoding="utf-8").splitlines()]
    keep = [i for i, r in enumerate(rows) if r["split"] != "dropped_dup"]
    rows = [rows[i] for i in keep]
    emb = np.load(args.emb / f"{args.encoder}.npy")[keep]
    species = np.array(sorted({r["species_key"] for r in rows if r["role"] == "catalog"}))
    sv, cv, nv, t = signals(emb, rows, args.gallery, "val", species, published=args.published)
    st, ct, nt, _ = signals(emb, rows, args.gallery, "test", species, t, args.published)
    print(f"{args.encoder}/{args.gallery}  softmax T={t:.4f}  test top-1 (catalog) {ct[~nt].mean():.3f}")
    for name in ("cos", "margin", "softmax"):
        line = f"  {name:<8} AUROC {auroc(st[name], ct):.3f}"
        for target in map(float, args.targets.split(",")):
            grid = np.quantile(sv[name], np.linspace(0, 1, 401))
            ok = [g for g in grid if open_set(sv[name], cv, nv, g)["selective_precision"] >= target]
            if not ok:
                line += f" | p>={target}: unreachable"
                continue
            m = open_set(st[name], ct, nt, min(ok))
            line += (f" | p>={target}: thr {min(ok):.4f} cov {m['coverage_catalog']:.2f} "
                     f"prec {m['selective_precision']:.2f} negrej {m['negatives_rejected']:.2f}")
        print(line)


if __name__ == "__main__":
    main()
