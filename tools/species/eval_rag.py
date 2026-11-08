"""Evaluate visual-RAG retrieval (kNN over a reference gallery) per encoder.

Gallery = train images of the catalogue species. Queries = test images.
Galleries compared: ``all`` (every train image, species score = max cosine),
``proto6`` (k-means k=6 prototypes per species: the SPEX format the app reads)
and, for ``pack_onnx``, ``published`` (the index published with the app).

Open-set: the answer is "not identified" when the confidence of the top-1 is
below a threshold. Confidence = softmax over the species scores with a
temperature fitted on **val** (what the app computes); the threshold is the
lowest one whose val selective precision is >= ``--precision`` (accepted answers
right at least that often, accepted negatives counted as errors). The same
numbers are then reported on **test**. ``confidence_signals.py`` compares this
signal with the raw cosine (the v1 Platt input) and the top-1/top-2 margin.

Writes ``<out>/rag_<encoder>_<gallery>.json`` and per-class CSVs.
"""

from __future__ import annotations

import argparse
import csv
import json
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from biodex_common import fit_temperature, l2n, read_spex, softmax_max  # noqa: E402


def kmeans(x: np.ndarray, k: int, seed: int = 0, iters: int = 30) -> np.ndarray:
    """Spherical k-means (k-means++ init), deterministic. Returns L2-norm centroids."""
    if len(x) <= k:
        return x.copy()
    rng = np.random.default_rng(seed)
    c = [x[rng.integers(len(x))]]
    for _ in range(1, k):
        d = np.clip(1 - np.max(x @ np.stack(c).T, axis=1), 0, None)
        c.append(x[rng.choice(len(x), p=d / d.sum())] if d.sum() > 0 else x[rng.integers(len(x))])
    c = np.stack(c)
    for _ in range(iters):
        a = np.argmax(x @ c.T, axis=1)
        c = l2n(np.stack([x[a == j].mean(0) if np.any(a == j) else c[j] for j in range(k)]))
    return c


def build_gallery(emb, rows, kind, k=6, published=None):
    tr = [i for i, r in enumerate(rows) if r["split"] == "train" and r["role"] == "catalog"]
    keys = np.array([rows[i]["species_key"] for i in tr])
    if kind == "all":
        return emb[tr], keys
    if kind == "published":
        protos, meta = read_spex(published)
        return protos, np.array([m["speciesKey"] for m in meta])
    vecs, ks = [], []
    for sp in sorted(set(keys)):
        c = kmeans(emb[np.array(tr)[keys == sp]], k)
        vecs.append(c)
        ks += [sp] * len(c)
    return np.concatenate(vecs), np.array(ks)


def score(q, gal, gkeys, species):
    sims = q @ gal.T
    out = np.full((len(q), len(species)), -1.0, np.float32)
    for j, sp in enumerate(species):
        m = gkeys == sp
        if m.any():
            out[:, j] = sims[:, m].max(1)
    return out


def fit_platt(s: np.ndarray, y: np.ndarray) -> tuple[float, float]:
    """1-D logistic regression P(correct | s) = sigmoid(a*s + b), Newton on standardised s.

    Standardising and starting from zero keeps Newton stable (no sklearn dependency).
    """
    mu, sd = float(s.mean()), float(s.std()) or 1.0
    z = (s - mu) / sd
    w = np.zeros(2)
    for _ in range(100):
        p = 1 / (1 + np.exp(-np.clip(w[0] * z + w[1], -30, 30)))
        g = np.array([np.sum((p - y) * z), np.sum(p - y)])
        q = p * (1 - p) + 1e-9
        h = np.array([[np.sum(q * z * z), np.sum(q * z)], [np.sum(q * z), np.sum(q)]]) + 1e-6 * np.eye(2)
        step = np.linalg.solve(h, g)
        w = w - step
        if np.abs(step).max() < 1e-9:
            break
    return float(w[0] / sd), float(w[1] - w[0] * mu / sd)


def open_set(conf, correct, is_neg, tau):
    acc = conf >= tau
    cat = ~is_neg
    return {
        "coverage_catalog": float(np.mean(acc[cat])),
        "selective_precision": float(np.sum(acc & correct) / max(1, np.sum(acc))),
        "accuracy_answered_catalog": float(np.sum(acc & correct & cat) / max(1, np.sum(acc & cat))),
        "negatives_rejected": float(np.mean(~acc[is_neg])) if is_neg.any() else None,
    }


