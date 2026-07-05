package app.getknit.knit.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.getknit.knit.ui.preview.KnitPreview
import kotlin.math.abs

/**
 * Knit's "now typing" flair: three little **knit-stitch** chevrons ("V"s, the shape of a stitch on the
 * needle) with a highlight that sweeps left → right and lifts each stitch as it passes — like a needle
 * laying a fresh row. Purely decorative and self-animating via [rememberInfiniteTransition]; it draws
 * itself in [color] (the brand primary by default) and is theme-agnostic (the caller picks the color).
 *
 * Used inside a received-style bubble in the chat typing indicator; see `TypingIndicatorRow` in
 * `ChatScreen`.
 */
@Composable
fun KnitStitchIndicator(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val transition = rememberInfiniteTransition(label = "knitStitch")
    // Sweeps 0 → STITCHES; the extra tail past the last stitch is a brief all-dim pause before it loops.
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = STITCHES + PAUSE,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = SWEEP_MS, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
        label = "sweep",
    )
    Canvas(modifier = modifier.size(width = 34.dp, height = 14.dp)) {
        val slot = size.width / STITCHES
        val stitchHalfWidth = slot * 0.35f
        val stitchHalfHeight = 4.dp.toPx()
        val stroke = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val cy = size.height / 2f
        repeat(STITCHES) { i ->
            // Brightest as the sweep passes this stitch's center (i + 0.5), fading with distance.
            val lit = (1f - abs(phase - (i + 0.5f))).coerceIn(0f, 1f)
            val alpha = DIM_ALPHA + (1f - DIM_ALPHA) * lit
            val rise = -2.dp.toPx() * lit // a little lift as the "needle" crosses it
            val cx = slot * i + slot / 2f
            val path =
                Path().apply {
                    moveTo(cx - stitchHalfWidth, cy - stitchHalfHeight + rise)
                    lineTo(cx, cy + stitchHalfHeight + rise)
                    lineTo(cx + stitchHalfWidth, cy - stitchHalfHeight + rise)
                }
            drawPath(path, color = color.copy(alpha = alpha), style = stroke)
        }
    }
}

private const val STITCHES = 3
private const val PAUSE = 0.5f // tail of the sweep with every stitch dim, before it restarts
private const val SWEEP_MS = 1200
private const val DIM_ALPHA = 0.35f

// A static preview renders the sweep at phase 0 (first stitch lit); run interactive mode for the animation.
@Preview(showBackground = true)
@Composable
fun KnitStitchIndicatorPreview() =
    KnitPreview {
        KnitStitchIndicator(modifier = Modifier.padding(8.dp))
    }
