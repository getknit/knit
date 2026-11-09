package app.getknit.knit.mesh.crypto

import androidx.annotation.VisibleForTesting
import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.subtle.X25519
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The seal on a Bluetooth or Wi-Fi Aware link (knit-next #286; the iOS port mirrors it byte for byte, knit-ios #51). Pure:
 * Tink's X25519 and HKDF, the JDK's AES-GCM, no Android, so it runs unchanged on the JVM and is the normative reference
 * beside the contract.
 *
 * ```
 * KEY_SHARE  = [0x0A][u32be 32][ephemeral X25519 public key]       each side's first record, fresh per attempt
 * ss         = X25519(own ephemeral private, peer's public)        a low-order point or an all-zero ss is refused
 * salt       = eD ‖ eR                                             the dialer's key share first, whoever derives
 * kD2R       = HKDF-SHA256(ss, salt, "knit/link/v1/seal/d2r", 32)
 * kR2D       = HKDF-SHA256(ss, salt, "knit/link/v1/seal/r2d", 32)
 * SEALED     = [0x0B][u32be n+16][AES-256-GCM(k, nonce, plain(n), aad = the 5 header bytes) ‖ tag(16)]   1 ≤ n ≤ 65,536
 * nonce      = 00 00 00 00 ‖ u64be(counter)                        counter = the frames this side has sealed, from 0
 * ```
 *
 * Why this shape:
 *  - **The counter is implicit.** A reader opens frame k with nonce k, so a frame replayed, reordered, dropped or
 *    injected fails to open and the link ends; nothing rides in the clear but the length. A stream never reorders, so
 *    `LinkLane`'s window (a UDP lane's) has no job here.
 *  - **One key per direction**, so the two counters, both from 0, never meet under one key. A side closes rather than
 *    seal counter [MAX_COUNTER], as `LinkLane` caps its own.
 *  - **The header in the AAD**, so a frame's length can't be changed to cut or pad it.
 *  - **Ephemeral only.** Neither identity key takes part: the PROOF binds both key shares to the identities afterwards
 *    ([LinkProof.sealedTranscript]), so a relay that swaps its own key share in breaks a signature, and the link's keys
 *    die with the link (forward secrecy for everything a listener recorded).
 *  - **The labels** part from `LinkKeys`' `knit/link/v1/lane/…` at `seal`, and HKDF is called in the same argument order
 *    (`Hkdf.computeHkdf(mac, ikm, salt, info, size)`), as the iOS `RatchetCrypto` does.
 */
object LinkSeal {
    const val KEY_BYTES = 32
    const val TAG_BYTES = 16
    const val HEADER_BYTES = 5

    /** The most plaintext one [app.getknit.knit.mesh.link.LinkFraming.Type.SEALED] frame carries. */
    const val MAX_PLAINTEXT_BYTES = 65_536

    /** The first counter a side may not seal: it closes the link instead. */
    const val MAX_COUNTER = 1L shl 48

    /** The longest whole SEALED record: header, a full frame's plaintext, the tag. */
    const val MAX_RECORD_BYTES = HEADER_BYTES + MAX_PLAINTEXT_BYTES + TAG_BYTES

    const val LABEL_D2R = "knit/link/v1/seal/d2r"
    const val LABEL_R2D = "knit/link/v1/seal/r2d"

    private const val SEALED_TAG: Byte = 11
    private const val HKDF_MAC = "HMACSHA256"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val NONCE_BYTES = 12
    private const val COUNTER_BYTES = 8
    private const val BITS_PER_BYTE = 8
    private const val BYTE_MASK = 0xFF
    private val D2R = LABEL_D2R.toByteArray(Charsets.US_ASCII)
    private val R2D = LABEL_R2D.toByteArray(Charsets.US_ASCII)
    private val random = SecureRandom()

