package app.getknit.knit.ui.diagnostics

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.getknit.knit.R
import app.getknit.knit.call.CaptureChoice
import app.getknit.knit.call.CaptureProfile
import app.getknit.knit.call.CaptureReason
import app.getknit.knit.crash.CrashReportRef
import app.getknit.knit.di.DEV_VIEW_WIRED
import app.getknit.knit.mesh.LinkSecurity
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.PlaneSupport
import app.getknit.knit.mesh.RadioSupport
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.mesh.TransportKind
import app.getknit.knit.mesh.TransportStatus
import app.getknit.knit.mesh.bluetooth.CodedPhyMode
import app.getknit.knit.mesh.bluetooth.LinkPhy
import app.getknit.knit.mesh.bluetooth.PromotionConfig
import app.getknit.knit.mesh.lora.LoraPlane
import app.getknit.knit.mesh.spool.SpoolStatus
import app.getknit.knit.mesh.spool.SpoolUrl
import app.getknit.knit.transfer.HostVerdict
import app.getknit.knit.ui.DeviceSupervision
import app.getknit.knit.ui.Reach
import app.getknit.knit.ui.components.AdaptiveRow
import app.getknit.knit.ui.components.BarTitle
import app.getknit.knit.ui.components.ChoiceButtons
import app.getknit.knit.ui.components.SectionHeader
import app.getknit.knit.ui.deviceSupervision
import app.getknit.knit.ui.preview.KnitPreview
import app.getknit.knit.ui.preview.PREVIEW_NOW
import app.getknit.knit.ui.rememberOnResume
import app.getknit.knit.ui.theme.knitColors
import app.getknit.knit.ui.util.compactTimeAgo
import app.getknit.knit.ui.util.countText
import app.getknit.knit.ui.util.relativeTimeLabels
import app.getknit.knit.ui.util.rememberCurrentTimeMillis
import org.koin.androidx.compose.koinViewModel
import java.io.File

/**
 * Mesh diagnostics: this device's identity, the live mesh metrics, and the known nodes split by how strong the
 * evidence for reaching them is — directly connected, reachable through a relay, or merely known (see [Reach]).
 * A merely known node can be selected with a long-press and hidden from this list (ADR 2026-11.b37x), which
 * changes nothing but this screen; everything else here only reads. Reached from the chat-list overflow menu.
 */
