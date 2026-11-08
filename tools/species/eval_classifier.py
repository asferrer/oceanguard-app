"""Evaluate a run of ``train_classifier.py`` as classifier and as kNN embedder.

Classifier: top-1 / top-5 / macro top-1 on test; open-set with the max-softmax
probability, threshold = lowest one reaching ``--precision`` selective
precision on val (negatives counted as errors), reported on test.
Embedder: the saved pre-logit features go through ``eval_rag.evaluate``.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from eval_rag import evaluate, open_set  # noqa: E402


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", type=Path, required=True)
    ap.add_argument("--run", type=Path, required=True)
    ap.add_argument("--precision", type=float, default=0.90)
    args = ap.parse_args()
    rows = [json.loads(x) for x in (args.data / "splits.jsonl").read_text(encoding="utf-8").splitlines()]
    rows = [r for r in rows if r["split"] != "dropped_dup"]
    species = np.array(sorted({r["species_key"] for r in rows if r["role"] == "catalog"}))
    logits = np.load(args.run / "logits.npy")
    p = np.exp(logits - logits.max(1, keepdims=True))
    p /= p.sum(1, keepdims=True)

    def part(split):
        idx = np.array([i for i, r in enumerate(rows) if r["split"] == split])
        lab = np.array([rows[i]["species_key"] for i in idx])
        neg = np.array([rows[i]["role"] == "negative" for i in idx])
        order = np.argsort(-p[idx], 1)
        top1 = species[order[:, 0]]
        return lab, neg, p[idx].max(1), (top1 == lab) & ~neg, species[order[:, :5]]

    vl, vn, vc, vcor, _ = part("val")
    taus = np.linspace(0, 1, 1001)
    ok = [t for t in taus if open_set(vc, vcor, vn, t)["selective_precision"] >= args.precision]
    tau = float(min(ok)) if ok else 1.0
    tl, tn, tc, tcor, t5 = part("test")
    cat = ~tn
    res = {
        "top1": float(tcor[cat].mean()),
        "top5": float(np.mean([l in s for l, s in zip(tl[cat], t5[cat])])),
        "macro_top1": float(np.mean([tcor[cat][tl[cat] == s].mean() for s in sorted(set(tl[cat]))])),
        "val_top1": float(vcor[~vn].mean()), "tau_softmax": tau,
        "open_set_test": open_set(tc, tcor, tn, tau),
    }
    (args.run / "eval_classifier.json").write_text(json.dumps(res, indent=2), encoding="utf-8")
    print("classifier:", json.dumps(res))
    emb_res = evaluate(np.load(args.run / "feats.npy"), rows, "all", None, args.precision,
                       args.run, f"{args.run.name}_feats_all")
    print("as kNN embedder:", json.dumps({k: emb_res[k] for k in ("top1", "top5", "macro_top1", "tau",
                                                                  "open_set_test")}))


if __name__ == "__main__":
    main()
