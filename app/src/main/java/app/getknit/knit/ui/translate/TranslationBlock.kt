package app.getknit.knit.ui.translate

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.getknit.knit.R
import app.getknit.knit.call.SttModelState
import app.getknit.knit.translate.TranslateDirection
import app.getknit.knit.translate.TranslateDirections
import app.getknit.knit.translate.TranslateLang
import app.getknit.knit.ui.preview.KnitPreview

/** [lang]'s name in the app's language, as a sentence carries it ("Translated from Spanish", "Übersetzt aus: Spanisch"). */
@Composable
internal fun languageName(lang: TranslateLang): String {
    val locale = LocalConfiguration.current.locales[0]
    return remember(lang, locale) { LanguageNames.name(lang, locale) }
}

/** [lang]'s name on its own, as a button or a list carries it: capitalised where the language lowercases it. */
@Composable
internal fun languageTitle(lang: TranslateLang): String {
    val locale = LocalConfiguration.current.locales[0]
    return remember(lang, locale) { LanguageNames.title(lang, locale) }
}

/**
 * Under a translated message, whose bubble shows the translation in the original's place: what it was translated
 * from, and the tap to the original and back. [onNotThisLanguage], given when [from] was a guess from the author's or
 * the chat's usual language rather than the words (ADR 2026-10.t2h6's short-line amendment), offers "Not English?":
 * the reader knows better than any prior.
 */
@Composable
fun TranslationToggle(
    from: TranslateLang,
    showingOriginal: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    onNotThisLanguage: (() -> Unit)? = null,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    // A narrow bubble wraps the row by whole items, never "Show" on one line and "original" on the next: a flow row
    // measures each item against the whole line, so an item breaks inside only when it is longer than a line alone.
    FlowRow(
        modifier = modifier.padding(top = 4.dp).testTag("chat_translation"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Translate, contentDescription = null, modifier = Modifier.size(14.dp), tint = muted)
        if (!showingOriginal) {
            Text(
                stringResource(R.string.translate_label, languageName(from)),
                style = MaterialTheme.typography.labelSmall,
                color = muted,
            )
            if (onNotThisLanguage != null) {
                Text(
                    stringResource(R.string.translate_not_language, languageName(from)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onNotThisLanguage).padding(vertical = 4.dp),
                )
            }
        }
        Text(
            stringResource(if (showingOriginal) R.string.translate_show_translation else R.string.translate_show_original),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(onClick = onToggle).padding(vertical = 4.dp),
        )
    }
}

/**
 * A translation on its way under a message's body: running, waiting on its model's download, or failed. A finished
 * one takes the body's place instead ([TranslationToggle]).
 */
@Composable
fun TranslationBlock(
    shown: TranslationShown,
    onDownload: (TranslateDirection) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier = modifier.padding(top = 4.dp).testTag("chat_translation")) {
        HorizontalDivider(color = muted.copy(alpha = 0.3f))
        Spacer(Modifier.height(4.dp))
        when (shown) {
            TranslationShown.Running -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.translate_running), style = MaterialTheme.typography.bodySmall, color = muted)
                }
            }

            // The bubble draws a finished translation in the body's place.
            is TranslationShown.Text -> {}

            is TranslationShown.NeedsModel -> {
                NeedsModel(shown, onDownload, onDismiss)
            }

            is TranslationShown.Failed -> {
                Text(
                    stringResource(if (shown.unusable) R.string.translate_unusable else R.string.translate_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                    modifier = Modifier.clickable(onClick = onDismiss),
                )
            }

            is TranslationShown.NoTarget -> {
                Text(
                    if (shown.already) {
                        stringResource(R.string.translate_already_in, languageName(shown.from))
                    } else {
                        stringResource(R.string.translate_no_target, languageName(shown.from))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                    modifier = Modifier.clickable(onClick = onDismiss),
                )
            }
        }
    }
}

