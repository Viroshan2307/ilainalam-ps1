package com.ilainalam.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One class the vision model can predict, from labels.json (written by notebook 01). */
data class LabelInfo(
    val raw: String,        // e.g. "Tomato___Early_blight" — also the key into kb.json
    val crop: String,       // e.g. "Tomato"
    val condition: String,  // e.g. "Early blight"
    val healthy: Boolean,
)

/** Pre-written advice in one language (kb.json "hi"/"ta"/"ml"/"te"/"kn" blocks), shown + read aloud in that language. */
data class KbLocal(
    val name: String?,
    val summary: String?,
    val todo: List<String>,
    val organic: List<String>,
    val chemical: List<String>,
    val expert: String?,
) {
    val hasContent: Boolean get() = summary != null || todo.isNotEmpty() || organic.isNotEmpty() || chemical.isNotEmpty()
}

/** One knowledge-base entry (kb.json). Only these English facts are given to Llama. */
data class KbEntry(
    val crop: String,
    val disease: String,
    val pathogen: String,
    val symptoms: String,
    val cultural: List<String>,
    val organic: List<String>,
    val chemical: List<String>,
    val escalateIf: String,
    val ref: String,
    val local: Map<String, KbLocal>,   // "hi", "ta", "ml", "te", "kn"
) {
    val nameHi: String? get() = local["hi"]?.name
    val nameTa: String? get() = local["ta"]?.name
}

/** Severity thresholds (severity_config.json) — identical to notebook 01. */
data class SeverityConfig(
    val sMin: Int = 40, val vMin: Int = 30,
    val greenHLo: Int = 35, val greenHHi: Int = 85,
    val darkV: Int = 60, val mild: Float = 0.10f, val moderate: Float = 0.25f,
)

/**
 * "Is this a plant?" cut-offs (plant_check.json, measured by notebook 01 v3):
 *  - minGreenFraction: at 0.05, 99 % of real leaves (lab and field) pass and ~85 % of faces/textures are rejected.
 *  - minConfidence: below this the result shows "Not sure". Real field photos the model got right: 5th percentile 0.47,
 *    10th 0.55; non-plant photos: median 0.41 → 0.5.
 */
data class PlantCheck(val minGreenFraction: Float = 0.05f, val minConfidence: Float = 0.5f)

/**
 * model_config.json (notebook 03): leaf-photo gate cut-off, rare-class correction and the "Not sure" cut-off
 * measured on the corrected probabilities. Missing file → gate off, no correction, plant_check.json cut-off.
 */
data class ModelConfig(
    val gateMinLeafProb: Float = 0.5f,
    val priorTau: Float = 0f,
    val classPrior: FloatArray = FloatArray(0),
    val minConfidence: Float? = null,
)

/**
 * vision_config.json (notebook 04, vision v4): which disease model file to use, its input size, whether to
 * average the photo with its mirror image, and its "Not sure" cut-off. Missing → v3 (crop_disease_int8.pte, 224).
 */
data class VisionConfig(
    val diseaseModel: String = AppAssets.VISION_MODEL_ASSET,
    val inputSize: Int = 224,
    val ttaFlip: Boolean = false,
    val minConfidence: Float? = null,
    val version: String = "v3",
) {
    /** v3 was trained/evaluated with Resize(256)+CenterCrop(224); v4 sees the whole square, like its tests. */
    val cropInner: Boolean get() = version == "v3"
}

/** Everything bundled in app/src/main/assets, plus a list of what's missing. */
class AppAssets(context: Context) {
    val missing = mutableListOf<String>()
    val labels: List<LabelInfo>
    val kb: Map<String, KbEntry>
    val severityConfig: SeverityConfig
    val plantCheck: PlantCheck
    val modelConfig: ModelConfig
    val visionConfig: VisionConfig
    /** The leaf-photo gate model (notebook 03) is installed. */
    val hasGate: Boolean
    /** "Not sure" below this (model_config.json when present, else plant_check.json). */
    val minConfidence: Float get() = visionConfig.minConfidence ?: modelConfig.minConfidence ?: plantCheck.minConfidence
    /** The 14 crops, in labels.json order (for the "Which crop?" buttons). */
    val crops: List<String> get() = labels.map { it.crop }.distinct()

