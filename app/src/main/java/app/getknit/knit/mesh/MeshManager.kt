package app.getknit.knit.mesh

import app.getknit.knit.data.AttachmentStore
import app.getknit.knit.data.AvatarStore
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.ReactionRepository
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.MentionStore
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.data.reaction.ReactionEntity
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.mesh.protocol.BlobRequestFrame
import app.getknit.knit.mesh.protocol.ChatFrame
import app.getknit.knit.mesh.protocol.Frame
import app.getknit.knit.mesh.protocol.Mention
import app.getknit.knit.mesh.protocol.ProfileFrame
import app.getknit.knit.mesh.protocol.ReactionFrame
import app.getknit.knit.mesh.protocol.ReceiptFrame
import app.getknit.knit.mesh.protocol.mention
import android.util.Log
import app.getknit.knit.notifications.Notifier
import app.getknit.knit.notifications.incomingNotification
import app.getknit.knit.notifications.mentionNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Orchestrates the mesh: owns the [MeshTransport] and [MeshRouter], handles delivery of new frames
 * (persist chat, ack delivery, cache profiles/avatars, mark receipts), broadcasts this device's
 * profile, and exposes the send/start API used by the foreground service and UI. A process singleton
 * (provided by Koin) so the bound service and the UI share one instance.
 */
