package app.getknit.knit.ui.call

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Bundle
import android.text.format.DateUtils
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.outlined.ClosedCaptionDisabled
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.SignalCellularAlt1Bar
import androidx.compose.material.icons.outlined.SignalCellularAlt2Bar
import androidx.compose.material.icons.outlined.WifiCalling3
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import app.getknit.knit.MainActivity
import app.getknit.knit.R
import app.getknit.knit.call.CallAudioRoute
import app.getknit.knit.call.CallCaptioner
import app.getknit.knit.call.CallKind
import app.getknit.knit.call.CallManager
import app.getknit.knit.call.CallPhase
import app.getknit.knit.call.CallRouting
import app.getknit.knit.call.CallState
import app.getknit.knit.call.CaptionStatus
import app.getknit.knit.call.Captions
import app.getknit.knit.call.CaptureWatch
import app.getknit.knit.call.SttModelState
import app.getknit.knit.call.SystemCallSync
import app.getknit.knit.call.TalkLink
import app.getknit.knit.call.TranscriptLine
import app.getknit.knit.call.WalkieStats
import app.getknit.knit.call.WalkieTranscriber
import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.data.settings.TranscriptStore
import app.getknit.knit.di.LIVE_CALLS_WIRED
import app.getknit.knit.di.STT_WIRED
import app.getknit.knit.di.WALKIE_WIRED
import app.getknit.knit.identity.Identity
import app.getknit.knit.mesh.protocol.CallPayload
import app.getknit.knit.notifications.MessageNotifier
import app.getknit.knit.ui.call.stt.SttModelStore
import app.getknit.knit.ui.chat.messagePreview
import app.getknit.knit.ui.components.Avatar
import app.getknit.knit.ui.preview.KnitPreview
import app.getknit.knit.ui.preview.PREVIEW_NOW
import app.getknit.knit.ui.theme.KnitTheme
import app.getknit.knit.ui.theme.LocalReduceMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * The one call screen (experimental, `CALLS_WIRED`): the peer's face and name, incoming Answer/Decline, then status, the
 * route (direct or relayed), mute, speaker and hang up, with who is calling ([CallerContext]) in the middle — or, once
 * the header's toggle asks for it, a live diagnostics card ([CallDiagnostics]) in its place; a walkie session (§3.11)
 * trades mute and speaker for a hold-to-talk button over [WalkieTalk], and with its diagnostics toggled off shows the
 * transcript of what the peer said instead ([WalkieTranscript], C-3.11-9).
 * [CallScreen] is the stateless half the previews draw.
 * Shows over the lock screen for the full-screen ring. While it is in the foreground it starts [CallService], the
 * microphone's foreground service, once the call is live and RECORD_AUDIO is granted.
 */
