package app.getknit.knit.ui.devview

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.mesh.TransportKind
import app.getknit.knit.mesh.TransportStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where the HUD's bubble and panel sit (ADR 2026-10.s7wg): inside the safe area with a margin whatever the drag, on
 * the nearer side edge once dropped, the panel opening into the room on the bubble's side. And the ring's one word.
 */
class HudPlacementTest {
    private val area = IntSize(1_000, 2_000)

    @Test
    fun theBubbleStartsOnTheEndEdgeTwoFifthsDown() {
        assertEquals(IntOffset(1_000 - B - M, 800), HudPlacement.initial(area, B, M, rtl = false))
        assertEquals("the end edge is the left one right to left", IntOffset(M, 800), HudPlacement.initial(area, B, M, rtl = true))
    }

    @Test
    fun aDragCanNeverTakeTheBubblePastTheMargin() {
        assertEquals(IntOffset(M, M), HudPlacement.clamp(IntOffset(-500, -500), area, B, M))
        assertEquals(IntOffset(1_000 - B - M, 2_000 - B - M), HudPlacement.clamp(IntOffset(5_000, 5_000), area, B, M))
        assertEquals(IntOffset(300, 700), HudPlacement.clamp(IntOffset(300, 700), area, B, M))
    }

    @Test
    fun aDroppedBubbleSettlesOnTheNearerSideEdgeAtTheHeightItWasDropped() {
        // Its centre decides: just left of the middle goes left, the middle itself goes right.
        assertEquals(IntOffset(M, 1_200), HudPlacement.snap(IntOffset(500 - B / 2 - 1, 1_200), area, B, M))
        assertEquals(IntOffset(1_000 - B - M, 1_200), HudPlacement.snap(IntOffset(500 - B / 2, 1_200), area, B, M))
        assertEquals("and stays inside the area", IntOffset(M, 2_000 - B - M), HudPlacement.snap(IntOffset(10, 9_000), area, B, M))
    }

    @Test
    fun thePanelOpensOnTheBubblesSideAndIntoTheRoomThere() {
        val panel = IntSize(600, 500)

        // Top right: lined up with the bubble's right edge, below it.
        val topRight = IntOffset(1_000 - B - M, 300)
        assertEquals(IntOffset(1_000 - M - 600, 300 + B + M), HudPlacement.panel(topRight, B, panel, area, M))
        // Bottom left: lined up with its left edge, above it.
        val bottomLeft = IntOffset(M, 1_500)
        assertEquals(IntOffset(M, 1_500 - M - 500), HudPlacement.panel(bottomLeft, B, panel, area, M))
    }

    @Test
    fun thePanelTakesTheLargerSideOfTheBubbleSoItNeverCoversIt() {
        // Room = the area less the bubble, a margin between them and a margin at the edge.
        assertEquals(
            PanelRoom(below = true, maxHeight = 2_000 - (300 + B + M) - M),
            HudPlacement.panelRoom(IntOffset(880, 300), B, area, M),
        )
        assertEquals(PanelRoom(below = false, maxHeight = 1_500 - 2 * M), HudPlacement.panelRoom(IntOffset(M, 1_500), B, area, M))
        // An even split opens below.
        val middle = (2_000 - B - M) / 2 + M / 2
        assertEquals(true, HudPlacement.panelRoom(IntOffset(M, middle), B, area, M).below)
    }

    @Test
    fun aPanelMeasuredPastItsRoomIsStillHeldInsideTheArea() {
        val tall = IntSize(600, 1_800)

        assertEquals(IntOffset(1_000 - M - 600, 2_000 - M - 1_800), HudPlacement.panel(IntOffset(1_000 - B - M, 300), B, tall, area, M))
        // Wider and taller than the area: pinned to the margin, never placed at a negative offset.
        assertEquals(IntOffset(M, M), HudPlacement.panel(IntOffset(M, 1_500), B, IntSize(5_000, 5_000), area, M))
    }

    @Test
    fun theRingSaysLinkedSearchingDownOrOff() {
        val up = TransportStatus(TransportKind.Bluetooth, TransportHealth.Healthy, linked = 0, nearby = 2)

        assertEquals(HudSignal.Linked, hudSignal(HudReading(MeshPosture.Running, listOf(up), neighbors = 1)))
        assertEquals(HudSignal.Searching, hudSignal(HudReading(MeshPosture.Running, listOf(up))))
        // A dormant or switched-off plane is not searching, nor is one that is down.
        val idle = listOf(up.copy(dormant = true), up.copy(kind = TransportKind.WifiAware, switchedOff = true))
        assertEquals(HudSignal.Down, hudSignal(HudReading(MeshPosture.Running, idle)))
        assertEquals(HudSignal.Down, hudSignal(HudReading(MeshPosture.Running, listOf(up.copy(health = TransportHealth.Unavailable)))))
        assertEquals(HudSignal.Down, hudSignal(HudReading(MeshPosture.Running)))
        assertEquals(HudSignal.Down, hudSignal(HudReading(MeshPosture.StartDeferred, listOf(up), neighbors = 1)))
        assertEquals(HudSignal.Off, hudSignal(HudReading(MeshPosture.Stopped, listOf(up), neighbors = 1)))
        assertEquals(HudSignal.Off, hudSignal(HudReading(MeshPosture.Paused)))
    }

    @Test
    fun theBadgeCountsNeighboursOnlyWhileTheMeshRuns() {
        assertEquals("5", HudLine.badge(HudReading(MeshPosture.Running, neighbors = 5)))
        assertEquals("0", HudLine.badge(HudReading(MeshPosture.Running)))
        assertEquals(InspectFormat.ABSENT, HudLine.badge(HudReading(MeshPosture.Paused, neighbors = 5)))
        assertEquals(InspectFormat.ABSENT, HudLine.badge(null))
    }

    private companion object {
        const val B = 100
        const val M = 20
    }
}
