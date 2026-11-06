# OceanGuard AI — Model Conversion

Scripts for converting Gemma 4 E2B (base and finetuned) to `.litertlm` format
for use with `LiteRTTextEngine` on Android.

## Prerequisites

```bash
pip install transformers peft torch accelerate safetensors pyyaml
# MediaPipe (Plan A only — Linux/macOS with GPU):
pip install mediapipe
```

---

## Finetuned LiteRT-LM build (OceanGuard)

Target: `gemma-4-E2B-it-oceanguard.litertlm` (~2.6 GB)
Adapter: `exp11g` — mAP@0.5 on TEST-200 = 0.2941 vs 0.1381 base (x2.13); VAL-100 0.2820 vs 0.1081.
  LoRA on the language tower, trained on real data plus 5,000 synthetic images (group-aware partition).
The adapter is not published yet (TODO: add the Hugging Face repository once released); set `--adapter-path` to your copy.

### Plan A — Direct LoRA conversion via MediaPipe (try first)

**Prerequisite**: MediaPipe `tasks.python.genai.converter` with Gemma 4 support.
As of May 2026, MediaPipe 0.10.x only defines `GEMMA_2B`/`GEMMA_4B` identifiers
for the Gemma **3n** family. Gemma 4 is architecturally different (MatMul-Free MLP,
interleaved local/global attention). Plan A is likely to fail with an unsupported
model type error. Attempt it only if you have a newer MediaPipe that explicitly
lists Gemma 4 in its changelog.

```bash
cd conversion/

# Step 1 — Convert base model (downloads ~5 GB, ~2-4h with GPU)
python convert_base_model.py

# Step 2 — Convert LoRA adapter on top of base (~15-45 min with GPU)
python convert_lora_adapter.py \
    --adapter-path <ADAPTER_DIR>

# Step 3 — Validate
python validate_converted_model.py \
    --model outputs/gemma-4-E2B-it-oceanguard.litertlm
```

Expected output: `conversion/outputs/gemma-4-E2B-it-oceanguard.litertlm`
Expected time (with GPU): 2-4h total

---

### Plan B — Merge PEFT + convert (fallback, more compatible)

Use when Plan A fails. Merges the LoRA weights into the base model weights,
then converts the standalone merged model through the standard base pipeline.
The resulting `.litertlm` is self-contained (no runtime LoRA loading needed).

```bash
# Step 1 — Merge adapter into base (run from repo root, ~20-40 min with GPU)
# Requires HF token for google/gemma-4-E2B-it (gated model):
#   huggingface-cli login   OR   export HF_TOKEN=hf_...
python conversion/merge_and_convert.py \
    --adapter-path <ADAPTER_DIR> \
    --base-model-id google/gemma-4-E2B-it \
    --output-dir conversion/outputs/gemma-4-E2B-it-oceanguard-merged \
    --dtype float16

# Step 2 — Convert merged model to .litertlm (~2-4h with GPU)
cd conversion/
python convert_base_model.py \
    --model-path outputs/gemma-4-E2B-it-oceanguard-merged

# Step 3 — Validate
python validate_converted_model.py \
    --model outputs/gemma-4-E2B-it-oceanguard.litertlm
```

Expected output: `conversion/outputs/gemma-4-E2B-it-oceanguard.litertlm`
Expected time (with GPU): 30-60 min merge + 2-4h conversion = ~3-5h total
Expected time (CPU-only): 2-4h merge + 8-12h conversion = 10-16h total

---

## Publishing the finetuned model

Nothing is uploaded from this repository. The app downloads the finetuned builds at run time from the URLs declared in
`VlmModelManager.kt`; while `FINETUNED_LITERTLM_PUBLISHED = false` the app does not offer the `.litertlm` variant.
TODO: document the final repository once the author publishes the adapter and the GGUF.

---

## Base model only (no finetuning)

```bash
cd conversion/
python download_base_model.py   # optional pre-download
python convert_base_model.py    # converts google/gemma-4-E2B-it → .litertlm
```

---

## config.yaml summary

| Key | Value |
|-----|-------|
| `base_model.model_id` | `google/gemma-4-E2B-it` |
| `base_model.variant` | `E2B` |
| `lora_adapter.adapter_path` | `./adapter` (local copy of the adapter) |
| `lora_adapter.output_file` | `./outputs/gemma-4-E2B-it-oceanguard.litertlm` |
| `lora_adapter.rank` | `16` |
| `lora_adapter.alpha` | `32` |
