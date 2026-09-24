package app.getknit.knit.ui.search

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerDirectory
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.commons.CommonsEntity
import app.getknit.knit.data.commons.CommonsRepository
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.message.ConversationKind
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.GroupFace
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.search.SearchQuery
import app.getknit.knit.data.search.Snippets
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.identity.PeerLabel
import app.getknit.knit.mesh.lora.LoraFacts
import app.getknit.knit.ui.ConversationTable
import app.getknit.knit.ui.ConversationTitle
import app.getknit.knit.ui.MeshRoomInputs
import app.getknit.knit.ui.contacts.contactIds
import app.getknit.knit.ui.observeConversationTable
import app.getknit.knit.ui.speakerLabel
import app.getknit.knit.ui.visibleConversations
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A thread that matched by name. */
data class ChatHit(
    val id: String,
    val title: String,
    val discriminator: String?,
    val avatarHash: String?,
    val kind: ConversationKind,
    /** A photo-less group's cluster, as the chat list draws it (`ConversationTitle.faces`); empty otherwise. */
    val faces: List<GroupFace> = emptyList(),
)

/** A contact that matched by name or alias. The alias is shown outright — a precision surface (ADR 058). */
data class PersonHit(
    val nodeId: String,
    val name: String,
    val alias: String,
    val avatarHash: String?,
)

/** A message that matched by what it said. */
data class MessageHit(
    val id: String,
    val conversationId: String,
    val conversationTitle: String,
    val kind: ConversationKind,
    val avatarHash: String?,
    /** A photo-less group's cluster, as the chat list draws it (`ConversationTitle.faces`); empty otherwise. */
    val faces: List<GroupFace> = emptyList(),
    /** Who said it, as the chat list would name them; null when the DM's peer did (the title already names them). */
    val sender: String?,
    /** One line around the match — [Snippets]. */
    val snippet: String,
    /** The matched span inside [snippet], to draw bold; null when the match could not be placed in the text. */
    val hit: IntRange?,
    val sentAt: Long,
)

/** What the search screen draws: the three sections that answered [forQuery]. */
data class SearchUiState(
    /** The trimmed query these results answer. */
    val forQuery: String = "",
    val chats: List<ChatHit> = emptyList(),
    val people: List<PersonHit> = emptyList(),
    val messages: List<MessageHit> = emptyList(),
    /** The field holds a newer, non-blank query than [forQuery] — an answer is on its way. */
    val isSearching: Boolean = false,
) {
    val isEmpty: Boolean get() = chats.isEmpty() && people.isEmpty() && messages.isEmpty()
}

/**
 * App-wide search: one query over the chat list's own universe. **Chats** are the threads the list shows,
 * titled as it titles them ([visibleConversations]); **People** are the picker's established contacts
 * ([contactIds]), matched by name or alias; **Messages** are ordinary bodies from those same threads,
 * newest first, through the `messages_fts` index ([MessageRepository.search]). A stranger's request thread
 * is in none of the three, by the same predicate that keeps it off the list (ADR 009).
 *
 * The typed text lives in [query] (never in DataStore — `rules/coding.md`); the results answer a debounced
 * copy of it, so a keystroke never waits on a search, and a newer query cancels a search still running.
 * [SearchUiState.isSearching] is derived from the two rather than toggled around the work, so a cancelled
 * search can never leave it stuck.
 */
