package app.getknit.knit

import app.getknit.knit.mesh.FileKind
import app.getknit.knit.mesh.FileMeta
import app.getknit.knit.mesh.InboundFrame
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.ReceivedDigest
import app.getknit.knit.mesh.ReceivedFile
import app.getknit.knit.mesh.link.DigestWire
import app.getknit.knit.mesh.link.FramedLink
import app.getknit.knit.mesh.link.LinkCallbacks
import app.getknit.knit.mesh.link.LinkFraming
import app.getknit.knit.mesh.link.LinkSocket
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Unit tests for [FramedLink] — the transport-agnostic per-connection read/write/file/digest loops extracted
 * from the Wi-Fi Aware transport. Driven over an in-process piped [LinkSocket] (no radios): the test plays the
 * remote peer, writing records the link decodes and reading records the link emits.
 */
class FramedLinkTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        scope.cancel()
    }

    /** Records every callback so a test can await one off its queue with a timeout. */
    private class Recording : LinkCallbacks {
        val inbound = LinkedBlockingQueue<InboundFrame>()
        val digests = LinkedBlockingQueue<ReceivedDigest>()
        val files = LinkedBlockingQueue<ReceivedFile>()
        val downs = LinkedBlockingQueue<String>()

        override fun onInbound(frame: InboundFrame) {
            inbound.add(frame)
        }

        override fun onDigest(digest: ReceivedDigest) {
            digests.add(digest)
        }

        override fun onFile(file: ReceivedFile) {
            files.add(file)
        }

        override fun onLinkDown(nodeId: String) {
            downs.add(nodeId)
        }
    }

    /** The link end of a duplex pipe pair plus the peer's write/read ends the test drives. */
    private class Harness(
        val link: FramedLink,
        val toLink: OutputStream, // test writes records here → link's read loop decodes them
        val fromLink: PipedInputStream, // link's write loop emits records here → test reads them
        val callbacks: Recording,
    )

    private fun harness(nodeId: String = "peer0001"): Harness {
        val toLink = PipedOutputStream()
        val linkInput = PipedInputStream(toLink, 1 shl 18)
        val fromLink = PipedInputStream(1 shl 18)
        val linkOutput = PipedOutputStream(fromLink)
        val socket =
            object : LinkSocket {
                override val input = linkInput
                override val output: OutputStream = linkOutput

                override fun close() {
                    runCatching { linkInput.close() }
                    runCatching { linkOutput.close() }
                }
            }
        val callbacks = Recording()
        val link =
            FramedLink(
                nodeId = nodeId,
                peer = Peer(nodeId),
                socket = socket,
                scope = scope,
                cacheDir = tmp.root,
                metrics = MeshMetrics(),
                callbacks = callbacks,
                now = { 0L },
            )
        link.start()
        return Harness(link, toLink, fromLink, callbacks)
    }

    private fun writeRecord(
        out: OutputStream,
        type: LinkFraming.Type,
        payload: ByteArray = ByteArray(0),
    ) {
        LinkFraming.write(out, type, payload)
        out.flush()
    }

    private fun frameBytes(
        id: String,
        senderId: String,
    ): ByteArray {
        val env = RelayEnvelope(type = FrameType.CHAT, id = id, senderId = senderId, payload = ByteArray(0))
        val wire = WireEnvelope(sig = byteArrayOf(1), signed = WireCodec.encodeEnvelope(env))
        return WireCodec.encodeWire(wire)
    }

    @Test
    fun frameRecordSurfacesDecodedInbound() {
        val h = harness(nodeId = "sender01")
        writeRecord(h.toLink, LinkFraming.Type.FRAME, frameBytes(id = "m1", senderId = "sender01"))
        val got = h.callbacks.inbound.poll(2, TimeUnit.SECONDS)
        assertNotNull("a FRAME record should surface as onInbound", got)
        assertEquals("m1", got!!.envelope.id)
        assertEquals("sender01", got.fromNodeId)
    }

    @Test
    fun digestRecordSurfacesReceivedDigest() {
        val h = harness(nodeId = "sender01")
        writeRecord(h.toLink, LinkFraming.Type.DIGEST, LinkFraming.encodeDigest(DigestWire(listOf("a", "b"))))
        val got = h.callbacks.digests.poll(2, TimeUnit.SECONDS)
        assertNotNull("a DIGEST record should surface as onDigest", got)
        assertEquals(listOf("a", "b"), got!!.ids)
        assertEquals("sender01", got.fromNodeId)
    }

    @Test
    fun attachmentFileStreamsReassemblesAndFinalizes() {
        val h = harness()
        val key = "a".repeat(64) // a valid 64-hex blob hash
        val body = ByteArray(5000) { (it % 251).toByte() }
        writeRecord(h.toLink, LinkFraming.Type.FILE_HEADER, LinkFraming.encodeFileHeader(hdr("ATTACHMENT", key)))
        writeRecord(h.toLink, LinkFraming.Type.FILE_CHUNK, body)
        writeRecord(h.toLink, LinkFraming.Type.FILE_END)
        val got = h.callbacks.files.poll(2, TimeUnit.SECONDS)
        assertNotNull("a completed file should surface as onFile", got)
        assertEquals(FileKind.ATTACHMENT, got!!.kind)
        assertEquals(key, got.key)
        assertArrayEquals(body, File(got.path).readBytes())
    }

    @Test
    fun malformedBlobKeyRejectsFileWithoutCallback() {
        val h = harness()
        // A key that isn't a 64-hex blob hash must be rejected (path-traversal defense) and never finalized.
        writeRecord(h.toLink, LinkFraming.Type.FILE_HEADER, LinkFraming.encodeFileHeader(hdr("ATTACHMENT", "../evil")))
        writeRecord(h.toLink, LinkFraming.Type.FILE_CHUNK, byteArrayOf(1, 2, 3))
        writeRecord(h.toLink, LinkFraming.Type.FILE_END)
        assertNull("a malformed blob key must not finalize a file", h.callbacks.files.poll(1, TimeUnit.SECONDS))
        assertTrue("no attachment file should be written", tmp.root.listFiles()!!.none { it.name.startsWith("attach-") })
    }

    @Test
    fun sendEmitsAFrameRecordOnTheWire() {
        val h = harness()
        val payload = frameBytes(id = "out1", senderId = "me000001")
        h.link.send(payload)
        val rec = LinkFraming.read(h.fromLink)
        assertNotNull(rec)
        assertEquals(LinkFraming.Type.FRAME, rec!!.type)
        assertArrayEquals(payload, rec.payload)
    }

    @Test
    fun sendFileEmitsHeaderChunkEndOnTheWire() {
        val h = harness()
        val key = "b".repeat(64)
        val body = ByteArray(3000) { (it % 97).toByte() }
        val file = tmp.newFile("out.bin").apply { writeBytes(body) }
        h.link.sendFile(file, FileMeta(FileKind.ATTACHMENT, key, "image/jpeg"))

        val header = LinkFraming.read(h.fromLink)
        assertEquals(LinkFraming.Type.FILE_HEADER, header!!.type)
        assertEquals(key, LinkFraming.decodeFileHeader(header.payload)!!.key)
        // Collect chunks until FILE_END and assert they reassemble to the original bytes.
        val received = ArrayList<Byte>()
        var rec = LinkFraming.read(h.fromLink)
        while (rec != null && rec.type != LinkFraming.Type.FILE_END) {
            assertEquals(LinkFraming.Type.FILE_CHUNK, rec.type)
            received.addAll(rec.payload.toList())
            rec = LinkFraming.read(h.fromLink)
        }
        assertArrayEquals(body, received.toByteArray())
    }

    @Test
    fun cleanEofRaisesOnLinkDown() {
        val h = harness(nodeId = "goner001")
        h.toLink.close() // peer closed the write end at a record boundary → clean EOF
        assertEquals("goner001", h.callbacks.downs.poll(2, TimeUnit.SECONDS))
    }

    @Test
    fun malformedLengthPrefixDropsLink() {
        val h = harness(nodeId = "bad00001")
        // A FRAME tag with an out-of-range length prefix → LinkFraming.read throws → read loop ends → onLinkDown.
        val tooBig = LinkFraming.MAX_PAYLOAD_BYTES + 1
        h.toLink.write(
            byteArrayOf(
                LinkFraming.Type.FRAME.tag,
                (tooBig ushr 24).toByte(),
                (tooBig ushr 16).toByte(),
                (tooBig ushr 8).toByte(),
                tooBig.toByte(),
            ),
        )
        h.toLink.flush()
        assertEquals("bad00001", h.callbacks.downs.poll(2, TimeUnit.SECONDS))
        assertFalse("a hostile length prefix must not surface a frame", h.callbacks.inbound.isNotEmpty())
    }

    private fun hdr(
        kind: String,
        key: String,
    ) = app.getknit.knit.mesh.link
        .FileHeaderWire(kind = kind, key = key, mime = "image/jpeg")
}