@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    onOpenCrashLog: () -> Unit,
    onOpenFieldLog: () -> Unit,
    viewModel: DiagnosticsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val health by viewModel.health.collectAsStateWithLifecycle()
    val lastCrash by viewModel.lastCrash.collectAsStateWithLifecycle()
    val moderationLatched by viewModel.moderationLatched.collectAsStateWithLifecycle()
    val bleLinkCap by viewModel.bleLinkCap.collectAsStateWithLifecycle()
    val nanOff by viewModel.nanOff.collectAsStateWithLifecycle()
    val wifiAwareOn by viewModel.wifiAwareOn.collectAsStateWithLifecycle()
    val directCallsOff by viewModel.directCallsOff.collectAsStateWithLifecycle()
    val walkieClips by viewModel.walkieClips.collectAsStateWithLifecycle()
    val devView by viewModel.devView.collectAsStateWithLifecycle()
    val directHost by viewModel.directHost.collectAsStateWithLifecycle()
    val callCapture by viewModel.callCapture.collectAsStateWithLifecycle()
    val blePhyMode by viewModel.blePhyMode.collectAsStateWithLifecycle()
    val blePhys by viewModel.blePhys.collectAsStateWithLifecycle()
    val presence by viewModel.presence.collectAsStateWithLifecycle()
    val advertDebug by viewModel.advertDebugInfo.collectAsStateWithLifecycle()
    var confirmingModerationReset by remember { mutableStateOf(false) }
    // The Known rows picked for hiding, kept across a rotation. Read through [state]'s shown rows, so a node that became
    // reachable since it was picked leaves the selection on its own (ADR 2026-11.b37x).
    var picked by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val selection =
        state.hideableKnown.keys
            .filter { it in picked }
            .toSet()
    BackHandler(enabled = selection.isNotEmpty()) { picked = emptyList() }
    // Inside a NavHost composable the lifecycle owner is this back-stack entry, so this fires again when
    // the crash screen pops — which is how deleting the report over there clears this row over here.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshLastCrash()
        onPauseOrDispose { }
    }
    // A ticking clock so each node's "profile updated N ago" label recomposes as time passes; a bare
    // System.currentTimeMillis() read would freeze at first composition (see rememberCurrentTimeMillis).
    val now by rememberCurrentTimeMillis()
    val context = LocalContext.current
    val supervision = rememberOnResume { deviceSupervision(context) }

    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalResources.current
    LaunchedEffect(resources) {
        viewModel.hid.collect { ids ->
            val result =
                snackbarHostState.showSnackbar(
                    message = resources.getQuantityString(R.plurals.diagnostics_hidden, ids.size, ids.size),
                    actionLabel = resources.getString(R.string.action_undo),
                    duration = SnackbarDuration.Short,
                )
            if (result == SnackbarResult.ActionPerformed) viewModel.unhideKnown(ids)
        }
    }
    // Resolve the action-feedback strings at composition (lint forbids LocalContext.getString here),
    // then map the emitted resource id to the matching message.
    val restartedMsg = stringResource(R.string.diagnostics_mesh_restarted)
    val scanningMsg = stringResource(R.string.diagnostics_scanning)
    val moderationResetMsg = stringResource(R.string.diagnostics_moderation_reset_done)
    val holdReleasedMsg = stringResource(R.string.diagnostics_nan_hold_retry_done)
    val hostResetMsg = stringResource(R.string.diagnostics_direct_host_reset_done)
    val captureResetMsg = stringResource(R.string.diagnostics_call_capture_reset_done)
    LaunchedEffect(restartedMsg, scanningMsg, moderationResetMsg, holdReleasedMsg, hostResetMsg, captureResetMsg) {
        viewModel.events.collect { resId ->
            snackbarHostState.showSnackbar(
                when (resId) {
                    R.string.diagnostics_mesh_restarted -> restartedMsg
                    R.string.diagnostics_moderation_reset_done -> moderationResetMsg
                    R.string.diagnostics_nan_hold_retry_done -> holdReleasedMsg
                    R.string.diagnostics_direct_host_reset_done -> hostResetMsg
                    R.string.diagnostics_call_capture_reset_done -> captureResetMsg
                    else -> scanningMsg
                },
            )
        }
    }

    DiagnosticsScreenContent(
        state = state,
        health = health,
        lastCrash = lastCrash,
        moderationLatched = moderationLatched,
        now = now,
        supervision = supervision,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onRestartMesh = viewModel::restartMesh,
        onScan = viewModel::rescan,
        onOpenCrashLog = onOpenCrashLog,
        onOpenFieldLog = onOpenFieldLog,
        onResetModeration = { confirmingModerationReset = true },
        onReleaseInitiatorHold = viewModel::releaseInitiatorHold,
        bleLinkCapOffered = viewModel.bleLinkCapOffered,
        bleLinkCap = bleLinkCap,
        onSetBleLinkCap = viewModel::setBleLinkCap,
        nanSwitchOffered = viewModel.nanSwitchOffered,
        nanOff = nanOff,
        onSetNanOff = viewModel::setNanOff,
        wifiAwareOff = !wifiAwareOn,
        directCallsSwitchOffered = viewModel.directCallsSwitchOffered,
        directCallsOff = directCallsOff,
        onSetDirectCallsOff = viewModel::setDirectCallsOff,
        walkieClipsOffered = viewModel.walkieClipsOffered,
        walkieClips = walkieClips,
        onSetWalkieClips = viewModel::setWalkieClips,
        devViewOffered = viewModel.devViewOffered,
        devView = devView,
        onSetDevView = viewModel::setDevView,
        directHost = directHost,
        onResetDirectHost = viewModel::resetDirectHost,
        callCapture = callCapture,
        onResetCallCapture = viewModel::resetCallCapture,
        blePhyMode = blePhyMode,
        onSetBlePhyMode = viewModel::setBlePhyMode,
        blePhys = blePhys,
        presence = presence,
        advertDebug = advertDebug,
        selected = selection,
        onToggleSelected = { id -> picked = if (id in selection) (selection - id).toList() else (selection + id).toList() },
        onClearSelection = { picked = emptyList() },
        onHideSelected = {
            viewModel.hideKnown(selection)
            picked = emptyList()
        },
        onHideAll = viewModel::hideAllKnown,
        onShowHidden = viewModel::showHiddenKnown,
    )

    if (confirmingModerationReset) {
        ConfirmDialog(
            title = R.string.diagnostics_moderation_reset_title,
            body = R.string.diagnostics_moderation_reset_body,
            confirm = R.string.diagnostics_moderation_reset_confirm,
            onConfirm = {
                confirmingModerationReset = false
                viewModel.resetModerationLatch()
            },
            onDismiss = { confirmingModerationReset = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DiagnosticsScreenContent(
    state: DiagnosticsUiState,
    health: TransportHealth,
    lastCrash: CrashReportRef?,
    moderationLatched: Boolean,
    now: Long,
    snackbarHostState: SnackbarHostState,
    // Who else administers this phone (ADR 2026-09.a8ud); read in the stateful wrapper like `now`.
    supervision: DeviceSupervision = DeviceSupervision.None,
    onBack: () -> Unit,
    onRestartMesh: () -> Unit,
    onScan: () -> Unit,
    onOpenCrashLog: () -> Unit,
    onResetModeration: () -> Unit,
    // The field log's screen (ADR 2026-10.a239). Defaulted: previews and screen tests that never open it.
    onOpenFieldLog: () -> Unit = {},
    // Diagnostics' "Try again" for a Wi-Fi Aware plane holding its initiator role (ADR 2026-09.m8kc). Defaulted:
    // the section only renders when a transport row says so, and most callers describe phones that never do.
    onReleaseInitiatorHold: () -> Unit = {},
    // The debug-only Bluetooth link limit. Defaulted off: a release build never offers it, and neither do the
    // screen's other callers.
    bleLinkCapOffered: Boolean = false,
    bleLinkCap: Int? = null,
    onSetBleLinkCap: (Int) -> Unit = {},
    // The debug-only Wi-Fi Aware switch, defaulted off for the same reason: [nanOff] true means the plane is stopped.
    nanSwitchOffered: Boolean = false,
    nanOff: Boolean = false,
    onSetNanOff: (Boolean) -> Unit = {},
    // The person's Settings › Wi-Fi Aware switch is off (GitHub #44): that row's switched-off tag names it.
    wifiAwareOff: Boolean = false,
    // The debug-only Wi-Fi Direct calls switch, defaulted off likewise: [directCallsOff] true sends calls over ICE.
    directCallsSwitchOffered: Boolean = false,
    directCallsOff: Boolean = false,
    onSetDirectCallsOff: (Boolean) -> Unit = {},
    // The debug-only walkie clips switch, defaulted off likewise: [walkieClips] true saves each burst for the speech eval.
    walkieClipsOffered: Boolean = false,
    walkieClips: Boolean = false,
    onSetWalkieClips: (Boolean) -> Unit = {},
    // The developer view's switch (ADR 2026-10.s7wg), defaulted off likewise: [devView] true shows the inspector and HUD.
    devViewOffered: Boolean = false,
    devView: Boolean = false,
    onSetDevView: (Boolean) -> Unit = {},
    // Whether this phone may host a Wi-Fi Direct group (ADR 2026-10.w3dw); the row shows only when it may not.
    directHost: HostVerdict = HostVerdict.Allowed,
    onResetDirectHost: () -> Unit = {},
    // Which microphone path calls record from (ADR 2026-10.kw29); null, or the standard path by default, hides the row.
    callCapture: CaptureChoice? = null,
    onResetCallCapture: () -> Unit = {},
    // Long-range Bluetooth's debug mode (ADR 2026-10.yvn6); null (a release build, one that keeps it dark, and every
    // other caller) hides the row.
    blePhyMode: CodedPhyMode? = null,
    onSetBlePhyMode: (CodedPhyMode) -> Unit = {},
    // The PHY each Bluetooth link with a PHY handle is on, by node id: a chip on its directly-connected row. Empty
    // (no chips) where the build keeps long-range Bluetooth dark.
    blePhys: Map<String, LinkPhy> = emptyMap(),
    // The presence-key facts (ADR 2026-11.xp6d): the line under This device, the Key chips and the "held" metric.
    presence: PresenceKeyInfo = PresenceKeyInfo(),
    // A debug build's rotating-ID reader counts; null (a release build, the plane down, every other caller) hides the row.
    advertDebug: AdvertDebugInfo? = null,
    // The Known rows picked for hiding (ADR 2026-11.b37x); non-empty, the top bar turns into the selection's.
    selected: Set<String> = emptySet(),
    onToggleSelected: (String) -> Unit = {},
    onClearSelection: () -> Unit = {},
    onHideSelected: () -> Unit = {},
    onHideAll: () -> Unit = {},
    onShowHidden: () -> Unit = {},
) {
    Scaffold(
        modifier = Modifier.testTag("screen_diagnostics"),
        topBar = {
            if (selected.isEmpty()) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.action_back),
                            )
                        }
                    },
                    title = { BarTitle(stringResource(R.string.diagnostics_title)) },
                )
            } else {
                SelectionTopBar(count = selected.size, onClear = onClearSelection, onHide = onHideSelected)
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                SelfSection(
                    name = state.myName,
                    nodeId = state.myNodeId,
                    supervision = supervision,
                    presenceKeyHeld = presence.ownKeyHeld,
                )
            }

            // What a user is asked to send, above everything else: "open Diagnostics and send me the crash" or "the field
            // log" should not mean scrolling past the node lists and forty metric rows. The field log row is always here;
            // the crash and latch rows only when there is one — a native crash captures no report (the premise of the
            // poison-pill it latched), so a latched phone very often has the latch and no crash.
            item { SectionHeader(stringResource(R.string.diagnostics_reports)) }
            if (lastCrash != null) {
                item { CrashRow(crash = lastCrash, now = now, onClick = onOpenCrashLog) }
            }
            if (moderationLatched) {
                item { ModerationLatchSection(onReset = onResetModeration) }
            }
            item { FieldLogRow(onClick = onOpenFieldLog) }

            item { SectionHeader(stringResource(R.string.diagnostics_controls)) }
            item {
                MeshControlsSection(
                    health = health,
                    radios = state.radios,
                    onRestart = onRestartMesh,
                    onScan = onScan,
                )
            }

            item { SectionHeader(stringResource(R.string.diagnostics_transports)) }
            item { TransportsSection(state.transports, nanOff = nanSwitchOffered && nanOff, wifiAwareOff = wifiAwareOff) }
            // Under the rows they explain, in every build: a plane holding its initiator role, a phone that may not host
            // a Wi-Fi Direct group, a call microphone off its standard path. Each comes with its way back.
            if (state.transports.any { it is TransportRow.Live && it.status.initiatorHeld }) {
                item { InitiatorHoldSection(onRelease = onReleaseInitiatorHold) }
            }
            if (directHost != HostVerdict.Allowed) {
                item { DirectHostSection(verdict = directHost, onReset = onResetDirectHost) }
            }
            callCapture?.reason?.let { reason ->
                item { CallCaptureSection(profile = callCapture.profile, reason = reason, onReset = onResetCallCapture) }
            }

            // Who this phone reaches, strongest evidence first, straight under the planes that reach them.
            item {
                SectionHeader(
                    stringResource(R.string.diagnostics_directly_connected, state.directNodes.size),
                )
            }
            if (state.directNodes.isEmpty()) {
                item { EmptyLine(stringResource(R.string.diagnostics_none_direct)) }
            } else {
                items(state.directNodes, key = { it.nodeId }) {
                    NodeRow(it, now, phy = blePhys[it.nodeId], keyHeld = it.nodeId in presence.keyHolders)
                }
            }

            item {
                SectionHeader(stringResource(R.string.diagnostics_indirect, state.indirectNodes.size))
            }
            if (state.indirectNodes.isEmpty()) {
                item { EmptyLine(stringResource(R.string.diagnostics_none_indirect)) }
            } else {
                items(state.indirectNodes, key = { it.nodeId }) { NodeRow(it, now, keyHeld = it.nodeId in presence.keyHolders) }
            }

            item {
                SectionHeader(stringResource(R.string.diagnostics_via_relay, state.longRangeNodes.size))
            }
            if (state.longRangeNodes.isEmpty()) {
                item { EmptyLine(stringResource(R.string.diagnostics_none_relay)) }
            } else {
                items(state.longRangeNodes, key = { it.nodeId }) { NodeRow(it, now, keyHeld = it.nodeId in presence.keyHolders) }
            }

            // Everyone else we hold a profile for. Capped: this is the peer table, and listing all of it was
            // exactly what made the old "reachable via relay" section meaningless.
            // Hidden rows still count here: with every row hidden, the section stays for its Show hidden button.
            if (state.knownTotal > 0 || state.hiddenKnownCount > 0) {
                item {
                    SectionHeader(stringResource(R.string.diagnostics_known_nodes, state.knownTotal))
                }
                items(state.knownNodes, key = { it.nodeId }) { node ->
                    if (state.hidingOffered) {
                        SelectableNodeRow(
                            node = node,
                            now = now,
                            selecting = selected.isNotEmpty(),
                            selected = node.nodeId in selected,
                            keyHeld = node.nodeId in presence.keyHolders,
                            onToggle = { onToggleSelected(node.nodeId) },
                        )
                    } else {
                        NodeRow(node, now, keyHeld = node.nodeId in presence.keyHolders)
                    }
                }
                val more = state.knownTotal - state.knownNodes.size
                if (more > 0) {
                    item { EmptyLine(pluralStringResource(R.plurals.diagnostics_known_more, more, more)) }
                }
                if (state.hidingOffered && selected.isEmpty()) {
                    item { KnownActions(state.hideableKnown.size, state.hiddenKnownCount, onHideAll, onShowHidden) }
                }
            }

            // Shown only while the Internet plane is actually running, so a mesh-only install (the default) never sees an
            // empty section. Below the node lists: the relay's own state matters less than who it reaches.
            if (state.spools.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.diagnostics_spools)) }
                items(state.spools, key = { it.url }) { SpoolSection(it) }
            }

            item { SectionHeader(stringResource(R.string.diagnostics_metrics)) }
            item { MetricsSection(state.metrics, presence) }

            // A debug build's switches, together at the foot, so they never sit between the planes and who they reach.
            val btLive = state.transports.any { it is TransportRow.Live && it.kind == TransportKind.Bluetooth }
            val awareLive = state.transports.any { it is TransportRow.Live && it.kind == TransportKind.WifiAware }
            // The constant first, so R8 drops the dev view's row from a build without the view (ADR 2026-10.s7wg).
            val devViewShown = DEV_VIEW_WIRED && devViewOffered
            val debugOffered =
                devViewShown ||
                    (bleLinkCapOffered && btLive) ||
                    (nanSwitchOffered && awareLive) ||
                    directCallsSwitchOffered ||
                    walkieClipsOffered ||
                    (blePhyMode != null && btLive) ||
                    advertDebug != null
            if (debugOffered) {
                item { SectionHeader(stringResource(R.string.diagnostics_debug_section)) }
            }
            if (devViewShown) {
                item {
                    DebugSwitchRow(
                        label = R.string.diagnostics_dev_view_label,
                        body = R.string.diagnostics_dev_view_body,
                        tag = "dev_view_switch",
                        off = !devView,
                        onSet = { off -> onSetDevView(!off) },
                    )
                }
            }
            if (bleLinkCapOffered && btLive) {
                item { BleLinkCapRow(cap = bleLinkCap, onSet = onSetBleLinkCap) }
            }
            if (blePhyMode != null && btLive) {
                item { BlePhyModeRow(mode = blePhyMode, onSet = onSetBlePhyMode) }
            }
            if (nanSwitchOffered && awareLive) {
                item {
                    DebugSwitchRow(
                        label = R.string.diagnostics_nan_switch_label,
                        body = R.string.diagnostics_nan_switch_body,
                        tag = "nan_switch",
                        off = nanOff,
                        onSet = onSetNanOff,
                    )
                }
            }
            // Not tied to a transport row: a call's direct leg raises its own Wi-Fi Direct group.
            if (directCallsSwitchOffered) {
                item {
                    DebugSwitchRow(
                        label = R.string.diagnostics_direct_calls_switch_label,
                        body = R.string.diagnostics_direct_calls_switch_body,
                        tag = "direct_calls_switch",
                        off = directCallsOff,
                        onSet = onSetDirectCallsOff,
                    )
                }
            }
            if (walkieClipsOffered) {
                item {
                    DebugSwitchRow(
                        label = R.string.diagnostics_walkie_clips_label,
                        body = R.string.diagnostics_walkie_clips_body,
                        tag = "walkie_clips_switch",
                        off = !walkieClips,
                        onSet = { off -> onSetWalkieClips(!off) },
                    )
                }
            }
            advertDebug?.let { info -> item { AdvertDebugSection(info) } }
        }
    }
}

