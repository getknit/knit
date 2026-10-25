package app.getknit.knit.ui.call

import android.content.Context
import android.media.AudioManager
import android.media.MediaRecorder
import app.getknit.knit.call.CallCaptioner
import app.getknit.knit.call.CallMedia
import app.getknit.knit.call.CallSession
import app.getknit.knit.call.CaptureHealth
import app.getknit.knit.call.CaptureProfile
import app.getknit.knit.call.DirectLink
import app.getknit.knit.call.IceNetwork
import app.getknit.knit.call.IceProbe
import app.getknit.knit.call.IceProtocol
import app.getknit.knit.call.IceType
import app.getknit.knit.call.MediaEvent
import app.getknit.knit.call.ProbeResult
import app.getknit.knit.call.ProbeVerdict
import app.getknit.knit.call.SdpGuard
import app.getknit.knit.call.TurnServer
import app.getknit.knit.call.iceSummary
import app.getknit.knit.fieldlog.FieldEvent
import app.getknit.knit.fieldlog.FieldRecorder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.AudioTrackSink
import org.webrtc.CandidatePairChangeEvent
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.IceCandidateErrorEvent
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.NetworkChangeDetector
import org.webrtc.NetworkMonitor
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RTCStatsCollectorCallback
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * [CallMedia] over libwebrtc (experimental, `LIVE_CALLS_WIRED` — the library is packaged only there): one audio-only
 * Unified Plan peer connection per call, Opus over DTLS-SRTP, bundled and RTCP-muxed (C-3.7-4). The factory and its
 * audio device module are built on the first call and kept for the process. Its audio goes where Telecom routes the
 * call (§3.13, [TelecomCalls]): the module plays and records on the voice-communication usage, which follows the call's
 * endpoint, so nothing here sets a route, a mode or focus.
 *
 * **Gathering is continual, and a description never leaves without a candidate.** libwebrtc's Android network monitor
 * starts with the first allocation and reports the phone's networks a few milliseconds later; gathering once, the
 * first call of a process finished gathering over no network at all and sent an SDP with no candidate on either side
 * (device-observed 2026-10-07: ICE never left NEW, and the call ended no_route). So the description waits for its
 * first candidate, and candidates that come after it ride the one CANDS a few seconds on.
 *
 * **A call between neighbours also gathers on its Wi-Fi Direct group** (§3.10, ADR 2026-10.kxnw): the monitor's
 * detector is [DirectNetworkDetector], which adds the group [link] names as an Ethernet-typed network, so its pair wins
 * on cost. A group candidate gathered after the description is no CANDS line — only a peer inside the group can reach
 * it — but a [MediaEvent.DirectCandidates] for the call's lane, as soon as it is gathered.
 *
 * **The microphone is watched, and its path is chosen per phone** (C-3.13-12/13, ADR 2026-10.kw29). The audio module is
 * built for the profile [capture] says — the call-tuned `VOICE_COMMUNICATION` path, or the fallback a generic system
 * image needs — and rebuilt, factory and all, between calls when that changes. Every module's [CaptureTap] watches what
 * it records and moves a call whose path hands it only zeros onto its own recorder mid-call.
 *
 * **It also answers the Calls screen's Test** ([IceProbe], C-3.14-5): a throwaway connection per kind on the same
 * factory, never while a call holds it, and never rebuilding the module under one.
 *
 * **What the field log keeps of a call's media** ([field], ADR 2026-10.a239's amendment): each pair the session
 * selects, as [FieldEvent.CallRoute], and the readings `CallMediaSampler` takes through [fieldSample] — on a stats reader
 * of their own, so the diagnostics card's rates and recent loss are never cut short by them. Types and numbers only.
 */
