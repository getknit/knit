package app.getknit.knit.data.settings

import kotlinx.coroutines.flow.Flow

/**
 * The read/block slice of [SettingsStore] the inbound frame pipeline needs — the two block lists, the
 * content-filtering toggle, and the self profile bits notifications render (own avatar hash + display
 * name). Extracted as a seam so [app.getknit.knit.mesh.InboundPipeline] can be faked in a plain JVM
 * test instead of standing up a Preferences DataStore. [SettingsStore] implements it verbatim.
 *
 * The outbound/profile members (`status`, `avatarUpdatedAt`, `profileVersion`, the `set*` writers) stay
 * off this interface: they're used only by `MeshManager`'s origination/profile-broadcast path.
 */
interface InboundSettings {
    val blockedNodeIds: Flow<Set<String>>
    val blockedDeviceTags: Flow<Set<String>>
    val contentFilteringEnabled: Flow<Boolean>
    val ownAvatarHash: Flow<String?>
    val displayName: Flow<String>

    /**
     * The declared size above which a received attachment waits for a tap before its fetch may ride Bluetooth,
     * null for never (ADR 2026-10.js2p, [SettingsStore.bluetoothDownloadLimit]).
     */
    val bluetoothDownloadLimitBytes: Flow<Long?>

    /** Conversation ids explicitly accepted out of the message-request queue (see [SettingsStore.acceptedConversations]). */
    val acceptedConversations: Flow<Set<String>>

    /** Whether a typing cue this phone receives is shown at all ([SettingsStore.typingIndicatorsEnabled]). */
    val typingIndicatorsEnabled: Flow<Boolean>

    /** Blocks [nodeId]; also records the peer's [deviceTag] (when known) so the block survives a key reset. */
    suspend fun block(
        nodeId: String,
        deviceTag: String? = null,
    )
}
