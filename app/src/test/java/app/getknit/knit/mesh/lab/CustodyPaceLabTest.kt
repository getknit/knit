package app.getknit.knit.mesh.lab

import app.getknit.knit.mesh.CarryLedger
import app.getknit.knit.mesh.CustodyPace
import app.getknit.knit.mesh.ForwardSync
import app.getknit.knit.mesh.link.FrameKey
import app.getknit.knit.mesh.protocol.WireCodec
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random

/**
 * Custody's answers released as the link drains, and its re-offer back-off, through the real stacks (the custody-carry-
 * policy contract's §2, §3, §6 and §7; ADR 2026-11.bmks). Every lab node cuts production's rounds (16 KiB over a lab link,
 * which custody reads as Bluetooth's) the moment its pipe holds less than a round for the peer: a plain pipe delivers as it
 * sends, so an answer goes in back-to-back rounds; a held pipe, an air link, a buffer below the count ([LabTransport.stackBelow])
 * or a wedged count ([LabTransport.countStuck]) holds the next round back. [LabTransport.awaitDigestsAnswered] waits for every
 * answer to end, so [settle] is the whole exchange. A lab clock jump never wakes the serve loop's sleep, so a scenario that
 * moves the clock with an answer open runs [LabNode.serveCustodyRounds] after its deliveries, and ticks with [LabNode.tick].
 *
 * A full store is filled with others' mail as a carrier's is ([carryLoad]): sealed DMs from free identities
 * ([LabSybil]) to recipients who never appear, written into custody past the router, each author's profile heard first
 * so the carrier holds its key.
 */
@RunWith(RobolectricTestRunner::class)
class CustodyPaceLabTest {
    private lateinit var lab: MeshLab

    @get:Rule
    val chaos = LabChaos.rule()

    @Before
    fun setUp() {
        lab = MeshLab()
    }

    @After
    fun tearDown() {
        lab.close()
    }

    /**
     * Below the cap, in rounds. Alice carries four rounds' worth of others' mail and Bob has never met her: the answer to his
     * link-up digest goes out in rounds of at most 16 KiB, each cut as the last drained, and ends with his store holding all
     * of it, so the two stores agree as ADR 006 promises below the cap.
     */
    @Test
    fun aNewcomerShortOfSeveralRoundsConvergesInRoundsOfARound() =
        runBlocking {
            val alice = lab.node("alice", PACED).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", PACED).apply { setDisplayName("Bob") }
            val load = (1..AUTHORS).flatMap { a -> carryLoad(alice, LabSybil(seed = 4_100 + a, name = "carried $a"), LOAD_EACH) }
            val bytes =
                alice
                    .custodySignedBytes()
                    .filterKeys { it in load }
                    .values
                    .sum()
            assertTrue("the load is several rounds: $bytes B", bytes > 2 * ROUND_BYTES)

            val before = alice.roundsCut()
            linkUp(alice, bob)
            val rounds = alice.roundsCut() - before
            assertTrue("alice answered $bytes B in $rounds rounds of at most $ROUND_BYTES B", rounds > 1 && rounds >= bytes / ROUND_BYTES)
            val missing = load.toSet() - bob.custodyIds()
            assertTrue("bob lacks ${missing.size} of the ${load.size} carried frames", missing.isEmpty())

            lab.awaitAcquainted(alice, bob)
            assertTrue(alice.sendDm(bob, "hello, newcomer"))
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { it.dmWith(if (it === alice) bob else alice) }
        }

