package app.getknit.knit.ui.translate

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.getknit.knit.R
import app.getknit.knit.translate.TranslateDirection
import app.getknit.knit.translate.TranslateLang
import app.getknit.knit.ui.preview.KnitPreview

/**
 * The line above the field: the draft holds a translation (and Undo puts the original back), or one is running,
 * downloading or failed, or the chat is in another language than the draft and a tap translates it. Nothing when
 * none of those holds, or [canSend] is false (an empty draft has nothing to suggest).
 */
@Composable
fun DraftTranslateLine(
    ui: DraftTranslateUi,
    canSend: Boolean,
    onTranslate: (from: TranslateLang?) -> Unit,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
    onNotThisLanguage: () -> Unit = {},
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val hint = ui.hint
    val from = ui.translatedFrom
    val line: (@Composable RowScope.() -> Unit)? =
        when {
            from != null -> {
                {
                    Text(
                        stringResource(R.string.translate_label, languageName(from)),
                        style = MaterialTheme.typography.labelMedium,
                        color = muted,
                    )
                    Spacer(Modifier.weight(1f))
                    if (ui.translatedGuessed) {
                        TextButton(
                            onClick = onNotThisLanguage,
                        ) { Text(stringResource(R.string.translate_not_language, languageName(from))) }
                    }
                    TextButton(onClick = onUndo) { Text(stringResource(R.string.translate_draft_undo)) }
                }
            }

            ui.running -> {
                {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.translate_running), style = MaterialTheme.typography.labelMedium, color = muted)
                }
            }

            ui.downloadingPercent != null -> {
                {
                    Text(
                        stringResource(R.string.translate_downloading, ui.downloadingPercent),
                        style = MaterialTheme.typography.labelMedium,
                        color = muted,
                    )
                }
            }

            ui.failedUnusable != null -> {
                {
                    Text(
                        stringResource(if (ui.failedUnusable) R.string.translate_unusable else R.string.translate_failed),
                        style = MaterialTheme.typography.labelMedium,
                        color = muted,
                    )
                }
            }

            hint != null && canSend -> {
                {
                    Text(
                        stringResource(R.string.translate_draft_hint, languageName(hint.to)),
                        style = MaterialTheme.typography.labelMedium,
                        color = muted,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    TextButton(onClick = { onTranslate(hint.from) }) { Text(stringResource(R.string.translate_draft_hint_action)) }
                }
            }

            else -> {
                null
            }
        }
    if (line == null) return
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .testTag("chat_draft_translate_line"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Filled.Translate, contentDescription = null, modifier = Modifier.size(16.dp), tint = muted)
        line()
    }
}

/**
 * The field's translate button, beside the text the way the mic sits on an empty one: [lit] when the chat is in
 * another language than the draft, a spinner while it runs.
 */
@Composable
fun TranslateDraftButton(
    lit: Boolean,
    running: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .size(48.dp)
                .testTag("chat_translate_draft")
                .clip(CircleShape)
                .clickable(enabled = !running, onClick = onClick, role = Role.Button),
        contentAlignment = Alignment.Center,
    ) {
        if (running) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Icon(
                Icons.Filled.Translate,
                contentDescription = stringResource(R.string.translate_draft_button),
                tint = if (lit) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/** Before the first translation in a direction: the model's size, and on a metered route that it rides mobile data. */
@Composable
fun TranslateModelOfferDialog(
    direction: TranslateDirection,
    metered: Boolean,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.translate_model_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(
                        R.string.translate_needs_model,
                        languageName(direction.from),
                        Formatter.formatShortFileSize(LocalContext.current, direction.bytes),
                    ),
                )
                if (metered) Text(stringResource(R.string.translate_metered_note))
            }
        },
        confirmButton = { TextButton(onClick = onDownload) { Text(stringResource(R.string.translate_download)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.translate_not_now)) } },
    )
}

@Composable
private fun ComposerPreviewFrame(content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(vertical = 8.dp)) {
            content()
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier
                    .padding(horizontal = 8.dp)
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(24.dp)),
            ) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.translate_preview_draft),
                            modifier = Modifier.padding(start = 16.dp).weight(1f),
                        )
                        TranslateDraftButton(lit = true, running = false, onClick = {})
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun DraftTranslateHintPreview() =
    KnitPreview {
        ComposerPreviewFrame {
            DraftTranslateLine(
                ui = DraftTranslateUi(hint = TranslateDirection.ES_EN),
                canSend = true,
                onTranslate = {},
                onUndo = {},
            )
        }
    }

@Preview(showBackground = true)
@Composable
fun DraftTranslatedGuessedPreview() =
    KnitPreview {
        DraftTranslateLine(
            ui = DraftTranslateUi(translatedFrom = TranslateLang.EN, translatedGuessed = true),
            canSend = true,
            onTranslate = {},
            onUndo = {},
        )
    }

@Preview(showBackground = true)
@Composable
fun DraftTranslatedPreview() =
    KnitPreview {
        DraftTranslateLine(
            ui = DraftTranslateUi(translatedFrom = TranslateLang.ES),
            canSend = true,
            onTranslate = {},
            onUndo = {},
        )
    }
