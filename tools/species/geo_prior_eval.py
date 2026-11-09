"""Geographic prior of the BioDex species search: what the app does now vs a soft prior.

Replicates, on the PC, the Kotlin code of the app (read-only reference):

* ``MarineRegionResolver``  : MEOW raster 0.25 deg + ring search (<= 2 deg) over land cells,
* ``GeoPrior.matchLevel`` / ``GeoPrior.g``,
* ``SpeciesReferenceIndex.search``: sort by ``cosine * g(level)``, hard filter, relaxation to a
  global search when the best filtered confidence is below tauLow, confidence = softmax of the
  RAW cosines, margin = cosine(top1) - cosine(top2) in the adjusted order,
* ``SpeciesConfidence.idSource``.

Methods compared on the photos that have coordinates (same subset for all):

1. ``no_geo``      cosine ranking, no location (what the previous sections measured).
2. ``app_noloc``   the app code without GPS (cosmopolitan species still get g = 0.1).
3. ``app_loc``     the app code with the real coordinates of the photo (BALANCED).
4. ``soft``        p ~ softmax(cos / T) * g(level), g chosen on val, T / tau refitted on val.
5. ``soft_nocos``  same with g = 1 for cosmopolitan species and no extra penalties (ablation).

Usage: see docs/SPECIES_ID.md section 9.
"""

from __future__ import annotations

import argparse
import itertools
import json
import struct
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from biodex_common import fit_temperature, read_spex, softmax_max, write_spex  # noqa: E402,F401
from eval_rag import kmeans, open_set  # noqa: E402

ECO, PROV, REALM, COSMO, GLOBAL = range(5)
APP_G = np.array([1.0, 0.6, 0.3, 0.1, 1.0])
RES = 0.25