    /**
     * At the cap, a pass. Alice and Bob each wrote the other while apart and each carries a full store of others' mail.
     * Alice's link to Bob takes what it is given and delivers none of it (the pipe holds it, as a radio that wrote and never
     * got it across): every DM for Bob is in the first round, ahead of all the carried mail (tier 2 before tier 4, §1), and
     * custody cuts rounds into the link only while it holds under one, so it stops with at most two on it. The link goes
     * down: those are all a cut loses, and the answer ends cut short. A link that counts no buffer below credits a round
     * at the first look that reads it under one (§3), so a first round shorter than a round on the wire is credited when the
     * second is cut, and Bob never got it (§9: such a credit runs a round ahead of the write). So custody credited at most
     * the first round, only when it cut a second, and never the last. The next link serves every one of them again: the
     * relink ends what was pending on its plane unjudged. The cap binds, so the oracle leaves custody parity out and
     * reads every store at or under its cap.
     */
    @Suppress("LongMethod") // one pass: the meeting, the tier order, the cut and its ledger, the relink and the oracle
    @Test
    fun atTheCapEveryDmForThePeerGoesFirstAndACutLosesOnlyWhatTheLinkHeld() =
        runBlocking {
            val limits = PACED.copy(custodyMaxRows = CAP)
            val alice = lab.node("alice", limits).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", limits).apply { setDisplayName("Bob") }
            meetAndPart(alice, bob)

            val dms =
                (1..DMS_EACH).map { i ->
                    assertTrue(alice.sendDm(bob, "alice while apart $i"))
                    alice.ownMessageId(alice.dmWith(bob), "alice while apart $i")
                }
            val loads =
                listOf(alice to 4_200, bob to 4_300).associate { (node, seed) ->
                    node to
                        (1..FULL_AUTHORS).flatMap { a -> carryLoad(node, LabSybil(seed = seed + a, name = "${node.name}'s $a"), FULL_EACH) }
                }
            for (n in listOf(alice, bob)) assertEquals("${n.name}'s store is full", CAP, n.custodyIds().size)
            assertTrue("alice still holds her own DMs at the cap", alice.custodyIds().containsAll(dms))

            val before = alice.roundsCut()
            val cutShort = alice.metrics.frames().custodyAnswersCutShort
            lab.link(alice, bob, arm = { alice.transport.hold(bob.transport) })
            // Custody stops cutting once the link holds a round; a look under the serve lock waits out a round being sent.
            lab.await(1) {
                if ((
                        alice.transport
                            .carrier(bob.nodeId)
                            ?.outbound
                            ?.unwritten ?: 0L
                    ) >= ROUND_BYTES
                ) {
                    1
                } else {
                    0
                }
            }
            alice.serveCustodyRounds()
            val parked = alice.transport.held(bob.transport).mapNotNull { WireCodec.decodeEnvelope(it.signed)?.id }
            val lastDm = parked.indexOfLast { it in dms }
            val firstLoad = parked.indexOfFirst { it in loads.getValue(alice) }
            assertTrue("alice's DMs to bob went after her carried mail: $parked", dms.all { it in parked } && lastDm < firstLoad)
            val rounds = alice.roundsCut() - before
            assertTrue("custody cut $rounds rounds into a link that held a round", rounds in 1L..2L)
            val lost = parked.filter { it in loads.getValue(alice) }
            val lostBytes =
                alice
                    .custodySignedBytes()
                    .filterKeys { it in lost }
                    .values
                    .sum()
            assertTrue("the link took more than two rounds: ${lost.size} frames, $lostBytes B", lostBytes <= 2 * ROUND_BYTES)

            // lab-lint: allow direct-disconnect — the held link's cut is the subject, and both ends' departure is awaited next
            alice.transport.disconnect(bob.transport)
            awaitDeparture(alice, bob)
            alice.serveCustodyRounds()
            assertTrue("the answer was never cut short", alice.metrics.frames().custodyAnswersCutShort > cutShort)
            val held = alice.custodyIds()
            val kept = lost.filter { it in held }
            val credited = kept.filter { alice.carrySpread(it) > 0 }
            // The pipe parks frames in the order written, so a credited first round is a head of the carried mail it lost.
            val head = if (rounds < 2) emptyList() else kept.take(credited.size)
            val creditedBytes =
                alice
                    .custodySignedBytes()
                    .filterKeys { it in credited }
                    .values
                    .sum()
            assertTrue(
                "alice credited bob with more than her first round, or with no second cut ($rounds rounds): $credited of $lost",
                credited == head && kept.lastOrNull() !in credited,
            )
            assertTrue("alice credited more than a round bob never got: $creditedBytes B", creditedBytes <= ROUND_BYTES)

            val relinked = alice.transport.sent.size
            linkUp(alice, bob)
            val again =
                alice.transport.sent
                    .drop(relinked)
                    .servedTo(bob)
                    .toSet()
            val stillHeld = alice.custodyIds()
            val notResent = (lost + dms).filter { it in stillHeld && it !in again }
            assertTrue("what the cut lost was not served again on the next link: $notResent", notResent.isEmpty())
            lab.assertConverged(listOf(alice, bob), atLeast = DMS_EACH, custody = false) { it.dmWith(if (it === alice) bob else alice) }
        }

