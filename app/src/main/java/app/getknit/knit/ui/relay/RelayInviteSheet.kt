package app.getknit.knit.ui.relay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.getknit.knit.R
import app.getknit.knit.data.relay.RelayInviteApplier

/**
 * The relay invite's preview (docs/RELAY_INVITE.md §3): what a tapped link would do, on one sheet, with
 * one confirmation. Raised by the Internet-relays screen for a link and by the Add-contact screen for a
 * card's relay hint, over the same [RelayInviteApplier.Preview], so the two doors cannot say different
 * things about the same relay.
 *
 * Built around one question — *do you trust this host?* — so the host is the largest thing on it. A link
 * is a bearer credential over an unauthenticated channel, and the sheet is what stands between a hostile
 * link and a relay that then sees every scope id and IP this device has (ADR 042): the cost of adding a
 * relay is stated every time, and the first time the whole disclosure is the body — the master switch's
 * own words ([RelayConsentText]), because confirming here records the same consent (ADR 063).
 *
 * The confirm button names what will happen ("Turn on and add", "Join room", …) rather than a bare
 * "OK", and is always enabled: applying is idempotent, and a sheet that answers a re-tapped link with a
 * disabled button reads as a broken link rather than an already-added relay.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RelayInviteSheet(
    preview: RelayInviteApplier.Preview,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("relay_invite_sheet"),
    ) {
        // Scrollable: with the first-time disclosure folded in, the sheet outgrows a small screen.
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.relays_invite_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = preview.host,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.testTag("relay_invite_host"),
            )
            InviteFacts(preview)
            if (preview.consentNeeded) {
                RelayConsentText()
            } else if (!preview.alreadyAdded || preview.replaces != null) {
                Text(
                    text = stringResource(R.string.relays_invite_sees),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("relay_invite_sees"),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("relay_invite_decline")) {
                    Text(stringResource(R.string.relays_invite_decline))
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onConfirm, modifier = Modifier.testTag("relay_invite_confirm")) {
                    Text(stringResource(confirmLabel(preview)))
                }
            }
        }
    }
}

/** The facts about this relay and its room, one line each, only the ones that apply. */
@Composable
private fun InviteFacts(preview: RelayInviteApplier.Preview) {
    val lines = mutableListOf<Pair<String, String>>()
    if (preview.private) lines += "relay_invite_private" to stringResource(R.string.relays_invite_private)
    when {
        preview.replaces != null -> lines += "relay_invite_replaces" to stringResource(R.string.relays_invite_replaces)
        preview.parked -> lines += "relay_invite_parked" to stringResource(R.string.relays_invite_parked)
        preview.alreadyAdded -> lines += "relay_invite_already" to stringResource(R.string.relays_invite_already)
    }
    preview.room?.let { room ->
        lines +=
            when {
                room.alreadyJoined -> "relay_invite_room_joined" to stringResource(R.string.relays_invite_room_joined)
                room.name != null -> "relay_invite_room" to stringResource(R.string.relays_invite_room, room.name)
                else -> "relay_invite_room" to stringResource(R.string.relays_invite_room_unnamed)
            }
        // A second room joins beside the first (C-7.4-18): said, because the app once left the first for it.
        if (room.beside) lines += "relay_invite_room_beside" to stringResource(R.string.relays_invite_room_beside)
    }
    // The key's line names the issuer and what it will be asked, about once a day (ADR 2026-10.3sk7): no price, no
    // product, no link. A held key says so; a different one says it replaces the held one.
    preview.ticketKey?.let { key ->
        lines +=
            when {
                key.alreadyHeld -> "relay_invite_key_held" to stringResource(R.string.relays_invite_key_held)
                key.replacesKey -> "relay_invite_key_replaces" to stringResource(R.string.relays_invite_key_replaces, key.issuerHost)
                else -> "relay_invite_key" to stringResource(R.string.relays_invite_key, key.issuerHost)
            }
    }
    for ((tag, text) in lines) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(tag),
        )
    }
}

/** The confirm button's label — the action, not an "OK", except when there is nothing left to do. */
private fun confirmLabel(preview: RelayInviteApplier.Preview): Int {
    val joins = preview.room?.let { !it.alreadyJoined } ?: false
    val turnsOn = preview.consentNeeded || preview.planeOff
    // The relay is in place and on, so all a confirmation does is hold the link's ticket key.
    val savesKeyOnly = preview.alreadyAdded && !preview.parked && preview.ticketKey?.alreadyHeld == false
    return when {
        preview.isNoOp -> R.string.relays_invite_confirm_noop
        turnsOn && joins -> R.string.relays_invite_confirm_turn_on_join
        turnsOn -> R.string.relays_invite_confirm_turn_on_add
        joins -> R.string.relays_invite_confirm_join
        savesKeyOnly -> R.string.relays_invite_confirm_key
        else -> R.string.relays_invite_confirm_add
    }
}
