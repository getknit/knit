package app.getknit.knit.ui.lora

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.getknit.knit.R
import app.getknit.knit.mesh.lora.BoardBattery
import app.getknit.knit.ui.preview.KnitPreview
import org.koin.androidx.compose.koinViewModel

/**
 * LoRa radio settings: the master switch, the bonded-board picker, a channel-index selector, and the live
 * link status. Structurally the sibling of the Internet-relays screen. Pairing itself happens in the system
 * Bluetooth settings (the board shows a PIN on its OLED); this screen picks from already-bonded boards —
 * re-listed on every resume, so a board paired over there is offered the moment the user comes back.
 */
@Composable
fun LoraRadioScreen(onBack: () -> Unit) {
    val viewModel: LoraRadioViewModel = koinViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.refreshBoards()
        onPauseOrDispose {}
    }
    LoraRadioScreenContent(
        state = state,
        onBack = onBack,
        onToggle = viewModel::onToggle,
        onToggleDms = viewModel::onToggleDms,
        onToggleBridge = viewModel::onToggleBridge,
        onPickBoard = viewModel::pickBoard,
        onForgetBoard = viewModel::forgetBoard,
        onShowAllBoards = viewModel::setShowAllBoards,
        onDismissProvision = viewModel::dismissProvisionOutcome,
        onAskSetup = viewModel::askSetup,
        onAskSetupDedicated = viewModel::askSetupDedicated,
        onDismissSetup = viewModel::dismissSetup,
        onSetUp = viewModel::setUpBoard,
        onRestore = viewModel::restoreBoard,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LoraRadioScreenContent(
    state: LoraRadioUiState,
    onBack: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onToggleDms: (Boolean) -> Unit = {},
    onToggleBridge: (Boolean) -> Unit = {},
    onPickBoard: (BoardOption) -> Unit = {},
    onForgetBoard: () -> Unit = {},
    onShowAllBoards: (Boolean) -> Unit = {},
    onDismissProvision: () -> Unit = {},
    onAskSetup: () -> Unit = {},
    onAskSetupDedicated: () -> Unit = {},
    onDismissSetup: () -> Unit = {},
    onSetUp: () -> Unit = {},
    onRestore: () -> Unit = {},
) {
    val context = LocalContext.current
    Scaffold(
        modifier = Modifier.testTag("screen_lora_radio"),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                title = { Text(stringResource(R.string.lora_title)) },
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
            MasterSwitchRow(enabled = state.enabled, onToggle = onToggle)
            Text(
                text = stringResource(R.string.lora_off_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            DmSwitchRow(enabled = state.dmEnabled, active = state.enabled, onToggle = onToggleDms)
            BridgeSwitchRow(enabled = state.bridgeEnabled, active = state.enabled, onToggle = onToggleBridge)

            Text(
                text = stringResource(R.string.lora_board_section),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            when {
                state.boards.isNotEmpty() -> {
                    state.boards.forEach { board ->
                        BoardRow(board = board, onClick = { onPickBoard(board) })
                    }
                }

                // Two empty states, because they ask for different things: pair a board at all, or reveal
                // the paired devices the board filter is hiding.
                !state.anyBonded -> {
                    Text(
                        text = stringResource(R.string.lora_board_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> {
                    Text(
                        text = stringResource(R.string.lora_board_none_meshtastic),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("lora_board_none_meshtastic"),
                    )
                }
            }
            if (state.hiddenBoards > 0 || state.showAllBoards) {
                ShowAllBoardsRow(hidden = state.hiddenBoards, checked = state.showAllBoards, onToggle = onShowAllBoards)
            }
            OutlinedButton(
                onClick = { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
                modifier = Modifier.testTag("lora_open_bt_settings"),
            ) {
                Text(stringResource(R.string.lora_open_settings))
            }
            if (state.boardAddress != null) {
                TextButton(onClick = onForgetBoard) { Text(stringResource(R.string.lora_forget)) }
            }

            SetupSection(
                state = state,
                onAskSetup = onAskSetup,
                onAskSetupDedicated = onAskSetupDedicated,
                onSetUp = onSetUp,
                onRestore = onRestore,
                onDismissProvision = onDismissProvision,
            )

            StatusRow(state = state)

            if (state.confirmSetup) {
                SetupConfirmDialog(dedicated = state.confirmDedicated, onConfirm = onSetUp, onDismiss = onDismissSetup)
            }
        }
    }
}

@Composable
private fun MasterSwitchRow(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .toggleable(value = enabled, onValueChange = onToggle, role = Role.Switch)
                .testTag("lora_switch"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_lora_title), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(R.string.settings_lora_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = enabled, onCheckedChange = null)
    }
}

/**
 * The private-messages switch (ADR 039): DMs stay end-to-end encrypted over LoRa, but their metadata becomes
 * visible at kilometre range, so the user can keep them on the phone radios. Inert while the plane is off.
 */
@Composable
private fun DmSwitchRow(
    enabled: Boolean,
    active: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .toggleable(value = enabled, enabled = active, onValueChange = onToggle, role = Role.Switch)
                .testTag("lora_dm_switch"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.lora_dm_title), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(R.string.lora_dm_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = enabled, onCheckedChange = null, enabled = active)
    }
}

/**
 * The bridge switch (ADR 044): whether this board carries other groups' backlog across the hop, not just the
 * live traffic its own pocket generates. Inert while the plane is off.
 */
@Composable
private fun BridgeSwitchRow(
    enabled: Boolean,
    active: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .toggleable(value = enabled, enabled = active, onValueChange = onToggle, role = Role.Switch)
                .testTag("lora_bridge_switch"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.lora_bridge_title), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(R.string.lora_bridge_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = enabled, onCheckedChange = null, enabled = active)
    }
}

/** Reveals the bonded devices the board filter hides — a heuristic, so the user can always see everything. */
@Composable
private fun ShowAllBoardsRow(
    hidden: Int,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .toggleable(value = checked, onValueChange = onToggle, role = Role.Switch)
                .testTag("lora_show_all_boards"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.lora_show_all_boards), style = MaterialTheme.typography.bodyMedium)
            if (hidden > 0) {
                Text(
                    text = pluralStringResource(R.plurals.lora_boards_hidden, hidden, hidden),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun BoardRow(
    board: BoardOption,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .selectable(selected = board.selected, onClick = onClick, role = Role.RadioButton)
                .testTag("lora_board_${board.address}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = board.selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(board.name, style = MaterialTheme.typography.bodyLarge)
            Text(board.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * The one step between a paired board and messages crossing (ADR 045): set the board up for Knit, or undo
 * that. Deliberately not a spectrum — a board is configured for Knit or it is a stock Meshtastic node — so
 * this is one button whose label is the whole choice. The exception is a board set up by an older Knit,
 * whose identity is unfinished: it is missing its name (ADR 049), or the unmonitored mark (ADR 2026-09.emd7),
 * or both. The action comes back for those and goes straight through — it writes one `set_owner` and nothing
 * else, so there is nothing for a confirmation to warn about. The two are told apart by the name: when the
 * board is already called what Knit calls it, the mark is the half that is missing.
 */
@Composable
private fun SetupSection(
    state: LoraRadioUiState,
    onAskSetup: () -> Unit,
    onAskSetupDedicated: () -> Unit,
    onSetUp: () -> Unit,
    onRestore: () -> Unit,
    onDismissProvision: () -> Unit,
) {
    if (state.connection != LoraConnState.Ready) return
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.lora_setup_title), style = MaterialTheme.typography.titleMedium)
        // Once it is set up, say what the board now calls itself: that name is what identifies it on the
        // board's own screen and in every other radio's node list.
        val markOnly = state.meshName != null && state.meshName == state.knitName
        val body =
            when {
                !state.boardSetUp -> stringResource(R.string.lora_setup_body)
                state.needsRename && markOnly -> stringResource(R.string.lora_setup_needs_unmonitored)
                state.needsRename && state.meshName != null -> stringResource(R.string.lora_setup_needs_rename, state.meshName)
                state.meshName != null -> stringResource(R.string.lora_setup_done_named, state.meshName)
                else -> stringResource(R.string.lora_setup_done)
            }
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("lora_setup_body"),
        )
        if (state.customPrimary) {
            Text(
                text = stringResource(R.string.lora_custom_primary_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("lora_custom_primary"),
            )
        }
        // The other setting that decides who this board can hear. Not an error, and deliberately not
        // coloured like one: Knit cannot know which preset the local mesh runs, so this states the coupling
        // and leaves the choice alone. See [PresetMismatch].
        state.presetMismatch?.let { mismatch ->
            Text(
                text = stringResource(R.string.lora_preset_mismatch_notice, mismatch.board, mismatch.stock),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("lora_preset_mismatch"),
            )
        }
        // Until it is done, this is the emphasized button on the screen; afterwards all that is left is the
        // way back out, which never wants emphasis.
        if (state.boardSetUp) {
            // A board set up by an older Knit: the setup is genuinely unfinished, so the action comes back —
            // but as the one `set_owner` it writes, which the label states in full.
            if (state.needsRename && state.knitName != null) {
                Button(
                    onClick = onSetUp,
                    enabled = !state.provisioning,
                    modifier = Modifier.testTag("lora_rename"),
                ) {
                    Text(
                        if (markOnly) {
                            stringResource(R.string.lora_setup_unmonitored_button)
                        } else {
                            stringResource(R.string.lora_setup_rename_button, state.knitName)
                        },
                    )
                }
            }
            OutlinedButton(
                onClick = onRestore,
                enabled = !state.provisioning,
                modifier = Modifier.testTag("lora_restore"),
            ) {
                Text(stringResource(R.string.lora_setup_restore_button))
            }
        } else {
            Button(onClick = onAskSetup, enabled = !state.provisioning, modifier = Modifier.testTag("lora_setup")) {
                if (state.provisioning) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.lora_provision_running))
                } else {
                    Text(stringResource(R.string.lora_setup_button))
                }
            }
        }
        DedicatedSetupAction(state = state, onAskSetupDedicated = onAskSetupDedicated)
        state.provisionOutcome?.let { outcome ->
            val (message, isError) = outcome.messageAndSeverity()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(message),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).testTag("lora_provision_outcome"),
                )
                TextButton(onClick = onDismissProvision) { Text(stringResource(R.string.action_dismiss)) }
            }
        }
    }
}

