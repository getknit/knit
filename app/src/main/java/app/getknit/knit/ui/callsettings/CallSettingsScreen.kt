package app.getknit.knit.ui.callsettings

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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.getknit.knit.R
import app.getknit.knit.call.OwnIceServers
import app.getknit.knit.call.ProbeResult
import app.getknit.knit.call.ProbeVerdict
import app.getknit.knit.call.SttModelState
import app.getknit.knit.data.settings.CallIntake
import app.getknit.knit.di.CALLS_WIRED
import app.getknit.knit.ui.call.mb
import app.getknit.knit.ui.components.BarTitle
import app.getknit.knit.ui.components.noAutofillMenu
import app.getknit.knit.ui.fullScreenCalls
import app.getknit.knit.ui.openFullScreenCallSettings
import app.getknit.knit.ui.preview.KnitPreview
import app.getknit.knit.ui.rememberOnResume
import org.koin.androidx.compose.koinViewModel

/**
 * Calls (experimental, docs/VOICE_CALLS.md §3.14, ADR 2026-10.mr88): whether this phone takes incoming live and mesh
 * calls (C-3.3-7), a switch per kind the build wires, and whether they ring on the lock screen (Android 14+); "Relay
 * all calls" where live calls are wired (`LIVE_CALLS_WIRED`), and the person's own STUN and TURN servers, each replacing
 * what the relays supply, where own servers are too (`OWN_ICE_WIRED`, debug builds only for now); the transcripts
 * and captions switch and their speech model (C-3.11-9, C-3.7-5) where speech is (`STT_WIRED`). Reached only where a
 * call kind is wired (`CALL_SETTINGS_WIRED`): the route is not registered otherwise.
 */
