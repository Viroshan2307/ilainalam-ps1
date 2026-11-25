package com.ilainalam.ml

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Step 1 + 2 of the scan, in plain Kotlin (no Android classes, so it can be unit-tested on a PC):
 *
 *  1. FIND THE LEAF — estimate the background colour from the photo border, keep everything clearly
 *     different from it, keep the main piece(s), fill holes (so spots inside the leaf stay "leaf").
 *  2. FIND THE SPOTS — take the leaf's own healthy colour as reference and mark leaf pixels that differ
 *     strongly from it (brown / black / yellow / olive spots). Shadows, the leaf rim and anything that
 *     looks like background are never counted.
 *
 * Same algorithm as severity() in notebook 01 (updated 18 Nov). Tested on PlantVillage photos.
 */
class LeafAnalysis(
    val size: Int,
    val leafMask: BooleanArray,    // leaf incl. its spots (background rejected)
    val lesionMask: BooleanArray,  // diseased spots
    val coreCount: Int,            // leaf pixels used for severity (rim excluded)
    val lesionCount: Int,
    val greenFraction: Float,      // share of the "leaf" that is plant-green — real leaves high, faces/walls ≈ 0
) {
    val leafCoverage: Float get() = leafMask.count { it }.toFloat() / leafMask.size
    val lesionFraction: Float get() = if (coreCount == 0) 0f else lesionCount.toFloat() / coreCount
    val leafFound: Boolean get() = leafCoverage >= MIN_LEAF_COVERAGE && coreCount >= 200

    companion object {
        // ---- tunable constants (tested on PlantVillage photos) -------------------------------
        const val BG_DIST = 18f          // Lab distance from background colour to count as "not background"
        const val LESION_DIST = 22f      // Lab distance from healthy leaf colour to count as a spot
        const val BORDER = 6             // px of the 224 image used to measure the background colour
        const val KEEP_PIECE = 0.15f     // keep extra pieces ≥ 15 % of the biggest (compound leaves)
        const val MIN_LEAF_COVERAGE = 0.05f
        // "plant-green" = OpenCV hue 30–90 and saturation ≥ 35 — same test as notebook 01 v3 (plant_check.json)
        const val GREEN_H_MIN = 30; const val GREEN_H_MAX = 90; const val GREEN_S_MIN = 35

        /** @param argb 224×224 pixels (row-major, ARGB ints, e.g. from Bitmap.getPixels). */
        fun analyze(argb: IntArray, size: Int): LeafAnalysis {
            val n = size * size
            val L = FloatArray(n); val A = FloatArray(n); val B = FloatArray(n)
            val H = IntArray(n); val S = IntArray(n); val V = IntArray(n)
            for (i in 0 until n) {
                val c = argb[i]
                val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
                rgbToLab(r, g, b, L, A, B, i)
                rgbToHsvCv(r, g, b, H, S, V, i)
            }

            // ---- 1. background colour from the border ring --------------------------------------
            val ring = ArrayList<Int>()
            for (y in 0 until size) for (x in 0 until size)
                if (y < BORDER || y >= size - BORDER || x < BORDER || x >= size - BORDER) ring += y * size + x
            val bgL = median(ring.map { L[it] }); val bgA = median(ring.map { A[it] }); val bgB = median(ring.map { B[it] })
            val dBg = FloatArray(n) { i -> dist(L[i] - bgL, A[i] - bgA, B[i] - bgB) }

            var fg = BooleanArray(n) { dBg[it] > BG_DIST }
            // Shadows cast by the leaf are "different from the background" too, but they are not leaf: drop dark,
            // colourless pixels. Dark spots INSIDE the leaf come back when holes are filled below. If that would
            // remove most of the object (a dark leaf, dim light), keep the original outline instead.
            val noShadow = BooleanArray(n) { i -> fg[i] && !((V[i] < 110 && S[i] < 55) || V[i] < 45) }
            if (noShadow.count { it } >= 0.5f * fg.count { it }) fg = noShadow
            fg = Morph.open(fg, size, 3)
            fg = Morph.close(fg, size, 7)

            // ---- keep the main piece(s) ------------------------------------------------------
            val (labels, areas) = Morph.components(fg, size)
            val biggest = areas.maxOrNull() ?: 0
            val keep = BooleanArray(n) { i -> labels[i] > 0 && areas[labels[i] - 1] >= KEEP_PIECE * biggest && biggest > 0 }
            var leaf = Morph.fillHoles(keep, size)
            if (leaf.count { it } > 0.97f * n) leaf = BooleanArray(n) { true }   // leaf fills the frame

            // ---- 2. spots ----------------------------------------------------------------------
            val core = Morph.erode(leaf, size, 5)                                  // drop the 2-px rim
            val refIdx = (0 until n).filter { core[it] && H[it] in 30..90 && S[it] >= 35 }
            val src = if (refIdx.size > 50) refIdx else (0 until n).filter { core[it] }
            val refL = median(src.map { L[it] }); val refA = median(src.map { A[it] }); val refB = median(src.map { B[it] })

            var lesion = BooleanArray(n) { i ->
                if (!core[i]) false else {
                    val dLeaf = dist(L[i] - refL, A[i] - refA, B[i] - refB)
                    val bgLike = dBg[i] < BG_DIST
                    val shadow = (V[i] < 110 && S[i] < 55) || V[i] < 45          // dark & colourless
                    dLeaf > LESION_DIST && !bgLike && !shadow
                }
            }
            lesion = Morph.open2(lesion, size)                                      // drop single-pixel noise
            var leafN = 0; var greenN = 0
            for (i in 0 until n) if (leaf[i]) {
                leafN++
                if (H[i] in GREEN_H_MIN..GREEN_H_MAX && S[i] >= GREEN_S_MIN) greenN++
            }
            val green = if (leafN < MIN_LEAF_COVERAGE * n) 0f else greenN.toFloat() / leafN
            return LeafAnalysis(size, leaf, lesion, core.count { it }, lesion.count { it }, green)
        }

        private fun dist(a: Float, b: Float, c: Float) = sqrt(a * a + b * b + c * c)

        private fun median(v: List<Float>): Float {
            if (v.isEmpty()) return 0f
            val s = v.sorted(); return s[s.size / 2]
        }

        /** sRGB → CIE Lab (D65). L 0–100, a/b ≈ −128…127 — same scale as OpenCV after rescaling. */
        private fun rgbToLab(r: Int, g: Int, b: Int, L: FloatArray, A: FloatArray, B: FloatArray, i: Int) {
            fun lin(c: Int): Double { val v = c / 255.0; return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4) }
            val rl = lin(r); val gl = lin(g); val bl = lin(b)
            val x = (0.412453 * rl + 0.357580 * gl + 0.180423 * bl) / 0.950456
            val y = 0.212671 * rl + 0.715160 * gl + 0.072169 * bl
            val z = (0.019334 * rl + 0.119193 * gl + 0.950227 * bl) / 1.088754
            fun f(t: Double) = if (t > 0.008856) t.pow(1.0 / 3) else 7.787 * t + 16.0 / 116
            val fx = f(x); val fy = f(y); val fz = f(z)
            L[i] = (if (y > 0.008856) 116 * fy - 16 else 903.3 * y).toFloat()
            A[i] = (500 * (fx - fy)).toFloat()
            B[i] = (200 * (fy - fz)).toFloat()
        }

        /** RGB → HSV on OpenCV's 8-bit scale (H 0–179, S/V 0–255). */
        private fun rgbToHsvCv(r: Int, g: Int, b: Int, H: IntArray, S: IntArray, V: IntArray, i: Int) {
            val mx = max(r, max(g, b)); val mn = min(r, min(g, b)); val d = mx - mn
            V[i] = mx
            S[i] = if (mx == 0) 0 else (255.0 * d / mx).roundToInt()
            var h = when {
                d == 0 -> 0.0
                mx == r -> 60.0 * (g - b) / d
                mx == g -> 120.0 + 60.0 * (b - r) / d
                else -> 240.0 + 60.0 * (r - g) / d
            }
            if (h < 0) h += 360.0
            H[i] = (h / 2).roundToInt() % 180
        }
    }
}

