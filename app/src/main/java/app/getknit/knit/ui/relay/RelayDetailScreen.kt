package app.getknit.knit.ui.relay

import android.text.format.Formatter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.getknit.knit.R
import app.getknit.knit.data.relay.FreeUploadChoice
import app.getknit.knit.mesh.spool.KeyAnswer
import app.getknit.knit.mesh.spool.SpoolUrl
import app.getknit.knit.ui.components.DetailCard
import app.getknit.knit.ui.components.DetailRow
import app.getknit.knit.ui.components.SectionHeader
import app.getknit.knit.ui.preview.KnitPreview

/**
 * One relay's own screen: its status and switch, what the live connection says about it, what it offers calls,
 * where this phone stands with its tickets, its room, the invite, and the way to remove it. The list row keeps only
 * the status and the switch, so the relays list stays a list.
 *
 * [relayKey] is the relay's [SpoolUrl.redact]ed URL, its identity everywhere else (an invite that adds a token is the
 * same relay): the bearer token never goes into a route or a saved back stack. [viewModel] is the list's own
 * instance, so this screen re-derives nothing and its verbs are the list's.
 */
@Composable
fun RelayDetailScreen(
    relayKey: String,
    viewModel: InternetRelayViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val relay = state.relays.firstOrNull { SpoolUrl.redact(it.url) == relayKey }
    val snackbarHostState = remember { SnackbarHostState() }
    RelayEventsEffect(viewModel.events, snackbarHostState)
    // Gone from the list — removed here, or elsewhere while this was open: nothing is left to show. The one way
    // out after a removal too, so Remove and an outside change leave by the same door.
    LaunchedEffect(state.loaded, relay == null) {
        if (state.loaded && relay == null) onBack()
    }

    RelayDetailScreenContent(
        relay = relay,
        planeEnabled = state.enabled,
        offline = state.offline,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onSetEnabled = { on -> relay?.let { viewModel.setRelayEnabled(it.url, on) } },
        onRemove = { relay?.let { viewModel.removeRelay(it.url) } },
        onJoinCommons = { invite -> relay?.let { viewModel.joinCommons(it.url, invite) } },
        onLeaveCommons = viewModel::leaveCommons,
        isValidInvite = viewModel::isValidInvite,
        onShareInvite = { relay?.let { viewModel.shareInvite(it.url) } },
        onCopyInvite = { relay?.let { viewModel.copyInvite(it.url) } },
        onShareRoomInvite = { room -> relay?.let { viewModel.shareInvite(it.url, room) } },
        onCopyRoomInvite = { room -> relay?.let { viewModel.copyInvite(it.url, room) } },
        onSetFreeUploads = { allowed -> relay?.let { SpoolUrl.hostname(it.url) }?.let { viewModel.setFreeUploads(it, allowed) } },
        onMoveHere = { relay?.let { viewModel.moveHere(it.url) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongMethod", "LongParameterList") // one screen's sections, its dialogs and its verbs, mirrored in one place
@Composable
internal fun RelayDetailScreenContent(
    relay: RelayRow?,
    planeEnabled: Boolean,
    offline: Boolean,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onBack: () -> Unit,
    onSetEnabled: (Boolean) -> Unit = {},
    onRemove: () -> Unit = {},
    onJoinCommons: (String) -> Unit = {},
    onLeaveCommons: (String) -> Unit = {},
    isValidInvite: (String) -> Boolean = { it.startsWith("knit-commons:v1:") },
    onShareInvite: () -> Unit = {},
    onCopyInvite: () -> Unit = {},
    onShareRoomInvite: (String) -> Unit = {},
    onCopyRoomInvite: (String) -> Unit = {},
    onSetFreeUploads: (Boolean) -> Unit = {},
    onMoveHere: () -> Unit = {},
) {
    var removing by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var joining by remember { mutableStateOf(false) }
    // The room whose Leave is being confirmed, by its thread id.
    var leaving by remember { mutableStateOf<String?>(null) }

    Scaffold(
        modifier = Modifier.testTag("screen_relay_detail"),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                title = {
                    Text(text = relay?.host.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                actions = {
                    if (relay != null) RelayInviteMenu(relay = relay, onShare = onShareInvite, onCopy = onCopyInvite)
                },
            )
        },
    ) { padding ->
        // Not read yet: an empty page for the frame before the list's state lands, never a half-drawn relay.
        if (relay == null) return@Scaffold
        // The cards carry the side gutter (`DetailCard`), so the column pads only top and bottom.
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
        ) {
            DetailCard { UseRelayRow(relay, planeEnabled, offline, onSetEnabled) }
            ConnectionSection(relay)
            CallsSection(relay)
            TicketsSection(relay, onSetFreeUploads, onMoveHere = { moving = true })
            if (relay.rooms.isNotEmpty() || relay.takesRooms) {
                RoomSection(
                    relay,
                    onJoin = { joining = true },
                    onLeave = { leaving = it },
                    onShare = onShareRoomInvite,
                    onCopy = onCopyRoomInvite,
                )
            }
            OutlinedButton(
                onClick = { removing = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp).testTag("relay_remove"),
            ) {
                Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.relays_detail_remove))
            }
        }
    }

    if (relay == null) return

    if (joining) {
        JoinCommonsDialog(
            isValidInvite = isValidInvite,
            onJoin = {
                onJoinCommons(it)
                joining = false
            },
            onDismiss = { joining = false },
        )
    }

    leaving?.let { room ->
        AlertDialog(
            onDismissRequest = { leaving = null },
            title = { Text(stringResource(R.string.relays_commons_leave)) },
            text = { Text(stringResource(R.string.relays_commons_leave_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onLeaveCommons(room)
                        leaving = null
                    },
                    modifier = Modifier.testTag("relay_commons_leave_confirm"),
                ) {
                    Text(stringResource(R.string.relays_commons_leave))
                }
            },
            dismissButton = {
                TextButton(onClick = { leaving = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (moving) {
        // Moving takes the account from the other phone, so it is the person's act and always confirmed (C-7.6-44).
        AlertDialog(
            onDismissRequest = { moving = false },
            title = { Text(stringResource(R.string.relays_move_title)) },
            text = { Text(stringResource(R.string.relays_move_body, relay.account?.issuer.orEmpty())) },
            confirmButton = {
                TextButton(
                    onClick = {
                        moving = false
                        onMoveHere()
                    },
                    modifier = Modifier.testTag("relay_move_confirm"),
                ) {
                    Text(stringResource(R.string.relays_move_here))
                }
            },
            dismissButton = {
                TextButton(onClick = { moving = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (removing) {
        AlertDialog(
            onDismissRequest = { removing = false },
            title = { Text(stringResource(R.string.relays_remove)) },
            text = { Text(relay.host) },
            confirmButton = {
                TextButton(
                    onClick = {
                        removing = false
                        onRemove()
                    },
                    modifier = Modifier.testTag("relay_remove_confirm"),
                ) {
                    Text(stringResource(R.string.relays_remove))
                }
            },
            dismissButton = {
                TextButton(onClick = { removing = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/**
 * The relay's own switch with its status under it. The row owns the `toggleable` and the [Switch] takes a null
 * handler, the house toggle-row shape, so a screen reader announces one switch labelled with the relay's state. It
 * greys out while the plane is off, and the status line says so ("Not in use").
 */
@Composable
private fun UseRelayRow(
    relay: RelayRow,
    planeEnabled: Boolean,
    offline: Boolean,
    onSetEnabled: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .toggleable(value = relay.enabled, enabled = planeEnabled, onValueChange = onSetEnabled, role = Role.Switch)
                .testTag("relay_detail_switch")
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = stringResource(R.string.relays_detail_use), style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.size(8.dp).clip(CircleShape).background(relayDotColor(relay, planeEnabled, offline)))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = relayStatusLine(relay, planeEnabled, offline),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = relay.enabled, onCheckedChange = null, enabled = planeEnabled)
    }
}

/**
 * What the live connection says: how many of our conversations it holds, what it carries to this phone, whether it
 * takes this phone's own uploads (a relay can carry photos and still refuse ours without a files ticket, C-7.6-24),
 * whether its HELLO asks for proof of work, and the software its `/source` named. All facts about the connection, so
 * while it is down one line says they will come back.
 */
@Composable
private fun ConnectionSection(relay: RelayRow) {
    SectionHeader(stringResource(R.string.relays_detail_section_connection))
    DetailCard(Modifier.testTag("relay_connection_${relay.host}")) {
        if (!relay.connected) {
            Text(
                text = stringResource(R.string.relays_detail_disconnected),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            return@DetailCard
        }
        val context = LocalContext.current
        DetailRow(stringResource(R.string.relays_detail_conversations), (relay.scopeCount ?: 0).toString())
        DetailRow(
            stringResource(R.string.relays_detail_downloads),
            relay.maxAttachBytes?.let {
                stringResource(R.string.relays_detail_downloads_limit, Formatter.formatShortFileSize(context, it.toLong()))
            } ?: stringResource(R.string.relays_detail_downloads_none),
        )
        relay.uploads?.let { uploads ->
            DetailRow(
                stringResource(R.string.relays_detail_uploads),
                when (uploads) {
                    is RelayUploads.Open -> {
                        stringResource(R.string.relays_detail_uploads_open, Formatter.formatShortFileSize(context, uploads.maxBytes))
                    }

                    is RelayUploads.Locked -> {
                        stringResource(
                            if (uploads.pending) R.string.relays_detail_uploads_pending else R.string.relays_detail_uploads_locked,
                        )
                    }

                    RelayUploads.Spent -> {
                        stringResource(R.string.relays_detail_uploads_spent)
                    }
                },
                modifier = Modifier.testTag("relay_uploads_${relay.host}"),
            )
        }
        DetailRow(
            stringResource(R.string.relays_detail_pow),
            relay.powBits?.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.relays_detail_pow_bits, it, it) }
                ?: stringResource(R.string.relays_detail_pow_none),
        )
        relay.software?.let { DetailRow(stringResource(R.string.relays_detail_software), it) }
    }
}

/** What the relay's HELLO offers calls (docs/VOICE_CALLS.md §2): TURN, the walkie's casts and its STUN servers. */
@Composable
private fun CallsSection(relay: RelayRow) {
    val calls = relay.calls ?: return
    // A relay that takes tickets and offers calls nothing has its ticket below and no section here.
    if (!calls.turn && !calls.walkie && calls.stunServers == 0) return
    SectionHeader(stringResource(R.string.relays_detail_section_calls))
    DetailCard(Modifier.testTag("relay_calls_${relay.host}")) {
        DetailRow(stringResource(R.string.relays_detail_turn), offered(calls.turn))
        DetailRow(stringResource(R.string.relays_detail_walkie), offered(calls.walkie))
        DetailRow(stringResource(R.string.relays_detail_stun), calls.stunServers.toString())
    }
}

/**
 * Where this phone stands with the relay's tickets (§2.6): the ticket on this connection with what it unlocks, the
 * ticket key held for the relay's host (ADR 2026-10.3sk7) and the free tickets' switch (C-7.6-34). Names the issuer's
 * host and the ticket's claims only — never the key, the ticket or a STUN URI.
 */
@Composable
private fun TicketsSection(
    relay: RelayRow,
    onSetFreeUploads: (Boolean) -> Unit,
    onMoveHere: () -> Unit,
) {
    val calls = relay.calls
    val ticket = calls?.ticket
    val rows =
        buildList {
            if (ticket != null) {
                add(stringResource(R.string.relays_detail_ticket) to ticketLine(ticket, calls.turn))
                if (ticket is RelayTicket.Live) addAll(ticketGrantRows(ticket))
            }
            relay.account?.let { add(accountLabel(it) to accountLine(it)) }
        }
    if (rows.isEmpty() && relay.freeUploads == null) return
    SectionHeader(stringResource(R.string.relays_detail_section_tickets))
    DetailCard(Modifier.testTag("relay_ticket_${relay.host}")) {
        rows.forEach { (label, value) -> DetailRow(label, value) }
        relay.account?.takeIf { it.answer == KeyAnswer.BOUND_ELSEWHERE && !it.free }?.let { MoveHere(relay.host, it, onMoveHere) }
        relay.freeUploads?.let { FreeUploadsSwitch(relay.host, it, onSetFreeUploads) }
    }
}

/**
 * A parked account (docs/VOICE_CALLS.md C-7.6-42): the one place the person learns it is in use on another phone, and
 * the way to move it here — a button once the issuer's `movableAt` has passed (or named none), else the time it can.
 */
@Composable
private fun MoveHere(
    host: String,
    account: RelayAccount,
    onMoveHere: () -> Unit,
) {
    val movableAt = account.movableAt
    if (movableAt != null && movableAt > System.currentTimeMillis()) {
        Text(
            text = movableLine(movableAt),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("relay_move_from_$host"),
        )
    } else {
        OutlinedButton(
            onClick = onMoveHere,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("relay_move_here_$host"),
        ) {
            Text(stringResource(R.string.relays_move_here))
        }
    }
}

/**
 * Free tickets for a relay (docs/VOICE_CALLS.md C-7.6-34): one labelled switch saying which host the phone contacts,
 * on unless the person turned it off. Turning it off forgets a free key held there.
 */
@Composable
private fun FreeUploadsSwitch(
    host: String,
    choice: FreeUploadChoice,
    onSet: (Boolean) -> Unit,
) {
    val on = choice.allowed != false
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag("relay_free_uploads_$host")
                .toggleable(value = on, onValueChange = onSet, role = Role.Switch)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = stringResource(R.string.relays_free_uploads_title), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(R.string.relays_free_uploads_summary, choice.issuerHost),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = on, onCheckedChange = null)
    }
}

/**
 * The relay's commons rooms (§7.4): each room joined here, with its invite and a Leave, and one Join while the relay
 * runs any — the invite says which room, so a second room joins beside the first (C-7.4-18).
 */
@Composable
private fun RoomSection(
    relay: RelayRow,
    onJoin: () -> Unit,
    onLeave: (String) -> Unit,
    onShare: (String) -> Unit,
    onCopy: (String) -> Unit,
) {
    SectionHeader(stringResource(R.string.relays_detail_section_room))
    DetailCard {
        for (room in relay.rooms) {
            val name = room.name ?: stringResource(R.string.commons_title)
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.relays_commons_label, name),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                InviteMenu(
                    key = room.conversationId,
                    contentDescription = stringResource(R.string.relays_commons_share_desc, name),
                    onShare = { onShare(room.conversationId) },
                    onCopy = { onCopy(room.conversationId) },
                    tagPrefix = "relay_commons",
                )
                TextButton(
                    onClick = { onLeave(room.conversationId) },
                    modifier = Modifier.testTag("relay_commons_leave_${room.conversationId}"),
                ) {
                    Text(stringResource(R.string.relays_commons_leave))
                }
            }
            // The room joined here is not one this relay runs: Leave, then Join with the operator's new invite.
            if (room.stale) {
                Text(
                    text = stringResource(R.string.commons_stale),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier =
                        Modifier
                            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                            .testTag("relay_commons_stale_${room.conversationId}"),
                )
            }
        }
        if (relay.takesRooms) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text =
                        stringResource(
                            if (relay.rooms.isEmpty()) R.string.relays_commons_offered else R.string.relays_commons_offered_more,
                        ),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onJoin, modifier = Modifier.testTag("relay_commons_join_${relay.host}")) {
                    Text(stringResource(R.string.relays_commons_join))
                }
            }
        }
    }
}

@Composable
private fun offered(on: Boolean): String = stringResource(if (on) R.string.relays_detail_offered else R.string.relays_detail_not_offered)

@Preview(showBackground = true)
@Composable
fun RelayDetailScreenPreview() =
    KnitPreview {
        RelayDetailScreenContent(
            relay =
                RelayRow(
                    url = "wss://lab.lax.spool.getknit.app/spool/v1",
                    host = "lab.lax.spool.getknit.app",
                    enabled = true,
                    connected = true,
                    scopeCount = 4,
                    carriesPhotos = true,
                    rooms =
                        listOf(
                            RelayRoom(conversationId = "c-1", name = "Lab"),
                            RelayRoom(conversationId = "c-2", name = "Book club", stale = true),
                        ),
                    takesRooms = true,
                    calls =
                        RelayCalls(
                            turn = true,
                            stunServers = 1,
                            walkie = true,
                            // 2026-10-14 16:00 UTC.
                            ticket = RelayTicket.Live(exp = 1_791_993_600L, turn = true, walkie = true, files = true, am = 2_000_000L),
                        ),
                    // 2026-11-30 UTC.
                    account = RelayAccount("issuer.example.org", KeyAnswer.OK, endsAt = 1_796_000_000_000L, failures = 0),
                    freeUploads = FreeUploadChoice(issuerHost = "free.example.org", allowed = true),
                    maxAttachBytes = 26_214_400,
                    software = "knit-spool 0.9.0 (e8a7790)",
                    powBits = 0,
                    uploads = RelayUploads.Open(maxBytes = 2_000_000L),
                ),
            planeEnabled = true,
            offline = false,
            onBack = {},
        )
    }

@Preview(showBackground = true)
@Composable
fun RelayDetailScreenDisconnectedPreview() =
    KnitPreview {
        RelayDetailScreenContent(
            relay =
                RelayRow(
                    url = "wss://ended.example.org/spool/v1",
                    host = "ended.example.org",
                    enabled = true,
                    connected = false,
                    lastError = "unreachable",
                    account = RelayAccount("issuer.example.org", KeyAnswer.ENDED, endsAt = 1_790_000_000_000L, failures = 0),
                ),
            planeEnabled = true,
            offline = false,
            onBack = {},
        )
    }

@Preview(showBackground = true)
@Composable
fun RelayDetailScreenParkedPreview() =
    KnitPreview {
        RelayDetailScreenContent(
            relay =
                RelayRow(
                    url = "wss://lab.lax.spool.getknit.app/spool/v1",
                    host = "lab.lax.spool.getknit.app",
                    enabled = true,
                    connected = true,
                    scopeCount = 2,
                    account =
                        RelayAccount(
                            "issuer.example.org",
                            KeyAnswer.BOUND_ELSEWHERE,
                            endsAt = 1_796_000_000_000L,
                            failures = 0,
                            movableAt = null,
                        ),
                ),
            planeEnabled = true,
            offline = false,
            onBack = {},
        )
    }
