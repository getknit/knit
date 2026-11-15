package app.getknit.knit.mesh.lab

import app.getknit.knit.data.forward.ForwardRepository
import app.getknit.knit.mesh.protocol.FrameId
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.TypingContent
import app.getknit.knit.mesh.protocol.WireCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
 * A send returns once the far end has the frame, however many frames its router has yet to read. A phone's link takes
 * a frame without waiting for anyone (`FramedLink.send` queues without a bound), and a router sends while it handles
 * a frame: a DM's tick, a key request, a frame handed on.
 *
 * The lab's pipe used to hand a frame straight into the far router's queue of 1,024 and wait when that was full. Two
 * routers each sending into the other's full queue then waited on each other for good, with every frame behind them
 * unread. A mobility simulation met it at 48 walkers, where a gather hands each node its neighbours' whole custody
 * at once: 15 to 21 DMs of 270 sat in a linked recipient's queue and never arrived. On that pipe this scenario's burst
 * never finishes crossing.
 */
@RunWith(RobolectricTestRunner::class)
class InboundBurstLabTest {
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
     * Alice stops at her custody write of Bob's DM, a database slow to commit, and Bob sends her as many frames as a
     * gathered node's neighbours could hand it at once. Every one of his sends returns with her router still parked.
     * Released, she reads the DM and her tick crosses to Bob.
     */
    @Test
    fun aSendReturnsHoweverFarBehindTheFarRouterIs() =
        runBlocking {
            val (alice, bob) = friendsWithASession()
            alice.custodyGate.hold { it.type == FrameType.CHAT && it.senderId == bob.nodeId && it.recipientId == alice.nodeId }
            assertTrue(bob.sendDm(alice, "read once the gate opens"))
            lab.await(1) { alice.custodyGate.parked }

            // Launched, so a pipe that makes a send wait fails the wait below instead of hanging the scenario.
            val burst = launch(Dispatchers.Default) { bob.burstSigned(alice, List(BURST) { typingCue(bob, alice) }) }
            val crossed = lab.tryAwait(1) { if (burst.isCompleted) 1 else 0 }
            assertTrue("a send waited on the far router: the burst never finished crossing", crossed)
            // All of it is queued behind a router that has not moved: Alice is still inside the DM.
            assertEquals(1, alice.custodyGate.parked)

            alice.custodyGate.release()
            // The DM is ahead of the burst in her queue. She is still reading cues when the oracle passes, and they
            // change nothing it weighs.
            lab.assertConverged(listOf(alice, bob), atLeast = DMS) { it.dmWith(if (it === alice) bob else alice) }
        }

    // Linked, contacts, and one DM each way delivered: what follows is ordinary ratchet traffic, not a first contact.
    private suspend fun friendsWithASession(): Pair<LabNode, LabNode> {
        val alice = lab.node("alice")
        val bob = lab.node("bob")
        lab.link(alice, bob)
        lab.awaitAcquainted(alice, bob)
        lab.befriend(alice, bob)
        assertTrue(alice.sendDm(bob, "hello"))
        lab.assertConverged(listOf(alice, bob), atLeast = 1) { it.dmWith(if (it === alice) bob else alice) }
        assertTrue(bob.sendDm(alice, "hello back"))
        lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }
        return alice to bob
    }

    // A "now typing" cue: read, shown and kept nowhere, so the burst leaves nothing for the oracle to weigh.
    private fun typingCue(
        from: LabNode,
        to: LabNode,
    ): RelayEnvelope =
        RelayEnvelope(
            type = FrameType.TYPING,
            id = FrameId.new(),
            senderId = from.nodeId,
            sentAt = from.now(),
            recipientId = to.nodeId,
            payload = WireCodec.encodePayload(TypingContent()),
        )

    private companion object {
        /**
         * Eight neighbours' full custody at once, about what a node in a gathered crowd is handed. What this pins is a
         * queue with room for that many, which is not the same as no bound. It is several times the 1,024 the pipe
         * used to hold, so the count does not turn on the 64 more a node with a board reads through its composite.
         */
        const val BURST = 8 * ForwardRepository.DEFAULT_MAX_ROWS

        /** Hello, its answer, and the one in the gate. */
        const val DMS = 3
    }
}
