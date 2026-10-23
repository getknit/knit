package app.getknit.knit.di

import android.os.Build
import android.util.Log
import androidx.compose.runtime.Composable
import app.getknit.knit.BuildConfig
import app.getknit.knit.call.CallCaptioner
import app.getknit.knit.call.CallControl
import app.getknit.knit.call.CallDirect
import app.getknit.knit.call.CallLog
import app.getknit.knit.call.CallManager
import app.getknit.knit.call.CallMedia
import app.getknit.knit.call.CallMediaSampler
import app.getknit.knit.call.CallPhase
import app.getknit.knit.call.CallReach
import app.getknit.knit.call.CallReachRule
import app.getknit.knit.call.CallSignals
import app.getknit.knit.call.CallTimings
import app.getknit.knit.call.CallToneSync
import app.getknit.knit.call.CallWalkie
import app.getknit.knit.call.CaptureHealth
import app.getknit.knit.call.CaptureJournal
import app.getknit.knit.call.CustomIce
import app.getknit.knit.call.DirectCallPath
import app.getknit.knit.call.IceProbe
import app.getknit.knit.call.OverridingTurnSource
import app.getknit.knit.call.SystemCallSync
import app.getknit.knit.call.SystemCalls
import app.getknit.knit.call.TalkLink
import app.getknit.knit.call.TurnSource
import app.getknit.knit.call.VoiceNoteSpeech
import app.getknit.knit.call.VoiceNoteTranscriber
import app.getknit.knit.call.WalkieTranscriber
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.VoiceAudio
import app.getknit.knit.data.blob.BlobDao
import app.getknit.knit.data.message.MessageDao
import app.getknit.knit.data.relay.RelayStatusRepository
import app.getknit.knit.data.settings.CallIntake
import app.getknit.knit.data.settings.CallTombStore
import app.getknit.knit.data.settings.CaptureStore
import app.getknit.knit.data.settings.IncomingCallStore
import app.getknit.knit.data.settings.OwnIceStore
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.data.settings.TranscriptStore
import app.getknit.knit.device.DeviceImage
import app.getknit.knit.fieldlog.FieldLog
import app.getknit.knit.fieldlog.FieldRecorder
import app.getknit.knit.identity.Identity
import app.getknit.knit.mesh.BulkRoute
import app.getknit.knit.mesh.MeshController
import app.getknit.knit.mesh.MeshManager
import app.getknit.knit.mesh.MeshTransport
import app.getknit.knit.mesh.crypto.AttachmentCrypto
import app.getknit.knit.mesh.crypto.b64d
import app.getknit.knit.mesh.indirectPeers
import app.getknit.knit.mesh.power.AppVisibility
import app.getknit.knit.mesh.protocol.Protocol
import app.getknit.knit.mesh.spool.spoolPresentPeers
import app.getknit.knit.moderation.ModelLoadGuard
import app.getknit.knit.moderation.modelGuardStamp
import app.getknit.knit.net.AndroidInternetGate
import app.getknit.knit.net.InternetGate
import app.getknit.knit.transfer.DirectWifiLease
import app.getknit.knit.ui.call.CallBarHost
import app.getknit.knit.ui.call.CallController
import app.getknit.knit.ui.call.CallFaces
import app.getknit.knit.ui.call.CallService
import app.getknit.knit.ui.call.TalkLinks
import app.getknit.knit.ui.call.TalkPath
import app.getknit.knit.ui.call.TalkPlayer
import app.getknit.knit.ui.call.TalkRecorder
import app.getknit.knit.ui.call.TelecomCalls
import app.getknit.knit.ui.call.ToneGeneratorPlayer
import app.getknit.knit.ui.call.WalkieTalk
import app.getknit.knit.ui.call.WebRtcCallMedia
import app.getknit.knit.ui.call.stt.MoonshineSpeech
import app.getknit.knit.ui.call.stt.SttDownloadService
import app.getknit.knit.ui.call.stt.SttKeytermFeed
import app.getknit.knit.ui.call.stt.SttModelStore
import app.getknit.knit.ui.call.stt.WalkieClipFiles
import app.getknit.knit.ui.callsettings.CallSettingsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.core.scope.Scope
import org.koin.dsl.module

