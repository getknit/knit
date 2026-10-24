// The file's single top-level class (SettingsFormState, the content composable's state holder) rides
// along with the screen composable that is the file's real subject.
@file:Suppress("MatchingDeclarationName")

package app.getknit.knit.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhonelinkErase
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.getknit.knit.BuildConfig
import app.getknit.knit.R
import app.getknit.knit.data.settings.BluetoothDownloadLimit
import app.getknit.knit.di.CALL_SETTINGS_WIRED
import app.getknit.knit.mesh.lora.BoardBattery
import app.getknit.knit.mesh.lora.LoraPlane
import app.getknit.knit.mesh.power.LOW_POWER_OFFERED
import app.getknit.knit.mesh.power.LowPowerReason
import app.getknit.knit.ui.BackgroundBattery
import app.getknit.knit.ui.DeviceSupervision
import app.getknit.knit.ui.UnusedAppPause
import app.getknit.knit.ui.backgroundBattery
import app.getknit.knit.ui.camera.openAppSettings
import app.getknit.knit.ui.components.AdaptiveRow
import app.getknit.knit.ui.components.Avatar
import app.getknit.knit.ui.components.BarTitle
import app.getknit.knit.ui.components.ChoiceButtons
import app.getknit.knit.ui.deviceSupervision
import app.getknit.knit.ui.openUnusedAppPauseSettings
import app.getknit.knit.ui.preview.KnitPreview
import app.getknit.knit.ui.rememberOnResume
import app.getknit.knit.ui.rememberWifiAwareSupported
import app.getknit.knit.ui.requestIgnoreBatteryOptimizations
import app.getknit.knit.ui.signout.SignOut
import app.getknit.knit.ui.signout.SignOutDialog
import app.getknit.knit.ui.theme.DYNAMIC_COLOR_SUPPORTED
import app.getknit.knit.ui.theme.THEME_MODE_SUPPORTED
import app.getknit.knit.ui.theme.ThemeMode
import app.getknit.knit.ui.unusedAppPause
import org.koin.androidx.compose.koinViewModel

/** UI-local projection of [SettingsViewModel]'s per-setting flows for the stateless content. */
internal data class SettingsFormState(
    val header: ProfileHeader = ProfileHeader(),
    val contentFilteringEnabled: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.System,
    val bluetoothDownloadLimit: BluetoothDownloadLimit = BluetoothDownloadLimit.DEFAULT,
    val linkPreviewsEnabled: Boolean = false,
    /** The Calls row's subtitle (docs/VOICE_CALLS.md §3.14), shown only where calls are wired. */
    val calls: CallsSummary = CallsSummary(),
    val dynamicColor: Boolean = false,
    val relay: RelaySummary = RelaySummary(),
    val lora: LoraSummary = LoraSummary(),
    /** The "also active on another phone" row (ADR 2026-09.ypcc), shown while the chat list's banner is. */
    val cloneVisible: Boolean = false,
    /** Low-power mode's switch and what holds it on (ADR 2026-10.d44b); its row shows only while it is offered. */
    val lowPower: LowPowerSummary = LowPowerSummary(),
    /** Settings › Wi-Fi Aware (GitHub #44): on by default. */
    val wifiAwareOn: Boolean = true,
)