@Composable
fun CallSettingsScreen(
    onBack: () -> Unit,
    viewModel: CallSettingsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val loaded by viewModel.loaded.collectAsStateWithLifecycle()
    val transcripts by viewModel.transcripts.collectAsStateWithLifecycle()
    val intake by viewModel.intake.collectAsStateWithLifecycle()
    val preferMesh by viewModel.preferMesh.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // The route exists only where calls are wired (`CALL_SETTINGS_WIRED` is `CALLS_WIRED`); re-read on every resume,
    // since the switch lives on a system page.
    val fullScreen = rememberOnResume { fullScreenCalls(context, callsWired = CALLS_WIRED) }
    val snackbarHostState = remember { SnackbarHostState() }
    val saved = stringResource(R.string.call_settings_saved)
    val busy = stringResource(R.string.call_settings_test_busy)
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            snackbarHostState.showSnackbar(
                when (event) {
                    CallSettingsEvent.Saved -> saved
                    CallSettingsEvent.TestBusy -> busy
                },
            )
        }
    }
    CallSettingsScreenContent(
        state = state,
        live = viewModel.live,
        ownServers = viewModel.ownServers,
        walkie = viewModel.walkie,
        intake = intake,
        fullScreen = fullScreen,
        onOpenFullScreenSettings = { openFullScreenCallSettings(context) },
        onToggleTakeLive = viewModel::setTakeLive,
        onToggleTakeWalkie = viewModel::setTakeWalkie,
        choosesFirstCall = viewModel.choosesFirstCall,
        preferMesh = preferMesh,
        onTogglePreferMesh = viewModel::setPreferMesh,
        loaded = loaded,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onToggleRelayAll = viewModel::setRelayAll,
        onToggleStun = viewModel::setStunOn,
        onToggleTurn = viewModel::setTurnOn,
        onEdit = viewModel::editDraft,
        onSave = viewModel::save,
        onTest = viewModel::test,
        transcripts = transcripts,
        onToggleTranscripts = viewModel::setTranscripts,
        onDownloadSpeechModel = viewModel::downloadSpeechModel,
        onCancelSpeechModel = viewModel::cancelSpeechModel,
        onRemoveSpeechModel = viewModel::removeSpeechModel,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CallSettingsScreenContent(
    state: CallSettingsUiState,
    onBack: () -> Unit,
    // "Relay all calls" and the live switch in "Incoming calls". Off where live calls are not wired.
    live: Boolean = true,
    // The own servers' rows, their Test and Save. Off where own servers are not wired (release builds for now).
    ownServers: Boolean = live,
    // Whether mesh calls are wired, so "Incoming calls" has a mesh switch.
    walkie: Boolean = false,
    intake: CallIntake = CallIntake(),
    // Whether an incoming call can ring full screen; null, and no line, where there is nothing to ask (below Android 14).
    fullScreen: Boolean? = null,
    onOpenFullScreenSettings: () -> Unit = {},
    onToggleTakeLive: (Boolean) -> Unit = {},
    onToggleTakeWalkie: (Boolean) -> Unit = {},
    // "Prefer mesh calls": shown where the build wires both kinds, so the chat has a choice of which to put first.
    choosesFirstCall: Boolean = live && walkie,
    preferMesh: Boolean = false,
    onTogglePreferMesh: (Boolean) -> Unit = {},
    loaded: Boolean = true,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onToggleRelayAll: (Boolean) -> Unit = {},
    onToggleStun: (Boolean) -> Unit = {},
    onToggleTurn: (Boolean) -> Unit = {},
    onEdit: ((CallServerDraft) -> CallServerDraft) -> Unit = {},
    onSave: () -> Unit = {},
    onTest: () -> Unit = {},
    // The transcripts and captions section; null, and no section, where the speech model is not wired.
    transcripts: TranscriptsUi? = null,
    onToggleTranscripts: (Boolean) -> Unit = {},
    onDownloadSpeechModel: () -> Unit = {},
    onCancelSpeechModel: () -> Unit = {},
    onRemoveSpeechModel: () -> Unit = {},
) {
    Scaffold(
        modifier = Modifier.testTag("screen_call_settings"),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                title = { BarTitle(stringResource(R.string.settings_calls_title)) },
                actions = {
                    if (ownServers) {
                        TextButton(onClick = onSave, enabled = state.canSave, modifier = Modifier.testTag("call_settings_save")) {
                            Text(stringResource(R.string.action_save))
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
            IncomingCallRows(live, walkie, intake, onToggleTakeLive, onToggleTakeWalkie)
            FullScreenRow(fullScreen, onOpenFullScreenSettings)
            if (choosesFirstCall) {
                HorizontalDivider()
                ToggleRow(
                    title = stringResource(R.string.call_settings_prefer_mesh_title),
                    subtitle = stringResource(R.string.call_settings_prefer_mesh_subtitle),
                    enabled = preferMesh,
                    onToggle = onTogglePreferMesh,
                    modifier = Modifier.testTag("call_settings_prefer_mesh"),
                )
            }
            if (live) {
                HorizontalDivider()
                ToggleRow(
                    title = stringResource(R.string.settings_relay_calls_title),
                    subtitle =
                        stringResource(
                            when {
                                state.turnAvailable -> R.string.settings_relay_calls_subtitle
                                state.relayAll -> R.string.settings_relay_calls_no_turn_on
                                else -> R.string.settings_relay_calls_no_turn
                            },
                        ),
                    enabled = state.relayAll,
                    onToggle = onToggleRelayAll,
                    toggles = state.relayAllToggles,
                    modifier = Modifier.testTag("call_settings_relay_all"),
                )
            }
            if (ownServers) {
                HorizontalDivider()
                ToggleRow(
                    title = stringResource(R.string.call_settings_stun_title),
                    subtitle = stringResource(R.string.call_settings_stun_subtitle),
                    enabled = state.saved.stunOn,
                    onToggle = onToggleStun,
                    modifier = Modifier.testTag("call_settings_stun_switch"),
                )
                // Hidden until the draft holds what was stored, so a field never flashes empty over a saved server.
                if (loaded && state.saved.stunOn) StunFields(state, onEdit)
                HorizontalDivider()
                ToggleRow(
                    title = stringResource(R.string.call_settings_turn_title),
                    subtitle = stringResource(R.string.call_settings_turn_subtitle),
                    enabled = state.saved.turnOn,
                    onToggle = onToggleTurn,
                    modifier = Modifier.testTag("call_settings_turn_switch"),
                )
                if (loaded && state.saved.turnOn) TurnFields(state, onEdit)
                Note(stringResource(R.string.call_settings_turn_disclosure))
                HorizontalDivider()
                TestRow(state, onTest)
            }
            if (transcripts != null) {
                HorizontalDivider()
                ToggleRow(
                    title = stringResource(R.string.call_settings_transcripts_title),
                    subtitle = stringResource(R.string.call_settings_transcripts_subtitle),
                    enabled = transcripts.on,
                    onToggle = onToggleTranscripts,
                    modifier = Modifier.testTag("call_settings_transcripts"),
                )
                // Switched off too, so a model already here can be removed and one can be had over Wi-Fi in advance.
                SpeechModelRow(transcripts, onDownloadSpeechModel, onCancelSpeechModel, onRemoveSpeechModel)
            }
        }
    }
}

/** C-3.3-7: one switch per kind this build wires. Off, a call of that kind never rings and its caller hears "No answer". */
@Composable
private fun IncomingCallRows(
    live: Boolean,
    walkie: Boolean,
    intake: CallIntake,
    onToggleTakeLive: (Boolean) -> Unit,
    onToggleTakeWalkie: (Boolean) -> Unit,
) {
    if (live) {
        ToggleRow(
            title = stringResource(R.string.call_settings_take_live_title),
            subtitle = stringResource(R.string.call_settings_take_live_subtitle),
            enabled = intake.live,
            onToggle = onToggleTakeLive,
            modifier = Modifier.testTag("call_settings_take_live"),
        )
    }
    if (walkie) {
        ToggleRow(
            title = stringResource(R.string.call_settings_take_walkie_title),
            subtitle = stringResource(R.string.call_settings_take_walkie_subtitle),
            enabled = intake.walkie,
            onToggle = onToggleTakeWalkie,
            modifier = Modifier.testTag("call_settings_take_walkie"),
        )
    }
}

/**
 * Whether a call can ring on the lock screen, in Settings' unused-app shape: a status line, and "Open settings" while it
 * can't — the switch is on a system page no prompt of ours can flip. Absent where there is nothing to ask.
 */
@Composable
private fun FullScreenRow(
    fullScreen: Boolean?,
    onOpenSettings: () -> Unit,
) {
    fullScreen ?: return
    Column(modifier = Modifier.fillMaxWidth().testTag("call_settings_full_screen")) {
        Note(stringResource(if (fullScreen) R.string.call_settings_full_screen_on else R.string.call_settings_full_screen_off))
        if (!fullScreen) {
            TextButton(onClick = onOpenSettings, modifier = Modifier.testTag("call_settings_full_screen_settings")) {
                Text(stringResource(R.string.action_open_settings))
            }
        }
    }
}

@Composable
private fun StunFields(
    state: CallSettingsUiState,
    onEdit: ((CallServerDraft) -> CallServerDraft) -> Unit,
) {
    val draft = state.draft
    val error =
        when {
            draft.badStunUrl != null -> stringResource(R.string.call_settings_stun_invalid, draft.badStunUrl.orEmpty())
            draft.tooManyStun -> stringResource(R.string.call_settings_stun_too_many)
            draft.stunRepeats -> stringResource(R.string.call_settings_stun_repeated)
            else -> null
        }
    OutlinedTextField(
        value = draft.stunText,
        onValueChange = { text -> onEdit { it.copy(stunText = text) } },
        label = { Text(stringResource(R.string.call_settings_stun_label)) },
        isError = error != null,
        supportingText = { Text(error ?: stringResource(R.string.call_settings_stun_hint)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        minLines = 2,
        modifier = Modifier.fillMaxWidth().testTag("call_settings_stun_field").noAutofillMenu(),
    )
    if (state.stunWaiting) Note(stringResource(R.string.call_settings_waiting))
}

@Composable
private fun TurnFields(
    state: CallSettingsUiState,
    onEdit: ((CallServerDraft) -> CallServerDraft) -> Unit,
) {
    val draft = state.draft
    var showPass by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = draft.turnUrl,
        onValueChange = { text -> onEdit { it.copy(turnUrl = text) } },
        label = { Text(stringResource(R.string.call_settings_turn_url_label)) },
        singleLine = true,
        isError = draft.turnUrlBad,
        supportingText = {
            Text(stringResource(if (draft.turnUrlBad) R.string.call_settings_turn_url_invalid else R.string.call_settings_turn_url_hint))
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth().testTag("call_settings_turn_url").noAutofillMenu(),
    )
    OutlinedTextField(
        value = draft.turnUser,
        onValueChange = { text -> onEdit { it.copy(turnUser = text) } },
        label = { Text(stringResource(R.string.call_settings_turn_user_label)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("call_settings_turn_user").noAutofillMenu(),
    )
    OutlinedTextField(
        value = draft.turnPass,
        onValueChange = { text -> onEdit { it.copy(turnPass = text) } },
        label = { Text(stringResource(R.string.call_settings_turn_pass_label)) },
        singleLine = true,
        visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { showPass = !showPass }) {
                Icon(
                    if (showPass) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription =
                        stringResource(if (showPass) R.string.call_settings_hide_password else R.string.call_settings_show_password),
                )
            }
        },
        modifier = Modifier.fillMaxWidth().testTag("call_settings_turn_pass"),
    )
    if (!draft.turnSavable && !draft.turnUrlBad) Note(stringResource(R.string.call_settings_turn_incomplete))
    if (state.turnWaiting) Note(stringResource(R.string.call_settings_waiting))
}

@Composable
private fun TestRow(
    state: CallSettingsUiState,
    onTest: () -> Unit,
) {
    OutlinedButton(onClick = onTest, enabled = state.canTest, modifier = Modifier.testTag("call_settings_test")) {
        Text(stringResource(if (state.testing) R.string.call_settings_testing else R.string.call_settings_test))
    }
    val result = state.result ?: return
    Column(modifier = Modifier.testTag("call_settings_result"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        verdictText(result.stun, stun = true)?.let { Text(stringResource(R.string.call_settings_result_stun, it)) }
        verdictText(result.turn, stun = false)?.let { Text(stringResource(R.string.call_settings_result_turn, it)) }
    }
}

/** One kind's line, never naming an address; null for a kind the Test did not try. */
@Composable
private fun verdictText(
    verdict: ProbeVerdict,
    stun: Boolean,
): String? =
    when (verdict) {
        ProbeVerdict.NotTried -> null
        ProbeVerdict.Reached -> stringResource(if (stun) R.string.call_settings_stun_reached else R.string.call_settings_turn_reached)
        ProbeVerdict.AuthFailed -> stringResource(R.string.call_settings_auth_failed)
        is ProbeVerdict.Refused -> stringResource(R.string.call_settings_refused, verdict.code)
        ProbeVerdict.NoAnswer -> stringResource(R.string.call_settings_no_answer)
    }

/**
 * The speech model behind the walkie's transcript and a live call's captions (docs/VOICE_CALLS.md C-3.11-9, C-3.7-5):
 * what it costs and Download, the progress and Cancel while it comes (the download runs on the app's scope and a
 * foreground service, so leaving the screen does not stop it), or that it is here and Remove.
 */
@Composable
private fun SpeechModelRow(
    ui: TranscriptsUi,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
) {
    val size = mb(LocalContext.current, ui.bytes)
    Column(
        modifier = Modifier.fillMaxWidth().testTag("call_settings_speech_model"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.call_settings_speech_model_title), style = MaterialTheme.typography.titleSmall)
        when (val model = ui.model) {
            SttModelState.Absent, is SttModelState.Failed -> {
                val failed = model as? SttModelState.Failed
                Text(
                    if (failed != null) {
                        stringResource(R.string.call_settings_speech_model_failed, failed.reason)
                    } else {
                        stringResource(R.string.call_settings_speech_model_absent, size)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (failed != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (ui.metered) Note(stringResource(R.string.call_settings_speech_model_metered))
                FilledTonalButton(onClick = onDownload, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                    Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Text(
                        stringResource(
                            if (failed != null) R.string.call_settings_speech_model_retry else R.string.call_settings_speech_model_download,
                        ),
                        Modifier.padding(start = ButtonDefaults.IconSpacing),
                    )
                }
            }

            is SttModelState.Downloading -> {
                LinearProgressIndicator(
                    progress = { if (model.total > 0) (model.done.toFloat() / model.total).coerceIn(0f, 1f) else 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(
                            R.string.call_settings_speech_model_downloading,
                            mb(LocalContext.current, model.done),
                            mb(LocalContext.current, model.total),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.call_settings_speech_model_cancel)) }
                }
            }

            SttModelState.Ready -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.call_settings_speech_model_ready, size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onRemove) { Text(stringResource(R.string.call_settings_speech_model_remove)) }
                }
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A titled switch row, the row owning the toggle (Settings' own idiom). */
@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    // False greys the row and takes no touch: what "Relay all calls" is with no TURN server to relay through.
    toggles: Boolean = true,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .toggleable(value = enabled, enabled = toggles, onValueChange = onToggle, role = Role.Switch),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = enabled, onCheckedChange = null, enabled = toggles)
    }
}

// Nothing of the person's own: every call's servers come from the relays.
@Preview(showBackground = true)
@Composable
fun CallSettingsScreenPreview() =
    KnitPreview {
        CallSettingsScreenContent(state = CallSettingsUiState(turnAvailable = true), onBack = {})
    }

// No relay issues this phone TURN (a free ticket, say) and no own server: "Relay all calls" cannot be switched on.
@Preview(showBackground = true)
@Composable
fun CallSettingsScreenNoTurnPreview() =
    KnitPreview {
        CallSettingsScreenContent(state = CallSettingsUiState(), onBack = {})
    }

// A build with both call kinds, the person taking mesh calls but no live ones and preferring mesh calls.
@Preview(showBackground = true)
@Composable
fun CallSettingsScreenIncomingPreview() =
    KnitPreview {
        CallSettingsScreenContent(
            state = CallSettingsUiState(turnAvailable = true),
            onBack = {},
            walkie = true,
            intake = CallIntake(live = false, walkie = true),
            fullScreen = false,
            preferMesh = true,
        )
    }

// A build with speech: transcripts on, the speech model not yet on the phone, and the phone on mobile data.
@Preview(showBackground = true, heightDp = 1000)
@Composable
fun CallSettingsScreenTranscriptsPreview() =
    KnitPreview {
        CallSettingsScreenContent(
            state = CallSettingsUiState(turnAvailable = true),
            onBack = {},
            transcripts = TranscriptsUi(on = true, model = SttModelState.Absent, bytes = 142_315_154, metered = true),
        )
    }

// Both kinds switched on and saved, after a Test whose TURN password was wrong.
@Preview(showBackground = true)
@Composable
fun CallSettingsScreenOwnServersPreview() =
    KnitPreview {
        val saved =
            OwnIceServers(
                stunOn = true,
                stunUrls = listOf("stun:stun.example.org:3478"),
                turnOn = true,
                turnUrl = "turns:turn.example.org:5349",
                turnUser = "ada",
                turnPass = "correct horse",
            )
        CallSettingsScreenContent(
            state =
                CallSettingsUiState(
                    relayAll = true,
                    turnAvailable = true,
                    saved = saved,
                    draft = CallServerDraft.of(saved),
                    result = ProbeResult(stun = ProbeVerdict.Reached, turn = ProbeVerdict.AuthFailed),
                ),
            onBack = {},
        )
    }
