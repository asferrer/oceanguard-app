"""Gemma 4 E2B as the final judge of the visual-RAG candidates.

For every val/test image, the kNN gallery of one encoder gives the top-k
species; Gemma 4 E2B (same base model the app runs with LiteRT-LM; here the
bf16 ``google/gemma-4-E2B-it`` weights through transformers, Apache-2.0)
sees the photo and the numbered candidate list and answers a number, or 0 for
"none of these / cannot tell". The prompt is the one in
``SpeciesDescriber.buildChoicePrompt`` (keep both in sync).

Saves ``<out>/gemma_<encoder>.jsonl`` (one line per image with the candidates,
the raw answer and the parsed choice) so the decision policies can be scored
offline by ``score_policies.py``.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import time
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gpu_guard  # noqa: E402
from biodex_common import center_square  # noqa: E402
from biodex_common import fit_temperature, softmax_max  # noqa: E402
from eval_rag import build_gallery, score  # noqa: E402

MODEL = "google/gemma-4-E2B-it"
SYSTEM = ("You are a marine biologist. You identify marine species from photos. "
          "Answer with a single number and nothing else.")


def choice_prompt(cands: list[tuple[str, str]]) -> str:
    """Must match SpeciesDescriber.buildChoicePrompt in the app."""
    lines = [f"{i + 1}. {sci} ({common})" if common else f"{i + 1}. {sci}"
             for i, (sci, common) in enumerate(cands)]
    return ("Which of these species is the main organism in the photo?\n" + "\n".join(lines) +
            "\n0. None of them, or the species cannot be told from the photo.\n"
            "Answer with the number only.")


def parse_choice(text: str, k: int) -> int:
    """1..k = pick, 0 = none, -1 = unparseable (the app then keeps the RAG top-1).

    Must match SpeciesDescriber.parseChoice.
    """
    m = re.search(r"\d+", text)
    if not m:
        return -1
    v = int(m.group())
    return v if 0 <= v <= k else -1


def main() -> None:
    import torch
    from transformers import AutoModelForImageTextToText, AutoProcessor

    ap = argparse.ArgumentParser()
    ap.add_argument("--data", type=Path, required=True)
    ap.add_argument("--emb", type=Path, required=True)
    ap.add_argument("--encoder", required=True)
    ap.add_argument("--catalog", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--k", type=int, default=5)
    ap.add_argument("--gallery", default="proto6", choices=["all", "proto6"])
    ap.add_argument("--image-size", type=int, default=448)
    ap.add_argument("--splits", default="val,test")
    ap.add_argument("--limit", type=int, default=0)
    args = ap.parse_args()

    rows = [json.loads(x) for x in (args.data / "splits.jsonl").read_text(encoding="utf-8").splitlines()]
    keep = [i for i, r in enumerate(rows) if r["split"] != "dropped_dup"]
    rows = [rows[i] for i in keep]
    emb = np.load(args.emb / f"{args.encoder}.npy")[keep]
    cat = {s["speciesKey"]: s for s in json.loads(args.catalog.read_text(encoding="utf-8"))["species"]}
    species = np.array(sorted({r["species_key"] for r in rows if r["role"] == "catalog"}))
    gal, gkeys = build_gallery(emb, rows, args.gallery)
    idx = [i for i, r in enumerate(rows) if r["split"] in args.splits.split(",")]
    if args.limit:
        idx = idx[:: max(1, len(idx) // args.limit)][: args.limit]
    sc = score(emb[idx], gal, gkeys, species)
    # Confidence = softmax over species scores, temperature fitted on val catalogue images.
    vi = [n for n, i in enumerate(idx) if rows[i]["split"] == "val" and rows[i]["role"] == "catalog"]
    pos = {k: j for j, k in enumerate(species)}
    temp = fit_temperature(sc[vi], np.array([pos[rows[idx[n]]["species_key"]] for n in vi]))
    conf = softmax_max(sc, temp)
    print(f"softmax temperature {temp:.4f}", flush=True)

    args.out.mkdir(parents=True, exist_ok=True)
    guard = gpu_guard.start(args.out / "gpu_guard")
    out_path = args.out / f"gemma_{args.encoder}.jsonl"
    done = set()
    if out_path.exists():
        done = {json.loads(x)["file"] for x in out_path.read_text(encoding="utf-8").splitlines()}
    try:
        proc = AutoProcessor.from_pretrained(MODEL)
        model = AutoModelForImageTextToText.from_pretrained(MODEL, device_map="cuda", dtype=torch.bfloat16).eval()
        with out_path.open("a", encoding="utf-8") as f:
            for n, (i, s, c) in enumerate(zip(idx, sc, conf)):
                r = rows[i]
                if r["file"] in done:
                    continue
                order = np.argsort(-s)[: args.k]
                keys = [str(species[j]) for j in order]
                cands = [(cat[k]["scientificName"], cat[k]["commonNames"].get("en", "")) for k in keys]
                img = center_square(Image.open(args.data / r["file"]).convert("RGB"))
                img = img.resize((args.image_size, args.image_size), Image.BILINEAR)
                msgs = [{"role": "system", "content": [{"type": "text", "text": SYSTEM}]},
                        {"role": "user", "content": [{"type": "image", "image": img},
                                                     {"type": "text", "text": choice_prompt(cands)}]}]
                inp = proc.apply_chat_template(msgs, add_generation_prompt=True, tokenize=True,
                                               return_dict=True, return_tensors="pt").to("cuda")
                t0 = time.time()
                with torch.no_grad():
                    gen = model.generate(**inp, max_new_tokens=6, do_sample=False)
                txt = proc.decode(gen[0, inp["input_ids"].shape[1]:], skip_special_tokens=True)
                ch = parse_choice(txt, args.k)
                f.write(json.dumps({"file": r["file"], "split": r["split"], "role": r["role"],
                                    "label": r["species_key"], "cands": keys,
                                    "scores": [float(s[j]) for j in order], "conf": float(c),
                                    "temperature": temp, "raw": txt.strip(),
                                    "choice": ch, "sec": time.time() - t0}) + "\n")
                if n % 100 == 0:
                    f.flush()
                    print(f"{n}/{len(idx)} {txt.strip()!r}", flush=True)
    finally:
        guard.terminate()


if __name__ == "__main__":
    main()
