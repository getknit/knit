package app.getknit.knit.data.crypto

import android.util.Log
import app.getknit.knit.mesh.crypto.PublicKeyBundle
import app.getknit.knit.mesh.crypto.TinkInit
import app.getknit.knit.mesh.crypto.cryptoCbor
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.TinkProtoKeysetFormat
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray

/** This device's long-term E2E private keysets plus the public bundle it advertises. */
data class IdentityKeys(
    val hybridPrivate: KeysetHandle,
    val sigPrivate: KeysetHandle,
    val publicBundle: PublicKeyBundle,
)

/**
 * Generates and persists this device's long-term end-to-end identity keypairs: a Tink **hybrid**
 * keypair (HPKE/X25519 — wraps per-message content keys to us) and an **Ed25519** keypair (signs our
 * outbound messages). The private keysets are serialized, wrapped (AES-256-GCM under a hardware-backed
 * AndroidKeyStore key) via [KeystoreSecret], and stored in `filesDir/identity.key`.
 *
 * Crucially this lives **outside** the Room database, which uses destructive migration
 * ([app.getknit.knit.data.KnitDatabase]) — the identity must survive schema bumps, otherwise every
 * migration would mint a new key and break peers' pinned keys and decryptability of stored ciphertext.
 *
 * Mirrors [DatabaseKey]'s "generate once, transparently load thereafter" lifecycle.
 */
class IdentityKeyStore(
    private val secret: KeystoreSecret,
) {
    @Volatile
    private var cached: IdentityKeys? = null

    init {
        TinkInit.ensure()
    }

    /** Returns the device identity keys, generating and persisting them on first use. */
    @Synchronized
    fun keys(): IdentityKeys {
        cached?.let { return it }
        val loaded =
            secret.load()?.let {
                runCatching { parse(it) }.getOrElse { error ->
                    Log.w(TAG, "Identity keys unrecoverable; regenerating", error)
                    null
                }
            }
        val keys = loaded ?: generateAndStore()
        cached = keys
        return keys
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun parse(blob: ByteArray): IdentityKeys {
        val stored = cryptoCbor.decodeFromByteArray<Stored>(blob)
        val hybrid = TinkProtoKeysetFormat.parseKeyset(stored.hybridPriv, InsecureSecretKeyAccess.get())
        val sig = TinkProtoKeysetFormat.parseKeyset(stored.sigPriv, InsecureSecretKeyAccess.get())
        return IdentityKeys(hybrid, sig, PublicKeyBundle.fromPrivate(hybrid, sig))
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun generateAndStore(): IdentityKeys {
        val hybrid = KeysetHandle.generateNew(KeyTemplates.get(HYBRID_TEMPLATE))
        val sig = KeysetHandle.generateNew(KeyTemplates.get(SIG_TEMPLATE))
        val stored =
            Stored(
                hybridPriv = TinkProtoKeysetFormat.serializeKeyset(hybrid, InsecureSecretKeyAccess.get()),
                sigPriv = TinkProtoKeysetFormat.serializeKeyset(sig, InsecureSecretKeyAccess.get()),
            )
        secret.store(cryptoCbor.encodeToByteArray(stored))
        return IdentityKeys(hybrid, sig, PublicKeyBundle.fromPrivate(hybrid, sig))
    }

    @Serializable
    private class Stored(
        val hybridPriv: ByteArray,
        val sigPriv: ByteArray,
    )

    private companion object {
        const val TAG = "IdentityKeyStore"

        // HPKE with X25519 + HKDF-SHA256 + AES-256-GCM (Tink's own impl; works on minSdk 29). The _RAW
        // (NO_PREFIX) variants emit bare RFC 9180 wrapped keys (`enc‖ct`) and RFC 8032 signatures (64 B) —
        // no 5-byte Tink output prefix — so the wire is Tink-free and iOS-interoperable (v22 wire break;
        // see PublicKeyBundle + docs/WIRE_COMPAT.md). Changing these re-mints every nodeId.
        const val HYBRID_TEMPLATE = "DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM_RAW"
        const val SIG_TEMPLATE = "ED25519_RAW"
    }
}
