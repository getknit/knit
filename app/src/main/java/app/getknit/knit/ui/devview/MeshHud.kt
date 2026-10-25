package app.getknit.knit.ui.devview

import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.getknit.knit.R
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.mesh.TransportKind
import app.getknit.knit.mesh.TransportStatus
import app.getknit.knit.ui.preview.KnitPreview
import app.getknit.knit.ui.theme.KnitMotion
import app.getknit.knit.ui.theme.knitColors
import org.koin.androidx.compose.koinViewModel

// The keys a step quieter than the values. The surfaces themselves are opaque: on a phone, a translucent bubble let the
// text under it run into its number, and a translucent panel into its rows.
private const val KEY_ALPHA = 0.8f
private val BUBBLE_SIZE = 44.dp
private val RING_WIDTH = 3.dp
private val EDGE_MARGIN = 8.dp
private val MAX_PANEL_WIDTH = 360.dp

/**
 * The mesh HUD (ADR 2026-10.s7wg): a small bubble over every screen, its ring saying whether the mesh is alive and its
 * number how many neighbours a short-range radio holds, a tap away from every field behind it. It covers 44 dp of
 * whatever is under it, can be dragged anywhere and settles on the nearer side edge. Composed only while the developer
 * view is on, so its view-model reads nothing otherwise.
 */
@Composable
fun MeshHud(
    onOpenDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MeshHudViewModel = koinViewModel(),
) {
    val reading by viewModel.reading.collectAsStateWithLifecycle()
    MeshHudContent(reading = reading, onOpenDiagnostics = onOpenDiagnostics, modifier = modifier)
}

/**
 * The bubble and, when tapped open, its panel beside it, placed by [HudPlacement] in the area [modifier] gives (the
 * caller passes the screen's safe area). The area takes no touch itself: only the bubble and the panel do. Where the
 * bubble was dropped is kept in memory only. The bubble is one accessibility node, a button that reads the whole line.
 */
@Composable
internal fun MeshHudContent(
    reading: HudReading?,
    onOpenDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
    startExpanded: Boolean = false,
) {
    var expanded by rememberSaveable { mutableStateOf(startExpanded) }
    // Null until the person moves the bubble, so its first place follows the area (a rotation, the keyboard).
    var placed by remember { mutableStateOf<IntOffset?>(null) }
    var dragging by remember { mutableStateOf(false) }
    val line = reading?.let(HudLine::line) ?: HudLine.WAITING
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val density = LocalDensity.current
    // Absolute top-left: the offsets below are absolute, and a start-aligned child would begin at the right edge in a
    // right-to-left locale.
    BoxWithConstraints(modifier.fillMaxSize().testTag("dev_view_hud"), contentAlignment = AbsoluteAlignment.TopLeft) {
        val area = IntSize(constraints.maxWidth, constraints.maxHeight)
        val bubble = with(density) { BUBBLE_SIZE.roundToPx() }
        val margin = with(density) { EDGE_MARGIN.roundToPx() }

        fun current(): IntOffset = HudPlacement.clamp(placed ?: HudPlacement.initial(area, bubble, margin, rtl), area, bubble, margin)
        val at = current()
        // With the finger while dragged, sprung to the edge once dropped.
        val sprung by animateIntOffsetAsState(at, KnitMotion.spatial(), label = "hudBubble")
        val drawn = if (dragging) at else sprung

        if (expanded && reading != null) {
            HudPanelPlacement(bubbleAt = at, bubble = bubble, margin = margin, area = area) {
                HudPanel(reading = reading, line = line, onOpenDiagnostics = onOpenDiagnostics)
            }
        }
        Surface(
            modifier =
                Modifier
                    .absoluteOffset { drawn }
                    .size(BUBBLE_SIZE)
                    .pointerInput(area, bubble, margin, rtl) {
                        detectDragGestures(
                            onDragStart = { dragging = true },
                            onDragEnd = {
                                dragging = false
                                placed = HudPlacement.snap(current(), area, bubble, margin)
                            },
                            onDragCancel = {
                                dragging = false
                                placed = HudPlacement.snap(current(), area, bubble, margin)
                            },
                        ) { change, drag ->
                            change.consume()
                            placed = HudPlacement.clamp(current() + drag.round(), area, bubble, margin)
                        }
                    }.clickable(role = Role.Button) { expanded = !expanded }
                    .semantics { contentDescription = line }
                    .testTag("dev_view_hud_bubble"),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            border = BorderStroke(RING_WIDTH, ringColor(reading?.let(::hudSignal) ?: HudSignal.Off)),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = HudLine.badge(reading),
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = FontFamily.Monospace,
                    // The bubble's description reads the whole line; the number alone would say less.
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
        }
    }
}