/** Binary image operations on row-major BooleanArray (size × size). Out-of-image pixels never change results. */
object Morph {
    fun erode(m: BooleanArray, size: Int, k: Int) = pass(pass(m, size, k, true, false), size, k, false, false)
    fun dilate(m: BooleanArray, size: Int, k: Int) = pass(pass(m, size, k, true, true), size, k, false, true)
    fun open(m: BooleanArray, size: Int, k: Int) = dilate(erode(m, size, k), size, k)
    fun close(m: BooleanArray, size: Int, k: Int) = erode(dilate(m, size, k), size, k)

    private fun pass(m: BooleanArray, size: Int, k: Int, horizontal: Boolean, isMax: Boolean): BooleanArray {
        val r = k / 2
        val out = BooleanArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            val p = if (horizontal) x else y
            val lo = max(0, p - r); val hi = min(size - 1, p - r + k - 1)
            var acc = !isMax
            for (t in lo..hi) {
                val v = if (horizontal) m[y * size + t] else m[t * size + x]
                if (isMax && v) { acc = true; break }
                if (!isMax && !v) { acc = false; break }
            }
            out[y * size + x] = acc
        }
        return out
    }

    /** 2×2 opening (removes isolated pixels, keeps spots ≥ 2 px). */
    fun open2(m: BooleanArray, size: Int): BooleanArray {
        fun at(a: BooleanArray, x: Int, y: Int, outside: Boolean) =
            if (x < 0 || y < 0 || x >= size || y >= size) outside else a[y * size + x]
        val e = BooleanArray(size * size) { i -> val x = i % size; val y = i / size
            at(m, x, y, true) && at(m, x - 1, y, true) && at(m, x, y - 1, true) && at(m, x - 1, y - 1, true) }
        return BooleanArray(size * size) { i -> val x = i % size; val y = i / size
            at(e, x, y, false) || at(e, x + 1, y, false) || at(e, x, y + 1, false) || at(e, x + 1, y + 1, false) }
    }

    /** 8-connected components. Returns (label per pixel, 0 = none; area of each label). */
    fun components(m: BooleanArray, size: Int): Pair<IntArray, IntArray> {
        val labels = IntArray(size * size); val areas = ArrayList<Int>()
        val queue = IntArray(size * size)
        for (start in m.indices) {
            if (!m[start] || labels[start] != 0) continue
            val id = areas.size + 1; var head = 0; var tail = 0; var area = 0
            queue[tail++] = start; labels[start] = id
            while (head < tail) {
                val p = queue[head++]; area++
                val px = p % size; val py = p / size
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = px + dx; val ny = py + dy
                    if (nx < 0 || ny < 0 || nx >= size || ny >= size) continue
                    val q = ny * size + nx
                    if (m[q] && labels[q] == 0) { labels[q] = id; queue[tail++] = q }
                }
            }
            areas += area
        }
        return labels to areas.toIntArray()
    }

    /** Fills holes: background pixels not reachable from the image border become foreground. */
    fun fillHoles(m: BooleanArray, size: Int): BooleanArray {
        val reached = BooleanArray(size * size); val queue = IntArray(size * size)
        var head = 0; var tail = 0
        fun seed(x: Int, y: Int) { val q = y * size + x; if (!m[q] && !reached[q]) { reached[q] = true; queue[tail++] = q } }
        for (i in 0 until size) { seed(i, 0); seed(i, size - 1); seed(0, i); seed(size - 1, i) }
        while (head < tail) {
            val p = queue[head++]; val px = p % size; val py = p / size
            if (px > 0) seed(px - 1, py); if (px < size - 1) seed(px + 1, py)
            if (py > 0) seed(px, py - 1); if (py < size - 1) seed(px, py + 1)
        }
        return BooleanArray(size * size) { m[it] || !reached[it] }
    }

}