    init {
        val am = context.assets
        val names = am.list("")?.toSet() ?: emptySet()
        visionConfig = if ("vision_config.json" in names) runCatching {
            val o = JSONObject(readAsset(context, "vision_config.json"))
            val file = o.optString("disease_model", VISION_MODEL_ASSET)
            if (file !in names) VisionConfig()                  // config without its model file → stay on v3
            else VisionConfig(file, o.optInt("input_size", 224), o.optBoolean("tta_flip", false),
                if (o.has("min_confidence")) o.getDouble("min_confidence").toFloat() else null, o.optString("version", "v4"))
        }.getOrDefault(VisionConfig()) else VisionConfig()
        for (f in listOf(visionConfig.diseaseModel, "labels.json", "kb.json")) if (f !in names) missing += f

        labels = if ("labels.json" in names) parseLabels(readAsset(context, "labels.json")) else emptyList()
        kb = if ("kb.json" in names) parseKb(readAsset(context, "kb.json")) else emptyMap()
        severityConfig = if ("severity_config.json" in names) parseSeverity(readAsset(context, "severity_config.json"))
                         else SeverityConfig()
        plantCheck = if ("plant_check.json" in names) runCatching {
            val o = JSONObject(readAsset(context, "plant_check.json"))
            PlantCheck(o.optDouble("min_green_fraction", 0.05).toFloat(), o.optDouble("min_confidence", 0.5).toFloat())
        }.getOrDefault(PlantCheck()) else PlantCheck()
        modelConfig = if ("model_config.json" in names) runCatching {
            val o = JSONObject(readAsset(context, "model_config.json"))
            val pr = o.optJSONArray("class_prior")
            val cls = o.optJSONArray("classes")
            val sameOrder = cls == null || (cls.length() == labels.size && labels.indices.all { cls.getString(it) == labels[it].raw })
            ModelConfig(
                gateMinLeafProb = o.optDouble("gate_min_leaf_prob", 0.5).toFloat(),
                priorTau = if (sameOrder) o.optDouble("prior_tau", 0.0).toFloat() else 0f,
                classPrior = if (pr != null && sameOrder) FloatArray(pr.length()) { pr.getDouble(it).toFloat() } else FloatArray(0),
                minConfidence = if (o.has("min_confidence")) o.getDouble("min_confidence").toFloat() else null,
            )
        }.getOrDefault(ModelConfig()) else ModelConfig()
        hasGate = GATE_MODEL_ASSET in names
    }

    companion object {
        const val VISION_MODEL_ASSET = "crop_disease_int8.pte"
        const val GATE_MODEL_ASSET = "leaf_gate_fp32.pte"

        fun readAsset(context: Context, name: String): String =
            context.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }

        private fun parseLabels(text: String): List<LabelInfo> {
            val arr = JSONArray(text)
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                LabelInfo(
                    raw = o.getString("raw"),
                    crop = o.optString("crop", ""),
                    condition = o.optString("condition", ""),
                    healthy = o.optBoolean("healthy", false),
                )
            }
        }

        private fun JSONObject.strList(key: String): List<String> {
            val a = optJSONArray(key) ?: return emptyList()
            return (0 until a.length()).map { a.getString(it).trim() }.filter { it.isNotEmpty() && it != "TODO" }
        }

        /** New format: "hi": {name, summary, todo[], organic[], chemical[], expert}. Old: name_hi / summary_hi. */
        private fun parseLocal(o: JSONObject, code: String): KbLocal? {
            val b = o.optJSONObject(code)
            val l = KbLocal(
                name = b?.optText("name") ?: o.optText("name_$code"),
                summary = b?.optText("summary") ?: o.optText("summary_$code"),
                todo = b?.strList("todo").orEmpty(),
                organic = b?.strList("organic").orEmpty(),
                chemical = b?.strList("chemical").orEmpty(),
                expert = b?.optText("expert"),
            )
            return if (l.name != null || l.hasContent) l else null
        }

        private fun JSONObject.optText(key: String): String? =
            optString(key, "").takeIf { it.isNotBlank() && it != "TODO" }

        private fun parseKb(text: String): Map<String, KbEntry> {
            val root = JSONObject(text)
            val out = HashMap<String, KbEntry>()
            for (key in root.keys()) {
                val o = root.optJSONObject(key) ?: continue   // skips "_readme"
                out[key] = KbEntry(
                    crop = o.optString("crop", ""),
                    disease = o.optString("disease", ""),
                    pathogen = o.optString("pathogen", ""),
                    symptoms = o.optString("symptoms", ""),
                    cultural = o.strList("cultural"),
                    organic = o.strList("organic"),
                    chemical = o.strList("chemical"),
                    escalateIf = o.optString("escalate_if", ""),
                    ref = o.optString("ref", ""),
                    local = listOf("hi", "ta", "ml", "te", "kn").mapNotNull { code -> parseLocal(o, code)?.let { code to it } }.toMap(),
                )
            }
            return out
        }

        private fun parseSeverity(text: String): SeverityConfig {
            val o = JSONObject(text)
            return SeverityConfig(
                sMin = o.optInt("S_MIN", 40), vMin = o.optInt("V_MIN", 30),
                greenHLo = o.optInt("GREEN_H_LO", 35), greenHHi = o.optInt("GREEN_H_HI", 85),
                darkV = o.optInt("DARK_V", 60),
                mild = o.optDouble("MILD", 0.10).toFloat(), moderate = o.optDouble("MODERATE", 0.25).toFloat(),
            )
        }
    }
}
