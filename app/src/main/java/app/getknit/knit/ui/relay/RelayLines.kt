package app.getknit.knit.ui.relay

import android.content.ClipData
import android.content.Context
import android.os.Build
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.getknit.knit.R
import app.getknit.knit.mesh.spool.KeyAnswer
import app.getknit.knit.mesh.spool.MoveResult
import app.getknit.knit.mesh.spool.RelayInvite
import app.getknit.knit.mesh.spool.ScopeSync
import app.getknit.knit.mesh.spool.SpoolConnection
import app.getknit.knit.mesh.spool.SpoolErrCode
import app.getknit.knit.ui.components.noAutofillMenu
import app.getknit.knit.ui.shareText
import app.getknit.knit.ui.theme.knitColors
import kotlinx.coroutines.flow.Flow

// What the relay list and a relay's own screen both say about one relay, so neither re-derives it.

/**
 * The share sheet, the clipboard and the snackbar for [events]. Each screen that can raise one collects it: the
 * events flow drops what nobody is listening for, and a screen under another one in the back stack is not composed.
 * Android 13+ shows its own copy confirmation, so the snackbar only fires below it (the Add-contact screen's idiom).
 */
@Composable
internal fun RelayEventsEffect(
    events: Flow<InternetRelayEvent>,
    snackbarHostState: SnackbarHostState,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val shareMessage = stringResource(R.string.relays_invite_share_text)
    val shareTitle = stringResource(R.string.relays_invite_chooser_title)
    val copiedMessage = stringResource(R.string.relays_invite_copied)
    val clipLabel = stringResource(R.string.relays_invite_clip_label)
    val refusedMessage = stringResource(R.string.relays_invite_refused)
    val refusedUrlMessage = stringResource(R.string.relays_invite_refused_url)
    val appliedMessage = stringResource(R.string.relays_invite_applied)
    LaunchedEffect(events) {
        events.collect { event ->
            when (event) {
                is InternetRelayEvent.ShareInvite -> {
                    shareText(context, shareMessage.format(event.url), shareTitle)
                }

                is InternetRelayEvent.CopyInvite -> {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(clipLabel, event.url)))
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) snackbarHostState.showSnackbar(copiedMessage)
                }

                is InternetRelayEvent.InviteRefused -> {
                    snackbarHostState.showSnackbar(if (event.reason == RelayInvite.Reason.BAD_URL) refusedUrlMessage else refusedMessage)
                }

                is InternetRelayEvent.InviteApplied -> {
                    snackbarHostState.showSnackbar(appliedMessage.format(event.host))
                }

                is InternetRelayEvent.Moved -> {
                    snackbarHostState.showSnackbar(moveMessage(context, event.result))
                }
            }
        }
    }
}

/** The snackbar line for a move's outcome (docs/VOICE_CALLS.md C-7.6-44). */
private fun moveMessage(
    context: Context,
    result: MoveResult,
): String =
    when (result) {
        MoveResult.Moved -> {
            context.getString(R.string.relays_move_done)
        }

        is MoveResult.TooSoon -> {
            result.movableAt?.let {
                val at =
                    DateUtils.formatDateTime(
                        context,
                        it,
                        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_NO_YEAR or
                            DateUtils.FORMAT_ABBREV_MONTH,
                    )
                context.getString(R.string.relays_move_from, at)
            } ?: context.getString(R.string.relays_move_failed)
        }

        MoveResult.UnknownKey -> {
            context.getString(R.string.relays_move_unknown_key)
        }

        MoveResult.Ended -> {
            context.getString(R.string.relays_move_ended)
        }

        is MoveResult.Failed, MoveResult.Unavailable -> {
            context.getString(R.string.relays_move_failed)
        }
    }

/**
 * A relay's status dot. Intent outranks liveness: a relay the user just parked keeps its worker for up to one
 * ScopeSync reconcile tick, and a dot that stayed green for those seconds would read as the switch not having
 * worked. And the phone's own lack of a route is not the relay's fault: no red dot for it.
 */
