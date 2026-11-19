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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.getknit.knit.R
import app.getknit.knit.mesh.lora.BoardBattery
import app.getknit.knit.mesh.lora.meshcore.MeshcoreNames
import app.getknit.knit.ui.components.BarTitle
import app.getknit.knit.ui.preview.KnitPreview
import app.getknit.knit.ui.util.countText
import org.koin.androidx.compose.koinViewModel
import java.text.NumberFormat

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
        onToggleRoom = viewModel::onToggleRoom,
        onToggleMeshcoreRoom = viewModel::onToggleMeshcoreRoom,
        onPickBoard = viewModel::pickBoard,
        onForgetBoard = viewModel::forgetBoard,
        onShowAllBoards = viewModel::setShowAllBoards,
        onDismissProvision = viewModel::dismissProvisionOutcome,
        onAskSetup = viewModel::askSetup,
        onAskSetupDedicated = viewModel::askSetupDedicated,
        onSwitchToShared = viewModel::switchToShared,
        onDismissSetup = viewModel::dismissSetup,
        onSetUp = viewModel::setUpBoard,
        onRestore = viewModel::restoreBoard,
        onReplace = viewModel::replaceBoard,
        onDismissReplace = viewModel::dismissReplace,
        onKeepBoard = viewModel::keepBoard,
        onForgetAnyway = viewModel::forgetAnyway,
        onAskRename = viewModel::askRename,
        onRenameDraft = viewModel::setRenameDraft,
        onSaveRename = viewModel::saveRename,
        onUseDefaultName = viewModel::useDefaultName,
        onDismissRename = viewModel::dismissRename,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LoraRadioScreenContent(
    state: LoraRadioUiState,
    onBack: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onToggleDms: (Boolean) -> Unit = {},
    onToggleRoom: (Boolean) -> Unit = {},
    onPickBoard: (BoardOption) -> Unit = {},
    // The MeshCore room's switch (ADR 2026-11.gsqu's Stage 6 amendment), drawn while a MeshCore board is bound.
    onToggleMeshcoreRoom: (Boolean) -> Unit = {},
    onForgetBoard: () -> Unit = {},
    onShowAllBoards: (Boolean) -> Unit = {},
    onDismissProvision: () -> Unit = {},
    onAskSetup: () -> Unit = {},
    onAskSetupDedicated: () -> Unit = {},
    onSwitchToShared: () -> Unit = {},
    onDismissSetup: () -> Unit = {},
    onSetUp: () -> Unit = {},
    onRestore: () -> Unit = {},
    // MeshCore's (ADR 2026-11.gsqu's Stage 7 amendment): set up over another board's record, and the Forget's pending answer.
    onReplace: () -> Unit = {},
    onDismissReplace: () -> Unit = {},
    onKeepBoard: () -> Unit = {},
    onForgetAnyway: () -> Unit = {},
    // The MeshCore board's name on its network (ADR 2026-11.f4d3): the rename dialog's actions.
    onAskRename: () -> Unit = {},
    onRenameDraft: (String) -> Unit = {},
    onSaveRename: () -> Unit = {},
    onUseDefaultName: () -> Unit = {},
    onDismissRename: () -> Unit = {},
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
                title = { BarTitle(stringResource(R.string.lora_title)) },
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
            MasterSwitchRow(enabled = state.enabled, anyKind = state.anyKind, onToggle = onToggle)
            Text(
                text = stringResource(R.string.lora_off_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            DmSwitchRow(enabled = state.dmEnabled, active = state.enabled, onToggle = onToggleDms)
            RoomSwitchRow(
                enabled = state.roomEnabled,
                active = state.enabled && !state.dedicated,
                dedicated = state.dedicated,
                onToggle = onToggleRoom,
            )
            if (state.meshcoreBound) {
                MeshcoreRoomSwitchRow(enabled = state.meshcoreRoomEnabled, active = state.enabled, onToggle = onToggleMeshcoreRoom)
            }

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
                        text = stringResource(if (state.anyKind) R.string.lora_board_none_any else R.string.lora_board_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> {
                    Text(
                        text =
                            stringResource(
                                if (state.anyKind) R.string.lora_board_none_supported else R.string.lora_board_none_meshtastic,
                            ),
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
                TextButton(
                    onClick = onForgetBoard,
                    enabled = !state.forgetting,
                    modifier = Modifier.testTag("lora_forget"),
                ) { Text(stringResource(R.string.lora_forget)) }
            }
            state.forgetNotice?.let { notice ->
                OutcomeLine(
                    message =
                        when (notice) {
                            MeshcoreForgetNotice.Failed -> R.string.lora_meshcore_forget_failed
                            MeshcoreForgetNotice.Mismatch -> R.string.lora_meshcore_forget_mismatch
                        },
                    isError = notice == MeshcoreForgetNotice.Failed,
                    tag = "lora_meshcore_forget_notice",
                    onDismiss = onDismissProvision,
                )
            }

            val meshcore = state.meshcore
            if (meshcore != null) {
                MeshcoreSetupSection(
                    state = state,
                    meshcore = meshcore,
                    onAskSetup = onAskSetup,
                    onRestore = onRestore,
                    onDismissProvision = onDismissProvision,
                )
                if (meshcore.setup == MeshcoreSetup.SetUp) {
                    MeshcoreNameSection(
                        state = state,
                        meshcore = meshcore,
                        onAskRename = onAskRename,
                        onDismissNotice = onDismissProvision,
                    )
                }
            } else {
                SetupSection(
                    state = state,
                    onAskSetup = onAskSetup,
                    onAskSetupDedicated = onAskSetupDedicated,
                    onSwitchToShared = onSwitchToShared,
                    onSetUp = onSetUp,
                    onRestore = onRestore,
                    onDismissProvision = onDismissProvision,
                )
            }

            StatusRow(state = state)

            if (state.confirmSetup) {
                if (meshcore != null) {
                    MeshcoreSetupConfirmDialog(
                        renames = state.erase?.renames != false,
                        erase = state.erase,
                        eraseLoading = state.eraseLoading,
                        sharesPosition = meshcore.sharesPosition,
                        onConfirm = onSetUp,
                        onDismiss = onDismissSetup,
                    )
                } else {
                    SetupConfirmDialog(dedicated = state.confirmDedicated, onConfirm = onSetUp, onDismiss = onDismissSetup)
                }
            }
            if (state.confirmReplace) MeshcoreReplaceDialog(onConfirm = onReplace, onDismiss = onDismissReplace)
            if (state.forgetPending) MeshcoreForgetPendingDialog(onKeep = onKeepBoard, onForgetAnyway = onForgetAnyway)
            val rename = state.meshcoreRename
            if (meshcore != null && rename != null) {
                MeshcoreRenameDialog(
                    rename = rename,
                    defaultName = meshcore.defaultName,
                    sharesPosition = meshcore.sharesPosition,
                    onDraft = onRenameDraft,
                    onSave = onSaveRename,
                    onUseDefault = onUseDefaultName,
                    onDismiss = onDismissRename,
                )
            }
        }
    }
}

@Composable
private fun MasterSwitchRow(
    enabled: Boolean,
    // The build carries a second board kind: the line names both.
    anyKind: Boolean,
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
                text = stringResource(if (anyKind) R.string.settings_lora_subtitle_any else R.string.settings_lora_subtitle),
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
 * The Meshtastic-room switch (ADR 2026-09.26q3): whether the board's own primary channel is mirrored into a
 * room on this phone at all. Off, slot 0 is never read — the room's row leaves the chat list, nothing from it
 * notifies, and posts stop being processed where they land rather than being filtered later. The board keeps
 * carrying Knit's own traffic either way. Inert while the plane is off.
 */
@Composable
private fun RoomSwitchRow(
    enabled: Boolean,
    active: Boolean,
    // The board is pinned to a dedicated RF slot (ADR 067): there is no public channel to mirror, so the room
    // is hidden whatever this switch says, and the subtitle says why instead of the switch pretending to act.
    dedicated: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .toggleable(value = enabled, enabled = active, onValueChange = onToggle, role = Role.Switch)
                .testTag("lora_room_switch"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.lora_room_title), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(if (dedicated) R.string.lora_room_subtitle_dedicated else R.string.lora_room_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = enabled, onCheckedChange = null, enabled = active)
    }
}

/**
 * The MeshCore room's switch (`SettingsStore.meshcoreRoomEnabled`, ADR 2026-11.gsqu's Stage 6 amendment): whether a bound
 * MeshCore board's Public channel is mirrored as a room. The Meshtastic room's row, for the other kind's room; a MeshCore
 * board has no dedicated frequency to hide it.
 */
@Composable
private fun MeshcoreRoomSwitchRow(
    enabled: Boolean,
    active: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .toggleable(value = enabled, enabled = active, onValueChange = onToggle, role = Role.Switch)
                .testTag("lora_meshcore_room_switch"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.lora_meshcore_room_title), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(R.string.lora_meshcore_room_subtitle),
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
                .selectable(selected = board.selected, enabled = board.pickable, onClick = onClick, role = Role.RadioButton)
                .testTag("lora_board_${board.address}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = board.selected, onClick = null, enabled = board.pickable)
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
    onSwitchToShared: () -> Unit,
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
        DedicatedSetupAction(state = state, onAskSetupDedicated = onAskSetupDedicated, onSwitchToShared = onSwitchToShared)
        state.provisionOutcome?.let { outcome ->
            val (message, isError) = outcome.messageAndSeverity()
            OutcomeLine(message = message, isError = isError, tag = "lora_provision_outcome", onDismiss = onDismissProvision)
        }
    }
}

/** One dismissible result line: a provisioning tap's outcome or a Forget's notice. */
@Composable
private fun OutcomeLine(
    message: Int,
    isError: Boolean,
    tag: String,
    onDismiss: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(message),
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).testTag(tag),
        )
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_dismiss)) }
    }
}

