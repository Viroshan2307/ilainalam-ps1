package com.ilainalam.ui

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ilainalam.IlaiNalamApp
import com.ilainalam.data.HistoryItem
import com.ilainalam.data.KbEntry
import com.ilainalam.i18n.Lang
import com.ilainalam.i18n.Region
import com.ilainalam.i18n.Season
import com.ilainalam.util.PowerMeter
import com.ilainalam.util.PowerReading
import java.util.Calendar
import com.ilainalam.ml.ClassifyResult
import com.ilainalam.ml.ImagePrep
import com.ilainalam.ml.LlmStats
import com.ilainalam.ml.PhotoQuality
import com.ilainalam.ml.PromptBuilder
import com.ilainalam.ml.QualityReport
import com.ilainalam.ml.SeverityResult
import com.ilainalam.ml.Trace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** IDLE = files are on the phone but Llama is not loaded yet (it loads after the first scan). */
enum class LlmStatus { NOT_SET_UP, IDLE, LOADING, READY, ERROR }

data class Scan(
    val id: String,
    val time: Long,
    val square: Bitmap,          // 512×512 photo shown on screen
    val spotsView: Bitmap,       // same with diseased spots in red
    val leafView: Bitmap,        // same with the background greyed out (what the app treated as leaf)
    val model: ClassifyResult,
    val severity: SeverityResult,
    val quality: QualityReport,
    val timings: ScanTimings,
    val chosenCrop: String?,      // crop the farmer chose (null = not sure → all 38 classes)
    val notPlant: Boolean,        // not a crop-leaf photo: rejected by the leaf-photo AI gate or the green check
    val lowConfidence: Boolean,   // shown result below the "Not sure" cut-off → no disease named, no advice
    /** Multi-photo scan: the single-photo scans that were combined (empty = just this one photo). */
    val parts: List<Scan> = emptyList(),
) {
    val photoCount get() = if (parts.isEmpty()) 1 else parts.size
    val best get() = model.best
    val leafFound get() = severity.leafFound
    /** A real leaf the model could judge — only these are saved to history and get advice. */
    val usable get() = leafFound && !notPlant
}

data class ScanTimings(val decodeMs: Long, val preprocessMs: Long, val classifyMs: Long, val severityMs: Long, val qualityMs: Long, val totalMs: Long)

data class FollowUp(val question: String, val answer: String)

data class AdviceState(
    val scanId: String? = null,
    val text: String = "",
    val generating: Boolean = false,
    val error: String? = null,
    val stats: LlmStats? = null,
    val followUps: List<FollowUp> = emptyList(),
    val pendingQuestion: String? = null,
    val hindi: Boolean = false,           // Llama's answer is in Hindi (app in Hindi mode)
)

/** What the Result screen shows while analysing. */
enum class ScanStep { DECODING, LEAF, SPOTS, DISEASE }

data class SpeedTest(
    val runs: Int, val classifyMedianMs: Long, val classifyMinMs: Long, val severityMedianMs: Long,
    val idlePower: PowerReading? = null, val visionPower: PowerReading? = null,
) {
    /** Extra energy per scan above idle, in millijoules (null if the phone was charging or the gauge gave nothing). */
    val scanMilliJoules: Double? get() {
        val v = visionPower ?: return null; val i = idlePower ?: return null
        if (v.charging) return null
        return (v.avgMilliwatts - i.avgMilliwatts).coerceAtLeast(0.0) * classifyMedianMs / 1000.0
    }
}

