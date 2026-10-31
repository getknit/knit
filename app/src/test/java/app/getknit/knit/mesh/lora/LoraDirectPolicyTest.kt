package app.getknit.knit.mesh.lora

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the plane keeps about each peer's board as a compact-DM destination (ADR 2026-10.x9fa): the reach marks, the
 * one-send-per-unconfirmed-peer rule, the in-flight table and its deadlines, and the arithmetic that books retries.
 */
class LoraDirectPolicyTest {
    private val linger = 45 * 60_000L

    private fun policy() = LoraDirectPolicy(lingerMs = linger)

    private fun flight(
        handle: UInt,
        peer: String = "bob",
        frameId: String = "f$handle",
        sentAt: Long = 0,
        deadline: Long = 45_000,
    ) = LoraDirectPolicy.Flight(
        handle = handle,
        send = DirectSend(peer, BoardAddr(2u), frameId, DirectKind.MESSAGE),
        airMs = 1_800,
        sentAt = sentAt,
        deadline = deadline,
        retransmitMs = 8_000,
    )

    @Test
    fun anUnknownBoardIsSentOneDmAtATime() {
        val p = policy()
        assertEquals(LoraDirectPolicy.Reach.UNKNOWN, p.reachOf("bob", 0))
        assertFalse("nothing in flight: the first goes", p.mustWait("bob", 0, queuedTo = false))
        assertTrue("one queued: the next waits", p.mustWait("bob", 0, queuedTo = true))
        p.launch(flight(1u))
        assertTrue("one awaiting its answer: the next waits", p.mustWait("bob", 0, queuedTo = false))
        assertFalse("another peer is not held behind it", p.mustWait("carol", 0, queuedTo = false))
    }

    @Test
    fun aDestinationAckMakesTheBoardReachableUntilItLingersOut() {
        val p = policy()
        p.launch(flight(1u))
        p.onAcked("bob", now = 10)
        assertEquals(LoraDirectPolicy.Reach.REACHABLE, p.reachOf("bob", 10 + linger))
        assertFalse("a reachable board takes DMs back to back", p.mustWait("bob", 10, queuedTo = true))
        assertEquals(LoraDirectPolicy.Reach.UNKNOWN, p.reachOf("bob", 11 + linger))
    }

    @Test
    fun aGiveUpHoldsOffersUntilTheBoardIsHeardOnceAndOnlyOnce() {
        val p = policy()
        p.onUnreachable("bob", now = 0)
        assertTrue(p.holds("bob", 0))
        assertTrue("the edge out of unreachable", p.onHeard("bob"))
        assertFalse(p.holds("bob", 1))
        assertFalse("a second hearing is no edge", p.onHeard("bob"))
        assertFalse("hearing an unmarked board is no edge", p.onHeard("carol"))
    }

    @Test
    fun aRefusalOutlivesHearingsAndGiveUpsAndLiftsOnlyOnANewProfile() {
        val p = policy()
        p.onRefused("bob", now = 0)
        assertFalse("hearing them does not lift it: they are plainly on the air", p.onHeard("bob"))
        p.onUnreachable("bob", now = 1)
        p.onAcked("bob", now = 2)
        assertEquals(LoraDirectPolicy.Reach.REFUSED, p.reachOf("bob", 3))
        p.onProfileChanged("bob")
        assertEquals(LoraDirectPolicy.Reach.UNKNOWN, p.reachOf("bob", 3))
    }

    @Test
    fun everyFlightEndsByItsDeadlineAndTheTableIsBounded() {
        val p = policy()
        p.launch(flight(1u, deadline = 100))
        p.launch(flight(2u, peer = "carol", deadline = 50))
        assertEquals(50L, p.nextDeadline())
        assertTrue(p.expired(49).isEmpty())
        assertEquals(listOf(2u), p.expired(50).map { it.handle })
        assertEquals(listOf(1u), p.expired(1_000).map { it.handle })
        assertNull(p.nextDeadline())

        repeat(LoraDirectPolicy.MAX_FLIGHTS) { p.launch(flight((it + 10).toUInt(), peer = "p$it")) }
        val evicted = p.launch(flight(999u, peer = "late"))
        assertEquals("past the cap the oldest ends", 10u, evicted?.handle)
        assertEquals(LoraDirectPolicy.MAX_FLIGHTS, p.inFlight())
    }

