# Model files

## Already in the repository (in `android/app/src/main/assets/`)

| File | What | Size | Made by |
|---|---|---|---|
| `crop_disease_v4_int8.pte` | Disease model v4, EfficientNet-B2, INT8, 288 px, 38 classes (used by the app) | 8.5 MB | `notebooks/04_vision_v4_efficientnet.ipynb` |
| `vision_config.json` | Tells the app to use v4: file name, 288 px, single pass, "Not sure" cut-off 0.5 | — | notebook 04 |
| `crop_disease_int8.pte` | Disease model v3, MobileNetV3-Large, INT8, 224 px (automatic fallback) | 4.6 MB | `notebooks/01_vision_v3_realworld.ipynb` |
| `leaf_gate_fp32.pte` | Leaf-photo check, MobileNetV3-Small, FP32 | 6.1 MB | `notebooks/03_leaf_photo_gate.ipynb` + `helpers/03b_gate_fp32_export_cell.py` |

## Not in the repository: Llama 3.2 1B (too large for git)

| File | What |
|---|---|
| `llama3_2_1b_8da4w.pte` | Llama 3.2 1B-Instruct for ExecuTorch: 8-bit dynamic activations, 4-bit weights (group 32), 4-bit embeddings, KV cache, SDPA with KV cache, XNNPACK, max context 1024 |
| `tokenizer.model` | Llama 3.2 tokenizer |

**Download:** from the [v1.0 release](https://github.com/meranjith28/ilainalam-ps1/releases/tag/v1.0):
[`llama3_2_1b_8da4w.pte`](https://github.com/meranjith28/ilainalam-ps1/releases/download/v1.0/llama3_2_1b_8da4w.pte) ·
[`tokenizer.model`](https://github.com/meranjith28/ilainalam-ps1/releases/download/v1.0/tokenizer.model) ·
ready-to-install app [`IlaiNalam-v1.0.apk`](https://github.com/meranjith28/ilainalam-ps1/releases/download/v1.0/IlaiNalam-v1.0.apk)

**Or build them yourself:** run `notebooks/02_llama_to_executorch.ipynb` on Kaggle or Colab. You need a Hugging Face token whose account has accepted the Llama 3.2 licence (`meta-llama/Llama-3.2-1B-Instruct`).

**Put them on the phone:** copy both files to the phone, then in the app: *Set up AI advisor → Select model file → Select tokenizer file*. Or use adb:
```
adb push llama3_2_1b_8da4w.pte /sdcard/Android/data/com.ilainalam/files/llm/
adb push tokenizer.model       /sdcard/Android/data/com.ilainalam/files/llm/
```

Llama 3.2 is licensed under the Llama 3.2 Community License. Built with Llama. See `../NOTICE`.
