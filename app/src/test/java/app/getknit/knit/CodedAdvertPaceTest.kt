package app.getknit.knit

import app.getknit.knit.mesh.bluetooth.CodedAdvertPace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the Coded set advertises fast (ADR 2026-10.yvn6, amendment 3): after a drop at range, until the peer is back. */
class CodedAdvertPaceTest {
    private val pace = CodedAdvertPace()

    @Test
    fun slowUntilALinkDrops() {
        assertFalse(pace.fast(now = 0))
        assertNull(pace.endsAt())
    }

    @Test
    fun aDropIsFastForTheHold() {
        pace.onDrop("p", now = 1_000, holdMs = HOLD)
        assertTrue(pace.fast(now = 1_000))
        assertTrue(pace.fast(now = 1_000 + HOLD - 1))
        assertEquals(1_000 + HOLD, pace.endsAt())
        assertFalse(pace.fast(now = 1_000 + HOLD))
        assertNull("the window is gone once it has run out", pace.endsAt())
    }

    @Test
    fun thePeerLinkingAgainEndsItsWindow() {
        pace.onDrop("p", now = 0, holdMs = HOLD)
        pace.onLinkUp("p")
        assertFalse(pace.fast(now = 1))
    }

    @Test
    fun anotherPeerStillWantedKeepsItFast() {
        pace.onDrop("p", now = 0, holdMs = HOLD)
        pace.onDrop("q", now = 60_000, holdMs = HOLD)
        pace.onLinkUp("p")
        assertTrue(pace.fast(now = 61_000))
        assertEquals(setOf("q"), pace.wanted(now = 61_000))
        assertEquals(60_000 + HOLD, pace.endsAt())
    }

    @Test
    fun aSecondDropRestartsThePeersHold() {
        pace.onDrop("p", now = 0, holdMs = HOLD)
        pace.onDrop("p", now = 100_000, holdMs = HOLD)
        assertTrue(pace.fast(now = HOLD + 1))
    }

    @Test
    fun twoRefusedFastEnablesGiveTheWindowUp() {
        pace.onDrop("p", now = 0, holdMs = HOLD)
        assertFalse(pace.onFastRefused())
        assertTrue(pace.fast(now = 1))
        assertTrue("the second refusal gives up", pace.onFastRefused())
        assertFalse(pace.fast(now = 2))
        assertFalse("and says so once", pace.onFastRefused())
    }

    @Test
    fun theNextDropAfterTheWindowTriesAgain() {
        pace.onDrop("p", now = 0, holdMs = HOLD)
        pace.onFastRefused()
        pace.onFastRefused()
        assertFalse(pace.fast(now = HOLD)) // the window ran out, and the count with it
        pace.onDrop("q", now = HOLD + 1, holdMs = HOLD)
        assertTrue(pace.fast(now = HOLD + 2))
    }

    private companion object {
        const val HOLD = 180_000L
    }
}