/**
 * App settings. Everything here persists the moment it is touched — there is no Save button, because the
 * one thing that batches its writes is the profile editor behind the header row at the top.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenProfile: () -> Unit = {},
    onOpenRelays: () -> Unit = {},
    onOpenLora: () -> Unit = {},
    onOpenCalls: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onOpenLicenses: () -> Unit = {},
    onOpenBackup: () -> Unit = {},
    onOpenBlockedUsers: () -> Unit = {},
    onOpenWhatsNew: () -> Unit = {},
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val header by viewModel.header.collectAsStateWithLifecycle()
    val contentFilteringEnabled by viewModel.contentFilteringEnabled.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val bluetoothDownloadLimit by viewModel.bluetoothDownloadLimit.collectAsStateWithLifecycle()
    val linkPreviewsEnabled by viewModel.linkPreviewsEnabled.collectAsStateWithLifecycle()
    val calls by viewModel.callsSummary.collectAsStateWithLifecycle()
    val dynamicColor by viewModel.dynamicColor.collectAsStateWithLifecycle()
    val relay by viewModel.relaySummary.collectAsStateWithLifecycle()
    val lora by viewModel.loraSummary.collectAsStateWithLifecycle()
    val cloneVisible by viewModel.cloneVisible.collectAsStateWithLifecycle()
    val lowPower by viewModel.lowPower.collectAsStateWithLifecycle()
    val wifiAwareOn by viewModel.wifiAwareOn.collectAsStateWithLifecycle()
    // The clone row's "Sign out here": one confirmation, then the wipe (ui/signout/SignOut).
    var showSignOut by remember { mutableStateOf(false) }

    val context = LocalContext.current
    if (showSignOut) {
        SignOutDialog(
            onConfirm = {
                showSignOut = false
                SignOut.here(context)
            },
            onDismiss = { showSignOut = false },
        )
    }
    SettingsScreenContent(
        form =
            SettingsFormState(
                header = header,
                contentFilteringEnabled = contentFilteringEnabled,
                themeMode = themeMode,
                bluetoothDownloadLimit = bluetoothDownloadLimit,
                linkPreviewsEnabled = linkPreviewsEnabled,
                calls = calls,
                dynamicColor = dynamicColor,
                relay = relay,
                lora = lora,
                cloneVisible = cloneVisible,
                lowPower = lowPower,
                wifiAwareOn = wifiAwareOn,
            ),
        battery = rememberOnResume { backgroundBattery(context) },
        unusedPause = rememberOnResume { unusedAppPause(context) },
        supervision = rememberOnResume { deviceSupervision(context) },
        onBack = onBack,
        onOpenProfile = onOpenProfile,
        onToggleContentFiltering = viewModel::setContentFilteringEnabled,
        onSelectThemeMode = viewModel::setThemeMode,
        onSelectBluetoothDownloadLimit = viewModel::setBluetoothDownloadLimit,
        onToggleLinkPreviews = viewModel::setLinkPreviewsEnabled,
        onToggleDynamicColor = viewModel::setDynamicColor,
        onToggleLowPower = viewModel::setLowPowerMode,
        onToggleWifiAware = viewModel::setWifiAwareOn,
        onOpenRelays = onOpenRelays,
        onOpenLora = onOpenLora,
        onOpenCalls = onOpenCalls,
        onOpenAbout = onOpenAbout,
        onOpenLicenses = onOpenLicenses,
        onOpenBackup = onOpenBackup,
        onOpenBlockedUsers = onOpenBlockedUsers,
        onOpenWhatsNew = onOpenWhatsNew,
        onSignOut = { showSignOut = true },
        onAllowBattery = { requestIgnoreBatteryOptimizations(context) },
        onOpenBatterySettings = { openAppSettings(context) },
        onOpenUnusedPauseSettings = { openUnusedAppPauseSettings(context) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreenContent(
    form: SettingsFormState,
    battery: BackgroundBattery,
    onBack: () -> Unit,
    onOpenProfile: () -> Unit = {},
    onToggleContentFiltering: (Boolean) -> Unit,
    onSelectThemeMode: (ThemeMode) -> Unit = {},
    onSelectBluetoothDownloadLimit: (BluetoothDownloadLimit) -> Unit = {},
    onToggleLinkPreviews: (Boolean) -> Unit = {},
    onToggleDynamicColor: (Boolean) -> Unit = {},
    onToggleLowPower: (Boolean) -> Unit = {},
    onToggleWifiAware: (Boolean) -> Unit = {},
    onOpenRelays: () -> Unit,
    onOpenLora: () -> Unit = {},
    onOpenCalls: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onOpenLicenses: () -> Unit = {},
    onOpenBackup: () -> Unit = {},
    onOpenBlockedUsers: () -> Unit = {},
    onOpenWhatsNew: () -> Unit = {},
    // The clone row's action — opens the confirmation in the stateful wrapper, never the wipe itself.
    onSignOut: () -> Unit = {},
    // Whether the Internet-relay plane is introduced at all in this build. A parameter rather than a
    // bare BuildConfig read so the hidden case is previewable and testable; see app/build.gradle.kts.
    showInternetRelays: Boolean = BuildConfig.INTERNET_PLANE,
    // Same, for the LoRa plane.
    showLoraRadio: Boolean = BuildConfig.LORA_PLANE,
    // Same, for calls (experimental): only a build that wires live calls or speech (`CALL_SETTINGS_WIRED`) has a Calls
    // screen.
    showCalls: Boolean = CALL_SETTINGS_WIRED,
    // Whether the platform can do wallpaper colours at all (API 31+). A parameter for the same reason as
    // the two above: the hidden case stays previewable and testable rather than depending on the device.
    showDynamicColor: Boolean = DYNAMIC_COLOR_SUPPORTED,
    // Same, for the platform's per-app night mode — a different fact that happens to carry the same
    // API level; see ui/theme/ThemeMode.kt.
    showThemeMode: Boolean = THEME_MODE_SUPPORTED,
    // Whether this phone has Wi-Fi Aware at all, so the large-download row never promises a plane it lacks, and the
    // Wi-Fi Aware switch shows only where there is a plane to switch.
    wifiAware: Boolean = rememberWifiAwareSupported(),
    // Whether low-power mode is offered (`LOW_POWER_OFFERED`, false since GitHub #44): its row stays in the code.
    showLowPower: Boolean = LOW_POWER_OFFERED,
    onAllowBattery: () -> Unit,
    onOpenBatterySettings: () -> Unit = {},
    // Who else holds this phone's switches (a Family Link parent, an administrator). Read in the stateful
    // wrapper like `battery`, so the preview and the content test can show either phone.
    supervision: DeviceSupervision = DeviceSupervision.None,
    // Android's "Pause app activity if unused" switch, read the same way; null where the platform has none.
    unusedPause: UnusedAppPause? = null,
    onOpenUnusedPauseSettings: () -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }
    Scaffold(
        modifier = Modifier.testTag("screen_settings"),
        topBar = {
            TopAppBar(
                title = { BarTitle(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                // Blocked users, What's new, About and the licenses list live behind the overflow rather than as rows:
                // none is a setting, and the column below is for things that change how Knit behaves.
                actions = {
                    Box {
                        IconButton(
                            onClick = { menuOpen = true },
                            modifier = Modifier.size(48.dp).testTag("settings_menu"),
                        ) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.chat_more_options))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.blocked_users_title)) },
                                leadingIcon = { Icon(Icons.Filled.Block, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onOpenBlockedUsers()
                                },
                                modifier = Modifier.testTag("settings_menu_blocked"),
                            )
                            // Where the announcements stay once the chat list's card has gone.
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.settings_menu_whats_new)) },
                                leadingIcon = { Icon(Icons.Outlined.NewReleases, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onOpenWhatsNew()
                                },
                                modifier = Modifier.testTag("settings_menu_whats_new"),
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.settings_menu_about)) },
                                leadingIcon = { Icon(Icons.Outlined.Info, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onOpenAbout()
                                },
                                modifier = Modifier.testTag("settings_menu_about"),
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.settings_menu_licenses)) },
                                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Article, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onOpenLicenses()
                                },
                                modifier = Modifier.testTag("settings_menu_licenses"),
                            )
                        }
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
            ProfileHeaderRow(header = form.header, onClick = onOpenProfile)

            // First, above every switch: an identity on two phones is the most consequential thing this
            // screen can say, and the row goes with the chat list's banner.
            if (form.cloneVisible) {
                CloneNoticeRow(onClick = onSignOut)
            }

            ToggleRow(
                title = stringResource(R.string.settings_content_filtering_title),
                subtitle = stringResource(R.string.settings_content_filtering_subtitle),
                enabled = form.contentFilteringEnabled,
                onToggle = onToggleContentFiltering,
                modifier = Modifier.testTag("settings_content_filtering"),
            )

            // How a large attachment only Bluetooth could bring arrives (ADR 2026-10.js2p). Beside content filtering:
            // both decide what reaches this phone from others.
            ChoiceRow(
                title = stringResource(R.string.settings_bt_download_title),
                subtitle =
                    stringResource(
                        if (wifiAware) R.string.settings_bt_download_subtitle else R.string.settings_bt_download_subtitle_bluetooth_only,
                    ),
                choices = BT_DOWNLOAD_LIMITS,
                selected = form.bluetoothDownloadLimit,
                onSelect = onSelectBluetoothDownloadLimit,
                modifier = Modifier.testTag("settings_bt_download"),
            )

            // Both appearance controls are hidden rather than disabled below API 31, matching the two plane
            // rows: a control that can never move needs a reason next to it, and there is nowhere here to
            // put one. They sit together because they answer the same question about how the app looks.
            if (showThemeMode) {
                ThemeModeRow(
                    selected = form.themeMode,
                    onSelect = onSelectThemeMode,
                    modifier = Modifier.testTag("settings_theme_mode"),
                )
            }

            if (showDynamicColor) {
                ToggleRow(
                    title = stringResource(R.string.settings_dynamic_color_title),
                    subtitle = stringResource(R.string.settings_dynamic_color_subtitle),
                    enabled = form.dynamicColor,
                    onToggle = onToggleDynamicColor,
                    modifier = Modifier.testTag("settings_dynamic_color"),
                )
            }

            // Link previews are the one other thing that uses the Internet, so they ship under the same build
            // switch as the relay plane; the subtitle carries the disclosure that would otherwise need a sheet.
            if (showInternetRelays) {
                ToggleRow(
                    title = stringResource(R.string.settings_link_previews_title),
                    subtitle = stringResource(R.string.settings_link_previews_subtitle),
                    enabled = form.linkPreviewsEnabled,
                    onToggle = onToggleLinkPreviews,
                    modifier = Modifier.testTag("settings_link_previews"),
                )
            }

            // Not under the Internet plane's flag: the person's own TURN server relays a call without any relay.
            if (showCalls) CallsRow(summary = form.calls, onClick = onOpenCalls)

            if (showInternetRelays) InternetRelayRow(summary = form.relay, onClick = onOpenRelays)

            if (showLoraRadio) LoraRadioRow(summary = form.lora, onClick = onOpenLora)

            NavigatingRow(
                title = stringResource(R.string.settings_backup_title),
                subtitle = stringResource(R.string.settings_backup_subtitle),
                onClick = onOpenBackup,
                modifier = Modifier.testTag("settings_backup"),
            )

            // The Wi-Fi Aware switch sits with the battery rows, where low-power mode was: what Knit asks of this phone's
            // radios, above what Android lets it do. The subtitle states the trade (GitHub #44).
            if (wifiAware) {
                ToggleRow(
                    title = stringResource(R.string.settings_wifi_aware_title),
                    subtitle =
                        stringResource(
                            if (form.wifiAwareOn) R.string.settings_wifi_aware_subtitle_on else R.string.settings_wifi_aware_subtitle_off,
                        ),
                    enabled = form.wifiAwareOn,
                    onToggle = onToggleWifiAware,
                    modifier = Modifier.testTag("settings_wifi_aware"),
                )
            }

            if (showLowPower) {
                ToggleRow(
                    title = stringResource(R.string.settings_low_power_title),
                    subtitle = lowPowerSubtitle(form.lowPower),
                    enabled = form.lowPower.switchOn,
                    onToggle = onToggleLowPower,
                    modifier = Modifier.testTag("settings_low_power"),
                )
            }

            BatteryOptimizationRow(battery = battery, onAllow = onAllowBattery, onOpenSettings = onOpenBatterySettings)
            UnusedAppPauseRow(unusedPause = unusedPause, onOpenSettings = onOpenUnusedPauseSettings)
            SupervisionRow(supervision = supervision)
        }
    }
}

/**
 * You, at the top of Settings: photo, name, alias, and a chevron into the profile editor. One tap target
 * carrying its own name, so a screen reader announces the person rather than an unlabelled row of parts.
 */