/**
 * Live calls (docs/VOICE_CALLS.md §3.5–3.10, ADR 2026-10.3tee): the one read of `BuildConfig.LIVE_CALLS` — on in every
 * build since 2.10.0-beta.0, `-PliveCalls=` overrides (ADR 2026-10.swvp). Off, [CallManager] gets
 * [CallMedia.None] and declines every live offer, no WebRTC media, Wi-Fi Direct leg, TURN source or capture watch is
 * built and the profile carries no `CAP_CALL`; the build packages no WebRTC either, so every class only the live graph
 * reaches is unreachable and R8 drops it.
 */
const val LIVE_CALLS_WIRED: Boolean = BuildConfig.LIVE_CALLS

/**
 * The walkie-talkie, a "mesh call" on screen (docs/VOICE_CALLS.md §3.11, ADR 2026-10.79yb): a call that streams
 * hold-to-talk bursts over the mesh. The one read of `BuildConfig.MESH_CALLS` — on in every build since 2.10.0-beta.0,
 * `-PmeshCalls=` overrides (ADR 2026-10.swvp). Off, [CallManager] gets [CallWalkie.None], no [WalkieTalk] is built and
 * the profile carries no `CAP_WALKIE`, so every call is a live one. It needs no live calls: its Opus is the platform's.
 */
const val WALKIE_WIRED: Boolean = BuildConfig.MESH_CALLS

/**
 * Voice calls of either kind: the call graph, its surfaces and core-telecom (every call, live or walkie, is a
 * self-managed Telecom call, §3.13). Off, [callModule] is null, nothing starts, no bar is drawn and the build packages
 * neither the call screen's manifest entries nor core-telecom (ADR 2026-10.3tee, the 2026-10-14 amendment).
 */
const val CALLS_WIRED: Boolean = LIVE_CALLS_WIRED || WALKIE_WIRED

/**
 * The capability bits this build's call kinds add to `Protocol.LOCAL_CAPABILITIES`: each kind's bit where that kind is
 * wired (ADR 2026-10.swvp), `CAP_CALL` meaning "takes a live call". `MeshManager` OR-s it into its profile; About shows it.
 */
val CALL_CAPABILITIES: Long =
    (if (LIVE_CALLS_WIRED) Protocol.CAP_CALL else 0L) or (if (WALKIE_WIRED) Protocol.CAP_WALKIE else 0L)

/**
 * Speech (docs/VOICE_CALLS.md C-3.11-9, C-3.7-5, ADR 2026-10.nz3g): the walkie's transcripts and a live call's
 * captions, from a model the user downloads. The one read of `BuildConfig.STT`, and only where a call kind is wired — a
 * transcript needs [WALKIE_WIRED], a caption [LIVE_CALLS_WIRED]. Off, no model store, speech runtime or feed is built,
 * the call screen shows no transcript, captions or CC button, and the build packages no Moonshine.
 */
const val STT_WIRED: Boolean = CALLS_WIRED && BuildConfig.STT

/**
 * The person's own STUN and TURN servers (§3.14, ADR 2026-10.mr88): debug builds only for now, and only with live calls.
 * Off, the Calls screen shows no server rows, Test or Save, the Settings row never names an own server, and every call
 * takes the spools' servers whatever [OwnIceStore] holds — [OverridingTurnSource] is not built. Not `const`:
 * `BuildConfig.DEBUG` is a `parseBoolean` AGP emits, which R8 folds but the compiler cannot.
 */
val OWN_ICE_WIRED: Boolean = LIVE_CALLS_WIRED && BuildConfig.DEBUG

/** The Calls screen's route and Settings row: which incoming calls it takes, then its live rows and speech section. */
const val CALL_SETTINGS_WIRED: Boolean = CALLS_WIRED

