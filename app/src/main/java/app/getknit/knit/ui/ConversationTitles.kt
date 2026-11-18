package app.getknit.knit.ui

import android.content.Context
import androidx.annotation.StringRes
import app.getknit.knit.R
import app.getknit.knit.TextLimits
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
import app.getknit.knit.data.message.hiddenByFilter
import app.getknit.knit.data.message.meshRoomChannel
import app.getknit.knit.identity.NameKey
import app.getknit.knit.mesh.lora.LoraFacts
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
    val isRoom: Boolean get() = kind == ConversationKind.NEARBY || kind.isBridged || kind == ConversationKind.COMMONS
}

/**
 * A bridged room's inputs: whether the user keeps the room ([enabled], `SettingsStore.loraRoomEnabled` for the Meshtastic
 * room, `meshcoreRoomEnabled` for the MeshCore room), whether its own kind's board is bound ([plane]), what the board
 * calls slot 0 now ([liveChannel]) and what the newest heard post named ([newestChannel], the Meshtastic room's alone: a
 * MeshCore post names no channel) — the two halves of [meshRoomChannel]. Each room's from its own kind's facts
 * (`roomFactsFor`), never the bound kind's (ADR 2026-11.gsqu's Stage 6 amendment).
 */
internal data class MeshRoomInputs(
    val enabled: Boolean,
    val plane: LoraPlane,
    val liveChannel: String?,
    val newestChannel: String?,
) {
    companion object {
        /** A room whose kind no input names: kept by default, no board, so its row stands on history alone. */
        val NO_BOARD = MeshRoomInputs(enabled = true, plane = LoraPlane.Off, liveChannel = null, newestChannel = null)

        /** A room's inputs off its own kind's [facts], with the newest heard post's channel where the room keeps one. */
        fun of(
            facts: LoraFacts,
            newestChannel: String? = null,
        ): MeshRoomInputs = MeshRoomInputs(facts.room, facts.plane, facts.primaryChannel, newestChannel)
    }
}

/**
 * The title of [conversationId]: the Nearby room's fixed name; a radio room's network and channel
 * ([radioRoomTitle] over [meshRoomChannel]); a group's stored name, else one generated from the other
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
            ConversationTitle(conversationId, kind, radioRoomTitle(context, R.string.meshtastic_title, meshRoomChannel), null, null)
        }

        // The board's slot 0 as it is named now (whatever it holds, never a constant `Public`), after the network's name.
        ConversationKind.MESHCORE -> {
            ConversationTitle(conversationId, kind, radioRoomTitle(context, R.string.meshcore_title, meshRoomChannel), null, null)
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
                    storedName = directory.shownGroup(group?.name.orEmpty()),
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
 * A radio room's title: its network ([network]: [R.string.meshtastic_title] or [R.string.meshcore_title]) and, while one
 * is known, the channel its board names ([channel], `meshRoomChannel`): "Meshtastic (LongFast)", "MeshCore (Public)".
 * The network leads, so the title starts the same with the board away (the network alone), and a channel's own name
 * beside a room glyph (a bare "Public") never reads as one of Knit's channels. A channel named for the network itself is
 * not repeated. One rule for the chat list, search, the shade and the thread header (`ChatViewModel`).
 *
 * The channel is cleaned for one line here ([TextLimits.cleanReceived]): Meshtastic's slot-0 name reaches this raw from
 * the board's channel table, which a shared channel URL or QR code sets, and a bidi override in it could reorder or hide
 * the network prefix (security-reviewer, 2026-11-18). MeshCore's is cleaned already; cleaning is idempotent.
 */
internal fun radioRoomTitle(
    context: Context,
    @StringRes network: Int,
    channel: String?,
): String {
    val name = context.getString(network)
    val shown =
        channel
            ?.let { TextLimits.cleanReceived(it, TextLimits.DISPLAY_NAME) }
            ?.takeIf { it.isNotEmpty() && !it.equals(name, ignoreCase = true) }
            ?: return name
    return context.getString(R.string.radio_room_title, name, shown)
}

