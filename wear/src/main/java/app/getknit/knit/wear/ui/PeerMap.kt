package app.getknit.knit.wear.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import app.getknit.knit.wear.LinkStyle
import app.getknit.knit.wear.PeerLinks
import app.getknit.knit.wear.Tone
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The status screen's hero: this phone in the middle and a node per peer the phone reaches — short-range peers
 * on the inner orbit, long-range ones (LoRa, the internet relay) on the outer, hollow because they are not in
 * range — each joined to the middle by a line drawn in its plane's [LinkStyle]: a solid line for Bluetooth, a
 * double one for Wi-Fi Aware, a wave for LoRa, dots for the relay. [links] are the phone's masks in its own
 * stable order, so a peer keeps its place between readings; the lines grow out of the middle when the set
 * changes. Alone, the middle node calls out in slow ripples; while [reading], the map breathes. [center] is
 * drawn over the middle — the state's glyph when there is nothing to map.
 */
@Composable
fun PeerMap(
    links: List<Int>,
    tone: Tone,
    alone: Boolean,
    reading: Boolean,
    modifier: Modifier = Modifier,
    diameter: Dp = 150.dp,
    center: (@Composable BoxScope.() -> Unit)? = null,
) {
    val nodeColor by animateColorAsState(tone.color(), label = "node")
    val lineColor = MaterialTheme.colorScheme.onSurfaceVariant
    val guide = MaterialTheme.colorScheme.outline.copy(alpha = GUIDE_ALPHA)
    val grow = remember { Animatable(0f) }
    LaunchedEffect(links) {
        grow.snapTo(0f)
        grow.animateTo(1f, tween(GROW_MS))
    }
    val transition = rememberInfiniteTransition(label = "map")
    val ripple by transition.animateFloat(
        initialValue = 0f,
        targetValue = if (alone) 1f else 0f,
        animationSpec = infiniteRepeatable(tween(RIPPLE_MS, easing = LinearEasing)),
        label = "ripple",
    )
    val breath by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (reading) BREATH_LOW else 1f,
        animationSpec = infiniteRepeatable(tween(BREATH_MS), RepeatMode.Reverse),
        label = "breath",
    )
    val near = links.filter(PeerLinks::near)
    val far = links.filterNot(PeerLinks::near)
    Box(modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val mid = Offset(size.width / 2, size.height / 2)
            val nearR = size.minDimension * NEAR_ORBIT
            val farR = size.minDimension * FAR_ORBIT
            val hub = HUB.toPx()
            val node = NODE.toPx()
            drawCircle(guide, nearR, mid, style = Stroke(GUIDE.toPx()))
            if (far.isNotEmpty()) drawCircle(guide, farR, mid, style = Stroke(GUIDE.toPx()))
            if (alone) {
                for (phase in listOf(0f, HALF)) {
                    val p = (ripple + phase) % 1f
                    drawCircle(nodeColor.copy(alpha = RIPPLE_ALPHA * (1 - p)), hub + p * (farR - hub), mid, style = Stroke(GUIDE.toPx()))
                }
            }
            // Far peers sit between the near ones' angles, so no two lines overlap.
            val placed = orbit(near.size, 0.0).zip(near) + orbit(far.size, if (near.isEmpty()) 0.0 else PI / far.size).zip(far)
            for ((angle, mask) in placed) {
                val style = PeerLinks.style(mask) ?: continue
                val radius = if (PeerLinks.near(mask)) nearR else farR
                val dir = Offset(cos(angle).toFloat(), sin(angle).toFloat())
                val start = mid + dir * hub
                val end = mid + dir * (hub + (radius - node - hub) * grow.value)
                val color = lineColor.copy(alpha = (if (PeerLinks.near(mask)) NEAR_LINE_ALPHA else FAR_LINE_ALPHA) * breath)
                drawLink(style, start, end, color)
                val at = mid + dir * radius
                if (PeerLinks.near(mask)) {
                    drawCircle(nodeColor.copy(alpha = breath * grow.value), node, at)
                } else {
                    drawCircle(lineColor.copy(alpha = breath * grow.value), node * FAR_NODE, at, style = Stroke(GUIDE.toPx() * 2))
                }
            }
            if (center == null) {
                drawCircle(nodeColor.copy(alpha = breath), hub, mid)
                drawCircle(Color.Black.copy(alpha = HUB_CORE_ALPHA), hub * HUB_CORE, mid)
            }
        }
        center?.invoke(this)
    }
}

