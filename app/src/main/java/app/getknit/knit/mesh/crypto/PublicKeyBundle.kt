package app.getknit.knit.mesh.crypto

import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.Key
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.PublicKeyVerify
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.hybrid.HpkeParameters
import com.google.crypto.tink.hybrid.HpkePublicKey
import com.google.crypto.tink.signature.Ed25519Parameters
import com.google.crypto.tink.signature.Ed25519PublicKey
import com.google.crypto.tink.util.Bytes
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.ByteString
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray

/**
 * A peer's published end-to-end public keys: an HPKE/X25519 **hybrid** public key (to wrap a content key
 * to them) and an **Ed25519** public key (to verify their signatures). Carried on the wire as the base64
 * [encoded] form — CBOR `{sigPub: 32B, hpkePub: 32B}` of the two **raw** RFC 8032 / RFC 9180 public keys
 * (not Tink keyset protobufs) — this is exactly what travels in
 * [app.getknit.knit.mesh.protocol.ProfileContent.pubKey] and is pinned in
 * [app.getknit.knit.data.peer.PeerEntity.pubKey].
 *
 * The wire layout is deliberately Tink-free (raw 32-byte keys, no keyset proto, no 5-byte output-prefix)
 * so a non-Tink implementation (e.g. an iOS CryptoKit client) can interoperate. Android keeps Tink
 * internally: the raw bytes are extracted from / re-imported into Tink `KeysetHandle`s with the
 * `NO_PREFIX` (RAW) variant, so signatures verify as bare 64-byte Ed25519 and wrapped keys as bare
 * `enc‖ct`. See docs/WIRE_COMPAT.md (the launch-baseline wire layout) and docs/IOS_PORT_REVIEW.md §2.2.
 *
 * Equality is by [encoded] so two bundles parsed from the same string compare equal (used for
 * trust-on-first-use key-change detection).
 */
class PublicKeyBundle private constructor(
    val encoded: String,
    private val hpkePubBytes: ByteArray,
    private val sigPubBytes: ByteArray,
) {
    init {
        TinkInit.ensure()
    }

    /** A primitive that encrypts (wraps) bytes to this peer's hybrid public key. */
    fun hybridEncrypt(): HybridEncrypt =
        keysetOf(HpkePublicKey.create(HPKE_PARAMS, Bytes.copyFrom(hpkePubBytes), null))
            .getPrimitive(RegistryConfiguration.get(), HybridEncrypt::class.java)

    /** A primitive that verifies signatures made by this peer's signing key. */
    fun verifier(): PublicKeyVerify =
        keysetOf(Ed25519PublicKey.create(Ed25519Parameters.Variant.NO_PREFIX, Bytes.copyFrom(sigPubBytes), null))
            .getPrimitive(RegistryConfiguration.get(), PublicKeyVerify::class.java)

    override fun equals(other: Any?): Boolean = other is PublicKeyBundle && other.encoded == encoded

    override fun hashCode(): Int = encoded.hashCode()

    /** The raw-key wire form: a CBOR map of two 32-byte `@ByteString`s, `sigPub` then `hpkePub`. */
    @Serializable
    private class Proto(
        @ByteString val sigPub: ByteArray,
        @ByteString val hpkePub: ByteArray,
    )

    companion object {
        /**
         * The HPKE parameters the wrapped-key layout is built against — must match Tink's
         * `DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM_RAW` template (asserted in a unit test). The
         * `NO_PREFIX` variant is what makes the wrapped key bare `enc‖ct` with no Tink output prefix.
         */
        internal val HPKE_PARAMS: HpkeParameters =
            HpkeParameters
                .builder()
                .setKemId(HpkeParameters.KemId.DHKEM_X25519_HKDF_SHA256)
                .setKdfId(HpkeParameters.KdfId.HKDF_SHA256)
                .setAeadId(HpkeParameters.AeadId.AES_256_GCM)
                .setVariant(HpkeParameters.Variant.NO_PREFIX)
                .build()

        /** A single-key keyset handle wrapping [key] (random id — irrelevant for a NO_PREFIX key). */
        private fun keysetOf(key: Key): KeysetHandle =
            KeysetHandle
                .newBuilder()
                .addEntry(KeysetHandle.importKey(key).withRandomId().makePrimary())
                .build()

        /** Derives the public bundle from this device's private keyset handles. */
        fun fromPrivate(
            hybridPrivate: KeysetHandle,
            sigPrivate: KeysetHandle,
        ): PublicKeyBundle {
            TinkInit.ensure()
            val hpkePub = (hybridPrivate.publicKeysetHandle.primary.key as HpkePublicKey).publicKeyBytes.toByteArray()
            val sigPub = (sigPrivate.publicKeysetHandle.primary.key as Ed25519PublicKey).publicKeyBytes.toByteArray()
            return build(hpkePub, sigPub)
        }

        /** Parses a wire/stored [encoded] bundle; null if it is malformed. */
        @OptIn(ExperimentalSerializationApi::class)
        fun decode(encoded: String): PublicKeyBundle? =
            runCatching {
                val proto = cryptoCbor.decodeFromByteArray<Proto>(b64d(encoded))
                PublicKeyBundle(encoded, proto.hpkePub, proto.sigPub)
            }.getOrNull()

        @OptIn(ExperimentalSerializationApi::class)
        private fun build(
            hpkePub: ByteArray,
            sigPub: ByteArray,
        ): PublicKeyBundle {
            val encoded = b64(cryptoCbor.encodeToByteArray(Proto(sigPub = sigPub, hpkePub = hpkePub)))
            return PublicKeyBundle(encoded, hpkePub, sigPub)
        }
    }
}
