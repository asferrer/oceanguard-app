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

> Update 2026-11-09: the packs to publish are the CC-BY ones of section 8
> (`C:/rerun_v9/runs/_species_2026-11-09/pack_v2_bioclip_int8_ccby/`, fp32 reference in `..._fp32_ccby/`): reference
> index without CC-BY-SA photos, so the pack can be released as CC-BY 4.0. The paragraph below describes the first
> build (with CC-BY-SA references).

The packs are built in `C:/rerun_v9/runs/_species_2026-11-08/pack_v2_bioclip_fp32/` (and `..._int8/`). To activate
them, upload their five files to `asferrer/oceanguard-biodex` (keeping the v1 files for older app versions):
`species_pack.json`, `biodex_bioclip_v2.onnx`, `species_index_v2.bin`, `species_catalog_v1.json`,
`reference_provenance.jsonl`, plus the BioCLIP licence (MIT, imageomics/bioclip) and the attribution note in the
model card. Only embeddings of the CC0/CC-BY/CC-BY-SA reference photos are shipped; `reference_provenance.jsonl`
lists observation, photo, licence and attribution of each one. Until then the app keeps using v1 with the new,
calibrated thresholds.


## 8. Pack without CC-BY-SA references and check with photos from other sources (2026-11-09)

Decision: BioCLIP ViT-B/16 RAG, **fp32 by default** (int8 does not run correctly on the phone, 8.4), reference index built **only from CC0 and CC-BY photos** (5 010 of
the 5 442 train photos: 4 302 CC-BY + 708 CC0, 198 species) so the pack can be published as CC-BY 4.0. Before
publishing it, it was run on real photos that do **not** come from iNaturalist.

### 8.1 Rebuild

```bash
RUN=C:/rerun_v9/runs/_species_2026-11-08; OUT=C:/rerun_v9/runs/_species_2026-11-09
python -I $T/build_pack.py --data $RUN/data/inat --encoder bioclip --catalog $RUN/hf_pack/species_catalog_v1.json \
    --out $OUT/pack_v2_bioclip_int8_ccby --gallery proto6 --licenses cc0,cc-by --calibrate --int8
python -I $T/build_pack.py ... --out $OUT/pack_v2_bioclip_fp32_ccby --gallery proto6 --licenses cc0,cc-by --calibrate
python -I $T/verify_pack.py --pack $OUT/pack_v2_bioclip_int8_ccby --data $RUN/data/inat
```

`build_pack.py` got `--licenses` (gallery filter) and `--calibrate`: with the shipped ONNX (int8 for the int8 pack) it
embeds val, fits the softmax temperature and takes as `tauHigh` / `tauLow` the lowest threshold whose val selective
precision (wrong species or accepted out-of-catalogue photo = error) is >= 0.90 / >= 0.80, the same criterion as the
first v2. `species_pack.json` (with self-test, cosine 1.000000 against the PC reference) and
`reference_provenance.jsonl` (5 010 lines, licences only `cc-by` and `cc0`) are regenerated. Calibration:

| Pack | Temperature | tauHigh | tauLow |
|---|---|---|---|
| int8, CC-BY (new) | 0.02283 | 0.938 | 0.768 |
| fp32, CC-BY (new) | 0.02111 | 0.939 | 0.795 |
| int8, with CC-BY-SA (previous) | 0.02283 | 0.949 | 0.798 |
| fp32, with CC-BY-SA (previous) | 0.02111 | 0.937 | 0.841 |

### 8.2 iNaturalist test split (observers disjoint from the gallery; 3 236 catalogue photos, 198 species, 305 negatives)

`eval_pack.py` (app chain on the ONNX of the pack; v1 = published pack):

| Pack | Top-1 | Top-5 | Macro top-1 | RAG_CORE cov. / prec. | RAG_TENTATIVE cov. / prec. | Negatives UNIDENTIFIED |
|---|---|---|---|---|---|---|
| v1 published (no calibrated thresholds) | 0.489 | 0.768 | 0.507 | n/a | n/a | n/a |
| v2 with CC-BY-SA, fp32 | 0.643 | 0.863 | 0.624 | 0.282 / 0.941 | 0.085 / 0.729 | 0.836 |
| v2 with CC-BY-SA, int8 | 0.604 | 0.834 | 0.588 | 0.227 / 0.966 | 0.125 / 0.775 | 0.869 |
| **v2 CC-BY, fp32** | 0.643 | 0.858 | 0.621 | 0.281 / 0.945 | 0.112 / 0.706 | 0.830 |
| v2 CC-BY, int8 (not deployable, 8.4) | 0.602 | 0.828 | 0.587 | 0.235 / 0.951 | 0.133 / 0.694 | 0.803 |

Coverage is over catalogue photos. Removing the CC-BY-SA references costs 0.0 to 0.2 points of top-1 and 0.5 to 0.6
points of top-5 (the CC-BY-SA photos are 8 % of the gallery); int8 costs 4 points against fp32 in both. The v1 row is
0.489 here against 0.492 in section 3 because this run uses the pack's own resize chain; same pack, same photos.

### 8.3 Independent set (no iNaturalist)

`fetch_gbif_independent.py` + `fetch_commons_independent.py` + `filter_indep.py` + `tag_non_photos.py`; data in
`C:/rerun_v9/runs/_species_2026-11-09/data/indep/` (not redistributed, evaluation only).

* **Sources.** GBIF occurrences with `mediaType=StillImage`, `basisOfRecord` HUMAN_OBSERVATION / OBSERVATION (no
  museum specimens), the iNaturalist dataset `50c9509d-22c7-4a22-a47d-8c48425ef4a7` excluded, taxon = GBIF backbone
  species key; and files directly in `Category:<scientific name>` of Wikimedia Commons. Licences CC0, CC-BY, CC-BY-SA
  only (4 107 catalogue photos: 2 025 CC-BY, 1 857 CC-BY-SA, 225 CC0); at most 3 photos per photographer per species.
  Label = the species of the GBIF record or Commons category, not re-checked by an expert: some errors in the
  labels are possible and count against the model. Sources of the catalogue photos: Wikimedia Commons 2 796, Explore
  Your Shore 656, Arter.dk 199, Norwegian Species Observation Service 185, Observation.org 158, southern African
  fish occurrence records 83, others 30. Many CC-NC photos (most of Observation.org) are excluded by licence.
  Dead links (e.g. minka-sdg.org) are skipped.
