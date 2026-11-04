package app.getknit.knit.mesh.lab

import app.getknit.knit.data.message.Conversations
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
 * An attachment whose bytes never leave their sender (work item #195). Sam, linked to Alice alone, sends her a DM with a
 * picture whose file sits on the link, then signs out for good. Alice, Bob and Carol are linked to each other. Alice's
 * row re-arms her want every tick, and each dead cycle used to end with every neighbour asked to pull the blob through,
 * each of which armed a want of its own and asked its neighbours in turn — every 6 min, for good (the Pixel 9's card
 * `92134a58…`, echoed by the Pixel 7 and the Moto G). A DM's frame is carried for a day from its `sentAt`; past that
 * only its two ends ever held the bytes, so nobody is asked to pull it, and an echo (a want only a neighbour's pull
 * made) pulls in its first cycles alone, so it ends once its root does.
 */
@RunWith(RobolectricTestRunner::class)
class StrandedAttachmentLabTest : StrandedLabBase() {
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
     * A day and an hour on, nobody carries Sam's frame. Alice still queries Bob and Carol once a cycle, but asks nobody
     * to pull it through, so neither wants it, and custody agrees.
     */
    @Test
    fun pastItsCustodyWindowNobodyIsAskedToPullAStrandedDmPicture() =
        runBlocking {
            val st = strand(querying)
            val three = listOf(st.alice, st.bob, st.carol)
            lab.clock.advance(PAST_WINDOW_MS)
            three.forEach { it.sweepExpired() }

            val pulls =
                st.alice.metrics
                    .files()
                    .blobPullThroughs
            val asks = st.alice.asksOf(st.hash)
            val toBob = st.alice.queriesTo(st.bob, st.hash)
            val toCarol = st.alice.queriesTo(st.carol, st.hash)
            repeat(CYCLES) { step(three) }

            assertEquals(
                "Alice asked nobody to pull it",
                pulls,
                st.alice.metrics
                    .files()
                    .blobPullThroughs,
            )
            assertEquals("no blobreq for it left Alice", asks, st.alice.asksOf(st.hash))
            assertTrue(
                "and said why",
                st.alice.metrics
                    .files()
                    .blobPullsWithheld > 0,
            )
            val bobQueries = st.alice.queriesTo(st.bob, st.hash) - toBob
            val carolQueries = st.alice.queriesTo(st.carol, st.hash) - toCarol
            assertTrue("Bob was queried once a cycle at most: $bobQueries in $CYCLES", bobQueries in 1..CYCLES)
            assertTrue("Carol was queried once a cycle at most: $carolQueries in $CYCLES", carolQueries in 1..CYCLES)
            assertEquals("no query was refused", 0L, three.sumOf { it.metrics.files().blobQueriesRefused })

            listOf(st.bob, st.carol).forEach { it.sweepExpired() }
            assertTrue("Alice still wants it: her row names it", st.alice.wants(st.hash))
            assertFalse("Bob wants nothing of it", st.bob.wants(st.hash))
            assertFalse("Carol wants nothing of it", st.carol.wants(st.hash))
            lab.assertConverged(three, atLeast = 0) { Conversations.NEARBY }
        }

    /** The control: an hour on, carriers still hold Sam's frame, and Alice still asks her neighbours to pull it through. */
    @Test
    fun insideItsCustodyWindowTheNeighboursAreStillAskedToPullIt() =
        runBlocking {
            // The lab's own answer wait: this case waits a refused answer out rather than counting cycles.
            val st = strand(querying.copy(queryWaitMs = LabLimits().queryWaitMs))
            val three = listOf(st.alice, st.bob, st.carol)
            // Read before the jump: nothing of the setup's may land between it and the tick that is counted.
            val pulls =
                st.alice.metrics
                    .files()
                    .blobPullThroughs
            lab.clock.advance(INSIDE_WINDOW_MS)
            three.forEach { it.sweepExpired() }
            three.forEach { it.reoffer() }
            // Awaited, not quiesced: an answer the holder's budget refused is waited on for the query's wait.
            lab.await(1) {
                if (st.alice.metrics
                        .files()
                        .blobPullThroughs > pulls
                ) {
                    1
                } else {
                    0
                }
            }
            quiesce(three)
            val pulled =
                st.alice.transport.blobAsks
                    .filter { it.hash == st.hash }
                    .map { it.to }
                    .toSet()
            assertTrue("of Bob or Carol: $pulled", pulled.any { it == st.bob.nodeId || it == st.carol.nodeId })
            lab.assertConverged(three, atLeast = 0) { Conversations.NEARBY }
        }

    /**
     * Every node keeps custody two hours, so three hours on Bob and Carol carry nothing of Sam's: what they want of the
     * picture is Alice's pull alone, echoed. Then Alice's row goes. Every ask an echo makes carries a hop less than
     * the want it echoes (#195, ADR 2026-10.2drc), so nothing Bob or Carol ask keeps Alice's want, or each other's, alive;
     * what their asks make of hers again has less still, and the lot ends within a TTL per budget from hers to 0. Before
     * the budget, Bob and Carol pulled each other and Alice every cycle and none of the three ever ended.
     */
    @Test
    fun echoesOfAPullEndOnceTheWantTheyEchoIsGone() =
        runBlocking {
            val st = strand(querying.copy(custodyTtlMs = SHORT_CUSTODY_MS))
            val three = listOf(st.alice, st.bob, st.carol)
            lab.clock.advance(PAST_SHORT_CUSTODY_MS)
            three.forEach { it.sweepExpired() }

            val pulls =
                st.alice.metrics
                    .files()
                    .blobPullThroughs
            three.forEach { it.reoffer() }
            quiesce(three)
            repeat(ECHO_CYCLES) { step(three) }
            assertTrue(
                "Alice pulls it through inside the DM's day",
                st.alice.metrics
                    .files()
                    .blobPullThroughs > pulls,
            )
            assertTrue("Bob echoes her pull", st.bob.wants(st.hash))
            assertTrue("Carol echoes her pull", st.carol.wants(st.hash))

            st.alice.messages.deleteByConversation(st.alice.dmWith(st.sam))
            var steps = 0
            while (three.any { it.wants(st.hash) } && steps < GENERATIONS_STEPS) {
                step(three)
                steps++
            }
            assertFalse("Alice's want never aged out: the echoes kept refreshing it", st.alice.wants(st.hash))
            assertFalse("Bob still wants it", st.bob.wants(st.hash))
            assertFalse("Carol still wants it", st.carol.wants(st.hash))
            assertEquals("no query was refused", 0L, three.sumOf { it.metrics.files().blobQueriesRefused })
            lab.assertConverged(three, atLeast = 0) { Conversations.NEARBY }
        }

    /**
     * Bob and Carol carry Sam's frame, linked to each other alone once Alice walks off, and nobody holds the bytes: each
     * wants it as a carrier and asks the other to pull it through. The frame's custody runs out (an hour, on every node).
     * Their own re-arm stops with it; each still asks the other to pull it, but with a hop less than the other holds
     * (#195, ADR 2026-10.2drc), which keeps nothing alive, so both end within the generations bound. Before, a
     * carrier's want stayed its own for good, and the two pulled each other every cycle (blobs-reviewer F1).
     */
    @Test
    fun twoLinkedCarriersOfAnExpiredFrameStopWantingItsPicture() =
        runBlocking {
            val st = strand(querying.copy(custodyTtlMs = HOUR_MS))
            lab.unlink(st.alice, st.bob)
            lab.unlink(st.alice, st.carol)
            val two = listOf(st.bob, st.carol)
            step(two)
            assertTrue("Bob wants it as its carrier", st.bob.wants(st.hash))
            assertTrue("Carol wants it as its carrier", st.carol.wants(st.hash))

            var steps = 0
            while (two.any { st.id in it.custodyIds() } && steps < CUSTODY_GONE_STEPS) {
                step(two)
                steps++
            }
            assertTrue("custody let the frame go", two.none { st.id in it.custodyIds() })
            steps = 0
            while (two.any { it.wants(st.hash) } && steps < CARRIERS_GONE_STEPS) {
                step(two)
                steps++
            }
            assertFalse("Bob still wants it", st.bob.wants(st.hash))
            assertFalse("Carol still wants it", st.carol.wants(st.hash))
            assertEquals("no query was refused", 0L, two.sumOf { it.metrics.files().blobQueriesRefused })
            lab.assertConverged(two, atLeast = 0) { Conversations.NEARBY }
        }

    /**
     * Alice's one neighbour, Dave, a current build that neither answers nor sends a holder query (no `CAP_BLOB_HAVE`,
     * querying off), can only be asked blind, and his
     * want is an echo of hers. Once the frame's custody is over (two hours, on every node), nothing but her row names it.
     * Her blind asks keep f7gg's pace through her first two lead-less cycles; the third is dead and the gaps double from
     * it, so the first four ticks each ask and the rest back off (#195). Once her row goes, Dave's asks back carry a hop
     * less than hers, so neither want outlives hers by more than a TTL per budget from hers to 0.
     */
    @Test
    fun aNeighbourThatCannotAnswerIsAskedBlindAtTheBackedOffPaceAndItsEchoEndsWithTheRoot() =
        runBlocking {
            val short = querying.copy(custodyTtlMs = SHORT_CUSTODY_MS)
            val sam = lab.node("sam", short).apply { setDisplayName("Sam") }
            val alice = lab.node("alice", short).apply { setDisplayName("Alice") }
            // Asks blind and is asked blind: no CAP_BLOB_HAVE, no queries of its own.
            val dave = lab.node("dave", short.copy(blobQuery = false, answersHolderQueries = false)).apply { setDisplayName("Dave") }
            lab.link(alice, dave)
            lab.awaitAcquainted(alice, dave)
            awaitLinkUpHooks(alice to dave, dave to alice)
            lab.link(sam, alice) { sam.transport.holdFiles(alice.transport) }
            lab.awaitAcquainted(sam, alice)
            lab.befriend(sam, alice)
            awaitLinkUpHooks(sam to alice, alice to sam)
            assertTrue(sam.sendImage(Random(1_195).nextBytes(4_096), "asked blind", to = alice))
            val id = sam.ownMessageId(sam.dmWith(alice), "asked blind")
            val hash = checkNotNull(sam.attachmentHash(sam.dmWith(alice), id))
            lab.await(1) { if (alice.attachmentHash(alice.dmWith(sam), id) != null) 1 else 0 }
            lab.await(1) { sam.transport.heldFiles(alice.transport).count { it == hash } }
            lab.await(1) { if (id in dave.custodyIds()) 1 else 0 }
            lab.retire(sam)
            lab.await(1) { if (sam.nodeId !in alice.manager.linksActedOn) 1 else 0 }
            val two = listOf(alice, dave)
            quiesce(two)

            lab.clock.advance(PAST_SHORT_CUSTODY_MS)
            two.forEach { it.sweepExpired() }
            val asked = mutableListOf<Int>()
            repeat(BLIND_TICKS) { tick ->
                val before = alice.transport.blobAsks.count { it.hash == hash && it.to == dave.nodeId }
                lab.clock.advance(MINUTE_MS)
                two.forEach { it.reoffer() }
                quiesce(two)
                if (alice.transport.blobAsks.count { it.hash == hash && it.to == dave.nodeId } > before) asked += tick
            }
            // f7gg's pace for the first two lead-less cycles (ticks 0, 1). The third (tick 2) is dead, a blind ask that
            // brought nothing, so the gaps double from there: 45 s, 90 s, 3 min, 6 min, each rounded up to the minute's
            // tick (3, 5, 8, 14). Dave is asked in every cycle that opens.
            assertEquals("Dave, at the backed-off pace", listOf(0, 1, 2, 3, 5, 8, 14), asked)
            assertTrue("Dave echoes her ask", dave.wants(hash))

            alice.messages.deleteByConversation(alice.dmWith(sam))
            var steps = 0
            while (two.any { it.wants(hash) } && steps < GENERATIONS_STEPS) {
                step(two)
                steps++
            }
            assertFalse("Alice's want outlived her row", alice.wants(hash))
            assertFalse("Dave's echo outlived her want", dave.wants(hash))
            lab.assertConverged(two, atLeast = 0) { Conversations.NEARBY }
        }

    /**
     * (d) with a link that will not stay up: Bob and Carol's link drops and re-forms every [FLAP_MS] for two hours after
     * the frame's custody runs out, as a Bluetooth redial or a Wi-Fi Aware sync does. A neighbour that links again is
     * pulled once at once, but that pull spends a hop of the budget like any other, so a link that keeps re-forming
     * keeps neither want alive (the blobs-reviewer's second and third passes).
     */
    @Test
    fun aFlappingLinkBetweenTwoExCarriersDoesNotKeepTheirWantsAlive() =
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

            val pulls = { from: LabNode, to: LabNode -> from.transport.blobAsks.count { it.hash == st.hash && it.to == to.nodeId } }
            val start = pulls(st.bob, st.carol) to pulls(st.carol, st.bob)
            val perRound = mutableListOf<Pair<Int, Int>>()
            repeat(FLAP_ROUNDS) {
                val before = pulls(st.bob, st.carol) to pulls(st.carol, st.bob)
                lab.unlink(st.bob, st.carol)
                lab.clock.advance(FLAP_MS)
                two.forEach { node -> node.sweepExpired() }
                val digests = st.bob.transport.digestsSent.size to st.carol.transport.digestsSent.size
                lab.link(st.bob, st.carol)
                // The link-up batch's last send is its digest: its re-arm, query and newcomer pull went ahead of it.
                lab.await(1) {
                    if (st.bob.transport.digestsSent.size > digests.first && st.carol.transport.digestsSent.size > digests.second) 1 else 0
                }
                two.forEach { node -> node.reoffer() }
                quiesce(two)
                perRound += (pulls(st.bob, st.carol) - before.first) to (pulls(st.carol, st.bob) - before.second)
            }
            val total = (pulls(st.bob, st.carol) - start.first) to (pulls(st.carol, st.bob) - start.second)
            assertTrue("at most a pull per link-up per side: $perRound", total.first <= FLAP_ROUNDS && total.second <= FLAP_ROUNDS)
            assertTrue("and none once both wants are gone: $perRound", perRound.takeLast(FLAP_ROUNDS / 2).all { it == (0 to 0) })
            assertFalse("Bob still wants it", st.bob.wants(st.hash))
            assertFalse("Carol still wants it", st.carol.wants(st.hash))
            lab.assertConverged(two, atLeast = 0) { Conversations.NEARBY }
        }

    /**
     * Alice wants a group picture (a group's row has no deadline), Bob is her neighbour and an echo of her pulls, kept
     * alive by them, and Bob's other neighbour Dave cannot answer a holder query (the iOS port today): Bob can only ask
     * him blind. Dave asks Bob back with a hop less, which keeps nothing alive, and comes to hold the picture after Bob's
     * second cycle. Bob still asks him blind in every cycle that opens, so the picture reaches Alice within two cycle
     * gaps, not a day later (the blobs-reviewer's second pass).
     */
    @Test
    fun anEchoStillAsksAThirdPartyBlindAndBringsWhatItCameToHold() =
        runBlocking {
            val short = querying.copy(custodyTtlMs = SHORT_CUSTODY_MS)
            val sam = lab.node("sam", short).apply { setDisplayName("Sam") }
            val alice = lab.node("alice", short).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", short).apply { setDisplayName("Bob") }
            val dave = lab.node("dave", short.copy(blobQuery = false, answersHolderQueries = false)).apply { setDisplayName("Dave") }
            lab.linkAll(alice to bob, bob to dave)
            lab.awaitAcquainted(alice, bob)
            lab.awaitAcquainted(bob, dave)
            awaitLinkUpHooks(alice to bob, bob to alice, bob to dave, dave to bob)
            lab.link(sam, alice) { sam.transport.holdFiles(alice.transport) }
            lab.awaitAcquainted(sam, alice)
            lab.befriend(sam, alice)
            awaitLinkUpHooks(sam to alice, alice to sam)
            val groupId = sam.createGroup(alice)
            assertTrue(sam.sendGroup(groupId, "hello"))
            lab.await(1) { alice.rowsIn(groupId).size }

            assertTrue(sam.sendImage(Random(2_195).nextBytes(4_096), "for the group", groupId = groupId))
            val id = sam.ownMessageId(groupId, "for the group")
            val hash = checkNotNull(sam.attachmentHash(groupId, id))
            val sealed = checkNotNull(sam.blobs.bytes(hash)) { "Sam holds the bytes he sent" }
            lab.await(1) { if (alice.attachmentHash(groupId, id) != null) 1 else 0 }
            lab.await(1) { sam.transport.heldFiles(alice.transport).count { it == hash } }
            lab.await(1) { if (listOf(bob, dave).all { id in it.custodyIds() }) 1 else 0 }
            lab.retire(sam)
            lab.await(1) { if (sam.nodeId !in alice.manager.linksActedOn) 1 else 0 }
            val three = listOf(alice, bob, dave)
            quiesce(three)

            // Custody is over everywhere: nothing of Bob's or Dave's own names the picture, only Alice's row does.
            lab.clock.advance(PAST_SHORT_CUSTODY_MS)
            three.forEach { it.sweepExpired() }
            three.forEach { it.reoffer() }
            quiesce(three)
            repeat(ECHO_SPENT_STEPS) { step(three) }
            assertTrue("Bob echoes Alice's pull", bob.wants(hash))

            dave.blobs.insert(hash, "image/jpeg", sealed) // what Dave's own slow pull brought him
            val asked = bob.transport.blobAsks.count { it.hash == hash && it.to == dave.nodeId }
            step(three) // the first gap: Alice's pull opens Bob's next cycle, which asks Dave blind, and Dave serves
            lab.await(1) { if (bob.blobs.exists(hash)) 1 else 0 }
            assertTrue(
                "Bob, past his first cycles, asked Dave blind",
                bob.transport.blobAsks.count { it.hash == hash && it.to == dave.nodeId } > asked,
            )
            step(three) // the second: Alice's next cycle finds Bob holding it
            lab.await(1) { if (alice.blobs.exists(hash)) 1 else 0 }
            lab.assertConverged(three, atLeast = 0) { Conversations.NEARBY }
        }

    /**
     * Alice has wanted a group picture for over a day, nobody near holding it, so her cycles stand for many minutes.
     * Carol links mid-cycle: she lacks the picture but is linked to Sam, who holds it. Alice asks her to pull it through
     * at once, in the link-up's own step — not at the next cycle, up to about 3.2 h off past a day — and holds it at
     * her next cycle (the blobs-reviewer's second pass).
     */
    @Test
    fun aLongDeadRootAsksALackingNewcomerToPullAtOnce() =
        runBlocking {
            val sam = lab.node("sam", querying).apply { setDisplayName("Sam") }
            val alice = lab.node("alice", querying).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", querying).apply { setDisplayName("Bob") }
            val carol = lab.node("carol", querying).apply { setDisplayName("Carol") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            awaitLinkUpHooks(alice to bob, bob to alice)
            lab.link(sam, alice) { sam.transport.holdFiles(alice.transport) }
            lab.awaitAcquainted(sam, alice)
            lab.befriend(sam, alice)
            awaitLinkUpHooks(sam to alice, alice to sam)
            val groupId = sam.createGroup(alice)
            assertTrue(sam.sendGroup(groupId, "hello"))
            lab.await(1) { alice.rowsIn(groupId).size }
            assertTrue(sam.sendImage(Random(3_195).nextBytes(4_096), "a day late", groupId = groupId))
            val id = sam.ownMessageId(groupId, "a day late")
            val hash = checkNotNull(sam.attachmentHash(groupId, id))
            lab.await(1) { if (alice.attachmentHash(groupId, id) != null) 1 else 0 }
            lab.await(1) { sam.transport.heldFiles(alice.transport).count { it == hash } }
            lab.await(1) { if (id in bob.custodyIds()) 1 else 0 }
            lab.unlink(sam, alice) // Sam walks off with the only copy, and stays up
            val two = listOf(alice, bob)
            quiesce(two)

            // A dead streak begins, then a day passes, then the cycles stretch: 12 min, then 24.
            repeat(STREAK_STEPS) { step(two) }
            lab.clock.advance(A_DAY_MS)
            two.forEach { it.reoffer() }
            quiesce(two)
            lab.clock.advance(LONG_GAP_MS)
            two.forEach { it.reoffer() }
            quiesce(two)
            assertFalse(alice.blobs.exists(hash))

            lab.link(carol, sam)
            lab.awaitAcquainted(carol, sam)
            val pulls = { alice.transport.blobAsks.count { it.hash == hash && it.to == carol.nodeId } }
            lab.link(carol, alice)
            lab.await(1) { pulls() } // in the link-up's own step, mid-cycle
            lab.await(1) { if (carol.blobs.exists(hash)) 1 else 0 }
            assertEquals("Carol was asked once", 1, pulls())

            lab.clock.advance(NEXT_LONG_GAP_MS)
            listOf(alice, bob, carol).forEach { it.reoffer() }
            lab.await(1) { if (alice.blobs.exists(hash)) 1 else 0 }
            // Bob and Carol never linked, and a day has expired what custody carried between them: Bob takes part as a
            // carrier.
            lab.assertConverged(listOf(alice, carol), atLeast = 0, carriers = listOf(bob)) { Conversations.NEARBY }
        }
}
