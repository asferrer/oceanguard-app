"""Halve an fp32 ONNX encoder keeping fp32 compute: weights stored as fp16 + a Cast(fp32) node per weight.

Runs on any ONNX Runtime execution provider (no fp16 kernels needed): the Cast of a constant is folded at
session load, so the file is ~50 % smaller and the in-memory model is fp32 again. Initialisers with fewer than
``--min-elems`` elements (biases, LayerNorm, positional tables of small size) stay fp32.

    python -I tools/species/onnx_fp16_storage.py in.onnx out.onnx
"""
from __future__ import annotations

import argparse

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper


def convert(src: str, dst: str, min_elems: int = 4096) -> int:
    m = onnx.load(src)
    g = m.graph
    new_inits, casts, rename = [], [], {}
    for init in g.initializer:
        if init.data_type != TensorProto.FLOAT or int(np.prod(init.dims)) < min_elems:
            new_inits.append(init)
            continue
        arr = numpy_helper.to_array(init)
        if np.abs(arr).max() > 6e4:
            new_inits.append(init)
            continue
        h = numpy_helper.from_array(arr.astype(np.float16), init.name + "__fp16")
        new_inits.append(h)
        rename[init.name] = init.name + "__f32"
        casts.append(helper.make_node("Cast", [h.name], [rename[init.name]], to=TensorProto.FLOAT,
                                      name="cast_" + init.name))
    del g.initializer[:]
    g.initializer.extend(new_inits)
    for node in g.node:
        for i, n in enumerate(node.input):
            if n in rename:
                node.input[i] = rename[n]
    nodes = list(g.node)
    del g.node[:]
    g.node.extend(casts + nodes)
    onnx.save(m, dst)
    return len(casts)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("src")
    ap.add_argument("dst")
    ap.add_argument("--min-elems", type=int, default=4096)
    a = ap.parse_args()
    print("converted initialisers:", convert(a.src, a.dst, a.min_elems))
