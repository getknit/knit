package app.getknit.knit.ui.preset

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.getknit.knit.BuildConfig
import app.getknit.knit.R
import app.getknit.knit.data.settings.Preset
import app.getknit.knit.data.settings.PresetChange
import app.getknit.knit.di.STT_WIRED
import app.getknit.knit.ui.components.BarTitle
import app.getknit.knit.ui.preview.KnitPreview
import app.getknit.knit.ui.relay.RelayConsentText
import app.getknit.knit.ui.rememberWifiAwareSupported
import org.koin.androidx.compose.koinViewModel

/** Settings › Starting point. */
@Composable
fun StartingPointScreen(
    onBack: () -> Unit,
    viewModel: StartingPointViewModel = koinViewModel(),
) {
    val current by viewModel.current.collectAsStateWithLifecycle()
    val spoolConsented by viewModel.spoolConsented.collectAsStateWithLifecycle()
    val asking by viewModel.asking.collectAsStateWithLifecycle()
    StartingPointScreenContent(
        current = current,
        asking = asking,
        spoolConsented = spoolConsented,
        awareSupported = rememberWifiAwareSupported(),
        onBack = onBack,
        onAsk = viewModel::ask,
        onDismiss = viewModel::dismiss,
        onConfirm = viewModel::confirm,
    )
}

/**
 * The offered presets with the current one selected ([Preset.STANDARD], an install from before presets, selects none).
 * Tapping another opens a confirmation rather than switching at once, because a switch can move several settings. The
 * dialog lists what each button moves from the settings as they read now ([SwitchPreview]), what shares more first, so
 * a switch never turns read receipts or relays on, or the screenshot block off, without saying so. A switch that turns
 * relays on shows their disclosure until it was accepted once. "Switch" forgets the person's own values for what the
 * preset covers; where they have some, "Keep my changes" moves only the defaults beneath them and leaves relays.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StartingPointScreenContent(
    current: StartingPoint,
    asking: SwitchPreview?,
    spoolConsented: Boolean,
    awareSupported: Boolean,
    onBack: () -> Unit,
    onAsk: (Preset) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (resetOverrides: Boolean) -> Unit,
    internetPlane: Boolean = BuildConfig.INTERNET_PLANE,
    sttWired: Boolean = STT_WIRED,
) {
    asking?.let { asked ->
        // A plane this phone or build lacks is left out, as the cards leave it out: its switch still moves, unseen.
        val shown = { change: PresetChange -> shownOn(change.switch, awareSupported = awareSupported, sttWired = sttWired) }
        val preview = asked.copy(reset = asked.reset.filter(shown), keep = asked.keep?.filter(shown))
        SwitchDialog(
            preview = preview,
            showRelayDisclosure = !spoolConsented && preview.reset.any { it.switch == PresetChange.Switch.RELAYS && it.on },
            onConfirm = onConfirm,
            onDismiss = onDismiss,
        )
    }
    Scaffold(
        modifier = Modifier.testTag("screen_starting_point"),
        topBar = {
            TopAppBar(
                title = { BarTitle(stringResource(R.string.settings_preset_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_preset_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PresetChoices(
                selected = current.preset.takeIf { it in Preset.offered },
                onSelect = { picked -> if (picked != current.preset || current.customised) onAsk(picked) },
                awareSupported = awareSupported,
                internetPlane = internetPlane,
                // The cards here say what a preset would set; the disclosure is the dialog's, at the moment it applies.
                showRelayDisclosure = false,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SwitchDialog(
    preview: SwitchPreview,
    showRelayDisclosure: Boolean,
    onConfirm: (resetOverrides: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val name = stringResource(preview.target.titleRes())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_preset_switch_title, name)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ChangeList(
                    heading = preview.keep?.let { stringResource(R.string.settings_preset_switch) },
                    changes = preview.reset,
                    tag = "preset_changes_reset",
                )
                preview.keep?.let { keep ->
                    ChangeList(
                        heading = stringResource(R.string.settings_preset_switch_keep),
                        changes = keep,
                        tag = "preset_changes_keep",
                    )
                }
                if (showRelayDisclosure) RelayConsentText()
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(true) }, modifier = Modifier.testTag("preset_switch_reset")) {
                Text(stringResource(R.string.settings_preset_switch))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                if (preview.keep != null) {
                    TextButton(onClick = { onConfirm(false) }, modifier = Modifier.testTag("preset_switch_keep")) {
                        Text(stringResource(R.string.settings_preset_switch_keep))
                    }
                }
            }
        },
        modifier = Modifier.testTag("preset_switch_dialog"),
    )
}

/** One button's changes, a line each ("Read receipts: on"), under the button's name when there are two to tell apart. */
@Composable
private fun ChangeList(
    heading: String?,
    changes: List<PresetChange>,
    tag: String,
) {
    Column(modifier = Modifier.testTag(tag), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        heading?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
        if (changes.isEmpty()) {
            Text(stringResource(R.string.settings_preset_no_change))
        }
        changes.forEach { change ->
            val title = stringResource(change.switch.titleRes())
            Text(
                stringResource(
                    R.string.preset_line,
                    stringResource(if (change.on) R.string.preset_change_on else R.string.preset_change_off, title),
                ),
            )
        }
    }
}

