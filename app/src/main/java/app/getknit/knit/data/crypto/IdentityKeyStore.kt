package app.getknit.knit.data.crypto

import android.util.Log
import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.crypto.PublicKeyBundle
import app.getknit.knit.mesh.crypto.TinkInit
import app.getknit.knit.mesh.crypto.cryptoCbor
import app.getknit.knit.mesh.crypto.ratchet.RatchetCrypto
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.PublicKeySign
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.hybrid.HpkePrivateKey
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
 * One of this device's ratchet signed prekeys, in publishable form: the raw X25519 public key plus the
 * detached Ed25519 signature over [RatchetCrypto.spkSigningBytes]. The private half never leaves
 * [IdentityKeyStore].
 */
class SignedPrekey(
    val id: Int,
    val pub: ByteArray,
    val sig: ByteArray,
    val createdAt: Long,
)

/**
 * Generates and persists this device's long-term end-to-end identity keypairs — a Tink **hybrid**
 * keypair (HPKE/X25519 — wraps v1 per-message content keys to us; its X25519 half doubles as the v2
 * ratchet DH identity, see [dhIdentityPrivate]) and an **Ed25519** keypair (signs our outbound
 * frames) — plus the rotating **ratchet signed prekeys** (v2 session bootstrap, X3DH-style). The
 * private material is serialized, wrapped (AES-256-GCM under a hardware-backed AndroidKeyStore key)
 * via [KeystoreSecret], and stored in `filesDir/identity.key`.
 *
 * This lives **outside** the Room database on purpose: the identity (and the prekeys peers may hold
 * in-flight session initiations against) must survive anything that takes `knit.db` down — the
 * [DatabaseKey] unrecoverable-wrap wipe path — otherwise every such event would mint a new nodeId and
 * break peers' pinned keys and decryptability of stored ciphertext.
 *
 * Mirrors [DatabaseKey]'s "generate once, transparently load thereafter" lifecycle.
 */
