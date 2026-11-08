# Species identification (BioDex)

How the app names the marine organism in a photo, why version 1.1.1 answered "Common octopus" for every photo, what
was measured to choose the replacement and how to reproduce it. Numbers in this page come from the scripts in
`tools/species/` (outputs listed at the end); nothing is estimated.

## 1. Root cause of "always Common octopus" (v1.1.1)

Two defects, both in the app wiring, not in a model:

1. **Silent demo fallback.** `OceanGuardApp.buildSpeciesIdentifier()` used `SampleSpeciesData` whenever the species
   pack (`models/species/`) was not complete. Its embedder ignores the photo: it returns the unit vector
   `abs(bitmap.hashCode()) % 4`, which matches one of four hard-coded prototypes (clownfish, loggerhead turtle,
   common octopus, moon jellyfish) with cosine 1.0, so the result is always `RAG_CORE` with a calibrated
   confidence of 99.8 %. On ART the identity hash comes from a linear congruential generator
   (`art/runtime/mirror/object.cc`, `seed * 1103515245 + 12345`) whose two low bits just count the hashes created in
   the process, so the "species" depends on how many objects were hashed before, never on the image. A flow that
   repeats the same steps between captures keeps landing on the same one of the four species.
2. **Identifier frozen at start-up.** The identifier was a `by lazy` resolved in `MainActivity.onCreate`. Downloading
   the pack afterwards did not replace the demo pipeline until the process was killed.

`SpeciesDemoRootCauseTest` reproduces both points on the JVM: the demo embedder never touches the bitmap
(`verifyNoInteractions`), any input yields one of the four species as `RAG_CORE` above 99 %, the ART hash generator
advances by +1 mod 4 per generated hash, and the new provider returns no identifier (instead of the demo) when the pack
is missing or broken.

When the published pack **is** loaded, the pipeline is not collapsed: `tools/species/diagnose_pack.py` runs the
exact app chain on the PC (centre-square crop, bilinear 224, CLIP normalisation, ONNX, max-prototype cosine, Platt)
and gives different species for different photos (49.2 % top-1 on the test split; the 3 541 test photos get 200 distinct answers,
the most frequent one 59 times and *Octopus vulgaris* 14 times; section 3). Two further problems of the v1 pack
were found on the way and are fixed by the v2 tooling:

* **Uncalibrated thresholds.** The Platt parameters were placeholders (A = 10, B = -3.5): with the v1 encoder the
  top-1 cosine of almost any photo maps above the "identified" threshold, so the app never answered "not identified".
* **Label noise in the v1 reference photos.** The v1 downloader queried iNaturalist with `taxon_name=` and
  `order_by=votes`, which is not an exact filter. Replaying that query for 20 random catalogue species returned
  88 of 900 observations (9.8 %) from another genus, concentrated in a few species (*Cerithium vulgatum* 45/45,
  *Conger conger* 37/45; for *Mola mola*, with the licence filter, 104 of the first 200 results are *Liquidambar styraciflua* and 25 are
  *Mola mola*). The new downloader
  resolves the exact `taxon_id` first.

## 2. Evaluation data

* **Source.** iNaturalist research-grade observations of the 203 catalogue species, downloaded with
  `fetch_inat_dataset.py` through the official API v1, one photo per observation, photo licence CC0, CC-BY or
  CC-BY-SA only (CC-BY-NC and "all rights reserved" excluded). 9 874 photos of 200 species (three species have no
  iNaturalist taxon match: *Sepia elegans*, *Eledone moschata*, *Cystoseira amentacea*); licences 8 175 CC-BY,
  1 154 CC0, 545 CC-BY-SA. Out-of-catalogue negatives: 723 photos of 29 marine taxa from other oceans (25 each).
  No CleanSea or JAMSTEC image is used anywhere.
* **Split by observer** (`make_splits.py`): every iNaturalist user goes to a single split for all species (salted
  hash), so photos of the same dive, animal or camera never cross splits; then any val/test photo within 4 bits of
  a train photo in a 64-bit dHash is dropped (69 removed). Result: train 5 442, val 1 136, test 3 236 catalogue
  photos; negatives val 409, test 305; 0 observers shared between splits. 198 species have test photos; 2 of them
  have no train photo, so they cannot be retrieved and count as errors.
