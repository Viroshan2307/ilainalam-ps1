package com.ilainalam.ml

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import org.json.JSONObject
import org.pytorch.executorch.extension.llm.LlmCallback
import org.pytorch.executorch.extension.llm.LlmGenerationConfig
import org.pytorch.executorch.extension.llm.LlmModule
import java.io.File
import java.util.concurrent.Executors

/** Where the Llama files live on the phone, and how they get there. */
class LlmFiles(private val context: Context) {
    /** In-app import target (private app storage). */
    val importDir: File get() = File(context.filesDir, "llm").apply { mkdirs() }

    /** adb push target: /sdcard/Android/data/com.ilainalam/files/llm/ */
    val adbDir: File? get() = context.getExternalFilesDir("llm")?.apply { mkdirs() }

    fun findModel(): File? = candidates().firstOrNull { it.name.endsWith(".pte") && it.length() > 50_000_000 }
    fun findTokenizer(): File? = candidates().firstOrNull {
        it.name == "tokenizer.model" || it.name == "tokenizer.json" || it.name.endsWith(".model")
    }
    private fun candidates(): List<File> =
        listOfNotNull(adbDir, importDir).flatMap { d -> d.listFiles()?.toList().orEmpty() }.filter { it.isFile }

    /** Copies a picked file into app storage. Reports progress 0..1. */
    fun import(uri: Uri, targetName: String, onProgress: (Float) -> Unit): File {
        val cr = context.contentResolver
        val total = cr.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
        } ?: -1L
        val out = File(importDir, targetName)
        val tmp = File(importDir, "$targetName.part")
        cr.openInputStream(uri)!!.use { inp ->
            tmp.outputStream().use { os ->
                val buf = ByteArray(1 shl 20); var done = 0L
                while (true) {
                    val r = inp.read(buf); if (r < 0) break
                    os.write(buf, 0, r); done += r
                    if (total > 0) onProgress(done.toFloat() / total)
                }
            }
        }
        if (out.exists()) out.delete()
        tmp.renameTo(out)
        onProgress(1f)
        return out
    }

    fun deleteImported() { importDir.listFiles()?.forEach { it.delete() } }
}

data class LlmStats(
    val timeToFirstTokenMs: Long,
    val totalMs: Long,
    val generatedTokens: Int,
    val promptTokens: Int,
    val decodeTokPerSec: Float,
    val prefillTokPerSec: Float,
)

/**
 * Llama 3.2 1B-Instruct running through ExecuTorch's LlmModule (XNNPACK, Arm CPU).
 * All calls run on one background thread; callbacks arrive on that thread.
 */
