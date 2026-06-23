package app.getknit.knit.mesh

import app.getknit.knit.data.AvatarStore
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.mesh.protocol.ChatFrame
import app.getknit.knit.mesh.protocol.Frame
import app.getknit.knit.mesh.protocol.ProfileFrame
import app.getknit.knit.mesh.protocol.ReceiptFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Orchestrates the mesh: owns the [MeshTransport] and [MeshRouter], handles delivery of new frames
 * (persist chat, ack the direct sender, cache profiles/avatars, mark receipts), broadcasts this
 * device's profile, and exposes the send/start API used by the foreground service and UI. A process
 * singleton (provided by Koin) so the bound service and the UI share one instance.
 */
class MeshManager(
    private val transport: MeshTransport,
    private val messages: MessageRepository,
    private val peers: PeerRepository,
    private val identity: Identity,
    private val settings: SettingsStore,
    private val avatars: AvatarStore,
    private val scope: CoroutineScope,
) {
    private val router = MeshRouter(transport, scope, onDeliver = ::onDeliver)

    @Volatile
    private var started = false

    // Bumped when this device's profile changes; part of the profile frame id so a changed profile
    // re-floods while an unchanged one dedups at peers that already have it.
    @Volatile
    private var profileVersion = 0L

    /** Number of directly-connected neighbors, for the UI status header. */
    val neighborCount: StateFlow<Int> =
        transport.neighbors
            .map { it.size }
            .stateIn(scope, SharingStarted.Eagerly, 0)

    fun start() {
        if (started) return
        started = true
        profileVersion = System.currentTimeMillis()
        router.start()
        transport.start()
        watchNeighbors()
        watchProfileChanges()
        watchIncomingAvatars()
    }

    fun stop() {
        if (!started) return
        started = false
        transport.stop()
    }

    /** Triggers an immediate rescan/reconnect (heartbeat alarm, device motion). */
    fun heal() {
        if (started) transport.heal()
    }

    /** Tears down and re-establishes the transport (e.g. after Bluetooth toggles back on). */
    fun restart() {
        if (!started) return
        transport.stop()
        transport.start()
    }

    /** Composes a chat message, stores it locally (unacked), and floods it to the mesh. */
    suspend fun sendChat(text: String) {
        val me = identity.nodeId()
        val frame = ChatFrame(
            id = UUID.randomUUID().toString(),
            senderId = me,
            sentAt = System.currentTimeMillis(),
            body = text,
        )
        messages.save(
            MessageEntity(id = frame.id, senderId = me, body = text, sentAt = frame.sentAt, received = false),
        )
        router.originate(frame)
    }

    // --- Profile broadcasting ---

    private fun watchNeighbors() {
        scope.launch {
            var known = emptySet<String>()
            transport.neighbors.collect { current ->
                val newcomers = current.filter { it.nodeId !in known }
                known = current.map { it.nodeId }.toSet()
                newcomers.forEach { pushProfileTo(it) }
            }
        }
    }

    private fun watchProfileChanges() {
        scope.launch {
            combine(settings.displayName, settings.status, settings.avatarUpdatedAt) { _, _, _ -> }
                .drop(1) // skip the initial stored value; only react to real edits
                .collect {
                    profileVersion = System.currentTimeMillis()
                    broadcastProfile()
                }
        }
    }

    private fun watchIncomingAvatars() {
        scope.launch {
            transport.incomingFiles.collect { (nodeId, path) -> onAvatarReceived(nodeId, path) }
        }
    }

    private suspend fun pushProfileTo(peer: Peer) {
        router.sendOwn(currentProfileFrame(), peer)
        avatars.ownAvatarFile.takeIf { it.exists() }?.let { transport.sendFile(it, peer) }
    }

    private suspend fun broadcastProfile() {
        router.originate(currentProfileFrame())
        val avatar = avatars.ownAvatarFile.takeIf { it.exists() } ?: return
        transport.neighbors.value.forEach { transport.sendFile(avatar, it) }
    }

    private suspend fun currentProfileFrame(): ProfileFrame {
        val me = identity.nodeId()
        return ProfileFrame(
            id = "profile-$me-$profileVersion",
            senderId = me,
            sentAt = profileVersion,
            name = settings.displayName.first(),
            status = settings.status.first(),
            avatarHash = avatars.ownAvatarHash(),
        )
    }

    // --- Delivery of inbound frames ---

    private suspend fun onDeliver(frame: Frame, fromNodeId: String) {
        when (frame) {
            is ChatFrame -> handleChat(frame)
            is ProfileFrame -> handleProfile(frame)
            is ReceiptFrame -> messages.markReceived(frame.ackId)
        }
    }

    private suspend fun handleChat(frame: ChatFrame) {
        messages.save(
            MessageEntity(
                id = frame.id,
                senderId = frame.senderId,
                recipientId = frame.recipientId,
                body = frame.body,
                sentAt = frame.sentAt,
                received = false,
            ),
        )
        // Acknowledge only if the author is a direct neighbor (mirrors the legacy app: relays don't ack).
        val direct = transport.neighbors.value.firstOrNull { it.nodeId == frame.senderId } ?: return
        val ack = ReceiptFrame(
            id = UUID.randomUUID().toString(),
            senderId = identity.nodeId(),
            ackId = frame.id,
        )
        transport.send(ack, direct)
    }

    private suspend fun handleProfile(frame: ProfileFrame) {
        val existing = peers.find(frame.senderId)
        peers.upsert(
            (existing ?: PeerEntity(frame.senderId)).copy(
                name = frame.name,
                status = frame.status,
                pubKey = frame.pubKey,
                updatedAt = frame.sentAt,
            ),
        )
    }

    private suspend fun onAvatarReceived(nodeId: String, path: String) {
        val existing = peers.find(nodeId)
        peers.upsert((existing ?: PeerEntity(nodeId)).copy(avatarPath = path))
    }
}