/**
 * A bound MeshCore board's setup (ADR 2026-11.gsqu's Stage 7 amendment): whether this phone set it up for Knit, shown while
 * the board is away too, since it is this phone's record; then, while it is Ready, the one action its state allows. A board
 * whose cleanup is pending offers nothing: the cleanup runs as it connects.
 */
@Composable
private fun MeshcoreSetupSection(
    state: LoraRadioUiState,
    meshcore: MeshcoreRows,
    onAskSetup: () -> Unit,
    onRestore: () -> Unit,
    onDismissProvision: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.lora_setup_title), style = MaterialTheme.typography.titleMedium)
        Text(
            text =
                stringResource(
                    when (meshcore.setup) {
                        MeshcoreSetup.None -> R.string.lora_meshcore_state_none
                        MeshcoreSetup.SetUp -> R.string.lora_meshcore_state_set_up
                        MeshcoreSetup.Pending -> R.string.lora_meshcore_state_pending
                        MeshcoreSetup.Unknown -> R.string.lora_meshcore_state_unknown
                    },
                ),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("lora_meshcore_state"),
        )
        if (meshcore.setup != MeshcoreSetup.SetUp && meshcore.setup != MeshcoreSetup.Pending) {
            Text(
                text = stringResource(R.string.lora_meshcore_setup_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("lora_setup_body"),
            )
        }
        if (state.connection == LoraConnState.Ready) {
            when (meshcore.setup) {
                MeshcoreSetup.SetUp -> {
                    OutlinedButton(
                        onClick = onRestore,
                        enabled = !state.provisioning,
                        modifier = Modifier.testTag("lora_restore"),
                    ) {
                        Text(stringResource(R.string.lora_setup_restore_button))
                    }
                }

                MeshcoreSetup.None, MeshcoreSetup.Unknown -> {
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

                MeshcoreSetup.Pending -> {}
            }
        }
        state.provisionOutcome?.let { outcome ->
            val (message, isError) =
                if (outcome == LoraProvisionOutcome.Restored) R.string.lora_meshcore_restored to false else outcome.messageAndSeverity()
            OutcomeLine(message = message, isError = isError, tag = "lora_provision_outcome", onDismiss = onDismissProvision)
        }
    }
}

