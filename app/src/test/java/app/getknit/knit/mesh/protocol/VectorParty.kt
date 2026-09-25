package app.getknit.knit.mesh.protocol

import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.crypto.MessageCrypto
import app.getknit.knit.mesh.crypto.PublicKeyBundle
import app.getknit.knit.mesh.crypto.TinkInit
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.hybrid.HpkePrivateKey
import com.google.crypto.tink.hybrid.HpkePublicKey
import com.google.crypto.tink.signature.Ed25519Parameters
import com.google.crypto.tink.signature.Ed25519PrivateKey
import com.google.crypto.tink.signature.Ed25519PublicKey
import com.google.crypto.tink.subtle.Ed25519Sign
import com.google.crypto.tink.subtle.X25519
import com.google.crypto.tink.util.Bytes
import com.google.crypto.tink.util.SecretBytes
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * A fixed identity for the keyed vectors: the two raw private keys a vector file names, and the Tink primitives
 * built from them. Tink's Ed25519 is deterministic (RFC 8032), so a frame this party signs has the same bytes on
 * every run.
 */
internal class VectorParty(
    val signingSeed: ByteArray,
    val agreementScalar: ByteArray,
) {
    init {
        TinkInit.ensure()
    }

    private val signingPair = Ed25519Sign.KeyPair.newKeyPairFromSeed(signingSeed)

    val bundle: PublicKeyBundle =
        checkNotNull(PublicKeyBundle.fromRaw(signingPair.publicKey, X25519.publicFromPrivate(agreementScalar)))

    val nodeId: String = NodeId.fromPublicKeyBundle(bundle.encoded)

    /** Seals and opens DMs as this party, exactly as `MeshManager` does. */
    val crypto: MessageCrypto by lazy { MessageCrypto(hybridKeyset(), signingKeyset()) }

    fun sign(bytes: ByteArray): ByteArray = Ed25519Sign(signingPair.privateKey).sign(bytes)

    /** A frame as it goes on the air: [relay] encoded, signed by this party, and wrapped with the default ttl. */
    fun signedWire(relay: RelayEnvelope): ByteArray {
        val signed = WireCodec.encodeEnvelope(relay)
        return WireCodec.encodeWire(WireEnvelope(sig = sign(signed), signed = signed))
    }

    fun toJson(): JsonObject =
        buildJsonObject {
            put("signingSeed", signingSeed.toHex())
            put("agreementScalar", agreementScalar.toHex())
            put("bundle", bundle.encoded)
            put("nodeId", nodeId)
        }

    private fun hybridKeyset(): KeysetHandle {
        val publicKey = HpkePublicKey.create(PublicKeyBundle.HPKE_PARAMS, Bytes.copyFrom(bundle.dhPublicKey()), null)
        val secret = SecretBytes.copyFrom(agreementScalar, InsecureSecretKeyAccess.get())
        return PublicKeyBundle.keysetOf(HpkePrivateKey.create(publicKey, secret))
    }

    private fun signingKeyset(): KeysetHandle {
        val publicKey = Ed25519PublicKey.create(Ed25519Parameters.Variant.NO_PREFIX, Bytes.copyFrom(signingPair.publicKey), null)
        val secret = SecretBytes.copyFrom(signingSeed, InsecureSecretKeyAccess.get())
        return PublicKeyBundle.keysetOf(Ed25519PrivateKey.create(publicKey, secret))
    }

    companion object {
        /** A party from a vector file's identity entry: the raw keys, never the derived fields. */
        fun fromJson(entry: JsonObject): VectorParty =
            VectorParty(
                signingSeed =
                    entry
                        .getValue("signingSeed")
                        .jsonPrimitive.content
                        .fromHex(),
                agreementScalar =
                    entry
                        .getValue("agreementScalar")
                        .jsonPrimitive.content
                        .fromHex(),
            )

        fun identity(
            file: JsonObject,
            name: String,
        ): VectorParty =
            fromJson(
                file
                    .getValue("identities")
                    .jsonObject
                    .getValue(name)
                    .jsonObject,
            )
    }
}

/** knit-next's fixture bytes, `bytes(n, seed)[i] = (7 * i + seed) & 0xFF`, shared by every vector test. */
internal fun fixtureBytes(
    n: Int,
    seed: Int,
): ByteArray = ByteArray(n) { ((it * 7 + seed) and 0xFF).toByte() }

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

internal fun String.fromHex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
