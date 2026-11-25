package com.ilainalam.ml

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.ilainalam.data.AppAssets
import com.ilainalam.data.LabelInfo
import com.ilainalam.data.ModelConfig
import com.ilainalam.data.VisionConfig
import org.pytorch.executorch.EValue
import org.pytorch.executorch.Module
import org.pytorch.executorch.Tensor
import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.math.pow

data class Prediction(val label: LabelInfo, val probability: Float)

data class ClassifyResult(
    val top: List<Prediction>,
    val millis: Long,
    val probs: FloatArray = FloatArray(0),   // all 38 probabilities, after the rare-class correction
    val crop: String? = null,                // set when the result was limited to the crop the farmer chose
    val leafProb: Float? = null,             // leaf-photo gate: chance this is a crop-leaf photo (null = no gate model)
) {
    val best: Prediction get() = top.first()
    val second: Prediction? get() = top.getOrNull(1)
}

/**
 * Runs the vision models with ExecuTorch (XNNPACK, INT8):
 *  - leaf_gate_fp32.pte (optional): "is this a crop-leaf photo?" → [p(not leaf), p(leaf)]  (notebook 03)
 *  - disease model: 38 disease probabilities — v3 crop_disease_int8.pte (MobileNetV3-Large, 224 px, notebook 01) or,
 *    when vision_config.json is present, v4 (EfficientNet-B2, 288 px, notebook 04); optional mirror-image average (tta_flip, off by default).
 * Input: float32 [1,3,N,N], RGB 0–1 (gate N = 224, disease N = vision_config input_size).
 *
 * Every ExecuTorch call (load, warm-up, scan) runs on ONE dedicated worker thread.
 * THREAD POOL: ExecuTorch has one shared CPU thread pool. XNNPACK models remember the pool that existed when they
 * were loaded; creating a Module/LlmModule with a different thread count destroys that pool, and older models then
 * freeze on their next run. So every vision Module uses ExecuTorch's default count (0 = cores/2) and the models are
 * rebuilt by [reloadOnNewPool] right after Llama is created (Llama resizes the pool) and before Llama loads.
 * SELF-RECOVERY: on one phone (OPPO, first minute after install) a forward() call sometimes never returned.
 * A watchdog now times each native step; if it hangs for [STEP_TIMEOUT_MS] the stuck worker is set aside, its
 * lock ticket returned, and a FRESH copy of the models is loaded on a NEW thread to try again ([ATTEMPTS] tries).
 */