/**
 * The debug-only alternative setup (ADR 067): the same board setup, but with the radio pinned to a slot of
 * its own instead of the shared public frequency. Absent from every release build ([LoraRadioUiState.dedicatedOffered]),
 * and shown disabled — with the reason — where Knit will not place a slot in the board's region, because a
 * greyed action that says why is more use than one that fails at the board.
 *
 * Offered whether or not the board is already set up: the session applies the slot write to a board that
 * already carries the Knit channel, so making the user Restore first would be a limitation of this screen
 * rather than of the thing underneath it. The line below the button says which slot it would pin, and
 * [LoraRadioUiState.dedicated] says whether the board is already on one.
 */
@Composable
private fun DedicatedSetupAction(
    state: LoraRadioUiState,
    onAskSetupDedicated: () -> Unit,
) {
    if (!state.dedicatedOffered) return
    OutlinedButton(
        onClick = onAskSetupDedicated,
        enabled = !state.provisioning && state.dedicatedSlot != null,
        modifier = Modifier.testTag("lora_setup_dedicated"),
    ) {
        Text(stringResource(R.string.lora_setup_dedicated_button))
    }
    Text(
        text =
            when {
                state.dedicated -> stringResource(R.string.lora_setup_dedicated_active)
                state.dedicatedSlot != null -> stringResource(R.string.lora_setup_dedicated_body, state.dedicatedSlot)
                else -> stringResource(R.string.lora_setup_dedicated_unavailable)
            },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("lora_setup_dedicated_body"),
    )
}