@Composable
private fun ProfileHeaderRow(
    header: ProfileHeader,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick, role = Role.Button)
                .testTag("settings_profile_row"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(
            avatarHash = header.avatarHash,
            name = header.name,
            nodeId = header.nodeId,
            size = 56.dp,
            textStyle = MaterialTheme.typography.titleLarge,
            // Decorative: the row carries the accessible name, and tapping it does the same thing.
            contentDescription = null,
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = header.name,
                style = MaterialTheme.typography.titleMedium,
            )
            header.alias?.let { alias ->
                Text(
                    text = stringResource(R.string.profile_alias, alias),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The light/dark choice: the theme's [ChoiceRow]. */
@Composable
private fun ThemeModeRow(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier,
) = ChoiceRow(
    title = stringResource(R.string.settings_theme_title),
    subtitle = stringResource(R.string.settings_theme_subtitle),
    choices = THEME_MODES,
    selected = selected,
    onSelect = onSelect,
    modifier = modifier,
)

/**
 * A title, a line of explanation, and one control carrying every answer at once — the theme and the
 * large-download limit share it.
 *
 * Every answer in view rather than the dialog the neighbouring `NavigatingRow`s would suggest, because picking a
 * theme applies a configuration change and recreates the Activity — a dialog would be torn down as you chose
 * from it. [ChoiceButtons] draws them as segmented buttons, or as a list of radio buttons when a label is too long
 * for its segment.
 */
@Composable
private fun <T> ChoiceRow(
    title: String,
    subtitle: String,
    choices: List<Pair<T, Int>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        ChoiceButtons(
            choices = choices.map { (choice, label) -> choice to stringResource(label) },
            selected = selected,
            onSelect = onSelect,
        )
    }
}

/** The three limits in the order they read, each with the string that names it. */
private val BT_DOWNLOAD_LIMITS =
    listOf(
        BluetoothDownloadLimit.NEVER to R.string.settings_bt_download_never,
        BluetoothDownloadLimit.MB_1 to R.string.settings_bt_download_1mb,
        BluetoothDownloadLimit.MB_5 to R.string.settings_bt_download_5mb,
    )

/** The three choices in the order they read, each with the string that names it. */
private val THEME_MODES =
    listOf(
        ThemeMode.System to R.string.settings_theme_system,
        ThemeMode.Light to R.string.settings_theme_light,
        ThemeMode.Dark to R.string.settings_theme_dark,
    )

/** A titled switch row — every switch on this screen shares it. */
@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                // One toggle target: the row owns the switch so a screen reader announces the title +
                // subtitle as the label with an on/off state, instead of an unlabelled switch node.
                .toggleable(value = enabled, onValueChange = onToggle, role = Role.Switch)
                .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        // null handler: the row's toggleable owns the interaction (avoids a duplicate focus stop).
        Switch(checked = enabled, onCheckedChange = null)
    }
}

