"""Experiments of the species-precision study (section 10 of docs/SPECIES_ID.md).

    python -I tools/species/precision_exp.py --emb <emb dir> --inat <data/inat> --indep <data/indep> \
        --exp gallery --encoders bioclip,bioclip2 [--ext <ext rows jsonl>] --out <json>

Experiments: ``gallery`` (prototypes k, kNN over all references, top-m mean), ``head`` (linear head and
head + RAG), ``tta`` (flip / extra crops), ``text`` (taxon-name prototypes), ``refs`` (references per species).
Embeddings are produced by ``embed_set.py`` (fp32, app preprocessing); the final packs are re-checked
with ``eval_pack.py`` (ONNX CPU), see the document.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import precision_lab as L  # noqa: E402


def load_ctx(a, encoders: list[str], variants=("",)) -> L.Ctx:
    inat_rows = L.load_jsonl(a.inat / "splits.jsonl")
    indep_rows = L.load_jsonl(a.indep / "indep.jsonl")
    for r in inat_rows:  # hypselodoris_picta is the same nudibranch as felimare_picta (identical observations)
        if r["species_key"] == "hypselodoris_picta":
            r["split"] = "dropped_dup"
    ctx = L.Ctx(inat_rows, indep_rows)
    if a.ext:
        ctx.ext_rows = L.load_jsonl(a.ext)
    for enc in encoders:
        for v in variants:
            for tag, fn in (("inat", f"inat_{enc}{v}.npy"), ("indep", f"indep_{enc}{v}.npy"), ("ext", f"ext_{enc}{v}.npy")):
                p = a.emb / fn
                if p.exists():
                    ctx.emb[(enc + v, tag)] = np.load(p)
    return ctx


def splits_for(ctx: L.Ctx, enc: str, G, GK, uniq, scorer=None, variant: str = ""):
    """Score matrices for val / test / indep from reference vectors (G, GK)."""
    pos = {s: j for j, s in enumerate(uniq)}
    scorer = scorer or (lambda q: L.score_max(q, G, GK, uniq))
    vi = [i for i, r in enumerate(ctx.inat_rows) if r["split"] == "val"]
    ti = [i for i, r in enumerate(ctx.inat_rows) if r["split"] == "test"]
    E = ctx.get(enc, "inat", variant)
    X = ctx.get(enc, "indep", variant)
    val = L.make_split([ctx.inat_rows[i] for i in vi], scorer(E[vi]), pos)
    test = L.make_split([ctx.inat_rows[i] for i in ti], scorer(E[ti]), pos)
    indep = L.make_split(ctx.indep_rows, scorer(X), pos)
    return val, {"test": test, "indep": indep}


def run_gallery(ctx, encoders, extra, out):
    print(L.HEADER)
    for enc in encoders:
        X, K = L.reference_set(ctx, enc, extra=extra)
        uniq = np.array(sorted(set(K)))
        cfgs = [("proto6", *L.prototypes(X, K, 6), 1), ("proto12", *L.prototypes(X, K, 12), 1),
                ("proto24", *L.prototypes(X, K, 24), 1), ("all-refs max", X, K, 1), ("all-refs top3 mean", X, K, 3)]
        for name, G, GK, m in cfgs:
            val, others = splits_for(ctx, enc, G, GK, uniq, lambda q, G=G, GK=GK, m=m: L.score_max(q, G, GK, uniq, m))
            res = L.evaluate(val, others)
            size = f"index {len(G) * X.shape[1] * 2 / 1e6:.2f} MB ({len(G)} vectors)"
            print(L.fmt_row(f"{enc} {name}" + (" +ext" if extra else ""), res, size), flush=True)
            out[f"{enc}|{name}|ext={extra}"] = {**res, "vectors": len(G), "index_mb": len(G) * X.shape[1] * 2 / 1e6}


def run_head(ctx, encoders, extra, out):
    print(L.HEADER)
    for enc in encoders:
        X, K = L.reference_set(ctx, enc, extra=extra)
        uniq = np.array(sorted(set(K)))
        pos = {s: j for j, s in enumerate(uniq)}
        y = np.array([pos[k] for k in K])
        G6, GK6 = L.prototypes(X, K, 6)
        for wd in (1e-5, 1e-4, 1e-3):
            W, b = L.train_head(X, y, len(uniq), wd=wd)
            for mode, lam in (("head", 0.0), ("head+rag", 1.0), ("head+rag2", 2.0)):
                def scorer(q, W=W, b=b, lam=lam):
                    h = L.head_logits(q, W, b)
                    h = h - h.mean(1, keepdims=True)
                    if lam == 0.0:
                        return h / 30.0
                    return h / 30.0 + lam * L.score_max(q, G6, GK6, uniq)
                val, others = splits_for(ctx, enc, None, None, uniq, scorer)
                res = L.evaluate(val, others)
                size = f"head {W.size * 2 / 1e6:.2f} MB"
                print(L.fmt_row(f"{enc} {mode} wd={wd:g}", res, size), flush=True)
                out[f"{enc}|{mode}|wd={wd}|ext={extra}"] = res


def run_tta(ctx, encoders, extra, out, variants):
    print(L.HEADER)
    for enc in encoders:
        X, K = L.reference_set(ctx, enc, extra=extra)
        uniq = np.array(sorted(set(K)))
        G, GK = L.prototypes(X, K, 6)
        base = None
        for name, vs in (("center", [""]), ("center+flip", ["", "_flip"])) + tuple(
                (f"center+{v[1:]}", ["", v]) for v in variants if v not in ("", "_flip")) + (
                ("all", ["", *variants]),):
            if any((enc + v, "inat") not in ctx.emb for v in vs):
                continue

            def scorer_for(tag):
                pass
            pos = {s: j for j, s in enumerate(uniq)}
            vi = [i for i, r in enumerate(ctx.inat_rows) if r["split"] == "val"]
            ti = [i for i, r in enumerate(ctx.inat_rows) if r["split"] == "test"]

            def q_of(tag, idx=None):
                E = L.l2n(sum(ctx.get(enc, tag, v)[idx] if idx is not None else ctx.get(enc, tag, v) for v in vs))
                return E
            val = L.make_split([ctx.inat_rows[i] for i in vi], L.score_max(q_of("inat", vi), G, GK, uniq), pos)
            test = L.make_split([ctx.inat_rows[i] for i in ti], L.score_max(q_of("inat", ti), G, GK, uniq), pos)
            indep = L.make_split(ctx.indep_rows, L.score_max(q_of("indep"), G, GK, uniq), pos)
            res = L.evaluate(val, {"test": test, "indep": indep})
            print(L.fmt_row(f"{enc} TTA {name}", res, f"x{len(vs)} encodes"), flush=True)
            out[f"{enc}|tta|{name}|ext={extra}"] = res


def run_text(ctx, encoders, extra, out, emb_dir):
    print(L.HEADER)
    for enc in encoders:
        X, K = L.reference_set(ctx, enc, extra=extra)
        uniq = np.array(sorted(set(K)))
        G, GK = L.prototypes(X, K, 6)
        tz = np.load(emb_dir / f"text_{enc}.npz")
        idx = {k: i for i, k in enumerate(tz["keys"])}
        for prompt in ("sci", "tax", "taxcom"):
            Tm = tz[prompt][[idx[k] for k in uniq]]
            for mu in (0.0, 0.25, 0.5, 1.0, 100.0):
                def scorer(q, mu=mu, Tm=Tm):
                    return (L.score_max(q, G, GK, uniq) if mu < 100 else 0.0) + (mu if mu < 100 else 1.0) * (q @ Tm.T)
                if mu == 0.0 and prompt != "sci":
                    continue
                val, others = splits_for(ctx, enc, G, GK, uniq, scorer)
                res = L.evaluate(val, others)
                nm = "rag only" if mu == 0 else ("zero-shot text only" if mu >= 100 else f"rag + {mu}*text")
                print(L.fmt_row(f"{enc} {prompt} {nm}", res), flush=True)
                out[f"{enc}|{prompt}|{mu}"] = res


def run_combo(ctx, encoders, extra, out, emb_dir, k):
    """score = rag + mu * text + lam * head, weights picked on val (top-1, then log-likelihood)."""
    print(L.HEADER)
    for enc in encoders:
        X, K = L.reference_set(ctx, enc, extra=extra)
        uniq = np.array(sorted(set(K)))
        pos = {s: j for j, s in enumerate(uniq)}
        y = np.array([pos[x] for x in K])
        G, GK = L.prototypes(X, K, k)
        tz = np.load(emb_dir / f"text_{enc}.npz")
        idx = {kk: i for i, kk in enumerate(tz["keys"])}
        Tm = tz["sci"][[idx[kk] for kk in uniq]]
        W, b = L.train_head(X, y, len(uniq), wd=1e-4)

        def comps(q):
            h = L.head_logits(q, W, b)
            return L.score_max(q, G, GK, uniq, 1), q @ Tm.T, (h - h.mean(1, keepdims=True)) / 30.0
        cache = {}
        vi = [i for i, r in enumerate(ctx.inat_rows) if r["split"] == "val"]
        ti = [i for i, r in enumerate(ctx.inat_rows) if r["split"] == "test"]
        E, XI = ctx.get(enc, "inat"), ctx.get(enc, "indep")
        parts = {"val": (comps(E[vi]), [ctx.inat_rows[i] for i in vi]),
                 "test": (comps(E[ti]), [ctx.inat_rows[i] for i in ti]),
                 "indep": (comps(XI), ctx.indep_rows)}
        rows = []
        for w_rag in (0.0, 1.0):
            for mu in (0.0, 0.5, 1.0, 2.0, 4.0):
                for lam in (0.0, 0.5, 1.0, 2.0):
                    if w_rag == 0.0 and mu == 0.0 and lam == 0.0:
                        continue
                    sp = {n: L.make_split(r, w_rag * c[0] + mu * c[1] + lam * c[2], pos) for n, (c, r) in parts.items()}
                    res = L.evaluate(sp["val"], {"test": sp["test"], "indep": sp["indep"]})
                    rows.append((res["val_top1"], f"{enc} rag={w_rag} text={mu} head={lam}", res))
        rows.sort(key=lambda t: -t[0])
        for v, name, res in rows[:12]:
            print(L.fmt_row(name, res), flush=True)
            out[name + f"|ext={extra}|k={k}"] = res
        for v, name, res in rows:
            if name.endswith("rag=1.0 text=0.0 head=0.0"):
                print(L.fmt_row(name + " (baseline)", res))


def run_distract(ctx, encoders, extra, out, emb_dir, k):
    """Out-of-catalogue text rows (congeners) as extra classes that mean 'not identified'."""
    print(L.HEADER)
    for enc in encoders:
        X, K = L.reference_set(ctx, enc, extra=extra)
        uniq = np.array(sorted(set(K)))
        pos = {s: j for j, s in enumerate(uniq)}
        G, GK = L.prototypes(X, K, k)
        tz = np.load(emb_dir / f"text_{enc}.npz")
        idx = {kk: i for i, kk in enumerate(tz["keys"])}
        Tm = tz["sci"][[idx[kk] for kk in uniq]]
        td = np.load(emb_dir / f"text_dist_{enc}.npz")["sci"]
        vi = [i for i, r in enumerate(ctx.inat_rows) if r["split"] == "val"]
        ti = [i for i, r in enumerate(ctx.inat_rows) if r["split"] == "test"]
        E, XI = ctx.get(enc, "inat"), ctx.get(enc, "indep")
        rho = 0.84
        for mu in (4.0,):
            for dmu in (None, 0.0, 0.5, 1.0, 1.5):  # distractor weight multiplier relative to mu
                def sc(q, mu=mu, dmu=dmu):
                    S = L.score_max(q, G, GK, uniq) + mu * (q @ Tm.T)
                    if dmu is None:
                        return S
                    return np.concatenate([S, rho + mu * dmu * (q @ td.T)], 1)
                parts = {"val": sc(E[vi]), "test": sc(E[ti]), "indep": sc(XI)}
                rows = {"val": [ctx.inat_rows[i] for i in vi], "test": [ctx.inat_rows[i] for i in ti], "indep": ctx.indep_rows}
                sp = {n: L.make_split(rows[n], parts[n], pos) for n in parts}
                rej = None if dmu is None else np.r_[np.zeros(len(uniq), bool), np.ones(len(td), bool)]
                res = L.evaluate(sp["val"], {"test": sp["test"], "indep": sp["indep"]}, reject=rej)
                name = f"{enc} mu={mu} " + ("no distractors" if dmu is None else f"distractors x{dmu}")
                print(L.fmt_row(name, res), flush=True)
                for n in ("test", "indep"):
                    print(f"    {n}: negatives core {res[n]['core_neg']:.3f} tent {res[n]['tent_neg']:.3f}")
                out[name] = res


def run_refs(ctx, encoders, out):
    print(L.HEADER)
    for enc in encoders:
        for cap in (10, 25, 50, 100, 150, 250, None):
            for k in (6, 12):
                X, K = L.reference_set(ctx, enc, extra=True, cap=cap)
                uniq = np.array(sorted(set(K)))
                G, GK = L.prototypes(X, K, k)
                val, others = splits_for(ctx, enc, G, GK, uniq)
                res = L.evaluate(val, others)
                print(L.fmt_row(f"{enc} <= {cap or 'all'} refs/sp proto{k}", res, f"{len(X)} refs, idx {len(G) * X.shape[1] * 2 / 1e6:.2f} MB"), flush=True)
                out[f"{enc}|{cap}|{k}"] = {**res, "refs": len(X)}


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--emb", type=Path, required=True)
    ap.add_argument("--inat", type=Path, required=True)
    ap.add_argument("--indep", type=Path, required=True)
    ap.add_argument("--ext", type=Path)
    ap.add_argument("--exp", required=True, choices=["gallery", "head", "tta", "text", "refs", "combo", "distract"])
    ap.add_argument("--encoders", default="bioclip")
    ap.add_argument("--use-ext", action="store_true")
    ap.add_argument("--protos", type=int, default=6)
    ap.add_argument("--variants", default="_flip", help="comma list of embedding variants for tta")
    ap.add_argument("--out", type=Path, required=True)
    a = ap.parse_args()
    encs = a.encoders.split(",")
    variants = tuple([""] + [v for v in a.variants.split(",") if v])
    ctx = load_ctx(a, encs, variants)
    out: dict = {}
    if a.exp == "gallery":
        run_gallery(ctx, encs, a.use_ext, out)
    elif a.exp == "text":
        run_text(ctx, encs, a.use_ext, out, a.emb)
    elif a.exp == "distract":
        run_distract(ctx, encs, a.use_ext, out, a.emb, a.protos)
    elif a.exp == "combo":
        run_combo(ctx, encs, a.use_ext, out, a.emb, a.protos)
    elif a.exp == "refs":
        run_refs(ctx, encs, out)
    elif a.exp == "head":
        run_head(ctx, encs, a.use_ext, out)
    else:
        run_tta(ctx, encs, a.use_ext, out, variants)
    a.out.parent.mkdir(parents=True, exist_ok=True)
    a.out.write_text(json.dumps(out, indent=1), encoding="utf-8")


if __name__ == "__main__":
    main()
