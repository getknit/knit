package app.getknit.knit.ui.search

import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.getknit.knit.R
import app.getknit.knit.data.message.ConversationKind
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.GroupFace
import app.getknit.knit.data.search.SearchQuery
import app.getknit.knit.identity.PeerLabel
import app.getknit.knit.ui.components.Avatar
import app.getknit.knit.ui.components.GroupAvatar
import app.getknit.knit.ui.components.PeerNameText
import app.getknit.knit.ui.components.RoomAvatar
import app.getknit.knit.ui.components.noAutofillMenu
import app.getknit.knit.ui.preview.KnitPreview
import app.getknit.knit.ui.preview.PREVIEW_NOW
import app.getknit.knit.ui.util.compactTimeAgo
import app.getknit.knit.ui.util.relativeTimeLabels
import app.getknit.knit.ui.util.rememberCurrentTimeMillis
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.emptyParametersHolder
import org.koin.core.parameter.parametersOf

// The keyboard follows a focus request only once the window has focus, which lands a frame or two after
// a navigation; a bounded wait so a host where it never flips (Robolectric) still focuses the field.
private const val FOCUS_WAIT_MS = 500L

/**
 * App-wide search, reached from the magnifier on the chat list: one field at the top, and below it the
 * chats, people and messages that match, each a tap from its thread. A message hit opens the thread on
 * that message. The field is bound straight to [SearchViewModel.query], so a keystroke never waits on the
 * results combine; the answer arrives a debounce later. Given [inConversation] it is "Search in this chat",
 * reached from a thread's overflow: that thread's messages alone (ADR 2026-10.4cyt).
 */
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenConversation: (conversationId: String) -> Unit,
    onOpenMessage: (conversationId: String, messageId: String) -> Unit,
    inConversation: String? = null,
    viewModel: SearchViewModel =
        koinViewModel { if (inConversation == null) emptyParametersHolder() else parametersOf(inConversation) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val now by rememberCurrentTimeMillis()
    // Focus the field only on arrival with nothing typed: returning from a thread (query intact, results
    // showing) must not throw the keyboard back over the results.
    val autoFocus = remember { query.isEmpty() }
    SearchScreenContent(
        state = state,
        query = query,
        now = now,
        autoFocus = autoFocus,
        scoped = viewModel.inConversation != null,
        onQueryChange = viewModel::setQuery,
        onClear = viewModel::clear,
        onOpenConversation = onOpenConversation,
        onOpenMessage = onOpenMessage,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchScreenContent(
    state: SearchUiState,
    query: String,
    now: Long,
    autoFocus: Boolean,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    onOpenConversation: (conversationId: String) -> Unit,
    onOpenMessage: (conversationId: String, messageId: String) -> Unit,
    onBack: () -> Unit,
    // One thread's search: its own hint and idle line, and message rows without the thread every one would repeat.
    scoped: Boolean = false,
) {
    Scaffold(
        modifier = Modifier.testTag("screen_search"),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                title = {
                    SearchField(
                        query = query,
                        hint = stringResource(if (scoped) R.string.search_in_chat_hint else R.string.search_hint),
                        autoFocus = autoFocus,
                        onQueryChange = onQueryChange,
                        onClear = onClear,
                    )
                },
            )
        },
    ) { padding ->
        val typed = query.trim()
        val idle = stringResource(if (scoped) R.string.search_in_chat_idle else R.string.search_idle)
        Box(Modifier.fillMaxSize().padding(padding).imePadding()) {
            when {
                typed.isEmpty() -> EmptyState(idle, "search_idle")

                // An answer is on its way and there is nothing older to show: stay quiet rather than flash
                // "no results" between the keystroke and the debounce.
                state.isEmpty && state.isSearching -> Unit

                state.isEmpty &&
                    !SearchQuery.isSearchable(
                        typed,
                    )
                -> EmptyState(stringResource(R.string.search_keep_typing), "search_keep_typing")

                state.isEmpty -> EmptyState(stringResource(R.string.search_empty, state.forQuery), "search_empty")

                else -> Results(state, now, scoped, onOpenConversation, onOpenMessage)
            }
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    hint: String,
    autoFocus: Boolean,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    if (autoFocus) {
        val windowInfo = LocalWindowInfo.current
        LaunchedEffect(Unit) {
            withTimeoutOrNull(FOCUS_WAIT_MS) { snapshotFlow { windowInfo.isWindowFocused }.first { it } }
            focusRequester.requestFocus()
        }
    }
    TextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        placeholder = { Text(hint, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon =
            if (query.isNotEmpty()) {
                {
                    IconButton(onClick = onClear, modifier = Modifier.size(48.dp).testTag("search_clear")) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.search_clear))
                    }
                }
            } else {
                null
            },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        // Search runs as you type; the IME's Search key just drops the keyboard so the results show.
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        // Flat in the bar: the app bar is the field's container.
        colors =
            TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
        modifier =
            Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .testTag("search_input")
                .noAutofillMenu(),
    )
}

