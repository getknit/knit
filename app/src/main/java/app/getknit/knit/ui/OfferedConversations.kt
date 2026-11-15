package app.getknit.knit.ui

import android.content.Context
import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.commons.CommonsRepository
import app.getknit.knit.data.message.ConversationKind
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.IdentitySource
import app.getknit.knit.identity.PeerLabelIndex
import app.getknit.knit.mesh.lora.BoardKind
import app.getknit.knit.mesh.lora.LoraFacts
import app.getknit.knit.ui.chat.roomFactsFor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * The conversations the chat list offers, for the surfaces that name a thread from outside the app — the
 * conversation shortcuts, and the routes a shortcut or a notification opens (ADR 2026-10.jbsa). The membership is
 * [visibleConversations]'s, over the same inputs search assembles, so the launcher's long-press menu cannot offer a
 * thread the list does not: a deleted chat, a blocked peer, a left group or commons, a request, the Meshtastic room
 * once it has gone.
 *
 * Read on demand ([snapshot]) rather than watched: the messages-table half of the inputs re-runs on every message
 * write, which the chat list pays only while it is on screen and a background reader would pay all day. [changes]
 * watches the cheap half instead — the settings sets, the commons table, and the peer and group rows of the threads
 * that have a shortcut, never the whole of either table.
 */
internal class OfferedConversations(
    private val context: Context,
    private val messages: MessageRepository,
    private val peers: PeerRepository,
    private val groups: GroupRepository,
    private val settings: SettingsStore,
    private val identity: IdentitySource,
    // The facts flow rather than the repository, as search takes it: the production flow never completes, and a
    // test supplies a fixed one.
    private val loraFacts: Flow<LoraFacts>,
    private val commons: CommonsRepository?,
    // Each board kind's own facts (`LoraStatusRepository.perKind`): each bridged room is offered by its own kind's rule (ADR
    // 2026-11.gsqu's Stage 6 amendment). Null reads [loraFacts] as its own kind's alone.
    private val loraRooms: Flow<Map<BoardKind, LoraFacts>>? = null,
) {
    /** The offered conversation [ids] at one moment, with our own id and the label index their faces are named off. */
    class Snapshot(
        val me: String,
        val ids: Set<String>,
        val labels: PeerLabelIndex,
    )

    suspend fun snapshot(): Snapshot {
        val me = identity.nodeId()
        val table = messages.observeConversationTable(setOf(MessageEntity.KIND_NORMAL), settings.blockedNodeIds, flowOf(me)).first()
        val directory = peers.observeDirectory().first()
        val perKind = loraRooms?.first() ?: loraFacts.first().let { mapOf(it.board to it) }
        val meshRoom =
            MeshRoomInputs.of(
                roomFactsFor(BoardKind.MESHTASTIC, perKind),
                newestChannel = messages.observeNewestOriginChannel(Conversations.MESHTASTIC).first(),
            )
        val visible =
            visibleConversations(
                context,
                table,
                groups.observeGroups().first(),
                directory,
                settings.acceptedConversations.first(),
                meshRoom,
                commons?.observeAll()?.first().orEmpty(),
                meshcoreRoom = MeshRoomInputs.of(roomFactsFor(BoardKind.MESHCORE, perKind)),
            )
        return Snapshot(me, visible.mapTo(HashSet()) { it.id }, directory.labels)
    }

    /** Whether [conversationId] is a thread the chat list shows now. */
    suspend fun isOffered(conversationId: String): Boolean = conversationId in snapshot().ids

    /**
     * Emits whenever an input that can move a watched thread in or out of the list, or change its face, has changed:
     * the blocked and accepted sets, a commons joined, left or renamed, and — for the rows [watch] names alone — a
     * group's name, photo, roster or departure and a peer's name or avatar. Only those rows are read: every inbound
     * profile frame upserts its peer row and every group frame its group row, and a read of the whole table with the
     * label index rebuilt over it, on each, is the chat list's cost while it is on screen, not a background one. A
     * label that moves because some *other* peer took the same name is not watched; the next pass (the window's stop,
     * the next start) reads it.
     */
    fun changes(watch: ShortcutWatch): Flow<Unit> =
        combine(
            settings.blockedNodeIds.distinctUntilChanged(),
            settings.acceptedConversations.distinctUntilChanged(),
            groups
                .observeGroups(watch.groupIds)
                .map { list -> list.map { GroupFace(it.groupId, it.name, it.members, it.left, it.photoShownHash) }.toSet() }
                .distinctUntilChanged(),
            peers.observeFaces(watch.peerIds).map { it.toSet() }.distinctUntilChanged(),
            (commons?.observeAll() ?: flowOf(emptyList()))
                .map { rooms -> rooms.map { it.conversationId to it.name } }
                .distinctUntilChanged(),
        ) { _, _, _, _, _ -> }

    /** What a group row can change about its thread's membership or face. */
    private data class GroupFace(
        val groupId: String,
        val name: String,
        val members: String,
        val left: Boolean,
        val photoShownHash: String?,
    )
}

/** The rows a conversation-shortcut watch reads ([OfferedConversations.changes]), named by the shortcuts' threads. */
internal data class ShortcutWatch(
    /** The peers whose name or avatar a shortcut wears: each DM's peer, and the faces of a photo-less group's cluster. */
    val peerIds: Set<String> = emptySet(),
    /** The groups that have a shortcut. */
    val groupIds: Set<String> = emptySet(),
) {
    operator fun plus(other: ShortcutWatch) = ShortcutWatch(peerIds + other.peerIds, groupIds + other.groupIds)

    companion object {
        /** What [conversationIds] need watched before their faces are known: a DM's peer, a group's row. */
        fun of(conversationIds: Collection<String>) =
            ShortcutWatch(
                peerIds = conversationIds.filterTo(HashSet()) { Conversations.kindFor(it) == ConversationKind.DM },
                groupIds = conversationIds.filterTo(HashSet()) { Conversations.kindFor(it) == ConversationKind.GROUP },
            )
    }
}
