package app.getknit.knit.mesh.lora.meshcore

import app.getknit.knit.mesh.FastPathDrop
import app.getknit.knit.mesh.link.FastFrameCodec
import app.getknit.knit.mesh.lora.KnitChannel
import app.getknit.knit.mesh.lora.LoraFragCandidates
import app.getknit.knit.mesh.lora.LoraMeshTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The air's physics, on the real MeshCore stack over [FakeMeshcoreAir]'s channel (ADR 2026-11.gsqu's Stage 5c-2a
 * amendment): what the lab cannot show on the wall clock. Virtual time throughout, and the fake's own airtime, never
 * shortened: the per-side cap is a ratio of the window to it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MeshcoreAirPhysicsTest {
    private fun TestScope.rig(channel: MeshcoreChannel = MeshcoreChannel()): MeshcoreAirRig =
        MeshcoreAirRig(this, FakeMeshcoreAir(backgroundScope, channel) { testScheduler.currentTime })

    @Test
    fun theFakesAirtimeIsQ5sMeasurement() {
        // Q5: 60 × 181-B packets at SF7 / 62.5 kHz / CR 4/5, Δtx_air 38 s → 633 ± 17 ms each.
        val q5 = MeshcoreChannel(spreadFactor = 7, bandwidthHz = 62_500, codingRate = 5)
        val ms = q5.airtimeMs(181)
        assertTrue("$ms ms against 633 ± 17", ms in 616..650)
    }

    @Test
    fun aDmAndATwoPartPostCrossTheChannel() =
        runTest {
            val rig = rig()
            val a = rig.board("alice")
            val b = rig.board("bob")
            rig.air.link("alice", "bob")
            val alice = rig.node(a, listOf("bob"))
            val bob = rig.node(b, listOf("alice"))
            rig.dm(alice, bob, "dm-1")
            rig.settle(20_000)
            assertEquals("delivered: ${bob.logs.takeLast(20)}", 1, bob.delivered.size)
            alice.transport.fastFanout(rig.postBy(alice.id))
            rig.settle(20_000)
            assertEquals("the post: ${bob.logs.takeLast(20)}", 1, bob.decodedBy(alice.id))
        }

    /**
     * A forger's board: it reads the fragment id off each of the author's part 0s as it lands, and from then on airs
     * junk bound heads of the shortest form under that id — back to back as a [rogue] radio (no budget, no
     * listen-before-talk: its own hardware) or each after [gapMs] plus [gapAirtimes] of its own airtime idle — until [untilMs] or
     * [budget] parts. [copyFirst]: the first junk head copies the honest head's commitment. Every junk part differs, so
     * no ring drops one.
     */
    private class Forger(
        val air: FakeMeshcoreAir,
        val author: String,
        val name: String = "mallory",
        val rogue: Boolean = true,
        val gapAirtimes: Double = 0.0,
        val gapMs: Long = 0,
        var budget: Int = Int.MAX_VALUE,
        val copyFirst: Boolean = false,
    ) {
        val board = air.board(name).also { it.setChannel(MeshcoreAirRig.KNIT_SLOT, KnitChannel.NAME, KnitChannel.PSK) }
        private val client = FakeForeignClient(board)
        var fragId: Int? = null
        private var commitment: ByteArray? = null
        var untilMs = Long.MAX_VALUE
        var aired = 0
        private var n = 0
        private var running = false

        init {
            if (rogue) air.channel!!.rogue += name
            air.listeners += { a ->
                val data = a.data
                if (a.from == author && data != null && isHead(data)) {
                    fragId = ((data[1].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF)
                    commitment = data.copyOfRange(4, 4 + FastFrameCodec.COMMITMENT_BYTES)
                    if (!running) next()
                }
                if (a.from == name) {
                    aired++
                    val idle = gapMs + (a.airMs * gapAirtimes).toLong()
                    if (idle == 0L) next() else air.later(idle) { next() }
                }
            }
        }

        /** A bound part 0: the tag, and part index 0 in the header's high nibble. */
        private fun isHead(data: ByteArray): Boolean = data[0] == FastFrameCodec.TAG_BOUND_FRAG && (data[3].toInt() shr 4) == 0

        private fun next() {
            val id = fragId
            if (id == null || air.now() >= untilMs || n >= budget) {
                running = false
                return
            }
            running = true
            n++
            val copied = commitment.takeIf { copyFirst && n == 1 }
            val named =
                copied ?: java.nio.ByteBuffer
                    .allocate(8)
                    .putLong(0x5EED_0000L + n)
                    .array()
            val junk = byteArrayOf(FastFrameCodec.TAG_BOUND_FRAG, (id shr 8).toByte(), id.toByte(), 0x02) + named + byteArrayOf(n.toByte())
            check(junk.size == LoraFragCandidates.SHORTEST_FRAGMENT_BYTES)
            client.send(requireNotNull(MeshcoreProto.sendChannelData(MeshcoreAirRig.KNIT_SLOT, junk)))
        }
    }

    /** Alice, bob (the receiver R) and mallory, every one in range of the others. */
    private fun TestScope.forgedRoom(
        rogue: Boolean,
        gapAirtimes: Double = 0.0,
        gapMs: Long = 0,
    ): Triple<MeshcoreAirRig, Pair<MeshcoreAirRig.Node, MeshcoreAirRig.Node>, Forger> {
        val rig = rig()
        val a = rig.board("alice")
        val b = rig.board("bob")
        val forger = Forger(rig.air, author = "alice", rogue = rogue, gapAirtimes = gapAirtimes, gapMs = gapMs)
        rig.air.link("alice", "bob")
        rig.air.link("alice", "mallory")
        rig.air.link("bob", "mallory")
        val alice = rig.node(a, listOf("bob"))
        val bob = rig.node(b, listOf("alice"))
        return Triple(rig, alice to bob, forger)
    }

    /** The shortest bound part's time on the air, by the fake's own formula: 21 B as an unscoped flood. */
    private fun MeshcoreAirRig.shortestAirMs(preamble: Double? = null): Long =
        air.channel!!.airtimeMs(2 + 3 + MeshcoreChannel.aes(3 + LoraFragCandidates.SHORTEST_FRAGMENT_BYTES), preamble)

    @Test
    fun aForgerAtFullRateForTheWholeWindowNeverFillsASide() =
        runTest {
            // Design pass B1: the cap is what one receiver decodes in FRAG_TIMEOUT_MS at the shortest bound part's airtime.
            // A rogue radio airs junk heads under the honest id back to back from the moment part 0 lands, for the window
            // and one airtime more, while the author's part 1 is held: the memory bound alone is the oracle here. Its
            // hardware airs behind the shortest preamble a receiver locks on (6 symbols), the floor the plane's cap assumes.
            // Sized off the booked price (25 B, 341 ms) the cap was 177 against 197 parts at the firmware's own preamble,
            // and this failed with 24 replacements, each a 1-in-177 chance at the honest head.
            val (rig, nodes, forger) = forgedRoom(rogue = true)
            rig.air.channel!!.preambleFor = { if (it == "mallory") LOCK_PREAMBLE else null }
            val (alice, bob) = nodes
            rig.air.listeners += { a -> if (a.from == "alice" && a.type == "grp_data" && forger.fragId != null) rig.air.hold("alice") }
            alice.transport.fastFanout(rig.postBy(alice.id))
            rig.settle(1)
            val shortest = rig.shortestAirMs(LOCK_PREAMBLE)
            forger.untilMs = testScheduler.currentTime + 5_000 + LoraMeshTransport.FRAG_TIMEOUT_MS + shortest
            rig.settle(5_000 + LoraMeshTransport.FRAG_TIMEOUT_MS + 10_000)
            val decoded = rig.air.airings.count { it.from == "mallory" && "bob" in it.heardBy }
            assertTrue("the forger filled the window: $decoded at $shortest ms", decoded >= LoraMeshTransport.FRAG_TIMEOUT_MS / shortest)
            assertEquals("bob's board lost no junk part", 0, bob.board.offlineDropped)
            assertEquals("no side filled: $decoded junk heads decoded", 0L, bob.drops(FastPathDrop.FRAG_CANDIDATES_FULL))
            assertEquals("the held part 1 never aired", 0, bob.decodedBy(alice.id))
        }

    /**
     * Three two-part posts, 15 s apart, against a rogue forger idle [gapMs] after each of its own for the window and more:
     * which of them bob decoded, by post (seed 1 to 3). The first one's head starts the forger, so its part 1 airs in the
     * first gap; the others air under the running schedule.
     */
    private class UnderForger(
        /** Which post bob decoded, by post. */
        val landed: List<Boolean>,
        /** Honest parts lost at bob to an overlap. */
        val lost: Int,
        /** Whether each post's part 0 started within one CAD retry of a forger packet's end: right behind it, at a gap's start. */
        val atGapStart: List<Boolean>,
    ) {
        override fun toString(): String = "landed=$landed atGapStart=$atGapStart lost=$lost"
    }

    private fun TestScope.postsUnderAForger(gapMs: Long): UnderForger {
        val (rig, nodes, forger) = forgedRoom(rogue = true, gapMs = gapMs)
        val (alice, bob) = nodes
        forger.untilMs = testScheduler.currentTime + LoraMeshTransport.FRAG_TIMEOUT_MS + rig.shortestAirMs() + 30_000
        // Three: what the flood ceiling admits of 2.2-s posts in the hour (6.75 s at the 10 % band); a fourth waits.
        val posts = 3
        repeat(posts) { i ->
            alice.transport.fastFanout(rig.postBy(alice.id, seed = i + 1))
            rig.settle(15_000)
        }
        rig.settle(30_000)
        assertEquals("each one aired", posts, alice.logs.count { it == "lora tx fanout:chat parts=2" })
        assertTrue("the forger ran: ${forger.aired}", forger.aired > 20)
        assertEquals(0L, bob.drops(FastPathDrop.FRAG_CANDIDATES_FULL))
        assertEquals("no junk took a check", 0L, bob.drops(FastPathDrop.FRAG_UNVERIFIED))
        val landed = (1..posts).map { seed -> bob.logs.any { it.startsWith("lora rx ") && it.contains("-$seed from ") } }
        val lost = rig.air.airings.count { it.from == "alice" && it.type == "grp_data" && "bob" in it.lostAt }
        val heads =
            rig.air.airings
                .filter { it.from == "alice" && it.type == "grp_data" }
                .chunked(2)
                .map { it.first().atMs }
        val forgerEnds =
            rig.air.airings
                .filter { it.from == forger.name }
                .map { it.atMs + it.airMs }
        val atGapStart = heads.map { h -> forgerEnds.any { e -> h - e in 0..MeshcoreChannel.CAD_RETRY_MS } }
        return UnderForger(landed, lost, atGapStart)
    }

    @Test
    fun aRogueForgerIdleForOneHonestPartBetweenItsOwnCostsEveryPostUnderItsScheduleItsSecondPart() =
        runTest {
            // The split oracle's other half, as round 1 asked it and measured, not tuned: the forger leaves air one honest
            // part fits in — the longest honest part's airtime (181 B) and one CAD retry — against an author who listens
            // before talking. An overlap corrupts both packets. Post 1 lands only because its own head starts the forger, so
            // its part 1 airs in the first gap. Under the running schedule part 0 finds a gap and part 1, back to back
            // behind it, runs into the forger's next head: neither later post lands. So this is no rate, and a two-part
            // post needs a gap two parts long (the next case).
            val longest = MeshcoreChannel().airtimeMs(181)
            val r = postsUnderAForger(longest + MeshcoreChannel.CAD_RETRY_MS)
            assertEquals("post 1 lands; none aired under the running schedule does: $r", listOf(true, false, false), r.landed)
            assertTrue("each later post lost a part at least: $r", r.lost >= 2)
        }

    @Test
    fun aRogueForgerIdleForTwoHonestPartsBetweenItsOwnLeavesRoomForATwoPartPost() =
        runTest {
            // Round 2: the gap a two-part post needs — two of the longest honest part's airtime and one CAD retry. The
            // property, not a phase: a post whose part 0 starts right behind a forger packet (within one CAD retry: the author
            // listened, waited, and took the gap at its start) has the whole gap for both parts, and lands. A post queued in
            // the middle of a gap airs at once on the idle channel and may not fit what is left of it; that is luck of the
            // schedule, not air the forger withheld. And some post under the running schedule lands (post 1 starts it).
            val longest = MeshcoreChannel().airtimeMs(181)
            val r = postsUnderAForger(2 * longest + MeshcoreChannel.CAD_RETRY_MS)
            assertTrue("a post under the running schedule lands: $r", r.landed.drop(1).any { it })
            assertTrue("every post that took a gap at its start lands: $r", r.atGapStart.indices.all { !r.atGapStart[it] || r.landed[it] })
        }

    /** Alice and bob out of each other's range, a repeater between them; mallory in range of alice and bob. */
    private fun TestScope.behindARepeater(
        forger: (FakeMeshcoreAir) -> Forger,
    ): Triple<MeshcoreAirRig, Pair<MeshcoreAirRig.Node, MeshcoreAirRig.Node>, Forger> {
        val rig = rig()
        val a = rig.board("alice")
        val b = rig.board("bob")
        val f = forger(rig.air)
        rig.air.repeater("relay", "alice", "bob")
        rig.air.link("alice", "mallory")
        rig.air.link("bob", "mallory")
        val alice = rig.node(a, listOf("bob"))
        val bob = rig.node(b, listOf("alice"))
        return Triple(rig, alice to bob, f)
    }

    private fun overtake(
        copyFirst: Boolean,
        heads: Int,
    ) = runTest {
        // Design pass B4 (mesh-lab-reviewer), ordered by hold and release, never by a draw: the repeater holds both
        // honest parts while the forger, who heard part 0 straight from alice, lands its heads at bob first. The draw is
        // pinned at its maximum, the forger's best case, for whatever the release leaves to it.
        val (rig, nodes, _) = behindARepeater { Forger(it, author = "alice", budget = heads, copyFirst = copyFirst) }
        val (alice, bob) = nodes
        rig.air.channel!!.pinDraw = { it }
        rig.air.hold("relay")
        alice.transport.fastFanout(rig.postBy(alice.id))
        rig.settle(30_000)
        val junkAtBob = rig.air.airings.count { it.from == "mallory" && "bob" in it.heardBy }
        assertEquals("every fake reached bob first", heads, junkAtBob)
        assertEquals("nothing honest yet", 0, bob.decodedBy(alice.id))
        assertEquals(listOf("grp_data", "grp_data"), rig.air.parked("relay").map { it.type })
        rig.air.release("relay")
        rig.settle(30_000)
        assertEquals("the post lands", 1, bob.decodedBy(alice.id))
        assertEquals("no fake took a check", 0L, bob.drops(FastPathDrop.FRAG_UNVERIFIED))
        assertEquals(0L, bob.drops(FastPathDrop.FRAG_CANDIDATES_FULL))
        assertTrue("through the repeater", rig.air.airings.any { it.from == "relay" && it.type == "grp_data" && "bob" in it.heardBy })
    }

    @Test
    fun aCopiedHashHeadAheadOfTheHonestOneBehindARepeaterNeverTakesItsPairing() = overtake(copyFirst = true, heads = 1)

    @Test
    fun manyShortHeadsAheadOfTheHonestOneBehindARepeaterLeaveThePostToLand() = overtake(copyFirst = false, heads = 40)

    // --- the replay after the ring evicts (security (e)) ---

    @Test
    fun aStaleReplayReachesThePlaneOnceTheRingForgetsAndTheFreshnessGateStopsIt() =
        runTest {
            val rig = rig()
            val a = rig.board("alice")
            val b = rig.board("bob")
            val m = rig.air.board("mallory").also { it.setChannel(MeshcoreAirRig.KNIT_SLOT, KnitChannel.NAME, KnitChannel.PSK) }
            rig.air.link("alice", "bob")
            rig.air.link("alice", "mallory")
            rig.air.link("bob", "mallory")
            val alice = rig.node(a, listOf("bob"))
            val bob = rig.node(b, listOf("alice"))
            alice.transport.fastFanout(rig.postBy(alice.id))
            rig.settle(10_000)
            assertEquals(1, bob.decodedBy(alice.id))
            val captured =
                rig.air.airings
                    .filter { it.from == "alice" && it.type == "grp_data" }
                    .map { checkNotNull(it.data) }
            assertEquals(2, captured.size)
            val replay = FakeForeignClient(m)

            fun replayAll() = captured.forEach { replay.send(requireNotNull(MeshcoreProto.sendChannelData(MeshcoreAirRig.KNIT_SLOT, it))) }

            // Byte-identical while bob's ring still holds the original: every copy dropped by the board (Q10).
            val dups = rig.air.radio("bob").dups
            val zeroHop =
                bob.hooks.counters.zeroHopKnitPosts
                    .get()
            replayAll()
            rig.settle(10_000)
            assertEquals("the ring dropped both", dups + 2, rig.air.radio("bob").dups)
            assertEquals(
                zeroHop,
                bob.hooks.counters.zeroHopKnitPosts
                    .get(),
            )
            // Bob's board marks alice unreachable: her board goes dark and his DM to it goes unanswered.
            a.powered = false
            rig.dm(bob, alice, "dm-1")
            rig.settle(60_000)
            assertEquals(1, bob.transport.status.value.direct.unreachable)
            // Past the freshness window and the ring evicted (160 newer packets, or a reboot): the replay reaches the
            // plane — the counter that moved — and verifies, but is no hearing of alice's radio.
            rig.settle(app.getknit.knit.mesh.lora.LoraFramePolicy.FRESH_MS)
            rig.air.evictRing("bob")
            val rx = bob.logs.count { it.startsWith("lora rx ") }
            replayAll()
            rig.settle(10_000)
            assertEquals(
                "it reached bob's plane, live at zero hops",
                zeroHop + 2,
                bob.hooks.counters.zeroHopKnitPosts
                    .get(),
            )
            assertTrue("and the router", bob.logs.count { it.startsWith("lora rx ") } > rx)
            assertTrue("no hearing", bob.logs.none { it == "lora direct heard ${alice.id}" })
            assertEquals("the mark stands", 1, bob.transport.status.value.direct.unreachable)
        }

    // --- Q13 and drift ---

    /** Alice and bob in range, a repeater in range of both. */
    private fun TestScope.q13Pair(): Triple<MeshcoreAirRig, MeshcoreAirRig.Node, MeshcoreAirRig.Node> {
        val rig = rig()
        val a = rig.board("alice")
        val b = rig.board("bob")
        rig.air.link("alice", "bob")
        rig.air.repeater("relay", "alice", "bob")
        val alice = rig.node(a, listOf("bob"))
        val bob = rig.node(b, listOf("alice"))
        // Past the re-pin gap since the Ready's teach: a push inside it is coalesced (`MeshcorePins.REPIN_GAP_MS`), and the
        // field's teach is far older than one DM.
        rig.settle(MeshcorePins.REPIN_GAP_MS)
        return Triple(rig, alice, bob)
    }

    private fun MeshcoreAirRig.airedSince(t: Long): List<String> =
        air.airings.filter { it.atMs >= t }.map { "${it.from} ${it.type} ${if (it.flood) "flood" else "direct"}" }

    @Test
    fun aFloodedAckDrawsTheQ13PathTheReceiverRepinsAndTheNextDmGoesDirectBothWays() =
        runTest {
            val (rig, alice, bob) = q13Pair()
            // Bob's record of alice drifts to UNKNOWN, silently (RESET_PATH pushes nothing).
            bob.board.driftPath(alice.board)
            val pinsBefore = bob.pinsOf(alice.board).size
            val readsBefore = bob.readsOf(alice.board)
            val t0 = testScheduler.currentTime
            rig.dm(alice, bob, "dm-1")
            rig.settle(10_000)
            val aired = rig.airedSince(t0)
            assertEquals(
                "the DM direct, bob's ack flooded and its repeat, then alice's direct PATH",
                listOf("alice msg direct", "bob ack flood", "relay ack flood", "alice path direct"),
                aired.take(4),
            )
            val ack = rig.air.airings.first { it.atMs >= t0 && it.from == "bob" && it.type == "ack" }
            val path = rig.air.airings.first { it.atMs >= t0 && it.from == "alice" && it.type == "path" }
            val gap = path.atMs - (ack.atMs + ack.airMs)
            assertTrue("about 3 s after the ack landed: $gap", gap in 3_000L..3_500L)
            // 0x81 drew the session's pin job: it read the record back, found the PATH had already left it at zero hops, and
            // wrote nothing. A PATH that came through the repeater leaves one hop, and the job writes (the next case).
            assertEquals("bob's session read alice's record back after the push", readsBefore + 1, bob.readsOf(alice.board))
            assertEquals("and needed no write", pinsBefore, bob.pinsOf(alice.board).size)
            assertEquals("bob's record reads zero hops", 0, bob.board.contact(alice.board.key)?.outPathLen)
            assertEquals(
                "alice's own DMs never flooded",
                0L,
                alice.session.counters.directFlooded
                    .get(),
            )
            // Bob's ✓✓ reached alice by another plane: dm-1 leaves her source, so nothing resends it on bob's next hearing.
            alice.unacked.clear()
            val t1 = testScheduler.currentTime
            rig.dm(alice, bob, "dm-2")
            rig.settle(10_000)
            assertEquals("the next DM and its ack both direct", listOf("alice msg direct", "bob ack direct"), rig.airedSince(t1))
            assertEquals(2, bob.delivered.size)
        }

    @Test
    fun aQ13PathThatCameThroughTheRepeaterDriftsTheReceiverToOneHopAndItsRepinUndoesIt() =
        runTest {
            val (rig, alice, bob) = q13Pair()
            bob.board.driftPath(alice.board)
            // Alice misses bob's own copy of the flooded ack: she learns it through the repeater, at one hop.
            rig.air.lossy("bob", "alice") { it.type == "ack" }
            val pinsBefore = bob.pinsOf(alice.board).size
            rig.dm(alice, bob, "dm-1")
            rig.settle(10_000)
            val own = rig.air.airings.first { it.from == "bob" && it.type == "ack" }
            assertTrue("alice missed bob's own copy", "alice" in own.lostAt)
            assertTrue("and heard the relay's", rig.air.airings.any { it.from == "relay" && it.type == "ack" && "alice" in it.heardBy })
            assertTrue("then aired the Q13 PATH", rig.air.airings.any { it.from == "alice" && it.type == "path" && "bob" in it.heardBy })
            assertTrue("bob's session re-pinned: ${bob.pinsOf(alice.board)}", bob.pinsOf(alice.board).drop(pinsBefore).contains(0))
            assertEquals("back to zero hops", 0, bob.board.contact(alice.board.key)?.outPathLen)
        }

    @Test
    fun aSilentOneHopDriftWithTheRepeaterGoneCostsOneAttemptAndTheRepinDeliversTheNext() =
        runTest {
            // Design pass B3: the drift the fake could not show. Bob's record of alice reads one hop through the relay, and
            // nothing pushed; the relay is then powered off. The session believes its pin, so the first attempt goes direct
            // along the stale route and alice, in range, drops it (not the next hop). An attempt that drew no ack re-pins
            // before the next (round 1): the read finds one hop, the write puts it back to zero, and the second attempt
            // lands — one retry gap lost, no air spent on the re-pin.
            val (rig, alice, bob) = q13Pair()
            bob.board.driftPath(alice.board, "relay")
            rig.air.power("relay", on = false)
            val pinsBefore = bob.pinsOf(alice.board).size
            val t0 = testScheduler.currentTime
            rig.dm(bob, alice, "dm-1")
            rig.settle(60_000)
            val tries = rig.air.airings.filter { it.atMs >= t0 && it.from == "bob" && it.type == "msg" }
            assertEquals("two attempts", 2, tries.size)
            assertTrue("both direct, alice heard both", tries.none { it.flood } && tries.all { "alice" in it.heardBy })
            assertEquals("the first through the dead relay", 1, tries[0].hops)
            assertEquals("the second zero-hop", 0, tries[1].hops)
            assertTrue("the re-pin wrote zero hops: ${bob.pinsOf(alice.board)}", bob.pinsOf(alice.board).drop(pinsBefore).contains(0))
            assertEquals("delivered once", 1, alice.delivered.size)
            assertEquals("never marked unreachable", 0, bob.transport.status.value.direct.unreachable)
            assertEquals(0, bob.board.contact(alice.board.key)?.outPathLen)
        }

    @Test
    fun aDmRoutedThroughARepeaterLiftsNoRadioCount() =
        runTest {
            // Round 1's decision: through a repeater a direct DM lands reported `0xFF` (`MyMesh.cpp:453`), which
            // MeshcoreTraits.heardZeroHop still reads as zero hops (it trusts the pin) for lifting a mark — but a DM never
            // counts its sender's radio in `boardsHeard` on MeshCore, so an out-of-range peer is no heard board.
            val rig = rig()
            val a = rig.board("alice")
            val b = rig.board("bob")
            rig.air.repeater("relay", "alice", "bob")
            val alice = rig.node(a, listOf("bob"))
            val bob = rig.node(b, listOf("alice"))
            bob.board.driftPath(alice.board, "relay")
            rig.dm(bob, alice, "dm-1")
            rig.settle(10_000)
            assertEquals("delivered through the relay", 1, alice.delivered.size)
            assertTrue(rig.air.airings.any { it.from == "relay" && it.type == "msg" && !it.flood && "alice" in it.heardBy })
            assertEquals("no radio counted for it", 0, alice.transport.status.value.boardsHeard)
            // A zero-hop room post from bob would count his radio: carried over the relay, it is not one.
            bob.transport.fastFanout(rig.postBy(bob.id))
            rig.settle(20_000)
            assertEquals(1, alice.decodedBy(bob.id))
            assertEquals("a post through the relay counts none either", 0, alice.transport.status.value.boardsHeard)
        }

    private companion object {
        /** The fewest preamble symbols a LoRa receiver locks on: the plane's cap floor (`MeshcoreTraits.leastRoomAirMs`). */
        const val LOCK_PREAMBLE = 6.0
    }
}
