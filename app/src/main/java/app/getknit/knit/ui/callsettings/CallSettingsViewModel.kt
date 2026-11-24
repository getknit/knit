package app.getknit.knit.ui.callsettings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.getknit.knit.call.IceProbe
import app.getknit.knit.call.OwnIceServers
import app.getknit.knit.call.ProbeResult
import app.getknit.knit.call.SttModelState
import app.getknit.knit.call.StunServers
import app.getknit.knit.call.TurnUrls
import app.getknit.knit.call.VoxSensitivity
import app.getknit.knit.data.settings.CallIntake
import app.getknit.knit.data.settings.CallPreferenceStore
import app.getknit.knit.data.settings.IncomingCallStore
import app.getknit.knit.data.settings.OwnIceStore
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.data.settings.TranscriptStore
import app.getknit.knit.ui.call.stt.SttModelStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The fields as typed: never bound to the store, whose async writes would race the keystrokes (rules/coding.md). */
data class CallServerDraft(
    val stunText: String = "",
    val turnUrl: String = "",
    val turnUser: String = "",
    val turnPass: String = "",
) {
    /** One URL per line, blank lines dropped. */
    val stunUrls: List<String> get() = stunText.lines().map(String::trim).filter(String::isNotEmpty)

    /** The first line [StunServers.accepts] refuses, or null. */
    val badStunUrl: String? get() = stunUrls.firstOrNull { !StunServers.accepts(it) }

    val tooManyStun: Boolean get() = stunUrls.size > StunServers.MAX

    val stunRepeats: Boolean get() = stunUrls.distinct().size != stunUrls.size

    /** Nothing typed, or a list a call takes. */
    val stunSavable: Boolean get() = stunUrls.isEmpty() || (badStunUrl == null && !tooManyStun && !stunRepeats)

    val turnEmpty: Boolean get() = turnUrl.isBlank() && turnUser.isEmpty() && turnPass.isEmpty()

    val turnUrlBad: Boolean get() = turnUrl.isNotBlank() && !TurnUrls.accepts(turnUrl.trim())

    /** Nothing typed, or a server a call takes. */
    val turnSavable: Boolean get() = turnEmpty || asServers(OwnIceServers()).turnValid

    /** [base]'s switches with these fields. */
    fun asServers(base: OwnIceServers): OwnIceServers =
        base.copy(stunUrls = stunUrls, turnUrl = turnUrl.trim(), turnUser = turnUser, turnPass = turnPass)

    override fun toString(): String = "CallServerDraft(stun=$stunUrls, turnUrl=$turnUrl)"

    companion object {
        fun of(saved: OwnIceServers) =
            CallServerDraft(
                stunText = saved.stunUrls.joinToString("\n"),
                turnUrl = saved.turnUrl,
                turnUser = saved.turnUser,
                turnPass = saved.turnPass,
            )
    }
}

data class CallSettingsUiState(
    /** "Relay all calls" (C-3.7-3). */
    val relayAll: Boolean = false,
    /**
     * Whether a TURN server is there for a relay-only call: a connected relay that issues this phone TURN, or the
     * person's own, switched on and valid. Without one such a call gathers nothing and never connects.
     */
    val turnAvailable: Boolean = false,
    /** What the store holds: the switches, and the fields a call reads. */
    val saved: OwnIceServers = OwnIceServers(),
    val draft: CallServerDraft = CallServerDraft(),
    val testing: Boolean = false,
    /** The last Test's answer, cleared by an edit: it described other fields. */
    val result: ProbeResult? = null,
) {
    val dirty: Boolean get() = draft != CallServerDraft.of(saved)

    val canSave: Boolean get() = dirty && draft.stunSavable && draft.turnSavable

    /** Something typed that a probe can try. */
    val canTest: Boolean
        get() = !testing && (testStun.isNotEmpty() || testTurn)

    internal val testStun: List<String> get() = if (draft.stunSavable) draft.stunUrls else emptyList()

    internal val testTurn: Boolean get() = !draft.turnEmpty && draft.turnSavable

    /** A switch is on but what is saved for it does not pass its rule: the spools' servers are used meanwhile. */
    val stunWaiting: Boolean get() = saved.stunOn && !saved.stunValid

    val turnWaiting: Boolean get() = saved.turnOn && !saved.turnValid

    /** "Relay all calls" can be switched on only with TURN available, and off always: a switch never traps the person. */
    val relayAllToggles: Boolean get() = turnAvailable || relayAll
}

