package app.getknit.knit.notifications

import app.getknit.knit.data.message.ConversationKind
import app.getknit.knit.identity.displayNameFor

/**
 * One inbound message, resolved into the fields a MessagingStyle line needs. [conversationId] is the
 * thread it belongs to — used both to pick the channel and to suppress/clear notifications for the
 * conversation currently on screen. [avatarBytes] are the sender's avatar image bytes (read from the
 * encrypted blob store), or null for the letter fallback — notifications can't go through Coil, so the
 * raw bytes are carried and decoded directly.
 */
data class NotifMessage(
    val senderId: String,
    val senderName: String,
    val body: String,
    val sentAt: Long,
    val conversationId: String,
    val avatarBytes: ByteArray?,
) {
    // ByteArray needs content-based equals/hashCode (the generated reference comparison would make two
    // otherwise-identical messages unequal).
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NotifMessage) return false
        return senderId == other.senderId &&
            senderName == other.senderName &&
            body == other.body &&
            sentAt == other.sentAt &&
            conversationId == other.conversationId &&
            avatarBytes.contentEquals(other.avatarBytes)
    }

    override fun hashCode(): Int {
        var result = senderId.hashCode()
        result = 31 * result + senderName.hashCode()
        result = 31 * result + body.hashCode()
        result = 31 * result + sentAt.hashCode()
        result = 31 * result + conversationId.hashCode()
        result = 31 * result + (avatarBytes?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * One member of a photo-less group's cluster as the shade draws it (ADR 2026-09.zapp): the shade's twin of
 * `GroupFace`, carrying the member's raw avatar bytes (or null for their tinted initial) in place of a hash,
 * for the same reason [NotifMessage] does.
 */
data class NotifFace(
    val nodeId: String,
    val name: String,
    val avatarBytes: ByteArray?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NotifFace) return false
        return nodeId == other.nodeId &&
            name == other.name &&
            avatarBytes.contentEquals(other.avatarBytes)
    }

    override fun hashCode(): Int {
        var result = nodeId.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + (avatarBytes?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * Conversation-level context for a notification: the resolved thread [title] and its avatar (group photo
 * / DM peer avatar) shown as the notification's large icon, plus the [kind] used to pick the channel and
 * MessagingStyle shape. [title] is `null` when the caller has no dynamic name to offer — [MessageNotifier]
 * then substitutes a per-kind default (the Nearby room title, or an unnamed-group fallback). [avatarBytes]
 * are raw image bytes from the encrypted blob store (decoded directly, since notifications can't use Coil),
 * or null for the letter/glyph fallback. [faces] is the cluster a photo-less group draws instead — the
 * caller's `groupFaceIds` pick, already ordered, resolved to bytes; empty for anything else, and for a
 * group whose [avatarBytes] cover it.
 */
data class NotifConversation(
    val conversationId: String,
    val title: String?,
    val avatarBytes: ByteArray?,
    val kind: ConversationKind,
    val faces: List<NotifFace> = emptyList(),
) {
    // ByteArray needs content-based equals/hashCode (the generated reference comparison would make two
    // otherwise-identical conversations unequal). The faces delegate to NotifFace's, which does the same.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NotifConversation) return false
        return conversationId == other.conversationId &&
            title == other.title &&
            kind == other.kind &&
            avatarBytes.contentEquals(other.avatarBytes) &&
            faces == other.faces
    }

    override fun hashCode(): Int {
        var result = conversationId.hashCode()
        result = 31 * result + (title?.hashCode() ?: 0)
        result = 31 * result + kind.hashCode()
        result = 31 * result + (avatarBytes?.contentHashCode() ?: 0)
        result = 31 * result + faces.hashCode()
        return result
    }
}

/**
 * Posts "new message" notifications. Kept behind an interface so [app.getknit.knit.mesh.MeshManager]
 * stays free of Android notification APIs (and so the message-resolution logic can be unit-tested via
 * [incomingNotification] without Robolectric). The Android implementation is [MessageNotifier], which
 * posts one MessagingStyle notification per conversation, all stacked under a single group summary
 * ("N messages in M chats"), Signal-style.
 */
interface Notifier {
    /** Registers the notification channels + groups. Safe to call repeatedly; called once at startup. */
    fun createChannel()

    /** Records [incoming] and (re)posts the per-conversation notification on the channel for its kind. */
    fun notify(
        incoming: NotifMessage,
        conversation: NotifConversation,
        selfId: String,
        selfName: String,
        selfAvatarBytes: ByteArray?,
    )

    /**
     * Posts the heads-up for a direct file transfer someone is offering (`transfer/TransferManager`).
     *
     * Its own notification rather than a line in the thread's, and the reason is the icon: a MessagingStyle
     * notification stands for the whole conversation, so its status-bar icon cannot say anything about one
     * message in it. An offer wearing the transfer mark is worth more than an offer stacked with the chat —
     * and it is an event to answer rather than something said. The [notifyMention] precedent, one thread
     * carrying two kinds of notification.
     */
    fun notifyTransferOffer(
        peerId: String,
        peerName: String,
        peerAvatarBytes: ByteArray?,
        fileName: String,
        sizeBytes: Long?,
    )

    /** Posts a high-priority "you were mentioned" notification (separate Mentions entry for the thread). */
    fun notifyMention(
        incoming: NotifMessage,
        conversation: NotifConversation,
        selfId: String,
        selfName: String,
        selfAvatarBytes: ByteArray?,
    )

    /**
     * Echoes an inline reply the user sent from the notification (via the Reply action) into the
     * notification identified by [notificationTag], so it shows as sent — Signal-style. Passed the self
     * identity explicitly so it works even after a process restart (no reliance on cached state).
     */
    fun onReplied(
        notificationTag: String,
        text: String,
        selfId: String,
        selfName: String,
        selfAvatarBytes: ByteArray?,
    )

    /**
     * Records which conversation is on screen (null = none). Messages for the visible conversation are
     * not notified, and opening one clears its already-posted notification (the user is reading it).
     * Other conversations keep notifying normally.
     */
    fun setVisibleConversation(conversationId: String?)

    /** Clears the posted notification(s) for [conversationId] (its normal + mention entries) — Mark-read. */
    fun clearConversation(conversationId: String)

    /**
     * [clearConversation], and the thread's conversation shortcut goes too — the thread itself is gone (a
     * removed contact's DM, ADR 2026-09.adgd), so the launcher and the share sheet must stop offering it. A
     * shortcut the user pinned to the home screen stays; that one was theirs to place.
     */
    fun forgetConversation(conversationId: String)

    /** Drops the accumulated state for the dismissed [tag] only (notification swiped away). */
    fun onDismissed(tag: String)

    /**
     * Posts (or refreshes) the single coalesced "message request received" heads-up — a stranger's DM/group
     * that isn't yet accepted. Passed the current total [count] of pending request threads so a Sybil flood
     * collapses into one heads-up (alert-once) with an updated count; [count] `<= 0` cancels it.
     */
    fun notifyMessageRequests(count: Int)

    /**
     * Records whether the Message Requests inbox is on screen. While visible, the coalesced request
     * notification is suppressed and any already-posted one is cleared (the user is looking at the list) —
     * the requests-list analogue of [setVisibleConversation].
     */
    fun setRequestsVisible(visible: Boolean)

    /**
     * Posts the single "open to chat nearby" cue naming [names] (the caller's collision-aware labels, arrival
     * order) — a nudge to post in the Nearby room, so tapping it opens that room. [avatarBytes] is the lone
     * person's avatar for the single form; the multi form shows the room glyph. Every call is a real cue
     * (the policy in `presence/OpenToChatPolicy` already spaces them), so it alerts each time. Suppressed
     * while the Nearby room is on screen; an empty [names] cancels it.
     */
    fun notifyOpenToChat(
        names: List<String>,
        avatarBytes: ByteArray?,
    )

    /** Cancels the open-to-chat cue — the user switched their own flag off. */
    fun clearOpenToChat()
}

/** How many people the open-to-chat cue names before folding the rest into "and N others". */
const val OPEN_TO_CHAT_MAX_NAMED = 2

/**
 * The names the open-to-chat cue prints, and how many it folds into "and N others": the first [maxNamed]
 * verbatim, the remainder as a count. Pure so it is unit-tested without Android.
 */
fun openToChatNames(
    names: List<String>,
    maxNamed: Int = OPEN_TO_CHAT_MAX_NAMED,
): Pair<List<String>, Int> = names.take(maxNamed) to (names.size - maxNamed).coerceAtLeast(0)

/**
 * Total buffered messages and the number of distinct conversations with any, for the group summary line
 * ("N messages in M chats"). Pure so it is unit-tested without Android. Conversations with a zero count
 * are excluded (they contribute no message and shouldn't inflate the chat count).
 */
fun summaryCounts(perConversationCounts: Collection<Int>): Pair<Int, Int> {
    val messages = perConversationCounts.sum()
    val chats = perConversationCounts.count { it > 0 }
    return messages to chats
}

/**
 * Builds the notification payload for an inbound chat message, or `null` when it must not notify —
 * the message is our own ([senderId] == [selfId]) or has a blank body. [peerName] is the caller's
 * collision-aware label (`PeerLabel.text` — `Name (Alias)` when another known peer shares the name,
 * ADR 058) and passes through verbatim; when it is unknown or blank the sender name falls back to the
 * friendly alias derived from the node id, mirroring the chat UI's resolution.
 */
fun incomingNotification(
    senderId: String,
    body: String,
    sentAt: Long,
    selfId: String,
    peerName: String?,
    peerAvatarBytes: ByteArray?,
    conversationId: String,
): NotifMessage? {
    if (senderId == selfId) return null
    if (body.isBlank()) return null
    return NotifMessage(
        senderId = senderId,
        senderName = displayNameFor(peerName, senderId),
        body = body,
        sentAt = sentAt,
        conversationId = conversationId,
        avatarBytes = peerAvatarBytes,
    )
}

/**
 * Builds the payload for a "you were mentioned" notification, or `null` when it must not notify. The
 * null/alias rules are identical to [incomingNotification] (skip own messages and blank bodies); kept
 * as a distinct, named symbol so the mention path stays independently unit-testable.
 */
fun mentionNotification(
    senderId: String,
    body: String,
    sentAt: Long,
    selfId: String,
    peerName: String?,
    peerAvatarBytes: ByteArray?,
    conversationId: String,
): NotifMessage? = incomingNotification(senderId, body, sentAt, selfId, peerName, peerAvatarBytes, conversationId)
