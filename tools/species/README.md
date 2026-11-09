# tools/species

Scripts that build and evaluate the BioDex species pack (visual RAG). Results, decision and full command list:
[docs/SPECIES_ID.md](../../docs/SPECIES_ID.md). Images, embeddings, weights and packs stay outside the repository.

| Script | What it does |
|---|---|
| `fetch_inat_dataset.py` | Licensed (CC0 / CC-BY / CC-BY-SA) iNaturalist photos per catalogue species, exact taxon id, one per observation, with provenance |
| `make_splits.py` | Train / val / test split by observer + near-duplicate removal (dHash) |
| `diagnose_pack.py` | Runs a pack with the exact app chain on labelled photos (root-cause check) |
| `embed_all.py` | Embeddings of every candidate encoder (app preprocessing) |
| `eval_rag.py` | kNN retrieval top-1 / top-5 / per class, softmax calibration and open-set threshold on val |
| `confidence_signals.py` | Cosine vs margin vs softmax as confidence for "not identified" |
| `train_classifier.py`, `eval_classifier.py` | Ad hoc light classifiers (alternative b) |
| `export_variants.py`, `bench_latency.py` | ONNX fp32 / int8 export, retrieval cost of int8, CPU latency and size |
| `gemma_rerank.py`, `score_policies.py`, `gemma_verify.py` | Gemma 4 E2B as judge of the RAG candidates and the decision policies |
| `build_pack.py`, `verify_pack.py` | Build the v2 pack (encoder, SPEX index, `species_pack.json`, provenance; `--licenses cc0,cc-by` for a gallery without CC-BY-SA, `--calibrate` to fit temperature and thresholds on val) and check it as the app decides |
| `fetch_gbif_independent.py`, `fetch_commons_independent.py`, `filter_indep.py`, `tag_non_photos.py` | Evaluation photos that do not come from iNaturalist (GBIF non-iNaturalist datasets, Wikimedia Commons), near-duplicate and not-a-photo filters |
| `eval_pack.py` | Pack on a labelled set with the app chain: top-1/5, macro, coverage and precision per decision, negatives, worst species, confusions |
| `fetch_indep_coords.py`, `geo_prior_eval.py` | Coordinates of the independent photos (GBIF occurrence / Commons GeoData) and the geographic-prior experiment: port of the app (`MarineRegionResolver`, `GeoPrior`, `SpeciesReferenceIndex.search`) against a soft prior in logit space |
| `count_inat_available.py`, `count_gbif_available.py`, `fetch_taxonomy.py`, `fetch_distractors.py` | Dataset survey (CC0 / CC-BY photos available per species on iNaturalist and GBIF), GBIF lineages for text prompts, congeners used as open-set distractors |
| `fetch_inat_more.py`, `dedupe_ext.py` | Up to 250 CC0 / CC-BY reference photos per species from train-split observers only, near-duplicate removal against val / test / negatives / independent set |
| `embed_set.py`, `embed_text.py` | fp32 GPU embeddings of any image list (flip / crop variants for TTA) and text-tower embeddings of the species names (BioCLIP, BioCLIP 2) |
| `precision_lab.py`, `precision_exp.py` | Experiments of the precision study: prototypes vs kNN, references per species, linear head, text rows, TTA, distractors; calibration on val, test and independent report |
| `onnx_fp16_storage.py`, `build_pack_plus.py`, `eval_pack_ort.py` | fp16 weight storage with fp32 compute, candidate pack builder (BioCLIP 2, text rows, merges) and a stand-alone ONNX Runtime check (run it with `onnxruntime==1.20.1`) |
| `biodex_common.py`, `gpu_guard.py` | Shared helpers (app parity) and the lab GPU guard |

Run them with `python -I` (Python 3.12 with torch, open_clip, timm, transformers, onnxruntime, Pillow, requests).
