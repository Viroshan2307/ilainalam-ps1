package com.ilainalam.ml

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import com.ilainalam.data.SeverityConfig

enum class SeverityLevel { MILD, MODERATE, SEVERE }

data class SeverityResult(
    val leafFound: Boolean,       // step 1: was a leaf separated from the background?
    val fraction: Float,          // step 2: share of the leaf that is spots
    val level: SeverityLevel,
    val leafCoverage: Float,      // share of the photo that is leaf (also used by the photo-quality check)
    val lesionMask: BooleanArray, // size*size, row-major
    val leafMask: BooleanArray,
    val size: Int,
    val millis: Long,
    val greenFraction: Float = 1f, // share of the leaf that is plant-green ("Is this a plant?" check)
)

/**
 * Leaf → spots → severity, using [LeafAnalysis] (background rejected first, then spots measured on the
 * leaf only). Mild < cfg.mild ≤ Moderate < cfg.moderate ≤ Severe.
 */
class SeverityAnalyzer(private val cfg: SeverityConfig) {

    fun analyze(bmp224: Bitmap): SeverityResult {
        val t0 = SystemClock.elapsedRealtime()
        val w = bmp224.width
        val px = IntArray(w * w); bmp224.getPixels(px, 0, w, 0, 0, w, w)
        val a = LeafAnalysis.analyze(px, w)
        val frac = a.lesionFraction
        val level = levelFor(frac)
        return SeverityResult(a.leafFound, frac, level, a.leafCoverage, a.lesionMask, a.leafMask, w,
            SystemClock.elapsedRealtime() - t0, a.greenFraction)
    }

    /** Mild / Moderate / Severe for a spot fraction (also used for the average over several photos). */
    fun levelFor(frac: Float): SeverityLevel = when {
        frac < cfg.mild -> SeverityLevel.MILD
        frac < cfg.moderate -> SeverityLevel.MODERATE
        else -> SeverityLevel.SEVERE
    }

    /** Spots view: diseased spots painted red on the photo. */
    fun spotsOverlay(base: Bitmap, r: SeverityResult): Bitmap = paint(base, r) { i, c ->
        if (r.lesionMask[i]) Color.argb(255, (Color.red(c) * 0.3f + 255 * 0.7f).toInt(), (Color.green(c) * 0.3f).toInt(), (Color.blue(c) * 0.3f).toInt())
        else c
    }

    /** Leaf view: background greyed out so the farmer sees exactly what the app treated as leaf. */
    fun leafOverlay(base: Bitmap, r: SeverityResult): Bitmap = paint(base, r) { i, c ->
        if (r.leafMask[i]) c else {
            val g = ((Color.red(c) + Color.green(c) + Color.blue(c)) / 3 * 0.35f + 230 * 0.65f).toInt()
            Color.argb(255, g, g, g)
        }
    }

    private inline fun paint(base: Bitmap, r: SeverityResult, f: (Int, Int) -> Int): Bitmap {
        val small = Bitmap.createScaledBitmap(base, r.size, r.size, true)
        val px = IntArray(r.size * r.size); small.getPixels(px, 0, r.size, 0, 0, r.size, r.size)
        for (i in px.indices) px[i] = f(i, px[i])
        val out = Bitmap.createBitmap(r.size, r.size, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, r.size, 0, 0, r.size, r.size)
        return Bitmap.createScaledBitmap(out, base.width, base.height, false)   // crisp mask edges
    }
}
