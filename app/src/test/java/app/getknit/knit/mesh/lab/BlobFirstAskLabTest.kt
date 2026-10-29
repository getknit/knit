package app.getknit.knit.mesh.lab

import app.getknit.knit.data.message.Conversations
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random

/**
 * A round of asks opens with the likeliest holder alone (work item #136, ADR 2026-10.f7gg). Every holder answers a
 * fresh ask, so the old first ask of every neighbour bought one whole copy from each holder in range — on 2026-10-04
 * the Pixel 9 took a room picture twice, 70 s of Bluetooth each. The file recorder on every transport is the phone's
 * `file …` line: these scenarios count the copies that reach the asker.
 */
@RunWith(RobolectricTestRunner::class)
class BlobFirstAskLabTest {
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

    private fun LabNode.filesTo(
        to: LabNode,
        hash: String,
    ) = transport.files.count { it == "${to.nodeId} ATTACHMENT $hash" }

    private fun LabNode.asksFor(hash: String) = transport.blobAsks.filter { it.hash == hash }.map { it.to }

    /**
     * Waits until each node has run its link-up blob re-ask toward each of its peers: its digest goes after it. Custody parity
     * can settle on the other ends' digests first, and a link-up batch still running re-arms wants from the database
     * and steps them: an ask this scenario did not mean to count.
     */
    private suspend fun awaitLinkUpHooks(vararg pairs: Pair<LabNode, LabNode>) =
        lab.await(1) { if (pairs.all { (node, peer) -> peer.nodeId in node.transport.digestsSent }) 1 else 0 }

    /**
     * The trial's shape: Alice posts a picture to the room, Bob pulls it, and only then does Carol hear of it, with
     * both of them linked to her and holding it. She asks the author alone and takes one copy.
     */
    @Test
    fun aPictureTwoNeighboursHoldIsAskedOfItsAuthorAloneAndCrossesOnce() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            lab.linkAll(alice to bob, bob to carol, alice to carol)
            lab.awaitAcquainted(alice, bob, carol)
            awaitLinkUpHooks(alice to bob, alice to carol, bob to alice, bob to carol, carol to alice, carol to bob)

            // Carol hears nothing until Bob holds the picture too: two holders, then the frame.
            alice.transport.hold(carol.transport)
            bob.transport.hold(carol.transport)
            val picture = Random(136).nextBytes(4_096)
            assertTrue(alice.sendImage(picture, "two holders"))
            val id = alice.ownMessageId(Conversations.NEARBY, "two holders")
            val hash = checkNotNull(alice.attachmentHash(Conversations.NEARBY, id))
            lab.await(1) { if (bob.blobs.exists(hash)) 1 else 0 }

            alice.transport.release(carol.transport)
            bob.transport.release(carol.transport)
            lab.assertConverged(listOf(alice, bob, carol), atLeast = 1) { Conversations.NEARBY }
            assertTrue(picture.contentEquals(carol.attachmentPlain(Conversations.NEARBY, id)))

            assertEquals("Carol asked the author alone: ${carol.asksFor(hash)}", listOf(alice.nodeId), carol.asksFor(hash))
            assertEquals("one copy, from Alice", 1, alice.filesTo(carol, hash))
            assertEquals("none from Bob, who held it too", 0, bob.filesTo(carol, hash))
        }

    /**
     * There is no negative answer on the wire, so a first choice that cannot serve costs the round its grace and no
     * more. Alice sends the group a picture and is out of Carol's reach. Bob, the hop Carol hears the frame from, has
     * its header but not its bytes (Alice's serve to him is parked); Dave holds them. Carol asks Bob first, and once
     * the grace is up the round widens to Dave, who serves her. A group, not the room: Carol's ✓✓ has to get two hops
     * home to Alice, and a room tick has no such path (ADR 2026-09.y5f3).
     */
    @Test
    fun aFirstChoiceThatCannotServeCostsTheRoundItsGraceThenTheRestAreAsked() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            val dave = lab.node("dave").apply { setDisplayName("Dave") }
            lab.linkAll(alice to bob, alice to dave, bob to carol, dave to carol)
            lab.awaitAcquainted(alice, bob, carol, dave)
            awaitLinkUpHooks(bob to alice, bob to carol, carol to bob, carol to dave, dave to alice, dave to carol)
            val groupId = alice.createGroup(bob, carol, dave)
            assertTrue(alice.sendGroup(groupId, "hello all"))
            lab.assertConverged(listOf(alice, bob, carol, dave), atLeast = 1) { groupId }

            alice.transport.holdFiles(bob.transport) // Bob reads the header and waits on the bytes
            dave.transport.hold(carol.transport) // Carol hears of the picture from Bob alone
            val picture = Random(137).nextBytes(4_096)
            val asksAtBob = bob.metrics.files().blobAsksHandled
            assertTrue(alice.sendImage(picture, "first choice lacks it", groupId = groupId))
            val id = alice.ownMessageId(groupId, "first choice lacks it")
            val hash = checkNotNull(alice.attachmentHash(groupId, id))
            lab.await(1) { if (dave.blobs.exists(hash)) 1 else 0 }
            lab.await(1) { alice.transport.heldFiles(bob.transport).count { it == hash } }

            // Bob relays the frame to Carol: she asks him first — the hop it came from, the author being out of reach.
            lab.await(1) { carol.asksFor(hash).size }
            assertEquals("the hop alone first: ${carol.asksFor(hash)}", listOf(bob.nodeId), carol.asksFor(hash))
            // Her ask reaching Bob is not Bob handling it: handled only once his bytes land, it would be a fresh ask he
            // rightly serves, a second copy no ask of hers caused (CI job 5268's shape). He handles it now, lacking them.
            lab.await(1) { if (bob.metrics.files().blobAsksHandled > asksAtBob) 1 else 0 }

            // The grace runs out with nothing arriving (the session's timer, or this tick — whichever comes first).
            lab.clock.advance(MeshLab.FIRST_ASK_GRACE_MS)
            carol.reoffer()
            lab.await(1) { if (carol.blobs.exists(hash)) 1 else 0 }
            assertEquals("then everyone else, once", listOf(bob.nodeId, dave.nodeId), carol.asksFor(hash))
            assertEquals("Dave served her", 1, dave.filesTo(carol, hash))
            assertEquals("Bob had nothing to serve", 0, bob.filesTo(carol, hash))

            dave.transport.release(carol.transport)
            alice.transport.releaseFiles(bob.transport)
            lab.assertConverged(listOf(alice, bob, carol, dave), atLeast = 2) { groupId }
            assertTrue(picture.contentEquals(carol.attachmentPlain(groupId, id)))
            assertEquals("still one copy to Carol", 1, dave.filesTo(carol, hash) + bob.filesTo(carol, hash))
        }
}
