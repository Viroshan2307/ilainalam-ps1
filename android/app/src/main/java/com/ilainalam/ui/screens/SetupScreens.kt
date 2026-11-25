package com.ilainalam.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ilainalam.i18n.Lang
import com.ilainalam.i18n.LocalStrings
import com.ilainalam.i18n.Region
import com.ilainalam.ui.LlmStatus
import com.ilainalam.ui.MainViewModel
import com.ilainalam.ui.components.AppBar
import com.ilainalam.ui.components.BigButton
import com.ilainalam.ui.components.BigOutlinedButton
import com.ilainalam.ui.components.SectionCard
import com.ilainalam.ui.theme.Leaf
import com.ilainalam.ui.theme.SevSevere

/** First launch: pick the interface language, then the state (for region-aware advice). */
@Composable
fun LanguageScreen(vm: MainViewModel, onDone: () -> Unit) {
    var pickState by remember { mutableStateOf(false) }
    if (pickState) { StatePicker(vm, onDone); return }
    Scaffold { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Filled.Eco, contentDescription = null, tint = Leaf, modifier = Modifier.size(84.dp))
            Text("IlaiNalam", style = MaterialTheme.typography.headlineSmall)
            Text("Choose your language · अपनी भाषा चुनें · உங்கள் மொழியைத் தேர்ந்தெடுக்கவும்",
                textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(8.dp))
            Lang.entries.forEach { l ->
                BigButton(l.nativeName, Icons.Filled.Translate, onClick = { vm.setLanguage(l); pickState = true })
            }
        }
    }
}

/** "Your state" — used to tailor Llama's advice (with the season from the phone's date). Also opened from Home. */
@Composable
fun StatePicker(vm: MainViewModel, onDone: () -> Unit) {
    val s = LocalStrings.current
    val lang by vm.lang.collectAsStateWithLifecycle()
    Scaffold { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Filled.Place, contentDescription = null, tint = Leaf, modifier = Modifier.size(64.dp))
            Text(s.stateTitle, style = MaterialTheme.typography.headlineSmall)
            Text(s.stateHint, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
            Region.entries.forEach { r ->
                BigOutlinedButton(r.display(lang), Icons.Filled.Place, onClick = { vm.setRegion(r); onDone() })
            }
        }
    }
}

/** One-time import of the Llama model + tokenizer (or adb push). */
@Composable
fun SetupScreen(vm: MainViewModel, onBack: (() -> Unit)?, onDone: () -> Unit) {
    val s = LocalStrings.current
    val llm by vm.llm.collectAsStateWithLifecycle()
    val err by vm.llmError.collectAsStateWithLifecycle()
    val progress by vm.importProgress.collectAsStateWithLifecycle()
    val test by vm.testResult.collectAsStateWithLifecycle()

    val pickModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importLlmFile(uri, isModel = true)
    }
    val pickTok = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importLlmFile(uri, isModel = false)
    }
    val modelPresent = vm.llmFiles.findModel() != null
    val tokPresent = vm.llmFiles.findTokenizer() != null

    Scaffold(topBar = { AppBar(s.setupTitle, onBack) }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(s.setupBody, style = MaterialTheme.typography.bodyLarge)

            BigOutlinedButton(s.pickModel, if (modelPresent) Icons.Filled.CheckCircle else Icons.Filled.Memory,
                onClick = { pickModel.launch(arrayOf("*/*")) }, enabled = progress == null)
            BigOutlinedButton(s.pickTokenizer, if (tokPresent) Icons.Filled.CheckCircle else Icons.Filled.Description,
                onClick = { pickTok.launch(arrayOf("*/*")) }, enabled = progress == null)

            progress?.let { (name, p) ->
                Text("${s.copying} $name  ${(p * 100).toInt()}%")
                LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
            }

            when (llm) {
                LlmStatus.LOADING -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(28.dp)); Spacer(Modifier.width(12.dp)); Text(s.loadingModel)
                }
                LlmStatus.READY -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CheckCircle, null, tint = Leaf, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.width(10.dp)); Text(s.llmReady, style = MaterialTheme.typography.titleMedium)
                    }
                    BigButton(s.continueBtn, Icons.AutoMirrored.Filled.ArrowForward, onClick = onDone)
                }
                else -> {}
            }
            err?.let { Text(it, color = SevSevere) }

            if (llm != LlmStatus.READY) TextButton(onClick = onDone) { Text(s.skipScanOnly) }

            // Self-test: runs both models once and shows timings (or the exact error)
            BigOutlinedButton(s.testModels, Icons.Filled.Science, onClick = { vm.testModels() },
                enabled = llm != LlmStatus.LOADING && progress == null)
            test?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

            SectionCard {
                Text(s.adbHint, style = MaterialTheme.typography.bodyMedium)
                Text("${vm.llmFiles.adbDir?.absolutePath}/", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Shown if the vision model / labels were not copied into app assets before building. */
@Composable
fun MissingAssetsScreen(missing: List<String>) {
    val s = LocalStrings.current
    Scaffold(topBar = { AppBar("IlaiNalam") }) { pad ->
        Column(Modifier.padding(pad).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(s.missingAssetsTitle, style = MaterialTheme.typography.titleLarge, color = SevSevere)
            Text(s.missingAssetsBody, style = MaterialTheme.typography.bodyLarge)
            missing.forEach { Text("• $it", style = MaterialTheme.typography.bodyLarge) }
        }
    }
}
