package com.ilainalam.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ilainalam.data.KbEntry
import com.ilainalam.i18n.Lang
import com.ilainalam.i18n.LocalStrings
import com.ilainalam.i18n.cropName
import com.ilainalam.ui.LlmStatus
import com.ilainalam.ui.MainViewModel
import com.ilainalam.ui.components.AppBar
import com.ilainalam.ui.components.BulletList
import com.ilainalam.ui.components.SectionCard
import com.ilainalam.ui.theme.LeafLight
import com.ilainalam.ui.theme.SevModerate
import com.ilainalam.ui.theme.SevSevere

@Composable
fun AdviceScreen(vm: MainViewModel, onBack: () -> Unit) {
    val s = LocalStrings.current
    val ctx = LocalContext.current
    val scan by vm.scan.collectAsStateWithLifecycle()
    val lang by vm.lang.collectAsStateWithLifecycle()
    val llm by vm.llm.collectAsStateWithLifecycle()
    val advice by vm.advice.collectAsStateWithLifecycle()
    val region by vm.region.collectAsStateWithLifecycle()
    val draft by vm.followUpDraft.collectAsStateWithLifecycle()
    val speaking by vm.speaker.speaking.collectAsStateWithLifecycle()
    var question by remember { mutableStateOf("") }
    var voiceMissing by remember { mutableStateOf(false) }

    val current = scan
    if (current == null) { LaunchedEffect(Unit) { onBack() }; return }
    val kb = vm.kbFor(current)

    LaunchedEffect(current.id, llm, lang) {
        if (llm == LlmStatus.IDLE) vm.loadLlmIfPresent()          // Llama not loaded yet → load it now
        if (llm == LlmStatus.READY) vm.requestAdvice()
    }
    DisposableEffect(Unit) { onDispose { vm.stopSpeaking() } }

    fun say(text: String, l: Lang) { voiceMissing = !vm.speak(text, l) }

    Scaffold(topBar = { AppBar(s.adviceTitle, onBack) }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("${cropName(current.best.label.crop, lang)} — ${vm.diseaseName(current.best.label.raw, current.best.label.condition, lang, s.healthyTitle)}",
                style = MaterialTheme.typography.titleLarge)
            // one-tap language switch: the same advice in English / Hindi / Tamil, offline
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                Lang.entries.forEach { l ->
                    FilterChip(selected = lang == l, onClick = { vm.stopSpeaking(); vm.setLanguage(l) },
                        label = { Text(l.nativeName, style = MaterialTheme.typography.bodyLarge) })
                }
            }
            Text("${s.adviceFor}: ${region?.display(lang) ?: "—"} · ${s.seasonLabel}: ${vm.season().display(lang)}",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            // ---- Tamil / Hindi: the team's pre-written advice from kb.json, read aloud ----
            if (lang != Lang.EN) {
                val local = kb?.local?.get(lang.code)
                SectionCard(container = LeafLight) {
                    Text(s.localAdviceTitle, style = MaterialTheme.typography.titleLarge)
                    if (local != null && local.hasContent) {
                        local.summary?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                        if (local.todo.isNotEmpty()) { Text(s.kbWhatToDo, style = MaterialTheme.typography.titleMedium); BulletList(local.todo) }
                        if (local.organic.isNotEmpty()) { Text(s.kbOrganic, style = MaterialTheme.typography.titleMedium); BulletList(local.organic) }
                        if (local.chemical.isNotEmpty()) { Text(s.kbChemical, style = MaterialTheme.typography.titleMedium); BulletList(local.chemical) }
                        local.expert?.let { Text(s.kbWhenExpert, style = MaterialTheme.typography.titleMedium); Text(it, style = MaterialTheme.typography.bodyLarge) }
                        val spoken = buildString {
                            local.summary?.let { append(it).append(". ") }
                            if (local.todo.isNotEmpty()) append(s.kbWhatToDo).append(". ").append(local.todo.joinToString(". ")).append(". ")
                            if (local.organic.isNotEmpty()) append(s.kbOrganic).append(". ").append(local.organic.joinToString(". ")).append(". ")
                            if (local.chemical.isNotEmpty()) append(s.kbChemical).append(". ").append(local.chemical.joinToString(". ")).append(". ")
                            local.expert?.let { append(s.kbWhenExpert).append(". ").append(it) }
                        }
                        ListenButton(speaking, s.listen, s.stopListening, onListen = { say(spoken, lang) }, onStop = vm::stopSpeaking)
                    } else Text(s.translationPending, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(s.moreDetailsEn, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // ---- Llama advice ----
            when (llm) {
                LlmStatus.READY -> SectionCard {
                    if (advice.text.isEmpty() && advice.generating) Generating(s.generating)
                    if (advice.text.isNotEmpty()) Text(advice.text, style = MaterialTheme.typography.bodyLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (advice.generating) OutlinedButton(onClick = vm::stopAdvice) {
                            Icon(Icons.Filled.Stop, null); Spacer(Modifier.width(6.dp)); Text(s.stop)
                        } else if (advice.text.isNotEmpty())
                            ListenButton(speaking, s.listen, s.stopListening, onListen = { say(advice.text, if (advice.hindi) Lang.HI else Lang.EN) }, onStop = vm::stopSpeaking)
                    }
                    advice.stats?.let {
                        Text("Llama 3.2 1B · ExecuTorch · ${"%.1f".format(it.timeToFirstTokenMs / 1000f)} s · ${"%.1f".format(it.decodeTokPerSec)} tok/s",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    advice.error?.let { Text(it, color = SevSevere) }
                }
                LlmStatus.LOADING, LlmStatus.IDLE -> { SectionCard { Generating(s.statusLlmLoading) }; KbFacts(kb) }
                else -> {
                    Text(s.llmNotReady, color = SevModerate, style = MaterialTheme.typography.bodyLarge)
                    KbFacts(kb)
                }
            }

            // ---- follow-up questions ----
            if (llm == LlmStatus.READY && advice.text.isNotEmpty()) {
                advice.followUps.forEach { f ->
                    SectionCard {
                        Text(f.question, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
                        Text(f.answer, style = MaterialTheme.typography.bodyLarge)
                        ListenButton(speaking, s.listen, s.stopListening, onListen = { say(f.answer, if (advice.hindi) Lang.HI else Lang.EN) }, onStop = vm::stopSpeaking)
                    }
                }
                advice.pendingQuestion?.let { q ->
                    SectionCard {
                        Text(q, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
                        if (draft.isEmpty()) Generating(s.generating) else Text(draft, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = question, onValueChange = { question = it },
                        placeholder = { Text(s.askFollowUp) }, modifier = Modifier.weight(1f),
                        enabled = !advice.generating, maxLines = 3,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { vm.askFollowUp(question); question = "" }),
                    )
                    IconButton(onClick = { vm.askFollowUp(question); question = "" }, enabled = !advice.generating && question.isNotBlank()) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = s.ask)
                    }
                }
            }

            if (voiceMissing) Text(s.voiceMissing, color = SevModerate, style = MaterialTheme.typography.bodyMedium)

            // ---- safety + human help ----
            SectionCard {
                Text(s.disclaimer, style = MaterialTheme.typography.bodyMedium)
            }
            SectionCard(container = LeafLight) {
                Text(s.kccTitle, style = MaterialTheme.typography.titleMedium)
                Text(s.kccHours, style = MaterialTheme.typography.bodyLarge)
                Button(onClick = {
                    // Opens the dialer with the number filled in — no phone permission needed
                    ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:18001801551")))
                }) { Icon(Icons.Filled.Call, null); Spacer(Modifier.width(8.dp)); Text(s.callNow) }
            }
            Spacer(Modifier.size(24.dp))
        }
    }
}

@Composable
private fun Generating(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(26.dp)); Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ListenButton(speaking: Boolean, listen: String, stop: String, onListen: () -> Unit, onStop: () -> Unit) {
    OutlinedButton(onClick = if (speaking) onStop else onListen) {
        Icon(if (speaking) Icons.Filled.Stop else Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null)
        Spacer(Modifier.width(6.dp)); Text(if (speaking) stop else listen)
    }
}

/** Facts straight from kb.json — shown when Llama isn't set up (and while it loads). */
@Composable
private fun KbFacts(kb: KbEntry?) {
    val s = LocalStrings.current
    SectionCard {
        if (kb == null) { Text(s.notInKb, style = MaterialTheme.typography.bodyLarge); return@SectionCard }
        if (kb.symptoms.isNotBlank()) { Text(s.kbSymptoms, style = MaterialTheme.typography.titleMedium); Text(kb.symptoms, style = MaterialTheme.typography.bodyLarge) }
        if (kb.cultural.isNotEmpty()) { Text(s.kbWhatToDo, style = MaterialTheme.typography.titleMedium); BulletList(kb.cultural) }
        if (kb.organic.isNotEmpty()) { Text(s.kbOrganic, style = MaterialTheme.typography.titleMedium); BulletList(kb.organic) }
        if (kb.chemical.isNotEmpty()) { Text(s.kbChemical, style = MaterialTheme.typography.titleMedium); BulletList(kb.chemical) }
        if (kb.escalateIf.isNotBlank()) { Text(s.kbWhenExpert, style = MaterialTheme.typography.titleMedium); Text(kb.escalateIf, style = MaterialTheme.typography.bodyLarge) }
    }
}