class LlmEngine(private val files: LlmFiles) {
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "llama").apply { priority = Thread.MAX_PRIORITY } }
    @Volatile private var module: LlmModule? = null
    @Volatile var loadMillis: Long = 0; private set
    @Volatile var isGenerating = false; private set

    /**
     * Called after the LlmModule object is created and BEFORE Llama's weights are loaded. Creating an LlmModule
     * makes ExecuTorch destroy and rebuild its shared CPU thread pool; models loaded earlier (the vision models)
     * still point at the destroyed pool and freeze on their next run. The app rebuilds them here, so they and
     * Llama end up on the same pool, which never changes again.
     */
    @Volatile var beforeWeightsLoad: (() -> Unit)? = null

    val isLoaded: Boolean get() = module != null
    fun filesPresent(): Boolean = files.findModel() != null && files.findTokenizer() != null

    fun load(onDone: (Result<Long>) -> Unit) = exec.execute {
        if (module != null) { onDone(Result.success(loadMillis)); return@execute }
        val result = runCatching {
            val model = files.findModel() ?: error("Llama .pte not found")
            val tok = files.findTokenizer() ?: error("tokenizer.model not found")
            val t0 = SystemClock.elapsedRealtime()
            Trace.i("llama: waiting for native lock to load")
            val m = NativeLock.withLock {          // never at the same time as the vision model
                Trace.i("llama: creating (ExecuTorch resizes its shared thread pool here)")
                LlmModule(model.absolutePath, tok.absolutePath, TEMPERATURE)
            }
            beforeWeightsLoad?.invoke()             // vision models rebuilt on the new pool (takes its own lock)
            NativeLock.withLock {
                Trace.i("llama: loading weights")
                m.load()
            }
            Trace.i("llama: loaded in ${SystemClock.elapsedRealtime() - t0} ms")
            module = m
            loadMillis = SystemClock.elapsedRealtime() - t0
            loadMillis
        }
        onDone(result)
    }

    /** Streams the answer token by token. The prompt must already be in Llama-3 chat format. */
    fun generate(
        prompt: String,
        onToken: (String) -> Unit,
        onDone: (Result<LlmStats>) -> Unit,
    ) = exec.execute {
        val m = module
        if (m == null) { onDone(Result.failure(IllegalStateException("Model not loaded"))); return@execute }
        isGenerating = true
        val t0 = SystemClock.elapsedRealtime()
        var first = -1L; var count = 0
        var statsJson: String? = null
        var error: String? = null
        Trace.i("llama: waiting for native lock to generate")
        val result = runCatching { NativeLock.withLock {   // the vision model waits until this finishes or is stopped
            Trace.i("llama: generating")
            m.resetContext()   // every request sends the full prompt, so start from an empty KV cache
            val cfg = LlmGenerationConfig.create()
                .seqLen(SEQ_LEN).echo(false).temperature(TEMPERATURE).build()
            m.generate(prompt, cfg, object : LlmCallback {
                override fun onResult(result: String) {
                    val clean = result.replace("<|eot_id|>", "").replace("<|end_of_text|>", "")
                    if (clean.isEmpty()) return
                    if (first < 0) first = SystemClock.elapsedRealtime() - t0
                    count++
                    onToken(clean)
                }
                override fun onStats(stats: String) { statsJson = stats }
                override fun onError(errorCode: Int, message: String) { error = "($errorCode) $message" }
            })
            error?.let { throw RuntimeException("Llama error $it") }
            val total = SystemClock.elapsedRealtime() - t0
            statsFrom(statsJson, first, total, count)
        } }
        Trace.i("llama: generation finished (${count} tokens)")
        isGenerating = false
        onDone(result)
    }

    /** Asks a running generation to stop at the next token (safe to call from any thread, no lock needed). */
    fun stop() { if (isGenerating) { Trace.i("llama: stop requested"); module?.stop() } }

    fun close() = exec.execute { module?.close(); module = null }

    private fun statsFrom(json: String?, firstMs: Long, totalMs: Long, count: Int): LlmStats {
        var prompt = 0; var gen = count
        var decode = if (totalMs > firstMs && count > 1) (count - 1) * 1000f / (totalMs - firstMs) else 0f
        var prefill = 0f
        if (json != null) runCatching {
            // ExecuTorch stats JSON (extension/llm/runner/stats.h) — timestamps in ms
            val o = JSONObject(json.substringAfter("{", "").let { "{$it" })
            prompt = o.optInt("prompt_tokens", 0)
            gen = o.optInt("generated_tokens", count)
            val start = o.optLong("inference_start_ms"); val promptEnd = o.optLong("prompt_eval_end_ms")
            val firstTok = o.optLong("first_token_ms"); val end = o.optLong("inference_end_ms")
            if (promptEnd > start && prompt > 0) prefill = prompt * 1000f / (promptEnd - start)
            if (end > firstTok && gen > 1) decode = (gen - 1) * 1000f / (end - firstTok)
        }
        return LlmStats(firstMs, totalMs, gen, prompt, decode, prefill)
    }

    companion object {
        const val TEMPERATURE = 0.2f   // low = factual, repeatable advice
        const val SEQ_LEN = 1024       // = max_context_length used in notebook 02
    }
}
