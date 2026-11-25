package com.ilainalam.ml

import com.ilainalam.data.KbEntry
import com.ilainalam.data.LabelInfo
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Builds the Llama-3 chat prompt — same structure as build_prompt() in notebook 02.
 * The model may ONLY use the facts in CONTEXT (diagnosis, severity, knowledge-base entry).
 */
object PromptBuilder {
    const val SYSTEM =
        "You are IlaiNalam, an offline crop-health assistant for Indian farmers. " +
        "Use ONLY the facts in CONTEXT. Never invent pesticide names or doses; if the context has none, say " +
        "'consult your local Krishi Vigyan Kendra'. Answer in simple English in at most 6 short bullet points: " +
        "what it is, how serious it is, what to do today, organic option, chemical option (say 'follow label dose'), " +
        "when to call an expert."

    private fun pct(f: Float) = "${(f * 100).roundToInt()}%"

    /**
     * @param place e.g. "Tamil Nadu, India" (null if not chosen) · @param season e.g. "Rabi (winter)"
     * @param minConfidence the app's "Not sure" cut-off (model_config.json / plant_check.json)
     */
    fun context(
        label: LabelInfo, kb: KbEntry?, confidence: Float, severity: SeverityResult?,
        place: String? = null, season: String? = null, minConfidence: Float = 0.5f,
    ): String {
        val o = JSONObject()
        o.put("diagnosis", "${kb?.crop ?: label.crop} — ${kb?.disease ?: label.condition}")
        if (place != null) o.put("farmer_location", place)
        if (season != null) o.put("season", season)
        o.put("model_confidence", pct(confidence))
        if (severity != null && !label.healthy)
            o.put("severity", "${severity.level.name.lowercase().replaceFirstChar { it.uppercase() }} (${pct(severity.fraction)} of leaf area affected)")
        if (kb != null) {
            if (kb.pathogen.isNotBlank()) o.put("cause", kb.pathogen)
            o.put("symptoms", kb.symptoms)
            o.put("cultural_practices", JSONArray(kb.cultural))
            o.put("organic_options", JSONArray(kb.organic))
            o.put("chemical_options", JSONArray(kb.chemical))
            o.put("escalate_if", kb.escalateIf)
        } else {
            o.put("note_kb", "No knowledge-base entry for this disease yet. Give only general crop hygiene advice and tell the farmer to consult the local Krishi Vigyan Kendra or Kisan Call Centre 1800-180-1551.")
        }
        if (confidence < minConfidence)
            o.put("note", "Low confidence: ask the farmer to retake the photo in daylight, one leaf on a plain background filling the frame.")
        return o.toString(1).replace("\\/", "/")
    }

    /**
     * Llama 3.2 officially supports Hindi, so in Hindi mode the answer is written in Hindi.
     * (Tamil, Malayalam, Telugu and Kannada are not supported by Llama 3.2 → those modes show the pre-written
     * kb.json advice in the farmer's language and Llama's details in English.)
     */
    private const val HINDI_RULE = "Answer in simple Hindi (Devanagari script). Keep product names and numbers as written."
    private fun sys(base: String, hindi: Boolean) =
        if (hindi) base.replace("in simple English", "in simple Hindi") + " " + HINDI_RULE else base
    private fun ask(q: String, hindi: Boolean) = if (hindi) "$q\nउत्तर सरल हिंदी में दें।" else q

    private fun turn(role: String, text: String) = "<|start_header_id|>$role<|end_header_id|>\n\n$text<|eot_id|>"

    /** First answer after a scan. */
    fun advice(ctx: String, question: String = "Give me advice for my crop.", hindi: Boolean = false): String =
        "<|begin_of_text|>" + turn("system", sys(SYSTEM, hindi)) +
        turn("user", "CONTEXT:\n$ctx\n\n" + ask(question, hindi)) +
        "<|start_header_id|>assistant<|end_header_id|>\n\n"

    /** Follow-up question. The previous answer is shortened so everything fits the 1024-token context. */
    fun followUp(ctx: String, previousAnswer: String, question: String, hindi: Boolean = false): String =
        "<|begin_of_text|>" + turn("system", sys(SYSTEM, hindi)) +
        turn("user", "CONTEXT:\n$ctx\n\nGive me advice for my crop.") +
        turn("assistant", previousAnswer.take(if (hindi) 450 else 700)) +     // Hindi uses more tokens per word
        turn("user", ask(question.take(300), hindi)) +
        "<|start_header_id|>assistant<|end_header_id|>\n\n"

    /** "Ask the advisor": general farming questions, no photo. Llama may use its own knowledge, but never doses. */
    const val GENERAL_SYSTEM =
        "You are IlaiNalam, an offline farming advisor for small farmers in India. Give practical, safe, general " +
        "guidance in simple English, in at most 6 short bullet points. Never give pesticide or fertiliser doses, " +
        "quantities or brand names: say 'confirm the dose with your Krishi Vigyan Kendra or Kisan Call Centre " +
        "1800-180-1551'. Prefer low-cost and organic options first. If you are not sure, say so plainly."

    fun general(place: String?, season: String?, previous: Pair<String, String>?, question: String, hindi: Boolean = false): String {
        val where = listOfNotNull(place?.let { "Location: $it." }, season?.let { "Season: $it." }).joinToString(" ")
        val sb = StringBuilder("<|begin_of_text|>").append(turn("system", sys(GENERAL_SYSTEM, hindi) + if (where.isNotEmpty()) " $where" else ""))
        previous?.let { (q, a) -> sb.append(turn("user", q.take(300))).append(turn("assistant", a.take(if (hindi) 400 else 600))) }
        sb.append(turn("user", ask(question.take(400), hindi)))
        return sb.append("<|start_header_id|>assistant<|end_header_id|>\n\n").toString()
    }
}