@Composable
private fun SelfSection(
    name: String,
    nodeId: String,
    supervision: DeviceSupervision = DeviceSupervision.None,
    // Whether this phone holds a presence key of its own (ADR 2026-11.xp6d): never the key, its generation or its age.
    presenceKeyHeld: Boolean = false,
) {
    SectionHeader(stringResource(R.string.diagnostics_self))
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(text = name, style = MaterialTheme.typography.titleMedium)
        Text(
            text = stringResource(R.string.profile_node_id, nodeId),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Only on a phone somebody else administers — the line a "why is this permission greyed out"
        // report needs, and nothing on the plain phone.
        val supervisionLabel =
            when (supervision) {
                DeviceSupervision.None -> null
                DeviceSupervision.FamilyLink -> R.string.diagnostics_supervised_family_link
                DeviceSupervision.Managed -> R.string.diagnostics_supervised_managed
            }
        if (supervisionLabel != null) {
            Text(
                text = stringResource(supervisionLabel),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("diagnostics_supervised"),
            )
        }
        Text(
            text =
                stringResource(
                    if (presenceKeyHeld) R.string.diagnostics_presence_key_held else R.string.diagnostics_presence_key_none,
                ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("diagnostics_presence_key"),
        )
    }
}

@Composable
private fun MeshControlsSection(
    health: TransportHealth,
    radios: RadioSupport,
    onRestart: () -> Unit,
    onScan: () -> Unit,
) {
    // Status line: distinguish Healthy, ForegroundOnly (the Wi-Fi search refused off screen, ADR 2026-09.535d),
    // Degraded (on but seized), and Unavailable (radios switched off).
    val statusRes =
        when (health) {
            TransportHealth.Healthy -> R.string.diagnostics_status_healthy
            TransportHealth.ForegroundOnly -> R.string.diagnostics_status_foreground_only
            TransportHealth.Degraded -> R.string.diagnostics_status_degraded
            TransportHealth.Unavailable -> R.string.diagnostics_status_unavailable
        }
    // The off hint names the radio this phone actually has: "turn on Wi-Fi or Bluetooth" on a phone with no
    // Wi-Fi Aware would contradict the Transports row a few lines down.
    val hintRes =
        when (health) {
            TransportHealth.Healthy -> null
            TransportHealth.ForegroundOnly -> R.string.diagnostics_status_foreground_only_hint
            TransportHealth.Degraded -> R.string.diagnostics_status_degraded_hint
            TransportHealth.Unavailable -> unavailableHintFor(radios)
        }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            text = stringResource(statusRes),
            style = MaterialTheme.typography.bodyMedium,
            color =
                when (health) {
                    TransportHealth.Healthy -> MaterialTheme.colorScheme.onSurface

                    // The OS's rule, not a fault: the muted colour a switched-off radio's hint gets.
                    TransportHealth.ForegroundOnly -> MaterialTheme.colorScheme.onSurfaceVariant

                    TransportHealth.Degraded, TransportHealth.Unavailable -> MaterialTheme.colorScheme.error
                },
        )
        if (hintRes != null) {
            Text(
                text = stringResource(hintRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onRestart, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.RestartAlt, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.diagnostics_restart_mesh))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Sync, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.diagnostics_scan_now))
        }
    }
}

@Composable
private fun MetricsSection(
    metrics: MeshMetrics.Snapshot,
    presence: PresenceKeyInfo = PresenceKeyInfo(),
) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        MetricRow(stringResource(R.string.diagnostics_metric_originated), metrics.frames.framesOriginated.toString())
        MetricRow(stringResource(R.string.diagnostics_metric_delivered), metrics.frames.framesDelivered.toString())
        MetricRow(stringResource(R.string.diagnostics_metric_relayed), metrics.frames.framesRelayed.toString())
        MetricRow(stringResource(R.string.diagnostics_metric_handed_on), metrics.frames.framesHandedOn.toString())
        MetricRow(stringResource(R.string.diagnostics_metric_suppressed), metrics.frames.framesSuppressed.toString())
        MetricRow(stringResource(R.string.diagnostics_metric_deduped), metrics.frames.framesDeduped.toString())
        MetricRow(
            stringResource(R.string.diagnostics_metric_bytes_sent),
            Formatter.formatShortFileSize(context, metrics.frames.bytesSent),
        )
        // Inbound drops are normally zero; surface a total plus a per-reason breakdown only when any
        // occur, so a staged rollout can spot a version causing frames to be discarded.
        if (metrics.frames.framesDropped > 0) {
            MetricRow(stringResource(R.string.diagnostics_metric_dropped), metrics.frames.framesDropped.toString())
            metrics.frames.dropsByReason.forEach { (reason, count) ->
                MetricRow(SUB_ROW_INDENT + reason.name, count.toString())
            }
        }
        // Links between phones (ADR 2026-11.264w, 2026-11.8mhs): shown once any link was proven or sealed. A seal that
        // failed, or a plain link refused between two sealing builds, is the "investigate" pair, so each shows only when
        // it happened.
        if (metrics.frames.linksSealed > 0 || metrics.frames.linksProven > 0) {
            MetricRow(stringResource(R.string.diagnostics_metric_links_sealed), metrics.frames.linksSealed.toString())
            if (metrics.frames.linkSealFailed > 0) {
                MetricRow(
                    SUB_ROW_INDENT + stringResource(R.string.diagnostics_metric_link_seal_failed),
                    metrics.frames.linkSealFailed.toString(),
                )
            }
            if (metrics.frames.linkSealRefused > 0) {
                MetricRow(
                    SUB_ROW_INDENT + stringResource(R.string.diagnostics_metric_link_seal_refused),
                    metrics.frames.linkSealRefused.toString(),
                )
            }
            MetricRow(stringResource(R.string.diagnostics_metric_links_proven), metrics.frames.linksProven.toString())
            if (metrics.frames.linkProofFailed > 0) {
                MetricRow(
                    SUB_ROW_INDENT + stringResource(R.string.diagnostics_metric_link_proof_failed),
                    metrics.frames.linkProofFailed.toString(),
                )
            }
            if (metrics.frames.linkClaimRefused > 0) {
                MetricRow(
                    SUB_ROW_INDENT + stringResource(R.string.diagnostics_metric_link_claim_refused),
                    metrics.frames.linkClaimRefused.toString(),
                )
            }
        }
        PresenceMetrics(metrics, presence)
        // Key recovery (inbound key-request): surfaced only once it's been exercised, so a mesh that never
        // hit a missing-key drop stays uncluttered. A rising NO_SENDER_KEY drop with a matching rise in
        // recovered keys is the signal that the gap is self-healing rather than losing frames.
        if (metrics.keys.keyRequestsSent > 0) {
            MetricRow(stringResource(R.string.diagnostics_metric_key_requests), metrics.keys.keyRequestsSent.toString())
            MetricRow(stringResource(R.string.diagnostics_metric_keys_served), metrics.keys.keysServed.toString())
            MetricRow(stringResource(R.string.diagnostics_metric_keys_recovered), metrics.keys.keysRecovered.toString())
        }
        if (metrics.keys.introsSent > 0 || metrics.keys.introsAnswered > 0) {
            MetricRow(stringResource(R.string.diagnostics_metric_intros_sent), metrics.keys.introsSent.toString())
            MetricRow(stringResource(R.string.diagnostics_metric_intros_answered), metrics.keys.introsAnswered.toString())
            MetricRow(stringResource(R.string.diagnostics_metric_frames_held), metrics.keys.framesHeld.toString())
            MetricRow(stringResource(R.string.diagnostics_metric_frames_replayed), metrics.keys.framesReplayed.toString())
        }
        // Delay-tolerant broadcast/group delivery ticks re-sent to authors that were out of range at delivery
        // time (see AckSync); shown only once it's happened so a mesh that never needed it stays uncluttered.
        if (metrics.receipts.receiptsResent > 0) {
            MetricRow(stringResource(R.string.diagnostics_metric_receipts_resent), metrics.receipts.receiptsResent.toString())
        }
        // Forward-secret DMs (the v2 epoch ratchet): how many sends ratcheted, and how many v2-eligible
        // sends fell back to the static wrap — the fallback should trend to zero as peers upgrade, so a
        // persistent count is the "investigate" signal. Shown only once a DM has been sealed either way.
        if (metrics.seals.dmSealedV2 > 0 || metrics.seals.dmSealedV1Fallback > 0) {
            MetricRow(stringResource(R.string.diagnostics_metric_dms_ratcheted), metrics.seals.dmSealedV2.toString())
            if (metrics.seals.dmSealedV1Fallback > 0) {
                MetricRow(stringResource(R.string.diagnostics_metric_dms_v1_fallback), metrics.seals.dmSealedV1Fallback.toString())
            }
        }
        // Forward-secret group messages (the sender-key ratchet) + their seed traffic. Same shape as
        // the DM pair: the v1 fallback should trend to zero as members upgrade — a persistent count means
        // some member is pinning the group at v1 (no capability, no prekey, or undeliverable seeds).
        if (metrics.groups.groupSealedRatchet > 0 || metrics.groups.groupSealedV1Fallback > 0) {
            MetricRow(stringResource(R.string.diagnostics_metric_groups_ratcheted), metrics.groups.groupSealedRatchet.toString())
            if (metrics.groups.groupSealedV1Fallback > 0) {
                MetricRow(stringResource(R.string.diagnostics_metric_groups_v1_fallback), metrics.groups.groupSealedV1Fallback.toString())
            }
        }
        if (metrics.groups.groupSeedsSent > 0 || metrics.groups.groupSeedsAdopted > 0) {
            MetricRow(stringResource(R.string.diagnostics_metric_group_seeds_sent), metrics.groups.groupSeedsSent.toString())
            MetricRow(stringResource(R.string.diagnostics_metric_group_seeds_adopted), metrics.groups.groupSeedsAdopted.toString())
        }
        // Sealed metadata (receipts/reactions as v2 ctl frames). Same fallback semantics as the pairs
        // above: a persistent fallback count means some private-context receipt/reaction still walks
        // the mesh cleartext (incapable peer, unsealable group).
        if (metrics.receipts.receiptsSealed > 0 || metrics.receipts.receiptsSealedFallback > 0) {
            MetricRow(stringResource(R.string.diagnostics_metric_receipts_sealed), metrics.receipts.receiptsSealed.toString())
            if (metrics.receipts.receiptsSealedFallback > 0) {
                MetricRow(
                    stringResource(R.string.diagnostics_metric_receipts_sealed_fallback),
                    metrics.receipts.receiptsSealedFallback.toString(),
                )
            }
            if (metrics.receipts.receiptsCustodied > 0) {
                MetricRow(stringResource(R.string.diagnostics_metric_receipts_custodied), metrics.receipts.receiptsCustodied.toString())
            }
        }
        if (metrics.seals.reactionsSealed > 0 || metrics.seals.reactionsSealedFallback > 0) {
            MetricRow(stringResource(R.string.diagnostics_metric_reactions_sealed), metrics.seals.reactionsSealed.toString())
            if (metrics.seals.reactionsSealedFallback > 0) {
                MetricRow(
                    stringResource(R.string.diagnostics_metric_reactions_sealed_fallback),
                    metrics.seals.reactionsSealedFallback.toString(),
                )
            }
        }
        // The Internet (spool) plane, shown only once it has done anything — it is off by default, so a
        // mesh-only install never sees these rows. `bridged` is the payoff (frames that reached us with no
        // radio in range); `quarantined` should stay at 0, and a rising count means some uploader is
        // putting blobs into a scope that fail validation (spec §9.3).
        if (metrics.spool.spoolPushed > 0 || metrics.spool.spoolBridged > 0 || metrics.spool.spoolInvalid > 0) {
            MetricRow(stringResource(R.string.diagnostics_metric_spool_pushed), metrics.spool.spoolPushed.toString())
            MetricRow(stringResource(R.string.diagnostics_metric_spool_bridged), metrics.spool.spoolBridged.toString())
            if (metrics.spool.spoolInvalid > 0) {
                MetricRow(stringResource(R.string.diagnostics_metric_spool_invalid), metrics.spool.spoolInvalid.toString())
            }
            if (metrics.spool.spoolErrors > 0) {
                MetricRow(stringResource(R.string.diagnostics_metric_spool_errors), metrics.spool.spoolErrors.toString())
            }
            // Attachments (spec §9.5) get their own rows: they are counted in chunks out and whole
            // images in, which is the difference between "an upload is progressing" and "a photo arrived".
            val attachments = with(metrics.spool) { listOf(spoolAttachPushed, spoolAttachPulled, spoolAttachDeferred, spoolAttachProved) }
            if (attachments.any { it > 0 }) {
                MetricRow(stringResource(R.string.diagnostics_metric_spool_attach_pushed), metrics.spool.spoolAttachPushed.toString())
                MetricRow(stringResource(R.string.diagnostics_metric_spool_attach_pulled), metrics.spool.spoolAttachPulled.toString())
                // Deferrals are the difference between "the relay isn't carrying this photo" and "the
                // radios still are" — without the row the gate is indistinguishable from a broken upload.
                if (metrics.spool.spoolAttachDeferred > 0) {
                    MetricRow(
                        stringResource(R.string.diagnostics_metric_spool_attach_deferred),
                        metrics.spool.spoolAttachDeferred.toString(),
                    )
                }
                // A DM photo its recipient provably holds is never uploaded (ADR 2026-10.vy27): the row says so on purpose.
                if (metrics.spool.spoolAttachProved > 0) {
                    MetricRow(
                        stringResource(R.string.diagnostics_metric_spool_attach_proved),
                        metrics.spool.spoolAttachProved.toString(),
                    )
                }
            }
        }
        // The LoRa (Meshtastic) plane, shown only once it has carried anything — it is off by default and
        // needs a paired board. `received`/`reassembled` are the payoff (a frame that crossed kilometres of
        // LoRa with no radio in range); `tooBig` counts posts too long to fragment into <= 3 packets.
        if (metrics.lora.loraSent > 0 || metrics.lora.loraReceived > 0) {
            MetricRow(stringResource(R.string.diagnostics_metric_lora_sent), metrics.lora.loraSent.toString())
            MetricRow(stringResource(R.string.diagnostics_metric_lora_received), metrics.lora.loraReceived.toString())
            if (metrics.lora.loraReassembled > 0) {
                MetricRow(stringResource(R.string.diagnostics_metric_lora_reassembled), metrics.lora.loraReassembled.toString())
            }
            if (metrics.lora.loraTooBig > 0) {
                MetricRow(stringResource(R.string.diagnostics_metric_lora_too_big), metrics.lora.loraTooBig.toString())
            }
            if (metrics.lora.loraDroppedQueue > 0) {
                MetricRow(stringResource(R.string.diagnostics_metric_lora_dropped), metrics.lora.loraDroppedQueue.toString())
            }
            if (metrics.lora.loraNak > 0) {
                MetricRow(stringResource(R.string.diagnostics_metric_lora_nak), metrics.lora.loraNak.toString())
            }
            // Compact DMs (ADR 2026-10.x9fa): a DM that crossed kilometres with no radio in range, as one unicast each way.
            if (metrics.lora.loraDirectSent > 0) {
                MetricRow(stringResource(R.string.diagnostics_metric_lora_dm_sent), metrics.lora.loraDirectSent.toString())
            }
            // Received: opened compact DMs, plus an older build's DM-form frames.
            val dmsReceived = metrics.lora.loraDirectDelivered + metrics.lora.loraDmReceived
            if (dmsReceived > 0) {
                MetricRow(stringResource(R.string.diagnostics_metric_lora_dm_received), dmsReceived.toString())
            }
        }
        // Bluetooth connect failures: shown only once any occur, with a per-reason breakdown, so an
        // intermittent "can link one peer but not the second" is visible and attributable (RADIO vs other).
        if (metrics.bluetooth.btConnectFails > 0) {
            MetricRow(stringResource(R.string.diagnostics_metric_bt_connect_fails), metrics.bluetooth.btConnectFails.toString())
            metrics.bluetooth.btConnectFailsByReason.forEach { (reason, count) ->
                MetricRow(SUB_ROW_INDENT + reason.name, count.toString())
            }
        }
    }
}

