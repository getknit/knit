package app.getknit.knit.mesh.link

import app.getknit.knit.mesh.crypto.LinkProof
import app.getknit.knit.mesh.crypto.LinkSeal
import app.getknit.knit.mesh.crypto.SealException
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * A [LinkSocket] whose [input] and [output] are a sealed link's inner record stream (#286): every byte written is sealed
 * into `SEALED` frames ([LinkFraming.Type.SEALED], [LinkSeal]) on [raw], and every byte read was opened from one. Made
 * only by [LinkHandshake.openSeal] (the dialer) and [LinkHandshake.receiveOpening] (the responder), once both
 * `KEY_SHARE`s crossed; [binding] is what the sealed PROOF signs, so the sealed handshake functions take this socket and
 * can't run without it. Pure: no Android, so it runs over pipes on the JVM.
 *
 * **Output.** Plaintext buffers to [LinkSeal.MAX_PLAINTEXT_BYTES]; a frame is sealed on [OutputStream.flush] or when the
 * buffer fills, never on a timer, so a 512 KiB record that passes `FramedLink`'s 8 KiB `BufferedOutputStream` as one write
 * becomes eight full frames and a tail. A flush with nothing buffered seals nothing (an empty frame is a failed seal on
 * the peer) but still flushes [raw]. The counter is taken and advanced under the lock before the seal, and the frame is
 * written inside the same lock, so frames reach the socket in counter order and no counter is sealed twice; a raw write
 * that throws kills the socket, so nothing is ever resealed under a counter whose frame may be half on the wire. A side
 * closes rather than seal [LinkSeal.MAX_COUNTER]. Frames go to `raw.output` as they are, so a Bluetooth link's
 * `SegmentingOutputStream` keeps SDUs ≤ 8 KiB and its `SocketStamps` count ciphertext: liveness and silence below this
 * socket are unchanged. Anything that replaces or reorders records (`FramedLink.pendingDigest`) acts on plaintext above
 * it, before the seal (contract §5).
 *
 * **Input.** One cached stream that the handshake and then `FramedLink` read in turn ([LinkSocket]'s contract), over
 * `raw.input` (the transport's one buffered stream). It reads one outer record at a time, its 5-byte header itself, and
 * opens it with the next receive counter; frame boundaries carry no meaning, so any split of the inner stream reads the
 * same. Two kinds of failure, kept apart because only one is a failed seal:
 *  - **A content fault** — a record that is not `SEALED`, a length out of range, a frame that won't open: bytes that
 *    arrived and were wrong. It throws [SealException], marks the socket [failed], and calls the armed `onFailed` once.
 *  - **A raw fault** — an EOF mid-frame, a `SocketTimeoutException`, our own [close]: passed through unchanged, never a
 *    [SealException], never `onFailed`. A clean EOF at a frame boundary is -1.
 *
 * Either kills the socket: every later read or write throws. [close] never takes the seal lock and never flushes: a
 * Bluetooth write can block indefinitely and only closing the socket unblocks it (#203).
 */
class SealedLinkSocket internal constructor(
    /**
     * The socket below, which carries the ciphertext. Not public: a caller registers this socket, never the one below
     * (which would write the link in the clear), and a transport that needs the raw socket's stamps keeps its own
     * reference to it.
     */
    internal val raw: LinkSocket,
    keys: LinkSeal.Keys,
    /** Which end this is: the dialer seals with `kD2R`, the responder with `kR2D`. */
    val role: LinkProof.Role,
    firstSendCounter: Long = 0,
) : LinkSocket {
    /** Both key shares as they crossed the link, the dialer's first: what the sealed PROOF signs. */
    val binding: LinkSeal.Binding = keys.binding

    // Built on first use, as the buffers below are: a responder that refuses a sealed dial before admission never seals.
    private val sendKey = keys.send
    private val recvKey = keys.recv
    private val sealer by lazy(LazyThreadSafetyMode.NONE) { LinkSeal.Sealer(sendKey) }
    private val opener by lazy(LazyThreadSafetyMode.NONE) { LinkSeal.Opener(recvKey) }

    private val dead = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val faulted = AtomicBoolean(false)
    private val onFailed = AtomicReference<(() -> Unit)?>(null)

    @Volatile
    private var failedFlag = false

    @Volatile
    private var rawEnded = false

    /** A content fault was met (on read, or reported by [sealViolated]): bytes arrived and were wrong. */
    val failed: Boolean get() = failedFlag

    /**
     * The inner stream stopped for a reason below the seal: a clean EOF, a raw read or write error, a timeout or our
     * [close] — anything that killed the socket but a content fault. An inner framing error the handshake meets while
     * this is false came from bytes that opened, so it is a failed seal; a read refused because a write already killed
     * the socket is not (no byte arrived that was wrong, contract revision 1).
     */
    internal val endedBelow: Boolean get() = rawEnded || closed.get() || (dead.get() && !failedFlag)

    /**
     * Arms the live link's failure count: [count] runs at most once, on the first content fault after this call. Armed at
     * registration only; before it, the handshake's outcome is the only count (the transport counts from that).
     */
    fun armFailureCount(count: () -> Unit) {
        onFailed.set(count)
    }

    /**
     * The inner reader met a record that is never valid inside a seal (`KEY_SHARE`, `SEALED`) on a live link: a failed
     * seal. Marks the socket [failed] and fires the armed count; the caller closes the link.
     */
    fun sealViolated() {
        fault()
    }

    override val input: InputStream = SealedInput()
    override val output: OutputStream = SealedOutput(firstSendCounter)

    /** Idempotent; never takes the seal lock, never flushes or seals. Closing [raw] unblocks a writer stuck in it. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        dead.set(true)
        raw.close()
    }

    private fun fault() {
        failedFlag = true
        dead.set(true)
        if (faulted.compareAndSet(false, true)) onFailed.get()?.invoke()
    }

    private fun contentFault(why: String): Nothing {
        fault()
        throw SealException(why)
    }

    private inner class SealedOutput(
        firstCounter: Long,
    ) : OutputStream() {
        private val lock = Any()

        // Under [lock] only; allocated by the first write, so a socket that never sends holds none of it.
        private val plain by lazy(LazyThreadSafetyMode.NONE) { ByteArray(LinkSeal.MAX_PLAINTEXT_BYTES) }
        private val frame by lazy(LazyThreadSafetyMode.NONE) { ByteArray(LinkSeal.MAX_RECORD_BYTES) }
        private var buffered = 0
        private var counter = firstCounter
        private val one = ByteArray(1)

        override fun write(b: Int) {
            one[0] = b.toByte()
            write(one, 0, 1)
        }

        override fun write(
            b: ByteArray,
            off: Int,
            len: Int,
        ) {
            if (off < 0 || len < 0 || off + len > b.size) throw IndexOutOfBoundsException("range $off+$len of ${b.size}")
            synchronized(lock) {
                alive()
                var at = off
                var left = len
                while (left > 0) {
                    val n = minOf(left, plain.size - buffered)
                    System.arraycopy(b, at, plain, buffered, n)
                    buffered += n
                    at += n
                    left -= n
                    if (buffered == plain.size) sealBuffered()
                }
            }
        }

        override fun flush() {
            synchronized(lock) {
                alive()
                if (buffered > 0) sealBuffered()
                rawWrite { raw.output.flush() }
            }
        }

        override fun close() {
            this@SealedLinkSocket.close()
        }

        // Under [lock]. The counter is spent before the seal, so a failed write never leaves it to be sealed again.
        private fun sealBuffered() {
            if (counter >= LinkSeal.MAX_COUNTER) {
                this@SealedLinkSocket.close()
                throw IOException("sealed link counter spent")
            }
            val at = counter++
            val n =
                try {
                    sealer.seal(at, plain, 0, buffered, frame)
                } catch (e: SealException) {
                    dead.set(true)
                    throw e
                }
            buffered = 0
            rawWrite { raw.output.write(frame, 0, n) }
        }

        private inline fun rawWrite(block: () -> Unit) {
            try {
                block()
            } catch (e: IOException) {
                dead.set(true)
                throw e
            }
        }
    }

    private fun alive() {
        if (dead.get()) throw IOException("sealed link closed")
    }

    private inner class SealedInput : InputStream() {
        // The one reader's; allocated by the first frame, so a socket that never reads holds none of it.
        private val record by lazy(LazyThreadSafetyMode.NONE) { ByteArray(LinkSeal.MAX_RECORD_BYTES) }
        private val plain by lazy(LazyThreadSafetyMode.NONE) { ByteArray(LinkSeal.MAX_PLAINTEXT_BYTES) }
        private var pos = 0
        private var lim = 0
        private var counter = 0L
        private var eof = false
        private val one = ByteArray(1)

        override fun read(): Int {
            val n = read(one, 0, 1)
            return if (n == -1) -1 else one[0].toInt() and BYTE_MASK
        }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            if (off < 0 || len < 0 || off + len > b.size) throw IndexOutOfBoundsException("range $off+$len of ${b.size}")
            if (len == 0) return 0
            if (pos == lim && !fill()) return -1
            val n = minOf(len, lim - pos)
            System.arraycopy(plain, pos, b, off, n)
            pos += n
            return n
        }

        override fun available(): Int = lim - pos

        override fun close() {
            this@SealedLinkSocket.close()
        }

        // One outer record: false at a clean EOF on a frame boundary.
        private fun fill(): Boolean {
            if (eof) return false
            alive()
            val tag = rawRead { raw.input.read() }
            if (tag == -1) {
                eof = true
                rawEnded = true
                return false
            }
            if (tag != SEALED_TAG) contentFault("a ${recordName(tag)} record where a sealed frame belongs")
            record[0] = tag.toByte()
            rawRead { readFully(1, LinkSeal.HEADER_BYTES - 1) }
            val body = headerLength()
            if (body !in LinkFraming.SEALED_MIN_BYTES..LinkFraming.SEALED_MAX_BYTES) contentFault("sealed frame length $body")
            rawRead { readFully(LinkSeal.HEADER_BYTES, body) }
            val n =
                try {
                    opener.open(counter, record, plain)
                } catch (_: SealException) {
                    contentFault("frame $counter did not open")
                }
            counter++
            pos = 0
            lim = n
            return true
        }

        private fun headerLength(): Int {
            var n = 0
            for (i in 1 until LinkSeal.HEADER_BYTES) n = (n shl BITS_PER_BYTE) or (record[i].toInt() and BYTE_MASK)
            return n
        }

        private fun readFully(
            at: Int,
            len: Int,
        ) {
            var off = 0
            while (off < len) {
                val n = raw.input.read(record, at + off, len - off)
                if (n == -1) throw EOFException("truncated sealed frame: $off/$len")
                off += n
            }
        }

        // A raw fault passes through unchanged and kills the socket; it is never a failed seal.
        private inline fun <T> rawRead(block: () -> T): T =
            try {
                block()
            } catch (e: IOException) {
                dead.set(true)
                rawEnded = true
                throw e
            }
    }

    private companion object {
        const val SEALED_TAG = 11
        const val BYTE_MASK = 0xFF
        const val BITS_PER_BYTE = 8

        fun recordName(tag: Int): String =
            LinkFraming.Type
                .fromTag(tag.toByte())
                ?.name
                ?.lowercase() ?: "type-$tag"
    }
}
