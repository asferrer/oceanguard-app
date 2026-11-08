"""CPU latency and size of ONNX encoders (PC proxy; the S22 figure comes from logcat).

ONNX Runtime CPU EP, batch 1, ``--threads`` intra-op threads (the app uses 4),
5 warm-up runs then ``--runs`` timed runs; prints p50 / p95 in ms and the file
size. The on-device number is logged by the app itself (``SpeciesIdentifier``
stage ``encode``, p50 over the session) and is the one to report for the S22.

Usage::

    python -I tools/species/bench_latency.py model_a.onnx model_b.onnx --threads 4
"""

from __future__ import annotations

import argparse
import platform
import time
from pathlib import Path

import numpy as np
import onnxruntime as ort


def bench(path: Path, threads: int, runs: int) -> tuple[float, float]:
    so = ort.SessionOptions()
    so.intra_op_num_threads = threads
    so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    sess = ort.InferenceSession(str(path), so, providers=["CPUExecutionProvider"])
    inp = sess.get_inputs()[0]
    size = inp.shape[2] if isinstance(inp.shape[2], int) else 224
    x = np.random.default_rng(0).standard_normal((1, 3, size, size)).astype(np.float32)
    for _ in range(5):
        sess.run(None, {inp.name: x})
    t = []
    for _ in range(runs):
        t0 = time.perf_counter()
        sess.run(None, {inp.name: x})
        t.append((time.perf_counter() - t0) * 1000)
    return float(np.percentile(t, 50)), float(np.percentile(t, 95))


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("models", nargs="+", type=Path)
    ap.add_argument("--threads", type=int, default=4)
    ap.add_argument("--runs", type=int, default=50)
    args = ap.parse_args()
    print(f"CPU: {platform.processor()} | ORT {ort.__version__} | threads={args.threads}")
    for m in args.models:
        p50, p95 = bench(m, args.threads, args.runs)
        print(f"{m.name:<40} {m.stat().st_size / 1e6:8.1f} MB  p50 {p50:7.1f} ms  p95 {p95:7.1f} ms")


if __name__ == "__main__":
    main()
