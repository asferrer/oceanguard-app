"""Check a built species pack end to end on the test split, as the app decides.

Reads ``species_pack.json``, runs its ONNX encoder (CPU, fp32 host) with the
manifest contract, searches the SPEX index (max prototype cosine per species),
computes the softmax confidence at the manifest temperature and applies the app
rule: >= tauHigh -> RAG_CORE, >= tauLow -> RAG_TENTATIVE, else UNIDENTIFIED.
Also re-runs the encoder self-test pattern against ``selfTest.expected``.
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
from build_pack import embed_onnx, self_test_pattern  # noqa: E402


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--pack", type=Path, required=True)
    ap.add_argument("--data", type=Path, required=True)
    ap.add_argument("--split", default="test")
    args = ap.parse_args()
    m = json.loads((args.pack / "species_pack.json").read_text(encoding="utf-8"))
    enc, cal = m["encoder"], m["calibration"]
    sess = ort.InferenceSession(str(args.pack / m["encoderFile"]), providers=["CPUExecutionProvider"])
    st = l2n(sess.run([enc["outputName"]], {enc["inputName"]: self_test_pattern(enc["size"], enc["mean"], enc["std"])})[0])[0]
    print(f"self-test cosine vs manifest: {float(np.dot(st, l2n(np.array(m['selfTest']['expected'])))):.6f}")

    protos, meta = read_spex(args.pack / m["indexFile"])
    keys = np.array([x["speciesKey"] for x in meta])
    uniq = np.array(sorted(set(keys)))
    rows = [json.loads(x) for x in (args.data / "splits.jsonl").read_text(encoding="utf-8").splitlines()]
    rows = [r for r in rows if r["split"] == args.split]
    q = embed_onnx(sess, [args.data / r["file"] for r in rows], enc["size"], (enc["mean"], enc["std"]))
    sc = species_scores(q, protos, keys, uniq)
    top1 = uniq[sc.argmax(1)]
    conf = softmax_max(sc, cal["temperature"])
    dec = np.where(conf >= cal["tauHigh"], "RAG_CORE", np.where(conf >= cal["tauLow"], "RAG_TENTATIVE", "UNIDENTIFIED"))
    lab = np.array([r["species_key"] for r in rows])
    neg = np.array([r["role"] == "negative" for r in rows])
    cat = ~neg
    print(f"{args.split}: {cat.sum()} catalogue photos, {neg.sum()} out-of-catalogue")
    print(f"top-1 {np.mean(top1[cat] == lab[cat]):.3f}  top-5 "
          f"{np.mean([l in uniq[np.argsort(-s)[:5]] for l, s in zip(lab[cat], sc[cat])]):.3f}")
    for name, mask in (("catalogue", cat), ("out-of-catalogue", neg)):
        c = collections.Counter(dec[mask])
        print(f"  {name:<17}", {k: round(v / mask.sum(), 3) for k, v in sorted(c.items())})
    for d in ("RAG_CORE", "RAG_TENTATIVE"):
        sel = dec == d
        right = (top1 == lab) & cat
        print(f"  precision of {d}: {right[sel].sum() / max(1, sel.sum()):.3f} (n={sel.sum()})")
    print("most frequent answers:", collections.Counter(top1[dec != "UNIDENTIFIED"]).most_common(3))


if __name__ == "__main__":
    main()
