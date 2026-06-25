package app.getknit.knit.ui.profile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.getknit.knit.R
import kotlin.math.max
import kotlin.math.min

private const val CIRCLE_FRACTION = 0.8f
private const val MAX_SCALE = 5f

/**
 * Full-screen avatar crop overlay: the picked image pans and pinch-zooms under a fixed, centered
 * circular window. [onConfirm] reports the final `scale`, `offset`, and crop-window `diameter` (px)
 * so the caller can compute the source crop with `computeAvatarCrop`. Mirrors the gesture/`Dialog`
 * recipe from chat's `FullscreenImageViewer`.
 */
@Composable
fun AvatarCropDialog(
    bitmap: ImageBitmap,
    onCancel: () -> Unit,
    onConfirm: (scale: Float, offset: Offset, diameter: Float) -> Unit,
) {
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        var viewport by remember { mutableStateOf(IntSize.Zero) }

        val srcW = bitmap.width
        val srcH = bitmap.height
        val measured = viewport != IntSize.Zero
        val diameter = if (measured) CIRCLE_FRACTION * min(viewport.width, viewport.height) else 0f
        val baseScale = if (diameter == 0f) 0f else diameter / min(srcW, srcH)

        val transformState = rememberTransformableState { zoomChange, panChange, _ ->
            scale = (scale * zoomChange).coerceIn(1f, MAX_SCALE)
            // Clamp the pan so the image edges can never pull inside the crop circle.
            val s = baseScale * scale
            val maxX = max(0f, (s * srcW - diameter) / 2f)
            val maxY = max(0f, (s * srcH - diameter) / 2f)
            offset = Offset(
                (offset.x + panChange.x).coerceIn(-maxX, maxX),
                (offset.y + panChange.y).coerceIn(-maxY, maxY),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.96f))
                .onSizeChanged { viewport = it },
            contentAlignment = Alignment.Center,
        ) {
            if (measured) {
                // ContentScale.Fit draws the bitmap at fitScale; graphicsLayer then multiplies by g
                // (about the same center), so total scale == baseScale * scale, matching the crop math.
                val fitScale = min(viewport.width.toFloat() / srcW, viewport.height.toFloat() / srcH)
                val g = baseScale * scale / fitScale
                Image(
                    bitmap = bitmap,
                    contentDescription = stringResource(R.string.profile_avatar_crop_desc),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .transformable(transformState)
                        .graphicsLayer(
                            scaleX = g,
                            scaleY = g,
                            translationX = offset.x,
                            translationY = offset.y,
                        ),
                )
            }

            CircleMaskOverlay(diameter = diameter, modifier = Modifier.fillMaxSize())

            Text(
                text = stringResource(R.string.profile_avatar_crop_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(24.dp),
            )

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(24.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.action_cancel), color = Color.White)
                }
                TextButton(
                    enabled = measured,
                    onClick = { onConfirm(scale, offset, diameter) },
                ) {
                    Text(stringResource(R.string.action_save), color = Color.White)
                }
            }
        }
    }
}

/** Dims everything outside a centered circle of [diameter] px and draws a thin guide ring. */
@Composable
private fun CircleMaskOverlay(diameter: Float, modifier: Modifier = Modifier) {
    val scrim = Color.Black.copy(alpha = 0.5f)
    Canvas(modifier = modifier.graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
        val radius = diameter / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        drawRect(color = scrim)
        if (radius > 0f) {
            // BlendMode.Clear needs the offscreen layer above so it clears to transparent, not black.
            drawCircle(color = Color.Transparent, radius = radius, center = center, blendMode = BlendMode.Clear)
            drawCircle(color = Color.White, radius = radius, center = center, style = Stroke(width = 2.dp.toPx()))
        }
    }
}
