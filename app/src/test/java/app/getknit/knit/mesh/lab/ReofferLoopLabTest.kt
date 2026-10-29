package app.getknit.knit.mesh.lab

import app.getknit.knit.mesh.MeshRound
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The 60 s re-offer loop outlives a round that throws (#206). Before the fix it was a bare `while (true)` in a
 * `launch`, and one throwing round ended it for the session: a Bluetooth link that stays up then never sent its custody
 * digest again, and a divergence that appeared after link-up never reconciled. The loop runs for real here, on a short
 * interval ([LabLimits.reofferIntervalMs]), not poked through [LabNode.reoffer].
 */
@RunWith(RobolectricTestRunner::class)
class ReofferLoopLabTest {
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
     * Alice's custody read fails for a while, so her re-offer rounds throw. Meanwhile Bob's DM to her is lost on the
     * link, which stays up. Bob holds the DM in custody and Alice does not, and only Alice's digest to Bob can heal that:
     * a digest makes its receiver push what the sender lacks (`ForwardSync.onDigest`), so Bob's own digest pushes nothing
     * to Alice. No link comes up, so the newcomer hooks don't run. Alice's digest goes out only from her re-offer round,
     * and only once the read answers again. The DM then arrives through the next round, and every check of the oracle
     * passes. A dead loop never sends that round.
     */
    @Test
    fun aReofferRoundThatThrowsDoesNotEndTheLoop() =
        runBlocking {
            val alice = lab.node("alice", LabLimits(reofferIntervalMs = REOFFER_MS)).apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)

            alice.custodyGate.failLiveIds()
            lab.await(1) { alice.failedReoffers() }
            // A round that read the ids before the gate failed them can still have its digest on the way. Its reply reads
            // Bob's store when it is computed, so one held back past the DM below would serve it once the link is clean.
            // Rounds run one after another and a failed one throws before it sends, so nothing sends another digest now.
            withContext(Dispatchers.Default) {
                bob.transport.awaitDigestsAnswered()
                alice.transport.awaitInboundDrained()
            }

            // Lost on the air, with the link up throughout. The DM is in Bob's custody before it is sent (#141).
            bob.transport.lossy(alice.transport) { it.isChatFrom(bob) }
            assertTrue(bob.sendDm(alice, "lost on the link"))
            bob.transport.lossy(alice.transport)
            val dm = bob.ownMessageId(bob.dmWith(alice), "lost on the link")
            // A round that runs after the loss also fails. That pins the loop as still alive, and Alice still lacks
            // the DM, so nothing but her round's digest was going to bring it.
            val failed = alice.failedReoffers()
            lab.await(failed + 1) { alice.failedReoffers() }
            assertTrue("bob carries his DM", dm in bob.custodyIds())
            assertFalse("and alice has not been handed it", dm in alice.custodyIds())

            alice.custodyGate.healLiveIds()
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { it.dmWith(if (it === alice) bob else alice) }
        }

    private fun LabNode.failedReoffers(): Int = metrics.frames().roundsFailedBy[MeshRound.REOFFER]?.toInt() ?: 0

    private fun WireEnvelope.isChatFrom(node: LabNode): Boolean =
        WireCodec.decodeEnvelope(signed)?.let { it.type == FrameType.CHAT && it.senderId == node.nodeId } == true

    private companion object {
        // Short enough that a round is a wait on an event, long enough that the rounds don't run back to back.
        const val REOFFER_MS = 300L
    }
}