* **Cleaning.** 5 177 downloaded; dropped 250 near-duplicates of any iNaturalist photo of the study (dHash <= 4 bits,
  train + val + test + the ones dropped before), 115 duplicates inside the set, and 369 non-photos by colour
  statistics (mostly white 143, fewer than 60 colours 190, aspect > 3:1 35, too small 1). Kept 4 443: 4 107
  catalogue photos of 197 species and 336 out-of-catalogue photos of 29 taxa (the negative taxa of section 2).
  A zero-shot OpenCLIP prompt check (`tag_non_photos.py`) tags drawings, maps, labels, museum specimens, shells on a
  table, people and screenshots: 3 712 catalogue photos (196 species, median 17 per species, 147 species with at
  least 10) and 324 negatives pass it ("photo_ok"). Looking at samples, what it rejects is almost all
  not-a-photo or specimens; what it keeps still contains some food plates, aquarium shots and a few drawings.
  Both sets are reported.
* **Chain.** Centre-square crop, bilinear 224, pack mean/std, the pack ONNX on CPU, max-prototype cosine over the
  shipped index, softmax at the pack temperature, thresholds of the pack.

| Pack | Set | Top-1 | Top-5 | Macro top-1 | RAG_CORE cov. / prec. | RAG_TENTATIVE cov. / prec. | Negatives UNIDENTIFIED |
|---|---|---|---|---|---|---|---|
| v1 published | all 4 107 | 0.404 | 0.699 | 0.429 | n/a | n/a | n/a |
| v2 with CC-BY-SA, fp32 | all | 0.599 | 0.824 | 0.572 | 0.312 / 0.924 | 0.102 / 0.705 | 0.818 |
| v2 with CC-BY-SA, int8 | all | 0.559 | 0.790 | 0.540 | 0.226 / 0.948 | 0.149 / 0.761 | 0.830 |
| v2 CC-BY, fp32 | all | 0.594 | 0.825 | 0.575 | 0.304 / 0.916 | 0.147 / 0.680 | 0.774 |
| v2 CC-BY, int8 (not deployable, 8.4) | all | 0.550 | 0.785 | 0.540 | 0.241 / 0.935 | 0.153 / 0.692 | 0.804 |
| v1 published | photo_ok 3 712 | 0.416 | 0.716 | 0.449 | n/a | n/a | n/a |
| v2 CC-BY, fp32 | photo_ok | 0.616 | 0.849 | 0.597 | 0.316 / 0.916 | 0.153 / 0.679 | 0.769 |
| v2 CC-BY, int8 (not deployable, 8.4) | photo_ok | 0.570 | 0.809 | 0.559 | 0.252 / 0.934 | 0.159 / 0.688 | 0.796 |

Top-1 per source (int8 CC-BY, all): Observation.org 0.665 (n = 158), Explore Your Shore 0.591 (656), Norwegian
service 0.578 (185), southern African fish 0.542 (83), Wikimedia Commons 0.540 (2 796), Arter.dk 0.477 (199).
The int8 CC-BY pack is 5.2 points below its iNaturalist figure (0.550 against 0.602) and 14.6 points above the
v1 pack; the confident answers keep a precision above 0.90 (target of `tauHigh`), the tentative ones (0.69) are
below the 0.80 of their val target, as they already were on the iNaturalist test (0.694). Of the negatives, 6.5 %
get a confident species (RAG_CORE) and 13.1 % a "possible" one.

Weakest species (int8 CC-BY, photo_ok, species with >= 8 photos; top-1, wrong answer): *Solea solea* 0.00 (27;
*Bothus podas*), *Sardina pilchardus* 0.00 (15; *Engraulis encrasicolus*), *Hippocampus hippocampus* 0.00 (11;
*H. guttulatus*), *Xiphias gladius* 0.00 (8; *Alopias vulpinus*), *Carcharodon carcharias* 0.05 (19; *Mola mola*),
*Thunnus thynnus* 0.07, *Myliobatis aquila* 0.08, *Merluccius merluccius* 0.09, *Lepidochelys kempii* 0.09,
*Paramuricea clavata* 0.11, *Squalus acanthias* 0.12 (*Mustelus mustelus*), *Mullus surmuletus* 0.17 (*M.
barbatus*), *Caretta caretta* 0.25 (40; 20 as *Eretmochelys imbricata*). 4 of the 157 species with >= 8 photos have
top-1 0 and 33 have >= 0.8. Most frequent confusions (photos): *Caretta caretta* -> *E. imbricata* 20,
*Delphinus delphis* -> *Tursiops truncatus* 14, *Felimare picta* -> *Hypselodoris picta* 14, *Chelonia mydas* ->
*E. imbricata* 13, *M. surmuletus* -> *M. barbatus* 10, *S. acanthias* -> *M. mustelus* 10, *S. pilchardus* ->
*E. encrasicolus* 9, *S. solea* -> *B. podas* 9. They are congeneric or look-alike pairs, as the top-5 (0.785 to 0.809)
suggests; a few may be mislabelled in Commons categories. The most frequent answer is *Chaetodon lunula* (78 of
4 107), so the pipeline does not collapse. Wrong answers with confidence above 0.98 exist (a *Serranus cabrilla*
read as *Eunicella cavolini*, a *Carcharodon carcharias* as *Mola mola*): the "identified" badge is right about
93 % of the times, not always.

Files: `eval/indep_*.json` and `eval/inat_test_*.json` under the run folder (per-species table, confusions, 25 most
confident errors); commands: `eval_pack.py --pack <pack> --rows data/indep/indep.jsonl --base data/indep`
(independent) and `--rows <inat>/splits.jsonl --base <inat> --split test` (iNaturalist).

### 8.4 Check on Android: int8 is not deployable, fp32 is the default

Both packs were installed in the app (release APK, ONNX Runtime 1.20.0, Android 16 emulator with arm64 translation)
and run on test photos:

* **int8 as built above** does not load: ONNX Runtime 1.20 has no `ConvInteger` kernel for int8 weights, and
  `quantize_dynamic` had quantised the patch-embedding convolution (`Could not find an implementation for
  ConvInteger(10)`; reproduced on the PC with `onnxruntime==1.20.1`). `build_pack.py` now quantises MatMul only.
