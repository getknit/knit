package app.getknit.knit.mesh.link

import app.getknit.knit.mesh.crypto.LinkProof
import app.getknit.knit.mesh.crypto.LinkSeal
import app.getknit.knit.mesh.crypto.SealException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * [SealedLinkSocket] (#286) over in-memory streams: what it seals (on flush or a full buffer, never an empty frame), what
 * it opens (any split of the inner stream), and how it fails — a content fault is a [SealException] and the armed count
 * once, a raw fault passes through unchanged and is never counted, and [SealedLinkSocket.close] unblocks a writer stuck in
 * the socket below without taking the seal lock.
 */
class SealedLinkSocketTest {
    private class RawLink(
        override val input: InputStream,
        override val output: OutputStream,
        private val onClose: () -> Unit = {},
    ) : LinkSocket {
        val closes = AtomicInteger()

        override fun close() {
            closes.incrementAndGet()
            onClose()
        }
    }

    /** Counts flushes and the bytes of each write the seal makes. */
    private class Recorder : ByteArrayOutputStream() {
        var flushes = 0

        override fun flush() {
            flushes++
        }
    }

    private val keys: Pair<LinkSeal.Keys, LinkSeal.Keys> by lazy {
        val d = LinkSeal.Ephemeral.fixed(ByteArray(32) { (0x40 + it).toByte() })
        val r = LinkSeal.Ephemeral.fixed(ByteArray(32) { (0x60 + it).toByte() })
        LinkSeal.derive(LinkProof.Role.DIALER, d, r.pub) to LinkSeal.derive(LinkProof.Role.RESPONDER, r, d.pub)
    }

    private fun dialer(
        raw: LinkSocket,
        firstCounter: Long = 0,
    ) = SealedLinkSocket(raw, keys.first, LinkProof.Role.DIALER, firstCounter)

    private fun responderReading(bytes: ByteArray): SealedLinkSocket =
        SealedLinkSocket(RawLink(ByteArrayInputStream(bytes), ByteArrayOutputStream()), keys.second, LinkProof.Role.RESPONDER)

    /** The outer records [bytes] hold. */
    private fun frames(bytes: ByteArray): List<LinkFraming.Message> {
        val input = ByteArrayInputStream(bytes)
        return generateSequence { LinkFraming.read(input) }.toList()
    }

    private fun sealedBy(write: (OutputStream) -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        val socket = dialer(RawLink(ByteArrayInputStream(ByteArray(0)), out))
        write(socket.output)
        return out.toByteArray()
    }

    @Test
    fun aFlushWithNothingBufferedSealsNothingButStillFlushesTheSocketBelow() {
        val out = Recorder()
        val socket = dialer(RawLink(ByteArrayInputStream(ByteArray(0)), out))

        socket.output.flush()
        socket.output.write(byteArrayOf(1, 2, 3))
        socket.output.flush()
        socket.output.flush() // a double flush seals nothing more

        assertEquals(3, out.flushes)
        val written = frames(out.toByteArray())
        assertEquals(listOf(LinkFraming.Type.SEALED), written.map { it.type })
        assertEquals(3 + LinkSeal.TAG_BYTES, written[0].payload.size)
    }

    @Test
    fun nothingIsSealedBeforeAFlushOrAFullBuffer() {
        val out = Recorder()
        val socket = dialer(RawLink(ByteArrayInputStream(ByteArray(0)), out))
        socket.output.write(ByteArray(LinkSeal.MAX_PLAINTEXT_BYTES - 1))
        assertEquals(0, out.size())
        socket.output.write(0)
        assertEquals("a full buffer seals at once", 1, frames(out.toByteArray()).size)
        assertEquals("but flushes nothing below", 0, out.flushes)
    }

    @Test
    fun aWriteOverOneFrameIsSplitIntoFullFramesAndATail() {
        val plain = Random(1).nextBytes(3 * LinkSeal.MAX_PLAINTEXT_BYTES + 3_392)
        val wire =
            sealedBy {
                it.write(plain)
                it.flush()
            }

        val sizes = frames(wire).map { it.payload.size - LinkSeal.TAG_BYTES }
        assertEquals(listOf(65_536, 65_536, 65_536, 3_392), sizes)
        assertArrayEquals(plain, responderReading(wire).input.readNBytes(plain.size + 1))
    }

    @Test
    fun a512KiBRecordThroughAn8KiBBufferedStreamCrossesWhole() {
        val payload = Random(2).nextBytes(LinkFraming.MAX_PAYLOAD_BYTES)
        val wire =
            sealedBy { sealed ->
                // FramedLink's writer: one BufferedOutputStream over the socket's output, the record one write.
                val out = BufferedOutputStream(sealed)
                LinkFraming.write(out, LinkFraming.Type.FILE_CHUNK, payload)
                LinkFraming.write(out, LinkFraming.Type.KEEPALIVE)
                out.flush()
            }

        assertTrue(frames(wire).all { it.payload.size <= LinkFraming.SEALED_MAX_BYTES })
        val input = responderReading(wire).input
        assertEquals(LinkFraming.Message(LinkFraming.Type.FILE_CHUNK, payload), LinkFraming.read(input))
        assertEquals(LinkFraming.Message(LinkFraming.Type.KEEPALIVE, ByteArray(0)), LinkFraming.read(input))
        assertEquals(null, LinkFraming.read(input))
    }

    @Test
    fun anySplitOfTheInnerStreamReadsTheSameRecords() {
        val records =
            listOf(
                LinkFraming.encode(LinkFraming.Type.HELLO, "a hello".toByteArray()),
                LinkFraming.encode(LinkFraming.Type.PROOF, ByteArray(128) { it.toByte() }),
                LinkFraming.encode(LinkFraming.Type.FRAME, Random(3).nextBytes(70_000)),
                LinkFraming.encode(LinkFraming.Type.DIGEST, "{}".toByteArray()),
            )
        val stream = records.reduce(ByteArray::plus)
        val random = Random(4)
        repeat(20) { round ->
            val wire =
                sealedBy { out ->
                    var at = 0
                    while (at < stream.size) {
                        val n = minOf(stream.size - at, 1 + random.nextInt(if (round % 2 == 0) 40 else 90_000))
                        out.write(stream, at, n)
                        out.flush()
                        at += n
                    }
                }
            val input = responderReading(wire).input
            val read = generateSequence { LinkFraming.read(input) }.map { LinkFraming.encode(it.type, it.payload) }.toList()
            assertEquals("round $round", records.map { it.toList() }, read.map { it.toList() })
        }
    }

    @Test
    fun aCleanEofAtAFrameBoundaryIsTheEndOfTheStream() {
        val wire =
            sealedBy {
                it.write(byteArrayOf(5))
                it.flush()
            }
        val input = responderReading(wire).input
        assertEquals(5, input.read())
        assertEquals(-1, input.read())
        assertEquals(-1, input.read())
    }

    @Test
    fun aContentFaultThrowsASealExceptionAndCountsOnceWhenArmed() {
        val wire =
            sealedBy {
                it.write(byteArrayOf(1, 2, 3))
                it.flush()
            }
        val tampered = wire.copyOf().also { it[7] = (it[7].toInt() xor 1).toByte() }
        val socket = responderReading(tampered)
        val count = AtomicInteger()
        socket.armFailureCount { count.incrementAndGet() }

        assertThrows(SealException::class.java) { socket.input.read() }
        assertTrue(socket.failed)
        assertThrows(IOException::class.java) { socket.input.read() }
        socket.sealViolated()
        assertEquals("once per link", 1, count.get())
        assertThrows(IOException::class.java) { socket.output.write(1) }
    }

    @Test
    fun aFaultBeforeArmingIsTheHandshakesToCountNeverTheArmedCounts() {
        val socket = responderReading(byteArrayOf(0x0B, 0, 0, 0, 16) + ByteArray(16)) // an empty frame
        val e = assertThrows(SealException::class.java) { socket.input.read() }
        assertFalse(e.message.orEmpty().isEmpty())
        val count = AtomicInteger()
        socket.armFailureCount { count.incrementAndGet() }
        socket.sealViolated()
        assertEquals(0, count.get())
    }

    @Test
    fun anInnerViolationReportedOnALiveLinkCountsOnce() {
        val socket = responderReading(ByteArray(0))
        val count = AtomicInteger()
        socket.armFailureCount { count.incrementAndGet() }
        socket.sealViolated()
        socket.sealViolated()
        assertTrue(socket.failed)
        assertEquals(1, count.get())
    }

    @Test
    fun everyOuterRecordButAnInRangeSealedFrameIsAContentFault() {
        val cases =
            mapOf(
                "a hello" to LinkFraming.encode(LinkFraming.Type.HELLO, "x".toByteArray()),
                "a key share" to LinkFraming.encodeKeyShare(ByteArray(32)),
                "an unknown tag" to byteArrayOf(12, 0, 0, 0, 0),
                "an empty frame" to byteArrayOf(0x0B, 0, 0, 0, 16),
                "an oversize frame" to byteArrayOf(0x0B, 0, 1, 0, 0x11),
                "a frame at the wrong counter" to LinkSeal.seal(keys.first.send, 1, byteArrayOf(1)),
                "a frame in the wrong direction" to LinkSeal.seal(keys.second.send, 0, byteArrayOf(1)),
            )
        for ((name, bytes) in cases) {
            val socket = responderReading(bytes)
            val count = AtomicInteger()
            socket.armFailureCount { count.incrementAndGet() }
            assertThrows(name, SealException::class.java) { socket.input.read() }
            assertEquals(name, 1, count.get())
        }
    }

    @Test
    fun aCleanEofAtAFrameBoundaryMidInnerRecordEndsTheStreamBelowTheSeal() {
        // A whole frame holding part of an inner record, then the peer closes: the inner reader meets an EOF, and the
        // socket must say it ended below the seal, so the handshake reads it as the peer gone, never a failed seal.
        val cut = LinkFraming.encode(LinkFraming.Type.HELLO, "a hello".toByteArray()).copyOf(7)
        val socket =
            responderReading(
                sealedBy {
                    it.write(cut)
                    it.flush()
                },
            )
        val count = AtomicInteger()
        socket.armFailureCount { count.incrementAndGet() }

        assertThrows(EOFException::class.java) { LinkFraming.read(socket.input) }

        assertTrue(socket.endedBelow)
        assertFalse(socket.failed)
        assertEquals(0, count.get())
    }

    @Test
    fun aTruncatedFrameIsARawFaultNeverASealFailure() {
        val wire =
            sealedBy {
                it.write(byteArrayOf(1, 2, 3))
                it.flush()
            }
        for (cut in listOf(1, 3, 5, wire.size - 1)) {
            val socket = responderReading(wire.copyOf(cut))
            val count = AtomicInteger()
            socket.armFailureCount { count.incrementAndGet() }
            val e = assertThrows("cut at $cut", IOException::class.java) { socket.input.read() }
            assertTrue("cut at $cut", e is EOFException)
            assertFalse(socket.failed)
            assertEquals(0, count.get())
            assertTrue(socket.endedBelow)
        }
    }

    @Test
    fun aRawReadErrorPassesThroughUnchanged() {
        val timeout = SocketTimeoutException("read timed out")
        val failing =
            object : InputStream() {
                override fun read(): Int = throw timeout

                override fun read(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ): Int = throw timeout
            }
        val socket = SealedLinkSocket(RawLink(failing, ByteArrayOutputStream()), keys.second, LinkProof.Role.RESPONDER)
        val count = AtomicInteger()
        socket.armFailureCount { count.incrementAndGet() }

        val e = assertThrows(IOException::class.java) { socket.input.read() }
        assertSame(timeout, e)
        assertFalse(socket.failed)
        assertEquals(0, count.get())
    }

    @Test
    fun aRawWriteFailureKillsTheSocketAndNothingIsResealed() {
        var writes = 0
        val failing =
            object : OutputStream() {
                override fun write(b: Int) = throw IOException("broken pipe")

                override fun write(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ) {
                    writes++
                    throw IOException("broken pipe")
                }
            }
        val socket = dialer(RawLink(ByteArrayInputStream(ByteArray(0)), failing))
        socket.output.write(byteArrayOf(1))
        assertThrows(IOException::class.java) { socket.output.flush() }

        assertThrows(IOException::class.java) { socket.output.write(byteArrayOf(2)) }
        assertThrows(IOException::class.java) { socket.output.flush() }
        assertEquals("the one frame under counter 0 was the only attempt", 1, writes)
    }

    @Test
    fun aReadAfterAWriteKilledTheSocketIsARawEndNeverASealFailure() {
        // Contract revision 1: no byte arrived that was wrong, so the handshake's reader must not count a failed seal.
        val failing =
            object : OutputStream() {
                override fun write(b: Int) = throw IOException("broken pipe")

                override fun write(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ) = throw IOException("broken pipe")
            }
        val wire =
            sealedBy {
                it.write(byteArrayOf(1))
                it.flush()
            }
        val socket = SealedLinkSocket(RawLink(ByteArrayInputStream(wire), failing), keys.second, LinkProof.Role.RESPONDER)
        val count = AtomicInteger()
        socket.armFailureCount { count.incrementAndGet() }

        socket.output.write(byteArrayOf(2))
        assertThrows(IOException::class.java) { socket.output.flush() }
        val e = assertThrows(IOException::class.java) { socket.input.read() }

        assertFalse(e is SealException)
        assertFalse(socket.failed)
        assertTrue(socket.endedBelow)
        assertEquals(0, count.get())
    }

    @Test
    fun theLastCounterSealsAndTheNextClosesTheLinkInstead() {
        val out = ByteArrayOutputStream()
        val raw = RawLink(ByteArrayInputStream(ByteArray(0)), out)
        val socket = dialer(raw, firstCounter = LinkSeal.MAX_COUNTER - 1)

        socket.output.write(byteArrayOf(1))
        socket.output.flush()
        val sealed = frames(out.toByteArray()).single()
        val record = LinkFraming.encode(LinkFraming.Type.SEALED, sealed.payload)
        assertArrayEquals(byteArrayOf(1), LinkSeal.open(keys.second.recv, LinkSeal.MAX_COUNTER - 1, record))

        socket.output.write(byteArrayOf(2))
        assertThrows(IOException::class.java) { socket.output.flush() }
        assertEquals(1, raw.closes.get())
        assertEquals(1, frames(out.toByteArray()).size)
    }

    @Test(timeout = 30_000)
    fun closeIsIdempotentAndUnblocksAWriterStuckInTheSocketBelow() {
        // Nobody reads the pipe: a write past its buffer blocks, as a Bluetooth write can (#203), until the pipe closes.
        val sink = PipedInputStream(PIPE_BUFFER)
        val pipe = PipedOutputStream(sink)
        val raw = RawLink(ByteArrayInputStream(ByteArray(0)), pipe) { sink.close() }
        val socket = dialer(raw)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val writer =
                pool.submit<Throwable?> {
                    runCatching {
                        socket.output.write(ByteArray(100_000))
                        socket.output.flush()
                    }.exceptionOrNull()
                }
            // Parked: the pipe's whole buffer is full and nobody reads it.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (sink.available() < PIPE_BUFFER && System.nanoTime() < deadline) Thread.sleep(5)
            assertEquals("the writer fills the pipe", PIPE_BUFFER, sink.available())
            assertFalse("the writer is stuck in the pipe", writer.isDone)

            // From another thread, bounded: a close that took the seal lock would wait on the writer for ever.
            val closer =
                Thread {
                    socket.close()
                    socket.close()
                }.apply { start() }
            closer.join(TimeUnit.SECONDS.toMillis(10))
            assertFalse("close() never waits on the writer", closer.isAlive)

            assertTrue(writer.get(10, TimeUnit.SECONDS) is IOException)
            assertEquals(1, raw.closes.get())
            assertThrows(IOException::class.java) { socket.output.write(1) }
            assertThrows(IOException::class.java) { socket.input.read() }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun theInputIsOneStableStream() {
        val socket = responderReading(ByteArray(0))
        assertSame(socket.input, socket.input)
        assertSame(socket.output, socket.output)
    }

    private companion object {
        /** The test pipe's buffer: a writer past it parks until the pipe is read or closed. */
        const val PIPE_BUFFER = 1024
    }
}