    /**
     * A buffer below the link's count (section 5's stack model): Alice's link to Bob airs at Bluetooth 1M's file pace and
     * the head of its queue, 64 KiB deep, reads as handed down to a stack below, which the pipe estimates and drains at the
     * air's rate, as Bluetooth's plane does (`StackEstimate`). Custody cuts the next round when the link and its estimate
     * hold under a round, and credits a round at the first look that reads its own link under one. The link is cut with
     * rounds in the air: what custody credited and Bob never got is at most what the link held under its line, one round
     * (§9's "a credit can run ahead of the write by a frame or a round"), never the buffer the estimate stands for.
     */
    @Test
    fun aCutUnderABufferBelowTheCountCreditsNoMoreThanARoundUndelivered() =
        runBlocking {
            val alice = lab.node("alice", PACED).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", PACED).apply { setDisplayName("Bob") }
            meetAndPart(alice, bob)
            val load = (1..AUTHORS).flatMap { a -> carryLoad(alice, LabSybil(seed = 4_700 + a, name = "carried $a"), LOAD_EACH) }

            val before = alice.roundsCut()
            lab.link(alice, bob, air = LinkAir(bytesPerSecond = BLE_FILE_PACE)) {
                alice.transport.stackBelow(bob.transport, bytes = STACK_BYTES, estimated = true)
            }
            lab.await(STACK_ROUNDS) { (alice.roundsCut() - before).toInt() }
            // lab-lint: allow direct-disconnect — an air link, which `unlink` refuses; the cut with rounds in the air is the subject
            alice.transport.disconnect(bob.transport)
            awaitDeparture(alice, bob)
            alice.serveCustodyRounds()

            val delivered =
                alice.transport.sent
                    .servedTo(bob)
                    .toSet()
            val sizes = alice.custodySignedBytes()
            val creditedUndelivered = load.filter { it in sizes && it !in delivered && alice.carrySpread(it) > 0 }
            val bytes = creditedUndelivered.sumOf { sizes.getValue(it) }
            assertTrue(
                "alice credited ${creditedUndelivered.size} frames, $bytes B, bob never got: more than the round under the line",
                bytes <= ROUND_BYTES + 2 * LOAD_BYTES,
            )
            assertTrue("rounds were in the air at the cut", load.any { it !in delivered })

            linkUp(alice, bob)
            assertTrue(alice.sendDm(bob, "after the cut"))
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { it.dmWith(if (it === alice) bob else alice) }
        }

    /**
     * A link whose count stays at 32 KB (a writer wedged, [LabTransport.countStuck]): custody cuts the digest's first round,
     * which it always does, and no other, since the link never reads under one; it credits nothing, since its own link never
     * reads under a round; and 120 s on ([ForwardSync.ANSWER_MAX_AGE_MS]) the answer ends cut short, after which a look reads
     * no answer to Bob. Once the count is lifted, Bob's next offer gets the rest.
     */
    @Test
    fun aLinkStuckAtTwoRoundsGetsOneRoundAndTheAnswerAgesOutUncredited() =
        runBlocking {
            val alice = lab.node("alice", PACED).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", PACED).apply { setDisplayName("Bob") }
            meetAndPart(alice, bob)
            val load = (1..AUTHORS).flatMap { a -> carryLoad(alice, LabSybil(seed = 4_800 + a, name = "carried $a"), LOAD_EACH) }

            val before = alice.roundsCut()
            val cutShort = alice.metrics.frames().custodyAnswersCutShort
            lab.link(alice, bob) { alice.transport.countStuck(bob.transport, STUCK_BYTES) }
            lab.await(1) { (alice.roundsCut() - before).toInt() }
            bob.transport.awaitInboundDrained()
            alice.serveCustodyRounds()
            assertEquals("one round into a link stuck at two", 1L, alice.roundsCut() - before)
            val first =
                alice.transport.sent
                    .servedTo(bob)
                    .filter { it in load }
            assertTrue("the first round carried the carried mail", first.isNotEmpty())

            lab.clock.advance(ForwardSync.ANSWER_MAX_AGE_MS)
            alice.serveCustodyRounds()
            assertTrue("the answer never aged out cut short", alice.metrics.frames().custodyAnswersCutShort > cutShort)
            assertFalse("an answer is still open to bob", alice.answering(bob.nodeId))
            assertTrue(
                "alice credited a round her link never drained: ${first.filter { alice.carrySpread(it) > 0 }}",
                first.none {
                    alice.carrySpread(it) >
                        0
                },
            )
            alice.serveCustodyRounds()
            assertEquals("a look cut a round after the answer ended", 1L, alice.roundsCut() - before)

            alice.transport.countStuck(bob.transport, 0)
            bob.reoffer()
            settle(alice, bob)
            assertTrue("bob lacks the rest once the link drains", bob.custodyIds().containsAll(load))
            assertTrue(alice.sendDm(bob, "unstuck"))
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { it.dmWith(if (it === alice) bob else alice) }
        }

