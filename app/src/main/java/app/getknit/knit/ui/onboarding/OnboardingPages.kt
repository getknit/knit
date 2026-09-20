package app.getknit.knit.ui.onboarding

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.getknit.knit.R
import app.getknit.knit.identity.displayNameFor
import app.getknit.knit.ui.DeviceSupervision
import app.getknit.knit.ui.MeshPermissionTier
import app.getknit.knit.ui.UnusedAppPause
import app.getknit.knit.ui.components.Avatar
import app.getknit.knit.ui.components.DisplayNameField
import app.getknit.knit.ui.preview.KnitPreview
import app.getknit.knit.ui.theme.KnitMotion
import app.getknit.knit.ui.theme.knitColors

/*
 * The three onboarding pages, each a plain scrolling column so every control stays reachable at any font
 * scale (the direct-transfer consent sheet learned this the hard way). The footer with the step dots and
 * the one CTA lives in OnboardingScreen and stays put while these swap underneath it.
 */

/**
 * Page 1: the brand mark, one sentence on what Knit is, and three lines on how it works — the model a
 * first-time user needs before Android starts asking them for grants. It does not name the two ways in
 * (say hello, add a contact); the chat list's Getting-started card does that right after Start.
 */
@Composable
internal fun WelcomePage(
    modifier: Modifier = Modifier,
    onRestore: () -> Unit = {},
) {
    // A finite reveal on arrival, never an infinite one: the page must settle for tests and screenshots.
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier =
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    .testTag("onboarding_page_welcome"),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AnimatedVisibility(visibleState = shown, enter = KnitMotion.enterPop()) {
                // The launcher's themed-icon layer: the K mark as a silhouette, tinted to the scheme's primary
                // — coral on the stock scheme, the wallpaper's hue under Material You, the same way the
                // launcher itself draws it. Decorative; the title below is the heading.
                Icon(
                    painter = painterResource(R.drawable.ic_launcher_monochrome),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(BRAND_MARK_SIZE),
                )
            }
            Text(
                text = stringResource(R.string.onboarding_title),
                style = MaterialTheme.typography.headlineLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(R.string.onboarding_tagline),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
            Spacer(Modifier.height(24.dp))
            AnimatedVisibility(visibleState = shown, enter = KnitMotion.enterReveal()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HowItWorksRow(
                        icon = Icons.Outlined.Sensors,
                        title = stringResource(R.string.onboarding_how_hop_title),
                        body = stringResource(R.string.onboarding_how_hop_body),
                    )
                    HowItWorksRow(
                        icon = Icons.Outlined.Lock,
                        title = stringResource(R.string.onboarding_how_sealed_title),
                        body = stringResource(R.string.onboarding_how_sealed_body),
                    )
                    HowItWorksRow(
                        icon = Icons.Outlined.Hub,
                        title = stringResource(R.string.onboarding_how_carry_title),
                        body = stringResource(R.string.onboarding_how_carry_body),
                    )
                }
            }
            // The second way in: a phone that is replacing another one. Quiet on purpose — a text button
            // under the pitch, since most first launches have nothing to restore.
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onRestore, modifier = Modifier.testTag("onboarding_restore")) {
                Text(stringResource(R.string.onboarding_restore))
            }
        }
    }
}

