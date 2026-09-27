package app.getknit.knit.wear.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import app.getknit.knit.wear.DayShare
import app.getknit.knit.wear.Tone

/**
 * Peers in range over the last hours, one point per step of [values]; a null step (no reading) breaks the
 * line rather than drawing a guess across it. The scale tops out at the busiest step, never below three, so a
 * day of ones does not look like a crowd.
 */
@Composable
fun NearbySparkline(
    values: List<Int?>,
    modifier: Modifier = Modifier,
) {
    val line = Tone.Good.color()
    val base = MaterialTheme.colorScheme.outline.copy(alpha = BASE_ALPHA)
    Canvas(modifier.fillMaxWidth().height(34.dp)) {
        val top = maxOf(MIN_SCALE, values.maxOf { it ?: 0 }).toFloat()
        val stepX = size.width / (values.size - 1).coerceAtLeast(1)
        val stroke = 2.dp.toPx()
        val usable = size.height - stroke
        drawLine(base, Offset(0f, size.height - stroke / 2), Offset(size.width, size.height - stroke / 2), 1.dp.toPx())
        val runs = mutableListOf<List<Offset>>()
        var run = mutableListOf<Offset>()
        for ((i, v) in values.withIndex()) {
            if (v == null) {
                if (run.isNotEmpty()) runs += run
                run = mutableListOf()
            } else {
                run += Offset(i * stepX, stroke / 2 + usable * (1 - v / top))
            }
        }
        if (run.isNotEmpty()) runs += run
        for (points in runs) {
            if (points.size == 1) {
                drawCircle(line, stroke, points.first())
                continue
            }
            val path = Path().apply { points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } }
            val fill =
                Path().apply {
                    addPath(path)
                    lineTo(points.last().x, size.height)
                    lineTo(points.first().x, size.height)
                    close()
                }
            drawPath(fill, line.copy(alpha = FILL_ALPHA))
            drawPath(path, line, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** The day as one bar: linked, alone, weak and at rest, in proportion, in the same colours as the pie. */
@Composable
fun DayBar(
    share: DayShare,
    modifier: Modifier = Modifier,
) {
    val parts =
        listOf(
            share.linkedMs to Tone.Good.color(),
            share.aloneMs to Tone.Calm.color(),
            share.weakMs to Tone.Warn.color(),
            share.restingMs to Tone.Muted.color(),
        ).filter { it.first > 0 }
    val empty = MaterialTheme.colorScheme.outline.copy(alpha = BASE_ALPHA)
    Canvas(modifier.fillMaxWidth().height(10.dp)) {
        val radius = CornerRadius(size.height / 2)
        val outline = Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, radius)) }
        clipPath(outline) {
            drawRect(empty)
            val total = share.totalMs.toFloat()
            if (total <= 0f) return@clipPath
            var x = 0f
            for ((ms, color) in parts) {
                val w = size.width * ms / total
                drawRect(color, Offset(x, 0f), Size(w, size.height))
                x += w
            }
        }
    }
}

private const val MIN_SCALE = 3
private const val BASE_ALPHA = 0.3f
private const val FILL_ALPHA = 0.18f