/** The call graph, or null in a build without calls ([CALLS_WIRED]). */
fun callModule(): Module? = if (CALLS_WIRED) callGraph() else null

/**
 * [CallManager]'s seams are late-bound over [MeshManager], so building the graph builds nothing else — the mesh graph
 * is the service's to build, off the main thread (ADR 2026-09.vztn). The direct leg's radio lease is late-bound the
 * same way: it wraps the Wi-Fi Direct radio, which holds the mesh transport. Each kind's own definitions sit behind its
 * constant ([liveGraph], [walkieGraph], [speechGraph]), so a build without one never reaches its classes.
 */
private fun callGraph(): Module =
    module {
        // §3.13: every call, live or walkie, is a self-managed Telecom call, which owns its audio route. The peers are
        // late-bound, as the faces' are; the sync follows the manager's call from process start (startCallsIfWired).
        single {
            val context = androidContext()
            TelecomCalls(
                context,
                peers = { get() },
                scope = get<CoroutineScope>(),
                log = { Log.i(TAG, it) },
                onAnswered = { CallService.startAnswered(context) },
            )
        }
        single<SystemCalls> { get<TelecomCalls>() }
        single<CallControl> { get<CallManager>() }
        single { SystemCallSync(calls = get(), system = get(), scope = get<CoroutineScope>(), log = { Log.i(TAG, it) }) }
        if (LIVE_CALLS_WIRED) liveGraph()
        if (CALL_SETTINGS_WIRED) {
            viewModel {
                CallSettingsViewModel(
                    settings = get(),
                    ownIce = get(),
                    probe = if (OWN_ICE_WIRED) get<IceProbe>() else null,
                    stt = if (STT_WIRED) get<SttModelStore>() else null,
                    transcriptStore = if (STT_WIRED) get<TranscriptStore>() else null,
                    incoming = get(),
                    preference = get(),
                    walkie = WALKIE_WIRED,
                    live = LIVE_CALLS_WIRED,
                    relayTurn = get<RelayStatusRepository>().turnAvailable,
                )
            }
        }
        single { CallTombStore(get()) }
        single { IncomingCalls(get(), get<CoroutineScope>()) }
        single { callManager() }
        if (STT_WIRED) speechGraph()
        if (WALKIE_WIRED) walkieGraph()
        recordGraph()
    }

/**
 * The one [CallManager], over the mesh's sealed DMs and each kind's own seams. A demo build (`-PseedDemo`, debug only)
 * calls scripted peers over loopback instead, with media that moves no audio and no TURN or Wi-Fi Direct leg to raise
 * (di/DemoWiring); every other build gets null from those seams.
 */
private fun Scope.callManager(): CallManager {
    val demo = if (LIVE_CALLS_WIRED) demoCallMediaOrNull() else null
    return CallManager(
        signals = demoCallSignalsOrNull() ?: CallSignals { peerId, payload -> get<MeshManager>().sendCallSignal(peerId, payload) },
        turn = if (LIVE_CALLS_WIRED && demo == null) get<TurnSource>() else TurnSource.None,
        media = demo ?: if (LIVE_CALLS_WIRED) get<CallMedia>() else CallMedia.None,
        scope = get<CoroutineScope>(),
        selfId = { get<Identity>().nodeId() },
        log = { Log.i(TAG, it) },
        direct = if (LIVE_CALLS_WIRED && demo == null) get<DirectCallPath>() else CallDirect.None,
        walkie = if (WALKIE_WIRED) walkieRoute() else CallWalkie.None,
        relayAll = if (LIVE_CALLS_WIRED) get<RelayAllCalls>().read else ({ true }),
        tombs = get<CallTombStore>(),
        reach = callReach(),
        takesIncoming = get<IncomingCalls>()::takes,
        field = fieldLog(),
    )
}