@Composable
internal fun relayDotColor(
    relay: RelayRow,
    planeEnabled: Boolean,
    offline: Boolean,
): Color =
    when {
        !planeEnabled || !relay.enabled -> MaterialTheme.colorScheme.outline
        relay.connected -> MaterialTheme.knitColors.positive
        offline -> MaterialTheme.colorScheme.outline
        relay.lastError != null -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }

/**
 * The one-line status under a relay's host.
 *
 * The two off states are tested before liveness, and are separate strings: "the plane is off" is a fact
 * about the whole screen and repeats down every row, while "you turned this one off" is a fact about
 * this relay that only its own row can state. Collapsing them would leave a parked relay indistinguishable
 * from its neighbours the moment the master switch went off.
 *
 * [offline] — the platform reports no validated route at all — is tested after liveness and before the
 * relay's own last error: a socket that is up outranks the platform's verdict for the seconds they
 * disagree, and a phone with no Internet must not send its user to check a relay's address. It does not
 * cover the black-hole case (a route the platform still calls validated that swallows the socket);
 * that one arrives as `unreachable`, which is the relay's row saying "cannot be reached" honestly.
 */
@Composable
internal fun relayStatusLine(
    relay: RelayRow,
    planeEnabled: Boolean,
    offline: Boolean,
): String =
    when {
        !planeEnabled -> {
            stringResource(R.string.relays_status_off)
        }

        !relay.enabled -> {
            stringResource(R.string.relays_status_paused)
        }

        relay.connected -> {
            stringResource(
                R.string.joined_with_dot,
                stringResource(R.string.relays_status_connected),
                pluralStringResource(R.plurals.relays_scope_count, relay.scopeCount ?: 0, relay.scopeCount ?: 0),
            )
        }

        offline -> {
            stringResource(R.string.relays_status_offline)
        }

        relay.lastError != null -> {
            relayErrorLabel(relay.lastError)
        }

        else -> {
            stringResource(R.string.relays_status_connecting)
        }
    }

/**
 * A spool `err` code turned into something a person can act on. The wire codes are an append-only
 * registry (spec §7.2), so anything unrecognised falls through to the generic form carrying the raw
 * code rather than being swallowed — an unknown refusal the user can quote is worth more than a
 * confident wrong guess.
 *
 * The reason is not always an `err` code: a socket that never opened reports a transport failure
 * instead, and the one worth separating is a spool at its connection cap. "Busy, it will come back on
 * its own" and "broken, check the URL" ask opposite things of the user and would otherwise read alike.
 * The client's own verdicts (`ScopeSync.NO_HELLO`, `SpoolConnection.UNRESPONSIVE`, and the two that say
 * the relay's numbers do not fit this app) each get a sentence too, so none of them lands in the generic
 * form as a refusal nobody made.
 */
@Composable
internal fun relayErrorLabel(code: String): String =
    when (code) {
        UNREACHABLE -> {
            stringResource(R.string.relays_error_unreachable)
        }

        ScopeSync.NO_HELLO, SpoolConnection.UNRESPONSIVE -> {
            stringResource(R.string.relays_error_unresponsive)
        }

        SpoolErrCode.TOO_LARGE, ScopeSync.OVERLONG_LISTING -> {
            stringResource(R.string.relays_error_incompatible)
        }

        SpoolErrCode.QUOTA -> {
            stringResource(R.string.relays_error_quota)
        }

        SpoolErrCode.RATE -> {
            stringResource(R.string.relays_error_rate)
        }

        SpoolErrCode.POW -> {
            stringResource(R.string.relays_error_pow)
        }

        BUSY_STATUS -> {
            stringResource(R.string.relays_error_busy)
        }

        else -> {
            if (code.contains(AUTH_CLOSE_CODE)) {
                stringResource(R.string.relays_error_auth)
            } else {
                stringResource(R.string.relays_error_other, code)
            }
        }
    }

