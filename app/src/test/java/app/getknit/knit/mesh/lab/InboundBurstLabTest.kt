package app.getknit.knit.mesh.lab

import app.getknit.knit.data.forward.ForwardRepository
import app.getknit.knit.mesh.SeenSet
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
 *
 * A phone's router reads its frames out of a bounded queue, though. Its Bluetooth plane used to hand each one over with a
 * `tryEmit` whose refusal nobody read, so a frame that found the queue full was lost, after both ends had counted it as
 * crossed (#331). Now the link's reader waits for room (ADR 2026-11.43f7), and [LabLimits.inboundSlots] plays that: the
 * frame waits on its pipe, and the send that carried it still returns at once.
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

    /**
     * The same burst at a router queued as a phone's is ([MeshLab.PHONE_INBOUND_SLOTS]): every send still returns, what
     * found the queue full waits on the link instead of being dropped, and once the router moves all of it reaches the
     * router's queue. The DM ahead of the burst lands, and its tick crosses.
     */
    @Test
    fun aPhoneQueueHoldsWhatFindsItFull() =
        runBlocking {
            val (alice, bob) = friendsWithASession(LabLimits(inboundSlots = MeshLab.PHONE_INBOUND_SLOTS))
            alice.custodyGate.hold { it.type == FrameType.CHAT && it.senderId == bob.nodeId && it.recipientId == alice.nodeId }
            assertTrue(bob.sendDm(alice, "read once the gate opens"))
            lab.await(1) { alice.custodyGate.parked }

            val burst = launch(Dispatchers.Default) { bob.burstSigned(alice, List(PHONE_BURST) { typingCue(bob, alice) }) }
            assertTrue("a send waited on the far router", lab.tryAwait(1) { if (burst.isCompleted) 1 else 0 })
            assertEquals(1, alice.custodyGate.parked)
            // The DM holds a slot and the burst could take only the rest: at least what was left over waits on the link.
            val leftOver = PHONE_BURST - (MeshLab.PHONE_INBOUND_SLOTS - 1).toLong()
            lab.await(1) { if (alice.transport.inboundHeld >= leftOver) 1 else 0 }

            alice.custodyGate.release()
            lab.assertConverged(listOf(alice, bob), atLeast = DMS) { it.dmWith(if (it === alice) bob else alice) }
            // Every cue reaches her router: the link holds none back. That is the claim, so the scenario ends here and does
            // not wait out the queue of cues behind the last one: under chaos each cue the router reads costs a lagged
            // dispatch, and reading a whole phone queue was most of a run (#334).
            lab.await(1, timeoutMs = BURST_AWAIT_MS) { if (alice.transport.inboundHeld == 0L) 1 else 0 }
        }

    /**
     * #331: Bob hands Alice, over their one link, more of his DMs than her router queues, with it parked on the first.
     * The claim turns on the queue being full, not on its size, so a queue of [BURST_SLOTS] plays a phone's 322 at a
     * scale a chaos run can afford. Each crossed the link, and both ends' crossings count it for ten minutes
     * ([SeenSet.DEFAULT_TTL_MS]), so a frame her queue lost was one his re-serves skipped for that long: custody stayed
     * apart, and a DM the custody cap evicted meanwhile was gone for good. Her link's reader waits for room instead, as
     * the iPhone's does (knit-ios ADR 2026-10.f5zs, rule 6): released, every DM lands over the link it came by, well
     * inside the window.
     */
    @Test
    fun aDmBurstPastAPhoneQueueLandsOverItsLink() =
        runBlocking {
            val (alice, bob) = friendsWithASession(LabLimits(inboundSlots = BURST_SLOTS))
            val from = lab.clock.now()
            alice.custodyGate.hold { it.type == FrameType.CHAT && it.senderId == bob.nodeId && it.recipientId == alice.nodeId }
            repeat(DM_BURST) { assertTrue(bob.sendDm(alice, "burst $it")) }
            lab.await(1) { alice.custodyGate.parked }

            // More than her queue holds came in behind the parked DM, so some of it waited on the link for room.
            lab.await(1) { if (alice.transport.inboundWaited > 0) 1 else 0 }

            alice.custodyGate.release()
            lab.assertConverged(listOf(alice, bob), atLeast = 2 + DM_BURST, timeoutMs = BURST_AWAIT_MS) {
                it.dmWith(if (it === alice) bob else alice)
            }
            assertTrue("the lab clock passed the crossing window", lab.clock.now() - from < SeenSet.DEFAULT_TTL_MS)
        }

    // Linked, contacts, and one DM each way delivered: what follows is ordinary ratchet traffic, not a first contact.
    private suspend fun friendsWithASession(aliceLimits: LabLimits = LabLimits()): Pair<LabNode, LabNode> {
        val alice = lab.node("alice", aliceLimits)
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

        /** How far [PHONE_BURST] overruns the phone's queue: enough waiting frames that a drop could not hide among them. */
        const val OVERFLOW = 64

        /**
         * [OVERFLOW] past what a phone queues: with the DM in the gate holding a slot, more than that many find the queue
         * full and wait on the link. The claim needs the queue to overflow, not to overflow threefold: under chaos each
         * cue the router reads costs a lagged dispatch, and the 500 this was took 15 s a run (#334).
         */
        const val PHONE_BURST = MeshLab.PHONE_INBOUND_SLOTS + OVERFLOW

        /** The router's queue in the DM burst: small, so the burst can overflow it with few sealed DMs. */
        const val BURST_SLOTS = 16

        /**
         * Four past what [BURST_SLOTS] queues, and far below the custody cap: with the first DM in the gate holding a slot,
         * at least four of the DMs behind it wait on the link. Each is sealed, custodied and ticked under chaos's lag, so
         * the burst stays just past the queue: twice it took 13 s a run (#334).
         */
        const val DM_BURST = BURST_SLOTS + 4

        /** What [DM_BURST] DMs, each sealed, custodied and ticked, take to converge on a busy runner. */
        const val BURST_AWAIT_MS = 120_000L
    }
}
