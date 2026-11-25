package com.ilainalam.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ilainalam.i18n.LocalStrings
import com.ilainalam.i18n.cropName
import com.ilainalam.i18n.Strings
import com.ilainalam.ml.QualityGrade
import com.ilainalam.ml.SeverityLevel
import com.ilainalam.ml.QualityIssue
import com.ilainalam.ui.MainViewModel
import com.ilainalam.ui.Scan
import com.ilainalam.ui.ScanStep
import com.ilainalam.ui.components.AppBar
import com.ilainalam.ui.components.BadgeRow
import com.ilainalam.ui.components.BigButton
import com.ilainalam.ui.components.BigOutlinedButton
import com.ilainalam.ui.components.SectionCard
import com.ilainalam.ui.components.qualityBadge
import com.ilainalam.ui.components.severityBadge
import com.ilainalam.ui.theme.Leaf
import com.ilainalam.ui.theme.LeafLight
import com.ilainalam.ui.theme.SevModerate
import com.ilainalam.ui.theme.SevSevere
import kotlin.math.roundToInt

private enum class View { PHOTO, LEAF, SPOTS }

@Composable
fun ResultScreen(vm: MainViewModel, onBack: () -> Unit, onRetake: () -> Unit, onAdvice: () -> Unit, onAddPhoto: () -> Unit = {}) {
    val s = LocalStrings.current
    val scan by vm.scan.collectAsStateWithLifecycle()
    val analyzing by vm.analyzing.collectAsStateWithLifecycle()
    val step by vm.step.collectAsStateWithLifecycle()
    val error by vm.scanError.collectAsStateWithLifecycle()
    val retrying by vm.retrying.collectAsStateWithLifecycle()

    Scaffold(topBar = { AppBar(s.diagnosis, onBack) }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when {
                analyzing -> AnalysingSteps(step, retrying)
                error != null -> {
                    Text(if (error == MainViewModel.ERR_TIMEOUT) s.timeoutError else (error ?: ""),
                        color = SevSevere, style = MaterialTheme.typography.bodyLarge)
                    BigButton(s.retake, Icons.Filled.CameraAlt, onClick = { vm.clearScanError(); onRetake() })
                }
                scan != null -> {
                    val rejected by vm.addRejected.collectAsStateWithLifecycle()
                    if (rejected) SectionCard(container = LeafLight) {
                        StepLine(Icons.AutoMirrored.Filled.HelpOutline, SevModerate, s.photoNotAdded)
                    }
                    ScanResult(vm, scan!!, onRetake, onAdvice, onAddPhoto)
                }
            }
        }
    }
}

/** Live progress: 1. find leaf → 2. check spots → 3. identify disease. */
@Composable
private fun AnalysingSteps(step: ScanStep, retrying: Boolean) {
    val s = LocalStrings.current
    val order = listOf(ScanStep.LEAF to s.analyzingLeaf, ScanStep.SPOTS to s.analyzingSpots, ScanStep.DISEASE to s.analyzingDisease)
    SectionCard {
        Text(s.analyzing, style = MaterialTheme.typography.titleMedium)
        order.forEach { (st, label) ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                when {
                    step.ordinal > st.ordinal -> Icon(Icons.Filled.CheckCircle, null, tint = Leaf, modifier = Modifier.size(28.dp))
                    step == st -> CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 3.dp)
                    else -> Icon(Icons.Filled.RadioButtonUnchecked, null, tint = Color.Gray, modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text(label, style = MaterialTheme.typography.bodyLarge)
            }
        }
        if (retrying) Text(s.stillWorking, style = MaterialTheme.typography.bodyLarge, color = SevModerate)
    }
}

/** Look-alike pairs: when the top two answers are one of these, show how to tell them apart. */
private fun lookAlikeHint(a: String, b: String, s: Strings): String? {
    val pair = setOf(a, b)
    return when (pair) {
        setOf("Tomato___Tomato_Yellow_Leaf_Curl_Virus", "Tomato___Tomato_mosaic_virus") -> s.hintVirus
        setOf("Tomato___Early_blight", "Tomato___Target_Spot") -> s.hintBlight
        setOf("Corn_(maize)___Cercospora_leaf_spot Gray_leaf_spot", "Corn_(maize)___Northern_Leaf_Blight") -> s.hintCorn
        else -> null
    }
}

