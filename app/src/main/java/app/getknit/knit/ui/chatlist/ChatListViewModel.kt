package app.getknit.knit.ui.chatlist

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.getknit.knit.R
import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerDirectory
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.group.GroupMembersStore
import app.getknit.knit.data.message.ConversationKind
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.message.groupTitle
import app.getknit.knit.data.message.isStatusNotice
import app.getknit.knit.data.message.meshRoomChannel
import app.getknit.knit.data.message.receivedPlane
import app.getknit.knit.data.relay.RelayFacts
import app.getknit.knit.data.relay.RelayPlane
import app.getknit.knit.data.relay.planeFor
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.mesh.MeshController
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.mesh.lora.LoraFacts
import app.getknit.knit.mesh.lora.LoraPlane
import app.getknit.knit.mesh.meshNodeLabel
import app.getknit.knit.ui.chat.DeliveryStatus
import app.getknit.knit.ui.chat.attachmentPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One row in the conversation list: the [Conversations.NEARBY] broadcast room ([isRoom] true), a
 * group chat ([isGroup] true, keyed by the group id, [title] is the group name, [avatarHash] its photo
 * or null for the glyph), or a 1:1 DM keyed by the peer's node id with the peer's [title]/[avatarHash].
 * [lastPreview]/[lastMessageAt] are null when the conversation has no messages yet.
 *
 * [lastStatus] is how far that last message got, and is non-null **only when the last message is one of
 * ours** — the row's delivery tick. A thread whose newest message arrived from someone else (or which has
 * none, or whose newest is a status notice) has no tick: delivery isn't ours to report. [lastDeliveredVia]
 * qualifies a [DeliveryStatus.Delivered] tick with the plane its receipt crossed, exactly as the chat
 * bubble does.
 */
data class ConversationRow(
    val id: String,
    val title: String,
    val avatarHash: String?,
    val isRoom: Boolean,
    val isGroup: Boolean,
    val lastPreview: String?,
    val lastMessageAt: Long?,
    val unreadCount: Int,
    val lastStatus: DeliveryStatus? = null,
    val lastDeliveredVia: DeliveryPlane = DeliveryPlane.Unknown,
    // The ` (Alias)` suffix already inside [title] when another known peer shares this DM peer's name
    // (ADR 058), so the row can draw it muted. Null for the room, groups, and an unambiguous name.
    val discriminator: String? = null,
    // True for the Meshtastic room — the paired radio's primary channel. It is a room ([isRoom] is true too,
    // so it draws the room glyph), but unlike Nearby it *is* clearable: clearing it drops the history, and
    // the row itself stays only while a radio is bound (or history remains).
    val isBridged: Boolean = false,
)

data class ChatListUiState(
    val conversations: List<ConversationRow> = emptyList(),
    // Number of pending message-request threads (stranger DM/group not yet accepted), for the top-bar badge.
    val requestCount: Int = 0,
    val neighborCount: Int = 0,
    // Radio health, so the connection header can distinguish "nobody nearby" from radios off/seized.
    val transportHealth: TransportHealth = TransportHealth.Healthy,
    // The Internet plane's state for the same header. [RelayPlane.Off] renders nothing, which is also the
    // default the plane ships in.
    val relayPlane: RelayPlane = RelayPlane.Off,
    // The LoRa plane's state for the same header — the board glyph beside the cloud; [LoraPlane.Off] renders nothing.
    val loraPlane: LoraPlane = LoraPlane.Off,
    // The radio-off warning banner to show (or null), already accounting for the user's dismissal.
    val radioWarning: RadioWarning? = null,
    // First run: show the getting-started hint under the Nearby row. True only while there is nothing on
    // this screen to open — see the flag's computation in [state] for what retires it.
    val showGettingStarted: Boolean = false,
    // True only for the initial seed value (see [state]'s stateIn below), before the underlying Room +
    // DataStore + mesh flows have all first-emitted. The list shows a skeleton instead of a blank screen
    // for that ~1s cold-start gap. Defaults false so every real combine emission — and the previews —
    // render content; only the seed passes true.
    val isLoading: Boolean = false,
)

