package app.getknit.knit

import app.getknit.knit.mesh.InboundFrame
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.PartialBlobs
import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.ReceivedDigest
import app.getknit.knit.mesh.ReceivedFile
import app.getknit.knit.mesh.TransportKind
import app.getknit.knit.mesh.bluetooth.LinkStamp
import app.getknit.knit.mesh.link.FramedLink
import app.getknit.knit.mesh.link.LinkCallbacks
import app.getknit.knit.mesh.link.LinkSocket
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * What a Bluetooth link stamps on the frames and digests it delivers ([LinkStamp], the custody-carry-policy contract's §3
 * and §6): the link's own proof, and the link itself as the token `BluetoothMeshTransport.linkToken` answers with. The
 * plane's `links` is a map here, swapped as `registerLink` swaps it. The links are never started: a stamp reads only
 * the link's identity and its registration-time proof.
 */
class LinkStampTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob())

    // The plane's `links`: which link holds the peer's node id now.
    private val links = ConcurrentHashMap<String, FramedLink>()

    @After
    fun tearDown() {
        links.values.forEach { it.close() }
        scope.cancel()
    }

    @Test
    fun aProvenLinksFrameAndDigestCarryItsProofAndItsToken() {
        val link = register(proven = true)

        val frame = LinkStamp.frame(frame(), link)
        val digest = LinkStamp.digest(ReceivedDigest(PEER, listOf("a")), link) { current(link) }

        assertTrue(frame.proven)
        assertTrue(digest.proven)
        assertSame(LinkStamp.linkToken(links, PEER), frame.link)
        assertSame(LinkStamp.linkToken(links, PEER), digest.link)
    }

    @Test
    fun anUnprovenLinkStampsItselfButNoProof() {
        val link = register(proven = false)

        val frame = LinkStamp.frame(frame(), link)
        val digest = LinkStamp.digest(ReceivedDigest(PEER, listOf("a")), link) { current(link) }

        assertFalse(frame.proven)
        assertFalse(digest.proven)
        assertSame(link, frame.link)
        assertSame(LinkStamp.linkToken(links, PEER), digest.link)
    }

    @Test
    fun aLinkReplacedUnderTheSameIdIsANewToken() {
        val old = register(proven = true)
        val stamped = LinkStamp.digest(ReceivedDigest(PEER, listOf("a")), old) { current(old) }

        val fresh = register(proven = true) // registerLink's `links.put` under the same node id

        val token = LinkStamp.linkToken(links, PEER)
        assertSame(fresh, token)
        assertNotSame(stamped.link, token) // the old link's stamps stop matching: the ledger's relink clear
        old.close()
    }

    @Test
    fun aDigestOffALinkNoLongerHeldIsAnsweredButCarriesNoProof() {
        val old = register(proven = true)
        val fresh = register(proven = true) // a #142 replacement or an R3 displacement, while old's reader decodes

        val late = LinkStamp.digest(ReceivedDigest(PEER, listOf("a")), old) { current(old) }

        assertFalse(late.proven) // custody must not read it as a relink and clear the peer's marks
        assertSame(old, late.link)
        assertTrue(LinkStamp.digest(ReceivedDigest(PEER, listOf("a")), fresh) { current(fresh) }.proven)
        old.close()
    }

    @Test
    fun theHeldLinksProofIsWhatLinkProvenReads() {
        assertFalse(LinkStamp.linkProven(links, PEER)) // no link
        val unproven = register(proven = false)
        assertFalse(LinkStamp.linkProven(links, PEER))
        register(proven = true) // a proven link takes the id's place
        assertTrue(LinkStamp.linkProven(links, PEER))
        unproven.close()
    }

    @Test
    fun theCarrierPairsOneLinkWithItsOwnProof() {
        assertNull(LinkStamp.carrier(links, PEER, TransportKind.Bluetooth)) // no link
        val unproven = register(proven = false)
        val first = LinkStamp.carrier(links, PEER, TransportKind.Bluetooth)!!
        assertSame(unproven, first.link)
        assertFalse(first.proven)

        val proven = register(proven = true) // an R3 displacement under the same id
        val next = LinkStamp.carrier(links, PEER, TransportKind.Bluetooth)!!
        assertSame(proven, next.link)
        assertTrue(next.proven)
        assertEquals(TransportKind.Bluetooth, next.kind)
        unproven.close()
    }

    @Test
    fun noLinkIsNoToken() {
        assertNull(LinkStamp.linkToken(links, PEER))
        val link = register(proven = true)
        links.remove(PEER, link) // teardownLink(only = link)
        assertNull(LinkStamp.linkToken(links, PEER))
        link.close()
    }

    // A side-channel page is unstamped: the side listener builds its frame as `InboundFrame(wire, env, env.senderId)`, and
    // no link carried it. Nothing here could drive that builder, so it is noted, not asserted: a test of the defaults
    // `InboundFrame` takes would pass whatever the listener did.

    // The transport's check: `links` still holds this link for its node id.
    private fun current(link: FramedLink) = links[link.nodeId] === link

    private fun register(proven: Boolean): FramedLink {
        val link =
            FramedLink(
                nodeId = PEER,
                peer = Peer(PEER),
                socket = Socket(),
                scope = scope,
                cacheDir = tmp.root,
                partials = PartialBlobs(tmp.newFolder(), minBytes = 1),
                metrics = MeshMetrics(),
                callbacks = Ignored,
                now = System::currentTimeMillis,
                proven = proven,
            )
        links[PEER] = link
        return link
    }

    private fun frame(): InboundFrame {
        val env = RelayEnvelope(type = FrameType.CHAT, id = "f1", senderId = PEER, payload = ByteArray(0))
        return InboundFrame(WireEnvelope(sig = "f1".toByteArray(), signed = WireCodec.encodeEnvelope(env)), env, PEER)
    }

    private class Socket : LinkSocket {
        override val input: InputStream = ByteArrayInputStream(ByteArray(0))
        override val output: OutputStream = OutputStream.nullOutputStream()

        override fun close() = Unit
    }

    private object Ignored : LinkCallbacks {
        override suspend fun onInbound(frame: InboundFrame) = Unit

        override fun onDigest(digest: ReceivedDigest) = Unit

        override fun onFile(file: ReceivedFile) = Unit

        override fun onLinkDown(nodeId: String) = Unit
    }

    private companion object {
        const val PEER = "peer-b"
    }
}