/** What every call leaves behind and rings with, of either kind: its line in the thread, its faces, its alerting. */
private fun Module.recordGraph() {
    // §3.12: each call's line in its thread. The repository is resolved at the first record, not here.
    single {
        CallLog(
            calls = get(),
            messages = { get<MessageRepository>() },
            selfId = { get<Identity>().nodeId() },
            scope = get<CoroutineScope>(),
            log = { Log.i(TAG, it) },
        )
    }
    single { CallFaces(androidContext(), peers = { get() }, blobs = { get() }, theme = { get() }) }
    single {
        val calls = get<CallManager>()
        CallController(
            context = androidContext(),
            calls = calls.state,
            missedCalls = calls.missed,
            scope = get<CoroutineScope>(),
            faceOf = get<CallFaces>()::of,
            alertMs = CallTimings().alertMs,
        )
    }
}

/**
 * C-3.3-7's two switches, held as a state on the app scope for [CallManager] to read as an offer lands. Until the
 * store's first read they read as on, the default: a wanted call is never refused for a read still in flight.
 */
private class IncomingCalls(
    store: IncomingCallStore,
    scope: CoroutineScope,
) {
    private val intake = store.intake.stateIn(scope, SharingStarted.Eagerly, CallIntake())

    fun takes(walkie: Boolean): Boolean = intake.value.takes(walkie)
}

/** C-3.7-3's "Relay all calls", held as a state on the app scope for [CallManager] to read as each call opens its media. */
private class RelayAllCalls(
    val read: () -> Boolean,
)

/** A live call's media and paths (§3.5–3.10): WebRTC, the Wi-Fi Direct leg, the capture watch and the ICE servers. */
@Suppress("LongMethod") // the live call's whole graph in one place, a definition per seam, as the other wiring files keep theirs
private fun Module.liveGraph() {
    // C-3.13-14: the caller's ringback, busy and end tones, which the system plays none of for a self-managed call.
    single {
        val scope = get<CoroutineScope>()
        CallToneSync(calls = get(), player = ToneGeneratorPlayer(scope, log = { Log.w(TAG, it) }), scope = scope)
    }
    // §3.10 (ADR 2026-10.kxnw): a call between neighbours rides a Wi-Fi Direct group, on the radio the transfer
    // shares through one lease. "Directly linked" is the transfer's own gate, a mesh neighbour now.
    single {
        // Read synchronously by `canJoin`, so held as a state on the app scope from the first call on.
        val directOff = get<SettingsStore>().debugDirectCallsOff.stateIn(get<CoroutineScope>(), SharingStarted.Eagerly, false)
        DirectCallPath(
            leaseOf = { get<DirectWifiLease>() },
            scope = get<CoroutineScope>(),
            nearby = { id -> get<MeshController>().neighbors.value.any { it.nodeId == id } },
            capable = { id -> (get<PeerRepository>().find(id)?.capabilities ?: 0L) and Protocol.CAP_DIRECT_TRANSFER != 0L },
            // API 29-32 refuses Wi-Fi Direct to an app off screen; 33+ gates it on NEARBY_WIFI_DEVICES alone.
            mayJoinWhileRinging = { Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU || get<AppVisibility>().visible.value },
            enabled = { !directOff.value },
            log = { Log.i(TAG, it) },
            field = fieldLog(),
        )
    }
    // C-3.13-12 (ADR 2026-10.kw29): which microphone path a call records from — the fallback on a generic system
    // image over an older vendor, or once a call here captured only zeros. Read from the store on the app scope;
    // until then, the image's own answer.
    single<CaptureJournal> { CaptureStore(get()) }
    single {
        CaptureHealth(
            journal = get(),
            stamp = modelGuardStamp(BuildConfig.VERSION_CODE, Build.FINGERPRINT.orEmpty()),
            genericImage = get<DeviceImage>().genericOnOlderVendor,
        ).also { health -> get<CoroutineScope>().launch { health.load() } }
    }
    single {
        WebRtcCallMedia(
            androidContext(),
            get<CoroutineScope>(),
            link = get<DirectCallPath>().link,
            capture = get(),
            log = { Log.i(TAG, it) },
            // C-3.7-5: a live call's captions, where speech is wired.
            captions = if (STT_WIRED) get<CallCaptioner>() else null,
            field = fieldLog(),
        )
    }
    // The call screen reads the media's stats itself (its diagnostics card); the manager sees only the seam.
    single<CallMedia> { get<WebRtcCallMedia>() }
    // The field log's readings of a live call's media (ADR 2026-10.a239's amendment), on the media's second reader.
    single {
        CallMediaSampler(
            calls = get<CallManager>().state,
            read = { reconnecting -> get<WebRtcCallMedia>().fieldSample(reconnecting) },
            field = fieldLog(),
            scope = get<CoroutineScope>(),
            log = { Log.i(TAG, it) },
        )
    }
    // The Calls screen's Test (C-3.14-5): the same factory, and refused while a call holds it.
    if (OWN_ICE_WIRED) single<IceProbe> { get<WebRtcCallMedia>() }
    single {
        // C-3.7-3's "Relay all calls", read as each call opens its media. Until the store's first read lands it
        // reads as on: a call in that instant goes through TURN rather than showing the peer an address it hides.
        val relayAll =
            get<SettingsStore>()
                .callsRelayAll
                .map<Boolean, Boolean?> { it }
                .stateIn(get<CoroutineScope>(), SharingStarted.Eagerly, null)
        RelayAllCalls { relayAll.value ?: true }
    }
    single<TurnSource> {
        val spool =
            object : TurnSource {
                override suspend fun turnServers(peerId: String) = get<MeshManager>().turnServers(peerId)

                override fun stunUrls() = get<MeshManager>().stunUrls()

                override fun turnAvailable() = get<MeshManager>().turnAvailable()
            }
        if (!OWN_ICE_WIRED) return@single spool
        // §3.14 (ADR 2026-10.mr88): the person's own servers, each kind replacing the spools'. Until the store's
        // first read lands they read as none, so that instant's call asks the spools as it always did.
        val ownIce =
            get<OwnIceStore>()
                .ownIce
                .map { it.effective() }
                .stateIn(get<CoroutineScope>(), SharingStarted.Eagerly, CustomIce.NONE)
        OverridingTurnSource(custom = { ownIce.value }, spool = spool)
    }
}