* **int8, MatMul only** loads, but the self-test fails on the device: cosine 0.978 between the ARM embedding and the
  PC embedding of the same pattern (minimum 0.99). The int8 kernels round differently on x86 and ARM, and on the PC
  the int8 embedding is already 0.954 away from fp32 on that pattern, so the int8 figures above, measured on x86, do
  not describe the phone. The app refuses the pack and now shows the encoder error instead of "no species".
* **fp32** (`pack_v2_bioclip_fp32_ccby`): self-test cosine 1.0, about 0.6 s per photo on the emulator, correct answers
  with high confidence on the test photos tried (*Chromis chromis* 0.99, *Thalassoma pavo* 0.85) and "not
  identified" below the thresholds. It is the published default: 345 MB to download (the v1 encoder was 352 MB),
  top-1 0.643 on the iNaturalist test split and 0.594 on the independent set, answers with high confidence for 30 %
  of the independent photos with 91.6 % precision.


## 9. Geographic prior: what the app did and what it should do (2026-11-09)

Everything above was measured **without** a location. The app also reorders the candidates with the position of the
photo, so this section replays that code on the PC (`tools/species/geo_prior_eval.py`: port of `MarineRegionResolver`
with the MEOW raster and the ring search, `GeoPrior.matchLevel` / `g`, `SpeciesReferenceIndex.search` with the
relaxation, `SpeciesConfidence.idSource`) with the **real coordinates of the photos**: iNaturalist observation
coordinates (3 222 test photos with a resolved ecoregion, val 1 131) and, for the independent set, GBIF
`decimalLatitude/Longitude` (1 267 photos) and Commons GeoData (781 photos): 1 917 catalogue photos with a resolved
ecoregion (`fetch_indep_coords.py`). Pack of section 8 (BioCLIP, 6 prototypes, CC-BY references), same photos for every row.

Defects of the code before the fix:

1. `matchLevel` returned `COSMOPOLITAN` before looking at the region, so the 35 cosmopolitan species were multiplied by
   0.1 **even without GPS**: top-1 of the cosmopolitan species was 0.000 (458 test photos, 14 % of the test; 483 in the
   independent set) and the global top-5 fell from 0.856 to 0.748.
2. Species outside the realm of the photo got `g = 1.0` (level `GLOBAL`), more than `PROVINCE` 0.6 and `REALM` 0.3, and
   `passesHardFilter` lets `GLOBAL` / `COSMOPOLITAN` always pass, so the `BALANCED` branch `level != GLOBAL` was dead code:
   nothing was excluded and only the multiplier worked.
3. The relaxation to a global search (best filtered confidence below `tauLow` = 0.795) fired for 63 % (test) / 57 %
   (independent) of the photos, which is why cosmopolitan species reappeared with GPS (0.38 / 0.28); the confident photos
   kept the penalty. The margin used the adjusted order and could be negative (with `delta = 0` that blocks `RAG_CORE`).

| Test (3 222 photos with region) | Top-1 | Top-5 | RAG_CORE cov. / prec. | Top-1 cosmopolitan (458) |
|---|---|---|---|---|
| No location, plain cosine | 0.642 | 0.856 | 0.284 / 0.944 | 0.520 |
| Old app code, no GPS | 0.574 | 0.748 | 0.273 / 0.946 | 0.000 |
| Old app code, with GPS | 0.623 | 0.831 | 0.271 / 0.951 | 0.384 |
| Soft prior, with GPS | 0.653 | 0.863 | 0.296 / 0.947 | 0.535 |

| Independent (1 917 photos with coordinates) | Top-1 | Top-5 | RAG_CORE cov. / prec. | Top-1 cosmopolitan (483) |
|---|---|---|---|---|
| No location, plain cosine | 0.621 | 0.844 | 0.298 / 0.948 | 0.530 |
| Old app code, no GPS | 0.513 | 0.643 | 0.257 / 0.954 | 0.000 |
| Old app code, with GPS | 0.561 | 0.772 | 0.254 / 0.955 | 0.280 |
| Soft prior, with GPS | 0.638 | 0.849 | 0.316 / 0.949 | 0.534 |

**Soft prior (recommended, now implemented in the app):** `logit_s = cos_s / T + ln g_s`, confidence = softmax of the
logits, `T` = pack temperature, pack thresholds unchanged; `g` = ECOREGION 1.0, PROVINCE 0.5, REALM 0.3, out of range
(species with distribution data, none in the realm) 0.1, COSMOPOLITAN 1.0, no distribution data 1.0; without region
`g = 1` for all; no hard filter in BALANCED, no relaxation; `STRICT` only removes REALM and out-of-range. Weights were
searched on val (log-likelihood of the true species over prov {0.5..1} x realm {0.2..1} x out {0.02..1}): the optimum is
(0.5, 0.2, 0.02) but the surface is flat (any prov <= 0.7, realm <= 0.4, out <= 0.1 gives 0.653-0.656 top-1 on test,
0.641-0.645 on the independent set). Out-of-range is rare in these samples (the true species is in its ecoregion in 85 % /
73 % of the photos, cosmopolitan in 14 % / 25 %, in another province or realm in < 1 %), so a hard penalty cannot be
validated; 0.1 was chosen for caution. Geography adds +1.1 (test) / +1.7 (independent) points of top-1 over no location;
the old code lost 1.9 / 6.0 with GPS and 6.8 / 10.8 without. With the final scoring of section 10 (BioCLIP 2 + text rows,
T = 0.0542) the same prior gives test 0.889 -> 0.895 and independent 0.839 -> 0.847 top-1.

## 10. Precision study: more references, other encoders, text rows (2026-11-09)