/** Where this phone stands with the relay's ticket, as the value of its "Ticket" row; a live one's grants are [ticketGrantRows]. */
@Composable
internal fun ticketLine(
    ticket: RelayTicket,
    turn: Boolean,
): String =
    when (ticket) {
        RelayTicket.None -> {
            stringResource(if (turn) R.string.relays_calls_ticket_none_turn else R.string.relays_calls_ticket_none)
        }

        RelayTicket.Presenting -> {
            stringResource(R.string.relays_calls_ticket_presenting)
        }

        is RelayTicket.Live -> {
            stringResource(R.string.relays_calls_ticket_live, dateTime(ticket.exp * MS_PER_S))
        }

        is RelayTicket.Refused -> {
            stringResource(R.string.relays_calls_ticket_refused, ticket.reason)
        }

        is RelayTicket.Expired -> {
            stringResource(R.string.relays_calls_ticket_expired, dateTime(ticket.exp * MS_PER_S))
        }
    }

/**
 * What a live ticket unlocks, and its own limits where it sets them (S-7.6-22, S-7.6-31) — what a free ticket is told
 * apart by: up to two label/value rows, none for a ticket that grants and limits nothing.
 */
@Composable
internal fun ticketGrantRows(ticket: RelayTicket.Live): List<Pair<String, String>> {
    val context = LocalContext.current
    val grants =
        buildList {
            if (ticket.turn) add(stringResource(R.string.relays_calls_turn))
            if (ticket.walkie) add(stringResource(R.string.relays_calls_walkie))
            if (ticket.files) add(stringResource(R.string.relays_calls_files))
        }
    val limits =
        buildList {
            ticket.am?.let { add(stringResource(R.string.relays_calls_files_each, Formatter.formatShortFileSize(context, it))) }
            ticket.ab?.let { add(stringResource(R.string.relays_calls_files_day, Formatter.formatShortFileSize(context, it))) }
        }
    return buildList {
        if (grants.isNotEmpty()) add(stringResource(R.string.relays_detail_ticket_grants) to grants.joinToString(", "))
        if (limits.isNotEmpty()) add(stringResource(R.string.relays_detail_ticket_limits) to limits.joinToString(", "))
    }
}

/** The label of the key's row: a free key (C-7.6-37) is never called a ticket key or an account. */
@Composable
internal fun accountLabel(account: RelayAccount): String =
    stringResource(if (account.free) R.string.relays_detail_free_key else R.string.relays_detail_key)

/** The ticket key held for the relay's host and what its issuer last said, as its row's value. Names the issuer's host, never the key. */
@Composable
internal fun accountLine(account: RelayAccount): String =
    when {
        // A free key (C-7.6-37) has no end to show, and is never called an account.
        account.free -> freeKeyLine(account)

        else -> customerKeyLine(account)
    }

@Composable
private fun freeKeyLine(account: RelayAccount): String =
    when (account.answer) {
        null if account.failures > 0 -> stringResource(R.string.relays_free_key_unanswered, account.issuer)
        null -> stringResource(R.string.relays_free_key_waiting, account.issuer)
        else -> stringResource(R.string.relays_free_key_held, account.issuer)
    }

@Composable
private fun customerKeyLine(account: RelayAccount): String =
    when (account.answer) {
        null -> {
            stringResource(
                if (account.failures > 0) R.string.relays_calls_key_unanswered else R.string.relays_calls_key_waiting,
                account.issuer,
            )
        }

        KeyAnswer.OK -> {
            account.endsAt?.let { stringResource(R.string.relays_calls_key_ok, account.issuer, date(it)) }
                ?: stringResource(R.string.relays_calls_key_ok_open, account.issuer)
        }

        KeyAnswer.UNKNOWN_KEY -> {
            stringResource(R.string.relays_calls_key_unknown, account.issuer)
        }

        KeyAnswer.ENDED -> {
            account.endsAt?.let { stringResource(R.string.relays_calls_key_ended, account.issuer, date(it)) }
                ?: stringResource(R.string.relays_calls_key_ended_undated, account.issuer)
        }

        KeyAnswer.BOUND_ELSEWHERE -> {
            stringResource(R.string.relays_calls_key_bound_elsewhere, account.issuer)
        }
    }

