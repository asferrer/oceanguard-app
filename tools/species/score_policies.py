"""Score decision policies (RAG alone vs RAG + Gemma) from gemma_rerank.py output.

Policies, all on the softmax confidence stored by gemma_rerank.py (softmax over
species scores, temperature fitted on val):

* ``rag``        answer the RAG top-1 when conf >= tau, else "not identified";
* ``gemma_band`` conf >= tau_high -> RAG top-1; tau_low <= conf < tau_high ->
                 Gemma's choice among the top-k (0 -> "not identified");
                 conf < tau_low -> "not identified" (this is the app logic);
* ``gemma_all``  conf >= tau_low -> Gemma's choice.

Thresholds are chosen on **val** to maximise the number of correct answers
subject to selective precision >= ``--precision`` (an accepted negative or a
wrong species counts as an error), then reported on **test**.
"""

from __future__ import annotations

import argparse
import itertools
import json
from pathlib import Path

import numpy as np


def load(path: Path):
    rows = [json.loads(x) for x in path.read_text(encoding="utf-8").splitlines()]
    out = {}
    for split in ("val", "test"):
        r = [x for x in rows if x["split"] == split]
        out[split] = {
            "neg": np.array([x["role"] == "negative" for x in r]),
            "lab": np.array([x["label"] for x in r]),
            "conf": np.array([x["conf"] for x in r]),
            "top1": np.array([x["cands"][0] for x in r]),
            # pick -> that candidate; 0 -> none; -1 (unparseable) -> the app keeps the RAG top-1
            "gem": np.array([x["cands"][x["choice"] - 1] if x["choice"] > 0 else
                             ("" if x["choice"] == 0 else x["cands"][0]) for x in r]),
            "sec": np.array([x["sec"] for x in r]),
        }
    return out


def answers(d, conf, policy, lo, hi):
    if policy == "rag":
        return np.where(conf >= lo, d["top1"], ""), np.zeros(len(conf), bool)
    band = (conf >= lo) & (conf < hi) if policy == "gemma_band" else conf >= lo
    ans = np.where(conf >= hi, d["top1"], "") if policy == "gemma_band" else np.full(len(conf), "", object)
    ans = np.where(band, d["gem"], ans)
    return ans, band


def metrics(d, ans, called):
    acc = ans != ""
    right = acc & (ans == d["lab"]) & ~d["neg"]
    cat = ~d["neg"]
    return {
        "correct_catalog": float(right[cat].mean()),
        "coverage_catalog": float(acc[cat].mean()),
        "selective_precision": float(right.sum() / max(1, acc.sum())),
        "negatives_rejected": float((~acc[d["neg"]]).mean()) if d["neg"].any() else None,
        "gemma_calls": float(called.mean()),
    }


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--gemma", type=Path, required=True)
    ap.add_argument("--precision", type=float, default=0.90)
    ap.add_argument("--out", type=Path, required=True)
    args = ap.parse_args()
    d = load(args.gemma)
    conf = {s: d[s]["conf"] for s in d}
    temperature = json.loads(args.gemma.read_text(encoding="utf-8").splitlines()[0])["temperature"]
    grid = np.round(np.linspace(0, 1, 101), 2)
    res = {"temperature": temperature, "target_precision": args.precision}
    for policy in ("rag", "gemma_band", "gemma_all"):
        pairs = [(lo, 1.01) for lo in grid] if policy != "gemma_band" else \
            [(lo, hi) for lo, hi in itertools.product(grid, grid) if hi >= lo]
        best, best_m = None, None
        for lo, hi in pairs:
            m = metrics(d["val"], *answers(d["val"], conf["val"], policy, lo, hi))
            if m["selective_precision"] >= args.precision and (best_m is None or
                                                               m["correct_catalog"] > best_m["correct_catalog"]):
                best, best_m = (lo, hi), m
        if best is None:
            continue
        test = metrics(d["test"], *answers(d["test"], conf["test"], policy, *best))
        res[policy] = {"tau_low": best[0], "tau_high": best[1], "val": best_m, "test": test}
        print(f"{policy:<11} tau_low={best[0]:.2f} tau_high={best[1]:.2f}  test: " +
              "  ".join(f"{k}={v:.3f}" for k, v in test.items() if v is not None))
    # Gemma alone as a top-k chooser, no thresholds (how often it keeps / fixes the RAG top-1).
    t = d["test"]
    cat = ~t["neg"]
    res["gemma_topk_accuracy_catalog"] = float((t["gem"][cat] == t["lab"][cat]).mean())
    res["rag_top1_accuracy_catalog"] = float((t["top1"][cat] == t["lab"][cat]).mean())
    res["gemma_none_on_negatives"] = float((t["gem"][t["neg"]] == "").mean()) if t["neg"].any() else None
    res["gemma_sec_p50_pc"] = float(np.median(np.concatenate([d["val"]["sec"], t["sec"]])))
    print({k: res[k] for k in ("gemma_topk_accuracy_catalog", "rag_top1_accuracy_catalog",
                               "gemma_none_on_negatives", "gemma_sec_p50_pc")})
    args.out.write_text(json.dumps(res, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