    @Test
    fun aFlightIsWaitingUntilResolvedAndResolvesOnce() {
        val p = policy()
        p.launch(flight(7u))
        assertTrue(p.isWaiting(7u))
        assertTrue(p.hasFrame("f7"))
        assertEquals(7u, p.resolve(7u)?.handle)
        assertFalse(p.isWaiting(7u))
        assertNull("resolved once", p.resolve(7u))
    }

    @Test
    fun parkedOffersAreBoundedPerPeerAndDedupedByFrame() {
        val p = policy()
        val cap = LoraDirectPolicy.MAX_PARKED_PER_PEER
        repeat(cap + 3) {
            p.park("bob", LoraDirectPolicy.Parked("f$it", 0, byteArrayOf(1), DirectKind.MESSAGE))
        }
        // A frame from the middle parked again: one entry for it, moved to the newest end, carrying the newer plaintext.
        p.park("bob", LoraDirectPolicy.Parked("f5", 0, byteArrayOf(2), DirectKind.MESSAGE))
        val parked = p.unpark("bob")
        val kept = (3 until cap + 3).map { "f$it" } - "f5" + "f5"
        assertEquals("the oldest three dropped, f5 once and last", kept, parked.map { it.frameId })
        assertArrayEquals(byteArrayOf(2), parked.last().plaintext)
        assertTrue(p.unpark("bob").isEmpty())
    }

    @Test
    fun theGapIsTheFirmwaresAndTheDeadlineOutlastsItsGiveUpOnABusyBand() {
        // `getRetransmissionMsec` at LongFast: a ≈44.5-ms slot, 2^CW + 16 + 32 slots, 4.5 s of processing.
        val ledger = LoraAirtime()
        val slot = ledger.slotTimeMs()
        assertEquals(44.46, slot, 0.01)
        val air = ledger.timeOnAirMs(MeshtasticProto.MAX_PAYLOAD, LoraAirtime.UNSIGNED)
        assertEquals(2 * air + (56 * slot).toLong() + 4_500, LoraDirectPolicy.retransmitMs(air, slot, LoraDirectPolicy.CW_MIN))
        val busy = LoraDirectPolicy.retransmitMs(air, slot, LoraDirectPolicy.CW_MAX)
        assertEquals(2 * air + (304 * slot).toLong() + 4_500, busy)
        // The firmware gives up one gap after its fifth attempt; on the busiest band that is five gaps.
        val deadline = LoraDirectPolicy.deadlineMs(air, slot)
        assertTrue("the plane's deadline comes after the firmware's slowest give-up", deadline > LoraDirectPolicy.AIRINGS * busy)
        assertEquals(5, LoraDirectPolicy.AIRINGS)
    }

    @Test
    fun anAckCountsRetriesAtTheShortestGapSoItErrsHigh() {
        val gap = 10_000L
        assertEquals(0, LoraDirectPolicy.retriesBefore(gap - 1, gap))
        assertEquals(1, LoraDirectPolicy.retriesBefore(gap, gap))
        assertEquals("never more than the firmware flies", LoraDirectPolicy.RETRANSMITS, LoraDirectPolicy.retriesBefore(100 * gap, gap))
    }

    @Test
    fun aRefusalLapsesAfterTheReprobesCapWithNoNewProfile() {
        val p = LoraDirectPolicy(lingerMs = linger, refusedLapseMs = 1_000)
        p.onRefused("bob", now = 0)
        assertEquals(LoraDirectPolicy.Reach.REFUSED, p.reachOf("bob", 1_000))
        assertEquals(LoraDirectPolicy.Reach.UNKNOWN, p.reachOf("bob", 1_001))
    }

    @Test
    fun aFlightThatEndedUnansweredIsRememberedOnceForALateAnswer() {
        val p = policy()
        p.launch(flight(1u, deadline = 10))
        p.launch(flight(2u, peer = "carol"))
        p.expired(10)
        assertEquals("bob", p.ended(1u)?.peerId)
        assertNull("read once", p.ended(1u))
        p.clearFlights()
        assertEquals("a flight the board took with it is remembered too", "carol", p.ended(2u)?.peerId)
        repeat(LoraDirectPolicy.MAX_FLIGHTS + 1) {
            p.launch(flight((100 + it).toUInt(), deadline = 0))
        }
        p.expired(0)
        assertNull("bounded: the oldest is forgotten", p.ended(100u))
    }

