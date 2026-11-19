package app.getknit.knit.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.getknit.knit.R
import app.getknit.knit.data.settings.Preset
import app.getknit.knit.ui.preset.PresetChoices
import app.getknit.knit.ui.preview.KnitPreview

/**
 * Page 2: the starting point ([Preset]), which supplies the defaults of the switches it covers. Nothing is picked for
 * the person (Everyday would switch relays on, which is theirs to choose), so Continue waits for a pick. A phone that
 * comes back to onboarding, or was restored, opens past this page and never has a preset written over what it has.
 */
@Composable
internal fun PresetPage(
    selected: Preset?,
    onSelect: (Preset) -> Unit,
    awareSupported: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
                .testTag("onboarding_page_preset"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.onboarding_preset_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(R.string.onboarding_preset_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        PresetChoices(
            selected = selected,
            onSelect = onSelect,
            awareSupported = awareSupported,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Preview(showBackground = true)
@Composable
fun PresetPagePreview() = KnitPreview { PresetPage(selected = null, onSelect = {}, awareSupported = true) }
