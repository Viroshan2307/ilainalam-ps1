package com.ilainalam.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.ilainalam.i18n.LocalStrings
import com.ilainalam.ui.MainViewModel
import com.ilainalam.ui.components.AppBar
import com.ilainalam.ui.components.BigButton
import com.ilainalam.ui.components.BigOutlinedButton
import com.ilainalam.ui.components.SectionCard
import com.ilainalam.ui.theme.Leaf
import com.ilainalam.ui.theme.SevModerate
import com.ilainalam.util.FrameHint
import com.ilainalam.util.FrameCheck
import java.io.File
import java.util.concurrent.Executors

/**
 * Live camera with a guide frame (CameraX). The preview is 3:4 — the whole photo — and the square frame is exactly
 * the centre square the app analyses. A light check on each preview frame (brightness, sharpness, green in the
 * frame) shows a hint; it never blocks the button. No AI runs here — the photo goes through the normal scan.
 */
@Composable
fun CameraScreen(vm: MainViewModel, onBack: () -> Unit, onPhoto: () -> Unit, onUsePhoneCamera: () -> Unit) {
    val s = LocalStrings.current
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var asked by remember { mutableStateOf(false) }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> granted = ok; asked = true }
    LaunchedEffect(Unit) { if (!granted) askPermission.launch(Manifest.permission.CAMERA) }

    Scaffold(topBar = { AppBar(s.liveCamera, onBack) }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (!granted) {
                SectionCard {
                    Text(s.cameraPermission, style = MaterialTheme.typography.bodyLarge)
                    if (asked) BigButton(s.liveCamera, Icons.Filled.CameraAlt, onClick = { askPermission.launch(Manifest.permission.CAMERA) })
                }
                BigOutlinedButton(s.usePhoneCamera, Icons.Filled.CameraAlt, onClick = onUsePhoneCamera)
                return@Column
            }
            LiveGuide(vm, onPhoto, onUsePhoneCamera)
        }
    }
}

@Composable
private fun LiveGuide(vm: MainViewModel, onPhoto: () -> Unit, onUsePhoneCamera: () -> Unit) {
    val s = LocalStrings.current
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var hint by remember { mutableStateOf(FrameHint.PLACE_LEAF) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val analysisExec = remember { Executors.newSingleThreadExecutor() }
    val check = remember { FrameCheck() }

    val controller = remember {
        LifecycleCameraController(ctx).apply {
            setEnabledUseCases(CameraController.IMAGE_CAPTURE or CameraController.IMAGE_ANALYSIS)
            imageCaptureMode = ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
            imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
        }
    }
    DisposableEffect(owner) {
        controller.setImageAnalysisAnalyzer(analysisExec) { img: ImageProxy ->
            try { val h = check.hint(img); ContextCompat.getMainExecutor(ctx).execute { hint = h } } finally { img.close() }
        }
        controller.bindToLifecycle(owner)
        onDispose { controller.clearImageAnalysisAnalyzer(); controller.unbind(); analysisExec.shutdown() }
    }

    Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(RoundedCornerShape(18.dp)).background(Color.Black)) {
        AndroidView(factory = { c ->
            PreviewView(c).apply { scaleType = PreviewView.ScaleType.FIT_CENTER; this.controller = controller }
        }, modifier = Modifier.fillMaxSize())
        val tint = if (hint == FrameHint.GOOD) Leaf else SevModerate
        Canvas(Modifier.fillMaxSize()) {                                   // the square the app analyses
            val side = size.width * 0.96f
            val left = (size.width - side) / 2; val top = (size.height - side) / 2
            drawRoundRect(tint, topLeft = Offset(left, top), size = Size(side, side), cornerRadius = CornerRadius(24f, 24f),
                style = Stroke(width = 8f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(30f, 18f))))
        }
        Text(
            when (hint) {
                FrameHint.PLACE_LEAF -> s.camPlaceLeaf
                FrameHint.DARK -> s.issueDark
                FrameHint.BRIGHT -> s.issueBright
                FrameHint.BLURRY -> s.camHoldSteady
                FrameHint.GOOD -> s.camGood
            },
            color = Color.White, style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }

    BigButton(if (busy) s.gettingReady else s.capture, Icons.Filled.CameraAlt, enabled = !busy, onClick = {
        busy = true; error = null
        val dir = File(ctx.cacheDir, "captures").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "live_${System.currentTimeMillis()}.jpg")
        controller.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), ContextCompat.getMainExecutor(ctx),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                    val uri: Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
                    vm.analyze(uri); onPhoto()
                }
                override fun onError(e: ImageCaptureException) { busy = false; error = e.message ?: s.noCamera }
            })
    })
    BigOutlinedButton(s.usePhoneCamera, Icons.Filled.CameraAlt, onClick = onUsePhoneCamera)
    error?.let { Text(it, color = SevModerate) }
}