class CallActivity :
    ComponentActivity(),
    KoinComponent {
    private val calls: CallManager by inject()
    private val peers: PeerRepository by inject()
    private val messages: MessageRepository by inject()
    private val groups: GroupRepository by inject()
    private val settings: SettingsStore by inject()
    private val identity: Identity by inject()
    private val system: SystemCallSync by inject()

    // Each kind's and speech's own objects, looked up only where the build wires them (`di/CallWiring`, ADR
    // 2026-10.swvp), so a build without one never reaches its classes. Null in a build without live calls: no call is
    // ever live there.
    private val media: WebRtcCallMedia? by lazy { if (LIVE_CALLS_WIRED) getKoin().get<WebRtcCallMedia>() else null }

    // Null in a build with the walkie off (`di/CallWiring.WALKIE_WIRED`): no call is ever a walkie there.
    private val walkie: WalkieTalk? = if (WALKIE_WIRED) getKoin().get<WalkieTalk>() else null

    // The walkie's transcript (null where the walkie is off) and a live call's captions (null where live calls are),
    // and the speech model both use: all null where speech is off (`STT_WIRED`).
    private val transcriber: WalkieTranscriber? = if (STT_WIRED && WALKIE_WIRED) getKoin().get<WalkieTranscriber>() else null
    private val captioner: CallCaptioner? = if (STT_WIRED && LIVE_CALLS_WIRED) getKoin().get<CallCaptioner>() else null
    private val stt: SttModelStore? = if (STT_WIRED) getKoin().get<SttModelStore>() else null
    private val transcriptStore: TranscriptStore? = if (STT_WIRED) getKoin().get<TranscriptStore>() else null
    private var pendingAnswer: String? = null
    private var serviceStarted = false

    private val micRequest =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val id = pendingAnswer
            pendingAnswer = null
            if (granted && id != null) {
                answer(id)
            } else if (!granted) {
                Toast.makeText(this, "Knit needs the microphone to answer", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        handle(intent)
        lifecycleScope.launch {
            // Latest: a Call back from a dropped call's screen is a new call, and it must cut that screen's linger short.
            calls.state.collectLatest { state ->
                // Both kinds play on the call's own stream (§3.13): the walkie's bursts too, on the route Telecom holds.
                volumeControlStream = if (state != null) AudioManager.STREAM_VOICE_CALL else AudioManager.USE_DEFAULT_STREAM_TYPE
                when {
                    state == null -> {
                        finish()
                    }

                    state.phase == CallPhase.Ended -> {
                        // The service stops with the call; a Call back starts it again.
                        serviceStarted = false
                        delay(if (state.dropped) DROPPED_LINGER_MS else ENDED_LINGER_MS)
                        finish()
                    }

                    state.phase != CallPhase.Alerting -> {
                        startService()
                    }
                }
            }
        }
        setContent { KnitTheme { Host() } }
    }

    @Composable
    private fun Host() {
        val state by calls.state.collectAsState()
        val peerId = state?.peerId
        var name by remember { mutableStateOf("") }
        var avatarHash by remember { mutableStateOf<String?>(null) }
        var caller by remember { mutableStateOf<CallerContext?>(null) }
        LaunchedEffect(peerId) {
            if (peerId == null) return@LaunchedEffect
            name = callerName(peers, peerId)
            avatarHash = peers.find(peerId)?.avatarHash
            val unnamed = getString(R.string.group_unnamed)
            caller = runCatching { callerContext(peerId, peers, messages, groups, settings, identity, unnamed) }.getOrNull()
        }
        // Over the lock screen the last message stays hidden; unlocking with the call up shows it without a restart.
        val keyguard = remember { getSystemService(KeyguardManager::class.java) }
        var locked by remember { mutableStateOf(keyguard?.isKeyguardLocked == true) }
        LaunchedEffect(Unit) {
            while (true) {
                locked = keyguard?.isKeyguardLocked == true
                delay(POLL_MS)
            }
        }
        val hideFlagged by settings.contentFilteringEnabled.collectAsState(initial = true)
        // The selected candidate pair and the media's stats, read while the media is up: the pair can move (a relay
        // pair first, a direct one later), and the stats are the diagnostics card's.
        val mediaUp = state?.walkie != true && state?.phase in MEDIA_UP
        var route by remember { mutableStateOf<String?>(null) }
        var stats by remember { mutableStateOf<CallStats?>(null) }
        var capture by remember { mutableStateOf<CaptureStatus?>(null) }
        // One judge per call: its hysteresis spans the call's readings, never two calls'.
        val judge = remember(state?.id) { QualityJudge() }
        var quality by remember(state?.id) { mutableStateOf<Quality?>(null) }
        LaunchedEffect(mediaUp) {
            while (mediaUp) {
                route = calls.route()
                stats = media?.stats()
                capture = media?.captureStatus()
                quality = judge.next(stats)
                delay(POLL_MS)
            }
        }
        // The walkie's numbers, read as often as the media's while its session is up.
        val walkieUp = state?.walkie == true && state?.phase == CallPhase.Connected
        var walkieStats by remember { mutableStateOf<WalkieStats?>(null) }
        LaunchedEffect(walkieUp) {
            while (walkieUp) {
                walkieStats = walkie?.stats()
                delay(POLL_MS)
            }
        }
        val talk by (walkie?.talk ?: NO_TALK).collectAsState()
        val link by (walkie?.link ?: NO_LINK).collectAsState()
        val routing by system.routing.collectAsState()
        val lines by (transcriber?.lines ?: NO_LINES).collectAsState()
        val model by (stt?.state ?: NO_MODEL).collectAsState()
        // Off until read, so the card never flashes up on a phone that switched transcripts off or closed the offer.
        val transcriptsOn by (transcriptStore?.enabled ?: NO_SWITCH).collectAsState(initial = false)
        val offerDismissed by (transcriptStore?.offerDismissed ?: NO_SWITCH).collectAsState(initial = true)
        val captionsOn by (transcriptStore?.liveCaptions ?: NO_SWITCH).collectAsState(initial = false)
        val captions by (captioner?.captions ?: NO_CAPTIONS).collectAsState()
        val earpiece = routing?.current?.kind == CallAudioRoute.Kind.EARPIECE
        // Read again as the model's state moves: a tap on Download is when the route matters.
        val metered = remember(model) { stt?.metered() == true }
        CallScreen(
            state = state,
            name = name,
            avatarHash = avatarHash,
            route = route.takeIf { state?.walkie != true },
            stats = stats.takeIf { state?.walkie != true },
            quality = quality.takeIf { state?.walkie != true },
            capture = capture.takeIf { state?.walkie != true },
            caller = caller,
            locked = locked,
            hideFlagged = hideFlagged,
            onOpenChat = { peerId?.let(::openChat) },
            onAnswer = { state?.id?.let(::answer) },
            onDecline = { state?.id?.let { id -> lifecycleScope.launch { calls.decline(id) } } },
            onHangUp = { lifecycleScope.launch { calls.hangUp() } },
            // A live call back where the build takes one, else a mesh call.
            onCallBack = { peerId?.let { peer -> lifecycleScope.launch { calls.start(peer, callBackKind(calls)) } } },
            onClose = ::finish,
            onMute = { on -> lifecycleScope.launch { calls.setMuted(on) } },
            routing = routing,
            onRoute = { route -> lifecycleScope.launch { system.request(route) } },
            talk = talk,
            link = link,
            onTalk = { lifecycleScope.launch { walkie?.press() } },
            onTalkSend = { lifecycleScope.launch { walkie?.release() } },
            onTalkCancel = { lifecycleScope.launch { walkie?.cancel() } },
            walkieStats = walkieStats.takeIf { walkieUp },
            // No card with transcripts switched off, nor while the model is absent once its offer was closed.
            transcript =
                stt
                    ?.takeIf { transcriptsOn && (model == SttModelState.Ready || !offerDismissed) }
                    ?.let { store -> TranscriptUi(lines, model, store.model.bytes, metered) },
            onSttDownload = { stt?.download() },
            onSttCancel = { stt?.cancel() },
            onSttDismiss = { lifecycleScope.launch { transcriptStore?.dismissOffer() } },
            // The CC button only with transcripts switched on, its choice remembered from call to call (C-3.7-5).
            captions =
                stt
                    ?.takeIf { transcriptsOn && captioner != null }
                    ?.let { store -> CaptionsUi(captions, model, store.model.bytes, metered, earpiece) },
            captionsOn = captionsOn,
            onCaptions = { on -> lifecycleScope.launch { transcriptStore?.setLiveCaptions(on) } },
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent?.action == ACTION_ANSWER) intent.getStringExtra(EXTRA_ID)?.let(::answer)
    }

    /** The caller's DM thread in the app, as a message notification's tap opens it; the call keeps running. */
    private fun openChat(peerId: String) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(MessageNotifier.ACTION_OPEN_CHAT)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_ROUTE, "chat/$peerId"),
        )
    }

    private fun micGranted(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun answer(id: String) {
        if (!micGranted()) {
            pendingAnswer = id
            micRequest.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        lifecycleScope.launch { if (calls.answer(id)) startService() }
    }

    private fun startService() {
        if (serviceStarted || !micGranted()) return
        serviceStarted = true
        ContextCompat.startForegroundService(this, Intent(this, CallService::class.java))
    }

    companion object {
        const val ACTION_ANSWER = "app.getknit.knit.call.ANSWER"
        private const val EXTRA_ID = "call_id"
        private const val ENDED_LINGER_MS = 1_500L

        // A dropped call's screen offers Call back, so it stays up long enough to be read and tapped.
        private const val DROPPED_LINGER_MS = 60_000L
        private const val POLL_MS = 1_000L

        fun intent(
            context: Context,
            id: String,
        ): Intent = Intent(context, CallActivity::class.java).putExtra(EXTRA_ID, id)
    }
}

// Red and green as every phone's call screen draws them, whatever the theme: the hang-up must read as one at a glance.
internal val HangUpRed = Color(0xFFD93025)
private val AnswerGreen = Color(0xFF1E8E3E)
private val TalkingGreen = Color(0xFF34A853)
private val AvatarSize = 144.dp

// With the diagnostics card up the face gives it room: smaller on a tall phone, beside the name on a short one.
private val CompactAvatarSize = 104.dp
private val CompactRingReach = 16.dp
private val InlineAvatarSize = 72.dp

/** The kind a Call back places: a live call where the build takes one, else a mesh call (ADR 2026-10.swvp). */
private fun callBackKind(calls: CallManager): CallKind = if (calls.liveEnabled) CallKind.LIVE else CallKind.WALKIE

private val StackedMinHeight = 760.dp
private val RingReach = 40.dp
private val ActionSize = 72.dp
private val TalkSize = 120.dp
private val NO_TALK = MutableStateFlow<Talk>(Talk.Idle())
private val NO_LINK = MutableStateFlow<TalkLink?>(null)
private val NO_LINES = MutableStateFlow<List<TranscriptLine>>(emptyList())
private val NO_MODEL = MutableStateFlow<SttModelState>(SttModelState.Absent)
private val NO_SWITCH = emptyFlow<Boolean>()
private val NO_CAPTIONS = MutableStateFlow(Captions())
internal val ToggleSize = 64.dp
private const val RING_MS = 1_800
private const val RING_ALPHA = 0.28f
private const val ENDED_ALPHA = 0.5f
private const val MOVED_NOTE_MS = 4_000L
private const val SECONDS_PER_MINUTE = 60
private val RINGING = setOf(CallPhase.Calling, CallPhase.Ringing, CallPhase.Alerting, CallPhase.Connecting)
private val MEDIA_UP = setOf(CallPhase.Connecting, CallPhase.Connected, CallPhase.Reconnecting)

/** A call that connected, then lost its path past the reconnect window (§3.8): the one ended call offered Call back. */
private val CallState.dropped: Boolean
    get() = phase == CallPhase.Ended && reason == CallPayload.REASON_NO_ROUTE && connectedAt != null && !walkie

/**
 * The call screen proper: the peer's face and name, where the call stands, the controls its phase offers and, between
 * them, who is calling ([caller]) — or, while the media is up and the header's toggle is on, the diagnostics card for
 * [stats] instead. The toggle starts off unless [statsShown] (the previews' way to draw the card); in a walkie session,
 * off trades the card for the [transcript].
 */
@Composable
internal fun CallScreen(
    state: CallState?,
    name: String,
    avatarHash: String?,
    route: String?,
    stats: CallStats?,
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
    onHangUp: () -> Unit,
    onMute: (Boolean) -> Unit,
    onRoute: (CallAudioRoute) -> Unit,
    modifier: Modifier = Modifier,
    routing: CallRouting? = null,
    onCallBack: () -> Unit = {},
    onClose: () -> Unit = {},
    clock: () -> Long = System::currentTimeMillis,
    talk: Talk = Talk.Idle(),
    link: TalkLink? = null,
    onTalk: () -> Unit = {},
    onTalkSend: () -> Unit = {},
    onTalkCancel: () -> Unit = {},
    walkieStats: WalkieStats? = null,
    quality: Quality? = null,
    // The microphone's health (C-3.13-13): a dead or silenced one is the note the screen shows first.
    capture: CaptureStatus? = null,
    caller: CallerContext? = null,
    locked: Boolean = false,
    hideFlagged: Boolean = true,
    statsShown: Boolean = false,
    onOpenChat: () -> Unit = {},
    transcript: TranscriptUi? = null,
    onSttDownload: () -> Unit = {},
    onSttCancel: () -> Unit = {},
    onSttDismiss: () -> Unit = {},
    // A live call's captions (C-3.7-5): null draws no CC button; [captionsOn] is the button, [onCaptions] its tap.
    captions: CaptionsUi? = null,
    captionsOn: Boolean = false,
    onCaptions: (Boolean) -> Unit = {},
) {
    val phase = state?.phase
    var showStats by rememberSaveable { mutableStateOf(statsShown) }
    // A walkie's transcript is the CC button's for this call alone: shown until it is tapped off.
    var showTranscript by rememberSaveable { mutableStateOf(true) }
    val walkieUp = state?.walkie == true && phase == CallPhase.Connected
    // A walkie session's card in place of the media's: it has no media to read.
    val diagnostics: (@Composable (Modifier) -> Unit)? =
        when {
            !showStats || phase !in MEDIA_UP -> null
            walkieUp -> walkieStats?.let { s -> { m -> WalkieDiagnostics(s, m) } }
            else -> stats?.let { s -> { m -> CallDiagnostics(s, m, capture) } }
        }
    // With the diagnostics off and its CC button on, a walkie session shows what the peer said in the card's place
    // (C-3.11-9), and a live call its captions (C-3.7-5).
    val transcribed = transcript?.takeIf { walkieUp && showTranscript && !showStats }
    val captioned = captions?.takeIf { captionsOn && state?.walkie != true && phase in MEDIA_UP && diagnostics == null }
    val card = diagnostics != null || transcribed != null || captioned != null
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 16.dp)) {
            // With the card up, a phone too short for the stacked face puts it beside the name instead.
            val inline = card && maxHeight < StackedMinHeight
            val view = route?.let { routeView(it, stats) }
            val note = callNote(state, name, quality, view, capture)
            val callerShown = phase != null && phase != CallPhase.Ended && !walkieUp
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Header(
                    stats = if (phase in MEDIA_UP) showStats else null,
                    onStats = { showStats = it },
                    walkie = walkieUp,
                    captionsOn =
                        when {
                            walkieUp -> showTranscript.takeIf { transcript != null }
                            else -> captionsOn.takeIf { captions != null && phase in MEDIA_UP && state?.walkie != true }
                        },
                    // Turning captions on puts them in the card's place, so the diagnostics give way.
                    onCaptions = { on ->
                        if (on) showStats = false
                        if (walkieUp) showTranscript = on else onCaptions(on)
                    },
                )
                if (inline) {
                    Spacer(Modifier.height(8.dp))
                    InlineFace(state, name, avatarHash, caller?.status, view, note, clock)
                } else {
                    if (card) Spacer(Modifier.height(8.dp)) else Spacer(Modifier.weight(0.6f))
                    StackedFace(state, name, avatarHash, caller?.status, view, note, compact = card, clock)
                }
                if (transcribed != null) {
                    // The transcript scrolls itself, to keep its newest line in view.
                    Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 16.dp)) {
                        WalkieTranscript(transcribed, name, onSttDownload, onSttCancel, Modifier.fillMaxSize(), onSttDismiss)
                    }
                } else if (captioned != null) {
                    Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 16.dp)) {
                        LiveCaptions(captioned, name, onSttDownload, onSttCancel, onOff = { onCaptions(false) }, Modifier.fillMaxSize())
                    }
                } else if (diagnostics != null) {
                    // The room between the face and the controls; a short screen scrolls the card rather than clip it.
                    Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                        diagnostics(Modifier.verticalScroll(rememberScrollState()))
                    }
                } else if (callerShown && caller != null) {
                    // Who this is to you, and what was said last — the why of most calls — until the call ends.
                    Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                        CallerCard(caller, name, locked, hideFlagged, clock, onOpenChat)
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }
                if (walkieUp) {
                    WalkieControls(state, name, talk, link, routing, onHangUp, onRoute, onTalk, onTalkSend, onTalkCancel)
                } else {
                    Controls(
                        state,
                        routing,
                        onAnswer,
                        onDecline,
                        onHangUp,
                        onMute,
                        onRoute,
                        onCallBack,
                        onClose,
                    )
                }
            }
        }
    }
}