@Composable
private fun HowItWorksRow(
    icon: ImageVector,
    title: String,
    body: String,
) {
    Row(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 2.dp).size(24.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Page 2: the display name, optional. The field is the Profile screen's own ([DisplayNameField]) so the
 * two can't drift; the keyboard comes up on arrival because typing is the whole page, and IME Done is the
 * same as the footer button.
 */
@Composable
internal fun NamePage(
    name: String,
    alias: String,
    nodeId: String,
    onNameChange: (String) -> Unit,
    onNameCommit: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier =
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp)
                    .testTag("onboarding_page_name"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // What peers will actually draw for this phone: the initial of whatever is typed (or of the
            // alias), on the tint the node id keys (ADR 2026-09.j8c7) — the same call the Profile screen
            // makes, so the preview cannot lie. Decorative here; the heading below carries the meaning.
            Avatar(
                avatarHash = null,
                name = displayNameFor(name, nodeId),
                nodeId = nodeId.ifEmpty { null },
                size = 96.dp,
                textStyle = MaterialTheme.typography.displaySmall,
                modifier = Modifier.testTag("onboarding_avatar"),
            )
            Text(
                text = stringResource(R.string.onboarding_name_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(R.string.onboarding_name_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            DisplayNameField(
                value = name,
                alias = alias,
                onValueChange = onNameChange,
                onCommit = onNameCommit,
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("onboarding_name"),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onDone() }),
            )
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}

/**
 * Page 3: one row per thing Android will ask about — not per Android permission, since the radio set is
 * one dialog sequence to the user. The first row is the gate (Start needs it); notifications (33+ only), the
 * battery exemption and the unused-app switch (11+ only) sit under an "Optional" header and never block.
 * [meshSupported] false puts a notice above the rows so the user knows why granting won't find anyone; Start
 * stays available regardless, so a phone with neither radio can still open the app and read what it has.
 */
@Composable
internal fun PermissionsPage(
    rows: PermissionRows,
    tier: MeshPermissionTier,
    meshSupported: Boolean,
    onRequestRadio: () -> Unit,
    onRequestNotifications: () -> Unit,
    onAllowBattery: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenUnusedPauseSettings: () -> Unit = {},
) {
    // Centred when it fits, scrolling when it doesn't — the Box/scroll pairing is what makes both true.
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier =
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp)
                    .testTag("onboarding_page_permissions"),
        ) {
            Text(
                text = stringResource(R.string.onboarding_permissions_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() }.padding(bottom = 8.dp),
            )
            if (!meshSupported) UnsupportedNotice(modifier = Modifier.padding(vertical = 8.dp))
            PermissionRow(
                icon =
                    when (tier) {
                        MeshPermissionTier.NEARBY_DEVICES -> Icons.Outlined.Sensors
                        MeshPermissionTier.LOCATION_AND_BLUETOOTH -> Icons.Outlined.Bluetooth
                        MeshPermissionTier.LOCATION -> Icons.Outlined.LocationOn
                    },
                title =
                    stringResource(
                        when (tier) {
                            MeshPermissionTier.NEARBY_DEVICES -> R.string.onboarding_perm_nearby_title
                            MeshPermissionTier.LOCATION_AND_BLUETOOTH -> R.string.onboarding_perm_location_bt_title
                            MeshPermissionTier.LOCATION -> R.string.onboarding_perm_location_title
                        },
                    ),
                rationale =
                    stringResource(
                        if (tier == MeshPermissionTier.NEARBY_DEVICES) {
                            R.string.onboarding_perm_nearby_body
                        } else {
                            R.string.onboarding_perm_location_body
                        },
                    ),
                state = rowState(granted = rows.radioGranted, needsSettings = rows.radioNeedsSettings),
                onAllow = onRequestRadio,
                onOpenSettings = onOpenSettings,
                actionTag = "onboarding_grant",
                settingsHint = deniedHint(supervisedHint(rows.supervision, tier, GrantRow.Radio)),
            )
            Text(
                text = stringResource(R.string.onboarding_optional),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp).semantics { heading() },
            )
            if (tier == MeshPermissionTier.NEARBY_DEVICES) {
                PermissionRow(
                    icon = Icons.Outlined.Notifications,
                    title = stringResource(R.string.onboarding_perm_notifications_title),
                    rationale = stringResource(R.string.onboarding_perm_notifications_body),
                    state = rowState(granted = rows.notificationsGranted, needsSettings = rows.notificationsNeedSettings),
                    onAllow = onRequestNotifications,
                    onOpenSettings = onOpenSettings,
                    actionTag = "onboarding_notifications",
                    settingsHint = deniedHint(supervisedHint(rows.supervision, tier, GrantRow.Notifications)),
                )
            }
            PermissionRow(
                icon = Icons.Outlined.BatteryChargingFull,
                title = stringResource(R.string.onboarding_perm_battery_title),
                rationale = stringResource(R.string.onboarding_perm_battery_body),
                // The exemption dialog can always be shown again, so the only way to "Open settings" here is
                // a Restricted setting, which no dialog of ours can lift — and the hint says which setting.
                state = rowState(granted = rows.batteryExempt && !rows.batteryRestricted, needsSettings = rows.batteryRestricted),
                onAllow = onAllowBattery,
                onOpenSettings = onOpenSettings,
                actionTag = "onboarding_battery",
                settingsHint = stringResource(R.string.onboarding_perm_battery_restricted_hint),
            )
            if (rows.unusedPause != null) {
                PermissionRow(
                    icon = Icons.Outlined.HourglassEmpty,
                    title = stringResource(R.string.onboarding_perm_unused_title),
                    rationale = stringResource(R.string.onboarding_perm_unused_body),
                    // A switch, not a grant: there is no dialog to ask with, so the only action is the
                    // app-info page, and the hint names the switch. Its default position is nothing to
                    // alarm anyone about, so the hint keeps the rationale's colour.
                    state = rowState(granted = rows.unusedPause == UnusedAppPause.Off, needsSettings = true),
                    onAllow = {},
                    onOpenSettings = onOpenUnusedPauseSettings,
                    actionTag = "onboarding_unused",
                    settingsHint = stringResource(R.string.onboarding_perm_unused_hint),
                    quietHint = true,
                )
            }
        }
    }
}

