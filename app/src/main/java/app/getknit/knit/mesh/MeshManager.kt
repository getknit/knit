package app.getknit.knit.mesh

import app.getknit.knit.data.AttachmentStore
import app.getknit.knit.data.BlobRepository
import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.MeshBlobStore
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.ReactionRepository
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.group.GroupMembersStore
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.MentionStore
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.data.reaction.ReactionEntity
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.crypto.AttachmentCrypto
import app.getknit.knit.mesh.crypto.MessageContent
import app.getknit.knit.mesh.crypto.MessageCrypto
import app.getknit.knit.mesh.crypto.PublicKeyBundle
import app.getknit.knit.mesh.crypto.b64
import app.getknit.knit.mesh.crypto.b64d
import app.getknit.knit.mesh.protocol.BlobRequestFrame
import app.getknit.knit.mesh.protocol.ChatFrame
import app.getknit.knit.mesh.protocol.Frame
import app.getknit.knit.mesh.protocol.GroupInfo
import app.getknit.knit.mesh.protocol.GroupUpdateFrame
import app.getknit.knit.mesh.protocol.Mention
import app.getknit.knit.mesh.protocol.ProfileFrame
import app.getknit.knit.mesh.protocol.ReactionFrame
import app.getknit.knit.mesh.protocol.ReceiptFrame
import app.getknit.knit.mesh.protocol.mention
import app.getknit.knit.mesh.protocol.signedBytes
import app.getknit.knit.mesh.protocol.withSig
import android.util.Log
import app.getknit.knit.moderation.ScopedTextModerator
import app.getknit.knit.notifications.Notifier
import app.getknit.knit.notifications.incomingNotification
import app.getknit.knit.notifications.mentionNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Orchestrates the mesh: owns the [MeshTransport] and [MeshRouter], handles delivery of new frames
 * (persist chat, ack delivery, cache profiles/avatars, mark receipts), broadcasts this device's
 * profile, and exposes the send/start API used by the foreground service and UI. A process singleton
 * (provided by Koin) so the bound service and the UI share one instance.
 */
