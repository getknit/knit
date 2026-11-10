package app.getknit.knit.mesh.lab

import app.getknit.knit.call.CallPhase
import app.getknit.knit.mesh.protocol.CallPayload
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A voice call's signalling between real stacks (docs/VOICE_CALLS.md §3, ADR 2026-10.3tee): each phase is a sealed
 * `CTL_CALL` DM that rides the mesh as any DM does — across a relay that can read none of it — into the far call layer
 * and nowhere else: no row in a thread, no tick owed, and nothing in the relay's call layer (C-3.1-1, C-3.1-3). The
 * media is [LabCallMedia], which connects once a side holds both descriptions; what is under test is what the two
 * phones exchange. And C-3.4-3 across a process death: a call whose end a phone heard before restarting never rings
 * when its offer reaches the relaunched process.
 */
@RunWith(RobolectricTestRunner::class)
class CallLabTest {
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

    /** [a] and [b] trade a DM each, as two contacts have: a confirmed session, and each other's accepted contact. */
    private suspend fun trade(
        a: LabNode,
        b: LabNode,
        carriers: List<LabNode>,
    ) {
        assertTrue(a.sendDm(b, "${a.name} opens"))
        lab.assertConverged(listOf(a, b), atLeast = 1, carriers = carriers) { it.dmWith(if (it === a) b else a) }
        assertTrue(b.sendDm(a, "${b.name} answers"))
        lab.assertConverged(listOf(a, b), atLeast = 2, carriers = carriers) { it.dmWith(if (it === a) b else a) }
    }

    private suspend fun LabNode.awaitPhase(phase: CallPhase) = lab.await(1) { if (calls.state.value?.phase == phase) 1 else 0 }

    @Test
    fun aCallRingsConnectsAndEndsAcrossARelayIntoTheCallLayersAlone() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val relay = lab.node("relay").apply { setDisplayName("Relay") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            lab.linkAll(alice to relay, relay to bob)
            lab.awaitAcquainted(alice, relay, bob)
            trade(alice, bob, carriers = listOf(relay))

            assertNull(alice.calls.start(bob.nodeId))
            bob.awaitPhase(CallPhase.Alerting)
            // Bob's RINGING came back the same way: Alice's screen moves from "Calling" to "Ringing".
            alice.awaitPhase(CallPhase.Ringing)
            val id = checkNotNull(bob.calls.state.value).id
            assertEquals(checkNotNull(alice.calls.state.value).id, id)

            assertTrue(bob.calls.answer(id))
            alice.awaitPhase(CallPhase.Connected)
            bob.awaitPhase(CallPhase.Connected)

            alice.calls.hangUp()
            bob.awaitPhase(CallPhase.Ended)
            val ended = checkNotNull(bob.calls.state.value)
            assertEquals(CallPayload.REASON_USER, ended.reason)
            assertTrue("bob's call was ended by alice's END", ended.endedByPeer)
            assertNull("the relay's call layer heard none of it", relay.calls.state.value)

            // No phase reached a thread or owes a tick: each side still holds the two DMs it traded, and the stores
            // that carried the phases converge like any DM's.
            lab.assertConverged(listOf(alice, bob), atLeast = 2, carriers = listOf(relay)) { it.dmWith(if (it === alice) bob else alice) }
            assertEquals(2, alice.decrypted(alice.dmWith(bob)).size)
            assertEquals(2, bob.decrypted(bob.dmWith(alice)).size)
        }

    /**
     * Alice calls and hangs up by hand at once; the air loses her OFFER, so Bob hears only the CANCEL, and his process
     * dies. On the relink the OFFER reaches the relaunched process from Alice's custody, which still knows the call
     * ended (C-3.4-3): it neither rings nor counts a missed call (C-3.4-2), and the next call between them rings.
     */
    @Test
    fun aCallWhoseEndWasHeardBeforeARestartNeverRingsAfterIt() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            trade(alice, bob, carriers = emptyList())

