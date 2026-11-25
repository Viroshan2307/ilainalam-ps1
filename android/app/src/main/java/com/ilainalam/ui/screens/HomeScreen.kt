package com.ilainalam.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ilainalam.data.HistoryItem
import com.ilainalam.i18n.Lang
import com.ilainalam.i18n.LocalStrings
import com.ilainalam.i18n.cropName
import com.ilainalam.ml.SeverityLevel
import com.ilainalam.ui.LlmStatus
import com.ilainalam.ui.MainViewModel
import com.ilainalam.ui.components.AppBar
import com.ilainalam.ui.components.BigButton
import com.ilainalam.ui.components.BigOutlinedButton
import com.ilainalam.ui.components.SectionCard
import com.ilainalam.ui.components.severityBadge
import com.ilainalam.ui.theme.Leaf
import com.ilainalam.ui.theme.SevModerate
import java.text.DateFormat
import java.util.Date

@Composable
fun HomeScreen(
    vm: MainViewModel,
    onScan: () -> Unit, onOpenHistory: (HistoryItem) -> Unit,
    onSetup: () -> Unit, onBenchmark: () -> Unit, onAbout: () -> Unit,
    onAdvisor: () -> Unit, onState: () -> Unit,
) {
    val s = LocalStrings.current
    val lang by vm.lang.collectAsStateWithLifecycle()
    val llm by vm.llm.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val region by vm.region.collectAsStateWithLifecycle()
    var langMenu by remember { mutableStateOf(false) }

    Scaffold(topBar = {
        AppBar("IlaiNalam", actions = {
            IconButton(onClick = { langMenu = true }) { Icon(Icons.Filled.Translate, contentDescription = s.language) }
            DropdownMenu(expanded = langMenu, onDismissRequest = { langMenu = false }) {
                Lang.entries.forEach { l ->
                    DropdownMenuItem(text = { Text(l.nativeName) }, onClick = { vm.setLanguage(l); langMenu = false })
                }
            }
            IconButton(onClick = onBenchmark) { Icon(Icons.Filled.Speed, contentDescription = s.benchmark) }
            IconButton(onClick = onAbout) { Icon(Icons.Filled.Info, contentDescription = s.about) }
        })
    }) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Spacer(Modifier.size(8.dp))
                Text(s.tagline, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                SectionCard {
                    StatusLine(Icons.Filled.CheckCircle, Leaf, s.statusVision)
                    val (icon, color, text) = when (llm) {
                        LlmStatus.READY -> Triple(Icons.Filled.CheckCircle, Leaf, s.statusLlmReady)
                        LlmStatus.LOADING -> Triple(Icons.Filled.HourglassTop, SevModerate, s.statusLlmLoading)
                        LlmStatus.IDLE -> Triple(Icons.Filled.CheckCircle, Leaf, s.statusLlmIdle)
                        else -> Triple(Icons.Filled.Warning, SevModerate, s.statusLlmMissing)
                    }
                    Row(Modifier.clickable(enabled = llm != LlmStatus.LOADING) { onSetup() }) {   // also opens "Test models"
                        StatusLine(icon, color, text)
                    }
                    Row(Modifier.clickable { onState() }) {                                          // change state
                        StatusLine(Icons.Filled.Place, Leaf,
                            "${s.stateTitle}: ${region?.display(lang) ?: "—"} · ${s.seasonLabel}: ${vm.season().display(lang)}")
                    }
                }
            }
            item { BigButton(s.scanCrop, Icons.Filled.CameraAlt, onClick = onScan, modifier = Modifier.padding(vertical = 4.dp)) }
            item { BigOutlinedButton(s.advisorTitle, Icons.Filled.Forum, onClick = onAdvisor) }
            item { Text(s.recentScans, style = MaterialTheme.typography.titleMedium) }
            if (history.isEmpty()) item { Text(s.noScansYet, style = MaterialTheme.typography.bodyLarge) }
            items(history, key = { it.id }) { h -> HistoryRow(vm, h, lang) { onOpenHistory(h) } }
            item { Spacer(Modifier.size(24.dp)) }
        }
    }
}

@Composable
private fun StatusLine(icon: androidx.compose.ui.graphics.vector.ImageVector, color: androidx.compose.ui.graphics.Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun HistoryRow(vm: MainViewModel, h: HistoryItem, lang: Lang, onClick: () -> Unit) {
    val s = LocalStrings.current
    val thumb = remember(h.id) { vm.historyThumb(h.id) }
    SectionCard(Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            thumb?.let {
                Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)))
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.fillMaxWidth()) {
                Text("${cropName(h.crop, lang)} — ${vm.diseaseName(h.labelRaw, h.condition, lang, s.healthyTitle)}",
                    style = MaterialTheme.typography.titleMedium)
                val sev = if (h.healthy) s.healthyTitle else
                    severityBadge(runCatching { SeverityLevel.valueOf(h.severityLabel) }.getOrDefault(SeverityLevel.MILD)).text
                Text("$sev · ${(h.confidence * 100).toInt()}% · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(h.time))}",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