// The central mesh orchestrator: many small frame handlers, and many collaborators injected by design.
@Suppress("TooManyFunctions", "LongParameterList")
class MeshManager(
    private val transport: MeshTransport,
    private val messages: MessageRepository,
    private val groups: GroupRepository,
    private val reactions: ReactionRepository,
    private val peers: PeerRepository,
    private val identity: Identity,
    private val settings: SettingsStore,
    private val blobs: BlobRepository,
    private val blobStore: MeshBlobStore,
    private val notifier: Notifier,
    private val textModeration: ScopedTextModerator,
    private val messageCrypto: MessageCrypto,
    private val scope: CoroutineScope,
    private val metrics: MeshMetrics,
) {
    // Reconstructed per session so its inbound collector + relay jobs live on the session scope and
    // are cancelled by stop() (rather than leaking on the never-cancelled app scope).
    private var router = MeshRouter(transport, scope, metrics = metrics, onDeliver = ::onDeliver)

    // Per-session scope for the collectors + metrics loop + router; cancelled in stop() so they don't
    // accumulate across start/stop cycles (e.g. restart() on Bluetooth recovery).
    private var sessionScope: CoroutineScope? = null

    // Content-addressed image fetch over the mesh, backed by the encrypted blob store.
    private val blobExchange = BlobExchange(
        transport = transport,
        store = blobStore,
        selfId = { identity.nodeId() },
        // The chat list observes the blobs table for presence, so no per-message path write is needed
        // when an attachment arrives. A pulled blob may also be a (multi-hop) peer's avatar, so attribute
        // it back to whoever advertised it, and — for an E2E attachment — screen its decrypted bytes now
        // that both the ciphertext and (from the delivered message) its key are on hand.
        onObtained = { hash, _ ->
            adoptAdvertisedAvatar(hash)
            screenObtainedAttachment(hash)
        },
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

    // nodeId -> avatar hash a non-direct peer advertised but whose bytes we're still pulling, so a blob
    // arriving via the multi-hop BlobExchange can be attributed back to the peer that advertised it.
    private val advertisedAvatars = ConcurrentHashMap<String, String>()

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
        blobStore.clearTransfers() // drop any plaintext transfer temp files left by a previous session
        // Child of the app Job so app-scope cancellation still propagates; SupervisorJob isolates a
        // single collector's failure from the rest of the session.
        val session = CoroutineScope(SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.Default)
        sessionScope = session
        router = MeshRouter(transport, session, metrics = metrics, onDeliver = ::onDeliver)
        router.start()
        transport.start()
        watchNeighbors(session)
        watchProfileChanges(session)
        watchIncomingFiles(session)
        resumePendingFetches(session)
        logMetricsPeriodically(session)
    }

    fun stop() {
        if (!started) return
        started = false
        transport.stop()
        // Clear this session's pending relays on the app scope (the session is about to die); capture
        // the instance so a fast restart reassigning `router` can't retarget the wrong one. Then tear
        // the session down — stopping the inbound collector, the metrics loop, and the watch* collectors.
        val session = sessionScope
        val sessionRouter = router
        scope.launch { sessionRouter.stop() }
        session?.cancel()
        sessionScope = null
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
     * peer. A non-null [group] sends a group message (recipientId stays null) — the [GroupInfo] rides
     * on the frame so members can (re)build the group from it. All three still flood (no routing table
     * yet); only the addressed recipient(s) deliver/ack, while relays forward the frame untouched.
     *
     * Returns false without sending or storing anything if on-device content filtering flags [text] as
     * abusive (block-on-send); true once the message is stored locally and flooded. The [attachment] is
     * screened separately at ingest time, so by here it is already clean.
     */
    suspend fun sendChat(
        text: String,
        attachment: AttachmentStore.Ingested? = null,
        mentions: List<Mention> = emptyList(),
        recipientId: String? = null,
        group: GroupInfo? = null,
    ): Boolean {
        if (isTextFlagged(text, "outgoing", isRoom = recipientId == null && group == null)) return false
        val me = identity.nodeId()
        val id = UUID.randomUUID().toString()
        val sentAt = System.currentTimeMillis()
        val conversationId = Conversations.idFor(me, recipientId, me, group?.id)

        // Broadcast room: plaintext (no fixed recipient set to encrypt to) — the legacy path, unchanged.
        if (recipientId == null && group == null) {
            messages.save(
                MessageEntity(
                    id = id, senderId = me, recipientId = null, conversationId = conversationId,
                    body = text, sentAt = sentAt, received = false,
                    mentions = MentionStore.encode(mentions),
                    attachmentHash = attachment?.hash, attachmentMime = attachment?.mime,
                ),
            )
            originateSigned(
                ChatFrame(
                    id = id, senderId = me, sentAt = sentAt, body = text, mentions = mentions,
                    attachmentHash = attachment?.hash, attachmentMime = attachment?.mime,
                ),
            )
            return true
        }

        // DM or group: end-to-end encrypt. The attachment (if any) is encrypted to its own key and
        // re-addressed by its ciphertext hash; body/mentions/attachment refs go into the sealed content.
        val sealedAttachment = attachment?.let { sealAttachment(it) }
        val content = MessageContent(
            body = text,
            mentions = mentions,
            attachmentHash = sealedAttachment?.hash,
            attachmentMime = attachment?.mime,
            attachmentKey = sealedAttachment?.key,
        )
        // Persist our own plaintext copy regardless, so the sender always sees their message.
        messages.save(
            MessageEntity(
                id = id, senderId = me, recipientId = recipientId, conversationId = conversationId,
                body = text, sentAt = sentAt, received = false,
                mentions = MentionStore.encode(mentions),
                attachmentHash = sealedAttachment?.hash, attachmentMime = attachment?.mime,
                attachmentKey = sealedAttachment?.key,
            ),
        )
        val thread = group?.id ?: recipientId.orEmpty()
        val header = MessageCrypto.header(id, me, sentAt, thread)
        val sealed = messageCrypto.seal(content.encode(), header, recipientBundles(recipientId, group, me))
        if (sealed == null) {
            // No recipient's key is known yet — nothing can decrypt this. Saved locally above but not
            // flooded; it stays unsent until a profile (carrying the key) arrives.
            Log.w(TAG, "no known keys for recipient(s) of chat $id; not sent")
            return true
        }
        router.originate(
            ChatFrame(
                id = id, senderId = me, sentAt = sentAt, body = "",
                recipientId = recipientId, sig = sealed.sig, group = group, enc = sealed.envelope,
            ),
        )
        return true
    }

    /** Resolves the published key bundles for a DM recipient or a group's members (excluding us). */
    private suspend fun recipientBundles(
        recipientId: String?,
        group: GroupInfo?,
        me: String,
    ): Map<String, PublicKeyBundle> {
        val targets = when {
            group != null -> group.members.filter { it != me }
            recipientId != null -> listOf(recipientId)
            else -> emptyList()
        }
        return targets.mapNotNull { nodeId ->
            peers.find(nodeId)?.pubKey?.let { PublicKeyBundle.decode(it) }?.let { nodeId to it }
        }.toMap()
    }

    /** Encrypted, content-addressed copy of a just-ingested attachment, plus its base64 key. */
    private data class SealedAttachment(val hash: String, val key: String)

    /**
     * Encrypts the ingested (plaintext) attachment to a fresh key, stores the ciphertext blob under its
     * ciphertext hash (so the existing content-addressed pull/dedup still works), and drops the now-
     * unreferenced plaintext blob.
     */
    private suspend fun sealAttachment(attachment: AttachmentStore.Ingested): SealedAttachment? {
        val plain = blobs.bytes(attachment.hash) ?: return null
        val sealed = AttachmentCrypto.seal(plain)
        val ctHash = sha256Hex(sealed.blob)
        blobs.insert(ctHash, attachment.mime, sealed.blob)
        blobs.deleteIfUnreferenced(attachment.hash)
        return SealedAttachment(ctHash, b64(sealed.key))
    }

    /**
     * Floods a group metadata update (e.g. a rename) immediately, independent of any chat message, so
     * members converge without waiting for the next message. The receiver applies it last-writer-wins on
     * [GroupUpdateFrame.sentAt]; the local store has already been updated by the caller.
     */
    suspend fun sendGroupUpdate(group: GroupInfo) {
        originateSigned(
            GroupUpdateFrame(
                id = UUID.randomUUID().toString(),
                senderId = identity.nodeId(),
                sentAt = System.currentTimeMillis(),
                group = group,
            ),
        )
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
        originateSigned(
            ReactionFrame(
                id = UUID.randomUUID().toString(),
                senderId = me,
                messageId = messageId,
                emoji = next,
                sentAt = now,
            ),
        )
    }

    /** On startup, sweep orphaned blobs/reactions and re-request attachment blobs we're still missing. */
    private fun resumePendingFetches(session: CoroutineScope) {
        session.launch {
            blobs.deleteOrphans() // reclaim blobs left by attachments staged but never sent
            reactions.deleteOrphans(System.currentTimeMillis()) // reclaim reactions left by deleted messages
            messages.hashesNeedingFetch().forEach { blobExchange.want(it) }
        }
    }

    // --- Profile broadcasting ---

    private fun watchNeighbors(session: CoroutineScope) {
        session.launch {
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

    private fun watchProfileChanges(session: CoroutineScope) {
        session.launch {
            combine(settings.displayName, settings.status, settings.avatarUpdatedAt) { _, _, _ -> }
                .drop(1) // skip the initial stored value; only react to real edits
                .collect {
                    profileVersion = System.currentTimeMillis()
                    broadcastProfile()
                }
        }
    }

    private fun watchIncomingFiles(session: CoroutineScope) {
        session.launch {
            transport.incomingFiles.collect { file ->
                when (file.kind) {
                    FileKind.AVATAR -> onAvatarReceived(file.fromNodeId, file.key, file.mime, file.path)
                    FileKind.ATTACHMENT ->
                        blobExchange.onReceived(file.key, file.mime, file.path, file.fromNodeId)
                }
            }
        }
    }

    private suspend fun pushProfileTo(peer: Peer) {
        sendOwnSigned(currentProfileFrame(), peer)
        sendAvatarIfNeeded(peer)
    }

    private suspend fun broadcastProfile() {
        originateSigned(currentProfileFrame())
        transport.neighbors.value.forEach { sendAvatarIfNeeded(it) }
    }

    /**
     * Sends our avatar file to [peer] only if we haven't already sent them this exact avatar. Profile
     * edits that don't touch the avatar (e.g. a status change) re-broadcast the [ProfileFrame] but no
     * longer re-ship the (unchanged) avatar JPEG to every neighbor.
     */
    private suspend fun sendAvatarIfNeeded(peer: Peer) {
        val hash = settings.ownAvatarHash.first() ?: return
        if (sentAvatarHashes[peer.nodeId] == hash) return
        val avatar = blobStore.fileFor(hash) ?: return
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
            avatarHash = settings.ownAvatarHash.first(),
            pubKey = identity.publicKeyBundle(),
            deviceTag = identity.deviceTag(),
        )
    }

    // --- Signed origination ---

    /** Floods a locally-built [frame] to the whole mesh, stamping it with our Ed25519 signature. */
    private suspend fun originateSigned(frame: Frame) = router.originate(sign(frame))

    /** Sends a locally-built [frame] to a single [peer] (targeted profile push), signed. */
    private suspend fun sendOwnSigned(frame: Frame, peer: Peer) = router.sendOwn(sign(frame), peer)

    /** Attaches our signature over the frame's canonical bytes (see [signedBytes]). */
    private fun sign(frame: Frame): Frame = frame.withSig(messageCrypto.sign(frame.signedBytes()))

    // --- Delivery of inbound frames ---

    private suspend fun onDeliver(frame: Frame, fromNodeId: String) {
        // Strict authentication gate: a flooded frame that isn't signed by the key its senderId binds
        // to is dropped (not delivered locally). We still return normally so MeshRouter relays it
        // onward — other peers verify independently, and we don't become a propagation black hole.
        if (!verifyInbound(frame)) return
        when (frame) {
            is ChatFrame -> handleChat(frame)
            is GroupUpdateFrame -> handleGroupUpdate(frame)
            is ProfileFrame -> handleProfile(frame)
            is ReceiptFrame -> messages.markReceived(frame.ackId)
            is ReactionFrame -> handleReaction(frame)
            is BlobRequestFrame -> blobExchange.onRequest(frame.hash, fromNodeId)
        }
    }

    /**
     * Authenticates a flooded frame: its [Frame.sig] must verify against a public-key bundle that
     * derives back to [Frame.senderId]. A [ProfileFrame] carries that bundle in-band (first contact
     * arrives before any pin); every other frame uses the sender's pinned key, so a frame from a peer
     * whose profile we haven't received yet is dropped. Encrypted chat keeps its envelope signature
     * (checked later in [decrypt]); the point-to-point [BlobRequestFrame] is unsigned by design.
     *
     * Wrapped in [runCatching] so it NEVER throws out of [onDeliver]: any failure returns false =
     * "drop locally", and the router still schedules the relay (it runs after onDeliver returns).
     */
    private suspend fun verifyInbound(frame: Frame): Boolean = runCatching {
        if (frame is BlobRequestFrame) return true
        if (frame is ChatFrame && frame.enc != null) return true // envelope sig verified in decrypt()
        val bundle = when (frame) {
            is ProfileFrame -> frame.pubKey?.let { PublicKeyBundle.decode(it) }
            else -> peers.find(frame.senderId)?.pubKey?.let { PublicKeyBundle.decode(it) }
        }
        if (bundle == null) {
            Log.w(TAG, "drop ${frame.kind()} ${frame.id} from ${frame.senderId}: no key to verify it")
            return false
        }
        // The verifying key must provably belong to the claimed senderId (a nodeId IS the hash of the
        // bundle). Mirrors the encrypted-chat check in decrypt(); also rejects stale device-derived pins.
        if (NodeId.fromPublicKeyBundle(bundle.encoded) != frame.senderId) {
            Log.w(TAG, "drop ${frame.kind()} ${frame.id} from ${frame.senderId}: key does not match nodeId")
            return false
        }
        if (!MessageCrypto.verify(bundle, frame.sig, frame.signedBytes())) {
            Log.w(TAG, "drop ${frame.kind()} ${frame.id} from ${frame.senderId}: bad/missing signature")
            return false
        }
        true
    }.getOrElse {
        Log.w(TAG, "drop frame ${frame.id} from ${frame.senderId}: verification error ${it.message}")
        false
    }

    /** Short frame-type label for verification logs. */
    private fun Frame.kind(): String = when (this) {
        is ChatFrame -> "chat"
        is GroupUpdateFrame -> "groupupdate"
        is ProfileFrame -> "profile"
        is ReceiptFrame -> "receipt"
        is ReactionFrame -> "reaction"
        is BlobRequestFrame -> "blobreq"
    }

    /**
     * Applies an inbound reaction. [ReactionRepository.apply] is last-writer-wins, so duplicates and
     * out-of-order add/retract/replace frames are idempotent. The target message may not exist yet
     * (reactions can outrun the message over the mesh) — the row persists regardless and the UI joins.
     */
    private suspend fun handleReaction(frame: ReactionFrame) {
        // Blocked sender: drop the reaction (the router still relays it onward to other peers).
        if (frame.senderId in settings.blockedNodeIds.first()) return
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
        // Blocked sender: never persist, notify, or ack — but the router still relays it onward, so a
        // blocked user stays a working mesh peer (we just don't surface anything from them).
        if (frame.senderId in settings.blockedNodeIds.first()) return
        // Group messages take the membership-gated path; they carry recipientId null, so they must be
        // handled before the DM check below (which would otherwise treat them as broadcast).
        val group = frame.group
        if (group != null) {
            handleGroupChat(frame, group, me)
            return
        }
        // A DM addressed to someone else: we're only relaying it (the router floods it onward). It
        // isn't ours, so don't persist, notify, or ack it.
        if (!Conversations.isForMe(frame.recipientId, me)) return
        decryptAndDeliver(frame, me, Conversations.idFor(frame.senderId, frame.recipientId, me))
    }

    /**
     * Handles an inbound group message: reconciles the group from the self-describing roster carried on
     * the frame, then (if the group is active for us) persists/notifies/acks the message.
     */
    private suspend fun handleGroupChat(frame: ChatFrame, group: GroupInfo, me: String) {
        if (reconcileGroup(group, frame.senderId, frame.sentAt, me)) {
            decryptAndDeliver(frame, me, group.id)
        }
    }

    /**
     * For an encrypted DM/group frame, verifies + decrypts it (dropping it on any failure) and delivers
     * the plaintext; a plaintext (broadcast) frame is delivered as-is. Decryption is wrapped so a failure
     * NEVER throws out of the inbound handler — the router schedules the relay *after* onDeliver returns,
     * so an exception here would silently stop us forwarding the frame to other peers.
     */
    private suspend fun decryptAndDeliver(frame: ChatFrame, me: String, conversationId: String) {
        if (frame.enc == null) {
            deliverChat(frame, me, conversationId)
            return
        }
        val decrypted = runCatching { decrypt(frame, me) }.getOrElse {
            Log.w(TAG, "drop encrypted chat ${frame.id}: ${it.message}")
            null
        } ?: return
        deliverChat(decrypted.frame, me, conversationId, decrypted.attachmentKey)
    }

    /** A successfully-decrypted chat: a plaintext frame copy plus the attachment key (if any). */
    private data class Decrypted(val frame: ChatFrame, val attachmentKey: String?)

    /**
     * Verifies the sender's signature against their pinned key and decrypts the envelope. Returns null
     * (drop) when we have no pinned key for the sender yet, or verification/decryption fails.
     */
    private suspend fun decrypt(frame: ChatFrame, me: String): Decrypted? {
        val envelope = frame.enc ?: return null
        val senderBundle = peers.find(frame.senderId)?.pubKey?.let { PublicKeyBundle.decode(it) }
        if (senderBundle == null) {
            Log.w(TAG, "drop encrypted chat from ${frame.senderId}: no pinned key")
            return null
        }
        // Defence in depth: the pinned key must derive to the sender's nodeId. handleProfile already
        // enforces this when pinning, so this also rejects any stale pin left by a pre-self-certifying
        // build (whose nodeIds were device-derived, not key-derived).
        if (NodeId.fromPublicKeyBundle(senderBundle.encoded) != frame.senderId) {
            Log.w(TAG, "drop encrypted chat from ${frame.senderId}: pinned key does not match nodeId")
            return null
        }
        val thread = frame.group?.id ?: frame.recipientId.orEmpty()
        val header = MessageCrypto.header(frame.id, frame.senderId, frame.sentAt, thread)
        val content = messageCrypto.open(envelope, frame.sig, header, me, senderBundle) ?: return null
        return Decrypted(
            frame.copy(
                body = content.body,
                mentions = content.mentions,
                attachmentHash = content.attachmentHash,
                attachmentMime = content.attachmentMime,
                enc = null,
                sig = null,
            ),
            content.attachmentKey,
        )
    }

    /**
     * Handles a standalone group-metadata update (e.g. a rename): reconciles the stored group, with no
     * message to persist or ack. A member who didn't yet know the group creates it from the roster.
     */
    private suspend fun handleGroupUpdate(frame: GroupUpdateFrame) {
        reconcileGroup(frame.group, frame.senderId, frame.sentAt, identity.nodeId())
    }

    /**
     * Brings the locally-stored group in line with a self-describing [group] roster carried on a frame
     * from [senderId] (stamped [sentAt]). Returns true when the group is active for us (so a chat frame
     * should be delivered), false when the frame must be ignored: blocked sender, a group we've left
     * (never re-upserted, so a frame can't resurrect it), one we're not a member of, or a *new* group
     * whose creator we've blocked (covers the proxy case where a non-blocked member relays the first
     * frame carrying a blocked createdBy). The name is last-writer-wins on [sentAt] so concurrent renames
     * across the mesh converge.
     */
    private suspend fun reconcileGroup(group: GroupInfo, senderId: String, sentAt: Long, me: String): Boolean {
        val blocked = settings.blockedNodeIds.first()
        val existing = groups.find(group.id)
        val refuse = senderId in blocked || // blocked sender's frame
            existing?.left == true || // a group we've left — never re-upsert, so it can't be resurrected
            !Conversations.isGroupMember(group.members, me) || // not for us; we're only relaying it
            (existing == null && group.createdBy in blocked) // a blocked user starting a new group here
        if (refuse) return false

        // The name is shared only when explicitly set; an unnamed (blank/null) frame never clears a name
        // someone else set. Adopt an incoming name only if it's newer (last-writer-wins on sentAt).
        val incomingName = group.name?.takeIf { it.isNotBlank() }
        val keepName = existing?.name.orEmpty()
        val keepClock = existing?.nameUpdatedAt ?: 0L
        val takeIncoming = incomingName != null && sentAt >= keepClock
        groups.upsert(
            GroupEntity(
                groupId = group.id,
                name = if (takeIncoming) incomingName else keepName,
                members = GroupMembersStore.encode(group.members),
                createdBy = group.createdBy,
                createdAt = existing?.createdAt ?: sentAt,
                nameUpdatedAt = if (takeIncoming) sentAt else keepClock,
                left = false,
            ),
        )
        return true
    }

    /**
     * Persists an inbound chat into [conversationId], starts pulling any attachment blob we don't hold,
     * fires the appropriate notification, and acks. Shared by the DM/broadcast and group delivery paths.
     */
    private suspend fun deliverChat(
        frame: ChatFrame,
        me: String,
        conversationId: String,
        attachmentKey: String? = null,
    ) {
        val hash = frame.attachmentHash
        messages.save(
            MessageEntity(
                id = frame.id,
                senderId = frame.senderId,
                recipientId = frame.recipientId,
                conversationId = conversationId,
                body = frame.body,
                sentAt = frame.sentAt,
                received = false,
                mentions = MentionStore.encode(frame.mentions),
                attachmentHash = hash,
                attachmentMime = frame.attachmentMime,
                attachmentKey = attachmentKey,
                moderation = if (
                    isTextFlagged(frame.body, "incoming", isRoom = conversationId == Conversations.NEARBY)
                ) {
                    MessageEntity.MODERATION_TEXT_FLAGGED
                } else {
                    MessageEntity.MODERATION_NONE
                },
            ),
        )
        // Start pulling the referenced blob unless we already hold it (the UI observes the blobs table
        // and flips the attachment from "loading" to shown once the bytes land). If we already hold it
        // (e.g. cached earlier while relaying), screen its decrypted bytes now that the key is in hand;
        // otherwise it's screened on arrival ([screenObtainedAttachment]) once the key has been stored.
        if (hash != null) {
            if (blobStore.has(hash)) {
                screenEncryptedAttachment(hash, attachmentKey)
            } else {
                blobExchange.want(hash)
            }
        }
        // A message that @-mentions us notifies on the dedicated Mentions channel only; everything else
        // takes the per-context channel (Nearby / Group messages / Direct messages), keyed off conversationId.
        if (frame.senderId != me && frame.mentions.mention(me)) {
            notifyMention(frame, conversationId)
        } else {
            notifyIncoming(frame, conversationId)
        }
        acknowledge(frame, me)
    }

    /**
     * Whether [text] is non-blank, content filtering is enabled, and the on-device moderator flags it as
     * abusive. Drives both block-on-send (in [sendChat]) and the stored flag on inbound messages (in
     * [deliverChat]); a flagged inbound message is still stored and merely collapsed in the UI, so a
     * false positive never silently drops content. [isRoom] selects the moderation scope: the Nearby
     * broadcast room gets profanity + toxicity, while DMs and groups get toxicity only (see
     * [ScopedTextModerator]). [direction] (`"outgoing"`/`"incoming"`) only labels the debug log; the
     * verdict score/category/decision is logged under [TEXT_MODERATION_TAG], mirroring the image
     * screen's `ImageModeration` logging — the body itself is never logged (only its length).
     */
    private suspend fun isTextFlagged(text: String, direction: String, isRoom: Boolean): Boolean {
        if (text.isBlank() || !settings.contentFilteringEnabled.first()) return false
        val verdict = textModeration.classify(text, isRoom)
        Log.d(
            TEXT_MODERATION_TAG,
            "$direction text score=${verdict.score} category=${verdict.category} " +
                "label=${verdict.label} flagged=${verdict.flagged} len=${text.length}",
        )
        return verdict.flagged
    }

    /**
     * Sends a delivery receipt for [frame]. A DM addressed to us floods its receipt via the router so
     * it reaches the sender across multiple hops (the recipient is the only one who acks). Broadcast
     * and group messages keep the legacy behaviour: ack only if the author is a direct neighbor (relays
     * don't) — group messages carry recipientId null, so the tick is best-effort, not per-member.
     */
    private suspend fun acknowledge(frame: ChatFrame, me: String) {
        val ack = ReceiptFrame(id = UUID.randomUUID().toString(), senderId = me, ackId = frame.id)
        if (frame.recipientId == me) {
            originateSigned(ack)
        } else {
            val direct = transport.neighbors.value.firstOrNull { it.nodeId == frame.senderId } ?: return
            transport.send(sign(ack), direct)
        }
    }

    /** Fires a "new message" notification for an inbound chat in [conversationId] (skips our own and empty messages). */
    private suspend fun notifyIncoming(frame: ChatFrame, conversationId: String) {
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
            peerAvatarBytes = peer?.avatarHash?.let { blobs.bytes(it) },
            conversationId = conversationId,
        ) ?: return
        val selfAvatar = settings.ownAvatarHash.first()?.let { blobs.bytes(it) }
        notifier.notify(incoming, me, settings.displayName.first(), selfAvatar)
    }

    /** Fires a "you were mentioned" notification on the Mentions channel for an inbound chat in [conversationId]. */
    private suspend fun notifyMention(frame: ChatFrame, conversationId: String) {
        val me = identity.nodeId()
        val peer = peers.find(frame.senderId)
        val body = frame.body.ifBlank { if (frame.attachmentHash != null) "📷 Photo" else frame.body }
        val incoming = mentionNotification(
            senderId = frame.senderId,
            body = body,
            sentAt = frame.sentAt,
            selfId = me,
            peerName = peer?.name,
            peerAvatarBytes = peer?.avatarHash?.let { blobs.bytes(it) },
            conversationId = conversationId,
        ) ?: return
        val selfAvatar = settings.ownAvatarHash.first()?.let { blobs.bytes(it) }
        notifier.notifyMention(incoming, me, settings.displayName.first(), selfAvatar)
    }

    private suspend fun handleProfile(frame: ProfileFrame) {
        // Self-certifying identity: a peer's nodeId IS the hash of its public-key bundle, so a profile
        // is only trustworthy if the advertised key actually derives back to the claimed senderId.
        // This makes the key pin race-proof — a peer cannot pin a key for a nodeId it doesn't hold the
        // keypair for (impersonating one would require a hash collision), so there is no first-speaker
        // TOFU window. A null or mismatched key is dropped outright.
        val pubKey = frame.pubKey
        if (pubKey == null || NodeId.fromPublicKeyBundle(pubKey) != frame.senderId) {
            Log.w(TAG, "drop profile from ${frame.senderId}: key does not derive to its nodeId")
            return
        }
        val existing = peers.find(frame.senderId)
        val advertised = frame.avatarHash
        // The stored avatarHash means "bytes are present locally": adopt the advertised hash only once
        // we hold its blob, otherwise keep the current avatar (if any) until the new one is fetched.
        val haveAvatar = advertised != null && blobStore.has(advertised)
        // The key is bound to the nodeId, so a verified peer's key cannot legitimately change; keep the
        // pinned-verified state as-is. (A different key for the same nodeId would be a hash collision,
        // already excluded above.)
        peers.upsert(
            (existing ?: PeerEntity(frame.senderId)).copy(
                name = frame.name,
                status = frame.status,
                pubKey = pubKey,
                verified = existing?.verified ?: false,
                deviceTag = frame.deviceTag ?: existing?.deviceTag,
                avatarHash = if (haveAvatar) advertised else existing?.avatarHash,
                updatedAt = frame.sentAt,
            ),
        )
        // Block-list continuity: a nodeId is the hash of a keypair, so a blocked peer that regenerates
        // its key returns under a new nodeId. If this peer's (key-independent) device tag is already
        // blocked, block this new nodeId too — every other block check stays plain nodeId-based.
        val deviceTag = frame.deviceTag
        if (deviceTag != null &&
            frame.senderId !in settings.blockedNodeIds.first() &&
            deviceTag in settings.blockedDeviceTags.first()
        ) {
            settings.block(frame.senderId, deviceTag)
        }
        // A direct neighbor pushes its avatar to us (sendAvatarIfNeeded); a peer we only reach through a
        // relay won't, so pull its avatar hop-by-hop over the same content-addressed exchange that
        // carries attachments. It's attributed back to this peer in [adoptAdvertisedAvatar] on arrival.
        if (advertised != null && !haveAvatar &&
            transport.neighbors.value.none { it.nodeId == frame.senderId }
        ) {
            advertisedAvatars[frame.senderId] = advertised
            blobExchange.want(advertised)
        }
    }

    /**
     * A pulled blob just landed: if any non-direct peer advertised it as their avatar (see
     * [handleProfile]), point those peers at it now that the bytes are local and drop the previous one.
     * A no-op for attachment blobs, which no peer advertises.
     */
    private suspend fun adoptAdvertisedAvatar(hash: String) {
        val owners = advertisedAvatars.entries.filter { it.value == hash }.map { it.key }
        if (owners.isEmpty()) return
        // A pulled avatar was screened in MeshBlobStore.saveIncoming; don't adopt it if flagged explicit.
        if (blobs.isImageFlagged(hash)) {
            owners.forEach { advertisedAvatars.remove(it) }
            blobs.deleteIfUnreferenced(hash)
            return
        }
        owners.forEach { nodeId ->
            advertisedAvatars.remove(nodeId)
            val peer = peers.find(nodeId) ?: return@forEach
            if (peer.avatarHash == hash) return@forEach
            val oldHash = peer.avatarHash
            peers.upsert(peer.copy(avatarHash = hash))
            if (oldHash != hash) blobs.deleteIfUnreferenced(oldHash)
        }
    }

    /**
     * Ingests a direct neighbor's pushed avatar into the encrypted blob store, points the peer row at
     * it by [hash], deletes the decrypted staging copy, and garbage-collects the peer's previous blob.
     */
    private suspend fun onAvatarReceived(nodeId: String, hash: String, mime: String, srcPath: String) {
        val bytes = runCatching { File(srcPath).readBytes() }.getOrNull() ?: return
        // [hash] is the peer's claimed content address. Verify the bytes hash to it before storing, so a
        // neighbor can't push arbitrary bytes under another avatar's address (content-address spoofing).
        if (!isValidBlobHash(hash) || sha256Hex(bytes) != hash) {
            Log.w(TAG, "Dropping avatar from $nodeId: bytes do not match claimed hash")
            File(srcPath).delete()
            return
        }
        blobs.insert(hash, mime, bytes)
        File(srcPath).delete()
        advertisedAvatars.remove(nodeId) // pushed directly; no need to also pull it
        blobs.screenImage(hash, bytes)
        // Don't adopt an explicit avatar: leave the peer on its monogram fallback and drop the blob.
        if (blobs.isImageFlagged(hash)) {
            blobs.deleteIfUnreferenced(hash)
            return
        }
        val existing = peers.find(nodeId)
        val oldHash = existing?.avatarHash
        peers.upsert((existing ?: PeerEntity(nodeId)).copy(avatarHash = hash))
        if (oldHash != hash) blobs.deleteIfUnreferenced(oldHash)
    }

    /**
     * A blob just landed via [BlobExchange]. If it's an E2E attachment we hold the key for (stored when
     * the message was delivered, which is what triggered the pull), screen its *decrypted* bytes. The
     * screen in [MeshBlobStore.saveIncoming] only ever sees the stored ciphertext for an encrypted
     * attachment — it can't decode it — so this is where receive-side image moderation actually runs for
     * DM/group attachments. A no-op for avatars and for relayed blobs we have no key for.
     */
    private suspend fun screenObtainedAttachment(hash: String) {
        screenEncryptedAttachment(hash, messages.attachmentKeyForHash(hash))
    }

    /**
     * Decrypts the stored ciphertext blob for [hash] with its base64 [key] and screens the plaintext
     * image, caching the verdict under the ciphertext [hash] — the same key the chat UI's flagged set
     * uses, so a flagged attachment blurs behind a tap-to-reveal. A no-op when there's no [key] (a
     * plaintext/broadcast attachment, already screened on arrival in [MeshBlobStore.saveIncoming]), the
     * blob isn't stored yet, or decryption fails. [BlobRepository.screenImage] is itself gated on the
     * content-filtering setting and idempotent per hash, so a repeat call (or a prior ciphertext screen)
     * is harmless.
     */
    private suspend fun screenEncryptedAttachment(hash: String?, key: String?) {
        if (hash == null || key == null) return
        val cipher = blobs.bytes(hash) ?: return
        val plain = AttachmentCrypto.open(cipher, b64d(key)) ?: return
        blobs.screenImage(hash, plain)
    }

    /** Periodically logs a transmission snapshot so flood-suppression and byte savings are visible. */
    private fun logMetricsPeriodically(session: CoroutineScope) {
        session.launch {
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
        const val TEXT_MODERATION_TAG = "TextModeration"
        const val METRICS_LOG_INTERVAL_MS = 60_000L
    }
}
