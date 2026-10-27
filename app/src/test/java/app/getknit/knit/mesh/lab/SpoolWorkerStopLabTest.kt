package app.getknit.knit.mesh.lab

import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.spool.FakeSpool
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A relay's worker stopped while the frame it pulled is still being delivered (#141, finding 11). `ScopeSync` hands a
 * pulled frame to `MeshRouter.handleInbound` on the worker's own coroutine, and a reconcile that no longer wants the
 * relay stops the worker (`Worker.stop` → `job.cancel()`). The router has already marked the frame seen and the
 * pipeline has already custodied it, so the copy the worker was delivering is the only one this node will take for a
 * `SeenSet` window: whatever the cancellation cut short is never done.
 *
 * Each case stalls the delivery at a custody write ([LabCustodyGate]) so the stop lands inside it on every run. The
 * radio's collector is the same shape: `MeshManager.stop` (a pause is one) cancels it with a frame still mid-delivery,
 * so the last case stops the receiver itself. `MeshRouter.handleInbound` finishes an admitted delivery whatever its
 * caller does (ADR 2026-10.f82h), so every case releases the gate before it ends: a held delivery can no longer be cut.
 */
@RunWith(RobolectricTestRunner::class)
class SpoolWorkerStopLabTest {
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

    private suspend fun LabNode.dmScopeWith(peer: LabNode) =
        manager
            .spoolStatus()
            .flatMap { it.scopes }
            .firstOrNull { it.label == peer.nodeId && !it.pair && !it.retiring }

    /**
     * Alice and Bob on one relay, apart, with Alice off it while Bob writes: the DM reaches her only through the pull
     * her next session's first round makes, on that worker's coroutine (an event would ride the pump instead).
     */
    private suspend fun aDmWaitingAtTheRelay(): Pair<LabNode, LabNode> {
        val spool = FakeSpool()
        val alice = lab.node("alice", spool = spool).apply { setDisplayName("Alice") }
        val bob = lab.node("bob", spool = spool).apply { setDisplayName("Bob") }
        lab.meetOnTheRelay(alice, bob)
        lab.leaveRelay(alice)
        val before = checkNotNull(bob.dmScopeWith(alice)).localCount
        assertTrue(bob.sendDm(alice, "pulled as the worker stops"))
        // Bob's push has landed: his custody grew and the relay's digest agrees with it again.
        lab.await(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) {
            val scope = bob.dmScopeWith(alice)
            if (scope != null && scope.localCount > before && scope.converged) 1 else 0
        }
        return alice to bob
    }

    /** Stops Alice's worker while the delivery is parked at [gate]'s write, then lets the write go and the pair meet. */
    private suspend fun stopTheWorkerMidDelivery(
        alice: LabNode,
        bob: LabNode,
    ) {
        lab.rejoinRelay(alice)
        lab.await(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { alice.custodyGate.parked }
        lab.leaveRelay(alice)
        alice.custodyGate.release()
        lab.rejoinRelay(alice)
        lab.link(alice, bob)
        lab.assertConverged(listOf(alice, bob), atLeast = 3, timeoutMs = MeshLab.SPOOL_AWAIT_MS) {
            it.dmWith(if (it === alice) bob else alice)
        }
    }

    /** The cut after the row's commit: Alice holds the DM, and its receipt is never sent. */
    @Test
    fun aDmWhoseWorkerStopsAfterItsRowIsStillAcked() =
        runBlocking {
            val (alice, bob) = aDmWaitingAtTheRelay()
            // Alice's own receipt to Bob, custodied before it floods: the delivery is past the row and short of the tick.
            alice.custodyGate.hold { it.senderId == alice.nodeId && it.recipientId == bob.nodeId && it.type == FrameType.CHAT }
            stopTheWorkerMidDelivery(alice, bob)
        }

    /**
     * The radio's half: Bob's DM reaches Alice over a link and the mesh stops while her delivery is past the row and
     * short of the tick. The stop cancels the router's inbound collector, which was the one running the delivery.
     * Released, the receipt is custodied first, then handed on by its flood or by the relaunch's digest exchange: a
     * lab node's pipes outlive `manager.stop()` until the restart disconnects them, so either path can carry it. The
     * cut itself is what the scenario pins.
     */
    @Test
    fun aDmWhoseReceiverStopsMidDeliveryIsStillAcked() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            alice.custodyGate.hold { it.senderId == alice.nodeId && it.recipientId == bob.nodeId && it.type == FrameType.CHAT }
            assertTrue(bob.sendDm(alice, "delivered as the mesh stops"))
            lab.await(1) { alice.custodyGate.parked }
            // The stop alone first: a restart joins the session scope, which a delivery parked at the gate keeps alive.
            alice.manager.stop()
            alice.custodyGate.release()
            alice.restart()
            lab.link(alice, bob)
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { it.dmWith(if (it === alice) bob else alice) }
        }

    /** The cut before the dispatch: the frame is seen and never delivered. */
    @Test
    fun aDmWhoseWorkerStopsBeforeItsDispatchIsStillDelivered() =
        runBlocking {
            val (alice, bob) = aDmWaitingAtTheRelay()
            // Alice's custody of Bob's DM, written before the dispatch.
            alice.custodyGate.hold { it.senderId == bob.nodeId && it.recipientId == alice.nodeId && it.type == FrameType.CHAT }
            stopTheWorkerMidDelivery(alice, bob)
        }
}