/** "Get expert help": agriculture officer / KVK, and a one-tap call to the free Kisan Call Centre. */
@Composable
private fun ExpertHelpCard() {
    val s = LocalStrings.current
    val ctx = LocalContext.current
    SectionCard(container = LeafLight) {
        StepLine(Icons.Filled.SupportAgent, SevSevere, s.expertTitle)
        Text(s.expertBody, style = MaterialTheme.typography.bodyLarge)
        Text(s.kccHours, style = MaterialTheme.typography.bodyMedium)
        Button(onClick = { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:18001801551"))) }) {
            Icon(Icons.Filled.Call, null); Spacer(Modifier.width(8.dp)); Text(s.callNow)
        }
    }
}

@Composable
private fun StepLine(icon: ImageVector, tint: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ScanResult(vm: MainViewModel, scan: Scan, onRetake: () -> Unit, onAdvice: () -> Unit, onAddPhoto: () -> Unit) {
    val s = LocalStrings.current
    val lang by vm.lang.collectAsStateWithLifecycle()
    var view by remember(scan.id) { mutableStateOf(View.PHOTO) }   // opens on the normal photo
    var part by remember(scan.id, scan.photoCount) { mutableStateOf(scan.photoCount - 1) }   // newest photo first
    val shown = scan.parts.getOrNull(part) ?: scan          // each photo keeps its own Leaf / Spots view
    val canAdd = scan.photoCount < MainViewModel.MAX_PHOTOS
    var acceptedPoor by remember(scan.id) { mutableStateOf(false) }
    val poor = scan.quality.grade == QualityGrade.POOR && !acceptedPoor
    val healthy = scan.best.label.healthy
    val leafPct = (scan.severity.leafCoverage * 100).roundToInt()
    val spotPct = (scan.severity.fraction * 100).roundToInt()

    Image(
        when (view) { View.PHOTO -> shown.square; View.LEAF -> shown.leafView; View.SPOTS -> shown.spotsView }.asImageBitmap(),
        contentDescription = when (view) { View.PHOTO -> s.showPhoto; View.LEAF -> s.showLeaf; View.SPOTS -> s.showLesions },
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(18.dp)),
    )
    if (scan.parts.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        scan.parts.indices.forEach { i ->
            FilterChip(selected = part == i, onClick = { part = i }, label = { Text("${s.photoLabel} ${i + 1}") })
        }
    }
    if (scan.leafFound) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = view == View.PHOTO, onClick = { view = View.PHOTO }, label = { Text(s.showPhoto) })
        FilterChip(selected = view == View.LEAF, onClick = { view = View.LEAF }, label = { Text(s.showLeaf) })
        FilterChip(selected = view == View.SPOTS, onClick = { view = View.SPOTS }, label = { Text(s.showLesions) })
    }

    // ---- 1. leaf found? (background rejected) ----
    if (!scan.leafFound) {
        SectionCard(container = LeafLight) {
            StepLine(Icons.Filled.Cancel, SevSevere, s.noLeafTitle)
            Text(s.noLeafBody, style = MaterialTheme.typography.bodyLarge)
            BigButton(s.retake, Icons.Filled.CameraAlt, onClick = onRetake)
        }
        return
    }

    // ---- 1b. is it a plant at all? (face, wall, object → no disease named) ----
    if (scan.notPlant) {
        SectionCard(container = LeafLight) {
            StepLine(Icons.Filled.Cancel, SevSevere, s.notPlantTitle)
            Text(s.notPlantBody, style = MaterialTheme.typography.bodyLarge)
            BigButton(s.retake, Icons.Filled.CameraAlt, onClick = onRetake)
        }
        return
    }

    // ---- how the photo was checked ----
    SectionCard {
        Text(s.stepsTitle, style = MaterialTheme.typography.titleMedium)
        StepLine(Icons.Filled.CheckCircle, Leaf, "${s.stepLeaf} · $leafPct% ${s.ofPhoto}")
        StepLine(Icons.Filled.CheckCircle, Leaf, "${s.stepSpots} · $spotPct% ${s.leafAffected}")
        StepLine(Icons.Filled.CheckCircle, Leaf, s.stepDisease)
        if (scan.parts.size > 1) StepLine(Icons.Filled.CheckCircle, Leaf, s.combinedFrom.format(scan.parts.size))
    }

    // ---- photo quality ----
    SectionCard {
        BadgeRow(s.photoQuality, qualityBadge(scan.quality.grade))
        scan.quality.issues.forEach { issue ->
            Text("• " + when (issue) {
                QualityIssue.BLURRY -> s.issueBlurry
                QualityIssue.TOO_DARK -> s.issueDark
                QualityIssue.TOO_BRIGHT -> s.issueBright
                QualityIssue.BUSY_BACKGROUND -> s.issueBusy
                QualityIssue.LEAF_TOO_SMALL -> s.issueSmall
            }, style = MaterialTheme.typography.bodyLarge)
        }
        if (scan.quality.grade != QualityGrade.GOOD)
            BigOutlinedButton(s.retake, Icons.Filled.CameraAlt, onClick = onRetake)
        if (poor) BigOutlinedButton(s.continueAnyway, Icons.AutoMirrored.Filled.ArrowForward, onClick = { acceptedPoor = true })
    }
    if (poor) return

    // ---- low confidence: no disease is named and no advice is offered (a wrong guess could mean a wrong spray) ----
    if (scan.lowConfidence) {
        SectionCard(container = LeafLight) {
            StepLine(Icons.AutoMirrored.Filled.HelpOutline, SevModerate, s.lowConfTitle)
            Text(s.lowConfBody, style = MaterialTheme.typography.bodyLarge)
            if (canAdd) BigButton(s.addPhoto, Icons.Filled.AddAPhoto, onClick = onAddPhoto)
            BigOutlinedButton(s.retake, Icons.Filled.CameraAlt, onClick = onRetake)
        }
        SectionCard {
            Text(s.possibly, style = MaterialTheme.typography.titleMedium)
            scan.model.top.filter { it.probability >= 0.05f }.forEach {
                Text((if (scan.chosenCrop != null) "" else "${cropName(it.label.crop, lang)} — ") +
                    "${vm.diseaseName(it.label.raw, it.label.condition, lang, s.healthyTitle)}: ${(it.probability * 100).roundToInt()}%",
                    style = MaterialTheme.typography.bodyLarge)
            }
        }
        ExpertHelpCard()
        Spacer(Modifier.size(16.dp))
        return
    }

    // ---- diagnosis ----
    val best = scan.best
    SectionCard {
        Text(s.diagnosis, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(cropName(best.label.crop, lang), style = MaterialTheme.typography.titleMedium)
        scan.chosenCrop?.let {
            Text("${s.checkedAsCrop} ${cropName(it, lang)}", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(vm.diseaseName(best.label.raw, best.label.condition, lang, s.healthyTitle),
            style = MaterialTheme.typography.headlineSmall, color = if (healthy) Leaf else MaterialTheme.colorScheme.onSurface)
        Text("${s.confidence}: ${(best.probability * 100).roundToInt()}%", style = MaterialTheme.typography.bodyLarge)
        if (healthy) {
            StepLine(Icons.Filled.CheckCircle, Leaf, s.healthyBody)
        } else {
            BadgeRow(s.severity, severityBadge(scan.severity.level), "$spotPct% ${s.leafAffected}")
            // ---- urgency: early warning vs act now ----
            val (urgency, tint) = when (scan.severity.level) {
                SeverityLevel.MILD -> s.urgencyMild to Leaf
                SeverityLevel.MODERATE -> s.urgencyModerate to SevModerate
                SeverityLevel.SEVERE -> s.urgencySevere to SevSevere
            }
            StepLine(Icons.Filled.Schedule, tint, urgency)
        }
    }

    // ---- look-alike diseases: how to tell them apart ----
    val second = scan.model.second
    val hint = second?.takeIf { it.probability >= 0.10f }?.let { lookAlikeHint(best.label.raw, it.label.raw, s) }
    if (hint != null) SectionCard {
        StepLine(Icons.Filled.Visibility, SevModerate, s.lookAlikeTitle)
        Text(hint, style = MaterialTheme.typography.bodyLarge)
    }

    // ---- severe → route to an expert ----
    if (!healthy && scan.severity.level == SeverityLevel.SEVERE) ExpertHelpCard()

    // ---- other possibilities (honest about uncertainty) ----
    val others = scan.model.top.drop(1).filter { it.probability >= 0.05f }
    if (others.isNotEmpty()) SectionCard {
        Text(s.otherPossibilities, style = MaterialTheme.typography.titleMedium)
        others.forEach {
            Text((if (scan.chosenCrop != null) "" else "${cropName(it.label.crop, lang)} — ") + "${vm.diseaseName(it.label.raw, it.label.condition, lang, s.healthyTitle)}: ${(it.probability * 100).roundToInt()}%",
                style = MaterialTheme.typography.bodyLarge)
        }
    }

    BigButton(s.getAdvice, Icons.AutoMirrored.Filled.ArrowForward, onClick = onAdvice)
    if (canAdd) BigOutlinedButton(s.addPhoto, Icons.Filled.AddAPhoto, onClick = onAddPhoto)
    Spacer(Modifier.size(16.dp))
}