Goal: the most accurate species identification that still fits the deployment limits (ONNX Runtime 1.20 on Android,
no int8, fp32 or fp16 only if the self-test passes, download up to ~600 MB, CC0 / CC-BY references, MIT / Apache encoder).
Artefacts: `C:/rerun_v9/runs/_species_precision/`. All figures use the app chain (centre square, bilinear 224, CLIP
mean/std); experiments run on fp32 GPU embeddings (torch vs ONNX CPU agree to 0.99999 on 24 photos), the candidate packs
are re-measured on ONNX Runtime 1.20.1 (CPU) in a clean venv. Val picks the configuration; test = iNaturalist test split
(3 210 catalogue photos after merging the duplicate species below, 305 negatives), indep = independent set (4 107
catalogue photos, 336 negatives). `hypselodoris_picta` and `felimare_picta` are the same nudibranch (identical
observations under both keys, which also made the old confusion "Felimare picta -> Hypselodoris picta" a catalogue
artefact): the duplicate rows were dropped and the references merged under `felimare_picta` (202 catalogue entries
remain, 197 with photos; the published-pack rows below were measured with the duplicate).

### 10.1 Datasets

Counts measured with the APIs (`count_inat_available.py`, `count_gbif_available.py`, 2026-11-09) unless marked.

| Source | Licence | Images for our species | Underwater | Reference (published index) | Evaluation |
|---|---|---|---|---|---|
| iNaturalist research grade, photo CC0 / CC-BY | per photo | **52 817 observations** for 202 species (median 104 per species, 64 species >= 250, 38 < 30, 15 < 10) | mostly in situ (dive, shore) | **yes** (CC BY index with attribution file) | yes |
| iNaturalist, photo CC-BY-SA | per photo | 3 549 | idem | no (index is CC BY 4.0) | yes |
| iNaturalist, CC-BY-NC / all rights reserved | per photo | not downloaded (467 731 CC-BY-NC still images over all GBIF publishers) | idem | no | no |
| GBIF, other publishers (Observation.org, Arter.dk, Explore Your Shore, Norwegian service, SANBI...) | CC0 / CC-BY only | 12 713 CC0 / CC-BY still images over 203 species (median 9 per species); most of Observation.org is CC-BY-NC | mixed | possible but small; **kept as independent test** | yes (part of the 4 107 photos in `indep`) |
| Wikimedia Commons, `Category:<species>` | CC0 / CC-BY / CC-BY-SA | 2 796 photos in the independent set (drawings and specimens included) | mixed | no (SA, noisy labels); **kept as test** | yes |
| FishBase / SeaLifeBase | per photo CC-BY or CC-BY-NC; copyright stays with the contributor (web search, not measured) | not counted | mostly specimens / aquarium | only individual CC-BY photos after checking each one | possible |
| Reef Life Survey photo quadrats (IMAS) | CC BY 3.0 AU (catalogue record) | habitat cover quadrats, Australia; no organism-level labels for our species | yes | no | no |
| FathomNet (MBARI) | Data Use Policy, contributor copyright | deep sea, practically no overlap with our shallow species (not counted) | yes | no | no |
| iNat2021, TreeOfLife-10M / 200M, Rare Species, BIOSCAN | dataset card CC0 for metadata, images keep their source licences (not verified one by one) | they are BioCLIP / BioCLIP 2 training data; BIOSCAN is insects | mixed | no | no: the encoders have seen them |
| CleanSea, JAMSTEC | excluded by rule | | | | |

Conclusion: iNaturalist is the only source with enough CC0 / CC-BY photos per species to publish references; GBIF
non-iNaturalist and Commons stay as the independent test. Independence of the new references: the 13 711 added
iNaturalist photos (`fetch_inat_more.py`, up to 250 train photos per species, <= 15 per observer and species, newest
first) come only from observers whose salted hash puts them in the **train** split of `make_splits.py`, so the val and
test observers are unchanged and disjoint; 121 photos within 4 bits of dHash of any val / test / negative photo, 59 within
4 bits of any independent photo and 53 duplicates inside the set were dropped (`dedupe_ext.py`); the independent set never
supplied a reference photo. 130 of 202 species received extra photos (the others had no more CC0 / CC-BY photos from
train observers). BioCLIP 2 was trained on data up to 2024; its gain holds on iNaturalist test photos created after
2025-06-01 (662 photos, below), so it is not memorisation of the test.

### 10.2 Experiments (no location; calibrated on val)

Same calibration criterion everywhere (val selective precision >= 0.90 / 0.80, negatives count as errors); coverage and
precision of the confident answers of the packs are in 10.3. "RAG" = max cosine over image prototypes.

| # | Configuration | Test top-1 / top-5 / macro | Indep top-1 / top-5 / macro | Index / extra size |
|---|---|---|---|---|
| 1 | BioCLIP B/16, RAG 6 prototypes (published recipe, 5 010 refs) | 0.645 / 0.855 / 0.628 | 0.597 / 0.825 / 0.585 | 1.2 MB |
| 2 | same, 12 / 24 prototypes | 0.634 / 0.856 / 0.623; 0.618 / 0.852 / 0.611 | 0.592 / 0.819 / 0.576; 0.574 / 0.814 / 0.564 | 2.2 / 4.1 MB |
| 3 | same, kNN max over all refs; kNN mean of top 3 | 0.618 / 0.851 / 0.611; 0.638 / 0.850 / 0.619 | 0.570 / 0.815 / 0.562; 0.614 / 0.831 / 0.598 | 5.1 MB |
| 4 | BioCLIP, 6 prototypes, up to 250 refs per species (18 695) | 0.660 / 0.860 / 0.639 | 0.625 / 0.827 / 0.586 | 1.2 MB |
| 5 | BioCLIP + logistic head (L-BFGS, wd 1e-4); head + RAG | 0.701 / 0.894 / 0.680; 0.706 / 0.895 / 0.690 | 0.637 / 0.817 / 0.611; 0.650 / 0.835 / 0.629 | +0.2 MB |
| 6 | BioCLIP + text row, RAG + 1.0 x cos(text) | 0.676 / 0.872 / 0.663 | 0.672 / 0.857 / 0.658 | +0.2 MB |
| 7 | **BioCLIP 2** ViT-L/14, RAG 6 prototypes | 0.822 / 0.955 / 0.807 | 0.743 / 0.884 / 0.722 | 1.8 MB |
| 8 | BioCLIP 2, 12 / 24 prototypes; kNN top-3 mean | 0.808 / 0.950 / 0.802; 0.810 / 0.948 / 0.798; 0.820 / 0.957 / 0.809 | 0.732 / 0.883 / 0.710; 0.721 / 0.883 / 0.697; 0.746 / 0.897 / 0.727 | 3.4 / 6.1 / 7.7 MB |
| 9 | BioCLIP 2, 6 prototypes, up to 250 refs per species | 0.817 / 0.955 / 0.798 | 0.743 / 0.888 / 0.713 | 1.8 MB |
| 10 | BioCLIP 2 + logistic head (wd 1e-4) | 0.845 / 0.963 / 0.830 | 0.757 / 0.898 / 0.734 | +0.3 MB |
| 11 | BioCLIP 2, text rows only (zero-shot, "a photo of Genus species") | 0.886 / 0.977 / 0.883 | 0.789 / 0.916 / 0.773 | 0.3 MB |
| 12 | BioCLIP 2, RAG + 1.0 x text | 0.876 / 0.975 / 0.862 | 0.790 / 0.911 / 0.775 | +0.3 MB |
| 13 | **BioCLIP 2, RAG (extra refs) + 4.0 x text (candidate)** | **0.888 / 0.977 / 0.878** | **0.800 / 0.922 / 0.781** | 2.2 MB |
| 14 | BioCLIP 2, RAG + 4.0 x text + 0.5 x head | 0.893 / 0.976 / 0.882 | 0.807 / 0.923 / 0.788 | +0.3 MB |
| 15 | BioCLIP 2 RAG, TTA flip; centre + whole photo; 6 views | 0.818 / 0.955 / 0.801; 0.828 / 0.958 / 0.812; 0.827 / 0.959 / 0.808 | 0.741 / 0.884 / 0.719; 0.751 / 0.896 / 0.734; 0.746 / 0.890 / 0.722 | x2 / x2 / x6 encodes |
| 16 | BioCLIP 2, text + 672 congeners as "not in the catalogue" classes (x1) | 0.820 / 0.945 / 0.798 | 0.665 / 0.833 / 0.624 | negative result |

