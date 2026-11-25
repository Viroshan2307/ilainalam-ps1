package com.ilainalam.util

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/** Read-aloud with the phone's own Text-to-Speech engine (works offline once the voice is installed). */
class Speaker(context: Context) {
    private var ready = false
    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
    }.apply {
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { _speaking.value = true }
            override fun onDone(utteranceId: String?) { _speaking.value = false }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { _speaking.value = false }
        })
    }

    /** @return false if the engine or the voice for [locale] isn't available. */
    fun speak(text: String, locale: Locale): Boolean {
        if (!ready) return false
        val r = tts.setLanguage(locale)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) return false
        // TTS has an input limit; split long text into chunks queued one after another
        val chunks = text.chunked(TextToSpeech.getMaxSpeechInputLength() - 10)
        chunks.forEachIndexed { i, c ->
            tts.speak(c, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "ilai-$i")
        }
        return true
    }

    fun stop() { tts.stop(); _speaking.value = false }
    fun shutdown() = tts.shutdown()
}
