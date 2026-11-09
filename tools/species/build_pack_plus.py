"""Build the candidate species pack of the precision study (BioCLIP 2 + image prototypes + text rows).

Differences with ``build_pack.py``:

* any encoder of ``embed_all.SPECS`` (here ``bioclip2``, ViT-L/14) and optional fp16 *storage* of the weights
  (``onnx_fp16_storage.py``: fp16 initialisers + Cast, fp32 compute, runs on every execution provider);
* reference embeddings come from precomputed fp32 GPU embeddings (``embed_set.py``; torch vs ONNX parity is
  checked on ``--parity-n`` photos) so 40 000 photos do not need a CPU pass of a 300 M parameter encoder;
* extra iNaturalist references (``fetch_inat_more.py`` + ``dedupe_ext.py``), species merges
  (``--merge hypselodoris_picta=felimare_picta``);
* *text rows*: one row per catalogue species (BioCLIP text tower, prompt "a photo of <Genus species>") with
  ``viewTag`` ``"text"``. ``species_pack.json`` gets ``scoring`` = {imageWeight, textWeight, textViewTag,
  missingImageScore}: score(species) = max cosine over its image rows + textWeight * cosine(query, text row).
  An app that ignores ``scoring`` treats the text rows as ordinary prototypes (max) and still works as RAG;
* temperature and thresholds are calibrated on val for the combined score (same criterion as before).
"""

from __future__ import annotations

