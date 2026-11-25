# Filling in kb.json (Person B)

`app/src/main/assets/kb.json` holds the facts the app is allowed to tell farmers. Llama 3.2 only rephrases the **English** fields. The **`hi`** and **`ta`** blocks are shown as they are, and read aloud, when the farmer picks Hindi or Tamil. No code changes are needed: edit the file, rebuild, and run.

## One entry
```json
"Tomato___Early_blight": {
  "crop": "Tomato", "disease": "Early blight", "pathogen": "Alternaria solani (fungus)",
  "symptoms": "…",
  "cultural": ["…", "…"],          // what to do today / prevention
  "organic":  ["…"],
  "chemical": ["Product + formulation — follow the label dose and pre-harvest interval"],
  "escalate_if": "…",              // when to call an expert
  "ref": "https://… (ICAR / TNAU Agritech / state agri dept page)",
  "hi": { "name": "…", "summary": "…", "todo": ["…"], "organic": ["…"], "chemical": ["…"], "expert": "…" },
  "ta": { "name": "…", "summary": "…", "todo": ["…"], "organic": ["…"], "chemical": ["…"], "expert": "…" }
}
```
`Tomato___Early_blight` and `Tomato___healthy` in kb.json are complete examples. Copy their shape.

## Rules
- **The key must match `labels.json` exactly** (copy-paste it). Keys are listed below.
- **Never write a dose.** Say "follow the label dose". For a chemical, give the product name and formulation only, and only if your source names it.
- **Put a source in every `ref`.** Judges may ask.
- `hi`/`ta` must say the same thing as the English. A native speaker must check them.
- Short sentences: they are read aloud.
- Leave a field as `""` or `[]` if you don't have it. The app hides empty sections, and shows "Translation coming soon" if a language block is missing.

## The 38 keys (from labels.json)
- `Apple___Apple_scab`
- `Apple___Black_rot`
- `Apple___Cedar_apple_rust`
- `Apple___healthy`
- `Blueberry___healthy`
- `Cherry_(including_sour)___Powdery_mildew`
- `Cherry_(including_sour)___healthy`
- `Corn_(maize)___Cercospora_leaf_spot Gray_leaf_spot`
- `Corn_(maize)___Common_rust_`
- `Corn_(maize)___Northern_Leaf_Blight`
- `Corn_(maize)___healthy`
- `Grape___Black_rot`
- `Grape___Esca_(Black_Measles)`
- `Grape___Leaf_blight_(Isariopsis_Leaf_Spot)`
- `Grape___healthy`
- `Orange___Haunglongbing_(Citrus_greening)`
- `Peach___Bacterial_spot`
- `Peach___healthy`
- `Pepper,_bell___Bacterial_spot`
- `Pepper,_bell___healthy`
- `Potato___Early_blight`
- `Potato___Late_blight`
- `Potato___healthy`
- `Raspberry___healthy`
- `Soybean___healthy`
- `Squash___Powdery_mildew`
- `Strawberry___Leaf_scorch`
- `Strawberry___healthy`
- `Tomato___Bacterial_spot`
- `Tomato___Early_blight`
- `Tomato___Late_blight`
- `Tomato___Leaf_Mold`
- `Tomato___Septoria_leaf_spot`
- `Tomato___Spider_mites Two-spotted_spider_mite`
- `Tomato___Target_Spot`
- `Tomato___Tomato_Yellow_Leaf_Curl_Virus`
- `Tomato___Tomato_mosaic_virus`
- `Tomato___healthy`
