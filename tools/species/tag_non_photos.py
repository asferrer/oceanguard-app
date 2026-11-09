"""Second, semantic not-a-photo filter for the independent set (zero-shot CLIP).

``filter_indep.py`` removes what colour statistics can catch. Drawings kept
there, museum specimens and maps still need a semantic check, so every kept
image is tagged with a zero-shot OpenCLIP ViT-B/32 (LAION-2B, CPU) choice among
fixed prompts: ``photo_ok`` is true when the best prompt describes a real
photograph of an organism (alive, dead on a dock or in the hand, underwater or
on the shore) and false for drawings, diagrams, maps, text/labels, museum
specimens in jars or display cases and people. Nothing is deleted: the
evaluation reports the whole set and the ``photo_ok`` subset. The prompts are
the contract; ``indep.jsonl`` gets ``photo_ok`` and ``photo_label`` fields.
"""

from __future__ import annotations

import argparse
import collections
import json
from pathlib import Path

import numpy as np
import open_clip
import torch
from PIL import Image

OK = ["a photograph of a live animal in its natural habitat", "an underwater photograph of marine life",
      "a photograph of a marine animal at the sea or on the shore", "a close-up photograph of a marine organism",
      "a photograph of a fish or seafood caught by a fisher", "a photograph of a seaweed or marine plant",
      "a photograph of a sea creature held in a hand"]
NOT = ["a drawing or illustration", "a scientific diagram, chart or map", "a museum specimen in a jar or on display",
       "a photograph of a shell or skeleton on a table", "a photograph of people", "a page of text or a label",
       "a microscope image", "a logo or a sign", "a screenshot"]


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--indep", type=Path, required=True)
    args = ap.parse_args()
    model, _, pre = open_clip.create_model_and_transforms("ViT-B-32", pretrained="laion2b_s34b_b79k")
    tok = open_clip.get_tokenizer("ViT-B-32")
    model.eval()
    prompts = OK + NOT
    with torch.no_grad():
        t = model.encode_text(tok(prompts))
        t = (t / t.norm(dim=-1, keepdim=True)).numpy()
    rows = [json.loads(x) for x in (args.indep / "indep.jsonl").read_text(encoding="utf-8").splitlines()]
    for i in range(0, len(rows), 64):
        batch = rows[i:i + 64]
        x = torch.stack([pre(Image.open(args.indep / r["path"]).convert("RGB")) for r in batch])
        with torch.no_grad():
            f = model.encode_image(x)
            f = (f / f.norm(dim=-1, keepdim=True)).numpy()
        for r, s in zip(batch, f @ t.T):
            j = int(np.argmax(s))
            r["photo_ok"] = j < len(OK)
            r["photo_label"] = prompts[j]
    with (args.indep / "indep.jsonl").open("w", encoding="utf-8") as fh:
        for r in rows:
            fh.write(json.dumps(r, ensure_ascii=False) + "\n")
    print(collections.Counter(r["photo_label"] for r in rows).most_common())
    print("photo_ok:", sum(r["photo_ok"] for r in rows), "of", len(rows))


if __name__ == "__main__":
    main()
