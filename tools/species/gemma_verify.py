"""Can Gemma 4 E2B verify the RAG top-1 (yes / no) better than it chooses among 5?

Reads the candidates saved by ``gemma_rerank.py`` and asks, for a stratified
sample of test images (RAG top-1 right, RAG top-1 wrong, out-of-catalogue
negatives), "Is the main organism in this photo <scientific> (<common>)?".
A useful verifier says "yes" much more often when the top-1 is right than when
it is wrong or the photo is a negative. Prints the three "yes" rates.
"""

from __future__ import annotations

import argparse
import json
import random
import sys
from pathlib import Path

from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gpu_guard  # noqa: E402
from biodex_common import center_square  # noqa: E402
from gemma_rerank import MODEL  # noqa: E402

SYSTEM = "You are a marine biologist. You identify marine species from photos. Answer yes or no."


def main() -> None:
    import torch
    from transformers import AutoModelForImageTextToText, AutoProcessor
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", type=Path, required=True)
    ap.add_argument("--gemma", type=Path, required=True, help="gemma_<encoder>.jsonl")
    ap.add_argument("--catalog", type=Path, required=True)
    ap.add_argument("--n", type=int, default=300)
    ap.add_argument("--out", type=Path, required=True)
    args = ap.parse_args()
    rows = [json.loads(x) for x in args.gemma.read_text(encoding="utf-8").splitlines()]
    test = [r for r in rows if r["split"] == "test"]
    rng = random.Random(0)
    groups = {
        "top1_right": [r for r in test if r["role"] == "catalog" and r["cands"][0] == r["label"]],
        "top1_wrong": [r for r in test if r["role"] == "catalog" and r["cands"][0] != r["label"]],
        "negative": [r for r in test if r["role"] == "negative"],
    }
    cat = {s["speciesKey"]: s for s in json.loads(args.catalog.read_text(encoding="utf-8"))["species"]}
    guard = gpu_guard.start(args.out.parent / "gpu_guard_verify")
    try:
        proc = AutoProcessor.from_pretrained(MODEL)
        model = AutoModelForImageTextToText.from_pretrained(MODEL, device_map="cuda", dtype=torch.bfloat16).eval()
        res = {}
        for name, g in groups.items():
            sample = rng.sample(g, min(args.n, len(g)))
            yes = 0
            for r in sample:
                c = cat[r["cands"][0]]
                q = f"Is the main organism in this photo {c['scientificName']} ({c['commonNames'].get('en', '')})?"
                img = center_square(Image.open(args.data / r["file"]).convert("RGB")).resize((448, 448))
                msgs = [{"role": "system", "content": [{"type": "text", "text": SYSTEM}]},
                        {"role": "user", "content": [{"type": "image", "image": img}, {"type": "text", "text": q}]}]
                inp = proc.apply_chat_template(msgs, add_generation_prompt=True, tokenize=True,
                                               return_dict=True, return_tensors="pt").to("cuda")
                with torch.no_grad():
                    out = model.generate(**inp, max_new_tokens=3, do_sample=False)
                yes += proc.decode(out[0, inp["input_ids"].shape[1]:], skip_special_tokens=True).strip().lower().startswith("yes")
            res[name] = {"n": len(sample), "yes_rate": yes / max(1, len(sample))}
            print(name, res[name], flush=True)
        args.out.write_text(json.dumps(res, indent=2), encoding="utf-8")
    finally:
        guard.terminate()


if __name__ == "__main__":
    main()