/** The face as every call screen draws it: the avatar centred over the name, the status and the route. */
@Composable
private fun StackedFace(
    state: CallState?,
    name: String,
    avatarHash: String?,
    status: String?,
    route: RouteView?,
    note: CallNote?,
    compact: Boolean,
    clock: () -> Long,
) {
    val phase = state?.phase
    val size = if (compact) CompactAvatarSize else AvatarSize
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        RingingAvatar(
            size = size,
            reach = if (compact) CompactRingReach else RingReach,
            // A call nothing reaches is not ringing anywhere (C-3.3-5), so the face doesn't pulse as if it were.
            pulsing = phase in RINGING && state?.unreachable != true,
            dimmed = phase == CallPhase.Ended,
        ) {
            Avatar(
                avatarHash = avatarHash,
                name = name,
                nodeId = state?.peerId,
                size = size,
                textStyle = if (compact) MaterialTheme.typography.displayMedium else MaterialTheme.typography.displayLarge,
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            name,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        if (!status.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                status,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            statusOf(state, clock),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        // One slot for the reason or the route, held empty otherwise so the face doesn't jump as the call moves on.
        Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
            when {
                phase == CallPhase.Calling && (state.unreachable || state.unproven) -> {
                    Text(
                        callingNote(state, name),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                }

                phase == CallPhase.Ended -> {
                    (if (state.dropped) "The connection was lost" else endedNote(state, name))?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                phase == CallPhase.Connected && state.walkie -> {
                    WalkiePill()
                }

                phase == CallPhase.Connected && route != null -> {
                    RoutePill(route)
                }
            }
        }
        // The note's own line under the pill, held while connected so a warning coming and going moves nothing.
        if (phase == CallPhase.Connected && !state.walkie) {
            Box(Modifier.heightIn(min = 24.dp), contentAlignment = Alignment.Center) { note?.let { NoteLine(it) } }
        }
    }
}

/** The face beside the name, status and route: what a short phone shows above the diagnostics card. */
@Composable
private fun InlineFace(
    state: CallState?,
    name: String,
    avatarHash: String?,
    status: String?,
    route: RouteView?,
    note: CallNote?,
    clock: () -> Long,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Avatar(
            avatarHash = avatarHash,
            name = name,
            nodeId = state?.peerId,
            size = InlineAvatarSize,
            textStyle = MaterialTheme.typography.headlineLarge,
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!status.isNullOrBlank()) {
                Text(
                    status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                statusOf(state, clock),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when {
                state?.phase == CallPhase.Calling && (state.unreachable || state.unproven) -> {
                    Text(
                        callingNote(state, name),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                state?.phase == CallPhase.Connected && state.walkie -> {
                    WalkiePill()
                }

                state?.phase == CallPhase.Connected && route != null -> {
                    RoutePill(route)
                }
            }
            note?.let { NoteLine(it) }
        }
    }
}

/**
 * The top row: the diagnostics card's toggle at its end while there is a card to show ([stats]). The CC button
 * ([captionsOn]: a live call's captions, C-3.7-5, or a [walkie]'s transcript, C-3.11-9) sits just before it, so the
 * controls below keep their three buttons. It holds its height when empty, so the face doesn't jump as the call connects.
 */
@Composable
private fun Header(
    stats: Boolean?,
    onStats: (Boolean) -> Unit,
    walkie: Boolean = false,
    captionsOn: Boolean? = null,
    onCaptions: (Boolean) -> Unit = {},
) {
    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Row(Modifier.align(Alignment.CenterEnd)) {
            if (captionsOn != null) {
                IconToggleButton(checked = captionsOn, onCheckedChange = onCaptions) {
                    Icon(
                        if (captionsOn) Icons.Filled.ClosedCaption else Icons.Outlined.ClosedCaptionDisabled,
                        contentDescription = if (captionsOn) "Hide captions" else "Show captions",
                    )
                }
            }
            if (stats != null) StatsToggle(stats, onStats, walkie)
        }
    }
}

@Composable
private fun StatsToggle(
    stats: Boolean,
    onStats: (Boolean) -> Unit,
    walkie: Boolean,
) {
    IconToggleButton(checked = stats, onCheckedChange = onStats) {
        val description =
            when {
                walkie && stats -> "Hide walkie diagnostics"
                walkie -> "Show walkie diagnostics"
                stats -> "Hide call diagnostics"
                else -> "Show call diagnostics"
            }
        Icon(Icons.Outlined.Insights, contentDescription = description)
    }
}

/** The avatar, with rings rippling out of it while the call is still being set up. */
@Composable
private fun RingingAvatar(
    size: Dp,
    reach: Dp,
    pulsing: Boolean,
    dimmed: Boolean,
    avatar: @Composable () -> Unit,
) {
    val ring = MaterialTheme.colorScheme.primary
    Box(Modifier.size(size + reach * 2), contentAlignment = Alignment.Center) {
        if (pulsing) {
            // Reduced motion keeps one still ring: the call is still ringing, it just doesn't move.
            val wave =
                if (LocalReduceMotion.current) {
                    0.5f
                } else {
                    val transition = rememberInfiniteTransition(label = "ring")
                    val animated by transition.animateFloat(
                        initialValue = 0f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(tween(RING_MS, easing = LinearEasing)),
                        label = "wave",
                    )
                    animated
                }
            Canvas(Modifier.matchParentSize()) {
                val inner = size.toPx() / 2
                val spread = this.size.minDimension / 2 - inner
                listOf(wave, (wave + 0.5f) % 1f).forEach { t ->
                    drawCircle(ring.copy(alpha = RING_ALPHA * (1f - t)), radius = inner + spread * t)
                }
            }
        }
        Box(Modifier.alpha(if (dimmed) ENDED_ALPHA else 1f)) { avatar() }
    }
}

@Composable
private fun Controls(
    state: CallState?,
    routing: CallRouting?,
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
    onHangUp: () -> Unit,
    onMute: (Boolean) -> Unit,
    onRoute: (CallAudioRoute) -> Unit,
    onCallBack: () -> Unit,
    onClose: () -> Unit,
) {
    // A fixed band, so the face and name above don't jump when the controls change or go.
    Box(Modifier.fillMaxWidth().heightIn(min = 112.dp), contentAlignment = Alignment.BottomCenter) {
        if (state?.dropped == true) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                CallAction("Close", Icons.Filled.Close, MaterialTheme.colorScheme.secondary, onClose)
                CallAction("Call back", Icons.Filled.Call, AnswerGreen, onCallBack)
            }
            return@Box
        }
        if (state == null || state.phase == CallPhase.Ended) return@Box
        if (state.phase == CallPhase.Alerting) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                CallAction("Decline", Icons.Filled.CallEnd, HangUpRed, onDecline)
                CallAction("Answer", Icons.Filled.Call, AnswerGreen, onAnswer)
            }
        } else {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Bottom,
            ) {
                CallToggle("Mute", if (state.muted) Icons.Filled.MicOff else Icons.Filled.Mic, state.muted, onMute)
                CallAction("End", Icons.Filled.CallEnd, HangUpRed, onHangUp)
                AudioRouteButton(routing, onRoute)
            }
        }
    }
}

