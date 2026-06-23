package app.getknit.knit.identity

import app.getknit.knit.data.settings.SettingsStore

/**
 * The device's stable mesh identity.
 *
 * For now this is just the persisted 8-char [nodeId] (used as the Nearby endpoint name and the
 * author of messages/profiles). A long-term signing/encryption keypair will be added here when
 * app-level end-to-end encryption lands; the wire format already reserves a `pubKey` field for it.
 */
class Identity(private val settings: SettingsStore) {

    @Volatile
    private var cached: String? = null

    /** Resolves (and lazily creates+persists) this device's node id. */
    suspend fun nodeId(): String =
        cached ?: settings.getOrCreateNodeId().also { cached = it }
}