internal class WebRtcCallMedia(
    private val context: Context,
    private val scope: CoroutineScope,
    private val link: StateFlow<DirectLink?>,
    private val capture: CaptureHealth,
    private val log: (String) -> Unit,
    // The live call's captions (C-3.7-5), fed the peer's decoded audio; null where calls carry none.
    private val captions: CallCaptioner? = null,
    private val field: FieldRecorder = FieldRecorder.NONE,
) : CallMedia,
    IceProbe {
    /** One audio module and the factory over it, for one capture profile. */
    private class Audio(
        val profile: CaptureProfile,
        val factory: PeerConnectionFactory,
        val module: JavaAudioDeviceModule,
        val tap: CaptureTap,
    )

    private val initialized: Unit by lazy {
        // Before the first monitor starts: it builds its detector from this factory, once per start.
        NetworkMonitor.getInstance().setNetworkChangeDetectorFactory { observer, ctx ->
            DirectNetworkDetector(observer, ctx, link, scope, log)
        }
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions(),
        )
    }

    private val audioLock = Any()

    @Volatile private var audio: Audio? = null

    // Probes running now (under [audioLock]): their factory is not swapped under them, as an open call's is not.
    private var probes = 0

    // The sessions open now, newest last: a glare's dropped one can close after its replacement opened.
    private val open = CopyOnWriteArrayList<WebRtcSession>()
    private val path = DefaultPath(context.applicationContext)

    override fun open(
        servers: List<TurnServer>,
        relayOnly: Boolean,
        direct: Boolean,
        events: (MediaEvent) -> Unit,
    ): CallSession {
        val a = audioFor(capture.choice.value.profile)
        // A glare's replacement opens beside the dropped session on the same module: the call is the same call.
        if (open.isEmpty()) {
            a.tap.begin(a.profile.name.lowercase(), canSwitch = a.profile == CaptureProfile.STANDARD)
            captions?.begin()
        }
        return WebRtcSession(
            a.factory,
            a.tap,
            servers,
            relayOnly,
            direct,
            link,
            events,
            scope,
            log,
            path,
            captions,
            field,
            onClosed = ::closed,
        ).also(open::add)
    }

    private fun closed(session: WebRtcSession) {
        open.remove(session)
        if (open.isEmpty()) {
            audio?.tap?.end()
            captions?.end()
        }
    }

    /**
     * The audio for [wanted]: the module built now when it matches, or while a session still holds it (the profile
     * changes for the next call, never under one); else a fresh module and factory, the old ones disposed.
     */
    private fun audioFor(wanted: CaptureProfile): Audio =
        synchronized(audioLock) {
            initialized
            val current = audio
            val held = open.isNotEmpty() || probes > 0
            if (current != null && (current.profile == wanted || held)) return current
            current?.let {
                it.factory.dispose()
                it.module.release()
            }
            build(wanted).also { audio = it }
        }

    private fun build(profile: CaptureProfile): Audio {
        val standard = profile == CaptureProfile.STANDARD
        val tap =
            CaptureTap(
                audio = checkNotNull(context.getSystemService(AudioManager::class.java)),
                onSwitched = { scope.launch { capture.learnedSilent() } },
                log = log,
            )
        val module =
            JavaAudioDeviceModule
                .builder(context.applicationContext)
                .setAudioSource(
                    if (standard) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION,
                )
                // Off on the fallback, so WebRTC runs its own canceller and suppressor: none rides that source.
                .setUseHardwareAcousticEchoCanceler(standard)
                .setUseHardwareNoiseSuppressor(standard)
                .setAudioBufferCallback(tap)
                .setAudioRecordErrorCallback(tap)
                .setAudioRecordStateCallback(tap)
                .createAudioDeviceModule()
        tap.module = module
        tap.faultSilence = pendingFault
        tap.feed = pendingFeed
        val factory = PeerConnectionFactory.builder().setAudioDeviceModule(module).createPeerConnectionFactory()
        log(
            "webrtc: audio module on ${profile.name.lowercase()} (${capture.choice.value.reason
                ?.name
                ?.lowercase() ?: "default"})",
        )
        return Audio(profile, factory, module, tap)
    }

    override suspend fun probe(
        stun: List<String>,
        turn: TurnServer?,
    ): ProbeResult? {
        val factory =
            synchronized(audioLock) {
                if (open.isNotEmpty()) return null
                audioFor(capture.choice.value.profile).factory.also { probes++ }
            }
        try {
            return coroutineScope {
                val stunVerdict =
                    async {
                        if (stun.isEmpty()) {
                            ProbeVerdict.NotTried
                        } else {
                            probeOne(factory, TurnServer(urls = stun, user = "", pass = ""), relayOnly = false, wanted = "srflx")
                        }
                    }
                val turnVerdict =
                    async { turn?.let { probeOne(factory, it, relayOnly = true, wanted = "relay") } ?: ProbeVerdict.NotTried }
                ProbeResult(stun = stunVerdict.await(), turn = turnVerdict.await())
            }.also { log("webrtc: probe stun ${it.stun}, turn ${it.turn}") }
        } finally {
            synchronized(audioLock) { probes-- }
        }
    }

    /**
     * One kind's probe: a connection over [server] alone, an audio m-line that only receives (no source, so no
     * microphone), an offer set locally to start gathering, then up to [PROBE_MS] for a [wanted]-typed candidate. A 401
     * ends it early: the credential is wrong on every network. Gathering is continual, as a call's is, so the first
     * probe of a process still gathers once the network monitor reports its networks.
     */
    private suspend fun probeOne(
        factory: PeerConnectionFactory,
        server: TurnServer,
        relayOnly: Boolean,
        wanted: String,
    ): ProbeVerdict {
        val reached = CompletableDeferred<Unit>()
        val done = CompletableDeferred<Unit>()
        val codes = CopyOnWriteArrayList<Int>()
        val observer =
            object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate) {
                    if (typeOf(candidate.sdp) != wanted) return
                    reached.complete(Unit)
                    done.complete(Unit)
                }

                override fun onIceCandidateError(event: IceCandidateErrorEvent) {
                    codes += event.errorCode
                    if (event.errorCode == UNAUTHORIZED) done.complete(Unit)
                }

                override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit

                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit

                override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit

                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit

                override fun onAddStream(stream: MediaStream) = Unit

                override fun onRemoveStream(stream: MediaStream) = Unit

                override fun onDataChannel(channel: DataChannel) = Unit

                override fun onRenegotiationNeeded() = Unit
            }
        val config = callConfiguration(iceServersOf(listOf(server)), relayOnly, direct = false, directAsEthernet = false, prune = true)
        val pc = factory.createPeerConnection(config, observer) ?: return ProbeVerdict.NoAnswer
        try {
            pc.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY),
            )
            val offer = createSdp(log) { pc.createOffer(it, MediaConstraints()) } ?: return ProbeVerdict.NoAnswer
            if (!applySdp(log) { pc.setLocalDescription(it, offer) }) return ProbeVerdict.NoAnswer
            withTimeoutOrNull(PROBE_MS) { done.await() }
            return ProbeVerdict.of(reached.isCompleted, codes)
        } finally {
            pc.dispose()
        }
    }

    /** The newest open session's stats, for the call screen's diagnostics card; null with no session or no report. */
    suspend fun stats(): CallStats? = open.lastOrNull()?.stats()

    /**
     * The newest open session's reading for the field log (`CallMediaSampler`), on that session's second stats reader,
     * with the microphone's path and health; null with no session or no report.
     */
    suspend fun fieldSample(reconnecting: Boolean): FieldEvent.CallSample? {
        val stats = open.lastOrNull()?.fieldStats() ?: return null
        val a = audio
        return stats.fieldSample(reconnecting, micPath = a?.profile, capture = a?.tap?.status?.value)
    }

    /** The open call's microphone (C-3.13-13), or null with no call. */
    fun captureStatus(): CaptureStatus? = audio?.tap?.status?.value

    /** A trial's dead standard path (`…debug.CALL --es op capture --es fault silence`): the module's buffers zeroed. */
    var faultSilence: Boolean
        get() = audio?.tap?.faultSilence == true
        set(value) {
            pendingFault = value
            audio?.tap?.faultSilence = value
        }

    // Carried to a module built later, so a fault armed before the first call applies to it.
    @Volatile private var pendingFault = false

    /** A trial's microphone from a file (`…debug.CALL --es op capture --es pcm <file>`), null for the microphone. */
    var micFeed: MicFeed?
        get() = audio?.tap?.feed ?: pendingFeed
        set(value) {
            pendingFeed = value
            audio?.tap?.feed = value
        }

    @Volatile private var pendingFeed: MicFeed? = null

    companion object {
        /** How long a probe waits for its kind's candidate: a `turns:` allocation over a slow network takes seconds. */
        private const val PROBE_MS = 8_000L

        private const val UNAUTHORIZED = 401

        /**
         * A diagnosis switch, never a default: true opens the next calls' `turns:` legs without checking the server's
         * certificate (`TLS_CERT_POLICY_INSECURE_NO_CHECK`). libwebrtc checks TURN/TLS against its own built-in roots,
         * which hold no Let's Encrypt root, so a `turns:` server on a Let's Encrypt certificate gathers no relay; a
         * `tls` relay appearing with this on is what pins that down. In memory, set by `…debug.CALL --es op turntls`.
         */
        @Volatile
        var turnTlsUnchecked: Boolean = false

        /**
         * A measurement switch, never a default: true opens the next calls with TURN pruning off (`NO_PRUNE`), so a
         * call holds every allocation it makes, as before C-7.6-26: the "before" of the allocation count. In memory,
         * set by `…debug.CALL --es op turnprune`.
         */
        @Volatile
        var turnPruneOff: Boolean = false
    }
}