/**
 * A connected walkie session's controls (§3.11): whether a burst can reach the peer, where the last one stands, why
 * this is a walkie when a live call dropped to one, and the hold-to-talk button between the hang-up and where its
 * audio goes (§3.13).
 */
@Composable
private fun WalkieControls(
    state: CallState,
    name: String,
    talk: Talk,
    link: TalkLink?,
    routing: CallRouting?,
    onHangUp: () -> Unit,
    onRoute: (CallAudioRoute) -> Unit,
    onTalk: () -> Unit,
    onTalkSend: () -> Unit,
    onTalkCancel: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (link != null && link != TalkLink.DIRECT) {
            LinkNotice(link, name)
            Spacer(Modifier.height(12.dp))
        }
        if (state.degraded) {
            Text(
                "No live connection — switched to walkie-talkie",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
        }
        Text(talkLine(talk, name), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        // The talk button takes the centre a call screen gives its red hang-up; the hang-up steps aside to the start.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.BottomCenter) {
                CallAction("End", Icons.Filled.CallEnd, HangUpRed, onHangUp)
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.BottomCenter) {
                TalkButton(recording = talk is Talk.Recording, onTalk, onTalkSend, onTalkCancel)
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.BottomCenter) {
                AudioRouteButton(routing, onRoute)
            }
        }
    }
}