# ---------------------------------------------------------------------------
# MarineRegionResolver (port)
# ---------------------------------------------------------------------------
class Resolver:
    def __init__(self, raster: Path, hierarchy: Path):
        b = raster.read_bytes()
        magic, ver, self.nlat, self.nlon = struct.unpack_from("<IIII", b, 0)
        assert magic == 0x4D455257 and ver == 1
        self.grid = np.frombuffer(b, dtype="<i2", count=self.nlat * self.nlon, offset=32).reshape(self.nlat, self.nlon)
        self.h = {e["ecoregionId"]: (e["provinceId"], e["realmId"]) for e in json.loads(hierarchy.read_text(encoding="utf-8"))}

    def cell(self, lat: float, lon: float) -> int:
        lat = min(90.0, max(-90.0, lat))
        row = min(max(int((90.0 - lat) / RES), 0), self.nlat - 1)
        n = ((lon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        col = min(max(int((n + 180.0) / RES), 0), self.nlon - 1)
        return int(self.grid[row, col])

    def ring(self, lat: float, lon: float, r: int) -> int:
        d = r * RES
        for rl in (lat + d, lat - d):
            x = lon - d
            while x <= lon + d:
                v = self.cell(rl, x)
                if v > 0:
                    return v
                x += RES
        for rlon in (lon + d, lon - d):
            y = lat - d + RES
            while y < lat + d:
                v = self.cell(y, rlon)
                if v > 0:
                    return v
                y += RES
        return 0

    def resolve(self, lat: float, lon: float):
        """-> (ecoregion, province, realm) or None, exactly as MarineRegionResolver.resolve."""
        v = self.cell(lat, lon)
        if v == -1:
            return None
        if v <= 0:
            v = 0
            for r in range(1, int(2.0 / RES) + 1):
                v = self.ring(lat, lon, r)
                if v > 0:
                    break
            if v <= 0:
                return None
        if v not in self.h:
            return None
        return (v, *self.h[v])


def match_levels(species: list[dict], region) -> np.ndarray:
    """GeoPrior.matchLevel for every species (list of catalogue entries) -> int codes."""
    out = np.empty(len(species), np.int8)
    for i, s in enumerate(species):
        eco = s.get("ecoregions") or []
        if s.get("cosmopolitan"):
            out[i] = COSMO
        elif region is None or not eco:
            out[i] = GLOBAL
        elif region[0] in eco:
            out[i] = ECO
        else:
            pr = [REG.h[e] for e in eco if e in REG.h]
            if region[1] in {p for p, _ in pr}:
                out[i] = PROV
            elif region[2] in {r for _, r in pr}:
                out[i] = REALM
            else:
                out[i] = GLOBAL  # app: "outside this realm" still gets g = 1.0
    return out


REG: Resolver  # set in main


# ---------------------------------------------------------------------------
# Scoring helpers
# ---------------------------------------------------------------------------
def topk_idx(score: np.ndarray, k: int = 5) -> np.ndarray:
    return np.argsort(-score, 1)[:, :k]


def softmax_rows(z: np.ndarray) -> np.ndarray:
    z = z - z.max(1, keepdims=True)
    p = np.exp(z)
    return p / p.sum(1, keepdims=True)


def app_search(sc: np.ndarray, lev: np.ndarray, has_region: np.ndarray, T: float, tau_low: float,
               sensitivity: str = "BALANCED"):
    """Literal SpeciesReferenceIndex.search. Returns (order [q,5], conf_top1 [q], margin [q])."""
    q, s = sc.shape
    g = APP_G[lev]
    adj = sc * g
    conf_raw = softmax_rows(sc / T)
    # hard filter
    if sensitivity == "STRICT":
        ok = (lev == ECO) | (lev == PROV) | (lev == COSMO) | (lev == GLOBAL)
    else:  # BALANCED: GLOBAL/COSMO always pass, ECO/PROV/REALM pass: nothing is excluded
        ok = np.ones_like(lev, bool)
    ok = np.where(has_region[:, None], ok, True)
    best_f = np.where(ok, conf_raw, 0).max(1)
    relax = has_region & ((~ok).all(1) | (best_f < tau_low))
    cand = np.where(ok, adj, -np.inf)
    cand = np.where(relax[:, None], sc, cand)  # relaxed: unfiltered, g = 1, level GLOBAL
    order = topk_idx(cand, 5)
    top1 = order[:, 0]
    ar = np.arange(q)
    margin = sc[ar, top1] - sc[ar, order[:, 1]]
    conf = conf_raw[ar, top1]
    return order, conf, margin, relax


def decide(conf, margin, tau_high, tau_low, delta=0.0):
    return np.where((conf >= tau_high) & (margin >= delta), 2, np.where(conf >= tau_low, 1, 0))  # 2 core, 1 tent


def summarise(name, order, dec, lab_idx, is_neg, extra=None, cosmo=None) -> dict:
    cat = ~is_neg
    ok = order[:, 0] == lab_idx
    res = {"method": name, "n_cat": int(cat.sum()), "n_neg": int(is_neg.sum()),
           "top1": float(ok[cat].mean()), "top5": float(np.mean([l in o for l, o in zip(lab_idx[cat], order[cat])]))}
    for code, nm in ((2, "core"), (1, "tent")):
        m = dec == code
        res[f"{nm}_cov"] = float(m[cat].mean())
        res[f"{nm}_prec"] = float((ok & cat)[m].sum() / max(1, m.sum()))
        res[f"{nm}_neg"] = float(m[is_neg].mean()) if is_neg.any() else None
    if cosmo is not None:
        res["top1_cosmo"] = float(ok[cat & cosmo].mean()) if (cat & cosmo).any() else None
        res["top1_other"] = float(ok[cat & ~cosmo].mean())
        res["n_cosmo"] = int((cat & cosmo).sum())
    res["neg_unid"] = float((dec[is_neg] == 0).mean()) if is_neg.any() else None
    if extra:
        res.update(extra)
    return res


def calibrate_conf(conf_fn, val_sc_args, lab_idx, is_neg, order_top1, prec_hi=0.90, prec_lo=0.80):
    """Lowest threshold with val selective precision >= target (wrong species or accepted negative = error)."""
    conf = conf_fn
    correct = (order_top1 == lab_idx) & ~is_neg

    def lowest(p):
        for t in np.linspace(0, 1, 1001):
            if open_set(conf, correct, is_neg, t)["selective_precision"] >= p:
                return round(float(t), 3)
        return 1.0
    return lowest(prec_hi), lowest(prec_lo)


# ---------------------------------------------------------------------------
# Data
# ---------------------------------------------------------------------------
def load_inat(inat: Path, emb: np.ndarray):
    rows = [json.loads(x) for x in (inat / "splits.jsonl").read_text(encoding="utf-8").splitlines()]
    pos = {}
    for fn in ("manifest.jsonl", "manifest_negatives.jsonl"):
        for x in (inat / fn).read_text(encoding="utf-8").splitlines():
            m = json.loads(x)
            pos[m["photo_id"]] = (m.get("lat"), m.get("lon"))
    for i, r in enumerate(rows):
        r["lat"], r["lon"] = pos.get(r["photo_id"], (None, None))
        r["i"] = i
    return rows, emb


def load_indep(indep: Path, emb: np.ndarray):
    rows = [json.loads(x) for x in (indep / "indep.jsonl").read_text(encoding="utf-8").splitlines()]
    coords = json.loads((indep / "coords.json").read_text(encoding="utf-8"))
    for i, r in enumerate(rows):
        r["lat"], r["lon"] = coords.get(r["path"], (None, None))
        r["split"] = "test"
        r["i"] = i
    return rows, emb


def build_gallery(emb, rows, licences):
    tr = [r for r in rows if r["split"] == "train" and r["role"] == "catalog" and (r.get("license") or "") in licences]
    keys = np.array([r["species_key"] for r in tr])
    e = emb[[r["i"] for r in tr]]
    vecs, ks = [], []
    for sp in sorted(set(keys)):
        c = kmeans(e[keys == sp], 6)
        vecs.append(c)
        ks += [sp] * len(c)
    g = np.concatenate(vecs).astype("<f2").astype(np.float32)
    return g, np.array(ks)


def species_scores(q, gal, gkeys, uniq):
    sims = q @ gal.T
    out = np.full((len(q), len(uniq)), -1.0, np.float32)
    for j, k in enumerate(uniq):
        out[:, j] = sims[:, gkeys == k].max(1)
    return out


def prepare(rows_sub, emb, gal, gkeys, uniq, catalog, T_dummy=None):
    """Scores, labels, level matrix and region flags for a list of rows."""
    sc = species_scores(emb[[r["i"] for r in rows_sub]], gal, gkeys, uniq)
    pos = {s: j for j, s in enumerate(uniq)}
    lab = np.array([pos.get(r["species_key"], -1) if r["role"] == "catalog" else -1 for r in rows_sub])
    neg = np.array([r["role"] == "negative" for r in rows_sub])
    ents = [catalog[s] for s in uniq]
    cache, lev, has = {}, np.zeros((len(rows_sub), len(uniq)), np.int8), np.zeros(len(rows_sub), bool)
    levels_no = match_levels(ents, None)
    for n, r in enumerate(rows_sub):
        reg = REG.resolve(r["lat"], r["lon"]) if r.get("lat") is not None else None
        has[n] = reg is not None
        key = reg
        if key not in cache:
            cache[key] = match_levels(ents, reg)
        lev[n] = cache[key]
    return dict(sc=sc, lab=lab, neg=neg, lev=lev, has=has, lev_no=np.tile(levels_no, (len(rows_sub), 1)))


# ---------------------------------------------------------------------------
# Methods
# ---------------------------------------------------------------------------
def soft_scores(sc, lev, T, w):
    """log-probability space: cos / T + log g(level); w = (prov, realm, out, cosmo)."""
    gv = np.array([1.0, w[0], w[1], w[3], w[2]])  # ECO, PROV, REALM, COSMO, GLOBAL(=out of range or no data)
    return sc / T + np.log(gv[lev])


def soft_eval_args(d, T, w, use_region=True):
    lev = d["lev"] if use_region else d["lev_no"]
    z = soft_scores(d["sc"], lev, T, w)
    p = softmax_rows(z)
    return p, z


def fit_soft(dv, grid):
    """Pick (w, T) on val by negative log-likelihood of the true species (catalogue photos with region)."""
    m = (~dv["neg"]) & dv["has"] & (dv["lab"] >= 0)
    best = None
    for w in grid:
        for T in np.geomspace(0.008, 0.08, 25):
            z = soft_scores(dv["sc"][m], dv["lev"][m], T, w)
            z = z - z.max(1, keepdims=True)
            ll = (z[np.arange(m.sum()), dv["lab"][m]] - np.log(np.exp(z).sum(1))).mean()
            if best is None or ll > best[0]:
                best = (ll, w, T)
    return best


def run_set(name, d, uniq, pack_cal, soft_w, soft_T, soft_tau, nogeo_cal, sens="BALANCED"):
    keep = d["has"]  # same subset for every method: photos whose coordinates resolve to a region
    sub = {k: v[keep] for k, v in d.items()}
    sc, lab, neg = sub["sc"], sub["lab"], sub["neg"]
    out = []
    ar = np.arange(len(lab))
    cosmo = sub["lev_no"][ar, np.maximum(lab, 0)] == COSMO
    # 1 no_geo with its own recalibration is the pack itself
    o = topk_idx(sc)
    T0, th0, tl0 = pack_cal
    conf = softmax_max(sc, T0)
    out.append(summarise("no_geo", o, decide(conf, np.zeros(len(sc)), th0, tl0), lab, neg, cosmo=cosmo))
    # 2/3 app code
    for mname, lev, has in (("app_noloc", sub["lev_no"], np.zeros(len(sc), bool)), ("app_loc", sub["lev"], sub["has"])):
        o, c, mg, relax = app_search(sc, lev, has, T0, tl0, sens)
        out.append(summarise(mname, o, decide(c, mg, th0, tl0), lab, neg,
                             {"relaxed": float(relax.mean())}, cosmo=cosmo))
    # 4 soft prior
    p, z = soft_eval_args(sub, soft_T, soft_w)
    o = topk_idx(z)
    out.append(summarise("soft", o, decide(p.max(1), np.zeros(len(sc)), *soft_tau), lab, neg, cosmo=cosmo))
    # 5 soft prior keeping the pack's own T and thresholds (minimal change for the app)
    p2, z2 = soft_eval_args(sub, T0, soft_w)
    out.append(summarise("soft_pack", topk_idx(z2), decide(p2.max(1), np.zeros(len(sc)), th0, tl0), lab, neg, cosmo=cosmo))
    return out, int(keep.sum())


def fmt(rs):
    print(f"{'method':<12}{'n':>6}{'top1':>7}{'top5':>7}{'core cov/prec':>16}{'tent cov/prec':>16}{'neg core/tent':>15}")
    for r in rs:
        print(f"{r['method']:<12}{r['n_cat']:>6}{r['top1']:7.3f}{r['top5']:7.3f}"
              f"{r['core_cov']:9.3f}/{r['core_prec']:.3f}{r['tent_cov']:9.3f}/{r['tent_prec']:.3f}"
              f"{(r['core_neg'] or 0):8.3f}/{(r['tent_neg'] or 0):.3f}"
              + (f"  relaxed={r['relaxed']:.2f}" if "relaxed" in r else "")
              + (f"  cosmo={r['top1_cosmo']:.3f}(n={r['n_cosmo']}) other={r['top1_other']:.3f}" if r.get("top1_cosmo") is not None else ""))


def main() -> None:
    global REG
    ap = argparse.ArgumentParser()
    ap.add_argument("--inat", type=Path, required=True)
    ap.add_argument("--inat-emb", type=Path, required=True)
    ap.add_argument("--indep", type=Path, required=True)
    ap.add_argument("--indep-emb", type=Path, required=True)
    ap.add_argument("--catalog", type=Path, required=True)
    ap.add_argument("--raster", type=Path, required=True)
    ap.add_argument("--hierarchy", type=Path, required=True)
    ap.add_argument("--pack", type=Path, help="pack with species_pack.json (T, tau) to use for the literal app")
    ap.add_argument("--out", type=Path, required=True)
    a = ap.parse_args()
    REG = Resolver(a.raster, a.hierarchy)
    catalog = {s["speciesKey"]: s for s in json.loads(a.catalog.read_text(encoding="utf-8"))["species"]}
    pk = json.loads((a.pack / "species_pack.json").read_text(encoding="utf-8"))["calibration"]
    pack_cal = (pk["temperature"], pk["tauHigh"], pk["tauLow"])

    inat_rows, inat_emb = load_inat(a.inat, np.load(a.inat_emb))
    indep_rows, indep_emb = load_indep(a.indep, np.load(a.indep_emb))
    gal, gkeys = build_gallery(inat_emb, inat_rows, {"cc0", "cc-by"})
    uniq = np.array(sorted(set(gkeys)))
    val = [r for r in inat_rows if r["split"] == "val"]
    tst = [r for r in inat_rows if r["split"] == "test"]
    dv = prepare(val, inat_emb, gal, gkeys, uniq, catalog)
    dt = prepare(tst, inat_emb, gal, gkeys, uniq, catalog)
    di = prepare(indep_rows, indep_emb, gal, gkeys, uniq, catalog)
    for nm, d in (("val", dv), ("test", dt), ("indep", di)):
        print(f"{nm}: {len(d['lab'])} photos, {int(d['has'].sum())} with a resolved region "
              f"({int(d['has'][~d['neg']].sum())} catalogue)")

    # distribution of match levels of the TRUE species in the test photos
    for nm, d in (("test", dt), ("indep", di)):
        m = d["has"] & ~d["neg"] & (d["lab"] >= 0)
        lv = d["lev"][m][np.arange(m.sum()), d["lab"][m]]
        print(nm, "level of the true species (eco, prov, realm, cosmo, out/global):",
              [round(float((lv == c).mean()), 3) for c in range(5)])

    # soft prior parameters chosen on val
    grid = [(p, r, o, c) for p, r, o, c in itertools.product(
        (0.5, 0.7, 0.85, 1.0), (0.2, 0.4, 0.6, 0.8, 1.0), (0.02, 0.05, 0.1, 0.2, 0.4, 1.0), (1.0,))]
    ll, w, T = fit_soft(dv, grid)
    print(f"soft prior chosen on val: g(prov, realm, out-of-range, cosmo) = {w}, T = {T:.4f}, val loglik {ll:.3f}")
    # recalibrate thresholds on val for the soft method
    keep = dv["has"]
    p, z = soft_eval_args({k: v[keep] for k, v in dv.items()}, T, w)
    o1 = z.argmax(1)
    tau = calibrate_conf(p.max(1), None, dv["lab"][keep], dv["neg"][keep], o1)
    print("soft thresholds on val (tauHigh, tauLow):", tau)

    res = {"soft": {"g_prov": w[0], "g_realm": w[1], "g_out": w[2], "g_cosmo": w[3], "T": T, "tau": tau}}
    for nm, d in (("val", dv), ("test", dt), ("indep", di)):
        rs, n = run_set(nm, d, uniq, pack_cal, w, T, tau, pack_cal)
        print(f"\n== {nm} (photos with region: {n})")
        fmt(rs)
        res[nm] = rs
    # sensitivity of the soft prior weights on val and test (top-1 only)
    tab = []
    for wi in grid:
        r = []
        for d in (dv, dt, di):
            m = d["has"] & ~d["neg"] & (d["lab"] >= 0)
            z = soft_scores(d["sc"][m], d["lev"][m], T, wi)
            r.append(float((z.argmax(1) == d["lab"][m]).mean()))
        tab.append((wi, r))
    tab.sort(key=lambda t: -t[1][0])
    print("\nbest g by val top-1 (g_prov, g_realm, g_out, g_cosmo) -> val / test / indep top-1")
    for wi, r in tab[:8]:
        print(wi, [round(x, 3) for x in r])
    res["grid_top"] = [[list(wi), r] for wi, r in tab[:30]]
    a.out.parent.mkdir(parents=True, exist_ok=True)
    a.out.write_text(json.dumps(res, indent=1), encoding="utf-8")


if __name__ == "__main__":
    main()
