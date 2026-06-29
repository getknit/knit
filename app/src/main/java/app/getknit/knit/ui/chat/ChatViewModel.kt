package app.getknit.knit.ui.chat

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.getknit.knit.R
import app.getknit.knit.TextLimits
import app.getknit.knit.data.AttachmentStore
import app.getknit.knit.data.BlobRepository
import app.getknit.knit.data.GallerySaver
import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.ReactionRepository
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.group.GroupMembersStore
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.MentionStore
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.message.groupTitle
import app.getknit.knit.data.reaction.ReactionEntity
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.identity.displayNameFor
import app.getknit.knit.mesh.MeshManager
import app.getknit.knit.mesh.protocol.GroupInfo
import app.getknit.knit.mesh.protocol.Mention
import app.getknit.knit.normalizeSingleLine
import app.getknit.knit.notifications.Notifier
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ChatRow(
    val id: String,
    val body: String,
    val mine: Boolean,
    val senderName: String,
    val senderNodeId: String,
    // A non-[MessageEntity.KIND_NORMAL] row is a status notice (e.g. [MessageEntity.KIND_MEMBER_LEFT]),
    // rendered as a centered line using [senderName] instead of a chat bubble.
    val kind: Int = MessageEntity.KIND_NORMAL,
    val avatarHash: String?,
    val sentAt: Long,
    val received: Boolean,
    // True when the on-device text moderator flagged this message's body; the bubble collapses it
    // behind a tap-to-reveal instead of showing the text outright.
    val moderationFlagged: Boolean = false,
    val attachmentHash: String? = null,
    val attachmentMime: String? = null,
    // Base64 key for an end-to-end-encrypted attachment (null for plaintext/broadcast attachments);
    // passed to the image loader to decrypt the ciphertext blob before decoding.
    val attachmentKey: String? = null,
    // True once the attachment blob is present locally; false while it's still being pulled (the bubble
    // shows a loading placeholder). Only meaningful when [attachmentHash] is non-null.
    val attachmentReady: Boolean = false,
    // True when on-device screening flagged the attachment as explicit; the bubble blurs it behind a
    // tap-to-view. Only meaningful when [attachmentHash] is non-null.
    val attachmentFlagged: Boolean = false,
    val mentions: List<Mention> = emptyList(),
    val reactions: List<ReactionSummary> = emptyList(),
)

/**
 * One emoji's tally on a message: the [emoji], how many people reacted with it ([count]), and whether
 * the local user is one of them ([mine], to highlight the chip). Distinct emoji become distinct chips;
 * the UI shows the count only when it exceeds 1.
 */
data class ReactionSummary(
    val emoji: String,
    val count: Int,
    val mine: Boolean,
)

/** A person who can be "@"-mentioned: someone we've received a message from, resolved to a name. */
data class MentionCandidate(
    val nodeId: String,
    val displayName: String,
    val avatarHash: String?,
)

data class ChatUiState(
    val rows: List<ChatRow> = emptyList(),
    val neighborCount: Int = 0,
    val myNodeId: String = "",
    val mentionCandidates: List<MentionCandidate> = emptyList(),
    // Conversation header: the room ([isRoom] true) or a 1:1 DM with [title]/[avatarHash] of the peer.
    val isRoom: Boolean = true,
    val title: String = "",
    val avatarHash: String? = null,
    // True when this DM's peer is blocked, so the header offers "Unblock" instead of "Block".
    val isBlocked: Boolean = false,
    // True when this DM's peer has been key-verified (safety number / QR), to show a verified badge.
    val verified: Boolean = false,
    // True when this thread is a group chat; [memberCount] sizes the header subtitle. The header then
    // offers "Rename group" / "Leave group" instead of Block/Unblock.
    val isGroup: Boolean = false,
    val memberCount: Int = 0,
)