/**
 * Why a burst may not reach the peer (§3.11): no link this phone can see — the radios are asked for one meanwhile — or
 * only a path through other phones, which works but is slower and less sure, or no radio path at all but a relay that
 * carries the bursts (§2.7), which works while both phones stay online.
 */
@Composable
private fun LinkNotice(
    link: TalkLink,
    name: String,
) {
    val none = link == TalkLink.NONE
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (none) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (none) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val icon =
                when (link) {
                    TalkLink.NONE -> Icons.Outlined.LinkOff
                    TalkLink.SPOOL -> Icons.Outlined.Cloud
                    else -> Icons.Outlined.Hub
                }
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(
                when (link) {
                    TalkLink.NONE -> "Not linked to $name yet. Linking…"
                    TalkLink.SPOOL -> "No radio link to $name. Going through your relay"
                    else -> "No direct link to $name. Going through other phones"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private fun talkLine(
    talk: Talk,
    name: String,
): String =
    when (talk) {
        is Talk.Recording -> {
            val seconds = talk.elapsedMs / 1_000
            "Talking %d:%02d".format(seconds / SECONDS_PER_MINUTE, seconds % SECONDS_PER_MINUTE)
        }

        Talk.Sending -> {
            "Sending…"
        }

        Talk.Hearing -> {
            "$name is talking…"
        }

        is Talk.Idle -> {
            when (talk.note) {
                Talk.Note.SENT -> "Sent"
                Talk.Note.NO_MIC -> "The microphone is busy"
                Talk.Note.NOT_SENT -> "Couldn't send that one"
                Talk.Note.NO_LINK -> "Not linked, so that one didn't go"
                Talk.Note.TOO_SHORT, null -> "Hold to talk"
            }
        }
    }

/** Held, it records; let go, it sends; a gesture the system cancels drops the burst. TalkBack gets both as actions. */
@Composable
private fun TalkButton(
    recording: Boolean,
    onTalk: () -> Unit,
    onTalkSend: () -> Unit,
    onTalkCancel: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val talk by rememberUpdatedState(onTalk)
    val send by rememberUpdatedState(onTalkSend)
    val cancel by rememberUpdatedState(onTalkCancel)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = CircleShape,
            // Never the hang-up's red: green as the answer button, brighter while it records.
            color = if (recording) TalkingGreen else AnswerGreen,
            contentColor = Color.White,
            modifier =
                Modifier
                    .size(TalkSize)
                    .semantics {
                        contentDescription = "Hold to talk"
                        customActions =
                            listOf(
                                CustomAccessibilityAction("Start talking") { true.also { talk() } },
                                CustomAccessibilityAction("Stop and send") { true.also { send() } },
                            )
                    }.pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                talk()
                                if (tryAwaitRelease()) send() else cancel()
                            },
                        )
                    },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(48.dp))
            }
        }
        ButtonLabel(if (recording) "Release to send" else "Hold to talk")
    }
}

/** Where a walkie session's audio goes: over the mesh in chunks as it is spoken, never a live media path. */
@Composable
private fun WalkiePill() {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Outlined.WifiCalling3, contentDescription = null, modifier = Modifier.size(16.dp))
            Text("Walkie-talkie · mesh", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun CallAction(
    label: String,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(
            onClick = onClick,
            modifier = Modifier.size(ActionSize),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = color, contentColor = Color.White),
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(32.dp))
        }
        ButtonLabel(label)
    }
}

@Composable
internal fun CallToggle(
    label: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalIconToggleButton(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.size(ToggleSize),
            enabled = enabled,
            shape = CircleShape,
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(28.dp))
        }
        ButtonLabel(label)
    }
}

/** The word under a button: the button already carries it as its description, so it is not read twice. */
@Composable
internal fun ButtonLabel(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 8.dp).clearAndSetSemantics {},
    )
}

/**
 * Who is calling: where they stand with you, then the newest message in your thread — held back over
 * the lock screen, where anyone holding the phone would read it, as are the shared groups' names (the line counts them
 * there instead). Tapping the message opens the thread.
 */