    /**
     * A file comes first (§2, contract revision 16). Alice and Bob are linked, and Alice is streaming Bob a photo (its file
     * held on the link, moving by the blob plane's rule until it would stall) when Bob's digest arrives, while Alice carries
     * others' mail Bob lacks and one DM for him. While the file moves, the answer's rounds carry only frames addressed to
     * Bob: the DM crosses and none of the carried mail does. The file lands, the rest follows, one copy of the photo crossed
     * the link, and the two converge.
     */
    @Test
    fun whileAPhotoStreamsARoundCarriesOnlyThePeersOwnMail() =
        runBlocking {
            val limits = PACED.copy(feedStallMs = ForwardSync.ANSWER_MAX_AGE_MS)
            val alice = lab.node("alice", limits).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", limits).apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            lab.befriend(alice, bob)
            settle(alice, bob)
            val load = (1..AUTHORS).flatMap { a -> carryLoad(alice, LabSybil(seed = 4_900 + a, name = "carried $a"), LOAD_EACH) }
            val forBob = carryLoad(alice, LabSybil(seed = 4_950, name = "writer"), 1, to = bob.nodeId)

            alice.transport.holdFiles(bob.transport)
            assertTrue(alice.sendImage(Random(PHOTO_SEED).nextBytes(PHOTO_BYTES), "the view", to = bob))
            val photo = alice.ownMessageId(alice.dmWith(bob), "the view")
            val hash = checkNotNull(alice.attachmentHash(alice.dmWith(bob), photo))
            lab.await(1) { alice.transport.heldFiles(bob.transport).size }
            assertTrue(
                "the file is moving on the link",
                alice.transport
                    .carrier(bob.nodeId)
                    ?.outbound
                    ?.fileMoving == true,
            )

            bob.reoffer()
            lab.await(1) {
                alice.transport.sent
                    .servedTo(bob)
                    .count { it in forBob }
            }
            repeat(2) { alice.serveCustodyRounds() }
            assertEquals(
                "carried mail crossed while the file was on the link",
                0,
                alice.transport.sent
                    .servedTo(bob)
                    .count { it in load },
            )

            alice.transport.releaseFiles(bob.transport)
            lab.await(1) { if (bob.blobs.exists(hash)) 1 else 0 }
            settle(alice, bob)
            assertTrue("bob lacks carried mail once the file landed", bob.custodyIds().containsAll(load))
            assertEquals("copies of the photo on the link", 1, alice.transport.files.count { it == "${bob.nodeId} ATTACHMENT $hash" })
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { it.dmWith(if (it === alice) bob else alice) }
        }