/**
 * The speech model (C-3.11-9, C-3.7-5): one the user downloads, writing out the walkie's bursts ([WALKIE_WIRED]) and a
 * live call's peer ([LIVE_CALLS_WIRED]) as they play.
 */
@Suppress("LongMethod") // the speech graph in one place, a definition per seam, as the other wiring files keep theirs
private fun Module.speechGraph() {
    single { TranscriptStore(get()) }
    single {
        SttModelStore(
            androidContext(),
            get<AndroidInternetGate>(),
            get<CoroutineScope>(),
            keepAlive = { SttDownloadService.start(androidContext()) },
            log = { Log.i(TAG, it) },
        )
    }
    single { MoonshineSpeech(get(), get<ModelLoadGuard>(), get<CoroutineScope>(), log = { Log.i(TAG, it) }) }
    // The names a call is likely to carry, for the model to listen for: read as a call begins, never logged.
    single {
        SttKeytermFeed(
            calls = get<CallManager>().state,
            speech = get(),
            peers = get(),
            groups = get(),
            messages = get(),
            settings = get(),
            scope = get<CoroutineScope>(),
            log = { Log.i(TAG, it) },
        )
    }
    if (LIVE_CALLS_WIRED) {
        single {
            // A live call's captions (C-3.7-5): the Calls screen's switch and the call screen's CC button, both on.
            val store = get<TranscriptStore>()
            val on =
                combine(store.enabled, store.liveCaptions) { enabled, live -> enabled && live }
                    .stateIn(get<CoroutineScope>(), SharingStarted.Eagerly, false)
            CallCaptioner(
                speech = get<MoonshineSpeech>(),
                ready = { get<SttModelStore>().paths() != null },
                scope = get<CoroutineScope>(),
                enabled = { on.value },
                // One model call at a time, in the order the audio came, off the media's audio thread.
                dispatcher = Dispatchers.Default.limitedParallelism(1),
                log = { Log.i(TAG, it) },
            )
        }
    }
    // A chat voice note's text, on the person's tap (ADR 2026-10.am8d): the walkie's model over the note's held bytes,
    // decoded to 16 kHz mono, the text kept on every row naming the note. A call comes first.
    single {
        val blobs = get<BlobDao>()
        val messages = get<MessageDao>()
        val calls = get<CallManager>()
        VoiceNoteTranscriber(
            speech = get<MoonshineSpeech>(),
            pcm = { hash, key ->
                val raw = blobs.bytes(hash)
                val audio = if (raw == null || key == null) raw else AttachmentCrypto.open(raw, b64d(key))
                audio?.let { VoiceAudio.speechPcm(it) }
            },
            save = messages::setVoiceTranscript,
            callActive = { calls.state.value?.let { it.phase != CallPhase.Ended } == true },
            scope = get<CoroutineScope>(),
            dispatcher = Dispatchers.Default.limitedParallelism(1),
            log = { Log.i(TAG, it) },
        )
    }
    single<VoiceNoteSpeech> {
        val store = get<SttModelStore>()
        val transcriber = get<VoiceNoteTranscriber>()
        val switch = get<TranscriptStore>().enabled
        object : VoiceNoteSpeech {
            override val enabled = switch
            override val model = store.state
            override val notes = transcriber.state
            override val modelBytes = store.model.bytes

            override fun metered() = store.metered()

            override fun transcribe(
                hash: String,
                key: String?,
            ) = transcriber.transcribe(hash, key)

            override fun download() = store.download()
        }
    }
    if (WALKIE_WIRED) {
        // A debug build's lab switch, off by default (`SettingsStore.debugWalkieClips`): each burst's audio and line.
        single {
            WalkieClipFiles(
                androidContext(),
                get<CoroutineScope>(),
                switch = get<SettingsStore>().debugWalkieClips.stateIn(get<CoroutineScope>(), SharingStarted.Eagerly, false),
                about = { line ->
                    mapOf(
                        "model" to get<SttModelStore>().model.id,
                        "lines" to get<MoonshineSpeech>().lines.name.lowercase(),
                        "call" to get<CallManager>().state.value?.id,
                        "suppressor" to if (line.mine) get<TalkRecorder>().suppressor else null,
                    )
                },
                log = { Log.i(TAG, it) },
            )
        }
        single {
            // The Calls screen's "Walkie transcripts" switch, on by default, read as each burst begins.
            val on = get<TranscriptStore>().enabled.stateIn(get<CoroutineScope>(), SharingStarted.Eagerly, true)
            WalkieTranscriber(
                speech = get<MoonshineSpeech>(),
                ready = { get<SttModelStore>().paths() != null },
                scope = get<CoroutineScope>(),
                enabled = { on.value },
                // One model call at a time, in the order the audio came, off the player's and the recorder's threads.
                dispatcher = Dispatchers.Default.limitedParallelism(1),
                log = { Log.i(TAG, it) },
                clips = get<WalkieClipFiles>(),
            )
        }
    }
}