/** "Ask the advisor" chat (general questions, no photo). */
data class AdvisorState(
    val turns: List<FollowUp> = emptyList(),
    val pendingQuestion: String? = null,
    val draft: String = "",
    val generating: Boolean = false,
    val error: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app as IlaiNalamApp
    val assets = c.assetsBundle
    val speaker = c.speaker
    val llmFiles = c.llmFiles

    private val _lang = MutableStateFlow(c.settings.language); val lang = _lang.asStateFlow()
    val languageChosen get() = c.settings.languageChosen

    private val _llm = MutableStateFlow(LlmStatus.NOT_SET_UP); val llm = _llm.asStateFlow()
    /** False until the vision model is loaded and warmed up — the scan buttons wait for this. */
    private val _visionReady = MutableStateFlow(false); val visionReady = _visionReady.asStateFlow()
    private val _llmError = MutableStateFlow<String?>(null); val llmError = _llmError.asStateFlow()
    private val _importProgress = MutableStateFlow<Pair<String, Float>?>(null); val importProgress = _importProgress.asStateFlow()

    private val _history = MutableStateFlow(c.history.list()); val history = _history.asStateFlow()
    private val _scan = MutableStateFlow<Scan?>(null); val scan = _scan.asStateFlow()
    private val _analyzing = MutableStateFlow(false); val analyzing = _analyzing.asStateFlow()
    private val _step = MutableStateFlow(ScanStep.DECODING); val step = _step.asStateFlow()
    private val _scanError = MutableStateFlow<String?>(null); val scanError = _scanError.asStateFlow()

    private val _advice = MutableStateFlow(AdviceState()); val advice = _advice.asStateFlow()

    /** "Which crop?" on the scan screen. null = Not sure. Remembered between scans and app starts. */
    private val _crop = MutableStateFlow(c.settings.lastCrop?.takeIf { it in assets.crops }); val crop = _crop.asStateFlow()
    fun setCrop(crop: String?) { _crop.value = crop; c.settings.lastCrop = crop }
    private val _speed = MutableStateFlow<SpeedTest?>(null); val speed = _speed.asStateFlow()

    /** True while the vision model is being restarted after a stuck attempt ("Still working…"). */
    private val _retrying = MutableStateFlow(false); val retrying = _retrying.asStateFlow()

    // ---------------- state + season (region-aware advice) ----------------
    private val _region = MutableStateFlow(c.settings.region?.let { n -> Region.entries.firstOrNull { it.name == n } })
    val region = _region.asStateFlow()
    fun setRegion(r: Region) { _region.value = r; c.settings.region = r.name }
    fun season(): Season = Season.of(Calendar.getInstance().get(Calendar.MONTH) + 1)
    /** e.g. "Tamil Nadu, India" for the prompt; null for "Other"/not chosen. */
    private fun placeForPrompt(): String? = _region.value?.takeIf { it != Region.OTHER }?.let { "${it.en}, India" } ?: "India"

    // ---------------- power (benchmark) ----------------
    private val llmPower = PowerMeter(app)
    private val benchPower = PowerMeter(app)
    private val _lastLlmPower = MutableStateFlow<PowerReading?>(null); val lastLlmPower = _lastLlmPower.asStateFlow()
    private val _powerBusy = MutableStateFlow(false); val powerBusy = _powerBusy.asStateFlow()

    // ---------------- "Ask the advisor" ----------------
    private val _advisor = MutableStateFlow(AdvisorState()); val advisor = _advisor.asStateFlow()

    // ---------------- multi-photo scan ----------------
    /** True between "Add another leaf photo" and the next photo: that photo is added to the current scan. */
    private val _addMode = MutableStateFlow(false); val addMode = _addMode.asStateFlow()
    /** Set when an added photo was not a usable leaf photo (it is then not added). */
    private val _addRejected = MutableStateFlow(false); val addRejected = _addRejected.asStateFlow()
    fun startAddPhoto() { _addRejected.value = false; _addMode.value = (_scan.value?.photoCount ?: 3) < MAX_PHOTOS }
    fun cancelAddPhoto() { _addMode.value = false }

    private fun addPhoto(uri: Uri) {
        val base = _scan.value ?: return
        _addMode.value = false; _addRejected.value = false
        _analyzing.value = true; _scanError.value = null; _step.value = ScanStep.DECODING; _retrying.value = false
        c.llm.stop()
        val first = base.parts.ifEmpty { listOf(base) }
        Trace.i("vm: adding photo ${first.size + 1} to ${base.id}")
        viewModelScope.launch {
            try {
                val part = withTimeoutOnWorker {
                    val t0 = SystemClock.elapsedRealtime()
                    val photo = ImagePrep.decode(getApplication<Application>(), uri)
                    val tDecode = SystemClock.elapsedRealtime()
                    runPipeline(ImagePrep.centerSquare(photo), "${base.id}-p${first.size + 1}", base.time, tDecode - t0, t0, base.chosenCrop)
                }
                if (!part.usable) { _addRejected.value = true; return@launch }   // not a leaf photo → keep the scan as it was
                val all = first + part
                val avg = FloatArray(part.model.probs.size)
                all.forEach { p -> p.model.probs.forEachIndexed { i, v -> avg[i] += v / all.size } }
                val model = c.classifier.restrictToCrop(c.classifier.fromProbs(avg), base.chosenCrop)
                val meanFrac = all.map { it.severity.fraction }.average().toFloat()
                val combined = first[0].copy(
                    model = model,
                    severity = first[0].severity.copy(fraction = meanFrac, level = c.severity.levelFor(meanFrac)),
                    notPlant = false, lowConfidence = model.best.probability < assets.minConfidence, parts = all,
                )
                Trace.i("vm: combined ${all.size} photos → ${model.best.label.raw} ${"%.2f".format(model.best.probability)}")
                _scan.value = combined; resetAdvice(combined.id)
                withContext(Dispatchers.IO) { c.history.add(combined.toHistory(), first[0].square); _history.value = c.history.list() }
            } catch (e: TimeoutCancellationException) { _scanError.value = ERR_TIMEOUT
            } catch (e: Exception) { _scanError.value = e.message ?: e.toString()
            } finally { _analyzing.value = false; _retrying.value = false }
        }
    }

    fun askAdvisor(question: String) {
        val q = question.trim()
        val a = _advisor.value
        if (q.isEmpty() || _llm.value != LlmStatus.READY || a.generating || _advice.value.generating) return
        val prev = a.turns.lastOrNull()?.let { it.question to it.answer }
        _advisor.update { it.copy(pendingQuestion = q, draft = "", generating = true, error = null) }
        stream(PromptBuilder.general(placeForPrompt(), season().en, prev, q, hindi = llamaHindi())) { text, done, stats, err ->
            _advisor.update {
                if (done) it.copy(turns = it.turns + FollowUp(q, text), pendingQuestion = null, draft = "", generating = false, error = err)
                else it.copy(draft = text, generating = true)
            }
            if (stats != null) _advice.update { ad -> ad.copy(stats = stats) }     // benchmark shows the latest Llama run
        }
    }
    fun clearAdvisor() { c.llm.stop(); _advisor.value = AdvisorState() }
    private val _peakPssMb = MutableStateFlow(0); val peakPssMb = _peakPssMb.asStateFlow()

    init {
        // Only the small vision model loads at start-up. Llama loads AFTER the first scan result is on screen
        // (or when advice is requested), so opening the app and scanning straight away is always safe.
        if (c.llm.filesPresent()) _llm.value = LlmStatus.IDLE
        c.classifier.onRetry = { attempt -> Trace.i("vm: vision retry $attempt"); _retrying.value = true }
        viewModelScope.launch(Dispatchers.Default) {
            if (assets.missing.isEmpty()) runCatching { c.classifier.ensureLoaded() }
                .onFailure { Trace.i("vision: load failed: ${it.message}") }
            _visionReady.value = true          // on failure too, so the scan shows the real error instead of waiting
        }
    }

    // ---------------- language ----------------
    fun setLanguage(l: Lang) {
        val wasHindi = _lang.value == Lang.HI
        _lang.value = l; c.settings.language = l; c.settings.languageChosen = true
        // Llama writes Hindi only in Hindi mode: switching into/out of Hindi clears the AI answer so it is rewritten
        if (wasHindi != (l == Lang.HI)) _scan.value?.let { if (_advice.value.scanId == it.id) resetAdvice(it.id) }
    }
    private fun llamaHindi() = _lang.value == Lang.HI

    // ---------------- Llama setup ----------------
    fun llmFilesPresent() = c.llm.filesPresent()

    fun loadLlmIfPresent() {
        if (!c.llm.filesPresent() || _llm.value == LlmStatus.LOADING || _llm.value == LlmStatus.READY) return
        Trace.i("vm: starting Llama load")
        _llm.value = LlmStatus.LOADING; _llmError.value = null
        c.llm.load { r ->
            r.onSuccess { _llm.value = LlmStatus.READY; samplePss() }
             .onFailure { _llm.value = LlmStatus.ERROR; _llmError.value = it.message }
        }
    }

    fun importLlmFile(uri: Uri, isModel: Boolean) {
        val name = if (isModel) "llama.pte" else "tokenizer.model"
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _importProgress.value = name to 0f
                c.llmFiles.import(uri, name) { p -> _importProgress.value = name to p }
                _importProgress.value = null
                withContext(Dispatchers.Main) { loadLlmIfPresent() }
            } catch (e: Exception) {
                _importProgress.value = null; _llmError.value = "Import failed: ${e.message}"
            }
        }
    }

    /** Setup-screen self-test: runs both models once and reports timings, so problems show up immediately. */
    private val _testResult = MutableStateFlow<String?>(null); val testResult = _testResult.asStateFlow()
    fun testModels() {
        _testResult.value = "…"
        viewModelScope.launch(Dispatchers.Default) {
            val sb = StringBuilder()
            try {
                val t0 = SystemClock.elapsedRealtime()
                val n = c.classifier.inputSize
                val r = c.classifier.classify(Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888), Bitmap.createBitmap(224, 224, Bitmap.Config.ARGB_8888))
                sb.append("✅ Vision model OK · ${r.millis} ms (load ${c.classifier.loadMillis} ms, total ${SystemClock.elapsedRealtime() - t0} ms)\n")
            } catch (e: Throwable) { sb.append("❌ Vision model: ${e.message}\n") }
            _testResult.value = sb.toString()
            if (_llm.value != LlmStatus.READY) {
                withContext(Dispatchers.Main) { loadLlmIfPresent() }
                _testResult.value = sb.append("⏳ Llama is loading now — tap Test models again in a few seconds").toString(); return@launch
            }
            val out = StringBuilder()
            c.llm.generate(PromptBuilder.advice("{}", "Reply with one short sentence: the app is working."),
                onToken = { out.append(it) },
                onDone = { res ->
                    res.onSuccess { st -> sb.append("✅ Llama OK · first word ${st.timeToFirstTokenMs} ms · ${"%.1f".format(st.decodeTokPerSec)} tok/s\n\"${out.toString().trim().take(120)}\"") }
                       .onFailure { sb.append("❌ Llama: ${it.message}") }
                    _testResult.value = sb.toString()
                })
        }
    }

    // ---------------- scanning ----------------
    fun analyze(uri: Uri) {
        if (_addMode.value && _scan.value != null) { addPhoto(uri); return }
        _addRejected.value = false
        _analyzing.value = true; _scanError.value = null; _step.value = ScanStep.DECODING; _retrying.value = false
        c.llm.stop()                       // a new scan always stops advice still being written
        val crop = _crop.value
        Trace.i("vm: scan started (crop: ${crop ?: "not sure"})")
        viewModelScope.launch {
            try {
                val scan = withTimeoutOnWorker {
                    val t0 = SystemClock.elapsedRealtime()
                    val photo = ImagePrep.decode(getApplication<Application>(), uri)
                    val tDecode = SystemClock.elapsedRealtime()
                    val square = ImagePrep.centerSquare(photo)
                    runPipeline(square, id = "s$t0", time = System.currentTimeMillis(), decodeMs = tDecode - t0, t0 = t0, crop = crop)
                }
                _scan.value = scan; resetAdvice(scan.id)
                Trace.i("vm: scan finished in ${scan.timings.totalMs} ms")
                loadLlmIfPresent()                 // Llama loads now, while the farmer reads the result
                if (scan.usable) withContext(Dispatchers.IO) {           // only real leaf scans go into history
                    c.history.add(scan.toHistory(), scan.square)
                    _history.value = c.history.list()
                }
            } catch (e: TimeoutCancellationException) {
                _scanError.value = ERR_TIMEOUT
            } catch (e: Exception) {
                _scanError.value = e.message ?: e.toString()
            } finally { _analyzing.value = false; _retrying.value = false }
        }
    }

    fun openHistory(item: HistoryItem) {
        val photo = c.history.photo(item.id) ?: return
        _analyzing.value = true; _scanError.value = null; _step.value = ScanStep.LEAF; _retrying.value = false
        c.llm.stop()
        viewModelScope.launch {
            try {
                val scan = withTimeoutOnWorker { runPipeline(photo, item.id, item.time, 0, SystemClock.elapsedRealtime(), item.chosenCrop) }
                _scan.value = scan; resetAdvice(item.id)
                loadLlmIfPresent()
            } catch (e: TimeoutCancellationException) { _scanError.value = ERR_TIMEOUT
            } catch (e: Exception) { _scanError.value = e.message } finally { _analyzing.value = false; _retrying.value = false }
        }
    }

    /**
     * Runs [block] on a separate worker and stops WAITING after [SCAN_TIMEOUT_MS]. A stuck native call can't
     * be interrupted, but the screen shows an error instead of spinning forever.
     */
    private val worker = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private suspend fun <T> withTimeoutOnWorker(block: () -> T): T {
        val job = worker.async { block() }
        return withTimeout(SCAN_TIMEOUT_MS) { job.await() }
    }

    /** Leaf → spots → photo quality → disease. Runs on a background thread. */
    private fun runPipeline(square: Bitmap, id: String, time: Long, decodeMs: Long, t0: Long, crop: String?): Scan {
        val tPre0 = SystemClock.elapsedRealtime()
        val vc = assets.visionConfig
        val modelIn = ImagePrep.modelInput(square, vc.inputSize, vc.cropInner)
        val analysisImg = ImagePrep.analysisImage(square)
        val preMs = SystemClock.elapsedRealtime() - tPre0
        _step.value = ScanStep.LEAF                       // 1. find the leaf (background rejected)
        val sev = c.severity.analyze(analysisImg)         // 2. spots on the leaf only
        _step.value = ScanStep.SPOTS
        val tq = SystemClock.elapsedRealtime()
        val q = PhotoQuality.assess(analysisImg, sev)     // 3. photo quality
        val qMs = SystemClock.elapsedRealtime() - tq
        _step.value = ScanStep.DISEASE
        val raw = c.classifier.classify(modelIn, analysisImg)   // 4. leaf-photo check (224) + disease (v3 224 / v4 288)
        val cls = c.classifier.restrictToCrop(raw, crop)  //    … only among the chosen crop's diseases
        val pc = assets.plantCheck
        val gateSaysNo = raw.leafProb != null && raw.leafProb < assets.modelConfig.gateMinLeafProb   // AI: not a crop-leaf photo
        val notGreen = sev.leafFound && sev.greenFraction < pc.minGreenFraction                       // colour safety net
        val notPlant = gateSaysNo || notGreen
        Trace.i("vm: leaf-photo ${raw.leafProb?.let { "%.2f".format(it) } ?: "n/a"} · green share ${"%.2f".format(sev.greenFraction)} · " +
            "best ${cls.best.label.raw} ${"%.2f".format(cls.best.probability)}")
        val spots = c.severity.spotsOverlay(square, sev)
        val leaf = c.severity.leafOverlay(square, sev)
        val total = SystemClock.elapsedRealtime() - t0
        return Scan(id, time, square, spots, leaf, cls, sev, q, ScanTimings(decodeMs, preMs, cls.millis, sev.millis, qMs, total),
            chosenCrop = crop, notPlant = notPlant, lowConfidence = cls.best.probability < assets.minConfidence)
    }

    private fun Scan.toHistory() = HistoryItem(
        id, time, best.label.raw, best.label.crop, best.label.condition, best.probability,
        severity.level.name, severity.fraction, best.label.healthy, chosenCrop,
    )

    fun clearScanError() { _scanError.value = null }

    fun kbFor(scan: Scan): KbEntry? = assets.kb[scan.best.label.raw]

    /** Disease name in the chosen language (kb.json name_hi / name_ta), falling back to English. */
    fun diseaseName(raw: String, fallback: String, lang: Lang, healthyText: String): String {
        if (raw.endsWith("___healthy")) return healthyText
        val kb = assets.kb[raw]
        val en = kb?.disease?.takeIf { it.isNotBlank() } ?: fallback
        return if (lang == Lang.EN) en else kb?.local?.get(lang.code)?.name ?: en
    }

    fun historyThumb(id: String): Bitmap? =
        c.history.photo(id)?.let { Bitmap.createScaledBitmap(it, 160, 160, true) }

    // ---------------- advice (Llama) ----------------
    private fun resetAdvice(id: String) { c.llm.stop(); _advice.value = AdviceState(scanId = id) }

    private fun contextFor(scan: Scan) =
        PromptBuilder.context(scan.best.label, kbFor(scan), scan.best.probability, scan.severity,
            place = placeForPrompt(), season = season().en, minConfidence = assets.minConfidence)

    /** Starts the first answer for the current scan (no-op if it already exists). */
    fun requestAdvice() {
        val scan = _scan.value ?: return
        val a = _advice.value
        if (scan.lowConfidence || !scan.usable) return              // no advice for an uncertain or non-leaf result
        if (_llm.value != LlmStatus.READY || a.generating || _advisor.value.generating ||
            (a.scanId == scan.id && a.text.isNotEmpty() && a.hindi == llamaHindi())) return
        val hindi = llamaHindi()
        stream(PromptBuilder.advice(contextFor(scan), hindi = hindi)) { text, done, stats, err ->
            _advice.update { it.copy(scanId = scan.id, text = text, generating = !done, stats = stats ?: it.stats, error = err, hindi = hindi) }
        }
    }

    fun askFollowUp(question: String) {
        val scan = _scan.value ?: return
        val a = _advice.value
        if (question.isBlank() || _llm.value != LlmStatus.READY || a.generating) return
        val prev = a.followUps.lastOrNull()?.answer ?: a.text
        _advice.update { it.copy(pendingQuestion = question) }
        stream(PromptBuilder.followUp(contextFor(scan), prev, question, hindi = llamaHindi())) { text, done, stats, err ->
            _advice.update {
                if (done) it.copy(followUps = it.followUps + FollowUp(question, text), pendingQuestion = null,
                    generating = false, stats = stats ?: it.stats, error = err)
                else it.copy(generating = true, pendingQuestion = question, error = err)
            }
            if (!done) _followUpDraft.value = text else _followUpDraft.value = ""
        }
    }
    private val _followUpDraft = MutableStateFlow(""); val followUpDraft = _followUpDraft.asStateFlow()

    private fun stream(prompt: String, onUpdate: (String, Boolean, LlmStats?, String?) -> Unit) {
        val sb = StringBuilder()
        var n = 0
        onUpdate("", false, null, null)
        llmPower.start(viewModelScope)                    // power while Llama answers (for the benchmark)
        c.llm.generate(prompt,
            onToken = { tok ->
                sb.append(tok); n++
                if (n % 16 == 0) samplePss()
                onUpdate(sb.toString().trimStart(), false, null, null)
            },
            onDone = { r ->
                samplePss()
                llmPower.stop()?.let { _lastLlmPower.value = it }
                r.onSuccess { onUpdate(sb.toString().trim(), true, it, null) }
                 .onFailure { onUpdate(sb.toString().trim(), true, null, it.message) }
            })
    }

    fun stopAdvice() = c.llm.stop()

    // ---------------- speech ----------------
    /** @return false if the voice for this language isn't installed. */
    fun speak(text: String, lang: Lang): Boolean = speaker.speak(text, lang.locale)
    fun stopSpeaking() = speaker.stop()

    // ---------------- benchmark ----------------
    private fun samplePss() {
        val mb = (Debug.getPss() / 1024).toInt()
        if (mb > _peakPssMb.value) _peakPssMb.value = mb
    }

    /**
     * Speed + power: 3 s idle baseline, then the vision model in a loop for at least 6 s (power sampled throughout),
     * then the severity step. Unplug the phone first, or power can't be measured.
     */
    fun runSpeedTest(minRuns: Int = 20) {
        val scan = _scan.value ?: return
        if (_powerBusy.value) return
        _powerBusy.value = true
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val modelIn = ImagePrep.modelInput(scan.square, assets.visionConfig.inputSize, assets.visionConfig.cropInner)
                val img = ImagePrep.analysisImage(scan.square)
                val idle = benchPower.idle(viewModelScope, 3.0)
                benchPower.start(viewModelScope)
                val t0 = SystemClock.elapsedRealtime()
                val cls = mutableListOf<Long>()
                while (cls.size < minRuns || SystemClock.elapsedRealtime() - t0 < 6_000) cls += c.classifier.classify(modelIn, img).millis
                val vision = benchPower.stop()
                cls.sort()
                val sev = (1..minRuns).map { c.severity.analyze(img).millis }.sorted()
                _speed.value = SpeedTest(cls.size, cls[cls.size / 2], cls.first(), sev[minRuns / 2], idle, vision)
                samplePss()
            } finally { _powerBusy.value = false }
        }
    }

    fun deviceInfo(): String {
        val am = getApplication<Application>().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val soc = if (Build.VERSION.SDK_INT >= 31) " · SoC ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else ""
        return "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}$soc · " +
            "${Runtime.getRuntime().availableProcessors()} cores · RAM ${mi.totalMem / (1024 * 1024)} MB"
    }

    fun visionLoadMs() = c.classifier.loadMillis
    fun llmLoadMs() = c.llm.loadMillis
    fun currentPssMb(): Int { samplePss(); return (Debug.getPss() / 1024).toInt() }

    /** Plain-text report for the technical report / README. */
    fun benchmarkReport(): String = buildString {
        appendLine("IlaiNalam benchmark")
        appendLine("Device: ${deviceInfo()}")
        val vc = assets.visionConfig
        appendLine("Vision models: ${vc.diseaseModel} (${if (vc.version == "v3") "MobileNetV3-Large INT8, 224 px" else "EfficientNet-B2 ${vc.version}, ${vc.inputSize} px"}" +
            (if (vc.ttaFlip) ", mirror-average" else "") + ") + " +
            (if (assets.hasGate) "leaf_gate_fp32.pte (MobileNetV3-Small FP32)" else "no leaf-photo gate") +
            ", XNNPACK, shared thread pool · load ${visionLoadMs()} ms (both)")
        appendLine("Rare-class correction τ = ${assets.modelConfig.priorTau} · Not-sure cut-off ${assets.minConfidence}")
        _scan.value?.let { s ->
            val t = s.timings
            appendLine("Last scan: total ${t.totalMs} ms (decode ${t.decodeMs}, preprocess ${t.preprocessMs}, classify ${t.classifyMs}, severity ${t.severityMs}, quality ${t.qualityMs})")
        }
        _speed.value?.let { appendLine("Speed test (${it.runs} runs): classify median ${it.classifyMedianMs} ms, min ${it.classifyMinMs} ms · severity median ${it.severityMedianMs} ms") }
        _speed.value?.let { sp ->
            if (sp.visionPower?.charging == true) appendLine("Power: not measured (phone was charging — unplug and run again)")
            else {
                sp.idlePower?.let { appendLine("Power idle (screen on): ${"%.0f".format(it.avgMilliwatts)} mW") }
                sp.visionPower?.let { appendLine("Power during vision loop: ${"%.0f".format(it.avgMilliwatts)} mW (${it.samples} samples, ${"%.1f".format(it.seconds)} s)") }
                sp.scanMilliJoules?.let { appendLine("Energy per scan (above idle): ${"%.1f".format(it)} mJ") }
            }
        }
        _lastLlmPower.value?.let {
            if (it.charging) appendLine("Llama power: not measured (phone was charging)")
            else appendLine("Power during last Llama answer: ${"%.0f".format(it.avgMilliwatts)} mW · ${"%.1f".format(it.seconds)} s · ${"%.1f".format(it.joules)} J per answer")
        }
        appendLine("Llama 3.2 1B-Instruct (8da4w, XNNPACK) · load ${llmLoadMs()} ms")
        _advice.value.stats?.let {
            appendLine("Last answer: first token ${it.timeToFirstTokenMs} ms · ${"%.1f".format(it.decodeTokPerSec)} tok/s decode · " +
                "${"%.1f".format(it.prefillTokPerSec)} tok/s prefill · ${it.promptTokens} prompt + ${it.generatedTokens} generated tokens")
        }
        appendLine("Memory (PSS): now ${currentPssMb()} MB · peak ${_peakPssMb.value} MB")
    }

    override fun onCleared() { speaker.stop() }

    companion object {
        const val SCAN_TIMEOUT_MS = 30_000L
        const val ERR_TIMEOUT = "TIMEOUT"
        const val MAX_PHOTOS = 3
    }
}
