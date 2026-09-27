package app.getknit.knit.wear.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.circle
import androidx.graphics.shapes.star
import androidx.graphics.shapes.toPath
import androidx.wear.compose.foundation.LocalReduceMotion
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import app.getknit.knit.wear.LinkStyle
import app.getknit.knit.wear.PeerLinks
import app.getknit.knit.wear.Tone
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The status screen's hero, drawn soft: this phone as a slowly turning Material 3 Expressive "cookie" in the
 * state's colour, with [center] (the nearby count, or the state's glyph at rest) inside, on a round tonal
 * backdrop; each peer the phone reaches a bubble — short-range ones close in and filled, long-range ones (LoRa,
 * the internet relay) further out and hollow, because they are not in range — joined to it by a curved line in
 * its plane's [LinkStyle]: a fine line for Bluetooth, a wide ribbon for Wi-Fi Aware, a wave for LoRa, dots for the
 * relay.
 *
 * [links] are the phone's masks in its own stable order, and each peer's small offset from a perfect circle is
 * a function of its slot, so the map scatters naturally but a peer keeps its place between readings. Bubbles
 * spring out of the middle when the set changes and drift a little while shown; alone, the middle softens into
 * a circle and back, calling out; while [reading], the cookie turns faster. With the system's reduce-motion on,
 * none of it moves.
 */
@Composable
fun PeerMap(
    links: List<Int>,
    tone: Tone,
    alone: Boolean,
    reading: Boolean,
    modifier: Modifier = Modifier,
    diameter: Dp = 150.dp,
    center: @Composable BoxScope.() -> Unit = {},
) {
    val still = LocalReduceMotion.current
    val accent by animateColorAsState(tone.color(), label = "accent")
    val backdrop = MaterialTheme.colorScheme.surfaceContainer
    val grow = remember { Animatable(0f) }
    LaunchedEffect(links) {
        grow.snapTo(0f)
        grow.animateTo(1f, spring(dampingRatio = GROW_DAMPING, stiffness = GROW_STIFFNESS))
    }
    val transition = rememberInfiniteTransition(label = "map")
    val turn by transition.animateFloat(
        initialValue = 0f,
        targetValue = if (still) 0f else FULL_TURN,
        animationSpec = infiniteRepeatable(tween(if (reading) TURN_FAST_MS else TURN_MS, easing = LinearEasing)),
        label = "turn",
    )
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = if (still) 0f else (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(DRIFT_MS, easing = LinearEasing)),
        label = "drift",
    )
    val soften by transition.animateFloat(
        initialValue = 0f,
        targetValue = if (alone && !still) 1f else 0f,
        animationSpec = infiniteRepeatable(tween(SOFTEN_MS, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "soften",
    )
    val shapes = remember { Morph(COOKIE, CIRCLE) }
    val near = links.filter(PeerLinks::near)
    val far = links.filterNot(PeerLinks::near)

    Box(modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val mid = Offset(size.width / 2, size.height / 2)
            val edge = size.minDimension / 2
            val hub = edge * HUB
            drawCircle(
                Brush.radialGradient(
                    0f to accent.copy(alpha = HALO_ALPHA),
                    HALO_STOP to backdrop.copy(alpha = BACKDROP_ALPHA),
                    1f to backdrop.copy(alpha = 0f),
                    center = mid,
                    radius = edge,
                ),
                edge,
                mid,
            )
            val peers = place(near, edge * NEAR_RING, 0.0) + place(far, edge * FAR_RING, farOffset(near.size, far.size))
            for ((i, peer) in peers.withIndex()) {
                val style = PeerLinks.style(peer.mask) ?: continue
                val bob = if (still) Offset.Zero else Offset(cos(drift + i), sin(drift * 2 + i)) * BOB.toPx()
                val at = mid + (peer.at - mid) * grow.value + bob
                val r = (if (peer.near) NEAR_BUBBLE else FAR_BUBBLE).toPx() * peer.scale
                val bend = if (i % 2 == 0) BEND else -BEND
                drawLink(style, mid, at, bend, accent.copy(alpha = LINK_ALPHA), hub = hub, end = r)
                drawCircle(accent.copy(alpha = BUBBLE_HALO_ALPHA * grow.value), r + HALO.toPx(), at)
                if (peer.near) {
                    drawCircle(accent.copy(alpha = grow.value), r, at)
                } else {
                    drawCircle(backdrop, r, at)
                    drawCircle(accent.copy(alpha = grow.value), r, at, style = Stroke(RING.toPx()))
                }
            }
            val cookie = shapes.toPath(soften).asComposePath()
            translate(mid.x, mid.y) {
                rotate(turn, pivot = Offset.Zero) {
                    scale(hub, hub, pivot = Offset.Zero) { drawPath(cookie, accent) }
                }
            }
        }
        center()
    }
}

