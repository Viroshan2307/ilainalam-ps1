package com.ilainalam.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Loading and cropping photos so the app sees images the way the model was trained. */
object ImagePrep {
    const val MODEL_SIZE = 224
    const val WORK_SIZE = 512   // square image kept for display + history

    /** Decodes a photo (camera or gallery). Handles EXIF rotation, downsamples big photos, software bitmap. */
    fun decode(context: Context, uri: Uri, maxSide: Int = 1600): Bitmap {
        val src = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE   // we need getPixels()
            val w = info.size.width; val h = info.size.height
            val scale = maxSide.toFloat() / max(w, h)
            if (scale < 1f) decoder.setTargetSize((w * scale).roundToInt(), (h * scale).roundToInt())
        }.copy(Bitmap.Config.ARGB_8888, false)
    }

    /** Centre square crop, scaled to [WORK_SIZE]. This is the square shown inside the capture guide frame. */
    fun centerSquare(src: Bitmap, size: Int = WORK_SIZE): Bitmap {
        val s = min(src.width, src.height)
        val x = (src.width - s) / 2; val y = (src.height - s) / 2
        val sq = Bitmap.createBitmap(src, x, y, s, s)
        return Bitmap.createScaledBitmap(sq, size, size, true)
    }

    /** Model input: same as the notebook's eval transform — Resize(256) + CenterCrop(224) — on the square image. */
    fun modelInput(square: Bitmap, size: Int = MODEL_SIZE, cropInner: Boolean = true): Bitmap {
        if (!cropInner) return Bitmap.createScaledBitmap(square, size, size, true)   // v4: whole square, like its tests
        val inner = (square.width * 224f / 256f).roundToInt()
        val off = (square.width - inner) / 2
        val crop = Bitmap.createBitmap(square, off, off, inner, inner)
        return Bitmap.createScaledBitmap(crop, size, size, true)
    }

    /** Mirror image (left↔right) of a CHW float array — the second pass of the mirror-average. */
    fun flipChw(src: FloatArray, size: Int): FloatArray {
        val out = FloatArray(src.size)
        val plane = size * size
        for (c in 0 until 3) for (y in 0 until size) {
            val row = c * plane + y * size
            for (x in 0 until size) out[row + x] = src[row + size - 1 - x]
        }
        return out
    }

    /** Whole square at 224×224 — used by severity + photo-quality checks (same as the notebook). */
    fun analysisImage(square: Bitmap): Bitmap = Bitmap.createScaledBitmap(square, MODEL_SIZE, MODEL_SIZE, true)

    /** Bitmap → float32 CHW array, RGB scaled to 0–1 (normalisation is inside the .pte). */
    fun toChwFloats(bmp: Bitmap): FloatArray {
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h); bmp.getPixels(px, 0, w, 0, 0, w, h)
        val out = FloatArray(3 * w * h)
        val plane = w * h
        for (i in 0 until plane) {
            val c = px[i]
            out[i] = ((c shr 16) and 0xFF) / 255f
            out[plane + i] = ((c shr 8) and 0xFF) / 255f
            out[2 * plane + i] = (c and 0xFF) / 255f
        }
        return out
    }
}
