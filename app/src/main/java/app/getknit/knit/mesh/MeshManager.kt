package app.getknit.knit.mesh

import app.getknit.knit.data.AttachmentStore
import app.getknit.knit.data.AvatarStore
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.message.MentionStore
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.mesh.protocol.BlobRequestFrame
import app.getknit.knit.mesh.protocol.ChatFrame
import app.getknit.knit.mesh.protocol.Frame
import app.getknit.knit.mesh.protocol.Mention
import app.getknit.knit.mesh.protocol.ProfileFrame
import app.getknit.knit.mesh.protocol.ReceiptFrame
import app.getknit.knit.mesh.protocol.mention
import app.getknit.knit.notifications.Notifier
import app.getknit.knit.notifications.incomingNotification
import app.getknit.knit.notifications.mentionNotification
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
    private val attachments: AttachmentStore,
    private val notifier: Notifier,
    private val scope: CoroutineScope,
) {
    private val router = MeshRouter(transport, scope, onDeliver = ::onDeliver)

    // Content-addressed image fetch over the mesh, backed by [AttachmentStore].
    private val blobStore = object : BlobStore {
        override fun has(hash: String) = attachments.has(hash)
        override fun fileFor(hash: String) = attachments.fileFor(hash)
        override fun mimeFor(hash: String) = attachments.mimeFor(hash)
        override suspend fun saveIncoming(hash: String, mime: String, srcPath: String) =
            attachments.saveIncoming(hash, mime, srcPath)
    }
    private val blobExchange = BlobExchange(
        transport = transport,
        store = blobStore,
        selfId = { identity.nodeId() },
        onObtained = { hash, path -> messages.setAttachmentPath(hash, path) },
    )

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
        watchIncomingFiles()
        resumePendingFetches()
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

    /**
     * Composes a chat message (optionally with an already-ingested image [attachment]), stores it
     * locally (unacked), and floods it to the mesh. The sender already holds the blob, so direct
     * neighbors will pull it by hash and it propagates outward from there.
     */
    suspend fun sendChat(
        text: String,
        attachment: AttachmentStore.Ingested? = null,
        mentions: List<Mention> = emptyList(),
    ) {
        val me = identity.nodeId()
        val frame = ChatFrame(
            id = UUID.randomUUID().toString(),
            senderId = me,
            sentAt = System.currentTimeMillis(),
            body = text,
            mentions = mentions,
            attachmentHash = attachment?.hash,
            attachmentMime = attachment?.mime,
        )
        messages.save(
            MessageEntity(
                id = frame.id,
                senderId = me,
                body = text,
                sentAt = frame.sentAt,
                received = false,
                mentions = MentionStore.encode(mentions),
                attachmentHash = attachment?.hash,
                attachmentMime = attachment?.mime,
                attachmentPath = attachment?.path,
            ),
        )
        router.originate(frame)
    }

    /** On startup, re-request any attachment blobs referenced by stored messages we don't yet have. */
    private fun resumePendingFetches() {
        scope.launch { messages.hashesNeedingFetch().forEach { blobExchange.want(it) } }
    }

    // --- Profile broadcasting ---

    private fun watchNeighbors() {
        scope.launch {
            var known = emptySet<String>()
            transport.neighbors.collect { current ->
                val newcomers = current.filter { it.nodeId !in known }
                known = current.map { it.nodeId }.toSet()
                newcomers.forEach {
                    pushProfileTo(it)
                    blobExchange.onNeighborAdded(it) // re-ask the new neighbor for blobs we still need
                }
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

    private fun watchIncomingFiles() {
        scope.launch {
            transport.incomingFiles.collect { file ->
                when (file.kind) {
                    FileKind.AVATAR -> onAvatarReceived(file.fromNodeId, file.path)
                    FileKind.ATTACHMENT ->
                        blobExchange.onReceived(file.key, file.mime, file.path, file.fromNodeId)
                }
            }
        }
    }

    private suspend fun pushProfileTo(peer: Peer) {
        router.sendOwn(currentProfileFrame(), peer)
        avatars.ownAvatarFile.takeIf { it.exists() }?.let { transport.sendFile(it, peer, avatarMeta()) }
    }

    private suspend fun broadcastProfile() {
        router.originate(currentProfileFrame())
        val avatar = avatars.ownAvatarFile.takeIf { it.exists() } ?: return
        transport.neighbors.value.forEach { transport.sendFile(avatar, it, avatarMeta()) }
    }

    private fun avatarMeta(): FileMeta =
        FileMeta(FileKind.AVATAR, key = avatars.ownAvatarHash().orEmpty(), mime = "image/jpeg")

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
            is BlobRequestFrame -> blobExchange.onRequest(frame.hash, fromNodeId)
        }
    }

    private suspend fun handleChat(frame: ChatFrame) {
        // If we already hold the referenced blob, link it now; otherwise start pulling it.
        val localPath = frame.attachmentHash?.let { attachments.path(it) }
        messages.save(
            MessageEntity(
                id = frame.id,
                senderId = frame.senderId,
                recipientId = frame.recipientId,
                body = frame.body,
                sentAt = frame.sentAt,
                received = false,
                mentions = MentionStore.encode(frame.mentions),
                attachmentHash = frame.attachmentHash,
                attachmentMime = frame.attachmentMime,
                attachmentPath = localPath,
            ),
        )
        frame.attachmentHash?.let { if (localPath == null) blobExchange.want(it) }
        // A message that @-mentions us notifies on the dedicated Mentions channel only; everything else
        // takes the normal room notification path.
        val me = identity.nodeId()
        if (frame.senderId != me && frame.mentions.mention(me)) {
            notifyMention(frame)
        } else {
            notifyIncoming(frame)
        }
        // Acknowledge only if the author is a direct neighbor (mirrors the legacy app: relays don't ack).
        val direct = transport.neighbors.value.firstOrNull { it.nodeId == frame.senderId } ?: return
        val ack = ReceiptFrame(
            id = UUID.randomUUID().toString(),
            senderId = identity.nodeId(),
            ackId = frame.id,
        )
        transport.send(ack, direct)
    }

    /** Fires a "new message" notification for an inbound chat (skips our own and empty messages). */
    private suspend fun notifyIncoming(frame: ChatFrame) {
        val me = identity.nodeId()
        val peer = peers.find(frame.senderId)
        // Image-only messages have a blank body; show a placeholder so they still notify.
        val body = frame.body.ifBlank { if (frame.attachmentHash != null) "📷 Photo" else frame.body }
        val incoming = incomingNotification(
            senderId = frame.senderId,
            body = body,
            sentAt = frame.sentAt,
            selfId = me,
            peerName = peer?.name,
            peerAvatarPath = peer?.avatarPath ?: avatars.peerAvatarPath(frame.senderId),
        ) ?: return
        notifier.notify(incoming, me, settings.displayName.first(), avatars.ownAvatarPath())
    }

    /** Fires a "you were mentioned" notification on the Mentions channel for an inbound chat. */
    private suspend fun notifyMention(frame: ChatFrame) {
        val me = identity.nodeId()
        val peer = peers.find(frame.senderId)
        val body = frame.body.ifBlank { if (frame.attachmentHash != null) "📷 Photo" else frame.body }
        val incoming = mentionNotification(
            senderId = frame.senderId,
            body = body,
            sentAt = frame.sentAt,
            selfId = me,
            peerName = peer?.name,
            peerAvatarPath = peer?.avatarPath ?: avatars.peerAvatarPath(frame.senderId),
        ) ?: return
        notifier.notifyMention(incoming, me, settings.displayName.first(), avatars.ownAvatarPath())
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
