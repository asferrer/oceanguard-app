"""Stand-alone check of a species pack with ONNX Runtime on CPU (numpy + Pillow + onnxruntime only).

Meant to run in a clean venv with the same ONNX Runtime as the app (``onnxruntime==1.20.1``):

1. loads ``species_pack.json``, creates the session, runs the self-test pattern and compares the embedding with the
   ``selfTest.expected`` vector of the pack (the app refuses the pack below ``minCosine``);
2. embeds the photos of a labelled set with the app chain (centre square, bilinear 224, pack mean/std);
3. scores them as the app would: per species ``max`` cosine over the image rows, plus ``textWeight`` times the cosine
   with the species' ``text`` row when the pack declares ``scoring``; softmax at the pack temperature; decisions with
   the pack thresholds;
4. prints top-1 / top-5 / macro, coverage and precision per decision and the decisions on the negatives, and the
   CPU latency (p50 of single-photo runs).

Sets: ``--rows`` jsonl with ``species_key``, ``role`` and ``path``/``file`` relative to ``--base``; ``--split`` keeps one
split of ``splits.jsonl``. ``--limit N`` takes a random subset (seeded).
"""

from __future__ import annotations

import argparse
import collections
import json
import struct
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import numpy as np
import onnxruntime as ort
from PIL import Image


def read_spex(path: Path):
    d = path.read_bytes()
    magic, ver, n, dim = struct.unpack_from("<IIII", d, 0)
    assert magic == 0x53504558 and ver == 1
    body = np.frombuffer(d, dtype="<f2", count=n * dim, offset=32).astype(np.float32).reshape(n, dim)
    meta = [json.loads(x) for x in d[32 + n * dim * 2:].decode("utf-8").strip().splitlines()]
    return body, meta


def prep(path: Path, size: int, mean, std) -> np.ndarray:
    im = Image.open(path).convert("RGB")
    w, h = im.size
    m = min(w, h)
    x, y = (w - m) // 2, (h - m) // 2
    im = im.crop((x, y, x + m, y + m)).resize((size, size), Image.BILINEAR)
    a = (np.asarray(im, np.float32) / 255.0 - np.array(mean, np.float32)) / np.array(std, np.float32)
    return a.transpose(2, 0, 1)


