package app.getknit.knit.identity

import app.getknit.knit.data.crypto.IdentityKeyStore
import app.getknit.knit.data.settings.SettingsStore

/**
 * The device's stable mesh identity: the persisted 8-char [nodeId] (used as the Nearby endpoint name
 * and the author of messages/profiles) plus the long-term end-to-end keypair. The nodeId is derived
 * deterministically from the device id (see [NodeId] / [DeviceIdSource]), so clearing app data
 * regenerates the same id; the keypair (see [IdentityKeyStore]) is generated once and survives
 * independently of the (destructively-migrated) database.
 */
class Identity(
    private val settings: SettingsStore,
    private val keyStore: IdentityKeyStore,
) {

    @Volatile
    private var cached: String? = null

    /** Resolves (and lazily creates+persists) this device's node id. */
    suspend fun nodeId(): String =
        cached ?: settings.getOrCreateNodeId().also { cached = it }

    /** The base64 public-key bundle this device advertises in its profile (`ProfileFrame.pubKey`). */
    fun publicKeyBundle(): String = keyStore.keys().publicBundle.encoded
}