/** Whether a change to [switch] means anything on this phone and build; pure so the omissions are tested. */
internal fun shownOn(
    switch: PresetChange.Switch,
    awareSupported: Boolean,
    sttWired: Boolean,
): Boolean =
    when (switch) {
        PresetChange.Switch.WIFI_AWARE -> awareSupported
        PresetChange.Switch.TRANSCRIPTS -> sttWired
        else -> true
    }

@StringRes
private fun PresetChange.Switch.titleRes(): Int =
    when (this) {
        PresetChange.Switch.RELAYS -> R.string.relays_title
        PresetChange.Switch.LINK_PREVIEWS -> R.string.settings_link_previews_title
        PresetChange.Switch.READ_RECEIPTS -> R.string.settings_read_receipts_title
        PresetChange.Switch.TYPING_INDICATORS -> R.string.settings_typing_indicators_title
        PresetChange.Switch.TRANSLATION -> R.string.settings_translation_title
        PresetChange.Switch.SECURE_SCREENS -> R.string.settings_secure_screens_title
        PresetChange.Switch.COVER_FACES -> R.string.settings_cover_faces_title
        PresetChange.Switch.WIFI_AWARE -> R.string.settings_wifi_aware_title
        PresetChange.Switch.TRANSCRIPTS -> R.string.call_settings_transcripts_title
    }

@Preview(showBackground = true)
@Composable
fun StartingPointScreenPreview() =
    KnitPreview {
        StartingPointScreenContent(
            current = StartingPoint(Preset.PRIVATE, customised = true),
            asking = null,
            spoolConsented = false,
            awareSupported = true,
            onBack = {},
            onAsk = {},
            onDismiss = {},
            onConfirm = {},
            internetPlane = true,
        )
    }

@Preview(showBackground = true)
@Composable
fun StartingPointSwitchDialogPreview() =
    KnitPreview {
        StartingPointScreenContent(
            current = StartingPoint(Preset.PRIVATE, customised = true),
            asking =
                SwitchPreview(
                    target = Preset.EVERYDAY,
                    reset =
                        listOf(
                            PresetChange(PresetChange.Switch.RELAYS, on = true),
                            PresetChange(PresetChange.Switch.READ_RECEIPTS, on = true),
                            PresetChange(PresetChange.Switch.SECURE_SCREENS, on = false),
                        ),
                    keep = emptyList(),
                ),
            spoolConsented = true,
            awareSupported = true,
            onBack = {},
            onAsk = {},
            onDismiss = {},
            onConfirm = {},
            internetPlane = true,
        )
    }
