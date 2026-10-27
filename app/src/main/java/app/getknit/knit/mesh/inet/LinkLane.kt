package app.getknit.knit.mesh.inet

import app.getknit.knit.identity.NodeId
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The sealed datagrams of one direct link (ADR 2026-10.a5yj): `CallLane`'s shape — one AES-256-GCM key per direction,
 * a counter nonce, an AAD that binds the pair — moved from a TCP stream onto UDP. A datagram is
 *
 * ```
 * version (1) · counter (8, big-endian) · ciphertext · tag (16)
 * ```
 *
 * The counter rides in the clear because UDP drops and reorders what TCP would not, so a reader cannot supply it from
 * its own count. Its nonce is the counter, zero-padded to 12 bytes; its AAD is the version byte, then [aad] (the label,
 * both node ids in order and the link's id), so a datagram cannot be moved to another version, link or pair.
 *
 * GCM is safe only while no (key, counter) pair is sealed twice, which holds by construction and must stay so:
 *  - a link attempt has **one** lane, so one send counter per direction key, shared by every candidate path the punch
 *    probes and by everything the stream above sends, monotonic for the key's life and never reset;
 *  - a new path or a re-punch (a NAT rebind, a route change) is a **new attempt**: a new link id and fresh keys
 *    (`LinkKeys`), never this lane restarted;
 *  - the keys live in memory only, for one attempt.
 *
 * [open] refuses quietly — a datagram that fails its tag, is too short or too old, or repeats one already opened is
 * noise on a UDP port and never ends the link. A counter more than [WINDOW] below the highest opened one is refused, as
 * is any counter opened before ([WINDOW]-bit sliding window), so a replay is dropped however it is timed. Pure and
 * thread-safe: one reader and any number of senders.
 */
class LinkLane(
    sendKey: ByteArray,
    recvKey: ByteArray,
    private val aad: ByteArray,
) {
    init {
        require(sendKey.size == KEY_BYTES && recvKey.size == KEY_BYTES) { "lane keys are $KEY_BYTES bytes" }
    }

    private val sendSpec = SecretKeySpec(sendKey, "AES")
    private val recvSpec = SecretKeySpec(recvKey, "AES")
    private val sendLock = Any()
    private var sent = 0L

    private val recvLock = Any()
    private var top = -1L
    private val seen = LongArray(WINDOW / Long.SIZE_BITS)

    /** Seals [plaintext] under the next counter. Throws once [MAX_COUNTER] datagrams have gone: the link must end. */
    fun seal(plaintext: ByteArray): ByteArray {
        val counter =
            synchronized(sendLock) {
                check(sent < MAX_COUNTER) { "a lane's counter is spent" }
                sent++
            }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, sendSpec, GCMParameterSpec(TAG_BITS, nonce(counter)))
        cipher.updateAAD(VERSION_AAD)
        cipher.updateAAD(aad)
        val sealed = cipher.doFinal(plaintext)
        val out = ByteArray(HEADER_BYTES + sealed.size)
        out[0] = VERSION
        putCounter(out, counter)
        sealed.copyInto(out, HEADER_BYTES)
        return out
    }

    /** The plaintext of the first [length] bytes of [datagram], or null when it is not a fresh datagram of this lane. */
    @Suppress("ReturnCount") // a ladder of refusals ahead of the one open
    fun open(
        datagram: ByteArray,
        length: Int = datagram.size,
    ): ByteArray? {
        if (length < HEADER_BYTES + TAG_BYTES || length > datagram.size || datagram[0] != VERSION) return null
        val counter = counter(datagram)
        if (counter < 0 || counter >= MAX_COUNTER || !fresh(counter)) return null
        val plaintext =
            try {
                val cipher = Cipher.getInstance(TRANSFORM)
                cipher.init(Cipher.DECRYPT_MODE, recvSpec, GCMParameterSpec(TAG_BITS, nonce(counter)))
                cipher.updateAAD(datagram, 0, 1)
                cipher.updateAAD(aad)
                cipher.doFinal(datagram, HEADER_BYTES, length - HEADER_BYTES)
            } catch (_: GeneralSecurityException) {
                return null
            }
        // Marked only once it authenticated, so a forged counter can neither burn a slot nor slide the window.
        return plaintext.takeIf { mark(counter) }
    }

    private fun fresh(counter: Long): Boolean =
        synchronized(recvLock) {
            when {
                counter > top -> true
                top - counter >= WINDOW -> false
                else -> !bit(counter)
            }
        }

    // Re-checked under the lock: two datagrams with one counter may both have passed [fresh] before either was marked.
    private fun mark(counter: Long): Boolean =
        synchronized(recvLock) {
            if (counter > top) {
                val gap = counter - top
                if (gap >= WINDOW) {
                    seen.fill(0L)
                } else {
                    for (c in top + 1 until counter) clear(c)
                }
                top = counter
            } else if (top - counter >= WINDOW || bit(counter)) {
                return@synchronized false
            }
            set(counter)
            true
        }

    private fun bit(counter: Long): Boolean {
        val i = (counter % WINDOW).toInt()
        return seen[i / Long.SIZE_BITS] and (1L shl (i % Long.SIZE_BITS)) != 0L
    }

    private fun set(counter: Long) {
        val i = (counter % WINDOW).toInt()
        seen[i / Long.SIZE_BITS] = seen[i / Long.SIZE_BITS] or (1L shl (i % Long.SIZE_BITS))
    }

    private fun clear(counter: Long) {
        val i = (counter % WINDOW).toInt()
        seen[i / Long.SIZE_BITS] = seen[i / Long.SIZE_BITS] and (1L shl (i % Long.SIZE_BITS)).inv()
    }

    companion object {
        const val KEY_BYTES = 32

        /** The bytes a datagram adds to its plaintext: the version, the counter and the tag. */
        const val OVERHEAD_BYTES = 25 // version 1 + counter 8 + tag 16

        /** How far below the highest opened counter a datagram may still arrive. */
        const val WINDOW = 1024

        /** The last counter a lane seals; far past anything a link moves before it idles out. */
        const val MAX_COUNTER = 1L shl 48

        private const val VERSION: Byte = 1
        private val VERSION_AAD = byteArrayOf(VERSION)
        private const val COUNTER_BYTES = 8
        private const val HEADER_BYTES = 1 + COUNTER_BYTES
        private const val TAG_BYTES = 16
        private const val TAG_BITS = TAG_BYTES * 8
        private const val NONCE_BYTES = 12
        private const val BITS_PER_BYTE = 8
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private val LABEL = "knit/link/v1".toByteArray(Charsets.US_ASCII)

        /**
         * The AAD every datagram of the link [linkId] between [a] and [b] carries: the label, both canonical node ids in
         * byte order (so both ends compute the same bytes) and the link's id.
         */
        fun aad(
            linkId: ByteArray,
            a: String,
            b: String,
        ): ByteArray {
            val ia = NodeId.toBytes(a)
            val ib = NodeId.toBytes(b)
            val (low, high) = if (compare(ia, ib) <= 0) ia to ib else ib to ia
            return LABEL + low + high + linkId
        }

        private fun compare(
            x: ByteArray,
            y: ByteArray,
        ): Int {
            for (i in 0 until minOf(x.size, y.size)) {
                val d = (x[i].toInt() and BYTE) - (y[i].toInt() and BYTE)
                if (d != 0) return d
            }
            return x.size - y.size
        }

        private const val BYTE = 0xFF

        private fun nonce(counter: Long): ByteArray =
            ByteArray(NONCE_BYTES).also { out ->
                for (i in 0 until COUNTER_BYTES) out[NONCE_BYTES - 1 - i] = (counter ushr (BITS_PER_BYTE * i)).toByte()
            }

        private fun putCounter(
            out: ByteArray,
            counter: Long,
        ) {
            for (i in 0 until COUNTER_BYTES) out[COUNTER_BYTES - i] = (counter ushr (BITS_PER_BYTE * i)).toByte()
        }

        private fun counter(datagram: ByteArray): Long {
            var c = 0L
            for (i in 1..COUNTER_BYTES) c = (c shl BITS_PER_BYTE) or (datagram[i].toLong() and BYTE.toLong())
            return c
        }
    }
}
