package app.getknit.knit.ui.chatlist

import android.content.Context
import app.getknit.knit.R
import app.getknit.knit.data.PeerDirectory
import app.getknit.knit.data.message.ConversationKind
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.message.isStatusNotice
import app.getknit.knit.data.message.receivedPlane
import app.getknit.knit.ui.ConversationTitle
import app.getknit.knit.ui.MeshRoomInputs
import app.getknit.knit.ui.chat.DeliveryStatus
import app.getknit.knit.ui.chat.messagePreview
import app.getknit.knit.ui.chat.transferPreview
import app.getknit.knit.ui.isPending
import app.getknit.knit.ui.speakerLabel
import app.getknit.knit.ui.visibleConversations

/**
 * Folds one [ChatListViewModel.Snapshot] into the list's rows. Pure over the snapshot — it reads the
 * per-thread summaries, never a thread — and leaves every row's `unreadCount` at zero for the ViewModel to
 * fill from a per-thread count (the one number that needs a query per row, and only for the rows drawn).
 *
 * A class rather than one long transform so each rule has a name and a size detekt can see.
 */
internal class ChatListAssembler(
    private val context: Context,
    private val s: ChatListViewModel.Snapshot,
) {
    private val table = s.table
    private val me = table.me
    private val directory: PeerDirectory = s.directory
    private val activeGroups = s.inputs.groups.filter { !it.left }

    // Left groups too, to hide stray rows.
    private val groupIds =
        s.inputs.groups
            .map { it.groupId }
            .toSet()

    fun build(): ChatListUiState {
        // Which threads exist and what they are called is `visibleConversations`'s rule, shared with search:
        // the Nearby room always, the Meshtastic room by the radio's state, active non-request groups, and
        // DM threads once they have a message. Most-recent first.
        val meshRoom = MeshRoomInputs(s.mesh.loraRoom, s.mesh.loraPlane, s.mesh.publicChannel, s.inputs.bridgedChannel)
        val rows =
            visibleConversations(
                context,
                table,
                s.inputs.groups,
                directory,
                s.inputs.accepted,
                meshRoom,
                s.inputs.commons,
            ).map(::rowFor)
        val requestCount = requestCount()
        // The list is never literally empty — the Nearby room always has a row — so a fresh install
        // reads as a working screen with nothing to do on it. Nudge until there is: any Nearby message,
        // a group, a DM, or a pending request. Deleting every thread again brings the hint back, which
        // is the state it is written for.
        val gettingStarted = rows.all { it.isRoom && it.lastMessageAt == null } && requestCount == 0
        return ChatListUiState(
            conversations = rows.sortedByDescending { it.lastMessageAt ?: 0L },
            requestCount = requestCount,
            neighborCount = s.mesh.neighborCount,
            transportHealth = s.mesh.health,
            relayPlane = s.mesh.relayPlane,
            loraPlane = s.mesh.loraPlane,
            radioWarning = s.mesh.warning,
            cloneVisible = s.mesh.cloneVisible,
            showGettingStarted = gettingStarted,
        )
    }

    /** Whether a thread is a stranger's message request — [ConversationTable.isPending] over this snapshot's inputs. */
    fun isPending(conversationId: String): Boolean = table.isPending(conversationId, s.inputs.accepted, directory.verified)

    /**
     * One row. Its last message is the thread's head: the newest normal or direct-transfer row, which is
     * what "speaks for the row" — its preview line, its time, its place in the list. Status notices never
     * do: a contact renaming themselves is worth a line inside the thread and is not worth reordering
     * someone's chat list, and a notice's senderId is the event's *subject* rather than an author, so
     * treating one as the last message would also mis-attribute the preview. A direct transfer is the
     * exception, and both halves of that reasoning are why: its sender really is the author (whoever
     * offered the file), and an offer or a gigabyte arriving is not a footnote about the thread — it is
     * the thread. It still earns no delivery tick and no unread count: nothing was sent anywhere on the
     * mesh, and the card in the thread is the thing that wants answering.
     */
    private fun rowFor(thread: ConversationTitle): ConversationRow {
        val last = table.heads[thread.id]
        // A draft only speaks for the row while it is the newest thing in the thread. Once a message
        // lands after it — ours or theirs — the conversation has moved on and the preview says so;
        // the draft is still in the composer, waiting where it was typed. Ties go to the message,
        // and a peer's `sentAt` is their clock, which is the same skew every row here already sorts on.
        val draft =
            s.inputs.drafts[thread.id]
                ?.takeIf { it.text.isNotBlank() && it.updatedAt > (last?.sentAt ?: 0L) }
                ?.text
        // The tick, and only for our own sends. "Ours" means we wrote it: a heard Meshtastic post sits in
        // our sender column by convention (the phone whose board heard it writes the row), but we did not
        // write a word of it, and hanging a tick on somebody else's words would be wrong. A notice was
        // never sent anywhere, so it can never grow one either.
        val mineLast = last?.takeIf { it.senderId == me && it.originNode == null && !it.isStatusNotice }
        val transferLine = transferLineFor(last)
        val isGroup = thread.kind == ConversationKind.GROUP
        val row =
            ConversationRow(
                id = thread.id,
                title = thread.text,
                avatarHash = thread.avatarHash,
                isRoom = thread.isRoom,
                isGroup = isGroup,
                faces = thread.faces,
                lastPreview = previewLineFor(last, transferLine, isDm = thread.kind == ConversationKind.DM),
                previewIsTransfer = transferLine != null,
                lastMessageAt = last?.sentAt,
                draft = draft,
                unreadCount = 0, // filled in by the ViewModel from a per-thread count, once our own id is known
                lastStatus = mineLast?.let { DeliveryStatus.of(it) },
                lastDeliveredVia = mineLast?.receivedPlane ?: DeliveryPlane.Unknown,
                discriminator = thread.discriminator,
                isBridged = thread.kind == ConversationKind.MESHTASTIC,
            )
        // An empty group sorts/labels by its creation time so it isn't stranded at the bottom.
        val createdAt = thread.group?.createdAt
        return if (isGroup && row.lastMessageAt == null && createdAt != null) row.copy(lastMessageAt = createdAt) else row
    }

    /** Count of threads moved to the requests inbox (mirrors exactly what `visibleConversations` drops). */
    private fun requestCount(): Int =
        table.conversations.count {
            it != Conversations.NEARBY && it != Conversations.MESHTASTIC && it !in groupIds && isPending(it)
        } + activeGroups.count { isPending(it.groupId) }

    /**
     * The row's preview line: the transfer's own sentence when [transferLine] resolved one, else the
     * "Sender: body" form.
     */
    private fun previewLineFor(
        last: MessageEntity?,
        transferLine: String?,
        isDm: Boolean,
    ): String? = transferLine ?: last?.let { previewFor(it, isDm) }

    /**
     * The transfer line for [last], or null when it is not a transfer row this build can read. Resolved
     * apart from [previewFor] because it takes no "You: " prefix — it already says who did what ("They
     * declined clip.mp4"), and a prefix would name the wrong person — and because the row needs to know it
     * chose this line, to draw the feature's mark beside it.
     */
    private fun transferLineFor(last: MessageEntity?): String? =
        last?.takeIf { it.kind == MessageEntity.KIND_FILE_TRANSFER }?.let { transferPreview(context, it, s.inputs.transfers) }

    /**
     * "Sender: body" preview: [speakerLabel]'s speaker, or just the body when it names nobody (an incoming
     * message in a 1:1 DM, whose sender is already the row title).
     */
    private fun previewFor(
        message: MessageEntity,
        isDm: Boolean,
    ): String {
        val body = messagePreview(context, message)
        val speaker = speakerLabel(context, message, directory, me, isDm) ?: return body
        return context.getString(R.string.chat_list_preview_with_sender, speaker, body)
    }
}
