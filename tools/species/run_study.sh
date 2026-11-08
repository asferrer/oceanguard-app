#!/usr/bin/env bash
# Full BioDex species study, end to end (artefacts outside the repository).
#   bash tools/species/run_study.sh C:/rerun_v9/runs/_species_2026-11-08
# Needs: the published v1 pack in $RUN/hf_pack (Hugging Face asferrer/oceanguard-biodex),
# Python with torch, open_clip, timm, transformers, onnxruntime. GPU steps start the lab GPU guard.
set -euo pipefail
RUN=${1:?run directory}
T=$(cd "$(dirname "$0")" && pwd)
PY="python -I"
DATA=$RUN/data/inat
CAT=$RUN/hf_pack/species_catalog_v1.json

$PY "$T/fetch_inat_dataset.py" --catalog "$CAT" --out "$DATA" --per-species 60 --negatives --per-negative 25
$PY "$T/make_splits.py" --data "$DATA"
$PY "$T/embed_all.py" --data "$DATA" --out "$RUN/emb" --pack "$RUN/hf_pack" \
    --encoders pack_onnx,openclip_b32,bioclip,dinov2_small,dinov2_base,siglip2_base,mnv3_large,effnet_lite0
$PY "$T/eval_rag.py" --data "$DATA" --emb "$RUN/emb" --out "$RUN/eval" \
    --encoders pack_onnx,openclip_b32,bioclip,dinov2_small,dinov2_base,siglip2_base,mnv3_large,effnet_lite0 \
    --published "$RUN/hf_pack/species_index_v1.bin"
for M in mobilenetv3_large_100.ra_in1k efficientnet_b0.ra_in1k; do
  $PY "$T/train_classifier.py" --data "$DATA" --out "$RUN/clf_${M%%.*}" --model "$M" --epochs 30
  $PY "$T/eval_classifier.py" --data "$DATA" --run "$RUN/clf_${M%%.*}"
done
