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
        val hideSensitiveContent: Boolean,
        val group: GroupEntity?,
    )

    // Present + moderation-flagged blob hashes plus the content-filtering setting, combined upstream so
    // the main bundle stays at the typed 5-flow combine overload. The setting only gates receive-side
    // *hiding* (the chat blur + toxic-text collapse below), so toggling it reactively reveals/hides
    // already-received content without re-screening; what you can send is enforced elsewhere regardless.
    private val blobState = combine(
        blobs.observeHashes(),
        blobs.observeFlaggedHashes(),
        settings.contentFilteringEnabled,
    ) { present, flagged, hideSensitive ->
        Triple(present.toSet(), flagged.toSet(), hideSensitive)
    }

    private val messagesWithReactions = combine(
        messages.observeMessages(conversationId),
        reactions.observeReactions(),
        settings.blockedNodeIds,
        blobState,
        groups.observeGroup(conversationId),
    ) { msgs, reacts, blocked, (present, flagged, hideSensitive), group ->
        MessagesBundle(
            msgs.filter { it.senderId !in blocked }, reacts, blocked, present, flagged, hideSensitive, group,
        )
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
        val hideSensitive = bundle.hideSensitiveContent
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
                moderationFlagged = hideSensitive && m.moderation == MessageEntity.MODERATION_TEXT_FLAGGED,
                attachmentHash = m.attachmentHash,
                attachmentMime = m.attachmentMime,
                attachmentKey = m.attachmentKey,
                attachmentReady = m.attachmentHash != null && m.attachmentHash in presentHashes,
                attachmentFlagged = hideSensitive && m.attachmentHash != null && m.attachmentHash in flaggedHashes,
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
            // The room uses a glyph; a group shows its photo (or the glyph when unset); a DM the peer avatar.
            avatarHash = when {
                isRoom -> null
                else -> group?.photoHash ?: peersByNode[conversationId]?.avatarHash
            },
            isBlocked = !isRoom && !isGroup && conversationId in blocked,
            verified = !isRoom && !isGroup && peersByNode[conversationId]?.verified == true,
            isGroup = isGroup,
            memberCount = members.size,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState(isRoom = isRoom))

    /**
     * Double-submit guard: true from the moment a send is accepted until its input is cleared (success)
     * or it's rejected (blocked). [send] is a suspending round-trip (seal-to-recipients + DB write +
     * enqueue), and the input isn't cleared until it returns, so without this a rapid burst of taps on
     * the always-enabled send button would each read the same still-present draft and flood duplicates.
     * Main-thread-confined: touched only from [send] and [onInputCleared], both on the main dispatcher.
     */
    private var sending = false

    fun send(text: String, mentions: List<Mention> = emptyList()) {
        val trimmed = text.trim().take(TextLimits.MESSAGE)
        val attachment = _pendingAttachment.value
        if (trimmed.isEmpty() && attachment == null) return
        // Ignore re-entrant taps while a send is in flight, and — on success — until the field is
        // actually cleared, so a tap landing in the gap between sendChat returning and clearText running
        // can't re-send the same draft. Released in the blocked branch and in onInputCleared().
        if (sending) return
        sending = true
        viewModelScope.launch {
            // Deferred release: an accepted send keeps the guard held until the field is actually
            // cleared (onInputCleared); the finally frees it on a block or an unexpected send-path throw
            // so the guard can never stick and freeze the input.
            var accepted = false
            try {
                // Re-read the group at send time so it's never misrouted as a DM in a startup race, and so
                // a pending rename rides this message (its GroupInfo.name converges last-writer-wins).
                val group = if (isRoom) null else groups.find(conversationId)
                val sent = if (group != null) {
                    meshManager.sendChat(trimmed, attachment, mentions, recipientId = null, group = group.toInfo())
                } else {
                    // Broadcast room -> no recipient; a DM thread is keyed by the peer's node id.
                    val recipientId = if (isRoom) null else conversationId
                    meshManager.sendChat(trimmed, attachment, mentions, recipientId)
                }
                // MeshManager applies block-on-send. Clear the input/attachment only once a message is
                // accepted; a blocked message keeps the draft and surfaces a toast so the user can edit.
                if (sent) {
                    accepted = true
                    _pendingAttachment.value = null
                    // Guard stays held until the screen reports the field cleared (onInputCleared), so no
                    // duplicate can slip through the tryEmit -> collect -> clearText hop.
                    _clearInput.tryEmit(Unit)
                } else {
                    _events.tryEmit(R.string.moderation_text_blocked)
                }
            } finally {
                if (!accepted) sending = false
            }
        }
    }

    /**
     * The screen finished clearing the input after an accepted send; release the double-submit guard.
     * Deferred to here (rather than the success branch above) so the guard covers the window between
     * [send] returning and the field visually clearing — see [sending].
     */
    fun onInputCleared() {
        sending = false
    }

    /**
     * The self-describing [GroupInfo] flooded on every group frame, built from the local row so each
     * message/update re-asserts the current name **and** photo (both converge last-writer-wins, by their
     * own clocks). Centralized so the chat-send, rename, and set-photo paths can't drift.
     */
    private fun GroupEntity.toInfo() = GroupInfo(
        id = groupId,
        // Only a renamed group carries a shared name; unnamed groups stay locally-titled.
        name = name.takeIf { it.isNotBlank() },
        members = GroupMembersStore.decode(members),
        createdBy = createdBy,
        photoHash = photoHash,
        photoUpdatedAt = photoUpdatedAt.takeIf { it > 0L },
    )

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
