package app.getknit.knit.ui.update

import android.content.Context
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.getknit.knit.BuildConfig
import app.getknit.knit.R
import app.getknit.knit.data.message.AppUpdateRecord
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.message.TransferPhase
import app.getknit.knit.data.message.UpdateRequestPhase
import app.getknit.knit.data.message.UpdateRequestRecord
import app.getknit.knit.mesh.protocol.TransferPayload
import app.getknit.knit.ui.chat.TransferActions
import app.getknit.knit.ui.chat.TransferProgress
import app.getknit.knit.ui.chat.TransferView
import app.getknit.knit.ui.chat.transferStatusText
import app.getknit.knit.ui.preview.KnitPreview
import app.getknit.knit.update.VersionNames

/**
 * The newer phone's card for a contact's ask (ADR 2026-11.29m7), drawn like a received transfer card on the asker's
 * side of the thread: [peerName] asked for this phone's Knit, [size] bytes over a direct transfer. Send and Not now
 * show only while the ask is [live] (still waiting in memory, so a tap can answer it); otherwise the card says how it
 * ended, and a waiting record with no live ask (after a restart) reads as expired.
 *
 * Once Send has started the update, [transfer] is that transfer (`ui/chat/foldAskedTransfers` ties the two by request
 * id), and this one card follows it as a transfer card would: connecting, the bytes, sent or why not, with Cancel while
 * it runs. The thread has no second card for it.
 */
@Composable
fun UpdateRequestCard(
    record: UpdateRequestRecord,
    peerName: String,
    live: Boolean,
    size: Long,
    onSend: () -> Unit,
    onNotNow: () -> Unit,
    modifier: Modifier = Modifier,
    transfer: TransferView? = null,
    onCancel: () -> Unit = {},
) {
    val context = LocalContext.current
    val version = VersionNames.nameOf(record.versionCode) ?: BuildConfig.VERSION_NAME
    val answerable = transfer == null && live && record.phase == UpdateRequestPhase.Waiting
    val status =
        if (transfer != null) {
            transferStatusText(transfer, installReady = false, installing = false)
        } else {
            stringResource(
                when (record.phase) {
                    UpdateRequestPhase.Waiting -> if (answerable) R.string.update_request_card_waiting else R.string.update_request_gone
                    UpdateRequestPhase.Sent -> R.string.update_request_card_accepted
                    UpdateRequestPhase.Declined -> R.string.update_request_card_declined
                    UpdateRequestPhase.Unsent -> R.string.update_request_card_unsent
                    UpdateRequestPhase.Expired -> R.string.update_request_gone
                },
            )
        }
    val shownSize = transfer?.size ?: size.takeIf { answerable } ?: 0L
    Row(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), horizontalArrangement = Arrangement.Start) {
        // The card paints its own container, so it carries the matching content colour (TransferCard's reason).
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
            Column(
                modifier =
                    Modifier
                        .width(CARD_WIDTH)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(10.dp)
                        .testTag("update_request_card"),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(ICON_SLOT)) {
                        Icon(Icons.Filled.SystemUpdate, contentDescription = null)
                    }
                    Column(modifier = Modifier.padding(start = 10.dp)) {
                        Text(
                            stringResource(R.string.update_request_title, peerName, version),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (shownSize > 0) "${Formatter.formatShortFileSize(context, shownSize)} · $status" else status,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (transfer != null) {
                    TransferProgress(transfer)
                    TransferActions(transfer, {}, {}, onCancel, onRetry = {}, onInstall = null, onOpen = {})
                } else if (answerable) {
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = onNotNow, modifier = Modifier.testTag("chat_update_not_now")) {
                            Text(stringResource(R.string.update_request_not_now))
                        }
                        TextButton(onClick = onSend, modifier = Modifier.testTag("chat_update_send")) {
                            Text(stringResource(R.string.update_request_send))
                        }
                    }
                }
            }
        }
    }
}

/** The chat list's line for an ask's row: "Asked for your Knit 2.14.0", whatever became of it. */
fun updateRequestPreview(
    context: Context,
    row: MessageEntity,
): String? =
    UpdateRequestRecord.decode(row.body)?.let {
        context.getString(R.string.notif_update_request, VersionNames.nameOf(it.versionCode) ?: BuildConfig.VERSION_NAME)
    }

private val CARD_WIDTH = 240.dp
private val ICON_SLOT = 36.dp
private const val PREVIEW_VERSION = 21_400_900L

@Preview(showBackground = true)
@Composable
fun UpdateRequestCardPreview() =
    KnitPreview {
        UpdateRequestCard(
            UpdateRequestRecord("r", PREVIEW_VERSION, UpdateRequestPhase.Waiting),
            peerName = "Alex",
            live = true,
            size = 124_000_000L,
            onSend = {},
            onNotNow = {},
        )
    }

@Preview(showBackground = true)
@Composable
fun UpdateRequestCardOutcomesPreview() =
    KnitPreview {
        Column {
            listOf(UpdateRequestPhase.Sent, UpdateRequestPhase.Declined, UpdateRequestPhase.Unsent, UpdateRequestPhase.Expired).forEach {
                UpdateRequestCard(
                    UpdateRequestRecord("r", PREVIEW_VERSION, it),
                    "Alex",
                    live = false,
                    size = 0L,
                    onSend = {},
                    onNotNow = {},
                )
            }
        }
    }

@Preview(showBackground = true)
@Composable
fun UpdateRequestCardSendingPreview() =
    KnitPreview {
        Column {
            listOf(
                TransferPhase.Connecting to 0L,
                TransferPhase.Transferring to 98_000_000L,
                TransferPhase.Done to 0L,
                TransferPhase.Failed to 0L,
            ).forEach { (phase, bytes) ->
                UpdateRequestCard(
                    UpdateRequestRecord("r", PREVIEW_VERSION, UpdateRequestPhase.Sent),
                    "Alex",
                    live = false,
                    size = 0L,
                    onSend = {},
                    onNotNow = {},
                    transfer =
                        TransferView(
                            id = "t",
                            outgoing = true,
                            name = "Knit.apk",
                            size = 259_000_000L,
                            mime = null,
                            phase = phase,
                            bytes = bytes,
                            savedUri = null,
                            reason = TransferPayload.REASON_CONNECTION.takeIf { phase == TransferPhase.Failed },
                            interrupted = false,
                            app = AppUpdateRecord(PREVIEW_VERSION, "00"),
                        ),
                )
            }
        }
    }