class IdentityKeyStore(
    private val secret: KeystoreSecret,
) {
    @Volatile
    private var cached: Loaded? = null

    init {
        TinkInit.ensure()
    }

    /** Returns the device identity keys, generating and persisting them on first use. */
    fun keys(): IdentityKeys = loaded().keys

    /**
     * The raw X25519 scalar of the hybrid identity key, for the v2 ratchet's X3DH derivations. Safe to
     * reuse across HPKE and X3DH: every ratchet derivation is domain-separated under `knit/dm/v2/...`
     * labels, disjoint from RFC 9180's.
     */
    fun dhIdentityPrivate(): ByteArray =
        (
            loaded()
                .keys.hybridPrivate.primary.key as HpkePrivateKey
        ).privateKeyBytes
            .toByteArray(InsecureSecretKeyAccess.get())

    /**
     * The newest signed prekey in publishable form, minting the first one on demand. Rotation is
     * [rotatePrekeyIfDue]'s job — callers that publish (the profile path) only ever read this.
     */
    @Synchronized
    fun currentPrekey(now: Long): SignedPrekey {
        val state = loaded()
        val newest = state.stored.prekeys?.maxByOrNull { it.id }
        if (newest != null) return signedPrekey(newest)
        return signedPrekey(mint(state, now))
    }

    /**
     * Mints a fresh signed prekey when the newest is older than [SPK_ROTATE_MS] (or none exists),
     * pruning retention to the newest [SPK_KEEP]. Returns true when a rotation happened — the caller
     * must then bump `profileVersion` so the new prekey re-floods. Old privates are retained so
     * in-flight initiations against a recently superseded prekey still open (~[SPK_KEEP] weeks).
     */
    @Synchronized
    fun rotatePrekeyIfDue(now: Long): Boolean {
        val state = loaded()
        val newest = state.stored.prekeys?.maxByOrNull { it.id }
        if (newest != null && now - newest.createdAt < SPK_ROTATE_MS) return false
        mint(state, now)
        return true
    }

    /**
     * Mints a fresh signed prekey now, whatever the newest one's age — the first start after a backup
     * restore, where the phone this identity came from may have minted prekeys after the snapshot and a
     * peer may hold one of those ids against a different public key. The caller bumps `profileVersion`
     * (a restore does, unconditionally) so the fresh prekey outranks any the old phone published.
     */
    @Synchronized
    fun rotatePrekey(now: Long) {
        mint(loaded(), now)
    }

    /** The private half of prekey [id], or null when it has been pruned (initiator must re-init). */
    @Synchronized
    fun prekeyPrivFor(id: Int): ByteArray? =
        loaded()
            .stored.prekeys
            ?.firstOrNull { it.id == id }
            ?.priv

    @Synchronized
    private fun loaded(): Loaded {
        cached?.let { return it }
        val fromDisk =
            secret.load()?.let { blob ->
                runCatching { parse(blob) }.getOrElse { error ->
                    Log.w(TAG, "Identity keys unrecoverable; regenerating", error)
                    null
                }
            }
        val state = fromDisk ?: generateAndStore()
        cached = state
        return state
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun parse(blob: ByteArray): Loaded {
        val stored = cryptoCbor.decodeFromByteArray<Stored>(blob)
        val hybrid = TinkProtoKeysetFormat.parseKeyset(stored.hybridPriv, InsecureSecretKeyAccess.get(), RegistryConfiguration.get())
        val sig = TinkProtoKeysetFormat.parseKeyset(stored.sigPriv, InsecureSecretKeyAccess.get(), RegistryConfiguration.get())
        return Loaded(IdentityKeys(hybrid, sig, PublicKeyBundle.fromPrivate(hybrid, sig)), stored)
    }

    private fun generateAndStore(): Loaded {
        val hybrid = KeysetHandle.generateNew(KeyTemplates.get(HYBRID_TEMPLATE))
        val sig = KeysetHandle.generateNew(KeyTemplates.get(SIG_TEMPLATE))
        val stored =
            Stored(
                hybridPriv = TinkProtoKeysetFormat.serializeKeyset(hybrid, InsecureSecretKeyAccess.get(), RegistryConfiguration.get()),
                sigPriv = TinkProtoKeysetFormat.serializeKeyset(sig, InsecureSecretKeyAccess.get(), RegistryConfiguration.get()),
            )
        val state = Loaded(IdentityKeys(hybrid, sig, PublicKeyBundle.fromPrivate(hybrid, sig)), stored)
        persist(state)
        return state
    }

    /** Mints prekey `newest.id + 1`, persists the pruned list, and returns the new record. */
    private fun mint(
        state: Loaded,
        now: Long,
    ): StoredPrekey {
        val pair = RatchetCrypto.generateKeyPair()
        val nextId = (state.stored.prekeys?.maxOfOrNull { it.id } ?: 0) + 1
        val fresh = StoredPrekey(id = nextId, priv = pair.priv, createdAt = now)
        val kept = ((state.stored.prekeys ?: emptyList()) + fresh).sortedByDescending { it.id }.take(SPK_KEEP)
        val next = Loaded(state.keys, Stored(state.stored.hybridPriv, state.stored.sigPriv, kept))
        persist(next)
        cached = next
        return fresh
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun persist(state: Loaded) {
        secret.store(cryptoCbor.encodeToByteArray(state.stored))
    }

    private fun signedPrekey(record: StoredPrekey): SignedPrekey {
        val pub = RatchetCrypto.publicFromPrivate(record.priv)
        return SignedPrekey(
            id = record.id,
            pub = pub,
            sig = signer().sign(RatchetCrypto.spkSigningBytes(record.id, pub)),
            createdAt = record.createdAt,
        )
    }

    private fun signer(): PublicKeySign = loaded().keys.sigPrivate.getPrimitive(RegistryConfiguration.get(), PublicKeySign::class.java)

    private class Loaded(
        val keys: IdentityKeys,
        val stored: Stored,
    )

    @Serializable
    private class Stored(
        val hybridPriv: ByteArray,
        val sigPriv: ByteArray,
        // Additive (nullable): blobs written by older builds parse fine, and cryptoCbor's
        // ignoreUnknownKeys lets an older build read a newer blob (dropping the prekeys is safe — they
        // regenerate).
        val prekeys: List<StoredPrekey>? = null,
    )

    @Serializable
    private class StoredPrekey(
        val id: Int,
        val priv: ByteArray,
        val createdAt: Long,
    )

    companion object {
        private const val TAG = "IdentityKeyStore"

        /** The AndroidKeyStore alias the identity file is wrapped under, and the file's name in `filesDir`. */
        const val KEYSTORE_ALIAS = "knit_identity_key"
        const val FILE_NAME = "identity.key"

        /**
         * The node id the identity in [blob] (a [KeystoreSecret]'s plaintext, as [keys] would load it)
         * certifies — parsed without persisting anything, so a backup restore can check the identity it is
         * about to install against its manifest. Throws on a blob that is not an identity; [loaded] would
         * regenerate, which is exactly what a verification must never do.
         */
        @OptIn(ExperimentalSerializationApi::class)
        fun nodeIdOf(blob: ByteArray): String {
            TinkInit.ensure()
            val stored = cryptoCbor.decodeFromByteArray<Stored>(blob)
            val hybrid = TinkProtoKeysetFormat.parseKeyset(stored.hybridPriv, InsecureSecretKeyAccess.get(), RegistryConfiguration.get())
            val sig = TinkProtoKeysetFormat.parseKeyset(stored.sigPriv, InsecureSecretKeyAccess.get(), RegistryConfiguration.get())
            return NodeId.fromPublicKeyBundle(PublicKeyBundle.fromPrivate(hybrid, sig).encoded)
        }

        // HPKE with X25519 + HKDF-SHA256 + AES-256-GCM (Tink's own impl; works on minSdk 29). The _RAW
        // (NO_PREFIX) variants emit bare RFC 9180 wrapped keys (`enc‖ct`) and RFC 8032 signatures (64 B) —
        // no 5-byte Tink output prefix — so the wire is Tink-free and iOS-interoperable (the launch-baseline
        // wire layout; see PublicKeyBundle + docs/WIRE_COMPAT.md). Changing these re-mints every nodeId.
        private const val HYBRID_TEMPLATE = "DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM_RAW"
        private const val SIG_TEMPLATE = "ED25519_RAW"

        /** Signed-prekey rotation cadence; the PFS horizon of session *initiations* (design doc §prekeys). */
        private const val SPK_ROTATE_MS = 7 * 24 * 60 * 60_000L

        /** Prekey privates retained (current + 4 predecessors ≈ 35 days of initiation lateness). */
        private const val SPK_KEEP = 5
    }
}