@Composable
private fun MetricRow(
    label: String,
    value: String,
) {
    // A long value (a relay's refusal, its software) moves under its label rather than squeezing it.
    AdaptiveRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        stackedGap = 0.dp,
        trailing = {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun TransportsSection(
    rows: List<TransportRow>,
    // The debug Wi-Fi Aware switch is off: that row says so rather than reading as a radio that is merely down.
    nanOff: Boolean = false,
    // The person's Wi-Fi Aware switch is off: a switched-off Aware row says "off in Settings", not "low power".
    wifiAwareOff: Boolean = false,
) {
    if (rows.isEmpty()) {
        EmptyLine(stringResource(R.string.diagnostics_none_transports))
        return
    }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        rows.forEach { row ->
            when (row) {
                is TransportRow.Live -> {
                    val aware = row.kind == TransportKind.WifiAware
                    LiveTransportRow(row, switchedOff = nanOff && aware, userSwitchedOff = wifiAwareOff && aware)
                }

                is TransportRow.Absent -> {
                    AbsentTransportRow(row)
                }
            }
        }
    }
}

@Composable
private fun LiveTransportRow(
    row: TransportRow.Live,
    switchedOff: Boolean = false,
    userSwitchedOff: Boolean = false,
) {
    val status = row.status
    // The tags and the count move under the plane's name, past its dot, when they would squeeze it.
    AdaptiveRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        stackedIndent = DOT_INDENT,
        trailing = { LiveTransportStatus(row, switchedOff, userSwitchedOff) },
    ) {
        LiveTransportName(status)
    }
}