/** The walkie's audio and its streaming over the mesh (§3.11); each tap feeds the transcript where speech is wired. */
private fun Module.walkieGraph() {
    single { TalkPlayer(tap = if (STT_WIRED) get<WalkieTranscriber>() else null) }
    single { TalkRecorder(get<CoroutineScope>(), tap = if (STT_WIRED) get<WalkieTranscriber>().mic else null) }
    single {
        WalkieTalk(
            calls = get(),
            recorder = get(),
            player = get(),
            send =
                demoTalkSendOrNull()
                    ?: { peerId, chunk, peerTicketed, castToo -> get<MeshManager>().sendCallTalk(peerId, chunk, peerTicketed, castToo) },
            links = talkLinks(),
            scope = get<CoroutineScope>(),
            log = { Log.i(TAG, it) },
            // A burst is spoken into the headset that has the call (§3.13), else the phone's own microphone.
            headset = {
                get<SystemCallSync>()
                    .routing.value
                    ?.current
                    ?.headset == true
            },
            transcript = if (STT_WIRED) get<WalkieTranscriber>() else null,
            field = fieldLog(),
        )
    }
}

/**
 * The field log (ADR 2026-10.a239), resolved at each record and never as the call graph builds, like every mesh seam
 * here: it is the mesh graph's, which the service builds off the main thread, and that graph is up before a call is
 * dialled or rings.
 */