@Composable
private fun CallerCard(
    caller: CallerContext,
    name: String,
    locked: Boolean,
    hideFlagged: Boolean,
    clock: () -> Long,
    onOpenChat: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        caller.relationshipShown(locked)?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        val last = caller.lastMessage ?: return@Column
        if (locked) {
            Text(
                "Unlock to see your last messages",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            return@Column
        }
        val preview = messagePreview(LocalContext.current, last.message, hideFlagged)
        if (preview.isBlank()) return@Column
        val who = if (last.fromMe) "You" else name
        val ago = DateUtils.getRelativeTimeSpanString(last.sentAt, clock(), DateUtils.MINUTE_IN_MILLIS).toString()
        Surface(
            onClick = onOpenChat,
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "$who · $ago",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(preview, style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** The route pill's content: which way the audio goes, and over which of this phone's networks. */
internal data class RouteView(
    val label: String,
    val kind: RouteKind,
)

internal enum class RouteKind { WIFI_DIRECT, RELAYED, DIRECT }

/**
 * Which way the audio goes, from the selected pair's candidate types ("relay/srflx") — through TURN or not, or over the
 * call's own Wi-Fi Direct group, which the media marks with a trailing "/wifi-direct" (§3.10) — and the network this
 * phone sends it over, from [stats]: "Relayed · Cellular", "Direct · Wi-Fi".
 */
internal fun routeView(
    route: String,
    stats: CallStats?,
): RouteView {
    val parts = route.split('/')
    val types = parts - ROUTE_DIRECT
    val network = stats?.let(::networkOf)
    return when {
        ROUTE_DIRECT in parts -> RouteView("Wi-Fi Direct", RouteKind.WIFI_DIRECT)
        "relay" in types -> RouteView(listOfNotNull("Relayed", network).joinToString(" · "), RouteKind.RELAYED)
        types.all { it == "host" } -> RouteView("Direct · same network", RouteKind.DIRECT)
        else -> RouteView(listOfNotNull("Direct", network).joinToString(" · "), RouteKind.DIRECT)
    }
}

@Composable
private fun RoutePill(route: RouteView) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                when (route.kind) {
                    RouteKind.WIFI_DIRECT -> Icons.Outlined.WifiTethering
                    RouteKind.RELAYED -> Icons.Outlined.Cloud
                    RouteKind.DIRECT -> Icons.Outlined.Sensors
                },
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Text(route.label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** The line under the route: a quality warning, or for a few seconds after the route moves, where it moved to. */
internal data class CallNote(
    val text: String,
    val level: QualityLevel,
)

/**
 * The note for now: a microphone that hands the call no sound wins (C-3.13-13), then a [quality] warning; else, for
 * [MOVED_NOTE_MS] after the route's label changes mid-call, the new route ("Switched to Relayed · Cellular"). The first
 * route a call settles on is not a move.
 */
@Composable
private fun callNote(
    state: CallState?,
    name: String,
    quality: Quality?,
    route: RouteView?,
    capture: CaptureStatus? = null,
): CallNote? {
    var settled by remember(state?.id) { mutableStateOf<String?>(null) }
    var moved by remember(state?.id) { mutableStateOf<String?>(null) }
    val label = route?.label
    LaunchedEffect(state?.id, label) {
        if (label == null) return@LaunchedEffect
        val before = settled
        settled = label
        if (before != null && before != label) {
            moved = label
            delay(MOVED_NOTE_MS)
            moved = null
        }
    }
    if (state?.phase != CallPhase.Connected || state.walkie) return null
    captureText(capture, name)?.let { return CallNote(it, QualityLevel.POOR) }
    quality?.issue?.takeIf { quality.level != QualityLevel.GOOD }?.let { return CallNote(issueText(it, name), quality.level) }
    return moved?.let { CallNote("Switched to $it", QualityLevel.GOOD) }
}

/** What the screen says of a microphone that gives the call no sound, or null while it does. */
internal fun captureText(
    capture: CaptureStatus?,
    name: String,
): String? =
    when (capture?.health) {
        CaptureWatch.Health.DEAD -> "$name can't hear you: this phone's microphone isn't giving Knit any sound"
        CaptureWatch.Health.SILENCED -> "$name can't hear you: Android silenced the microphone"
        else -> null
    }

/** How the screen words an issue: a direction ("breaking up for Ada"), never whose network is to blame. */
internal fun issueText(
    issue: QualityIssue,
    name: String,
): String =
    when (issue) {
        QualityIssue.NOT_HEARING -> "Can't hear $name right now"
        QualityIssue.HEARING_BREAKS -> "$name's audio is breaking up"
        QualityIssue.HEARD_BREAKS -> "Your audio is breaking up for $name"
        QualityIssue.BOTH_BREAK -> "Poor connection both ways"
        QualityIssue.DELAY -> "Long delay on this connection"
    }

/** The note, with bars for a warning; read out by TalkBack as it changes. */
@Composable
private fun NoteLine(note: CallNote) {
    val color =
        if (note.level == QualityLevel.POOR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when (note.level) {
            QualityLevel.GOOD -> null
            QualityLevel.FAIR -> Icons.Outlined.SignalCellularAlt2Bar
            QualityLevel.POOR -> Icons.Outlined.SignalCellularAlt1Bar
        }?.let { Icon(it, contentDescription = null, tint = color, modifier = Modifier.size(16.dp)) }
        Text(note.text, style = MaterialTheme.typography.bodyMedium, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun statusOf(
    state: CallState?,
    clock: () -> Long,
): String {
    val connectedAt = state?.connectedAt
    if (state?.phase == CallPhase.Connected && connectedAt != null) {
        var now by remember { mutableLongStateOf(clock()) }
        LaunchedEffect(connectedAt) {
            while (true) {
                now = clock()
                delay(1_000)
            }
        }
        val seconds = ((now - connectedAt) / 1_000).coerceAtLeast(0)
        return "%d:%02d".format(seconds / 60, seconds % 60)
    }
    return when (state?.phase) {
        null -> ""
        CallPhase.Calling -> "Calling…"
        CallPhase.Ringing -> "Ringing…"
        CallPhase.Alerting -> if (state.walkie) "Incoming walkie-talkie" else "Incoming voice call"
        CallPhase.Connecting -> "Connecting…"
        CallPhase.Connected -> "Connected"
        CallPhase.Reconnecting -> "Reconnecting…"
        CallPhase.Ended -> if (state.dropped) "Call dropped" else "Call ended"
    }
}

/**
 * An outgoing call with no RINGING back: nothing this phone can see reaches [name] (C-3.3-5), or something does and
 * still no RINGING came back (C-3.3-6).
 */
private fun callingNote(
    state: CallState,
    name: String,
): String = if (state.unreachable) "Can't reach $name right now" else "Waiting for $name's phone to respond"

/** Why [state] ended, when it is anything but somebody hanging up: [reasonOf], or that it never reached [name]. */
private fun endedNote(
    state: CallState,
    name: String,
): String? =
    when {
        state.reason != CallPayload.REASON_NO_ROUTE || state.connectedAt != null -> reasonOf(state.reason)
        state.unreachable -> "Couldn't reach $name"
        state.unproven -> "$name's phone didn't respond"
        else -> reasonOf(state.reason)
    }

/** Why it ended, when it is anything but somebody hanging up. */
private fun reasonOf(reason: Int?): String? =
    when (reason) {
        CallPayload.REASON_USER -> null
        CallPayload.REASON_BUSY -> "Busy"
        CallPayload.REASON_TIMEOUT -> "No answer"
        CallPayload.REASON_NO_ROUTE -> "No route between the phones"
        CallPayload.REASON_MEDIA -> "Media not supported"
        else -> "Something went wrong"
    }

private const val PREVIEW_PEER = "hzwyqdbj74hu6gkurmcyxomaca"
private val PREVIEW_EARPIECE = CallAudioRoute(CallAudioRoute.Kind.EARPIECE, "Earpiece", "earpiece")
private val PREVIEW_SPEAKER = CallAudioRoute(CallAudioRoute.Kind.SPEAKER, "Speaker", "speaker")
private val PREVIEW_BUDS = CallAudioRoute(CallAudioRoute.Kind.BLUETOOTH, "Pixel Buds Pro", "buds")
private val PREVIEW_ON_EARPIECE = CallRouting(PREVIEW_EARPIECE, listOf(PREVIEW_EARPIECE, PREVIEW_SPEAKER))
private val PREVIEW_ON_SPEAKER = CallRouting(PREVIEW_SPEAKER, listOf(PREVIEW_EARPIECE, PREVIEW_SPEAKER))
private val PREVIEW_ON_BUDS = CallRouting(PREVIEW_BUDS, listOf(PREVIEW_EARPIECE, PREVIEW_SPEAKER, PREVIEW_BUDS))

@Composable
private fun PreviewCall(
    phase: CallPhase,
    outgoing: Boolean = true,
    name: String = "Ada Lovelace",
    route: String? = null,
    stats: CallStats? = null,
    muted: Boolean = false,
    reason: Int? = null,
    walkie: Boolean = false,
    routing: CallRouting? = if (walkie) PREVIEW_ON_SPEAKER else PREVIEW_ON_EARPIECE,
    degraded: Boolean = false,
    talk: Talk = Talk.Idle(),
    link: TalkLink? = null,
    walkieStats: WalkieStats? = null,
    quality: Quality? = null,
    caller: CallerContext? = null,
    locked: Boolean = false,
    transcript: TranscriptUi? = null,
    statsShown: Boolean = transcript == null && (stats != null || walkieStats != null),
    connected: Boolean = phase == CallPhase.Connected || phase == CallPhase.Reconnecting,
    unreachable: Boolean = false,
    captions: CaptionsUi? = null,
    captionsOn: Boolean = captions != null,
) = KnitPreview {
    CallScreen(
        state =
            CallState(
                id = "preview",
                peerId = PREVIEW_PEER,
                outgoing = outgoing,
                phase = phase,
                startedAt = PREVIEW_NOW - 90_000,
                connectedAt = (PREVIEW_NOW - 83_000).takeIf { connected },
                muted = muted,
                reason = reason,
                walkie = walkie,
                degraded = degraded,
                unreachable = unreachable,
            ),
        name = name,
        avatarHash = null,
        route = route,
        stats = stats,
        onAnswer = {},
        onDecline = {},
        onHangUp = {},
        onMute = {},
        onRoute = {},
        routing = routing,
        clock = { PREVIEW_NOW },
        talk = talk,
        link = link,
        walkieStats = walkieStats,
        quality = quality,
        caller = caller,
        locked = locked,
        statsShown = statsShown,
        transcript = transcript,
        captions = captions,
        captionsOn = captionsOn,
    )
}

@Preview(name = "Incoming", widthDp = 411, heightDp = 891)
@Composable
fun CallScreenIncomingPreview() = PreviewCall(CallPhase.Alerting, outgoing = false)

@Preview(name = "Incoming, long name", widthDp = 411, heightDp = 891)
@Composable
fun CallScreenLongNamePreview() = PreviewCall(CallPhase.Alerting, outgoing = false, name = "IMABETHENEXTTRILLIONARE🔥🔥🔥🔥")

private val PREVIEW_CALLER =
    CallerContext(
        status = "At the crag till 6",
        relationship = "Verified contact · 2 groups together",
        namedRelationship = "Verified contact · in Climbing, Lab",
        lastMessage =
            LastMessage(
                fromMe = false,
                sentAt = PREVIEW_NOW - 2 * 60 * 60_000,
                message =
                    MessageEntity(
                        id = "m1",
                        conversationId = PREVIEW_PEER,
                        senderId = PREVIEW_PEER,
                        body = "Are you near the lab? The P7 needs a hand",
                        sentAt = PREVIEW_NOW - 2 * 60 * 60_000,
                    ),
            ),
    )

@Preview(name = "Incoming, caller context", widthDp = 411, heightDp = 891)
@Preview(name = "Incoming, caller context, dark", widthDp = 411, heightDp = 891, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Incoming, caller context, small", widthDp = 360, heightDp = 640)
@Composable
fun CallScreenCallerContextPreview() = PreviewCall(CallPhase.Alerting, outgoing = false, caller = PREVIEW_CALLER)

@Preview(name = "Incoming, caller context, locked", widthDp = 411, heightDp = 891)
@Composable
fun CallScreenCallerLockedPreview() = PreviewCall(CallPhase.Alerting, outgoing = false, caller = PREVIEW_CALLER, locked = true)

@Preview(name = "Incoming, stranger", widthDp = 411, heightDp = 891)
@Composable
fun CallScreenStrangerPreview() =
    PreviewCall(CallPhase.Alerting, outgoing = false, caller = CallerContext(relationship = "Not a contact · no messages yet"))

@Preview(name = "Ringing", widthDp = 411, heightDp = 891)
@Composable
fun CallScreenRingingPreview() = PreviewCall(CallPhase.Ringing)

@Preview(name = "Connected", widthDp = 411, heightDp = 891)
@Preview(name = "Connected, dark", widthDp = 411, heightDp = 891, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun CallScreenConnectedPreview() = PreviewCall(CallPhase.Connected, route = "relay/srflx", muted = true, caller = PREVIEW_CALLER)

@Preview(name = "Connected, on a headset", widthDp = 411, heightDp = 891)
@Composable
fun CallScreenHeadsetPreview() =
    PreviewCall(CallPhase.Connected, route = "host/host/wifi-direct", routing = PREVIEW_ON_BUDS, caller = PREVIEW_CALLER)

@Preview(name = "Connected, breaking up", widthDp = 411, heightDp = 891)
@Preview(name = "Connected, breaking up, dark", widthDp = 411, heightDp = 891, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun CallScreenPoorPreview() =
    PreviewCall(
        CallPhase.Connected,
        route = "srflx/srflx",
        stats = CallStats(localType = "srflx", remoteType = "srflx", networkType = "cellular"),
        quality = Quality(QualityLevel.POOR, QualityIssue.HEARING_BREAKS),
        caller = PREVIEW_CALLER,
        statsShown = false,
    )

@Preview(name = "Connected, relayed, diagnostics", widthDp = 411, heightDp = 891)
@Composable
fun CallScreenRelayDiagnosticsPreview() =
    PreviewCall(
        CallPhase.Connected,
        route = "relay/srflx",
        quality = Quality(QualityLevel.FAIR, QualityIssue.HEARD_BREAKS),
        stats =
            CallStats(
                localType = "relay",
                remoteType = "srflx",
                protocol = "udp",
                relayProtocol = "udp",
                networkType = "wifi",
                codec = "opus",
                clockRate = 48_000,
                channels = 2,
                fec = true,
                rttMs = 81.0,
                jitterMs = 6.4,
                lossPercent = 0.8,
                recentLossPercent = 0.0,
                sentLossPercent = 4.3,
                sendKbps = 33.1,
                receiveKbps = 32.4,
                jitterBufferMs = 62.0,
                relayHost = "stun.lab.lax.spool.getknit.app",
                relays = "tcp 4, tls 2, udp 5",
                prefer = "wifi",
                localCandidates = 14,
                remoteCandidates = 6,
                pairs =
                    listOf(
                        PairInfo(true, "relay", "srflx", "udp", "udp", "wifi", 81.0),
                        PairInfo(false, "srflx", "srflx", "udp", null, "wifi", 96.0),
                        PairInfo(false, "srflx", "srflx", "udp", null, "cellular", 171.0),
                    ),
                pendingPairs = 9,
            ),
    )

@Preview(name = "Connected, diagnostics", widthDp = 411, heightDp = 891)
@Preview(name = "Connected, diagnostics, small", widthDp = 360, heightDp = 640)
@Preview(name = "Connected, diagnostics, dark", widthDp = 411, heightDp = 891, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun CallScreenDiagnosticsPreview() =
    PreviewCall(
        CallPhase.Connected,
        route = "host/host/wifi-direct",
        stats =
            CallStats(
                localType = "host",
                remoteType = "host",
                protocol = "udp",
                networkType = "ethernet",
                wifiDirect = true,
                codec = "opus",
                clockRate = 48_000,
                channels = 2,
                fec = true,
                rttMs = 4.2,
                jitterMs = 1.8,
                lossPercent = 0.0,
                sendKbps = 33.6,
                receiveKbps = 32.9,
                jitterBufferMs = 41.0,
                dtlsVersion = "1.2",
                dtlsCipher = "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
                srtpCipher = "AEAD_AES_256_GCM",
                localCandidates = 5,
                remoteCandidates = 4,
            ),
    )

@Preview(name = "Calling, unreachable", widthDp = 411, heightDp = 891)
@Composable
fun CallScreenUnreachablePreview() = PreviewCall(CallPhase.Calling, unreachable = true)

@Preview(name = "Ended", widthDp = 411, heightDp = 891)
@Composable
fun CallScreenEndedPreview() = PreviewCall(CallPhase.Ended, reason = CallPayload.REASON_TIMEOUT)

@Preview(name = "Reconnecting", widthDp = 411, heightDp = 891)
@Composable
fun CallScreenReconnectingPreview() = PreviewCall(CallPhase.Reconnecting, route = "relay/srflx", caller = PREVIEW_CALLER)

@Preview(name = "Dropped", widthDp = 411, heightDp = 891)
@Composable
fun CallScreenDroppedPreview() = PreviewCall(CallPhase.Ended, reason = CallPayload.REASON_NO_ROUTE, connected = true)

@Preview(name = "Walkie, degraded", widthDp = 411, heightDp = 891)
@Preview(name = "Walkie, degraded, dark", widthDp = 411, heightDp = 891, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun CallScreenWalkiePreview() = PreviewCall(CallPhase.Connected, walkie = true, degraded = true, talk = Talk.Recording(4_200, 0.6f))

@Preview(name = "Walkie, not linked", widthDp = 411, heightDp = 891)
@Preview(name = "Walkie, not linked, dark", widthDp = 411, heightDp = 891, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun CallScreenWalkieUnlinkedPreview() = PreviewCall(CallPhase.Connected, walkie = true, link = TalkLink.NONE)

@Preview(name = "Walkie, through the mesh", widthDp = 411, heightDp = 891)
@Composable
fun CallScreenWalkieMeshPreview() = PreviewCall(CallPhase.Connected, walkie = true, link = TalkLink.MESH)

@Preview(name = "Walkie, diagnostics", widthDp = 411, heightDp = 891)
@Preview(name = "Walkie, diagnostics, small", widthDp = 360, heightDp = 640)
@Preview(name = "Walkie, diagnostics, dark", widthDp = 411, heightDp = 891, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun CallScreenWalkieDiagnosticsPreview() =
    PreviewCall(
        CallPhase.Connected,
        walkie = true,
        link = TalkLink.DIRECT,
        walkieStats =
            WalkieStats(
                transitMs = 148.0,
                jitterMs = 22.4,
                lossPercent = 1.2,
                sendKbps = 11.9,
                receiveKbps = 11.8,
                bufferMs = 412.0,
                handMs = 9.0,
                frameOut = 712,
                frameIn = 708,
                chunksOut = 42,
                chunksIn = 81,
                chunksLost = 1,
                burstsOut = 3,
                burstsIn = 6,
                path = "Bluetooth Coded · direct",
            ),
    )

@Preview(name = "Walkie, transcript", widthDp = 411, heightDp = 891)
@Preview(name = "Walkie, transcript, small", widthDp = 360, heightDp = 640)
@Preview(name = "Walkie, transcript, dark", widthDp = 411, heightDp = 891, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun CallScreenWalkieTranscriptPreview() =
    PreviewCall(
        CallPhase.Connected,
        walkie = true,
        link = TalkLink.DIRECT,
        talk = Talk.Hearing,
        transcript =
            TranscriptUi(
                lines =
                    listOf(
                        TranscriptLine(0, PREVIEW_NOW - 64_000, TranscriptLine.State.Text("Are you at the north gate yet?")),
                        TranscriptLine(0, PREVIEW_NOW - 52_000, TranscriptLine.State.Text("Almost, I'm by the food trucks."), mine = true),
                        TranscriptLine(1, PREVIEW_NOW - 41_000, TranscriptLine.State.NoSpeech),
                        TranscriptLine(
                            2,
                            PREVIEW_NOW - 22_000,
                            TranscriptLine.State.Text("Okay. I'll wait by the food trucks, the blue one with the long line."),
                        ),
                        TranscriptLine(3, PREVIEW_NOW - 4_000, TranscriptLine.State.Listening),
                    ),
                model = SttModelState.Ready,
                modelBytes = PREVIEW_MODEL_BYTES,
                metered = false,
            ),
    )

@Preview(name = "Walkie, transcript, no model", widthDp = 411, heightDp = 891)
@Composable
fun CallScreenWalkieTranscriptNoModelPreview() =
    PreviewCall(
        CallPhase.Connected,
        walkie = true,
        link = TalkLink.MESH,
        transcript =
            TranscriptUi(
                lines = listOf(TranscriptLine(0, PREVIEW_NOW - 12_000, TranscriptLine.State.NoModel)),
                model = SttModelState.Absent,
                modelBytes = PREVIEW_MODEL_BYTES,
                metered = true,
            ),
    )

@Preview(name = "Walkie, transcript, downloading", widthDp = 411, heightDp = 891)
@Composable
fun CallScreenWalkieTranscriptDownloadingPreview() =
    PreviewCall(
        CallPhase.Connected,
        walkie = true,
        link = TalkLink.DIRECT,
        transcript =
            TranscriptUi(
                lines = emptyList(),
                model = SttModelState.Downloading(17_400_000, PREVIEW_MODEL_BYTES),
                modelBytes = PREVIEW_MODEL_BYTES,
                metered = false,
            ),
    )

@Preview(name = "Live, captions", widthDp = 411, heightDp = 891)
@Preview(name = "Live, captions, small", widthDp = 360, heightDp = 640)
@Preview(name = "Live, captions, dark", widthDp = 411, heightDp = 891, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun CallScreenCaptionsPreview() =
    PreviewCall(
        CallPhase.Connected,
        route = "srflx/srflx",
        routing = PREVIEW_ON_SPEAKER,
        captions =
            CaptionsUi(
                Captions(
                    listOf(
                        TranscriptLine(0, PREVIEW_NOW - 40_000, TranscriptLine.State.Text("Hey, can you hear me now?")),
                        TranscriptLine(1, PREVIEW_NOW - 31_000, TranscriptLine.State.Text("I'm at the north gate, by the food trucks.")),
                        TranscriptLine(
                            2,
                            PREVIEW_NOW - 6_000,
                            TranscriptLine.State.Listening,
                            settled = "The blue one with the long line.",
                            live = "I'll wait here until",
                        ),
                    ),
                ),
                SttModelState.Ready,
                PREVIEW_MODEL_BYTES,
                metered = false,
            ),
    )

@Preview(name = "Live, captions off", widthDp = 411, heightDp = 891)
@Composable
fun CallScreenCaptionsOffPreview() =
    PreviewCall(
        CallPhase.Connected,
        route = "relay/srflx",
        caller = PREVIEW_CALLER,
        captions = CaptionsUi(Captions(), SttModelState.Ready, PREVIEW_MODEL_BYTES, metered = false),
        captionsOn = false,
    )

@Preview(name = "Live, captions, earpiece", widthDp = 411, heightDp = 891)
@Composable
fun CallScreenCaptionsEarpiecePreview() =
    PreviewCall(
        CallPhase.Connected,
        route = "relay/srflx",
        captions = CaptionsUi(Captions(), SttModelState.Ready, PREVIEW_MODEL_BYTES, metered = false, earpiece = true),
    )

@Preview(name = "Live, captions, no model", widthDp = 411, heightDp = 891)
@Composable
fun CallScreenCaptionsNoModelPreview() =
    PreviewCall(
        CallPhase.Connected,
        route = "relay/srflx",
        captions = CaptionsUi(Captions(), SttModelState.Absent, PREVIEW_MODEL_BYTES, metered = true, earpiece = true),
    )

@Preview(name = "Live, captions, behind", widthDp = 411, heightDp = 891)
@Composable
fun CallScreenCaptionsBehindPreview() =
    PreviewCall(
        CallPhase.Connected,
        route = "srflx/srflx",
        routing = PREVIEW_ON_SPEAKER,
        captions =
            CaptionsUi(
                Captions(
                    listOf(TranscriptLine(0, PREVIEW_NOW - 20_000, TranscriptLine.State.Text("So the plan is still on for Saturday?"))),
                    CaptionStatus.BEHIND,
                ),
                SttModelState.Ready,
                PREVIEW_MODEL_BYTES,
                metered = false,
            ),
    )

private const val PREVIEW_MODEL_BYTES = 142_315_154L
