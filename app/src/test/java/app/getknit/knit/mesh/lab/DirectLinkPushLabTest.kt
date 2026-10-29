package app.getknit.knit.mesh.lab

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

/**
 * A contact's direct link answers a digest through the pair's view (ADR 2026-10.a5yj), so the two ends' buckets for one
 * sender can differ for good: the sender's end fills it from everything that sender writes and evicts its oldest, the far
 * end only from what the link carries. The far end then holds frames the other end evicted, and its answer pushes them;
 * the receiver keeps none of them, and its digest moves with every frame it writes. Each push is link use, so a re-push
 * every round kept the link open for good (#235, the 2026-10-28 P9 / P7 trial). A push the peer did not keep crosses once
 * per link-up (the 2026-10-29 amendment, `LinkPushRounds`); a frame it lacks and keeps still crosses.
 */
@RunWith(RobolectricTestRunner::class)
class DirectLinkPushLabTest {
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
     * Twenty frames per sender everywhere, the global cap out of reach. Alice, Bob and Dave share a group; Bob, who never
     * has a radio to Alice, meets the others through Dave, then leaves for an island of his own and keeps in touch with
     * Alice over a direct link, across which her group posts and Dave's reach him. Then Alice and Dave each write to Carol,
     * who has left, until Alice's buckets for both of them keep only those: her own posts (the author's case) and Dave's
     * (a sender whose bucket she fills from outside the view) are evicted on her end and still held on Bob's.
     *
     * Round after round, Alice writing Bob between them so her digest moves, Bob pushes each of those frames once per
     * link-up, counted where it leaves him (Alice's router hides a repeat). A DM Bob wrote while the link was down still
     * crosses at the next link-up, which also proves every round before it was answered: Bob answers digests one at a
     * time, in order.
     */
    @Test
    fun aFrameTheFarEndEvictedCrossesTheLinkOncePerLinkUp() =
        runBlocking {
            val limits = LabLimits(custodyMaxPerSender = PER_SENDER)
            val alice = lab.node("alice", limits, directLinks = true).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", limits, directLinks = true).apply { setDisplayName("Bob") }
            val carol = lab.node("carol", limits).apply { setDisplayName("Carol") }
            val dave = lab.node("dave", limits).apply { setDisplayName("Dave") }
            lab.linkAll(alice to carol, alice to dave, bob to dave)
            lab.awaitAcquainted(alice, bob, carol, dave)
            val group = alice.createGroup(bob, dave)
            assertTrue(alice.sendGroup(group, "founded"))
            lab.assertConverged(listOf(alice, bob, dave), atLeast = 1) { group }

            // Bob leaves for an island of his own and Carol for good; Alice and Bob link over the Internet.
            lab.unlink(bob, dave)
            lab.unlink(alice, carol)
            lab.directLink(alice, bob)
            (1..OLD).forEach { assertTrue(alice.sendGroup(group, "alice old $it")) }
            (1..OLD).forEach { assertTrue(dave.sendGroup(group, "dave old $it")) }
            val aliceOld = (1..OLD).map { alice.ownMessageId(group, "alice old $it") }
            val daveOld = (1..OLD).map { dave.ownMessageId(group, "dave old $it") }
            lab.await(2 * OLD) { bob.custodyIds().count { it in aliceOld || it in daveOld } }

            // Carol is not in the group, so none of this crosses the link: Alice's buckets for herself and Dave fill on her
            // end alone, and evict every old post Bob still holds.
            (1..PER_SENDER + 1).forEach { assertTrue(alice.sendDm(carol, "alice to carol $it")) }
            (1..PER_SENDER + 1).forEach { assertTrue(dave.sendDm(carol, "dave to carol $it")) }
            lab.await(1) { if (alice.custodyIds().none { it in aliceOld || it in daveOld }) 1 else 0 }
            assertTrue("bob lost the old posts: ${bob.custodyIds()}", bob.custodyIds().containsAll(aliceOld + daveOld))

            repeat(ROUNDS) { round(alice, bob, "first link, round $it") }
            relinkWithADmWrittenApart(alice, bob, "written apart")
            // Bob's own bucket for Alice fills too (seeds, profile, rounds, receipts): a count of nothing would blame the wrong
            // cause if he had evicted one.
            assertTrue("bob lost an old post by link-up 1", bob.custodyIds().containsAll(aliceOld + daveOld))
            bob.assertCrossedOnce(alice, "alice's own post", aliceOld, linkUp = 1)
            bob.assertCrossedOnce(alice, "dave's post", daveOld, linkUp = 1)

            repeat(ROUNDS) { round(alice, bob, "second link, round $it") }
            relinkWithADmWrittenApart(alice, bob, "written apart again")
            // Bob's own bucket for Alice fills too (seeds, profile, rounds, receipts): a count of nothing would blame the wrong
            // cause if he had evicted one.
            assertTrue("bob lost an old post by link-up 2", bob.custodyIds().containsAll(aliceOld + daveOld))
            bob.assertCrossedOnce(alice, "alice's own post", aliceOld, linkUp = 2)
            bob.assertCrossedOnce(alice, "dave's post", daveOld, linkUp = 2)

            // Every round crossed the link: the two never shared a radio, so no radio digest ever went between them.
            assertFalse("alice sent bob a radio digest", bob.nodeId in alice.transport.digestsSent)
            assertFalse("bob sent alice a radio digest", alice.nodeId in bob.transport.digestsSent)
            assertTrue(bob.linkDigestsFrom.count { it == alice.nodeId } >= 2 * ROUNDS)

            // The two custody sets differ for good across the link (that is the subject); Alice's radio island agrees.
            alice.reoffer()
            dave.reoffer()
            lab.assertConverged(listOf(alice, bob), atLeast = 2 * ROUNDS + 2, custodyAcross = listOf(alice, dave)) {
                it.dmWith(if (it === alice) bob else alice)
            }
        }

    /**
     * One 60 s re-offer round on both ends after Alice writes Bob [text], which moves her digest: done once each end's
     * link plane has taken the other's digest and Bob has read the DM.
     */
    private suspend fun round(
        alice: LabNode,
        bob: LabNode,
        text: String,
    ) {
        val toBob = bob.linkDigestsFrom.count { it == alice.nodeId }
        val toAlice = alice.linkDigestsFrom.count { it == bob.nodeId }
        assertTrue(alice.sendDm(bob, text))
        alice.reoffer()
        bob.reoffer()
        lab.await(toBob + 1) { bob.linkDigestsFrom.count { it == alice.nodeId } }
        lab.await(toAlice + 1) { alice.linkDigestsFrom.count { it == bob.nodeId } }
        lab.await(1) { if (bob.decrypted(bob.dmWith(alice)).any { it.second == text }) 1 else 0 }
    }

    /**
     * The positive control, and the barrier behind each link-up's count: Bob writes Alice [text] with the link down, which
     * only his custody holds, so it reaches her in his answer to the next link-up's digest. Bob answers digests in order on
     * one collector, so by then every earlier one was answered.
     */
    private suspend fun relinkWithADmWrittenApart(
        alice: LabNode,
        bob: LabNode,
        text: String,
    ) {
        lab.cutDirectLink(alice, bob)
        assertTrue(bob.sendDm(alice, text))
        lab.directLink(alice, bob)
        assertTrue(
            "alice never read what bob wrote apart\n${lab.report(listOf(alice, bob))}",
            lab.tryAwait(1) { if (alice.decrypted(alice.dmWith(bob)).any { it.second == text }) 1 else 0 },
        )
    }

    // Each of [ids] was handed to this node's link plane for [to] exactly once during its [linkUp]th link-up.
    private fun LabNode.assertCrossedOnce(
        to: LabNode,
        what: String,
        ids: List<String>,
        linkUp: Int,
    ) = ids.forEach { id ->
        val crossings = linkFrames.count { it == "$linkUp ${to.nodeId} $id" }
        assertEquals("$what $id crossed once in link-up $linkUp: $linkFrames", 1, crossings)
    }

    private companion object {
        const val PER_SENDER = 20
        const val OLD = 4
        const val ROUNDS = 4
    }
}
