package app.getknit.knit

import app.getknit.knit.mesh.wifiaware.NanServePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [NanServePolicy] — the pure concurrent-inbound-serve admission decision. */
class NanServePolicyTest {
    @Test
    fun capReservesOneSessionForOutboundAndHonorsTheSanityCap() {
        assertEquals("Pixel-class budget of 8 → min(7, sanity 4)", 4, NanServePolicy.capFor(8))
        assertEquals("a 3-session budget → 2 serves", 2, NanServePolicy.capFor(3))
        assertEquals("sanity cap can be raised", 7, NanServePolicy.capFor(8, sanityCap = 7))
    }

    @Test
    fun tinyOrUnreadableBudgetsFallBackToSingleSlot() {
        assertEquals("Samsung S.LSI ships maxNdpSessions=1", 1, NanServePolicy.capFor(1))
        assertEquals("a 2-session budget still leaves only 1 after the outbound reserve", 1, NanServePolicy.capFor(2))
        assertEquals("unreadable capability defaults to 1 at the call site", 1, NanServePolicy.capFor(0))
    }

    @Test
    fun capOneIsByteEquivalentToTheLegacySingleSlotGate() {
        // Legacy: admit iff nothing live/in-flight/accepting AND the post-teardown settle elapsed.
        for (busy in listOf(true, false)) {
            for (settle in listOf(true, false)) {
                val admitted =
                    NanServePolicy.admitAccept(
                        inFlightHandshakes = 0,
                        liveInbound = 0,
                        acceptsInHello = 0,
                        cap = 1,
                        singleSlotBusy = busy,
                        settleOk = settle,
                    )
                assertEquals("legacy semantics at cap 1 (busy=$busy settle=$settle)", !busy && settle, admitted)
            }
        }
    }

    @Test
    fun concurrentModeAdmitsUpToTheCap() {
        fun admit(
            live: Int,
            inHello: Int,
        ) = NanServePolicy.admitAccept(
            inFlightHandshakes = 0,
            liveInbound = live,
            acceptsInHello = inHello,
            cap = 3,
            singleSlotBusy = true, // ignored at cap > 1 (live inbound makes the legacy gate "busy")
            settleOk = false, // ignored at cap > 1 (an accept files no requestNetwork)
        )
        assertTrue(admit(0, 0))
        assertTrue(admit(1, 1))
        assertFalse("live + in-HELLO reservations fill the cap", admit(2, 1))
        assertFalse(admit(3, 0))
    }

    @Test
    fun anInitiatorHandshakeExcludesAccepts() {
        // An inbound NDP arriving mid-initiate is framework-refused and kills the responder request — never
        // reserve an accept slot while our own handshake is in flight.
        assertFalse(
            NanServePolicy.admitAccept(
                inFlightHandshakes = 1,
                liveInbound = 0,
                acceptsInHello = 0,
                cap = 4,
                singleSlotBusy = true,
                settleOk = true,
            ),
        )
    }
}
