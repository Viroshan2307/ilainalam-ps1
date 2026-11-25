package com.ilainalam

import android.app.Application
import com.ilainalam.data.AppAssets
import com.ilainalam.data.HistoryStore
import com.ilainalam.data.SettingsStore
import com.ilainalam.ml.LlmEngine
import com.ilainalam.ml.LlmFiles
import com.ilainalam.ml.SeverityAnalyzer
import com.ilainalam.ml.VisionClassifier
import com.ilainalam.util.Speaker

/** Holds the app-wide objects (models, stores). Created once per app start. */
class IlaiNalamApp : Application() {
    lateinit var assetsBundle: AppAssets; private set
    lateinit var settings: SettingsStore; private set
    lateinit var history: HistoryStore; private set
    lateinit var classifier: VisionClassifier; private set
    lateinit var severity: SeverityAnalyzer; private set
    lateinit var llmFiles: LlmFiles; private set
    lateinit var llm: LlmEngine; private set
    lateinit var speaker: Speaker; private set

    override fun onCreate() {
        super.onCreate()
        assetsBundle = AppAssets(this)
        settings = SettingsStore(this)
        history = HistoryStore(this)
        classifier = VisionClassifier(this, assetsBundle.labels, assetsBundle.modelConfig, assetsBundle.hasGate, assetsBundle.visionConfig)
        severity = SeverityAnalyzer(assetsBundle.severityConfig)
        llmFiles = LlmFiles(this)
        llm = LlmEngine(llmFiles)
        // Creating Llama rebuilds ExecuTorch's shared thread pool → rebuild the vision models on the new pool
        // before Llama loads its weights (otherwise the next scan freezes at "Identifying the disease").
        llm.beforeWeightsLoad = { runCatching { classifier.reloadOnNewPool() }.onFailure { com.ilainalam.ml.Trace.i("vision: reload failed: ${it.message}") } }
        speaker = Speaker(this)
    }
}
