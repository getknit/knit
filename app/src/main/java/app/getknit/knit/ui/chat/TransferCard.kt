package app.getknit.knit.ui.chat

import android.content.Context
import android.content.Intent
import android.text.format.Formatter
import android.widget.Toast
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
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.getknit.knit.R
import app.getknit.knit.data.message.TransferPhase
import app.getknit.knit.mesh.protocol.TransferPayload
import app.getknit.knit.ui.theme.KnitTheme

/**
 * The chat card for a direct Wi-Fi file transfer ([TransferView]): the file's icon, name and size, where the
 * transfer stands, a progress bar while the bytes move, and the one or two answers the phase allows —
 * Accept/Decline on an incoming offer, Cancel while it runs, Open once a file has been received, Retry on
 * the sender's side once it ended short of that (the same file again, no picker). Drawn in
 * the [FileAttachmentBubble] idiom and aligned like a bubble, on the side of whoever offered the file.
 */
@Composable
fun TransferCard(
    view: TransferView,
    mine: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {},
) {
    val context = LocalContext.current
    val size = Formatter.formatShortFileSize(context, view.size)
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        // The card paints its own container, so it carries the matching content colour (FileAttachmentBubble's reason).
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
            Column(
                modifier =
                    Modifier
                        .width(CARD_WIDTH)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(10.dp)
                        .testTag("transfer_card"),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(ICON_SLOT)) {
                        Icon(
                            if (view.models !=
                                null
                            ) {
                                Icons.Filled.Translate
                            } else {
                                fileIconFor(view.mime, view.name)
                            },
                            contentDescription = null,
                        )
                    }
                    Column(modifier = Modifier.padding(start = 10.dp)) {
                        Text(
                            transferTitle(context, view),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // An offer that brings this phone nothing new carries no size: nothing would cross.
                        val status = transferStatusText(view)
                        Text(
                            if (view.size > 0) "$size · $status" else status,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (view.phase == TransferPhase.Transferring && !view.interrupted) {
                    LinearProgressIndicator(
                        progress = { (view.bytes.toFloat() / view.size.coerceAtLeast(1L)).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    Text(
                        stringResource(R.string.chat_transfer_progress, Formatter.formatShortFileSize(context, view.bytes), size),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (view.incomingModelsOffer) {
                    ModelsOfferLines(view)
                }
                if (view.risky && view.phase == TransferPhase.Offered && !view.outgoing) {
                    Text(
                        stringResource(R.string.chat_file_risky_body, view.name),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                TransferActions(view, onAccept, onDecline, onCancel, onRetry) { uri ->
                    if (!openSavedFile(context, uri, view.mime)) {
                        Toast.makeText(context, R.string.chat_transfer_no_app, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}

/**
 * What an incoming models offer means for this phone (ADR 2026-11.ejn8), read from its own table and models: what it
 * already holds, what its version of Knit does not use, or that nothing in it is new. Only the card says so; the
 * sender learns nothing until someone taps.
 */
@Composable
private fun ModelsOfferLines(view: TransferView) {
    val style = MaterialTheme.typography.labelSmall
    val lines =
        buildList {
            if (view.modelsNew == 0) add(stringResource(R.string.chat_transfer_models_nothing_new))
            if (view.modelsHeld > 0) add(pluralStringResource(R.plurals.chat_transfer_models_held, view.modelsHeld, view.modelsHeld))
            if (view.modelsUnknown > 0) {
                add(pluralStringResource(R.plurals.chat_transfer_models_unknown, view.modelsUnknown, view.modelsUnknown))
            }
        }
    lines.forEach { Text(it, style = style, modifier = Modifier.padding(top = 4.dp)) }
}

@Composable
private fun TransferActions(
    view: TransferView,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onOpen: (uri: String) -> Unit,
) {
    val answerable = !view.interrupted && !view.phase.terminal
    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
        when {
            answerable && view.phase == TransferPhase.Offered && !view.outgoing -> {
                TextButton(onClick = onDecline, modifier = Modifier.testTag("transfer_decline")) {
                    Text(stringResource(R.string.chat_transfer_decline))
                }
                // A models offer with nothing new for this phone has nothing to accept.
                if (view.models == null || view.modelsNew > 0) {
                    TextButton(onClick = onAccept, modifier = Modifier.testTag("transfer_accept")) {
                        Text(stringResource(R.string.chat_transfer_accept))
                    }
                }
            }

            answerable -> {
                TextButton(onClick = onCancel, modifier = Modifier.testTag("transfer_cancel")) {
                    Text(stringResource(R.string.chat_transfer_cancel))
                }
            }

            view.retryable -> {
                TextButton(onClick = onRetry, modifier = Modifier.testTag("transfer_retry")) {
                    Text(stringResource(R.string.chat_transfer_retry))
                }
            }

            // Open lands in Downloads through whatever app claims the type. Never for a package: Knit must
            // not become the install source, so a received APK is left to the Files app.
            view.phase == TransferPhase.Done && !view.outgoing && view.savedUri != null && !view.installable -> {
                val uri = view.savedUri
                TextButton(onClick = { onOpen(uri) }, modifier = Modifier.testTag("transfer_open")) {
                    Text(stringResource(R.string.chat_transfer_open))
                }
            }
        }
    }
}

@Composable
private fun transferStatusText(view: TransferView): String =
    when {
        view.interrupted -> {
            stringResource(R.string.chat_transfer_interrupted)
        }

        view.phase == TransferPhase.Offered && !view.outgoing && view.models != null -> {
            stringResource(R.string.chat_transfer_models_incoming)
        }

        view.phase == TransferPhase.Offered -> {
            stringResource(if (view.outgoing) R.string.chat_transfer_offered else R.string.chat_transfer_incoming)
        }

        view.phase == TransferPhase.Connecting -> {
            stringResource(R.string.chat_transfer_connecting)
        }

        view.phase == TransferPhase.Transferring -> {
            stringResource(if (view.outgoing) R.string.chat_transfer_sending else R.string.chat_transfer_receiving)
        }

        view.phase == TransferPhase.Done && !view.outgoing && view.models != null -> {
            stringResource(R.string.chat_transfer_models_installed)
        }

        view.phase == TransferPhase.Done -> {
            stringResource(if (view.outgoing) R.string.chat_transfer_sent else R.string.chat_transfer_received)
        }

        view.phase == TransferPhase.Declined -> {
            stringResource(declinedText(view))
        }

        view.phase == TransferPhase.Expired -> {
            stringResource(R.string.chat_transfer_expired)
        }

        view.phase == TransferPhase.Cancelled -> {
            stringResource(R.string.chat_transfer_cancelled)
        }

        else -> {
            stringResource(failedText(view.reason, models = view.models != null))
        }
    }

private fun declinedText(view: TransferView): Int =
    when {
        !view.outgoing -> R.string.chat_transfer_declined_by_you
        view.reason == TransferPayload.REASON_BUSY -> R.string.chat_transfer_declined_busy
        else -> R.string.chat_transfer_declined
    }

internal fun failedText(
    reason: Int?,
    models: Boolean,
): Int {
    // Every byte arrived intact; the receiver could not keep them, which is not a dropped link.
    val notSaved = if (models) R.string.chat_transfer_failed_not_installed else R.string.chat_transfer_failed_not_saved
    return when (reason) {
        TransferPayload.REASON_NOT_SAVED -> notSaved
        TransferPayload.REASON_CORRUPT -> R.string.chat_transfer_failed_corrupt
        TransferPayload.REASON_CONNECTION -> R.string.chat_transfer_failed_connection
        TransferPayload.REASON_JOIN_FAILED -> R.string.chat_transfer_failed_join
        TransferPayload.REASON_NO_SPACE -> R.string.chat_transfer_failed_no_space
        TransferPayload.REASON_TIMEOUT -> R.string.chat_transfer_failed_timeout
        TransferPayload.REASON_FOREGROUND -> R.string.chat_transfer_failed_background
        else -> R.string.chat_transfer_failed
    }
}

/** Hands the saved file to whatever app claims its type; false when nothing does. */
internal fun openSavedFile(
    context: Context,
    uri: String,
    mime: String?,
): Boolean =
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri.toUri(), mime ?: "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }.isSuccess

private val CARD_WIDTH = 240.dp
private val ICON_SLOT = 36.dp

@Preview(showBackground = true)
@Composable
fun TransferCardModelsOfferPreview() {
    // An incoming bundle of three (ADR 2026-11.ejn8): two new to this phone, one already here.
    KnitTheme {
        TransferCard(
            view =
                TransferView(
                    id = "x",
                    outgoing = false,
                    name = "knit-translation-models.tar",
                    size = 110_000_000L,
                    mime = null,
                    phase = TransferPhase.Offered,
                    bytes = 0L,
                    savedUri = null,
                    reason = null,
                    interrupted = false,
                    models = listOf(0x3b1c3995L, 0x4aed7734L, 0xed8bb4a8L),
                    modelsNew = 2,
                    modelsHeld = 1,
                ),
            mine = false,
            onAccept = {},
            onDecline = {},
            onCancel = {},
        )
    }
}