class ChatViewModel(
    private val conversationId: String,
    private val messages: MessageRepository,
    private val groups: GroupRepository,
    private val peers: PeerRepository,
    private val reactions: ReactionRepository,
    private val meshManager: MeshManager,
    private val identity: Identity,
    private val settings: SettingsStore,
    private val notifier: Notifier,
    private val attachments: AttachmentStore,
    private val blobs: BlobRepository,
    private val gallerySaver: GallerySaver,
    private val context: Context,
) : ViewModel() {

    /** This thread is the broadcast room (vs a 1:1 DM keyed by the peer's node id). */
    private val isRoom = conversationId == Conversations.NEARBY

    private val myNodeId = MutableStateFlow<String?>(null)

    /** True while the chat is on screen; drives the read watermark below. */
    private val chatForeground = MutableStateFlow(false)

    /** Image staged in the input bar, ready to send with the next message (null when none). */
    private val _pendingAttachment = MutableStateFlow<AttachmentStore.Ingested?>(null)
    val pendingAttachment: StateFlow<AttachmentStore.Ingested?> = _pendingAttachment.asStateFlow()

    /**
     * An image flagged as explicit by on-device screening, awaiting the user's "send anyway?"
     * confirmation. Sending such images is allowed but discouraged: it's staged only once confirmed.
     */
    private val _confirmAttachment = MutableStateFlow<AttachmentStore.Ingested?>(null)
    val confirmAttachment: StateFlow<AttachmentStore.Ingested?> = _confirmAttachment.asStateFlow()

    /** One-shot UI messages (a string res id), surfaced as toasts — e.g. the result of saving an image. */
    private val _events = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val events: SharedFlow<Int> = _events.asSharedFlow()

    /** Emitted once the DM's peer is blocked, so the screen can close (the thread is now hidden). */
    private val _closeChat = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val closeChat: SharedFlow<Unit> = _closeChat.asSharedFlow()

    /**
     * Emitted after a message is accepted and sent, so the screen clears its input field/mentions. The
     * screen no longer clears optimistically: if [send] blocks the text for abuse, nothing is emitted and
     * the user keeps their draft to edit.
     */
    private val _clearInput = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val clearInput: SharedFlow<Unit> = _clearInput.asSharedFlow()

    init {
        viewModelScope.launch { myNodeId.value = identity.nodeId() }
        // Advance this conversation's read watermark while the chat is on screen: on every stream
        // emission (so messages arriving while you read don't reappear as unread), stamp newest sentAt.
        viewModelScope.launch {
            combine(chatForeground, messages.observeMessages(conversationId)) { foreground, msgs ->
                if (foreground) msgs.maxOfOrNull { it.sentAt } else null
            }.distinctUntilChanged().collect { watermark ->
                if (watermark != null) settings.setLastReadAt(conversationId, watermark)
            }
        }
    }

    // Bundles the four message-related streams so the outer combine below stays at the 5-flow typed
    // overload (a 6th flow falls back to unchecked Array<*> casts). Blocked senders' messages are
    // filtered out here, so they also drop out of rows and mention candidates. Observing the blob
    // hashes here is what flips an attachment from "loading" to shown when its bytes arrive.
    private data class MessagesBundle(
        val messages: List<MessageEntity>,
        val reactions: List<ReactionEntity>,
        val blocked: Set<String>,
        val presentHashes: Set<String>,
        val flaggedHashes: Set<String>,
        val group: GroupEntity?,
    )

    // Present + moderation-flagged blob hashes, combined upstream so the main bundle stays at the typed
    // 5-flow combine overload.
    private val blobState = combine(blobs.observeHashes(), blobs.observeFlaggedHashes()) { present, flagged ->
        present.toSet() to flagged.toSet()
    }

    private val messagesWithReactions = combine(
        messages.observeMessages(conversationId),
        reactions.observeReactions(),
        settings.blockedNodeIds,
        blobState,
        groups.observeGroup(conversationId),
    ) { msgs, reacts, blocked, (present, flagged), group ->
        MessagesBundle(msgs.filter { it.senderId !in blocked }, reacts, blocked, present, flagged, group)
    }

    val state: StateFlow<ChatUiState> = combine(
        messagesWithReactions,
        peers.observePeers(),
        meshManager.neighborCount,
        myNodeId,
        settings.displayName,
    ) { bundle, peerList, count, me, myName ->
        val msgs = bundle.messages
        val reacts = bundle.reactions
        val blocked = bundle.blocked
        val presentHashes = bundle.presentHashes
        val flaggedHashes = bundle.flaggedHashes
        val group = bundle.group
        val isGroup = group != null
        val members = group?.let { GroupMembersStore.decode(it.members) }.orEmpty()
        val peersByNode = peerList.associateBy { it.nodeId }
        // Group once, then tally per emoji within each message's bucket. Orphan reactions (no matching
        // message yet) simply never produce a row until their message arrives.
        val reactionsByMessage = reacts.groupBy { it.messageId }
        val rows = msgs.map { m ->
            val mine = m.senderId == me
            val name = when {
                mine -> myName.ifBlank { context.getString(R.string.chat_self_name) }
                else -> displayNameFor(peersByNode[m.senderId]?.name, m.senderId)
            }
            val tallies = reactionsByMessage[m.id].orEmpty()
                .groupBy { it.emoji }
                .mapNotNull { (emoji, group) ->
                    // emoji is non-null in the stream (tombstones are filtered in the DAO); guard anyway.
                    if (emoji == null) null
                    else ReactionSummary(emoji, group.size, group.any { it.reactorNodeId == me })
                }
            ChatRow(
                id = m.id,
                body = m.body,
                mine = mine,
                senderName = name,
                senderNodeId = m.senderId,
                kind = m.kind,
                avatarHash = peersByNode[m.senderId]?.avatarHash,
                sentAt = m.sentAt,
                received = m.received,
                moderationFlagged = m.moderation == MessageEntity.MODERATION_TEXT_FLAGGED,
                attachmentHash = m.attachmentHash,
                attachmentMime = m.attachmentMime,
                attachmentKey = m.attachmentKey,
                attachmentReady = m.attachmentHash != null && m.attachmentHash in presentHashes,
                attachmentFlagged = m.attachmentHash != null && m.attachmentHash in flaggedHashes,
                mentions = MentionStore.decode(m.mentions),
                reactions = tallies,
            )
        }
        // Autocomplete candidates: everyone we've received a message from, plus a group's roster (so
        // @-mentions work in a fresh group before anyone has spoken), resolved to a display name.
        val candidates = (msgs.map { it.senderId } + members).asSequence()
            .filter { it != me }
            .distinct()
            .map { id ->
                MentionCandidate(
                    nodeId = id,
                    displayName = displayNameFor(peersByNode[id]?.name, id),
                    avatarHash = peersByNode[id]?.avatarHash,
                )
            }
            .sortedBy { it.displayName.lowercase() }
            .toList()
        ChatUiState(
            rows = rows,
            neighborCount = count,
            myNodeId = me.orEmpty(),
            mentionCandidates = candidates,
            isRoom = isRoom,
            title = when {
                group != null -> groupTitle(
                    storedName = group.name,
                    memberIds = members,
                    selfId = me,
                    fallback = context.getString(R.string.group_unnamed),
                ) { id -> displayNameFor(peersByNode[id]?.name, id) }
                isRoom -> context.getString(R.string.nearby_title)
                else -> displayNameFor(peersByNode[conversationId]?.name, conversationId)
            },
            // Groups and the room use a glyph, not a peer avatar.
            avatarHash = if (isRoom || isGroup) null else peersByNode[conversationId]?.avatarHash,
            isBlocked = !isRoom && !isGroup && conversationId in blocked,
            verified = !isRoom && !isGroup && peersByNode[conversationId]?.verified == true,
            isGroup = isGroup,
            memberCount = members.size,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState(isRoom = isRoom))

    fun send(text: String, mentions: List<Mention> = emptyList()) {
        val trimmed = text.trim().take(TextLimits.MESSAGE)
        val attachment = _pendingAttachment.value
        if (trimmed.isEmpty() && attachment == null) return
        viewModelScope.launch {
            // Re-read the group at send time so it's never misrouted as a DM in a startup race, and so a
            // pending rename rides this message (its GroupInfo.name converges last-writer-wins).
            val group = if (isRoom) null else groups.find(conversationId)
            val sent = if (group != null) {
                val info = GroupInfo(
                    id = group.groupId,
                    // Only a renamed group carries a shared name; unnamed groups stay locally-titled.
                    name = group.name.takeIf { it.isNotBlank() },
                    members = GroupMembersStore.decode(group.members),
                    createdBy = group.createdBy,
                )
                meshManager.sendChat(trimmed, attachment, mentions, recipientId = null, group = info)
            } else {
                // Broadcast room -> no recipient; a DM thread is keyed by the peer's node id.
                val recipientId = if (isRoom) null else conversationId
                meshManager.sendChat(trimmed, attachment, mentions, recipientId)
            }
            // MeshManager applies block-on-send. Clear the input/attachment only once a message is
            // accepted; a blocked message keeps the draft and surfaces a toast so the user can edit.
            if (sent) {
                _pendingAttachment.value = null
                _clearInput.tryEmit(Unit)
            } else {
                _events.tryEmit(R.string.moderation_text_blocked)
            }
        }
    }

    /**
     * Renames this group: updates the local store immediately and floods a [GroupUpdateFrame] so members
     * converge right away (no waiting for the next message). The name is last-writer-wins by timestamp.
     */
    fun renameGroup(newName: String) {
        val trimmed = normalizeSingleLine(newName).take(TextLimits.GROUP_NAME)
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val group = groups.find(conversationId) ?: return@launch
            val updated = group.copy(name = trimmed, nameUpdatedAt = System.currentTimeMillis())
            groups.upsert(updated)
            meshManager.sendGroupUpdate(
                GroupInfo(
                    id = updated.groupId,
                    name = updated.name,
                    members = GroupMembersStore.decode(updated.members),
                    createdBy = updated.createdBy,
                ),
            )
        }
    }

    /**
     * Leaves this group: tombstones it (so its frames stop being delivered and it can't be resurrected)
     * and deletes its messages, then closes the screen. Emitted only after the leave persists.
     */
    fun leaveGroup() {
        viewModelScope.launch {
            // Tell the other members first (we're still a member, our key is known), then tombstone and
            // wipe locally. The leave frame floods once; it's not custodied, so an offline member learns
            // of the smaller roster from the next group message rather than this frame.
            meshManager.sendGroupLeave(conversationId)
            groups.leave(conversationId)
            _closeChat.tryEmit(Unit)
        }
    }

    /** Toggles the local user's [emoji] reaction on [messageId] (add / replace / remove) and floods it. */
    fun react(messageId: String, emoji: String) {
        viewModelScope.launch { meshManager.sendReaction(messageId, emoji) }
    }

    /**
     * Removes [messageId] from this device only — its row, its reactions, and (if no other message
     * still references it) its content-addressed attachment blob. Sends nothing over the mesh.
     */
    fun deleteMessage(messageId: String) {
        val hash = state.value.rows.firstOrNull { it.id == messageId }?.attachmentHash
        viewModelScope.launch {
            messages.delete(messageId)
            reactions.deleteForMessage(messageId)
            blobs.deleteIfUnreferenced(hash)
            _events.tryEmit(R.string.chat_message_deleted)
        }
    }

    /** Blocks [nodeId] locally: their messages/reactions stop being stored, shown, and notified. */
    fun block(nodeId: String) {
        viewModelScope.launch {
            settings.block(nodeId, peers.find(nodeId)?.deviceTag)
            _events.tryEmit(R.string.chat_user_blocked)
            // Blocking the peer of a DM empties this thread (and hides it from the list), so close the
            // now-confusing screen. Emitted only after the block persists, so navigating away can't
            // cancel the write. Blocking from the Nearby room leaves the room open.
            if (!isRoom) _closeChat.tryEmit(Unit)
        }
    }

    /** Unblocks [nodeId], restoring their (never-deleted) message history. */
    fun unblock(nodeId: String) {
        viewModelScope.launch {
            settings.unblock(nodeId, peers.find(nodeId)?.deviceTag)
            _events.tryEmit(R.string.chat_user_unblocked)
        }
    }

    /**
     * Ingests a picked or keyboard-inserted image and stages it in the input bar. A picture flagged as
     * explicit by on-device screening is handled by context: the public Nearby room **blocks** it
     * outright (no confirmation bypass), while DMs/groups route it to [confirmAttachment] for a
     * "send anyway?" confirmation. A decode failure is silently ignored, as before.
     */
    fun attach(uri: Uri) {
        viewModelScope.launch {
            when (val result = attachments.ingest(uri)) {
                is AttachmentStore.IngestResult.Success -> when {
                    !result.flagged -> _pendingAttachment.value = result.ingested
                    isRoom -> {
                        // Hard block in the broadcast room; drop the ingested-but-unsent blob.
                        blobs.deleteIfUnreferenced(result.ingested.hash)
                        _events.tryEmit(R.string.moderation_image_blocked)
                    }
                    else -> _confirmAttachment.value = result.ingested
                }
                AttachmentStore.IngestResult.Failed -> Unit
            }
        }
    }

    /** The user confirmed the explicit-image warning: stage the (already-ingested) image for sending. */
    fun confirmFlaggedAttachment() {
        _pendingAttachment.value = _confirmAttachment.value ?: return
        _confirmAttachment.value = null
    }

    /** The user declined the explicit-image warning: drop it and GC the ingested-but-unsent blob. */
    fun dismissFlaggedAttachment() {
        val pending = _confirmAttachment.value ?: return
        _confirmAttachment.value = null
        viewModelScope.launch { blobs.deleteIfUnreferenced(pending.hash) }
    }

    /** Discards the staged image; its blob (ingested on pick) is GC'd unless a sent message references it. */
    fun clearAttachment() {
        val pending = _pendingAttachment.value ?: return
        _pendingAttachment.value = null
        viewModelScope.launch { blobs.deleteIfUnreferenced(pending.hash) }
    }

    /** Exports the attachment blob [hash] to the public `Pictures/Knit` folder and toasts the result. */
    fun saveAttachment(hash: String) {
        viewModelScope.launch {
            val bytes = blobs.bytes(hash)
            val mime = blobs.mimeFor(hash)
            val ok = bytes != null && mime != null && gallerySaver.saveToPictures(bytes, hash, mime)
            _events.tryEmit(if (ok) R.string.chat_image_saved else R.string.chat_image_save_failed)
        }
    }

    /** A message's text was copied to the clipboard; surface the confirmation toast. */
    fun onMessageCopied() {
        _events.tryEmit(R.string.chat_message_copied)
    }

    /** Chat is on screen: suppress this conversation's notifications and clear any active one (the user is reading). */
    fun onChatForeground() {
        chatForeground.value = true
        notifier.setVisibleConversation(conversationId)
    }

    /** Chat left the screen: resume notifying for this conversation's incoming messages. */
    fun onChatBackground() {
        chatForeground.value = false
        notifier.setVisibleConversation(null)
    }
}