Rows 2, 3 and 8 were measured before the duplicate merge (differences <= 0.006). Findings:

* **a. More references.** For BioCLIP B/16, up to 250 photos per species give +1.5 / +2.8 points (it saturates at ~100);
  for BioCLIP 2 nothing (0.822 -> 0.817), and val and test disagree (val 0.828 -> 0.847): the new photos are recent and
  unselected, val and test are the older, most-voted ones. The index does not grow (6 prototypes per species).
* **b. Gallery.** 6 prototypes is the best size for both encoders; more prototypes or kNN over all photos lower top-1
  (the maximum over many near-duplicates picks the wrong species more often) although they raise precision at a given
  threshold; the top-3 mean is the only variant that matches 6 prototypes (-0.7 / +1.7 for BioCLIP, -0.2 / +0.3 for
  BioCLIP 2) and it needs 4x the index.
* **c. Encoder.** BioCLIP 2 (MIT, ViT-L/14, 304 M parameters in the image tower, output 768) gives +17.7 / +14.6 points of
  top-1 over BioCLIP with the same recipe. On test photos created after 2025-06-01 (662): BioCLIP 0.657, BioCLIP 2 0.831,
  RAG + 4 x text 0.893. Size: 1.22 GB fp32, 609 MB with fp16 weight storage (10.3).
* **d. Linear head.** +5.6 points for BioCLIP, +2.3 for BioCLIP 2, 0.2-0.3 MB, but only +0.5 on top of RAG + text for
  BioCLIP 2: **not worth an app change** once text rows exist.
* **Text rows (not in the brief, the largest single gain).** The BioCLIP 2 text tower knows our species by name:
  zero-shot "a photo of <Genus species>" beats the image RAG (0.886 vs 0.822); adding it to the RAG score (weight 4)
  gives 0.888 and keeps the adaptation to our photos. It also covers the 5 species without any reference photo
  (*Cystoseira amentacea*, *Eledone moschata*, *Patella ferruginea*, *Sepia elegans*, *Sphyraena sphyraena*) and helps
  the ones with very few. Prompts with the full lineage or the common name are
  1-2 points worse.
* **e. TTA.** Flip: no gain (-0.4 / -0.2); centre + whole photo (squashed, no crop): +0.6 / +0.8 for 2 encodes; 6 views:
  +0.5 / +0.3. Not worth the phone cost. The whole photo alone beats the centre square (0.895 vs 0.888 test, 0.811 vs
  0.799 independent, with the final scoring) at no extra cost: **a free +0.7 / +1.2 points if the app resized the whole
  photo instead of cropping the centre square**.
* **f. Organism crop (potential only).** An oracle that knows the label and takes the best of 5 fixed crops (centre, whole
  photo, two edge squares, central 80 %) reaches 0.950 test / 0.874 independent against 0.888 / 0.799 with the centre
  square, i.e. an upper bound of +6.2 / +7.5 points for perfect localisation; the cheap heuristic "most confident of the
  5 crops" gets 0.904 / 0.822 (+1.6 / +2.3) at 5 encodes. Nothing was implemented.
* **Distractors (negative).** Adding 672 congeneric species as text-only "not in the catalogue" classes cuts the accepted
  negatives (core 22 % -> 13 %) but costs 7 points of top-1, because a congener often takes the true species.

Per group (test / independent, top-1; BioCLIP RAG -> BioCLIP 2 RAG + text): turtles 0.493 / 0.338 -> 0.732 / 0.824;
mammals 0.469 / 0.433 -> 0.821 / 0.772; sharks and rays 0.509 / 0.383 -> 0.826 / 0.696; cnidarians 0.827 / 0.722 ->
0.960 / 0.885; molluscs 0.799 / 0.643 -> 0.926 / 0.826; plants and algae 0.694 / 0.673 -> 0.871 / 0.766. What remains
are look-alike pairs: *Sardina pilchardus* / *Engraulis encrasicolus*, the two *Hippocampus*, *Sabella spallanzanii* /
*Protula tubularia*, *Mugil cephalus* / *Chelon labrosus*, *Conger conger* / *Anguilla anguilla*, *Scorpaena porcus* /
*S. scrofa*, *Pagrus pagrus* / *Pagellus erythrinus*.

### 10.3 Candidate packs (ONNX Runtime 1.20.1, CPU, clean venv `ort120`)

`build_pack_plus.py` builds both; `eval_pack_ort.py` loads each with `onnxruntime==1.20.1`, runs the self-test (cosine
1.000000, minimum 0.99) and measures the app chain. The published row is section 8 (v2 CC-BY, fp32).