private enum class RowState { Granted, Ask, OpenSettings }

/**
 * The "Open settings" line for a permission row: Android's own "won't ask again", or — on a phone somebody
 * else administers, where that same instant refusal may be theirs — the line that says who and where.
 */
@Composable
private fun deniedHint(holder: DeviceSupervision?): String =
    stringResource(
        when (holder) {
            null, DeviceSupervision.None -> R.string.onboarding_perm_denied_hint
            DeviceSupervision.FamilyLink -> R.string.onboarding_perm_denied_hint_family_link
            DeviceSupervision.Managed -> R.string.onboarding_perm_denied_hint_managed
        },
    )

private fun rowState(
    granted: Boolean,
    needsSettings: Boolean,
): RowState =
    when {
        granted -> RowState.Granted
        needsSettings -> RowState.OpenSettings
        else -> RowState.Ask
    }

/**
 * One permission concept: icon, title, a one-line why, and a trailing action that reads the state — a
 * check once held, "Allow" while the dialog can still be shown, "Open settings" once it can't. The swap is a
 * plain one, not animated: it lands while a system dialog covers the screen, so nobody watches it (and it
 * sidesteps the double-announce an `AnimatedContent` would cause, ADR 047). The row merges to one TalkBack
 * item; the button stays its own focus stop.
 */
@Composable
private fun PermissionRow(
    icon: ImageVector,
    title: String,
    rationale: String,
    state: RowState,
    onAllow: () -> Unit,
    onOpenSettings: () -> Unit,
    actionTag: String,
    // What the row says under its rationale once it lands on "Open settings"; the permission rows share
    // Android's "won't ask again", the battery row names the setting that put it there.
    settingsHint: String = stringResource(R.string.onboarding_perm_denied_hint),
    // The hint reads as an error by default, because "Open settings" usually means something went wrong;
    // a row whose settings state is the platform's own default says it in the rationale's colour instead.
    quietHint: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = rationale,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state == RowState.OpenSettings) {
                Text(
                    text = settingsHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (quietHint) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        // The buttons say "Allow" three times over on the page, so each one names its row to a screen
        // reader ("Allow, Nearby devices") — ATF flags identical speakable text on sibling controls.
        val allowLabel = stringResource(R.string.onboarding_allow_desc, title)
        val settingsLabel = stringResource(R.string.onboarding_open_settings_desc, title)
        when (state) {
            RowState.Granted -> {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = stringResource(R.string.onboarding_allowed),
                    tint = MaterialTheme.knitColors.positive,
                    modifier = Modifier.size(24.dp).testTag("${actionTag}_granted"),
                )
            }

            RowState.Ask -> {
                FilledTonalButton(
                    onClick = onAllow,
                    modifier = Modifier.testTag(actionTag).semantics { contentDescription = allowLabel },
                ) {
                    Text(stringResource(R.string.onboarding_allow))
                }
            }

            RowState.OpenSettings -> {
                FilledTonalButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.testTag("${actionTag}_settings").semantics { contentDescription = settingsLabel },
                ) {
                    Text(stringResource(R.string.action_open_settings))
                }
            }
        }
    }
}