private fun Scope.fieldLog(): FieldRecorder = FieldRecorder { event -> get<FieldLog>().record(event) }

/**
 * When a call is placed as a walkie (§3.11): the peer must run one, and a live call must look impossible — the peer
 * takes none (a build may take mesh calls only, ADR 2026-10.swvp), or there is no Internet route here (no spool, no STUN
 * or TURN) and no Wi-Fi Direct leg, which wants a neighbour that runs the transfer's radio with direct calls on, and
 * this phone able to host it. A wrong guess costs little: a live call with no path drops to a walkie anyway.
 */
private fun Scope.walkieRoute(): CallWalkie =
    object : CallWalkie {
        override val enabled = true

        override suspend fun capable(peerId: String) =
            (get<PeerRepository>().find(peerId)?.capabilities ?: 0L) and Protocol.CAP_WALKIE != 0L

        override suspend fun liveLikely(peerId: String): Boolean {
            val caps = get<PeerRepository>().find(peerId)?.capabilities ?: 0L
            if (caps and Protocol.CAP_CALL == 0L) return false
            if (get<InternetGate>().isOnline()) return true
            val neighbour = get<MeshController>().neighbors.value.any { it.nodeId == peerId }
            val direct = caps and Protocol.CAP_DIRECT_TRANSFER != 0L
            // The caller hosts the leg, so a phone that may not host has none to offer (ADR 2026-10.w3dw).
            val hosts = get<DirectWifiLease>().mayHost()
            return neighbour && direct && hosts && !get<SettingsStore>().debugDirectCallsOff.first()
        }

        override fun castTicketed() = get<MeshManager>().castTicketed()
    }

/**
 * C-3.3-5's evidence that a call's signals can reach a peer, read off the mesh's own sets by [CallReachRule]: a live
 * link, the peer heard through one, a Wi-Fi Aware sighting ([MeshTransport.bulkRouteTo]), or a spool we share a scope on
 * that the peer's own traffic reached (`spoolPresentPeers`, the presence dot's window). All in memory.
 */
private fun Scope.callReach(): CallReach =
    CallReach { peerId ->
        val mesh = get<MeshController>()
        val now = System.currentTimeMillis()
        CallReachRule.reachable(
            peerId,
            neighbours = mesh.neighbors.value.mapTo(HashSet()) { it.nodeId },
            heardIndirectly = indirectPeers(mesh.heardIndirectly.value, now).keys,
            sighted = get<MeshTransport>().bulkRouteTo(peerId) != BulkRoute.None,
            spoolPresent = spoolPresentPeers(mesh.spoolStatus(), now),
        )
    }

/**
 * The walkie's view of the mesh (§3.11): a chunk's link to a peer, from the live links, the peers heard through them
 * and whether a relay would cast to it (§2.7), and the call's hold on a Wi-Fi Aware link to it (`MeshTransport.holdLink`).
 */