@Composable
private fun LiveTransportName(status: TransportStatus) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Health dot: the semantic positive when Healthy, error when Degraded (seized), muted outline when Unavailable
        // (radio off) or ForegroundOnly (the OS refuses the search off screen) — neither is a fault, so neither
        // should read as alarmingly as a seized radio.
        val dotColor =
            when (status.health) {
                TransportHealth.Healthy -> MaterialTheme.knitColors.positive
                TransportHealth.Degraded -> MaterialTheme.colorScheme.error
                TransportHealth.Unavailable, TransportHealth.ForegroundOnly -> MaterialTheme.colorScheme.outline
            }
        Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(dotColor))
        Spacer(Modifier.width(12.dp))
        Text(
            text = transportName(status.kind),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun LiveTransportStatus(
    row: TransportRow.Live,
    switchedOff: Boolean,
    userSwitchedOff: Boolean,
) {
    val status = row.status
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Debug flag: the Wi-Fi Aware switch under this section has stopped the plane (its dot reads Unavailable).
        // Otherwise a plane Knit has off is the person's Settings switch (GitHub #44) or low-power mode's doing (ADR
        // 2026-10.d44b), not a radio the user turned off.
        if (switchedOff) {
            TransportTag(stringResource(R.string.diagnostics_transport_debug_off))
            Spacer(Modifier.width(8.dp))
        } else if (status.switchedOff) {
            TransportTag(
                stringResource(
                    if (userSwitchedOff) R.string.diagnostics_transport_switched_off else R.string.diagnostics_transport_low_power,
                ),
            )
            Spacer(Modifier.width(8.dp))
        }
        // The advert carries a rotating tag, not the node id (ADR 2026-11.xp6d): what is aired while the radio is up,
        // never what the build asks for. Whether the advert is live is the dot's to say.
        if (status.blindedAdvert) {
            TransportTag(stringResource(R.string.diagnostics_transport_blinded))
            Spacer(Modifier.width(8.dp))
        }
        // Diagnostic flag: this radio reports itself contended (Bluetooth ↔ A2DP audio streaming).
        if (status.contended) {
            TransportTag(stringResource(R.string.diagnostics_transport_audio))
            Spacer(Modifier.width(8.dp))
        }
        // Diagnostic flag: this radio has stopped initiating data paths (Wi-Fi Aware, ADR 2026-09.m8kc). The
        // dot stays whatever its health is — discovery, cues and the responder still run.
        if (status.initiatorHeld) {
            TransportTag(stringResource(R.string.diagnostics_transport_held))
            Spacer(Modifier.width(8.dp))
        }
        // A long-range plane has no links by design and its count is *authors heard*, not people nearby —
        // a gateway relays for peers whose own radio is nowhere near. Saying "nearby · linked" there read
        // as two facts that were both false. (The board count itself is on the LoRa settings screen.) And
        // that plane exists whether or not a board is bound, so its off states are named rather than counted:
        // "0 heard" under a grey dot had meant both "no board" and "board out of reach".
        Text(
            text =
                when (row.lora) {
                    // Detached on purpose while no phone nearby can use it (GitHub #44): idle, not off; its counts are 0.
                    null -> {
                        when {
                            status.dormant -> {
                                stringResource(R.string.diagnostics_transport_dormant)
                            }

                            // A direct link is no radio (ADR 2026-10.a5yj): its "nearby" was its link count again, and
                            // false besides, since a contact on a link is never near. Only the links are counted.
                            status.kind == TransportKind.DirectLink -> {
                                countText(R.plurals.diagnostics_transport_links, R.string.diagnostics_transport_links_zero, status.linked)
                            }

                            else -> {
                                stringResource(R.string.diagnostics_transport_counts, status.nearby, status.linked)
                            }
                        }
                    }

                    LoraPlane.Off -> {
                        stringResource(R.string.lora_status_off)
                    }

                    LoraPlane.Down -> {
                        stringResource(R.string.diagnostics_transport_lora_down)
                    }

                    LoraPlane.Live -> {
                        countText(
                            R.plurals.diagnostics_transport_heard,
                            R.string.diagnostics_transport_heard_zero,
                            status.nearby,
                        )
                    }
                },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A plane the composite never built. Same shape as a live row so the section reads as one list, but muted
 * throughout — the outline dot a switched-off radio gets (absence isn't a fault either) and the name in the
 * secondary colour — with the reason where the counts would be. Plain text only: an icon with a description
 * here would fail the accessibility audit for announcing twice.
 */
@Composable
private fun AbsentTransportRow(row: TransportRow.Absent) {
    AdaptiveRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        stackedIndent = DOT_INDENT,
        trailing = { AbsentTransportReason(row) },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outline))
            Spacer(Modifier.width(12.dp))
            Text(
                text = transportName(row.kind),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AbsentTransportReason(row: TransportRow.Absent) {
    Text(
        text =
            stringResource(
                if (row.why == PlaneSupport.NeedsAndroid12) {
                    R.string.diagnostics_transport_needs_android_12
                } else {
                    R.string.diagnostics_transport_unsupported
                },
            ),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun transportName(kind: TransportKind): String =
    stringResource(
        when (kind) {
            TransportKind.Bluetooth -> R.string.diagnostics_transport_bluetooth
            TransportKind.WifiAware -> R.string.diagnostics_transport_wifi_aware
            TransportKind.LoRa -> R.string.diagnostics_transport_lora
            TransportKind.Other -> R.string.diagnostics_transport_other
            TransportKind.DirectLink -> R.string.diagnostics_transport_direct_link
        },
    )

/**
 * The planes on a node's row — `BLE·NAN` for a directly-connected node, `via Alex` for one another phone
 * carried, `LoRa` / `Link` / `Internet` for one reached long-range — or null when the row has nothing to claim. The
 * caller has already narrowed [transports] to the kinds its section may show (see [NodeInfo.transports]);
 * [viaSpool] is the Internet plane, which is deliberately not a transport at all (ADR 019) and so has no
 * [TransportKind]; [via] is the neighbour that handed us an indirect peer's frame (ADR 2026-10.fw8g).
 */
@Composable
private fun transportTag(
    transports: Set<TransportKind>,
    viaSpool: Boolean = false,
    via: String? = null,
): String? {
    if (via != null) return stringResource(R.string.diagnostics_tag_via, via)
    val ble = stringResource(R.string.diagnostics_tag_ble)
    val nan = stringResource(R.string.diagnostics_tag_nan)
    val lora = stringResource(R.string.diagnostics_tag_lora)
    val spool = stringResource(R.string.diagnostics_tag_spool)
    val link = stringResource(R.string.diagnostics_tag_link)
    val parts =
        buildList {
            if (TransportKind.Bluetooth in transports) add(ble)
            if (TransportKind.WifiAware in transports) add(nan)
            if (TransportKind.LoRa in transports) add(lora)
            if (TransportKind.DirectLink in transports) add(link)
            if (viaSpool) add(spool)
        }
    return parts.joinToString("·").ifEmpty { null }
}

/** How faded the relay dot is against the filled direct one — present, but weaker evidence. */
private const val RELAY_DOT_ALPHA = 0.45f

/** The indirect dot sits between the two: live traffic a few radio hops out, but not this peer's radio. */
private const val INDIRECT_DOT_ALPHA = 0.7f

@Composable
private fun TransportTag(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier =
            Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * The PHY a Bluetooth link is on (long-range Bluetooth, ADR 2026-10.yvn6), in the transport tag's style: a Coded link
 * takes the tertiary colour, so a long-range link stands out on a walk.
 */
@Composable
private fun PhyTag(
    phy: LinkPhy,
    nodeId: String,
) {
    val label =
        when (phy) {
            LinkPhy.ONE_M -> stringResource(R.string.diagnostics_phy_1m)
            LinkPhy.TWO_M -> stringResource(R.string.diagnostics_phy_2m)
            LinkPhy.CODED -> stringResource(R.string.diagnostics_phy_coded)
            LinkPhy.UNKNOWN -> return
        }
    val coded = phy == LinkPhy.CODED
    val description = stringResource(R.string.diagnostics_phy_cd, label)
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = if (coded) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        modifier =
            Modifier
                .testTag("ble_phy_chip_$nodeId")
                .semantics { contentDescription = description }
                .clip(RoundedCornerShape(4.dp))
                .background(if (coded) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer)
                .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * What protects the live link a node row claims ([NodeInfo.link], ADR 2026-11.8mhs), in the transport tag's shape:
 * "Encrypted" with a lock on a sealed link, "Not encrypted" outlined and muted on a plain one (an older build, or a claim
 * not yet closed). Its description is neutral on purpose: a plain link's cause is not known to this row.
 */
@Composable
private fun LinkChip(
    security: LinkSecurity,
    nodeId: String,
) {
    val sealed = security == LinkSecurity.SEALED
    val label = stringResource(if (sealed) R.string.diagnostics_link_encrypted else R.string.diagnostics_link_plain)
    val description = stringResource(if (sealed) R.string.diagnostics_link_encrypted_cd else R.string.diagnostics_link_plain_cd)
    val shape = RoundedCornerShape(4.dp)
    val content = if (sealed) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier =
            Modifier
                .testTag("link_chip_$nodeId")
                .clearAndSetSemantics { contentDescription = description }
                .clip(shape)
                .then(
                    if (sealed) {
                        Modifier.background(MaterialTheme.colorScheme.secondaryContainer)
                    } else {
                        Modifier.border(1.dp, MaterialTheme.colorScheme.outline, shape)
                    },
                ).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (sealed) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                tint = MaterialTheme.knitColors.positive,
                modifier = Modifier.size(12.dp),
            )
            Spacer(Modifier.width(3.dp))
        }
        Text(text = label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium, color = content)
    }
}

/** This phone holds the node's presence key (ADR 2026-11.xp6d), so it can recognise the node's rotating ID. */
@Composable
private fun KeyChip(nodeId: String) {
    val description = stringResource(R.string.diagnostics_key_chip_cd)
    Box(
        modifier =
            Modifier
                .testTag("key_chip_$nodeId")
                .clearAndSetSemantics { contentDescription = description },
    ) {
        TransportTag(stringResource(R.string.diagnostics_key_chip))
    }
}

/**
 * Presence keys (ADR 2026-11.xp6d): how many direct contacts' keys this phone holds, what it sent and stored, and the
 * faults, each only once it happened. Never a generation, a rotation count or a time: any of them dates a rotation, and so
 * a removal or a block.
 */
@Composable
private fun PresenceMetrics(
    metrics: MeshMetrics.Snapshot,
    presence: PresenceKeyInfo,
) {
    val contacts = presence.directContacts
    if (contacts != null && contacts > 0) {
        MetricRow(
            stringResource(R.string.diagnostics_metric_presence_keys_held),
            stringResource(R.string.diagnostics_metric_presence_keys_of, presence.keyHolders.size, contacts),
        )
    }
    if (metrics.keys.presenceKeysSent > 0 || metrics.keys.presenceKeysStored > 0) {
        MetricRow(stringResource(R.string.diagnostics_metric_presence_keys_sent), metrics.keys.presenceKeysSent.toString())
        MetricRow(stringResource(R.string.diagnostics_metric_presence_keys_stored), metrics.keys.presenceKeysStored.toString())
    }
    if (metrics.keys.presenceMarkNotFull > 0) {
        MetricRow(stringResource(R.string.diagnostics_metric_presence_mark_not_full), metrics.keys.presenceMarkNotFull.toString())
    }
    if (metrics.bluetooth.bleTagsUnresolvable > 0) {
        MetricRow(stringResource(R.string.diagnostics_metric_tags_unresolvable), metrics.bluetooth.bleTagsUnresolvable.toString())
    }
    if (metrics.bluetooth.bleTagsStale > 0) {
        MetricRow(stringResource(R.string.diagnostics_metric_tags_stale), metrics.bluetooth.bleTagsStale.toString())
    }
    val blindDrops = metrics.bluetooth.bleBlindDropsByReason.filterValues { it > 0 }
    if (blindDrops.isNotEmpty()) {
        MetricRow(stringResource(R.string.diagnostics_metric_blind_drops), blindDrops.values.sum().toString())
        blindDrops.forEach { (reason, count) -> MetricRow(SUB_ROW_INDENT + reason.name, count.toString()) }
    }
}

/**
 * A debug build's rotating-ID reader (ADR 2026-11.xp6d), through `AdvertDiag`: how many sightings it recognised by a
 * contact's key and by a link's claim, how many tags it expects and for how many contacts, and the form on the air. Counts
 * only, never a tag or a key; "resolved" by a claim is not "recognised by key", so the two stay apart.
 */
@Composable
private fun AdvertDebugSection(info: AdvertDebugInfo) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("advert_debug")) {
        Text(
            text = stringResource(R.string.diagnostics_advert_debug_label),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        MetricRow(stringResource(R.string.diagnostics_advert_debug_by_key), info.resolvedByKey.toString())
        MetricRow(stringResource(R.string.diagnostics_advert_debug_by_claim), info.resolvedByClaim.toString())
        MetricRow(
            stringResource(R.string.diagnostics_advert_debug_expected),
            stringResource(R.string.diagnostics_advert_debug_expected_value, info.expectedTags, info.expectedContacts),
        )
        MetricRow(
            stringResource(R.string.diagnostics_advert_debug_form),
            stringResource(if (info.airingTag) R.string.diagnostics_transport_blinded else R.string.diagnostics_advert_form_clear),
        )
    }
}

/** The top bar while Known rows are picked: how many, a way out, and Hide (ADR 2026-11.b37x). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    count: Int,
    onClear: () -> Unit,
    onHide: () -> Unit,
) {
    TopAppBar(
        navigationIcon = {
            IconButton(
                onClick = onClear,
                modifier = Modifier.size(48.dp).testTag("diagnostics_clear_selection"),
            ) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.diagnostics_hide_clear_selection))
            }
        },
        title = { BarTitle(pluralStringResource(R.plurals.diagnostics_hide_selected, count, count)) },
        actions = {
            IconButton(
                onClick = onHide,
                modifier = Modifier.size(48.dp).testTag("diagnostics_hide_selected"),
            ) {
                Icon(Icons.Filled.VisibilityOff, contentDescription = stringResource(R.string.diagnostics_hide_action))
            }
        },
    )
}

/**
 * A Known row that can be picked for hiding: a long-press (or the accessibility action) picks it, and once anything is
 * picked a tap toggles it like a checkbox. A tap outside a selection does nothing, as on any node row.
 */
@Composable
private fun SelectableNodeRow(
    node: NodeInfo,
    now: Long,
    selecting: Boolean,
    selected: Boolean,
    onToggle: () -> Unit,
    keyHeld: Boolean = false,
) {
    val selectLabel = stringResource(R.string.diagnostics_hide_select)
    val haptics = LocalHapticFeedback.current
    val pick =
        if (selecting) {
            Modifier.toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle() })
        } else {
            Modifier
                .pointerInput(onToggle) {
                    detectTapGestures(
                        onLongPress = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onToggle()
                        },
                    )
                }.semantics {
                    onLongClick(label = selectLabel) {
                        onToggle()
                        true
                    }
                    customActions =
                        listOf(
                            CustomAccessibilityAction(selectLabel) {
                                onToggle()
                                true
                            },
                        )
                }
        }
    Row(
        modifier = Modifier.fillMaxWidth().then(pick).testTag("known_row_${node.nodeId}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            // No description: the Checkbox role already says whether the row is picked.
            Icon(
                imageVector = if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp).size(24.dp),
            )
        }
        Box(modifier = Modifier.weight(1f)) { NodeRow(node, now, keyHeld = keyHeld) }
    }
}