* **Metrics.** Top-1 / top-5 over test catalogue photos, macro top-1 (mean of per-species top-1). Open set: the
  confidence threshold is fitted on **val** as the lowest one reaching a target selective precision (an accepted
  out-of-catalogue photo or a wrong species is an error) and then reported on **test**: coverage (share of
  catalogue photos that get a species), precision of the answers, share of negatives rejected.
* **Preprocessing** is the app's: centre-square crop of the photo, bilinear 224 x 224, per-encoder mean/std.

## 3. Results (test split)

Alternatives: (a) the published v1 pack (its own index, fine-tuned OpenCLIP ViT-B/32); (b) light classifiers
trained ad hoc on the train split (timm ImageNet weights, 30 epochs, label smoothing, best val epoch); (c) visual
RAG with zero-shot encoders, gallery = train photos as k-means prototypes (6 per species, the SPEX format), and Gemma
4 E2B as optional judge.

| Alternative | Top-1 | Top-5 | Macro top-1 | Encoder size | CPU p50 (PC) |
|---|---|---|---|---|---|
| (a) v1 pack, published index | 0.492 | 0.770 | 0.510 | 351.8 MB | 25.3 ms |
| (a) v1 encoder, index rebuilt from the clean train split | 0.512 | 0.782 | 0.497 | 351.8 MB | 25.3 ms |
| (b) MobileNetV3-Large classifier | 0.540 | 0.761 | 0.524 | 17.2 MB (checkpoint) | n/m |
| (b) EfficientNet-B0 classifier | 0.561 | 0.785 | 0.544 | 16.5 MB (checkpoint) | n/m |
| (c) OpenCLIP ViT-B/32 LAION-2B | 0.399 | 0.681 | 0.387 | n/m | n/m |
| (c) SigLIP 2 base | 0.526 | 0.813 | 0.507 | n/m | n/m |
| (c) MobileNetV3-Large ImageNet | 0.437 | 0.698 | 0.425 | n/m | n/m |
| (c) EfficientNet-Lite0 ImageNet | 0.332 | 0.602 | 0.320 | n/m | n/m |
| (c) DINOv2-small fp32 | 0.592 | 0.824 | 0.568 | 88.4 MB | 55.5 ms |
| (c) DINOv2-small int8 | 0.586 | 0.824 | 0.562 | 24.4 MB | 40.0 ms |
| (c) DINOv2-base fp32 | 0.656 | 0.874 | 0.636 | 346.5 MB | 158.9 ms |
| (c) DINOv2-base int8 | 0.274 | 0.527 | 0.267 | 90.9 MB | n/m |
| **(c) BioCLIP ViT-B/16 fp32 (chosen)** | **0.643** | **0.863** | **0.623** | 345.1 MB | 94.7 ms |
| (c) BioCLIP ViT-B/16 int8 | 0.611 | 0.838 | 0.594 | 88.0 MB | 34.6 ms |
| (c) BioCLIP fp32 + Gemma 4 E2B choosing among the top 5 | 0.275 | n/a | n/a | + Gemma | 0.23 s / photo (RTX 5090) |

n/m = not measured. The CPU column is ONNX Runtime on the PC (Intel, 4 threads, batch 1, `bench_latency.py`); it is
a relative proxy, not the S22 figure (section 6). The classifiers used as kNN embedders give 0.532 (MobileNetV3) and
0.557 (EfficientNet-B0). Rows from torch (bf16) and from the exported ONNX (fp32 CPU) agree to the third decimal
(BioCLIP 0.644 / 0.643, DINOv2-small 0.592 / 0.592).

**Gemma 4 E2B does not help.** With the photo (448 px) and the numbered top-5 of BioCLIP, Gemma picked the right
species only 31.7 % of the times it was among the five (2 797 test photos; chance is 20 %), against 64.4 % for the
RAG top-1, and answered "none" for only 11.1 % of the out-of-catalogue photos. As a yes/no verifier of the RAG top-1
("Is the main organism in this photo X?", 300 photos per group) it said "yes" to 2.3 % of correct top-1, 0.3 % of
wrong ones and 1.7 % of negatives. No threshold policy that involves Gemma reached the val precision target with more
correct answers than the RAG alone (`score_policies.py`). Gemma stays in the app for the debris flow and the
organism locator; its candidate choice is implemented but disabled (`vlmChoiceTopK = 0`).

