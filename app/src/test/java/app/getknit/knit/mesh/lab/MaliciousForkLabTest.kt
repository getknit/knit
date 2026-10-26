package app.getknit.knit.mesh.lab

import app.getknit.knit.data.message.Conversations
import app.getknit.knit.mesh.BlobExchange
import app.getknit.knit.mesh.DropReason
import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.protocol.BlobReqContent
import app.getknit.knit.mesh.protocol.DEFAULT_TTL
import app.getknit.knit.mesh.protocol.FrameId
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import app.getknit.knit.mesh.sha256Hex
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
 * What a modified client that ignores the send-side rules can make honest phones do (the malicious-fork review): each
 * case is one frame no build sends, and what the mesh must not do with it. Every case ends in `assertConverged`.
 */
@RunWith(RobolectricTestRunner::class)
class MaliciousForkLabTest : StrandedLabBase() {
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
     * A blob request marked for relay, with the whole pull budget: honoured, it would have Bob pull Carol's picture
     * through for Mallory, and every node it was flooded to serve or pull it for the neighbour that handed it on. Bob
     * refuses it at the door, so nobody serves, wants, asks or queries for it. The same ask point-to-point is pulled
     * through and served, so the line could have carried the flood. That the router never floods it is pinned by
     * `MeshRouterTest.neverFloodsTheUnsignedBlobTrafficWhateverItsWrapperSays`: a relay waits out the router's real-time
     * jitter, which the lab cannot pin, so the reads of Bob's `sent` here back that test up and do not prove it.
     */
    @Test
    fun aBlobRequestMarkedForRelayIsNeitherHonouredNorFlooded() =
        runBlocking {
            val mallory = lab.node("mallory", querying).apply { setDisplayName("Mallory") }
            val bob = lab.node("bob", querying).apply { setDisplayName("Bob") }
            val carol = lab.node("carol", querying).apply { setDisplayName("Carol") }
            val dave = lab.node("dave", querying).apply { setDisplayName("Dave") }
            lab.linkAll(mallory to bob, bob to carol, carol to dave)
            lab.awaitAcquainted(mallory, bob)
            lab.awaitAcquainted(bob, carol)
            lab.awaitAcquainted(carol, dave)
            awaitLinkUpHooks(mallory to bob, bob to mallory, bob to carol, carol to bob, carol to dave, dave to carol)
            val picture = Random(1_001).nextBytes(4_096)
            val hash = sha256Hex(picture)
            carol.blobs.insert(hash, "image/jpeg", picture)
            val four = listOf(mallory, bob, carol, dave)

            val flood = ask(mallory, hash, relay = true)
            val floodId = checkNotNull(WireCodec.decodeEnvelope(flood.signed)).id
            mallory.transport.send(flood, Peer(bob.nodeId))
            lab.await(1) { bob.drops(DropReason.POINT_TO_POINT_FLOODED).toInt() }
            quiesce(four)
            assertTrue("Bob has not relayed it: ${bob.transport.sent}", bob.transport.sent.none { floodId in it })
            assertTrue("nobody wants it", four.none { it.wants(hash) })
            assertTrue("nobody asked for it but Mallory", (four - mallory).none { it.asksOf(hash) > 0 })
            assertTrue("nobody queried for it", four.none { n -> n.transport.blobQueries.any { hash in it.hashes } })
            assertTrue("nobody sent it", four.none { n -> n.transport.files.any { hash in it } })

            mallory.transport.send(ask(mallory, hash, relay = false), Peer(bob.nodeId))
            lab.await(1) { if (bob.holdsBlob(hash)) 1 else 0 }
            mallory.transport.send(ask(mallory, hash, relay = false), Peer(bob.nodeId))
            lab.await(1) { bob.transport.files.count { it == "${mallory.nodeId} ATTACHMENT $hash" } }
            quiesce(four)
            assertTrue("and never later: ${bob.transport.sent}", bob.transport.sent.none { floodId in it })
            assertFalse("Dave, past the hop that took it, never wanted it", dave.wants(hash))
            assertEquals("only Bob counted it", 0L, (four - bob).sumOf { it.drops(DropReason.POINT_TO_POINT_FLOODED) })
            lab.assertConverged(four, atLeast = 0) { Conversations.NEARBY }
        }

    /** A `blobreq` for [hash] with the whole pull budget, as a current build's root sends it, but for [relay]. */
    private fun ask(
        from: LabNode,
        hash: String,
        relay: Boolean,
    ): WireEnvelope {
        val content = BlobReqContent(hash, pull = BlobExchange.PULL_BUDGET)
        val env =
            RelayEnvelope(type = FrameType.BLOB_REQ, id = FrameId.new(), senderId = from.nodeId, payload = WireCodec.encodePayload(content))
        return WireEnvelope(ttl = DEFAULT_TTL, relay = relay, sig = ByteArray(0), signed = WireCodec.encodeEnvelope(env))
    }
}
