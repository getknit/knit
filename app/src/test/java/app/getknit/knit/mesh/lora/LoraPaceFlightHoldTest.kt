package app.getknit.knit.mesh.lora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pacer's flight-table hold ([LoraPacePolicy.take]'s `directRoom`, ADR 2026-10.x9fa's 2026-11-19 amendment): apart
 * from `LoraPacePolicyTest`, which is at its size.
 */
class LoraPaceFlightHoldTest {
    private val full: (OutboundFrame) -> Boolean = { false }
    private val room: (OutboundFrame) -> Boolean = { true }

    private fun frame(label: String) = OutboundFrame(messages = listOf(byteArrayOf(1)), label = label, klass = FrameClass.ROOM)

    /** A compact frame of [kind] — what `LoraMeshTransport.enqueueDirect` queues, its sealed bytes stood in for. */
    private fun direct(
        label: String,
        kind: DirectKind = DirectKind.MESSAGE,
        peer: String = "bob",
    ) = OutboundFrame(
        messages = listOf(byteArrayOf(1)),
        label = label,
        klass = FrameClass.DIRECT,
        direct = DirectSend(peer, BoardAddr(2u), label, kind),
    )

    /**
     * The design pass's B2 on ADR 2026-10.x9fa's 2026-11-19 amendment: with the flight table full a compact DM waits in the
     * queue — never taken, never asked for air, never a holder — while a room post still goes; the first take with a place
     * free sends it, in its turn, and nothing was dropped.
     */
    @Test
    fun aFullFlightTableHoldsEveryCompactFrameAndNothingElse() {
        val pace = LoraPacePolicy(minGapMs = 0)
        pace.enqueue(direct("dm"))
        pace.enqueue(direct("tick", DirectKind.RECEIPT))
        pace.enqueue(frame("room"))
        assertEquals("the room post goes past the held DMs", "room", pace.take(0, directRoom = full)?.label)
        assertNull("only compact frames are left", pace.take(1, directRoom = full))
        assertEquals("never an airtime hold", 0, pace.lastAirtimeHolds)
        assertEquals(2, pace.pending)
        assertTrue("a backstop deferral, never a tick", pace.nextDueAt() >= 1 + LoraPacePolicy.FLIGHT_RECHECK_MS)
        // A place came back (a send ended, and the transport woke the pacer): the deferral lifts with it.
        pace.onFlightEnded()
        assertEquals("dm", pace.take(2, directRoom = room)?.label)
        assertNull("one place, one send: the table is full again", pace.take(3, directRoom = full))
        pace.onFlightEnded()
        assertEquals("tick", pace.take(4, directRoom = room)?.label)
        assertEquals(0, pace.pending)
    }

    @Test
    fun aNewcomerEndsTheFlightTablesDeferralSoARoomPostQueuedBehindIsNotKeptWaiting() {
        val pace = LoraPacePolicy(minGapMs = 0)
        pace.enqueue(direct("dm"))
        assertNull(pace.take(0, directRoom = full))
        assertTrue(pace.nextDueAt() > 0)
        pace.enqueue(frame("room"))
        assertEquals("a fact about the queue as it stood", 0L, pace.nextDueAt())
        assertEquals("room", pace.take(0, directRoom = full)?.label)
    }

    /**
     * Round 2's blocking finding: the room is asked per frame, so a frame to a peer the table has no place for waits while
     * one to a peer it does goes past it — the transport's [DirectFlightRoom] keeps the last place for a confirmed peer.
     */
    @Test
    fun aCompactFrameTheTableHasNoPlaceForWaitsWhileOneToAnotherPeerGoesPastIt() {
        val pace = LoraPacePolicy(minGapMs = 0)
        pace.enqueue(direct("to-bob", peer = "bob"))
        pace.enqueue(direct("to-carol", peer = "carol"))
        val room = DirectFlightRoom(inFlight = 1, toUnreachable = 1, unreachable = setOf("bob"), maxFlights = 2)
        assertEquals("carol", pace.take(0) { room.admits(checkNotNull(it.direct)) }?.direct?.peerId)
        assertEquals(1, pace.pending)
    }
}
