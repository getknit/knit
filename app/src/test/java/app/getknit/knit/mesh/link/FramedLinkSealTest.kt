package app.getknit.knit.mesh.link

import app.getknit.knit.mesh.InboundFrame
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.PartialBlobs
import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.ReceivedDigest
import app.getknit.knit.mesh.ReceivedFile
import app.getknit.knit.mesh.crypto.LinkProof
import app.getknit.knit.mesh.crypto.LinkSeal
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * [FramedLink] over a [SealedLinkSocket] (#286, contract §4): an inner `KEY_SHARE` or `SEALED` record on a live link is a
 * failed seal, counted once by the socket's armed count, and closes the link; the same record on a plain link (a direct
 * link's, which shares FramedLink) only closes it. The test plays the remote dialer, sealing records toward the link.
 */
class FramedLinkSealTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private class RawLink(
        override val input: InputStream,
        override val output: OutputStream,
    ) : LinkSocket {
        override fun close() {
            runCatching { input.close() }
            runCatching { output.close() }
        }
    }

    private class Recording : LinkCallbacks {
        val inbound = LinkedBlockingQueue<InboundFrame>()
        val downs = LinkedBlockingQueue<String>()

        override fun onInbound(frame: InboundFrame) {
            inbound.add(frame)
        }

        override fun onDigest(digest: ReceivedDigest) = Unit

        override fun onFile(file: ReceivedFile) = Unit

        override fun onLinkDown(nodeId: String) {
            downs.add(nodeId)
        }
    }

    private val keys: Pair<LinkSeal.Keys, LinkSeal.Keys> by lazy {
        val d = LinkSeal.Ephemeral.fixed(ByteArray(32) { (0x40 + it).toByte() })
        val r = LinkSeal.Ephemeral.fixed(ByteArray(32) { (0x60 + it).toByte() })
        LinkSeal.derive(LinkProof.Role.DIALER, d, r.pub) to LinkSeal.derive(LinkProof.Role.RESPONDER, r, d.pub)
    }

    /** The link under test, the dialer's sealing end the test writes through, and the counts it raises. */
    private class Harness(
        val callbacks: Recording,
        val peer: OutputStream,
        val failures: AtomicInteger,
    )

    private fun start(sealed: Boolean): Harness {
        val toLink = PipedOutputStream()
        val linkInput = PipedInputStream(toLink, 1 shl 18)
        val raw = RawLink(linkInput, ByteArrayOutputStream())
        val failures = AtomicInteger()
        val socket: LinkSocket =
            if (sealed) {
                SealedLinkSocket(raw, keys.second, LinkProof.Role.RESPONDER).also { s ->
                    s.armFailureCount { failures.incrementAndGet() }
                }
            } else {
                raw
            }
        // The peer's end: the dialer's seal over the pipe toward the link, or the bare pipe for a plain link.
        val peer: OutputStream =
            if (sealed) {
                SealedLinkSocket(
                    RawLink(ByteArrayInputStream(ByteArray(0)), toLink),
                    keys.first,
                    LinkProof.Role.DIALER,
                ).output
            } else {
                toLink
            }
        val callbacks = Recording()
        FramedLink(
            nodeId = NODE,
            peer = Peer(NODE),
            socket = socket,
            scope = scope,
            cacheDir = tmp.root,
            partials = PartialBlobs(File(tmp.root, "partials"), minBytes = 1),
            metrics = MeshMetrics(),
            callbacks = callbacks,
            now = { 0L },
            proven = sealed,
        ).start()
        return Harness(callbacks, peer, failures)
    }

    private fun send(
        out: OutputStream,
        bytes: ByteArray,
    ) {
        out.write(bytes)
        out.flush()
    }

    private fun frame(id: String): ByteArray {
        val env = RelayEnvelope(type = FrameType.CHAT, id = id, senderId = NODE, payload = ByteArray(0))
        return WireCodec.encodeWire(WireEnvelope(sig = byteArrayOf(1), signed = WireCodec.encodeEnvelope(env)))
    }

    private fun down(h: Harness): String? = h.callbacks.downs.poll(2, TimeUnit.SECONDS)

    @Test
    fun anInnerKeyShareOnALiveSealedLinkClosesItAndCountsOneFailedSeal() {
        val h = start(sealed = true)
        send(h.peer, LinkFraming.encode(LinkFraming.Type.FRAME, frame("m1")))
        assertNotNull("records before it still land", h.callbacks.inbound.poll(2, TimeUnit.SECONDS))
        send(h.peer, LinkFraming.encodeKeyShare(ByteArray(LinkFraming.KEY_SHARE_BYTES) { 9 }))
        assertEquals(NODE, down(h))
        assertEquals(1, h.failures.get())
    }

    @Test
    fun anInnerSealedRecordOnALiveSealedLinkClosesItAndCountsOneFailedSeal() {
        val h = start(sealed = true)
        send(h.peer, sealedFrameOf(4))
        assertEquals(NODE, down(h))
        assertEquals(1, h.failures.get())
    }

    @Test
    fun anInnerKeyShareOfTheWrongLengthIsTheSameFailedSeal() {
        // Its header is out of range, so LinkFraming throws before the record reaches the read loop's `when`.
        val h = start(sealed = true)
        send(h.peer, byteArrayOf(LinkFraming.Type.KEY_SHARE.tag, 0, 0, 0, 5, 1, 2, 3, 4, 5))
        assertEquals(NODE, down(h))
        assertEquals(1, h.failures.get())
    }

    @Test
    fun aSealedLinkThatEndsCleanlyCountsNothing() {
        val h = start(sealed = true)
        send(h.peer, LinkFraming.encode(LinkFraming.Type.FRAME, frame("m1")))
        assertNotNull(h.callbacks.inbound.poll(2, TimeUnit.SECONDS))
        h.peer.close()
        assertEquals(NODE, down(h))
        assertEquals("an EOF is never a failed seal", 0, h.failures.get())
    }

    @Test
    fun aKeyShareOnALivePlainLinkOnlyClosesIt() {
        // A direct link shares FramedLink over a plain socket: the record ends the link, as an unknown tag did before.
        val h = start(sealed = false)
        send(h.peer, LinkFraming.encode(LinkFraming.Type.FRAME, frame("m1")))
        assertNotNull(h.callbacks.inbound.poll(2, TimeUnit.SECONDS))
        send(h.peer, LinkFraming.encodeKeyShare(ByteArray(LinkFraming.KEY_SHARE_BYTES) { 9 }))
        assertEquals(NODE, down(h))
        assertTrue(h.callbacks.inbound.isEmpty())
        assertEquals(0, h.failures.get())
    }

    private companion object {
        const val NODE = "peer0001"
    }
}
