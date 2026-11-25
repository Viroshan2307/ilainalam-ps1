# IlaiNalam — offline crop-disease detection & advice (Android)

**Bharat AI-SoC Challenge 2026–27 · Problem Statement 1**
Photo of a leaf → disease + severity + photo-quality check → advice from **Llama 3.2 1B running on the phone through ExecuTorch**. No internet permission at all (the only permission is the camera, for the live guide).

| Part | What runs | Where |
|---|---|---|
| 0. Leaf-photo check | MobileNetV3-Small FP32 (`leaf_gate_fp32.pte`, notebook 03): "is this a crop-leaf photo?" — people, objects and green non-plant things are rejected before any disease is named | ExecuTorch `Module` + XNNPACK, Arm CPU |
| Disease detection | **v4** (notebook 04, when `vision_config.json` is in assets): EfficientNet-B2, 288 px, INT8 8.5 MB (`crop_disease_v4_int8.pte`), balanced sampling + CutMix/MixUp + EMA + background swap + PlantDoc: **99.5 % lab, 72.5 % real field photos / 83 % with the crop chosen**; no Septoria↔leaf-mould or peach↔orange mistakes on the test photos. **v3** fallback: MobileNetV3-Large INT8, 224 px (`crop_disease_int8.pte`: 98.2 % lab, 66 % field / ~77 % with crop chosen) | ExecuTorch `Module` + XNNPACK, Arm CPU |
| Multi-photo scan | Up to 3 leaf photos of the same plant: disease probabilities averaged, severity = mean; each photo keeps its own Leaf / Spots view. Photos that are not a crop leaf are not added | Kotlin |
| Live camera guide | CameraX preview with the exact analysis square; per-frame hint (dark / bright / no leaf / hold steady / good) — hints only, never blocks. The phone's camera app stays as a fallback | CameraX, Kotlin |
| 1. Leaf | Background colour measured on the border → leaf separated, holes filled, shadows/rim ignored (`LeafAnalysis.kt`) | Kotlin |
| 2. Spots + severity | Leaf pixels far from the leaf's own healthy colour = spots; severity = spots ÷ leaf (same as notebook 01) | Kotlin |
| Photo quality | Blur, light, busy background, leaf size → Good / Fair / Poor + tips | Kotlin |
| Advice | Llama 3.2 1B-Instruct 8da4w (`llama3_2_1b_8da4w.pte`) grounded in `kb.json`, with the farmer's state + season; urgency line and "Get expert help" (Kisan Call Centre) for severe or uncertain results | ExecuTorch `LlmModule` + XNNPACK |
| Ask the advisor | General farming questions to the same Llama, no photo, offline; never gives doses | ExecuTorch `LlmModule` + XNNPACK |
| Languages | English / हिंदी / தமிழ் / മലയാളം / తెలుగు / ಕನ್ನಡ interface and disease cards for all 38 classes; Llama answers in **English or Hindi** (Llama 3.2 supports Hindi; for the other languages the KB card is shown first); offline read-aloud | Android Text-to-Speech |

---

## Setup (first time, about 20 minutes)

### 1. Open the project
1. Unzip `IlaiNalam.zip`.
2. Android Studio ▸ **File ▸ Open** ▸ select the `IlaiNalam` folder.
3. Wait for **Gradle sync** (the laptop needs internet for this — it downloads ExecuTorch and the Android libraries once).
   - If Android Studio offers to upgrade the Android Gradle Plugin, you can accept.

### 2. Vision model
**v4 is already in the zip's assets.** If you retrain, from `ps1_vision_outputs_v4.zip` (notebook 04) copy into `app/src/main/assets/`
- `crop_disease_v4_int8.pte` **or** `crop_disease_v4_fp32.pte` (whichever the zip contains — the notebook picks one)
- `vision_config.json` (tells the app which file, 288 px, mirror-average off, "Not sure" cut-off 0.5)
- `labels.json` (replace — same 38 classes)

Keep `crop_disease_int8.pte` (v3) in assets: if `vision_config.json` is missing or names a file that is not there, the app uses v3 automatically. The leaf-photo check, Leaf/Spots view and severity are unchanged.

**v3 (from `ps1_vision_outputs_v3.zip`):**
The project zip already contains these in **`app/src/main/assets/`**. If you retrain, replace all three from the same zip:
- `crop_disease_int8.pte`
- `labels.json`
- `plant_check.json` ("Is this a plant?" colour cut-off)

From `ps1_gate_outputs.zip` (notebook 03) — **optional, the app works without them** (no leaf-photo AI check, no rare-class correction):
- `leaf_gate_fp32.pte` (leaf-photo check — from `ps1_gate_fp32.zip`, the extra FP32 cell; do NOT use the INT8 gate)
- `model_config.json` (its cut-off, the rare-class correction τ and the "Not sure" cut-off)

> `kb.json` and `severity_config.json` are already there. Replace `kb.json` with the team's completed knowledge base when it's ready — no code change needed.

### 3. Run on the phone
1. On the phone: Settings ▸ About phone ▸ tap **Build number** 7× ▸ Developer options ▸ **USB debugging** on.
2. Connect by USB, allow the prompt, pick the phone in Android Studio's device list, press **Run ▶**.