    /**
     * One side's ephemeral key pair for one connection attempt. Single-use: [derive] zeroes the private half, and a
     * second derive throws, so no two links can ever share one. Production keys come only from [fresh].
     */
    class Ephemeral private constructor(
        private val priv: ByteArray,
    ) {
        /** The raw public key, the KEY_SHARE payload. */
        val pub: ByteArray = X25519.publicFromPrivate(priv)

        private var spent = false

        /** The private half, once; zeroed by the caller as soon as the shared secret is computed. */
        internal fun take(): ByteArray {
            check(!spent) { "an ephemeral key derives once" }
            spent = true
            return priv
        }

        companion object {
            /** A fresh key from the platform CSPRNG: one per connection attempt, per side. */
            fun fresh(): Ephemeral = Ephemeral(ByteArray(KEY_BYTES).also(random::nextBytes))

            /**
             * A fixed scalar, for the vectors only (contract "Vectors": `40..5f`, `60..7f`, unclamped; Tink clamps as
             * CryptoKit does, so both ports reach the same public key).
             */
            @VisibleForTesting
            internal fun fixed(scalar: ByteArray): Ephemeral {
                require(scalar.size == KEY_BYTES) { "an X25519 scalar is $KEY_BYTES bytes" }
                return Ephemeral(scalar.copyOf())
            }
        }
    }

    /** Both key shares as they crossed the link, the dialer's first: what the sealed PROOF signs. */
    class Binding(
        eD: ByteArray,
        eR: ByteArray,
    ) {
        init {
            require(eD.size == KEY_BYTES && eR.size == KEY_BYTES) { "a key share is $KEY_BYTES bytes" }
        }

        private val dialer = eD.copyOf()
        private val responder = eR.copyOf()

        val eD: ByteArray get() = dialer.copyOf()
        val eR: ByteArray get() = responder.copyOf()
    }

    /** One end's keys: it seals with [send] and opens with [recv]. Memory only, for the life of the link. */
    class Keys internal constructor(
        val send: ByteArray,
        val recv: ByteArray,
        val binding: Binding,
    )

    /**
     * This side's keys, where it plays [role], from its [own] ephemeral and the peer's key share [peerPub]. Throws
     * [SealException] on a key share X25519 refuses (the wrong length, a low-order point) or a shared secret of 32 zero
     * bytes (CryptoKit's answer to such a point, refused by hand on iOS): both are a failed seal.
     */
    fun derive(
        role: LinkProof.Role,
        own: Ephemeral,
        peerPub: ByteArray,
    ): Keys {
        val ss = sharedSecret(own.take(), peerPub)
        try {
            val binding =
                when (role) {
                    LinkProof.Role.DIALER -> Binding(own.pub, peerPub)
                    LinkProof.Role.RESPONDER -> Binding(peerPub, own.pub)
                }
            val salt = binding.eD + binding.eR
            // HKDF-SHA256 for 32 bytes refuses nothing: its only GeneralSecurityException is an unknown MAC name.
            val d2r = Hkdf.computeHkdf(HKDF_MAC, ss, salt, D2R, KEY_BYTES)
            val r2d = Hkdf.computeHkdf(HKDF_MAC, ss, salt, R2D, KEY_BYTES)
            return when (role) {
                LinkProof.Role.DIALER -> Keys(send = d2r, recv = r2d, binding = binding)
                LinkProof.Role.RESPONDER -> Keys(send = r2d, recv = d2r, binding = binding)
            }
        } finally {
            ss.fill(0)
        }
    }

    /**
     * X25519 of [priv] and [peerPub], [priv] zeroed whatever happens; [SealException] on a refused point or an all-zero
     * secret. Tink's refusal is not kept as a cause: its message carries the refused key's bytes.
     */
    @Suppress("SwallowedException")
    private fun sharedSecret(
        priv: ByteArray,
        peerPub: ByteArray,
    ): ByteArray {
        val ss =
            try {
                X25519.computeSharedSecret(priv, peerPub)
            } catch (_: GeneralSecurityException) {
                null
            } finally {
                priv.fill(0)
            }
        if (ss == null || ss.all { it == 0.toByte() }) throw SealException("rejected key share")
        return ss
    }

    /** The whole SEALED record for [plain] at [counter] under [key]. For the vectors and tests; a link uses [Sealer]. */
    fun seal(
        key: ByteArray,
        counter: Long,
        plain: ByteArray,
    ): ByteArray {
        val out = ByteArray(HEADER_BYTES + plain.size + TAG_BYTES)
        val n = Sealer(key).seal(counter, plain, 0, plain.size, out)
        return out.copyOf(n)
    }

    /** The plaintext of the whole SEALED [record] at [counter] under [key]; throws [SealException] on anything else. */
    fun open(
        key: ByteArray,
        counter: Long,
        record: ByteArray,
    ): ByteArray {
        if (record.size < HEADER_BYTES) throw SealException("short frame")
        val len = checkHeader(record)
        if (record.size != HEADER_BYTES + len) throw SealException("frame length")
        val out = ByteArray(len - TAG_BYTES)
        val n = Opener(key).open(counter, record, out)
        return out.copyOf(n)
    }

