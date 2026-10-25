package app.getknit.knit.ui.devview

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import app.getknit.knit.mesh.TransportHealth

/**
 * Where the mesh HUD's bubble and its panel sit (ADR 2026-10.s7wg), in absolute pixels within the safe area: never
 * mirrored, so a bubble left at the left edge stays there in a right-to-left locale. Pure, so `HudPlacementTest` pins
 * it. Every position keeps [margin] from the area's edges, so a drag can never lose the bubble off screen.
 */
object HudPlacement {
    /** Two fifths down: clear of the top bar's actions and of the composer. */
    private const val START_HEIGHT = 0.4f

    /** Where the bubble starts: the end edge (the left one in a right-to-left locale), two fifths down. */
    fun initial(
        area: IntSize,
        bubble: Int,
        margin: Int,
        rtl: Boolean,
    ): IntOffset {
        val x = if (rtl) margin else area.width - bubble - margin
        return clamp(IntOffset(x, (area.height * START_HEIGHT).toInt()), area, bubble, margin)
    }

    /** [at], held inside [area]. */
    fun clamp(
        at: IntOffset,
        area: IntSize,
        bubble: Int,
        margin: Int,
    ): IntOffset =
        IntOffset(
            at.x.coerceIn(margin, maxOf(margin, area.width - bubble - margin)),
            at.y.coerceIn(margin, maxOf(margin, area.height - bubble - margin)),
        )

    /** Where a dropped bubble settles: the nearer side edge, at the height it was dropped. */
    fun snap(
        at: IntOffset,
        area: IntSize,
        bubble: Int,
        margin: Int,
    ): IntOffset {
        val nearLeft = at.x + bubble / 2 < area.width / 2
        return clamp(IntOffset(if (nearLeft) margin else area.width - bubble - margin, at.y), area, bubble, margin)
    }

    /**
     * The room the panel may take: toward the larger side of the bubble, above or below, so it never has to cover the
     * bubble to fit. The panel is measured against [PanelRoom.maxHeight] and scrolls inside it.
     */
    fun panelRoom(
        bubbleAt: IntOffset,
        bubble: Int,
        area: IntSize,
        margin: Int,
    ): PanelRoom {
        val below = area.height - (bubbleAt.y + bubble + margin) - margin
        val above = bubbleAt.y - 2 * margin
        return if (below >= above) PanelRoom(below = true, maxHeight = below.coerceAtLeast(0)) else PanelRoom(false, above.coerceAtLeast(0))
    }

    /**
     * Where a measured panel opens: on the bubble's side of the screen and lined up with its outer edge, into the room
     * [panelRoom] chose. A panel measured past that room is still held inside the area, over the bubble if it must be.
     */
    fun panel(
        bubbleAt: IntOffset,
        bubble: Int,
        panel: IntSize,
        area: IntSize,
        margin: Int,
    ): IntOffset {
        val onRight = bubbleAt.x + bubble / 2 >= area.width / 2
        val x = if (onRight) bubbleAt.x + bubble - panel.width else bubbleAt.x
        val y =
            if (panelRoom(bubbleAt, bubble, area, margin).below) bubbleAt.y + bubble + margin else bubbleAt.y - margin - panel.height
        return IntOffset(
            x.coerceIn(margin, maxOf(margin, area.width - panel.width - margin)),
            y.coerceIn(margin, maxOf(margin, area.height - panel.height - margin)),
        )
    }
}

/** Which side of the bubble the panel opens on, and how tall it may be there. */
data class PanelRoom(
    val below: Boolean,
    val maxHeight: Int,
)

/** The bubble's one-glance word for the mesh: its ring's colour. */
enum class HudSignal {
    /** A short-range radio holds at least one neighbour. */
    Linked,

    /** Running, a plane is up, and no neighbour yet. */
    Searching,

    /** Running, or meant to be, and no plane can carry anything: a start the system deferred, or every plane down. */
    Down,

    /** Stopped or paused by the person. */
    Off,
}

/** The [HudSignal] of [reading]. */
fun hudSignal(reading: HudReading): HudSignal =
    when (reading.posture) {
        MeshPosture.Stopped, MeshPosture.Paused -> {
            HudSignal.Off
        }

        MeshPosture.StartDeferred -> {
            HudSignal.Down
        }

        MeshPosture.Running -> {
            when {
                reading.neighbors > 0 -> {
                    HudSignal.Linked
                }

                reading.statuses.any { !it.switchedOff && !it.dormant && it.health != TransportHealth.Unavailable } -> {
                    HudSignal.Searching
                }

                else -> {
                    HudSignal.Down
                }
            }
        }
    }
