package app.getknit.knit.mesh.lab

import app.getknit.knit.data.message.Conversations
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
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
 * Process death at the wrong moment. [LabNode.restart] rebuilds the live stack over the same identity,
 * database and settings, so everything in memory — the AckSync hold, the `PendingInbound` park, the
 * `KeyExchange` missing set, the router's seen set — is gone and only what was committed remains. Each case
 * kills one role mid-protocol and expects the committed state (custody, sessions, receipts) to finish the job.
 */
@RunWith(RobolectricTestRunner::class)
class RestartLabTest {
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

    /** The receipt is a custodied frame: Alice dies before it lands and gets it from Bob's custody on relink. */
    @Test
    fun aSenderThatRestartsBeforeTheTickLandsStillGetsIt() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)

            bob.transport.hold(alice.transport)
            assertTrue(alice.sendDm(bob, "acked after a restart"))
            assertTrue(lab.tryAwait(1) { bob.decrypted(bob.dmWith(alice)).size })
            assertTrue("bob's tick was held", lab.tryAwait(1) { bob.transport.held(alice.transport).count { it.isChatFrom(bob.nodeId) } })

            alice.restart() // the held tick dies with the link
            lab.link(alice, bob)
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { it.dmWith(if (it === alice) bob else alice) }
        }

    /** Custody is in the database: the carrier restarts between taking the DM and meeting its recipient. */
    @Test
    fun aCarrierThatRestartsMidCarryStillServes() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            lab.linkAll(alice to bob, bob to carol)
            lab.awaitAcquainted(alice, bob, carol)

            lab.unlink(bob, carol)
            assertTrue(alice.sendDm(carol, "carried across a restart"))
            assertTrue(lab.tryAwait(1) { bob.custodiedChatsFrom(alice, carol) })
            lab.unlink(alice, bob)
            bob.restart()

            lab.link(bob, carol)
            lab.assertConverged(listOf(carol), atLeast = 1, carriers = listOf(bob)) { it.dmWith(alice) }
            lab.link(alice, bob)
            lab.assertConverged(listOf(alice, carol), atLeast = 1, carriers = listOf(bob)) { it.dmWith(if (it === alice) carol else alice) }
        }

    /**
     * Carol parks a stranger's frame and asks for the key, then dies before the answer. The park and the
     * request are memory; the relink's digest exchange serves the profile and the frame again.
     */
    @Test
    fun aRecipientThatRestartsWithAFrameParkedForAKeyRecoversIt() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            lab.link(bob, carol)
            lab.awaitAcquainted(bob, carol)

            bob.transport.hold(carol.transport)
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            assertTrue(alice.sendRoom("parked on carol"))
            assertTrue(lab.tryAwait(1) { bob.transport.held(carol.transport).count { it.isRoomPostFrom(alice.nodeId) } })
            // The pipe stays held through the release: the served key carol asks for is parked too, so she dies
            // still waiting for it (a hold re-applied after the release leaves a gap the whole key round trip fits).
            bob.transport.release(carol.transport, keepHolding = true) { batch -> batch.filter { it.isRoomPostFrom(alice.nodeId) } }
            assertTrue(
                "carol never parked the frame",
                lab.tryAwait(1) {
                    carol.metrics
                        .snapshot()
                        .framesHeld
                        .toInt()
                },
            )

            carol.restart()
            lab.link(bob, carol)
            lab.assertConverged(listOf(bob, carol), atLeast = 1, carriers = listOf(alice)) { Conversations.NEARBY }
        }

    /** The session is in the database too: both die, both come back, the ratchet carries on. */
    @Test
    fun bothSidesRestartAndTheRatchetSessionCarriesOn() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            assertTrue(alice.sendDm(bob, "before"))
            lab.await(1) { bob.decrypted(bob.dmWith(alice)).size }
            assertTrue(bob.sendDm(alice, "before too"))
            lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }
            val root = alice.session(bob)?.rootHash

            alice.restart()
            bob.restart()
            lab.link(alice, bob)
            assertTrue(alice.sendDm(bob, "after"))
            assertTrue(bob.sendDm(alice, "after too"))
            lab.assertConverged(listOf(alice, bob), atLeast = 4) { it.dmWith(if (it === alice) bob else alice) }
            assertEquals("the same session, not a new one", root, alice.session(bob)?.rootHash)
        }

    private fun WireEnvelope.isChatFrom(nodeId: String): Boolean =
        WireCodec.decodeEnvelope(signed)?.let { it.type == FrameType.CHAT && it.senderId == nodeId } == true
}
