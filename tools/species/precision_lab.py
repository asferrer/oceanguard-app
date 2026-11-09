"""Experiment library for the species-precision study (embeddings in memory, app-parity metrics).

A *context* holds, per encoder, the embeddings of: the iNaturalist rows (``splits.jsonl`` order),
extra iNaturalist reference rows (train-observer only, ``fetch_inat_more.py``) and the independent set.
A *score matrix* ``S [n, species]`` (higher = better) goes through the same open-set calibration as the
packs: softmax temperature fitted on val (catalogue photos), ``tauHigh`` / ``tauLow`` = lowest threshold
whose val selective precision (wrong species or accepted negative = error) is >= 0.90 / 0.80, then
reported on the iNaturalist test split and on the independent set.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np

from biodex_common import l2n
from eval_rag import kmeans


def load_jsonl(p: Path) -> list[dict]:
    return [json.loads(x) for x in p.read_text(encoding="utf-8").splitlines() if x.strip()]


@dataclass
class Ctx:
    inat_rows: list[dict]
    indep_rows: list[dict]
    ext_rows: list[dict] = field(default_factory=list)
    emb: dict = field(default_factory=dict)  # (encoder, tag) -> array; tags: inat, indep, ext

    def get(self, enc: str, tag: str, variant: str = ""):
        return self.emb[(enc + variant, tag)]


# ---------------------------------------------------------------------------
# Reference sets
# ---------------------------------------------------------------------------
def reference_set(ctx: Ctx, enc: str, licences=("cc0", "cc-by"), extra=False, cap=None, seed=0, variants=("",)):
    """Return (vectors [n, d], species keys [n]) of the reference photos (train observers only)."""
    xs, ks = [], []
    base = [(r["species_key"], i) for i, r in enumerate(ctx.inat_rows)
            if r["split"] == "train" and r["role"] == "catalog" and (r.get("license") or "") in licences]
    for v in variants:
        E = ctx.get(enc, "inat", v)
        xs.append(E[[i for _, i in base]])
        ks += [k for k, _ in base]
        if extra and ctx.ext_rows:
            Ee = ctx.get(enc, "ext", v)
            xs.append(Ee)
            ks += [r["species_key"] for r in ctx.ext_rows]
    X, K = np.concatenate(xs), np.array(ks)
    if cap:
        rng = np.random.default_rng(seed)
        keep = []
        for sp in sorted(set(K)):
            idx = np.flatnonzero(K == sp)
            keep += list(rng.choice(idx, min(cap, len(idx)), replace=False)) if len(idx) > cap else list(idx)
        keep = np.array(sorted(keep))
        X, K = X[keep], K[keep]
    return X, K


def prototypes(X: np.ndarray, K: np.ndarray, k: int):
    vecs, keys = [], []
    for sp in sorted(set(K)):
        c = kmeans(X[K == sp], k)
        vecs.append(c)
        keys += [sp] * len(c)
    return np.concatenate(vecs).astype("<f2").astype(np.float32), np.array(keys)


# ---------------------------------------------------------------------------
# Scores
# ---------------------------------------------------------------------------
def score_max(q: np.ndarray, G: np.ndarray, GK: np.ndarray, uniq: np.ndarray, topm: int = 1) -> np.ndarray:
    """Species score = mean of the ``topm`` best cosines among its reference vectors (topm=1: max)."""
    sims = q @ G.T
    out = np.full((len(q), len(uniq)), -1.0, np.float32)
    for j, sp in enumerate(uniq):
        s = sims[:, GK == sp]
        if s.shape[1] == 0:
            continue
        m = min(topm, s.shape[1])
        out[:, j] = s.max(1) if m == 1 else np.partition(s, -m, axis=1)[:, -m:].mean(1)
    return out


def softmax_rows(z):
    z = z - z.max(1, keepdims=True)
    p = np.exp(z)
    return p / p.sum(1, keepdims=True)


def fit_T(S: np.ndarray, lab: np.ndarray) -> float:
    best = (None, -np.inf)
    for t in np.geomspace(0.002, 50, 90):
        z = S / t
        z = z - z.max(1, keepdims=True)
        ll = (z[np.arange(len(z)), lab] - np.log(np.exp(z).sum(1))).mean()
        if ll > best[1]:
            best = (float(t), ll)
    return best[0]


def open_stats(conf, correct, neg, tau):
    acc = conf >= tau
    return float(np.sum(acc & correct) / max(1, acc.sum()))


def lowest_tau(conf, correct, neg, p):
    for t in np.linspace(0, 1, 1001):
        if open_stats(conf, correct, neg, t) >= p:
            return round(float(t), 3)
    return 1.0


@dataclass
class Split:
    S: np.ndarray       # [n, species]
    lab: np.ndarray     # species index, -1 for negatives or unknown
    neg: np.ndarray


def make_split(rows, S, pos) -> Split:
    lab = np.array([pos.get(r["species_key"], -1) if r["role"] == "catalog" else -1 for r in rows])
    neg = np.array([r["role"] == "negative" for r in rows])
    return Split(S, lab, neg)


def evaluate(val: Split, others: dict[str, Split], T: float | None = None, reject: np.ndarray | None = None,
             prec_high: float = 0.90, prec_low: float = 0.80) -> dict:
    """Calibrate on ``val`` and report each split of ``others``."""
    m = (~val.neg) & (val.lab >= 0)
    T = T or fit_T(val.S[m], val.lab[m])
    cv = softmax_rows(val.S / T).max(1)
    if reject is not None:  # columns that mean "not in the catalogue": never an answer
        cv = np.where(reject[val.S.argmax(1)], 0.0, cv)
    corr = (val.S.argmax(1) == val.lab) & ~val.neg
    th, tl = lowest_tau(cv, corr, val.neg, prec_high), lowest_tau(cv, corr, val.neg, prec_low)
    res = {"T": round(T, 5), "tauHigh": th, "tauLow": tl,
           "val_top1": float(corr[~val.neg].mean())}
    for name, sp in others.items():
        cat = ~sp.neg
        order = np.argsort(-sp.S, 1)[:, :5]
        top1 = order[:, 0]
        ok = (top1 == sp.lab) & cat
        conf = softmax_rows(sp.S / T).max(1)
        if reject is not None:
            conf = np.where(reject[sp.S.argmax(1)], 0.0, conf)
        r = {"n": int(cat.sum()), "top1": float(ok[cat].mean()),
             "top5": float(np.mean([l in o for l, o in zip(sp.lab[cat], order[cat])])),
             "macro": float(np.mean([ok[cat & (sp.lab == s)].mean() for s in sorted(set(sp.lab[cat])) if s >= 0]))}
        for tag, lo, hi in (("core", th, 2.0), ("tent", tl, th)):
            a = (conf >= lo) & (conf < hi)
            r[f"{tag}_cov"] = float(a[cat].mean())
            r[f"{tag}_prec"] = float(ok[a].sum() / max(1, a.sum()))
            r[f"{tag}_neg"] = float(a[sp.neg].mean()) if sp.neg.any() else None
        r["neg_unid"] = float((conf[sp.neg] < tl).mean()) if sp.neg.any() else None
        res[name] = r
    return res


def fmt_row(name: str, res: dict, size: str = "") -> str:
    out = f"{name:<34}"
    for k in ("test", "indep"):
        r = res[k]
        out += (f" | {r['top1']:.3f} {r['top5']:.3f} {r['macro']:.3f} "
                f"{r['core_cov']:.3f}/{r['core_prec']:.3f} {r['tent_cov']:.3f}/{r['tent_prec']:.3f} {r['neg_unid']:.3f}")
    return out + f" | val {res['val_top1']:.3f} T={res['T']} tau={res['tauHigh']}/{res['tauLow']} {size}"


HEADER = (f"{'config':<34} | " + "test: top1 top5 macro  core cov/prec tent cov/prec negUnid | "
          + "indep: same")


# ---------------------------------------------------------------------------
# Linear head (multinomial logistic regression on L2-normalised embeddings)
# ---------------------------------------------------------------------------
def train_head(X: np.ndarray, y: np.ndarray, n_cls: int, wd: float = 1e-4, epochs: int = 200, scale: float = 30.0,
               device: str = "cpu"):
    """Softmax regression; returns (W [cls, d], b [cls]) such that logits = scale * X @ W.T + b."""
    import torch
    x = torch.from_numpy(X).float().to(device)
    t = torch.from_numpy(y).long().to(device)
    W = torch.zeros(n_cls, X.shape[1], device=device, requires_grad=True)
    b = torch.zeros(n_cls, device=device, requires_grad=True)
    opt = torch.optim.LBFGS([W, b], lr=1.0, max_iter=epochs, history_size=20, line_search_fn="strong_wolfe")

    def closure():
        opt.zero_grad()
        loss = torch.nn.functional.cross_entropy(scale * x @ W.T + b, t) + wd * (W ** 2).sum()
        loss.backward()
        return loss
    opt.step(closure)
    return W.detach().cpu().numpy(), b.detach().cpu().numpy()


def head_logits(q: np.ndarray, W: np.ndarray, b: np.ndarray, scale: float = 30.0) -> np.ndarray:
    return scale * q @ W.T + b