/** Under the Known rows: Hide all of them (the ones past the shown five too), and Show the hidden ones again. */
@Composable
private fun KnownActions(
    shown: Int,
    hidden: Int,
    onHideAll: () -> Unit,
    onShowHidden: () -> Unit,
) {
    // A flow, not a row: in a long language the two labels each keep a line rather than squeezing one another.
    FlowRow(modifier = Modifier.padding(horizontal = 8.dp)) {
        if (shown > 0) {
            TextButton(onClick = onHideAll, modifier = Modifier.testTag("diagnostics_hide_all")) {
                Text(stringResource(R.string.diagnostics_hide_all, shown))
            }
        }
        if (hidden > 0) {
            TextButton(onClick = onShowHidden, modifier = Modifier.testTag("diagnostics_show_hidden")) {
                Text(stringResource(R.string.diagnostics_show_hidden, hidden))
            }
        }
    }
}

@Composable
private fun NodeRow(
    node: NodeInfo,
    now: Long,
    // The PHY of this peer's Bluetooth link, when the transport holds a handle on it; null draws no chip.
    phy: LinkPhy? = null,
    // This phone holds the peer's presence key (ADR 2026-11.xp6d): the Key chip.
    keyHeld: Boolean = false,
) {
    // The tags and the profile's age move under the name, past its dot, rather than ellipsizing the name.
    AdaptiveRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        stackedIndent = DOT_INDENT,
        trailing = { NodeTags(node, now, phy, keyHeld) },
    ) {
        NodeName(node)
    }
}

@Composable
private fun NodeName(node: NodeInfo) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Four strengths of evidence, four dots: filled = a radio saw this peer itself; lighter = another phone
        // carried its traffic over the radio mesh; faded = a long-range plane did; muted = we only hold its profile.
        val dotColor =
            when (node.reach) {
                Reach.Direct -> MaterialTheme.knitColors.positive
                Reach.Indirect -> MaterialTheme.knitColors.positive.copy(alpha = INDIRECT_DOT_ALPHA)
                Reach.LongRange -> MaterialTheme.knitColors.positive.copy(alpha = RELAY_DOT_ALPHA)
                Reach.Known -> MaterialTheme.colorScheme.outline
            }
        Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(dotColor))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = node.displayName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = node.nodeId,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NodeTags(
    node: NodeInfo,
    now: Long,
    phy: LinkPhy?,
    keyHeld: Boolean,
) {
    val tag = transportTag(node.transports, node.viaSpool, node.via)
    val age = node.profileUpdatedAt?.let { compactTimeAgo(it, now, labels = relativeTimeLabels()) }
    // Plane, PHY, link, key, then the profile's age. A flow, not a row: a long language or a row with every chip wraps
    // onto a second line rather than squeezing the last chip into a column of letters.
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        tag?.let { TransportTag(it) }
        phy?.takeIf { it != LinkPhy.UNKNOWN }?.let { PhyTag(it, node.nodeId) }
        node.link?.let { LinkChip(it, node.nodeId) }
        if (keyHeld) KeyChip(node.nodeId)
        age?.let {
            val chips = tag != null || (phy != null && phy != LinkPhy.UNKNOWN) || node.link != null || keyHeld
            Text(
                text = stringResource(R.string.diagnostics_profile_age, it),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = if (chips) Modifier.padding(start = 4.dp) else Modifier,
            )
        }
    }
}

/** How far a row's text sits in from its leading 10 dp dot and 12 dp gap: where a stacked status lines up. */
private val DOT_INDENT = 22.dp

/**
 * The link-cap stepper's share of its row: two 48 dp buttons and a number take about half on a phone already, so it
 * moves under the explanation only once its value grows past that.
 */
private const val STEPPER_SHARE = 0.55f

/** How much of a scope label (a node id or group id) fits a Diagnostics row without wrapping. */
private const val SCOPE_LABEL_CHARS = 12

/** The lead-in that sets a detail row under the row it belongs to. */
private const val SUB_ROW_INDENT = "   "

/**
 * One spool's live state: whether we are connected, its most recent refusal, and per-scope convergence.
 *
 * `local` vs `spool` counts are the honest divergence readout the design doc asks for — a spool that
 * silently withholds shows up here as a persistent gap. Read them together with the retiring flag,
 * though: a rotating scope is drained and never refilled (spec §3.1/§3.3), so it legitimately sits at
 * `local > spool` for its whole drain window, and that is the plane working rather than failing.
 */
@Composable
private fun SpoolSection(spool: SpoolStatus) {
    MetricRow(
        SpoolUrl.host(spool.url),
        stringResource(if (spool.connected) R.string.diagnostics_spool_connected else R.string.diagnostics_spool_offline),
    )
    spool.lastError?.let { MetricRow(SUB_ROW_INDENT + stringResource(R.string.diagnostics_spool_last_error), it) }
    if (spool.connected) {
        // The relay's own build, when it publishes one. Absent for a spool implementation that serves no
        // `/source`, so the row appears rather than reading "unknown" for a perfectly healthy relay.
        spool.software?.label?.let { MetricRow(SUB_ROW_INDENT + stringResource(R.string.diagnostics_spool_software), it) }
        MetricRow(
            SUB_ROW_INDENT + stringResource(R.string.diagnostics_spool_photos),
            stringResource(
                if (spool.maxAttachBytes != null) R.string.diagnostics_spool_photos_yes else R.string.diagnostics_spool_photos_frames_only,
            ),
        )
        if (spool.powBits > 0) {
            MetricRow(
                SUB_ROW_INDENT + stringResource(R.string.diagnostics_spool_pow),
                pluralStringResource(R.plurals.diagnostics_spool_pow_bits, spool.powBits, spool.powBits),
            )
        }
    }
    spool.scopes.forEach { scope ->
        // A pair scope shares its label with the DM scope it precedes, so two rows for one peer are not a bug.
        val name = scope.label.take(SCOPE_LABEL_CHARS)
        val label =
            when {
                scope.retiring -> stringResource(R.string.diagnostics_spool_scope_retiring, name)
                scope.pair -> stringResource(R.string.diagnostics_spool_scope_pair, name)
                else -> name
            }
        var counts = stringResource(R.string.diagnostics_spool_scope_counts, scope.localCount, scope.spoolCount)
        if (scope.accountedCount >
            0
        ) {
            counts = pluralStringResource(R.plurals.diagnostics_spool_scope_aged, scope.accountedCount, counts, scope.accountedCount)
        }
        if (scope.invalidCount >
            0
        ) {
            counts = pluralStringResource(R.plurals.diagnostics_spool_scope_bad, scope.invalidCount, counts, scope.invalidCount)
        }
        MetricRow(SUB_ROW_INDENT + label, counts)
    }
}

/**
 * Shown only when the poison-pill has turned an on-device model off (ADR 037). It sits in the same
 * "Problem reports" section as the crash row because it is the same kind of thing — something went wrong
 * on this phone and the user may want to act — and because a native crash leaves no report, so this is
 * often the *only* thing in that section.
 *
 * Non-interactive text plus one button, so it needs no `clickable`/`Role.Button` handling; the button
 * carries its own label and its leading icon is decorative (a described icon beside a labelled button is
 * what the accessibility checks flag as a duplicate).
 */
@Composable
private fun ModerationLatchSection(onReset: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            text = stringResource(R.string.diagnostics_moderation_latched_label),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            text = stringResource(R.string.diagnostics_moderation_latched_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.RestartAlt, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.diagnostics_moderation_reset_action))
        }
    }
}

/**
 * Why this phone does not start Wi-Fi Direct groups (ADR 2026-10.w3dw), and for a latch the way back. A generic system
 * image has none: hosting there restarts the phone, and nothing Knit can reset changes that. Not error-coloured, like
 * [InitiatorHoldSection]: Knit is protecting the phone, and receiving and joining still work.
 */
@Composable
private fun DirectHostSection(
    verdict: HostVerdict,
    onReset: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("direct_host_section"),
    ) {
        Text(
            text = stringResource(R.string.diagnostics_direct_host_off_label),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text =
                stringResource(
                    if (verdict is HostVerdict.Latched) {
                        R.string.diagnostics_direct_host_latched_body
                    } else {
                        R.string.diagnostics_direct_host_generic_body
                    },
                ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        if (verdict is HostVerdict.Latched) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.RestartAlt, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.diagnostics_direct_host_reset_action))
            }
        }
    }
}

/**
 * Which microphone path this phone's calls record from, when it is not the call-tuned one by default (C-3.13-12, ADR
 * 2026-10.kw29). A generic image's fallback has no way back; a learned or chosen one has.
 */
@Composable
private fun CallCaptureSection(
    profile: CaptureProfile,
    reason: CaptureReason,
    onReset: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("call_capture_section"),
    ) {
        Text(
            text =
                stringResource(
                    if (profile == CaptureProfile.FALLBACK) {
                        R.string.diagnostics_call_capture_fallback_label
                    } else {
                        R.string.diagnostics_call_capture_standard_label
                    },
                ),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text =
                stringResource(
                    when (reason) {
                        CaptureReason.GENERIC_IMAGE -> R.string.diagnostics_call_capture_generic_body
                        CaptureReason.SILENT_CALL -> R.string.diagnostics_call_capture_silent_body
                        CaptureReason.CHOSEN -> R.string.diagnostics_call_capture_chosen_body
                    },
                ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        if (reason != CaptureReason.GENERIC_IMAGE) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.RestartAlt, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.diagnostics_call_capture_reset_action))
            }
        }
    }
}

/**
 * Why the Wi-Fi Aware row above says "on hold", and the way back (ADR 2026-09.m8kc, work item #78). The same
 * shape as [ModerationLatchSection], but no confirm dialog: the release is instant, costs nothing, and three
 * more Wi-Fi drops put the hold straight back. The label is not error-coloured — the plane is healthy and the
 * hold is Knit protecting the phone's Wi-Fi, not a fault to alarm over.
 */
@Composable
private fun InitiatorHoldSection(onRelease: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("nan_hold_section"),
    ) {
        Text(
            text = stringResource(R.string.diagnostics_nan_hold_label),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = stringResource(R.string.diagnostics_nan_hold_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onRelease, modifier = Modifier.fillMaxWidth().testTag("nan_hold_release")) {
            Icon(Icons.Filled.RestartAlt, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.diagnostics_nan_hold_retry_action))
        }
    }
}

/**
 * The debug build's Bluetooth link limit (`SettingsStore.debugBleLinkCap`): a stepper from 0 up to the shipped
 * budget, where the top step is "Default" and clears the cap. Both buttons are 48 dp targets and go disabled
 * at their end of the range.
 */
