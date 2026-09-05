package app.getknit.knit.mesh.lora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoraPacePolicyTest {
    private fun frame(
        label: String,
        klass: FrameClass = FrameClass.ROOM,
        supersedes: String? = null,
    ) = OutboundFrame(messages = listOf(byteArrayOf(1)), label = label, klass = klass, supersedes = supersedes)

    @Test
    fun holdsTheMinimumGapBetweenSends() {
        val pace = LoraPacePolicy(minGapMs = 3_000)
        pace.enqueue(frame("a"))
        pace.enqueue(frame("b"))
        assertEquals("a", pace.take(0)!!.label)
        assertNull("second send blocked until the gap elapses", pace.take(2_999))
        assertEquals("b", pace.take(3_000)!!.label)
    }

    @Test
    fun theQueueDropsTheOldestWholeFrameWhenFull() {
        val pace = LoraPacePolicy(queueCap = 2)
        assertEquals(LoraPacePolicy.Admission.ACCEPTED, pace.enqueue(frame("a")))
        assertEquals(LoraPacePolicy.Admission.ACCEPTED, pace.enqueue(frame("b")))
        assertEquals(LoraPacePolicy.Admission.DROPPED_OLDEST, pace.enqueue(frame("c")))
        assertEquals(2, pace.pending)
        assertEquals("oldest evicted, b is next", "b", pace.take(10_000)!!.label)
        assertEquals("c", pace.take(20_000)!!.label)
    }

    @Test
    fun aFullQueueShedsTheRoomBeforeADmAndNeverTheProfile() {
        val pace = LoraPacePolicy(queueCap = 3)
        pace.enqueue(frame("profile", FrameClass.BOOTSTRAP))
        pace.enqueue(frame("room-1"))
        pace.enqueue(frame("dm-1", FrameClass.DM))
        // A second DM evicts the room post (the lowest class present), not the older profile or DM.
        assertEquals(LoraPacePolicy.Admission.DROPPED_OLDEST, pace.enqueue(frame("dm-2", FrameClass.DM)))
        assertEquals(listOf("profile", "dm-1", "dm-2"), drain(pace))
    }

    @Test
    fun aNewcomerAloneAtTheBottomYieldsInsteadOfEvicting() {
        val pace = LoraPacePolicy(queueCap = 2)
        pace.enqueue(frame("profile", FrameClass.BOOTSTRAP))
        pace.enqueue(frame("dm", FrameClass.DM))
        assertEquals("a room post cannot displace a DM or the bootstrap", LoraPacePolicy.Admission.REFUSED, pace.enqueue(frame("room")))
        assertEquals(2, pace.pending)
        // Within one class the oldest still goes and the newcomer stays (recency wins, as before).
        assertEquals(LoraPacePolicy.Admission.DROPPED_OLDEST, pace.enqueue(frame("dm-2", FrameClass.DM)))
        assertEquals(listOf("profile", "dm-2"), drain(pace))
    }

    @Test
    fun dequeueGoesByClassThenFifoWithinIt() {
        val pace = LoraPacePolicy(minGapMs = 0)
        pace.enqueue(frame("tick", FrameClass.TICK))
        pace.enqueue(frame("room"))
        pace.enqueue(frame("dm", FrameClass.DM))
        pace.enqueue(frame("profile", FrameClass.BOOTSTRAP))
        // ADR 044 changed this from plain FIFO: the bridge enqueues gossip and backfill in bursts nobody is
        // waiting for, and at a 3-second gap those would put a live message seconds behind for no reason.
        assertEquals("class governs send order too", listOf("profile", "dm", "room", "tick"), drain(pace))
    }

    /** ADR 054: our own ✓✓ is the first thing a full queue gives up, whichever side of the cap it arrives on. */
    @Test
    fun aFullQueueShedsATickBeforeTheRoomAndATickAloneAtTheBottomYields() {
        val pace = LoraPacePolicy(queueCap = 2)
        pace.enqueue(frame("tick", FrameClass.TICK))
        pace.enqueue(frame("room"))
        assertEquals(LoraPacePolicy.Admission.DROPPED_OLDEST, pace.enqueue(frame("dm", FrameClass.DM)))
        assertEquals(listOf("dm", "room"), drain(pace))

        val again = LoraPacePolicy(queueCap = 2)
        again.enqueue(frame("room"))
        again.enqueue(frame("dm", FrameClass.DM))
        assertEquals("a tick cannot displace content", LoraPacePolicy.Admission.REFUSED, again.enqueue(frame("tick", FrameClass.TICK)))
        assertEquals(listOf("dm", "room"), drain(again))
    }

    @Test
    fun withinOneClassTheOldestStillGoesFirst() {
        val pace = LoraPacePolicy(minGapMs = 0)
        pace.enqueue(frame("room-1"))
        pace.enqueue(frame("room-2"))
        pace.enqueue(frame("dm", FrameClass.DM))
        pace.enqueue(frame("room-3"))
        assertEquals(listOf("dm", "room-1", "room-2", "room-3"), drain(pace))
    }

    @Test
    fun aFrameOverItsAirtimeBudgetIsSkippedRatherThanBlockingTheQueue() {
        // A bridge frame with the bridge share spent must not hold up the live frame behind it.
        val air = LoraAirtime()
        val pace = LoraPacePolicy(minGapMs = 0, airtime = air)
        var now = 0L
        while (air.admits(AirBucket.BRIDGE, FrameClass.ROOM, listOf(MeshtasticProto.MAX_PAYLOAD), now)) {
            air.record(AirBucket.BRIDGE, MeshtasticProto.MAX_PAYLOAD, now)
            now += 3_000
        }
        val big = ByteArray(MeshtasticProto.MAX_PAYLOAD)
        pace.enqueue(OutboundFrame(listOf(big), "backfill", FrameClass.ROOM, AirBucket.BRIDGE))
        pace.enqueue(OutboundFrame(listOf(big), "live", FrameClass.ROOM, AirBucket.LIVE))
        assertEquals("live", pace.take(now)!!.label)
        assertEquals("the refused backfill is still queued, not dropped", 1, pace.pending)
        assertEquals(1, pace.lastAirtimeRefusals)
    }

    @Test
    fun aSupersededOfferLeavesTheQueue() {
        // An OFFER is a snapshot of what we hold. One still queued from a previous interval names a set we
        // have since changed, so a far gateway would compute its backfill against a lie — and one queued at
        // a time is what keeps the Trickle timer, not the queue, the rate bound on this class.
        val pace = LoraPacePolicy(minGapMs = 0)
        pace.enqueue(frame("offer:old", FrameClass.GOSSIP, supersedes = "offer"))
        pace.enqueue(frame("room"))
        pace.enqueue(frame("offer:new", FrameClass.GOSSIP, supersedes = "offer"))
        assertEquals("the older snapshot went, not the room post", 2, pace.pending)
        assertEquals(1, pace.lastSuperseded)
        assertEquals("offer:new", pace.take(0)!!.label)
        assertEquals("nothing else went with it", "room", pace.take(0)!!.label)
    }

    @Test
    fun aProfileSupersedesOnlyItsOwnAuthorsOlderCopy() {
        // Two peers' profiles are different state and neither replaces the other. Getting this wrong in the
        // other direction — one key for the whole class — would drop a peer's only profile whenever anybody
        // else republished, which is the bootstrap the far side cannot decrypt anything without.
        val pace = LoraPacePolicy(minGapMs = 0)
        pace.enqueue(frame("alice:v1", FrameClass.BOOTSTRAP, supersedes = "profile:alice"))
        pace.enqueue(frame("bob:v1", FrameClass.BOOTSTRAP, supersedes = "profile:bob"))
        pace.enqueue(frame("alice:v2", FrameClass.BOOTSTRAP, supersedes = "profile:alice"))

        assertEquals(2, pace.pending)
        assertEquals(1, pace.lastSuperseded)
        assertEquals("bob:v1", pace.take(0)!!.label)
        assertEquals("only the newest copy of alice survives", "alice:v2", pace.take(0)!!.label)
    }

    @Test
    fun aSnapshotBacklogCannotStarveTheOfferForever() {
        // The lab failure this exists for. Profiles outrank the OFFER in the dequeue (BOOTSTRAP < GOSSIP),
        // so before supersession a growing backlog of them took every window's freed air and the offer was
        // never *chosen* — `loraOfferSent` stuck at 0 and the gateway election could never settle. Bounded
        // per author, the backlog drains and the offer gets its turn.
        val pace = LoraPacePolicy(minGapMs = 0)
        repeat(30) { i -> pace.enqueue(frame("alice:v$i", FrameClass.BOOTSTRAP, supersedes = "profile:alice")) }
        pace.enqueue(frame("offer", FrameClass.GOSSIP, supersedes = "offer"))

        assertEquals("one profile, not thirty", 2, pace.pending)
        assertEquals("alice:v29", pace.take(0)!!.label)
        assertEquals("and the offer is right behind it", "offer", pace.take(0)!!.label)
    }

    @Test
    fun aSupersessionIsNeverCountedAsADrop() {
        // `loraDroppedQueue` says the plane shed something it wanted. Nothing is lost here that the newer
        // frame does not already carry, so the newcomer must still report ACCEPTED.
        val pace = LoraPacePolicy(minGapMs = 0)
        pace.enqueue(frame("offer:old", FrameClass.GOSSIP, supersedes = "offer"))
        assertEquals(
            LoraPacePolicy.Admission.ACCEPTED,
            pace.enqueue(frame("offer:new", FrameClass.GOSSIP, supersedes = "offer")),
        )
    }

    @Test
    fun aFullQueueMakesRoomBySupersedingBeforeItSheds() {
        // Supersession runs first, so a snapshot replacing its own older copy never costs an unrelated frame
        // its slot — the queue was never really full for this newcomer.
        val pace = LoraPacePolicy(minGapMs = 0, queueCap = 2)
        pace.enqueue(frame("alice:v1", FrameClass.BOOTSTRAP, supersedes = "profile:alice"))
        pace.enqueue(frame("room"))
        assertEquals(
            LoraPacePolicy.Admission.ACCEPTED,
            pace.enqueue(frame("alice:v2", FrameClass.BOOTSTRAP, supersedes = "profile:alice")),
        )
        assertEquals(2, pace.pending)
        assertEquals("alice:v2", pace.take(0)!!.label)
        assertEquals("the unrelated frame kept its place", "room", pace.take(0)!!.label)
    }

    @Test
    fun aHeldFrameIsReportedOnceAndNotOnEveryTake() {
        // The pacer re-asks the budget every few seconds; a counter built on that would report the clock.
        val air = LoraAirtime()
        val pace = LoraPacePolicy(minGapMs = 0, airtime = air)
        var now = 0L
        while (air.admits(AirBucket.BRIDGE, FrameClass.ROOM, listOf(MeshtasticProto.MAX_PAYLOAD), now)) {
            air.record(AirBucket.BRIDGE, MeshtasticProto.MAX_PAYLOAD, now)
            now += 3_000
        }
        val big = ByteArray(MeshtasticProto.MAX_PAYLOAD)
        pace.enqueue(OutboundFrame(listOf(big), "backfill", FrameClass.ROOM, AirBucket.BRIDGE))
        assertNull(pace.take(now))
        assertEquals(listOf(AirBucket.BRIDGE), pace.lastAirtimeHolds)
        assertNull(pace.take(now + 3_000))
        assertEquals("still stuck, but no longer news", emptyList<AirBucket>(), pace.lastAirtimeHolds)
        assertEquals("and still queued, never dropped", 1, pace.pending)
    }

    @Test
    fun theOfferGoesWhileTheBackfillBesideItWaitsForTheWindow() {
        // The whole failure, at the layer that decides it: both frames spend BRIDGE, the bucket is spent, and
        // only the one that unlocks the far pocket's reply may still leave.
        val air = LoraAirtime()
        val pace = LoraPacePolicy(minGapMs = 0, airtime = air)
        var now = 0L
        while (air.admits(AirBucket.BRIDGE, FrameClass.ROOM, listOf(MeshtasticProto.MAX_PAYLOAD), now)) {
            air.record(AirBucket.BRIDGE, MeshtasticProto.MAX_PAYLOAD, now)
            now += 3_000
        }
        val big = ByteArray(MeshtasticProto.MAX_PAYLOAD)
        pace.enqueue(OutboundFrame(listOf(big), "backfill", FrameClass.ROOM, AirBucket.BRIDGE))
        pace.enqueue(OutboundFrame(listOf(big), "offer", FrameClass.GOSSIP, AirBucket.BRIDGE))
        assertEquals("offer", pace.take(now)!!.label)
        assertEquals("the backfill it would have crowded out is still queued", 1, pace.pending)
    }

    @Test
    fun theBootstrapRidesEvenWithTheBudgetSpent() {
        val air = LoraAirtime()
        val pace = LoraPacePolicy(minGapMs = 0, airtime = air)
        var now = 0L
        while (air.admits(AirBucket.LIVE, FrameClass.ROOM, listOf(MeshtasticProto.MAX_PAYLOAD), now)) {
            air.record(AirBucket.LIVE, MeshtasticProto.MAX_PAYLOAD, now)
            now += 3_000
        }
        val big = ByteArray(MeshtasticProto.MAX_PAYLOAD)
        pace.enqueue(OutboundFrame(listOf(big), "room", FrameClass.ROOM, AirBucket.LIVE))
        pace.enqueue(OutboundFrame(listOf(big), "profile", FrameClass.BOOTSTRAP))
        assertEquals("profile", pace.take(now)!!.label)
        assertNull("everything else waits for the window to roll", pace.take(now + 10_000))
    }

    @Test
    fun aBootstrapFrameOverItsOwnShareWaitsInTheQueueRatherThanBeingLost() {
        // ADR 056: the bootstrap is bounded now, so it can be refused — and a refused profile must be
        // deferred like any other frame, since dropping the key bootstrap is what the exemption existed
        // to prevent in the first place.
        val air = LoraAirtime()
        val pace = LoraPacePolicy(minGapMs = 0, airtime = air)
        val big = ByteArray(MeshtasticProto.MAX_PAYLOAD)
        var now = 0L
        while (air.admits(AirBucket.BOOTSTRAP, FrameClass.BOOTSTRAP, listOf(MeshtasticProto.MAX_PAYLOAD), now)) {
            air.record(AirBucket.BOOTSTRAP, MeshtasticProto.MAX_PAYLOAD, now)
            now += 3_000
        }
        pace.enqueue(OutboundFrame(listOf(big), "profile", FrameClass.BOOTSTRAP))
        pace.enqueue(OutboundFrame(listOf(big), "dm", FrameClass.DM, AirBucket.LIVE))
        assertEquals("the DM goes while the profile's share is spent", "dm", pace.take(now)!!.label)
        assertEquals("the profile is still queued, not dropped", 1, pace.pending)
        assertEquals(1, pace.lastAirtimeRefusals)
        // A whole window later its share is back and it rides.
        val later = now + LoraAirtime.WINDOW_MS
        assertEquals("profile", pace.take(later)!!.label)
    }

    @Test
    fun aBootstrapFrameTakesTheBootstrapBucketWithoutBeingTold() {
        // The class implies the bucket (AirBucket.defaultFor), so no call site can accidentally book a
        // profile against LIVE and get the old unmetered behaviour back.
        assertEquals(AirBucket.BOOTSTRAP, OutboundFrame(listOf(ByteArray(1)), "profile", FrameClass.BOOTSTRAP).bucket)
        assertEquals(AirBucket.LIVE, OutboundFrame(listOf(ByteArray(1)), "dm", FrameClass.DM).bucket)
        assertEquals(
            "an explicit bucket still wins — a backfilled profile is bridge traffic",
            AirBucket.BRIDGE,
            OutboundFrame(listOf(ByteArray(1)), "backfill", FrameClass.BOOTSTRAP, AirBucket.BRIDGE).bucket,
        )
    }

    /** Takes everything queued, advancing the clock past the min gap between takes. */
    private fun drain(pace: LoraPacePolicy): List<String> {
        var now = 1_000_000L
        return generateSequence { pace.take(now).also { now += 10_000 } }.map { it.label }.toList()
    }

    @Test
    fun aFullBoardQueueHoldsAllSends() {
        val pace = LoraPacePolicy()
        pace.enqueue(frame("a"))
        pace.onQueueStatus(free = 0)
        assertNull("board has no headroom", pace.take(10_000))
        pace.onQueueStatus(free = 3)
        assertNotNull(pace.take(10_000))
    }

    @Test
    fun aRateLimitNakWidensTheGap() {
        val pace = LoraPacePolicy(minGapMs = 3_000, nakBackoffMs = 60_000)
        pace.enqueue(frame("a"))
        assertNotNull(pace.take(0))
        pace.enqueue(frame("b"))
        pace.onNak(RoutingError.RATE_LIMIT_EXCEEDED, now = 1_000)
        assertNull("cool-down blocks the next send past the normal gap", pace.take(3_000))
        assertNotNull("sends resume after the cool-down", pace.take(61_000))
    }

    @Test
    fun anUnrelatedNakDoesNotPace() {
        val pace = LoraPacePolicy(minGapMs = 3_000)
        pace.enqueue(frame("a"))
        assertNotNull(pace.take(0))
        pace.enqueue(frame("b"))
        pace.onNak(RoutingError.NO_CHANNEL, now = 1_000)
        assertNotNull("a NO_CHANNEL nak is not a rate limit", pace.take(3_000))
    }

    /**
     * The regression behind the pacer spin: a queue nothing may leave must report a due time in the *future*.
     * Before this, `nextDueAt()` still read "3 s after the last send" — already past — so the transport's
     * drain loop computed a zero wait, never suspended, and spun a core until the window pruned.
     */
    @Test
    fun aSaturatedBudgetDefersTheNextSendToWhenTheWindowFreesAir() {
        val air = LoraAirtime()
        val pace = LoraPacePolicy(minGapMs = 3_000, airtime = air)
        var at = 0L
        var spent = 0L
        while (spent < air.allowanceMs()) {
            air.record(AirBucket.LIVE, 200, at)
            spent += air.timeOnAirMs(200)
            at += 3_000
        }
        val big = OutboundFrame(messages = listOf(ByteArray(200)), label = "room", klass = FrameClass.ROOM)
        pace.enqueue(big)

        val now = at + 3_000
        assertNull("the budget is spent, so nothing goes out", pace.take(now))
        assertEquals(1, pace.lastAirtimeRefusals)
        assertTrue(
            "the next send is deferred to when the window returns air, not left in the past (${pace.nextDueAt()} <= $now)",
            pace.nextDueAt() > now,
        )
        assertEquals("and that is the oldest sample's expiry", air.nextReleaseAt(now), pace.nextDueAt())
    }

    @Test
    fun aNewFrameLiftsTheAirtimeDeferralSoTheBootstrapIsNotHeldBehindIt() {
        val air = LoraAirtime()
        val pace = LoraPacePolicy(minGapMs = 0, airtime = air)
        var at = 0L
        var spent = 0L
        while (spent < air.allowanceMs()) {
            air.record(AirBucket.LIVE, 200, at)
            spent += air.timeOnAirMs(200)
            at += 3_000
        }
        pace.enqueue(OutboundFrame(messages = listOf(ByteArray(200)), label = "room"))
        assertNull(pace.take(at))
        assertTrue("deferred", pace.nextDueAt() > at)

        // A profile is always admitted, so the deferral the room post earned must not strand it.
        pace.enqueue(OutboundFrame(messages = listOf(ByteArray(200)), label = "profile", klass = FrameClass.BOOTSTRAP))
        assertEquals("profile", pace.take(at)?.label)
    }

    @Test
    fun takeIsNullWhenEmpty() {
        assertNull(LoraPacePolicy().take(10_000))
    }

    @Test
    fun evictOversizeShedsOnlyFramesWithAPartPastTheCap() {
        val pace = LoraPacePolicy()
        pace.enqueue(OutboundFrame(listOf(ByteArray(233), ByteArray(40)), "chunked-at-the-old-max", FrameClass.DM))
        pace.enqueue(OutboundFrame(listOf(ByteArray(120)), "fits", FrameClass.ROOM))
        pace.enqueue(OutboundFrame(listOf(ByteArray(100), ByteArray(229)), "tail-too-big", FrameClass.ROOM))
        assertEquals(2, pace.evictOversize(228))
        assertEquals(1, pace.pending)
        assertEquals("fits", pace.take(now = 0L)?.label)
        assertEquals(0, pace.evictOversize(1))
    }
}