class MeshManager(
    private val transport: MeshTransport,
    private val messages: MessageRepository,
    private val reactions: ReactionRepository,
    private val peers: PeerRepository,
    private val identity: Identity,
    private val settings: SettingsStore,
    private val avatars: AvatarStore,
    private val attachments: AttachmentStore,
    private val notifier: Notifier,
    private val scope: CoroutineScope,
    private val metrics: MeshMetrics,
) {
    private val router = MeshRouter(transport, scope, metrics = metrics, onDeliver = ::onDeliver)

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

    // nodeId -> avatar hash we last sent that neighbor, so we don't re-push an unchanged avatar on
    // every profile edit or reconnect. Cleared per-peer when they disconnect (see watchNeighbors).
    private val sentAvatarHashes = ConcurrentHashMap<String, String>()

    /** Number of directly-connected neighbors, for the UI status header. */
    val neighborCount: StateFlow<Int> =
        transport.neighbors
            .map { it.size }
            .stateIn(scope, SharingStarted.Eagerly, 0)

    /** Currently-connected direct neighbors, for the contact picker (message someone on connect). */
    val neighbors: StateFlow<Set<Peer>> get() = transport.neighbors

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
        logMetricsPeriodically()
    }

    fun stop() {
        if (!started) return
        started = false
        transport.stop()
        scope.launch { router.stop() } // cancel any relays still waiting out their jitter window
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
     *
     * [recipientId] null sends to the broadcast room; a node id sends a 1:1 DM addressed to that
     * peer. DMs still flood (no routing table yet) — only the addressed recipient delivers/acks them
     * locally; relays forward the frame untouched.
     */
    suspend fun sendChat(
        text: String,
        attachment: AttachmentStore.Ingested? = null,
        mentions: List<Mention> = emptyList(),
        recipientId: String? = null,
    ) {
        val me = identity.nodeId()
        val frame = ChatFrame(
            id = UUID.randomUUID().toString(),
            senderId = me,
            sentAt = System.currentTimeMillis(),
            body = text,
            recipientId = recipientId,
            mentions = mentions,
            attachmentHash = attachment?.hash,
            attachmentMime = attachment?.mime,
        )
        messages.save(
            MessageEntity(
                id = frame.id,
                senderId = me,
                recipientId = recipientId,
                conversationId = Conversations.idFor(me, recipientId, me),
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

    /**
     * Toggles this device's emoji reaction on [messageId] and floods the change. Tapping the emoji you
     * already chose retracts it; tapping a different one replaces it (one reaction per person). The
     * change is stored optimistically and propagates as a [ReactionFrame]; [sentAt] is the wall clock
     * used for last-writer-wins so concurrent reactors across the mesh converge.
     */
    suspend fun sendReaction(messageId: String, emoji: String) {
        val me = identity.nodeId()
        val next = if (reactions.currentEmoji(messageId, me) == emoji) null else emoji
        val now = System.currentTimeMillis()
        reactions.apply(ReactionEntity(messageId, me, next, now))
        router.originate(
            ReactionFrame(
                id = UUID.randomUUID().toString(),
                senderId = me,
                messageId = messageId,
                emoji = next,
                sentAt = now,
            ),
        )
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
                val currentIds = current.map { it.nodeId }.toSet()
                val newcomers = current.filter { it.nodeId !in known }
                (known - currentIds).forEach { sentAvatarHashes.remove(it) } // forget departed peers
                known = currentIds
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
        sendAvatarIfNeeded(peer)
    }

    private suspend fun broadcastProfile() {
        router.originate(currentProfileFrame())
        transport.neighbors.value.forEach { sendAvatarIfNeeded(it) }
    }

    /**
     * Sends our avatar file to [peer] only if we haven't already sent them this exact avatar. Profile
     * edits that don't touch the avatar (e.g. a status change) re-broadcast the [ProfileFrame] but no
     * longer re-ship the (unchanged) avatar JPEG to every neighbor.
     */
    private suspend fun sendAvatarIfNeeded(peer: Peer) {
        val avatar = avatars.ownAvatarFile.takeIf { it.exists() } ?: return
        val hash = avatars.ownAvatarHash() ?: return
        if (sentAvatarHashes[peer.nodeId] == hash) return
        transport.sendFile(avatar, peer, avatarMeta(hash))
        sentAvatarHashes[peer.nodeId] = hash
    }

    private fun avatarMeta(hash: String): FileMeta =
        FileMeta(FileKind.AVATAR, key = hash, mime = "image/jpeg")

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
            is ReactionFrame -> handleReaction(frame)
            is BlobRequestFrame -> blobExchange.onRequest(frame.hash, fromNodeId)
        }
    }

    /**
     * Applies an inbound reaction. [ReactionRepository.apply] is last-writer-wins, so duplicates and
     * out-of-order add/retract/replace frames are idempotent. The target message may not exist yet
     * (reactions can outrun the message over the mesh) — the row persists regardless and the UI joins.
     */
    private suspend fun handleReaction(frame: ReactionFrame) {
        reactions.apply(
            ReactionEntity(
                messageId = frame.messageId,
                reactorNodeId = frame.senderId,
                emoji = frame.emoji,
                updatedAt = frame.sentAt,
            ),
        )
    }

    private suspend fun handleChat(frame: ChatFrame) {
        val me = identity.nodeId()
        // A DM addressed to someone else: we're only relaying it (the router floods it onward). It
        // isn't ours, so don't persist, notify, or ack it.
        if (!Conversations.isForMe(frame.recipientId, me)) return

        // If we already hold the referenced blob, link it now; otherwise start pulling it.
        val localPath = frame.attachmentHash?.let { attachments.path(it) }
        messages.save(
            MessageEntity(
                id = frame.id,
                senderId = frame.senderId,
                recipientId = frame.recipientId,
                conversationId = Conversations.idFor(frame.senderId, frame.recipientId, me),
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
        if (frame.senderId != me && frame.mentions.mention(me)) {
            notifyMention(frame)
        } else {
            notifyIncoming(frame)
        }
        acknowledge(frame, me)
    }

    /**
     * Sends a delivery receipt for [frame]. A DM addressed to us floods its receipt via the router so
     * it reaches the sender across multiple hops (the recipient is the only one who acks). Broadcast
     * messages keep the legacy behaviour: ack only if the author is a direct neighbor (relays don't).
     */
    private suspend fun acknowledge(frame: ChatFrame, me: String) {
        val ack = ReceiptFrame(id = UUID.randomUUID().toString(), senderId = me, ackId = frame.id)
        if (frame.recipientId == me) {
            router.originate(ack)
        } else {
            val direct = transport.neighbors.value.firstOrNull { it.nodeId == frame.senderId } ?: return
            transport.send(ack, direct)
        }
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

    /** Periodically logs a transmission snapshot so flood-suppression and byte savings are visible. */
    private fun logMetricsPeriodically() {
        scope.launch {
            while (true) {
                delay(METRICS_LOG_INTERVAL_MS)
                val s = metrics.snapshot()
                Log.d(
                    TAG,
                    "metrics: originated=${s.framesOriginated} delivered=${s.framesDelivered} " +
                        "relayed=${s.framesRelayed} suppressed=${s.framesSuppressed} " +
                        "deduped=${s.framesDeduped} bytesSent=${s.bytesSent}",
                )
            }
        }
    }

    private companion object {
        const val TAG = "MeshManager"
        const val METRICS_LOG_INTERVAL_MS = 60_000L
    }
}
