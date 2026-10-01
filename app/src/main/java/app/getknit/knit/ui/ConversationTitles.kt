package app.getknit.knit.ui

import android.content.Context
import app.getknit.knit.R
import app.getknit.knit.data.PeerDirectory
import app.getknit.knit.data.commons.CommonsEntity
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.group.GroupMembersStore
import app.getknit.knit.data.message.ConversationKind
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.GroupFace
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.message.groupFaces
import app.getknit.knit.data.message.groupTitle
import app.getknit.knit.data.message.meshRoomChannel
import app.getknit.knit.mesh.lora.LoraPlane
import app.getknit.knit.mesh.meshNodeLabel

/**
 * What a list surface knows about a thread before it reads a single message of it: which thread, what
 * kind, what it is called, and what to draw beside the name. One resolver for every surface that names a
 * thread — the chat list (and the share picker, through its rows) and search — so the same DM, group or
 * room reads the same everywhere. The thread header keeps its own copy of the group and room rules in
 * `ChatViewModel`, off its window.
 */
internal data class ConversationTitle(
    val id: String,
    val kind: ConversationKind,
    /** The rendered name: a peer's label (`Name (Alias)` when needed), a group's stored or generated title, a room's. */
    val text: String,
    /** The ` (Alias)` already inside [text], for a DM whose peer shares a name (ADR 058); null otherwise. */
    val discriminator: String?,
    /** A peer's avatar or a group's photo; null for the rooms. */
    val avatarHash: String?,
    /** The group's row, for a group; null otherwise. */
    val group: GroupEntity? = null,
    /**
     * The other members a photo-less group draws as its avatar (`groupFaces`: self left out, by node id, at
     * most four; empty under two), so every list built from these titles shows the same cluster. Empty for
     * a DM or a room.
     */
    val faces: List<GroupFace> = emptyList(),
) {
    val isRoom: Boolean get() = kind == ConversationKind.NEARBY || kind == ConversationKind.MESHTASTIC || kind == ConversationKind.COMMONS
}

/**
 * The Meshtastic room's inputs: whether the user keeps the room ([enabled], `SettingsStore.loraRoomEnabled`),
 * whether a board is bound ([plane]), what the board calls slot 0 now ([liveChannel]) and what the newest
 * heard post named ([newestChannel]) — the two halves of [meshRoomChannel].
 */
internal data class MeshRoomInputs(
    val enabled: Boolean,
    val plane: LoraPlane,
    val liveChannel: String?,
    val newestChannel: String?,
)

/**
 * The title of [conversationId]: the Nearby room's fixed name; the Meshtastic room's channel
 * ([meshRoomChannel], else the generic name); a group's stored name, else one generated from the other
 * members' labels ([groupTitle]); a DM peer's label with its discriminator. [group] is the group's row when
 * there is one (an unknown group id still titles, as unnamed).
 */
internal fun conversationTitle(
    context: Context,
    conversationId: String,
    group: GroupEntity?,
    directory: PeerDirectory,
    me: String?,
    meshRoomChannel: String?,
    // The commons' advertised name, for a commons; null titles it generically.
    commonsName: String? = null,
): ConversationTitle =
    when (val kind = Conversations.kindFor(conversationId)) {
        ConversationKind.NEARBY -> {
            ConversationTitle(conversationId, kind, context.getString(R.string.nearby_title), null, null)
        }

        ConversationKind.MESHTASTIC -> {
            ConversationTitle(conversationId, kind, meshRoomChannel ?: context.getString(R.string.meshtastic_title), null, null)
        }

        ConversationKind.COMMONS -> {
            ConversationTitle(
                conversationId,
                kind,
                commonsName?.takeIf { it.isNotBlank() } ?: context.getString(R.string.commons_title),
                null,
                null,
            )
        }

        ConversationKind.GROUP -> {
            val memberIds = group?.let { GroupMembersStore.decode(it.members) }.orEmpty()
            ConversationTitle(
                conversationId,
                kind,
                groupTitle(
                    storedName = group?.name.orEmpty(),
                    memberIds = memberIds,
                    selfId = me,
                    fallback = context.getString(R.string.group_unnamed),
                ) { id -> directory.label(id).text },
                discriminator = null,
                avatarHash = group?.photoShownHash,
                group = group,
                faces = groupFaces(memberIds, me, directory),
            )
        }

        ConversationKind.DM -> {
            val label = directory.label(conversationId)
            ConversationTitle(conversationId, kind, label.text, label.discriminator, directory.byNode[conversationId]?.avatarHash)
        }
    }