/**
 * Read-only projection of the conversation list. The per-conversation read watermarks
 * ([SettingsStore.lastReadAll]) are written by [app.getknit.knit.ui.chat.ChatViewModel] while a chat
 * is on screen; this VM only reads them to compute unread badges.
 */
class ChatListViewModel(
    private val messages: MessageRepository,
    peers: PeerRepository,
    settings: SettingsStore,
    identity: Identity,
    meshManager: MeshController,
    private val groups: GroupRepository,
    // The facts flow rather than the repository, for the reason spelled out on ChatViewModel's copy of this
    // parameter: the production flow is an infinite poller, which a test driving this VM with
    // `advanceUntilIdle()` could never let go idle.
    relayFacts: Flow<RelayFacts>,
    loraFacts: Flow<LoraFacts>,
    private val context: Context,
) : ViewModel() {
    private val myNodeId = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch { myNodeId.value = identity.nodeId() }
    }

    // Messages, the blocklist, and groups are pre-combined so the outer combine stays at the 5-flow
    // typed overload. Blocked senders' messages are filtered out, and their DM thread is dropped below.
    private data class ListBundle(
        val messages: List<MessageEntity>,
        val blocked: Set<String>,
        val groups: List<GroupEntity>,
        val accepted: Set<String>,
    )

    // Neighbor count + radio health + the (already-dismissal-aware) banner + Internet-plane state, folded
    // into one source.
    private data class MeshStatus(
        val neighborCount: Int,
        val health: TransportHealth,
        val warning: RadioWarning?,
        val relayPlane: RelayPlane,
        val loraPlane: LoraPlane,
        /** The board's primary channel as the Meshtastic room names it, while live — the row's title. */
        val publicChannel: String?,
        /** Whether the Meshtastic room exists on this phone at all (`SettingsStore.loraRoomEnabled`). */
        val loraRoom: Boolean,
    )

    private val messagesAndBlocks =
        combine(
            messages.observeMessages(), // ORDER BY sentAt ASC -> newest is last()
            settings.blockedNodeIds,
            groups.observeGroups(),
            settings.acceptedConversations,
        ) { msgs, blocked, groupList, accepted ->
            ListBundle(msgs.filter { it.senderId !in blocked }, blocked, groupList, accepted)
        }

    // Radio-off banner: which warning the per-radio statuses imply, and whether the user has dismissed it.
    // The critical AllRadiosOff warning is never stored in [dismissed], so it always shows (not dismissible).
    private val dismissed = MutableStateFlow<RadioWarning?>(null)

    private val rawWarning =
        meshManager.transportStatuses
            .map { radioWarningFor(it) }
            .distinctUntilChanged()

    private val visibleWarning =
        combine(rawWarning, dismissed) { warning, hidden ->
            if (warning != null && warning != hidden) warning else null
        }

    init {
        // Re-arm: when radios recover (warning clears), forget any prior dismissal so a later off-episode
        // shows the banner again.
        viewModelScope.launch { rawWarning.collect { if (it == null) dismissed.value = null } }
    }

    // Neighbor count + radio health + the banner folded into one source so the main state combine stays
    // within its five-flow arity.
    // Collapsed to the coarse plane state (and de-duped) before it reaches the combine: the facts flow
    // re-emits on scope-table churn this screen has no opinion about, and every such emission would
    // otherwise rebuild the whole conversation list.
    private val relayPlane =
        relayFacts.map { planeFor(it) }.distinctUntilChanged()

    // Same collapse for the LoRa plane: the facts also carry the DM switch and the battery, which this screen
    // never reads. The primary channel's name rides beside the plane because the Meshtastic room's row is
    // titled by it, and the room switch because it decides whether that row exists.
    private val loraRoom =
        loraFacts.map { Triple(it.plane, it.primaryChannel, it.room) }.distinctUntilChanged()

    private val meshStatus =
        combine(
            meshManager.neighborCount,
            meshManager.transportHealth,
            visibleWarning,
            relayPlane,
            loraRoom,
        ) { count, health, warning, plane, (loraPlane, channel, room) ->
            MeshStatus(count, health, warning, plane, loraPlane, channel, room)
        }

    val state: StateFlow<ChatListUiState> =
        combine(
            messagesAndBlocks,
            peers.observeDirectory(),
            settings.lastReadAll,
            myNodeId,
            meshStatus,
        ) { bundle, directory, lastReadAll, me, mesh ->
            val msgs = bundle.messages
            val blocked = bundle.blocked
            val activeGroups = bundle.groups.filter { !it.left }
            val groupIds = bundle.groups.map { it.groupId }.toSet() // left groups too, to hide stray rows
            val peersByNode = directory.byNode
            val byConversation = msgs.groupBy { it.conversationId }
            // Partition out stranger "message requests" using the SAME shared predicate as the notify gate
            // (Nearby / accepted-set / verified peer / self-authored) so this list and the gate agree. A
            // pending DM/group is dropped from here and surfaced in the Message Requests inbox instead.
            val accepted = bundle.accepted
            val verified =
                directory.peers
                    .filter { it.verified }
                    .map { it.nodeId }
                    .toSet()
            val authored = msgs.filter { it.senderId == me }.map { it.conversationId }.toSet()
            // Senders per thread, so a group a known peer has posted in reads as a chat rather than a request.
            val sendersByConversation = byConversation.mapValues { (_, tms) -> tms.map { it.senderId }.toSet() }

            fun isPending(conversationId: String): Boolean =
                conversationId !in blocked &&
                    !Conversations.isAccepted(
                        conversationId,
                        accepted,
                        verified,
                        authored,
                        sendersByConversation[conversationId].orEmpty(),
                    )

            fun rowFor(
                conversationId: String,
                threadMsgs: List<MessageEntity>,
                title: String,
                isRoom: Boolean,
                isGroup: Boolean,
                avatarHash: String?,
                discriminator: String? = null,
                isBridged: Boolean = false,
            ): ConversationRow {
                // Status notices are invisible to this list, entirely: they are not the thread's "last
                // message", so they never become its preview and never re-sort it to the top. A contact
                // renaming themselves is worth a line inside the thread and is not worth reordering
                // someone's chat list — and a notice's senderId is the event's *subject* rather than an
                // author, so treating one as the last message would also mis-attribute the preview.
                val last = threadMsgs.lastOrNull { !it.isStatusNotice }
                val lastReadAt = lastReadAll[conversationId] ?: 0L

                // "Ours" means we wrote it. A heard Meshtastic post sits in our sender column by convention
                // (the phone whose board heard it writes the row) — but we did not write a word of it, and
                // treating it as ours would exempt it from the unread count and hang a delivery tick on
                // somebody else's words.
                fun MessageEntity.isOurs() = senderId == me && originNode == null
                // Until our own id resolves, count nothing as unread so our own messages aren't miscounted.
                val unread =
                    if (me == null) {
                        0
                    } else {
                        threadMsgs.count { it.sentAt > lastReadAt && !it.isOurs() && !it.isStatusNotice }
                    }
                // The tick, and only for our own sends: a notice was never sent anywhere. Redundant with
                // the filter on `last` above and kept anyway — the two express different rules, and this
                // one is what guarantees no notice can ever grow a delivery tick.
                val mineLast = last?.takeIf { it.isOurs() && !it.isStatusNotice }
                return ConversationRow(
                    id = conversationId,
                    title = title,
                    avatarHash = avatarHash,
                    isRoom = isRoom,
                    isGroup = isGroup,
                    lastPreview = last?.let { previewFor(it, directory, me, isDm = !isRoom && !isGroup) },
                    lastMessageAt = last?.sentAt,
                    unreadCount = unread,
                    lastStatus = mineLast?.let { DeliveryStatus.of(it) },
                    lastDeliveredVia = mineLast?.receivedPlane ?: DeliveryPlane.Unknown,
                    discriminator = discriminator,
                    isBridged = isBridged,
                )
            }

            // The Nearby room is always present (even with no messages yet). Groups appear from the groups
            // table (so a freshly created group shows even before its first message); DM threads appear once
            // they have a message — excluding any conversation that is actually a group. Most-recent first.
            val nearby =
                rowFor(
                    Conversations.NEARBY,
                    byConversation[Conversations.NEARBY].orEmpty(),
                    title = context.getString(R.string.nearby_title),
                    isRoom = true,
                    isGroup = false,
                    avatarHash = null,
                )
            // The Meshtastic room is this phone's own radio's channel, so it exists whenever a radio is bound
            // — empty until the channel speaks, like Nearby — and stays while history does after the radio
            // goes. Never on a phone with no radio and no history: a standing empty row there would be an
            // offer of something this install cannot have. And never at all once the user has switched the
            // room off: that hides the row **including** its history, which is the whole of what "hidden"
            // means here — the rows stay in the database and come back with the switch.
            val bridgedMsgs = byConversation[Conversations.MESHTASTIC].orEmpty()
            val bridged =
                if (mesh.loraRoom && (mesh.loraPlane != LoraPlane.Off || bridgedMsgs.isNotEmpty())) {
                    rowFor(
                        Conversations.MESHTASTIC,
                        bridgedMsgs,
                        // The live board's channel, else the newest post's, else the generic label — the
                        // same rule the thread header uses, so the list and the screen agree.
                        title = meshRoomChannel(mesh.publicChannel, bridgedMsgs) ?: context.getString(R.string.meshtastic_title),
                        isRoom = true,
                        isGroup = false,
                        avatarHash = null,
                        isBridged = true,
                    )
                } else {
                    null
                }
            val groupRows =
                activeGroups.filter { !isPending(it.groupId) }.map { g ->
                    val title =
                        groupTitle(
                            storedName = g.name,
                            memberIds = GroupMembersStore.decode(g.members),
                            selfId = me,
                            fallback = context.getString(R.string.group_unnamed),
                        ) { id -> directory.label(id).text }
                    val row =
                        rowFor(
                            g.groupId,
                            byConversation[g.groupId].orEmpty(),
                            title = title,
                            isRoom = false,
                            isGroup = true,
                            avatarHash = g.photoHash,
                        )
                    // An empty group sorts/labels by its creation time so it isn't stranded at the bottom.
                    if (row.lastMessageAt == null) row.copy(lastMessageAt = g.createdAt) else row
                }
            val dms =
                byConversation
                    .filterKeys {
                        it != Conversations.NEARBY &&
                            it != Conversations.MESHTASTIC &&
                            it !in blocked &&
                            it !in groupIds &&
                            !isPending(it)
                    }.map { (conversationId, threadMsgs) ->
                        rowFor(
                            conversationId,
                            threadMsgs,
                            title = directory.label(conversationId).text,
                            isRoom = false,
                            isGroup = false,
                            avatarHash = peersByNode[conversationId]?.avatarHash,
                            discriminator = directory.label(conversationId).discriminator,
                        )
                    }
            // Count of threads moved to the requests inbox (mirrors exactly what the two filters above drop).
            val requestCount =
                byConversation.keys.count {
                    it != Conversations.NEARBY && it != Conversations.MESHTASTIC && it !in groupIds && isPending(it)
                } + activeGroups.count { isPending(it.groupId) }
            // The list is never literally empty — the Nearby room always has a row — so a fresh install
            // reads as a working screen with nothing to do on it. Nudge until there is: any Nearby message,
            // a group, a DM, or a pending request. Deleting every thread again brings the hint back, which
            // is the state it is written for.
            val gettingStarted =
                nearby.lastMessageAt == null &&
                    bridged?.lastMessageAt == null &&
                    groupRows.isEmpty() &&
                    dms.isEmpty() &&
                    requestCount == 0
            ChatListUiState(
                conversations =
                    (listOf(nearby) + listOfNotNull(bridged) + groupRows + dms)
                        .sortedByDescending { it.lastMessageAt ?: 0L },
                requestCount = requestCount,
                neighborCount = mesh.neighborCount,
                transportHealth = mesh.health,
                relayPlane = mesh.relayPlane,
                loraPlane = mesh.loraPlane,
                radioWarning = mesh.warning,
                showGettingStarted = gettingStarted,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatListUiState(isLoading = true))

    /**
     * Hides the currently-shown radio-off banner. Only the dismissible warnings (Bluetooth/Wi-Fi off) are
     * recorded — the critical [RadioWarning.AllRadiosOff] is intentionally not dismissible, so a request to
     * dismiss it is ignored. A recorded dismissal is forgotten once the radios recover (see the re-arm
     * collector), so a later off-episode shows the banner again.
     */
    fun dismissRadioWarning() {
        state.value.radioWarning
            ?.takeIf { it != RadioWarning.AllRadiosOff }
            ?.let { dismissed.value = it }
    }

    /**
     * "Sender: body" preview, mirroring how ChatViewModel resolves names and labels own messages.
     * In a 1:1 DM the peer's name is already the row title, so an incoming message shows just its body;
     * our own messages still get the "You: …" prefix (it's not the recipient's name and signals who spoke).
     */
    private fun previewFor(
        message: MessageEntity,
        directory: PeerDirectory,
        me: String?,
        isDm: Boolean,
    ): String {
        val body =
            when {
                message.body.isNotBlank() -> {
                    message.body
                }

                message.attachmentHash != null -> {
                    attachmentPreview(context, message)
                }

                else -> {
                    ""
                }
            }
        // A heard Meshtastic post's author is the speaker, never us — the row sits in our sender column by
        // convention, so without this the preview would read "You: …" over somebody else's words. A speaker
        // whose board a contact's profile claims is named as that contact; a stranger is the NodeDB name the
        // board had for them, else the `!hex` id every Meshtastic client would show.
        message.originNode?.let { node ->
            val contact = message.originPeerId?.let { directory.label(it) }
            val speaker = contact?.text ?: message.originName?.takeIf { it.isNotBlank() } ?: meshNodeLabel(node)
            return context.getString(R.string.chat_list_preview_with_sender, speaker, body)
        }
        val isOwn = message.senderId == me
        if (isDm && !isOwn) return body
        val sender =
            if (isOwn) {
                context.getString(R.string.chat_self_name)
            } else {
                directory.label(message.senderId).text
            }
        return context.getString(R.string.chat_list_preview_with_sender, sender, body)
    }

    /**
     * Deletes a conversation locally: clears its messages (DM/group/bridged room) and, for a group,
     * hard-deletes the group row so it leaves the list but can be re-added by a future group frame. Nearby is
     * not deletable. Sends nothing over the mesh; the list updates from the underlying flows.
     */
    fun deleteConversation(conversationId: String) {
        viewModelScope.launch {
            when (Conversations.kindFor(conversationId)) {
                ConversationKind.NEARBY -> Unit

                // the broadcast room can't be deleted
                ConversationKind.GROUP -> groups.delete(conversationId)

                // Unlike Nearby, the Meshtastic room *is* clearable: the history goes, and the row stays
                // only while a radio is bound — the honest way to say "not interested" in a channel that
                // arrives unasked.
                ConversationKind.MESHTASTIC, ConversationKind.DM -> messages.deleteByConversation(conversationId)
            }
        }
    }
}