private fun Scope.talkLinks(): TalkLinks =
    object : TalkLinks {
        override fun linkTo(
            peerId: String,
            peerTicketed: () -> Boolean,
        ): Flow<TalkLink> {
            val mesh = get<MeshController>()
            val manager = get<MeshManager>()
            // A relay's hello, its SUB's answer, a ticket's verdict and the peer's traffic arrive on no flow the walkie can
            // watch, and the radio path lapses on the clock alone (`TalkRadioPaths.WINDOW_MS`), so both are looked at again
            // every few seconds; the links move the moment they do. A relay counts only where the peer was lately seen: one
            // it never was on fans the cast out to nobody.
            val looked =
                flow {
                    while (true) {
                        emit(manager.talkRadioPath(peerId) to manager.castCoversTalk(peerId, peerTicketed()))
                        delay(CAST_LOOK_MS)
                    }
                }
            return combine(mesh.neighbors, looked) { linked, (radioPath, cast) ->
                TalkLink.of(peerId, linked.mapTo(HashSet()) { it.nodeId }, radioPath, cast)
            }.distinctUntilChanged()
        }

        override fun raise(peerId: String): Boolean = get<MeshTransport>().holdLink(peerId)

        // sendCallTalk's order: the peer's own link, a Wi-Fi Aware one first (sendLive), else a flood over any link,
        // and a cast through a relay for a peer the radios have no path to, or beside the radio copy when the peer's own
        // link fades or the session's reports say the radios lose chunks.
        override fun pathTo(
            peerId: String,
            peerTicketed: Boolean,
            castToo: Boolean,
        ): TalkPath {
            val transport = get<MeshTransport>()
            val linked = transport.neighbors.value
            val manager = get<MeshManager>()
            val cast = !manager.talkRadioPath(peerId) && manager.canCastTalk(peerId, peerTicketed)
            return when {
                cast && manager.castCoversTalk(peerId, peerTicketed) -> TalkPath.SPOOL
                cast -> TalkPath.CAST_UNSEEN
                linked.isEmpty() -> TalkPath.NONE
                castToo && manager.canCastTalk(peerId, peerTicketed) -> TalkPath.LOSSY
                transport.bulkRouteTo(peerId) == BulkRoute.Linked -> TalkPath.WIFI_AWARE
                linked.none { it.nodeId == peerId } -> TalkPath.FLOODED
                transport.liveLinkFading(peerId) && manager.canCastTalk(peerId, peerTicketed) -> TalkPath.FADING
                transport.slowLinkTo(peerId) -> TalkPath.BLUETOOTH_CODED
                else -> TalkPath.BLUETOOTH
            }
        }
    }

/** Starts the call surfaces' collectors at process start, on a background thread — never on main. */
fun startCallsIfWired(koin: Koin) {
    if (!CALLS_WIRED) return
    CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
        // First: a call that rings before the system holds it has no route but the one the phone was left in.
        runCatching { koin.get<SystemCallSync>().start() }.onFailure { Log.w(TAG, "system calls not started", it) }
        if (LIVE_CALLS_WIRED) runCatching { koin.get<CallToneSync>().start() }.onFailure { Log.w(TAG, "call tones not started", it) }
        if (LIVE_CALLS_WIRED) {
            runCatching { koin.get<CallMediaSampler>().start() }.onFailure { Log.w(TAG, "call media sampling not started", it) }
        }
        runCatching { koin.get<CallController>().start() }.onFailure { Log.w(TAG, "calls not started", it) }
        runCatching { koin.get<CallLog>().start() }.onFailure { Log.w(TAG, "call records not started", it) }
        if (WALKIE_WIRED) runCatching { koin.get<WalkieTalk>().start() }.onFailure { Log.w(TAG, "walkie not started", it) }
        if (STT_WIRED) {
            runCatching { koin.get<SttModelStore>().start() }.onFailure { Log.w(TAG, "speech model not started", it) }
            runCatching { koin.get<SttKeytermFeed>().start() }.onFailure { Log.w(TAG, "speech key terms not started", it) }
        }
    }
}

/** The app's screens, under a bar that leads back to a live call ([CallBarHost]) — or as they are, with no calls. */
@Composable
fun WithCallBar(content: @Composable () -> Unit) {
    if (CALLS_WIRED) CallBarHost(content) else content()
}

private const val TAG = "KnitCall"

/** How often the walkie's link looks again at whether a relay would cast to its peer. */
private const val CAST_LOOK_MS = 3_000L
