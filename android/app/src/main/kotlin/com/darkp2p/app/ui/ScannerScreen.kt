package com.darkp2p.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.darkp2p.app.QrCodes
import com.darkp2p.app.R
import com.google.zxing.PlanarYUVLuminanceSource
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reads a room link from a QR code. Frames are looked at in memory and thrown
 * away; nothing is saved or sent, and the camera is only on while this screen is.
 */
@Composable
fun ScannerScreen(onResult: (String) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val result by rememberUpdatedState(onResult)
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }

    val executor = remember { Executors.newSingleThreadExecutor() }
    val done = remember { AtomicBoolean(false) }
    DisposableEffect(Unit) {
        onDispose {
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
            executor.shutdown()
        }
    }

    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
        if (!granted) {
            Text(stringResource(R.string.scan_no_permission))
            Button(onClick = { ask.launch(Manifest.permission.CAMERA) }) { Text(stringResource(R.string.scan_grant)) }
            return@Column
        }
        Text(stringResource(R.string.scan_title))
        AndroidView(
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).clipToBounds(),
            factory = { ctx ->
                PreviewView(ctx).also { view ->
                    // The default draws on a separate surface, which is not cut to this
                    // square: the camera picture spilled over the line of text above it.
                    view.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    val future = ProcessCameraProvider.getInstance(ctx)
                    future.addListener({
                        val provider = future.get()
                        val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                        analysis.setAnalyzer(executor) { image ->
                            image.use {
                                if (done.get()) return@use
                                // The first plane is the brightness of each pixel, which is all a QR code needs.
                                val plane = it.planes[0]
                                val bytes = ByteArray(plane.buffer.remaining()).also(plane.buffer::get)
                                val source = PlanarYUVLuminanceSource(bytes, plane.rowStride, it.height, 0, 0, it.width, it.height, false)
                                val text = QrCodes.decode(source)
                                if (text != null && done.compareAndSet(false, true)) ContextCompat.getMainExecutor(ctx).execute { result(text) }
                            }
                        }
                        runCatching {
                            provider.unbindAll()
                            provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                }
            },
        )
    }
}
