package app.getknit.knit.ui.preset

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.getknit.knit.BuildConfig
import app.getknit.knit.R
import app.getknit.knit.data.settings.Preset
import app.getknit.knit.di.FACE_REDACTION_WIRED
import app.getknit.knit.di.STT_WIRED
import app.getknit.knit.ui.preview.KnitPreview
import app.getknit.knit.ui.relay.RelayConsentText
import app.getknit.knit.ui.theme.KnitMotion

/**
 * The offered [Preset]s as one radio group of cards, shared by onboarding's preset page and Settings › Starting point.
 * Each card says what it changes; a line for a plane this build or phone lacks is left out, so a card never promises
 * what will not happen. The selected Everyday card shows the relay disclosure in full ([RelayConsentText], the same
 * words as the relay switch's sheet and the invite's: ADR 063's one disclosure, now with a third door), because
 * picking it switches relays on; [showRelayDisclosure] false leaves it to a caller that shows it elsewhere.
 */
@Composable
fun PresetChoices(
    selected: Preset?,
    onSelect: (Preset) -> Unit,
    awareSupported: Boolean,
    modifier: Modifier = Modifier,
    internetPlane: Boolean = BuildConfig.INTERNET_PLANE,
    showRelayDisclosure: Boolean = true,
) {
    Column(
        modifier = modifier.selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Preset.offered.forEach { preset ->
            PresetCard(
                preset = preset,
                lines = presetLines(preset, awareSupported = awareSupported, internetPlane = internetPlane),
                selected = preset == selected,
                showRelayDisclosure = showRelayDisclosure && preset == selected && preset.defaults.relays && internetPlane,
                onClick = { onSelect(preset) },
            )
        }
    }
}

/** What a card lists under its summary, in order; pure so the omissions are tested. */
internal fun presetLines(
    preset: Preset,
    awareSupported: Boolean,
    internetPlane: Boolean,
): List<Int> =
    when (preset) {
        Preset.EVERYDAY -> {
            listOfNotNull(
                R.string.preset_everyday_relays.takeIf { internetPlane },
                R.string.preset_everyday_previews.takeIf { internetPlane },
                R.string.preset_everyday_downloads,
            )
        }

        Preset.PRIVATE -> {
            listOfNotNull(
                R.string.preset_private_cues,
                R.string.preset_private_translation.takeIf { BuildConfig.TRANSLATE },
                R.string.preset_private_offline.takeIf { internetPlane },
                R.string.preset_private_screenshots,
                R.string.preset_private_faces.takeIf { FACE_REDACTION_WIRED },
            )
        }

        Preset.BATTERY -> {
            listOfNotNull(
                R.string.preset_battery_aware.takeIf { awareSupported },
                R.string.preset_battery_transcripts.takeIf { STT_WIRED },
            )
        }

        Preset.STANDARD -> {
            emptyList()
        }
    }

@StringRes
internal fun Preset.titleRes(): Int =
    when (this) {
        Preset.EVERYDAY -> R.string.preset_everyday_title
        Preset.PRIVATE -> R.string.preset_private_title
        Preset.BATTERY -> R.string.preset_battery_title
        Preset.STANDARD -> R.string.preset_standard_title
    }

@StringRes
private fun Preset.summaryRes(): Int =
    when (this) {
        Preset.EVERYDAY -> R.string.preset_everyday_summary
        Preset.PRIVATE -> R.string.preset_private_summary
        Preset.BATTERY -> R.string.preset_battery_summary
        Preset.STANDARD -> R.string.preset_standard_summary
    }

@Composable
private fun PresetCard(
    preset: Preset,
    lines: List<Int>,
    selected: Boolean,
    showRelayDisclosure: Boolean,
    onClick: () -> Unit,
) {
    OutlinedCard(
        border =
            BorderStroke(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
            ),
        modifier =
            Modifier
                .fillMaxWidth()
                .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
                .testTag("preset_${preset.name.lowercase()}"),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            RadioButton(selected = selected, onClick = null)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(preset.titleRes()), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(preset.summaryRes()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                lines.forEach { line ->
                    Text(
                        text = stringResource(R.string.preset_line, stringResource(line)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AnimatedVisibility(
                    visible = showRelayDisclosure,
                    enter = KnitMotion.enterReveal(),
                    exit = KnitMotion.exitReveal(),
                ) {
                    RelayConsentText(modifier = Modifier.padding(top = 8.dp).testTag("preset_relay_disclosure"))
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun PresetChoicesPreview() =
    KnitPreview {
        PresetChoices(
            selected = Preset.EVERYDAY,
            onSelect = {},
            awareSupported = true,
            internetPlane = true,
            modifier = Modifier.padding(16.dp),
        )
    }
