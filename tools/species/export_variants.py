"""Export candidate encoders to ONNX fp32 and int8 (dynamic) and embed the split with each.

The int8 variant uses ``onnxruntime.quantization.quantize_dynamic`` (weights
int8 per channel, activations fp32), which ONNX Runtime Mobile runs on CPU. The
embeddings of both variants are written as ``<emb>/<encoder>_onnx_fp32.npy`` and
``..._onnx_int8.npy`` (CPU, app-parity preprocessing) so ``eval_rag.py`` measures
what quantisation costs in retrieval, and ``bench_latency.py`` can time them.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from build_pack import embed_onnx, export_onnx  # noqa: E402
from embed_all import SPECS  # noqa: E402


def main() -> None:
    import onnxruntime as ort
    from onnxruntime.quantization import QuantType, quantize_dynamic
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", type=Path, required=True)
    ap.add_argument("--encoders", required=True)
    ap.add_argument("--out", type=Path, required=True, help="directory for the .onnx files")
    ap.add_argument("--emb", type=Path, required=True)
    ap.add_argument("--no-embed", action="store_true")
    args = ap.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    rows = [json.loads(x) for x in (args.data / "splits.jsonl").read_text(encoding="utf-8").splitlines()]
    files = [args.data / r["file"] for r in rows]
    for name in args.encoders.split(","):
        size, norm = SPECS[name]
        fp32 = args.out / f"{name}_fp32.onnx"
        int8 = args.out / f"{name}_int8.onnx"
        if not fp32.exists():
            export_onnx(name, fp32, size)
        if not int8.exists():
            quantize_dynamic(str(fp32), str(int8), weight_type=QuantType.QInt8, per_channel=True)
        print(f"{name}: fp32 {fp32.stat().st_size / 1e6:.1f} MB, int8 {int8.stat().st_size / 1e6:.1f} MB", flush=True)
        if args.no_embed:
            continue
        for tag, path in (("fp32", fp32), ("int8", int8)):
            sess = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
            np.save(args.emb / f"{name}_onnx_{tag}.npy", embed_onnx(sess, files, size, norm))
            print(f"  embedded with {tag}", flush=True)


if __name__ == "__main__":
    main()
