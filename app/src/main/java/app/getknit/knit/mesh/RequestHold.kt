package app.getknit.knit.mesh

import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.message.AttachmentFetchRow
import app.getknit.knit.data.message.ConversationKind
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.settings.InboundSettings
import kotlinx.coroutines.flow.first

/**
 * Whether a received attachment waits for its message request to be accepted before anything fetches it (#282). A
 * stranger's DM or group (ADR 009's message request) is stored and acknowledged, and its attachment is held: the request
 * inbox shows no attachment and offers no "Tap to download", so a fetch before acceptance would only spend the radios
 * and the store on bytes a stranger chose, and let the stranger make us ask the mesh on their behalf.
 *
 * Folded over the rows the way `BluetoothFetchGate` folds them (ADR 2026-10.js2p): one row that would fetch the hash
 * fetches it for all. A row releases it when it is ours, tapped, or in a conversation [Conversations.isAccepted]
 * accepts — so a contact's copy of a picture a stranger also sent is fetched, and only the releasing rows say who is
 * asked first and until when it is pulled through. A public room is always accepted, so a room post is never held.
 *
 * A local decision on our own pull of bytes addressed to us, and nothing else: custody, relay, acks and the digest never
 * read it (ADR 009, 006), and a frame we carry for somebody else is judged by the carrier rules alone.
 */
object RequestHold {
    /** Whether [row] would fetch its attachment, with [accepted] answering for its conversation. */
    fun releases(
        row: AttachmentFetchRow,
        accepted: (conversationId: String) -> Boolean,
    ): Boolean = row.own || row.approved || accepted(row.conversationId)

    /** The rows among [rows] that would fetch their attachment ([releases]), in their order. */
    fun releasing(
        rows: List<AttachmentFetchRow>,
        accepted: (conversationId: String) -> Boolean,
    ): List<AttachmentFetchRow> = rows.filter { releases(it, accepted) }

    /** Whether the attachment [rows] name is held: some row names it and none releases it. No rows, nothing held. */
    fun held(
        rows: List<AttachmentFetchRow>,
        accepted: (conversationId: String) -> Boolean,
    ): Boolean = rows.isNotEmpty() && rows.none { releases(it, accepted) }

    /**
     * The conversation a custodied [frame] is ours alone in — its DM's sender when it is addressed to [me], its group when
     * we are a member (the group row, or the frame's own roster or departed list: a stranger who lists us only as departed
     * still addresses us) and nobody but us and its sender is named — or null: somebody else's frame, our own, or a group
     * that names a third party, whose copy we carry for that party (ADR 2026-11.qu98's carrier rule). A group frame whose
     * roster is unknown is treated as naming others.
     */
    fun conversationOf(
        frame: CarriedAttachment,
        me: String,
    ): String? {
        val sender = frame.senderId
        val group = frame.groupId
        return when {
            sender == null || sender == me -> null
            frame.recipientId == me -> sender
            group == null -> null
            !frame.member && me !in frame.roster.orEmpty() && me !in frame.departed.orEmpty() -> null
            frame.roster == null -> null
            (frame.roster + frame.departed.orEmpty() - me - sender).isNotEmpty() -> null
            else -> group
        }
    }

    /**
     * Whether [frame] is a group's that [acceptance] does not accept yet, naming somebody besides [me] and its sender
     * ([conversationOf] null for it even counting us a member): our copy is that party's carrier copy (#282's B3), judged
     * by the carrier rules and never by our own rows or limit. `InboundPipeline.onCarriedFrame` pulls it plain by this, and
     * the database re-arm's fast-only hold (`MeshManager.unopenedForUs`) does not count it as ours by the same rule, so the
     * re-arm never re-holds that pull (#291).
     */
    suspend fun carriedForOthers(
        frame: CarriedAttachment,
        me: String,
        acceptance: AcceptanceSnapshot,
    ): Boolean {
        val group = frame.groupId ?: return false
        if (frame.senderId == me || conversationOf(frame.copy(member = true), me) != null) return false
        return !acceptance.isAccepted(group, frame.senderId)
    }

    /**
     * Whether the carried attachment [frames] name is held before any row names it: every frame naming it is addressed
     * to us ([conversationOf]) from a conversation [accepted] refuses, asked with the frame's own sender, which counts as
     * one of a group's senders — as the delivery's read after its persist would count it. One frame we carry for somebody
     * else, our own, or one from an accepted conversation keeps the carrier's rules in charge. No frames, nothing held.
     */
    fun carriedHeld(
        frames: Collection<CarriedAttachment>,
        me: String,
        accepted: (conversationId: String, sender: String) -> Boolean,
    ): Boolean =
        frames.isNotEmpty() &&
            frames.all { f ->
                val conversation = conversationOf(f, me)
                conversation != null && !accepted(conversation, checkNotNull(f.senderId))
            }
}

/**
 * One read of what [Conversations.isAccepted] judges a conversation by (ADR 009): the user's accepted set, the verified
 * peers, the conversations the user wrote in, and a group's senders, each read on first use and kept for this snapshot
 * only — a public room reads nothing. The delivery's notify gate, the request count and [RequestHold]'s readers share
 * it, so they stay one rule. Built per pass and never kept across passes: acceptance moves under it.
 */
class AcceptanceSnapshot(
    private val acceptedIds: suspend () -> Set<String>,
    private val verifiedIds: suspend () -> Set<String>,
    private val authoredIds: suspend () -> Set<String>,
    private val sendersIn: suspend (groupId: String) -> Collection<String>,
) {
    private var accepted: Set<String>? = null
    private var verified: Set<String>? = null
    private var authored: Set<String>? = null
    private val senders = HashMap<String, Set<String>>()

    /**
     * Whether [conversationId] is accepted, with [sender] — the sender of a frame not stored yet, or not stored as a
     * message — counted among a group's senders.
     */
    suspend fun isAccepted(
        conversationId: String,
        sender: String? = null,
    ): Boolean {
        if (Conversations.isPublicRoom(conversationId)) return true
        val group = Conversations.kindFor(conversationId) == ConversationKind.GROUP
        val groupSenders =
            if (group) senders.getOrPut(conversationId) { sendersIn(conversationId).toSet() } + listOfNotNull(sender) else emptySet()
        return Conversations.isAccepted(
            conversationId,
            accepted ?: acceptedIds().also { accepted = it },
            verified ?: verifiedIds().also { verified = it },
            authored ?: authoredIds().also { authored = it },
            groupSenders,
        )
    }

    /** The conversations among [conversationIds] this snapshot accepts, each judged once. */
    suspend fun acceptedAmong(conversationIds: Collection<String>): Set<String> =
        conversationIds.toSet().filterTo(HashSet()) { isAccepted(it) }

    companion object {
        /** A snapshot for [me] over the settings, the peers and the messages, read as it is asked. */
        fun of(
            settings: InboundSettings,
            peers: PeerRepository,
            messages: MessageRepository,
            me: String,
        ): AcceptanceSnapshot =
            AcceptanceSnapshot(
                acceptedIds = { settings.acceptedConversations.first() },
                verifiedIds = { peers.verifiedNodeIds().toSet() },
                authoredIds = { messages.conversationsIAuthoredIn(me).toSet() },
                sendersIn = messages::sendersIn,
            )
    }
}
