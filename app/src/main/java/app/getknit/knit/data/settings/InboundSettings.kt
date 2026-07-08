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

    /** Conversation ids explicitly accepted out of the message-request queue (see [SettingsStore.acceptedConversations]). */
    val acceptedConversations: Flow<Set<String>>

    /** Blocks [nodeId]; also records the peer's [deviceTag] (when known) so the block survives a key reset. */
    suspend fun block(
        nodeId: String,
        deviceTag: String? = null,
    )
}
