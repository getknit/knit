package app.getknit.knit.mesh.lab

import app.getknit.knit.data.forward.ForwardRepository
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.mesh.BlobExchange
import app.getknit.knit.mesh.BulkRoute
import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.protocol.BlobReqContent
import app.getknit.knit.mesh.protocol.FrameId
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import app.getknit.knit.mesh.sha256Hex
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
 * The pull-through's hop budget and deadline (#195, ADR 2026-10.2drc), across the shapes its reviews found: a stream of
 * newcomers, reach to the budget and no further, an older build's ask, a neighbour on the iOS port's rules, a DM delivered
 * past its window, and a want a refused splice makes again. Every case ends in `assertConverged`.
 */
@RunWith(RobolectricTestRunner::class)
class PullBudgetLabTest : StrandedLabBase() {
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
     * (d)'s two ex-carriers, and a stream of strangers: every [CHURN_EVERY_MS] for three hours a fresh phone links to
     * Bob, stays two minutes and signs out. Bob pulls each newcomer at once, and each one's echo asks him back — with a
     * hop less than he holds, so none of it keeps his want alive (the blobs-reviewer's third pass: a newcomer's pull
     * once reset an echo's clock and vouched for it). Bob's want ends, and no newcomer that came after holds one.
     */
    @Test
    fun aStreamOfNewcomersDoesNotKeepAnExCarriersWantAlive() =
        runBlocking {
            val st = strand(querying.copy(custodyTtlMs = HOUR_MS))
            lab.unlink(st.alice, st.bob)
            lab.unlink(st.alice, st.carol)
            val two = listOf(st.bob, st.carol)
            var steps = 0
            while (two.any { st.id in it.custodyIds() } && steps < CUSTODY_GONE_STEPS) {
                step(two)
                steps++
            }
            assertTrue("custody let the frame go", two.none { st.id in it.custodyIds() })

            var bobEnded: Int? = null
            val heldAfter = mutableListOf<String>()
            for (round in 1..CHURN_ROUNDS) {
                val wanted = walkBy(st.bob, st.carol, round, st.hash)
                if (bobEnded != null && wanted) heldAfter += "walker$round"
                lab.clock.advance(CHURN_EVERY_MS - CHURN_STAY_MS)
                two.forEach { it.sweepExpired() }
                two.forEach { it.reoffer() }
                quiesce(two)
                if (bobEnded == null && !st.bob.wants(st.hash)) bobEnded = round
            }
            assertTrue("Bob's want ended", bobEnded != null && !st.bob.wants(st.hash))
            assertFalse("Carol's too", st.carol.wants(st.hash))
            assertTrue("no newcomer after that came to want it: $heldAfter", heldAfter.isEmpty())
            lab.assertConverged(two, atLeast = 0) { Conversations.NEARBY }
        }

    /**
     * A fresh phone links to [bob], stays [CHURN_STAY_MS] with the ticks that run meanwhile, and signs out; whether it
     * wanted [hash] as it left.
     */
    private suspend fun walkBy(
        bob: LabNode,
        carol: LabNode,
        round: Int,
        hash: String,
    ): Boolean {
        val n = lab.node("walker$round", querying.copy(custodyTtlMs = HOUR_MS)).apply { setDisplayName("Walker $round") }
        lab.link(n, bob)
        lab.await(1) { if (n.nodeId in bob.transport.digestsSent) 1 else 0 } // Bob's link-up re-ask toward it: the digest follows it
        val three = listOf(bob, carol, n)
        three.forEach { it.reoffer() }
        quiesce(three)
        lab.clock.advance(CHURN_STAY_MS)
        three.forEach { it.sweepExpired() }
        three.forEach { it.reoffer() }
        quiesce(three)
        val wanted = n.wants(hash)
        lab.retire(n)
        lab.await(1) { if (n.nodeId !in bob.manager.linksActedOn) 1 else 0 }
        return wanted
    }

    /**
     * How far a pull-through reaches: [BlobExchange.PULL_BUDGET] rings past the root's neighbours. Rita wants two
     * pictures nobody near her holds, on a line of phones that carry nothing for either: Nate, Olga and Pia, then Hank,
     * who holds the first, then Ivy, who holds the second. Each phone along the line wants it with a hop less than the
     * one that asked; Pia, at 0, still queries Hank and fetches from him, so the first walks back to Rita. Nobody asks
     * Hank to pull the second, so Ivy is never asked for it (#195, ADR 2026-10.2drc).
     */
    @Test
    fun aPullReachesAHolderItsBudgetAwayAndNoFurther() =
        runBlocking {
            val short = querying.copy(custodyTtlMs = SHORT_CUSTODY_MS)
            val rita = lab.node("rita", short).apply { setDisplayName("Rita") }
            val hank = lab.node("hank", short).apply { setDisplayName("Hank") }
            val ivy = lab.node("ivy", short).apply { setDisplayName("Ivy") }
            val line = List(PULL_BUDGET_HOPS) { lab.node("line$it", short).apply { setDisplayName("Line $it") } }
            val near = sendHeld(hank, rita, "near")
            val far = sendHeld(ivy, rita, "far")
            lab.unlink(rita, hank)
            lab.unlink(rita, ivy)

            // Custody is over everywhere before the line exists: none of them carries either frame.
            lab.clock.advance(PAST_SHORT_CUSTODY_MS)
            listOf(rita, hank, ivy).forEach { it.sweepExpired() }
            val chain = listOf(rita) + line + listOf(hank, ivy)
            lab.linkAll(*chain.zipWithNext().toTypedArray())
            chain.zipWithNext().forEach { (x, y) -> awaitLinkUpHooks(x to y, y to x) }
            quiesce(chain)

            var steps = 0
            while (!rita.blobs.exists(near) && steps < REACH_STEPS) {
                step(chain)
                steps++
            }
            assertTrue("the holder the budget reaches is fetched from (took $steps)", rita.blobs.exists(near))
            repeat(2) { step(chain) }
            assertFalse("the one a hop further is not", rita.blobs.exists(far))
            assertTrue("Ivy was never asked for it", hank.transport.blobAsks.none { it.hash == far && it.to == ivy.nodeId })
            assertTrue("and Hank never wanted it", !hank.wants(far))
            lab.assertConverged(chain, atLeast = 0) { Conversations.NEARBY }
        }

    /** [from] sends [to] a DM picture whose file stays on the link, so [to] holds the row and not the bytes. */
    private suspend fun sendHeld(
        from: LabNode,
        to: LabNode,
        text: String,
    ): String {
        lab.link(from, to) { from.transport.holdFiles(to.transport) }
        lab.awaitAcquainted(from, to)
        awaitLinkUpHooks(from to to, to to from)
        assertTrue(from.sendImage(Random(text.hashCode()).nextBytes(4_096), text, to = to))
        val id = from.ownMessageId(from.dmWith(to), text)
        val hash = checkNotNull(from.attachmentHash(from.dmWith(to), id))
        lab.await(1) { if (to.attachmentHash(to.dmWith(from), id) != null) 1 else 0 }
        lab.await(1) { from.transport.heldFiles(to.transport).count { it == hash } }
        return hash
    }

    /**
     * An older build's ask — the iOS port's today — carries no budget. Olive asks Bob for one blob he holds and one
     * nobody does: the first is served, the second makes Bob a fetch-only want that queries Carol and asks nobody to
     * pull it through, and that nothing keeps alive past its TTL (#195).
     */
    @Test
    fun anOlderBuildsAskIsServedIfHeldAndFetchedOnlyOtherwise() =
        runBlocking {
            val olive = lab.node("olive", querying).apply { setDisplayName("Olive") }
            val bob = lab.node("bob", querying).apply { setDisplayName("Bob") }
            val carol = lab.node("carol", querying).apply { setDisplayName("Carol") }
            lab.linkAll(olive to bob, bob to carol)
            lab.awaitAcquainted(olive, bob)
            lab.awaitAcquainted(bob, carol)
            awaitLinkUpHooks(olive to bob, bob to olive, bob to carol, carol to bob)
            val held = Random(4_195).nextBytes(4_096)
            val heldHash = sha256Hex(held)
            bob.blobs.insert(heldHash, "image/jpeg", held)
            val lacking = sha256Hex(Random(5_195).nextBytes(4_096))

            olive.transport.send(olderAsk(olive, heldHash), Peer(bob.nodeId))
            lab.await(1) { bob.transport.files.count { it == "${olive.nodeId} ATTACHMENT $heldHash" } }

            val queries = bob.transport.blobQueries.count { lacking in it.hashes && it.to == carol.nodeId }
            olive.transport.send(olderAsk(olive, lacking), Peer(bob.nodeId))
            lab.await(1) { if (bob.wants(lacking)) 1 else 0 }
            lab.await(1) { if (bob.transport.blobQueries.count { lacking in it.hashes && it.to == carol.nodeId } > queries) 1 else 0 }
            val three = listOf(olive, bob, carol)
            quiesce(three)
            assertTrue("Bob asked nobody to pull it through", bob.transport.blobAsks.none { it.hash == lacking })
            var steps = 0
            while (bob.wants(lacking) && steps < ROOT_GONE_STEPS) {
                step(three)
                steps++
            }
            assertFalse("and nothing kept his fetch-only want alive", bob.wants(lacking))
            assertTrue("Carol never wanted it", !carol.wants(lacking))
            lab.assertConverged(three, atLeast = 0) { Conversations.NEARBY }
        }

    /** A `blobreq` for [hash] as a build before #195 sends it: no budget, no deadline. */
    private fun olderAsk(
        from: LabNode,
        hash: String,
    ): WireEnvelope {
        val env =
            RelayEnvelope(
                type = FrameType.BLOB_REQ,
                id = FrameId.new(),
                senderId = from.nodeId,
                payload = WireCodec.encodePayload(BlobReqContent(hash)),
            )
        return WireEnvelope(relay = false, sig = ByteArray(0), signed = WireCodec.encodeEnvelope(env))
    }

    /**
     * The mixed fleet: Ivan plays the iOS port today ([LabLimits.pullBudgets] off) — no holder query, asks with no budget,
     * any ask refreshes his wants, and every echo of his pulls through. Alice wants a picture nobody holds and asks him
     * blind; he pulls Bob, a current build, through with no field, so Bob's want is a fetch only, and Bob asks Ivan back
     * blind. Ivan's asks may make Bob's want, never keep it: each of Bob's lives one TTL at most (#195's review). The loop
     * between them is the iOS port's to end — until its companion lands, Ivan keeps wanting it, which this asserts too.
     */
    @Test
    fun anIphoneRulesNeighbourCanMakeOurWantsButNeverKeepOneAliveForMoreThanATtl() =
        runBlocking {
            val short = querying.copy(custodyTtlMs = SHORT_CUSTODY_MS)
            val sam = lab.node("sam", short).apply { setDisplayName("Sam") }
            val alice = lab.node("alice", short).apply { setDisplayName("Alice") }
            val ivan =
                lab.node("ivan", short.copy(blobQuery = false, answersHolderQueries = false, pullBudgets = false)).apply {
                    setDisplayName("Ivan")
                }
            val bob = lab.node("bob", short).apply { setDisplayName("Bob") }
            lab.linkAll(alice to ivan, ivan to bob)
            lab.awaitAcquainted(alice, ivan)
            lab.awaitAcquainted(ivan, bob)
            awaitLinkUpHooks(alice to ivan, ivan to alice, ivan to bob, bob to ivan)
            val hash = sendHeld(sam, alice, "for alice")
            val id = sam.ownMessageId(sam.dmWith(alice), "for alice")
            lab.await(1) { if (listOf(ivan, bob).all { id in it.custodyIds() }) 1 else 0 }
            lab.retire(sam)
            lab.await(1) { if (sam.nodeId !in alice.manager.linksActedOn) 1 else 0 }
            val three = listOf(alice, ivan, bob)
            quiesce(three)
            lab.clock.advance(PAST_SHORT_CUSTODY_MS) // nothing carries Sam's frame any more: Bob's want is Ivan's echo
            three.forEach { it.sweepExpired() }

            val (made, swept) = watchGenerations(three, bob, hash)
            val first = checkNotNull(made) { "Ivan's pull never reached Bob" }
            assertTrue("Bob's wants were swept, generation by generation: $swept", swept.isNotEmpty())
            val marks = listOf(first) + swept
            val longest = marks.zipWithNext { x, y -> y - x }.maxOrNull() ?: 0
            assertTrue("none lived past a TTL and its sweep (gaps $longest steps, $swept)", longest <= TTL_STEPS)
            assertTrue("the loop itself is the iOS port's to end: Ivan still wants it", ivan.wants(hash))
            lab.assertConverged(three, atLeast = 0) { Conversations.NEARBY }
        }

    /**
     * [MIXED_STEPS] steps of [nodes]' ticks: the first step after which [watched] wants [hash], and every step whose sweep
     * took its want — a generation's end.
     */
    private suspend fun watchGenerations(
        nodes: List<LabNode>,
        watched: LabNode,
        hash: String,
    ): Pair<Int?, List<Int>> {
        val swept = mutableListOf<Int>()
        var made: Int? = null
        for (s in 1..MIXED_STEPS) {
            lab.clock.advance(CYCLE_STEP_MS)
            nodes.forEach { it.sweepExpired() }
            // Counted from the sweep itself: a stray timer's ask can make the want again before a read of it could tell.
            if (hash in watched.manager.lastSweptBlobs) swept += s
            nodes.forEach { it.reoffer() }
            quiesce(nodes)
            if (made == null && watched.wants(hash)) made = s
        }
        return made to swept
    }

    /**
     * A DM delivered after its custody window: Sam's clock sits a day and an hour behind, so his DM reaches Alice with its
     * window already past, and he signs out holding the only copy. Alice's want takes its deadline from the delivery
     * itself, not from the next tick, so from its first step she asks nobody on the line to pull it through, and every ask
     * anyone makes for it carries her deadline (#195's review: a delivery's want once reset the deadline to none).
     */
    @Test
    fun aDmDeliveredPastItsWindowIsPulledThroughByNobodyFromItsFirstStep() =
        runBlocking {
            val sam = lab.node("sam", querying).apply { setDisplayName("Sam") }
            val alice = lab.node("alice", querying).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", querying).apply { setDisplayName("Bob") }
            val carol = lab.node("carol", querying).apply { setDisplayName("Carol") }
            lab.linkAll(alice to bob, bob to carol)
            lab.awaitAcquainted(alice, bob)
            lab.awaitAcquainted(bob, carol)
            awaitLinkUpHooks(alice to bob, bob to alice, bob to carol, carol to bob)
            lab.link(sam, alice) { sam.transport.holdFiles(alice.transport) }
            lab.awaitAcquainted(sam, alice)
            awaitLinkUpHooks(sam to alice, alice to sam)

            lab.clock.skew("sam", -PAST_WINDOW_MS) // his DM's sentAt is a day and an hour old when it lands
            assertTrue(sam.sendImage(Random(6_195).nextBytes(4_096), "late", to = alice))
            val id = sam.ownMessageId(sam.dmWith(alice), "late")
            val hash = checkNotNull(sam.attachmentHash(sam.dmWith(alice), id))
            val deadline = checkNotNull(sam.row(id)).sentAt + ForwardRepository.DEFAULT_TTL_MS
            lab.await(1) { if (alice.attachmentHash(alice.dmWith(sam), id) != null) 1 else 0 }
            lab.await(1) { sam.transport.heldFiles(alice.transport).count { it == hash } }
            lab.retire(sam)
            lab.await(1) { if (sam.nodeId !in alice.manager.linksActedOn) 1 else 0 }
            val three = listOf(alice, bob, carol)
            quiesce(three)
            repeat(LATE_STEPS) { step(three) }

            val asks = three.flatMap { n -> n.transport.blobAsks.filter { it.hash == hash } }
            assertTrue("Alice asked nobody on the line to pull it: $asks", asks.none { it.to == bob.nodeId || it.to == carol.nodeId })
            assertTrue("every ask for it carries her deadline: $asks", asks.all { it.until == deadline })
            assertFalse("Bob never wanted it", bob.wants(hash))
            assertFalse("Carol never wanted it", carol.wants(hash))
            lab.assertConverged(three, atLeast = 0) { Conversations.NEARBY }
        }

    /**
     * A splice that fails after its want was swept: Bob, an echo of a stranger's one ask, kept a bad prefix from Hank; his
     * resumed ask's tail lands only after the sweep took the want. The refused splice makes the want again — fast-only, as
     * ADR 2026-10.js2p leaves a want no re-arm has judged, so Hank is linked over Wi-Fi Aware too — and a want no row,
     * custody or ask stands behind asks with no budget left, pull 0, never as a root (#195's review: it minted one).
     */
    @Test
    fun aWantARefusedSpliceMakesAgainAsksWithNoBudget() =
        runBlocking {
            val bob = lab.node("bob", querying).apply { setDisplayName("Bob") }
            val hank = lab.node("hank", querying).apply { setDisplayName("Hank") }
            val xena = lab.node("xena", querying).apply { setDisplayName("Xena") }
            lab.linkAll(bob to xena)
            awaitLinkUpHooks(bob to xena, xena to bob)
            val picture = Random(7_195).nextBytes(SPLICE_BYTES)
            val hash = sha256Hex(picture)
            hank.blobs.insert(hash, "image/jpeg", picture)
            // Linked over Wi-Fi Aware too: a want a refused splice makes again is fast-only until a re-arm judges it (ADR
            // 2026-10.js2p), and this one has no row to judge it by, so only a fast holder is asked.
            bob.transport.bulkRoutes[hank.nodeId] = BulkRoute.Linked
            hank.transport.bulkRoutes[bob.nodeId] = BulkRoute.Linked

            hank.transport.corruptFiles = true
            lab.link(bob, hank) { hank.transport.streamFiles(bob.transport, untilBytes = SPLICE_CUT) }
            awaitLinkUpHooks(bob to hank, hank to bob)
            xena.transport.send(askWithBudget(xena, hash, BlobExchange.PULL_BUDGET), Peer(bob.nodeId))
            lab.await(1) { hank.transport.heldFiles(bob.transport).count { it == hash } }
            lab.unlink(bob, hank)
            lab.unlink(bob, xena)
            assertEquals("a bad prefix kept", SPLICE_CUT, bob.partials.length(hash))

            hank.transport.corruptFiles = false
            lab.clock.advance(BlobExchange.SERVE_MEMO_MS)
            lab.link(bob, hank) { hank.transport.holdFiles(bob.transport) }
            lab.await(1) { hank.transport.heldFiles(bob.transport).count { it == hash } } // the tail, waiting on the link
            lab.clock.advance(BlobExchange.FETCH_TTL_MS + MINUTE_MS)
            bob.sweepExpired()
            assertFalse("the sweep took the want", bob.wants(hash))

            val asked = bob.transport.blobAsks.count { it.hash == hash }
            hank.transport.releaseFiles(bob.transport) // the tail lands on the bad prefix, and the splice is refused
            lab.await(1) {
                bob.metrics
                    .files()
                    .splicesRefused
                    .toInt()
            }
            // Counted as the refusal begins, ahead of the want it makes again: awaited.
            lab.await(1) { if (bob.wants(hash)) 1 else 0 }
            // Hank, who sent the tail, is passed over for the rest of this cycle: the next one asks him from byte 0.
            lab.clock.advance(BlobExchange.CYCLE_MS)
            bob.reoffer()
            lab.await(1) { bob.transport.blobAsks.count { it.hash == hash } - asked }
            val again =
                bob.transport.blobAsks
                    .filter { it.hash == hash }
                    .drop(asked)
            assertEquals("asked again from byte 0 with no budget left: $again", listOf<Int?>(0), again.map { it.pull }.distinct())
            lab.await(1) { if (bob.blobs.exists(hash)) 1 else 0 } // Hank serves a fresh ask with the whole file
            // Awaited: the store holds the bytes a moment before the exchange lets the want go (`onReceived`).
            lab.await(1) { if (bob.wants(hash)) 0 else 1 }
            lab.assertConverged(listOf(bob, hank), atLeast = 0) { Conversations.NEARBY }
        }

    /**
     * One picture, two conversations (ADR 2026-10.mptm's reuse): Sam's DM to Alice reached her past its custody window,
     * so her want of it pulls nothing through. Then Sam posts the same picture to a group of theirs, under the same
     * identity, by way of Bob, who carries the group frame and is not in it. A group's row means no deadline, and the
     * delivery read every row naming the hash, so Alice asks Bob to pull it through at once — not a tick later, when
     * the re-arm would have lifted the DM's past deadline (#195's review).
     */
    @Test
    fun aGroupPostNamingAPictureWhoseDmIsPastItsWindowIsPulledThroughAtOnce() =
        runBlocking {
            val sam = lab.node("sam", querying).apply { setDisplayName("Sam") }
            val alice = lab.node("alice", querying).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", querying).apply { setDisplayName("Bob") }
            lab.linkAll(sam to bob, bob to alice)
            lab.awaitAcquainted(sam, bob)
            lab.awaitAcquainted(bob, alice)
            awaitLinkUpHooks(sam to bob, bob to sam, bob to alice, alice to bob)
            lab.link(sam, alice) { sam.transport.holdFiles(alice.transport) }
            lab.awaitAcquainted(sam, alice)
            awaitLinkUpHooks(sam to alice, alice to sam)

            val picture = Random(8_195).nextBytes(4_096)
            lab.clock.skew("sam", -PAST_WINDOW_MS) // the DM lands a day and an hour after its sentAt
            assertTrue(sam.sendImage(picture, "an old one", to = alice))
            val dmId = sam.ownMessageId(sam.dmWith(alice), "an old one")
            val hash = checkNotNull(sam.attachmentHash(sam.dmWith(alice), dmId))
            lab.await(1) { if (alice.attachmentHash(alice.dmWith(sam), dmId) != null) 1 else 0 }
            lab.await(1) { sam.transport.heldFiles(alice.transport).count { it == hash } }
            lab.clock.skew("sam", 0L)
            lab.unlink(sam, alice) // her only holder walks off before the bytes cross
            val three = listOf(sam, alice, bob)
            quiesce(three)
            // Every blobreq Alice sends Bob for it: Bob holds nothing, so each is a pull. (That the DM's past window asks for
            // none is the past-window case's to pin; here it is what the group post changes.)
            val asksOfBob = { alice.transport.blobAsks.count { it.hash == hash && it.to == bob.nodeId } }

            val groupId = sam.createGroup(alice)
            assertTrue(sam.sendGroup(groupId, "hello"))
            lab.await(1) { alice.rowsIn(groupId).size }
            lab.await(1) { if (sam.holdsIdentityFor(picture)) 1 else 0 }
            assertTrue(sam.forwardImage(picture, "again, for the group", groupId = groupId))
            val postId = sam.ownMessageId(groupId, "again, for the group")
            assertEquals("the same identity", hash, sam.attachmentHash(groupId, postId))
            lab.await(1) { if (alice.attachmentHash(groupId, postId) != null) 1 else 0 }
            // No tick: the delivery's own want is what asks.
            lab.await(1) { asksOfBob() }
            val asked = alice.transport.blobAsks.filter { it.hash == hash && it.to == bob.nodeId }
            assertTrue(
                "with the whole budget and no deadline: $asked",
                asked.all { it.pull == BlobExchange.PULL_BUDGET && it.until == null },
            )
            quiesce(three)
            lab.assertConverged(listOf(alice, bob), atLeast = 0) { Conversations.NEARBY }
        }

    /** A `blobreq` for [hash] with [pull] of budget and no deadline, as a current build's root sends it. */
    private fun askWithBudget(
        from: LabNode,
        hash: String,
        pull: Int,
    ): WireEnvelope {
        val content = BlobReqContent(hash, pull = pull)
        val env =
            RelayEnvelope(type = FrameType.BLOB_REQ, id = FrameId.new(), senderId = from.nodeId, payload = WireCodec.encodePayload(content))
        return WireEnvelope(relay = false, sig = ByteArray(0), signed = WireCodec.encodeEnvelope(env))
    }
}
