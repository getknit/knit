package app.getknit.knit.ui.chat

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.getknit.knit.R
import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.ui.components.Avatar
import app.getknit.knit.ui.components.PeerNameText
import app.getknit.knit.ui.preview.KnitPreview
import app.getknit.knit.ui.preview.PREVIEW_NOW
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/** A long body is a preview here, not the whole message — the bubble is where you read it. */
private const val MAX_BODY_LINES = 4

/**
 * "Message info" (keyed by [messageId]), reached from a message's long-press menu: for a message we sent,
 * which people it has actually reached and which it hasn't; then everyone who reacted, by display name and
 * with the emoji they left, plus the per-message metadata the bubble has no room for — when it was sent,
 * and how far it got.
 *
 * The reaction chip under a bubble can only say "👍 3"; this is where the *which three* lives. Tapping a
 * reactor opens their profile ([onOpenProfile]); your own row is labelled "You" and isn't tappable, the
 * [app.getknit.knit.ui.group.GroupDetailsScreen] roster rule. Deleting the message while this is open
 * pops back rather than leaving a blank screen.
 */
@Composable
fun MessageDetailsScreen(
    messageId: String,
    onBack: () -> Unit,
    onOpenProfile: (nodeId: String) -> Unit,
    viewModel: MessageDetailsViewModel = koinViewModel { parametersOf(messageId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // The message can be deleted from the chat behind us (or reaped by retention); close instead of
    // rendering an empty shell. Only a row we actually saw counts as gone — a read that beats the write
    // (a deep link into a still-seeding build) would otherwise close the screen the moment it opened.
    LaunchedEffect(state.vanished) {
        if (state.vanished) onBack()
    }

    MessageDetailsScreenContent(
        state = state,
        onBack = onBack,
        onOpenProfile = onOpenProfile,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MessageDetailsScreenContent(
    state: MessageDetailsUiState,
    onBack: () -> Unit = {},
    onOpenProfile: (nodeId: String) -> Unit = {},
) {
    // Which emoji the chip row has selected (null = All). Pure view state: it never leaves the screen,
    // and rememberSaveable keeps the choice across a rotation.
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    // A filter whose emoji was just retracted away would silently show an empty list; fall back to All.
    val activeFilter = selected?.takeIf { emoji -> state.filters.any { it.emoji == emoji } }
    val shown = state.reactors.filter { activeFilter == null || it.emoji == activeFilter }

    Scaffold(
        modifier = Modifier.testTag("screen_message_details"),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.message_details_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { MessageSummary(state) }
            item { HorizontalDivider() }
            if (state.showRecipients) {
                recipientSections(state, onOpenProfile)
                item { HorizontalDivider() }
            }
            if (state.reactors.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.message_details_no_reactions),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("message_details_no_reactions"),
                    )
                }
            } else {
                item {
                    ReactionFilterRow(
                        filters = state.filters,
                        total = state.reactors.size,
                        selected = activeFilter,
                        onSelect = { selected = it },
                    )
                }
                items(shown, key = { it.nodeId }) { reactor ->
                    ReactorListRow(reactor = reactor, onOpen = onOpenProfile)
                }
            }
        }
    }
}

