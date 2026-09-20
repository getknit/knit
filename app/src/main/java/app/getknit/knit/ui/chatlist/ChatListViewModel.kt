package app.getknit.knit.ui.chatlist

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerDirectory
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.commons.CommonsEntity
import app.getknit.knit.data.commons.CommonsRepository
import app.getknit.knit.data.draft.DraftEntity
import app.getknit.knit.data.draft.DraftRepository
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.message.ConversationKind
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.data.message.GroupFace
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.relay.RelayFacts
import app.getknit.knit.data.relay.RelayPlane
import app.getknit.knit.data.relay.planeFor
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.mesh.MeshController
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.mesh.lora.LoraFacts
import app.getknit.knit.mesh.lora.LoraPlane
import app.getknit.knit.transfer.TransferManager
import app.getknit.knit.transfer.TransferState
import app.getknit.knit.ui.ConversationTable
import app.getknit.knit.ui.chat.DeliveryStatus
import app.getknit.knit.ui.observeConversationTable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One row in the conversation list: the [Conversations.NEARBY] broadcast room ([isRoom] true), a
 * group chat ([isGroup] true, keyed by the group id, [title] is the group name, [avatarHash] its photo
 * or null for the glyph), or a 1:1 DM keyed by the peer's node id with the peer's [title]/[avatarHash].
 * [lastPreview]/[lastMessageAt] are null when the conversation has no messages yet, and a status notice
 * speaks for neither — except a direct-transfer row, which does both (see `rowFor`).
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
    // The other members a photo-less group draws as its avatar (`groupFaces`, ADR 2026-09.zapp); empty for
    // a DM, a room, or a group with fewer than two others, which wears a tinted glyph instead.
    val faces: List<GroupFace> = emptyList(),
    val lastPreview: String?,
    // Whether [lastPreview] is a direct transfer's line, so the row draws the feature's mark beside it
    // rather than an emoji. The glyph itself stays in the UI layer; this only says which line it belongs to.
    val previewIsTransfer: Boolean = false,
    val lastMessageAt: Long?,
    val unreadCount: Int,
    val lastStatus: DeliveryStatus? = null,
    val lastDeliveredVia: DeliveryPlane = DeliveryPlane.Unknown,
    // The ` (Alias)` suffix already inside [title] when another known peer shares this DM peer's name
    // (ADR 058), so the row can draw it muted. Null for the room, groups, and an unambiguous name.
    val discriminator: String? = null,
    // The unsent text sitting in this thread's composer, but **only** when it is newer than [lastMessageAt]
    // — the row then reads "Draft: …" in italics in place of [lastPreview], the way Signal's does. Null
    // whenever there is no draft or the thread has moved on since it was typed, which is the honest reading:
    // the newest thing in the conversation is what the preview line is for.
    val draft: String? = null,
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
    // This identity was seen running on another phone since the user last dismissed the notice
    // (`SettingsStore.cloneSeenAt` past `cloneDismissedAt`, ADR 2026-09.ypcc): the sign-out banner.
    val cloneVisible: Boolean = false,
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
 *
 * Nothing here reads a thread, let alone the table. Every row comes from per-thread summaries
 * ([ConversationTable]: the newest speaking row, the sender set, the authored set) plus one indexed count
 * per drawn row for its badge, so the screen costs the same whether a thread holds ten messages or a
 * hundred thousand — which matters because an accepted thread is never trimmed.
 */