class VisionClassifier(
    private val context: Context,
    private val labels: List<LabelInfo>,
    private val config: ModelConfig,
    private val hasGate: Boolean,
    private val vision: VisionConfig = VisionConfig(),
) {
    /** Disease model input size (224 for v3, 288 for v4) — the gate is always 224. */
    val inputSize: Int get() = vision.inputSize
    private val diseaseShape = longArrayOf(1, 3, vision.inputSize.toLong(), vision.inputSize.toLong())
    private class Worker(val id: Int) {
        val exec: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "vision-$id") }
        @Volatile var disease: Module? = null
        @Volatile var gate: Module? = null
        @Volatile var ticket: NativeLock.Ticket? = null
        @Volatile var nativeSince = 0L           // when the current native step started (0 = not in native code)
    }

    @Volatile private var worker = Worker(1)
    @Volatile var loadMillis: Long = 0; private set
    /** Called (on a background thread) when a stuck attempt is set aside and a fresh one starts. */
    @Volatile var onRetry: ((attempt: Int) -> Unit)? = null

    /** Prior correction (notebook 03): p_i / prior_i^τ, then rescaled. τ = 0 → unchanged. */
    private val priorDivisor: FloatArray? =
        if (config.priorTau > 0f && config.classPrior.size == labels.size)
            FloatArray(labels.size) { config.classPrior[it].coerceAtLeast(1e-6f).toDouble().pow(config.priorTau.toDouble()).toFloat() }
        else null

    // ---------------------------------------------------------------- files
    private fun assetFile(name: String): File {
        val f = File(context.filesDir, "models/$name")
        val assetLen = runCatching { context.assets.openFd(name).use { it.length } }.getOrDefault(-1L)
        if (!f.exists() || (assetLen >= 0 && f.length() != assetLen)) {
            f.parentFile?.mkdirs()
            context.assets.open(name).use { inp -> f.outputStream().use { inp.copyTo(it) } }
        }
        return f
    }

    // ---------------------------------------------------------------- native steps (run on the worker thread)
    private inline fun <T> native(w: Worker, what: String, block: () -> T): T {
        Trace.i("vision: waiting for native lock ($what)")
        val t = NativeLock.acquire()
        w.ticket = t; w.nativeSince = SystemClock.elapsedRealtime()
        Trace.i("vision: $what")
        try { return block() } finally { w.nativeSince = 0L; w.ticket = null; t.release() }
    }

    private fun loadOn(w: Worker) {
        if (w.disease != null) return
        val diseaseFile = assetFile(vision.diseaseModel)
        val gateFile = if (hasGate) assetFile(AppAssets.GATE_MODEL_ASSET) else null
        val t0 = SystemClock.elapsedRealtime()
        native(w, "loading models") {
            val d = Module.load(diseaseFile.absolutePath, Module.LOAD_MODE_FILE, VISION_THREADS)
            d.forward(EValue.from(Tensor.fromBlob(FloatArray(3 * vision.inputSize * vision.inputSize), diseaseShape)))   // warm-up
            val g = gateFile?.let { Module.load(it.absolutePath, Module.LOAD_MODE_FILE, VISION_THREADS) }
            g?.forward(EValue.from(Tensor.fromBlob(FloatArray(3 * 224 * 224), INPUT_SHAPE)))
            w.disease = d; w.gate = g
        }
        loadMillis = SystemClock.elapsedRealtime() - t0
        Trace.i("vision: loaded ${vision.diseaseModel} (${vision.version}, ${vision.inputSize} px${if (vision.ttaFlip) ", mirror-average" else ""}) " +
            "in $loadMillis ms · gate ${if (hasGate) "on" else "not installed"}")
    }

    /** Submits [block] to worker [w] and waits; gives up if a native step runs longer than STEP_TIMEOUT_MS. */
    private fun <T> runWatched(w: Worker, block: (Worker) -> T): T {
        val f: Future<T> = w.exec.submit<T> { block(w) }
        while (true) {
            try { return f.get(250, TimeUnit.MILLISECONDS) }
            catch (e: ExecutionException) { throw e.cause ?: e }
            catch (_: TimeoutException) {
                val since = w.nativeSince
                if (since != 0L && SystemClock.elapsedRealtime() - since > STEP_TIMEOUT_MS) throw StuckException()
            }
        }
    }

    private class StuckException : RuntimeException("vision step did not finish")

    /** Sets the stuck worker aside: returns its lock ticket and starts a fresh worker. The old thread is left to finish on its own. */
    private fun abandon(w: Worker) {
        w.ticket?.release()
        w.exec.shutdown()
        synchronized(this) { if (worker === w) worker = Worker(w.id + 1) }
    }

    private fun <T> withRecovery(block: (Worker) -> T): T {
        var last: Throwable? = null
        for (attempt in 1..ATTEMPTS) {
            val w = worker
            try { return runWatched(w, block) }
            catch (e: StuckException) {
                last = e
                Trace.i("vision: attempt $attempt stuck > $STEP_TIMEOUT_MS ms — starting a fresh model on a new thread")
                abandon(w)
                if (attempt < ATTEMPTS) onRetry?.invoke(attempt + 1)
            }
        }
        throw RuntimeException("The disease check did not respond after $ATTEMPTS tries", last)
    }

    fun ensureLoaded() = withRecovery { loadOn(it) }

    /** Rebuilds the vision models on ExecuTorch's current thread pool (called after Llama is created). */
    fun reloadOnNewPool() = withRecovery { w ->
        native(w, "releasing models (thread pool changed)") {
            w.disease?.destroy(); w.gate?.destroy(); w.disease = null; w.gate = null
        }
        loadOn(w)
    }

    // ---------------------------------------------------------------- public API
    /**
     * @param diseaseIn the photo at [inputSize] for the disease model
     * @param gateIn the whole square at 224 for the leaf-photo check (defaults to [diseaseIn] when that is 224)
     */
    fun classify(diseaseIn: Bitmap, gateIn: Bitmap? = null, topK: Int = 3): ClassifyResult {
        val t0 = SystemClock.elapsedRealtime()
        val floats = ImagePrep.toChwFloats(diseaseIn)   // pixel work stays on the caller's thread
        val flipped = if (vision.ttaFlip) ImagePrep.flipChw(floats, diseaseIn.width) else null
        val gateFloats = when {
            !hasGate -> null
            gateIn != null -> ImagePrep.toChwFloats(gateIn)
            diseaseIn.width == 224 -> floats
            else -> ImagePrep.toChwFloats(Bitmap.createScaledBitmap(diseaseIn, 224, 224, true))
        }
        val (raw, leafProb) = withRecovery { w ->
            loadOn(w)
            native(w, "classifying") {
                val lp = gateFloats?.let { gf ->
                    w.gate?.forward(EValue.from(Tensor.fromBlob(gf, INPUT_SHAPE)))?.get(0)?.toTensor()?.dataAsFloatArray?.getOrNull(1)
                }
                val p1 = w.disease!!.forward(EValue.from(Tensor.fromBlob(floats, diseaseShape)))[0].toTensor().dataAsFloatArray
                val p = if (flipped == null) p1 else {
                    val p2 = w.disease!!.forward(EValue.from(Tensor.fromBlob(flipped, diseaseShape)))[0].toTensor().dataAsFloatArray
                    FloatArray(p1.size) { 0.5f * (p1[it] + p2[it]) }            // mirror-average
                }
                Pair(p, lp)
            }
        }
        val ms = SystemClock.elapsedRealtime() - t0
        Trace.i("vision: classified in $ms ms" + (leafProb?.let { " · leaf-photo ${"%.2f".format(it)}" } ?: ""))
        check(raw.size == labels.size) {
            "Model has ${raw.size} outputs but labels.json has ${labels.size} — copy both from the same ps1_vision_outputs.zip"
        }
        val probs = correctPriors(raw)
        val top = probs.indices.sortedByDescending { probs[it] }.take(topK).map { Prediction(labels[it], probs[it]) }
        return ClassifyResult(top, ms, probs, leafProb = leafProb)
    }

    private fun correctPriors(p: FloatArray): FloatArray {
        val div = priorDivisor ?: return p
        val q = FloatArray(p.size) { p[it] / div[it] }
        val s = q.sum().coerceAtLeast(1e-12f)
        for (i in q.indices) q[i] /= s
        return q
    }

    /** A result from given probabilities (e.g. the average over several photos). */
    fun fromProbs(p: FloatArray, topK: Int = 3): ClassifyResult {
        val top = p.indices.sortedByDescending { p[it] }.take(topK).map { Prediction(labels[it], p[it]) }
        return ClassifyResult(top, 0, p)
    }

    /**
     * Keeps only the diseases of [crop] (the farmer said which crop it is) and rescales them to add up to 100 %.
     * On real field photos this raised accuracy from 67 % to 77 % and removes "wrong crop" answers (notebook 01 v3).
     */
    fun restrictToCrop(r: ClassifyResult, crop: String?, topK: Int = 3): ClassifyResult {
        if (crop == null || r.probs.size != labels.size) return r
        val idx = labels.indices.filter { labels[it].crop == crop }
        if (idx.isEmpty()) return r
        val sum = idx.sumOf { r.probs[it].toDouble() }.toFloat().coerceAtLeast(1e-9f)
        val top = idx.sortedByDescending { r.probs[it] }.take(topK).map { Prediction(labels[it], r.probs[it] / sum) }
        return r.copy(top = top, crop = crop)
    }

    fun close() {
        val w = worker
        runCatching { w.exec.submit { w.disease?.destroy(); w.gate?.destroy() }.get(2, TimeUnit.SECONDS) }
        w.exec.shutdown()
    }

    companion object {
        private val INPUT_SHAPE = longArrayOf(1, 3, 224, 224)
        const val VISION_THREADS = 0           // ExecuTorch default (cores/2); the SAME for every vision Module
        const val STEP_TIMEOUT_MS = 7_000L      // a normal scan takes well under 1 s; loading under 2 s
        const val ATTEMPTS = 3
    }
}