/** The styles on the map, each with a sample of its line — only the ones drawn. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LinkLegend(
    styles: List<LinkStyle>,
    modifier: Modifier = Modifier,
) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
    ) {
        for (style in styles) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(width = 16.dp, height = 8.dp)) {
                    drawLink(style, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), color)
                }
                Spacer(Modifier.width(4.dp))
                Text(style.label, style = MaterialTheme.typography.labelSmall, color = color)
            }
        }
    }
}

/** One line from [start] to [end] in [style]'s pattern — shared by the map and its legend. */
private fun DrawScope.drawLink(
    style: LinkStyle,
    start: Offset,
    end: Offset,
    color: Color,
) {
    val len = hypot(end.x - start.x, end.y - start.y)
    if (len < 1f) return
    val dir = Offset((end.x - start.x) / len, (end.y - start.y) / len)
    val perp = Offset(-dir.y, dir.x)
    when (style) {
        LinkStyle.Bluetooth -> {
            drawLine(color, start, end, SOLID.toPx(), StrokeCap.Round)
        }

        LinkStyle.Aware -> {
            val gap = perp * (PIPE_GAP.toPx() / 2)
            drawLine(color, start + gap, end + gap, PIPE.toPx(), StrokeCap.Round)
            drawLine(color, start - gap, end - gap, PIPE.toPx(), StrokeCap.Round)
        }

        LinkStyle.LoRa -> {
            val amp = WAVE_AMP.toPx()
            val wavelength = WAVE_LENGTH.toPx()
            val path = Path().apply { moveTo(start.x, start.y) }
            var t = 0f
            while (t <= len) {
                val p = start + dir * t + perp * (amp * sin(2 * PI * t / wavelength).toFloat())
                path.lineTo(p.x, p.y)
                t += 1f
            }
            drawPath(path, color, style = Stroke(SOLID.toPx(), cap = StrokeCap.Round))
        }

        LinkStyle.Relay -> {
            drawLine(
                color,
                start,
                end,
                DOT.toPx(),
                StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(DOT_DASH, DOT_GAP.toPx())),
            )
        }
    }
}

/** [n] evenly spaced angles from the top, turned by [offset] radians. */
private fun orbit(
    n: Int,
    offset: Double,
): List<Double> = List(n) { i -> -PI / 2 + offset + 2 * PI * i / n }

private const val NEAR_ORBIT = 0.30f
private const val FAR_ORBIT = 0.45f
private const val FAR_NODE = 0.85f
private const val HALF = 0.5f
private const val GUIDE_ALPHA = 0.18f
private const val RIPPLE_ALPHA = 0.55f
private const val NEAR_LINE_ALPHA = 0.85f
private const val FAR_LINE_ALPHA = 0.6f
private const val HUB_CORE = 0.42f
private const val HUB_CORE_ALPHA = 0.35f
private const val BREATH_LOW = 0.45f
private const val BREATH_MS = 700
private const val RIPPLE_MS = 2_400
private const val GROW_MS = 600

/** A near-zero dash: with a round cap, each one draws as a dot. */
private const val DOT_DASH = 0.01f
private val HUB = 9.dp
private val NODE = 5.5.dp
private val GUIDE = 1.dp
private val SOLID = 1.6.dp
private val PIPE = 1.2.dp
private val PIPE_GAP = 3.2.dp
private val WAVE_AMP = 1.8.dp
private val WAVE_LENGTH = 7.dp
private val DOT = 2.4.dp
private val DOT_GAP = 4.5.dp