            alice.transport.hold(bob.transport)
            val before = alice.dmsHeldFor(bob).size
            assertNull(alice.calls.start(bob.nodeId))
            lab.await(before + 1) { alice.dmsHeldFor(bob).size }
            val id = checkNotNull(alice.calls.state.value).id
            alice.calls.hangUp()
            lab.await(before + 2) { alice.dmsHeldFor(bob).size }
            // Held in send order: the OFFER is the first DM after the ones already parked, the CANCEL the next.
            val offer = alice.dmsHeldFor(bob)[before]
            // The air keeps losing the OFFER until Bob's process dies. On a composite node (a board beside the radio)
            // `CompositeMeshTransport.fastSend` launches a second copy, which can reach the pipe after the release: it
            // would reach the dying process, and the relaunched one, its custody already holding the OFFER, would never
            // be handed it. The restart drops the link, and the relink's pipe is clean.
            alice.transport.lossy(bob.transport) { it.signed.contentEquals(offer.signed) }
            alice.transport.release(bob.transport) { frames -> frames.filterNot { it.signed.contentEquals(offer.signed) } }
            lab.await(1) { if (id in bob.savedCallTombs()) 1 else 0 }
            val dying = bob.callLog.toList()

            bob.restart()
            lab.link(alice, bob)
            // The relaunched process's own log (each boot starts one).
            val judged = "call $id from ${alice.nodeId}: offer after its end"
            val heard = lab.tryAwait(1) { bob.callLog.count { it == judged } }
            assertTrue("the relaunched process never judged the offer: ${bob.callLog}, before the restart $dying", heard)
            assertTrue("the verdict came from the journal: ${bob.callLog}", bob.callLog.any { it.endsWith("kept from before") })
            assertNull("the late offer rang", bob.calls.state.value)

            assertNull(alice.calls.start(bob.nodeId))
            bob.awaitPhase(CallPhase.Alerting)
            alice.calls.hangUp()
            bob.awaitPhase(CallPhase.Ended)
            lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }
        }

    /**
     * GitHub #63 (ADR 2026-11.mfbq): Bob's phone has no network and no Wi-Fi Direct, so Alice's call from the chat, which
     * looked live to her, is refused `no_route` without ringing (C-3.3-8), and her phone places a mesh call instead
     * (C-3.3-9). It rings, connects and ends as any mesh call, and the phases leave nothing in either thread.
     */
    @Test
    fun aCallToANeighbourWithNoNetworkBecomesAMeshCall() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            trade(alice, bob, carriers = emptyList())
            alice.walkieCalls = true
            bob.walkieCalls = true
            bob.networked = false

            assertNull(alice.calls.start(bob.nodeId))
            // Bob never alerts for the live offer: the first call he rings for is the walkie.
            bob.awaitPhase(CallPhase.Alerting)
            val ringing = checkNotNull(bob.calls.state.value)
            assertTrue("bob rang for the live offer", ringing.walkie)
            val declined = bob.callLog.single { it.endsWith("no network and no group to join; declined") }
            val liveId = declined.removePrefix("call ").substringBefore(" ")
            lab.await(1) {
                val state = alice.calls.state.value
                if (state?.id == ringing.id && state.phase == CallPhase.Ringing) 1 else 0
            }
            val rePlaced = "call $liveId: the peer has no live path"
            assertTrue("alice's live call was not the one re-placed: ${alice.callLog}", alice.callLog.any { it.startsWith(rePlaced) })

            assertTrue(bob.calls.answer(ringing.id))
            alice.awaitPhase(CallPhase.Connected)
            bob.awaitPhase(CallPhase.Connected)
            alice.calls.hangUp()
            bob.awaitPhase(CallPhase.Ended)
            assertTrue("bob's mesh call was ended by alice's END", checkNotNull(bob.calls.state.value).endedByPeer)

            lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }
            assertEquals(2, alice.decrypted(alice.dmWith(bob)).size)
            assertEquals(2, bob.decrypted(bob.dmWith(alice)).size)
        }

    /** Every distinct DM-form chat frame this node holds parked for [to], in send order. */
    private fun LabNode.dmsHeldFor(to: LabNode): List<WireEnvelope> =
        transport
            .held(to.transport)
            .filter { env ->
                WireCodec.decodeEnvelope(env.signed)?.let {
                    it.type == FrameType.CHAT && it.senderId == nodeId && it.recipientId == to.nodeId
                } == true
            }.distinctBy { it.signed.toList() }
}
