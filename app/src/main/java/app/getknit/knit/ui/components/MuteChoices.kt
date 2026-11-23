package app.getknit.knit.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.getknit.knit.R
import app.getknit.knit.data.settings.ChatMutes
import app.getknit.knit.notifications.MuteFor
import app.getknit.knit.notifications.mutedUntilLabel
import app.getknit.knit.ui.preview.KnitPreview
import app.getknit.knit.ui.preview.PREVIEW_NOW
import java.util.concurrent.TimeUnit

/**
 * The spans a chat can be muted for (ADR 2026-11.sp7k), one tap each. The chat screen puts this in its in-window
 * dialog, which keeps the keyboard up; the details screens use [MuteDialog].
 */
@Composable
fun MuteChoices(
    onPick: (MuteFor) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        MuteFor.entries.forEach { span ->
            Text(
                text = muteForLabel(span),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { onPick(span) }
                        .padding(vertical = 12.dp)
                        .testTag("mute_for_${span.name.lowercase()}"),
            )
        }
    }
}

/** [MuteChoices] in a plain dialog, for a screen with no composer to keep up. */
@Composable
fun MuteDialog(
    onPick: (MuteFor) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_mute)) },
        text = { MuteChoices(onPick) },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        modifier = Modifier.testTag("mute_dialog"),
    )
}

/** What a span is called: the plural's count is the span's own number. */
@Composable
fun muteForLabel(span: MuteFor): String =
    when (span) {
        MuteFor.HOUR -> pluralStringResource(R.plurals.chat_mute_for_hours, 1, 1)
        MuteFor.EIGHT_HOURS -> pluralStringResource(R.plurals.chat_mute_for_hours, EIGHT, EIGHT)
        MuteFor.DAY -> pluralStringResource(R.plurals.chat_mute_for_days, 1, 1)
        MuteFor.WEEK -> pluralStringResource(R.plurals.chat_mute_for_days, SEVEN, SEVEN)
        MuteFor.ALWAYS -> stringResource(R.string.chat_mute_always)
    }

/** "Muted until 3:15 PM", or "Muted" for a mute with no end: a live mute ending at [until], read at [now]. */
@Composable
fun mutedLabel(
    until: Long,
    now: Long,
): String =
    if (until == ChatMutes.ALWAYS) {
        stringResource(R.string.chat_muted)
    } else {
        stringResource(R.string.chat_muted_until, mutedUntilLabel(LocalContext.current, until, now))
    }

/**
 * The details screens' row: Mute, which opens the spans, or Unmute with when the mute ends. [mutedUntil] is a live
 * deadline (`ChatMute.active` has dropped a lapsed one), null when the chat is not muted. [horizontalPadding] lines
 * the row up with a screen whose column is padded already.
 */
@Composable
fun MuteNotificationsRow(
    mutedUntil: Long?,
    now: Long,
    onMute: (MuteFor) -> Unit,
    onUnmute: () -> Unit,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 16.dp,
) {
    var choosing by remember { mutableStateOf(false) }
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(role = Role.Button) { if (mutedUntil == null) choosing = true else onUnmute() }
                .padding(horizontal = horizontalPadding, vertical = 8.dp)
                .testTag("mute_notifications_row"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            if (mutedUntil == null) Icons.Outlined.Notifications else Icons.Outlined.NotificationsOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(if (mutedUntil == null) R.string.chat_mute else R.string.chat_unmute),
                style = MaterialTheme.typography.bodyLarge,
            )
            if (mutedUntil != null) {
                Text(
                    mutedLabel(mutedUntil, now),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (choosing) {
        MuteDialog(
            onPick = {
                choosing = false
                onMute(it)
            },
            onDismiss = { choosing = false },
        )
    }
}

private const val EIGHT = 8
private const val SEVEN = 7

@Preview(showBackground = true)
@Composable
fun MuteChoicesPreview() =
    KnitPreview {
        MuteChoices(onPick = {}, modifier = Modifier.padding(horizontal = 24.dp))
    }

@Preview(showBackground = true)
@Composable
fun MuteNotificationsRowPreview() =
    KnitPreview {
        Column {
            MuteNotificationsRow(mutedUntil = null, now = PREVIEW_NOW, onMute = {}, onUnmute = {})
            MuteNotificationsRow(
                mutedUntil = PREVIEW_NOW + TimeUnit.HOURS.toMillis(8),
                now = PREVIEW_NOW,
                onMute = {},
                onUnmute = {},
            )
            MuteNotificationsRow(mutedUntil = ChatMutes.ALWAYS, now = PREVIEW_NOW, onMute = {}, onUnmute = {})
        }
    }