/** Places the panel beside the bubble, in the room [HudPlacement.panelRoom] gives it. Takes no touch itself. */
@Composable
private fun HudPanelPlacement(
    bubbleAt: IntOffset,
    bubble: Int,
    margin: Int,
    area: IntSize,
    content: @Composable () -> Unit,
) {
    val maxWidth = with(LocalDensity.current) { MAX_PANEL_WIDTH.roundToPx() }
    Layout(content = content, modifier = Modifier.fillMaxSize()) { measurables, constraints ->
        val panel =
            measurables.single().measure(
                Constraints(
                    maxWidth = minOf(maxWidth, constraints.maxWidth - 2 * margin).coerceAtLeast(0),
                    maxHeight = HudPlacement.panelRoom(bubbleAt, bubble, area, margin).maxHeight,
                ),
            )
        layout(constraints.maxWidth, constraints.maxHeight) {
            val at = HudPlacement.panel(bubbleAt, bubble, IntSize(panel.width, panel.height), area, margin)
            panel.place(at.x, at.y)
        }
    }
}

@Composable
private fun HudPanel(
    reading: HudReading,
    line: String,
    onOpenDiagnostics: () -> Unit,
) {
    Surface(
        modifier = Modifier.testTag("dev_view_hud_panel"),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
    ) {
        // The line and the rows scroll in whatever room the panel was given; the way to Diagnostics stays in view.
        Column(Modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp)) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                Text(text = line, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                InspectGroups(
                    groups = HudLine.panel(reading),
                    modifier = Modifier.padding(top = 6.dp),
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    keyColor = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = KEY_ALPHA),
                    titleColor = MaterialTheme.colorScheme.inversePrimary,
                )
            }
            TextButton(onClick = onOpenDiagnostics) {
                Text(stringResource(R.string.dev_view_open_diagnostics), color = MaterialTheme.colorScheme.inversePrimary)
            }
        }
    }
}

@Composable
private fun ringColor(signal: HudSignal): Color =
    when (signal) {
        // The app's pinned green, never the wallpaper's tertiary, which can land beside error (KnitSemanticColors).
        HudSignal.Linked -> MaterialTheme.knitColors.positive

        HudSignal.Searching -> MaterialTheme.colorScheme.outline

        HudSignal.Down -> MaterialTheme.colorScheme.error

        HudSignal.Off -> MaterialTheme.colorScheme.outlineVariant
    }

private val sampleReading =
    HudReading(
        posture = MeshPosture.Running,
        statuses =
            listOf(
                TransportStatus(TransportKind.Bluetooth, TransportHealth.Healthy, linked = 2, nearby = 5),
                TransportStatus(TransportKind.WifiAware, TransportHealth.Healthy, linked = 0, nearby = 0, dormant = true),
                TransportStatus(TransportKind.LoRa, TransportHealth.Healthy, linked = 1, nearby = 1),
            ),
        neighbors = 5,
        indirect = 2,
        spoolsConnected = 1,
        spools = 2,
        custody = 41,
        rates = HudRates(FrameCounts(delivered = 12, sent = 3, dropped = 0), spanMs = 60_000L),
    )

@Preview(name = "Mesh HUD")
@Composable
fun MeshHudPreview() =
    KnitPreview {
        Box(Modifier.size(360.dp, 200.dp)) {
            MeshHudContent(reading = sampleReading, onOpenDiagnostics = {})
        }
    }

@Preview(name = "Mesh HUD open")
@Composable
fun MeshHudOpenPreview() =
    KnitPreview {
        Box(Modifier.size(360.dp, 640.dp)) {
            MeshHudContent(reading = sampleReading, onOpenDiagnostics = {}, startExpanded = true)
        }
    }