/** The styles on the map, each with a sample of its line — only the ones drawn. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LinkLegend(
    styles: List<LinkStyle>,
    tone: Tone,
    modifier: Modifier = Modifier,
) {
    val accent = tone.color()
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
    ) {
        for (style in styles) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(width = 18.dp, height = 10.dp)) {
                    drawLink(style, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 0f, accent.copy(alpha = LINK_ALPHA))
                }
                Spacer(Modifier.width(4.dp))
                Text(style.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A peer's resting place: [at] on the map, whether it is [near], and its bubble's [scale]. */
private class Placed(
    val at: Offset,
    val near: Boolean,
    val mask: Int,
    val scale: Float,
)

/**
 * [masks] around a ring of [radius], from the top, turned by [offset] radians — each nudged off the perfect
 * circle by an amount fixed by its slot, so the layout looks scattered but is the same every reading.
 */
private fun DrawScope.place(
    masks: List<Int>,
    radius: Float,
    offset: Double,
): List<Placed> {
    val mid = Offset(size.width / 2, size.height / 2)
    return masks.mapIndexed { i, mask ->
        val wobble = WOBBLE[i % WOBBLE.size]
        val angle = -PI / 2 + offset + 2 * PI * i / masks.size + wobble * ANGLE_JITTER
        val r = radius * (1 + wobble * RADIUS_JITTER)
        Placed(
            at = mid + Offset(cos(angle).toFloat(), sin(angle).toFloat()) * r,
            near = PeerLinks.near(mask),
            mask = mask,
            scale = 1 + wobble * SIZE_JITTER,
        )
    }
}

/**
 * The turn for the far ring that keeps its spokes clear of the near ones: of [SEARCH_STEPS] candidate turns,
 * the one whose closest far-to-near angle is widest. Pure in the two counts, so it never moves between readings.
 */
private fun farOffset(
    near: Int,
    far: Int,
): Double {
    if (near == 0 || far == 0) return 0.0
    val nearAngles = List(near) { 2 * PI * it / near }
    return (0 until SEARCH_STEPS).map { 2 * PI * it / SEARCH_STEPS / far }.maxBy { offset ->
        List(far) { offset + 2 * PI * it / far }.minOf { f ->
            nearAngles.minOf { n ->
                val d = abs(f - n) % (2 * PI)
                minOf(d, 2 * PI - d)
            }
        }
    }
}

/**
 * One line in [style]'s pattern from [from] to [to], bowed sideways by [bend] (a fraction of its length) so a
 * map of spokes reads as a web; it starts [hub] out of [from] and stops [end] short of [to], at the shapes' rims.
 * Shared by the map and its legend.
 */