/** The message itself: body (or a photo placeholder), who sent it, when — absolutely — and how far it got. */
@Composable
private fun MessageSummary(state: MessageDetailsUiState) {
    val context = LocalContext.current
    val sender = if (state.mine) stringResource(R.string.chat_self_name) else state.senderName
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text =
                when {
                    // Match the bubble: a message the content filter collapsed there isn't re-revealed here.
                    state.moderationFlagged -> {
                        stringResource(R.string.message_details_hidden_body)
                    }

                    state.body.isNotBlank() -> {
                        state.body
                    }

                    state.hasAttachment -> {
                        // Every attachment used to read "📷 Photo" here, including voice notes and files.
                        // One label shared with the chat list, so a file is named and sized in both places.
                        attachmentLabel(
                            LocalContext.current,
                            state.attachmentMime,
                            state.attachmentName,
                            state.attachmentSize,
                        )
                    }

                    else -> {
                        ""
                    }
                },
            style = MaterialTheme.typography.bodyLarge,
            maxLines = MAX_BODY_LINES,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("message_details_body"),
        )
        Text(
            text = stringResource(R.string.message_details_sent_by, sender),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = absoluteTime(context, state.sentAt),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("message_details_sent_at"),
        )
        // When it got here, on OUR clock — the line above is the author's. Deliberately unclamped against it:
        // sentAt is a peer's wall clock (bounded only by Protocol.MAX_FUTURE_SKEW_MS), so "sent 19:29, arrived
        // 19:24" is a thing a skewed sender can produce, and a details screen is where an odd clock should be
        // visible rather than papered over. Absent on a message we sent, and on one stored before DB v7.
        if (!state.mine) {
            state.arrivedAt?.let { arrivedAt ->
                Text(
                    text = stringResource(R.string.message_details_arrived_at, absoluteTime(context, arrivedAt)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("message_details_arrived_at"),
                )
            }
        }
        // The DM half of the same question: when their receipt reached us. A group/room post answers "who has
        // it" with the roster below instead, so this is only ever set for a DM (see MessageDetailsViewModel).
        state.deliveredAt?.let { deliveredAt ->
            Text(
                text = stringResource(R.string.message_details_delivered_at, absoluteTime(context, deliveredAt)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("message_details_delivered_at"),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.testTag("message_details_delivery"),
        ) {
            Icon(
                imageVector =
                    when {
                        !state.mine -> planeGlyph(state.plane) ?: Icons.Filled.DoneAll
                        else -> deliveryIcon(state.delivery)
                    },
                // Decorative: the label beside it says the same thing in words.
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text =
                    deliveryLabel(
                        state.delivery,
                        state.plane,
                        state.mine,
                        // The ratio lives here, on the tick line, rather than being repeated in the
                        // section header below — which is why that header is a plain label.
                        delivered = state.deliveredTo.size,
                        total = state.recipientTotal,
                    ).resolve(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The per-recipient delivery split: who a message we sent has reached, then who it hasn't.
 *
 * The second half is absent in the broadcast room, which has no roster to be waiting on — and with no
 * roster there is no denominator either, so the room's list is **open**: everyone whose tick got home, and
 * never a count of everyone who received the post. Its header says "Confirmed delivered to" rather than the
 * group's "Delivered to", and a note under the rows says the rest out loud, because a bare list of names
 * over a header reads as a census whatever the header is called. A room tick only reaches its author over a
 * live link or a frame already going that way (ADR 2026-09.aa27), so the gap is real and permanent rather
 * than a delay: an acker that never sends this author anything is simply never listed.
 */
private fun LazyListScope.recipientSections(
    state: MessageDetailsUiState,
    onOpenProfile: (nodeId: String) -> Unit,
) {
    item {
        SectionHeader(
            text =
                if (state.recipientTotal > 0) {
                    // Plain label: the ratio is already on the delivery line in the summary above.
                    stringResource(R.string.message_details_delivered_to)
                } else {
                    // The room's list is what came back, not who received it — so the header claims exactly
                    // that and no count, which here would only look like a total it cannot be.
                    stringResource(R.string.message_details_confirmed_delivered_to)
                },
            testTag = "message_details_delivered_header",
        )
    }
    items(state.deliveredTo, key = { "delivered_${it.nodeId}" }) { recipient ->
        RecipientListRow(recipient = recipient, onOpen = onOpenProfile)
    }
    if (state.recipientTotal == 0) {
        item {
            // Under the rows, not above them: it is a footnote to the list, and putting it first would push
            // the names the reader came for off the top. A group send never sees it — it has a roster, so
            // its two sections already account for everyone.
            Text(
                text = stringResource(R.string.message_details_room_open_list),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("message_details_open_list_note"),
            )
        }
    }
    if (state.waitingOn.isNotEmpty()) {
        item {
            SectionHeader(
                text = stringResource(R.string.message_details_waiting_on),
                testTag = "message_details_waiting_header",
            )
        }
        items(state.waitingOn, key = { "waiting_${it.nodeId}" }) { recipient ->
            RecipientListRow(recipient = recipient, onOpen = onOpenProfile)
        }
    }
}

/**
 * Absolute, unlike the bubble's relative "5m" — the exact time is the point of this screen. Its own function
 * so the four timestamps here can't drift apart. [showYear] is off for the recipient rows, which sit under a
 * summary that already carries the year.
 */
private fun absoluteTime(
    context: Context,
    millis: Long,
    showYear: Boolean = true,
): String =
    DateUtils.formatDateTime(
        context,
        millis,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or
            (if (showYear) DateUtils.FORMAT_SHOW_YEAR else 0),
    )

/** A list-section label. Its own composable so the two delivery headers can't drift apart. */
@Composable
private fun SectionHeader(
    text: String,
    testTag: String,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag(testTag),
    )
}

/**
 * One recipient: avatar + name, and the time their receipt reached us, trailing — blank while we are still
 * waiting on them.
 *
 * The section header above is not announced with the rows beneath it, so each row carries its own status in
 * its content description; the trailing time alone would leave a waiting row saying nothing at all. Same
 * rule [deliveryLabel] enforces for the bubble's tick — the glyph (or its absence) never carries the
 * meaning.
 */
@Composable
private fun RecipientListRow(
    recipient: RecipientRow,
    onOpen: (nodeId: String) -> Unit,
) {
    val context = LocalContext.current
    val delivered = recipient.deliveredAt?.let { absoluteTime(context, it, showYear = false) }
    val description =
        if (delivered != null) {
            stringResource(R.string.message_details_recipient_delivered, recipient.displayName, delivered)
        } else {
            stringResource(R.string.message_details_recipient_waiting, recipient.displayName)
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                // Clickable merges the row's children, so it is announced as one node; the description
                // replaces that merged text with the status the header would otherwise have to carry.
                .clickable(
                    onClickLabel = stringResource(R.string.chat_view_profile, recipient.displayName),
                    role = Role.Button,
                ) { onOpen(recipient.nodeId) }
                .semantics { contentDescription = description }
                .testTag("recipient_row_${recipient.nodeId}")
                .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(avatarHash = recipient.avatarHash, name = recipient.displayName, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        PeerNameText(
            text = recipient.displayName,
            discriminator = recipient.discriminator,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        if (delivered != null) {
            Text(
                text = delivered,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** All + one chip per emoji, filtering the list below. Chip order is fixed by the ViewModel. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReactionFilterRow(
    filters: List<ReactionFilter>,
    total: Int,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        FilterChip(
            selected = selected == null,
            onClick = { onSelect(null) },
            label = { Text(stringResource(R.string.message_details_filter_all, total)) },
            modifier = Modifier.testTag("message_details_filter_all"),
        )
        filters.forEach { filter ->
            FilterChip(
                selected = selected == filter.emoji,
                onClick = { onSelect(filter.emoji) },
                label = {
                    Text(stringResource(R.string.message_details_filter_emoji, filter.emoji, filter.count))
                },
                modifier = Modifier.testTag("message_details_filter_${filter.emoji}"),
            )
        }
    }
}

/** One reactor: avatar + name (or "You"), and the emoji they left, trailing. */
@Composable
private fun ReactorListRow(
    reactor: ReactorRow,
    onOpen: (nodeId: String) -> Unit,
) {
    val name = if (reactor.isSelf) stringResource(R.string.chat_self_name) else reactor.displayName
    // Your own row is inert (nothing to open), so it carries no click label either — the
    // GroupDetailsScreen roster rule.
    val openLabel = stringResource(R.string.chat_view_profile, name)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(
                    if (reactor.isSelf) {
                        Modifier
                    } else {
                        // Clickable merges the row's children, so it is announced as one node —
                        // "Sam Rivera, 👍" — with the label naming what a tap does.
                        Modifier.clickable(onClickLabel = openLabel, role = Role.Button) { onOpen(reactor.nodeId) }
                    },
                ).testTag("reactor_row_${reactor.nodeId}")
                .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(avatarHash = reactor.avatarHash, name = name, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        PeerNameText(
            text = name,
            discriminator = if (reactor.isSelf) null else reactor.discriminator,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        Text(text = reactor.emoji, style = MaterialTheme.typography.titleMedium)
    }
}

@Preview(name = "Message details — delivery + reactions")
@Composable
fun MessageDetailsScreenPreview() =
    KnitPreview {
        MessageDetailsScreenContent(
            state =
                MessageDetailsUiState(
                    messageId = "demo-group-4",
                    body = "Works for me. I'll grab snacks.",
                    mine = true,
                    senderName = "You",
                    senderNodeId = "me00",
                    sentAt = PREVIEW_NOW - 10 * 60_000L,
                    delivery = DeliveryStatus.Delivered,
                    plane = DeliveryPlane.Internet,
                    showRecipients = true,
                    deliveredTo =
                        listOf(
                            RecipientRow("samr1v00", "Sam Rivera", null, PREVIEW_NOW - 9 * 60_000L),
                            RecipientRow("priya001", "Priya Nair", null, PREVIEW_NOW - 5 * 60_000L),
                        ),
                    waitingOn = listOf(RecipientRow("theod001", "Theo Diaz", null, null)),
                    recipientTotal = 3,
                    reactors =
                        listOf(
                            ReactorRow("samr1v00", "Sam Rivera", null, "👍", PREVIEW_NOW - 9 * 60_000L, false),
                            ReactorRow("priya001", "Priya Nair", null, "👍", PREVIEW_NOW - 8 * 60_000L, false),
                            ReactorRow("theod001", "Theo Diaz", null, "👍", PREVIEW_NOW - 7 * 60_000L, false),
                            ReactorRow("me00", "You", null, "❤️", PREVIEW_NOW - 6 * 60_000L, true),
                        ),
                    filters = listOf(ReactionFilter("👍", 3), ReactionFilter("❤️", 1)),
                ),
        )
    }

@Preview(name = "Message details — no reactions")
@Composable
fun MessageDetailsScreenEmptyPreview() =
    KnitPreview {
        MessageDetailsScreenContent(
            state =
                MessageDetailsUiState(
                    messageId = "demo-group-1",
                    body = "Trailhead Crew assemble! Saturday 7am?",
                    senderName = "Sam Rivera",
                    senderNodeId = "samr1v00",
                    sentAt = PREVIEW_NOW - 60 * 60_000L,
                    // Inbound, so it carries an arrival stamp; the four-minute gap is a custody hop.
                    arrivedAt = PREVIEW_NOW - 56 * 60_000L,
                    plane = DeliveryPlane.Nearby,
                ),
        )
    }