/** Neither radio on this phone: said before the rows, so the user knows why granting won't find anyone. */
@Composable
private fun UnsupportedNotice(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(modifier = Modifier.padding(16.dp)) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.onboarding_unsupported),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

// The launcher vector keeps its 108dp canvas with the glyph in the middle ~65%, so this reads as a ~120dp mark.
private val BRAND_MARK_SIZE = 184.dp

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun WelcomePagePreview() = KnitPreview { WelcomePage() }

@Preview(showBackground = true)
@Composable
fun NamePagePreview() =
    KnitPreview {
        NamePage(
            name = "Sam Rivera",
            alias = "SmartlyBrightSparrow",
            nodeId = "node-preview",
            onNameChange = {},
            onNameCommit = {},
            onDone = {},
        )
    }

@Preview(showBackground = true)
@Preview(showBackground = true, fontScale = 1.5f)
@Composable
fun PermissionsPagePreview() =
    KnitPreview {
        PermissionsPage(
            rows = PermissionRows.FRESH,
            tier = MeshPermissionTier.NEARBY_DEVICES,
            meshSupported = true,
            onRequestRadio = {},
            onRequestNotifications = {},
            onAllowBattery = {},
            onOpenSettings = {},
        )
    }

@Preview(showBackground = true)
@Composable
fun PermissionsPageGrantedPreview() =
    KnitPreview {
        PermissionsPage(
            rows = PermissionRows.ALL,
            tier = MeshPermissionTier.NEARBY_DEVICES,
            meshSupported = true,
            onRequestRadio = {},
            onRequestNotifications = {},
            onAllowBattery = {},
            onOpenSettings = {},
        )
    }

@Preview(showBackground = true)
@Composable
fun PermissionsPageDeniedPreview() =
    KnitPreview {
        PermissionsPage(
            rows = PermissionRows.FRESH.copy(radioNeedsSettings = true),
            tier = MeshPermissionTier.NEARBY_DEVICES,
            meshSupported = true,
            onRequestRadio = {},
            onRequestNotifications = {},
            onAllowBattery = {},
            onOpenSettings = {},
        )
    }

@Preview(showBackground = true)
@Composable
fun PermissionsPageLocationTierPreview() =
    KnitPreview {
        PermissionsPage(
            rows = PermissionRows.FRESH,
            tier = MeshPermissionTier.LOCATION_AND_BLUETOOTH,
            meshSupported = true,
            onRequestRadio = {},
            onRequestNotifications = {},
            onAllowBattery = {},
            onOpenSettings = {},
        )
    }

@Preview(showBackground = true)
@Composable
fun PermissionsPageUnsupportedPreview() =
    KnitPreview {
        PermissionsPage(
            rows = PermissionRows.FRESH,
            tier = MeshPermissionTier.NEARBY_DEVICES,
            meshSupported = false,
            onRequestRadio = {},
            onRequestNotifications = {},
            onAllowBattery = {},
            onOpenSettings = {},
        )
    }

@Preview(showBackground = true)
@Composable
fun PermissionsPageFamilyLinkPreview() =
    KnitPreview {
        PermissionsPage(
            rows = PermissionRows.FRESH.copy(radioNeedsSettings = true, supervision = DeviceSupervision.FamilyLink),
            tier = MeshPermissionTier.LOCATION_AND_BLUETOOTH,
            meshSupported = true,
            onRequestRadio = {},
            onRequestNotifications = {},
            onAllowBattery = {},
            onOpenSettings = {},
        )
    }