import argparse
import json
import shutil
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import precision_lab as L  # noqa: E402
from biodex_common import l2n, read_spex, write_spex  # noqa: E402
from build_pack import MODEL_LICENSES, export_onnx, self_test_pattern  # noqa: E402
from embed_all import SPECS  # noqa: E402


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--encoder", default="bioclip2")
    ap.add_argument("--emb", type=Path, required=True, help="dir with inat_<enc>.npy, ext_<enc>.npy, text_<enc>.npz")
    ap.add_argument("--inat", type=Path, required=True)
    ap.add_argument("--ext", type=Path, help="ext_rows.jsonl from dedupe_ext.py")
    ap.add_argument("--catalog", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--protos", type=int, default=6)
    ap.add_argument("--text-weight", type=float, default=4.0, help="0 = no text rows")
    ap.add_argument("--licenses", default="cc0,cc-by")
    ap.add_argument("--merge", default="hypselodoris_picta=felimare_picta")
    ap.add_argument("--w16", action="store_true", help="fp16 weight storage (fp32 compute)")
    ap.add_argument("--version", type=int, default=3)
    ap.add_argument("--precision-high", type=float, default=0.90)
    ap.add_argument("--precision-low", type=float, default=0.80)
    a = ap.parse_args()

    enc = a.encoder
    size, norm = SPECS[enc]
    merge = dict(x.split("=") for x in a.merge.split(",") if x)
    lic = {x.strip() for x in a.licenses.split(",") if x.strip()}
    rows = L.load_jsonl(a.inat / "splits.jsonl")
    for r in rows:
        if r["species_key"] in merge:  # same animal under two names: identical observations, keep one copy
            r["split"] = "dropped_dup"
    E = np.load(a.emb / f"inat_{enc}.npy")
    ext_rows, E_ext = [], None
    if a.ext:
        ext_rows = [r for r in L.load_jsonl(a.ext) if r["species_key"] not in merge]
        keep_idx = {(r["observation_id"], r["photo_id"]) for r in ext_rows}
        all_ext = L.load_jsonl(a.ext)
        E_ext = np.load(a.emb / f"ext_{enc}.npy")
        sel = [i for i, r in enumerate(all_ext) if (r["observation_id"], r["photo_id"]) in keep_idx
               and r["species_key"] not in merge]
        E_ext = E_ext[sel]
    cat = {s["speciesKey"]: s for s in json.loads(a.catalog.read_text(encoding="utf-8"))["species"]}
    species = sorted(k for k in cat if k not in merge)
    pos = {s: j for j, s in enumerate(species)}

    # --- references (train observers, publishable licences) ---
    ref_idx = [i for i, r in enumerate(rows) if r["split"] == "train" and r["role"] == "catalog"
               and (r.get("license") or "") in lic]
    X = [E[ref_idx]]
    K = [r["species_key"] for r in (rows[i] for i in ref_idx)]
    prov = [rows[i] for i in ref_idx]
    if E_ext is not None:
        X.append(E_ext)
        K += [r["species_key"] for r in ext_rows]
        prov += ext_rows
    X, K = np.concatenate(X), np.array(K)
    protos, pkeys = L.prototypes(X, K, a.protos)
    have_img = set(pkeys)

    # --- text rows ---
    use_text = a.text_weight > 0
    Tm = None
    if use_text:
        tz = np.load(a.emb / f"text_{enc}.npz")
        ti = {k: i for i, k in enumerate(tz["keys"])}
        Tm = tz["sci"][[ti[k] for k in species]]

    def img_scores(q: np.ndarray, rho: float) -> np.ndarray:
        S = np.full((len(q), len(species)), rho, np.float32)
        sims = q @ protos.T
        for j, sp in enumerate(species):
            m = pkeys == sp
            if m.any():
                S[:, j] = sims[:, m].max(1)
        return S

    def scores(q: np.ndarray, rho: float) -> np.ndarray:
        return img_scores(q, rho) + (a.text_weight * (q @ Tm.T) if use_text else 0.0)

    # --- calibration on val ---
    val_i = [i for i, r in enumerate(rows) if r["split"] == "val"]
    test_i = [i for i, r in enumerate(rows) if r["split"] == "test"]
    Sv_img = img_scores(E[val_i], 0.0)
    corr_img = np.array([Sv_img[n, pos[rows[i]["species_key"]]] for n, i in enumerate(val_i)
                         if rows[i]["role"] == "catalog" and rows[i]["species_key"] in have_img])
    rho = float(np.percentile(corr_img, 10))  # image term given to species without any reference photo
    val = L.make_split([rows[i] for i in val_i], scores(E[val_i], rho), pos)
    test = L.make_split([rows[i] for i in test_i], scores(E[test_i], rho), pos)
    res = L.evaluate(val, {"test": test})
    T, th, tl = res["T"], res["tauHigh"], res["tauLow"]
    print(f"calibration: T={T} tauHigh={th} tauLow={tl} missingImageScore={rho:.3f}; "
          f"val top-1 {res['val_top1']:.3f}, test top-1 {res['test']['top1']:.3f} top-5 {res['test']['top5']:.3f}")

    # --- files ---
    a.out.mkdir(parents=True, exist_ok=True)
    meta = [{"speciesKey": k, "scientificName": cat[k]["scientificName"], "viewTag": f"proto{a.protos}",
             "nRefs": int((K == k).sum())} for k in pkeys]
    vecs = [protos]
    if use_text:
        vecs.append(Tm)
        meta += [{"speciesKey": k, "scientificName": cat[k]["scientificName"], "viewTag": "text", "nRefs": 0}
                 for k in species]
    idx_file = f"species_index_v{a.version}.bin"
    write_spex(a.out / idx_file, np.concatenate(vecs), meta)
    enc_file = f"biodex_{enc}_v{a.version}.onnx"
    tmp = a.out / "_fp32.onnx"
    export_onnx(enc, tmp, size)
    import onnxruntime as ort
    if a.w16:
        from onnx_fp16_storage import convert
        print("fp16 storage of", convert(str(tmp), str(a.out / enc_file)), "initialisers")
        tmp.unlink()
    else:
        tmp.replace(a.out / enc_file)
    sess = ort.InferenceSession(str(a.out / enc_file), providers=["CPUExecutionProvider"])
    # parity torch (fp32 GPU embeddings used for the index) vs the shipped ONNX on CPU
    from embed_all import to_tensor
    pi = ref_idx[:: max(1, len(ref_idx) // 24)][:24]
    xin = np.stack([to_tensor(a.inat / rows[i]["file"], size, norm) for i in pi])
    out = l2n(sess.run(["image_features"], {"pixel_values": xin})[0])
    parity = float(np.min(np.sum(out * E[pi], axis=1)))
    print(f"torch(GPU) vs shipped ONNX(CPU) min cosine on {len(pi)} photos: {parity:.6f}")
    pattern = self_test_pattern(size, *norm)
    expected = l2n(sess.run(["image_features"], {"pixel_values": pattern})[0])[0]
    shutil.copy2(a.catalog, a.out / "species_catalog_v1.json")
    manifest = {
        "version": a.version, "encoderFile": enc_file, "indexFile": idx_file, "catalogFile": "species_catalog_v1.json",
        "encoder": {"inputName": "pixel_values", "outputName": "image_features", "size": size,
                    "mean": list(norm[0]), "std": list(norm[1]), "dim": int(X.shape[1])},
        "calibration": {"plattA": 0.0, "plattB": 0.0, "tauHigh": th, "tauLow": tl, "delta": 0.0, "temperature": T},
        "scoring": ({"imageWeight": 1.0, "textWeight": a.text_weight, "textViewTag": "text",
                     "missingImageScore": round(rho, 4)} if use_text else None),
        "vlmChoiceTopK": 0,
        "selfTest": {"pattern": "gradient_v1", "expected": [round(float(v), 6) for v in expected], "minCosine": 0.99},
        "encoderLicense": MODEL_LICENSES[enc],
        "referenceImages": {"source": "iNaturalist research-grade", "licenses": " / ".join(sorted(x.upper() for x in lic)),
                            "count": int(len(K)), "species": int(len(have_img)),
                            "note": "only embeddings are shipped; attribution in reference_provenance.jsonl"},
        "merged": merge,
    }
    if not use_text:
        manifest.pop("scoring")
    (a.out / "species_pack.json").write_text(json.dumps(manifest, indent=1), encoding="utf-8")
    with (a.out / "reference_provenance.jsonl").open("w", encoding="utf-8") as f:
        for r in prov:
            f.write(json.dumps({k: r.get(k) for k in ("species_key", "observation_id", "photo_id", "license", "attribution")},
                               ensure_ascii=False) + "\n")
    (a.out / "build_report.json").write_text(json.dumps({"calibration": res, "parity_min_cos": parity,
                                                         "species": len(species), "species_with_images": len(have_img)},
                                                        indent=1), encoding="utf-8")
    for p in sorted(a.out.iterdir()):
        print(f"{p.name:<36} {p.stat().st_size / 1e6:9.2f} MB")


if __name__ == "__main__":
    main()