    /**
     * A flight pushed out of a full table ended unanswered as surely as one past its deadline: the destination's ack
     * that may still come for it is read as a late answer — the marks move, and the chat hears their radio has it.
     */
    @Test
    fun aFlightPushedOutOfAFullTableIsRememberedForALateAnswer() {
        val p = policy()
        repeat(LoraDirectPolicy.MAX_FLIGHTS) { p.launch(flight((it + 10).toUInt(), peer = "p$it")) }
        val evicted = p.launch(flight(999u, peer = "late"))
        assertEquals(10u, evicted?.handle)
        assertFalse(p.isWaiting(10u))
        assertEquals("p0", p.ended(10u)?.peerId)
        assertNull("read once", p.ended(10u))
    }

    @Test
    fun theCountsSayWhatEachBoardSaid() {
        val p = policy()
        p.onAcked("a", 0)
        p.onUnreachable("b", 0)
        p.onRefused("c", 0)
        p.launch(flight(1u, peer = "d"))
        p.park("d", LoraDirectPolicy.Parked("x", 0, byteArrayOf(), DirectKind.RECEIPT))
        assertEquals(DirectReachCounts(reachable = 1, unreachable = 1, refused = 1, inFlight = 1, parked = 1), p.counts(0))
        p.clearPeers()
        assertEquals(1, p.clearFlights().size)
        assertEquals(DirectReachCounts(), p.counts(0))
    }

    private fun toUs(
        wantAck: Boolean = false,
        hopLimit: Int = 0,
        nextHop: Int = 0,
    ) = ReceivedPacket(
        from = 9u,
        to = 0x1234abcdu,
        id = 1u,
        channelIndex = 1,
        portnum = MeshtasticProto.PORT_PRIVATE_APP,
        payload = byteArrayOf(0x11),
        rxSnr = 6f,
        rxRssi = -80,
        hopsAway = 0,
        wantAck = wantAck,
        hopLimit = hopLimit,
        nextHop = nextHop,
    )

    @Test
    fun ourBoardAcksAUnicastThatAskedOrThatNamedUsItsNextHopWithHopsLeft() {
        val us = 0x1234abcdu
        assertTrue("want_ack draws the ACK", LoraDirectPolicy.boardAcks(toUs(wantAck = true), us))
        assertTrue("however many hops it has left", LoraDirectPolicy.boardAcks(toUs(wantAck = true, hopLimit = 0), us))
        assertFalse("neither asked nor routed through us: no ACK", LoraDirectPolicy.boardAcks(toUs(hopLimit = 3), us))
        assertTrue(
            "named as its next hop with hops left: the hop-zero ACK that stops the sender's retries",
            LoraDirectPolicy.boardAcks(toUs(hopLimit = 3, nextHop = 0xCD), us),
        )
        assertFalse("no hops left: none", LoraDirectPolicy.boardAcks(toUs(hopLimit = 0, nextHop = 0xCD), us))
        assertFalse("another node's byte: none", LoraDirectPolicy.boardAcks(toUs(hopLimit = 3, nextHop = 0xCE), us))
    }

    @Test
    fun aNodeWhoseLowByteIsZeroAnswersToFf() {
        assertEquals(0xFF, LoraDirectPolicy.lastByteOf(0x1234ab00u))
        assertEquals(0xCD, LoraDirectPolicy.lastByteOf(0x1234abcdu))
        assertFalse("an absent next_hop never names anybody", LoraDirectPolicy.boardAcks(toUs(hopLimit = 3), 0x1234ab00u))
    }

    // --- PKI on 2.8 (ADR 2026-10.x9fa's PKI amendment, ADR 2026-10.aajj) ---

    @Test
    fun theCompactCapIsTheFirmwaresPkiCheckUnderTheWantAckWrite() {
        val pki = MeshtasticProto.MAX_PAYLOAD - MeshtasticProto.PKC_OVERHEAD_BYTES
        assertEquals(219, pki)
        assertEquals(
            "at the MTU-255 floor the PKI check binds, not the BLE write",
            pki,
            LoraDirectPolicy.payloadCap(LoraMeshTransport.PRE_READY_PAYLOAD),
        )
        assertEquals("and at the largest MTU", pki, LoraDirectPolicy.payloadCap(MeshtasticProto.MAX_PAYLOAD))
        assertTrue(
            "12 B under the old cap",
            LoraDirectPolicy.payloadCap(LoraMeshTransport.PRE_READY_PAYLOAD) <
                LoraMeshTransport.PRE_READY_PAYLOAD - MeshtasticProto.WANT_ACK_FIELD_BYTES,
        )
        assertEquals("a small board's BLE write still binds below it", 98, LoraDirectPolicy.payloadCap(100))
    }