@Composable
private fun BleLinkCapRow(
    cap: Int?,
    onSet: (Int) -> Unit,
) {
    val max = PromotionConfig.DEFAULT_MAX_LINKS
    val value = cap ?: max
    // The stepper moves under the explanation, at the end, when its value ("Default (6)") would squeeze it.
    AdaptiveRow(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp).testTag("ble_link_cap"),
        gap = 0.dp,
        stackedAtEnd = true,
        maxTrailingShare = STEPPER_SHARE,
        trailing = { BleLinkCapStepper(cap, value, max, onSet) },
    ) {
        Column {
            Text(
                text = stringResource(R.string.diagnostics_ble_link_cap_label),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = stringResource(R.string.diagnostics_ble_link_cap_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun BleLinkCapStepper(
    cap: Int?,
    value: Int,
    max: Int,
    onSet: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = { onSet(value - 1) },
            enabled = value > 0,
            modifier = Modifier.size(48.dp).testTag("ble_link_cap_dec"),
        ) {
            Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.diagnostics_ble_link_cap_decrease))
        }
        Text(
            text = cap?.toString() ?: stringResource(R.string.diagnostics_ble_link_cap_default, max),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("ble_link_cap_value"),
        )
        IconButton(
            onClick = { onSet(value + 1) },
            enabled = value < max,
            modifier = Modifier.size(48.dp).testTag("ble_link_cap_inc"),
        ) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.diagnostics_ble_link_cap_increase))
        }
    }
}

/**
 * A debug build's on/off switch, persisted: the Wi-Fi Aware plane (`SettingsStore.debugNanOff`, applied by the
 * transport without a restart, so the mesh runs on Bluetooth alone) or calls' Wi-Fi Direct leg
 * (`SettingsStore.debugDirectCallsOff`, read when the next call starts) or saving walkie clips
 * (`SettingsStore.debugWalkieClips`, read as each burst begins) or the developer view (`SettingsStore.debugDevView`,
 * ADR 2026-10.s7wg). The whole row toggles; [off] reads unchecked.
 */
@Composable
private fun DebugSwitchRow(
    @StringRes label: Int,
    @StringRes body: Int,
    tag: String,
    off: Boolean,
    onSet: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .toggleable(value = !off, onValueChange = { on -> onSet(!on) }, role = Role.Switch)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(label),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = stringResource(body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = !off, onCheckedChange = null)
    }
}

/** The four modes of long-range Bluetooth, in the row's order, with their labels. */
private val BLE_PHY_MODES =
    listOf(
        CodedPhyMode.OFF to R.string.diagnostics_ble_phy_off,
        CodedPhyMode.AUTO to R.string.diagnostics_ble_phy_auto,
        CodedPhyMode.CODED to R.string.diagnostics_ble_phy_coded,
        CodedPhyMode.ONE_M to R.string.diagnostics_ble_phy_one_m,
    )

/**
 * Long-range Bluetooth's mode (`SettingsStore.debugBlePhyMode`, ADR 2026-10.yvn6, on in release since 2026-10-08), so a
 * walk test can switch between standard Bluetooth, the automatic steps and the two pinned PHYs without adb. The transport applies a change
 * without a restart. Debug builds only: a release build always runs AUTO and never shows the row.
 */
@Composable
private fun BlePhyModeRow(
    mode: CodedPhyMode,
    onSet: (CodedPhyMode) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("ble_phy_mode")) {
        Text(
            text = stringResource(R.string.diagnostics_ble_phy_label),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = stringResource(R.string.diagnostics_ble_phy_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        )
        ChoiceButtons(
            choices = BLE_PHY_MODES.map { (value, label) -> value to stringResource(label) },
            selected = mode,
            onSelect = onSet,
            check = false,
            tagOf = { "ble_phy_${it.wire}" },
        )
    }
}

/**
 * The one-line "Last crash" entry. Interactive, so it takes the 48 dp minimum touch target — `clickable`
 * goes **before** `padding` so the target covers the whole row, unlike the non-interactive [MetricRow]
 * next door, which is ~32 dp tall.
 *
 * Labelled with `onClickLabel` rather than `clearAndSetSemantics`: the two visible texts already read
 * correctly, and a redundant contentDescription is exactly what the accessibility checks flag as a
 * duplicate — it also keeps `onNodeWithText` working in the screen tests.
 */