/**
 * Entry point to the Internet (spool) plane's own screen, with its current state as the subtitle.
 *
 * A navigating row rather than the switch it used to be: the switch alone was `BuildConfig.DEBUG`-gated,
 * because the app seeds a default relay and a release user who could enable the plane but not edit the
 * list would be stuck with an endpoint they could not remove. The editor lives behind this row, which is
 * what makes the control shippable.
 */
@Composable
private fun InternetRelayRow(
    summary: RelaySummary,
    onClick: () -> Unit,
) {
    // "None added" and "none in use" both mean nothing crosses the plane, but they ask different things
    // of the user — add a relay, or switch one back on — so the empty list keeps its own line.
    val subtitle =
        when {
            !summary.enabled -> stringResource(R.string.relays_summary_off)
            summary.configured == 0 -> stringResource(R.string.relays_summary_none)
            summary.active == 0 -> stringResource(R.string.relays_summary_none_active)
            else -> stringResource(R.string.relays_summary_on, summary.connected, summary.active)
        }
    NavigatingRow(
        title = stringResource(R.string.relays_title),
        subtitle = subtitle,
        onClick = onClick,
        modifier = Modifier.testTag("settings_relays"),
    )
}

/** Entry point to the Calls screen (docs/VOICE_CALLS.md §3.14): whose servers a call uses, and relay-all. */
@Composable
private fun CallsRow(
    summary: CallsSummary,
    onClick: () -> Unit,
) {
    val servers =
        stringResource(
            when {
                summary.ownStun && summary.ownTurn -> R.string.settings_calls_summary_own_both
                summary.ownTurn -> R.string.settings_calls_summary_own_turn
                summary.ownStun -> R.string.settings_calls_summary_own_stun
                else -> R.string.settings_calls_summary_relays
            },
        )
    NavigatingRow(
        title = stringResource(R.string.settings_calls_title),
        subtitle =
            when {
                summary.refusesAll -> stringResource(R.string.settings_calls_summary_none)

                summary.refusesLive -> stringResource(R.string.settings_calls_summary_no_live)

                summary.refusesWalkie -> stringResource(R.string.settings_calls_summary_no_walkie)

                // A build with mesh calls alone uses no servers to name.
                !summary.live -> stringResource(R.string.settings_calls_summary_walkie)

                summary.relayAll -> stringResource(R.string.settings_calls_summary_relayed, servers)

                else -> servers
            },
        onClick = onClick,
        modifier = Modifier.testTag("settings_calls"),
    )
}

