package app.getknit.knit.ui.scan

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.getknit.knit.R
import app.getknit.knit.ui.camera.CameraGate
import app.getknit.knit.ui.camera.CameraMessage
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import androidx.camera.core.Preview as CameraXPreview

/**
 * Full-screen identity-QR scanner, rendered *in place of* the calling screen's content (see
 * [app.getknit.knit.ui.verify.VerifyContactScreen] and [app.getknit.knit.ui.profile.ProfileDetailsScreen]).
 * It is a plain composable rather than an Activity or a `Dialog`: screens in this app take lambdas and
 * `KnitApp` owns navigation, and a camera `SurfaceView` inside a `Dialog` window has z-ordering quirks on
 * exactly the kind of hardware this rewrite exists to support.
 *
 * The hardware probe, the `CAMERA` permission launcher and the non-camera states live in
 * [CameraGate], shared with [app.getknit.knit.ui.camera.PhotoCapture]. Decoding is [QrDecoder], which
 * is Android-free and cannot throw. See ADR 015 for why this replaced zxing-android-embedded.
 *
 * [onResult] fires at most once, on the main thread, with the decoded text.
 */
@Composable
fun QrScanner(
    onResult: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CameraGate(
        noCameraMessage = stringResource(R.string.scan_no_camera),
        deniedMessage = stringResource(R.string.scan_permission_denied),
        onCancel = onCancel,
        modifier = modifier.testTag("screen_scan"),
    ) {
        CameraFeed(onResult = onResult, onCancel = onCancel)
    }
}

/** Live camera preview with the QR analyzer bound to it for as long as this composable is in the tree. */
@Composable
private fun CameraFeed(
    onResult: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // rememberUpdatedState so the analyzer — captured once, when the effect runs — always calls the
    // current lambda rather than a stale one from an earlier recomposition.
    val currentResult by rememberUpdatedState(onResult)
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    var failed by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val analysisExecutor = Executors.newSingleThreadExecutor()
        val delivered = AtomicBoolean(false)
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        // The future can resolve *after* this effect is disposed, in which case onDispose has already
        // seen a null provider and binding now would leave the camera running unattended.
        var disposed = false

        future.addListener({
            if (disposed) return@addListener
            runCatching {
                val resolved = future.get()
                provider = resolved
                val preview =
                    CameraXPreview.Builder().build().apply { surfaceProvider = previewView.surfaceProvider }
                val analysis =
                    qrAnalysis(analysisExecutor, delivered) { text -> previewView.post { currentResult(text) } }
                resolved.unbindAll()
                resolved.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }.onFailure { failed = true }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            disposed = true
            runCatching { provider?.unbindAll() }
            analysisExecutor.shutdown()
        }
    }

    if (failed) {
        CameraMessage(stringResource(R.string.camera_unavailable), onCancel)
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        Column(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.scan_hint),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    }
}

/**
 * The analysis use case. [delivered] latches on the first frame carrying a code — the analyzer keeps
 * running until CameraX tears it down, so without it a lingering frame could fire [onText] twice.
 */
private fun qrAnalysis(
    executor: Executor,
    delivered: AtomicBoolean,
    onText: (String) -> Unit,
): ImageAnalysis =
    ImageAnalysis
        .Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .build()
        .apply {
            setAnalyzer(executor) { image ->
                scan(image)?.let { text ->
                    if (delivered.compareAndSet(false, true)) onText(text)
                }
            }
        }

/**
 * Pulls the luminance plane off one CameraX frame and hands it to [QrDecoder]. Always closes the
 * [ImageProxy] — a leaked frame stalls the analyzer for good — and never lets a throwable escape: this
 * runs on CameraX's analysis executor, where an escape would kill the process (which is exactly how the
 * zxing-android-embedded scanner this replaced failed).
 *
 * `internal` rather than private so `QrScanInstrumentedTest` can hand it a real camera's frames.
 */
internal fun scan(image: ImageProxy): String? =
    image.use {
        runCatching {
            val plane = it.planes.firstOrNull() ?: return@runCatching null
            val buffer = plane.buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            QrDecoder.decode(bytes, plane.rowStride, plane.pixelStride, it.width, it.height)
        }.getOrNull()
    }