/**
 * The call's peer connection configuration, built once per session; a restart (C-3.8) only swaps its ICE servers.
 *
 * **TURN ports are pruned by priority (C-7.6-26, knit-spool ADR 2026-10.y2ph).** Without it a phone keeps an allocation
 * for every network, address family and TURN URL it tried. Once a relay is ready on a network, libwebrtc releases each
 * relay on that network (by interface name, across every server) that ranks below the best ready one: UDP over TCP over
 * TLS to the server, then IPv6 over IPv4. Ties are kept, so C-7.6-12's second spool stays a backup while its best relay
 * ties the first's (both over UDP), and is lost on a network only where it ranks lower (its TLS relay against the
 * first's UDP). A pruned port's allocation is freed (a Refresh with lifetime 0) once it holds no connection: the
 * caller prunes in the gathering window, before the answer, so at once; the callee has the offer's candidates already,
 * so a port pruned after its checks began lingers until the ICE controller drops them, about a minute after connecting.
 * The deprecated `pruneTurnPorts` stays false, since true would override the policy with this same one.
 */
internal fun callConfiguration(
    servers: List<PeerConnection.IceServer>,
    relayOnly: Boolean,
    direct: Boolean,
    directAsEthernet: Boolean,
    prune: Boolean,
): PeerConnection.RTCConfiguration =
    PeerConnection.RTCConfiguration(servers).apply {
        sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
        rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        // Continual, so a network the monitor reports after the first allocation is still gathered (WebRtcCallMedia).
        continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        turnPortPrunePolicy = if (prune) PeerConnection.PortPrunePolicy.PRUNE_BASED_ON_PRIORITY else PeerConnection.PortPrunePolicy.NO_PRUNE
        // C-3.7-3: relay candidates only; SdpGuard strips anything else on the way out as well.
        iceTransportsType = if (relayOnly) PeerConnection.IceTransportsType.RELAY else PeerConnection.IceTransportsType.ALL
        if (direct) {
            // §3.10: the group's network wins ties too, and the Internet pairs it outranks are pinged often enough to
            // take over within seconds of the group going away.
            if (directAsEthernet) networkPreference = PeerConnection.AdapterType.ETHERNET
            iceBackupCandidatePairPingInterval = BACKUP_PING_MS
        }
    }