/**
 * The transcripts and captions section (docs/VOICE_CALLS.md C-3.11-9, C-3.7-5): the switch, where the speech model stands on this phone,
 * what it costs to fetch, and whether the route a fetch would take now is mobile data or under Data Saver.
 */
data class TranscriptsUi(
    val on: Boolean = true,
    val model: SttModelState = SttModelState.Absent,
    val bytes: Long = 0,
    val metered: Boolean = false,
)

/** One-shot outcomes for the screen's snackbar. */
sealed interface CallSettingsEvent {
    data object Saved : CallSettingsEvent

    /** A call holds the media, so the probe was refused. */
    data object TestBusy : CallSettingsEvent
}

/**
 * The Calls screen (docs/VOICE_CALLS.md §3.14, ADR 2026-10.mr88): which incoming calls this phone takes (C-3.3-7),
 * one switch per kind the build wires, "Prefer mesh calls" where it wires both (C-3.11-3), "Relay all calls", the
 * person's own STUN and TURN servers (debug builds only, `OWN_ICE_WIRED`) and the walkie's transcripts and live calls'
 * captions with their speech model (C-3.11-9, C-3.7-5).
 * The switches write as they are touched, as every Settings switch does; the fields are a draft, loaded once and
 * written by Save, and a Test tries the draft before it is saved.
 */
