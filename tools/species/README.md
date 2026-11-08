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
| `build_pack.py`, `verify_pack.py` | Build the v2 pack (encoder, SPEX index, `species_pack.json`, provenance) and check it as the app decides |
| `biodex_common.py`, `gpu_guard.py` | Shared helpers (app parity) and the lab GPU guard |

Run them with `python -I` (Python 3.12 with torch, open_clip, timm, transformers, onnxruntime, Pillow, requests).