private fun DrawScope.drawLink(
    style: LinkStyle,
    from: Offset,
    to: Offset,
    bend: Float,
    color: Color,
    hub: Float = 0f,
    end: Float = 0f,
) {
    val len = hypot(to.x - from.x, to.y - from.y)
    if (len <= hub + end + 1f) return
    val dir = Offset((to.x - from.x) / len, (to.y - from.y) / len)
    val normal = Offset(-dir.y, dir.x)
    val a = from + dir * hub
    val b = to - dir * end
    val control = (a + b) / 2f + normal * (len * bend)
    // Walk the quadratic Bézier; LoRa's wave rides its normal.
    val steps = (len / STEP_PX).toInt().coerceAtLeast(2)
    val waveAmp = WAVE_AMP.toPx()
    val wavelength = WAVE_LENGTH.toPx()
    val path = Path()
    for (k in 0..steps) {
        val t = k / steps.toFloat()
        val u = 1 - t
        var p = a * (u * u) + control * (2 * u * t) + b * (t * t)
        if (style == LinkStyle.LoRa) {
            val tangent = (control - a) * (2 * u) + (b - control) * (2 * t)
            val tl = hypot(tangent.x, tangent.y).coerceAtLeast(MIN_TANGENT)
            p += Offset(-tangent.y / tl, tangent.x / tl) * (waveAmp * sin(2 * PI * t * len / wavelength).toFloat())
        }
        if (k == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
    }
    val stroke =
        when (style) {
            LinkStyle.Bluetooth, LinkStyle.LoRa -> {
                Stroke(FINE.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            }

            LinkStyle.Aware -> {
                Stroke(RIBBON.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            }

            LinkStyle.Relay -> {
                Stroke(DOT.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(DOT_DASH, DOT_GAP.toPx())))
            }
        }
    drawPath(path, if (style == LinkStyle.Aware) color.copy(alpha = color.alpha * RIBBON_ALPHA) else color, style = stroke)
}

/** The M3 Expressive "cookie": nine soft scallops. Its partner is a plain circle, for the alone breath. */
private val COOKIE =
    RoundedPolygon.star(
        numVerticesPerRadius = 9,
        radius = 1f,
        innerRadius = 0.8f,
        rounding = CornerRounding(radius = 0.32f),
        innerRounding = CornerRounding(radius = 0.32f),
    )
private val CIRCLE = RoundedPolygon.circle(numVertices = 9, radius = 0.92f)

/** Fixed per-slot nudges in −1..1, so the scatter is organic but the same every reading. */
private val WOBBLE = floatArrayOf(0.35f, -0.6f, 0.8f, -0.2f, -0.85f, 0.5f, 0.1f, -0.45f)

private const val HUB = 0.26f
private const val NEAR_RING = 0.58f
private const val FAR_RING = 0.86f
private const val ANGLE_JITTER = 0.16
private const val RADIUS_JITTER = 0.07f
private const val SIZE_JITTER = 0.14f
private const val BEND = 0.12f
private const val HALO_STOP = 0.55f
private const val HALO_ALPHA = 0.16f
private const val BACKDROP_ALPHA = 0.9f
private const val LINK_ALPHA = 0.7f
private const val RIBBON_ALPHA = 0.55f
private const val BUBBLE_HALO_ALPHA = 0.22f
private const val FULL_TURN = 360f
private const val TURN_MS = 30_000
private const val TURN_FAST_MS = 4_000
private const val DRIFT_MS = 9_000
private const val SOFTEN_MS = 1_800
private const val GROW_DAMPING = 0.6f
private const val GROW_STIFFNESS = 180f
private const val STEP_PX = 1.5f
private const val SEARCH_STEPS = 36
private const val MIN_TANGENT = 1e-3f

/** A near-zero dash: with a round cap, each one draws as a dot. */
private const val DOT_DASH = 0.01f

private val NEAR_BUBBLE = 7.dp
private val FAR_BUBBLE = 5.5.dp
private val HALO = 3.dp
private val RING = 1.8.dp
private val BOB = 1.5.dp
private val FINE = 1.8.dp
private val RIBBON = 4.5.dp
private val WAVE_AMP = 2.2.dp
private val WAVE_LENGTH = 10.dp
private val DOT = 2.6.dp
private val DOT_GAP = 5.dp