    @Test
    fun anAckIsBookedWithItsProof() {
        assertEquals("tag, length and the 8-byte ACK_PROOF_SIZE", 10, LoraDirectPolicy.ACK_PROOF_FIELD_BYTES)
        assertEquals(18, LoraDirectPolicy.ACK_PAYLOAD_BYTES)
    }

    @Test
    fun aMissingKeyHoldsUntilTheProfileChangesAKeyIsHeardOrItLapses() {
        val p = policy()
        p.onNoKey("bob", now = 0)
        assertEquals(LoraDirectPolicy.Reach.NO_KEY, p.reachOf("bob", 0))
        assertTrue(p.holds("bob", 0))
        p.onUnreachable("bob", now = 1)
        assertEquals("a give-up never overwrites it", LoraDirectPolicy.Reach.NO_KEY, p.reachOf("bob", 1))
        assertTrue("a key heard lifts it", p.onKeyLearned("bob"))
        assertFalse("once", p.onKeyLearned("bob"))
        p.onNoKey("bob", now = 0)
        p.onProfileChanged("bob")
        assertEquals(LoraDirectPolicy.Reach.UNKNOWN, p.reachOf("bob", 0))
        p.onNoKey("bob", now = 0)
        assertEquals(LoraDirectPolicy.Reach.UNKNOWN, p.reachOf("bob", LoraReprobe.CAP_MS + 1))
        p.onNoKey("carol", now = 0)
        assertEquals(1, p.counts(0).noKey)
    }

    @Test
    fun a39IsActedOnOncePerPacketWhicheverReportCameFirst() {
        val p = policy()
        assertTrue(p.firstNoKey(7u))
        assertFalse("its self-NAK after its queueStatus, or the other way round", p.firstNoKey(7u))
        assertTrue(p.firstNoKey(8u))
    }

    private fun held(frameId: String) =
        OutboundFrame(
            listOf(byteArrayOf(0x11)),
            "direct:message:$frameId",
            FrameClass.DIRECT,
            direct = DirectSend("bob", BoardAddr(2u), frameId, DirectKind.MESSAGE),
        )

    @Test
    fun aFrameHeldForItsContactWriteIsTheOneSendItsPeerIsAllowed() {
        val p = policy()
        p.holdForContact("bob", held("f1"))
        assertTrue("the next offer parks behind it", p.mustWait("bob", 0, queuedTo = false))
        assertTrue("and no second copy of it queues", p.hasFrame("f1"))
        assertFalse(p.mustWait("carol", 0, queuedTo = false))
        assertEquals("f1", p.releaseContact("bob")?.direct?.frameId)
        assertFalse(p.mustWait("bob", 0, queuedTo = false))
        assertNull(p.releaseContact("bob"))
    }

    @Test
    fun theReofferAfterAPeerLackedOurKeyIsDueAfterItsGapAndHoldsThePeerMeanwhile() {
        val p = policy()
        p.launch(flight(1u, deadline = 120_000))
        p.scheduleRetry("bob", at = 9_000, frame = held("f1"))
        assertEquals("the re-offer is the soonest wake", 9_000L, p.nextDeadline())
        assertTrue(p.mustWait("bob", 0, queuedTo = false))
        assertTrue(p.hasFrame("f1"))
        assertTrue(p.dueRetries(8_999).isEmpty())
        assertEquals(listOf("f1"), p.dueRetries(9_000).map { it.direct?.frameId })
        assertTrue("taken once", p.dueRetries(9_000).isEmpty())
        p.scheduleRetry("bob", at = 9_000, frame = held("f2"))
        p.holdForContact("carol", held("g1"))
        p.clearPeers()
        assertTrue("the board went: both holds with it", p.dueRetries(Long.MAX_VALUE).isEmpty())
        assertNull(p.releaseContact("carol"))
    }
}