/**
 * Whether a thread is a stranger's message request — the SAME shared predicate as the notify gate (Nearby /
 * accepted-set / verified peer / self-authored / a known peer has spoken in the group), over this table's
 * own inputs, so the list screens and the gate agree. A blocked peer's DM thread is not a request either;
 * it is hidden outright. A pending DM/group is dropped from the chat list and surfaced in the Message
 * Requests inbox instead.
 */
internal fun ConversationTable.isPending(
    conversationId: String,
    accepted: Set<String>,
    verified: Set<String>,
): Boolean =
    conversationId !in blocked &&
        !Conversations.isAccepted(conversationId, accepted, verified, authored, groupSenders[conversationId].orEmpty())

/**
 * Every thread a list surface shows, titled, in the list's own order before its sort: the Nearby room
 * (always present, even with no messages yet); the Meshtastic room while the user keeps it and either a
 * board is bound or history remains (the row stays while history does after the radio goes, and never
 * exists on a phone with neither — a standing empty row there would offer something this install cannot
 * have); every active group from the groups table that is not a request (so a freshly created group shows
 * before its first message); and every DM thread with a message whose peer is not blocked and not a
 * request — excluding any conversation that is actually a group, left ones included, to hide stray rows.
 */
internal fun visibleConversations(
    context: Context,
    table: ConversationTable,
    groups: List<GroupEntity>,
    directory: PeerDirectory,
    accepted: Set<String>,
    meshRoom: MeshRoomInputs,
    // Every commons this device has joined (`CommonsRepository.observeAll`): a row each, present from the
    // join on, like a freshly created group — the room exists before anyone has posted in it.
    commons: List<CommonsEntity> = emptyList(),
): List<ConversationTitle> {
    val verified = directory.verified
    val groupIds = groups.mapTo(HashSet()) { it.groupId }
    val nearby = conversationTitle(context, Conversations.NEARBY, null, directory, table.me, null)
    val hasBridgedHistory = Conversations.MESHTASTIC in table.conversations
    val bridged =
        if (!meshRoom.enabled || (meshRoom.plane == LoraPlane.Off && !hasBridgedHistory)) {
            null
        } else {
            val channel = meshRoomChannel(meshRoom.liveChannel, meshRoom.newestChannel)
            conversationTitle(context, Conversations.MESHTASTIC, null, directory, table.me, channel)
        }
    val commonsTitles = commons.map { conversationTitle(context, it.conversationId, null, directory, table.me, null, it.name) }
    val groupTitles =
        groups
            .filter { !it.left && !table.isPending(it.groupId, accepted, verified) }
            .map { conversationTitle(context, it.groupId, it, directory, table.me, null) }
    val dms =
        table.conversations
            .filter {
                Conversations.kindFor(it) == ConversationKind.DM &&
                    it !in table.blocked &&
                    it !in groupIds &&
                    !table.isPending(it, accepted, verified)
            }.map { conversationTitle(context, it, null, directory, table.me, null) }
    return listOf(nearby) + listOfNotNull(bridged) + commonsTitles + groupTitles + dms
}

/**
 * Who a list surface names as the speaker of [message], or null when it names nobody: in a 1:1 DM the
 * peer's name is already the row's title, so their message shows just its body, while our own still get
 * "You" (it is not the recipient's name and signals who spoke). A heard Meshtastic post's author is the
 * speaker, never us — the row sits in our sender column by convention, so without this the line would
 * read "You: …" over somebody else's words. A speaker whose board a contact's profile claims is named as
 * that contact; a stranger is the NodeDB name the board had for them, else the `!hex` id every Meshtastic
 * client would show. Mirrors how `ChatViewModel` resolves names and labels own messages.
 */
internal fun speakerLabel(
    context: Context,
    message: MessageEntity,
    directory: PeerDirectory,
    me: String?,
    isDm: Boolean,
): String? {
    message.originNode?.let { node ->
        val contact = message.originPeerId?.let { directory.label(it) }
        return contact?.text ?: message.originName?.takeIf { it.isNotBlank() } ?: meshNodeLabel(node)
    }
    val isOwn = message.senderId == me
    if (isDm && !isOwn) return null
    return if (isOwn) context.getString(R.string.chat_self_name) else directory.label(message.senderId).text
}