/**
 * Every thread a list surface shows, titled, in the list's own order before its sort: the Nearby room
 * (always present, even with no messages yet); each bridged room (Meshtastic's, then MeshCore's) while the user keeps it
 * and either its own kind's board is bound or history remains (the row stays while history does after the radio goes, and never
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
    // The MeshCore room's own inputs (ADR 2026-11.gsqu's Stage 6 amendment), by the Meshtastic room's rule.
    meshcoreRoom: MeshRoomInputs = MeshRoomInputs.NO_BOARD,
): List<ConversationTitle> {
    val verified = directory.verified
    val groupIds = groups.mapTo(HashSet()) { it.groupId }
    val nearby = conversationTitle(context, Conversations.NEARBY, null, directory, table.me, null)
    val bridged = bridgedRoomTitle(context, Conversations.MESHTASTIC, meshRoom, table, directory)
    val meshcore = bridgedRoomTitle(context, Conversations.MESHCORE_PUBLIC, meshcoreRoom, table, directory)
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
    return listOf(nearby) + listOfNotNull(bridged, meshcore) + commonsTitles + groupTitles + dms
}

/**
 * A bridged room's row ([conversationId]: the Meshtastic or the MeshCore room) while the user keeps it ([room]'s
 * switch) and either its own kind's board is bound or its history remains; null otherwise — a standing empty row on a
 * phone with neither would offer something this install cannot have.
 */
private fun bridgedRoomTitle(
    context: Context,
    conversationId: String,
    room: MeshRoomInputs,
    table: ConversationTable,
    directory: PeerDirectory,
): ConversationTitle? {
    if (!room.enabled || (room.plane == LoraPlane.Off && conversationId !in table.conversations)) return null
    return conversationTitle(context, conversationId, null, directory, table.me, meshRoomChannel(room.liveChannel, room.newestChannel))
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
    // Content filtering is on: a MeshCore room post it hides names nobody, since its name was screened with its words.
    hideFlagged: Boolean = false,
): String? {
    message.originNode?.let { node ->
        // A MeshCore room post (ADR 2026-11.gsqu's Stage 6 amendment, security B1, N2): never a contact and never the
        // sentinel node's `!hex`. Its name is always quoted and marked on a line like this one — every author, whatever it
        // matches, so "You: hello" can never read as our own post (the maintainer's call, 2026-11-15) — and a missing or
        // hidden one (screened with the words, or by the name screen, ADR 2026-11.y4pc) is plainly "Unnamed".
        if (message.conversationId == Conversations.MESHCORE_PUBLIC) {
            val name = message.originName?.takeIf { !message.hiddenByFilter(hideFlagged) }?.let(directory::shown)
            return meshcoreAuthorLine(context, name)
        }
        val contact = message.originPeerId?.let { directory.label(it) }
        return contact?.text ?: message.originName?.let(directory::shown)?.takeIf { it.isNotBlank() } ?: meshNodeLabel(node)
    }
    val isOwn = message.senderId == me
    if (isDm && !isOwn) return null
    return if (isOwn) context.getString(R.string.chat_self_name) else directory.label(message.senderId).text
}

/**
 * A MeshCore room post's author on a single-line surface (ADR 2026-11.gsqu's Stage 6 amendment): its claimed [name], always
 * quoted and marked (`chat_meshcore_name_claimed`) whatever it matches, or "Unnamed" for none. The chat-list and search
 * previews ([speakerLabel]), the pinned bar and the details screen all read it; the notification's person is worded the
 * same way off `NotificationLabels.claimedName`.
 */
internal fun meshcoreAuthorLine(
    context: Context,
    name: String?,
): String =
    name?.takeIf { it.isNotBlank() }?.let { context.getString(R.string.chat_meshcore_name_claimed, it) }
        ?: context.getString(R.string.chat_meshcore_unnamed)

/**
 * The [NameKey]s a MeshCore room post's claimed name must not read as for the bubble to draw it unmarked (ADR
 * 2026-11.gsqu's Stage 6 amendment, security N2): "You", [ownNames] (the person's display name and their board's
 * `Knit xxxx`), and every name the screens render for a person — each peer's stored name and its label as drawn, the
 * discriminated "Alice (QuietOtter)" and the alias a blank name shows, and our own label the same way. Blank names are
 * left out. A name in it gets the bubble's "Same name as someone you know" line; the one-line surfaces mark every name.
 */
internal fun meshcoreRoomClashKeys(
    context: Context,
    ownNames: List<String?>,
    directory: PeerDirectory,
    me: String?,
): Set<String> {
    val people = directory.peers.map { it.nodeId } + listOfNotNull(me)
    val names =
        listOf(context.getString(R.string.chat_self_name)) + ownNames + directory.peers.map { it.name } +
            people.flatMap { id -> directory.label(id).let { label -> listOf(label.text, label.name) } }
    return names.mapNotNullTo(HashSet()) { name -> name?.let(NameKey::of)?.takeIf { it.isNotEmpty() } }
}

/** Whether a MeshCore room post's claimed [name] reads, by [NameKey], as one of [keys] ([meshcoreRoomClashKeys]). */
internal fun meshcoreNameClashes(
    name: String?,
    keys: Set<String>,
): Boolean = name != null && NameKey.of(name) in keys
