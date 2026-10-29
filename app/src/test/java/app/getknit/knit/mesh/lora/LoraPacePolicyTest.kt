package app.getknit.knit.mesh.lora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /** A compact frame of [kind] — what `LoraMeshTransport.enqueueDirect` queues, its sealed bytes stood in for. */
    private fun direct(
        label: String,
        kind: DirectKind = DirectKind.MESSAGE,
    ) = OutboundFrame(
        messages = listOf(byteArrayOf(1)),
        label = label,
        klass = FrameClass.DIRECT,
        direct = DirectSend("bob", BoardAddr(2u), label, kind),
    )

    /**
     * The queue has six writers on five coroutines and [java.util.ArrayDeque] is not thread-safe: a
     * structural change under `admitBest`'s index walk nulls the slot it is about to read. Field-observed on
     * the lab P7 (2026-09-10) as `NullPointerException: … OutboundFrame.getBucket()`, which kills the pacer
     * loop and silences the plane until the app restarts.
     *
     * A race is not deterministic, so this leans on volume rather than on ordering: without the monitors it
     * fails within a run or two, and it costs a few hundred milliseconds when they are there.
     */
    @Test
    fun aQueueDrainedWhileOtherThreadsEnqueueDoesNotTearItself() {
        val pace = LoraPacePolicy(minGapMs = 0, queueCap = 64)
        pace.onQueueStatus(free = 16)
        val failures = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val start = java.util.concurrent.CountDownLatch(1)
        val writers =
            (1..4).map { w ->
                Thread {
                    start.await()
                    runCatching { repeat(2_000) { pace.enqueue(frame("w$w-$it", FrameClass.entries[it % FrameClass.entries.size])) } }
                        .onFailure { failures += it }
                }
            }
        val drainer =
            Thread {
                start.await()
                runCatching { repeat(8_000) { pace.take(it.toLong()) } }.onFailure { failures += it }
            }
        (writers + drainer).forEach { it.start() }
        start.countDown()
        (writers + drainer).forEach { it.join(30_000) }

        assertTrue("the queue tore under concurrent access: ${failures.firstOrNull()}", failures.isEmpty())
    }

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
    fun aFullQueueShedsTheRoomBeforeADm() {
        val pace = LoraPacePolicy(queueCap = 3)
        pace.enqueue(frame("dm-0", FrameClass.DIRECT))
        pace.enqueue(frame("room-1"))
        pace.enqueue(frame("dm-1", FrameClass.DIRECT))
        // A third DM evicts the room post (the lowest class present), not an older DM.
        assertEquals(LoraPacePolicy.Admission.DROPPED_OLDEST, pace.enqueue(frame("dm-2", FrameClass.DIRECT)))
        assertEquals(listOf("dm-0", "dm-1", "dm-2"), drain(pace))
    }

    @Test
    fun aNewcomerAloneAtTheBottomYieldsInsteadOfEvicting() {
        val pace = LoraPacePolicy(queueCap = 2)
        pace.enqueue(frame("dm-0", FrameClass.DIRECT))
        pace.enqueue(frame("dm", FrameClass.DIRECT))
        assertEquals("a room post cannot displace a DM", LoraPacePolicy.Admission.REFUSED, pace.enqueue(frame("room")))
        assertEquals(2, pace.pending)
        // Within one class the oldest still goes and the newcomer stays (recency wins, as before).
        assertEquals(LoraPacePolicy.Admission.DROPPED_OLDEST, pace.enqueue(frame("dm-2", FrameClass.DIRECT)))
        assertEquals(listOf("dm", "dm-2"), drain(pace))
    }

    @Test
    fun dequeueGoesByClassThenFifoWithinIt() {
        val pace = LoraPacePolicy(minGapMs = 0)
        pace.enqueue(frame("room"))
        pace.enqueue(direct("tick", DirectKind.RECEIPT))
        pace.enqueue(direct("dm"))
        // Two priorities since ADR 2026-10.ug4a: the ✓✓ is DIRECT, and goes in its turn before any room post.
        assertEquals("class governs send order, FIFO within it", listOf("tick", "dm", "room"), drain(pace))
    }

    /** ADR 2026-10.ug4a: a full queue sheds a room post, then a ✓✓, and only then a DM or a reaction the user wrote. */
    @Test
    fun aFullQueueShedsTheRoomThenAReceiptBeforeAMessage() {
        val pace = LoraPacePolicy(queueCap = 3)
        pace.enqueue(direct("tick", DirectKind.RECEIPT))
        pace.enqueue(frame("room"))
        pace.enqueue(direct("dm-0"))
        assertEquals(LoraPacePolicy.Admission.DROPPED_OLDEST, pace.enqueue(direct("dm-1")))
        assertEquals("the room post went first", listOf("room"), pace.drainShed().map { it.label })
        assertEquals(LoraPacePolicy.Admission.DROPPED_OLDEST, pace.enqueue(direct("reaction", DirectKind.REACTION)))
        assertEquals("then the ✓✓, ahead of the older DM", listOf("tick"), pace.drainShed().map { it.label })
        assertEquals(listOf("dm-0", "dm-1", "reaction"), drain(pace))

        val again = LoraPacePolicy(queueCap = 2)
        again.enqueue(direct("dm-0"))
        again.enqueue(direct("dm-1"))
        assertEquals("a ✓✓ cannot displace a DM", LoraPacePolicy.Admission.REFUSED, again.enqueue(direct("tick", DirectKind.RECEIPT)))
        assertEquals(LoraPacePolicy.Admission.REFUSED, again.enqueue(frame("room")))
        assertEquals(listOf("dm-0", "dm-1"), drain(again))
    }

    @Test
    fun aReceiptDisplacesARoomPostButNeverAMessage() {
        val pace = LoraPacePolicy(queueCap = 2)
        pace.enqueue(frame("room"))
        pace.enqueue(direct("dm"))
        assertEquals(LoraPacePolicy.Admission.DROPPED_OLDEST, pace.enqueue(direct("tick", DirectKind.RECEIPT)))
        assertEquals(listOf("dm", "tick"), drain(pace))
    }

    @Test
    fun withinOneClassTheOldestStillGoesFirst() {
        val pace = LoraPacePolicy(minGapMs = 0)
        pace.enqueue(frame("room-1"))
        pace.enqueue(frame("room-2"))
        pace.enqueue(frame("dm", FrameClass.DIRECT))
        pace.enqueue(frame("room-3"))
        assertEquals(listOf("dm", "room-1", "room-2", "room-3"), drain(pace))
    }

    /** Spends [air] to the foreign audience's half and returns the time: a [Ceiling.HALF] frame now refuses, a full one fits. */
    private fun publicSpent(air: LoraAirtime): Long {
        var now = 0L
        while (air.admits(Ceiling.HALF, listOf(MeshtasticProto.MAX_PAYLOAD), now)) {
            air.record(MeshtasticProto.MAX_PAYLOAD, now)
            now += 3_000
        }
        return now
    }

    @Test
    fun aFrameOverItsAirtimeBudgetIsSkippedRatherThanBlockingTheQueue() {
        // A Meshtastic-room post past the half ceiling must not hold up the Knit frame behind it.
        val air = LoraAirtime()
        val pace = LoraPacePolicy(minGapMs = 0, airtime = air)
        val now = publicSpent(air)
        val big = ByteArray(MeshtasticProto.MAX_PAYLOAD)
        pace.enqueue(OutboundFrame(listOf(big), "public", FrameClass.ROOM, Ceiling.HALF, Destination.Public))
        pace.enqueue(OutboundFrame(listOf(big), "live", FrameClass.ROOM, Ceiling.FULL))
        assertEquals("live", pace.take(now)!!.label)
        assertEquals("the refused public post is still queued, not dropped", 1, pace.pending)
        assertEquals(1, pace.lastAirtimeRefusals)
    }

    /**
     * Spends the window's total down to one packet's worth: everything booked live until a single full packet no
     * longer fits, then the window rolls far enough to hand the oldest booking back. A one-packet frame now fits
     * and a two-packet one does not, the state the 2026-10-04 walk sat in for eleven minutes. Returns the time.
     */
    private fun oneSlotLeft(air: LoraAirtime): Long {
        var now = 0L
        while (air.admits(Ceiling.FULL, listOf(MeshtasticProto.MAX_PAYLOAD), now)) {
            air.record(MeshtasticProto.MAX_PAYLOAD, now)
            now += 3_000
        }
        now = air.nextReleaseAt(now)!!
        assertTrue(air.admits(Ceiling.FULL, listOf(MeshtasticProto.MAX_PAYLOAD), now))
        assertFalse(air.admits(Ceiling.FULL, List(2) { MeshtasticProto.MAX_PAYLOAD }, now))
        return now
    }

    private fun live(
        label: String,
        klass: FrameClass,
        parts: Int,
        ceiling: Ceiling = Ceiling.FULL,
        destination: Destination = Destination.Knit,
    ) = OutboundFrame(List(parts) { ByteArray(MeshtasticProto.MAX_PAYLOAD) }, label, klass, ceiling, destination)

    @Test
    fun aDmTheTotalRefusesHoldsTheRoomPostsThatWouldTakeItsAir() {
        // The 2026-10-04 walk: a two-packet DM waited from 16:28 to 16:39 while one-packet room posts took each
        // packet's worth of air the rolling window handed back.
        val air = LoraAirtime()
        val pace = LoraPacePolicy(minGapMs = 0, airtime = air)
        var now = oneSlotLeft(air)
        pace.enqueue(live("dm", FrameClass.DIRECT, parts = 2))
        pace.enqueue(live("room", FrameClass.ROOM, parts = 1))
        assertNull("the room post fits, but it would spend the air the DM is waiting for", pace.take(now))
        assertEquals("both still queued", 2, pace.pending)
        assertEquals(2, pace.lastAirtimeRefusals)
        now = air.nextReleaseAt(now)!!
        assertEquals("a second packet's worth back, and the DM goes first", "dm", pace.take(now)!!.label)
    }

    @Test
    fun onlyADirectFrameTheTotalRefusesHoldsWhatRanksBehindIt() {
        // ADR 2026-10.ug4a narrows the holder to DIRECT: a room frame the total refuses waits for air like any frame, but
        // holds nothing behind it — every room post is one packet, so none is starved by smaller ones.
        val air = LoraAirtime()
        val pace = LoraPacePolicy(minGapMs = 0, airtime = air)
        val now = oneSlotLeft(air)
        pace.enqueue(live("room:long", FrameClass.ROOM, parts = 2))
        pace.enqueue(live("room", FrameClass.ROOM, parts = 1))
        assertEquals("room", pace.take(now)!!.label)
    }

    @Test
    fun aFrameRefusedOnTheCeilingHoldsNothing() {
        val air = LoraAirtime()
        val pace = LoraPacePolicy(minGapMs = 0, airtime = air)
        val now = publicSpent(air)
        pace.enqueue(live("public", FrameClass.ROOM, parts = 1, ceiling = Ceiling.HALF, destination = Destination.Public))
        pace.enqueue(live("room", FrameClass.ROOM, parts = 1))
        assertEquals("the Knit post behind it goes", "room", pace.take(now)!!.label)
        assertEquals(1, pace.pending)
    }

    @Test
    fun aDmQueuedAheadOfTheHeldOneStillGoesAndOneBehindItWaits() {
        // FIFO within the class: the hold is on what dequeues after the held frame, not on the whole class.
        val air = LoraAirtime()
        val pace = LoraPacePolicy(minGapMs = 0, airtime = air)
        val now = oneSlotLeft(air)
        pace.enqueue(live("dm:short", FrameClass.DIRECT, parts = 1))
        pace.enqueue(live("dm:long", FrameClass.DIRECT, parts = 2))
        pace.enqueue(live("dm:later", FrameClass.DIRECT, parts = 1))
        assertEquals("dm:short", pace.take(now)!!.label)
        val after = LoraPacePolicy(minGapMs = 0, airtime = LoraAirtime())
        val then = oneSlotLeft(after.airtime)
        after.enqueue(live("dm:long", FrameClass.DIRECT, parts = 2))
        after.enqueue(live("dm:later", FrameClass.DIRECT, parts = 1))
        assertNull("the later DM would take the long one's air", after.take(then))
    }

    @Test
    fun nothingWaitsOnAFrameNoWindowWouldAdmit() {
        // Bigger than the whole allowance: holding the queue behind it would hold it forever.
        val air = LoraAirtime()
        val pace = LoraPacePolicy(minGapMs = 0, airtime = air)
        val now = oneSlotLeft(air)
        val parts = (air.allowanceMs() / air.timeOnAirMs(MeshtasticProto.MAX_PAYLOAD) + 1).toInt()
        pace.enqueue(live("huge", FrameClass.DIRECT, parts = parts))
        pace.enqueue(live("room", FrameClass.ROOM, parts = 1))
        assertEquals("room", pace.take(now)!!.label)
    }

    @Test
    fun aFrameHeldBehindAHeldDmIsReportedOnce() {
        val air = LoraAirtime()
        val pace = LoraPacePolicy(minGapMs = 0, airtime = air)
        val now = oneSlotLeft(air)
        pace.enqueue(live("dm", FrameClass.DIRECT, parts = 2))
        pace.enqueue(live("room", FrameClass.ROOM, parts = 1))
        assertNull(pace.take(now))
        assertEquals(2, pace.lastAirtimeHolds)
        assertNull(pace.take(now + 1))
        assertEquals("still waiting, no longer news", 0, pace.lastAirtimeHolds)
    }

    @Test
    fun aQueuedCopyOfTheSameFrameIsReplacedNotDoubled() {
        // A DM-form frame keys every copy the plane might queue on its id (`frameKey`): a newer one replaces the copy
        // still waiting rather than airing twice, and frees no slot of anything unrelated.
        val pace = LoraPacePolicy(minGapMs = 0)
        pace.enqueue(frame("dm:old", FrameClass.DIRECT, supersedes = "frame:x"))
        pace.enqueue(frame("room"))
        pace.enqueue(frame("dm:new", FrameClass.DIRECT, supersedes = "frame:x"))
        assertEquals("the older copy went, not the room post", 2, pace.pending)
        assertEquals(1, pace.lastSuperseded)
        assertEquals("dm:new", pace.take(0)!!.label)
        assertEquals("nothing else went with it", "room", pace.take(0)!!.label)
    }

    @Test
    fun aSupersessionIsNeverCountedAsADrop() {
        // `loraDroppedQueue` says the plane shed something it wanted. Nothing is lost here that the newer
        // frame does not already carry, so the newcomer must still report ACCEPTED.
        val pace = LoraPacePolicy(minGapMs = 0, queueCap = 2)
        pace.enqueue(frame("dm:old", FrameClass.DIRECT, supersedes = "frame:x"))
        pace.enqueue(frame("room"))
        assertEquals(
            "and a full queue makes room by superseding before it sheds",
            LoraPacePolicy.Admission.ACCEPTED,
            pace.enqueue(frame("dm:new", FrameClass.DIRECT, supersedes = "frame:x")),
        )
        assertEquals(listOf("dm:new", "room"), drain(pace))
    }

    @Test
    fun aHeldFrameIsReportedOnceAndNotOnEveryTake() {
        // The pacer re-asks the budget every few seconds; a counter built on that would report the clock.
        val air = LoraAirtime()
        val pace = LoraPacePolicy(minGapMs = 0, airtime = air)
        val now = publicSpent(air)
        val big = ByteArray(MeshtasticProto.MAX_PAYLOAD)
        pace.enqueue(OutboundFrame(listOf(big), "public", FrameClass.ROOM, Ceiling.HALF, Destination.Public))
        assertNull(pace.take(now))
        assertEquals(1, pace.lastAirtimeHolds)
        assertNull(pace.take(now + 3_000))
        assertEquals("still stuck, but no longer news", 0, pace.lastAirtimeHolds)
        assertEquals("and still queued, never dropped", 1, pace.pending)
    }

    @Test
    fun everyFrameMaySpendTheWholeAllowanceUnlessItsCallerSaysOtherwise() {
        assertEquals(Ceiling.FULL, OutboundFrame(listOf(ByteArray(1)), "dm", FrameClass.DIRECT).ceiling)
        assertEquals(Ceiling.HALF, OutboundFrame(listOf(ByteArray(1)), "public", FrameClass.ROOM, Ceiling.HALF).ceiling)
    }

    @Test
    fun aUnicastIsPricedUnsignedAndABroadcastAtItsPortsCliff() {
        // 2.8 signs a broadcast it originates, never a unicast (`Router::perhapsEncode`): the auto-reply is priced like
        // a compact DM, the Meshtastic room's post at the text port's cliff (ADR 2026-10.ug4a).
        fun at(destination: Destination) = OutboundFrame(listOf(ByteArray(1)), "x", FrameClass.ROOM, Ceiling.HALF, destination).signedUpTo
        assertEquals(LoraAirtime.UNSIGNED, at(Destination.Reply))
        assertEquals(MeshtasticProto.maxSignedPayload(MeshtasticProto.PORT_TEXT_MESSAGE), at(Destination.Public))
        assertEquals(MeshtasticProto.MAX_SIGNED_PAYLOAD, at(Destination.Knit))
        assertEquals(LoraAirtime.UNSIGNED, direct("dm").signedUpTo)
        // The auto-reply is a PKI unicast: the firmware adds its 12 B PKC overhead, priced at admission (and booking).
        val reply = OutboundFrame(listOf(ByteArray(149)), "reply", FrameClass.ROOM, Ceiling.HALF, Destination.Reply, to = 9u)
        assertEquals(listOf(149 + MeshtasticProto.PKC_OVERHEAD_BYTES), reply.admissionSizes())
        assertEquals(12, MeshtasticProto.PKC_OVERHEAD_BYTES)
        val public = OutboundFrame(listOf(ByteArray(149)), "public", FrameClass.ROOM, Ceiling.HALF, Destination.Public)
        assertEquals("a broadcast carries none", listOf(149), public.admissionSizes())
        assertEquals("nor does a compact DM, channel-encrypted on the Knit slot", listOf(1, 1, 1, 1, 1), direct("dm").admissionSizes())
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
            air.record(200, at)
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
    fun aNewFrameLiftsTheAirtimeDeferralSoALiveFrameIsNotHeldBehindIt() {
        val air = LoraAirtime()
        val pace = LoraPacePolicy(minGapMs = 0, airtime = air)
        val at = publicSpent(air)
        pace.enqueue(
            OutboundFrame(messages = listOf(ByteArray(200)), label = "public", ceiling = Ceiling.HALF, destination = Destination.Public),
        )
        assertNull(pace.take(at))
        assertTrue("deferred", pace.nextDueAt() > at)

        // A live frame spends the rest of the total, so the deferral the public post earned must not strand it.
        pace.enqueue(OutboundFrame(messages = listOf(ByteArray(200)), label = "dm", klass = FrameClass.DIRECT))
        assertEquals("dm", pace.take(at)?.label)
    }

    @Test
    fun takeIsNullWhenEmpty() {
        assertNull(LoraPacePolicy().take(10_000))
    }

    @Test
    fun evictOversizeShedsOnlyFramesWithAPartPastTheCap() {
        val pace = LoraPacePolicy()
        pace.enqueue(OutboundFrame(listOf(ByteArray(233), ByteArray(40)), "chunked-at-the-old-max", FrameClass.DIRECT))
        pace.enqueue(OutboundFrame(listOf(ByteArray(120)), "fits", FrameClass.ROOM))
        pace.enqueue(OutboundFrame(listOf(ByteArray(100), ByteArray(229)), "tail-too-big", FrameClass.ROOM))
        assertEquals(2, pace.evictOversize(228))
        assertEquals(1, pace.pending)
        assertEquals("fits", pace.take(now = 0L)?.label)
        assertEquals(0, pace.evictOversize(1))
    }

    /** feb2d5a4: a frame the board has taken part of finishes as it is — never superseded. */
    @Test
    fun aPartSentCopyIsNotSuperseded() {
        val pace = LoraPacePolicy(minGapMs = 0)
        pace.onQueueStatus(free = 16)
        val started = OutboundFrame(listOf(byteArrayOf(1), byteArrayOf(2)), "probe:x", FrameClass.DIRECT, supersedes = "frame:x")
        started.onPartSent()
        pace.enqueue(started)
        pace.enqueue(OutboundFrame(listOf(byteArrayOf(1)), "newer:x", FrameClass.DIRECT, supersedes = "frame:x"))
        assertEquals("the part-sent copy stayed", 0, pace.lastSuperseded)
        assertEquals(2, pace.pending)
    }

    /** xdm2: a frame put back after a busy board is the one that was here, not a newer snapshot — it supersedes nothing. */
    @Test
    fun aRequeuedFrameSupersedesNothing() {
        val pace = LoraPacePolicy(minGapMs = 0)
        pace.onQueueStatus(free = 16)
        pace.enqueue(frame("dm:v1", FrameClass.DIRECT, supersedes = "frame:x"))
        val taken = checkNotNull(pace.take(1_000_000L))
        pace.enqueue(frame("dm:v2", FrameClass.DIRECT, supersedes = "frame:x"))
        assertEquals(LoraPacePolicy.Admission.ACCEPTED, pace.requeue(taken))
        assertEquals("the newer copy survived the older one coming back", 0, pace.lastSuperseded)
        assertEquals(setOf("dm:v1", "dm:v2"), drain(pace).toSet())
    }

    @Test
    fun hasQueuedSeesAWaitingCopyByKey() {
        val pace = LoraPacePolicy(minGapMs = 0)
        assertFalse(pace.hasQueued("frame:x"))
        pace.enqueue(frame("probe", FrameClass.DIRECT, supersedes = "frame:x"))
        assertTrue(pace.hasQueued("frame:x"))
        assertFalse(pace.hasQueued("frame:y"))
    }

    /** A frame being written is off the queue but not gone: it still counts as queued until the pacer hears its end. */
    @Test
    fun anInFlightFrameStillCountsAsQueued() {
        val pace = LoraPacePolicy(minGapMs = 0)
        pace.onQueueStatus(free = 16)
        pace.enqueue(OutboundFrame(listOf(byteArrayOf(1)), "probe", FrameClass.DIRECT, supersedes = "frame:x"))
        val taken = checkNotNull(pace.take(1_000_000L))
        assertTrue("in flight", pace.hasQueued("frame:x"))
        pace.requeue(taken) // the board was busy: back in the queue, still one copy
        assertTrue(pace.hasQueued("frame:x"))
        val again = checkNotNull(pace.take(2_000_000L))
        pace.done(again)
        assertFalse("sent: gone", pace.hasQueued("frame:x"))
    }

    @Test
    fun aShedFrameIsHandedBackForTheCallerToAbandon() {
        val pace = LoraPacePolicy(minGapMs = 0, queueCap = 1)
        val room = frame("room")
        pace.enqueue(room)
        pace.enqueue(frame("dm", FrameClass.DIRECT))
        assertEquals(listOf(room), pace.drainShed())
        assertTrue(pace.drainShed().isEmpty())
    }

    /** A refused newcomer — enqueued or requeued — is handed back like a shed frame, so its caller can abandon it. */
    @Test
    fun aRefusedFrameIsHandedBackToo() {
        val pace = LoraPacePolicy(minGapMs = 0, queueCap = 1)
        pace.enqueue(frame("dm", FrameClass.DIRECT))
        val tick = direct("tick", DirectKind.RECEIPT)
        assertEquals(LoraPacePolicy.Admission.REFUSED, pace.enqueue(tick))
        assertEquals(listOf(tick), pace.drainShed())
        val requeued = direct("tick 2", DirectKind.RECEIPT)
        assertEquals(LoraPacePolicy.Admission.REFUSED, pace.requeue(requeued))
        assertEquals(listOf(requeued), pace.drainShed())
    }

    @Test
    fun airGivenBackEndsTheWindowDeferralAtOnce() {
        // A compact DM's ack refunds what it did not fly (ADR 2026-10.x9fa): the pacer must not sleep to the window's roll.
        val airtime = LoraAirtime()
        val pace = LoraPacePolicy(minGapMs = 0, airtime = airtime)
        val size = 200
        val ms = airtime.timeOnAirMs(size, LoraAirtime.UNSIGNED)
        while (airtime.admits(Ceiling.FULL, listOf(size), 0)) airtime.record(size, 0, LoraAirtime.UNSIGNED)
        pace.enqueue(OutboundFrame(listOf(ByteArray(size)), "held", FrameClass.DIRECT))
        assertNull(pace.take(1))
        assertTrue("deferred to the window's roll", pace.nextDueAt() > 1)
        airtime.refund(0, ms, 1)
        pace.onAirReturned()
        assertNotNull(pace.take(1))
    }

    // --- The shared-band brake (ADR 2026-10.ug4a) ---

    private fun knitRoomPost(label: String) = OutboundFrame(listOf(byteArrayOf(1)), label, FrameClass.ROOM)

    @Test
    fun aBusyBandHoldsAKnitRoomPostAndNothingElse() {
        val pace = LoraPacePolicy(minGapMs = 0)
        pace.onBandReading(LoraPacePolicy.BUSY_BAND_PERCENT, now = 0)
        assertTrue("40 % brakes, compared >= as the firmware's max_channel_util_percent is", pace.braked(0))
        pace.enqueue(knitRoomPost("room"))
        pace.enqueue(direct("dm"))
        pace.enqueue(direct("tick", DirectKind.RECEIPT))
        pace.enqueue(OutboundFrame(listOf(byteArrayOf(1)), "public", FrameClass.ROOM, Ceiling.HALF, Destination.Public))
        pace.enqueue(OutboundFrame(listOf(byteArrayOf(1)), "reply", FrameClass.ROOM, Ceiling.HALF, Destination.Reply, to = 9u))
        val sent = generateSequence { pace.take(1_000) }.map { it.label }.toList()
        assertEquals("a DM, a ✓✓, the Meshtastic room and the auto-reply all go", listOf("dm", "tick", "public", "reply"), sent)
        assertEquals("the Knit room post waits, queued", 1, pace.pending)
        assertEquals("a brake is not an airtime hold", 0, pace.lastAirtimeRefusals)
    }

    @Test
    fun aBrakedPostIsCountedOnceAndNeverAsHeldForAir() {
        val pace = LoraPacePolicy(minGapMs = 0)
        pace.onBandReading(40f, now = 0)
        pace.enqueue(knitRoomPost("room"))
        assertNull(pace.take(1))
        assertEquals(1, pace.lastBandBrakes)
        assertEquals(0, pace.lastAirtimeHolds)
        assertNull(pace.take(2))
        assertEquals("still braked, no longer news", 0, pace.lastBandBrakes)
    }

    @Test
    fun aBrakedOnlyQueueSleepsUntilTheReadingGoesStaleAndANewReadingWakesIt() {
        val pace = LoraPacePolicy(minGapMs = 0)
        pace.onBandReading(45f, now = 1_000)
        pace.enqueue(knitRoomPost("room"))
        assertNull(pace.take(2_000))
        assertTrue("not due now, so the drain loop suspends (${pace.nextDueAt()})", pace.nextDueAt() > 2_000)
        assertEquals("due when the reading ages out", 1_000 + LoraPacePolicy.BRAKE_FRESH_MS + 1, pace.nextDueAt())
        assertNull("still braked at the deadline's edge", pace.take(1_000 + LoraPacePolicy.BRAKE_FRESH_MS))

        // A new reading under the threshold is the event that lifts it sooner.
        pace.onBandReading(10f, now = 60_000)
        assertTrue(pace.nextDueAt() <= 60_000)
        assertEquals("room", pace.take(60_000)?.label)
    }

    @Test
    fun aStaleReadingDoesNotBrake() {
        val pace = LoraPacePolicy(minGapMs = 0)
        pace.onBandReading(80f, now = 0)
        pace.enqueue(knitRoomPost("room"))
        assertFalse(pace.braked(LoraPacePolicy.BRAKE_FRESH_MS + 1))
        assertEquals("room", pace.take(LoraPacePolicy.BRAKE_FRESH_MS + 1)?.label)
    }

    @Test
    fun noReadingAndAQuietBandDoNotBrake() {
        val pace = LoraPacePolicy(minGapMs = 0)
        assertFalse(pace.braked(0))
        pace.onBandReading(39.9f, now = 0)
        assertFalse("under 40 % does not brake", pace.braked(0))
        pace.onBandReading(30f, now = 0)
        assertFalse("nor does the polite 25 %, which stock firmware never applies to typed text", pace.braked(0))
        pace.onBandReading(null, now = 1)
        assertFalse("a reading without the figure changes nothing", pace.braked(1))
        pace.onBandReading(50f, now = 2)
        pace.forgetBand()
        assertFalse("the board is gone, and its reading with it", pace.braked(3))
    }

    @Test
    fun aDedicatedSlotIsNeverBraked() {
        // ADR 067 lifts manners on a slot of Knit's own: any congestion there is Knit's.
        val dedicated = LoraAirtime(dedicatedUnlocksDuty = true)
        val slot = LoraSlot.forRegion(LoraRegion.US, ModemPreset.LONG_FAST)!!
        dedicated.onRadioConfig(
            LoraRadioConfig(
                usePreset = true,
                modemPreset = ModemPreset.LONG_FAST,
                region = LoraRegion.US,
                hopLimit = 3,
                overrideDutyCycle = false,
                channelNum = slot,
            ),
        )
        assertTrue(dedicated.dedicated())
        val pace = LoraPacePolicy(minGapMs = 0, airtime = dedicated)
        pace.onBandReading(90f, now = 0)
        pace.enqueue(knitRoomPost("room"))
        assertFalse(pace.braked(0))
        assertEquals("room", pace.take(0)?.label)
    }

    @Test
    fun aBrakeAndASpentWindowTogetherWakeAtTheSoonerOfTheTwo() {
        val air = LoraAirtime()
        val pace = LoraPacePolicy(minGapMs = 0, airtime = air)
        var now = 0L
        while (air.admits(Ceiling.HALF, listOf(MeshtasticProto.MAX_PAYLOAD), now)) {
            air.record(MeshtasticProto.MAX_PAYLOAD, now)
            now += 3_000
        }
        pace.onBandReading(45f, now)
        pace.enqueue(knitRoomPost("room"))
        pace.enqueue(
            OutboundFrame(listOf(ByteArray(MeshtasticProto.MAX_PAYLOAD)), "public", FrameClass.ROOM, Ceiling.HALF, Destination.Public),
        )
        assertNull(pace.take(now))
        val release = checkNotNull(air.nextReleaseAt(now))
        assertEquals(minOf(release, now + LoraPacePolicy.BRAKE_FRESH_MS + 1), pace.nextDueAt())
    }

    @Test
    fun aNewFrameEndsTheBrakeDeferralSoADmIsNotHeldBehindABrakedPost() {
        val pace = LoraPacePolicy(minGapMs = 0)
        pace.onBandReading(45f, now = 0)
        pace.enqueue(knitRoomPost("room"))
        assertNull(pace.take(1))
        assertTrue(pace.nextDueAt() > 1)
        pace.enqueue(direct("dm"))
        assertEquals("dm", pace.take(1)?.label)
    }
}