    /**
     * Two full stores linked for long cross each frame once a lapse and back off unless an offer draws (the supervisor's
     * addition, the contract's revision 9; knit-ios runs the same case). After the first exchange a frame one store took
     * and evicted in its own write is marked not kept by the other's memo, and a release is pending until judged, so:
     *
     * - **(a)** no frame is released to the same peer twice inside one [CarryLedger.NOT_KEPT_LAPSE_MS], either way (a
     *   write the link's own memo skipped as a repeat counts as a release, since that is what custody's memo bounds),
     *   unless the releaser kept that frame as a new row between the two: §3's "entries die with their row", so a row its
     *   holder's own write evicted at the cap and the peer then re-served is new to the memo, and pending nothing (the
     *   chaos sweep's seeds 639592878 and 639593000). "Between" is the node's own order of events (`LabTransport.order`,
     *   shared with its custody gate): a write numbered when it was sent, a keep when the store took the row;
     * - **(b)** no later lapse sets more not-kept marks than the first: a lapse re-releases at most what was found not
     *   kept, once (§6; §9's "a frame the peer would evict on arrival still crosses once per lapse");
     * - **(c)** each side's offer gaps follow §7: back to 60 s only once custody kept a frame from that neighbour, and with
     *   none kept 60, 120, 240, 480, then 600 s and staying there, never past `REOFFER_MAX_MS` and its tolerance.
     *
     * Not that every schedule reaches the 600 s gap: a mark's lapse ends inside the 480 s gap, so the offer after it
     * re-releases, and whether the peer keeps some of that depends on its ledger (§9, "a full pair's offers don't always
     * reach the longest gap"; the lab's seeds 543869138, 296711625, 296711629, 296711630 and 296711636).
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod") // fifty minutes of ticks, and what each one released and kept
    @Test
    fun twoLinkedFullStoresReleaseOnceALapseAndBackOffUnlessAnOfferDraws() =
        runBlocking {
            val limits = PACED.copy(custodyMaxRows = SETTLE_CAP)
            val alice = lab.node("alice", limits).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", limits).apply { setDisplayName("Bob") }
            val two = listOf(alice, bob)
            meetAndPart(alice, bob)
            for ((node, seed) in listOf(alice to 4_400, bob to 4_500)) {
                (1..AUTHORS).forEach { a -> carryLoad(node, LabSybil(seed = seed + a, name = "${node.name}'s $a"), SETTLE_EACH) }
                assertEquals("${node.name}'s store is full", SETTLE_CAP, node.custodyIds().size)
            }
            val peerOf = mapOf(alice to bob, bob to alice)

            // Per node: its offers' lab times (the link-up digest first), the lab times of the steps in which it kept a frame
            // from its peer (each step's keeps come after its tick's offers), per frame the windows (from, to] in which it
            // released that frame to its peer with the write's number in the node's order, and per frame the numbers of the
            // writes that kept it as a new row.
            val offers = two.associateWith { mutableListOf<Long>() }
            val keeps = two.associateWith { mutableListOf<Long>() }
            val releases = two.associateWith { HashMap<String, MutableList<LongArray>>() }
            val keptAt = two.associateWith { HashMap<String, MutableList<Long>>() }
            val keptSeen = two.associateWith { it.custodyGate.keptFrom.size }.toMutableMap()
            val writesSeen = two.associateWith { it.transport.writes.size }.toMutableMap()
            val carried = carriedKeys.values.toSet()

            // What a step [from, to] at tick [tickAt] released and kept, read off each node's logs since the last step.
            fun record(
                tickAt: Long,
                from: Long,
                to: Long,
            ) {
                for (n in two) {
                    val peer = peerOf.getValue(n)
                    val kept = n.custodyGate.keptFrom.drop(keptSeen.getValue(n))
                    kept.forEach { w -> keptAt.getValue(n).getOrPut(w.id) { mutableListOf() } += w.seq }
                    if (kept.any { it.source == peer.nodeId }) keeps.getValue(n) += tickAt
                    // What crossed to the peer, and the carried frames the link's memo skipped as repeats.
                    n.transport.writes
                        .drop(writesSeen.getValue(n))
                        .filter { w -> peer.nodeId.startsWith(w.to) && (!w.skipped || w.id in carried) }
                        .forEach { w -> releases.getValue(n).getOrPut(w.id) { mutableListOf() } += longArrayOf(from, to, w.seq) }
                    keptSeen[n] = n.custodyGate.keptFrom.size
                    writesSeen[n] = n.transport.writes.size
                }
            }

            val linkedAt = lab.clock.now()
            two.forEach { offers.getValue(it) += linkedAt }
            linkUp(alice, bob)
            val start = lab.clock.now()
            record(linkedAt, linkedAt, start)

            val notKept = mutableListOf<Pair<Long, Long>>()
            val held = two.associateWith { it.metrics.frames().custodyReoffersHeld }
            var tick = 0
            while (true) {
                val due = start + (++tick) * ForwardSync.REOFFER_MS
                if (due > start + SETTLE_MS) break
                val from = lab.clock.now()
                lab.clock.advance(maxOf(0, due - from))
                val tickAt = lab.clock.now()
                for (n in two) {
                    val digests = n.transport.digestsSent.size
                    n.tick()
                    if (n.transport.digestsSent.size > digests) offers.getValue(n) += tickAt
                }
                settle(alice, bob)
                record(tickAt, from, lab.clock.now())
                notKept += lab.clock.now() to two.sumOf { it.metrics.frames().custodyNotKept }
            }

            // (a) A pair of releases [a] then [b] inside a lapse, unless custody kept the frame as a new row between them.
            for ((n, byId) in releases) {
                val keptBy = keptAt.getValue(n)
                val repeats =
                    byId.filter { (id, w) ->
                        // In the node's order: a write is logged after its send's lag, so the log's order can differ.
                        w.sortedBy { it[2] }.zipWithNext().any { (a, b) ->
                            b[1] - a[0] < CarryLedger.NOT_KEPT_LAPSE_MS && keptBy[id].orEmpty().none { it > a[2] && it < b[2] }
                        }
                    }
                val story =
                    repeats.map { (id, w) ->
                        "$id released at ${w.sortedBy {
                            it[2]
                        }.map { "#${it[2]} in ${(it[0] - linkedAt) / 1_000}-${(it[1] - linkedAt) / 1_000} s" }}, " +
                            "kept at ${keptBy[id].orEmpty().map { "#$it" }}"
                    }
                assertTrue("${n.name} released frames to ${peerOf.getValue(n).name} twice inside a lapse: $story", repeats.isEmpty())
            }
            // (b)
            val firstMark = notKept.firstOrNull { it.second > 0 }?.first ?: start
            val perLapse =
                notKept
                    .groupBy { (at, _) -> (at - firstMark).coerceAtLeast(0) / CarryLedger.NOT_KEPT_LAPSE_MS }
                    .toSortedMap()
                    .values
                    .map { window -> window.last().second }
                    .let { ends -> ends.mapIndexed { i, end -> end - if (i == 0) 0 else ends[i - 1] } }
            assertTrue(
                "a later lapse set more not-kept marks than the first ($perLapse a lapse): $notKept",
                perLapse.drop(1).all { it <= perLapse.first() },
            )
            // (c) Offer k's gap after k − 1: a keep after offer k − 2 shortens it or holds it at 60 s (an offer that drew judges
            // the next interval 60 s); without one it doubles from the gap before, up to the ceiling. The link-up's first gap
            // waited on the meeting's rounds, so it is held to the ceiling only.
            for ((n, times) in offers) {
                val kept = keeps.getValue(n)
                val gaps = times.zipWithNext { a, b -> b - a }
                val story =
                    "offers at ${times.map { (it - linkedAt) / 1_000 }} s, kept from ${peerOf.getValue(n).name} at " +
                        "${kept.map { (it - linkedAt) / 1_000 }} s"
                gaps.forEachIndexed { k, gap ->
                    assertTrue("${n.name}'s offer gap $gap ms is past the ceiling: $story", gap <= ForwardSync.REOFFER_MAX_MS + SLACK_MS)
                    if (k == 0) return@forEachIndexed
                    val doubled = minOf(2 * gaps[k - 1], ForwardSync.REOFFER_MAX_MS)
                    val drew = kept.any { it >= times[maxOf(0, k - 1)] && it < times[k + 1] }
                    if (drew) {
                        assertTrue("${n.name}'s gap $gap ms passed the back-off's $doubled ms: $story", gap <= doubled + SLACK_MS)
                    } else {
                        assertTrue(
                            "${n.name}'s gap $gap ms is not the back-off's $doubled ms, with nothing kept: $story",
                            gap in doubled - SLACK_MS..doubled + SLACK_MS,
                        )
                    }
                }
                assertTrue("${n.name} held no offer back", n.metrics.frames().custodyReoffersHeld > held.getValue(n))
            }

            assertTrue(alice.sendDm(bob, "still here"))
            lab.assertConverged(listOf(alice, bob), atLeast = 1, custody = false) { it.dmWith(if (it === alice) bob else alice) }
        }

    /**
     * A proven answer survives an unproven claim (the B2 security and blobs reviews). Alice holds three rounds of DMs for
     * Bob. His proven link's digest starts her answer, and the link holds what she cuts, so the answer is still open, its
     * rounds to go, when a digest from a link that proved nothing, claiming Bob's id and listing everything Alice holds,
     * arrives. It is answered and leaves nothing behind (§3, §6): it neither replaces the digest Bob's rounds are cut from
     * nor tells Alice he holds what he lacks, so once the link delivers Bob still gets every DM for him in the answer's own
     * rounds, with no re-offer between. Below the cap, so the stores agree.
     *
     * The held round is the sync: the claim once followed the link-up's waits, which for a node with a board returned
     * before Bob's digest, so the claim came first and the read came with the answer's rounds still being cut (CI job
     * 14558, `test:mesh-lab-boards`), and which on a plain pipe let the answer end before the claim, so it was never tested.
     */
    @Test
    fun aProvenAnswerSurvivesADigestClaimedOverALinkThatProvedNothing() =
        runBlocking {
            val alice = lab.node("alice", PACED).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", PACED).apply { setDisplayName("Bob") }
            meetAndPart(alice, bob)
            val bodies = (1..CLAIM_DMS).map { i -> "while apart $i " + PAD.repeat(PAD_REPEATS) }
            val dms =
                bodies.map { body ->
                    assertTrue(alice.sendDm(bob, body))
                    alice.ownMessageId(alice.dmWith(bob), body)
                }
            val bytes =
                alice
                    .custodySignedBytes()
                    .filterKeys { it in dms }
                    .values
                    .sum()
            assertTrue("the DMs for bob are more than two rounds: $bytes B", bytes > 2 * ROUND_BYTES)

            lab.link(alice, bob, arm = { alice.transport.hold(bob.transport) })
            // Bob's digest has opened Alice's answer: its first round is on the held link, and custody cuts no more into it.
            lab.await(1) { alice.transport.held(bob.transport).count { WireCodec.decodeEnvelope(it.signed)?.id in dms } }
            assertTrue("alice's answer to bob ended with the link holding it", alice.transport.answersLive())
            val handled = alice.transport.digestsReceived
            alice.transport.hearClaimedDigest(bob.nodeId, alice.custodyIds().toList())
            lab.await(1) { if (alice.transport.digestsHandled > handled) 1 else 0 }
            assertTrue("the claim ended alice's answer to bob", alice.transport.answersLive())
            alice.transport.release(bob.transport)
            settle(alice, bob)
            val read = bob.decrypted(bob.dmWith(alice)).map { it.first }.toSet()
            val missing = dms.filterNot { it in read }
            assertTrue("bob never got ${missing.size} of alice's ${dms.size} DMs in her answer's rounds", missing.isEmpty())
            lab.assertConverged(listOf(alice, bob), atLeast = CLAIM_DMS) { it.dmWith(if (it === alice) bob else alice) }
        }

