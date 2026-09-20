package app.getknit.knit.identity

import app.getknit.knit.data.crypto.IdentityKeyStore
import app.getknit.knit.data.crypto.SignedPrekey

/**
 * The device's mesh identity: a 128-bit [nodeId] (used as the mesh advert/endpoint name and the author of
 * messages/profiles) that is the hash of the long-term end-to-end [publicKeyBundle]. Because the
 * nodeId is *derived from* the keypair (see [NodeId.fromPublicKeyBundle]), identity is self-certifying
 * — no peer can claim our nodeId without our private key. The keypair (see [IdentityKeyStore]) is
 * generated once and survives independently of the database, so the nodeId is stable for the life of
 * that keypair.
 *
 * Separately, [deviceTag] is a key-independent, reinstall-surviving device tag used *only* for soft
 * block-list continuity (a blocked peer that regenerates their key returns under a new nodeId but the
 * same tag) — see [DeviceTag]. It is never part of routing, addressing, or trust.
 */
class Identity(
    private val keyStore: IdentityKeyStore,
    private val deviceIdSource: DeviceIdSource,
) : IdentitySource {
    @Volatile
    private var cachedNodeId: String? = null

    @Volatile
    private var cachedDeviceTag: String? = null

    /** This device's node id — the self-certifying hash of its [publicKeyBundle]. */
    override suspend fun nodeId(): String = cachedNodeId ?: NodeId.fromPublicKeyBundle(publicKeyBundle()).also { cachedNodeId = it }

    /** The base64 public-key bundle this device advertises in its profile (`ProfileContent.pubKey`). */
    override fun publicKeyBundle(): String = keyStore.keys().publicBundle.encoded

    /** This device's soft block-continuity tag (null when the platform reports no stable device id). */
    fun deviceTag(): String? = cachedDeviceTag ?: DeviceTag.derive(deviceIdSource.rawDeviceId()).also { cachedDeviceTag = it }

    /**
     * The raw X25519 identity private scalar — the input of the spool plane's **pair secret**
     * (`ScopeCrypto.pairSecret`, spec §3.5), the one scope input derived from the identity rather than a
     * session. Read on demand, never cached here, so the key stays behind the store's keystore wrap.
     */
    fun dhIdentityPrivate(): ByteArray = keyStore.dhIdentityPrivate()

    /** The current ratchet signed prekey to publish in the profile (v2 DM bootstrap), minting on first use. */
    fun currentPrekey(now: Long): SignedPrekey = keyStore.currentPrekey(now)

    /** Rotates the signed prekey when due; on true the caller must bump `profileVersion` so it re-floods. */
    fun rotatePrekeyIfDue(now: Long): Boolean = keyStore.rotatePrekeyIfDue(now)

    /** Rotates the signed prekey now, due or not (the first start after a restore); the caller bumps the version. */
    fun rotatePrekey(now: Long) = keyStore.rotatePrekey(now)
}
