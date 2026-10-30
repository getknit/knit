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
 * primitive every Knit frame is checked with — over firmware 2.8.1's signing input ([signingInput];
 * `CryptoEngine::buildSigningBuffer`, `CryptoEngine.cpp:137` and `CryptoEngine.h:33-48` at `v2.8.1.8e6a88d`):
 * `0x01 ‖ LE32 from ‖ LE32 id ‖ LE32 to ‖ LE32 portnum ‖ LE32 request_id ‖ LE32 reply_id ‖ LE32 emoji ‖ LE32 bitfield
 * (0 when absent) ‖ flags ‖ payload`, the flags byte `0x01` for `want_response` and `0x02` for a present bitfield. The
 * whole `Data` envelope is covered, so a signature cannot be moved to another sender, packet, port, destination,
 * reply or tapback.
 *
 * **Only that layout.** 2.8.0 signed `LE32 from ‖ LE32 id ‖ LE32 portnum ‖ payload`; Meshtastic withdrew the 2.8.0
 * release, and Knit dropped it (ADR 2026-09.ggq4, amendment 2026-10-29, the user's decision of 2026-10-30). A contact
 * still on a 2.8.0 board fails here and reads as a mismatch.
 *
 * What a pass proves is exactly "the holder of this key transmitted these bytes as this packet" — the
 * radio, never the person holding it. Pure JVM; never throws.
 */
object XeddsaVerify {
    /** A Curve25519 public key is 32 bytes. */
    const val KEY_BYTES = 32

    /** An XEdDSA signature is 64 bytes, like any Ed25519 one. */
    const val SIGNATURE_BYTES = 64

    /** 2.8.1's header: the version byte, eight little-endian `uint32`s and the flags byte (`XEDDSA_SIGNED_HEADER_LEN`). */
    private const val SIGNED_HEADER_BYTES = 34

    /** `XEDDSA_SIGNING_VERSION`, the first byte of a 2.8.1 buffer. */
    private const val SIGNING_VERSION: Byte = 0x01

    /** `XEDDSA_SIGNED_FLAG_WANT_RESPONSE` and `XEDDSA_SIGNED_FLAG_HAS_BITFIELD`, the flags byte's two bits. */
    private const val FLAG_WANT_RESPONSE = 0x01
    private const val FLAG_HAS_BITFIELD = 0x02

    /** `BITFIELD_WANT_RESPONSE_MASK` (`Router.h:316`): bit 1 of `Data.bitfield`. */
    private const val BITFIELD_WANT_RESPONSE = 0x02u

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

    /**
     * The `Data` fields 2.8.1 signs beyond the port and the payload, as the phone decoded them
     * (`mesh.proto` at the tag: `want_response` 3, `request_id` 6, `reply_id` 7, `emoji` 8, all `fixed32` but the
     * first; `bitfield` 9, `optional uint32`). [bitfield] keeps its presence — null when tag 9 was absent, which
     * signs differently from a present zero (the flags byte carries it).
     */
    data class DataFields(
        val wantResponse: Boolean = false,
        val requestId: UInt = 0u,
        val replyId: UInt = 0u,
        val emoji: UInt = 0u,
        val bitfield: UInt? = null,
    ) {
        /** These fields on a packet addressed to [to] — the `MeshPacket` field 2.8.1 signs beside them. */
        fun signedTo(to: UInt): SignedFields = SignedFields(to, wantResponse, requestId, replyId, emoji, bitfield)
    }

    /**
     * Everything the 2.8.1 layout covers beyond `from`, `id`, the port and the payload: the packet's [to] and the
     * [DataFields]. Carried from the decoded packet to the check as one value, never rebuilt from a constant.
     */
    data class SignedFields(
        val to: UInt,
        val wantResponse: Boolean = false,
        val requestId: UInt = 0u,
        val replyId: UInt = 0u,
        val emoji: UInt = 0u,
        val bitfield: UInt? = null,
    ) {
        /**
         * Whether the `want_response` the phone sees may be the receiving board's merge rather than what was signed.
         * The board verifies **before** it ORs the bitfield's want-response bit into the field (`Router.cpp:1102-1108`
         * at the tag), so a packet signed with `false` and bit 1 set reaches the phone reading `true`. Exactly that
         * case and no other: never an absent bitfield, never bit 1 clear, never `false` read as a signed `true`.
         */
        internal val mayBeMergedWantResponse: Boolean
            get() = wantResponse && bitfield != null && (bitfield and BITFIELD_WANT_RESPONSE) != 0u
    }

    /**
     * The bytes firmware 2.8.1 signs for a packet (`CryptoEngine::buildSigningBuffer` at `v2.8.1.8e6a88d`):
     * `0x01 ‖ LE32 from ‖ LE32 id ‖ LE32 to ‖ LE32 portnum ‖ LE32 request_id ‖ LE32 reply_id ‖ LE32 emoji ‖
     * LE32 bitfield ‖ flags ‖ payload`. An absent bitfield signs as zero, and its presence rides in the flags.
     */
    fun signingInput(
        from: UInt,
        id: UInt,
        portnum: Int,
        payload: ByteArray,
        fields: SignedFields,
    ): ByteArray {
        val flags =
            (if (fields.wantResponse) FLAG_WANT_RESPONSE else 0) or
                (if (fields.bitfield != null) FLAG_HAS_BITFIELD else 0)
        return ByteBuffer
            .allocate(SIGNED_HEADER_BYTES + payload.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(SIGNING_VERSION)
            .putInt(from.toInt())
            .putInt(id.toInt())
            .putInt(fields.to.toInt())
            .putInt(portnum)
            .putInt(fields.requestId.toInt())
            .putInt(fields.replyId.toInt())
            .putInt(fields.emoji.toInt())
            .putInt((fields.bitfield ?: 0u).toInt())
            .put(flags.toByte())
            .put(payload)
            .array()
    }

    /**
     * Whether [signature] is a valid XEdDSA signature by [curvePub] for the packet ([from], [id], [portnum], [fields])
     * carrying [payload], in firmware 2.8.1's layout. Tried with the fields as decoded, then — only when
     * [SignedFields.mayBeMergedWantResponse] — with `want_response` read as signed `false`: one Ed25519 verify on the
     * common case, two at most, both built from the same decoded fields with one key and one verifier. [fields] null
     * (not carried) is false here, and the caller must read it as no verdict rather than a mismatch
     * (`InboundPipeline.judgeMeshPostSignature` skips the check). False on **any** failure — a missing or short
     * signature, a malformed key, a point Tink rejects — and never throws, so the ingest path can record the verdict
     * and go on.
     */
    fun verify(
        curvePub: ByteArray,
        from: UInt,
        id: UInt,
        portnum: Int,
        payload: ByteArray,
        signature: ByteArray?,
        fields: SignedFields?,
    ): Boolean {
        if (fields == null || signature == null || signature.size != SIGNATURE_BYTES) return false
        val edPub = edPublicKey(curvePub) ?: return false
        // One key and one verifier for both candidates.
        val verifier =
            runCatching {
                TinkInit.ensure()
                verifierFor(edPub)
            }.getOrNull() ?: return false

        fun passes(input: ByteArray): Boolean = runCatching { verifier.verify(signature, input) }.isSuccess
        return passes(signingInput(from, id, portnum, payload, fields)) ||
            (
                fields.mayBeMergedWantResponse &&
                    passes(signingInput(from, id, portnum, payload, fields.copy(wantResponse = false)))
            )
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
