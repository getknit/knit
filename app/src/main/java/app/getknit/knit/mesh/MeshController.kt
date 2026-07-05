package app.getknit.knit.mesh

import app.getknit.knit.data.AttachmentStore
import app.getknit.knit.mesh.protocol.GroupInfo
import app.getknit.knit.mesh.protocol.Mention
import app.getknit.knit.mesh.protocol.ReplyRef
import kotlinx.coroutines.flow.StateFlow

/**
 * The app-facing surface of the mesh: the reads (nearby presence, radio health, typing) plus the
 * send/lifecycle actions the UI ViewModels, the foreground [MeshService], and the notification/debug
 * entry points use. Extracted as an interface over [MeshManager] (ARCHITECTURE_REVIEW #15) so those
 * callers bind a narrow seam a test can fake, instead of the concrete orchestrator (which drags in the
 * whole DI graph — transport, repos, crypto, DTN services). [MeshManager] is the only production impl.
 *
 * The `sendChat` defaults live here (an override may not restate them), so every caller must reach the
 * mesh through this interface to get them — which is why all consumers inject `MeshController`, not the
 * concrete class.
 */
interface MeshController {
    /** Number of nearby peers for the UI status header — the smoothed reachable set. */
    val neighborCount: StateFlow<Int>

    /** Nearby peers for the contact picker (message someone nearby) — the smoothed reachable set. */
    val neighbors: StateFlow<Set<Peer>>

    /** Radio health for the Diagnostics screen (Healthy vs Degraded). */
    val transportHealth: StateFlow<TransportHealth>

    /** Per-radio status for the Diagnostics screen (Bluetooth vs Wi-Fi Aware: health + link/nearby counts). */
    val transportStatuses: StateFlow<List<TransportStatus>>

    /** nodeId → the radios each node is reachable over, so Diagnostics can tag a node BLE / NAN. */
    val peerTransports: StateFlow<Map<String, Set<TransportKind>>>

    /** conversationId → the set of peers currently shown as "typing" there, for the chat UI. */
    val typing: StateFlow<Map<String, Set<String>>>

    /** Starts the mesh engine (called by the foreground [MeshService]). */
    fun start()

    /** Stops the mesh engine and tears down the session. */
    fun stop()

    /** Triggers an immediate rescan/reconnect (heartbeat/motion) and sweeps stale carry. */
    fun heal()

    /** Tears down and re-establishes the transport (e.g. after Bluetooth toggles back on). */
    fun restart()

    /**
     * Composes a chat message (optionally with an ingested image [attachment]), stores it locally, and
     * floods it. [recipientId] null + null [group] is the broadcast room; a node id is a 1:1 DM; a non-null
     * [group] is a group message. Returns false without sending if on-device filtering flags [text].
     */
    suspend fun sendChat(
        text: String,
        attachment: AttachmentStore.Ingested? = null,
        mentions: List<Mention> = emptyList(),
        recipientId: String? = null,
        group: GroupInfo? = null,
        replyTo: ReplyRef? = null,
    ): Boolean

    /** Floods a group metadata update (e.g. a rename) immediately, independent of any chat message. */
    suspend fun sendGroupUpdate(group: GroupInfo)

    /** Floods a signed `groupleave` frame announcing that we've left [groupId]. */
    suspend fun sendGroupLeave(groupId: String)

    /** Toggles this device's emoji reaction on [messageId] and floods the change. */
    suspend fun sendReaction(
        messageId: String,
        emoji: String,
    )

    /** Broadcasts a best-effort "now typing" cue for [conversationId] to nearby peers. */
    suspend fun sendTyping(conversationId: String)
}