/**
 * The alternative setup (ADR 067): the same board setup, but with the radio pinned to a slot of its own
 * instead of the shared public frequency. Shown disabled — with the reason — where Knit will not place a slot
 * in the board's region, because a greyed action that says why is more use than one that fails at the board.
 *
 * Offered whether or not the board is already set up on the shared frequency: the session applies the slot
 * write to a board that already carries the Knit channel, so making the user Restore first would be a
 * limitation of this screen rather than of the thing underneath it. Once the board *is* on its slot
 * ([LoraRadioUiState.dedicated]) the button gives way to its reverse — one tap back onto the shared
 * frequency with the setup kept, so the way back is not Restore and a second setup — and the line says
 * where the board is and that the Meshtastic room is hidden while it lasts. Otherwise the line says which
 * slot the button would pin.
 */
@Composable
private fun DedicatedSetupAction(
    state: LoraRadioUiState,
    onAskSetupDedicated: () -> Unit,
    onSwitchToShared: () -> Unit,
) {
    if (state.dedicated) {
        OutlinedButton(
            onClick = onSwitchToShared,
            enabled = !state.provisioning,
            modifier = Modifier.testTag("lora_setup_shared"),
        ) {
            Text(stringResource(R.string.lora_setup_shared_button))
        }
    } else {
        OutlinedButton(
            onClick = onAskSetupDedicated,
            enabled = !state.provisioning && state.dedicatedSlot != null,
            modifier = Modifier.testTag("lora_setup_dedicated"),
        ) {
            Text(stringResource(R.string.lora_setup_dedicated_button))
        }
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
 * [dedicated] one does move the radio, and trades the free relaying away, so it says so instead.
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

/**
 * The bound MeshCore board's name on its network (ADR 2026-11.f4d3), on a board set up for Knit: the name every Public post
 * goes out under — the user's handle, or Knit's `Knit abcd` default — and, while the board is Ready, the way to change it.
 * A name another app wrote that the Public channel can't carry says so: Knit posts nothing there until it is renamed.
 */
@Composable
private fun MeshcoreNameSection(
    state: LoraRadioUiState,
    meshcore: MeshcoreRows,
    onAskRename: () -> Unit,
    onDismissNotice: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.lora_meshcore_name_title), style = MaterialTheme.typography.titleMedium)
        val handle = meshcore.handle
        if (state.connection == LoraConnState.Ready && handle != null) {
            Text(
                text = if (meshcore.namedDefault) stringResource(R.string.lora_meshcore_name_default, handle) else handle,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("lora_meshcore_name"),
            )
            if (meshcore.handleUnpostable) {
                Text(
                    text = stringResource(R.string.lora_meshcore_name_unpostable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("lora_meshcore_name_unpostable"),
                )
            }
            OutlinedButton(
                onClick = onAskRename,
                enabled = !state.provisioning && state.meshcoreRename == null,
                modifier = Modifier.testTag("lora_meshcore_rename"),
            ) {
                Text(stringResource(R.string.lora_meshcore_name_change))
            }
        } else {
            Text(
                text = stringResource(R.string.lora_meshcore_name_away),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("lora_meshcore_name_away"),
            )
        }
        state.meshcoreRenameNotice?.let { notice ->
            OutcomeLine(
                message =
                    when (notice) {
                        MeshcoreRenameNotice.Done -> R.string.lora_meshcore_rename_done
                        MeshcoreRenameNotice.Failed -> R.string.lora_meshcore_rename_failed
                        MeshcoreRenameNotice.NotReady -> R.string.lora_meshcore_rename_not_ready
                        MeshcoreRenameNotice.NotSetUp -> R.string.lora_meshcore_rename_not_set_up
                    },
                isError = notice != MeshcoreRenameNotice.Done,
                tag = "lora_meshcore_rename_notice",
                onDismiss = onDismissNotice,
            )
        }
    }
}