/** When a parked account (C-7.6-42) can move here, as its line says it: the day and the time. */
@Composable
internal fun movableLine(epochMillis: Long): String = stringResource(R.string.relays_move_from, dateTime(epochMillis))

/** A ticket's `exp`: the day and the time, never the year — a ticket is good for 48 h at most (§2.6). */
@Composable
private fun dateTime(epochMillis: Long): String =
    DateUtils.formatDateTime(
        LocalContext.current,
        epochMillis,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_NO_YEAR or DateUtils.FORMAT_ABBREV_MONTH,
    )

/** An account's end: the day, with its year. */
@Composable
private fun date(epochMillis: Long): String =
    DateUtils.formatDateTime(
        LocalContext.current,
        epochMillis,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_ABBREV_MONTH,
    )

/** The invite (docs/RELAY_INVITE.md §4): one labelled button opening Share and Copy. */
@Composable
internal fun RelayInviteMenu(
    relay: RelayRow,
    onShare: () -> Unit,
    onCopy: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = Modifier.size(48.dp).testTag("relay_invite_${relay.host}")) {
        Icon(
            Icons.Filled.Share,
            contentDescription = stringResource(R.string.relays_invite_share_desc, relay.host),
            modifier = Modifier.size(20.dp),
        )
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.relays_invite_share)) },
            onClick = {
                open = false
                onShare()
            },
            modifier = Modifier.testTag("relay_invite_share_${relay.host}"),
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.relays_invite_copy)) },
            onClick = {
                open = false
                onCopy()
            },
            modifier = Modifier.testTag("relay_invite_copy_${relay.host}"),
        )
    }
}

/**
 * Pasting a commons invite for one relay. The invite is the room's whole key, so the field validates the
 * exact grammar the daemon mints (`CommonsInvite`) and never lets a near-miss through: a typo would be a
 * subscription to a room nobody else is in. The copy says the one thing worth knowing before joining —
 * everyone in the room becomes a contact.
 */
@Composable
internal fun JoinCommonsDialog(
    relay: RelayRow,
    isValidInvite: (String) -> Boolean,
    onJoin: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var invite by remember { mutableStateOf("") }
    val trimmed = invite.trim()
    val malformed = trimmed.isNotEmpty() && !isValidInvite(trimmed)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.relays_commons_join_title, relay.commons?.name ?: stringResource(R.string.commons_title))) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.relays_commons_join_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = invite,
                    onValueChange = { invite = it },
                    singleLine = true,
                    isError = malformed,
                    label = { Text(stringResource(R.string.relays_commons_invite_label)) },
                    supportingText = {
                        if (malformed) {
                            Text(stringResource(R.string.relays_commons_invite_invalid))
                        } else {
                            Text(stringResource(R.string.relays_commons_invite_hint))
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("relay_commons_invite_field").noAutofillMenu(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onJoin(trimmed) },
                enabled = trimmed.isNotEmpty() && !malformed,
                modifier = Modifier.testTag("relay_commons_join_confirm"),
            ) {
                Text(stringResource(R.string.relays_commons_join))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** `SpoolStatus.lastError` when nothing answered the dial at all (`ScopeSync.UNREACHABLE`). */
private const val UNREACHABLE = ScopeSync.UNREACHABLE

/**
 * `SpoolStatus.lastError` when the spool refused the WebSocket upgrade because it is at its connection
 * cap. Deliberately an HTTP status and not a close code: spec §7.1 defines four, and none of them means
 * "come back later" — a full spool is a property of the box, not of the protocol.
 */
private const val BUSY_STATUS = "http 503"

/** A close-reason string carrying spec §7.1's private-spool token rejection (`close 4001 …`). */
private const val AUTH_CLOSE_CODE = "4001"

private const val MS_PER_S = 1_000L
