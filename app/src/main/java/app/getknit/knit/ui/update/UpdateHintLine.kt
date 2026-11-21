package app.getknit.knit.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.getknit.knit.R
import app.getknit.knit.mesh.protocol.AppUpdatePayload
import app.getknit.knit.ui.preview.KnitPreview
import app.getknit.knit.update.AppBuild
import app.getknit.knit.update.AskState
import app.getknit.knit.update.UpdateHint

/**
 * The older phone's line above the composer (ADR 2026-11.29m7), drawn like the translation hint: [peerName] runs a
 * newer Knit that can install here. It offers Ask, then says how the ask stands; the close puts it away for that version.
 */
@Composable
fun UpdateHintLine(
    peerName: String,
    hint: UpdateHint,
    onAsk: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val version = hint.build.versionName
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val (text, action) =
        when (val ask = hint.ask) {
            is AskState.Waiting -> stringResource(R.string.update_hint_waiting, peerName, version) to null
            is AskState.Answered -> stringResource(R.string.update_hint_answered, peerName, version) to null
            is AskState.Declined -> stringResource(declinedText(ask.reason), peerName) to R.string.update_hint_ask_again
            is AskState.Unanswered -> stringResource(R.string.update_hint_unanswered, peerName) to R.string.update_hint_ask_again
            is AskState.Failed -> stringResource(R.string.update_hint_failed, peerName) to R.string.update_hint_ask_again
            null -> stringResource(R.string.update_hint, peerName, version) to R.string.update_hint_ask
        }
    Row(
        modifier = modifier.fillMaxWidth().padding(start = 12.dp).testTag("chat_update_hint"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Filled.SystemUpdate, contentDescription = null, tint = muted, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = muted, modifier = Modifier.weight(1f))
        if (action != null) {
            TextButton(onClick = onAsk, modifier = Modifier.testTag("chat_update_ask")) { Text(stringResource(action)) }
        }
        IconButton(onClick = onDismiss, modifier = Modifier.testTag("chat_update_dismiss")) {
            Icon(
                Icons.Filled.Close,
                contentDescription = stringResource(R.string.update_hint_dismiss),
                tint = muted,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

private fun declinedText(reason: Int): Int =
    when (reason) {
        AppUpdatePayload.REASON_BUSY -> R.string.update_hint_declined_busy
        AppUpdatePayload.REASON_GONE -> R.string.update_hint_declined_gone
        else -> R.string.update_hint_declined
    }

private val PREVIEW_BUILD = AppBuild(21_400_900L, "2.14.0", 7L, 124_000_000L, 29, slim = false)

@Preview(showBackground = true)
@Composable
fun UpdateHintLinePreview() =
    KnitPreview {
        UpdateHintLine(peerName = "Alex", hint = UpdateHint("a", PREVIEW_BUILD, ask = null), onAsk = {}, onDismiss = {})
    }

@Preview(showBackground = true)
@Composable
fun UpdateHintLineWaitingPreview() =
    KnitPreview {
        UpdateHintLine(
            peerName = "Alex",
            hint = UpdateHint("a", PREVIEW_BUILD, AskState.Waiting(PREVIEW_BUILD, "r", 0L)),
            onAsk = {},
            onDismiss = {},
        )
    }