@Composable
private fun Results(
    state: SearchUiState,
    now: Long,
    scoped: Boolean,
    onOpenConversation: (conversationId: String) -> Unit,
    onOpenMessage: (conversationId: String, messageId: String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("search_results"),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        // Keys carry their section: a person's node id IS their DM's conversation id, so a bare id would
        // collide across the Chats and People sections.
        section(R.string.search_section_chats, "chats", state.chats, key = { "chat:${it.id}" }) { hit ->
            ChatHitRow(hit) { onOpenConversation(hit.id) }
        }
        section(R.string.search_section_people, "people", state.people, key = { "person:${it.nodeId}" }) { hit ->
            PersonHitRow(hit) { onOpenConversation(hit.nodeId) }
        }
        // Scoped, the messages are all there is, and the header would only restate the field.
        section(
            R.string.search_section_messages.takeUnless { scoped },
            "messages",
            state.messages,
            key = { "message:${it.id}" },
        ) { hit ->
            MessageHitRow(hit, now, showThread = !scoped) { onOpenMessage(hit.conversationId, hit.id) }
        }
    }
}

/** A titled section — untitled when [title] is null — absent entirely when it has nothing to show. */
private fun <T> LazyListScope.section(
    @StringRes title: Int?,
    tag: String,
    rows: List<T>,
    key: (T) -> String,
    row: @Composable (T) -> Unit,
) {
    if (rows.isEmpty()) return
    if (title != null) {
        item(key = "header:$tag", contentType = "header") {
            Text(
                text = stringResource(title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier =
                    Modifier
                        .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 4.dp)
                        .semantics { heading() }
                        .testTag("search_section_$tag"),
            )
        }
    }
    items(rows, key = key, contentType = { tag }) { row(it) }
}