| Pack | Test top-1 / top-5 / macro | RAG_CORE cov. / prec. | RAG_TENTATIVE cov. / prec. | Test negatives: core / tentative / unidentified | Indep top-1 / top-5 / macro | RAG_CORE cov. / prec. | Indep negatives: core / tentative / unidentified |
|---|---|---|---|---|---|---|---|
| Published v2 CC-BY fp32 | 0.643 / 0.858 / 0.621 | 0.281 / 0.945 | 0.112 / 0.706 | n/a / n/a / 0.830 | 0.594 / 0.825 / 0.575 | 0.304 / 0.916 | n/a / n/a / 0.774 |
| **pack_best**: BioCLIP 2 (fp16 storage), 6 prototypes, text rows (weight 4) | **0.890 / 0.979 / 0.876** | 0.787 / 0.957 | 0.120 / 0.599 | 0.213 / 0.207 / 0.580 | **0.798 / 0.925 / 0.769** | 0.699 / 0.931 | 0.146 / 0.262 / 0.592 |
| pack_light: BioCLIP B/16 fp32, 6 prototypes, text rows (weight 1) | 0.679 / 0.869 / 0.660 | 0.366 / 0.962 | 0.134 / 0.750 | 0.066 / 0.128 / 0.807 | 0.660 / 0.841 / 0.626 | 0.425 / 0.929 | 0.113 / 0.146 / 0.741 |

The precision of the decisions counts accepted negatives as errors. Open set got worse for pack_best: 21 % / 15 % of the
out-of-catalogue photos get a confident species (6 % before), because the text tower places other species close to
ours. Raising the val precision target of `tauHigh` from 0.90 to 0.95 (tauHigh 0.91 instead of 0.773) gives: test
coverage 0.669, precision of the answers 0.980, negatives accepted 8.9 %; independent 0.586 / 0.964 / 4.8 % (target 0.97:
tauHigh 0.973, coverage 0.531 / 0.457, precision 0.993 / 0.981, negatives 3.6 % / 1.8 %). The choice is a product one.

Sizes and CPU cost on the PC (ONNX Runtime 1.20.1, 4 threads, batch 1; the phone was not available):

| Encoder file | Size | p50 | Peak RAM |
|---|---|---|---|
| BioCLIP B/16 fp32 (pack_light) | 345 MB | 118 ms | 0.77 GB |
| BioCLIP 2 fp32 | 1 217 MB (over the limit) | 546 ms | 2.57 GB |
| **BioCLIP 2, fp16 weights + Cast (pack_best)** | **609 MB** | 534 ms | 2.57 GB (ORT folds the Casts at load) |
| same, `optimization.disable_specified_optimizers = ConstantFolding` | 609 MB | 723 ms | **0.79 GB** |

fp16 here is *storage only* (`onnx_fp16_storage.py`: fp16 initialisers plus a Cast to fp32 per weight, fp32 compute), so
no fp16 kernel is needed on ARM; embeddings differ from fp32 by cosine >= 0.9999999. A fully fp16 graph was not tried
(ORT 1.20 CPU has no x86 fp16 MatMul and it could not be checked on ARM). pack_best is 611.5 MB (609.1 encoder + 2.2
index + 0.2 catalogue) plus the 3.2 MB attribution file. Expect about 4.5x the BioCLIP B/16 latency on the phone (the S22
figure is not measured).

Files and SHA-256 (`C:/rerun_v9/runs/_species_precision/`, not committed):

```
pack_best/biodex_bioclip2_v3.onnx        aee44cd78984b1ea0f41364f881071040c4b648c124d73803d038d30eaac4bf7
pack_best/species_index_v3.bin           c1672a131e0ad49d54f96b11071d1cf1739db5fe93da4bc6aaacacc90cc437ff
pack_best/species_pack.json              decd03c3d974d5070667a91563d3bab2492dc6969404096f7eaedf909ec10c50
pack_best/species_catalog_v1.json        249f5d99da41681d636756a3377429169c7d30df1dfa530d72f7d5055edf0b6c
pack_best/reference_provenance.jsonl     0709da07aa431ef076252f29321f6f7befdf4414012efb335d70fc4cf1d085aa
pack_light/biodex_bioclip_v3.onnx        3d9129e394821437eee051a586dac66723c8d19cf9f410b876111225ab137822
pack_light/species_index_v3.bin          2cb3ffcf6c82fa4bccae19d4c0a89ac0bf263dc1524daf5288c9954097396ba6
pack_light/species_pack.json             2d7532d693459cc15d4747a5c36c69dea5868d68539c07da2899cfc12726d770
```

`species_pack.json` of pack_best: temperature 0.05421, tauHigh 0.773, tauLow 0.531 (val precision >= 0.90 / 0.80 with
negatives as errors), `scoring` = {imageWeight 1, textWeight 4, textViewTag "text", missingImageScore 0.8427}, 768-d,
`encoderLicense` MIT (imageomics/bioclip-2). The index keeps the SPEX v1 layout: 1 137 image prototypes (`viewTag`
"proto6") and 202 rows with `viewTag` "text" (one per species), 1 339 rows in total.

### 10.4 What the app must change for pack_best

The SPEX file and the encoder contract are unchanged, so an app that ignores `scoring` still loads the pack and runs as
plain RAG with BioCLIP 2 (the text rows are ordinary prototypes whose cosines, 0.1-0.4, never win the max), which measures
0.822 / 0.743 top-1; the gain of the text rows needs this:

1. `SpeciesPackConfig`: read the optional `scoring` object (`imageWeight`, `textWeight`, `textViewTag`, `missingImageScore`).
2. `SpeciesReferenceIndex`: per species, image score = max cosine over its rows whose `viewTag` != `textViewTag` (or
   `missingImageScore` when it has none); text score = cosine with its `textViewTag` row; species score = `imageWeight`
   x image + `textWeight` x text. Then the geographic logits `score / T + ln g` (section 9) and the softmax. The top-1 /
   top-2 margin should use these scores.
3. Optional, memory: create the session with `optimization.disable_specified_optimizers = ConstantFolding` for this pack
   (peak 0.79 GB instead of 2.57 GB, 723 ms instead of 534 ms on the PC). Not tried on ARM.