    /** The body length a SEALED [header]'s first 5 bytes state, or [SealException] when the tag or length is wrong. */
    fun checkHeader(header: ByteArray): Int {
        if (header[0] != SEALED_TAG) throw SealException("not a sealed frame")
        val len = u32(header)
        if (len < 1 + TAG_BYTES || len > MAX_PLAINTEXT_BYTES + TAG_BYTES) throw SealException("sealed frame length")
        return len
    }

    /** One direction's sealer: one cipher for the link, a caller-owned counter (it takes and advances it under its lock). */
    class Sealer(
        key: ByteArray,
    ) {
        private val spec = SecretKeySpec(key.copyOf(), "AES")
        private val cipher = Cipher.getInstance(TRANSFORM)
        private val nonce = ByteArray(NONCE_BYTES)

        /** Seals [len] bytes of [plain] from [off] at [counter] into [out] as a whole record; returns its length. */
        fun seal(
            counter: Long,
            plain: ByteArray,
            off: Int,
            len: Int,
            out: ByteArray,
        ): Int {
            if (counter !in 0 until MAX_COUNTER) throw SealException("counter spent")
            require(len in 1..MAX_PLAINTEXT_BYTES) { "a sealed frame holds 1..$MAX_PLAINTEXT_BYTES bytes" }
            val body = len + TAG_BYTES
            out[0] = SEALED_TAG
            putU32(out, body)
            putNonce(nonce, counter)
            return try {
                cipher.init(Cipher.ENCRYPT_MODE, spec, GCMParameterSpec(TAG_BYTES * BITS_PER_BYTE, nonce))
                cipher.updateAAD(out, 0, HEADER_BYTES)
                HEADER_BYTES + cipher.doFinal(plain, off, len, out, HEADER_BYTES)
            } catch (e: GeneralSecurityException) {
                throw SealException("seal: ${e.javaClass.simpleName}", e)
            }
        }
    }

    /** One direction's opener: one cipher for the link. */
    class Opener(
        key: ByteArray,
    ) {
        private val spec = SecretKeySpec(key.copyOf(), "AES")
        private val cipher = Cipher.getInstance(TRANSFORM)
        private val nonce = ByteArray(NONCE_BYTES)

        /**
         * Opens the record in [record] (its header checked by [checkHeader], its body behind it) at [counter] into [out];
         * returns the plaintext length. Throws [SealException] when it does not open.
         */
        fun open(
            counter: Long,
            record: ByteArray,
            out: ByteArray,
        ): Int {
            if (counter !in 0 until MAX_COUNTER) throw SealException("counter spent")
            val body = checkHeader(record)
            putNonce(nonce, counter)
            return try {
                cipher.init(Cipher.DECRYPT_MODE, spec, GCMParameterSpec(TAG_BYTES * BITS_PER_BYTE, nonce))
                cipher.updateAAD(record, 0, HEADER_BYTES)
                cipher.doFinal(record, HEADER_BYTES, body, out, 0)
            } catch (e: GeneralSecurityException) {
                // The JDK's refusal (a tag mismatch) names no key, nonce or plaintext.
                throw SealException("frame did not open", e)
            }
        }
    }

    private fun putNonce(
        nonce: ByteArray,
        counter: Long,
    ) {
        for (i in 0 until COUNTER_BYTES) nonce[NONCE_BYTES - 1 - i] = (counter ushr (BITS_PER_BYTE * i)).toByte()
    }

    private fun putU32(
        out: ByteArray,
        n: Int,
    ) {
        for (i in 0 until Int.SIZE_BYTES) out[HEADER_BYTES - 1 - i] = (n ushr (BITS_PER_BYTE * i)).toByte()
    }

    private fun u32(header: ByteArray): Int {
        var n = 0
        for (i in 1 until HEADER_BYTES) n = (n shl BITS_PER_BYTE) or (header[i].toInt() and BYTE_MASK)
        return n
    }
}

/**
 * A seal that failed on bytes that arrived and were wrong (#286): a record other than `SEALED` after the exchange, a
 * frame length out of range, a frame that would not open, a refused key share. Never a missing reply, an EOF or a
 * timeout: those pass through as the socket's own [IOException]. The message names the fault only, never a key, a
 * nonce or a byte of plaintext.
 */
class SealException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)
