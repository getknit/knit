package app.getknit.knit.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.getknit.knit.R
import app.getknit.knit.ui.BackgroundBattery
import app.getknit.knit.ui.DeviceSupervision
import app.getknit.knit.ui.UnusedAppPause
import app.getknit.knit.ui.backgroundBattery
import app.getknit.knit.ui.camera.openAppSettings
import app.getknit.knit.ui.components.BarTitle
import app.getknit.knit.ui.deviceSupervision
import app.getknit.knit.ui.openUnusedAppPauseSettings
import app.getknit.knit.ui.preview.KnitPreview
import app.getknit.knit.ui.rememberOnResume
import app.getknit.knit.ui.requestIgnoreBatteryOptimizations
import app.getknit.knit.ui.unusedAppPause

/**
 * Settings › Background running: what Android lets Knit do while it is out of sight — the battery position (ADR
 * 2026-09.gc3m), the unused-app switch (ADR 2026-09.54xg) and who else administers the phone (ADR 2026-09.a8ud). Each
 * is read again on every resume, since the page it sends you to is another app's.
 */
@Composable
fun BackgroundRunningScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    BackgroundRunningScreenContent(
        battery = rememberOnResume { backgroundBattery(context) },
        unusedPause = rememberOnResume { unusedAppPause(context) },
        supervision = rememberOnResume { deviceSupervision(context) },
        onBack = onBack,
        onAllowBattery = { requestIgnoreBatteryOptimizations(context) },
        onOpenBatterySettings = { openAppSettings(context) },
        onOpenUnusedPauseSettings = { openUnusedAppPauseSettings(context) },
    )
}

@Composable
internal fun BackgroundRunningScreenContent(
    battery: BackgroundBattery,
    onBack: () -> Unit,
    onAllowBattery: () -> Unit = {},
    onOpenBatterySettings: () -> Unit = {},
    // Who else holds this phone's switches (a Family Link parent, an administrator).
    supervision: DeviceSupervision = DeviceSupervision.None,
    // Android's "Pause app activity if unused" switch; null where the platform has none.
    unusedPause: UnusedAppPause? = null,
    onOpenUnusedPauseSettings: () -> Unit = {},
) {
    Scaffold(
        modifier = Modifier.testTag("screen_background_running"),
        topBar = {
            TopAppBar(
                title = { BarTitle(stringResource(R.string.settings_background_title)) },
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
            BatteryOptimizationRow(battery = battery, onAllow = onAllowBattery, onOpenSettings = onOpenBatterySettings)
            UnusedAppPauseRow(unusedPause = unusedPause, onOpenSettings = onOpenUnusedPauseSettings)
            SupervisionRow(supervision = supervision)
        }
    }
}

/**
 * The line Settings' Background running row carries, for the battery position alone: the one of the three facts on
 * this page that can stop the mesh. Restricted is the fault (no prompt of ours lifts it), so the row shows it in the
 * error colour; Optimized is Android's default and stays quiet.
 */
@StringRes
internal fun backgroundSummary(battery: BackgroundBattery): Int =
    when (battery) {
        BackgroundBattery.Unrestricted -> R.string.settings_background_summary_allowed
        BackgroundBattery.Optimized -> R.string.settings_background_summary_optimized
        BackgroundBattery.Restricted -> R.string.settings_background_summary_restricted
    }

/**
 * Pulls a text button back by its own start padding, so its label lines up with the status line above it rather than
 * sitting indented under it. `offset` mirrors under RTL, and the touch target keeps its full size.
 */
private fun Modifier.alignedToText(): Modifier =
    offset(x = -ButtonDefaults.TextButtonContentPadding.calculateLeftPadding(LayoutDirection.Ltr))

/**
 * The app's battery position and what it means for the mesh, with the one action that can move it:
 * the exemption prompt from Optimized, the app-info page from Restricted (no prompt of ours lifts that one —
 * ADR 2026-09.f69x is what a Restricted install used to crash on).
 */

@Composable
private fun BatteryOptimizationRow(
    battery: BackgroundBattery,
    onAllow: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("settings_battery")) {
        Text(
            text =
                stringResource(
                    when (battery) {
                        BackgroundBattery.Unrestricted -> R.string.battery_allowed
                        BackgroundBattery.Optimized -> R.string.battery_optimized
                        BackgroundBattery.Restricted -> R.string.battery_restricted
                    },
                ),
            style = MaterialTheme.typography.bodyMedium,
            color =
                if (battery == BackgroundBattery.Restricted) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
        )
        when (battery) {
            BackgroundBattery.Unrestricted -> {
                // Nothing to press: the quiet state.
            }

            BackgroundBattery.Optimized -> {
                TextButton(onClick = onAllow, modifier = Modifier.alignedToText().testTag("settings_battery_allow")) {
                    Text(stringResource(R.string.battery_allow_button))
                }
            }

            BackgroundBattery.Restricted -> {
                TextButton(onClick = onOpenSettings, modifier = Modifier.alignedToText().testTag("settings_battery_settings")) {
                    Text(stringResource(R.string.action_open_settings))
                }
            }
        }
    }
}