4. Optional, +0.7 / +1.2 points: resize the whole photo to 224 instead of cropping the centre square.
5. The catalogue still lists `hypselodoris_picta`; the pack has no image or text row for it (merged into `felimare_picta`).

pack_light has the size of the published pack (347 MB) and needs the same app change (text weight 1); it is +3.6 / +6.6
points of top-1 over it.

### 10.5 Recommendation

Publish pack_best (BioCLIP 2 + text rows) if the S22 shows a latency of a few seconds and no memory kills (with the
ConstantFolding option if needed): +24.7 points of top-1 on the iNaturalist test (0.643 -> 0.890) and +20.4 on the
independent set (0.594 -> 0.798), top-5 0.979 / 0.925; turtles, mammals and sharks go from 0.34-0.51 to 0.70-0.83. Ship
it together with the geographic fix of section 9 (otherwise the cosmopolitan species, turtles and cetaceans among them,
never come out). Raise the `tauHigh` precision target if wrong confident answers matter more than coverage (10.3). Keep
pack_light as the fallback for phones that cannot hold the larger model. More photos, a linear head, kNN, more
prototypes and TTA are not worth their cost once the encoder and the text rows change.

Reproduce (outputs in the run folder): `count_inat_available.py`, `count_gbif_available.py`, `fetch_taxonomy.py`,
`fetch_inat_more.py`, `dedupe_ext.py`, `embed_set.py` (bioclip, bioclip2; `--flip`, `--crop full|start|end|c80`),
`embed_text.py`, `precision_exp.py --exp gallery|head|tta|text|refs|combo|distract`, `build_pack_plus.py --encoder bioclip2
--text-weight 4 --w16 --ext ext_rows.jsonl`, `eval_pack_ort.py` (inside the `onnxruntime==1.20.1` venv).


## 11. Final pack for 1.2.0-rc3 (pack_final, 2026-11-09)

`C:/rerun_v9/runs/_species_precision/pack_final/`: the encoder of pack_best (same ONNX, same SHA-256) with a new index,
calibration for precision and three optional manifest fields. Built with `build_pack_plus.py --crop-mode full
--precision-high 0.95 --precision-low 0.80 --session-optimization none --catalog-name species_catalog_v2.json
--reuse-encoder pack_best`.

**Calibration (val).** `tauHigh` = lowest threshold with val selective precision >= 0.95, `tauLow` = lowest with >= 0.80
(the "possible" band is a suggestion, never stored); wrong species and accepted out-of-catalogue photos count as errors.
The softmax temperature is still the optimum of the val fit: 0.05421. Result: **tauHigh 0.904, tauLow 0.525** (the
0.91 quoted in 10.3 was for the centre-square pack; pack_best at target 0.90 had 0.773 / 0.531).

**`cropMode`: "full".** Without a locator the encoder gets the whole photo resized to 224 x 224 (aspect ratio not kept)
instead of the centre square; the reference photos are embedded the same way. Same pipeline, same val calibration:

| Input (references / queries) | Val top-1 | Test top-1 / top-5 / macro | Independent top-1 / top-5 / macro |
|---|---|---|---|
| centre square / centre square | 0.891 | 0.888 / 0.977 / 0.869 | 0.800 / 0.922 / 0.766 |
| centre square / whole photo | 0.887 | 0.896 / 0.977 / 0.881 | 0.810 / 0.931 / 0.786 |
| **whole photo / whole photo (pack_final)** | 0.884 | **0.898 / 0.977 / 0.886** | **0.811 / 0.931 / 0.785** |

Confirmed on both held-out sets (+1.0 test, +1.1 independent, 3 210 and 4 107 photos; the same direction as the +0.7 /
+1.2 of 10.2); val (1 131 photos) goes the other way by -0.7, within its noise. The app resize is bilinear on the whole
bitmap, as in `embed_set.py --crop full`.

**`sessionOptimization`: "none".** `mem_lat2.py`: peak working set and p50 of one photo, ONNX Runtime 1.20.1, 4 threads,
x86 CPU, the pack ONNX (fp16 storage + Cast). The Java levels map to `ORT_DISABLE_ALL / BASIC / EXTENDED / ALL`; every level
gives the same embedding (cosine >= 0.9999998).

| Level (Java) | Load | p50 | Peak RAM |
|---|---|---|---|
| none (`NO_OPT`) | 0.7 s | 505 ms | **786 MB** |
| basic (`BASIC_OPT`) | 2.9 s | 404 ms | 2 568 MB |
| extended (`EXTENDED_OPT`) | 2.9 s | 372 ms | 2 567 MB |
| all (`ALL_OPT`) | 2.9 s | 376 ms | 2 568 MB |

The constant folding of the fp16 -> fp32 Casts (it starts at BASIC) materialises the 1.2 GB fp32 weights; only `NO_OPT`
stays under 1 GB, and it costs ~35 % of latency on this CPU (no operator fusion). On ARM the penalty may be different
(not measured). No fp32 or pure-fp16 ONNX is needed for the memory limit, but the real S22 figure is still unknown.

**`catalogFile`: `species_catalog_v2.json`** (SHA-256 48e47f34...a112e, the regenerated one): the 202 species keys of the
index (197 with image prototypes + 5 text-only: *Cystoseira amentacea*, *Eledone moschata*, *Patella ferruginea*, *Sepia
elegans*, *Sphyraena sphyraena*) are exactly the 202 keys of the catalogue, no key in only one of them (`verify_pack.py`).
`merged` {hypselodoris_picta: felimare_picta} is kept in the manifest. 18 677 reference photos (CC0 / CC-BY).

**Results (ONNX Runtime 1.20.1, CPU, clean venv; cropMode full, level none; no location).** Decisions count accepted
out-of-catalogue photos as errors in the precision.

| Set | Top-1 / top-5 / macro | RAG_CORE cov. / prec. | RAG_TENTATIVE cov. / prec. | Negatives: core / tentative / unidentified |
|---|---|---|---|---|
| iNaturalist test (3 210 + 305 negatives) | 0.899 / 0.979 / 0.890 | 0.686 / 0.980 | 0.234 / 0.709 | 0.089 / 0.348 / 0.564 |
| Independent (4 107 + 336 negatives) | 0.810 / 0.934 / 0.792 | 0.616 / 0.963 | 0.236 / 0.652 | 0.062 / 0.339 / 0.598 |

