package app.getknit.knit.mesh.lab

import app.getknit.knit.data.message.Conversations
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The two local presentation decisions the mesh must never observe (ADR 009, ADR 010): a message request
 * that was never accepted is custodied and relayed like any DM, and a blocked sender's room post is still
 * acked — blocking stays invisible to the blocked party — while never surfacing on the blocker. Both ADRs
 * say the decision is "never folded into custody/relay"; the full oracle is what checks that.
 */
@RunWith(RobolectricTestRunner::class)
class BlockAndRequestLabTest {
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
     * Finding #45 (2026-09-14, first run), decided the same day as ADR 2026-09.bts9: the delivery half always
     * held — Bob's room never shows the post and Alice still gets Bob's tick — but `InboundPipeline.canCarry`
     * refused a blocked author, so Bob's live set lacked every frame Alice sent while Carol's held them, and
     * the push-based digest exchange re-served them to Bob every round for as long as the block stood. The
     * block list left the carry gate; Bob rides the custody oracle as a carrier and must hold what Carol holds.
     */
    @Test
    fun aBlockedSendersRoomPostIsStillAckedAndBlockingStaysInvisible() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            lab.linkAll(alice to bob, bob to carol, alice to carol)
            lab.awaitAcquainted(alice, bob, carol)

            bob.block(alice)
            assertTrue(alice.sendRoom("from someone bob blocked"))
            val post = alice.ownMessageId(Conversations.NEARBY, "from someone bob blocked")
            lab.awaitReceipt(alice, post, bob)
            lab.awaitReceipt(alice, post, carol)
            assertNull("bob's room never shows the post", bob.roomPosts()[alice.nodeId])

            lab.assertConverged(listOf(alice, carol), atLeast = 1, carriers = listOf(bob)) { Conversations.NEARBY }
        }

    /** A message request Carol never answers is still carried by Bob and ticked by Carol: presentation only. */
    @Test
    fun aMessageRequestIsCustodiedAndRelayedLikeAnyDm() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            lab.linkAll(alice to bob, bob to carol)
            lab.awaitAcquainted(alice, bob, carol)

            assertTrue(alice.sendDm(carol, "a request"))
            lab.assertConverged(listOf(alice, carol), atLeast = 1, carriers = listOf(bob)) { it.dmWith(if (it === alice) carol else alice) }

            carol.accept(alice.nodeId)
            assertTrue(carol.sendDm(alice, "accepted"))
            lab.assertConverged(listOf(alice, carol), atLeast = 2, carriers = listOf(bob)) { it.dmWith(if (it === alice) carol else alice) }
        }
}
