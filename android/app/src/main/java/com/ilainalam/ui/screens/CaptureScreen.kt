package com.ilainalam.ui.screens

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ilainalam.i18n.LocalStrings
import com.ilainalam.i18n.cropEmoji
import com.ilainalam.i18n.cropName
import com.ilainalam.ui.MainViewModel
import com.ilainalam.ui.components.AppBar
import com.ilainalam.ui.components.BigButton
import com.ilainalam.ui.components.BigOutlinedButton
import com.ilainalam.ui.components.SectionCard
import com.ilainalam.ui.theme.Leaf
import com.ilainalam.ui.theme.LeafLight
import com.ilainalam.ui.theme.SevSevere
import java.io.File

/** Capture guide + camera/gallery. The photo is centre-cropped to the square shown in the guide. */
@Composable
fun CaptureScreen(vm: MainViewModel, onBack: () -> Unit, onPhoto: () -> Unit, onLiveCamera: () -> Unit = {}) {
    val s = LocalStrings.current
    val ctx = LocalContext.current
    var pendingUri by rememberSaveable { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = pendingUri
        if (ok && u != null) { vm.analyze(Uri.parse(u)); onPhoto() }
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) { vm.analyze(uri); onPhoto() }
    }

    fun launchCamera() {
        val dir = File(ctx.cacheDir, "captures").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }   // keep only the latest capture
        val file = File(dir, "leaf_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        pendingUri = uri.toString()
        try { takePicture.launch(uri) } catch (e: ActivityNotFoundException) { error = s.noCamera }
    }

    Scaffold(topBar = { AppBar(s.captureTitle, onBack) }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val adding by vm.addMode.collectAsStateWithLifecycle()
            if (adding) SectionCard(container = LeafLight) {
                val n = (vm.scan.value?.photoCount ?: 1) + 1
                Text("${s.addPhoto} · ${s.photoLabel} $n / ${MainViewModel.MAX_PHOTOS}", style = MaterialTheme.typography.titleMedium)
                Text(s.maxPhotos, style = MaterialTheme.typography.bodyLarge)
            } else CropPicker(vm)
            GuideIllustration(Modifier.fillMaxWidth(0.75f).aspectRatio(1f).align(Alignment.CenterHorizontally)
                .semantics { contentDescription = s.tipPaper })
            SectionCard {
                Tip(Icons.Filled.Description, s.tipPaper)
                Tip(Icons.Filled.WbSunny, s.tipLight)
                Tip(Icons.Filled.CropFree, s.tipFill)
                Text(s.tipWhy, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val ready by vm.visionReady.collectAsStateWithLifecycle()     // model loaded on its own thread first
            BigButton(if (ready) s.liveCamera else s.gettingReady, Icons.Filled.CenterFocusStrong, onClick = onLiveCamera, enabled = ready)
            BigOutlinedButton(if (ready) s.takePhoto else s.gettingReady, Icons.Filled.CameraAlt, onClick = { launchCamera() }, enabled = ready)
            BigOutlinedButton(if (ready) s.chooseGallery else s.gettingReady, Icons.Filled.PhotoLibrary, enabled = ready, onClick = {
                pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            })
            error?.let { Text(it, color = SevSevere) }
        }
    }
}

/** "Which crop is this?" — the model then only chooses among that crop's diseases. Remembered for next time. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CropPicker(vm: MainViewModel) {
    val s = LocalStrings.current
    val lang by vm.lang.collectAsStateWithLifecycle()
    val chosen by vm.crop.collectAsStateWithLifecycle()
    SectionCard {
        Text(s.whichCrop, style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            vm.assets.crops.forEach { crop ->
                FilterChip(selected = chosen == crop, onClick = { vm.setCrop(crop) },
                    label = { Text("${cropEmoji(crop)} ${cropName(crop, lang)}", style = MaterialTheme.typography.bodyLarge) })
            }
            FilterChip(selected = chosen == null, onClick = { vm.setCrop(null) },
                label = { Text("❓ ${s.cropNotSure}", style = MaterialTheme.typography.bodyLarge) })
        }
        Text(s.cropHint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Tip(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = Leaf, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

/** A leaf on white paper inside the dashed square the app will crop to. */
@Composable
private fun GuideIllustration(modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        drawRect(Color(0xFFDCE5D6))                                                     // ground
        drawRoundRect(Color.White, topLeft = Offset(w * 0.08f, h * 0.08f), size = Size(w * 0.84f, h * 0.84f),
            cornerRadius = CornerRadius(18f, 18f))                                      // paper
        val leaf = Path().apply {
            moveTo(w * 0.5f, h * 0.18f)
            cubicTo(w * 0.80f, h * 0.28f, w * 0.80f, h * 0.70f, w * 0.5f, h * 0.84f)
            cubicTo(w * 0.20f, h * 0.70f, w * 0.20f, h * 0.28f, w * 0.5f, h * 0.18f)
            close()
        }
        drawPath(leaf, Color(0xFF43A047))
        drawLine(Color(0xFF1B5E20), Offset(w * 0.5f, h * 0.22f), Offset(w * 0.5f, h * 0.82f), strokeWidth = 5f)
        drawCircle(Color(0xFF8D6E63), radius = w * 0.035f, center = Offset(w * 0.42f, h * 0.45f))     // a lesion
        drawCircle(Color(0xFF8D6E63), radius = w * 0.025f, center = Offset(w * 0.58f, h * 0.62f))
        drawRoundRect(Leaf, topLeft = Offset(w * 0.04f, h * 0.04f), size = Size(w * 0.92f, h * 0.92f),
            cornerRadius = CornerRadius(24f, 24f),
            style = Stroke(width = 8f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(30f, 18f))))
    }
}