class SearchViewModel(
    private val messages: MessageRepository,
    peers: PeerRepository,
    groups: GroupRepository,
    settings: SettingsStore,
    identity: Identity,
    // The facts flow rather than the repository, for the reason spelled out on ChatViewModel's copy of this
    // parameter: the production flow is an infinite poller, which a test could never let go idle.
    loraFacts: Flow<LoraFacts>,
    private val context: Context,
    // The joined commons (§7.4), so a room's posts search like a group's. Nullable and last for the test rigs.
    commons: CommonsRepository? = null,
) : ViewModel() {
    /** The field's text, exactly as typed. */
    val query = MutableStateFlow("")

    fun setQuery(text: String) {
        query.value = text
    }

    fun clear() = setQuery("")

    private val myNodeId = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch { myNodeId.value = identity.nodeId() }
    }

    // Per-thread summaries for every conversation — the list's own read, for the list's own universe.
    private val table = messages.observeConversationTable(setOf(MessageEntity.KIND_NORMAL), settings.blockedNodeIds, myNodeId)

    // The settings-, group- and radio-shaped inputs, pre-combined so the results combine stays small.
    // De-duplicated where the source is DataStore-backed: those flows re-emit on every preferences write.
    internal data class Inputs(
        val accepted: Set<String>,
        val groups: List<GroupEntity>,
        val hideFlagged: Boolean,
        val meshRoom: MeshRoomInputs,
        val commons: List<CommonsEntity> = emptyList(),
    )

    // The two thread tables (groups, joined commons) are paired ahead of the combine below, which sits on
    // the typed five-flow overload; a sixth argument would drop it onto the untyped vararg one.
    private val threadTables =
        combine(groups.observeGroups(), commons?.observeAll() ?: flowOf(emptyList())) { groupList, rooms -> groupList to rooms }

    private val inputs =
        combine(
            settings.acceptedConversations.distinctUntilChanged(),
            threadTables,
            messages.observeNewestOriginChannel(Conversations.MESHTASTIC).distinctUntilChanged(),
            settings.contentFilteringEnabled.distinctUntilChanged(),
            loraFacts.map { Triple(it.plane, it.primaryChannel, it.room) }.distinctUntilChanged(),
        ) { accepted, (groupList, rooms), newestChannel, hideFlagged, (plane, liveChannel, room) ->
            Inputs(accepted, groupList, hideFlagged, MeshRoomInputs(room, plane, liveChannel, newestChannel), rooms)
        }

    // A quarter-second after the last keystroke; clearing is instant, so Back-then-retype never waits.
    @OptIn(FlowPreview::class)
    private val debouncedQuery =
        query
            .map { it.trim() }
            .debounce { if (it.isEmpty()) 0L else DEBOUNCE_MS }
            .distinctUntilChanged()

    /** Everything one answer is built from. */
    internal data class Snapshot(
        val query: String,
        val table: ConversationTable,
        val directory: PeerDirectory,
        val inputs: Inputs,
    )

    // `mapLatest` so a newer snapshot — a newer query, or a write to the messages table — cancels a search
    // still running rather than queueing behind it.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val results: Flow<SearchUiState> =
        combine(debouncedQuery, table, peers.observeDirectory(), inputs) { q, t, d, i -> Snapshot(q, t, d, i) }
            .mapLatest { search(it) }

    val state: StateFlow<SearchUiState> =
        combine(query, results) { raw, answer ->
            val typed = raw.trim()
            answer.copy(isSearching = typed.isNotEmpty() && typed != answer.forQuery)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

    private suspend fun search(s: Snapshot): SearchUiState {
        if (s.query.isEmpty()) return SearchUiState()
        val tokens = SearchQuery.tokens(s.query)
        if (tokens.isEmpty()) return SearchUiState(forQuery = s.query)
        val visible =
            visibleConversations(context, s.table, s.inputs.groups, s.directory, s.inputs.accepted, s.inputs.meshRoom, s.inputs.commons)
        return SearchUiState(
            forQuery = s.query,
            chats = chatsFor(visible, tokens),
            people = peopleFor(s, tokens),
            messages = messagesFor(s, visible, tokens),
        )
    }

    private fun chatsFor(
        visible: List<ConversationTitle>,
        tokens: List<String>,
    ): List<ChatHit> =
        visible
            .asSequence()
            .map { it to SearchQuery.fold(it.text) }
            .filter { (_, folded) -> SearchQuery.matches(tokens, folded) }
            .sortedWith(compareBy({ (_, folded) -> SearchQuery.rank(folded, tokens) }, { (thread, _) -> thread.text.lowercase() }))
            .take(CHAT_LIMIT)
            .map { (thread, _) -> ChatHit(thread.id, thread.text, thread.discriminator, thread.avatarHash, thread.kind, thread.faces) }
            .toList()

    /** The picker's contacts, matched on the name or the alias — "riv sam" and "quiet lantern" both find Sam. */
    private fun peopleFor(
        s: Snapshot,
        tokens: List<String>,
    ): List<PersonHit> {
        val me = s.table.me ?: return emptyList()
        val ids =
            contactIds(
                s.table.conversations,
                s.table.authored,
                s.inputs.groups,
                s.table.groupSenders,
                s.inputs.accepted,
                s.directory.verified,
                s.table.blocked,
                me,
            )
        return ids
            .asSequence()
            .map { s.directory.label(it) }
            .map { label -> label to SearchQuery.fold(label.name) }
            .filter { (label, foldedName) -> SearchQuery.matches(tokens, foldedName, SearchQuery.fold(label.alias)) }
            .sortedWith(compareBy({ (_, folded) -> SearchQuery.rank(folded, tokens) }, { (label, _) -> label.name.lowercase() }))
            .take(PEOPLE_LIMIT)
            .map { (label, _) -> label.toHit(s.directory) }
            .toList()
    }

    private fun PeerLabel.toHit(directory: PeerDirectory) = PersonHit(nodeId, name, alias, directory.byNode[nodeId]?.avatarHash)

    /** Message hits, only once the query is worth asking the index ([SearchQuery.isSearchable]). */
    private suspend fun messagesFor(
        s: Snapshot,
        visible: List<ConversationTitle>,
        tokens: List<String>,
    ): List<MessageHit> {
        if (!SearchQuery.isSearchable(s.query)) return emptyList()
        val byId = visible.associateBy { it.id }
        return messages
            .search(s.query, byId.keys, s.table.blocked, s.inputs.hideFlagged, MESSAGE_LIMIT)
            .mapNotNull { row -> byId[row.conversationId]?.let { thread -> messageHit(row, thread, tokens, s) } }
    }

    private fun messageHit(
        row: MessageEntity,
        thread: ConversationTitle,
        tokens: List<String>,
        s: Snapshot,
    ): MessageHit {
        val snippet = Snippets.around(row.body, tokens)
        return MessageHit(
            id = row.id,
            conversationId = row.conversationId,
            conversationTitle = thread.text,
            kind = thread.kind,
            avatarHash = thread.avatarHash,
            faces = thread.faces,
            sender = speakerLabel(context, row, s.directory, s.table.me, isDm = thread.kind == ConversationKind.DM),
            snippet = snippet.text,
            hit = snippet.hit,
            sentAt = row.sentAt,
        )
    }

    companion object {
        /** How long after the last keystroke the search runs. */
        const val DEBOUNCE_MS = 250L

        /** Per-section caps: a query this broad wants refining, not scrolling. */
        const val CHAT_LIMIT = 10
        const val PEOPLE_LIMIT = 10
        const val MESSAGE_LIMIT = 100
    }
}