@Composable
private fun CrashRow(
    crash: CrashReportRef,
    now: Long,
    onClick: () -> Unit,
) {
    val label = stringResource(R.string.crash_open_action)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.BugReport,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = stringResource(R.string.crash_last_label), style = MaterialTheme.typography.bodyMedium)
            Text(
                text = stringResource(R.string.crash_when, compactTimeAgo(crash.at, now, labels = relativeTimeLabels())),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The way into the field log (ADR 2026-10.a239); shaped like [CrashRow] next door. */
@Composable
private fun FieldLogRow(onClick: () -> Unit) {
    val label = stringResource(R.string.field_log_open_action)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Sensors,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = stringResource(R.string.field_log_row_label), style = MaterialTheme.typography.bodyMedium)
            Text(
                text = stringResource(R.string.field_log_row_supporting),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun EmptyLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Preview(showBackground = true)
@Composable
fun SelfSectionPreview() =
    KnitPreview {
        // SelfSection emits a header + a Column as siblings; wrap so the preview lays them out vertically
        // (the real screen places it in a LazyColumn item).
        Column { SelfSection(name = "Ada Lovelace", nodeId = "8f3a2b1c9d4e5f60", presenceKeyHeld = true) }
    }

@Preview(showBackground = true)
@Composable
fun MeshControlsSectionHealthyPreview() =
    KnitPreview {
        MeshControlsSection(health = TransportHealth.Healthy, radios = RadioSupport.ALL, onRestart = {}, onScan = {})
    }

@Preview(showBackground = true)
@Composable
fun MeshControlsSectionDegradedPreview() =
    KnitPreview {
        MeshControlsSection(health = TransportHealth.Degraded, radios = RadioSupport.ALL, onRestart = {}, onScan = {})
    }

@Preview(showBackground = true)
@Composable
fun MeshControlsSectionUnavailablePreview() =
    KnitPreview {
        MeshControlsSection(health = TransportHealth.Unavailable, radios = RadioSupport.ALL, onRestart = {}, onScan = {})
    }

@Preview(showBackground = true)
@Composable
fun MeshControlsSectionUnavailableBleOnlyPreview() =
    KnitPreview {
        MeshControlsSection(
            health = TransportHealth.Unavailable,
            radios = RadioSupport(bluetooth = PlaneSupport.Supported, wifiAware = PlaneSupport.NoHardware),
            onRestart = {},
            onScan = {},
        )
    }

@Preview(showBackground = true)
@Composable
fun MetricsSectionPopulatedPreview() =
    KnitPreview {
        MetricsSection(
            metrics =
                MeshMetrics.Snapshot(
                    frames =
                        MeshMetrics.Snapshot.Frames(
                            framesOriginated = 128,
                            framesDelivered = 96,
                            framesRelayed = 1_024,
                            framesHandedOn = 7,
                            framesSuppressed = 12,
                            framesDeduped = 340,
                            bytesSent = 2_500_000,
                            linksProven = 14,
                            linksSealed = 12,
                            linkSealRefused = 1,
                        ),
                    keys = MeshMetrics.Snapshot.Keys(presenceKeysSent = 9, presenceKeysStored = 7),
                ),
            presence = PresenceKeyInfo(ownKeyHeld = true, keyHolders = setOf("a1b2c3d4e5f6", "b2c3d4e5f6a1"), directContacts = 3),
        )
    }

/** A debug build's rotating-ID reader, under Debug options. */
@Preview(showBackground = true)
@Composable
fun AdvertDebugSectionPreview() =
    KnitPreview {
        AdvertDebugSection(
            AdvertDebugInfo(resolvedByKey = 42, resolvedByClaim = 7, expectedTags = 1_024, expectedContacts = 8, airingTag = true),
        )
    }

@Preview(showBackground = true)
@Composable
fun MetricsSectionEmptyPreview() =
    KnitPreview {
        MetricsSection(
            metrics =
                MeshMetrics.Snapshot(),
        )
    }

@Preview(showBackground = true)
@Composable
fun TransportsSectionPreview() =
    KnitPreview {
        TransportsSection(
            rows =
                listOf(
                    TransportRow.Live(
                        TransportStatus(
                            TransportKind.Bluetooth,
                            TransportHealth.Healthy,
                            linked = 3,
                            nearby = 5,
                            contended = true,
                            blindedAdvert = true,
                        ),
                    ),
                    TransportRow.Live(TransportStatus(TransportKind.WifiAware, TransportHealth.Healthy, linked = 1, nearby = 4)),
                    TransportRow.Live(
                        TransportStatus(TransportKind.LoRa, TransportHealth.Healthy, linked = 0, nearby = 2),
                        lora = LoraPlane.Live,
                    ),
                    // A direct link's own neighbours are its links: the row counts links, never "nearby".
                    TransportRow.Live(TransportStatus(TransportKind.DirectLink, TransportHealth.Healthy, linked = 2, nearby = 2)),
                ),
        )
    }

/** A phone with no Wi-Fi Aware (the RedMagic 11 case), LoRa shipped but no board bound. */
@Preview(showBackground = true)
@Composable
fun TransportsSectionAbsentPreview() =
    KnitPreview {
        TransportsSection(
            rows =
                listOf(
                    TransportRow.Live(TransportStatus(TransportKind.Bluetooth, TransportHealth.Healthy, linked = 1, nearby = 2)),
                    TransportRow.Absent(TransportKind.WifiAware, PlaneSupport.NoHardware),
                    TransportRow.Live(
                        TransportStatus(TransportKind.LoRa, TransportHealth.Unavailable, linked = 0, nearby = 0),
                        lora = LoraPlane.Off,
                    ),
                ),
        )
    }

@Preview(showBackground = true)
@Composable
fun NodeRowDirectPreview() =
    KnitPreview {
        NodeRow(
            node =
                NodeInfo(
                    nodeId = "8f3a2b1c9d4e",
                    displayName = "Ada Lovelace",
                    reach = Reach.Direct,
                    profileUpdatedAt = PREVIEW_NOW - 3 * 60_000L,
                    transports = setOf(TransportKind.Bluetooth, TransportKind.WifiAware),
                    link = LinkSecurity.SEALED,
                ),
            now = PREVIEW_NOW,
            keyHeld = true,
        )
    }

/** A neighbour on an older build: its live link is plain. */
@Preview(showBackground = true)
@Composable
fun NodeRowPlainLinkPreview() =
    KnitPreview {
        NodeRow(
            node =
                NodeInfo(
                    nodeId = "b2c3d4e5f6a1",
                    displayName = "Edsger Dijkstra",
                    reach = Reach.Direct,
                    profileUpdatedAt = PREVIEW_NOW - 20 * 60_000L,
                    transports = setOf(TransportKind.Bluetooth),
                    link = LinkSecurity.LEGACY,
                ),
            now = PREVIEW_NOW,
        )
    }

/** The worst case for the row's width: a long name and every chip — planes, PHY, link, key — beside the profile age. */
@Preview(showBackground = true)
@Composable
fun NodeRowAllChipsPreview() =
    KnitPreview {
        NodeRow(
            node =
                NodeInfo(
                    nodeId = "c3d4e5f6a1b2",
                    displayName = "Margaret Hamilton-Lovelace of the Apollo Guidance Computer",
                    reach = Reach.Direct,
                    profileUpdatedAt = PREVIEW_NOW - 26 * 60 * 60_000L,
                    transports = setOf(TransportKind.Bluetooth, TransportKind.WifiAware),
                    link = LinkSecurity.SEALED,
                ),
            now = PREVIEW_NOW,
            phy = LinkPhy.CODED,
            keyHeld = true,
        )
    }

/** A contact reached over a direct Internet link: long-range, tagged Link, sealed by its lane. */
@Preview(showBackground = true)
@Composable
fun NodeRowDirectLinkPreview() =
    KnitPreview {
        NodeRow(
            node =
                NodeInfo(
                    nodeId = "f6a1b2c3d4e5",
                    displayName = "Frances Allen",
                    reach = Reach.LongRange,
                    profileUpdatedAt = PREVIEW_NOW - 2 * 60 * 60_000L,
                    transports = setOf(TransportKind.DirectLink),
                    link = LinkSecurity.SEALED,
                ),
            now = PREVIEW_NOW,
            keyHeld = true,
        )
    }

@Preview(showBackground = true)
@Composable
fun NodeRowCodedPreview() =
    KnitPreview {
        NodeRow(
            node =
                NodeInfo(
                    nodeId = "8f3a2b1c9d4e",
                    displayName = "Ada Lovelace",
                    reach = Reach.Direct,
                    profileUpdatedAt = PREVIEW_NOW - 3 * 60_000L,
                    transports = setOf(TransportKind.Bluetooth),
                ),
            now = PREVIEW_NOW,
            phy = LinkPhy.CODED,
        )
    }

@Preview(showBackground = true)
@Composable
fun NodeRowIndirectPreview() =
    KnitPreview {
        NodeRow(
            node =
                NodeInfo(
                    nodeId = "d4e5f6a1b2c3",
                    displayName = "Barbara Liskov",
                    reach = Reach.Indirect,
                    profileUpdatedAt = PREVIEW_NOW - 40 * 60_000L,
                    via = "Ada Lovelace",
                ),
            now = PREVIEW_NOW,
        )
    }

@Preview(showBackground = true)
@Composable
fun NodeRowRelayPreview() =
    KnitPreview {
        NodeRow(
            node =
                NodeInfo(
                    nodeId = "a1b2c3d4e5f6",
                    displayName = "Grace Hopper",
                    reach = Reach.LongRange,
                    profileUpdatedAt = null,
                    transports = setOf(TransportKind.LoRa),
                ),
            now = PREVIEW_NOW,
        )
    }

private val PREVIEW_KNOWN =
    NodeInfo(
        nodeId = "e5f6a1b2c3d4",
        displayName = "Alan Turing",
        reach = Reach.Known,
        profileUpdatedAt = PREVIEW_NOW - 26 * 60 * 60_000L,
    )

/** A Known row outside a selection: it looks like any node row, and a long-press picks it. */
@Preview(showBackground = true)
@Composable
fun KnownRowSelectablePreview() =
    KnitPreview {
        SelectableNodeRow(node = PREVIEW_KNOWN, now = PREVIEW_NOW, selecting = false, selected = false, onToggle = {})
    }

/** Known rows while picking: one picked, one not. */
@Preview(showBackground = true)
@Composable
fun KnownRowsSelectingPreview() =
    KnitPreview {
        Column {
            SelectableNodeRow(node = PREVIEW_KNOWN, now = PREVIEW_NOW, selecting = true, selected = true, onToggle = {})
            SelectableNodeRow(
                node = PREVIEW_KNOWN.copy(nodeId = "a6b1c2d3e4f5", displayName = "Ada Byron"),
                now = PREVIEW_NOW,
                selecting = true,
                selected = false,
                onToggle = {},
            )
        }
    }

/** The actions under the Known rows: Hide all, and Show hidden once something is hidden. */
@Preview(showBackground = true)
@Composable
fun KnownActionsPreview() =
    KnitPreview {
        KnownActions(shown = 7, hidden = 2, onHideAll = {}, onShowHidden = {})
    }

/** The screen while picking Known rows: the selection's top bar. */
@Preview(showBackground = true)
@Composable
fun DiagnosticsScreenSelectingPreview() =
    KnitPreview {
        DiagnosticsScreenContent(
            state =
                DiagnosticsUiState(
                    myNodeId = "8f3a2b1c9d4e",
                    myName = "Ada Lovelace",
                    knownNodes = listOf(PREVIEW_KNOWN),
                    knownTotal = 1,
                    hidingOffered = true,
                    hideableKnown = mapOf(PREVIEW_KNOWN.nodeId to PREVIEW_NOW),
                ),
            health = TransportHealth.Healthy,
            lastCrash = null,
            moderationLatched = false,
            now = PREVIEW_NOW,
            snackbarHostState = remember { SnackbarHostState() },
            onBack = {},
            onRestartMesh = {},
            onScan = {},
            onOpenCrashLog = {},
            onResetModeration = {},
            selected = setOf(PREVIEW_KNOWN.nodeId),
        )
    }

@Preview(showBackground = true)
@Composable
fun DiagnosticsRowsPreview() =
    KnitPreview {
        Column {
            SectionHeader(text = "Metrics")
            MetricRow(label = "Frames originated", value = "128")
            MetricRow(label = "Frames relayed", value = "1,024")
            EmptyLine(text = "No nodes connected directly.")
        }
    }

private val PREVIEW_CRASH =
    CrashReportRef(
        at = PREVIEW_NOW - 2 * 60 * 60_000L,
        summary = "IllegalStateException at BluetoothMeshTransport.kt:552",
        appVersion = "2.3.0 (13) release",
        device = "Google Pixel 8 (shiba)",
        androidVersion = "16 (SDK 36)",
        file = File("crash-1700000000000-deadbeef.txt"),
    )

@Preview(showBackground = true)
@Composable
fun CrashRowPreview() =
    KnitPreview {
        CrashRow(crash = PREVIEW_CRASH, now = PREVIEW_NOW, onClick = {})
    }

@Preview(showBackground = true)
@Composable
fun DiagnosticsScreenPopulatedPreview() =
    KnitPreview {
        DiagnosticsScreenContent(
            state =
                DiagnosticsUiState(
                    myNodeId = "8f3a2b1c9d4e",
                    myName = "Ada Lovelace",
                    directNodes =
                        listOf(
                            NodeInfo(
                                nodeId = "a1b2c3d4e5f6",
                                displayName = "Grace Hopper",
                                reach = Reach.Direct,
                                profileUpdatedAt = PREVIEW_NOW - 3 * 60_000L,
                                transports = setOf(TransportKind.Bluetooth, TransportKind.WifiAware),
                                link = LinkSecurity.SEALED,
                            ),
                            NodeInfo(
                                nodeId = "b2c3d4e5f6a1",
                                displayName = "Edsger Dijkstra",
                                reach = Reach.Direct,
                                profileUpdatedAt = PREVIEW_NOW - 20 * 60_000L,
                                transports = setOf(TransportKind.Bluetooth),
                                link = LinkSecurity.LEGACY,
                            ),
                        ),
                    indirectNodes =
                        listOf(
                            NodeInfo(
                                nodeId = "d4e5f6a1b2c3",
                                displayName = "Barbara Liskov",
                                reach = Reach.Indirect,
                                profileUpdatedAt = PREVIEW_NOW - 40 * 60_000L,
                                via = "Grace Hopper",
                            ),
                        ),
                    longRangeNodes =
                        listOf(
                            NodeInfo(
                                nodeId = "c3d4e5f6a1b2",
                                displayName = "Radia Perlman",
                                reach = Reach.LongRange,
                                profileUpdatedAt = null,
                                transports = setOf(TransportKind.LoRa),
                            ),
                        ),
                    metrics =
                        MeshMetrics.Snapshot(
                            frames =
                                MeshMetrics.Snapshot.Frames(
                                    framesOriginated = 128,
                                    framesDelivered = 96,
                                    framesRelayed = 1_024,
                                    framesHandedOn = 7,
                                    framesSuppressed = 12,
                                    framesDeduped = 340,
                                    bytesSent = 2_500_000,
                                ),
                        ),
                    transports =
                        listOf(
                            TransportRow.Live(
                                TransportStatus(
                                    TransportKind.Bluetooth,
                                    TransportHealth.Healthy,
                                    linked = 2,
                                    nearby = 4,
                                    blindedAdvert = true,
                                ),
                            ),
                            TransportRow.Live(TransportStatus(TransportKind.WifiAware, TransportHealth.Healthy, linked = 1, nearby = 3)),
                            TransportRow.Live(TransportStatus(TransportKind.DirectLink, TransportHealth.Healthy, linked = 0, nearby = 0)),
                        ),
                ),
            presence = PresenceKeyInfo(ownKeyHeld = true, keyHolders = setOf("a1b2c3d4e5f6"), directContacts = 2),
            health = TransportHealth.Healthy,
            lastCrash = PREVIEW_CRASH,
            moderationLatched = false,
            now = PREVIEW_NOW,
            snackbarHostState = remember { SnackbarHostState() },
            onBack = {},
            onRestartMesh = {},
            onScan = {},
            onOpenCrashLog = {},
            onResetModeration = {},
        )
    }

@Preview(showBackground = true)
@Composable
fun DiagnosticsScreenEmptyDegradedPreview() =
    KnitPreview {
        DiagnosticsScreenContent(
            state =
                DiagnosticsUiState(
                    myNodeId = "8f3a2b1c9d4e",
                    myName = "Ada Lovelace",
                    transports = listOf(TransportRow.Absent(TransportKind.WifiAware, PlaneSupport.NeedsAndroid12)),
                    radios = RadioSupport(bluetooth = PlaneSupport.Supported, wifiAware = PlaneSupport.NeedsAndroid12),
                ),
            health = TransportHealth.Degraded,
            lastCrash = null,
            // No crash report *and* a latched model is the realistic pairing, not a contrived one: the
            // native crash the latch reacts to is precisely the kind CrashHandler cannot capture.
            moderationLatched = true,
            now = PREVIEW_NOW,
            snackbarHostState = remember { SnackbarHostState() },
            onBack = {},
            onRestartMesh = {},
            onScan = {},
            onOpenCrashLog = {},
            onResetModeration = {},
        )
    }

@Preview(showBackground = true)
@Composable
fun BleLinkCapRowPreview() =
    KnitPreview {
        Column {
            BleLinkCapRow(cap = null, onSet = {})
            BleLinkCapRow(cap = 2, onSet = {})
        }
    }

@Preview(showBackground = true)
@Composable
fun BlePhyModeRowPreview() =
    KnitPreview {
        BlePhyModeRow(mode = CodedPhyMode.AUTO, onSet = {})
    }