    /**
     * A relink with the answer open (the B2 blobs review). Alice holds DMs for Bob from three free identities whose profiles
     * her sender quota evicted, so each goes with its sender's key ahead (ADR 2026-09.g64k), and two of her own. The link
     * takes the first rounds and never delivers them, and is replaced with no clock move. A release over the old link may
     * have died with it: the answer forgets what it sent there, keys included, and the memo ends what was pending on it
     * (§6), so Bob gets every DM for him and every key on the new link.
     */
    @Test
    fun aRelinkWithTheAnswerOpenStillDeliversThePeersDmsAndKeys() =
        runBlocking {
            val limits = PACED.copy(custodyMaxPerSender = RELINK_QUOTA)
            val alice = lab.node("alice", limits).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", limits).apply { setDisplayName("Bob") }
            meetAndPart(alice, bob)
            val sybils = (1..AUTHORS - 1).map { LabSybil(seed = 4_600 + it, name = "writer $it") }
            val forBob = sybils.flatMap { carryLoad(alice, it, RELINK_QUOTA, to = bob.nodeId) }
            for (s in sybils) {
                assertTrue("alice no longer carries ${s.nodeId.take(6)}'s profile", alice.custodiedProfileOf(s.nodeId).isEmpty())
            }
            val own =
                (1..2).map { i ->
                    assertTrue(alice.sendDm(bob, "own $i"))
                    alice.ownMessageId(alice.dmWith(bob), "own $i")
                }

            lab.link(alice, bob, arm = { alice.transport.hold(bob.transport) })
            lab.await(1) { alice.transport.held(bob.transport).count { WireCodec.decodeEnvelope(it.signed)?.id in forBob } }
            lab.unlink(alice, bob)
            linkUp(alice, bob)
            val lacking = forBob.toSet() - bob.custodyIds()
            assertTrue("bob lacks ${lacking.size} of the ${forBob.size} DMs for him", lacking.isEmpty())
            val unpinned = sybils.filter { bob.pinned(it.nodeId) == null }.map { it.nodeId.take(6) }
            assertTrue("bob never got the keys of $unpinned", unpinned.isEmpty())
            lab.assertConverged(listOf(alice, bob), atLeast = own.size) { it.dmWith(if (it === alice) bob else alice) }
        }