def self_test(size, mean, std) -> np.ndarray:
    m = size - 1
    yy, xx = np.mgrid[0:size, 0:size]
    rgb = np.stack([xx * 255 // m, yy * 255 // m, (xx + yy) * 255 // (2 * m)]).astype(np.float32) / 255.0
    rgb = (rgb - np.array(mean, np.float32)[:, None, None]) / np.array(std, np.float32)[:, None, None]
    return rgb[None].astype(np.float32)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--pack", type=Path, required=True)
    ap.add_argument("--rows", type=Path, required=True)
    ap.add_argument("--base", type=Path, required=True)
    ap.add_argument("--split")
    ap.add_argument("--limit", type=int)
    ap.add_argument("--threads", type=int, default=8)
    ap.add_argument("--out", type=Path)
    a = ap.parse_args()
    man = json.loads((a.pack / "species_pack.json").read_text(encoding="utf-8"))
    e, cal, sc = man["encoder"], man["calibration"], man.get("scoring")
    so = ort.SessionOptions()
    so.intra_op_num_threads = a.threads
    sess = ort.InferenceSession(str(a.pack / man["encoderFile"]), so, providers=["CPUExecutionProvider"])
    print("onnxruntime", ort.__version__, "providers", sess.get_providers())
    t = sess.run([e["outputName"]], {e["inputName"]: self_test(e["size"], e["mean"], e["std"])})[0][0]
    t = t / np.linalg.norm(t)
    exp = np.array(man["selfTest"]["expected"], np.float32)
    exp = exp / np.linalg.norm(exp)
    cos = float(t @ exp)
    print(f"self-test cosine {cos:.6f} (minimum {man['selfTest']['minCosine']}) -> {'OK' if cos >= man['selfTest']['minCosine'] else 'REFUSED'}")

    P, meta = read_spex(a.pack / man["indexFile"])
    tag = (sc or {}).get("textViewTag", "text")
    is_text = np.array([m["viewTag"] == tag for m in meta]) if sc else np.zeros(len(meta), bool)
    keys = np.array([m["speciesKey"] for m in meta])
    species = np.array(sorted(set(keys)))
    rho = (sc or {}).get("missingImageScore", 0.0)

    rows = [json.loads(x) for x in a.rows.read_text(encoding="utf-8").splitlines() if x.strip()]
    if a.split:
        rows = [r for r in rows if r.get("split") == a.split]
    # species merged in the pack (same animal under two names): drop the duplicate rows
    rows = [r for r in rows if r["species_key"] not in man.get("merged", {})]
    if a.limit and len(rows) > a.limit:
        rows = [rows[i] for i in sorted(np.random.default_rng(0).choice(len(rows), a.limit, replace=False))]
    files = [a.base / (r.get("path") or r["file"]) for r in rows]
    X = []
    with ThreadPoolExecutor(4) as pool:
        for i in range(0, len(files), 16):
            batch = np.stack(list(pool.map(lambda p: prep(p, e["size"], e["mean"], e["std"]), files[i:i + 16])))
            X.append(sess.run([e["outputName"]], {e["inputName"]: batch})[0])
            if (i // 16) % 25 == 0:
                print(f"  {i + len(batch)}/{len(files)}", flush=True)
    q = np.concatenate(X)
    q = q / np.linalg.norm(q, axis=1, keepdims=True)
    lat = []
    for i in range(10):
        x1 = np.stack([prep(files[i], e["size"], e["mean"], e["std"])])
        t0 = time.time()
        sess.run([e["outputName"]], {e["inputName"]: x1})
        lat.append(time.time() - t0)
    sims = q @ P.T
    S = np.full((len(q), len(species)), rho, np.float32)
    for j, s in enumerate(species):
        m = (keys == s) & ~is_text
        if m.any():
            S[:, j] = sims[:, m].max(1)
    if sc:
        for j, s in enumerate(species):
            mt = (keys == s) & is_text
            if mt.any():
                S[:, j] += sc["textWeight"] * sims[:, mt].max(1)
    z = S / cal["temperature"]
    z -= z.max(1, keepdims=True)
    p = np.exp(z)
    p /= p.sum(1, keepdims=True)
    conf = p.max(1)
    lab = np.array([r["species_key"] for r in rows])
    neg = np.array([r["role"] == "negative" for r in rows])
    cat = ~neg
    order = np.argsort(-S, 1)[:, :5]
    top1 = species[order[:, 0]]
    ok = top1 == lab
    res = {"n_catalog": int(cat.sum()), "n_negative": int(neg.sum()), "top1": float(ok[cat].mean()),
           "top5": float(np.mean([l in species[o] for l, o in zip(lab[cat], order[cat])])),
           "macro_top1": float(np.mean([ok[cat & (lab == s)].mean() for s in sorted(set(lab[cat]))])),
           "latency_p50_ms": float(np.median(lat) * 1000), "self_test_cosine": cos}
    dec = np.where(conf >= cal["tauHigh"], "RAG_CORE", np.where(conf >= cal["tauLow"], "RAG_TENTATIVE", "UNIDENTIFIED"))
    res["decisions"] = {
        "catalog": {d: float(np.mean(dec[cat] == d)) for d in ("RAG_CORE", "RAG_TENTATIVE", "UNIDENTIFIED")},
        "negative": {d: float(np.mean(dec[neg] == d)) for d in ("RAG_CORE", "RAG_TENTATIVE", "UNIDENTIFIED")} if neg.any() else None,
        "precision": {d: float((ok & cat)[dec == d].sum() / max(1, (dec == d).sum())) for d in ("RAG_CORE", "RAG_TENTATIVE")}}
    conf_pairs = collections.Counter((x, y) for x, y in zip(lab[cat & ~ok], top1[cat & ~ok]))
    res["top_confusions"] = [[x, y, n] for (x, y), n in conf_pairs.most_common(10)]
    print(json.dumps({k: v for k, v in res.items() if k != "top_confusions"}, indent=1))
    if a.out:
        a.out.parent.mkdir(parents=True, exist_ok=True)
        a.out.write_text(json.dumps(res, indent=1), encoding="utf-8")


if __name__ == "__main__":
    main()
