package app.getknit.knit.usbcontrol

import app.getknit.knit.mesh.crypto.LinkSeal
import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.subtle.X25519
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A key share, a frame or a counter the seal refuses. Its message never carries key or plaintext bytes. */
internal class UsbSealException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * A paired computer's session with this phone (`docs/USB_CONTROL.md`, *Sessions*): the shape of `mesh/crypto/LinkSeal`,
 * with its own labels and a header that is the USB framing's own. Pure: Tink's X25519 and HKDF, the JDK's AES-GCM.
 *
 * ```
 * ss      = X25519(own e, peer e)                     fresh ephemerals for every `open`; a low-order point is refused
 * k(dir)  = HKDF-SHA256(ikm = psk ‖ ss, salt = eH ‖ eP, info = "knit/usb/v1/seal/h2p" | "knit/usb/v1/seal/p2h" ‖ handle, 32)
 * frame   = u32be(0x8000_0000 | n + 16) ‖ AES-256-GCM(k, nonce = 0⁴ ‖ u64be counter, plain n, aad = those 4 bytes) ‖ tag
 *           1 ≤ n ≤ 65,520; the counter is the frames this side has sealed, from 0, and never rides the wire
 * ```
 *
 * Why this shape:
 *  - **The psk authenticates, the ephemerals make it fresh.** Only the computer and the phone that paired hold the psk,
 *    so a frame that opens came from one of them; the ephemerals give every session its own keys, so a psk stolen later
 *    opens nothing a listener recorded.
 *  - **The top bit marks a sealed frame**, so the phone tells one from a clear `open` without trying to open it (a
 *    knit-cli run starts with a clear `open` while the last run's session may still be up), and the length is the AAD,
 *    so it can't be changed to cut or pad a frame.
 *  - **The counter is implicit.** A frame replayed, dropped, reordered or injected fails to open and the session ends;
 *    a stream never reorders. One key per direction, so the two counters, both from 0, never meet under one key.
 */
internal object UsbSeal {
    const val HEADER_BYTES = 4
    const val TAG_BYTES = 16
    const val KEY_BYTES = 32
    const val SEALED_BIT = 0x8000_0000.toInt()

    /** The most plaintext a sealed frame holds: the frame cap less the tag. */
    const val MAX_PLAINTEXT = UsbControlFrames.MAX_FRAME - TAG_BYTES

    /** The first counter a side may not seal: it ends the session instead. */
    const val MAX_COUNTER = LinkSeal.MAX_COUNTER

    const val LABEL_H2P = "knit/usb/v1/seal/h2p"
    const val LABEL_P2H = "knit/usb/v1/seal/p2h"

    private const val HKDF_MAC = "HMACSHA256"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val NONCE_BYTES = 12
    private const val COUNTER_AT = 4
    private const val BITS_PER_BYTE = 8
    private val H2P = LABEL_H2P.toByteArray(Charsets.US_ASCII)
    private val P2H = LABEL_P2H.toByteArray(Charsets.US_ASCII)

    /** Which end this is. */
    enum class Role { HOST, PHONE }

    /** One end's two keys for one session: it seals with [send] and opens with [recv]. Memory only. */
    class Keys(
        val send: ByteArray,
        val recv: ByteArray,
    ) {
        fun zero() {
            send.fill(0)
            recv.fill(0)
        }
    }

    /**
     * X25519 of [own]'s private half (spent and zeroed) and [peerPub]. [UsbSealException] on a key share X25519 refuses
     * (the wrong length, a low-order point) or a secret of 32 zero bytes. Tink's refusal is not kept: its message
     * carries the key's bytes.
     */
    @Suppress("SwallowedException")
    fun sharedSecret(
        own: LinkSeal.Ephemeral,
        peerPub: ByteArray,
    ): ByteArray {
        val priv = own.take()
        val ss =
            try {
                X25519.computeSharedSecret(priv, peerPub)
            } catch (_: GeneralSecurityException) {
                null
            } finally {
                priv.fill(0)
            }
        if (ss == null || ss.all { it == 0.toByte() }) throw UsbSealException("rejected key share")
        return ss
    }

    /** [role]'s keys for the session over [ss], from the pairing's [psk], both key shares and the pairing's [handle]. */
    fun sessionKeys(
        role: Role,
        psk: ByteArray,
        ss: ByteArray,
        eH: ByteArray,
        eP: ByteArray,
        handle: ByteArray,
    ): Keys {
        require(psk.size == KEY_BYTES && ss.size == KEY_BYTES) { "psk and ss are $KEY_BYTES bytes" }
        require(eH.size == KEY_BYTES && eP.size == KEY_BYTES) { "a key share is $KEY_BYTES bytes" }
        require(handle.size == UsbPairingCrypto.HANDLE_BYTES) { "a handle is ${UsbPairingCrypto.HANDLE_BYTES} bytes" }
        val ikm = psk + ss
        val salt = eH + eP
        try {
            val h2p = Hkdf.computeHkdf(HKDF_MAC, ikm, salt, H2P + handle, KEY_BYTES)
            val p2h = Hkdf.computeHkdf(HKDF_MAC, ikm, salt, P2H + handle, KEY_BYTES)
            return when (role) {
                Role.HOST -> Keys(send = h2p, recv = p2h)
                Role.PHONE -> Keys(send = p2h, recv = h2p)
            }
        } finally {
            ikm.fill(0)
        }
    }

    /** Whether a frame header's length word marks a sealed frame. */
    fun isSealed(lengthWord: Int): Boolean = lengthWord and SEALED_BIT != 0

    /** One direction's sealer, counting its own frames. One thread: the link's worker. */
    class Sealer(
        key: ByteArray,
    ) {
        private val spec = SecretKeySpec(key.copyOf(), "AES")
        private val cipher = Cipher.getInstance(TRANSFORM)
        private val nonce = ByteArray(NONCE_BYTES)

        /** The frames sealed so far: the next one's counter. */
        var counter = 0L
            private set

        /** [plain] as a whole sealed frame, at the next counter. */
        fun seal(plain: ByteArray): ByteArray {
            if (counter >= MAX_COUNTER) refuse("counter spent")
            if (plain.size !in 1..MAX_PLAINTEXT) refuse("a sealed frame holds 1..$MAX_PLAINTEXT bytes")
            val out = ByteArray(HEADER_BYTES + plain.size + TAG_BYTES)
            ByteBuffer.wrap(out).putInt(SEALED_BIT or (plain.size + TAG_BYTES))
            putNonce(nonce, counter)
            try {
                cipher.init(Cipher.ENCRYPT_MODE, spec, GCMParameterSpec(TAG_BYTES * BITS_PER_BYTE, nonce))
                cipher.updateAAD(out, 0, HEADER_BYTES)
                cipher.doFinal(plain, 0, plain.size, out, HEADER_BYTES)
            } catch (e: GeneralSecurityException) {
                refuse("seal: ${e.javaClass.simpleName}", e)
            }
            counter++
            return out
        }
    }

    /** One direction's opener, counting the frames it has opened. One thread: the link's worker. */
    class Opener(
        key: ByteArray,
    ) {
        private val spec = SecretKeySpec(key.copyOf(), "AES")
        private val cipher = Cipher.getInstance(TRANSFORM)
        private val nonce = ByteArray(NONCE_BYTES)

        /** The frames opened so far: the counter the next one must have been sealed at. */
        var counter = 0L
            private set

        /** The plaintext of the whole sealed [frame] (header first), at the next counter; [UsbSealException] otherwise. */
        @Suppress("SwallowedException") // a tag that fails is the whole story; the JDK's message adds nothing
        fun open(frame: ByteArray): ByteArray {
            if (counter >= MAX_COUNTER) refuse("counter spent")
            if (frame.size < HEADER_BYTES + TAG_BYTES + 1) refuse("short frame")
            val word = ByteBuffer.wrap(frame).int
            if (!isSealed(word)) refuse("not a sealed frame")
            if ((word and SEALED_BIT.inv()) != frame.size - HEADER_BYTES) refuse("frame length")
            putNonce(nonce, counter)
            val plain =
                try {
                    cipher.init(Cipher.DECRYPT_MODE, spec, GCMParameterSpec(TAG_BYTES * BITS_PER_BYTE, nonce))
                    cipher.updateAAD(frame, 0, HEADER_BYTES)
                    cipher.doFinal(frame, HEADER_BYTES, frame.size - HEADER_BYTES)
                } catch (_: GeneralSecurityException) {
                    refuse("frame does not open")
                }
            counter++
            return plain
        }
    }

    private fun refuse(
        message: String,
        cause: Throwable? = null,
    ): Nothing = throw UsbSealException(message, cause)

    private fun putNonce(
        nonce: ByteArray,
        counter: Long,
    ) {
        nonce.fill(0)
        ByteBuffer.wrap(nonce, COUNTER_AT, Long.SIZE_BYTES).putLong(counter)
    }
}