/**
 * Android's "Pause app activity if unused" switch and what it means for the grants, in the battery row's
 * shape: a status line, and "Open settings" while the switch is on — no prompt of ours flips it, and its
 * default is not a fault, so the line keeps the quiet colour. Absent where the platform has no switch.
 */
@Composable
private fun UnusedAppPauseRow(
    unusedPause: UnusedAppPause?,
    onOpenSettings: () -> Unit,
) {
    if (unusedPause == null) return
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("settings_unused")) {
        Text(
            text =
                stringResource(
                    when (unusedPause) {
                        UnusedAppPause.Off -> R.string.unused_pause_off
                        UnusedAppPause.On -> R.string.unused_pause_on
                    },
                ),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (unusedPause == UnusedAppPause.On) {
            TextButton(onClick = onOpenSettings, modifier = Modifier.alignedToText().testTag("settings_unused_settings")) {
                Text(stringResource(R.string.action_open_settings))
            }
        }
    }
}

/**
 * One quiet line on a phone somebody else administers — a Family Link parent, an organisation — saying so,
 * and what it means for Knit. Nothing to press: the switches it talks about live in the parent app or the
 * EMM, not in Settings. A plain consumer phone renders nothing here. ADR 2026-09.a8ud.
 */
@Composable
private fun SupervisionRow(supervision: DeviceSupervision) {
    val text =
        when (supervision) {
            DeviceSupervision.None -> return
            DeviceSupervision.FamilyLink -> R.string.settings_supervised_family_link
            DeviceSupervision.Managed -> R.string.settings_supervised_managed
        }
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("settings_supervised"),
    )
}

@Preview(showBackground = true)
@Composable
fun BatteryOptimizationRowPreview() =
    KnitPreview {
        Column {
            BatteryOptimizationRow(battery = BackgroundBattery.Unrestricted, onAllow = {}, onOpenSettings = {})
            BatteryOptimizationRow(battery = BackgroundBattery.Optimized, onAllow = {}, onOpenSettings = {})
            BatteryOptimizationRow(battery = BackgroundBattery.Restricted, onAllow = {}, onOpenSettings = {})
        }
    }

@Preview(showBackground = true)
@Composable
fun SupervisionRowPreview() =
    KnitPreview {
        Column {
            SupervisionRow(supervision = DeviceSupervision.FamilyLink)
            SupervisionRow(supervision = DeviceSupervision.Managed)
        }
    }

// The page as a phone with Android's defaults sees it: battery Optimized, the unused-app switch on.
@Preview(showBackground = true)
@Composable
fun BackgroundRunningScreenPreview() =
    KnitPreview {
        BackgroundRunningScreenContent(
            battery = BackgroundBattery.Optimized,
            unusedPause = UnusedAppPause.On,
            onBack = {},
        )
    }