class ChatListViewModel(
    private val messages: MessageRepository,
    peers: PeerRepository,
    private val settings: SettingsStore,
    identity: Identity,
    private val meshManager: MeshController,
    private val groups: GroupRepository,
    private val drafts: DraftRepository,
    // Live direct-transfer state, for the one thing the persisted row cannot say: a record left non-terminal
    // by a process death. The chat card folds the same two sources the same way (`transferViewFor`).
    transfers: TransferManager,
    // The facts flow rather than the repository, for the reason spelled out on ChatViewModel's copy of this
    // parameter: the production flow is an infinite poller, which a test driving this VM with
    // `advanceUntilIdle()` could never let go idle.
    relayFacts: Flow<RelayFacts>,
    loraFacts: Flow<LoraFacts>,
    private val context: Context,
    // The joined commons (§7.4): a row per room from the join on, and the leave behind "delete". Nullable and
    // last so the positional test rigs still compile; production always passes it.
    private val commons: CommonsRepository? = null,
) : ViewModel() {
    private val myNodeId = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch { myNodeId.value = identity.nodeId() }
    }

    // The settings-, group- and draft-shaped inputs, pre-combined so the outer combine stays at the 5-flow
    // typed overload. [bridgedChannel] is the newest heard post's channel in the Meshtastic room — the list
    // half of `meshRoomChannel`, read as one scalar rather than off the thread.
    internal data class ListInputs(
        val accepted: Set<String>,
        val groups: List<GroupEntity>,
        val drafts: Map<String, DraftEntity>,
        val transfers: Map<String, TransferState>,
        val bridgedChannel: String?,
        val commons: List<CommonsEntity> = emptyList(),
    )

    // Neighbor count + radio health + the (already-dismissal-aware) banner + Internet-plane state, folded
    // into one source.
    internal data class MeshStatus(
        val neighborCount: Int,
        val health: TransportHealth,
        val warning: RadioWarning?,
        /** See [ChatListUiState.cloneVisible]. */
        val cloneVisible: Boolean,
        val relayPlane: RelayPlane,
        val loraPlane: LoraPlane,
        /** The board's primary channel as the Meshtastic room names it, while live — the row's title. */
        val publicChannel: String?,
        /** Whether the Meshtastic room exists on this phone at all (`SettingsStore.loraRoomEnabled`). */
        val loraRoom: Boolean,
    )

    // Paired before the combine below rather than added to it: that combine sits on the typed five-flow
    // overload on purpose, and a sixth argument would drop it onto the untyped vararg one.
    private val draftsAndTransfers =
        combine(drafts.all, transfers.states) { draftRows, live -> draftRows to live }

    // Per-thread summaries for every conversation, blocked senders left out of each (so a blocked peer's
    // message never becomes a row's preview, and their DM thread is dropped below).
    private val table = messages.observeConversationTable(CHAT_LIST_KINDS, settings.blockedNodeIds, myNodeId)

    // De-duplicated where the source is DataStore-backed: those flows re-emit on every preferences write,
    // and an unchanged accepted set or channel name is no reason to rebuild the list.
    private val listInputs =
        combine(
            settings.acceptedConversations.distinctUntilChanged(),
            groups.observeGroups(),
            draftsAndTransfers,
            messages.observeNewestOriginChannel(Conversations.MESHTASTIC).distinctUntilChanged(),
            commons?.observeAll() ?: flowOf(emptyList()),
        ) { accepted, groupList, (draftRows, live), channel, rooms -> ListInputs(accepted, groupList, draftRows, live, channel, rooms) }

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

    // The clone notice is two persisted stamps, so it survives a restart and a mesh that is off; paired with
    // the relay plane to keep the combine below on its typed five-flow overload.
    private val cloneVisible =
        combine(settings.cloneSeenAt, settings.cloneDismissedAt) { seen, dismissed -> seen > dismissed }
            .distinctUntilChanged()

    private val meshStatus =
        combine(
            meshManager.neighborCount,
            meshManager.transportHealth,
            combine(visibleWarning, cloneVisible) { warning, clone -> warning to clone },
            relayPlane,
            loraRoom,
        ) { count, health, (warning, clone), plane, (loraPlane, channel, room) ->
            MeshStatus(count, health, warning, clone, plane, loraPlane, channel, room)
        }

    /** Everything one emission of [state] is built from — the five combined sources, named. */
    internal data class Snapshot(
        val table: ConversationTable,
        val inputs: ListInputs,
        val directory: PeerDirectory,
        val lastReadAll: Map<String, Long>,
        val mesh: MeshStatus,
    )

    // `mapLatest` because the fold ends in one small query per drawn row (the unread count), and a newer
    // snapshot — every write to the messages table is one — should cancel a stale fold rather than queue
    // behind it. The Room-backed sources re-emit on every write on purpose (see `observeConversationTable`);
    // an emission that changes nothing is deduplicated by the StateFlow, so the screen never recomposes for it.
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<ChatListUiState> =
        combine(
            table,
            listInputs,
            peers.observeDirectory(),
            settings.lastReadAll.distinctUntilChanged(),
            meshStatus,
        ) { table, inputs, directory, lastReadAll, mesh ->
            Snapshot(table, inputs, directory, lastReadAll, mesh)
        }.mapLatest { snapshot -> ChatListAssembler(context, snapshot).build().withUnread(snapshot) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatListUiState(isLoading = true))

    /**
     * Fills each drawn row's unread badge from one indexed count per thread: ordinary messages newer than
     * the thread's read watermark that are not ours (a heard Meshtastic post counts — it sits in our sender
     * column by convention, but somebody else said it) and not from a blocked sender. Until our own id
     * resolves, count nothing as unread so our own messages aren't miscounted. Rows the list does not draw
     * — pending requests — are never counted.
     */
    private suspend fun ChatListUiState.withUnread(snapshot: Snapshot): ChatListUiState {
        val me = snapshot.table.me ?: return this
        return copy(
            conversations =
                conversations.map { row ->
                    val since = snapshot.lastReadAll[row.id] ?: 0L
                    row.copy(unreadCount = messages.countUnreadIn(row.id, since, me, snapshot.table.blocked))
                },
        )
    }

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
     * Hides the "also active on another phone" banner — the user says the other phone is gone. Persisted:
     * only a twin frame stamped after this moment brings it back (`mesh/CloneWatch`), so a custody re-serve
     * of what the other phone published before it was wiped stays quiet.
     */
    fun dismissClone() {
        viewModelScope.launch { settings.setCloneDismissedAt(System.currentTimeMillis()) }
    }

    /**
     * Deletes a conversation locally: clears its messages (DM/group/bridged room) and, for a group,
     * hard-deletes the group row so it leaves the list but can be re-added by a future group frame. Nearby is
     * not deletable. Sends nothing over the mesh; the list updates from the underlying flows.
     */
    fun deleteConversation(conversationId: String) {
        viewModelScope.launch {
            // An unsent line belongs to the thread it was typed in, and goes when the thread does.
            drafts.clear(conversationId)
            when (Conversations.kindFor(conversationId)) {
                // the broadcast room can't be deleted
                ConversationKind.NEARBY -> {}

                ConversationKind.GROUP -> {
                    groups.delete(conversationId)
                }

                // Unlike Nearby, the Meshtastic room *is* clearable: the history goes, and the row stays
                // only while a radio is bound — the honest way to say "not interested" in a channel that
                // arrives unasked.
                ConversationKind.MESHTASTIC, ConversationKind.DM -> {
                    messages.deleteByConversation(conversationId)
                }

                // A commons goes as a whole — the room, its key, its history — and its relay is told to stop
                // subscribing it now rather than at the next tick.
                ConversationKind.COMMONS -> {
                    commons?.leave(conversationId)
                    meshManager.refreshRelays()
                }
            }
        }
    }

    private companion object {
        /**
         * The rows that may stand as a thread's last one — its preview line, its time, its place in the
         * list. Status notices may not; a direct-transfer row is the exception, for the reasons
         * `ChatListAssembler.rowFor` sets out.
         */
        val CHAT_LIST_KINDS = setOf(MessageEntity.KIND_NORMAL, MessageEntity.KIND_FILE_TRANSFER)
    }
}