def evaluate(emb, rows, kind, published, precision, out_dir, tag):
    species = np.array(sorted({r["species_key"] for r in rows if r["role"] == "catalog"}))
    gal, gkeys = build_gallery(emb, rows, kind, published=published)
    res = {"encoder_gallery": tag, "gallery_vectors": int(len(gal)), "dim": int(emb.shape[1])}
    per = {}
    for split in ("val", "test"):
        idx = np.array([i for i, r in enumerate(rows) if r["split"] == split])
        lab = np.array([rows[i]["species_key"] for i in idx])
        is_neg = np.array([rows[i]["role"] == "negative" for i in idx])
        sc = score(emb[idx], gal, gkeys, species)
        order = np.argsort(-sc, 1)
        top1 = species[order[:, 0]]
        s1 = sc[np.arange(len(idx)), order[:, 0]]
        correct = (top1 == lab) & ~is_neg
        per[split] = dict(lab=lab, is_neg=is_neg, s1=s1, sc=sc, correct=correct,
                          top5=species[order[:, :5]], top1=top1)
    v = per["val"]
    pos = {s: j for j, s in enumerate(species)}
    vm = ~v["is_neg"]
    temp = fit_temperature(v["sc"][vm], np.array([pos[x] for x in v["lab"][vm]]))
    conf_v = softmax_max(v["sc"], temp)
    taus = np.linspace(0, 1, 1001)
    ok = [t for t in taus if open_set(conf_v, v["correct"], v["is_neg"], t)["selective_precision"] >= precision]
    tau = float(min(ok)) if ok else 1.0
    t = per["test"]
    cat = ~t["is_neg"]
    conf_t = softmax_max(t["sc"], temp)
    res.update({
        "test_catalog_images": int(cat.sum()), "test_negative_images": int(t["is_neg"].sum()),
        "top1": float(np.mean(t["correct"][cat])),
        "top5": float(np.mean([l in s for l, s in zip(t["lab"][cat], t["top5"][cat])])),
        "macro_top1": float(np.mean([np.mean(t["correct"][cat][t["lab"][cat] == sp])
                                     for sp in sorted(set(t["lab"][cat]))])),
        "val_top1": float(np.mean(v["correct"][~v["is_neg"]])),
        "temperature": temp, "tau": tau, "target_precision": precision,
        "open_set_test": open_set(conf_t, t["correct"], t["is_neg"], tau),
        "open_set_val": open_set(conf_v, v["correct"], v["is_neg"], tau),
    })
    with (out_dir / f"perclass_{tag}.csv").open("w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["species_key", "n_test", "top1", "top5"])
        for sp in sorted(set(t["lab"][cat])):
            m = cat & (t["lab"] == sp)
            w.writerow([sp, int(m.sum()), f"{np.mean(t['correct'][m]):.3f}",
                        f"{np.mean([sp in s for s in t['top5'][m]]):.3f}"])
    (out_dir / f"rag_{tag}.json").write_text(json.dumps(res, indent=2), encoding="utf-8")
    return res


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", type=Path, required=True)
    ap.add_argument("--emb", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--encoders", required=True)
    ap.add_argument("--galleries", default="all,proto6")
    ap.add_argument("--published", type=Path, help="published species_index_v1.bin (pack_onnx only)")
    ap.add_argument("--precision", type=float, default=0.90)
    args = ap.parse_args()
    rows = [json.loads(x) for x in (args.data / "splits.jsonl").read_text(encoding="utf-8").splitlines()]
    keep = [i for i, r in enumerate(rows) if r["split"] != "dropped_dup"]
    rows = [rows[i] for i in keep]
    args.out.mkdir(parents=True, exist_ok=True)
    print(f"{'encoder/gallery':<28}{'top1':>7}{'top5':>7}{'macro':>7}{'tau':>6}{'cov':>6}{'prec':>6}{'negrej':>7}")
    for enc in args.encoders.split(","):
        emb = np.load(args.emb / f"{enc}.npy")[keep]
        gals = args.galleries.split(",") + (["published"] if enc == "pack_onnx" and args.published else [])
        for g in gals:
            r = evaluate(emb, rows, g, args.published, args.precision, args.out, f"{enc}_{g}")
            o = r["open_set_test"]
            print(f"{enc + '/' + g:<28}{r['top1']:7.3f}{r['top5']:7.3f}{r['macro_top1']:7.3f}{r['tau']:6.2f}"
                  f"{o['coverage_catalog']:6.2f}{o['selective_precision']:6.2f}{(o['negatives_rejected'] or 0):7.2f}")


if __name__ == "__main__":
    main()