private const val BACKUP_PING_MS = 5_000

/** The route's trailing mark for a pair on the call's Wi-Fi Direct group: "host/host/wifi-direct" (§3.10). */
internal const val ROUTE_DIRECT = "wifi-direct"

private class WebRtcSession(
    factory: PeerConnectionFactory,
    // The call's microphone, watched: told of a mute, since the module records on under one.
    private val tap: CaptureTap,
    servers: List<TurnServer>,
    relayOnly: Boolean,
    private val direct: Boolean,
    private val link: StateFlow<DirectLink?>,
    private val events: (MediaEvent) -> Unit,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
    private val path: DefaultPath,
    private val captions: CallCaptioner?,
    private val field: FieldRecorder,
    private val onClosed: (WebRtcSession) -> Unit,
) : CallSession {
    // Two locks, because libwebrtc's observer runs on its signaling thread and a getStats or setConfiguration call
    // blocks the caller until that thread has run it. [lock] keeps the description's bookkeeping (the late and group
    // candidates) and is taken on the signaling thread, so it is never held across a call into [pc]. [pcLock] is held
    // across those calls and over close()'s flag, so close() never disposes [pc] under one; it guards [config] and
    // [preferred], is never taken on the signaling thread, and nothing under it takes [lock]. Holding [lock] across
    // getStats froze the call screen whenever a candidate came during a stats read.
    private val lock = Any()
    private val pcLock = Any()
    private val statsReader = CallStatsReader()

    // The field log's own reader (`fieldStats`): a reader's rates and recent loss are since its last report.
    private val fieldReader = CallStatsReader()

    // How many pairs this session has selected, for the field log's route lines.
    private val routes = AtomicInteger()
    private val relays = RelayTally()
    private val gathered = CompletableDeferred<Unit>()
    private val firstCandidate = CompletableDeferred<Unit>()

    // Set when our description is handed to the call layer: a candidate after it is a late one (C-3.6-2).
    private var described = false
    private val late = mutableListOf<String>()
    private var lateReported = false

    // Set under [pcLock]; read anywhere.
    @Volatile private var closed = false

    // Group candidates gathered after the description, waiting out a short debounce for the call's lane.
    private val directLate = mutableListOf<String>()
    private var directFlushArmed = false

    @Volatile
    private var selected: String? = null

    private val source: AudioSource = factory.createAudioSource(MediaConstraints())
    private val track: AudioTrack = factory.createAudioTrack("knit-audio", source)

    // The peer's track and the sink on it that hands its decoded audio to the captions (C-3.7-5), and whether the
    // session has closed, under [sinkLock] — never [pcLock]: the sink is added on the signaling thread, which a stats
    // read holds [pcLock] waiting for. Taken off before the connection is disposed, so no audio thread calls into a
    // closed session.
    private val sinkLock = Any()
    private var heard: Pair<AudioTrack, AudioTrackSink>? = null
    private var sinkClosed = false

    private val observer =
        object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = log("webrtc: ice $state")

            override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                log("webrtc: connection $state")
                when (state) {
                    PeerConnection.PeerConnectionState.CONNECTED -> events(MediaEvent.Connected)
                    PeerConnection.PeerConnectionState.DISCONNECTED -> events(MediaEvent.Disconnected)
                    PeerConnection.PeerConnectionState.FAILED -> events(MediaEvent.Failed)
                    else -> Unit
                }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                // Gathering continually, libwebrtc never reports COMPLETE; kept for a policy that does.
                if (state != PeerConnection.IceGatheringState.COMPLETE) return
                gathered.complete(Unit)
                reportLate()
            }

            override fun onIceCandidate(candidate: IceCandidate) {
                firstCandidate.complete(Unit)
                relays.gathered(candidate.sdp, candidate.serverUrl)?.let { log("webrtc: relay gathered $it") }
                if (onGroup(candidate.sdp)) {
                    lateDirect(candidate.sdp)
                    return
                }
                synchronized(lock) { if (described && !lateReported) late += candidate.sdp }
            }

            override fun onIceCandidateError(event: IceCandidateErrorEvent) {
                // A TURN server out of room (C-7.6-54): the call layer marks it and may gather on the next one.
                if (event.errorCode in TURN_REFUSALS) events(MediaEvent.TurnRefused(event.errorCode, event.url.orEmpty()))
                // The answer and the kind of server only: its URL names a host, which the call names by its slot, and
                // the local address is ours to keep out of the log.
                log("webrtc: candidate error ${event.errorCode} ${event.errorText} from ${serverKind(event.url)}")
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {
                // Pruned (C-7.6-26) or its network gone: libwebrtc is releasing these relays' allocations.
                val released = relays.removed(candidates.map { it.sdp })
                if (released.isNotEmpty()) log("webrtc: relays removed ${RelayTally.counts(released)}")
            }

            override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent) {
                val onGroup = onGroup(event.local.sdp)
                val group = if (onGroup) "/$ROUTE_DIRECT" else ""
                selected = "${typeOf(event.local)}/${typeOf(event.remote)}$group"
                field.record(
                    FieldEvent.CallRoute(
                        local = IceType.of(typeOf(event.local)) ?: IceType.OTHER,
                        remote = IceType.of(typeOf(event.remote)) ?: IceType.OTHER,
                        protocol = IceProtocol.ofLine(event.local.sdp),
                        network = IceNetwork.of(event.local.adapterType?.name),
                        direct = onGroup,
                        seq = routes.incrementAndGet(),
                    ),
                )
                // libwebrtc's own words for why it switched, then every answered pair's round trip at that moment:
                // what a switch to a lower-priority pair (srflx → relay) was chosen on.
                log("webrtc: route $selected (${event.reason})")
                scope.launch { report()?.let { log("webrtc: pairs ${pairSummary(it)}") } }
            }

            override fun onAddStream(stream: MediaStream) = Unit

            override fun onRemoveStream(stream: MediaStream) = Unit

            override fun onDataChannel(channel: DataChannel) = Unit

            override fun onRenegotiationNeeded() = Unit

            override fun onAddTrack(
                receiver: RtpReceiver,
                streams: Array<out MediaStream>,
            ) = listen(receiver)
        }

    private val pc: PeerConnection
    private val config: PeerConnection.RTCConfiguration

    // Follows Android's default network for the session's life (DefaultPath); null on a direct call, which prefers
    // its group's Ethernet-typed network instead, or with the bridge's switch off.
    private val pathWatch: AutoCloseable?

    @Volatile
    private var preferred: PeerConnection.AdapterType? = null

    init {
        val tlsUnchecked = WebRtcCallMedia.turnTlsUnchecked
        val prune = !WebRtcCallMedia.turnPruneOff
        config =
            callConfiguration(
                iceServersOf(servers),
                relayOnly = relayOnly,
                direct = direct,
                directAsEthernet = DirectNetworkDetector.reportAs == NetworkChangeDetector.ConnectionType.CONNECTION_ETHERNET,
                prune = prune,
            )
        val followPath = !direct && DefaultPath.enabled
        if (followPath) {
            // Known now unless this is the process's first call: its network monitor starts with the connection.
            path.preference()?.let {
                config.networkPreference = it
                preferred = it
            }
        }
        pc = checkNotNull(factory.createPeerConnection(config, observer)) { "no peer connection" }
        pc.addTrack(track, listOf("knit"))
        log(
            "webrtc: opened (${servers.iceSummary()}, relay=$relayOnly, direct=$direct" +
                (if (tlsUnchecked) ", turns unchecked" else "") +
                (if (prune) "" else ", prune off") +
                (if (followPath) ", prefer ${preferred?.name?.lowercase() ?: "pending"}" else "") + ")",
        )
        // Also fires at once with the current default: the first call of a process gets its preference here.
        pathWatch = if (followPath) path.watch(::onDefaultNetwork) else null
    }

    // The peer's audio track has arrived: its decoded audio, as it plays, to a captions source of this session's own.
    // Called on the signaling thread; the sink is called on the audio thread, every 10 ms, and must not wait.
    private fun listen(receiver: RtpReceiver) {
        val captions = captions ?: return
        val remote = receiver.track() as? AudioTrack ?: return
        val tap = captions.source()
        val sink =
            AudioTrackSink { data, bits, rate, channels, _, _ ->
                // 16-bit PCM is all the tap reads; anything else is skipped rather than misread.
                if (bits == PCM_BITS) tap.pcm(data, rate, channels)
            }
        // Added under the sink's lock, so a close either finds it to take off or has already closed and it is never
        // added.
        val replaced =
            synchronized(sinkLock) {
                if (sinkClosed) return
                remote.addSink(sink)
                heard.also { heard = remote to sink }
            }
        replaced?.let { (track, old) -> track.removeSink(old) }
    }

    override suspend fun offer(): String? {
        val description = create { pc.createOffer(it, MediaConstraints()) } ?: return null
        if (!apply { pc.setLocalDescription(it, description) }) return null
        return gatheredDescription("offer")
    }

    override suspend fun answer(remoteOffer: String): String? {
        if (!apply { pc.setRemoteDescription(it, SessionDescription(SessionDescription.Type.OFFER, remoteOffer)) }) return null
        val description = create { pc.createAnswer(it, MediaConstraints()) } ?: return null
        if (!apply { pc.setLocalDescription(it, description) }) return null
        return gatheredDescription("answer")
    }

    override suspend fun restart(servers: List<TurnServer>): String? {
        if (!useServers(servers)) return null
        relays.restarted()
        pc.restartIce()
        val description = create { pc.createOffer(it, MediaConstraints()) } ?: return null
        if (!apply { pc.setLocalDescription(it, description) }) return null
        return restartedDescription("restart offer")
    }

    override suspend fun reanswer(
        remoteOffer: String,
        servers: List<TurnServer>,
    ): String? {
        if (!useServers(servers)) return null
        relays.restarted()
        if (!apply { pc.setRemoteDescription(it, SessionDescription(SessionDescription.Type.OFFER, remoteOffer)) }) return null
        val description = create { pc.createAnswer(it, MediaConstraints()) } ?: return null
        if (!apply { pc.setLocalDescription(it, description) }) return null
        return restartedDescription("restart answer")
    }

    /**
     * A restart's ICE servers (§3.8): what the call layer just fetched, so a long call's TURN credential is a live one.
     * False once closed. A refused configuration keeps the old servers and the restart goes on with them. The same
     * [config] goes back, so the restart's new ports are pruned under the policy the call opened with.
     */
    private fun useServers(servers: List<TurnServer>): Boolean =
        synchronized(pcLock) {
            if (closed) return false
            config.iceServers = iceServersOf(servers)
            if (!pc.setConfiguration(config)) log("webrtc: restart's ICE servers refused; keeping the old ones")
            true
        }

    /**
     * A restart's description, after C-3.6-1's gathering window on the fresh ICE credentials. Gathering is continual
     * and the first candidate long came, so this waits the window alone; no CANDS follows a restart (§3.8).
     */
    private suspend fun restartedDescription(kind: String): String? {
        delay(GATHER_MS)
        if (closed) return null
        val sdp = pc.localDescription?.description
        log("webrtc: $kind carries ${summary(sdp.orEmpty().lines().filter { it.startsWith("a=candidate:") })}")
        return sdp
    }

    override suspend fun accept(remoteAnswer: String): Boolean =
        apply { pc.setRemoteDescription(it, SessionDescription(SessionDescription.Type.ANSWER, remoteAnswer)) }

    override fun addCandidates(lines: List<String>) {
        if (closed) return
        // One audio m-line, bundled: its mid is "0" under Unified Plan.
        lines.forEach { pc.addIceCandidate(IceCandidate("0", 0, it)) }
    }

    override fun setMuted(muted: Boolean) {
        if (!closed) track.setEnabled(!muted)
        tap.muted = muted
    }

    override suspend fun route(): String? = selected

    /** One stats report, read: the first call's counters are the bitrates' baseline, so those come on the second. */
    suspend fun stats(): CallStats? = read(statsReader)

    /** The same reading on the field log's own reader, so neither consumer's rates eat into the other's. */
    suspend fun fieldStats(): CallStats? = read(fieldReader)

    private suspend fun read(reader: CallStatsReader): CallStats? {
        val report = report() ?: return null
        val prefer =
            synchronized(pcLock) { config.networkPreference }
                .takeIf { it != PeerConnection.AdapterType.UNKNOWN }
                ?.name
                ?.lowercase()
        return reader.read(report, wifiDirect = selected?.endsWith("/$ROUTE_DIRECT") == true).copy(prefer = prefer)
    }

    /** One raw stats report, or null once closed or after [STATS_MS]. */
    private suspend fun report(): List<StatEntry>? =
        withTimeoutOrNull(STATS_MS) {
            suspendCancellableCoroutine { cont ->
                val callback =
                    RTCStatsCollectorCallback { report ->
                        val entries =
                            report.statsMap.values.map { StatEntry(it.id, it.type, it.timestampUs, it.members) }
                        if (cont.isActive) cont.resume(entries)
                    }
                // Under [pcLock], so close() cannot dispose the connection between the check and the call.
                synchronized(pcLock) {
                    if (closed) cont.resume(null) else pc.getStats(callback)
                }
            }
        }

    /** Android moved its default network: the call prefers the new one from now on (DefaultPath). */
    private fun onDefaultNetwork(network: android.net.Network) {
        val type = path.preferenceFor(network) ?: return
        val applied =
            synchronized(pcLock) {
                if (closed || type == preferred) return
                preferred = type
                config.networkPreference = type
                pc.setConfiguration(config)
            }
        log("webrtc: prefer ${type.name.lowercase()} (android default)${if (applied) "" else ", refused mid-call"}")
    }

    override fun close() {
        synchronized(pcLock) {
            if (closed) return
            closed = true
        }
        pathWatch?.close()
        onClosed(this)
        gathered.complete(Unit)
        // What this phone held of the TURN server's quota when the call ended (C-7.6-26).
        log("webrtc: ${relays.summary()}")
        synchronized(sinkLock) {
            sinkClosed = true
            heard.also { heard = null }
        }?.let { (remote, sink) -> remote.removeSink(sink) }
        // dispose() closes the connection and its senders; the source is ours to free.
        pc.dispose()
        source.dispose()
        log("webrtc: closed")
    }

    /**
     * Waits for the first candidate (up to [FIRST_CANDIDATE_MS] — the network monitor may not have reported a network
     * yet), then gathers on for [GATHER_MS] (C-3.6-1), and hands over the description with what was gathered. What
     * comes after rides one CANDS, [LATE_WINDOW_MS] on.
     */
    private suspend fun gatheredDescription(kind: String): String? {
        // A phone whose only network is the group to come must not hold its offer for one that cannot come yet.
        withTimeoutOrNull(if (direct) FIRST_CANDIDATE_DIRECT_MS else FIRST_CANDIDATE_MS) { firstCandidate.await() }
        withTimeoutOrNull(GATHER_MS) { gathered.await() }
        synchronized(lock) {
            if (closed) return null
            described = true
            // Gathering already finished: nothing can be late.
            if (gathered.isCompleted) lateReported = true
        }
        val sdp = pc.localDescription?.description
        log("webrtc: $kind carries ${summary(sdp.orEmpty().lines().filter { it.startsWith("a=candidate:") })}")
        // An SDP relay line does not say how this phone reaches its TURN server; the stats do (udp, tcp or tls).
        scope.launch { report()?.let { log("webrtc: relays ${relaySummary(it)}") } }
        scope.launch {
            delay(LATE_WINDOW_MS)
            reportLate()
        }
        return sdp
    }

    /** Whether [line]'s address sits on the call's Wi-Fi Direct group. */
    private fun onGroup(line: String): Boolean {
        if (!direct) return false
        val address = SdpGuard.candidateAddress(line) ?: return false
        return link.value?.carries(address) == true
    }

    /**
     * A group candidate: in the description when it came before it, else for the lane after a short debounce, so
     * the candidates of one new network go as one frame.
     */
    private fun lateDirect(line: String) {
        val arm =
            synchronized(lock) {
                if (!described || closed) return
                directLate += line
                !directFlushArmed.also { directFlushArmed = true }
            }
        if (!arm) return
        scope.launch {
            delay(DIRECT_DEBOUNCE_MS)
            val lines =
                synchronized(lock) {
                    directFlushArmed = false
                    if (closed) return@launch
                    directLate.toList().also { directLate.clear() }
                }
            log("webrtc: group ${summary(lines)}")
            if (lines.isNotEmpty()) events(MediaEvent.DirectCandidates(lines))
        }
    }

    /** The candidates gathered since the description, once: the one CANDS this side sends (C-3.6-2). */
    private fun reportLate() {
        val lines =
            synchronized(lock) {
                if (!described || lateReported || closed) return
                lateReported = true
                late.toList()
            }
        log("webrtc: late ${summary(lines)}")
        if (lines.isNotEmpty()) events(MediaEvent.LateCandidates(lines))
    }

    private suspend fun create(call: (SdpObserver) -> Unit): SessionDescription? = createSdp(log, call)

    private suspend fun apply(call: (SdpObserver) -> Unit): Boolean = applySdp(log, call)

    private companion object {
        const val FIRST_CANDIDATE_MS = 5_000L
        const val FIRST_CANDIDATE_DIRECT_MS = 1_000L
        const val DIRECT_DEBOUNCE_MS = 250L
        const val GATHER_MS = 1_500L
        const val LATE_WINDOW_MS = 4_000L
        const val STATS_MS = 2_000L

        // The sample width the captions' tap reads (libwebrtc's playout is 16-bit).
        const val PCM_BITS = 16

        fun typeOf(candidate: IceCandidate): String = typeOf(candidate.sdp)

        /** "3 candidates (host 1, srflx 1, relay 1)" — the types only, never an address. */
        fun summary(lines: List<String>): String {
            val types =
                lines
                    .groupingBy(::typeOf)
                    .eachCount()
                    .entries
                    .joinToString { "${it.key} ${it.value}" }
            return "${lines.size} candidates${if (types.isEmpty()) "" else " ($types)"}"
        }

        // RFC 8656's two answers that mean the server is full, not that the credential is wrong: 486 Allocation Quota
        // Reached and 508 Insufficient Capacity.
        val TURN_REFUSALS = setOf(486, 508)

        /** "turn/udp", "turns/tcp" or "stun": what kind of server a candidate error came from, without its host. */
        fun serverKind(url: String?): String {
            val scheme = url?.substringBefore(':', missingDelimiterValue = "")?.lowercase().orEmpty()
            val transport =
                url
                    ?.substringAfter("transport=", missingDelimiterValue = "")
                    ?.substringBefore('&')
                    ?.lowercase()
                    .orEmpty()
            return when {
                scheme.isEmpty() -> "?"
                transport.isEmpty() -> scheme
                else -> "$scheme/$transport"
            }
        }
    }
}