@Composable
private fun NeedsModel(
    shown: TranslationShown.NeedsModel,
    onDownload: (TranslateDirection) -> Unit,
    onDismiss: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val size = Formatter.formatShortFileSize(LocalContext.current, shown.direction.bytes)
    val from = languageName(shown.direction.from)
    when (val model = shown.model) {
        is SttModelState.Downloading -> {
            val percent = if (model.total > 0) (model.done * PERCENT / model.total).toInt() else 0
            Text(stringResource(R.string.translate_downloading, percent), style = MaterialTheme.typography.bodySmall, color = muted)
        }

        else -> {
            Text(stringResource(R.string.translate_needs_model, from, size), style = MaterialTheme.typography.bodySmall, color = muted)
            if (model is SttModelState.Failed) {
                Text(model.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            // A narrow bubble puts Not now under Download rather than cutting it off.
            FlowRow {
                TextButton(onClick = { onDownload(shown.direction) }) { Text(stringResource(R.string.translate_download)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.translate_not_now)) }
            }
        }
    }
}

/** Asked when CLD2 could not tell which language a message is in, or the reader said it is not the one it guessed. */
@Composable
fun TranslateFromDialog(
    choices: List<TranslateLang>,
    onPick: (TranslateLang) -> Unit,
    onDismiss: () -> Unit,
) = LanguageChoiceDialog(
    title = stringResource(R.string.translate_from_title),
    body = stringResource(R.string.translate_from_body),
    choices = choices,
    onPick = onPick,
    onDismiss = onDismiss,
    modifier = Modifier.testTag("chat_translate_from"),
)

/** Asked when a draft could go into more than one language and the chat says nothing of which. */
@Composable
fun TranslateToDialog(
    choices: List<TranslateLang>,
    onPick: (TranslateLang) -> Unit,
    onDismiss: () -> Unit,
) = LanguageChoiceDialog(
    title = stringResource(R.string.translate_to_title),
    body = null,
    choices = choices,
    onPick = onPick,
    onDismiss = onDismiss,
    modifier = Modifier.testTag("chat_translate_to"),
)

// A few languages are buttons; more are a list, which scrolls inside the dialog rather than off its edge.
@Composable
private fun LanguageChoiceDialog(
    title: String,
    body: String?,
    choices: List<TranslateLang>,
    onPick: (TranslateLang) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val asButtons = choices.size <= BUTTON_CHOICES
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (body != null) Text(body)
                if (!asButtons) {
                    choices.forEach { lang ->
                        TextButton(onClick = { onPick(lang) }, modifier = Modifier.fillMaxWidth()) {
                            Text(languageTitle(lang), modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (asButtons) {
                // A language name runs long in some languages: they wrap as whole buttons, never off the dialog's edge.
                FlowRow(horizontalArrangement = Arrangement.End) {
                    choices.forEach { lang -> TextButton(onClick = { onPick(lang) }) { Text(languageTitle(lang)) } }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.translate_not_now)) } },
        modifier = modifier,
    )
}

/** Before a download on a metered route: the model's size, and that it rides mobile data. */
@Composable
fun TranslateMeteredDialog(
    direction: TranslateDirection,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.translate_download)) },
        text = {
            Text(
                stringResource(
                    R.string.translate_metered,
                    Formatter.formatShortFileSize(LocalContext.current, direction.bytes),
                ),
            )
        },
        confirmButton = { TextButton(onClick = onDownload) { Text(stringResource(R.string.translate_download)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.translate_not_now)) } },
    )
}

private const val PERCENT = 100

// More choices than this are a list.
private const val BUTTON_CHOICES = 3

@Preview(showBackground = true)
@Composable
fun TranslationToggleTranslatedPreview() =
    KnitPreview {
        TranslationToggle(from = TranslateDirections.ES_EN.from, showingOriginal = false, onToggle = {})
    }

@Preview(showBackground = true)
@Composable
fun TranslationToggleGuessedPreview() =
    KnitPreview {
        TranslationToggle(from = TranslateLang.EN, showingOriginal = false, onToggle = {}, onNotThisLanguage = {})
    }

@Preview(showBackground = true)
@Composable
fun TranslationToggleOriginalPreview() =
    KnitPreview {
        TranslationToggle(from = TranslateDirections.ES_EN.from, showingOriginal = true, onToggle = {})
    }

@Preview(showBackground = true)
@Composable
fun TranslationBlockTranslatingPreview() =
    KnitPreview {
        TranslationBlock(shown = TranslationShown.Running, onDownload = {}, onDismiss = {})
    }

@Preview(showBackground = true)
@Composable
fun TranslationBlockNeedsModelPreview() =
    KnitPreview {
        TranslationBlock(
            shown = TranslationShown.NeedsModel(TranslateDirections.ES_EN, SttModelState.Absent),
            onDownload = {},
            onDismiss = {},
        )
    }