/** Entry point to the LoRa radio screen, with its current state as the subtitle. */
@Composable
private fun LoraRadioRow(
    summary: LoraSummary,
    onClick: () -> Unit,
) {
    val subtitle =
        when {
            !summary.enabled -> {
                stringResource(R.string.lora_summary_off)
            }

            summary.plane == LoraPlane.Live && summary.boardName != null -> {
                loraConnectedSubtitle(summary.boardName, summary.battery)
            }

            summary.boardName != null -> {
                stringResource(R.string.lora_summary_disconnected, summary.boardName)
            }

            else -> {
                stringResource(R.string.lora_summary_connecting)
            }
        }
    NavigatingRow(
        title = stringResource(R.string.lora_title),
        subtitle = subtitle,
        onClick = onClick,
        modifier = Modifier.testTag("settings_lora"),
    )
}

/**
 * The "also active on another phone" row (ADR 2026-09.ypcc): the screen's one danger row, in the chat-list
 * banner's colours, whose tap is "Sign out here". Not a [NavigatingRow] — it leads to a confirmation, not a screen.
 */
@Composable
private fun CloneNoticeRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        tonalElevation = 3.dp,
        modifier = modifier.fillMaxWidth().testTag("settings_clone_row"),
    ) {
        // The action's label moves under the message, at the end, when a longer language would squeeze the message.
        AdaptiveRow(
            modifier =
                Modifier
                    .clickable(onClick = onClick, role = Role.Button)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            stackedAtEnd = true,
            trailing = {
                Text(
                    text = stringResource(R.string.chatlist_clone_banner_action),
                    style = MaterialTheme.typography.labelLarge,
                )
            },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.PhonelinkErase, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = stringResource(R.string.settings_clone_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.settings_clone_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

/** A titled row that hands off to another screen: title, live subtitle, chevron. One tap target. */
@Composable
private fun NavigatingRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClick = onClick, role = Role.Button)
                .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * What low-power mode is doing now (ADR 2026-10.d44b). The switch shows the user's own choice, so the subtitle says
 * when the posture differs from it: Battery Saver holding it on with the switch off, or the charger holding it off
 * with the switch on.
 */
@Composable
private fun lowPowerSubtitle(summary: LowPowerSummary): String =
    when {
        summary.switchOn && summary.reason == null -> {
            stringResource(R.string.settings_low_power_subtitle_charging)
        }

        !summary.switchOn && summary.reason == LowPowerReason.BATTERY_SAVER -> {
            stringResource(R.string.settings_low_power_subtitle_saver)
        }

        else -> {
            stringResource(R.string.settings_low_power_subtitle)
        }
    }

/** "On · <board> · connected", with the board's battery appended once it has reported one. */
@Composable
private fun loraConnectedSubtitle(
    boardName: String,
    battery: BoardBattery?,
): String =
    when {
        battery == null -> stringResource(R.string.lora_summary_connected, boardName)
        battery.percent != null -> stringResource(R.string.lora_summary_connected_battery, boardName, battery.percent)
        else -> stringResource(R.string.lora_summary_connected_powered, boardName)
    }

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
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("settings_battery"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text =
                stringResource(
                    when (battery) {
                        BackgroundBattery.Unrestricted -> R.string.battery_allowed
                        BackgroundBattery.Optimized -> R.string.battery_optimized
                        BackgroundBattery.Restricted -> R.string.battery_restricted
                    },
                ),
            style = MaterialTheme.typography.bodySmall,
            color =
                if (battery == BackgroundBattery.Restricted) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            textAlign = TextAlign.Center,
        )
        when (battery) {
            BackgroundBattery.Unrestricted -> {
                // Nothing to press: the quiet state.
            }

            BackgroundBattery.Optimized -> {
                TextButton(onClick = onAllow, modifier = Modifier.testTag("settings_battery_allow")) {
                    Text(stringResource(R.string.battery_allow_button))
                }
            }

            BackgroundBattery.Restricted -> {
                TextButton(onClick = onOpenSettings, modifier = Modifier.testTag("settings_battery_settings")) {
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
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("settings_unused"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text =
                stringResource(
                    when (unusedPause) {
                        UnusedAppPause.Off -> R.string.unused_pause_off
                        UnusedAppPause.On -> R.string.unused_pause_on
                    },
                ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (unusedPause == UnusedAppPause.On) {
            TextButton(onClick = onOpenSettings, modifier = Modifier.testTag("settings_unused_settings")) {
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
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("settings_supervised"),
    )
}

@Preview(showBackground = true)
@Composable
fun ToggleRowPreview() =
    KnitPreview {
        Column {
            ToggleRow(
                title = stringResource(R.string.settings_content_filtering_title),
                subtitle = stringResource(R.string.settings_content_filtering_subtitle),
                enabled = true,
                onToggle = {},
            )
            ToggleRow(
                title = stringResource(R.string.settings_dynamic_color_title),
                subtitle = stringResource(R.string.settings_dynamic_color_subtitle),
                enabled = false,
                onToggle = {},
            )
        }
    }

@Preview(showBackground = true)
@Composable
fun ThemeModeRowPreview() =
    KnitPreview {
        Column {
            ThemeModeRow(selected = ThemeMode.System, onSelect = {})
            ThemeModeRow(selected = ThemeMode.Dark, onSelect = {})
        }
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

@Preview(showBackground = true)
@Composable
fun SettingsScreenPreview() =
    KnitPreview {
        SettingsScreenContent(
            form =
                SettingsFormState(
                    header =
                        ProfileHeader(
                            name = "Ada Lovelace",
                            alias = "GentlyRustlingRabbit",
                        ),
                    contentFilteringEnabled = true,
                    relay = RelaySummary(enabled = true, configured = 2, active = 2, connected = 1),
                    lora = LoraSummary(enabled = true, boardName = "Meshtastic_1a2b", plane = LoraPlane.Live),
                ),
            battery = BackgroundBattery.Optimized,
            onBack = {},
            onToggleContentFiltering = {},
            onOpenRelays = {},
            onAllowBattery = {},
            // The screen a shipped build shows: it has no calls (`CALL_SETTINGS_WIRED` off), and so no Calls row.
            showCalls = false,
        )
    }

// The identity was seen on another phone (ADR 2026-09.ypcc): the sign-out row leads the column.
@Preview(showBackground = true)
@Composable
fun SettingsScreenClonePreview() =
    KnitPreview {
        SettingsScreenContent(
            form = SettingsFormState(header = ProfileHeader(name = "Ada Lovelace", alias = "GentlyRustlingRabbit"), cloneVisible = true),
            battery = BackgroundBattery.Unrestricted,
            onBack = {},
            onToggleContentFiltering = {},
            onOpenRelays = {},
            onAllowBattery = {},
            showCalls = false,
        )
    }

// A fresh install: no name yet, so the header falls back to the generated alias — and drops the alias line
// under it, which would otherwise print the same word twice. Both planes are off.
@Preview(showBackground = true)
@Composable
fun SettingsScreenNewUserPreview() =
    KnitPreview {
        SettingsScreenContent(
            form = SettingsFormState(header = ProfileHeader(name = "GentlyRustlingRabbit")),
            battery = BackgroundBattery.Unrestricted,
            onBack = {},
            onToggleContentFiltering = {},
            onOpenRelays = {},
            onAllowBattery = {},
            showCalls = false,
        )
    }
