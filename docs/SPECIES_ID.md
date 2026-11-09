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


## 9. Species catalogue v2 (2026-11-09)

`tools/species/build_catalog_v2.py` builds `species_catalog_v2.json` (same wrapper and fields as v1, `version: 2`) for the
198 species of the v2 index (the 5 without references are dropped). Output and cache live outside the repository
(`C:/rerun_v9/runs/_species_catalog_v2/`: catalogue, `names_provenance.jsonl`, `summary.json`).

* **Identity.** `speciesKey` never changes. The AphiaID is checked against WoRMS REST; 16 v1 AphiaIDs belonged to another
  taxon and are replaced by the one WoRMS gives for the exact name (listed in `summary.json`, `name_mismatch`). Names that
  WoRMS no longer accepts are only reported (`not_accepted`); the key and the displayed scientific name stay.
* **Common names (en, es, fr, de, it, pt).** Only WoRMS vernaculars (CC BY 4.0) or, if WoRMS has none in that language,
  GBIF vernacular names (source recorded). A v1 name is kept if it appears in either source; if it does not and it is not
  identical to the English one it is kept and flagged `v1_unverified`; otherwise the scientific name is used. First letter
  upper-cased. `names_provenance.jsonl` gives source and detail per species and language.
* **Descriptions.** Per-language templates filled only with WoRMS (group, class/order/family, marine/brackish/freshwater/
  terrestrial flags), the IUCN code already in the catalogue and the number of MEOW ecoregions (Mediterranean = the seven
  ecoregions of province "Mediterranean Sea"); cosmopolitan species say so. No sizes, diets or other facts.
* **`group` (optional, new).** Stable key derived from the WoRMS classification: `bony_fish`, `sharks_rays`, `turtles`,
  `mammals`, `cephalopods`, `other_molluscs`, `crustaceans`, `echinoderms`, `corals_anemones`, `jellyfish_hydrozoans`,
  `sponges`, `polychaetes`, `ascidians`, `seagrasses`, `algae`. `SpeciesCatalogEntry.group` is nullable; Gson ignores
  unknown fields, so older apps read the file unchanged.
* **Tests.** `SpeciesCatalogV2Test` parses a v2 sample (with an unknown extra field), checks that the info tab picks
  `descriptions[lang]` with English fallback (same expression as `SpeciesInfoTabContent`) and, if present, parses the generated
  file (198 species, six names and descriptions each).

```bash
P=C:/rerun_v9/runs/_species_2026-11-09/pack_v2_bioclip_fp32_ccby
python -I tools/species/build_catalog_v2.py --catalog $P/species_catalog_v1.json --provenance $P/reference_provenance.jsonl     --hierarchy C:/rerun_v9/runs/_species_2026-11-08/hf_pack/ecoregion_hierarchy_v1.json --out C:/rerun_v9/runs/_species_catalog_v2
```
