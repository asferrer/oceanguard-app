"""Build a BioDex species pack v2 (encoder ONNX + SPEX index + species_pack.json).

Steps:
1. Export the encoder to ONNX (opset 17, input ``pixel_values`` [b,3,S,S],
   output ``image_features`` [b,D]) and check torch/ONNX parity on real photos.
2. Embed the reference gallery (``train`` split of the catalogue species) with
   the exported ONNX on CPU in fp32, exactly as the app will.
3. Write the SPEX v1 index (one row per reference photo, or k-means prototypes).
4. Compute the self-test embedding of ``SelfTestPattern.GRADIENT_V1``.
5. Write ``species_pack.json`` with the encoder contract and the calibration
   passed on the command line (fitted on val by eval_rag.py / score_policies.py),
   plus ``reference_provenance.jsonl`` (licence and attribution of every
   reference photo whose embedding is in the index; the photos are not shipped).

Usage::

    python -I tools/species/build_pack.py --data <data/inat> --encoder bioclip \
        --catalog <hf_pack>/species_catalog_v1.json --out <pack dir> \
        --temperature 0.021 --tau-high 0.9 --tau-low 0.6 --gallery proto6
"""

from __future__ import annotations

import argparse
import json
import shutil
import sys
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))
from biodex_common import center_square, l2n, write_spex  # noqa: E402
from embed_all import SPECS, build_torch, to_tensor  # noqa: E402
from eval_rag import kmeans  # noqa: E402

MODEL_LICENSES = {
    "bioclip": "MIT (imageomics/bioclip)", "openclip_b32": "MIT (OpenCLIP, LAION-2B)",
    "dinov2_small": "Apache-2.0 (facebook/dinov2-small)", "dinov2_base": "Apache-2.0 (facebook/dinov2-base)",
    "siglip2_base": "Apache-2.0 (google/siglip2-base-patch16-224)",
    "mnv3_large": "Apache-2.0 (timm mobilenetv3_large_100)", "effnet_lite0": "Apache-2.0 (timm tf_efficientnet_lite0)",
}