**Confidence signal.** The raw top-1 cosine (the v1 input of Platt) separates right from wrong answers poorly
(test AUROC 0.586 for BioCLIP); the softmax over the per-species cosines at a temperature fitted on val does better
(AUROC 0.810), as does the top-1/top-2 margin (0.797). The app now uses the softmax.

**Open set with the chosen pack** (`verify_pack.py` on the built pack, temperature 0.02111, tauHigh 0.937 = val
precision >= 0.90, tauLow 0.841 = val precision >= 0.80):

| Decision | Catalogue photos | Out-of-catalogue photos | Precision |
|---|---|---|---|
| `RAG_CORE` (species) | 28.2 % | 8.2 % | 0.941 |
| `RAG_TENTATIVE` ("possible", badge) | 8.5 % | 8.2 % | 0.729 |
| `UNIDENTIFIED` (closest candidates shown as hints) | 63.3 % | 83.6 % | n/a |

The v1 app with its placeholder thresholds answered a species for 100 % of the test photos, catalogue or not, with
44.9 % precision. The trade-off is explicit: the new pack names a species for a third of the photos and is right
about 9 out of 10 times when it does; for the rest it says it does not know and lists the closest candidates (the
right one is in the top 5 for 86.3 % of catalogue photos). A lower precision target gives more answers.

Per species (BioCLIP fp32): 59 of 198 test species have top-1 >= 0.8 and 10 have 0 (`perclass_*.csv`).

## 4. Decision

Visual RAG with **BioCLIP ViT-B/16 (zero-shot, MIT)**, 6 prototypes per species, softmax confidence, calibrated
thresholds and an honest "not identified" answer:

* **Accuracy.** Best top-1 among the deployable options except DINOv2-base (0.656 vs 0.643, within the noise of 3 236
  photos), but BioCLIP is clearly better calibrated (with the threshold for val precision 0.80 it answers 37 % of the
  test catalogue photos, DINOv2-base 1 %) and its int8 version keeps working (DINOv2-base int8 collapses to 0.274). +15 points top-1 over
  the published v1 pack and ahead of the ad hoc classifiers (+8 points over EfficientNet-B0): with ~27 training
  photos per species, training a model from ImageNet weights does not reach a biology-pretrained encoder.
* **Adding species without retraining.** A new species only needs reference photos: `build_pack.py` embeds them
  and rewrites the index. No classifier head to retrain.
* **Size.** Downloaded on demand, not in the APK: 345 MB (fp32), about the size of the current v1 encoder
  (352 MB), plus a 1.3 MB index. The int8 variant (88 MB, top-1 0.604 on the built pack) is built too, for a
  smaller download at -4 points.
* **Maintenance.** Same ONNX Runtime path the app already used; the encoder contract, thresholds and a numeric
  self-test travel in `species_pack.json`, so a new encoder needs no app release.
* **Gemma 4 E2B** is not used for the decision (section 3).

## 5. What changed in the app

* No demo fallback: `SampleSpeciesData` moved to the test sources. Without a pack the species screen shows
  "download the species pack".
* `SpeciesPipelineProvider` builds the identifier from the installed pack and rebuilds it when the files change.
* `species_pack.json` (`SpeciesPackConfig`): encoder file, input name/size/mean/std, output name/dim, index and
  catalogue files, calibration (softmax temperature, tauHigh, tauLow, delta), Gemma choice top-k, self-test.
  Without it, the published v1 layout is assumed with thresholds calibrated on the same val split
  (`LegacyV1Calibration`).
* `OnnxSpeciesEmbedder`: contract from the pack; on the first session it compares the embedding of a synthetic
  pattern with the PC fp32 reference and drops NNAPI (fp16 relaxed) for the CPU if the cosine is below 0.99; if the
  CPU fails too, identification fails loudly instead of returning one species for everything.
* `SpeciesReferenceIndex`: any embedding dimension; confidence = softmax over species at the pack temperature.
* `SpeciesIdentifier`: `RAG_CORE` / `RAG_TENTATIVE` / `UNIDENTIFIED`; optional Gemma choice among the top-k
  (`DescriberPort.chooseAmong`, same prompt as `gemma_rerank.py`), off by default. Unidentified results keep the
  closest candidates as hints and are not added to the BioDex.
