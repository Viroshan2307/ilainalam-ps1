package com.ilainalam.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.ilainalam.i18n.Lang
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Small persistent settings (language, first-run flag). */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("ilainalam_settings", Context.MODE_PRIVATE)

    var language: Lang
        get() = Lang.fromCode(prefs.getString("lang", null))
        set(v) = prefs.edit().putString("lang", v.code).apply()

    /** Crop chosen on the scan screen; null = "Not sure" (all 38 classes). */
    var lastCrop: String?
        get() = prefs.getString("last_crop", null)?.takeIf { it.isNotBlank() }
        set(v) = prefs.edit().putString("last_crop", v ?: "").apply()

    /** State chosen for region-aware advice (Region enum name); null = not chosen yet. */
    var region: String?
        get() = prefs.getString("region", null)?.takeIf { it.isNotBlank() }
        set(v) = prefs.edit().putString("region", v ?: "").apply()

    var languageChosen: Boolean
        get() = prefs.getBoolean("lang_chosen", false)
        set(v) = prefs.edit().putBoolean("lang_chosen", v).apply()
}

/** One saved scan for the Home screen's "Recent scans" list. */
data class HistoryItem(
    val id: String,
    val time: Long,
    val labelRaw: String,
    val crop: String,
    val condition: String,
    val confidence: Float,
    val severityLabel: String,
    val severityFrac: Float,
    val healthy: Boolean,
    val chosenCrop: String? = null,   // crop the farmer chose for this scan (null = not sure)
)

/** Keeps the last [MAX] scans on the phone: a JPEG of the leaf + a JSON index. Nothing leaves the device. */
class HistoryStore(context: Context) {
    private val dir = File(context.filesDir, "history").apply { mkdirs() }
    private val index = File(dir, "index.json")

    @Synchronized
    fun list(): List<HistoryItem> {
        if (!index.exists()) return emptyList()
        return try {
            val arr = JSONArray(index.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                HistoryItem(
                    id = o.getString("id"), time = o.getLong("time"), labelRaw = o.getString("labelRaw"),
                    crop = o.optString("crop"), condition = o.optString("condition"),
                    confidence = o.optDouble("confidence").toFloat(), severityLabel = o.optString("severityLabel"),
                    severityFrac = o.optDouble("severityFrac").toFloat(), healthy = o.optBoolean("healthy"),
                    chosenCrop = o.optString("chosenCrop", "").takeIf { it.isNotBlank() },
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    @Synchronized
    fun add(item: HistoryItem, photo: Bitmap) {
        File(dir, "${item.id}.jpg").outputStream().use { photo.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        val items = (listOf(item) + list().filter { it.id != item.id }).take(MAX)
        // delete photos that fell off the list
        val keep = items.map { "${it.id}.jpg" }.toSet()
        dir.listFiles()?.forEach { f -> if (f.name.endsWith(".jpg") && f.name !in keep) f.delete() }
        val arr = JSONArray()
        items.forEach { h ->
            arr.put(JSONObject().apply {
                put("id", h.id); put("time", h.time); put("labelRaw", h.labelRaw); put("crop", h.crop)
                put("condition", h.condition); put("confidence", h.confidence.toDouble())
                put("severityLabel", h.severityLabel); put("severityFrac", h.severityFrac.toDouble())
                put("healthy", h.healthy); put("chosenCrop", h.chosenCrop ?: "")
            })
        }
        index.writeText(arr.toString())
    }

    fun photo(id: String): Bitmap? = File(dir, "$id.jpg").takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    companion object { const val MAX = 10 }
}