/**
 * Setting a board up changes settings on hardware the user may also use for other things — what it stops
 * broadcasting, and that it stops relaying other people's traffic — so it is confirmed first. What the
 * ordinary setup does *not* touch is the board's own primary channel, and therefore its frequency; the
 * debug-only [dedicated] one does move the radio, and trades the free relaying away, so it says so instead.
 */
@Composable
private fun SetupConfirmDialog(
    dedicated: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("lora_setup_dialog"),
        title = {
            Text(
                stringResource(
                    if (dedicated) R.string.lora_setup_dedicated_confirm_title else R.string.lora_setup_confirm_title,
                ),
            )
        },
        text = {
            Text(
                stringResource(
                    if (dedicated) R.string.lora_setup_dedicated_confirm_body else R.string.lora_setup_confirm_body,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("lora_setup_confirm")) {
                Text(stringResource(R.string.lora_setup_confirm_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

private fun LoraProvisionOutcome.messageAndSeverity(): Pair<Int, Boolean> =
    when (this) {
        LoraProvisionOutcome.Provisioned -> R.string.lora_provisioned to false
        LoraProvisionOutcome.AlreadyPresent -> R.string.lora_provision_already to false
        LoraProvisionOutcome.Restored -> R.string.lora_restored to false
        LoraProvisionOutcome.NoFreeSlot -> R.string.lora_provision_no_slot to true
        LoraProvisionOutcome.NoDedicatedSlot -> R.string.lora_provision_no_dedicated_slot to true
        LoraProvisionOutcome.Failed -> R.string.lora_provision_failed to true
        LoraProvisionOutcome.NotReady -> R.string.lora_provision_not_ready to true
    }

@Composable
private fun StatusRow(state: LoraRadioUiState) {
    val label =
        when (state.connection) {
            LoraConnState.Ready -> stringResource(R.string.lora_status_connected)
            LoraConnState.Connecting -> stringResource(R.string.lora_status_connecting)
            LoraConnState.Reconnecting -> stringResource(R.string.lora_status_reconnecting)
            LoraConnState.NeedsPairing -> stringResource(R.string.lora_status_needs_pairing)
            LoraConnState.Unavailable -> stringResource(R.string.lora_status_bt_off)
            LoraConnState.Off -> stringResource(R.string.lora_status_off)
        }
    Column(modifier = Modifier.fillMaxWidth().testTag("lora_status")) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        if (state.connection == LoraConnState.Ready) {
            val detail = MaterialTheme.typography.bodySmall
            val muted = MaterialTheme.colorScheme.onSurfaceVariant
            state.boardNodeNum?.let { Text(it, style = detail, color = muted) }
            state.firmware?.let { Text(stringResource(R.string.lora_firmware, it), style = detail, color = muted) }
            state.battery?.let { battery ->
                Text(
                    text = batteryText(battery),
                    style = detail,
                    color = if (battery.low) MaterialTheme.colorScheme.error else muted,
                    modifier = Modifier.testTag("lora_battery"),
                )
            }
            if (state.snr != null || state.rssi != null) {
                Text(
                    text = stringResource(R.string.lora_signal, state.snr ?: 0f, state.rssi ?: 0),
                    style = detail,
                    color = muted,
                )
            }
            // Radios first, because that is what "is the other board provisioned and in range" asks, and it
            // is the only sign short of a message. People second, and only when the two differ: a person is
            // counted from the frames that reached us, which a gateway may have relayed or backfilled on
            // their behalf from kilometres away — so "1 radio, 3 people" is normal, and reporting only the
            // second read as phantom hardware.
            Text(
                text = pluralStringResource(R.plurals.lora_boards_heard, state.boardsHeard, state.boardsHeard),
                style = detail,
                color = muted,
                modifier = Modifier.testTag("lora_boards_heard"),
            )
            if (state.heard > 0 && state.heard != state.boardsHeard) {
                Text(
                    text = pluralStringResource(R.plurals.lora_peers_heard, state.heard, state.heard),
                    style = detail,
                    color = muted,
                    modifier = Modifier.testTag("lora_peers_heard"),
                )
            }
            state.radioConfig?.let { Text(stringResource(R.string.lora_radio_config, it), style = detail, color = muted) }
            // What the Meshtastic room mirrors — slot 0 as the board names it.
            state.publicChannel?.let {
                Text(
                    text = stringResource(R.string.lora_public_channel, it),
                    style = detail,
                    color = muted,
                    modifier = Modifier.testTag("lora_public_channel"),
                )
            }
            // What the plane has spent of its own hourly allowance — the whole point of the governor is that
            // this is visible rather than inferred from a duty-cycle refusal.
            state.airtimePercent?.let {
                Text(
                    text = stringResource(R.string.lora_airtime, it),
                    style = detail,
                    color = muted,
                    modifier = Modifier.testTag("lora_airtime"),
                )
            }
            // A spare board is not a broken one; say so, or its silent counters read as a fault.
            if (state.bridgePassive) {
                Text(
                    text = stringResource(R.string.lora_role_passive),
                    style = detail,
                    color = muted,
                    modifier = Modifier.testTag("lora_role_passive"),
                )
            }
        }
    }
}

/**
 * "Battery 78% · 3.92 V" or, on external power (no [BoardBattery.percent]), "Plugged in · 4.10 V"; the
 * voltage clause only when the board measures one.
 */
@Composable
private fun batteryText(battery: BoardBattery): String {
    val percent = battery.percent
    val volts = battery.voltage
    return when {
        percent != null && volts != null -> stringResource(R.string.lora_battery_percent_voltage, percent, volts)
        percent != null -> stringResource(R.string.lora_battery_percent, percent)
        volts != null -> stringResource(R.string.lora_battery_powered_voltage, volts)
        else -> stringResource(R.string.lora_battery_powered)
    }
}

@Preview(showBackground = true)
@Composable
private fun LoraRadioScreenPreview() =
    KnitPreview {
        LoraRadioScreenContent(
            state =
                LoraRadioUiState(
                    enabled = true,
                    boardName = "Meshtastic_1a2b",
                    boardAddress = "AA:BB:CC:DD:EE:FF",
                    channel = 1,
                    connection = LoraConnState.Ready,
                    boardNodeNum = "!12345678",
                    snr = 6.5f,
                    rssi = -85,
                    heard = 2,
                    firmware = "2.5.0",
                    battery = BoardBattery(percent = 78, voltage = 3.92f, powered = false),
                    channelName = "Knit",
                    boards = listOf(BoardOption("AA:BB:CC:DD:EE:FF", "Meshtastic_1a2b", selected = true)),
                    hiddenBoards = 1,
                    anyBonded = true,
                ),
            onBack = {},
            onToggle = {},
        )
    }