* `VlmModelManager.downloadSpeciesPack`: downloads `species_pack.json` first and then the files it lists; without
  it, the v1 file list.

## 6. Reproduce

```bash
RUN=C:/rerun_v9/runs/_species_2026-11-08     # artefacts stay outside the repository
T=tools/species
python -I $T/fetch_inat_dataset.py --catalog $RUN/hf_pack/species_catalog_v1.json --out $RUN/data/inat \
    --per-species 60 --negatives --per-negative 25
python -I $T/make_splits.py --data $RUN/data/inat
python -I $T/embed_all.py --data $RUN/data/inat --out $RUN/emb --pack $RUN/hf_pack \
    --encoders pack_onnx,openclip_b32,bioclip,dinov2_small,dinov2_base,siglip2_base,mnv3_large,effnet_lite0
python -I $T/eval_rag.py --data $RUN/data/inat --emb $RUN/emb --out $RUN/eval --precision 0.8 \
    --encoders bioclip,dinov2_small --published $RUN/hf_pack/species_index_v1.bin
python -I $T/confidence_signals.py --data $RUN/data/inat --emb $RUN/emb --encoder bioclip --gallery proto6
python -I $T/train_classifier.py --data $RUN/data/inat --out $RUN/clf_efficientnet_b0 --model efficientnet_b0.ra_in1k
python -I $T/eval_classifier.py --data $RUN/data/inat --run $RUN/clf_efficientnet_b0
python -I $T/export_variants.py --data $RUN/data/inat --encoders bioclip,dinov2_small --out $RUN/onnx --emb $RUN/emb
python -I $T/gemma_rerank.py --data $RUN/data/inat --emb $RUN/emb --encoder bioclip \
    --catalog $RUN/hf_pack/species_catalog_v1.json --out $RUN/gemma
python -I $T/score_policies.py --gemma $RUN/gemma/gemma_bioclip.jsonl --out $RUN/gemma/policies.json
python -I $T/gemma_verify.py --data $RUN/data/inat --gemma $RUN/gemma/gemma_bioclip.jsonl \
    --catalog $RUN/hf_pack/species_catalog_v1.json --out $RUN/gemma/verify.json
python -I $T/build_pack.py --data $RUN/data/inat --encoder bioclip --catalog $RUN/hf_pack/species_catalog_v1.json \
    --out $RUN/pack_v2_bioclip_fp32 --gallery proto6 --temperature 0.02111 --tau-low 0.841 --tau-high 0.937
python -I $T/verify_pack.py --pack $RUN/pack_v2_bioclip_fp32 --data $RUN/data/inat
python -I $T/bench_latency.py $RUN/onnx/*.onnx --threads 4
python -I $T/diagnose_pack.py --pack $RUN/hf_pack --images <folder>/<species_key>/*.jpg   # v1 pack, app chain
```

GPU steps (`embed_all`, `train_classifier`, `gemma_*`) start the lab GPU guard (`gpu_guard.py`), which refuses to
run if the GPU is busy and stops the job at 83 degC, over 420 W or if the GPU stops answering.

**S22 latency.** Not measured here (no device attached, nothing installed on the phone). The app logs it: run a few
identifications and read the `SpeciesIdentifier` p50 per stage (`locate`, `encode`, `search`, `total`) and the
`OnnxSpeciesEmbedder` lines (execution provider and self-test cosine) with
`adb logcat -s SpeciesIdentifier OnnxSpeciesEmbedder SpeciesPipeline`.

## 7. Publishing the v2 pack (author)

The packs are built in `C:/rerun_v9/runs/_species_2026-11-08/pack_v2_bioclip_fp32/` (and `..._int8/`). To activate
them, upload their five files to `asferrer/oceanguard-biodex` (keeping the v1 files for older app versions):
`species_pack.json`, `biodex_bioclip_v2.onnx`, `species_index_v2.bin`, `species_catalog_v1.json`,
`reference_provenance.jsonl`, plus the BioCLIP licence (MIT, imageomics/bioclip) and the attribution note in the
model card. Only embeddings of the CC0/CC-BY/CC-BY-SA reference photos are shipped; `reference_provenance.jsonl`
lists observation, photo, licence and attribution of each one. Until then the app keeps using v1 with the new,
calibrated thresholds.
