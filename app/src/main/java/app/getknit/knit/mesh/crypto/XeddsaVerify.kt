package app.getknit.knit.mesh.crypto

import com.google.crypto.tink.PublicKeyVerify
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.signature.Ed25519Parameters
import com.google.crypto.tink.signature.Ed25519PublicKey
import com.google.crypto.tink.util.Bytes
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Verifies the XEdDSA signature Meshtastic firmware 2.8 puts on a broadcast (`Data.xeddsa_signature`),
 * on the phone, against a node's Curve25519 key — the key its `User.public_key` carries and a contact's
 * profile advertises as `loraKey`.
 *
 * XEdDSA (Signal) is plain Ed25519 under a key derived from the X25519 pair: the signer converts its
 * Montgomery private scalar to an Edwards one and negates it if the public point's sign bit would be set,
 * so the Edwards public key is always the birational image of the Curve25519 key with **sign bit zero**
 * (`CryptoEngine::curve_to_ed_pub` in the firmware, RFC 7748 §4.1: `y = (u − 1) / (u + 1)`). The
 * signature itself is a standard `R ‖ s` that any Ed25519 verifier accepts — Tink's, here, the same
 * primitive every Knit frame is checked with — over the firmware's own signing input
 * (`CryptoEngine::buildSigningBuffer`): `LE32(from) ‖ LE32(id) ‖ LE32(portnum) ‖ payload`, which is what
 * makes a signature unreplayable under another sender, packet id or port.
 *
 * What a pass proves is exactly "the holder of this key transmitted these bytes as this packet" — the
 * radio, never the person holding it. Pure JVM; never throws.
 */
object XeddsaVerify {
    /** A Curve25519 public key is 32 bytes. */
    const val KEY_BYTES = 32

    /** An XEdDSA signature is 64 bytes, like any Ed25519 one. */
    const val SIGNATURE_BYTES = 64

    /** `from`, `id` and `portnum`, each a little-endian `uint32`, ahead of the payload. */
    private const val HEADER_BYTES = 12

    /** The field prime, 2^255 − 19. (`BigInteger.TWO` is API 33; minSdk is 29.) */
    private val P: BigInteger = BigInteger.valueOf(2).pow(255).subtract(BigInteger.valueOf(19))

    /**
     * The Ed25519 public key a board signs under, from its Curve25519 [curvePub]: `y = (u − 1)·(u + 1)^−1
     * mod p`, encoded little-endian with the sign bit clear. Null for anything that is not a key — the
     * wrong length, or the one `u` with no inverse (u = p − 1). Bit 255 of the input is masked, as RFC 7748
     * and the firmware's `fe_frombytes` both do.
     */
    fun edPublicKey(curvePub: ByteArray): ByteArray? {
        if (curvePub.size != KEY_BYTES) return null
        val masked = curvePub.copyOf().also { it[KEY_BYTES - 1] = (it[KEY_BYTES - 1].toInt() and SIGN_MASK).toByte() }
        val u = BigInteger(1, masked.reversedArray()).mod(P)
        val denominator = u.add(BigInteger.ONE).mod(P)
        if (denominator.signum() == 0) return null
        val y = u.subtract(BigInteger.ONE).multiply(denominator.modInverse(P)).mod(P)
        return y.toLittleEndian()
    }

    /** The bytes the firmware signs for a packet: `LE32(from) ‖ LE32(id) ‖ LE32(portnum) ‖ payload`. */
    fun signingInput(
        from: UInt,
        id: UInt,
        portnum: Int,
        payload: ByteArray,
    ): ByteArray =
        ByteBuffer
            .allocate(HEADER_BYTES + payload.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(from.toInt())
            .putInt(id.toInt())
            .putInt(portnum)
            .put(payload)
            .array()

    /**
     * Whether [signature] is a valid XEdDSA signature under [curvePub] for the packet ([from], [id],
     * [portnum]) carrying [payload]. False on **any** failure — a missing or short signature, a malformed
     * key, a point Tink rejects — and never throws, so the ingest path can record the verdict and go on.
     */
    fun verify(
        curvePub: ByteArray,
        from: UInt,
        id: UInt,
        portnum: Int,
        payload: ByteArray,
        signature: ByteArray?,
    ): Boolean {
        if (signature == null || signature.size != SIGNATURE_BYTES) return false
        val edPub = edPublicKey(curvePub) ?: return false
        return runCatching {
            TinkInit.ensure()
            verifierFor(edPub).verify(signature, signingInput(from, id, portnum, payload))
            true
        }.getOrDefault(false)
    }

    private fun verifierFor(edPub: ByteArray): PublicKeyVerify =
        PublicKeyBundle
            .keysetOf(Ed25519PublicKey.create(Ed25519Parameters.Variant.NO_PREFIX, Bytes.copyFrom(edPub), null))
            .getPrimitive(RegistryConfiguration.get(), PublicKeyVerify::class.java)

    /** [KEY_BYTES] little-endian bytes of a non-negative field element (below 2^255, so bit 255 is clear). */
    private fun BigInteger.toLittleEndian(): ByteArray {
        val be = toByteArray() // big-endian two's complement; may carry a leading zero or be short
        val out = ByteArray(KEY_BYTES)
        var i = 0
        var j = be.size - 1
        while (i < KEY_BYTES && j >= 0) {
            out[i++] = be[j--]
        }
        return out
    }

    private const val SIGN_MASK = 0x7F
}