    /**
     * Takes [count] sealed DMs from [sybil] to [to] (a recipient who never appears, by default) into [node]'s custody, past
     * its router: [node] hears the author's profile first, as a carrier that met them would, and pins it. Each DM's `signed`
     * is [LOAD_BYTES] long, stamped a millisecond apart and after the profile. Returns their ids, whether or not custody
     * kept them all.
     */
    private suspend fun carryLoad(
        node: LabNode,
        sybil: LabSybil,
        count: Int,
        to: String = LabSybil(seed = sybil.nodeId.hashCode(), name = "nobody").nodeId,
    ): List<String> {
        val at = lab.clock.now() - count - 1
        val profile = sybil.profile(at)
        node.transport.hearPage(profile, checkNotNull(WireCodec.decodeEnvelope(profile.signed)))
        lab.await(1) { if (node.pinned(sybil.nodeId) != null) 1 else 0 }
        return (1..count).map { i ->
            val dm = sybil.dm(to, sentAt = at + i, signedBytes = LOAD_BYTES)
            node.carry(dm)
            checkNotNull(WireCodec.decodeEnvelope(dm.signed)).id.also { id -> FrameKey.ofSigned(dm)?.let { carriedKeys[it] = id } }
        }
    }

    // The crossing key of every frame [carryLoad] made, to its id: what a link memo's skipped write names.
    private val carriedKeys = HashMap<String, String>()

    /**
     * [a] and [b] meet, become contacts, finish answering each other and part: what a pass's two walkers did before. The
     * meeting's answers end while linked ([settle]), so none is still open when the scenario links them again.
     */
    private suspend fun meetAndPart(
        a: LabNode,
        b: LabNode,
    ) {
        lab.link(a, b)
        lab.awaitAcquainted(a, b)
        lab.befriend(a, b)
        settle(a, b)
        lab.unlink(a, b)
    }

    /**
     * Links [a] and [b] over a plain pipe and waits out the link-up's own exchange: both newcomer passes ran (each node was
     * handed the other's digest), both digests are answered and their answers ended, and what crossed is handled. The
     * digests are counted, not read off [LabTransport.awaitNeighborsHandled]: for a node with a board that returns before
     * its manager's pass (the composite re-publishes the link set on a collector of its own), and [settle] then found
     * nothing handed yet and returned with the exchange still to come (`test:mesh-lab-boards`, CI job 14558).
     */
    private suspend fun linkUp(
        a: LabNode,
        b: LabNode,
    ) {
        val nodes = listOf(a, b)
        val before = nodes.associateWith { it.transport.digestsReceived }
        lab.link(a, b)
        val handed = lab.tryAwait(1) { if (nodes.all { it.transport.digestsReceived > before.getValue(it) }) 1 else 0 }
        assertTrue(
            "the link-up's digests never came: ${nodes.map { "${it.name} ${before.getValue(it)}→${it.transport.digestsReceived}" }}",
            handed,
        )
        nodes.forEach { it.transport.awaitNeighborsHandled() }
        settle(a, b)
    }

