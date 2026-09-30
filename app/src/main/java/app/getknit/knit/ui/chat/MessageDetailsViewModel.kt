package app.getknit.knit.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.MessageReceiptRepository
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerDirectory
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.ReactionRepository
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.group.GroupMembersStore
import app.getknit.knit.data.message.ConversationKind
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.message.receivedPlane
import app.getknit.knit.data.receipt.MessageReceiptEntity
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One person's reaction to the message, resolved for display: who they are ([displayName], falling back
 * to a stable alias when we hold no profile for them), what they left ([emoji]), and when ([reactedAt],
 * the reacting device's clock as stored in `reactions.updatedAt`).
 */
data class ReactorRow(
    val nodeId: String,
    val displayName: String,
    val avatarHash: String?,
    val emoji: String,
    val reactedAt: Long,
    val isSelf: Boolean,
    // The ` (Alias)` suffix already inside [displayName] when another known peer shares the name (ADR 058).
    val discriminator: String? = null,
)

/** The stored row, carrying whether this screen has ever seen it — see [MessageDetailsUiState.vanished]. */
private data class MessageSighting(
    val row: MessageEntity? = null,
    val everSeen: Boolean = false,
)

/**
 * One person a message we sent was addressed to, resolved for display. [deliveredAt] is our own clock when
 * their delivery receipt reached us (see [MessageReceiptEntity]) — null while we are still waiting on them.
 */
data class RecipientRow(
    val nodeId: String,
    val displayName: String,
    val avatarHash: String?,
    val deliveredAt: Long?,
    // The ` (Alias)` suffix already inside [displayName] when another known peer shares the name (ADR 058).
    val discriminator: String? = null,
)

/** One selectable emoji on the details screen's filter row — the chip's emoji and how many left it. */
data class ReactionFilter(
    val emoji: String,
    val count: Int,
)

/**
 * The message as shown on its details screen.
 *
 * [vanished] means the row was here and is now gone (deleted from the chat behind us, or reaped by
 * retention) — the screen's cue to close. It is deliberately **not** "the query returned nothing": a
 * deep link that lands before the row is written reads null once and would slam the screen shut on
 * arrival, which is exactly what a `demo_route` into a still-seeding build does.
 */
data class MessageDetailsUiState(
    val messageId: String,
    val vanished: Boolean = false,
    val body: String = "",
    val hasAttachment: Boolean = false,
    // The attachment's MIME and (for a file) the name and byte count the sender gave it, so the body line
    // can name it rather than calling every attachment a photo. Held as the raw fields rather than a
    // rendered string because the screen has the `Context` and this class deliberately does not.
    val attachmentMime: String? = null,
    val attachmentName: String? = null,
    val attachmentSize: Long? = null,
    // More than one frame: the body line says "GIF" rather than "Photo".
    val attachmentAnimated: Boolean = false,
    val moderationFlagged: Boolean = false,
    val mine: Boolean = false,
    val senderName: String = "",
    val senderNodeId: String = "",
    val sentAt: Long = 0L,
    // Our clock when an inbound message was first persisted (`messages.arrivedAt`) — null for a message we
    // sent, and for one stored before the column existed. The screen renders the absence, never a zero.
    val arrivedAt: Long? = null,
    // Our clock when a DM we sent was acked (`message_receipts.notedAt`) — null until their receipt lands,
    // and never set for a group or room post, whose "who has it" question the roster below answers instead.
    val deliveredAt: Long? = null,
    val delivery: DeliveryStatus = DeliveryStatus.Sent,
    val plane: DeliveryPlane = DeliveryPlane.Unknown,
    val reactors: List<ReactorRow> = emptyList(),
    val filters: List<ReactionFilter> = emptyList(),
    // Whether to render the per-recipient delivery split at all — see [MessageDetailsViewModel].
    val showRecipients: Boolean = false,
    val deliveredTo: List<RecipientRow> = emptyList(),
    val waitingOn: List<RecipientRow> = emptyList(),
    // The group roster excluding ourselves, i.e. the "of N" denominator. 0 in the broadcast room, which
    // has no roster and therefore no total to count against.
    val recipientTotal: Int = 0,
)

/**
 * Backs the message-details screen (keyed by [messageId]): who reacted and with what, who a message we
 * sent has actually reached, plus the per-message metadata the chat bubble has no room for — when it was
 * sent, and how far it got.
 *
 * Read-only over rows this device already holds. The reactor identity the chat's
 * [ReactionSummary] tally aggregates away ("👍 3" says nothing about *which* three) is right there in
 * `reactions.reactorNodeId`; this just declines to throw it away, resolving each one through
 * [displayNameFor] so a reactor whose profile hasn't reached us still reads as a stable alias rather
 * than a raw node id. The delivery half is the same move one table over: the ✓✓ on a group send means
 * only "≥1 member received it" (a wire semantic, deliberately unchanged), while
 * `message_receipts.ackerNodeId` names each member whose receipt has actually arrived.
 *
 * Reactions from blocked people are listed, not filtered: [ChatViewModel] filters blocked senders'
 * *messages* but never their reactions, so the chip this screen was opened from already counts them —
 * hiding them here would make the screen disagree with the chip. Blocked ackers follow the same rule.
 *
 * The delivery split is shown only for a message *we* sent, and only where "who" is answerable:
 *
 * - **Group** — the effective roster minus ourselves, split into delivered (ordered by when their
 *   receipt reached us) and waiting. A message already ticked ✓✓ with no receipt rows at all predates
 *   the table, so the split is hidden rather than claiming everyone missed it.
 * - **Broadcast room** — an open "received by" list with no denominator: the room has no roster, so
 *   there is nobody to be waiting on. Hidden until at least one ack lands.
 * - **DM** — never: the single ✓✓ already names the only recipient there is. The screen still reads that
 *   recipient's receipt for its `notedAt`, so a DM says *when* it was delivered without listing *who* to.
 */
class MessageDetailsViewModel(
    private val messageId: String,
    messages: MessageRepository,
    reactions: ReactionRepository,
    receipts: MessageReceiptRepository,
    groups: GroupRepository,
    peers: PeerRepository,
    settings: SettingsStore,
    identity: Identity,
) : ViewModel() {
    private val myNodeId = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch { myNodeId.value = identity.nodeId() }
    }

    // The row plus whether it has ever been seen, so a not-yet-written row is told apart from a deleted
    // one. `scan` carries that one bit forward; `drop(1)` discards the seed accumulator.
    private val sightings =
        messages
            .observeMessage(messageId)
            .scan(MessageSighting()) { previous, row -> MessageSighting(row, previous.everSeen || row != null) }
            .drop(1)

    // Everything except the delivery split, which needs two more flows than `combine` has an overload for.
    // The row and the peer index ride along so the second stage doesn't have to re-derive them.
    private data class Base(
        val state: MessageDetailsUiState,
        val row: MessageEntity?,
        val me: String?,
        val directory: PeerDirectory,
    )

    private val base: Flow<Base> =
        combine(
            sightings,
            reactions.observeReactionsFor(messageId),
            peers.observeDirectory(),
            myNodeId,
            settings.contentFilteringEnabled,
        ) { sighting, reacts, directory, me, hideSensitive ->
            val message = sighting.row
            val everSeen = sighting.everSeen
            val peersByNode = directory.byNode
            if (message == null) {
                Base(MessageDetailsUiState(messageId = messageId, vanished = everSeen), null, me, directory)
            } else {
                // Chronological, matching the DAO's `updatedAt ASC` — the order people reacted in reads
                // better here than any ranking, and the filter chips carry the per-emoji grouping.
                val rows =
                    reacts.mapNotNull { reaction ->
                        val emoji = reaction.emoji ?: return@mapNotNull null
                        val label = directory.label(reaction.reactorNodeId)
                        ReactorRow(
                            nodeId = reaction.reactorNodeId,
                            displayName = label.text,
                            avatarHash = peersByNode[reaction.reactorNodeId]?.avatarHash,
                            emoji = emoji,
                            reactedAt = reaction.updatedAt,
                            isSelf = reaction.reactorNodeId == me,
                            discriminator = label.discriminator,
                        )
                    }
                Base(
                    MessageDetailsUiState(
                        messageId = messageId,
                        body = message.body,
                        hasAttachment = message.attachmentHash != null,
                        attachmentMime = message.attachmentMime,
                        attachmentName = message.attachmentName,
                        attachmentSize = message.attachmentSize,
                        attachmentAnimated = message.attachmentAnimated,
                        moderationFlagged = hideSensitive && message.moderation == MessageEntity.MODERATION_TEXT_FLAGGED,
                        mine = message.senderId == me,
                        senderName = directory.label(message.senderId).text,
                        senderNodeId = message.senderId,
                        sentAt = message.sentAt,
                        arrivedAt = message.arrivedAt,
                        delivery = DeliveryStatus.of(message),
                        plane = message.receivedPlane,
                        reactors = rows,
                        filters = filtersOf(rows),
                    ),
                    message,
                    me,
                    directory,
                )
            }
        }

    val state: StateFlow<MessageDetailsUiState> =
        combine(
            base,
            receipts.observeForMessage(messageId),
            // Every group, not a flatMapLatest on this message's conversation: the roster caps at 8 and a
            // device holds a handful of groups, so the whole table is cheaper than re-subscribing per row.
            groups.observeGroups(),
        ) { base, acks, groupList ->
            val message = base.row ?: return@combine base.state
            withRecipients(base, message, acks, groupList.firstOrNull { it.groupId == message.conversationId })
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            MessageDetailsUiState(messageId = messageId),
        )

    /**
     * Folds the per-recipient delivery split onto [base]'s state — see the class doc for which
     * conversations answer "who" and which don't.
     */
    private fun withRecipients(
        base: Base,
        message: MessageEntity,
        acks: List<MessageReceiptEntity>,
        group: GroupEntity?,
    ): MessageDetailsUiState {
        val ackedAt = acks.associate { it.ackerNodeId to it.notedAt }
        return when {
            !base.state.mine -> {
                base.state
            }

            group != null -> {
                // We never ack our own message, so our own row would sit in "waiting on" forever.
                val roster = GroupMembersStore.decode(group.members).filterNot { it == base.me }
                // Already ✓✓ with nothing recorded = acked before this device kept receipts. We know
                // somebody got it and cannot say who, so we say nothing rather than accuse the roster.
                if (acks.isEmpty() && message.received) {
                    base.state
                } else {
                    base.state.copy(
                        showRecipients = true,
                        // Ordered by the DAO's `notedAt ASC`, so the list grows downward as receipts land.
                        deliveredTo = acks.filter { it.ackerNodeId in roster }.map { row(base, it.ackerNodeId, it.notedAt) },
                        waitingOn = roster.filterNot { it in ackedAt }.map { row(base, it, null) },
                        recipientTotal = roster.size,
                    )
                }
            }

            // The public room has no roster, so this is an open list: everyone we heard back from, nobody
            // to be waiting on, and no total to count against. Empty means we know nothing, not "nobody".
            message.conversationId == Conversations.NEARBY && acks.isNotEmpty() -> {
                base.state.copy(
                    showRecipients = true,
                    deliveredTo = acks.map { row(base, it.ackerNodeId, it.notedAt) },
                )
            }

            // A DM's single ✓✓ already names its only recipient, so it still shows no split. But the
            // receipt that flipped that tick recorded *when* it reached us, and that is worth a line —
            // matched on the addressed recipient so an orphan row can never supply the stamp.
            Conversations.kindFor(message.conversationId) == ConversationKind.DM -> {
                base.state.copy(deliveredAt = acks.firstOrNull { it.ackerNodeId == message.recipientId }?.notedAt)
            }

            else -> {
                base.state
            }
        }
    }

    private fun row(
        base: Base,
        nodeId: String,
        deliveredAt: Long?,
    ): RecipientRow =
        RecipientRow(
            nodeId = nodeId,
            displayName = base.directory.label(nodeId).text,
            avatarHash = base.directory.byNode[nodeId]?.avatarHash,
            deliveredAt = deliveredAt,
            discriminator = base.directory.label(nodeId).discriminator,
        )

    /** Chip order: most-reacted first, ties broken by who got there first, so chips don't reshuffle. */
    private fun filtersOf(rows: List<ReactorRow>): List<ReactionFilter> =
        rows
            .groupBy { it.emoji }
            .map { (emoji, group) -> ReactionFilter(emoji, group.size) to group.minOf { it.reactedAt } }
            .sortedWith(compareByDescending<Pair<ReactionFilter, Long>> { it.first.count }.thenBy { it.second })
            .map { it.first }
}
