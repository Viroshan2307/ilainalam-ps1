package com.ilainalam.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ilainalam.i18n.LocalStrings
import com.ilainalam.ui.MainViewModel
import com.ilainalam.ui.components.AppBar
import com.ilainalam.ui.components.BigButton
import com.ilainalam.ui.components.BigOutlinedButton
import com.ilainalam.ui.components.SectionCard
import kotlinx.coroutines.delay

/** Numbers for the technical report: run on both phones and copy the text. */
@Composable
fun BenchmarkScreen(vm: MainViewModel, onBack: () -> Unit) {
    val s = LocalStrings.current
    val ctx = LocalContext.current
    val scan by vm.scan.collectAsStateWithLifecycle()
    val speed by vm.speed.collectAsStateWithLifecycle()
    val advice by vm.advice.collectAsStateWithLifecycle()
    val peak by vm.peakPssMb.collectAsStateWithLifecycle()
    val llmPower by vm.lastLlmPower.collectAsStateWithLifecycle()
    val powerBusy by vm.powerBusy.collectAsStateWithLifecycle()
    var tick by remember { mutableIntStateOf(0) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { while (true) { delay(2000); tick++ } }   // refresh the memory line

    Scaffold(topBar = { AppBar(s.benchTitle, onBack) }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard { Text(vm.deviceInfo(), style = MaterialTheme.typography.bodyLarge) }
            SectionCard {
                Text("Vision · " + (if (vm.assets.visionConfig.version == "v3") "MobileNetV3-Large INT8 (4.6 MB)"
                    else "EfficientNet-B2 ${vm.assets.visionConfig.version} (${vm.assets.visionConfig.inputSize} px${if (vm.assets.visionConfig.ttaFlip) ", mirror-average" else ""})") + (if (vm.assets.hasGate) " + leaf-photo gate MobileNetV3-Small FP32" else "") +
                    " · ExecuTorch XNNPACK", style = MaterialTheme.typography.titleMedium)
                Text("Load: ${vm.visionLoadMs()} ms", style = MaterialTheme.typography.bodyLarge)
                scan?.timings?.let { t ->
                    Text("Last scan: ${t.totalMs} ms total", style = MaterialTheme.typography.bodyLarge)
                    Text("decode ${t.decodeMs} · preprocess ${t.preprocessMs} · classify ${t.classifyMs} · severity ${t.severityMs} · quality ${t.qualityMs} ms",
                        style = MaterialTheme.typography.bodyMedium)
                }
                speed?.let {
                    Text("Speed test (${it.runs} runs): classify median ${it.classifyMedianMs} ms (min ${it.classifyMinMs}) · severity median ${it.severityMedianMs} ms",
                        style = MaterialTheme.typography.bodyLarge)
                    when {
                        it.visionPower?.charging == true ->
                            Text("Power: phone is charging — unplug it and run again", style = MaterialTheme.typography.bodyMedium)
                        it.visionPower != null -> {
                            Text("Power: idle ${"%.0f".format(it.idlePower?.avgMilliwatts ?: 0.0)} mW · during scans ${"%.0f".format(it.visionPower.avgMilliwatts)} mW",
                                style = MaterialTheme.typography.bodyLarge)
                            it.scanMilliJoules?.let { mj -> Text("Energy per scan (above idle): ${"%.1f".format(mj)} mJ", style = MaterialTheme.typography.bodyLarge) }
                        }
                        else -> Text("Power: this phone's battery gauge gave no reading", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text("Unplug the phone before measuring power. The test takes about 10 seconds.", style = MaterialTheme.typography.bodyMedium)
                BigOutlinedButton(if (powerBusy) "Measuring…" else s.runSpeedTest, Icons.Filled.Speed,
                    onClick = { vm.runSpeedTest() }, enabled = scan != null && !powerBusy)
            }
            SectionCard {
                Text("Llama 3.2 1B-Instruct · 8da4w · ExecuTorch XNNPACK", style = MaterialTheme.typography.titleMedium)
                Text("Load: ${vm.llmLoadMs()} ms", style = MaterialTheme.typography.bodyLarge)
                advice.stats?.let {
                    Text("First token: ${it.timeToFirstTokenMs} ms", style = MaterialTheme.typography.bodyLarge)
                    Text("Decode: ${"%.1f".format(it.decodeTokPerSec)} tok/s · Prefill: ${"%.1f".format(it.prefillTokPerSec)} tok/s",
                        style = MaterialTheme.typography.bodyLarge)
                    Text("Tokens: ${it.promptTokens} prompt + ${it.generatedTokens} generated · total ${it.totalMs} ms",
                        style = MaterialTheme.typography.bodyMedium)
                }
                llmPower?.let {
                    Text(if (it.charging) "Power: phone was charging during the last answer — unplug and ask again"
                         else "Power during last answer: ${"%.0f".format(it.avgMilliwatts)} mW · ${"%.1f".format(it.seconds)} s · ${"%.1f".format(it.joules)} J",
                        style = MaterialTheme.typography.bodyLarge)
                }
            }
            SectionCard {
                key(tick) { Text("Memory (PSS): now ${vm.currentPssMb()} MB · peak $peak MB", style = MaterialTheme.typography.bodyLarge) }
            }
            BigButton(if (copied) s.copied else s.copyResults, Icons.Filled.ContentCopy, onClick = {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("IlaiNalam benchmark", vm.benchmarkReport()))
                copied = true
            })
            Spacer(Modifier.size(16.dp))
        }
    }
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val s = LocalStrings.current
    Scaffold(topBar = { AppBar(s.aboutTitle, onBack) }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard { Text(s.aboutBody, style = MaterialTheme.typography.bodyLarge) }
            SectionCard {
                Text("How it works (all on the phone)", style = MaterialTheme.typography.titleMedium)
                Text("1. Disease detection — MobileNetV3-Large (38 classes, 14 crops) trained on PlantVillage lab photos with swapped backgrounds plus PlantDoc field photos, INT8, run with ExecuTorch + XNNPACK on the Arm CPU. Accuracy: 98.2% on lab photos, 66% on real field photos (PlantDoc test set), about 77% when the farmer chooses the crop.", style = MaterialTheme.typography.bodyMedium)
                Text("1b. Leaf-photo check — a small AI model (MobileNetV3-Small) first checks that the photo shows a crop leaf; people, objects and green non-plant things are rejected. Results the disease model is unsure about name no disease and offer expert help instead.", style = MaterialTheme.typography.bodyMedium)
                Text("2. Severity — share of the leaf area with lesions (colour analysis), shown as Mild / Moderate / Severe.", style = MaterialTheme.typography.bodyMedium)
                Text("3. Photo quality check — blur, light, background and leaf size, with tips to retake.", style = MaterialTheme.typography.bodyMedium)
                Text("4. Advice — Llama 3.2 1B-Instruct (4-bit) through ExecuTorch, using only facts from the built-in knowledge base.", style = MaterialTheme.typography.bodyMedium)
                Text("5. Languages — English, Hindi and Tamil interface, read aloud with the phone's offline voice.", style = MaterialTheme.typography.bodyMedium)
            }
            SectionCard {
                Text("Privacy", style = MaterialTheme.typography.titleMedium)
                Text("The app has no internet permission. Photos and results never leave the phone.", style = MaterialTheme.typography.bodyMedium)
            }
            SectionCard {
                Text("Limits (Phase 1)", style = MaterialTheme.typography.titleMedium)
                Text("Real field photos are harder than lab photos (66% vs 98%): photograph one leaf on a plain background and choose the crop for the best result. Pests, rice and wheat are planned for Phase 2. Advice is guidance only — follow the pesticide label and consult your Krishi Vigyan Kendra.", style = MaterialTheme.typography.bodyMedium)
            }
            SectionCard {
                Text("Built for the Bharat AI-SoC Challenge 2026–27 · Problem Statement 1", style = MaterialTheme.typography.bodyMedium)
                Text("Llama 3.2 is licensed under the Llama 3.2 Community License. Built with Llama.", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
