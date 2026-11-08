"""Fine-tune a light ImageNet backbone as a closed-set species classifier.

Alternative (b) of the species study. Trains on the ``train`` split only,
selects the epoch by ``val`` top-1 and reports ``test`` top-1 / top-5 and the
open-set behaviour of the max-softmax score (threshold fitted on val, see
``eval_rag.py``). Also saves the pre-logit embeddings of every image so the
same network can be evaluated as a kNN (RAG) embedder.

Runs under the lab GPU guard. Usage::

    python -I tools/species/train_classifier.py --data <data/inat> --out <run dir> \
        --model mobilenetv3_large_100.ra_in1k --epochs 30
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

import numpy as np
import torch
import torch.nn as nn
from PIL import Image
from torch.utils.data import DataLoader, Dataset
from torchvision import transforms as T

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gpu_guard  # noqa: E402
from biodex_common import center_square  # noqa: E402

IMNET = ([0.485, 0.456, 0.406], [0.229, 0.224, 0.225])


class Images(Dataset):
    def __init__(self, root: Path, rows: list[dict], cls: dict, tf):
        self.root, self.rows, self.cls, self.tf = root, rows, cls, tf

    def __len__(self):
        return len(self.rows)

    def __getitem__(self, i):
        r = self.rows[i]
        img = Image.open(self.root / r["file"]).convert("RGB")
        return self.tf(img), self.cls.get(r["species_key"], -1)


def eval_tf(size: int):
    # App parity: centre-square crop then bilinear resize.
    return T.Compose([T.Lambda(center_square), T.Resize((size, size), T.InterpolationMode.BILINEAR),
                      T.ToTensor(), T.Normalize(*IMNET)])


def train_tf(size: int):
    return T.Compose([T.RandomResizedCrop(size, scale=(0.35, 1.0)), T.RandomHorizontalFlip(),
                      T.ColorJitter(0.3, 0.3, 0.3, 0.05), T.ToTensor(), T.Normalize(*IMNET)])


@torch.no_grad()
def predict(model, loader):
    model.eval()
    logits, feats = [], []
    for x, _ in loader:
        with torch.autocast("cuda", dtype=torch.bfloat16):
            f = model.forward_head(model.forward_features(x.cuda()), pre_logits=True)
            logits.append(model.get_classifier()(f).float().cpu())
        feats.append(f.float().cpu())
    return torch.cat(logits).numpy(), torch.cat(feats).numpy()


def main() -> None:
    import timm
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--model", default="mobilenetv3_large_100.ra_in1k")
    ap.add_argument("--size", type=int, default=224)
    ap.add_argument("--epochs", type=int, default=30)
    ap.add_argument("--bs", type=int, default=64)
    ap.add_argument("--lr", type=float, default=1e-3)
    ap.add_argument("--workers", type=int, default=6)
    args = ap.parse_args()
    torch.manual_seed(0)
    args.out.mkdir(parents=True, exist_ok=True)
    guard = gpu_guard.start(args.out / "gpu_guard")

    rows = [json.loads(x) for x in (args.data / "splits.jsonl").read_text(encoding="utf-8").splitlines()]
    rows = [r for r in rows if r["split"] != "dropped_dup"]
    species = sorted({r["species_key"] for r in rows if r["role"] == "catalog"})
    cls = {s: i for i, s in enumerate(species)}
    tr = [r for r in rows if r["split"] == "train"]
    dl = lambda rs, tf, sh: DataLoader(Images(args.data, rs, cls, tf), batch_size=args.bs, shuffle=sh,
                                       num_workers=args.workers, persistent_workers=True, drop_last=sh)
    train_dl = dl(tr, train_tf(args.size), True)
    all_dl = dl(rows, eval_tf(args.size), False)
    val_idx = np.array([i for i, r in enumerate(rows) if r["split"] == "val" and r["role"] == "catalog"])
    val_lab = np.array([cls[rows[i]["species_key"]] for i in val_idx])
    val_dl = dl([rows[i] for i in val_idx], eval_tf(args.size), False)

    model = timm.create_model(args.model, pretrained=True, num_classes=len(species), drop_rate=0.2).cuda()
    opt = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=0.05)
    steps = args.epochs * len(train_dl)
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=args.lr, total_steps=steps, pct_start=0.1)
    crit = nn.CrossEntropyLoss(label_smoothing=0.1)
    best, log = -1.0, []
    try:
        for ep in range(args.epochs):
            model.train()
            t0, tot = time.time(), 0.0
            for x, y in train_dl:
                with torch.autocast("cuda", dtype=torch.bfloat16):
                    loss = crit(model(x.cuda()), y.cuda())
                opt.zero_grad(set_to_none=True)
                loss.backward()
                opt.step()
                sched.step()
                tot += loss.item()
            lv, _ = predict(model, val_dl)
            acc = float(np.mean(lv.argmax(1) == val_lab))
            log.append({"epoch": ep, "loss": tot / len(train_dl), "val_top1": acc, "sec": time.time() - t0})
            print(json.dumps(log[-1]), flush=True)
            if acc > best:
                best = acc
                torch.save({"model": args.model, "species": species, "state_dict": model.state_dict(),
                            "size": args.size, "norm": IMNET}, args.out / "best.pt")
        ck = torch.load(args.out / "best.pt", map_location="cuda")
        model.load_state_dict(ck["state_dict"])
        logits, feats = predict(model, all_dl)
        np.save(args.out / "logits.npy", logits)
        np.save(args.out / "feats.npy", feats / np.linalg.norm(feats, axis=1, keepdims=True))
        (args.out / "train_log.json").write_text(json.dumps({"args": {k: str(v) for k, v in vars(args).items()},
                                                             "best_val_top1": best, "log": log}, indent=2))
    finally:
        guard.terminate()


if __name__ == "__main__":
    main()
