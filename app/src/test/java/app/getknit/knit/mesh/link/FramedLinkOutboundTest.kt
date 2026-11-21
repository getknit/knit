package app.getknit.knit.mesh.link

import app.getknit.knit.mesh.FileKind
import app.getknit.knit.mesh.FileMeta
import app.getknit.knit.mesh.InboundFrame
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.PartialBlobs
import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.ReceivedDigest
import app.getknit.knit.mesh.ReceivedFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * [FramedLink]'s outbound count, its handed-down counter and its drain signal (contract revision 13, "Rounds, released as
 * the link drains"), over a fake socket whose flush the test holds: the count falls only once the flush that took a
 * record returned, never for a file's bytes or a control record, and the signal fires once per crossing.
 */
class FramedLinkOutboundTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Every link a test opened: closed after it, so no reader stays blocked on its input holding an IO thread.
    private val opened = CopyOnWriteArrayList<FramedLink>()

    @After
    fun tearDown() {
        opened.forEach { it.close() }
        scope.cancel()
    }

    /** The socket's output: keeps what it was written, holds each flush while [hold] is set, throws while [fail] is. */
    private class HeldOutput : OutputStream() {
        private val written = ByteArrayOutputStream()
        private val permits = Semaphore(0)

        @Volatile var hold = false

        @Volatile var fail = false
        val flushes = AtomicInteger()

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(
            b: ByteArray,
            off: Int,
            len: Int,
        ) {
            if (fail) throw IOException("socket gone")
            synchronized(written) { written.write(b, off, len) }
        }

        override fun flush() {
            flushes.incrementAndGet()
            if (hold) permits.acquire()
        }

        /** Lets [n] held flushes return. */
        fun let(n: Int = 1) = permits.release(n)

        /** Holds no flush from now on, and lets go of any held. */
        fun open() {
            hold = false
            permits.release(LOTS)
        }

        fun records(): List<LinkFraming.Message> {
            val input = ByteArrayInputStream(synchronized(written) { written.toByteArray() })
            return generateSequence { LinkFraming.read(input) }.toList()
        }
    }

    /** An input that never carries a record: it blocks until the link closes it. */
    private class SilentInput : InputStream() {
        private val closed = CountDownLatch(1)

        override fun read(): Int {
            closed.await()
            return -1
        }

        override fun close() = closed.countDown()
    }

    /** What the reader raises; these tests drive only the writer, so each is dropped. */
    private class NoCallbacks : LinkCallbacks {
        override suspend fun onInbound(frame: InboundFrame) = Unit

        override fun onDigest(digest: ReceivedDigest) = Unit

        override fun onFile(file: ReceivedFile) = Unit

        override fun onLinkDown(nodeId: String) = Unit
    }

    private class Harness(
        val link: FramedLink,
        val out: HeldOutput,
        val drained: AtomicInteger,
    )

    private fun harness(
        drainLine: Long = 0L,
        pace: () -> PaceConfig = { PaceConfig() },
        now: () -> Long = { 0L },
        onDrained: () -> Unit = {},
        // What the peer writes to the link; silent unless a test streams a file in.
        input: InputStream = SilentInput(),
    ): Harness {
        val out = HeldOutput()
        val drained = AtomicInteger()
        val socket =
            object : LinkSocket {
                override val input: InputStream = input
                override val output: OutputStream = out

                override fun close() {
                    input.close()
                }
            }
        val link =
            FramedLink(
                nodeId = "peer0001",
                peer = Peer("peer0001"),
                socket = socket,
                scope = scope,
                cacheDir = tmp.root,
                partials = PartialBlobs(File(tmp.root, "partials"), minBytes = 1),
                metrics = MeshMetrics(),
                callbacks = NoCallbacks(),
                now = now,
                pace = pace,
                proven = false,
                drainLine = drainLine,
                onDrained = {
                    drained.incrementAndGet()
                    onDrained()
                },
            )
        opened += link
        link.start()
        return Harness(link, out, drained)
    }

    private fun awaitUntil(cond: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_S)
        while (System.nanoTime() < deadline) {
            if (cond()) return true
            Thread.sleep(POLL_MS)
        }
        return cond()
    }

    private fun digestSize(ids: List<String>): Long = LinkFraming.encodeDigest(DigestWire(ids)).size.toLong()

    private fun file(bytes: Int): File = tmp.newFile().apply { writeBytes(ByteArray(bytes) { it.toByte() }) }

    @Test
    fun aFrameCountsUntilTheFlushThatTookItReturned() {
        val h = harness()
        h.out.hold = true
        h.link.send(ByteArray(100))
        assertTrue("the writer reached the frame's flush", awaitUntil { h.out.flushes.get() == 1 })
        // Written to the stream, not yet through its flush: still queued, not handed down.
        assertEquals(100L, h.link.queuedBytes)
        assertEquals(0L, h.link.handedDownBytes)
        h.out.let()
        assertTrue(awaitUntil { h.link.queuedBytes == 0L })
        assertEquals(100L, h.link.handedDownBytes)
    }

    @Test
    fun handedDownOnlyGrowsByWhatEachFlushCarried() {
        val h = harness()
        h.link.send(ByteArray(40))
        assertTrue(awaitUntil { h.link.handedDownBytes == 40L })
        h.link.send(ByteArray(60))
        assertTrue(awaitUntil { h.link.handedDownBytes == 100L })
        assertEquals(0L, h.link.queuedBytes)
    }

    @Test
    fun aSendRefusedByAClosedLinkIsTakenBack() {
        val h = harness()
        h.link.close()
        h.link.send(ByteArray(100))
        h.link.sendDigest(listOf("a", "b"))
        assertEquals(0L, h.link.countedBytes)
        assertEquals(0L, h.link.handedDownBytes)
    }

    @Test
    fun thePendingDigestCountsItsEncodedSizeAndAReplacementSwapsIt() {
        val h = harness()
        h.out.hold = true
        h.link.send(ByteArray(10)) // holds the writer at its flush
        assertTrue(awaitUntil { h.out.flushes.get() == 1 })
        val older = listOf("id-1", "id-2", "id-3")
        val newer = listOf("id-4")
        h.link.sendDigest(older)
        assertEquals(10L + digestSize(older), h.link.queuedBytes)
        h.link.sendDigest(newer)
        // The newer set replaces the older where it stands: one digest counted, at its own size.
        assertEquals(10L + digestSize(newer), h.link.queuedBytes)
        h.out.open()
        assertTrue(awaitUntil { h.link.queuedBytes == 0L })
        assertEquals(10L + digestSize(newer), h.link.handedDownBytes)
        val digests = h.out.records().filter { it.type == LinkFraming.Type.DIGEST }
        assertEquals(1, digests.size)
        assertEquals(newer, LinkFraming.decodeDigest(digests.single().payload)?.ids)
    }

    @Test
    fun keepalivesAndTagsAreNeverCounted() {
        val h = harness()
        h.out.hold = true
        assertTrue(h.link.sendKeepalive())
        assertTrue(awaitUntil { h.out.flushes.get() == 1 })
        assertEquals(0L, h.link.queuedBytes)
        assertTrue(h.link.sendTagNext(ByteArray(LinkFraming.TAG_NEXT_BYTES)))
        h.link.send(ByteArray(100))
        assertEquals(100L, h.link.queuedBytes)
        h.out.open()
        assertTrue(awaitUntil { h.out.records().size == 3 && h.link.queuedBytes == 0L })
        assertEquals(100L, h.link.handedDownBytes)
    }

    @Test
    fun aFileIsNeverCountedButAFrameBetweenItsChunksIs() {
        val h = harness()
        h.out.hold = true
        val f = file(LinkFraming.FILE_CHUNK_BYTES * 3)
        assertTrue(h.link.sendFile(f, FileMeta(FileKind.ATTACHMENT, "k".repeat(64), "image/jpeg")))
        // The writer holds at the stream's first flush: its header and first chunk's.
        assertTrue(awaitUntil { h.out.flushes.get() == 1 })
        assertTrue("a queued file is the link's `busy`", h.link.busy)
        assertEquals(0L, h.link.queuedBytes)
        h.link.send(ByteArray(70))
        assertEquals(70L, h.link.queuedBytes)
        h.out.open()
        assertTrue(awaitUntil { !h.link.busy && h.link.queuedBytes == 0L })
        assertEquals("only the frame was handed down as counted", 70L, h.link.handedDownBytes)
    }

    @Test
    fun anOversizedRecordIsTakenOffAndNeverHandedDown() {
        val h = harness()
        h.link.send(ByteArray(LinkFraming.MAX_PAYLOAD_BYTES + 1))
        h.link.send(ByteArray(5))
        assertTrue(awaitUntil { h.link.handedDownBytes == 5L })
        assertEquals(0L, h.link.queuedBytes)
    }

    @Test
    fun aWriteThatThrowsLeavesItsBytesCountedAndTheLinkNotDraining() {
        val h = harness()
        h.out.fail = true
        h.link.send(ByteArray(100))
        assertTrue(awaitUntil { h.link.queuedBytes >= FramedLink.NOT_DRAINING })
        assertEquals("what the failed write held stays counted", 100L, h.link.countedBytes)
        assertEquals(0L, h.link.handedDownBytes)
        assertEquals(0, h.drained.get())
    }

    @Test
    fun aClosedLinkReadsAsNotDrainingAndCloseSignalsNothing() {
        val h = harness()
        h.link.send(ByteArray(10))
        assertTrue(awaitUntil { h.link.queuedBytes == 0L })
        val signals = h.drained.get()
        h.link.close()
        assertTrue("an empty queue on a closed link is never zero", h.link.queuedBytes >= FramedLink.NOT_DRAINING)
        Thread.sleep(SETTLE_MS)
        assertEquals(signals, h.drained.get())
    }

    @Test
    fun theSignalFiresOnCrossingBelowTheLineAndOnEmptyNotPerFrame() {
        val h = harness(drainLine = 1_000L)
        h.out.hold = true
        repeat(10) { h.link.send(ByteArray(300)) } // 3,000 counted before the first flush returns
        assertTrue(awaitUntil { h.out.flushes.get() == 1 })
        assertEquals(3_000L, h.link.queuedBytes)
        h.out.open()
        assertTrue(awaitUntil { h.link.queuedBytes == 0L })
        // 1,200 → 900 crosses the line; 300 → 0 empties it. The frames in between poke nothing.
        assertEquals(2, h.drained.get())
    }

    @Test
    fun withNoLineOnlyAnEmptyQueueSignals() {
        val h = harness()
        h.out.hold = true
        repeat(3) { h.link.send(ByteArray(10)) }
        assertTrue(awaitUntil { h.out.flushes.get() == 1 })
        h.out.open()
        assertTrue(awaitUntil { h.link.queuedBytes == 0L })
        assertEquals(1, h.drained.get())
    }

    @Test
    fun aDigestThatShrankTheQueueBelowTheLineIsSignalledAtTheNextFlush() {
        val h = harness(drainLine = 1_000L)
        h.out.hold = true
        h.link.send(ByteArray(200))
        assertTrue(awaitUntil { h.out.flushes.get() == 1 })
        h.link.send(ByteArray(200))
        val big = List(120) { "frame-$it" }
        h.link.sendDigest(big)
        assertTrue(digestSize(big) >= 1_000L)
        h.out.let() // the first frame's flush: the writer sees the queue over the line, still over after
        assertTrue(awaitUntil { h.out.flushes.get() == 2 })
        assertEquals(0, h.drained.get())
        h.link.sendDigest(listOf("one")) // off the writer, the queue falls below the line
        assertTrue(h.link.queuedBytes < 1_000L)
        h.out.open()
        assertTrue(awaitUntil { h.link.queuedBytes == 0L })
        // The second frame's flush owes the crossing; the digest's empties the queue.
        assertEquals(2, h.drained.get())
    }

    @Test
    fun aPacedFeedTakesAboutOneRoundPerChunkGap() {
        // A Bluetooth-like pace: 2 KiB chunks at 8 KiB/s, a chunk every 250 ms, on the real clock the pace waits run on.
        val chunk = 2_048
        val chunks = 6
        val round = 600
        val signals = LinkedBlockingQueue<Unit>()
        val h =
            harness(
                drainLine = round.toLong(),
                pace = { PaceConfig(bytesPerSec = chunk * 4, chunkBytes = chunk) },
                now = { System.nanoTime() / NANOS_PER_MS },
                onDrained = { signals.add(Unit) },
            )
        assertTrue(h.link.sendFile(file(chunk * chunks), FileMeta(FileKind.ATTACHMENT, "p".repeat(64), "image/jpeg")))
        // Custody's side: a round (one frame of it) now, the next whenever the link says it drained, off the writer.
        var sent = 0
        val rounds =
            Thread {
                while (h.link.busy || sent == 0) {
                    h.link.send(ByteArray(round))
                    sent++
                    if (signals.poll(WAIT_S, TimeUnit.SECONDS) == null) break
                }
            }
        rounds.start()
        assertTrue(awaitUntil { !h.link.busy })
        rounds.join(TimeUnit.SECONDS.toMillis(WAIT_S))
        val records = h.out.records()
        val first = records.indexOfFirst { it.type == LinkFraming.Type.FILE_CHUNK }
        val last = records.indexOfLast { it.type == LinkFraming.Type.FILE_CHUNK }
        // Between two chunks, never more than one round: the writer writes frames only in a chunk's gap, and the next
        // round is cut only once that gap's flush returned.
        val gaps =
            records
                .subList(first, last + 1)
                .fold(mutableListOf(0)) { acc, r ->
                    if (r.type == LinkFraming.Type.FILE_CHUNK) acc.add(0) else acc[acc.lastIndex] += 1
                    acc
                }.drop(1)
        assertEquals(chunks - 1, gaps.size - 1)
        assertTrue("at most one round a gap: $gaps", gaps.all { it <= 1 })
        // Not one a gap: the pace is cumulative, so a writer held up writes its next chunks back to back, and a slow runner
        // may give the rounds thread no turn between them.
        assertTrue("a round rode between the file's chunks: $gaps", gaps.count { it == 1 } >= 1)
        assertFalse("never more than a round counted while the file streamed", h.link.queuedBytes > round)
    }

    @Test
    fun anOutgoingFileMovesWhileItsWriterOrItsReceiverDoesAndNotPastTheStall() {
        val clock = AtomicLong(1_000L)
        val h = harness(now = clock::get)
        h.out.hold = true
        val key = "m".repeat(64)
        assertFalse("no file, nothing moving", h.link.fileMoving)
        assertTrue(h.link.sendFile(file(LinkFraming.FILE_CHUNK_BYTES * 2), FileMeta(FileKind.ATTACHMENT, key, "image/jpeg")))
        // The writer holds at its first chunk's flush: the stream's last write is at 1,000.
        assertTrue(awaitUntil { h.out.flushes.get() == 1 })
        assertTrue(h.link.fileMoving)
        clock.set(1_000L + FeedMeter.STALL_MS)
        assertTrue("still moving at the stall's edge", h.link.fileMoving)
        clock.set(1_001L + FeedMeter.STALL_MS)
        assertFalse("a stream with no write for the stall is stalled", h.link.fileMoving)
        assertTrue("and still busy: only the moving read changes", h.link.busy)
        // The receiver's report that it holds more: the stream moves again, timed from the report from now on.
        h.link.onProgress(key, held = 1_024L, ms = 500L)
        assertTrue(h.link.fileMoving)
        clock.addAndGet(FeedMeter.STALL_MS + 1)
        assertFalse(h.link.fileMoving)
        // A report that holds no more than the last moves nothing.
        h.link.onProgress(key, held = 1_024L, ms = 900L)
        assertFalse(h.link.fileMoving)
        h.out.open()
        assertTrue(awaitUntil { !h.link.busy })
        assertFalse(h.link.fileMoving)
    }

    @Test
    fun aPacedWriterAsleepPastItsWaitIsNotAStall() {
        // FeedMeter's rule (pj9w): a pace `delay` the CPU slept through is the writer's sleep, not the feed's. One chunk a
        // second, so the writer sits in its pace wait after each; the clock then jumps as a suspend would move it.
        val clock = AtomicLong(1_000L)
        val chunk = 1_024
        // A chunk every 8 s: the first chunk's pace wait outlasts the test, so the sleep below lands inside it.
        val h = harness(now = clock::get, pace = { PaceConfig(bytesPerSec = chunk / 8, chunkBytes = chunk) })
        assertTrue(h.link.sendFile(file(chunk * 4), FileMeta(FileKind.ATTACHMENT, "s".repeat(64), "image/jpeg")))
        assertTrue(awaitUntil { h.out.records().count { it.type == LinkFraming.Type.FILE_CHUNK } == 1 })
        // Covers the writer's few steps from the chunk's flush into pauseFor's `meter.pausing`; it then waits 8 s there.
        Thread.sleep(SETTLE_MS)
        clock.addAndGet(FeedMeter.STALL_MS + 5_000L)
        assertTrue("asleep in its own pace wait, the writer has not stalled", h.link.fileMoving)
        h.link.close()
    }

    @Test
    fun aFileQueuedWithNothingStreamingMovesWhileTheWriterDoes() {
        val clock = AtomicLong(1_000L)
        val h = harness(now = clock::get)
        h.out.hold = true
        h.link.send(ByteArray(10)) // the writer holds on this frame's flush, before it reaches the file
        assertTrue(awaitUntil { h.out.flushes.get() == 1 })
        assertTrue(h.link.sendFile(file(100), FileMeta(FileKind.ATTACHMENT, "q".repeat(64), "image/jpeg")))
        assertTrue("queued is moving while the writer moves", h.link.fileMoving)
        clock.addAndGet(FeedMeter.STALL_MS + 1)
        assertFalse("a writer stuck on a write holds the queued file", h.link.fileMoving)
        h.out.open()
        assertTrue(awaitUntil { !h.link.busy })
    }

    @Test
    fun anIncomingFileMovesByItsLastRecordRead() {
        val clock = AtomicLong(1_000L)
        val toLink = PipedOutputStream()
        val h = harness(now = clock::get, input = PipedInputStream(toLink, 1 shl 16))
        val header = FileHeaderWire(kind = "ATTACHMENT", key = "e".repeat(64), mime = "image/jpeg", size = 4_096L, offset = null)
        LinkFraming.write(toLink, LinkFraming.Type.FILE_HEADER, LinkFraming.encodeFileHeader(header))
        toLink.flush()
        assertTrue(awaitUntil { h.link.rxInProgress })
        assertTrue(h.link.fileMoving)
        clock.addAndGet(FeedMeter.STALL_MS + 1)
        assertFalse("nothing in for the stall: stalled", h.link.fileMoving)
        assertTrue(h.link.busy)
        LinkFraming.write(toLink, LinkFraming.Type.FILE_CHUNK, ByteArray(1_024))
        toLink.flush()
        assertTrue("a chunk in moves it again", awaitUntil { h.link.fileMoving })
        LinkFraming.write(toLink, LinkFraming.Type.FILE_END)
        toLink.flush()
        assertTrue(awaitUntil { !h.link.rxInProgress })
        assertFalse(h.link.fileMoving)
        toLink.close()
    }

    private companion object {
        const val WAIT_S = 5L
        const val POLL_MS = 5L
        const val SETTLE_MS = 100L
        const val LOTS = 1_000_000
        const val NANOS_PER_MS = 1_000_000L
    }
}