    /**
     * Waits until both ends' managers acted on a link [LabTransport.disconnect] cut, as [MeshLab.unlink] does for the links
     * it may cut: the radios' collectors saw it, and with a board each composite's merged link set, which the manager reads
     * and which conflates on a collector of its own. Read before that, the answer was not yet lost to the link, and a relink
     * inside the window was no newcomer to the manager, so no digest crossed (`test:mesh-lab-boards` under chaos).
     */
    private suspend fun awaitDeparture(vararg nodes: LabNode) {
        nodes.forEach { it.transport.awaitNeighborsObserved() }
        nodes.forEach { it.awaitBoardSawLinks() }
        nodes.forEach { it.awaitLinksActedOn() }
    }

    /**
     * Waits until every digest the nodes were handed is answered, every answer they opened has ended, and every frame is
     * handled, again until a pass hands none of them a new frame or digest: on an air link a digest still airing is not
     * counted in yet, so one pass can return before its answer is opened.
     */
    private suspend fun settle(vararg nodes: LabNode) {
        val handed = { nodes.sumOf { it.transport.framesReceived + it.transport.digestsReceived } }
        do {
            val before = handed()
            nodes.forEach { it.transport.awaitDigestsAnswered() }
            nodes.forEach { it.transport.awaitInboundDrained() }
        } while (handed() != before)
    }

    private fun LabNode.roundsCut(): Long = metrics.frames().custodyRoundsCut

    /** The ids of the frames in these `sent` lines that crossed to [to], in the order they landed. */
    private fun List<String>.servedTo(to: LabNode): List<String> =
        mapNotNull { line ->
            val cols = line.split(' ')
            cols.getOrNull(3)?.takeIf { to.nodeId.startsWith(cols[1]) }
        }

    /** The ids of the profile frames from [sender] this node holds in custody. */
    private suspend fun LabNode.custodiedProfileOf(sender: String): List<String> =
        custodyFrames().filter { it.split(':').let { f -> f[1] == "profile" && sender.startsWith(f[2].substringBefore('→')) } }

    private companion object {
        // The wall-clock re-offer loop pushed a day out, so [LabNode.tick] and [LabNode.reoffer] are a scenario's only ticks:
        // at 60 s it fired beside the lab clock's, stamping an offer between a jump and a tick, or sending a digest mid-cut.
        val PACED = LabLimits(custodyReofferBackoff = true, reofferIntervalMs = 24 * 60 * 60_000L)

        // How far an offer gap may sit from the back-off's: the scenario's ticks are a minute apart in lab time, and a step's
        // own rounds and real time move the clock by seconds at most.
        const val SLACK_MS = 5_000L
        const val ROUND_BYTES = CustodyPace.BLUETOOTH_ROUND_BYTES

        // The stack model: Bluetooth 1M's configured file pace, a stack two rounds deep and more, and the rounds the cut
        // waits for so some are in the air.
        const val BLE_FILE_PACE = 8_192L
        const val STACK_BYTES = 65_536L
        const val STACK_ROUNDS = 3

        // A count wedged at two rounds.
        const val STUCK_BYTES = 2L * CustodyPace.BLUETOOTH_ROUND_BYTES

        // A photo of a few rounds, so its file is a stream of its own on the link.
        const val PHOTO_BYTES = 40_000
        const val PHOTO_SEED = 11

        // A carried DM's `signed` length: about a long message, so a round holds about sixteen.
        const val LOAD_BYTES = 1_000
        const val AUTHORS = 4
        const val LOAD_EACH = 16

        // At the cap: six authors of seventeen rows each (a profile and sixteen DMs) into a hundred, so each author's bucket
        // stays well above a node's own four DMs and its profile, which the cap therefore never takes, and the carried
        // mail is about six rounds: the cut below lands with rounds still to go.
        const val CAP = 100
        const val FULL_AUTHORS = 6
        const val FULL_EACH = 16
        const val DMS_EACH = 4

        // Fifty lab minutes of two full stores: four authors of twelve into forty.
        const val SETTLE_CAP = 40
        const val SETTLE_EACH = 12
        const val SETTLE_MS = 50 * 60_000L

        // Thirty DMs of about a kilobyte: two rounds and more of tier 2.
        const val CLAIM_DMS = 30
        const val PAD = "a long day, and more to say about it "
        const val PAD_REPEATS = 30

        // Each writer's DMs fill its sender quota, so its profile, older than all of them, is evicted.
        const val RELINK_QUOTA = 6
    }
}