class CallSettingsViewModel(
    private val settings: SettingsStore,
    private val ownIce: OwnIceStore,
    // The own servers' Test; null, and no server, Test or Save rows, where own servers are not wired (OWN_ICE_WIRED).
    private val probe: IceProbe? = null,
    // The speech model and the transcripts switch; null, and no section, where they are not wired.
    private val stt: SttModelStore? = null,
    private val transcriptStore: TranscriptStore? = null,
    // C-3.3-7's switches; null, and no "Incoming calls" section, in a test that does not reach them.
    private val incoming: IncomingCallStore? = null,
    // "Prefer mesh calls" (C-3.11-3); null, and no row, in a test that does not reach it.
    private val preference: CallPreferenceStore? = null,
    /** Whether mesh calls are wired, so "Incoming calls" has a mesh switch. */
    val walkie: Boolean = false,
    /** Whether live calls are wired, so "Incoming calls" has a live switch and "Relay all calls" shows. */
    val live: Boolean = probe != null,
    /**
     * Whether a connected relay issues this phone TURN now (`RelayStatusRepository.turnAvailable`). No default: a build
     * that forgot to wire it would never let "Relay all calls" on where own servers are not wired.
     */
    relayTurn: Flow<Boolean>,
) : ViewModel() {
    /** Whether the own servers' rows show: the STUN and TURN switches and fields, their Test and Save. */
    val ownServers: Boolean = live && probe != null

    /** "Incoming calls": what each switch holds, on until the store's first read. */
    val intake: StateFlow<CallIntake> =
        (incoming?.intake ?: flowOf(CallIntake())).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CallIntake())

    /** Whether "Prefer mesh calls" shows: only a build with both kinds has a choice of which the chat puts first. */
    val choosesFirstCall: Boolean = live && walkie

    /** "Prefer mesh calls": what the switch holds, off until the store's first read. */
    val preferMesh: StateFlow<Boolean> =
        (preference?.preferMesh ?: flowOf(false)).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** "Hands-free sensitivity" (C-3.11-11): shown where mesh calls are wired, the default until the store's first read. */
    val voxSensitivity: StateFlow<VoxSensitivity> =
        (preference?.voxSensitivity ?: flowOf(VoxSensitivity.DEFAULT))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VoxSensitivity.DEFAULT)

    private val draft = MutableStateFlow(CallServerDraft())
    private val testing = MutableStateFlow(false)
    private val result = MutableStateFlow<ProbeResult?>(null)

    private val _events = MutableSharedFlow<CallSettingsEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<CallSettingsEvent> = _events.asSharedFlow()

    private val _loaded = MutableStateFlow(false)

    /** True once the draft holds what was stored, so the fields never flash empty over a saved server. */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    val state: StateFlow<CallSettingsUiState> =
        combine(combine(settings.callsRelayAll, relayTurn, ::Pair), ownIce.ownIce, draft, testing, result) {
            (relayAll, relayTurn),
            saved,
            draft,
            testing,
            result,
            ->
            // An own TURN server counts only where a call uses it: OverridingTurnSource is wired with the rows.
            val ownTurn = ownServers && saved.turnOn && saved.turnValid
            CallSettingsUiState(
                relayAll = relayAll,
                turnAvailable = relayTurn || ownTurn,
                saved = saved,
                draft = draft,
                testing = testing,
                result = result,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CallSettingsUiState())

    /** The transcripts section; null where it is not wired. The route is read again as the model's state moves. */
    val transcripts: StateFlow<TranscriptsUi?> =
        (
            if (stt == null || transcriptStore == null) {
                flowOf(null)
            } else {
                combine(transcriptStore.enabled, stt.state) { on, model ->
                    TranscriptsUi(on = on, model = model, bytes = stt.model.bytes, metered = stt.metered())
                }
            }
        ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch {
            draft.value = CallServerDraft.of(ownIce.ownIce.first())
            _loaded.value = true
        }
    }

    fun setTakeLive(value: Boolean) {
        val store = incoming ?: return
        viewModelScope.launch { store.setLive(value) }
    }

    fun setTakeWalkie(value: Boolean) {
        val store = incoming ?: return
        viewModelScope.launch { store.setWalkie(value) }
    }

    fun setPreferMesh(value: Boolean) {
        val store = preference ?: return
        viewModelScope.launch { store.setPreferMesh(value) }
    }

    fun setVoxSensitivity(value: VoxSensitivity) {
        val store = preference ?: return
        viewModelScope.launch { store.setVoxSensitivity(value) }
    }

    fun setRelayAll(value: Boolean) {
        viewModelScope.launch { settings.setCallsRelayAll(value) }
    }

    fun setStunOn(value: Boolean) {
        if (!ownServers) return
        viewModelScope.launch { ownIce.setStunOn(value) }
    }

    fun setTurnOn(value: Boolean) {
        if (!ownServers) return
        viewModelScope.launch { ownIce.setTurnOn(value) }
    }

    fun setTranscripts(value: Boolean) {
        val store = transcriptStore ?: return
        viewModelScope.launch { store.setEnabled(value) }
    }

    /** Fetches the speech model; the download runs on the app's scope, so it outlasts this screen. */
    fun downloadSpeechModel() {
        stt?.download()
    }

    fun cancelSpeechModel() {
        stt?.cancel()
    }

    fun removeSpeechModel() {
        val store = stt ?: return
        viewModelScope.launch { store.delete() }
    }

    fun editDraft(change: (CallServerDraft) -> CallServerDraft) {
        draft.update(change)
        result.value = null
    }

    /** Writes the fields as typed, each kind in one edit. Refused while anything typed fails its rule. */
    fun save() {
        val now = draft.value
        if (!ownServers) return
        if (!now.stunSavable || !now.turnSavable) return
        viewModelScope.launch {
            ownIce.setStunUrls(now.stunUrls)
            ownIce.setTurn(url = now.turnUrl.trim(), user = now.turnUser, pass = now.turnPass)
            // What was saved, so the draft compares equal to the store: the trimmed URL, the dropped blank lines.
            draft.value = CallServerDraft.of(now.asServers(OwnIceServers()))
            _events.emit(CallSettingsEvent.Saved)
        }
    }

    /** Tries what is typed, whether or not it is saved or switched on. */
    fun test() {
        val probe = probe ?: return
        val current = state.value.copy(draft = draft.value, testing = testing.value)
        if (!current.canTest) return
        val turn = if (current.testTurn) draft.value.asServers(OwnIceServers()).turnServer() else null
        testing.value = true
        result.value = null
        viewModelScope.launch {
            try {
                val answer = probe.probe(current.testStun, turn)
                if (answer == null) _events.emit(CallSettingsEvent.TestBusy) else result.value = answer
            } finally {
                testing.value = false
            }
        }
    }
}
