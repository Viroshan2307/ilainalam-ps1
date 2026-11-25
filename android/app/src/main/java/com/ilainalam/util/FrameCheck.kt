package com.ilainalam.util

import androidx.camera.core.ImageProxy

enum class FrameHint { PLACE_LEAF, DARK, BRIGHT, BLURRY, GOOD }

/**
 * Cheap per-frame check for the live camera guide, on the centre square of a YUV_420_888 preview frame:
 * brightness (mean Y), sharpness (variance of a Laplacian on sampled Y), and how much of the square is green
 * (U and V both below neutral). Pure Kotlin, no AI, a few ms per frame. Hints only — the photo is still checked
 * by the full scan (leaf-photo AI gate, photo quality, leaf/spots analysis).
 */
class FrameCheck(
    private val dark: Int = 55,
    private val bright: Int = 215,
    private val minSharpness: Double = 18.0,
    private val minGreen: Double = 0.12,
) {
    private var last = FrameHint.PLACE_LEAF
    private var same = 0

    fun hint(img: ImageProxy): FrameHint {
        val raw = evaluate(img)
        // small hysteresis so the text does not flicker between frames
        if (raw == last) same = 0 else if (++same >= 3) { last = raw; same = 0 }
        return last
    }

    private fun evaluate(img: ImageProxy): FrameHint {
        val w = img.width; val h = img.height
        val side = minOf(w, h); val x0 = (w - side) / 2; val y0 = (h - side) / 2
        val yP = img.planes[0]; val uP = img.planes[1]; val vP = img.planes[2]
        val yBuf = yP.buffer; val uBuf = uP.buffer; val vBuf = vP.buffer
        val yRow = yP.rowStride; val yPix = yP.pixelStride
        val uvRow = uP.rowStride; val uvPix = uP.pixelStride
        fun y(x: Int, yy: Int) = yBuf.get(yy * yRow + x * yPix).toInt() and 0xFF

        val step = maxOf(4, side / 120)
        var sum = 0L; var n = 0; var green = 0
        var lapSum = 0.0; var lapSq = 0.0; var ln = 0
        var yy = y0 + step
        while (yy < y0 + side - step) {
            var xx = x0 + step
            while (xx < x0 + side - step) {
                val c = y(xx, yy); sum += c; n++
                val lap = 4 * c - y(xx - step / 2, yy) - y(xx + step / 2, yy) - y(xx, yy - step / 2) - y(xx, yy + step / 2)
                lapSum += lap; lapSq += lap.toDouble() * lap; ln++
                val ui = (yy / 2) * uvRow + (xx / 2) * uvPix
                val u = uBuf.get(ui).toInt() and 0xFF; val v = vBuf.get((yy / 2) * vP.rowStride + (xx / 2) * vP.pixelStride).toInt() and 0xFF
                if (u < 124 && v < 126 && c in 30..230) green++
                xx += step
            }
            yy += step
        }
        if (n == 0) return FrameHint.PLACE_LEAF
        val mean = sum / n
        val mLap = lapSum / ln; val varLap = lapSq / ln - mLap * mLap
        return when {
            mean < dark -> FrameHint.DARK
            mean > bright -> FrameHint.BRIGHT
            green.toDouble() / n < minGreen -> FrameHint.PLACE_LEAF
            varLap < minSharpness -> FrameHint.BLURRY
            else -> FrameHint.GOOD
        }
    }
}