@Composable
private fun ChatHitRow(
    hit: ChatHit,
    onClick: () -> Unit,
) {
    HitRow(tag = "search_result_chat_${hit.id}", description = hit.title, onClick = onClick) {
        ThreadAvatar(hit.kind, hit.id, hit.avatarHash, hit.title, hit.faces)
        Spacer(Modifier.width(12.dp))
        PeerNameText(
            text = hit.title,
            discriminator = hit.discriminator,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun PersonHitRow(
    hit: PersonHit,
    onClick: () -> Unit,
) {
    val text = PeerLabel.text(hit.name, hit.alias)
    HitRow(tag = "search_result_person_${hit.nodeId}", description = text, onClick = onClick) {
        Avatar(avatarHash = hit.avatarHash, name = hit.name, nodeId = hit.nodeId, size = HIT_AVATAR_DP.dp)
        Spacer(Modifier.width(12.dp))
        PeerNameText(
            text = text,
            discriminator = hit.alias,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun MessageHitRow(
    hit: MessageHit,
    now: Long,
    // False in one thread's search, where every row would repeat the same avatar and title.
    showThread: Boolean,
    onClick: () -> Unit,
) {
    val line =
        remember(hit) {
            buildAnnotatedString {
                hit.sender?.let { append("$it: ") }
                val offset = length
                append(hit.snippet)
                hit.hit?.let { addStyle(SpanStyle(fontWeight = FontWeight.Bold), offset + it.first, offset + it.last + 1) }
            }
        }
    val spokenTime = DateUtils.getRelativeTimeSpanString(hit.sentAt, now, DateUtils.MINUTE_IN_MILLIS).toString()
    HitRow(
        tag = "search_result_message_${hit.id}",
        description = listOfNotNull(hit.conversationTitle.takeIf { showThread }, line.text, spokenTime).joinToString(", "),
        onClick = onClick,
    ) {
        if (showThread) {
            ThreadAvatar(hit.kind, hit.conversationId, hit.avatarHash, hit.conversationTitle, hit.faces)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            if (showThread) {
                Text(
                    text = hit.conversationTitle,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = line,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = compactTimeAgo(hit.sentAt, now, labels = relativeTimeLabels()),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One tappable result: a single accessibility node, like the chat list's rows. */
@Composable
private fun HitRow(
    tag: String,
    description: String,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .heightIn(min = 56.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clearAndSetSemantics {
                    testTag = tag
                    contentDescription = description
                    role = Role.Button
                    onClick {
                        onClick()
                        true
                    }
                },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

/**
 * The thread's glyph, as the chat list draws it: the knit logo for a room, a group's photo or its members'
 * cluster, a peer's avatar.
 */
@Composable
private fun ThreadAvatar(
    kind: ConversationKind,
    conversationId: String,
    avatarHash: String?,
    name: String,
    faces: List<GroupFace>,
) {
    val size = HIT_AVATAR_DP.dp
    when (kind) {
        ConversationKind.NEARBY, ConversationKind.MESHTASTIC, ConversationKind.COMMONS -> RoomAvatar(size = size)

        ConversationKind.GROUP -> GroupAvatar(photoHash = avatarHash, groupId = conversationId, faces = faces, size = size)

        // A DM's conversation id is the peer's node id.
        ConversationKind.DM -> Avatar(avatarHash = avatarHash, name = name, nodeId = conversationId, size = size)
    }
}

@Composable
private fun EmptyState(
    text: String,
    tag: String,
) {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp).testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

private const val HIT_AVATAR_DP = 44

@Preview(showBackground = true)
@Composable
fun SearchScreenIdlePreview() =
    KnitPreview {
        SearchScreenContent(
            state = SearchUiState(),
            query = "",
            now = PREVIEW_NOW,
            autoFocus = false,
            onQueryChange = {},
            onClear = {},
            onOpenConversation = {},
            onOpenMessage = { _, _ -> },
            onBack = {},
        )
    }

@Preview(showBackground = true)
@Composable
fun SearchScreenResultsPreview() =
    KnitPreview {
        SearchScreenContent(
            state =
                SearchUiState(
                    forQuery = "water",
                    chats = listOf(ChatHit("samr1v00", "Sam Rivera", null, null, ConversationKind.DM)),
                    people = listOf(PersonHit("samr1v00", "Sam Rivera", "quiet lantern", null)),
                    messages =
                        listOf(
                            MessageHit(
                                id = "m1",
                                conversationId = "samr1v00",
                                conversationTitle = "Sam Rivera",
                                kind = ConversationKind.DM,
                                avatarHash = null,
                                sender = null,
                                snippet = "Oh and I found your water bottle",
                                hit = 20..24,
                                sentAt = PREVIEW_NOW - 3_600_000L,
                            ),
                            MessageHit(
                                id = "m2",
                                conversationId = Conversations.NEARBY,
                                conversationTitle = "Nearby",
                                kind = ConversationKind.NEARBY,
                                avatarHash = null,
                                sender = "Dani Cho",
                                snippet = "Water refill at the trailhead kiosk",
                                hit = 0..4,
                                sentAt = PREVIEW_NOW - 86_400_000L,
                            ),
                        ),
                ),
            query = "water",
            now = PREVIEW_NOW,
            autoFocus = false,
            onQueryChange = {},
            onClear = {},
            onOpenConversation = {},
            onOpenMessage = { _, _ -> },
            onBack = {},
        )
    }

@Preview(showBackground = true)
@Composable
fun SearchScreenInChatPreview() =
    KnitPreview {
        SearchScreenContent(
            state =
                SearchUiState(
                    forQuery = "water",
                    messages =
                        listOf(
                            MessageHit(
                                id = "m1",
                                conversationId = "ridgerun",
                                conversationTitle = "Ridge Run 2026",
                                kind = ConversationKind.GROUP,
                                avatarHash = null,
                                sender = "Dani Cho",
                                snippet = "Water refill at the trailhead kiosk",
                                hit = 0..4,
                                sentAt = PREVIEW_NOW - 3_600_000L,
                            ),
                            MessageHit(
                                id = "m2",
                                conversationId = "ridgerun",
                                conversationTitle = "Ridge Run 2026",
                                kind = ConversationKind.GROUP,
                                avatarHash = null,
                                sender = "You",
                                snippet = "Bring two litres of water each",
                                hit = 20..24,
                                sentAt = PREVIEW_NOW - 86_400_000L,
                            ),
                        ),
                ),
            query = "water",
            now = PREVIEW_NOW,
            autoFocus = false,
            onQueryChange = {},
            onClear = {},
            onOpenConversation = {},
            onOpenMessage = { _, _ -> },
            onBack = {},
            scoped = true,
        )
    }

@Preview(showBackground = true)
@Composable
fun SearchScreenNoResultsPreview() =
    KnitPreview {
        SearchScreenContent(
            state = SearchUiState(forQuery = "zzzz"),
            query = "zzzz",
            now = PREVIEW_NOW,
            autoFocus = false,
            onQueryChange = {},
            onClear = {},
            onOpenConversation = {},
            onOpenMessage = { _, _ -> },
            onBack = {},
        )
    }
