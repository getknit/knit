package app.getknit.knit

import app.getknit.knit.mesh.InboundFrame
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.PartialBlobs
import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.ReceivedDigest
import app.getknit.knit.mesh.ReceivedFile
import app.getknit.knit.mesh.TransportKind
import app.getknit.knit.mesh.link.FramedLink
import app.getknit.knit.mesh.link.LinkCallbacks
import app.getknit.knit.mesh.link.LinkSocket
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import app.getknit.knit.mesh.wifiaware.NanLinkStamp
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
 * What a Wi-Fi Aware data path stamps on what it delivers ([NanLinkStamp], the custody-carry-policy contract's §3 and §6),
 * and the token `WifiAwareTransport.linkToken` answers with: the path's own [FramedLink], read off `peers`, whose entries
 * wrap it as `NanLink` does ([Path] here). `peers` is a map swapped as `registerConn` and `teardownPeer` swap it; the
 * links are never started, since a stamp reads only the link's identity and its registration-time proof.
 */
class NanLinkStampTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob())

    /** `NanLink`'s part a stamp reads: the [FramedLink] it wraps. */
    private class Path(
        val link: FramedLink,
    )

    // The plane's `peers`: which path holds the peer's node id now.
    private val peers = ConcurrentHashMap<String, Path>()

    @After
    fun tearDown() {
        peers.values.forEach { it.link.close() }
        scope.cancel()
    }

    @Test
    fun aProvenPathsFrameAndDigestCarryItsProofAndItsToken() {
        val path = register(proven = true)

        val frame = NanLinkStamp.frame(frame(), path.link)
        val digest = NanLinkStamp.digest(ReceivedDigest(PEER, listOf("a")), path.link, current = current(path))

        assertTrue(frame.proven)
        assertTrue(digest.proven)
        assertSame(token(), frame.link)
        assertSame(token(), digest.link)
        assertSame("the token is the FramedLink, not its wrapper", path.link, token())
    }

    @Test
    fun anUnprovenPathStampsItselfButNoProof() {
        val path = register(proven = false)

        val frame = NanLinkStamp.frame(frame(), path.link)
        val digest = NanLinkStamp.digest(ReceivedDigest(PEER, listOf("a")), path.link, current = current(path))

        assertFalse(frame.proven)
        assertFalse(digest.proven)
        assertSame(token(), frame.link)
        assertSame(token(), digest.link)
    }

    @Test
    fun everyPathIsANewToken() {
        val first = register(proven = true)
        val stamped = NanLinkStamp.digest(ReceivedDigest(PEER, listOf("a")), first.link, current = current(first))
        peers.remove(PEER, first) // teardownPeer(only = first): a clean close at quiescence
        assertNull("no path, no token", token())

        val next = register(proven = true)

        assertSame(next.link, token())
        assertNotSame("the last path's stamps never match the next one's token", stamped.link, token())
        first.link.close()
    }

    @Test
    fun aDigestOffAPathNoLongerThePeersIsAnsweredButCarriesNoProof() {
        val old = register(proven = true)
        val displacing = register(proven = true) // registerConn's `peers.put` while old's reader finishes a record

        val late = NanLinkStamp.digest(ReceivedDigest(PEER, listOf("a")), old.link, current = current(old))

        assertFalse("custody takes nothing from it", late.proven)
        assertSame(old.link, late.link)
        assertTrue(NanLinkStamp.digest(ReceivedDigest(PEER, listOf("a")), displacing.link, current = current(displacing)).proven)

        peers.remove(PEER, displacing) // torn down while the newcomer's reader finished one more
        val afterTeardown = NanLinkStamp.digest(ReceivedDigest(PEER, listOf("a")), displacing.link, current = current(displacing))
        assertFalse(afterTeardown.proven)
        old.link.close()
        displacing.link.close()
    }

    @Test
    fun linkProvenReadsTheCurrentPathsProof() {
        assertFalse("no path, no proof", proven())
        val unproven = register(proven = false)
        assertFalse(proven())
        val path = register(proven = true) // R3: a proven path displaces the unproven claim
        assertTrue(proven())
        peers.remove(PEER, path)
        assertFalse("torn down: no path, no proof", proven())
        unproven.link.close()
        path.link.close()
    }

    @Test
    fun theCarrierIsOnePathsTokenAndProof() {
        assertNull("no path, no carrier", carrier())
        val unproven = register(proven = false)
        val first = carrier()!!
        assertSame(unproven.link, first.link)
        assertFalse(first.proven)
        assertEquals(TransportKind.WifiAware, first.kind)

        val path = register(proven = true) // R3 displacement: the token and the proof move together
        val next = carrier()!!
        assertSame(path.link, next.link)
        assertTrue(next.proven)
        unproven.link.close()
    }

    // A hop-less coordination frame is unstamped: `emitFastWire` builds `InboundFrame(wire, env, hop ?: env.senderId)`,
    // and no data path carried it. Nothing here could drive that builder, so it is noted, not asserted: a test of the
    // defaults `InboundFrame` takes would pass whatever the plane did.

    // The transport's check, `peers[c.nodeId] === c`.
    private fun current(path: Path) = peers[path.link.nodeId] === path

    private fun token() = NanLinkStamp.token(peers, PEER) { it.link }

    private fun proven() = NanLinkStamp.proven(peers, PEER) { it.link }

    private fun carrier() = NanLinkStamp.carrier(peers, PEER, TransportKind.WifiAware) { it.link }

    private fun register(proven: Boolean): Path {
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
        return Path(link).also { peers[PEER] = it }
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
