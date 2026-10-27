package app.getknit.knit

import app.getknit.knit.mesh.ArrivingFile
import app.getknit.knit.mesh.DropReason
import app.getknit.knit.mesh.FileKind
import app.getknit.knit.mesh.FileMeta
import app.getknit.knit.mesh.InboundFrame
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.PartialBlobs
import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.ReceivedDigest
import app.getknit.knit.mesh.ReceivedFile
import app.getknit.knit.mesh.link.DigestWire
import app.getknit.knit.mesh.link.FeedMeter
import app.getknit.knit.mesh.link.FeedWindow
import app.getknit.knit.mesh.link.FramedLink
import app.getknit.knit.mesh.link.LinkCallbacks
import app.getknit.knit.mesh.link.LinkFraming
import app.getknit.knit.mesh.link.LinkSocket
import app.getknit.knit.mesh.link.PaceConfig
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Unit tests for [FramedLink] — the transport-agnostic per-connection read/write/file/digest loops extracted
 * from the Wi-Fi Aware transport. Driven over an in-process piped [LinkSocket] (no radios): the test plays the
 * remote peer, writing records the link decodes and reading records the link emits.
 */
@Suppress("LargeClass") // one link's whole contract over one piped harness: frames, files, digests, resume and busy edges
class FramedLinkTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // One store for every link a test opens, as both radios share one in the app, so a second link can resume what
    // the first was cut off from (#116). Kept from a single byte, so a test needs no 16 KiB stream.
    private val partials by lazy { PartialBlobs(File(tmp.root, "partials"), minBytes = 1) }

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

    private fun harness(
        nodeId: String = "peer0001",
        // A small out-pipe lets a test force the writer to back-pressure mid-file (see the interleave test).
        fromLinkBuffer: Int = 1 shl 18,
        pace: () -> PaceConfig = { PaceConfig() },
        metrics: MeshMetrics = MeshMetrics(),
        onBusy: () -> Unit = {},
        onFeedChanged: () -> Unit = {},
        now: () -> Long = { 0L },
        onRxProgress: (String, Long, Long) -> Unit = { _, _, _ -> },
        windowedReports: Boolean = false,
        onKeepalive: () -> Unit = {},
        onKeepaliveWritten: () -> Unit = {},
    ): Harness {
        val toLink = PipedOutputStream()
        val linkInput = PipedInputStream(toLink, 1 shl 18)
        val fromLink = PipedInputStream(fromLinkBuffer)
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
                partials = partials,
                metrics = metrics,
                callbacks = callbacks,
                now = now,
                pace = pace,
                onBusy = onBusy,
                onFeedChanged = onFeedChanged,
                onRxProgress = onRxProgress,
                windowedReports = { windowedReports },
                onKeepalive = onKeepalive,
                onKeepaliveWritten = onKeepaliveWritten,
            )
        link.start()
        return Harness(link, toLink, fromLink, callbacks)
    }

    /** Polls [cond] for up to two seconds (the loops run on Dispatchers.IO). */
    private fun awaitUntil(cond: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            if (cond()) return true
            Thread.sleep(5)
        }
        return cond()
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
    fun frameWhoseSignedTextIsNotUtf8IsDroppedAsUndecodable() {
        // IOS-5 (ADR 2026-10.qjap): the id's last byte becomes 0xFF, which kotlinx would repair into U+FFFD.
        val metrics = MeshMetrics()
        val h = harness(nodeId = "sender01", metrics = metrics)
        val env = RelayEnvelope(type = FrameType.CHAT, id = "m1-QQ", senderId = "sender01", payload = ByteArray(0))
        val signed = WireCodec.encodeEnvelope(env)
        signed[signed.indexOfFirst { it == 'Q'.code.toByte() } + 1] = 0xFF.toByte()
        writeRecord(h.toLink, LinkFraming.Type.FRAME, WireCodec.encodeWire(WireEnvelope(sig = byteArrayOf(1), signed = signed)))
        // A good frame behind it proves the read loop got past the bad one.
        writeRecord(h.toLink, LinkFraming.Type.FRAME, frameBytes(id = "m2", senderId = "sender01"))
        assertEquals(
            "m2",
            h.callbacks.inbound
                .poll(2, TimeUnit.SECONDS)
                ?.envelope
                ?.id,
        )
        assertNull(h.callbacks.inbound.poll())
        assertEquals(1L, metrics.frames().dropsByReason[DropReason.DECODE_FAILED])
    }

    @Test
    fun frameWithANegativeTtlIsDroppedAsUndecodable() {
        // AND-11: 0xFFFFFFFF, which kotlinx reads as -1, is refused as iOS's int32 refuses it.
        val metrics = MeshMetrics()
        val h = harness(nodeId = "sender01", metrics = metrics)
        val env = RelayEnvelope(type = FrameType.CHAT, id = "m1", senderId = "sender01", payload = ByteArray(0))
        writeRecord(
            h.toLink,
            LinkFraming.Type.FRAME,
            WireCodec.encodeWire(WireEnvelope(ttl = -1, sig = byteArrayOf(1), signed = WireCodec.encodeEnvelope(env))),
        )
        writeRecord(h.toLink, LinkFraming.Type.FRAME, frameBytes(id = "m2", senderId = "sender01"))
        assertEquals(
            "m2",
            h.callbacks.inbound
                .poll(2, TimeUnit.SECONDS)
                ?.envelope
                ?.id,
        )
        assertEquals(1L, metrics.frames().dropsByReason[DropReason.DECODE_FAILED])
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
    fun rxKeyNamesTheFileStreamingInFromItsHeaderToItsEnd() {
        // What MeshTransport.arrivingFiles reads: exactly "a FILE_HEADER is in and its FILE_END is not", so
        // BlobExchange can keep a blob already on the way from being asked for again (#79).
        val h = harness()
        val key = "d".repeat(64)
        assertNull(h.link.rxKey)
        writeRecord(h.toLink, LinkFraming.Type.FILE_HEADER, LinkFraming.encodeFileHeader(hdr("ATTACHMENT", key)))
        assertTrue("the header names the arriving file", awaitUntil { h.link.rxKey == key })
        writeRecord(h.toLink, LinkFraming.Type.FILE_CHUNK, ByteArray(100))
        assertEquals("still arriving between chunks", key, h.link.rxKey)
        writeRecord(h.toLink, LinkFraming.Type.FILE_END)
        assertNotNull(h.callbacks.files.poll(2, TimeUnit.SECONDS))
        assertTrue("cleared at the end", awaitUntil { h.link.rxKey == null })
    }

    @Test
    fun rxKeyClearsWhenTheLinkCloses() {
        // A torn link clears its own state — the read above needs no abort callback and no TTL.
        val h = harness()
        val key = "e".repeat(64)
        writeRecord(h.toLink, LinkFraming.Type.FILE_HEADER, LinkFraming.encodeFileHeader(hdr("ATTACHMENT", key)))
        assertTrue(awaitUntil { h.link.rxKey == key })
        h.link.close()
        assertNull("nothing is arriving on a closed link", h.link.rxKey)
    }

    @Test
    fun rxFileCountsTheBytesInAgainstTheDeclaredTotal() {
        // What the chat's progress ring reads (#115): the bytes written so far against the size the header
        // declared, from the header to the FILE_END — the same span rxKey names.
        val h = harness()
        val key = "c".repeat(64)
        assertNull(h.link.rxFile)
        writeRecord(h.toLink, LinkFraming.Type.FILE_HEADER, LinkFraming.encodeFileHeader(hdr("ATTACHMENT", key, size = 3000)))
        assertTrue("the header opens it at nothing in", awaitUntil { h.link.rxFile == ArrivingFile(key, 0, 3000) })
        writeRecord(h.toLink, LinkFraming.Type.FILE_CHUNK, ByteArray(1200))
        assertTrue("partway", awaitUntil { h.link.rxFile == ArrivingFile(key, 1200, 3000) })
        writeRecord(h.toLink, LinkFraming.Type.FILE_CHUNK, ByteArray(1800))
        assertTrue("every byte in is still arriving until the end", awaitUntil { h.link.rxFile == ArrivingFile(key, 3000, 3000) })
        writeRecord(h.toLink, LinkFraming.Type.FILE_END)
        assertNotNull(h.callbacks.files.poll(2, TimeUnit.SECONDS))
        assertNull("gone at the end", h.link.rxFile)
    }

    @Test
    fun aHeaderWithoutAUsableSizeArrivesWithNoTotal() {
        // An older sender declares nothing, and a size of nothing, below it, or past the stream's ceiling is no
        // total either: the count alone is what the bubble shows (#115).
        listOf(null, 0L, -5L, 9L * 1024 * 1024).forEachIndexed { i, declared ->
            val h = harness()
            val key = "${i + 1}".repeat(64)
            writeRecord(h.toLink, LinkFraming.Type.FILE_HEADER, LinkFraming.encodeFileHeader(hdr("ATTACHMENT", key, size = declared)))
            writeRecord(h.toLink, LinkFraming.Type.FILE_CHUNK, ByteArray(10))
            assertTrue("declared $declared", awaitUntil { h.link.rxFile == ArrivingFile(key, 10, null) })
        }
    }

    @Test
    fun aSecondHeaderReplacesTheFileArrivingAndAnUndecodableOneLeavesNone() {
        // A header with no FILE_END before it drops the partial file it interrupts, count and all.
        val h = harness()
        val first = "a".repeat(64)
        val second = "b".repeat(64)
        writeRecord(h.toLink, LinkFraming.Type.FILE_HEADER, LinkFraming.encodeFileHeader(hdr("ATTACHMENT", first, size = 500)))
        writeRecord(h.toLink, LinkFraming.Type.FILE_CHUNK, ByteArray(100))
        assertTrue(awaitUntil { h.link.rxFile == ArrivingFile(first, 100, 500) })
        writeRecord(h.toLink, LinkFraming.Type.FILE_HEADER, LinkFraming.encodeFileHeader(hdr("ATTACHMENT", second, size = 700)))
        assertTrue("the new header's file, from nothing", awaitUntil { h.link.rxFile == ArrivingFile(second, 0, 700) })
        writeRecord(h.toLink, LinkFraming.Type.FILE_HEADER, "not json".encodeToByteArray())
        assertTrue("a header that will not decode leaves nothing arriving", awaitUntil { h.link.rxFile == null })
        assertFalse(h.link.rxInProgress)
    }

    @Test
    fun aFilePastTheCeilingStopsArrivingAndLeavesNothingBehind() {
        // The receive ceiling (8 MiB plus the seal's headroom): sixteen full records stay under it and the
        // seventeenth crosses it, so the file is aborted — its entry goes, and no temp file or onFile outlives it.
        val h = harness()
        val key = "9".repeat(64)
        val chunk = ByteArray(LinkFraming.MAX_PAYLOAD_BYTES)
        writeRecord(h.toLink, LinkFraming.Type.FILE_HEADER, LinkFraming.encodeFileHeader(hdr("ATTACHMENT", key, size = 4096)))
        repeat(16) { writeRecord(h.toLink, LinkFraming.Type.FILE_CHUNK, chunk) }
        assertTrue("under the ceiling it is arriving", awaitUntil { h.link.rxFile?.bytes == 16L * LinkFraming.MAX_PAYLOAD_BYTES })
        writeRecord(h.toLink, LinkFraming.Type.FILE_CHUNK, chunk)
        assertTrue("past it, nothing is", awaitUntil { h.link.rxFile == null })
        writeRecord(h.toLink, LinkFraming.Type.FILE_END)
        assertNull("an aborted file never finalizes", h.callbacks.files.poll(1, TimeUnit.SECONDS))
        assertTrue("no partial file is left in the cache", tmp.root.listFiles()!!.none { it.name.startsWith("link-rx-") })
    }

    @Test
    fun rxFileClearsWhenTheLinkClosesMidStream() {
        val h = harness()
        val key = "e".repeat(64)
        writeRecord(h.toLink, LinkFraming.Type.FILE_HEADER, LinkFraming.encodeFileHeader(hdr("ATTACHMENT", key, size = 1000)))
        writeRecord(h.toLink, LinkFraming.Type.FILE_CHUNK, ByteArray(400))
        assertTrue(awaitUntil { h.link.rxFile == ArrivingFile(key, 400, 1000) })
        h.link.close()
        assertNull("nothing is arriving on a closed link", h.link.rxFile)
        assertFalse(h.link.rxInProgress)
    }

    @Test
    fun aPendingFileIsKnownFromEnqueueToTheEndOfItsStream() {
        // What MeshTransport.fileInFlightTo reads: the enqueue side's view of a file queued on, or streaming
        // over, the link — so a re-ask never queues a second copy behind the first (#79). A small out-pipe
        // back-pressures the writer mid-file so the streaming phase is observable.
        val h = harness(fromLinkBuffer = 16 * 1024)
        val key = "f".repeat(64)
        val file = tmp.newFile("pending.bin").apply { writeBytes(ByteArray(80 * 1024)) }
        assertFalse(h.link.hasPendingFile(key))
        assertTrue(h.link.sendFile(file, FileMeta(FileKind.ATTACHMENT, key, "image/jpeg")))
        assertTrue("pending from the enqueue", h.link.hasPendingFile(key))
        var rec = LinkFraming.read(h.fromLink)
        assertEquals(LinkFraming.Type.FILE_HEADER, rec!!.type)
        assertTrue("pending while streaming", h.link.hasPendingFile(key))
        while (rec != null && rec.type != LinkFraming.Type.FILE_END) rec = LinkFraming.read(h.fromLink)
        assertTrue("released once the stream ends", awaitUntil { !h.link.hasPendingFile(key) })
    }

    @Test
    fun aFileIsFedOnlyWhileItStreamsOrIsNextAndItsEndIsPoked() {
        // ADR 2026-10.g24x: what a holder parks a second ask behind. A copy queued behind another file is not fed yet, the
        // one streaming is, with its ETA by the pace until a report measures one, and its end is poked.
        val ended = AtomicInteger()
        val h =
            harness(
                fromLinkBuffer = 16 * 1024,
                pace = { PaceConfig(bytesPerSec = 1024, chunkBytes = 2048) },
                onFeedChanged = { ended.incrementAndGet() },
            )
        val first = "a".repeat(64)
        val second = "b".repeat(64)
        val file = tmp.newFile("feeds.bin").apply { writeBytes(ByteArray(80 * 1024)) }
        assertTrue(h.link.sendFile(file, FileMeta(FileKind.ATTACHMENT, first, "image/jpeg")))
        assertTrue(h.link.sendFile(file, FileMeta(FileKind.ATTACHMENT, second, "image/jpeg")))
        assertEquals(LinkFraming.Type.FILE_HEADER, LinkFraming.read(h.fromLink)!!.type)
        assertEquals(LinkFraming.Type.FILE_CHUNK, LinkFraming.read(h.fromLink)!!.type)
        // The pace is noted before the chunk's write, which can be after the test has read it.
        assertTrue("78 KB at 1 KiB/s is over a minute from its end", awaitUntil { (h.link.feed(first)?.etaMs ?: 0) > 60_000 })
        val feed = h.link.feed(first)!!
        assertTrue(feed.live)
        assertFalse("not measured yet", feed.measured)
        assertNull("the one queued behind it is not fed yet", h.link.feed(second))
        h.link.close()
        assertTrue("a stream cut short still ends its feed", awaitUntil { ended.get() >= 1 })
        assertNull(h.link.feed(first))
    }

    @Test
    fun aFeedIsReadAtItsSettlingPaceUntilItsSecondChunkIsPaced() {
        // ADR 2026-10.g24x's amendment: the first chunk is paced before the link answers the boost it asked for, so
        // until the second chunk's pace is read the owner's settling pace stands in for it. Counted in chunks: the
        // next pace is read after a delay, which a clock would not wait for while the CPU sleeps.
        val h = harness(fromLinkBuffer = 1024, pace = { PaceConfig(bytesPerSec = 32 * 1024, chunkBytes = 2048) })
        val key = "a".repeat(64)
        val file = tmp.newFile("settle.bin").apply { writeBytes(ByteArray(4 * 1024 * 1024)) }
        assertTrue(h.link.sendFile(file, FileMeta(FileKind.ATTACHMENT, key, "image/jpeg")))
        assertEquals(LinkFraming.Type.FILE_HEADER, LinkFraming.read(h.fromLink)!!.type)
        // The first chunk's write blocks on the small buffer: paced once, its pace known.
        assertTrue("4 MiB at 32 KiB/s is over 30 s from its end", awaitUntil { (h.link.feed(key)?.etaMs ?: 0) > 30_000 })
        assertTrue("at the settling pace it is not", h.link.feed(key, settlingPace = 1024 * 1024)!!.etaMs!! < 30_000)
        assertEquals(LinkFraming.Type.FILE_CHUNK, LinkFraming.read(h.fromLink)!!.type) // the writer paces the second
        assertTrue(
            "from the second chunk, at its own",
            awaitUntil { (h.link.feed(key, settlingPace = 1024 * 1024)?.etaMs ?: 0) > 30_000 },
        )
        h.link.close()
    }

    @Test
    fun theReceiversReportsMeasureTheFeedAndItsStall() {
        // ADR 2026-10.nsr7: a report of how much the peer holds, by its own clock, measures the drain the stack's queue
        // hides; a feed whose reports stop moving for the stall window holds nobody.
        val t = AtomicLong(0)
        val h =
            harness(
                fromLinkBuffer = 1024,
                pace = { PaceConfig(bytesPerSec = 32 * 1024, chunkBytes = 2048) },
                now = { t.get() },
            )
        val key = "c".repeat(64)
        val size = 4L * 1024 * 1024
        val file = tmp.newFile("measured.bin").apply { writeBytes(ByteArray(size.toInt())) }
        assertTrue(h.link.sendFile(file, FileMeta(FileKind.ATTACHMENT, key, "image/jpeg")))
        assertEquals(LinkFraming.Type.FILE_HEADER, LinkFraming.read(h.fromLink)!!.type)
        assertTrue(awaitUntil { h.link.feed(key)?.etaMs != null })
        // The third chunk on the pipe means the first two were written: a report of them is one the meter can prove (#158).
        val chunks = List(3) { chunkBytes(h.fromLink) }
        val held = chunks[0] + chunks[1]

        h.link.onProgress("d".repeat(64), 1_000_000, 1_000) // another file's: ignored
        assertFalse(h.link.feed(key)!!.measured)
        h.link.onProgress(key, held, 2_000) // its first two chunks in its first two seconds (the meter samples over two)
        val measured = h.link.feed(key)!!
        assertTrue(measured.measured)
        assertEquals("the rest at what it held over two seconds", (size - held) * 1_000 / (held / 2), measured.etaMs)

        // Past its pace wait and blocked on the fourth chunk's write: a writer waiting out its pace counts no stall.
        assertTrue(awaitUntil { h.fromLink.available() > 0 })
        t.addAndGet(FeedMeter.STALL_MS + 1) // no report moved
        assertFalse("stalled: it holds nobody", h.link.feed(key)!!.live)
        LinkFraming.read(h.fromLink) // the fourth: the third was written
        h.link.onProgress(key, held + chunks[2], 4_000)
        assertTrue("moving again", h.link.feed(key)!!.live)
        h.link.close()
    }

    @Test
    fun aReportOfMoreThanWasWrittenIsMeteredAsWhatWas() {
        // #158: the meter holds a report to the bytes this side wrote ([FeedMeter.wrote], counted beside the window's). A
        // receiver claiming the whole file reads as what was written, not a feed about to end; an honest one as it says.
        val t = AtomicLong(0)
        val pace = 32 * 1024
        val h =
            harness(
                fromLinkBuffer = 1024,
                pace = { PaceConfig(bytesPerSec = pace, chunkBytes = 2048) },
                now = { t.get() },
            )
        val key = "e".repeat(64)
        val size = 4L * 1024 * 1024
        val file = tmp.newFile("clamped.bin").apply { writeBytes(ByteArray(size.toInt())) }
        assertTrue(h.link.sendFile(file, FileMeta(FileKind.ATTACHMENT, key, "image/jpeg")))
        assertEquals(LinkFraming.Type.FILE_HEADER, LinkFraming.read(h.fromLink)!!.type)
        val first = chunkBytes(h.fromLink)
        val written = first + chunkBytes(h.fromLink)
        // The third chunk's write cannot complete into the small pipe: written stays at two once the pace's ETA shows it.
        assertTrue(awaitUntil { h.link.feed(key)?.etaMs == (size - written) * 1_000 / pace })

        h.link.onProgress(key, first, 2_000) // honest: the first chunk
        val rate = first * 1_000 / 2_000
        assertEquals("an honest report reads as it says", (size - first) * 1_000 / rate, h.link.feed(key)!!.etaMs)

        h.link.onProgress(key, size, 4_000) // all of it: only two chunks were ever written
        val clamped = (rate * 3 + (written - first) * 1_000 / 2_000) / 4
        val feed = h.link.feed(key)!!
        assertTrue(feed.live)
        assertEquals("read as what was written, not a feed about to end", (size - written) * 1_000 / clamped, feed.etaMs)
        h.link.close()
    }

    /** Reads the next record, a FILE_CHUNK, and returns its size. */
    private fun chunkBytes(fromLink: PipedInputStream): Long {
        val rec = LinkFraming.read(fromLink)!!
        assertEquals(LinkFraming.Type.FILE_CHUNK, rec.type)
        return rec.payload.size.toLong()
    }

    /** Reads the FILE_CHUNK records that arrive within [quietMs] of each other, and returns their sizes. */
    private fun chunksUntilQuiet(
        fromLink: PipedInputStream,
        quietMs: Long = 700,
    ): List<Int> {
        val sizes = ArrayList<Int>()
        while (true) {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(quietMs)
            while (fromLink.available() == 0 && System.nanoTime() < deadline) Thread.sleep(5)
            if (fromLink.available() == 0) return sizes
            val rec = LinkFraming.read(fromLink)!!
            if (rec.type == LinkFraming.Type.FILE_CHUNK) sizes += rec.payload.size
        }
    }

    @Test
    fun aWindowedFeedHoldsAtItsWindowUntilAReportFreesIt() {
        // ADR 2026-10.nsr7's phase 2: before any report only the starting window goes; a report measuring the drain
        // lets the writer refill its window at once, without waiting out its timeout.
        val h = harness(pace = { PaceConfig(bytesPerSec = 4_096, chunkBytes = 2_048, windowed = true) })
        val key = "a1".repeat(32)
        val file = tmp.newFile("windowed.bin").apply { writeBytes(ByteArray(1024 * 1024)) }
        assertTrue(h.link.sendFile(file, FileMeta(FileKind.ATTACHMENT, key, "image/jpeg")))
        assertEquals(LinkFraming.Type.FILE_HEADER, LinkFraming.read(h.fromLink)!!.type)
        val first = chunksUntilQuiet(h.fromLink)
        assertEquals("the starting window, in starting chunks", FeedWindow.INITIAL_WINDOW, first.sum().toLong())
        assertTrue(first.all { it == FeedWindow.INITIAL_CHUNK_BYTES })
        assertTrue(h.link.txWindowed)

        h.link.onProgress(key, 8_192, FramedLink.WINDOWED_PROGRESS_EVERY_MS) // all of it by the first report: 16 KiB/s
        // The harness clock stands still, so the round trip reads as its 50 ms floor: the window is 16 KiB/s over
        // 50 + 250 ms, 4915 B, and refills in quarter-second chunks.
        val refill = chunksUntilQuiet(h.fromLink)
        assertEquals(8_192, refill.sum())
        assertTrue(refill.all { it == 4_096 })
        h.link.close()
    }

    @Test
    fun aWindowedFeedOnCodedStartsInItsSmallerChunks() {
        // ADR 2026-10.5buf's Coded amendment: the window's floors are counted in the link's smallest chunk, which the
        // pace carries, so Coded's slower air starts with half the 1M window in half its chunks.
        val h = harness(pace = { PaceConfig(bytesPerSec = 1_024, chunkBytes = 2_048, windowed = true, windowChunkBytes = 1_024) })
        val key = "c3".repeat(32)
        val file = tmp.newFile("coded.bin").apply { writeBytes(ByteArray(64 * 1024)) }
        assertTrue(h.link.sendFile(file, FileMeta(FileKind.ATTACHMENT, key, "image/jpeg")))
        assertEquals(LinkFraming.Type.FILE_HEADER, LinkFraming.read(h.fromLink)!!.type)
        assertEquals("four smallest chunks, in chunks of two", listOf(2_048, 2_048), chunksUntilQuiet(h.fromLink))
        assertTrue(h.link.txWindowed)
        h.link.close()
    }

    @Test
    fun aWindowedFeedWhoseReceiverIsSilentIsPacedUntilItReports() {
        // A peer that carries the bit but never reports (or whose reports stopped) gets the old pace, not a stopped feed.
        // On a real clock: a test clock jumping past the writer's wait would read as the writer asleep, not the receiver
        // silent.
        val t0 = System.nanoTime()
        val h =
            harness(
                pace = { PaceConfig(bytesPerSec = 1024 * 1024, chunkBytes = 2_048, windowed = true) },
                now = { TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) },
            )
        val key = "b2".repeat(32)
        val file = tmp.newFile("silent.bin").apply { writeBytes(ByteArray(64 * 1024)) }
        assertTrue(h.link.sendFile(file, FileMeta(FileKind.ATTACHMENT, key, "image/jpeg")))
        assertEquals(LinkFraming.Type.FILE_HEADER, LinkFraming.read(h.fromLink)!!.type)
        assertEquals(FeedWindow.INITIAL_WINDOW, chunksUntilQuiet(h.fromLink).sum().toLong())

        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(FeedWindow.NO_REPORT_MS + 2_000)
        while (h.fromLink.available() == 0 && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue("paced from its pace's chunks once the receiver was silent", h.fromLink.available() > 0)
        assertEquals(2_048, LinkFraming.read(h.fromLink)!!.payload.size)
        assertFalse(h.link.txWindowed)
        assertArrayEquals(ByteArray(64 * 1024 - FeedWindow.INITIAL_WINDOW.toInt() - 2_048), readFileBody(h.fromLink))
        h.link.close()
    }

    @Test
    fun aFeedTakenForATwoSecondReporterIsPacedInSmallChunksAndWinsItsWindowBack() {
        // The D2 run of 2026-10-08: a dozing receiver's late reports, several chunks each, read as a two-second
        // reporter's. Paced, the feed writes the smallest chunks, so a receiver answering each one on time shows itself;
        // three such reports give the window back. The clock moves only when the test moves it, so the receiver is
        // never silent.
        val t = AtomicLong(0)
        val h =
            harness(
                pace = { PaceConfig(bytesPerSec = 1024 * 1024, chunkBytes = 8_192, windowed = true) },
                now = { t.get() },
            )
        val key = "d4".repeat(32)
        val file = tmp.newFile("dozing.bin").apply { writeBytes(ByteArray(256 * 1024)) }
        assertTrue(h.link.sendFile(file, FileMeta(FileKind.ATTACHMENT, key, "image/jpeg")))
        assertEquals(LinkFraming.Type.FILE_HEADER, LinkFraming.read(h.fromLink)!!.type)
        var written = chunksUntilQuiet(h.fromLink).sum().toLong()
        h.link.onProgress(key, written, 2_000)
        // Two late reports two seconds apart, each holding everything written since: several chunks apiece.
        for (ms in listOf(4_000L, 6_000L)) {
            val refill = chunksUntilQuiet(h.fromLink)
            assertTrue("refilled in more than one chunk: $refill", refill.size > 1)
            written += refill.sum()
            h.link.onProgress(key, written, ms)
        }
        val paced = chunksUntilQuiet(h.fromLink)
        assertTrue("paced: $paced", paced.isNotEmpty() && !h.link.txWindowed)
        assertTrue("in the smallest chunks, not the pace's 8 KiB: $paced", paced.all { it == FeedWindow.MIN_CHUNK_BYTES })

        // The pace lets more go as its clock moves: 20 ms of 1 MiB/s, about ten chunks.
        t.addAndGet(20)
        val more = paced + chunksUntilQuiet(h.fromLink)
        assertTrue("paced chunks to answer: $more", more.size >= FeedWindow.RECOVER_REPORTS && !h.link.txWindowed)
        // Woken: it reports chunk by chunk, half a second apart on its own clock.
        var held = written
        for (i in 1..FeedWindow.RECOVER_REPORTS) {
            held += FeedWindow.MIN_CHUNK_BYTES
            h.link.onProgress(key, held, 6_000L + i * 500)
        }
        assertTrue("windowed again", awaitUntil { h.link.txWindowed })
        h.link.close()
    }

    @Test
    fun framesQueuedDuringAWindowedFeedsFallbackDoNotHurryItsPace() {
        // A silent receiver's feed is paced; a frame's wake drains the frame but must not end the pace's wait, or the pace
        // follows the link's frames (the bluetooth-reviewer's finding). On a real clock, as the silent-receiver case.
        val t0 = System.nanoTime()
        val h =
            harness(
                pace = { PaceConfig(bytesPerSec = 4_096, chunkBytes = 2_048, windowed = true) },
                now = { TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) },
            )
        val key = "c3".repeat(32)
        val file = tmp.newFile("fallback.bin").apply { writeBytes(ByteArray(256 * 1024)) }
        assertTrue(h.link.sendFile(file, FileMeta(FileKind.ATTACHMENT, key, "image/jpeg")))
        assertEquals(LinkFraming.Type.FILE_HEADER, LinkFraming.read(h.fromLink)!!.type)
        assertEquals(FeedWindow.INITIAL_WINDOW, chunksUntilQuiet(h.fromLink).sum().toLong())
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(FeedWindow.NO_REPORT_MS + 2_000)
        while (h.fromLink.available() == 0 && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue("paced once the receiver was silent", h.fromLink.available() > 0)

        val frame = frameBytes(id = "busy", senderId = "sender01")
        val chunks = AtomicInteger()
        val reader =
            Thread {
                runCatching {
                    while (true) {
                        val rec = LinkFraming.read(h.fromLink) ?: break
                        if (rec.type == LinkFraming.Type.FILE_CHUNK) chunks.incrementAndGet()
                    }
                }
            }.apply { start() }
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < end) {
            h.link.send(frame)
            Thread.sleep(50)
        }
        // Two seconds at 4 KiB/s in 2 KiB chunks is four, give or take the one paced as the window fell back.
        assertTrue("paced, not hurried by 40 frames: ${chunks.get()} chunks", chunks.get() <= 6)
        h.link.close()
        reader.join(2_000)
    }

    @Test
    fun aQueuedFileThePeerWithdrawsIsSkippedButAStreamingOneIsNot() {
        // ADR 2026-10.nsr7's cancel: a copy queued behind another file is taken off the link; one streaming goes on.
        val h = harness(fromLinkBuffer = 16 * 1024, pace = { PaceConfig(bytesPerSec = 64 * 1024, chunkBytes = 4096) })
        val first = "a".repeat(64)
        val second = "b".repeat(64)
        val file = tmp.newFile("withdrawn.bin").apply { writeBytes(ByteArray(64 * 1024)) }
        assertTrue(h.link.sendFile(file, FileMeta(FileKind.ATTACHMENT, first, "image/jpeg")))
        assertTrue(h.link.sendFile(file, FileMeta(FileKind.ATTACHMENT, second, "image/jpeg")))
        assertEquals(LinkFraming.Type.FILE_HEADER, LinkFraming.read(h.fromLink)!!.type)
        assertFalse("the one streaming is not withdrawn", h.link.cancelQueued(first))
        assertTrue(h.link.cancelQueued(second))
        assertFalse("one withdrawal per queued copy", h.link.cancelQueued(second))
        // It stops counting at once: a later ask of the peer's is served, not refused as a copy on the link or answered
        // SENDING (the bluetooth-reviewer's finding), though the writer has not reached it yet.
        assertFalse(h.link.hasPendingFile(second))
        assertNull(h.link.feed(second))
        var rec = LinkFraming.read(h.fromLink)
        while (rec != null && rec.type != LinkFraming.Type.FILE_END) rec = LinkFraming.read(h.fromLink)
        assertTrue(awaitUntil { !h.link.hasPendingFile(first) })
        h.link.send(byteArrayOf(1, 2, 3)) // the next record out is this frame, not the withdrawn file's header
        assertEquals(LinkFraming.Type.FRAME, LinkFraming.read(h.fromLink)!!.type)
        h.link.close()
    }

    @Test
    fun aFreshSendAfterAWithdrawalStreamsOnceAndTheWithdrawnOneNever() {
        // The withdrawn send stays in the queue with its skip mark, so a fresh send of the same key queued after it is
        // the one that streams: one copy (the bluetooth-reviewer's ordering note).
        val h = harness(fromLinkBuffer = 16 * 1024, pace = { PaceConfig(bytesPerSec = 64 * 1024, chunkBytes = 4096) })
        val first = "a".repeat(64)
        val second = "b".repeat(64)
        val file = tmp.newFile("fresh.bin").apply { writeBytes(ByteArray(32 * 1024)) }
        assertTrue(h.link.sendFile(file, FileMeta(FileKind.ATTACHMENT, first, "image/jpeg")))
        assertTrue(h.link.sendFile(file, FileMeta(FileKind.ATTACHMENT, second, "image/jpeg")))
        assertEquals(LinkFraming.Type.FILE_HEADER, LinkFraming.read(h.fromLink)!!.type)
        assertTrue(h.link.cancelQueued(second))
        assertTrue(
            "a fresh ask is not refused as a copy on the link",
            h.link.sendFile(file, FileMeta(FileKind.ATTACHMENT, second, "image/jpeg")),
        )
        val headers = mutableListOf<String>()
        var ends = 0
        while (ends < 2) {
            val rec = LinkFraming.read(h.fromLink) ?: break
            if (rec.type == LinkFraming.Type.FILE_HEADER) headers += LinkFraming.decodeFileHeader(rec.payload)!!.key
            if (rec.type == LinkFraming.Type.FILE_END) ends++
        }
        assertEquals(listOf(second), headers)
        assertTrue(awaitUntil { !h.link.hasPendingFile(second) })
        h.link.close()
    }

    @Test
    fun aFeedsSettleAndFirstMeasurementArePoked() {
        // ADR 2026-10.nsr7: a holder re-reads its parked asks when a feed's reading changes, not only at its end: an ask
        // that may now go beside should not wait for the poll (device rep 72 waited 11 s).
        val pokes = AtomicInteger()
        val h =
            harness(
                fromLinkBuffer = 1024,
                pace = { PaceConfig(bytesPerSec = 32 * 1024, chunkBytes = 2048) },
                onFeedChanged = { pokes.incrementAndGet() },
            )
        val key = "f".repeat(64)
        val file = tmp.newFile("pokes.bin").apply { writeBytes(ByteArray(4 * 1024 * 1024)) }
        assertTrue(h.link.sendFile(file, FileMeta(FileKind.ATTACHMENT, key, "image/jpeg")))
        assertEquals(LinkFraming.Type.FILE_HEADER, LinkFraming.read(h.fromLink)!!.type)
        assertEquals(LinkFraming.Type.FILE_CHUNK, LinkFraming.read(h.fromLink)!!.type)
        assertTrue("the second chunk's pace settles the feed", awaitUntil { pokes.get() == 1 })
        h.link.onProgress(key, 2_048, 2_000)
        assertEquals("its first sampling report measures it", 2, pokes.get())
        h.link.onProgress(key, 4_096, 4_000)
        assertEquals("a later one changes nothing the holder reads at once", 2, pokes.get())
        h.link.close()
    }

    @Test
    fun aCopyQueuedBehindABlockedWriteStalls() {
        // ADR 2026-10.nsr7: a copy queued with nothing streaming is next only while the writer moves. One blocked on a
        // frame's write (a full stack buffer) holds every ask parked behind it, so it stalls like a feed would.
        val t = AtomicLong(0)
        val h = harness(fromLinkBuffer = 1024, now = { t.get() })
        h.link.send(ByteArray(8 * 1024)) // more than the pipe takes: the writer blocks on it
        val key = "g".repeat(64)
        val file = tmp.newFile("queued.bin").apply { writeBytes(ByteArray(4 * 1024)) }
        assertTrue(h.link.sendFile(file, FileMeta(FileKind.ATTACHMENT, key, "image/jpeg")))
        t.set(FeedMeter.STALL_MS)
        val queued = h.link.feed(key)!!
        assertTrue("next, while the writer may still move", queued.live)
        assertNull(queued.etaMs)
        t.set(FeedMeter.STALL_MS + 1)
        assertFalse("the writer has not moved for the stall window", h.link.feed(key)!!.live)
        assertEquals(LinkFraming.Type.FRAME, LinkFraming.read(h.fromLink)!!.type) // the write goes through
        assertTrue("moving again", awaitUntil { h.link.feed(key)?.live == true })
        h.link.close()
    }

    @Test
    fun aFileStreamingInIsReportedEveryReportIntervalOfChunks() {
        // ADR 2026-10.nsr7: the receive side tells its owner how much it holds, by its own clock since the header, every
        // FramedLink.PROGRESS_EVERY_MS of chunks; a stall reports nothing.
        val t = AtomicLong(0)
        val reports = CopyOnWriteArrayList<Triple<String, Long, Long>>()
        val h = harness(now = { t.get() }, onRxProgress = { k, held, ms -> reports += Triple(k, held, ms) })
        val key = "e".repeat(64)
        writeRecord(h.toLink, LinkFraming.Type.FILE_HEADER, LinkFraming.encodeFileHeader(hdr("ATTACHMENT", key, size = 8_192)))
        writeRecord(h.toLink, LinkFraming.Type.FILE_CHUNK, ByteArray(1_024))
        assertTrue(awaitUntil { h.link.rxFile?.bytes == 1_024L })
        assertTrue("not yet an interval in", reports.isEmpty())
        t.set(FramedLink.PROGRESS_EVERY_MS)
        writeRecord(h.toLink, LinkFraming.Type.FILE_CHUNK, ByteArray(1_024))
        assertTrue(awaitUntil { reports.isNotEmpty() })
        assertEquals(Triple(key, 2_048L, FramedLink.PROGRESS_EVERY_MS), reports.single())
        h.link.close()
    }

    @Test
    fun aWindowedSenderIsReportedToOnceMoreAfterChunksThatAllLandedInsideOneInterval() {
        // ADR 2026-10.5buf: a windowed sender writes nothing more until it hears, so a fast pair's starting window lands
        // inside one interval and no later chunk carries the report. The trailing report does; a stall sends nothing.
        val t = AtomicLong(0)
        val reports = CopyOnWriteArrayList<Triple<String, Long, Long>>()
        val h =
            harness(
                now = { t.get() },
                onRxProgress = { k, held, ms -> reports += Triple(k, held, ms) },
                windowedReports = true,
            )
        val key = "f6".repeat(32)
        writeRecord(h.toLink, LinkFraming.Type.FILE_HEADER, LinkFraming.encodeFileHeader(hdr("ATTACHMENT", key, size = 64_000)))
        writeRecord(h.toLink, LinkFraming.Type.FILE_CHUNK, ByteArray(1_024)) // under a sender's smallest chunk
        assertTrue(awaitUntil { h.link.rxFile?.bytes == 1_024L })
        t.set(FramedLink.WINDOWED_PROGRESS_EVERY_MS)
        assertTrue("the trailing report", awaitUntil { reports.isNotEmpty() })
        assertEquals(Triple(key, 1_024L, FramedLink.WINDOWED_PROGRESS_EVERY_MS), reports.single())

        writeRecord(h.toLink, LinkFraming.Type.FILE_CHUNK, ByteArray(1_024)) // inside the next interval: owed at it
        assertTrue(awaitUntil { h.link.rxFile?.bytes == 2_048L })
        Thread.sleep(FramedLink.WINDOWED_PROGRESS_EVERY_MS + 200)
        assertEquals("not before its interval", 1, reports.size)
        t.set(2 * FramedLink.WINDOWED_PROGRESS_EVERY_MS)
        assertTrue("the chunk is owed at its interval", awaitUntil { reports.size == 2 })
        assertEquals(2_048L, reports[1].second)
        Thread.sleep(2 * FramedLink.WINDOWED_PROGRESS_EVERY_MS + 200)
        assertEquals("nothing more landed: a stall says nothing", 2, reports.size)
        h.link.close()
    }

    @Test
    fun aSendersSmallestChunkLandedIsReportedOnTheChunkThatCompletesIt() {
        // ADR 2026-10.5buf: on the reader, awake for the chunk, not on a timer a dark receiver may sleep through.
        val reports = CopyOnWriteArrayList<Triple<String, Long, Long>>()
        val t = AtomicLong(0)
        val h =
            harness(
                now = { t.get() },
                onRxProgress = { k, held, ms -> reports += Triple(k, held, ms) },
                windowedReports = true,
            )
        val key = "f7".repeat(32)
        writeRecord(h.toLink, LinkFraming.Type.FILE_HEADER, LinkFraming.encodeFileHeader(hdr("ATTACHMENT", key, size = 64_000)))
        writeRecord(h.toLink, LinkFraming.Type.FILE_CHUNK, ByteArray(1_024))
        assertTrue(awaitUntil { h.link.rxFile?.bytes == 1_024L })
        t.set(100)
        writeRecord(h.toLink, LinkFraming.Type.FILE_CHUNK, ByteArray(1_024))
        assertTrue(awaitUntil { reports.isNotEmpty() })
        assertEquals(Triple(key, 2_048L, 100L), reports.first())
        h.link.close()
    }

    @Test
    fun aFileFedWhileTheLinkGoesOnFeedingThePeerStaysPendingUntilTheLinkIsIdle() {
        // #121: the stack's buffer drains in order, so a file fed ahead of one still streaming may not have reached the
        // peer yet. The link keeps vouching for it until nothing is queued or streaming, and forgets it then.
        val h = harness(fromLinkBuffer = 16 * 1024)
        val first = "a".repeat(64)
        val second = "b".repeat(64)
        val third = "c".repeat(64)
        val file = tmp.newFile("fed.bin").apply { writeBytes(ByteArray(80 * 1024)) }
        assertTrue(h.link.sendFile(file, FileMeta(FileKind.ATTACHMENT, first, "image/jpeg")))
        assertTrue(h.link.sendFile(file, FileMeta(FileKind.ATTACHMENT, second, "image/jpeg")))
        assertEquals(LinkFraming.Type.FILE_HEADER, LinkFraming.read(h.fromLink)!!.type)
        readFileBody(h.fromLink)
        // The second header out means the first stream's bookkeeping is done: the writer runs one file at a time.
        assertEquals(LinkFraming.Type.FILE_HEADER, LinkFraming.read(h.fromLink)!!.type)
        assertTrue("fed, and the link is still feeding the peer", h.link.hasPendingFile(first))
        assertTrue(h.link.hasPendingFile(second))

        readFileBody(h.fromLink)
        assertTrue("idle: neither is vouched for", awaitUntil { !h.link.hasPendingFile(first) && !h.link.hasPendingFile(second) })
        assertTrue(h.link.sendFile(file, FileMeta(FileKind.ATTACHMENT, third, "image/jpeg")))
        assertTrue(h.link.hasPendingFile(third))
        assertFalse("a later busy spell does not bring an earlier one's files back", h.link.hasPendingFile(first))
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
    fun fileKindWireTokensAreFrozen() {
        // The JSON file-header `kind` token is the on-wire contract and must NOT track the enum constant
        // name (which R8 obfuscation may rename). Freeze both tokens, both directions, plus the fallback.
        assertEquals("AVATAR", FileKind.AVATAR.wire)
        assertEquals("ATTACHMENT", FileKind.ATTACHMENT.wire)
        assertEquals(FileKind.AVATAR, FileKind.fromWire("AVATAR"))
        assertEquals(FileKind.ATTACHMENT, FileKind.fromWire("ATTACHMENT"))
        assertEquals("an unknown token routes as a chat attachment", FileKind.ATTACHMENT, FileKind.fromWire("nope"))
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
        // The producer writes the frozen wire token (FileKind.wire), not the obfuscatable constant name.
        assertEquals("ATTACHMENT", LinkFraming.decodeFileHeader(header.payload)!!.kind)
        // And the stream's length, so the receiver can show how far it has got (#115).
        assertEquals(body.size.toLong(), LinkFraming.decodeFileHeader(header.payload)!!.size)
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
    fun aKeepaliveInGoesToTheOwnerAloneAndCountsAsNoActivity() {
        // #203: the Bluetooth transport answers it; Wi-Fi Aware's quiescence clock must not see it.
        val clock = AtomicLong(1_000)
        val heard = AtomicInteger()
        val h = harness(now = clock::get, onKeepalive = { heard.incrementAndGet() })
        clock.set(9_000)
        writeRecord(h.toLink, LinkFraming.Type.KEEPALIVE)
        writeRecord(h.toLink, LinkFraming.Type.DIGEST, LinkFraming.encodeDigest(DigestWire(listOf("a"))))
        assertNotNull("a record behind it still lands", h.callbacks.digests.poll(2, TimeUnit.SECONDS))
        assertEquals(1, heard.get())
        assertNull(h.callbacks.inbound.poll())
        clock.set(20_000)
        writeRecord(h.toLink, LinkFraming.Type.KEEPALIVE)
        assertTrue(awaitUntil { heard.get() == 2 })
        assertEquals("the digest moved the clock, the keepalive did not", 9_000L, h.link.lastActivityAt)
    }

    @Test
    fun aKeepaliveSentIsOneEmptyRecordReportedOnceWritten() {
        val written = LinkedBlockingQueue<Unit>()
        val h = harness(onKeepaliveWritten = { written.add(Unit) })
        assertTrue(h.link.sendKeepalive())
        val rec = LinkFraming.read(h.fromLink)!!
        assertEquals(LinkFraming.Type.KEEPALIVE, rec.type)
        assertEquals(0, rec.payload.size)
        assertNotNull(written.poll(2, TimeUnit.SECONDS))
        h.link.close()
        assertFalse("a closed link queues none", h.link.sendKeepalive())
    }

    @Test
    fun aKeepaliveRidesBetweenFileChunksAheadOfTheEnd() {
        // N5 of #203's design pass: an answer never waits behind a stream. The small out-pipe holds the writer mid-file.
        val written = AtomicInteger()
        val h = harness(fromLinkBuffer = 16 * 1024, onKeepaliveWritten = { written.incrementAndGet() })
        val body = ByteArray(80 * 1024) { (it % 251).toByte() }
        val file = tmp.newFile("keepalive.bin").apply { writeBytes(body) }
        h.link.sendFile(file, FileMeta(FileKind.ATTACHMENT, "d".repeat(64), "image/webp"))
        h.link.sendKeepalive()
        val types = ArrayList<LinkFraming.Type>()
        var rec = LinkFraming.read(h.fromLink)
        while (rec != null) {
            types.add(rec.type)
            if (rec.type == LinkFraming.Type.FILE_END) break
            rec = LinkFraming.read(h.fromLink)
        }
        val at = types.indexOf(LinkFraming.Type.KEEPALIVE)
        assertTrue("a KEEPALIVE between the header and the end, saw $types", at in 1 until types.indexOf(LinkFraming.Type.FILE_END))
        assertEquals(1, written.get())
    }

    @Test
    fun liveFrameInterleavesBetweenFileChunks() {
        // A blob is streamed as many chunks; a frame enqueued during it must ride the wire BEFORE FILE_END, not
        // queue behind the whole file. A small out-pipe (16 KiB) forces the writer to block after the first
        // chunk, so the frame — enqueued before the test starts draining — is deterministically picked up by
        // drainFramesInto between chunks rather than racing to the end. Body spans several FILE_CHUNK_BYTES.
        val h = harness(fromLinkBuffer = 16 * 1024)
        val key = "c".repeat(64)
        val body = ByteArray(80 * 1024) { (it % 251).toByte() }
        val file = tmp.newFile("interleave.bin").apply { writeBytes(body) }
        val framePayload = frameBytes(id = "live1", senderId = "sender01")

        h.link.sendFile(file, FileMeta(FileKind.ATTACHMENT, key, "image/webp"))
        h.link.send(framePayload) // enqueued while the writer is back-pressured mid-file

        val types = ArrayList<LinkFraming.Type>()
        var interleaved: ByteArray? = null
        var rec = LinkFraming.read(h.fromLink)
        while (rec != null && rec.type != LinkFraming.Type.FILE_END) {
            types.add(rec.type)
            if (rec.type == LinkFraming.Type.FRAME && interleaved == null) interleaved = rec.payload
            rec = LinkFraming.read(h.fromLink)
        }
        if (rec != null) types.add(rec.type) // the terminating FILE_END
        val frameAt = types.indexOf(LinkFraming.Type.FRAME)
        val endAt = types.indexOf(LinkFraming.Type.FILE_END)
        assertTrue("a FRAME must interleave before FILE_END, saw $types", frameAt in 0 until endAt)
        assertArrayEquals("the interleaved frame's bytes are intact", framePayload, interleaved)
    }

    @Test
    fun aSmallChunkPaceKeepsAFrameQueuedMidFileWithinAChunkOrTwo() {
        // #114: on a Coded link the feed takes 2 KiB chunks, so a frame sent mid-transfer waits behind what the
        // socket already holds plus one chunk — never a 16 KiB one. The 4 KiB out-pipe holds the writer mid-file
        // when the frame is enqueued; unbounded rate, so the test runs on the chunk size alone.
        val h = harness(fromLinkBuffer = 4 * 1024, pace = { PaceConfig(bytesPerSec = 0, chunkBytes = 2048) })
        val body = ByteArray(80 * 1024) { (it % 251).toByte() }
        val file = tmp.newFile("coded.bin").apply { writeBytes(body) }
        val framePayload = frameBytes(id = "live1", senderId = "sender01")

        h.link.sendFile(file, FileMeta(FileKind.ATTACHMENT, "d".repeat(64), "image/webp"))
        h.link.send(framePayload)

        val chunks = ArrayList<Int>()
        var bodyBeforeFrame: Int? = null
        val received = ByteArrayOutputStream()
        var rec = LinkFraming.read(h.fromLink)
        while (rec != null && rec.type != LinkFraming.Type.FILE_END) {
            when (rec.type) {
                LinkFraming.Type.FILE_CHUNK -> {
                    chunks.add(rec.payload.size)
                    received.write(rec.payload)
                }

                LinkFraming.Type.FRAME -> {
                    if (bodyBeforeFrame == null) bodyBeforeFrame = received.size()
                }

                else -> {}
            }
            rec = LinkFraming.read(h.fromLink)
        }
        assertTrue("every chunk is at most 2 KiB, saw $chunks", chunks.all { it <= 2048 })
        val before = checkNotNull(bodyBeforeFrame) { "the frame must interleave before FILE_END" }
        assertTrue("the frame waits behind the pipe plus a chunk, not the file ($before B)", before <= 4 * 1024 + 2048)
        assertArrayEquals(body, received.toByteArray())
    }

    @Test
    fun aPaceChangeMidFileTakesTheNextChunk() {
        // The link steps to Coded after the first chunk: the feed reads its pace before every chunk, so the rest of
        // the file goes in Coded-sized chunks without restarting the transfer.
        val reads = AtomicInteger()
        val h =
            harness(pace = {
                if (reads.incrementAndGet() == 1) PaceConfig() else PaceConfig(bytesPerSec = 0, chunkBytes = 2048)
            })
        val body = ByteArray(40 * 1024) { (it % 251).toByte() }
        val file = tmp.newFile("stepdown.bin").apply { writeBytes(body) }

        h.link.sendFile(file, FileMeta(FileKind.ATTACHMENT, "e".repeat(64), "image/webp"))

        val chunks = ArrayList<Int>()
        val received = ByteArrayOutputStream()
        var rec = LinkFraming.read(h.fromLink)
        while (rec != null && rec.type != LinkFraming.Type.FILE_END) {
            if (rec.type == LinkFraming.Type.FILE_CHUNK) {
                chunks.add(rec.payload.size)
                received.write(rec.payload)
            }
            rec = LinkFraming.read(h.fromLink)
        }
        assertEquals("the first chunk at the 1M size", LinkFraming.FILE_CHUNK_BYTES, chunks.first())
        assertTrue("every later chunk at the Coded size, saw $chunks", chunks.drop(1).all { it <= 2048 })
        assertArrayEquals(body, received.toByteArray())
    }

    @Test
    fun theLinkCountsAsFeedingWheneverItsPaceIsRead() {
        // #117: the Bluetooth transport splits its budget among the links whose `txInProgress` is set, so a link must
        // count itself every time it reads its pace, and stop counting once the file is out.
        val linkRef = AtomicReference<FramedLink>()
        val seen = LinkedBlockingQueue<Boolean>()
        val h =
            harness(pace = {
                seen.add(linkRef.get().txInProgress)
                PaceConfig()
            })
        linkRef.set(h.link)
        val body = ByteArray(40 * 1024) { (it % 251).toByte() }
        val file = tmp.newFile("feeding.bin").apply { writeBytes(body) }

        h.link.sendFile(file, FileMeta(FileKind.ATTACHMENT, "f".repeat(64), "image/webp"))
        LinkFraming.read(h.fromLink) // the header
        assertArrayEquals(body, readFileBody(h.fromLink))

        assertTrue("the pace was read", seen.isNotEmpty())
        assertTrue("feeding at every read, saw $seen", seen.all { it })
        assertTrue("not feeding once the file is out", awaitUntil { !h.link.txInProgress })
    }

    @Test
    fun digestsSentWhileTheWriterIsBusyCollapseIntoTheNewest() {
        // ADR 2026-09.tjfb: on a slow link a back-fill held one digest per 60 s re-offer, each minutes stale when
        // written and each ahead of every frame queued after it. A 1 KiB out-pipe holds the writer inside the
        // 64 KiB frame until the test reads, so every send below lands while that frame is still on the socket.
        val metrics = MeshMetrics()
        val h = harness(fromLinkBuffer = 1024, metrics = metrics)
        val backfill = ByteArray(64 * 1024) { 7 }
        val live = frameBytes(id = "live1", senderId = "me000001")
        h.link.send(backfill)
        h.link.sendDigest(listOf("a"))
        h.link.sendDigest(listOf("a", "b"))
        h.link.sendDigest(listOf("a", "b", "c"))
        h.link.send(live)

        assertArrayEquals(backfill, LinkFraming.read(h.fromLink)!!.payload)
        val digest = LinkFraming.read(h.fromLink)!!
        assertEquals(LinkFraming.Type.DIGEST, digest.type)
        assertEquals("one digest, with the newest id set", listOf("a", "b", "c"), LinkFraming.decodeDigest(digest.payload)!!.ids)
        val next = LinkFraming.read(h.fromLink)!!
        assertEquals("the live frame comes next, not two stale digests", LinkFraming.Type.FRAME, next.type)
        assertArrayEquals(live, next.payload)
        assertEquals(2L, metrics.frames().digestsReplaced)
    }

    @Test
    fun anIdleLinkWritesEveryDigestItIsHanded() {
        // The slot empties when the writer takes it, before the record reaches the socket, so a digest sent after
        // the last one was read is a fresh record: the re-offer cadence on an idle link is what it was.
        val metrics = MeshMetrics()
        val h = harness(metrics = metrics)
        h.link.sendDigest(listOf("a"))
        assertEquals(listOf("a"), LinkFraming.decodeDigest(LinkFraming.read(h.fromLink)!!.payload)!!.ids)
        h.link.sendDigest(listOf("b"))
        assertEquals(listOf("b"), LinkFraming.decodeDigest(LinkFraming.read(h.fromLink)!!.payload)!!.ids)
        assertEquals(0L, metrics.frames().digestsReplaced)
    }

    @Test
    fun aDigestAlreadyOnTheSocketIsNeverRewrittenAndTheNextQueuesBehindIt() {
        // Once the writer has taken the waiting digest its bytes are going out as they were; a newer digest is a
        // second record. The ids overflow the 1 KiB out-pipe, so the first digest stays on the socket until read.
        val h = harness(fromLinkBuffer = 1024)
        val first = List(200) { "id-%019d".format(it) }
        h.link.sendDigest(first)
        assertTrue("the writer took the first digest", awaitUntil { h.fromLink.available() > 0 })
        h.link.sendDigest(listOf("newer"))

        val a = LinkFraming.read(h.fromLink)!!
        assertEquals(first, LinkFraming.decodeDigest(a.payload)!!.ids)
        val b = LinkFraming.read(h.fromLink)!!
        assertEquals(LinkFraming.Type.DIGEST, b.type)
        assertEquals(listOf("newer"), LinkFraming.decodeDigest(b.payload)!!.ids)
    }

    @Test
    fun aDigestStillGoesOutBetweenFileChunksAndCollapsesThereToo() {
        // A digest is what makes the peer serve us what we lack, so it keeps riding between a file's chunks
        // rather than waiting out the file, and several sent while the file streams go out as one. The 64 KiB
        // frame ahead of the file holds the writer until the test reads, so all of them are queued first.
        val h = harness(fromLinkBuffer = 1024)
        val file = tmp.newFile("between.bin").apply { writeBytes(ByteArray(80 * 1024)) }
        h.link.send(ByteArray(64 * 1024))
        h.link.sendFile(file, FileMeta(FileKind.ATTACHMENT, "9".repeat(64), "image/jpeg"))
        h.link.sendDigest(listOf("a"))
        h.link.sendDigest(listOf("a", "b"))
        h.link.send(frameBytes(id = "live1", senderId = "me000001"))

        val types = ArrayList<LinkFraming.Type>()
        val digests = ArrayList<List<String>>()
        var rec = LinkFraming.read(h.fromLink)
        while (rec != null) {
            types.add(rec.type)
            if (rec.type == LinkFraming.Type.DIGEST) digests.add(LinkFraming.decodeDigest(rec.payload)!!.ids)
            if (rec.type == LinkFraming.Type.FILE_END) break
            rec = LinkFraming.read(h.fromLink)
        }
        assertEquals("one digest, with the newest id set", listOf(listOf("a", "b")), digests)
        val end = types.indexOf(LinkFraming.Type.FILE_END)
        assertTrue("the digest rides between chunks, saw $types", types.indexOf(LinkFraming.Type.DIGEST) in 0 until end)
        assertTrue("so does the live frame, saw $types", types.lastIndexOf(LinkFraming.Type.FRAME) in 1 until end)
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

    // --- A cut attachment resumes (#116, ADR 2026-10.wtyc) ---

    @Test
    fun anAttachmentCutByTheLinkClosingIsResumedByTheNextLinkAndLandsWhole() {
        val key = "7".repeat(64)
        val body = ByteArray(1000) { (it % 251).toByte() }
        val first = harness(nodeId = "holder01")
        writeRecord(first.toLink, LinkFraming.Type.FILE_HEADER, LinkFraming.encodeFileHeader(hdr("ATTACHMENT", key, size = 1000)))
        writeRecord(first.toLink, LinkFraming.Type.FILE_CHUNK, body.copyOf(400))
        assertTrue(awaitUntil { first.link.rxFile == ArrivingFile(key, 400, 1000) })
        first.link.close() // the radio dropped it 40 % in
        assertEquals("the prefix is kept for the next ask", 400L, partials.length(key))

        val metrics = MeshMetrics()
        val second = harness(nodeId = "holder02", metrics = metrics)
        writeRecord(
            second.toLink,
            LinkFraming.Type.FILE_HEADER,
            LinkFraming.encodeFileHeader(hdr("ATTACHMENT", key, size = 600, offset = 400)),
        )
        assertTrue("the ring resumes where it was", awaitUntil { second.link.rxFile == ArrivingFile(key, 400, 1000) })
        writeRecord(second.toLink, LinkFraming.Type.FILE_CHUNK, body.copyOfRange(400, 1000))
        writeRecord(second.toLink, LinkFraming.Type.FILE_END)
        val got = second.callbacks.files.poll(2, TimeUnit.SECONDS)
        assertNotNull(got)
        assertEquals(400L, got!!.resumedFrom)
        assertArrayEquals("spliced byte for byte", body, File(got.path).readBytes())
        assertEquals("one tail taken onto a kept prefix", 1L, metrics.files().filesResumedIn)
    }

    @Test
    fun aHeaderReadAfterCloseOpensNoFile() {
        // A socket's buffered input can still hand the reader records after close() (neither LinkSocket closes it):
        // a header among them must not open a temp file that nothing would ever clean up.
        val toLink = PipedOutputStream()
        val linkInput = PipedInputStream(toLink, 1 shl 18)
        val fromLink = PipedInputStream(1 shl 18)
        val linkOutput = PipedOutputStream(fromLink)
        val socket =
            object : LinkSocket {
                override val input = linkInput
                override val output: OutputStream = linkOutput

                override fun close() {
                    runCatching { linkOutput.close() } // the input stays readable, as a buffered one does
                }
            }
        val callbacks = Recording()
        val link =
            FramedLink(
                nodeId = "late0001",
                peer = Peer("late0001"),
                socket = socket,
                scope = scope,
                cacheDir = tmp.root,
                partials = partials,
                metrics = MeshMetrics(),
                callbacks = callbacks,
                now = { 0L },
            )
        link.start()
        link.close()
        val key = "6".repeat(64)
        writeRecord(toLink, LinkFraming.Type.FILE_HEADER, LinkFraming.encodeFileHeader(hdr("ATTACHMENT", key, size = 100)))
        writeRecord(toLink, LinkFraming.Type.FILE_CHUNK, ByteArray(100))
        writeRecord(toLink, LinkFraming.Type.FILE_END)
        assertNull("nothing finalizes", callbacks.files.poll(500, TimeUnit.MILLISECONDS))
        assertNull(link.rxFile)
        assertTrue("no temp file", tmp.root.listFiles()!!.none { it.name.startsWith("link-rx-") })
    }

    @Test
    fun sendFileFromAnOffsetStreamsOnlyTheTailAndSaysSo() {
        val metrics = MeshMetrics()
        val h = harness(metrics = metrics)
        val key = "5".repeat(64)
        val body = ByteArray(3000) { (it % 97).toByte() }
        val file = tmp.newFile("tail.bin").apply { writeBytes(body) }
        h.link.sendFile(file, FileMeta(FileKind.ATTACHMENT, key, "image/jpeg", offset = 1000))

        val header = LinkFraming.decodeFileHeader(LinkFraming.read(h.fromLink)!!.payload)!!
        assertEquals("the size is the tail's", 2000L, header.size)
        assertEquals(1000L, header.offset)
        assertArrayEquals(body.copyOfRange(1000, 3000), readFileBody(h.fromLink))
        assertTrue(awaitUntil { metrics.files().filesResumedOut == 1L })
    }

    @Test
    fun aCompletePrefixGetsAnEmptyTailAndAnOffsetPastTheEndTheWholeFile() {
        val h = harness()
        val key = "4".repeat(64)
        val body = ByteArray(3000) { (it % 89).toByte() }
        val file = tmp.newFile("edges.bin").apply { writeBytes(body) }

        h.link.sendFile(file, FileMeta(FileKind.ATTACHMENT, key, "image/jpeg", offset = 3000))
        val empty = LinkFraming.decodeFileHeader(LinkFraming.read(h.fromLink)!!.payload)!!
        assertEquals(0L, empty.size)
        assertEquals(3000L, empty.offset)
        assertEquals("no chunk, just the end", 0, readFileBody(h.fromLink).size)

        h.link.sendFile(file, FileMeta(FileKind.ATTACHMENT, key, "image/jpeg", offset = 5000))
        val whole = LinkFraming.decodeFileHeader(LinkFraming.read(h.fromLink)!!.payload)!!
        assertNull("the peer's nonsense is a whole file", whole.offset)
        assertEquals(3000L, whole.size)
        assertArrayEquals(body, readFileBody(h.fromLink))
    }

    @Test
    fun aSourceThatIsGoneCostsThatFileNotTheLink() {
        val h = harness()
        val key = "3".repeat(64)
        h.link.sendFile(File(tmp.root, "evicted.bin"), FileMeta(FileKind.ATTACHMENT, key, "image/jpeg"))
        assertTrue("released", awaitUntil { !h.link.hasPendingFile(key) })
        assertFalse("no longer counted as feeding (#117)", h.link.txInProgress)
        val payload = frameBytes(id = "after01", senderId = "me000001")
        h.link.send(payload)
        val rec = LinkFraming.read(h.fromLink)
        assertEquals("no header went out; the link still carries frames", LinkFraming.Type.FRAME, rec!!.type)
        assertNull(h.callbacks.downs.poll(200, TimeUnit.MILLISECONDS))
    }

    /**
     * A harness whose `onBusy` pokes record the link's (feeding, receiving) pair as the poke reads it — the Bluetooth
     * link boost's view (#120) — so a test can tell the edges apart.
     */
    private fun busyHarness(fromLinkBuffer: Int = 1 shl 18): Pair<Harness, LinkedBlockingQueue<Pair<Boolean, Boolean>>> {
        val pokes = LinkedBlockingQueue<Pair<Boolean, Boolean>>()
        val linkRef = AtomicReference<FramedLink>()
        val h =
            harness(fromLinkBuffer = fromLinkBuffer, onBusy = {
                linkRef.get()?.let { pokes.add(it.feeding to it.rxInProgress) }
            })
        linkRef.set(h.link)
        return h to pokes
    }

    @Test
    fun aQueuedFileIsOneFeedingEdgeAndItsEndIsAnother() {
        // #120: the boost asks for a short interval on the rising edge and lets go after the falling one, so a second
        // file queued behind the first must not poke, and a poke must be free to read the link (no lock held).
        val (h, pokes) = busyHarness(fromLinkBuffer = 16 * 1024)
        val file = tmp.newFile("edges.bin").apply { writeBytes(ByteArray(40 * 1024)) }
        assertTrue(h.link.sendFile(file, FileMeta(FileKind.ATTACHMENT, "a".repeat(64), "image/jpeg")))
        assertEquals("feeding from the enqueue", true to false, pokes.poll(2, TimeUnit.SECONDS))
        assertTrue(h.link.sendFile(file, FileMeta(FileKind.ATTACHMENT, "b".repeat(64), "image/jpeg")))
        assertTrue(h.link.busy)
        readFileBody(h.fromLink.also { assertEquals(LinkFraming.Type.FILE_HEADER, LinkFraming.read(it)!!.type) })
        readFileBody(h.fromLink.also { assertEquals(LinkFraming.Type.FILE_HEADER, LinkFraming.read(it)!!.type) })
        assertEquals("idle once the last is out", false to false, pokes.poll(2, TimeUnit.SECONDS))
        assertNull("nothing between the two files", pokes.poll(200, TimeUnit.MILLISECONDS))
        assertFalse(h.link.busy)
    }

    @Test
    fun aFileWhoseSourceIsGoneStillEndsItsFeedingEdge() {
        // A file larger than the pipe holds the feed open until we read it, and the gone one queues behind it. Alone,
        // the gone file could be taken and skipped by the writer before the enqueue's own report read the state, and
        // a rising edge nobody saw is never reported (job 8771): the edges are the state's, not the enqueue's.
        val (h, pokes) = busyHarness(fromLinkBuffer = 16 * 1024)
        val held = tmp.newFile("held.bin").apply { writeBytes(ByteArray(40 * 1024)) }
        assertTrue(h.link.sendFile(held, FileMeta(FileKind.ATTACHMENT, "2".repeat(64), "image/jpeg")))
        assertEquals("feeding from the enqueue", true to false, pokes.poll(2, TimeUnit.SECONDS))
        h.link.sendFile(File(tmp.root, "evicted.bin"), FileMeta(FileKind.ATTACHMENT, "3".repeat(64), "image/jpeg"))
        readFileBody(h.fromLink.also { assertEquals(LinkFraming.Type.FILE_HEADER, LinkFraming.read(it)!!.type) })
        assertEquals("the skipped file ends the feed", false to false, pokes.poll(2, TimeUnit.SECONDS))
        assertFalse(h.link.busy)
    }

    @Test
    fun aFileComingInIsAReceivingEdgeFromItsHeaderToItsEnd() {
        val (h, pokes) = busyHarness()
        val key = "d".repeat(64)
        writeRecord(h.toLink, LinkFraming.Type.FILE_HEADER, LinkFraming.encodeFileHeader(hdr("ATTACHMENT", key, size = 300)))
        assertEquals("receiving from the header", false to true, pokes.poll(2, TimeUnit.SECONDS))
        writeRecord(h.toLink, LinkFraming.Type.FILE_CHUNK, ByteArray(300))
        writeRecord(h.toLink, LinkFraming.Type.FRAME, frameBytes(id = "mid01", senderId = "peer0001"))
        assertNotNull(h.callbacks.inbound.poll(2, TimeUnit.SECONDS))
        assertNull("a chunk or a frame is no edge", pokes.poll(200, TimeUnit.MILLISECONDS))
        writeRecord(h.toLink, LinkFraming.Type.FILE_END)
        assertEquals("idle at the end", false to false, pokes.poll(2, TimeUnit.SECONDS))
    }

    @Test
    fun aCloseEndsEveryEdgeAtOnce() {
        val (h, pokes) = busyHarness(fromLinkBuffer = 16 * 1024)
        val file = tmp.newFile("closing.bin").apply { writeBytes(ByteArray(80 * 1024)) }
        writeRecord(h.toLink, LinkFraming.Type.FILE_HEADER, LinkFraming.encodeFileHeader(hdr("ATTACHMENT", "e".repeat(64), size = 900)))
        assertEquals(false to true, pokes.poll(2, TimeUnit.SECONDS))
        assertTrue(h.link.sendFile(file, FileMeta(FileKind.ATTACHMENT, "f".repeat(64), "image/jpeg")))
        assertEquals(true to true, pokes.poll(2, TimeUnit.SECONDS))
        h.link.close()
        assertEquals("a closed link carries nothing", false to false, pokes.poll(2, TimeUnit.SECONDS))
        assertFalse(h.link.busy)
    }

    /** Reads FILE_CHUNK records up to the FILE_END and returns their bytes. */
    private fun readFileBody(fromLink: PipedInputStream): ByteArray {
        val out = ByteArrayOutputStream()
        var rec = LinkFraming.read(fromLink)
        while (rec != null && rec.type != LinkFraming.Type.FILE_END) {
            assertEquals(LinkFraming.Type.FILE_CHUNK, rec.type)
            out.write(rec.payload)
            rec = LinkFraming.read(fromLink)
        }
        return out.toByteArray()
    }

    private fun hdr(
        kind: String,
        key: String,
        size: Long? = null,
        offset: Long? = null,
    ) = app.getknit.knit.mesh.link
        .FileHeaderWire(kind = kind, key = key, mime = "image/jpeg", size = size, offset = offset)
}
