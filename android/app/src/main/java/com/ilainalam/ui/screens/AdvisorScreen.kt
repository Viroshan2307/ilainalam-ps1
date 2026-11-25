package com.ilainalam.ui.screens

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
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ilainalam.i18n.Lang
import com.ilainalam.i18n.LocalStrings
import com.ilainalam.ui.LlmStatus
import com.ilainalam.ui.MainViewModel
import com.ilainalam.ui.components.AppBar
import com.ilainalam.ui.components.SectionCard
import com.ilainalam.ui.theme.LeafLight
import com.ilainalam.ui.theme.SevModerate

/** "Ask the farming advisor": general questions to Llama 3.2 1B on the phone, no photo needed, offline. */
@Composable
fun AdvisorScreen(vm: MainViewModel, onBack: () -> Unit) {
    val s = LocalStrings.current
    val lang by vm.lang.collectAsStateWithLifecycle()
    val llm by vm.llm.collectAsStateWithLifecycle()
    val region by vm.region.collectAsStateWithLifecycle()
    val st by vm.advisor.collectAsStateWithLifecycle()
    val speaking by vm.speaker.speaking.collectAsStateWithLifecycle()
    var question by remember { mutableStateOf("") }
    var voiceMissing by remember { mutableStateOf(false) }

    LaunchedEffect(llm) { if (llm == LlmStatus.IDLE) vm.loadLlmIfPresent() }
    DisposableEffect(Unit) { onDispose { vm.stopSpeaking() } }

    fun send(q: String) { if (q.isNotBlank()) { vm.askAdvisor(q); question = "" } }
    val ready = llm == LlmStatus.READY && !st.generating

    Scaffold(topBar = { AppBar(s.advisorTitle, onBack) }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(s.advisorIntro, style = MaterialTheme.typography.bodyLarge)
            Text("${s.stateTitle}: ${region?.display(lang) ?: "—"} · ${s.seasonLabel}: ${vm.season().display(lang)}",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            when (llm) {
                LlmStatus.NOT_SET_UP, LlmStatus.ERROR ->
                    SectionCard(container = LeafLight) { Text(s.advisorNeedsSetup, style = MaterialTheme.typography.bodyLarge) }
                LlmStatus.IDLE, LlmStatus.LOADING -> SectionCard { Busy(s.statusLlmLoading) }
                LlmStatus.READY -> Unit
            }

            // previous questions and answers
            st.turns.forEach { t ->
                SectionCard {
                    Text("❓ ${t.question}", style = MaterialTheme.typography.titleMedium)
                    Text(t.answer, style = MaterialTheme.typography.bodyLarge)
                    OutlinedButton(onClick = { if (speaking) vm.stopSpeaking() else voiceMissing = !vm.speak(t.answer, if (lang == Lang.HI) Lang.HI else Lang.EN) }) {
                        Icon(if (speaking) Icons.Filled.Stop else Icons.AutoMirrored.Filled.VolumeUp, null)
                        Spacer(Modifier.width(8.dp)); Text(if (speaking) s.stopListening else s.listen)
                    }
                }
            }
            st.pendingQuestion?.let { q ->
                SectionCard {
                    Text("❓ $q", style = MaterialTheme.typography.titleMedium)
                    if (st.draft.isBlank()) Busy(s.generating) else Text(st.draft, style = MaterialTheme.typography.bodyLarge)
                }
            }
            st.error?.let { Text(it, color = SevModerate, style = MaterialTheme.typography.bodyMedium) }

            // example questions (English, because the answers are in English)
            if (st.turns.isEmpty() && st.pendingQuestion == null) s.advisorExamples.forEach { ex ->
                AssistChip(onClick = { send(ex) }, enabled = ready, label = { Text(ex, style = MaterialTheme.typography.bodyMedium) })
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = question, onValueChange = { question = it },
                    placeholder = { Text(s.advisorAsk) }, modifier = Modifier.weight(1f),
                    enabled = llm == LlmStatus.READY, maxLines = 3,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (ready) send(question) }),
                )
                IconButton(onClick = { send(question) }, enabled = ready && question.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = s.ask)
                }
            }
            if (voiceMissing) Text(s.voiceMissing, color = SevModerate, style = MaterialTheme.typography.bodyMedium)
            SectionCard(container = LeafLight) { Text(s.advisorNote, style = MaterialTheme.typography.bodyMedium) }
            Spacer(Modifier.size(24.dp))
        }
    }
}

@Composable
private fun Busy(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        CircularProgressIndicator(Modifier.size(24.dp)); Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