def self_test_pattern(size: int, mean, std) -> np.ndarray:
    """Must match SelfTestPattern.kt (GRADIENT_V1)."""
    m = size - 1
    y, x = np.mgrid[0:size, 0:size]
    rgb = np.stack([x * 255 // m, y * 255 // m, (x + y) * 255 // (2 * m)]).astype(np.float32) / 255.0
    rgb = (rgb - np.array(mean, np.float32)[:, None, None]) / np.array(std, np.float32)[:, None, None]
    return rgb[None].astype(np.float32)


def export_onnx(name: str, path: Path, size: int) -> None:
    import torch
    model = build_torch(name).float().eval()
    dummy = torch.randn(1, 3, size, size)
    torch.onnx.export(model, (dummy,), str(path), input_names=["pixel_values"], output_names=["image_features"],
                      dynamic_axes={"pixel_values": {0: "b"}, "image_features": {0: "b"}}, opset_version=17,
                      dynamo=False)


def parity(name: str, sess, files: list[Path], size: int, norm) -> float:
    import torch
    model = build_torch(name).float().eval()
    x = np.stack([to_tensor(p, size, norm) for p in files])
    with torch.no_grad():
        ref = l2n(model(torch.from_numpy(x)).numpy())
    out = l2n(sess.run(["image_features"], {"pixel_values": x})[0])
    return float(np.min(np.sum(ref * out, axis=1)))


def embed_onnx(sess, files: list[Path], size: int, norm, bs: int = 32) -> np.ndarray:
    out = []
    for i in range(0, len(files), bs):
        x = np.stack([to_tensor(p, size, norm) for p in files[i:i + bs]])
        out.append(sess.run(["image_features"], {"pixel_values": x})[0])
    return l2n(np.concatenate(out)).astype(np.float32)


def main() -> None:
    import onnxruntime as ort
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", type=Path, required=True)
    ap.add_argument("--encoder", required=True, choices=sorted(MODEL_LICENSES))
    ap.add_argument("--catalog", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--gallery", default="all", choices=["all", "proto6"])
    ap.add_argument("--platt-a", type=float, default=0.0, help="unused when --temperature > 0")
    ap.add_argument("--platt-b", type=float, default=0.0)
    ap.add_argument("--tau-high", type=float, required=True)
    ap.add_argument("--tau-low", type=float, required=True)
    ap.add_argument("--delta", type=float, default=0.0)
    ap.add_argument("--temperature", type=float, required=True, help="softmax temperature (val fit)")
    ap.add_argument("--vlm-topk", type=int, default=0, help="Gemma choice; 0 = off (measured not to help)")
    ap.add_argument("--version", type=int, default=2)
    ap.add_argument("--int8", action="store_true", help="ship the dynamic-int8 encoder (4x smaller)")
    args = ap.parse_args()

    size, norm = SPECS[args.encoder]
    args.out.mkdir(parents=True, exist_ok=True)
    enc_file = f"biodex_{args.encoder}_v{args.version}.onnx"
    idx_file = f"species_index_v{args.version}.bin"
    export_onnx(args.encoder, args.out / enc_file, size)
    sess = ort.InferenceSession(str(args.out / enc_file), providers=["CPUExecutionProvider"])

    rows = [json.loads(x) for x in (args.data / "splits.jsonl").read_text(encoding="utf-8").splitlines()]
    gal = [r for r in rows if r["split"] == "train" and r["role"] == "catalog"]
    files = [args.data / r["file"] for r in gal]
    print(f"torch/ONNX parity (min cosine on 8 photos): {parity(args.encoder, sess, files[:8], size, norm):.6f}")
    if args.int8:  # dynamic int8 weights; the fp32 export is removed from the pack
        from onnxruntime.quantization import QuantType, quantize_dynamic
        q_file = f"biodex_{args.encoder}_int8_v{args.version}.onnx"
        quantize_dynamic(str(args.out / enc_file), str(args.out / q_file), weight_type=QuantType.QInt8,
                         per_channel=True)
        del sess
        (args.out / enc_file).unlink()
        enc_file = q_file
        sess = ort.InferenceSession(str(args.out / enc_file), providers=["CPUExecutionProvider"])
    emb = embed_onnx(sess, files, size, norm)
    cat = {s["speciesKey"]: s for s in json.loads(args.catalog.read_text(encoding="utf-8"))["species"]}
    keys = np.array([r["species_key"] for r in gal])
    vecs, meta = [], []
    for sp in sorted(set(keys)):
        e = emb[keys == sp]
        e = kmeans(e, 6) if args.gallery == "proto6" else e
        vecs.append(e)
        meta += [{"speciesKey": sp, "scientificName": cat[sp]["scientificName"], "viewTag": args.gallery,
                  "nRefs": int((keys == sp).sum())}] * len(e)
    write_spex(args.out / idx_file, np.concatenate(vecs), meta)

    pattern = self_test_pattern(size, *norm)
    expected = l2n(sess.run(["image_features"], {"pixel_values": pattern})[0])[0]
    shutil.copy2(args.catalog, args.out / "species_catalog_v1.json")
    manifest = {
        "version": args.version, "encoderFile": enc_file, "indexFile": idx_file,
        "catalogFile": "species_catalog_v1.json",
        "encoder": {"inputName": "pixel_values", "outputName": "image_features", "size": size,
                    "mean": list(norm[0]), "std": list(norm[1]), "dim": int(emb.shape[1])},
        "calibration": {"plattA": args.platt_a, "plattB": args.platt_b, "tauHigh": args.tau_high,
                        "tauLow": args.tau_low, "delta": args.delta,
                        "temperature": args.temperature},
        "vlmChoiceTopK": args.vlm_topk,
        "selfTest": {"pattern": "gradient_v1", "expected": [round(float(v), 6) for v in expected], "minCosine": 0.99},
        "encoderLicense": MODEL_LICENSES[args.encoder],
        "referenceImages": {"source": "iNaturalist research-grade", "licenses": "CC0 / CC-BY / CC-BY-SA",
                            "count": len(gal), "species": len(set(keys)),
                            "note": "only embeddings are shipped; attribution in reference_provenance.jsonl"},
    }
    (args.out / "species_pack.json").write_text(json.dumps(manifest, indent=1), encoding="utf-8")
    with (args.out / "reference_provenance.jsonl").open("w", encoding="utf-8") as f:
        for r in gal:
            f.write(json.dumps({k: r.get(k) for k in ("species_key", "observation_id", "photo_id",
                                                      "license", "attribution")},
                               ensure_ascii=False) + "\n")
    for p in sorted(args.out.iterdir()):
        print(f"{p.name:<36} {p.stat().st_size / 1e6:8.2f} MB")


if __name__ == "__main__":
    main()
