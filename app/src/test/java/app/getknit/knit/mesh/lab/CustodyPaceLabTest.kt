package app.getknit.knit.mesh.lab

import app.getknit.knit.mesh.CarryLedger
import app.getknit.knit.mesh.CustodyPace
import app.getknit.knit.mesh.ForwardSync
import app.getknit.knit.mesh.RoundPlan
import app.getknit.knit.mesh.link.FrameKey
import app.getknit.knit.mesh.protocol.WireCodec
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Custody's paced answers and its re-offer back-off through the real stacks (the custody-carry-policy contract's §2, §3,
 * §6 and §7; ADR 2026-11.bmks). Every node runs production's table ([CustodyPace.CONTRACT]: 16 KiB a Bluetooth round,
 * one every 2 s) and the back-off, where the rest of the lab answers whole. A lab clock jump never wakes the serve loop's
 * sleep or the 60 s tick, so a scenario moves the clock an interval and cuts the rounds due itself
 * ([LabNode.serveCustodyRounds], [serveRoundsUntilQuiet]), and ticks with [LabNode.tick]. The serve loop still runs on
 * the wall clock beside it: a round it cuts first is one the scenario's cut then finds not due, so the two never cut a
 * round twice, and every assertion below reads what crossed, never which of them cut it.
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
     * Below the cap, paced. Alice carries four rounds' worth of others' mail and Bob has never met her: the answer to his
     * link-up digest goes out in rounds of at most 16 KiB, one an interval, and ends with his store holding all of it, so
     * the two stores agree as ADR 006 promises below the cap.
     */
    @Test
    fun aNewcomerShortOfSeveralRoundsConvergesInPacedRounds() =
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
            lab.link(alice, bob)
            awaitLinkUp(alice, bob)
            serveRoundsUntilQuiet(alice, bob)
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
     * When they meet on an air link, every DM for the other crosses ahead of all of that mail, in the
     * first round (tier 2 before tier 4, §1). Then the link takes one round and never delivers it (the pipe holds it, as a
     * Bluetooth buffer holds what was written), and goes down: that round is all a cut loses, and the carry ledger credits
     * nothing for it (§3), while the rounds before it, which landed, are credited. The next link re-serves the lost round.
     * The cap binds, so the oracle leaves custody parity out and reads every store at or under its cap.
     *
     * Rounds of the contract's 16 KiB, at [SLOW_INTERVAL_MS] rather than 2 s: the serve loop beside the scenario wakes on
     * the wall clock, which a lab jump never moves, and at 2 s it cut rounds while the scenario waited under chaos (seeds
     * 363285215, 363285224), so the cut found the answer over or a second round in the hold.
     */
    @Suppress("LongMethod") // one pass: the meeting, the tier order, the cut and its ledger, the relink and the oracle
    @Test
    fun atTheCapEveryDmForThePeerCrossesFirstAndACutLosesOneUncreditedRound() =
        runBlocking {
            val limits = PACED.copy(custodyMaxRows = CAP, custodyPace = SLOW)
            val alice = lab.node("alice", limits).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", limits).apply { setDisplayName("Bob") }
            meetAndPart(alice, bob, SLOW_INTERVAL_MS)

            val dms =
                listOf(alice to bob, bob to alice).associate { (from, to) ->
                    from to
                        (1..DMS_EACH).map { i ->
                            assertTrue(from.sendDm(to, "${from.name} while apart $i"))
                            from.ownMessageId(from.dmWith(to), "${from.name} while apart $i")
                        }
                }
            val loads =
                listOf(alice to 4_200, bob to 4_300).associate { (node, seed) ->
                    node to
                        (1..FULL_AUTHORS).flatMap { a -> carryLoad(node, LabSybil(seed = seed + a, name = "${node.name}'s $a"), FULL_EACH) }
                }
            for (n in listOf(alice, bob)) {
                assertEquals("${n.name}'s store is full", CAP, n.custodyIds().size)
                assertTrue("${n.name} still holds its own DMs at the cap", n.custodyIds().containsAll(dms.getValue(n)))
            }

            lab.link(alice, bob, air = LinkAir(bytesPerSecond = AIR_BYTES_PER_SECOND))
            awaitLinkUp(alice, bob)
            for ((from, to) in listOf(alice to bob, bob to alice)) {
                lab.await(DMS_EACH) { to.decrypted(to.dmWith(from)).count { it.second.startsWith("${from.name} while apart") } }
                val order = from.transport.sent.servedTo(to)
                val lastDm = order.indexOfLast { it in dms.getValue(from) }
                val firstLoad = order.indexOfFirst { it in loads.getValue(from) }
                assertTrue("${from.name}'s DMs to ${to.name} crossed after its carried mail: $order", lastDm in 0 until firstLoad)
            }

            // A round that landed, then one the link takes and never delivers.
            alice.transport.awaitAirDrained(bob.transport)
            lab.clock.advance(SLOW_INTERVAL_MS)
            alice.serveCustodyRounds()
            alice.transport.awaitAirDrained(bob.transport)
            val landed =
                alice.transport.sent
                    .servedTo(bob)
                    .filter { it in loads.getValue(alice) }
            alice.transport.hold(bob.transport)
            val heldFrom = alice.roundsCut()
            lab.clock.advance(SLOW_INTERVAL_MS)
            alice.serveCustodyRounds()
            val cutShort = alice.metrics.frames().custodyAnswersCutShort
            val parked = alice.transport.held(bob.transport)
            // lab-lint: allow direct-disconnect — an air link, which `unlink` refuses; what the cut loses is the held round,
            // and both ends' departure is awaited next
            alice.transport.disconnect(bob.transport)
            val lost = parked.mapNotNull { WireCodec.decodeEnvelope(it.signed)?.id }.filter { it in loads.getValue(alice) }
            val lostBytes =
                alice
                    .custodySignedBytes()
                    .filterKeys { it in lost }
                    .values
                    .sum()
            // One round: nothing else is due without a lab clock move (the serve loop's wall-clock wake is [SLOW_INTERVAL_MS]
            // out, the tick's 24 h), and no digest arrived to wake it.
            assertEquals("rounds released into the held link", 1L, alice.roundsCut() - heldFrom)
            assertTrue("the held round carried no carried frame", lost.isNotEmpty())
            assertTrue("the cut lost more than one round: ${lost.size} frames, $lostBytes B", lostBytes <= ROUND_BYTES)
            alice.transport.awaitNeighborsObserved()
            bob.transport.awaitNeighborsObserved()
            // The lost round's credit falls due with the link gone: nothing is credited, and the answer ends cut short.
            lab.clock.advance(SLOW_INTERVAL_MS)
            alice.serveCustodyRounds()
            assertTrue("the answer was never cut short", alice.metrics.frames().custodyAnswersCutShort > cutShort)
            val held = alice.custodyIds()
            val credited = lost.filter { it in held && alice.carrySpread(it) > 0 }
            assertTrue("alice credited bob with frames he never got: $credited", credited.isEmpty())
            val uncredited = landed.filter { it in held && alice.carrySpread(it) == 0 }
            assertTrue("alice never credited bob with frames that landed: $uncredited", uncredited.isEmpty())

            val relinked = alice.transport.sent.size
            lab.link(alice, bob)
            awaitLinkUp(alice, bob)
            serveRoundsUntilQuiet(alice, bob, intervalMs = SLOW_INTERVAL_MS)
            val again =
                alice.transport.sent
                    .drop(relinked)
                    .servedTo(bob)
                    .toSet()
            val stillHeld = alice.custodyIds()
            val notResent = lost.filter { it in stillHeld && it !in again }
            assertTrue("the lost round was not served again on the next link: $notResent", notResent.isEmpty())
            lab.assertConverged(listOf(alice, bob), atLeast = 2 * DMS_EACH, custody = false) { it.dmWith(if (it === alice) bob else alice) }
        }

    /**
     * Two full stores linked for long cross each frame once a lapse and back off unless an offer draws (the supervisor's
     * addition, the contract's revision 9; knit-ios runs the same case). After the first exchange a frame one store took
     * and evicted in its own write is marked not kept by the other's memo, and a release is pending until judged, so:
     *
     * - **(a)** no frame is released to the same peer twice inside one [CarryLedger.NOT_KEPT_LAPSE_MS], either way (a
     *   write the link's own memo skipped as a repeat counts as a release, since that is what custody's memo bounds);
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
            // from its peer (each step's keeps come after its tick's offers), and per frame the windows (from, to] in which
            // it released that frame to its peer.
            val offers = two.associateWith { mutableListOf<Long>() }
            val keeps = two.associateWith { mutableListOf<Long>() }
            val releases = two.associateWith { HashMap<String, MutableList<LongArray>>() }
            val keptSeen = two.associateWith { it.custodyGate.keptFrom.size }.toMutableMap()
            val sentSeen = two.associateWith { it.transport.sent.size }.toMutableMap()
            val skippedSeen = two.associateWith { it.transport.dupSkipped.size }.toMutableMap()

            // What a step [from, to] at tick [tickAt] released and kept, read off each node's logs since the last step.
            fun record(
                tickAt: Long,
                from: Long,
                to: Long,
            ) {
                for (n in two) {
                    val peer = peerOf.getValue(n)
                    val kept =
                        n.custodyGate.keptFrom
                            .drop(keptSeen.getValue(n))
                            .count { it.source == peer.nodeId }
                    if (kept > 0) keeps.getValue(n) += tickAt
                    val written =
                        n.transport.sent
                            .drop(sentSeen.getValue(n))
                            .servedTo(peer) +
                            n.transport.dupSkipped
                                .drop(skippedSeen.getValue(n))
                                .carriedTo(peer)
                    written.forEach { id -> releases.getValue(n).getOrPut(id) { mutableListOf() } += longArrayOf(from, to) }
                    keptSeen[n] = n.custodyGate.keptFrom.size
                    sentSeen[n] = n.transport.sent.size
                    skippedSeen[n] = n.transport.dupSkipped.size
                }
            }

            val linkedAt = lab.clock.now()
            two.forEach { offers.getValue(it) += linkedAt }
            lab.link(alice, bob)
            awaitLinkUp(alice, bob)
            serveRoundsUntilQuiet(alice, bob)
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
                serveRoundsUntilQuiet(alice, bob)
                record(tickAt, from, lab.clock.now())
                notKept += lab.clock.now() to two.sumOf { it.metrics.frames().custodyNotKept }
            }

            // (a)
            for ((n, byId) in releases) {
                val repeats = byId.filterValues { w -> w.zipWithNext().any { (a, b) -> b[1] - a[0] < CarryLedger.NOT_KEPT_LAPSE_MS } }
                assertTrue(
                    "${n.name} released frames to ${peerOf.getValue(n).name} twice inside a lapse: ${repeats.keys}",
                    repeats.isEmpty(),
                )
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
     * Bob. His proven link's digest starts her answer; then a digest from a link that proved nothing, claiming Bob's id and
     * listing everything Alice holds, arrives. It is answered and leaves nothing behind (§3, §6): it neither replaces the
     * digest Bob's rounds are cut from nor tells Alice he holds what he lacks, so Bob still gets every DM for him in the
     * answer's own rounds, with no re-offer between. Below the cap, so the stores agree.
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

            lab.link(alice, bob)
            awaitLinkUp(alice, bob)
            alice.transport.hearClaimedDigest(bob.nodeId, alice.custodyIds().toList())
            alice.transport.awaitDigestsAnswered()
            serveRoundsUntilQuiet(alice, bob)
            val read = bob.decrypted(bob.dmWith(alice)).map { it.first }.toSet()
            val missing = dms.filterNot { it in read }
            assertTrue("bob never got ${missing.size} of alice's ${dms.size} DMs in her answer's rounds", missing.isEmpty())
            lab.assertConverged(listOf(alice, bob), atLeast = CLAIM_DMS) { it.dmWith(if (it === alice) bob else alice) }
        }

    /**
     * Relink inside one interval (the B2 blobs review). Alice holds DMs for Bob from three free identities whose profiles
     * her sender quota evicted, so each goes with its sender's key ahead (ADR 2026-09.g64k), and two of her own. The link
     * takes the first round and never delivers it, and is replaced within the interval, before the next round is due. A
     * release over the old link may have died with it: the answer forgets what it sent there, keys included, and the memo
     * ends what was pending on it (§6), so Bob gets every DM for him and every key on the new link.
     */
    @Test
    fun aRelinkInsideOneIntervalStillDeliversThePeersDmsAndKeys() =
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
            lab.link(alice, bob)
            awaitLinkUp(alice, bob)
            serveRoundsUntilQuiet(alice, bob)
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
     * meeting's answers are served to their end while linked ([serveRoundsUntilQuiet]), so none is still live when the
     * scenario links them again: an answer a relink finds live takes the new digest and keeps its own next round's time
     * (§2), so its first round on the new link would wait for the serve loop rather than go at the digest.
     */
    private suspend fun meetAndPart(
        a: LabNode,
        b: LabNode,
        intervalMs: Long = INTERVAL_MS,
    ) {
        lab.link(a, b)
        lab.awaitAcquainted(a, b)
        lab.befriend(a, b)
        serveRoundsUntilQuiet(a, b, intervalMs = intervalMs)
        lab.unlink(a, b)
    }

    /**
     * Waits out a link-up's own exchange: both newcomer passes ran (their digests are out), both digests are answered
     * (each answer's first round is released), and what crossed is handled, the air's included.
     */
    private suspend fun awaitLinkUp(vararg nodes: LabNode) {
        nodes.forEach { it.transport.awaitNeighborsHandled() }
        settle(*nodes)
    }

    /**
     * Waits until every digest the nodes were handed is answered and every frame is handled, again until a pass hands
     * none of them a new frame or digest: on an air link a digest still airing is not counted in yet, so one pass can
     * return before its answer is cut, and the answer's round is on the air after the far end drained.
     */
    private suspend fun settle(vararg nodes: LabNode) {
        val handed = { nodes.sumOf { it.transport.framesReceived + it.transport.digestsReceived } }
        do {
            val before = handed()
            nodes.forEach { it.transport.awaitDigestsAnswered() }
            nodes.forEach { it.transport.awaitInboundDrained() }
        } while (handed() != before)
    }

    /**
     * Moves the lab clock one Bluetooth interval of the nodes' pace ([intervalMs]) at a time and cuts the rounds due on
     * every node, until a pass cuts none:
     * every live answer has then released all it had or ended. Each pass waits for what its rounds carried to be handled
     * at the far end. Returns the passes it took, and fails past [MAX_PASSES] (an answer lives [ForwardSync.ANSWER_MAX_AGE_MS]).
     */
    private suspend fun serveRoundsUntilQuiet(
        vararg nodes: LabNode,
        intervalMs: Long = INTERVAL_MS,
    ): Int {
        var passes = 0
        while (true) {
            val before = nodes.sumOf { it.roundsCut() }
            lab.clock.advance(intervalMs)
            nodes.forEach { it.serveCustodyRounds() }
            settle(*nodes)
            if (nodes.sumOf { it.roundsCut() } == before) return passes
            check(++passes < MAX_PASSES) { "rounds were still being cut after $passes intervals" }
        }
    }

    private fun LabNode.roundsCut(): Long = metrics.frames().custodyRoundsCut

    /** The ids of the frames in these `sent` lines that crossed to [to], in the order they landed. */
    private fun List<String>.servedTo(to: LabNode): List<String> =
        mapNotNull { line ->
            val cols = line.split(' ')
            cols.getOrNull(3)?.takeIf { to.nodeId.startsWith(cols[1]) }
        }

    /** The ids of the carried frames in these `dupSkipped` lines whose writes toward [to] the link memo skipped as repeats. */
    private fun List<String>.carriedTo(to: LabNode): List<String> =
        mapNotNull { line ->
            val (peer, key) = line.split(' ')
            carriedKeys[key]?.takeIf { to.nodeId.startsWith(peer) }
        }

    /** The ids of the profile frames from [sender] this node holds in custody. */
    private suspend fun LabNode.custodiedProfileOf(sender: String): List<String> =
        custodyFrames().filter { it.split(':').let { f -> f[1] == "profile" && sender.startsWith(f[2].substringBefore('→')) } }

    private companion object {
        // The wall-clock re-offer loop pushed a day out, so [LabNode.tick] and [LabNode.reoffer] are a scenario's only ticks:
        // at 60 s it fired beside the lab clock's, stamping an offer between a jump and a tick, or sending a digest mid-cut.
        val PACED =
            LabLimits(custodyPace = CustodyPace.CONTRACT, custodyReofferBackoff = true, reofferIntervalMs = 24 * 60 * 60_000L)

        // An air link fast enough that a round airs in a few milliseconds: what the pass reads is the order and the cut,
        // never the airtime, and at twice the pace (16 KiB/s) its rounds alone took the scenario past 10 s a run.
        const val AIR_BYTES_PER_SECOND = 1_048_576L

        // How far an offer gap may sit from the back-off's: the scenario's ticks are a minute apart in lab time, and a step's
        // own rounds and real time move the clock by seconds at most.
        const val SLACK_MS = 5_000L
        const val ROUND_BYTES = CustodyPace.BLUETOOTH_ROUND_BYTES
        const val INTERVAL_MS = CustodyPace.BLUETOOTH_INTERVAL_MS

        // The contract's round, at an interval the serve loop's wall-clock wake never reaches inside the scenario: a lab jump
        // never moves that wake, and a scenario that cuts a round between two of its own steps needs no round but its own.
        // A quarter of an answer's life (ForwardSync.ANSWER_MAX_AGE_MS), so four rounds fit in one.
        const val SLOW_INTERVAL_MS = 30_000L
        val SLOW =
            CustodyPace(
                bluetooth = RoundPlan(ROUND_BYTES, SLOW_INTERVAL_MS),
                wifiAware = CustodyPace.CONTRACT.wifiAware,
                directLink = CustodyPace.CONTRACT.directLink,
            )

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
        const val MAX_PASSES = 50
    }
}