### 4. Load Llama on the phone (from `ps1_llm_outputs.zip`)
Get `llama3_2_1b_8da4w.pte` and `tokenizer.model` onto the phone (USB file transfer to *Downloads*, or download them from Drive on the phone). Then either:
- **In the app (no cable):** first launch → choose language → *Set up AI advisor* → **Select model file** → **Select tokenizer file**. The app copies them in once (~1 min) and loads the model.
- **Or with adb (developer):**
  ```
  adb push llama3_2_1b_8da4w.pte /sdcard/Android/data/com.ilainalam/files/llm/
  adb push tokenizer.model       /sdcard/Android/data/com.ilainalam/files/llm/
  ```
  then reopen the app.

### 5. Try it
- **Self-test first:** Home ▸ tap the AI-advisor status line ▸ **Test models**. It runs both models once and shows timings, or the exact error.
- **Scan a leaf** (tip: on white paper, in daylight). The screen shows each step — *finding the leaf → checking spots → identifying the disease*.
- On the result, switch **Photo / Leaf / Spots** to see what the app treated as leaf (background greyed out) and which spots it counted.
- **Live camera with guide** (first time: allow the camera) — fit one leaf in the square, wait for *Good — take the photo*.
- On a result, **Add another leaf photo** (up to 3) — the diagnosis is combined; use the *Photo 1 / 2 / 3* chips to see each photo's Leaf / Spots view.
- **Get advice** → **Listen**. In Tamil, Malayalam, Telugu or Kannada mode the disease card from `kb.json` is shown and read aloud first, and Llama's English answer is below as *More details*. In Hindi mode Llama itself answers in Hindi.
Open **Speed & memory** (gauge icon on Home) → **unplug the phone** → **Run speed test** (also measures power) → ask Llama one question (measures its power) → **Copy results** for the technical report. Do this on both the Nord 4 and the Moto G34.

---

## Knowledge base
See **[`../kb/KB_TEMPLATE.md`](../kb/KB_TEMPLATE.md)**. `app/src/main/assets/kb.json` has all 38 classes in English + `hi`, `ta`, `ml`, `te`, `kn`. The Tamil and English text was written for the team to check; Hindi, Malayalam, Telugu and Kannada are machine-drafted from the English card (same facts, simple words) — if anyone can get a local KVK/agri officer to glance at them, do. Edit, then rebuild — no code changes needed.

## Project map
```
app/src/main/
  assets/                 crop_disease_int8.pte*, leaf_gate_fp32.pte, labels.json, plant_check.json, model_config.json, kb.json, severity_config.json   (*GitHub Release)
  java/com/ilainalam/
    MainActivity.kt       navigation
    IlaiNalamApp.kt       creates models + stores once
    ml/VisionClassifier   ExecuTorch Module → 38 probabilities
    ml/LeafAnalysis       leaf segmentation + spot detection (pure Kotlin, unit-testable)
    ml/SeverityAnalyzer   severity level + Leaf / Spots views
    ml/PhotoQuality       blur / light / background / leaf-size checks (thresholds at the top)
    ml/LlmEngine          ExecuTorch LlmModule, streaming, stats, model import
    ml/PromptBuilder      Llama-3 prompt — same as notebook 02
    i18n/Strings.kt       ALL interface text in 6 languages (EN, HI, TA, ML, TE, KN)
    ui/                   screens (Compose)
```

## Tuning
- **Photo-quality thresholds** — top of `ml/PhotoQuality.kt`. Calibrated on 300 PlantVillage test photos (background texture median 15.8, p95 27.8 → Fair ≥ 35, Poor ≥ 55; leaf cover p5 0.30 → Fair < 0.20). Re-check with 20–30 real phone photos.
- **"Not sure" cut-off** — `min_confidence` in `model_config.json` (notebook 03), else `plant_check.json` (0.5). Below it no disease is named and no advice is offered.
- **Rare-class correction** — `prior_tau` in `model_config.json` (0 = off).
- **Self-recovery** — `STEP_TIMEOUT_MS` (7 s) and `ATTEMPTS` (3) in `ml/VisionClassifier.kt`.
- **Llama** — `TEMPERATURE` and `SEQ_LEN` in `ml/LlmEngine.kt`; system prompt in `ml/PromptBuilder.kt`.

## Troubleshooting
| Problem | Fix |
|---|---|
| App shows *Model files missing* | Step 2 — copy `crop_disease_int8.pte` + `labels.json` into `app/src/main/assets/` and rebuild |
| Gradle can't find `org.pytorch:executorch-android:1.5.1` | Check the latest version on Maven Central and change it in `app/build.gradle.kts` |
| "Model has N outputs but labels.json has M" | Use `labels.json` from the **same** zip as the `.pte` |
| Live camera stays black / errors | Tap **Use the phone's camera app** — same scan, no CameraX |
| Read-aloud says voice missing | Phone Settings ▸ Text-to-speech ▸ install the Hindi/Tamil voice data |
| Llama fails to load | Check both files are complete (the .pte is ~1 GB); re-import |

Llama 3.2 is licensed under the Llama 3.2 Community License — *Built with Llama*.
