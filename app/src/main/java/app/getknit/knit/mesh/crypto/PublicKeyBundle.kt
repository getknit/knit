package app.getknit.knit.mesh.crypto

import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.PublicKeyVerify
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.TinkProtoKeysetFormat
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray

/**
 * A peer's published end-to-end public keys: a Tink **hybrid** public keyset (to wrap a content key to
 * them) and a Tink **Ed25519** public keyset (to verify their signatures). Carried on the wire as the
 * base64 [encoded] form (CBOR of the two raw Tink public-keyset blobs) — this is exactly what travels
 * in [app.getknit.knit.mesh.protocol.ProfileContent.pubKey] and is pinned in
 * [app.getknit.knit.data.peer.PeerEntity.pubKey].
 *
 * Equality is by [encoded] so two bundles parsed from the same string compare equal (used for
 * trust-on-first-use key-change detection).
 */
class PublicKeyBundle private constructor(
    val encoded: String,
    private val hybridPubBytes: ByteArray,
    private val sigPubBytes: ByteArray,
) {
    init {
        TinkInit.ensure()
    }

    /** A primitive that encrypts (wraps) bytes to this peer's hybrid public key. */
    fun hybridEncrypt(): HybridEncrypt =
        TinkProtoKeysetFormat.parseKeysetWithoutSecret(hybridPubBytes)
            .getPrimitive(RegistryConfiguration.get(), HybridEncrypt::class.java)

    /** A primitive that verifies signatures made by this peer's signing key. */
    fun verifier(): PublicKeyVerify =
        TinkProtoKeysetFormat.parseKeysetWithoutSecret(sigPubBytes)
            .getPrimitive(RegistryConfiguration.get(), PublicKeyVerify::class.java)

    override fun equals(other: Any?): Boolean = other is PublicKeyBundle && other.encoded == encoded

    override fun hashCode(): Int = encoded.hashCode()

    @Serializable
    private class Proto(val hybridPub: ByteArray, val sigPub: ByteArray)

    companion object {
        /** Derives the public bundle from this device's private keyset handles. */
        fun fromPrivate(hybridPrivate: KeysetHandle, sigPrivate: KeysetHandle): PublicKeyBundle {
            TinkInit.ensure()
            val hp = TinkProtoKeysetFormat.serializeKeysetWithoutSecret(hybridPrivate.publicKeysetHandle)
            val sp = TinkProtoKeysetFormat.serializeKeysetWithoutSecret(sigPrivate.publicKeysetHandle)
            return build(hp, sp)
        }

        /** Parses a wire/stored [encoded] bundle; null if it is malformed. */
        @OptIn(ExperimentalSerializationApi::class)
        fun decode(encoded: String): PublicKeyBundle? = runCatching {
            val proto = cryptoCbor.decodeFromByteArray<Proto>(b64d(encoded))
            PublicKeyBundle(encoded, proto.hybridPub, proto.sigPub)
        }.getOrNull()

        @OptIn(ExperimentalSerializationApi::class)
        private fun build(hybridPub: ByteArray, sigPub: ByteArray): PublicKeyBundle {
            val encoded = b64(cryptoCbor.encodeToByteArray(Proto(hybridPub, sigPub)))
            return PublicKeyBundle(encoded, hybridPub, sigPub)
        }
    }
}
