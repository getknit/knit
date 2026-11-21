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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Article
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.getknit.knit.BuildConfig
import app.getknit.knit.R
import app.getknit.knit.di.CALL_SETTINGS_WIRED
import app.getknit.knit.mesh.lora.BoardBattery
import app.getknit.knit.mesh.lora.BoardKind
import app.getknit.knit.mesh.lora.LoraPlane
import app.getknit.knit.mesh.power.LOW_POWER_OFFERED
import app.getknit.knit.mesh.power.LowPowerReason
import app.getknit.knit.ui.BackgroundBattery
import app.getknit.knit.ui.backgroundBattery
import app.getknit.knit.ui.components.AdaptiveRow
import app.getknit.knit.ui.components.Avatar
import app.getknit.knit.ui.components.BarTitle
import app.getknit.knit.ui.preset.StartingPoint
import app.getknit.knit.ui.preset.titleRes
import app.getknit.knit.ui.preview.KnitPreview
import app.getknit.knit.ui.rememberOnResume
import app.getknit.knit.ui.rememberWifiAwareSupported
import app.getknit.knit.ui.signout.SignOut
import app.getknit.knit.ui.signout.SignOutDialog
import app.getknit.knit.ui.theme.DYNAMIC_COLOR_SUPPORTED
import app.getknit.knit.ui.theme.THEME_MODE_SUPPORTED
import app.getknit.knit.ui.theme.ThemeMode
import org.koin.androidx.compose.koinViewModel

/** UI-local projection of [SettingsViewModel]'s per-setting flows for the stateless content. */
internal data class SettingsFormState(
    val header: ProfileHeader = ProfileHeader(),
    val themeMode: ThemeMode = ThemeMode.System,
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
    /** Settings › Watch (ADR 2026-09.wetm's seventh amendment): off by default. */
    val watchOn: Boolean = false,
    /** Settings › Starting point: the preset the defaults come from, and whether the person changed any of them. */
    val startingPoint: StartingPoint = StartingPoint(),
    /** Settings › Block screenshots: off by default, or as the starting point has it. */
    val secureScreens: Boolean = false,
)