Against pack_best (target 0.90): CORE coverage falls from 0.787 / 0.699 to 0.686 / 0.616 and the precision of the
confident answers rises from 0.957 / 0.931 to 0.980 / 0.963; accepted negatives fall from 21.3 % / 14.6 % to 8.9 % /
6.2 %. The tentative band is wide (0.23-0.24 of the photos) and only 0.65-0.71 right: it is a suggestion.

**With the new geographic prior of the app** (section 9: `logit = score / T + ln g`, g = 1 / 0.5 / 0.3 / 0.1 / 1,
pack thresholds; photos with a resolved ecoregion: test 3 196 + 305 negatives, independent 1 917 + 106 negatives; same
photos for both rows, `geo/geo_final2.txt`):

| Set | Prior | Top-1 / top-5 | RAG_CORE cov. / prec. | RAG_TENTATIVE cov. / prec. | Negatives: core / tentative |
|---|---|---|---|---|---|
| Test | none | 0.899 / 0.979 | 0.686 / 0.980 | 0.234 / 0.707 | 0.089 / 0.348 |
| Test | new app prior | **0.905 / 0.981** | 0.693 / 0.983 | 0.230 / 0.704 | 0.079 / 0.380 |
| Independent | none | 0.840 / 0.949 | 0.652 / 0.977 | 0.222 / 0.675 | 0.066 / 0.387 |
| Independent | new app prior | **0.847 / 0.953** | 0.671 / 0.977 | 0.203 / 0.677 | 0.075 / 0.387 |

Geography adds +0.6 / +0.7 points of top-1 and keeps the precision of the confident answers (0.983 / 0.977).

`verify_pack.py` was rewritten (no torch needed): manifest and file check, SHA-256 of every file, index vs catalogue
keys, session with the manifest level, self-test and the app chain on a split (it calls `eval_pack_ort.py`, which now
honours `cropMode` and `sessionOptimization`).

Files (`pack_final/`, not committed):

```
biodex_bioclip2_v3.onnx        aee44cd78984b1ea0f41364f881071040c4b648c124d73803d038d30eaac4bf7   (609.11 MB)
species_index_v3.bin           9dd4d8ae870385438f58fd506d77cd75654ee8f8a2b574568379b500a05eccd7   (2.19 MB)
species_pack.json              dd5c56c3f47b36ec23adb82c8dcb82b4de2d1c220b50d765dc9820edb41f05cc
species_catalog_v2.json        48e47f343918efca837bd87df53135bbd901903afddfa075a17c37d12cfa112e   (0.38 MB)
reference_provenance.jsonl     0709da07aa431ef076252f29321f6f7befdf4414012efb335d70fc4cf1d085aa   (3.20 MB)
build_report.json              57879d2d5cd38e53634ef233509d3b99603d310dedc02c1bb30f146f882db73f

## 12. Species catalogue v2 (2026-11-09)

`tools/species/build_catalog_v2.py` builds `species_catalog_v2.json` (same wrapper and fields as v1, `version: 2`) for the
202 species of the v3 index (`pack_best`, BioCLIP 2 + text: 197 with photos plus 5 recognised by text; `hypselodoris_picta` is merged into `felimare_picta` and dropped). The species are read from the SPEX metadata of `species_index_v3.bin` (`--index`). Output and cache live outside the repository
(`C:/rerun_v9/runs/_species_catalog_v2/`: catalogue, `names_provenance.jsonl`, `summary.json`).

* **Identity.** `speciesKey` never changes. The AphiaID is checked against WoRMS REST; 16 v1 AphiaIDs belonged to another
  taxon and are replaced by the one WoRMS gives for the exact name (listed in `summary.json`, `name_mismatch`). Names that
  WoRMS no longer accepts are only reported (`not_accepted`); the key and the displayed scientific name stay.
* **Common names (en, es, fr, de, it, pt).** Rule, same for the six languages: (1) en/fr/es come from the FAO ASFIS list
  (`ASFIS_sp_2026.1.csv`, https://www.fao.org/fishery/en/collection/asfis; FAO statistical databases are CC BY 4.0, credit FAO)
  when the species is in it, with the "(=...)" alternatives of ASFIS removed; (2) otherwise the v1 name is kept unless it is
  empty, identical to the English one in a non-English language, several names in one string, listed by WoRMS/GBIF only for
  another language (Catalan, Galician...) or of another taxon (manual review); only then the WoRMS/GBIF name with the exact
  ISO-639-3 language, the one repeated most across both sources; if none, the scientific name. Only the first letter is
  capitalised (Title Case is undone; German keeps its nouns). `names_provenance.jsonl` gives the real source of each name
  (`asfis` / `v1` / `worms` / `gbif` / `scientific`) and why a v1 name was replaced.
* **Descriptions.** Per-language templates filled only with WoRMS (group, class/order/family, marine/brackish/freshwater/
  terrestrial flags), the IUCN code already in the catalogue and the number of MEOW ecoregions (Mediterranean = the seven
  ecoregions of province "Mediterranean Sea"); cosmopolitan species say so. No sizes, diets or other facts.
* **`group` (optional, new).** Stable key derived from the WoRMS classification: `bony_fish`, `sharks_rays`, `turtles`,
  `mammals`, `cephalopods`, `other_molluscs`, `crustaceans`, `echinoderms`, `corals_anemones`, `jellyfish_hydrozoans`,
  `sponges`, `polychaetes`, `ascidians`, `seagrasses`, `algae`. `SpeciesCatalogEntry.group` is nullable; Gson ignores
  unknown fields, so older apps read the file unchanged.
* **Tests.** `SpeciesCatalogV2Test` parses a v2 sample (with an unknown extra field), checks that the info tab picks
  `descriptions[lang]` with English fallback (same expression as `SpeciesInfoTabContent`) and, if present, parses the generated
  file (202 species, six names and descriptions each).

```bash
P=C:/rerun_v9/runs/_species_precision/pack_best
python -I tools/species/build_catalog_v2.py --catalog $P/species_catalog_v1.json --index $P/species_index_v3.bin \
    --hierarchy C:/rerun_v9/runs/_species_2026-11-08/hf_pack/ecoregion_hierarchy_v1.json --out C:/rerun_v9/runs/_species_catalog_v2
```