/** A candidate line's type: host, srflx, prflx or relay. */
private fun typeOf(line: String): String = line.substringAfter(" typ ", "?").substringBefore(' ')

/**
 * [servers] for libwebrtc: a credential only where there is one (a STUN entry carries none, CallManager.servers), and
 * [WebRtcCallMedia.turnTlsUnchecked]'s diagnosis switch on `turns:` legs.
 */
private fun iceServersOf(servers: List<TurnServer>): List<PeerConnection.IceServer> {
    val tlsUnchecked = WebRtcCallMedia.turnTlsUnchecked
    return servers.map {
        val builder = PeerConnection.IceServer.builder(it.urls)
        if (it.user.isNotEmpty()) builder.setUsername(it.user).setPassword(it.pass)
        if (tlsUnchecked && it.urls.any { url -> url.startsWith("turns:") }) {
            builder.setTlsCertPolicy(PeerConnection.TlsCertPolicy.TLS_CERT_POLICY_INSECURE_NO_CHECK)
        }
        builder.createIceServer()
    }
}

private const val SDP_MS = 10_000L

/** A description created by [call], or null when it failed or took longer than [SDP_MS]. */
private suspend fun createSdp(
    log: (String) -> Unit,
    call: (SdpObserver) -> Unit,
): SessionDescription? =
    withTimeoutOrNull(SDP_MS) {
        suspendCancellableCoroutine { cont ->
            call(
                object : SdpObserver {
                    override fun onCreateSuccess(description: SessionDescription) = cont.resume(description)

                    override fun onCreateFailure(error: String?) {
                        log("webrtc: create failed: $error")
                        cont.resume(null)
                    }

                    override fun onSetSuccess() = Unit

                    override fun onSetFailure(error: String?) = Unit
                },
            )
        }
    }

/** Whether the description [call] sets was applied within [SDP_MS]. */
private suspend fun applySdp(
    log: (String) -> Unit,
    call: (SdpObserver) -> Unit,
): Boolean =
    withTimeoutOrNull(SDP_MS) {
        suspendCancellableCoroutine { cont ->
            call(
                object : SdpObserver {
                    override fun onCreateSuccess(description: SessionDescription) = Unit

                    override fun onCreateFailure(error: String?) = Unit

                    override fun onSetSuccess() = cont.resume(true)

                    override fun onSetFailure(error: String?) {
                        log("webrtc: set failed: $error")
                        cont.resume(false)
                    }
                },
            )
        }
    } == true
