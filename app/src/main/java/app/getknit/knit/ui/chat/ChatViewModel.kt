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
import app.getknit.knit.data.message.replyRef
import app.getknit.knit.data.reaction.ReactionEntity
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.identity.displayNameFor
import app.getknit.knit.mesh.MeshManager
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.mesh.protocol.GroupInfo
import app.getknit.knit.mesh.protocol.Mention
import app.getknit.knit.mesh.protocol.ReplyRef
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
import kotlinx.coroutines.flow.first
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
    // The message this row quotes (Signal-style reply), or null when it isn't a reply. Denormalized so the
    // quote renders even if the quoted original isn't in this thread. See [MessageEntity.replyRef].
    val replyTo: ReplyRef? = null,
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

/** A peer currently shown as "typing" in this thread, resolved to a display [name] + [avatarHash] for the
 *  animated indicator row. */
data class TypingPeer(
    val nodeId: String,
    val name: String,
    val avatarHash: String?,
)

data class ChatUiState(
    val rows: List<ChatRow> = emptyList(),
    val neighborCount: Int = 0,
    // Radio health, so the connection header can distinguish "nobody nearby" from radios off/seized.
    val transportHealth: TransportHealth = TransportHealth.Healthy,
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
    // Peers currently typing in this thread, shown as an animated indicator above the input. Ephemeral
    // (TTL'd in the mesh layer) and best-effort; empty most of the time.
    val typingPeers: List<TypingPeer> = emptyList(),
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
    private val blobState =
        combine(
            blobs.observeHashes(),
            blobs.observeFlaggedHashes(),
            settings.contentFilteringEnabled,
        ) { present, flagged, hideSensitive ->
            Triple(present.toSet(), flagged.toSet(), hideSensitive)
        }

    private val messagesWithReactions =
        combine(
            messages.observeMessages(conversationId),
            reactions.observeReactions(),
            settings.blockedNodeIds,
            blobState,
            groups.observeGroup(conversationId),
        ) { msgs, reacts, blocked, (present, flagged, hideSensitive), group ->
            MessagesBundle(
                msgs.filter { it.senderId !in blocked },
                reacts,
                blocked,
                present,
                flagged,
                hideSensitive,
                group,
            )
        }

    // Neighbor count + radio health + the "who's typing" map folded into one source so the main state
    // combine stays within its five-flow arity.
    private data class MeshStatus(
        val neighborCount: Int,
        val transportHealth: TransportHealth,
        val typing: Map<String, Set<String>>,
    )

    private val meshStatus =
        combine(
            meshManager.neighborCount,
            meshManager.transportHealth,
            meshManager.typing,
        ) { count, health, typing -> MeshStatus(count, health, typing) }

    val state: StateFlow<ChatUiState> =
        combine(
            messagesWithReactions,
            peers.observePeers(),
            meshStatus,
            myNodeId,
            settings.displayName,
        ) { bundle, peerList, (count, health, typingMap), me, myName ->
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
            val rows =
                msgs.map { m ->
                    val mine = m.senderId == me
                    val name =
                        when {
                            mine -> myName.ifBlank { context.getString(R.string.chat_self_name) }
                            else -> displayNameFor(peersByNode[m.senderId]?.name, m.senderId)
                        }
                    val tallies =
                        reactionsByMessage[m.id]
                            .orEmpty()
                            .groupBy { it.emoji }
                            .mapNotNull { (emoji, group) ->
                                // emoji is non-null in the stream (tombstones are filtered in the DAO); guard anyway.
                                if (emoji == null) {
                                    null
                                } else {
                                    ReactionSummary(emoji, group.size, group.any { it.reactorNodeId == me })
                                }
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
                        replyTo = m.replyRef(),
                    )
                }
            // Autocomplete candidates: everyone we've received a message from, plus a group's roster (so
            // @-mentions work in a fresh group before anyone has spoken), resolved to a display name.
            val candidates =
                (msgs.map { it.senderId } + members)
                    .asSequence()
                    .filter { it != me }
                    .distinct()
                    .map { id ->
                        MentionCandidate(
                            nodeId = id,
                            displayName = displayNameFor(peersByNode[id]?.name, id),
                            avatarHash = peersByNode[id]?.avatarHash,
                        )
                    }.sortedBy { it.displayName.lowercase() }
                    .toList()
            // Peers typing in THIS thread, resolved for the indicator row. Skip ourselves (defensive — our
            // own cue never lands here) and blocked senders (as their messages are already filtered out).
            val typingPeers =
                typingMap[conversationId]
                    .orEmpty()
                    .asSequence()
                    .filter { it != me && it !in blocked }
                    .map { id -> TypingPeer(id, displayNameFor(peersByNode[id]?.name, id), peersByNode[id]?.avatarHash) }
                    .sortedBy { it.name.lowercase() }
                    .toList()
            ChatUiState(
                rows = rows,
                neighborCount = count,
                transportHealth = health,
                myNodeId = me.orEmpty(),
                mentionCandidates = candidates,
                isRoom = isRoom,
                title =
                    when {
                        group != null -> {
                            groupTitle(
                                storedName = group.name,
                                memberIds = members,
                                selfId = me,
                                fallback = context.getString(R.string.group_unnamed),
                            ) { id -> displayNameFor(peersByNode[id]?.name, id) }
                        }

                        isRoom -> {
                            context.getString(R.string.nearby_title)
                        }

                        else -> {
                            displayNameFor(peersByNode[conversationId]?.name, conversationId)
                        }
                    },
                // The room uses a glyph; a group shows its photo (or the glyph when unset); a DM the peer avatar.
                avatarHash =
                    when {
                        isRoom -> null
                        else -> group?.photoHash ?: peersByNode[conversationId]?.avatarHash
                    },
                isBlocked = !isRoom && !isGroup && conversationId in blocked,
                verified = !isRoom && !isGroup && peersByNode[conversationId]?.verified == true,
                isGroup = isGroup,
                memberCount = members.size,
                typingPeers = typingPeers,
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

    fun send(
        text: String,
        mentions: List<Mention> = emptyList(),
        replyTo: ReplyRef? = null,
    ) {
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
                // Normalize a self-quote's snapshotted author before it goes on the wire (see the helper).
                val outgoingReply = normalizeSelfAuthor(replyTo)
                // Re-read the group at send time so it's never misrouted as a DM in a startup race, and so
                // a pending rename rides this message (its GroupInfo.name converges last-writer-wins).
                val group = if (isRoom) null else groups.find(conversationId)
                val sent =
                    if (group != null) {
                        meshManager.sendChat(
                            trimmed,
                            attachment,
                            mentions,
                            recipientId = null,
                            group = group.toInfo(),
                            replyTo = outgoingReply,
                        )
                    } else {
                        // Broadcast room -> no recipient; a DM thread is keyed by the peer's node id.
                        val recipientId = if (isRoom) null else conversationId
                        meshManager.sendChat(trimmed, attachment, mentions, recipientId, replyTo = outgoingReply)
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
     * Normalizes a quoted-reply's author snapshot before it goes on the wire: a reply quoting *our own*
     * message must carry the display name a peer resolves for us — never the local "You" self-label — so
     * every recipient shows our real name and only swaps in "You" when they are themselves the quoted
     * author. A reply to anyone else is returned unchanged (its snapshot is already a peer-resolved name).
     */
    private suspend fun normalizeSelfAuthor(replyTo: ReplyRef?): ReplyRef? {
        val me = identity.nodeId()
        return replyTo
            ?.takeIf { it.authorId == me }
            ?.copy(author = displayNameFor(settings.displayName.first(), me))
            ?: replyTo
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
    private fun GroupEntity.toInfo() =
        GroupInfo(
            id = groupId,
            // Only a renamed group carries a shared name; unnamed groups stay locally-titled.
            name = name.takeIf { it.isNotBlank() },
            members = GroupMembersStore.decode(members),
            createdBy = createdBy,
            photoHash = photoHash,
            photoUpdatedAt = photoUpdatedAt.takeIf { it > 0L },
        )

    /** Toggles the local user's [emoji] reaction on [messageId] (add / replace / remove) and floods it. */
    fun react(
        messageId: String,
        emoji: String,
    ) {
        viewModelScope.launch { meshManager.sendReaction(messageId, emoji) }
    }

    /**
     * Removes [messageId] from this device only — its row, its reactions, and (if no other message
     * still references it) its content-addressed attachment blob. Sends nothing over the mesh.
     */
    fun deleteMessage(messageId: String) {
        val hash =
            state.value.rows
                .firstOrNull { it.id == messageId }
                ?.attachmentHash
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
                is AttachmentStore.IngestResult.Success -> {
                    when {
                        !result.flagged -> {
                            _pendingAttachment.value = result.ingested
                        }

                        isRoom -> {
                            // Hard block in the broadcast room; drop the ingested-but-unsent blob.
                            blobs.deleteIfUnreferenced(result.ingested.hash)
                            _events.tryEmit(R.string.moderation_image_blocked)
                        }

                        else -> {
                            _confirmAttachment.value = result.ingested
                        }
                    }
                }

                AttachmentStore.IngestResult.Failed -> {
                    Unit
                }
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

    // Wall clock of the last typing cue we sent, so we throttle to at most one per TYPING_SEND_INTERVAL_MS
    // while the user edits (see onUserTyping). Main-thread-confined (the screen's snapshotFlow collector).
    private var lastTypingSentAt = 0L

    /**
     * The user changed the (non-empty) draft: emit a best-effort "now typing" cue, throttled to at most one per
     * [TYPING_SEND_INTERVAL_MS] and only while the chat is foregrounded. Fires immediately on the first keystroke
     * after an idle gap (the throttle window has elapsed), so the indicator appears promptly on the other side.
     * Cheap and fire-and-forget — the screen may call this on every keystroke.
     */
    fun onUserTyping() {
        val now = System.currentTimeMillis()
        if (!chatForeground.value || now - lastTypingSentAt < TYPING_SEND_INTERVAL_MS) return
        lastTypingSentAt = now
        viewModelScope.launch { meshManager.sendTyping(conversationId) }
    }

    private companion object {
        /** Send a typing cue at most this often while actively editing (< the receiver's ~12 s hold, so a peer
         *  who keeps typing re-cues before their indicator would expire). */
        const val TYPING_SEND_INTERVAL_MS = 8_000L
    }
}
