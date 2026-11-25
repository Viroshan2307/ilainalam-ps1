package com.ilainalam.ml

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs

enum class QualityGrade { GOOD, FAIR, POOR }

/** Each issue maps to a tip string in i18n/Strings.kt. */
enum class QualityIssue { BLURRY, TOO_DARK, TOO_BRIGHT, BUSY_BACKGROUND, LEAF_TOO_SMALL }

data class QualityReport(
    val grade: QualityGrade,
    val issues: List<QualityIssue>,
    val sharpness: Float,       // variance of Laplacian
    val brightness: Float,      // mean luma 0–255
    val backgroundTexture: Float, // mean |Laplacian| in the border strip: plain paper ≈ 0, soil/grass/clutter ≫ 4
    val leafCoverage: Float,
)

/**
 * Checks the photo before trusting the diagnosis. The model was trained on single leaves on plain
 * backgrounds in even light, so photos like that are the most accurate.
 *
 * Thresholds calibrated on 300 PlantVillage test photos (18 Nov) so ~95% rate Good; re-check with real field photos.
 */
object PhotoQuality {
    // ---- tunable thresholds -------------------------------------------------
    const val SHARP_POOR = 15f;  const val SHARP_FAIR = 40f
    const val DARK_POOR = 40f;   const val DARK_FAIR = 65f
    const val BRIGHT_POOR = 240f; const val BRIGHT_FAIR = 225f
    const val BG_TEX_FAIR = 35f; const val BG_TEX_POOR = 55f  // PlantVillage test photos: p5 4.7, median 15.8, p95 27.8
    const val LEAF_POOR = 0.10f; const val LEAF_FAIR = 0.20f  // PlantVillage test photos: p5 0.30, median 0.56
    private const val BORDER = 16

    fun assess(bmp224: Bitmap, sev: SeverityResult): QualityReport {
        val w = bmp224.width; val h = bmp224.height; val n = w * h
        val px = IntArray(n); bmp224.getPixels(px, 0, w, 0, 0, w, h)
        val y = FloatArray(n) { i -> val c = px[i]; 0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c) }

        val brightness = y.average().toFloat()

        // Sharpness: variance of the 4-neighbour Laplacian
        var sum = 0.0; var sumSq = 0.0; var cnt = 0
        for (r in 1 until h - 1) for (c in 1 until w - 1) {
            val i = r * w + c
            val lap = (y[i - 1] + y[i + 1] + y[i - w] + y[i + w] - 4 * y[i]).toDouble()
            sum += lap; sumSq += lap * lap; cnt++
        }
        val mean = sum / cnt
        val sharpness = (sumSq / cnt - mean * mean).toFloat()

        // Background: edge texture in a border strip. A plain sheet of paper (or a leaf filling the
        // frame) is smooth; soil, grass and clutter are full of edges.
        var tex = 0.0; var ring = 0
        for (r in 1 until h - 1) for (c in 1 until w - 1) {
            if (r < BORDER || r >= h - BORDER || c < BORDER || c >= w - BORDER) {
                val i = r * w + c
                tex += abs(y[i - 1] + y[i + 1] + y[i - w] + y[i + w] - 4 * y[i]); ring++
            }
        }
        val bgTexture = (tex / ring).toFloat()

        val issues = mutableListOf<QualityIssue>()
        var grade = QualityGrade.GOOD
        fun flag(issue: QualityIssue, g: QualityGrade) { issues += issue; if (g.ordinal > grade.ordinal) grade = g }

        when {
            sharpness < SHARP_POOR -> flag(QualityIssue.BLURRY, QualityGrade.POOR)
            sharpness < SHARP_FAIR -> flag(QualityIssue.BLURRY, QualityGrade.FAIR)
        }
        when {
            brightness < DARK_POOR -> flag(QualityIssue.TOO_DARK, QualityGrade.POOR)
            brightness < DARK_FAIR -> flag(QualityIssue.TOO_DARK, QualityGrade.FAIR)
            brightness > BRIGHT_POOR -> flag(QualityIssue.TOO_BRIGHT, QualityGrade.POOR)
            brightness > BRIGHT_FAIR -> flag(QualityIssue.TOO_BRIGHT, QualityGrade.FAIR)
        }
        when {
            bgTexture >= BG_TEX_POOR -> flag(QualityIssue.BUSY_BACKGROUND, QualityGrade.POOR)
            bgTexture >= BG_TEX_FAIR -> flag(QualityIssue.BUSY_BACKGROUND, QualityGrade.FAIR)
        }
        when {
            sev.leafCoverage < LEAF_POOR -> flag(QualityIssue.LEAF_TOO_SMALL, QualityGrade.POOR)
            sev.leafCoverage < LEAF_FAIR -> flag(QualityIssue.LEAF_TOO_SMALL, QualityGrade.FAIR)
        }
        return QualityReport(grade, issues, sharpness, brightness, bgTexture, sev.leafCoverage)
    }
}
