package app.getknit.knit.ui.camera

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.getknit.knit.R
import android.view.Surface as AndroidSurface
import androidx.camera.core.Preview as CameraXPreview

/**
 * Full-screen photo capture, rendered *in place of* the calling chat's content (see
 * [app.getknit.knit.ui.chat.ChatScreen]) — the same in-place-composable shape as
 * [app.getknit.knit.ui.scan.QrScanner], and for the reasons recorded in ADR 015. Hardware probe,
 * `CAMERA` permission and the non-camera states come from [CameraGate].
 *
 * [onCaptured] fires at most once per shot, on the main thread, with the **JPEG bytes**. They are
 * deliberately never written to disk: `AttachmentStore` keeps attachment bytes in the encrypted blob
 * store only, and staging a plaintext photo in `cacheDir` would break that for the window between
 * shutter and ingest (and leave one behind if the process died mid-flight).
 */
@Composable
fun PhotoCapture(
    onCaptured: (ByteArray) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CameraGate(
        noCameraMessage = stringResource(R.string.camera_no_camera),
        deniedMessage = stringResource(R.string.camera_permission_denied),
        onCancel = onCancel,
        modifier = modifier.testTag("screen_camera"),
    ) {
        CaptureFeed(onCaptured = onCaptured, onCancel = onCancel)
    }
}

/** Live preview with an [ImageCapture] bound alongside it for as long as this composable is in the tree. */
@Composable
private fun CaptureFeed(
    onCaptured: (ByteArray) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // rememberUpdatedState so the capture callback — captured once, when the shutter fires — always
    // calls the current lambda rather than a stale one from an earlier recomposition.
    val currentCaptured by rememberUpdatedState(onCaptured)
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val imageCapture =
        remember {
            ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
        }
    // Survives rotation so a half-composed selfie doesn't flip back to the rear camera.
    var lensFacing by rememberSaveable { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var canSwitchLens by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    var shooting by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner, lensFacing) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        // The future can resolve *after* this effect is disposed (leaving the screen while the provider
        // is still starting). Binding then would leave the camera running with nothing on screen and no
        // one left to unbind it — onDispose has already seen a null provider. Both run on the main
        // executor, so this flag is enough.
        var disposed = false

        future.addListener({
            if (disposed) return@addListener
            runCatching {
                val resolved = future.get()
                provider = resolved
                val preview =
                    CameraXPreview.Builder().build().apply { surfaceProvider = previewView.surfaceProvider }
                // FEATURE_CAMERA_ANY only promises *a* camera, so the requested facing may not exist —
                // a front-only device would otherwise dead-end on the error screen. Fall back to the
                // other lens, and only offer the toggle when there really are two.
                val wanted = CameraSelector.Builder().requireLensFacing(lensFacing).build()
                val selector = if (resolved.hasCamera(wanted)) wanted else otherLens(lensFacing)
                canSwitchLens =
                    resolved.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) &&
                    resolved.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                resolved.unbindAll()
                resolved.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
            }.onFailure { error = R.string.camera_unavailable }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            disposed = true
            runCatching { provider?.unbindAll() }
        }
    }

    error?.let {
        CameraMessage(stringResource(it), onCancel)
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        CaptureControls(
            shooting = shooting,
            canSwitchLens = canSwitchLens,
            onCancel = onCancel,
            onSwitchLens = {
                lensFacing =
                    if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                        CameraSelector.LENS_FACING_FRONT
                    } else {
                        CameraSelector.LENS_FACING_BACK
                    }
            },
            onShutter = {
                if (!shooting) {
                    shooting = true
                    // ImageCapture records orientation as EXIF rather than rotating pixels, and that EXIF
                    // is what AttachmentStore's decode reads — so stamp the rotation the device has *now*,
                    // not whatever it had when the use case was bound.
                    imageCapture.targetRotation = previewView.display?.rotation ?: AndroidSurface.ROTATION_0
                    imageCapture.takePicture(
                        ContextCompat.getMainExecutor(context),
                        object : ImageCapture.OnImageCapturedCallback() {
                            override fun onCaptureSuccess(image: ImageProxy) {
                                val jpeg = jpegBytes(image)
                                shooting = false
                                if (jpeg != null) currentCaptured(jpeg) else error = R.string.camera_capture_failed
                            }

                            override fun onError(exception: ImageCaptureException) {
                                shooting = false
                                error = R.string.camera_capture_failed
                            }
                        },
                    )
                }
            },
        )
    }
}

/** Cancel / shutter / lens-toggle, overlaid on the preview. All three meet the 48dp touch target. */
@Composable
private fun BoxScope.CaptureControls(
    shooting: Boolean,
    canSwitchLens: Boolean,
    onCancel: () -> Unit,
    onSwitchLens: () -> Unit,
    onShutter: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(24.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalIconButton(
            onClick = onCancel,
            modifier = Modifier.size(48.dp).testTag("camera_cancel"),
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(R.string.action_cancel),
                modifier = Modifier.size(24.dp),
            )
        }
        FilledIconButton(
            onClick = onShutter,
            modifier = Modifier.size(72.dp).testTag("camera_shutter"),
        ) {
            if (shooting) {
                val label = stringResource(R.string.camera_capturing)
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp).semantics { contentDescription = label },
                    strokeWidth = 3.dp,
                    color = LocalContentColor.current,
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.PhotoCamera,
                    contentDescription = stringResource(R.string.action_take_photo),
                    modifier = Modifier.size(32.dp),
                )
            }
        }
        if (canSwitchLens) {
            FilledTonalIconButton(
                onClick = onSwitchLens,
                modifier = Modifier.size(48.dp).testTag("camera_switch_lens"),
            ) {
                Icon(
                    imageVector = Icons.Filled.Cameraswitch,
                    contentDescription = stringResource(R.string.camera_switch_lens),
                    modifier = Modifier.size(24.dp),
                )
            }
        } else {
            // Keep the shutter centred on a one-lens device.
            Spacer(Modifier.size(48.dp))
        }
    }
}

/** The selector for the lens [lensFacing] is not — used when the requested facing doesn't exist. */
private fun otherLens(lensFacing: Int): CameraSelector =
    if (lensFacing == CameraSelector.LENS_FACING_BACK) {
        CameraSelector.DEFAULT_FRONT_CAMERA
    } else {
        CameraSelector.DEFAULT_BACK_CAMERA
    }

/**
 * Copies the JPEG out of a captured frame. Always closes the [ImageProxy] — a leaked frame stalls the
 * capture pipeline — and never throws: this runs on the main thread from a CameraX callback.
 */
private fun jpegBytes(image: ImageProxy): ByteArray? =
    image.use {
        runCatching {
            val buffer = it.planes.firstOrNull()?.buffer ?: return@runCatching null
            ByteArray(buffer.remaining()).also { bytes -> buffer.get(bytes) }
        }.getOrNull()
    }
