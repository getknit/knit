package app.getknit.knit.identity

import app.getknit.knit.data.crypto.IdentityKeyStore

/**
 * The device's mesh identity: an 8-char [nodeId] (used as the Nearby endpoint name and the author of
 * messages/profiles) that is the hash of the long-term end-to-end [publicKeyBundle]. Because the
 * nodeId is *derived from* the keypair (see [NodeId.fromPublicKeyBundle]), identity is self-certifying
 * — no peer can claim our nodeId without our private key. The keypair (see [IdentityKeyStore]) is
 * generated once and survives independently of the (destructively-migrated) database, so the nodeId is
 * stable for the life of that keypair.
 *
 * Separately, [deviceTag] is a key-independent, reinstall-surviving device tag used *only* for soft
 * block-list continuity (a blocked peer that regenerates their key returns under a new nodeId but the
 * same tag) — see [DeviceTag]. It is never part of routing, addressing, or trust.
 */
class Identity(
    private val keyStore: IdentityKeyStore,
    private val deviceIdSource: DeviceIdSource,
) {

    @Volatile
    private var cachedNodeId: String? = null

    @Volatile
    private var cachedDeviceTag: String? = null

    /** This device's node id — the self-certifying hash of its [publicKeyBundle]. */
    suspend fun nodeId(): String =
        cachedNodeId ?: NodeId.fromPublicKeyBundle(publicKeyBundle()).also { cachedNodeId = it }

    /** The base64 public-key bundle this device advertises in its profile (`ProfileContent.pubKey`). */
    fun publicKeyBundle(): String = keyStore.keys().publicBundle.encoded

    /** This device's soft block-continuity tag (null when the platform reports no stable device id). */
    fun deviceTag(): String? =
        cachedDeviceTag ?: DeviceTag.derive(deviceIdSource.rawDeviceId()).also { cachedDeviceTag = it }
}
