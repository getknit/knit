package app.getknit.knit.ui.chat

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.getknit.knit.R
import app.getknit.knit.translate.TranslateLang
import app.getknit.knit.ui.icons.KnitIcons
import app.getknit.knit.ui.theme.KnitTheme
import app.getknit.knit.ui.translate.LanguageNames

/**
 * The first step of a direct send when this phone holds translation models the peer can take (ADR 2026-11.ejn8):
 * a file, or models. Never shown where no model could go; the overflow item opens the picker straight away there.
 */
@Composable
internal fun DirectSendChooserBody(
    onFile: () -> Unit,
    onModels: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Text(
            stringResource(R.string.chat_transfer_choose_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.chat_transfer_choose_file)) },
            leadingContent = { Icon(KnitIcons.DirectTransfer, contentDescription = null) },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            modifier = Modifier.toggleable(value = false, role = Role.Button, onValueChange = { onFile() }).testTag("direct_send_file"),
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.chat_transfer_choose_models)) },
            supportingContent = { Text(stringResource(R.string.chat_transfer_choose_models_body)) },
            leadingContent = { Icon(Icons.Filled.Translate, contentDescription = null) },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            modifier = Modifier.toggleable(value = false, role = Role.Button, onValueChange = { onModels() }).testTag("direct_send_models"),
        )
    }
}

/**
 * Which installed translation models to send (ADR 2026-11.ejn8): one row per direction with its size, all of them at
 * once, and the total. Send stays off until one is ticked.
 */
@Composable
internal fun TranslationModelsBody(
    models: List<ModelChoice>,
    onSend: (pins: List<Long>) -> Unit,
    onCancel: () -> Unit,
    initiallyChecked: Set<Long> = emptySet(),
) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val checked = rememberSaveable(models) { mutableStateOf(initiallyChecked) }
    val picked = models.filter { it.pin in checked.value }
    Column(
        // Scrollable for the consent sheet's reason: a long list at a large font scale must still reach Send.
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
    ) {
        Text(
            stringResource(R.string.translate_models_send_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        Text(
            stringResource(R.string.translate_models_send_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 8.dp),
        )
        val all = picked.size == models.size
        ListItem(
            headlineContent = { Text(stringResource(R.string.translate_models_select_all)) },
            leadingContent = { Checkbox(checked = all, onCheckedChange = null) },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            modifier =
                Modifier
                    .toggleable(value = all, role = Role.Checkbox, onValueChange = { on ->
                        checked.value = if (on) models.map { it.pin }.toSet() else emptySet()
                    })
                    .testTag("models_select_all"),
        )
        models.forEach { model ->
            val on = model.pin in checked.value
            ListItem(
                headlineContent = {
                    Text(
                        stringResource(
                            R.string.translate_models_direction,
                            LanguageNames.title(model.from, locale),
                            LanguageNames.title(model.to, locale),
                        ),
                    )
                },
                supportingContent = { Text(Formatter.formatShortFileSize(context, model.bytes)) },
                leadingContent = { Checkbox(checked = on, onCheckedChange = null) },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                modifier =
                    Modifier
                        .toggleable(value = on, role = Role.Checkbox, onValueChange = { now ->
                            checked.value = if (now) checked.value + model.pin else checked.value - model.pin
                        })
                        .testTag("models_row_${model.from}_${model.to}"),
            )
        }
        if (picked.isNotEmpty()) {
            Text(
                pluralStringResource(
                    R.plurals.translate_models_total,
                    picked.size,
                    picked.size,
                    Formatter.formatShortFileSize(context, picked.sumOf { it.bytes }),
                ),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
            Spacer(Modifier.width(8.dp))
            Button(
                // In the list's order, so the bundle is laid out the same way every time.
                onClick = { onSend(picked.map { it.pin }) },
                enabled = picked.isNotEmpty(),
                modifier = Modifier.testTag("models_send"),
            ) { Text(stringResource(R.string.action_send)) }
        }
    }
}

private val PREVIEW_MODELS =
    listOf(
        ModelChoice(pin = 0x3b1c3995L, from = TranslateLang("en"), to = TranslateLang("es"), bytes = 37_178_000L),
        ModelChoice(pin = 0x4aed7734L, from = TranslateLang("es"), to = TranslateLang("en"), bytes = 37_542_000L),
        ModelChoice(pin = 0xed8bb4a8L, from = TranslateLang("en"), to = TranslateLang("uk"), bytes = 36_100_000L),
    )

@Preview(showBackground = true)
@Composable
fun DirectSendChooserPreview() {
    // In the sheet's own colour, as the ModalBottomSheet paints it.
    KnitTheme { Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) { DirectSendChooserBody(onFile = {}, onModels = {}) } }
}

@Preview(showBackground = true)
@Composable
fun TranslationModelsPreview() {
    KnitTheme {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            TranslationModelsBody(
                PREVIEW_MODELS,
                onSend = {},
                onCancel = {},
                initiallyChecked = PREVIEW_MODELS.take(2).map { it.pin }.toSet(),
            )
        }
    }
}