/**
 * App settings. Everything here persists the moment it is touched — there is no Save button, because the
 * one thing that batches its writes is the profile editor behind the header row at the top.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenProfile: () -> Unit = {},
    onOpenChats: () -> Unit = {},
    onOpenRelays: () -> Unit = {},
    onOpenLora: () -> Unit = {},
    onOpenCalls: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onOpenLicenses: () -> Unit = {},
    onOpenBackup: () -> Unit = {},
    onOpenBlockedUsers: () -> Unit = {},
    onOpenWhatsNew: () -> Unit = {},
    onOpenStartingPoint: () -> Unit = {},
    onOpenBackgroundRunning: () -> Unit = {},
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val header by viewModel.header.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val calls by viewModel.callsSummary.collectAsStateWithLifecycle()
    val dynamicColor by viewModel.dynamicColor.collectAsStateWithLifecycle()
    val relay by viewModel.relaySummary.collectAsStateWithLifecycle()
    val lora by viewModel.loraSummary.collectAsStateWithLifecycle()
    val cloneVisible by viewModel.cloneVisible.collectAsStateWithLifecycle()
    val lowPower by viewModel.lowPower.collectAsStateWithLifecycle()
    val wifiAwareOn by viewModel.wifiAwareOn.collectAsStateWithLifecycle()
    val watchOn by viewModel.watchOn.collectAsStateWithLifecycle()
    val startingPoint by viewModel.startingPoint.collectAsStateWithLifecycle()
    val secureScreens by viewModel.secureScreens.collectAsStateWithLifecycle()
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
                themeMode = themeMode,
                calls = calls,
                dynamicColor = dynamicColor,
                relay = relay,
                lora = lora,
                cloneVisible = cloneVisible,
                lowPower = lowPower,
                wifiAwareOn = wifiAwareOn,
                watchOn = watchOn,
                startingPoint = startingPoint,
                secureScreens = secureScreens,
            ),
        // Read on resume for the Background running row's line; the page behind it reads its own three facts.
        battery = rememberOnResume { backgroundBattery(context) },
        onBack = onBack,
        onOpenProfile = onOpenProfile,
        onOpenChats = onOpenChats,
        onOpenStartingPoint = onOpenStartingPoint,
        onSelectThemeMode = viewModel::setThemeMode,
        onToggleDynamicColor = viewModel::setDynamicColor,
        onToggleLowPower = viewModel::setLowPowerMode,
        onToggleWifiAware = viewModel::setWifiAwareOn,
        onToggleWatch = viewModel::setWatchOn,
        onToggleSecureScreens = viewModel::setSecureScreens,
        onOpenRelays = onOpenRelays,
        onOpenLora = onOpenLora,
        onOpenCalls = onOpenCalls,
        onOpenAbout = onOpenAbout,
        onOpenLicenses = onOpenLicenses,
        onOpenBackup = onOpenBackup,
        onOpenBlockedUsers = onOpenBlockedUsers,
        onOpenWhatsNew = onOpenWhatsNew,
        onOpenBackgroundRunning = onOpenBackgroundRunning,
        onSignOut = { showSignOut = true },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreenContent(
    form: SettingsFormState,
    battery: BackgroundBattery,
    onBack: () -> Unit,
    onOpenProfile: () -> Unit = {},
    onOpenChats: () -> Unit = {},
    onOpenStartingPoint: () -> Unit = {},
    onSelectThemeMode: (ThemeMode) -> Unit = {},
    onToggleDynamicColor: (Boolean) -> Unit = {},
    onToggleLowPower: (Boolean) -> Unit = {},
    onToggleWifiAware: (Boolean) -> Unit = {},
    onToggleWatch: (Boolean) -> Unit = {},
    onToggleSecureScreens: (Boolean) -> Unit = {},
    onOpenRelays: () -> Unit,
    onOpenLora: () -> Unit = {},
    onOpenCalls: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onOpenLicenses: () -> Unit = {},
    onOpenBackup: () -> Unit = {},
    onOpenBlockedUsers: () -> Unit = {},
    onOpenWhatsNew: () -> Unit = {},
    onOpenBackgroundRunning: () -> Unit = {},
    // The clone row's action — opens the confirmation in the stateful wrapper, never the wipe itself.
    onSignOut: () -> Unit = {},
    // Whether the Internet-relay plane is introduced at all in this build. A parameter rather than a
    // bare BuildConfig read so the hidden case is previewable and testable; see app/build.gradle.kts.
    showInternetRelays: Boolean = BuildConfig.INTERNET_PLANE,
    // Same, for the LoRa plane.
    showLoraRadio: Boolean = BuildConfig.LORA_PLANE,
    // Same, for the Wear OS status service: only a build that serves the watch has a Watch switch.
    showWatch: Boolean = BuildConfig.WEAR_STATUS,
    // Same, for calls (experimental): only a build that wires live calls or speech (`CALL_SETTINGS_WIRED`) has a Calls
    // screen.
    showCalls: Boolean = CALL_SETTINGS_WIRED,
    // Whether the platform can do wallpaper colours at all (API 31+). A parameter for the same reason as
    // the two above: the hidden case stays previewable and testable rather than depending on the device.
    showDynamicColor: Boolean = DYNAMIC_COLOR_SUPPORTED,
    // Same, for the platform's per-app night mode — a different fact that happens to carry the same
    // API level; see ui/theme/ThemeMode.kt.
    showThemeMode: Boolean = THEME_MODE_SUPPORTED,
    // Whether this phone has Wi-Fi Aware at all, so the Wi-Fi Aware switch shows only where there is a plane to switch.
    wifiAware: Boolean = rememberWifiAwareSupported(),
    // Whether low-power mode is offered (`LOW_POWER_OFFERED`, false since GitHub #44): its row stays in the code.
    showLowPower: Boolean = LOW_POWER_OFFERED,
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
                // What's new, About and the licenses list live behind the overflow rather than as rows: none is a
                // setting, and the column below is for things that change how Knit behaves. Blocked users left the menu
                // for a row under Privacy: it is a list you keep, like the backup beside it.
                actions = {
                    Box {
                        IconButton(
                            onClick = { menuOpen = true },
                            modifier = Modifier.size(48.dp).testTag("settings_menu"),
                        ) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.chat_more_options))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
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

            // The rows sit in five groups, most-used first within each. A group's heading shows only where at least one of
            // its rows does: the flag-gated rows (calls, relays, LoRa, watch) and the API-gated ones (theme, wallpaper
            // colours, Wi-Fi Aware) can each be absent.

            // What a chat sends, shows and lets in — typing, link previews, content filtering, large Bluetooth downloads —
            // on its own screen, so this column keeps to the app, its planes and the phone. Calls beside it: the other
            // way of talking to someone.
            SettingsSection(stringResource(R.string.settings_section_conversations))
            NavigatingRow(
                title = stringResource(R.string.settings_chats_title),
                subtitle = stringResource(R.string.settings_chats_subtitle),
                onClick = onOpenChats,
                modifier = Modifier.testTag("settings_chats"),
            )
            // Not under the Internet plane's flag: the person's own TURN server relays a call without any relay.
            if (showCalls) CallsRow(summary = form.calls, onClick = onOpenCalls)

            // How Knit reaches people beyond Bluetooth, which is always on and so has no row. The Wi-Fi Aware subtitle
            // states its battery trade (GitHub #44).
            val anyConnection = wifiAware || showInternetRelays || showLoraRadio
            if (anyConnection || showLowPower) SettingsSection(stringResource(R.string.settings_section_connections))
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
            if (showInternetRelays) InternetRelayRow(summary = form.relay, onClick = onOpenRelays)
            if (showLoraRadio) LoraRadioRow(summary = form.lora, onClick = onOpenLora)
            if (showLowPower) {
                ToggleRow(
                    title = stringResource(R.string.settings_low_power_title),
                    subtitle = lowPowerSubtitle(form.lowPower),
                    enabled = form.lowPower.switchOn,
                    onToggle = onToggleLowPower,
                    modifier = Modifier.testTag("settings_low_power"),
                )
            }

            // What this phone shows to whoever holds it, who can reach you, and the backup that carries what it keeps.
            SettingsSection(stringResource(R.string.settings_section_privacy))
            ToggleRow(
                title = stringResource(R.string.settings_secure_screens_title),
                subtitle = stringResource(R.string.settings_secure_screens_subtitle),
                enabled = form.secureScreens,
                onToggle = onToggleSecureScreens,
                modifier = Modifier.testTag("settings_secure_screens"),
            )
            NavigatingRow(
                title = stringResource(R.string.blocked_users_title),
                subtitle = stringResource(R.string.settings_blocked_subtitle),
                onClick = onOpenBlockedUsers,
                modifier = Modifier.testTag("settings_blocked"),
            )
            NavigatingRow(
                title = stringResource(R.string.settings_backup_title),
                subtitle = stringResource(R.string.settings_backup_subtitle),
                onClick = onOpenBackup,
                modifier = Modifier.testTag("settings_backup"),
            )

            // Both appearance controls are hidden rather than disabled below API 31: a control that can never move needs
            // a reason next to it, and there is nowhere here to put one.
            if (showThemeMode || showDynamicColor) {
                SettingsSection(stringResource(R.string.settings_section_appearance))
            }
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

            // The phone itself: what Android lets Knit do in the background, a paired watch, and the preset the defaults
            // above come from — touched at onboarding and rarely after, so last.
            SettingsSection(stringResource(R.string.settings_section_this_phone))
            NavigatingRow(
                title = stringResource(R.string.settings_background_title),
                subtitle = stringResource(backgroundSummary(battery)),
                subtitleColor = if (battery == BackgroundBattery.Restricted) MaterialTheme.colorScheme.error else Color.Unspecified,
                onClick = onOpenBackgroundRunning,
                modifier = Modifier.testTag("settings_background"),
            )
            // What this phone tells a paired watch. Off by default: the status leaves the phone, so the subtitle says
            // what the watch reads (ADR 2026-09.wetm's seventh amendment).
            if (showWatch) {
                ToggleRow(
                    title = stringResource(R.string.settings_watch_title),
                    subtitle =
                        stringResource(if (form.watchOn) R.string.settings_watch_subtitle_on else R.string.settings_watch_subtitle_off),
                    enabled = form.watchOn,
                    onToggle = onToggleWatch,
                    modifier = Modifier.testTag("settings_watch"),
                )
            }
            val presetName = stringResource(form.startingPoint.preset.titleRes())
            NavigatingRow(
                title = stringResource(R.string.settings_preset_title),
                subtitle =
                    if (form.startingPoint.customised) {
                        stringResource(R.string.settings_preset_subtitle_customised, presetName)
                    } else {
                        presetName
                    },
                onClick = onOpenStartingPoint,
                modifier = Modifier.testTag("settings_starting_point"),
            )
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

/** The three choices in the order they read, each with the string that names it. */
private val THEME_MODES =
    listOf(
        ThemeMode.System to R.string.settings_theme_system,
        ThemeMode.Light to R.string.settings_theme_light,
        ThemeMode.Dark to R.string.settings_theme_dark,
    )

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
    // A MeshCore board's name is never kept, so the row names the bound board by its kind instead. Unread without one.
    val boardName =
        summary.boardName ?: when (summary.boundKind) {
            BoardKind.MESHTASTIC -> stringResource(R.string.lora_summary_kind_meshtastic)
            BoardKind.MESHCORE -> stringResource(R.string.lora_summary_kind_meshcore)
            null -> ""
        }
    val subtitle =
        when (summary.line) {
            LoraRowLine.Off -> stringResource(R.string.lora_summary_off)
            LoraRowLine.Connected -> loraConnectedSubtitle(boardName, summary.battery)
            LoraRowLine.NotConnected -> stringResource(R.string.lora_summary_disconnected, boardName)
            LoraRowLine.Connecting -> stringResource(R.string.lora_summary_connecting)
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

/**
 * "On · <board> · connected", with the board's battery appended once it has reported a percent or external power. A
 * MeshCore board reports a voltage alone, which the row leaves to the LoRa screen.
 */
@Composable
private fun loraConnectedSubtitle(
    boardName: String,
    battery: BoardBattery?,
): String =
    when {
        battery?.percent != null -> stringResource(R.string.lora_summary_connected_battery, boardName, battery.percent)
        battery?.powered == true -> stringResource(R.string.lora_summary_connected_powered, boardName)
        else -> stringResource(R.string.lora_summary_connected, boardName)
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
                    relay = RelaySummary(enabled = true, configured = 2, active = 2, connected = 1),
                    lora = LoraSummary(enabled = true, boardName = "Meshtastic_1a2b", plane = LoraPlane.Live),
                ),
            battery = BackgroundBattery.Optimized,
            onBack = {},
            onOpenRelays = {},
            // The screen a shipped build shows: it has no calls (`CALL_SETTINGS_WIRED` off), and so no Calls row, and
            // serves no watch (`WEAR_STATUS` off), and so no Watch row.
            showCalls = false,
            showWatch = false,
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
            onOpenRelays = {},
            showCalls = false,
            showWatch = false,
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
            onOpenRelays = {},
            showCalls = false,
            showWatch = false,
        )
    }