/**
 * Renaming the MeshCore board (ADR 2026-11.f4d3): the name, its size against the board's 31 bytes, why it can't be sent yet,
 * and where it goes — the board's Bluetooth name, its adverts and every Public post, all in the clear — with the position
 * line unless the board is known not to share one. Saving a name of the user's own covers a post consent already given.
 */
@Composable
private fun MeshcoreRenameDialog(
    rename: MeshcoreRenameUi,
    defaultName: String?,
    sharesPosition: Boolean?,
    onDraft: (String) -> Unit,
    onSave: () -> Unit,
    onUseDefault: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("lora_meshcore_rename_dialog"),
        title = { Text(stringResource(R.string.lora_meshcore_rename_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = rename.draft,
                    onValueChange = onDraft,
                    enabled = !rename.running,
                    singleLine = true,
                    label = { Text(stringResource(R.string.lora_meshcore_rename_label)) },
                    isError = rename.problem != null,
                    supportingText = {
                        Text(
                            text =
                                rename.problem?.let { stringResource(it.message()) }
                                    ?: stringResource(R.string.lora_meshcore_rename_counter, rename.bytes, MeshcoreNames.MAX_NAME_BYTES),
                            modifier = Modifier.testTag("lora_meshcore_rename_hint"),
                        )
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth().testTag("lora_meshcore_rename_field"),
                )
                Text(
                    text = stringResource(R.string.lora_meshcore_rename_disclosure),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("lora_meshcore_rename_disclosure"),
                )
                if (sharesPosition != false) {
                    Text(
                        text = stringResource(R.string.lora_meshcore_position),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("lora_meshcore_rename_position"),
                    )
                }
                if (defaultName != null) {
                    TextButton(
                        onClick = onUseDefault,
                        enabled = !rename.running,
                        modifier = Modifier.testTag("lora_meshcore_rename_default"),
                    ) {
                        Text(stringResource(R.string.lora_meshcore_rename_use_default, defaultName))
                    }
                }
                if (rename.running) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = !rename.running, modifier = Modifier.testTag("lora_meshcore_rename_save")) {
                Text(stringResource(R.string.lora_meshcore_rename_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !rename.running) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

private fun MeshcoreNameProblem.message(): Int =
    when (this) {
        MeshcoreNameProblem.Empty -> R.string.lora_meshcore_rename_empty
        MeshcoreNameProblem.TooLong -> R.string.lora_meshcore_rename_too_long
        MeshcoreNameProblem.Control -> R.string.lora_meshcore_rename_control
        MeshcoreNameProblem.Separator -> R.string.lora_meshcore_rename_separator
        MeshcoreNameProblem.Flagged -> R.string.moderation_name_refused
    }

/**
 * A MeshCore setup's confirmation (ADR 2026-11.gsqu's Stage 7 amendment): what Knit does to the board, that the erase can't
 * be undone, how much it erases once the board has said, and — unless the board is known not to — that the board may share
 * where it is, which Knit's setup ties to the user. Scrolls: the lines can outgrow a small screen.
 */
@Composable
private fun MeshcoreSetupConfirmDialog(
    erase: MeshcoreErase?,
    /** Whether this setup renames the board `Knit abcd`: not a re-setup, which keeps the name the board carries. */
    renames: Boolean = true,
    eraseLoading: Boolean,
    sharesPosition: Boolean?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("lora_setup_dialog"),
        title = { Text(stringResource(R.string.lora_setup_confirm_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(
                        if (renames) R.string.lora_meshcore_setup_confirm_body else R.string.lora_meshcore_setup_confirm_body_keeps_name,
                    ),
                )
                Text(
                    text = stringResource(R.string.lora_meshcore_setup_erase),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("lora_meshcore_setup_erase"),
                )
                erase?.contacts?.takeIf { it > 0 }?.let { contacts ->
                    val n = contacts.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    Text(
                        text = pluralStringResource(R.plurals.lora_meshcore_erase_contacts, n, n),
                        modifier = Modifier.testTag("lora_meshcore_erase_contacts"),
                    )
                }
                erase?.slots?.takeIf { it > 0 }?.let { slots ->
                    Text(
                        text = pluralStringResource(R.plurals.lora_meshcore_erase_channels, slots, slots),
                        modifier = Modifier.testTag("lora_meshcore_erase_channels"),
                    )
                }
                if (eraseLoading) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                if (sharesPosition != false) {
                    Text(
                        text = stringResource(R.string.lora_meshcore_position),
                        modifier = Modifier.testTag("lora_meshcore_position"),
                    )
                }
            }
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

/** A MeshCore setup found another board's record at this address (`ProvisionResult.DifferentBoard`): set it up over it? */
@Composable
private fun MeshcoreReplaceDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("lora_meshcore_replace_dialog"),
        title = { Text(stringResource(R.string.lora_meshcore_replace_title)) },
        text = { Text(stringResource(R.string.lora_meshcore_replace_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("lora_meshcore_replace_confirm")) {
                Text(stringResource(R.string.lora_meshcore_replace_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * The MeshCore Forget could not clean the board now (`MeshcoreForgetResult.PENDING`): keeping it bound lets the next connection
 * clean it, which is the default; forgetting it anyway leaves the board as it is.
 */
@Composable
private fun MeshcoreForgetPendingDialog(
    onKeep: () -> Unit,
    onForgetAnyway: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onKeep,
        modifier = Modifier.testTag("lora_meshcore_forget_dialog"),
        title = { Text(stringResource(R.string.lora_meshcore_forget_pending_title)) },
        text = { Text(stringResource(R.string.lora_meshcore_forget_pending_body)) },
        confirmButton = {
            TextButton(onClick = onKeep, modifier = Modifier.testTag("lora_meshcore_forget_keep")) {
                Text(stringResource(R.string.lora_meshcore_forget_keep))
            }
        },
        dismissButton = {
            TextButton(onClick = onForgetAnyway, modifier = Modifier.testTag("lora_meshcore_forget_anyway")) {
                Text(stringResource(R.string.lora_meshcore_forget_anyway))
            }
        },
    )
}

private fun LoraProvisionOutcome.messageAndSeverity(): Pair<Int, Boolean> =
    when (this) {
        LoraProvisionOutcome.Provisioned -> R.string.lora_provisioned to false
        LoraProvisionOutcome.AlreadyPresent -> R.string.lora_provision_already to false
        LoraProvisionOutcome.Restored -> R.string.lora_restored to false
        LoraProvisionOutcome.Shared -> R.string.lora_provision_shared to false
        LoraProvisionOutcome.NoFreeSlot -> R.string.lora_provision_no_slot to true
        LoraProvisionOutcome.NoDedicatedSlot -> R.string.lora_provision_no_dedicated_slot to true
        LoraProvisionOutcome.Failed -> R.string.lora_provision_failed to true
        LoraProvisionOutcome.NotReady -> R.string.lora_provision_not_ready to true
    }

@Composable
private fun StatusRow(state: LoraRadioUiState) {
    val label =
        when (state.connection) {
            LoraConnState.Ready -> {
                stringResource(R.string.lora_status_connected)
            }

            LoraConnState.Connecting -> {
                stringResource(R.string.lora_status_connecting)
            }

            LoraConnState.Reconnecting -> {
                stringResource(R.string.lora_status_reconnecting)
            }

            LoraConnState.NeedsPairing -> {
                when {
                    !state.wrongFirmware -> stringResource(R.string.lora_status_needs_pairing)
                    state.meshcore != null -> stringResource(R.string.lora_status_wrong_firmware_meshcore)
                    else -> stringResource(R.string.lora_status_wrong_firmware)
                }
            }

            LoraConnState.Unavailable -> {
                stringResource(R.string.lora_status_bt_off)
            }

            LoraConnState.Off -> {
                stringResource(R.string.lora_status_off)
            }
        }
    Column(modifier = Modifier.fillMaxWidth().testTag("lora_status")) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        val meshcore = state.meshcore
        if (meshcore != null) {
            Text(
                text = stringResource(R.string.lora_meshcore_kind),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("lora_meshcore_kind"),
            )
        }
        if (state.connection == LoraConnState.Ready) {
            val detail = MaterialTheme.typography.bodySmall
            val muted = MaterialTheme.colorScheme.onSurfaceVariant
            state.boardNodeNum?.let { Text(it, style = detail, color = muted) }
            state.firmware?.let { Text(stringResource(R.string.lora_firmware, it), style = detail, color = muted) }
            if (meshcore != null) MeshcoreStatusRows(meshcore = meshcore, boardsHeard = state.boardsHeard)
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
            if (meshcore == null) {
                Text(
                    text = countText(R.plurals.lora_boards_heard, R.string.lora_boards_heard_zero, state.boardsHeard),
                    style = detail,
                    color = muted,
                    modifier = Modifier.testTag("lora_boards_heard"),
                )
            }
            if (meshcore == null && state.heard > 0 && state.heard != state.boardsHeard) {
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
        }
    }
}

/**
 * A Ready MeshCore board's own rows (ADR 2026-11.gsqu's Stage 7 amendment): its radio as the board reports it, whether it
 * floods under a default region scope, its battery voltage (MeshCore reports no percent), and the Knit radios heard — on
 * MeshCore, the authors of what reached the board, since a post names no airing radio.
 */
@Composable
private fun MeshcoreStatusRows(
    meshcore: MeshcoreRows,
    boardsHeard: Int,
) {
    val detail = MaterialTheme.typography.bodySmall
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val locale = LocalConfiguration.current.locales[0]
    meshcore.radio()?.let { radio ->
        val number = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = KHZ_DIGITS }
        Text(
            text =
                stringResource(
                    R.string.lora_meshcore_radio,
                    number.format(radio.freqKhz / KHZ_PER_MHZ),
                    radio.spreadFactor,
                    number.format(radio.bandwidthHz / HZ_PER_KHZ),
                    radio.codingRate,
                ),
            style = detail,
            color = muted,
            modifier = Modifier.testTag("lora_meshcore_radio"),
        )
    }
    meshcore.defaultScope?.let { scoped ->
        Text(
            text = stringResource(if (scoped) R.string.lora_meshcore_scope_set else R.string.lora_meshcore_scope_none),
            style = detail,
            color = muted,
            modifier = Modifier.testTag("lora_meshcore_scope"),
        )
    }
    meshcore.batteryMv?.let { mv ->
        Text(
            text = stringResource(R.string.lora_meshcore_battery, mv / MV_PER_V),
            style = detail,
            color = muted,
            modifier = Modifier.testTag("lora_battery"),
        )
    }
    Text(
        text = countText(R.plurals.lora_meshcore_knit_radios, R.string.lora_meshcore_knit_radios_zero, boardsHeard),
        style = detail,
        color = muted,
        modifier = Modifier.testTag("lora_boards_heard"),
    )
}

/** The board's radio line, only once every part of it is known. */
private class MeshcoreRadio(
    val freqKhz: Long,
    val spreadFactor: Int,
    val bandwidthHz: Int,
    val codingRate: Int,
)

private fun MeshcoreRows.radio(): MeshcoreRadio? {
    val freq = freqKhz ?: return null
    val sf = spreadFactor ?: return null
    val bw = bandwidthHz ?: return null
    val cr = codingRate ?: return null
    return MeshcoreRadio(freq, sf, bw, cr)
}

private const val KHZ_DIGITS = 3
private const val KHZ_PER_MHZ = 1_000.0
private const val HZ_PER_KHZ = 1_000.0
private const val MV_PER_V = 1_000f

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
fun LoraRadioScreenPreview() =
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

/**
 * The MeshCore name row on a board set up for Knit (ADR 2026-11.f4d3), alone so it is not below the fold: a name of the
 * user's own, Knit's default, and a name another app wrote that the Public channel cannot carry.
 */
@Preview(showBackground = true)
@Composable
fun LoraRadioMeshcoreNamePreview() =
    KnitPreview {
        val state =
            LoraRadioUiState(
                enabled = true,
                meshcoreBound = true,
                connection = LoraConnState.Ready,
                boardSetUp = true,
                anyKind = true,
            )
        val rows = MeshcoreRows(setup = MeshcoreSetup.SetUp, handle = "Sam 🧶", defaultName = "Knit 312b")
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            MeshcoreNameSection(state = state, meshcore = rows, onAskRename = {}, onDismissNotice = {})
            MeshcoreNameSection(
                state = state,
                meshcore = rows.copy(handle = "Knit 312b", namedDefault = true),
                onAskRename = {},
                onDismissNotice = {},
            )
            MeshcoreNameSection(
                state = state,
                meshcore = rows.copy(handle = "Sam: hi", handleUnpostable = true),
                onAskRename = {},
                onDismissNotice = {},
            )
        }
    }

@Preview(showBackground = true)
@Composable
fun MeshcoreRenameDialogPreview() =
    KnitPreview {
        MeshcoreRenameDialog(
            rename = MeshcoreRenameUi(draft = "Knit 312b", bytes = 9),
            defaultName = "Knit 312b",
            sharesPosition = false,
            onDraft = {},
            onSave = {},
            onUseDefault = {},
            onDismiss = {},
        )
    }

/** A name of the user's own on a board that may share where it is: the position line joins the disclosure. */
@Preview(showBackground = true)
@Composable
fun MeshcoreRenameDialogPositionPreview() =
    KnitPreview {
        MeshcoreRenameDialog(
            rename = MeshcoreRenameUi(draft = "Sam 🧶", bytes = 8),
            defaultName = "Knit 312b",
            sharesPosition = true,
            onDraft = {},
            onSave = {},
            onUseDefault = {},
            onDismiss = {},
        )
    }

@Preview(showBackground = true)
@Composable
fun MeshcoreRenameDialogTooLongPreview() =
    KnitPreview {
        MeshcoreRenameDialog(
            rename =
                MeshcoreRenameUi(
                    draft = "Riverside Community Radio Club 2",
                    bytes = 32,
                    problem = MeshcoreNameProblem.TooLong,
                ),
            defaultName = "Knit 312b",
            sharesPosition = false,
            onDraft = {},
            onSave = {},
            onUseDefault = {},
            onDismiss = {},
        )
    }

@Preview(showBackground = true)
@Composable
fun MeshcoreRenameDialogFlaggedPreview() =
    KnitPreview {
        MeshcoreRenameDialog(
            rename = MeshcoreRenameUi(draft = "Radio Rex", bytes = 9, problem = MeshcoreNameProblem.Flagged),
            defaultName = "Knit 312b",
            sharesPosition = false,
            onDraft = {},
            onSave = {},
            onUseDefault = {},
            onDismiss = {},
        )
    }
