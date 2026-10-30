package app.getknit.knit.ui.translate

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.getknit.knit.R
import app.getknit.knit.translate.TranslateLang
import app.getknit.knit.ui.preview.KnitPreview

/** The row, and the list it opens: Automatic first, then each language. */
@Composable
fun TranslateIntoRow(
    form: TranslateIntoForm,
    onSelect: (TranslateLang?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val automatic =
        form.automatic?.let { stringResource(R.string.settings_translate_into_auto, languageName(it)) }
            ?: stringResource(R.string.settings_translate_into_auto_none)
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { open = true }
                .padding(vertical = 8.dp),
    ) {
        Text(stringResource(R.string.settings_translate_into_title), style = MaterialTheme.typography.titleMedium)
        Text(
            form.selected?.let { languageTitle(it) } ?: automatic,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (!open) return
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(stringResource(R.string.settings_translate_into_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.settings_translate_into_body),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                IntoChoice(automatic, form.selected == null) {
                    open = false
                    onSelect(null)
                }
                form.choices.forEach { lang ->
                    IntoChoice(languageTitle(lang), form.selected == lang) {
                        open = false
                        onSelect(lang)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_cancel)) } },
        modifier = Modifier.testTag("settings_translate_into_dialog"),
    )
}

@Composable
private fun IntoChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
                .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Preview(showBackground = true)
@Composable
fun TranslateIntoRowPreview() =
    KnitPreview {
        TranslateIntoRow(
            form =
                TranslateIntoForm(
                    selected = null,
                    automatic = TranslateLang.EN,
                    choices = listOf(TranslateLang.EN, TranslateLang("es")),
                ),
            onSelect = {},
        )
    }
