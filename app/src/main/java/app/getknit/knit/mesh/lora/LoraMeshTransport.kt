package app.getknit.knit.mesh.lora

import app.getknit.knit.mesh.FanoutHint
import app.getknit.knit.mesh.FastPathDrop
import app.getknit.knit.mesh.InboundFrame
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.MeshPost
import app.getknit.knit.mesh.MeshTransport
import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.PublicChannelSink
import app.getknit.knit.mesh.PublicPostRefusal
import app.getknit.knit.mesh.ReceivedFile
import app.getknit.knit.mesh.SeenSet
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.mesh.TransportKind
import app.getknit.knit.mesh.isPresenceEvidence
import app.getknit.knit.mesh.link.FastFrameCodec
import app.getknit.knit.mesh.link.FragReassembler
import app.getknit.knit.mesh.meshNodeLabel
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import app.getknit.knit.mesh.runRound
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * A [MeshTransport] that carries this phone's user's own small frames over LoRa via a Meshtastic board
 * ([MeshtasticLink]): their **Nearby-room** posts and their sealed DMs, at kilometre range. It is a fast-plane-ONLY
 * child of [app.getknit.knit.mesh.CompositeMeshTransport] (added last, lowest preference), and since ADR
 * 2026-10.rp3s an **endpoint**, not a mesh plane: the board airs only frames this phone's user wrote
 * ([LoraFramePolicy.airs]) — no pocket bridge, no profile beacon, no re-fan of anybody else's frame, no backfill or
 * OFFER gossip, and no room ✓✓.
 *
 * - [neighbors] is always empty, so the reliable flood, custody digest sync, key requests, blob pulls and
 *   the `watchNeighbors` hooks never touch a ~1 kbps link — [send]/[sendFile]/[sendDigest] are no-ops, and so is
 *   [fastSend].
 * - [fastFanout]/[longRangeFanout] are the only outbound Knit paths: they decode the envelope (never re-encoding
 *   it — `sig`/`signed` pass through [FastFrameCodec] byte-exact), apply [LoraFramePolicy] (ours, and a room post in
 *   one packet or DM-form chat), skip a DM-form frame a live link or a spool already carries ([coveredByLink], ADR
 *   054; ADR 2026-09.y5f3), compact/fragment via [LoraFrameCodec], and pace the result ([LoraPacePolicy]) onto the
 *   board. The long-range path is what carries sealed DM-form chat (ADR 039).
 * - inbound packets are decoded/reassembled and injected into [inbound] exactly like the Wi-Fi Aware fast
 *   plane's `emitFastWire`, so the router's dedup/verify/custody/relay all run unchanged — and a frame heard over
 *   LoRa still floods into the pocket over BLE/NAN, which costs no air. Only what goes *out* is ours alone.
 * - [shortRange] is false: a LoRa sighting doesn't imply proximity, so siblings ignore its `reachable` set
 *   and nothing above the composite may read it as *nearby* (`MeshController.neighbors` is the short-range
 *   set for exactly this reason). [reachable] here is keyed on the frame **author**. Only a frame that passes
 *   [app.getknit.knit.mesh.isPresenceEvidence] counts — a custody re-serve says where a frame has been, not where
 *   its author is. The Internet plane keys presence off the same rule.
 *
 * On first hearing a peer the transport also re-offers our own unacked DMs to it ([reofferTo]) — the plane's only
 * backfill, since custody's digest sync needs a data path. Since ADR 2026-10.a4pm that is also how most DMs to a
 * quiet peer reach the air: a DM-form fan-out rides only to a recipient in [reachable], except one plain DM per
 * recipient per [DM_PROBE_WINDOW_MS] ([dmProbeAt]), whose ✓✓ is what makes a present recipient heard — and, since
 * ADR 2026-10.rp3s, a backed-off re-probe of the newest held one ([LoraReprobe]) for a recipient who stays silent.
 *
 * Its rate limiters outlive the process ([LoraPlaneState]): the airtime ledger, the auto-reply memory, the probe
 * window, the re-offer's hour and the re-probe's backoff are all read back at [start] and written behind a
 * debounce, because a limiter that resets on launch is not a limiter — a restart used to hand this plane a fresh
 * 45-second allowance and re-run every "first time" behaviour against it.
 *
 * [clock] is monotonic (pacing, dedup, linger); [wallClock] is the epoch clock a frame's `sentAt` is stamped
 * in, read by the freshness gate and by the persisted limiters (a monotonic stamp cannot survive a reboot).
 * Pure/Android-free — the
 * only `android.bluetooth.*` sits behind the [MeshtasticLink]/[MeshtasticGattDialer] seam.
 *
 * Large by suppression, as [MeshtasticSession] is: this is the plane's single owner. The pacer loop, the
 * linger sweep and the published status all read and write one set of mutable fields under one scope, so
 * splitting it would mean a second owner of that state.
 */
@Suppress("TooManyFunctions", "LongParameterList", "LargeClass")
internal class LoraMeshTransport(
    private val selfId: suspend () -> String,
    private val link: MeshtasticLink,
    private val config: Flow<LoraConfig?>,
    /** Our own unacked DMs to a peer, newest first — what [reofferTo] and the re-probe air ([app.getknit.knit.mesh.FarPeerFrameSource]). */
    private val farFrames: suspend (nodeId: String) -> List<WireEnvelope> = { emptyList() },
    /**
     * The contacts whose pinned profile names a board (`PeerEntity.loraNode`, supplied by the DI layer): the only
     * recipients the plane probes or re-probes (ADR 2026-10.rp3s). Null means no such source — every recipient counts.
     */
    private val boardHolders: Flow<Set<String>>? = null,
    /** Delivers a post heard on the board's primary channel into the Meshtastic room — [app.getknit.knit.mesh.MeshPostSink]. */
    private val onPublicPost: suspend (MeshPost) -> Unit = {},
    /**
     * The bound board's node number and signing key, reported each time its session comes up. What the
     * profile advertises so a contact's phone can line a heard post up with this one and verify it
     * (`SettingsStore.loraBoard`); persisted by the caller, so a link drop does not unsay it and only a
     * different board changes it.
     */
    private val onBoardBound: suspend (BoardBinding) -> Unit = {},
    private val scope: CoroutineScope,
    private val metrics: MeshMetrics,
    /**
     * Where this plane's rate limiters go when the process dies, so a restart does not begin with a fresh
     * airtime allowance and re-run every "first time" behaviour against it ([LoraPlaneState]).
     */
    private val state: LoraPlaneState = LoraPlaneState.None,
    private val clock: () -> Long,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
    // A board on a dedicated slot lifts the politeness ceiling, the setup's whole point (ADR 067's amendment).
    private val pace: LoraPacePolicy = LoraPacePolicy(airtime = LoraAirtime(dedicatedUnlocksDuty = true)),
    /**
     * How often [lingerSweepLoop] re-reads the heard set. The field value always; the mesh-in-a-box lab shortens it,
     * because a scenario that moves the calendar past the linger needs the sweep to notice inside an await.
     */
    private val lingerSweepMs: Long = LINGER_SWEEP_MS,
) : MeshTransport,
    LoraPlaneStatus,
    PublicChannelSink {
    override val kind = TransportKind.LoRa
    override val hasFastPlane = true
    override val shortRange = false

    private val _neighbors = MutableStateFlow<Set<Peer>>(emptySet())
    override val neighbors = _neighbors.asStateFlow() // always empty: no data-path links over LoRa

    private val _reachable = MutableStateFlow<Set<Peer>>(emptySet())
    override val reachable = _reachable.asStateFlow()

    private val _health = MutableStateFlow(TransportHealth.Unavailable)
    override val health = _health.asStateFlow()

    private val _inbound = MutableSharedFlow<InboundFrame>(extraBufferCapacity = INBOUND_BUFFER)
    override val inbound = _inbound.asSharedFlow()

    override val incomingFiles: Flow<ReceivedFile> = emptyFlow() // LoRa carries no files

    private val _status = MutableStateFlow(LoraStatus())

    /** Serialises [publishStatus]; outermost, never taken under the pacer's or the ledger's monitor. */
    private val statusLock = Any()

    /** A snapshot for the LoRa settings row + the `…debug.LORA` bridge; derived, never routing-affecting. */
    override val status = _status.asStateFlow()

    /**
     * The fan-out's dedup, keyed on the first 8 bytes of the Ed25519 sig ([dedupKey]): one copy of our own frame per
     * ten minutes, so AckSync's verbatim tick retries and a frame the composite fans twice do not air twice. The
     * fan-out's alone since ADR 2026-10.rp3s. It used to record every frame received over LoRa too, so that a heard
     * frame was not re-fanned back over the hop; the admit rule refuses every frame that is not ours now, which makes
     * that add dead. The re-offer and the re-probe never consult it — it records a transmission, and on a plane with
     * no acks that is not evidence anyone heard (ADR 2026-09.y8pu); they ask [LoraPacePolicy.hasQueued] instead.
     */
    private val sigSeen = SeenSet(ttlMillis = SIG_TTL_MS, clock = clock)

    /**
     * Who has been told the board is unmonitored, and when the board last said so — the DM auto-reply's two
     * caps. Not cleared by [quiesce]: the memory is about the senders, not the board, and losing the link
     * for a minute must not earn anybody a second reply.
     */
    private val autoReply = DmAutoReplyPolicy(clock = clock)

    /**
     * When a DM last went out as a probe to each recipient this plane had not heard (ADR 2026-10.a4pm): the
     * reach gate's one exception per [DM_PROBE_WINDOW_MS]. Persisted with the other limiters and, like
     * [autoReply], not cleared by [quiesce] — see [LoraDmProbe].
     */
    private val dmProbeAt = LoraDmProbe()

    /**
     * The backed-off re-probe per silent recipient with own DMs held (ADR 2026-10.rp3s): what makes a recipient who
     * missed the probe heard again, now that no beacon or OFFER does. Persisted with the other limiters and, like
     * [dmProbeAt], not cleared by [quiesce] — see [LoraReprobe].
     */
    private val reprobe = LoraReprobe()

    /**
     * The DM-form frames the first-hearing re-offer put on the air within the last [REOFFER_MEMORY_MS], by frame
     * id (ADR 2026-10.a4pm): one frame is re-aired at most once an hour across first hearings, so a recipient
     * heard only now and then does not have the same backlog re-aired every linger cycle. A limiter, so it is
     * persisted with the others and not cleared by [quiesce].
     */
    private val reofferedAt = SeenSet(ttlMillis = REOFFER_MEMORY_MS, clock = clock)
    private val fragSeq = AtomicInteger()
    private val reassembler = FragReassembler<UInt>(now = clock, capacity = FRAG_CAP, timeoutMs = FRAG_TIMEOUT_MS)

    // LoRa-heard senders (a long linger — there are no periodic cues on LoRa).
    // NOTE these are frame **authors**, not radios: an older build's gateway can still air a frame its pocket-mate
    // wrote. That is the right key for `reachable` (who can I reach through this mesh) and the wrong one for "how many
    // radios can I hear" — see boardsHeardAt.
    // Only frames that pass `mesh/FramePresence.kt`'s isPresenceEvidence write here: a re-serve proves where a
    // frame has been, not where its author is, and taking it as presence made powered-off phones look live.
    // Also the reason nothing above this plane may read `reachable` as *nearby* — that is the short-range set.
    private val lastHeardAt = ConcurrentHashMap<String, Long>()

    // Meshtastic node numbers we have heard transmit on our channel: one entry per **radio**, which is what
    // the settings row is actually asking. Counted for every Knit packet including control packets and
    // incomplete fragments — an older build's board that only publishes offers is still a board in range. The value is that
    // radio's last signal reading, so the row reports a link we actually have rather than the last thing the
    // board overheard, and one linger rule ages the reading out with the radio it belongs to.
    private val boardsHeardAt = ConcurrentHashMap<UInt, RxQuality>()
    private val heardPeers = ConcurrentHashMap<String, Peer>()

    // Peers a higher-preference plane holds a **live link** to right now (BLE/NAN, via suppressDataPath). The link
    // cover reads this — [coveredByLink], the send-time LINKED re-ask, the re-offer's skip — and never a sighting:
    // a peer merely heard advertising is being carried by nothing.
    @Volatile
    private var linkedPeers: Set<String> = emptySet()

    /** [linkedPeers], for the `mesh/lab` harness to wait on the composite's hand-off (`LabNode.awaitBoardSawLinks`). */
    internal val linkIds: Set<String> get() = linkedPeers

    // Peers a connected spool was recently a path to (`coveredByInternet`, ADR 2026-09.y5f3). A cover for
    // DM-form traffic only — the spool carries it for free.
    @Volatile
    private var internetPeers: Set<String> = emptySet()

    @Volatile
    private var currentConfig: LoraConfig? = null

    /** The last [boardHolders] set, or null until it has reported. */
    @Volatile
    private var boardHolderIds: Set<String>? = null

    /**
     * Frames fanned before our own id was read, admitted so far (ADR 2026-10.rp3s): capped at [PRE_ID_ADMISSIONS], so a
     * Keystore stall cannot fill the queue with relayed DM-form re-fans that would shed an own room post typed meanwhile.
     * Charged only for a frame that reaches the queue ([admitFanout], [reservePreId]).
     */
    private val preIdAdmitted = AtomicInteger()

    // Largest Data.payload a single board packet may carry, sized DOWN from the negotiated BLE MTU on
    // session-up so a full fragment's ToRadio write fits one ATT op (ESP32 boards commonly cap at MTU 255).
    @Volatile
    // The pre-Ready floor, not the protocol maximum: frames fanned out while the board is still connecting are
    // chunked with this and drained the moment the session is Ready, so it has to fit the smallest MTU a real
    // board negotiates. It used to be MAX_PAYLOAD — every one of those frames then came back TOO_LARGE.
    private var maxPayload: Int = PRE_READY_PAYLOAD

    // Our own node id, resolved off [selfId] (a suspend read) by [start]. Until it lands, every gate that asks
    // "is this frame addressed to us?" answers no, so [pacerLoop] takes nothing before it does (#141).
    private val selfIdKnown = MutableStateFlow<String?>(null)
    private val selfIdCached: String? get() = selfIdKnown.value

    // When this phone last put a post on the foreign public channel, for the per-phone floor. Monotonic
    // (`clock`), so it survives a wall-clock jump; NEVER so the first post of a session is never held.
    private val lastPublicPostAt = AtomicLong(NEVER)

    /**
     * Whether a board is configured — the gate the timed loop parks on. Mirrors `currentConfig != null`
     * as a flow, because [lingerSweepLoop] has to *wait* on it rather than poll it.
     *
     * Most installs never arm this plane (the child joins the composite on `BuildConfig.LORA_PLANE`
     * alone, while `SettingsStore.loraEnabled` defaults false), so an ungated loop is a wake-up every
     * minute, forever, on a plane with no radio behind it.
     */
    private val configured = MutableStateFlow(false)

    private val wake = Channel<Unit>(Channel.CONFLATED)

    /**
     * Flipped once the persisted limiter state has been applied — or has failed to load, which must cost the
     * plane an allowance rather than its voice. Everything that can put a packet on the air waits on it
     * ([awaitRestored]): a frame sent against an empty ledger is exactly the free air [state] exists to stop
     * handing out. [LoraPlaneState.None] flips it on the first pass.
     */
    private val restored = MutableStateFlow(false)

    /** Poked whenever something spends a limiter; [stateSaveLoop] debounces the write behind it. */
    private val stateWake = Channel<Unit>(Channel.CONFLATED)
    private val jobs = mutableListOf<Job>()

    override fun start() {
        // First, and before anything may transmit: what the previous process spent is still owed to the
        // window it spent it in.
        jobs += scope.launch { restoreState() }
        jobs += scope.launch { stateSaveLoop() }
        jobs += scope.launch { resolveSelfId() }
        jobs += scope.launch { config.collect(::onConfig) }
        jobs += scope.launch { link.state.collect(::onLinkState) }
        jobs += scope.launch { link.packets.collect(::onLoraPacket) }
        // Nudge the pacer too: a board that was full is the one state [waitForNextSend] can only wait out on
        // its floor, and this is the event that ends it.
        jobs +=
            scope.launch {
                link.queue.collect {
                    it?.let { q ->
                        pace.onQueueStatus(q.free)
                        if (q.free > 0) wake.trySend(Unit)
                    }
                }
            }
        jobs += scope.launch { link.outcomes.collect(::onNak) }
        jobs += scope.launch { link.battery.collect { publishStatus() } }
        jobs += scope.launch { pacerLoop() }
        jobs += scope.launch { lingerSweepLoop() }
        boardHolders?.let { source -> jobs += scope.launch { collectBoardHolders(source) } }
    }

    /**
     * Keeps [boardHolderIds] current. A failing source is caught, logged (`lora board holders failed`) and counted
     * (`loraBoardHoldersFailed`) rather than left to take the scope down or die unseen: the last set it gave stays in
     * force, and with none the re-probe only defers, so the plane would stop probing silent board holders while the
     * row reads Ready. The counter is what makes that visible (ADR 2026-10.rp3s).
     */
    private suspend fun collectBoardHolders(source: Flow<Set<String>>) {
        runRound(onFailure = { e ->
            metrics.onLoraBoardHoldersFailed()
            log("lora board holders failed: ${e.javaClass.simpleName}: ${e.message}")
        }) { source.collect { boardHolderIds = it } }
    }

    override fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        // Taken before [quiesce], and written on the surviving scope: this is the orderly half of what
        // [stateSaveLoop] covers on a kill, and the ledger it carries is what a restart must not be handed back
        // for free.
        val pending = snapshotState()
        quiesce()
        scope.launch { writeState(pending) }
        // Republish, or the row keeps serving the snapshot taken while the plane was up — a signal reading
        // and a board count for a plane that is no longer running.
        publishStatus()
    }

    /**
     * Drops the board and everything this session heard through it, leaving the plane in the state it has
     * before it is ever configured. Shared by [stop] and by [onConfig] losing its board, because those are
     * the same state and used not to be: unpairing only stopped the link, and the heard sets were left for
     * [lingerSweepLoop] to expire on its next pass.
     *
     * That is what makes parking the sweep safe. [recomputeReachable] is the only thing that ages
     * [lastHeardAt] / [boardsHeardAt] out, so a loop that idles while unconfigured would otherwise strand
     * whatever the last board heard in `reachable` — forever, since nothing else ever shrinks it. Clearing
     * here rather than waiting out `REACHABLE_LINGER_MS` is also the more honest answer: the route those
     * peers were reachable *through* is gone the moment the board is.
     */
    private fun quiesce() {
        configured.value = false
        link.stop()
        lastHeardAt.clear()
        heardPeers.clear()
        // Takes the signal readings with it: they belong to radios this session heard, and a stale one used
        // to survive a restart and read as the state of a link that had not been re-established yet.
        boardsHeardAt.clear()
        _reachable.value = emptySet()
        _health.value = TransportHealth.Unavailable
    }

    /**
     * Parks a timed loop until a board is configured. The plane's own gate, not the link's: a configured
     * board that is merely disconnected still has sweeping to do, and the loop already handles a down link on its
     * own.
     */
    private suspend fun awaitConfigured() {
        configured.first { it }
    }

    /**
     * Reads our own node id into [selfIdKnown] (everything that transmits waits on it), retrying a read that
     * throws: the production source is the identity's Keystore-backed key, which can refuse (ADR 2026-10.47rw),
     * and a refused read must cost the plane a delay, not leave the pacer parked for the life of the process.
     */
    private suspend fun resolveSelfId() {
        var wait = SELF_ID_RETRY_MIN_MS
        while (true) {
            val read = runCatching { selfId() }
            read.exceptionOrNull()?.let { e ->
                if (e is CancellationException) throw e
                log("lora self id unavailable (${e.javaClass.simpleName}); retrying in ${wait}ms")
            }
            val id = read.getOrNull()
            if (id != null) {
                selfIdKnown.value = id
                return
            }
            delay(wait)
            wait = (wait * 2).coerceAtMost(SELF_ID_RETRY_MAX_MS)
        }
    }

    /**
     * Parks until [restoreState] has applied the previous process's limiters. Everything that can put a
     * packet on the air waits here — the pacer and the re-probe — because the whole
     * point of persisting a ledger is that nothing spends against the empty one first.
     */
    private suspend fun awaitRestored() {
        restored.first { it }
    }

    // --- limiters that outlive the process (LoraPlaneState) ---

    /**
     * Reads the limiter state the last process left and applies it, then releases [awaitRestored] — always,
     * including on a store that is missing, slow or broken. A plane that stays silent because a preferences
     * read failed would be a far worse trade than the fresh window it is avoiding.
     */
    private suspend fun restoreState() {
        // Once per instance. A stop/start inside one process still holds the live ledger, and re-reading
        // would replace it with a blob up to one debounce older — or, if [stop]'s write has not landed yet,
        // with the one before that.
        if (restored.value) return
        try {
            withTimeoutOrNull(STATE_LOAD_TIMEOUT_MS) {
                runCatching { state.load() }
                    .onFailure { log("lora state load failed: ${it.message}") }
                    .getOrNull()
            }?.let(::applyState)
        } finally {
            restored.value = true
        }
    }

    private fun applyState(snapshot: LoraPlaneSnapshot) {
        val nowWall = wallClock()
        if (nowWall < snapshot.savedAtWall) {
            // The wall clock moved backwards — a manual change, or an NTP correction over a reboot. Every age
            // in the snapshot would come out negative, i.e. in the future, and would then never expire; one
            // fresh window is much the smaller error.
            log("lora state: wall clock is behind the snapshot, limiters start clean")
            return
        }
        val now = clock()
        val shift = WallShift(now, nowWall)
        pace.airtime.restore(
            snapshot.air.mapNotNull { booking ->
                // A retired bucket's booking is still air the window owes for (ADR 2026-10.rp3s).
                AirBucket.restoredFrom(booking.bucket)?.let { Booking(shift.toMono(booking.atWall), booking.ms, it) }
            },
            now,
        )
        autoReply.restore(snapshot.autoReplied.map { it.id to shift.toMono(it.atWall) })
        dmProbeAt.restore(snapshot.dmProbes.map { it.id to shift.toMono(it.atWall) }, now)
        reofferedAt.restore(snapshot.reoffered.map { it.id to shift.toMono(it.atWall) })
        reprobe.restore(
            snapshot.reprobes.map { LoraReprobe.Entry(it.id, shift.toMono(it.dueAtWall), it.gapMs, it.lastFrameId) },
        )
        val air = pace.airtime.snapshot(now)
        log(
            "lora state restored: air=${air.totalUsedMs}/${air.liveBudgetMs}ms " +
                "autoReplied=${snapshot.autoReplied.size} probes=${snapshot.dmProbes.size} " +
                "reoffered=${snapshot.reoffered.size} reprobes=${snapshot.reprobes.size}",
        )
    }

    private fun snapshotState(): LoraPlaneSnapshot {
        val now = clock()
        val nowWall = wallClock()
        val shift = WallShift(now, nowWall)
        return LoraPlaneSnapshot(
            savedAtWall = nowWall,
            air = pace.airtime.bookings(now).map { AirBooking(shift.toWall(it.atMs), it.ms, it.bucket.name) },
            // The DM auto-reply's once-a-day memory: the board replays its queue on reconnect, and a restart
            // that forgot who was answered would answer the same message again.
            autoReplied = autoReply.stamps().takeLast(STATE_AUTO_REPLY_CAP).map { SeenStamp(it.first, shift.toWall(it.second)) },
            // The reach gate's probe window (ADR 2026-10.a4pm): forgotten on launch, every restart would buy each
            // unheard recipient another DM's worth of air.
            dmProbes = dmProbeAt.stamps(now).takeLast(STATE_DM_PROBE_CAP).map { SeenStamp(it.first, shift.toWall(it.second)) },
            // And the re-offer's per-frame hour: forgotten, every restart would re-air the backlog on first hearing.
            reoffered = reofferedAt.stamps().takeLast(STATE_REOFFER_CAP).map { SeenStamp(it.first, shift.toWall(it.second)) },
            // And the re-probe's backoff (ADR 2026-10.rp3s): forgotten, every restart would re-probe each silent
            // recipient at the base gap again. Soonest first, so the cap keeps the schedules about to fire.
            reprobes =
                reprobe.stamps().take(STATE_REPROBE_CAP).map {
                    ReprobeStamp(it.recipientId, shift.toWall(it.dueAt), it.gapMs, it.lastFrameId)
                },
        )
    }

    /**
     * Persists the limiters a beat after something spends them. Conflated and debounced rather than written
     * per booking: the pacer's 3-second floor already bounds how often this can fire, and coalescing a
     * fragmented frame's packets into one write keeps a burst off the disk. The window it leaves is one
     * debounce wide — a process killed inside it loses the last packet or two of the ledger, which is the
     * same order of error as the governor's own estimate of what a packet costs.
     */
    private suspend fun stateSaveLoop() {
        while (scope.isActive) {
            stateWake.receive()
            delay(STATE_SAVE_DEBOUNCE_MS)
            writeState(snapshotState())
        }
    }

    private suspend fun writeState(snapshot: LoraPlaneSnapshot) {
        runCatching { state.save(snapshot) }.onFailure { log("lora state save failed: ${it.message}") }
    }

    override fun heal() {
        // No rescan to trigger — the board session self-heals with its own backoff. Nudge the pacer in case
        // a frame is queued behind a stale gap.
        wake.trySend(Unit)
    }

    /**
     * The peers BLE/NAN currently hold a live link to. This plane has no data path of its own, so the hint's usual
     * meaning (don't bring up a redundant sync) is moot; what it is read for here is the **link cover**: a DM-form
     * frame to a linked peer stays off the air ([coveredByLink]), is refused at send if the link came up while it
     * waited (`StaleAtSend.LINKED`), and a linked peer's first hearing re-offers nothing ([reofferTo]). The only
     * feed of [linkedPeers]. It used to move the gateway election and withdraw co-pocket hearings too; both left with
     * the bridge (ADR 2026-10.rp3s).
     */
    override fun suppressDataPath(peers: Set<String>) {
        linkedPeers = peers
    }

    override fun coveredByInternet(peers: Set<String>) {
        internetPeers = peers
        publishStatus()
    }

    /**
     * Sets the connected board up for Knit — the one-tap alternative to configuring a Meshtastic board by
     * hand. On [ProvisionResult.Provisioned] the settings VM persists the returned index so this plane binds
     * to it, along with the intervals the board had before so a restore can put them back. Requires a Ready
     * link.
     */
    override suspend fun provisionKnitChannel(
        mode: ProvisionMode,
        previous: BoardSettings?,
    ): ProvisionResult = link.provisionChannel(ProvisionSpec(KnitChannel.NAME, KnitChannel.PSK, mode, previous))

    // --- outbound (fast plane only) ---

    override fun fastFanout(wire: WireEnvelope) = fanout(wire, "fanout", FanoutHint.CONTENT)

    override fun longRangeFanout(
        wire: WireEnvelope,
        hint: FanoutHint,
    ) = fanout(wire, "far", hint)

    /**
     * The one fan-out: the composite's coordination-plane blast ([fastFanout] — the room) and its long-range sibling
     * ([longRangeFanout] — sealed DM-form chat, ADR 039) both land here, and [LoraFramePolicy] is the single gate
     * for what rides.
     *
     * The admit rule first (ADR 2026-10.rp3s): a frame somebody else wrote never rides — the composite re-calls this
     * on every relay, and [app.getknit.knit.mesh.InboundPipeline] re-fans every DM-form frame it delivers, both
     * plane-agnostic on purpose. Until our own id is read the rule cannot be asked, so the frame is judged on its kind
     * alone and carries its author ([RideGate.authorId]) for [staleReason] to ask once the pacer — which waits for the
     * id — takes it: an own room post typed during a Keystore backoff still rides, a relayed one queued then never does.
     */
    private fun fanout(
        wire: WireEnvelope,
        label: String,
        hint: FanoutHint,
    ) {
        if (!mayTransmit()) return // no board of our own
        val env = WireCodec.decodeEnvelope(wire.signed) ?: return
        val self = selfIdCached
        if (self != null && !LoraFramePolicy.airs(env.senderId, self)) return // somebody else's: this board is not a relay
        if (!LoraFramePolicy.eligible(env)) return
        if (LoraFramePolicy.isDmForm(env) && currentConfig?.dms != true) return // the user keeps DMs off this plane
        if (skipCovered(env)) return // a live link or a spool carries it; a ~1 kbps medium must not pay twice
        if (!LoraFramePolicy.isFresh(env, wallClock())) {
            metrics.onLoraSuppressed() // a custody re-serve of an old frame — custody's business, not a live plane's
            return
        }
        // The reach gate (ADR 2026-10.a4pm), before the dedup slot like the covers: a DM of ours to someone this
        // plane has not heard is held in custody, and their first hearing re-offers it — except one plain DM per
        // recipient per window, which goes out as the probe whose ✓✓ makes them heard. Every own DM puts its
        // recipient on the re-probe's schedule (ADR 2026-10.rp3s), so a probe nobody heard is not the last word.
        admitPastReach(reachGate(env, hint), wire, env, label, hint, preId = self == null)
    }

    /** Acts on the reach gate's verdict for a frame the fan-out admitted: hold it, or queue it as heard, exempt or the probe. */
    private fun admitPastReach(
        reach: Reach,
        wire: WireEnvelope,
        env: RelayEnvelope,
        label: String,
        hint: FanoutHint,
        preId: Boolean,
    ) {
        when (reach) {
            is Reach.Held -> {
                metrics.onLoraUnheardHeld()
                // Every held recipient, a board holder or not (ADR 2026-10.rp3s): the schedule costs no air, the
                // re-probe defers a recipient it may not probe, and a `loraNode` that arrives later (their profile,
                // or a pin restored after launch) is then probed on the next due round without waiting for a new DM.
                reprobe.onHeld(reach.recipientId, clock())
                stateWake.trySend(Unit)
            }

            Reach.Exempt -> {
                admitFanout(wire, env, label, hint, reachGated = false, probe = null, preId = preId)
            }

            Reach.Heard -> {
                // On the schedule too, though nothing is re-probed while they stay heard: a DM that flies to a heard
                // recipient and is lost has no first hearing to repair it until they linger out, and the bridge's
                // serve that used to is gone (ADR 2026-10.rp3s).
                env.recipientId?.let { reprobe.onHeld(it, clock()) }
                admitFanout(wire, env, label, hint, reachGated = true, probe = null, preId = preId)
            }

            is Reach.Probe -> {
                admitFanout(wire, env, label, hint, reachGated = false, probe = reach.claim, preId = preId)
            }
        }
    }

    /** What [reachGate] decided for a fanned frame. */
    private sealed interface Reach {
        /** Not a frame the gate asks about: not DM-form, our own frame other than a DM, or our id unknown ([reachGate]). */
        data object Exempt : Reach

        /** Our DM, and its addressee is heard: it rides, and is re-asked at send ([RideGate.reachGated]). */
        data object Heard : Reach

        /** Our DM to an unheard addressee, and no probe to spend on it: custody keeps it for the re-offer. */
        class Held(
            val recipientId: String,
        ) : Reach

        /** Our own DM to an unheard addressee, riding as the window's probe on [claim]. */
        class Probe(
            val claim: LoraDmProbe.Claim,
        ) : Reach
    }

    /**
     * The reach gate's verdict for a fanned frame (ADR 2026-10.a4pm), keyed on the originator's [hint] because
     * the plane cannot read a sealed frame. Only our own frames reach it (ADR 2026-10.rp3s):
     *
     * - our own DM ([FanoutHint.CHAT]) is gated, and may probe — it is the one frame that draws a ✓✓ back;
     * - any other frame of our own is **not** gated. Our ✓✓ ([FanoutHint.TICK]) exists only because its author's
     *   DM just reached us, so it is evidence-driven by construction, about one packet, and nothing would re-offer
     *   it if held (it has no message row) — held, the author would show one tick until radio or spool contact. Our
     *   sealed ctls ([FanoutHint.CONTENT]: a reaction, a session reset, a seed or key reply, the post-reset re-seal)
     *   are in the same position: no row to re-offer from, no ✓✓ to probe with;
     * - until our own id is known nothing is gated: ours cannot be told from a stranger's, and the send-time
     *   re-asks still refuse a frame addressed to us or written by somebody else once the pacer has the id (#141).
     *
     * Never a probe before the last process's stamps are back either: claimed against an empty window, a DM sent
     * in the launch's first moments would buy the recipient a second probe inside the one it spent.
     */
    private fun reachGate(
        env: RelayEnvelope,
        hint: FanoutHint,
    ): Reach {
        if (!LoraFramePolicy.isDmForm(env)) return Reach.Exempt
        // Not knowing who we are, we cannot tell our own ctl from a stranger's DM: let it through as it always went,
        // and the send-time re-asks (the pacer waits for the id) still refuse one that is not ours or is to us (#141).
        selfIdCached ?: return Reach.Exempt
        if (hint != FanoutHint.CHAT) return Reach.Exempt
        val to = unheardRecipient(env) ?: return Reach.Heard
        // A recipient with no board can be reached by nothing on the air: never spend a probe on them (ADR 2026-10.rp3s).
        if (!restored.value || !isBoardHolder(to)) return Reach.Held(to)
        return dmProbeAt.claim(to, clock())?.let(Reach::Probe) ?: Reach.Held(to)
    }

    /**
     * The fan-out past its gates: encode, the one-packet rule for a room post, the signature dedup, and the queue.
     * [probe] is the window the reach gate claimed for this frame, handed back if it never reaches the queue — a
     * frame that cannot be encoded, was deduplicated or was refused must not spend it.
     *
     * [preId] marks a frame fanned before our own id was read: it takes one of the [PRE_ID_ADMISSIONS] slots, and only
     * here — past the covers, the freshness check, the encode and the one-packet rule — so a covered, stale or oversize
     * frame never uses up the slot an own post typed during the backoff needs. A slot is given back if the dedup or
     * the queue refuses the frame.
     */
    private fun admitFanout(
        wire: WireEnvelope,
        env: RelayEnvelope,
        label: String,
        hint: FanoutHint,
        reachGated: Boolean,
        probe: LoraDmProbe.Claim?,
        preId: Boolean = false,
    ) {
        val parts = encodeOrNull(wire, "$label:${env.type}")
        if (parts == null) {
            probe?.let(dmProbeAt::release)
            return
        }
        // A room post rides only in one packet (ADR 2026-10.rp3s): the room is read by everyone the flood reaches, so
        // a post that needs two is two packets of a shared window spent on one line nobody is waiting for, while the
        // radios and the spool carry it anyway. Asked after encoding, because the 0x05 deflate makes the fit
        // depend on the words, not on a length. Its own counter, apart from `loraTooBig` (past three packets).
        if (LoraFramePolicy.isBroadcastRoomChat(env) && parts.size > 1) {
            metrics.onLoraRoomTooLong()
            log("lora room too long $label:${env.id} parts=${parts.size}")
            return
        }
        // Before the id, judged on kind alone — but only a few, refused like a non-own frame past the cap.
        if (preId && !reservePreId()) {
            metrics.onLoraPreIdRefused()
            return
        }
        val key = dedupKey(wire, env)
        if (!sigSeen.add(key)) {
            probe?.let(dmProbeAt::release)
            if (preId) preIdAdmitted.decrementAndGet()
            metrics.onLoraSuppressed() // already fanned over LoRa within the window
            return
        }
        val dmForm = LoraFramePolicy.isDmForm(env)
        val accepted =
            enqueue(
                parts,
                "$label:${env.type}",
                classOf(env, hint),
                supersedes = supersedeKeyFor(env),
                // All the gates above are answered about the mesh as it is *now*; the frame may not fly for a
                // whole window. Carried so [staleReason] can ask them again on the way out — the reach gate
                // only of a frame that passed it, never of the probe, which flew because the answer was no.
                gate =
                    RideGate(
                        recipientId = env.recipientId?.takeIf { dmForm },
                        freshUntil = freshUntilFor(env),
                        authorId = env.senderId,
                        reachGated = reachGated,
                    ),
                sigKey = key,
                probe = probe,
            )
        if (preId && !accepted) preIdAdmitted.decrementAndGet() // the queue refused it: nothing of the cap was spent
        probe?.let { settleProbe(it, accepted, "$label:${env.type}") }
    }

    /**
     * Takes one of the [PRE_ID_ADMISSIONS] slots, or says none is left. Check-and-take in one step: the room and the DM
     * fan-outs run on different coroutines, and an increment-then-check let a refused frame hold a slot nobody used.
     */
    private fun reservePreId(): Boolean {
        while (true) {
            val taken = preIdAdmitted.get()
            if (taken >= PRE_ID_ADMISSIONS) return false
            if (preIdAdmitted.compareAndSet(taken, taken + 1)) return true
        }
    }

    /** Books a probe that reached the queue; one the queue refused was given back by [abandon]. */
    private fun settleProbe(
        probe: LoraDmProbe.Claim,
        accepted: Boolean,
        label: String,
    ) {
        if (!accepted) return // refused: the pacer handed it back and [abandon] already released the window
        metrics.onLoraDmProbe()
        // A probe into an empty sky must not be the last word on the DMs behind it (ADR 2026-10.rp3s).
        reprobe.onHeld(probe.recipientId, clock())
        stateWake.trySend(Unit) // a limiter that outlives the process; persist what it just spent
        log("lora probe $label -> ${probe.recipientId}")
    }

    /**
     * The addressee of a DM-form [env] this plane has not heard within the linger, or null when [env] is not
     * DM-form or its addressee is in [reachable] (ADR 2026-10.a4pm). Author-keyed reach, as everywhere on this
     * plane: a recipient we hear only through a Meshtastic repeater, or an older build's gateway, is heard — that
     * airing is the route the frame would take back.
     */
    private fun unheardRecipient(env: RelayEnvelope): String? {
        if (!LoraFramePolicy.isDmForm(env)) return null
        val to = env.recipientId ?: return null
        return to.takeUnless { heard(it) }
    }

    private fun heard(nodeId: String): Boolean = _reachable.value.any { it.nodeId == nodeId }

    /**
     * The [OutboundFrame.supersedes] key for a fanned frame, or null for one nothing else might queue a copy of. A
     * DM-form frame gets its own [frameKey], so that every copy of it in the queue is one copy (ADR 2026-10.a4pm).
     */
    private fun supersedeKeyFor(env: RelayEnvelope): String? = if (LoraFramePolicy.isDmForm(env)) frameKey(env) else null

    /**
     * One key for every copy of one DM-form frame the plane might queue — the fan-out's, the first-hearing re-offer's
     * and the re-probe's — so the latter two can see a copy still waiting ([LoraPacePolicy.hasQueued]) and not queue
     * a second (ADR 2026-10.a4pm, 2026-10.rp3s). Room posts keep none: nothing re-offers them.
     */
    private fun frameKey(env: RelayEnvelope): String = "frame:${env.id}"

    /**
     * Refuses everything (ADR 2026-10.rp3s). The targeted path carried the room's ✓✓ to a far author (AckSync's
     * cleartext receipt and its sealed escalation) — a packet from every reader of every room post, and the one
     * thing on this plane nobody typed. A room tick now gets home by a link or the spool, or not at all; a DM's ✓✓
     * still rides, as our own DM-form tick on [longRangeFanout]. Overridden only to say so: the seam's default is the
     * same no-op.
     */
    override fun fastSend(
        wire: WireEnvelope,
        to: Peer,
    ) = Unit

    override suspend fun send(
        wire: WireEnvelope,
        to: Peer?,
    ) = Unit // LoRa is fast-plane only; the reliable flood never rides it

    override suspend fun sendFile(
        file: File,
        to: Peer,
        meta: app.getknit.knit.mesh.FileMeta,
    ): Boolean = false // no bulk transfers over LoRa

    /**
     * Compacts + fragments [wire] for the hop, or null when it can't ride (counted `loraTooBig`). Deliberately
     * separate from [enqueue] and called **before** the sig dedup: recording the sig first would burn the
     * frame's 10-minute dedup slot on a frame that never went out.
     */
    private fun encodeOrNull(
        wire: WireEnvelope,
        label: String,
    ): List<ByteArray>? {
        // `transcode = true` unconditionally is the ADR 060 flag-day: this plane has no per-peer capability (a
        // LoRa sighting carries caps 0), so an older build on the channel drops 0x05 as UNKNOWN_TAG — recorded in
        // the roadmap.
        val encoded =
            LoraFrameCodec.encodeBest(
                wire,
                fragSeq.getAndIncrement() and FRAG_ID_MASK,
                maxPayload,
                transcode = true,
                // ADR 2026-09.mhs5: the governor prices the packet against this board's own preset and firmware, so a
                // frame under the 2.8 signature cliff is grown past it and leaves cheaper than it arrived.
                cost = pace.airtime,
            )
        if (encoded == null) {
            metrics.onLoraTooBig()
            log("lora too-big $label")
            return null
        }
        if (encoded.transcodeRefused) metrics.onTranscodeFallback()
        if (encoded.transcoded) metrics.onLoraTranscoded()
        if (encoded.grewBy > 0) {
            metrics.onLoraPadded()
            log("lora pad $label +${encoded.grewBy}B past the signature cliff")
        }
        return encoded.parts
    }

    private fun enqueue(
        parts: List<ByteArray>,
        label: String,
        klass: FrameClass,
        bucket: AirBucket = AirBucket.LIVE,
        destination: Destination = Destination.Knit,
        supersedes: String? = null,
        gate: RideGate = RideGate(),
        to: UInt = MeshtasticProto.BROADCAST,
        sigKey: String? = null,
        probe: LoraDmProbe.Claim? = null,
    ): Boolean =
        enqueue(
            OutboundFrame(parts, label, klass, bucket, destination, to, supersedes, gate, probe = probe, sigKey = sigKey),
        )

    /**
     * Queues [frame] and says whether it is in the queue now. [LoraPacePolicy.Admission.DROPPED_OLDEST] is in:
     * it shed an older frame to make room (counted `loraDroppedQueue`, as ever), and the newcomer waits.
     */
    private fun enqueue(frame: OutboundFrame): Boolean {
        val admission = pace.enqueue(frame)
        if (admission != LoraPacePolicy.Admission.ACCEPTED) metrics.onLoraDroppedQueue()
        abandonShed() // the frame this one displaced, if it shed one
        if (pace.lastSuperseded > 0) log("lora ${frame.label} superseded ${pace.lastSuperseded} still queued")
        wake.trySend(Unit)
        return admission != LoraPacePolicy.Admission.REFUSED
    }

    /**
     * The pacing class of a frame: a DM outranks ambient room traffic, and a sealed tick the originator has vouched
     * for ([FanoutHint.TICK]) ranks below everything — feedback, not content (ADR 054).
     */
    private fun classOf(
        env: RelayEnvelope,
        hint: FanoutHint,
    ): FrameClass =
        when {
            LoraFramePolicy.isDmForm(env) -> if (hint == FanoutHint.TICK) FrameClass.TICK else FrameClass.DM
            else -> FrameClass.ROOM
        }

    /**
     * Re-offers our own unacked DMs to [peer] — pulled through [farFrames] (custody, via
     * [app.getknit.knit.mesh.FarPeerFrameSource]) — on first hearing it (ADR 039): this plane has no custody
     * sync, so a DM sent while the peer's board was off — or held by the reach gate because it was not heard
     * (ADR 2026-10.a4pm) — is otherwise lost to it until radio contact. Bounded by the source (our newest few
     * unacked DMs), the per-frame hour ([reofferedAt]) and the 45-min linger (a peer is "first heard" at most once
     * per window). Skipped for a peer another plane already carries — it gets custody's real digest sync there.
     * Relayed frames are no longer re-offered (ADR 2026-10.rp3s): the board airs only ours.
     */
    private suspend fun reofferTo(peer: Peer) {
        if (!mayTransmit()) return
        // Links, not sightings: the re-offer is skipped because custody syncs to this peer for real elsewhere, and
        // `ForwardSync`'s digest exchange runs off `neighbors` — a sighting never triggers it, so skipping on one
        // strands the very DMs this path exists to deliver.
        if (currentConfig?.dms != true || peer.nodeId in linkedPeers) return
        if (peer.nodeId in internetPeers) {
            metrics.onLoraSkippedInternet() // the spool re-serves to this peer for real, and for free
            return
        }
        farFrames(peer.nodeId).forEach { wire -> reofferOne(wire, peer.nodeId) }
    }

    /**
     * Enqueues one re-offered frame if it is our own DM-form chat addressed to [to], not re-offered within the hour
     * ([reofferedAt]) and not already waiting in the queue ([LoraPacePolicy.hasQueued]).
     *
     * Deliberately **not** gated by the fan-out's [sigSeen] (ADR 2026-10.rp3s, after ADR 2026-09.y8pu): that slot
     * records that we transmitted, and on a plane with no acks that is not evidence anyone heard — the probe that
     * went out into an empty sky a minute ago is exactly the frame this first hearing exists to repair, and the
     * bridge's digest-driven serve that used to repair it is gone.
     */
    private fun reofferOne(
        wire: WireEnvelope,
        to: String,
        labelPrefix: String = "reoffer",
    ): Boolean {
        val env = WireCodec.decodeEnvelope(wire.signed) ?: return false
        if (!LoraFramePolicy.isDmForm(env) || env.recipientId != to) return false
        if (!LoraFramePolicy.airs(env.senderId, selfIdCached)) return false // only ever ours (the source returns nothing else)
        // Once an hour per frame across first hearings (ADR 2026-10.a4pm): a recipient that hears us but that we
        // hear only now and then is "first heard" every linger cycle, and would have the same backlog re-aired
        // every 45 minutes.
        if (reofferedAt.contains(env.id)) return false
        // A copy already waits — the fan-out's, or a re-probe's: it is going anyway, and a second would air twice.
        if (pace.hasQueued(frameKey(env))) return false
        val label = "$labelPrefix:${env.id}"
        val parts = encodeOrNull(wire, label) ?: return false
        // DM class so a room post can never evict it, and LIVE: a live send the reach gate held back. [reofferTo]
        // asked this peer's link and — by running on a first hearing — its reach, so both are re-asked.
        val frame =
            OutboundFrame(
                parts,
                label,
                FrameClass.DM,
                AirBucket.LIVE,
                supersedes = frameKey(env),
                gate = RideGate(recipientId = to, authorId = env.senderId, reachGated = true),
                reofferId = env.id,
            )
        if (!enqueue(frame)) return false
        reofferedAt.add(env.id)
        stateWake.trySend(Unit)
        metrics.onLoraReoffered()
        return true
    }

    /**
     * The re-probe round (ADR 2026-10.rp3s, [LoraReprobe]): for each silent recipient whose schedule is due, re-air
     * the newest of our DMs to them that is still unacked, as a probe — so a recipient whose board was off when the
     * first probe flew, and who has nothing to say, is still asked again; their ✓✓ makes them heard, and the
     * first-hearing re-offer carries the rest. Run on the linger sweep, so only with a board configured, and only
     * once the last process's limiters and our own id are back.
     */
    private suspend fun reprobeDue() {
        if (!restored.value || !mayTransmit() || currentConfig?.dms != true) return
        val self = selfIdCached ?: return
        reprobe.due(clock()).forEach { to -> reprobeOne(to, self) }
    }

    private suspend fun reprobeOne(
        to: String,
        self: String,
    ) {
        val now = clock()
        // Covered: a link or the spool carries it for free.
        if (to in linkedPeers || to in internetPeers) {
            reprobe.defer(to, now)
            return
        }
        val held =
            farFrames(to)
                .mapNotNull { wire -> WireCodec.decodeEnvelope(wire.signed)?.let { wire to it } }
                .filter { (_, env) -> LoraFramePolicy.isDmForm(env) && env.recipientId == to && LoraFramePolicy.airs(env.senderId, self) }
                .sortedByDescending { (_, env) -> env.sentAt }
        if (held.isEmpty()) {
            reprobe.forget(to) // acked, or aged out of custody: nothing left to ask with
            stateWake.trySend(Unit)
            return
        }
        // The newest: the one most worth delivering, and the one whose ✓✓ is freshest evidence.
        val (wire, env) = held.first()
        val heldIds = held.mapTo(HashSet()) { it.second.id }
        when {
            // A copy still waits (the window is spent, or the board is between sessions): it is the probe already.
            pace.hasQueued(frameKey(env)) -> reprobe.defer(to, now)

            heard(to) -> repairHeard(wire, env, to, heldIds, now)

            // Not known to hold a board — no `loraNode` pinned, or the holder source has not reported yet: nothing on
            // the air is known to reach them, so this round spends nothing, and the schedule waits one gap without
            // doubling. Deferred, never forgotten (ADR 2026-10.rp3s): a restored schedule must outlive a slow first
            // emission, and a `loraNode` that arrives later is probed on the next due round without a new DM.
            !isBoardHolder(to) -> reprobe.defer(to, now)

            else -> probeAgain(to, wire, env, heldIds, now)
        }
    }

    /** [reprobeOne]'s probe: [env], the newest own unacked DM to a silent board holder [to], re-aired on `LIVE`. */
    private fun probeAgain(
        to: String,
        wire: WireEnvelope,
        env: RelayEnvelope,
        heldIds: Set<String>,
        now: Long,
    ) {
        // One probe per recipient per window, whichever path spends it: a fresh DM may have just probed.
        val claim = dmProbeAt.claim(to, now)
        val parts = claim?.let { encodeOrNull(wire, "reprobe:${env.id}") }
        if (claim == null || parts == null) {
            claim?.let(dmProbeAt::release)
            reprobe.defer(to, now)
            return
        }
        val frame =
            OutboundFrame(
                parts,
                "reprobe:${env.id}",
                FrameClass.DM,
                AirBucket.LIVE,
                supersedes = frameKey(env),
                // The covers, re-asked at send; never the reach gate, which this probe exists to answer.
                gate = RideGate(recipientId = to, authorId = env.senderId),
                probe = claim,
            )
        if (!enqueue(frame)) {
            reprobe.defer(to, now) // refused: the pacer handed it back and [abandon] released the window
            return
        }
        reprobe.onReprobed(to, env.id, heldIds, now)
        metrics.onLoraReprobed()
        stateWake.trySend(Unit)
        log("lora reprobe ${env.id} -> $to")
    }

    /**
     * The heard-loss repair (ADR 2026-10.rp3s): [to] is heard, so no probe is needed and no first hearing will come
     * while they keep talking — yet a DM that flew to them live and was lost has no other repair. Once the newest own
     * unacked DM to them is older than one gap of the schedule and was not re-offered within the hour, it is re-aired
     * exactly as a re-offer is (LIVE, `reofferedAt`, `hasQueued`, re-asked at send while they stay heard), and the gap
     * still doubles: a talking peer costs air only when a DM stays unacked for a whole gap.
     */
    private fun repairHeard(
        wire: WireEnvelope,
        env: RelayEnvelope,
        to: String,
        held: Set<String>,
        now: Long,
    ) {
        val gap = reprobe.gapOf(to) ?: DM_PROBE_WINDOW_MS
        val overdue = wallClock() - env.sentAt >= gap
        if (!overdue || !reofferOne(wire, to, labelPrefix = "repair")) {
            reprobe.defer(to, now)
            return
        }
        reprobe.onReprobed(to, env.id, held, now)
        stateWake.trySend(Unit)
        log("lora repair ${env.id} -> $to")
    }

    /**
     * Whether [nodeId] has a board this plane could reach: this plane hears them, or their pinned profile names one
     * (`PeerEntity.loraNode`, through [boardHolders]). Hearing is evidence enough — reach here is author-keyed and fed
     * only by fresh frames off their board ([heard]). With no source at all, as in a test, everyone counts. With a source
     * that has not reported yet the answer is no, and every caller reads that as "not now", never as "never": the
     * reach gate holds without a probe and the re-probe defers (ADR 2026-10.rp3s).
     */
    private fun isBoardHolder(nodeId: String): Boolean = heard(nodeId) || (boardHolderIds?.contains(nodeId) ?: (boardHolders == null))

    /**
     * Whether the bound slot really is the Knit channel. Since ADR 045 a board is either set up for Knit or a
     * stock Meshtastic node, so a bound slot carrying anything else means the setup was undone or never run —
     * and transmitting there would put Knit's cleartext frames onto somebody else's channel, most likely the
     * public one every stock radio listens to. Silence is the only safe reading of that.
     *
     * A board that reports no channel table at all is given the benefit of the doubt: going mute on firmware
     * whose table we failed to read would be a worse failure than the one this guards against.
     *
     * Takes [channels] rather than reading the link itself, because "the board is between sessions" is not an
     * answer to this question and must never be confused with one: a disconnected link used to fail here and
     * cost the frame, when the only correct handling of it is to put the frame back — see [sendFrame].
     */
    private fun boundSlotIsKnit(
        channels: List<ChannelInfo>,
        index: Int,
    ): Boolean = channels.isEmpty() || channels.any { it.index == index && it.name == KnitChannel.NAME }

    /**
     * Whether a DM-form frame is addressed to us, or to a peer a higher-preference plane holds a **live link**
     * to (ADR 054). Either way it already has a data path — the BLE/NAN flood, or it is ours and delivered — so
     * a ~1 kbps medium buys nothing by carrying it. Before this gate, texting a pocket-mate over Bluetooth
     * spent the whole airtime budget on DMs and ✓✓s that never needed the board (the composite's
     * [longRangeFanout] is unconditional), and a far peer then went without for the rest of the window.
     *
     * Links, never sightings: a sighting is not a data path, and refusing on one would take away a far peer's only
     * route (ADR 044's field amendment).
     */
    private fun coveredByLink(env: RelayEnvelope): Boolean {
        if (!LoraFramePolicy.isDmForm(env)) return false
        val to = env.recipientId ?: return false
        return to == selfIdCached || to in linkedPeers
    }

    /**
     * The second cover (ADR 2026-09.y5f3): a DM-form frame whose addressee a connected spool was recently a
     * path to. Counted apart from [coveredByLink] so the two reasons a frame stayed off the air can be told
     * apart in the field.
     */
    private fun coveredByInternet(env: RelayEnvelope): Boolean {
        if (!LoraFramePolicy.isDmForm(env)) return false
        val to = env.recipientId ?: return false
        return to in internetPeers
    }

    /** Both covers at once, each counted under its own name; true means the caller must not put [env] on air. */
    private fun skipCovered(env: RelayEnvelope): Boolean {
        if (coveredByLink(env)) {
            metrics.onLoraSkippedLinked()
            return true
        }
        if (coveredByInternet(env)) {
            metrics.onLoraSkippedInternet()
            return true
        }
        return false
    }

    /**
     * When [LoraFramePolicy.isFresh] would start refusing [env], or null for a type it exempts. The gate is a
     * deadline rather than an age, so the queue can carry it without carrying the envelope.
     */
    private fun freshUntilFor(env: RelayEnvelope): Long? =
        if (LoraFramePolicy.freshnessExempt(env)) null else env.sentAt + LoraFramePolicy.FRESH_MS

    /**
     * Why a frame the pacer just handed us should no longer go on the air, or null to send it.
     *
     * The enqueuing path asked these same questions, but the queue is a time-delayed commitment — the pacing
     * floor, a full board queue and a spent airtime share can hold a frame for a whole window — and the
     * answers move underneath it. Field-observed 2026-09-09: a phone that had been alone for hours met two
     * peers on Wi-Fi Aware, queued their receipts in the four seconds before the second link came up, and
     * then spent its entire 45-second allowance putting on the air what that link had already delivered. The
     * far side confirmed it, receiving one frame id over Wi-Fi Aware and the same one over LoRa ten minutes
     * later.
     *
     * Only [RideGate]'s own questions are asked, so this can never refuse a frame its path did not already
     * gate: the public post and the auto-reply carry none, and the probe never carries the reach gate.
     */
    private fun staleReason(frame: OutboundFrame): StaleAtSend? {
        val gate = frame.gate
        // The admit rule, for a frame fanned before our own id was read (ADR 2026-10.rp3s). The pacer waits for the
        // id, so this always has one to ask with.
        if (gate.authorId != null && !LoraFramePolicy.airs(gate.authorId, selfIdCached)) return StaleAtSend.NOT_OWN
        // Links, never sightings — the same reading as [coveredByLink], and for the same field reason.
        if (gate.recipientId != null && (gate.recipientId == selfIdCached || gate.recipientId in linkedPeers)) {
            return StaleAtSend.LINKED
        }
        if (gate.recipientId != null && gate.recipientId in internetPeers) return StaleAtSend.INTERNET
        // Only of a frame that passed the reach gate: the probe flew because its recipient was unheard.
        if (gate.reachGated && gate.recipientId != null && !heard(gate.recipientId)) return StaleAtSend.UNHEARD
        if (gate.freshUntil != null && wallClock() > gate.freshUntil) return StaleAtSend.STALE
        return null
    }

    /** Whether we may put anything on the air at all: a board is bound and the plane is on. */
    private fun mayTransmit(): Boolean {
        // No board bound, or the plane switched off — the state most installs are in, since the child joins the
        // composite on `BuildConfig.LORA_PLANE` alone while the setting defaults off. Asked here rather than in
        // each caller so a new transmit path cannot forget it: before this, [fanout] alone was unguarded and
        // every eligible broadcast frame still paid for the ADR 060 transcode and the ADR 2026-09.mhs5 pad
        // pricing, then sat in a queue the pacer can never drain (it takes only while the link is Ready) until
        // the cap shed it — a lab Pixel that has never been near a board logged `lora pad fanout:chat +42B past
        // the signature cliff`. Deliberately uncounted: `loraSuppressed` means this plane could have carried the
        // frame and declined, and an unconfigured plane would tick it on every frame, on most devices, forever.
        return currentConfig != null
    }

    /**
     * Puts this phone's user's post on the board's primary channel — [PublicChannelSink], the Meshtastic
     * room's outbound half. Called by [app.getknit.knit.mesh.MeshManager.sendPublicPost] for a post typed on
     * this phone and no other: the room posts through its own board, never through a pocket-mate's, and a
     * refusal is something the composer shows the user.
     *
     * The guards are all here rather than at the write, so each one can be counted and named. They are
     * deliberately **not** [boundSlotIsKnit]'s: that guard exists to keep Knit's cleartext frames off the
     * public channel by accident and reads an unknown channel table as "stay silent", while this is a
     * consented path *to* that channel. Same shape, opposite safe answer, so sharing code between them would
     * make one of the two wrong.
     */
    override suspend fun postToPublicChannel(body: String): PublicPostRefusal? {
        // The composer is gone with the room's row, so this is the net under a route that no longer draws one
        // (the debug SEND intent, a screen still on the back stack) — the same net `MeshManager.sendTyping`
        // keeps under the room it must not cue.
        if (currentConfig?.room == false) return refusePublicPost(PublicPostRefusal.ROOM_OFF)
        // A board on a dedicated RF slot (ADR 067) has no public channel under it: a post would go on the
        // air where only other pinned Knit boards could hear it, into a room this phone no longer draws.
        if (pace.airtime.dedicated()) return refusePublicPost(PublicPostRefusal.DEDICATED)
        val ready = link.state.value as? LinkState.Ready ?: return refusePublicPost(PublicPostRefusal.NOT_READY)
        // A board whose slot 0 is the Knit channel has no primary to post on — the debug-bridge shape the
        // receive half refuses to read for the same reason, decided off the same table.
        if (PublicChannelPolicy.isKnitPrimary(ready.channels)) return refusePublicPost(PublicPostRefusal.KNIT_ON_PRIMARY)
        val now = clock()
        val last = lastPublicPostAt.get()
        if (last != NEVER && now - last < PUBLIC_POST_FLOOR_MS) return refusePublicPost(PublicPostRefusal.TOO_SOON)
        // The same budget the composer applied — a draft typed while the facts still said 200 (a board that
        // came up signing after the draft began) is trimmed here rather than sent past the cliff unsigned.
        val message = PublicPostPolicy.onAirText(body, PublicPostPolicy.onAirBudget(pace.airtime.signing)).encodeToByteArray()
        if (message.size > maxPayload) return refusePublicPost(PublicPostRefusal.TOO_LARGE)
        // Asked before queueing rather than left to the pacer: a post the budget will not carry should be
        // counted as refused now, not sit in the queue looking sent until the window rolls over.
        // Priced at the text port's own cliff: a post at the 166-byte cap is signed air, not an unsigned packet.
        if (!pace.airtime.admits(
                AirBucket.PUBLIC,
                FrameClass.ROOM,
                listOf(message.size),
                now,
                signedUpTo = MeshtasticProto.maxSignedPayload(MeshtasticProto.PORT_TEXT_MESSAGE),
            )
        ) {
            return refusePublicPost(PublicPostRefusal.NO_AIR)
        }
        // Claimed at enqueue, not at write: the floor is about how often this phone *decides* to speak, and
        // charging it only once the pacer drains would let a burst queue up behind one 3 s gap.
        lastPublicPostAt.set(now)
        enqueue(listOf(message), "public", FrameClass.ROOM, AirBucket.PUBLIC, Destination.Public)
        return null
    }

    private fun refusePublicPost(reason: PublicPostRefusal): PublicPostRefusal {
        metrics.onPublicPostRefused(reason.name)
        log("lora public post refused: $reason")
        return reason
    }

    // --- the pacer ---

    private suspend fun pacerLoop() {
        // Nothing may be taken off the queue until the ledger says what the last process spent: this is the
        // one place all outbound air passes through, so it is the one that has to wait.
        awaitRestored()
        // ...nor until we know who we are. [selfId] resolves on a coroutine of its own, and until it has, the
        // enqueuing gates read every DM-form frame addressed to us as uncovered: a DM a pocket-mate handed us
        // over the link, re-fanned on relay, was queued for the air. [staleReason] asks the same question again
        // on the way out, so waiting here is what lets it answer with the id it was missing (#141: the lab's
        // linked-DM gate, under a chaos stall of that one launch, aired a DM and a ✓✓ addressed to itself).
        selfIdKnown.first { it != null }
        while (scope.isActive) {
            // Nothing leaves while the board is between sessions, so take nothing: a frame off the queue has
            // no owner but the pacer, and every take during an outage is one more round-trip through
            // [requeue]. Park on the link itself rather than [waitForNextSend]: with a frame queued its idle
            // tick fired every second for the whole outage (#67), and an absent board can be away for days.
            // Reading the state flow cannot miss the session-up the way a wake could.
            if (link.state.value !is LinkState.Ready) {
                link.state.first { it is LinkState.Ready }
                continue
            }
            val frame =
                pace.take(clock()).also {
                    // Reported after every take, admitted or not: one bucket can be spent while another still
                    // flows, and it is the *held* frame that has to be visible — nothing else counts it until
                    // the queue fills and sheds it as an ordinary drop. Only after a real take because
                    // [LoraPacePolicy.lastAirtimeHolds] clears on entry to `take` and nowhere else: reading it
                    // on a wake that took nothing re-reports the previous wake's holds.
                    pace.lastAirtimeHolds.forEach { hold -> metrics.onLoraAirtimeHeld(hold.name) }
                }
            if (frame == null) {
                waitForNextSend()
            } else {
                try {
                    sendFrame(frame)
                } finally {
                    pace.done(frame)
                }
            }
        }
    }

    /**
     * Parks the pacer until the queue could plausibly move: a wake (something enqueued, the board's queue
     * freed, [heal]), or the pacer's own due time. Only reached with the link Ready — [pacerLoop] parks on
     * the link state during an outage.
     *
     * The floor is load-bearing. A queue can be non-empty while nothing may
     * leave it — the board reports no headroom, or the hour's airtime is spent — and [LoraPacePolicy.take]
     * says only "not now", not "not until". A due time already in the past then yields a zero wait, and
     * `withTimeoutOrNull(0)` returns without suspending: the loop spins a core flat until the condition
     * clears, and with no suspension point in it, [stop] cannot even cancel it.
     */
    private suspend fun waitForNextSend() {
        if (pace.pending == 0) {
            wake.receive()
        } else {
            val due = pace.nextDueAt() - clock()
            withTimeoutOrNull(if (due > 0) due else IDLE_TICK_MS) { wake.receive() }
        }
    }

    private suspend fun sendFrame(frame: OutboundFrame) {
        // Two destinations, two guards, and deliberately no shared code between them (§5 of work item #37):
        // `boundSlotIsKnit` stops Knit's own frames reaching the public channel by accident, while the public
        // post's guards were all applied at `postToPublicChannel`, where a refusal could still be counted and
        // reported. All that is left here is which channel and which portnum.
        if (frame.destination != Destination.Knit) {
            sendPublicFrame(frame)
            return
        }
        // The board went away between the take and here — a GATT drop, or the reconnect backoff still running.
        // The frame is owed either way, so it goes back on the queue: nothing else holds a copy, and the
        // pacer would otherwise eat one every `minGapMs` for the whole outage. Field-observed on a board whose
        // link flapped five times in twenty minutes: a 93-second backoff destroyed six queued frames, and the
        // log blamed the channel setup because this read a missing link as the wrong slot.
        val ready = link.state.value as? LinkState.Ready
        if (ready == null) {
            requeue(frame)
            return
        }
        // Asked only of a frame that has not yet put a fragment on the air: [OutboundFrame.remaining] exists
        // to finish a part-sent frame, and abandoning one would strand the fragments the board already holds.
        if (frame.sentParts == 0) {
            staleReason(frame)?.let { reason ->
                metrics.onLoraStaleAtSend(reason.name)
                log("lora stale at send ${frame.label}: ${reason.name.lowercase()}")
                abandon(frame)
                return
            }
        }
        val ch = currentConfig?.channelIndex
        if (ch == null) {
            abandon(frame)
            return
        }
        if (!boundSlotIsKnit(ready.channels, ch)) {
            metrics.onLoraSuppressed()
            log("lora send skipped: slot $ch is not the Knit channel — set this board up")
            abandon(frame)
            return
        }
        // Resume where the board left off. A frame refused part-way is requeued whole, and re-sending the
        // fragments it already holds would both duplicate them on the air and book their airtime again — the
        // ledger only grows on a retry, so that inflates the hourly budget until it refuses the whole plane.
        for (message in frame.remaining) {
            if (!sendMessage(message, ch, frame)) return
        }
        metrics.onLoraSent()
        if (frame.fragmented) metrics.onLoraFragSent()
        if (frame.klass == FrameClass.DM || frame.klass == FrameClass.TICK) metrics.onLoraDmSent() // both DM-form
        log("lora tx ${frame.label} parts=${frame.messages.size}")
    }

    /**
     * Writes one public post on the foreign mesh's primary channel: index 0, `TEXT_MESSAGE_APP`, cleartext —
     * or, for a [Destination.Reply], the same write addressed to one node, which the firmware then encrypts
     * to that node's key rather than to the channel.
     *
     * Never fragmented — [PublicPostPolicy.MAX_ON_AIR_BYTES] is a fifth of what one packet carries — so there
     * is no resume cursor to honour and one `sendMessage` is the whole frame.
     */
    private suspend fun sendPublicFrame(frame: OutboundFrame) {
        val message = frame.messages.firstOrNull() ?: return
        val reply = frame.destination == Destination.Reply
        if (!sendMessage(message, PublicChannelPolicy.PRIMARY_INDEX, frame, MeshtasticProto.PORT_TEXT_MESSAGE, to = frame.to)) {
            if (reply) metrics.onAutoReplyRefused(AutoReplyRefusal.NAK.name) else metrics.onPublicPostRefused(PublicPostRefusal.NAK.name)
            return
        }
        if (reply) {
            metrics.onAutoReplySent()
            log("lora tx auto-reply to ${meshNodeLabel(frame.to.toLong())} ${message.size}B")
        } else {
            metrics.onPublicPostSent()
            log("lora tx public ${message.size}B")
        }
    }

    /** Sends one fragment; false ends the frame (a NAK, error, or no headroom). */
    private suspend fun sendMessage(
        message: ByteArray,
        channelIndex: Int,
        frame: OutboundFrame,
        portnum: Int = MeshtasticProto.PORT_PRIVATE_APP,
        to: UInt = MeshtasticProto.BROADCAST,
    ): Boolean =
        when (val result = link.send(message, channelIndex, portnum = portnum, hopLimit = HOP_LIMIT, to = to)) {
            is SendResult.Queued -> {
                pace.onQueueStatus(result.queue.free)
                pace.airtime.record(frame.bucket, message.size, clock(), signedUpTo = MeshtasticProto.maxSignedPayload(portnum))
                stateWake.trySend(Unit)
                frame.onPartSent()
                // The ledger just moved, and a send is the only thing that spends it — so republish here
                // rather than leave the chat's saturation notice and the radio screen's percentage waiting
                // on the 60 s linger sweep. Cheap: the pacer's 3 s floor bounds how often this can run, and
                // `LoraStatusRepository` reduces the snapshot to a threshold before any UI sees it.
                publishStatus()
                true
            }

            is SendResult.Nak -> {
                metrics.onLoraNak(result.reason.name)
                log("lora nak id=${result.id} reason=${result.reason} label=${frame.label}")
                pace.onNak(result.reason, clock())
                abandon(frame) // a no-op once a fragment has flown
                false
            }

            SendResult.Busy -> {
                requeue(frame)
                false
            }

            else -> {
                log("lora tx ${frame.label} gave up: $result")
                abandon(frame)
                false
            }
        }

    private fun requeue(frame: OutboundFrame) {
        // Put back, never re-admitted: a requeue runs no supersede, or an older snapshot put back here would delete
        // the newer one queued while it was out (ADR 2026-09.xdm2).
        if (pace.requeue(frame) != LoraPacePolicy.Admission.ACCEPTED) metrics.onLoraDroppedQueue()
        abandonShed()
    }

    /**
     * Hands back what a frame that never flew was holding (ADR 2026-10.a4pm): its fan-out dedup slot, which records
     * a transmission that did not happen and would turn the next fan-out of the same bytes away; a probe's window,
     * since a probe that never flew told nobody anything; a re-offer's hour. A no-op once a fragment has gone —
     * that frame did fly, in part. `SeenSet.reopen` gives one more copy per id per window, so a frame abandoned
     * twice inside ten minutes keeps its slot the second time. Called from every path that drops an unsent frame:
     * the send-time re-ask, a NAK or give-up on the first fragment, a missing config or a slot that is not Knit's,
     * and the pacer's shed and oversize evictions ([abandonShed]).
     */
    private fun abandon(frame: OutboundFrame) {
        if (frame.sentParts > 0) return
        frame.sigKey?.let(sigSeen::reopen)
        frame.probe?.let(dmProbeAt::release)
        frame.reofferId?.let(reofferedAt::reopen)
    }

    /** [abandon]s whatever the pacer dropped unsent since the last look. */
    private fun abandonShed() {
        pace.drainShed().forEach(::abandon)
    }

    private fun onNak(outcome: PacketOutcome) {
        metrics.onLoraNak(outcome.reason.name)
        log("lora nak id=${outcome.id} reason=${outcome.reason} (late)")
        pace.onNak(outcome.reason, clock())
    }

    // --- inbound ---

    private fun onLoraPacket(packet: ReceivedPacket) {
        if (packet.payload.isEmpty()) return
        val bound = currentConfig?.channelIndex
        // Slot 0 is the Meshtastic room's channel, mirrored as the user configured it. The room reads only
        // chat, so only `TEXT_MESSAGE_APP` turns off here; everything else on the slot falls through to the
        // Knit path, which keeps its own portnum filter. Decided off the portnum rather than off the bound
        // index, because the index defaults to 0 on a board that never ran Knit's setup and such a board
        // still has a primary to mirror — the one shape with nothing to mirror (Knit itself at slot 0, the
        // debug bridge's lab binding) is refused inside, off the channel table.
        // A text addressed to the board rather than to the channel is a Meshtastic user's DM — which nothing
        // on this phone reads (ADR 2026-09.emd7 marks the board unmonitored to say exactly that), so the one
        // thing to do with it is tell the sender. Taken ahead of the room's branch on the portnum and the
        // address alone, never the channel index: on 2.5+ firmware a DM is PKI-encrypted and reported on
        // index 0 whatever slot the sender used, and an older board reports the slot it decoded on.
        if (packet.portnum == MeshtasticProto.PORT_TEXT_MESSAGE && packet.to != MeshtasticProto.BROADCAST) {
            onDirectMessage(packet)
            return
        }
        if (packet.channelIndex == PublicChannelPolicy.PRIMARY_INDEX && packet.portnum == MeshtasticProto.PORT_TEXT_MESSAGE) {
            // The room is switched off, or hidden because the board is on a dedicated RF slot (ADR 067):
            // this packet has nowhere to go. Dropped here rather than later so the whole cost of a post the
            // user asked not to see — the judge, the signature verify, the contact lookup, the moderator, the
            // row and its notification — is never paid. Counted by reason, so `…debug.LORA` can still tell
            // "the channel is quiet" from "the channel is busy and Knit is ignoring it".
            val closed =
                when {
                    currentConfig?.room == false -> MESH_POST_ROOM_OFF

                    // A post on a pinned board's slot 0 is another pinned board's, never a public one, and
                    // would land in a thread with no row and ring a notification for it.
                    pace.airtime.dedicated() -> MESH_POST_DEDICATED

                    else -> null
                }
            if (closed != null) metrics.onMeshPostRefused(closed) else onPrimaryPacket(packet)
            return
        }
        if (packet.portnum != MeshtasticProto.PORT_PRIVATE_APP) return
        // Outbound is pinned to the bound channel; inbound was not, so a board carrying a second channel with
        // Knit traffic on it used to ingest both. Ignore anything off the channel this plane is bound to.
        if (bound != null && packet.channelIndex != bound) return
        noteBoard(packet)
        // An older build's gateway still airs its OFFERs (tag 0x10, burned in `FastFrameCodec`'s registry). Dropped
        // here under a counter of their own, so `UNKNOWN_TAG` keeps meaning a tag nobody ever assigned
        // (ADR 2026-10.rp3s). The radio is still counted above: a board in range is a board in range.
        if (packet.payload[0] == RETIRED_CTL_TAG) {
            metrics.onLoraRetiredCtl()
            return
        }
        onFramePacket(packet)
    }

    /**
     * One chat packet off the board's **primary** channel — what the Meshtastic room mirrors. [PublicChannelPolicy]
     * decides whether it is a post; a refusal is counted and dropped, and an accepted post is delivered into
     * this phone's room and nowhere else. Every board reads its own slot 0: there is no election and no
     * minting, because nothing about a heard post leaves the phone.
     *
     * One rule that is easy to get wrong here: **[noteBoard] is not called.** `boardsHeard` means "radios
     * that have sent a *Knit* frame", which is what makes "heard nobody" the ordinary state of a solo user
     * and is why the preset-mismatch notice cannot be gated on evidence. Counting stock neighbours here would
     * quietly change all of that.
     */
    private fun onPrimaryPacket(packet: ReceivedPacket) {
        metrics.onMeshPostHeard()
        val ready = link.state.value as? LinkState.Ready ?: return
        val verdict =
            PublicChannelPolicy.judge(
                packet = packet,
                channels = ready.channels,
                radio = ready.radio,
                name = link.nodes.value[packet.from]?.longName,
                // Our own board's transmissions are never read back in: the outbound half puts this phone's
                // posts on this channel, and they are already in the room as our own rows.
                ownNode = ready.board.myNodeNum,
            )
        when (verdict) {
            is PublicChannelPolicy.Verdict.Refused -> {
                metrics.onMeshPostRefused(verdict.reason.name)
            }

            is PublicChannelPolicy.Verdict.Post -> {
                metrics.onMeshPostIngested(verdict.post.viaMqtt)
                log(
                    "lora public post from ${meshNodeLabel(packet.from.toLong())} " +
                        "${verdict.post.body.length}ch viaMqtt=${verdict.post.viaMqtt} " +
                        "signed=${verdict.post.signature != null} boardVerified=${verdict.post.boardVerified}",
                )
                scope.launch { onPublicPost(verdict.post) }
            }
        }
    }

    /**
     * One text addressed to the board — a Meshtastic user's DM, answered once with [DmAutoReplyPolicy.TEXT].
     *
     * The guards are all here rather than at the write, so each one can be counted and named, the same shape
     * as [postToPublicChannel] and for the same reason. Two are the room's own: a board on a **dedicated**
     * RF slot (ADR 067) has no Meshtastic neighbours to answer, and a board whose slot 0 is the Knit channel
     * has no primary to answer on. One is this path's alone: the board must be **set up for Knit**
     * ([boundSlotIsKnit]), because the setup's confirmation sheet is where the user agreed to the board
     * saying it is unmonitored (ADR 2026-09.emd7) — a paired stock board that never ran it stays as quiet
     * as a stock board. Then the policy's own caps, and last the room's airtime share, asked before queueing
     * so a reply the window will not carry is counted now rather than left to look sent.
     *
     * The reply rides the shared pacer as a [Destination.Reply] frame and is priced as a signed post: a
     * unicast is PKI-encrypted rather than signed, whose overhead is smaller, so the ledger over-charges it
     * by a few dozen bytes — the conservative error, and not worth a second price.
     */
    private fun onDirectMessage(packet: ReceivedPacket) {
        metrics.onAutoReplyHeard()
        val refusal = autoReplyRefusal(packet) ?: return
        metrics.onAutoReplyRefused(refusal)
        log("lora dm from ${meshNodeLabel(packet.from.toLong())}: auto-reply refused $refusal")
    }

    /** The gate that stopped the reply to [packet], by name — or null once the reply is queued. */
    private fun autoReplyRefusal(packet: ReceivedPacket): String? {
        val ready = link.state.value as? LinkState.Ready ?: return AutoReplyRefusal.NOT_READY.name
        if (pace.airtime.dedicated()) return AutoReplyRefusal.DEDICATED.name
        if (PublicChannelPolicy.isKnitPrimary(ready.channels)) return AutoReplyRefusal.KNIT_ON_PRIMARY.name
        // `boundSlotIsKnit` reads an empty table as "don't suppress"; this is a consented transmission, so the
        // unknown reads the other way, like the setup's own guard.
        val bound = currentConfig?.channelIndex
        if (bound == null || ready.channels.isEmpty() || !boundSlotIsKnit(ready.channels, bound)) return AutoReplyRefusal.NOT_SET_UP.name
        val reply =
            when (val verdict = autoReply.judge(packet, ownNode = ready.board.myNodeNum)) {
                is DmAutoReplyPolicy.Verdict.Refused -> return verdict.reason.name
                is DmAutoReplyPolicy.Verdict.Reply -> verdict
            }
        val message = reply.text.encodeToByteArray()
        val signedUpTo = MeshtasticProto.maxSignedPayload(MeshtasticProto.PORT_TEXT_MESSAGE)
        val held =
            when {
                message.size > maxPayload -> {
                    AutoReplyRefusal.TOO_LARGE
                }

                !pace.airtime.admits(AirBucket.PUBLIC, FrameClass.ROOM, listOf(message.size), clock(), signedUpTo = signedUpTo) -> {
                    AutoReplyRefusal.NO_AIR
                }

                else -> {
                    null
                }
            }
        if (held != null) return held.name
        log("lora dm from ${meshNodeLabel(packet.from.toLong())}: auto-reply queued")
        enqueue(listOf(message), "autoreply", FrameClass.ROOM, AirBucket.PUBLIC, Destination.Reply, to = reply.to)
        stateWake.trySend(Unit) // the sender is stamped now, not at the write: persist it with the ledger
        return null
    }

    /** One inbound mesh frame off the air: reassemble, decode, dedup, and hand it to the router. */
    private fun onFramePacket(packet: ReceivedPacket) {
        val fragmented = packet.payload[0] == FastFrameCodec.TAG_FRAG
        val compact = reassemble(packet) ?: return
        val wire = FastFrameCodec.decodeCompact(compact)
        if (wire == null) {
            val reason = if (compact[0] == FastFrameCodec.TAG_TRANSCODED) FastPathDrop.TRANSCODE_FAILED else FastPathDrop.DECODE_FAILED
            metrics.onFastDropped(reason)
            return
        }
        val env = WireCodec.decodeEnvelope(wire.signed) ?: return
        if (env.senderId == selfIdCached) return // our own frame echoed back over the mesh
        // No `sigSeen` add (ADR 2026-10.rp3s): it stopped the composite's relay re-fan bouncing this frame back over
        // LoRa, and the admit rule refuses every frame that is not ours now.
        // Presence, and ONLY presence, is gated on the frame's age: a backfill, a re-offer or a re-fan of
        // something a spool just handed an older build's gateway says where a *frame* has been, not where its
        // author is. Everything below runs either way — this path must never become a propagation black hole.
        // And never off our own board, heard back through a repeater: that is our own airing, nobody's reach.
        if (isPresenceEvidence(env, wallClock()) && packet.from != (link.state.value as? LinkState.Ready)?.board?.myNodeNum) {
            noteReachable(Peer(env.senderId))
        }
        metrics.onLoraReceived()
        if (fragmented) metrics.onLoraReassembled()
        if (LoraFramePolicy.isDmForm(env)) metrics.onLoraDmReceived()
        _inbound.tryEmit(InboundFrame(wire, env, fromNodeId = env.senderId))
        log("lora rx ${env.type} id=${env.id} from ${env.senderId}")
    }

    /** Returns the complete frame for [packet]: itself if a whole `0x03`/`0x05` frame, else reassembled from `0x04` parts. */
    private fun reassemble(packet: ReceivedPacket): ByteArray? =
        when (packet.payload[0]) {
            FastFrameCodec.TAG_COMPACT, FastFrameCodec.TAG_TRANSCODED -> {
                packet.payload
            }

            FastFrameCodec.TAG_FRAG -> {
                val frag = FastFrameCodec.parseFragment(packet.payload) ?: return null
                reassembler.accept(packet.from, frag)?.takeIf { it.isNotEmpty() && FastFrameCodec.isFrameTag(it[0]) }
            }

            else -> {
                metrics.onFastDropped(FastPathDrop.UNKNOWN_TAG)
                null
            }
        }

    /**
     * Records that the radio [from] is on air. Separate from [noteReachable] because they answer different
     * questions: this one counts **radios**, that one counts the **people** whose frames reached us — and the
     * two diverged the moment a gateway relayed somebody else's frame, which an older build still does. Reporting
     * the second as the first read as a phantom radio in the field.
     */
    private fun noteBoard(packet: ReceivedPacket) {
        val from = packet.from
        if (from == 0u) return // undecoded `from`; counting it would invent a radio
        if (from == (link.state.value as? LinkState.Ready)?.board?.myNodeNum) return // our own board's echo
        val heard = RxQuality(packet.rxSnr, packet.rxRssi, clock())
        boardsHeardAt.compute(from) { _, previous -> previous?.refreshedBy(heard) ?: heard }
        publishStatus()
    }

    private fun noteReachable(peer: Peer) {
        val now = clock()
        val firstHeard = lastHeardAt.put(peer.nodeId, now) == null
        heardPeers[peer.nodeId] = peer
        recomputeReachable(now)
        if (firstHeard) {
            // Heard: the re-offer below is what delivers the held DMs, and a re-probe, if one is still needed after
            // it, starts again from the base gap (ADR 2026-10.rp3s).
            reprobe.onHeard(peer.nodeId, now)
            stateWake.trySend(Unit)
            scope.launch {
                // A beat for the router first: the frame that made this a first hearing is very often the ✓✓ for the
                // probe, and the re-offer no longer asks the dedup slot that probe took (ADR 2026-10.rp3s). Read before
                // that ✓✓ is applied, custody still calls the probe unacked and it would air a second time.
                delay(REOFFER_SETTLE_MS)
                reofferTo(peer)
            }
        }
    }

    private fun recomputeReachable(now: Long) {
        lastHeardAt.entries.removeAll { now - it.value > REACHABLE_LINGER_MS }
        boardsHeardAt.entries.removeAll { now - it.value.atMs > REACHABLE_LINGER_MS }
        heardPeers.keys.retainAll(lastHeardAt.keys)
        _reachable.value = heardPeers.values.toSet()
        publishStatus()
    }

    private suspend fun lingerSweepLoop() {
        while (scope.isActive) {
            // [quiesce] has already emptied everything this would sweep, so an unconfigured pass could only
            // re-run the election over an empty heard set — once a minute, on most installs, forever.
            awaitConfigured()
            delay(lingerSweepMs)
            // One round's failure — the custody read behind the re-probe — costs that round alone (#206).
            runRound({ log("lora sweep failed: ${it.javaClass.simpleName}") }) {
                recomputeReachable(clock())
                reprobeDue()
            }
        }
    }

    // --- config + state ---

    private fun onConfig(cfg: LoraConfig?) {
        currentConfig = cfg
        if (cfg == null) {
            quiesce()
        } else {
            configured.value = true
            link.start(cfg.address)
        }
        publishStatus()
    }

    private fun onLinkState(state: LinkState) {
        _health.value =
            when (state) {
                is LinkState.Ready -> TransportHealth.Healthy
                LinkState.Connecting, LinkState.Bonding, is LinkState.Handshaking, is LinkState.Disconnected -> TransportHealth.Degraded
                LinkState.Idle, LinkState.Unavailable, is LinkState.NeedsPairing, is LinkState.StaleBond -> TransportHealth.Unavailable
            }
        if (state is LinkState.Ready) {
            maxPayload = (state.mtu - TORADIO_OVERHEAD).coerceIn(LoraFrameCodec.MIN_PAYLOAD, MeshtasticProto.MAX_PAYLOAD)
            val evicted = pace.evictOversize(maxPayload)
            abandonShed()
            if (evicted > 0) {
                repeat(evicted) { metrics.onLoraTooBig() }
                log("lora ready: evicted $evicted queued frame(s) chunked past the negotiated cap $maxPayload")
            }
            metrics.onLoraSessionUp()
            // The pacer parks itself while the link is down, so the queue it kept across the outage only
            // moves again if the session-up wakes it.
            wake.trySend(Unit)
            pace.airtime.onRadioConfig(state.radio)
            // 2.8 signs the broadcasts it sends for us, which is airtime the budget has to know about.
            pace.airtime.onFirmware(state.board.firmwareVersion, state.board.hasXeddsa)
            log(
                "lora ready board=${state.board.myNodeNum} mtu=${state.mtu} maxPayload=$maxPayload " +
                    "radio=${state.radio?.region}/${state.radio?.modemPreset} " +
                    "fw=${state.board.firmwareVersion} signing=${pace.airtime.signing}",
            )
            // The key is advertised only while the board signs: a key that never signs verifies nothing,
            // and a pre-2.8 user's profile should not grow by it.
            val signingKey =
                state.board.owner
                    ?.publicKey
                    ?.takeIf { pace.airtime.signing }
            scope.launch { onBoardBound(BoardBinding(state.board.myNodeNum, signingKey)) }
        }
        publishStatus()
    }

    /**
     * Rebuilds [status] from what the plane holds now. Serialised on [statusLock]: it is called from a dozen
     * coroutines on `Dispatchers.Default`, and an unserialised read-then-write let a pacer that read Ready land its
     * snapshot over the link collector's Disconnected — harmless to the settings row, which only shows the latest,
     * but the field log follows the sequence (`LoraFieldLog`) and read it as a down, a phantom up and a second down
     * (ADR 2026-10.a239's 2026-10-18 amendment). Under the lock each write's reads are newer than the last write's.
     * Taken outermost: no caller holds the pacer's or the ledger's monitor, and those are taken inside it
     * ([LoraPacePolicy.pending], [LoraAirtime.snapshot]), never the other way round. No JVM test pins it: the window
     * is two non-suspending statements, and `FakeMeshtasticLink` has no seam to park a thread between them, so a stress
     * test would pass on the unlocked code too. Keep the `link.state` read inside the lock.
     */
    private fun publishStatus() =
        synchronized(statusLock) {
            val linkState = link.state.value
            val board = (linkState as? LinkState.Ready)?.board
            // The freshest reading among the radios still within the linger — the row's "last heard", now bounded
            // to radios this plane actually talks to. Empty once they all age out, which is the honest answer.
            val lastRx = boardsHeardAt.values.maxByOrNull { it.atMs }
            _status.value =
                LoraStatus(
                    state = linkState,
                    boardAddress = currentConfig?.address,
                    boardNodeNum = board?.myNodeNum,
                    lastSnr = lastRx?.snr,
                    lastRssi = lastRx?.rssi,
                    queueFree = link.queue.value?.free,
                    queued = pace.pending,
                    heard = _reachable.value.size,
                    boardsHeard = boardsHeardAt.size,
                    battery = link.battery.value,
                    boardAir = link.boardAir.value,
                    airtime = pace.airtime.snapshot(clock()),
                    internetCovered = internetPeers.size,
                )
        }

    /**
     * The window's dedup key. Keyed on the signature rather than the frame id because a re-seal keeps its
     * id (`resealAndFlood`) and carries a fresh signature that must not be suppressed by a ten-minute-old
     * entry. An **unsigned** frame (the v3 live-link tick, ADR 059) has no signature to key on — every one
     * would collapse to the empty key and the first would silence the rest for the window — so it keys on
     * its id, which is exact for that form: AckSync seals a tick once and re-sends it verbatim, never
     * re-sealing under the same id. A distinct namespace so the two kinds of key can never collide.
     */
    private fun dedupKey(
        wire: WireEnvelope,
        env: RelayEnvelope,
    ): String = if (wire.sig.isEmpty()) "u:${env.id}" else sigKey(wire)

    private fun sigKey(wire: WireEnvelope): String {
        val n = minOf(SIG_KEY_BYTES, wire.sig.size)
        return buildString(n * 2) { for (i in 0 until n) append("%02x".format(wire.sig[i])) }
    }

    /** Tuning constants; not private so the JVM tests can assert against the numbers rather than restate them. */
    companion object {
        /**
         * Hops a Knit packet is born with. It has to be **stated**: omitting `hop_limit` was documented as
         * riding the node's configured default and does not — 2.8 leaves the field at zero, so every frame
         * reached the air already spent and nothing has ever repeated one (measured 2026-09-04; both the
         * evidence and the airtime it costs are in `context/lora-bridge.md`). Three is the Meshtastic
         * default and what every stock node around us sends, which is the point: ADR 045 buys its reach by
         * being ordinary traffic to the neighbours it borrows hops from.
         *
         * Reading the board's own `lora.hop_limit` instead is the obvious refinement and is deliberately not
         * done here: the field is absent on a board that never set it, which decodes as 0 — the exact value
         * that caused this, and a silent regression the moment a board reports it.
         */
        const val HOP_LIMIT = 3

        /**
         * The shortest gap between two posts this phone puts on the foreign public channel (§5 of work
         * item #37).
         *
         * The binding limit in practice, ahead of [AirBucket.PUBLIC]'s share, and that ordering is the point:
         * a refusal a user can predict ("wait a moment") beats one that depends on what the rest of the plane
         * happened to be doing. It also bounds the worst case a bridge can inflict on a shared community band
         * — a whole pocket of Knit users is still one voice every 30 s, not one each.
         */
        const val PUBLIC_POST_FLOOR_MS = 30_000L

        const val INBOUND_BUFFER = 64
        const val SIG_TTL_MS = 10 * 60_000L // = SeenSet.DEFAULT_TTL_MS
        const val SIG_KEY_BYTES = 8
        const val FRAG_CAP = 16
        const val FRAG_TIMEOUT_MS = 60_000L // LoRa parts are seconds apart; the NAN 5 s default would drop them
        const val FRAG_ID_MASK = 0xFFFF
        const val REACHABLE_LINGER_MS = 45 * 60_000L

        /**
         * How often a DM may go out as a probe to a recipient this plane has not heard (ADR 2026-10.a4pm): one
         * airtime window, so an absent recipient costs at most one DM's air per window.
         */
        const val DM_PROBE_WINDOW_MS = 15 * 60_000L
        const val LINGER_SWEEP_MS = 60_000L

        const val NEVER = Long.MIN_VALUE

        // Limiters that outlive the process (LoraPlaneState).

        /**
         * How long the plane waits for its persisted limiters before transmitting anyway. Generous against a
         * cold DataStore read and still far inside the board's own BLE handshake, so in practice nothing ever
         * waits on it — it exists so that a store which never answers costs one window rather than the plane.
         */
        const val STATE_LOAD_TIMEOUT_MS = 5_000L

        /** How long a spend waits for its neighbours before the snapshot is written. */
        const val STATE_SAVE_DEBOUNCE_MS = 2_000L

        /** The most answered DM senders carried across a restart — [DmAutoReplyPolicy.MAX_SENDERS], all of them. */
        const val STATE_AUTO_REPLY_CAP = DmAutoReplyPolicy.MAX_SENDERS

        /**
         * The most probe stamps carried across a restart, newest kept. One per recipient a DM went to unheard
         * inside the last [DM_PROBE_WINDOW_MS] — a handful — so this is slack, not a limit.
         */
        const val STATE_DM_PROBE_CAP = 64

        /**
         * How long one frame stays re-offered (ADR 2026-10.a4pm): an hour, longer than the 45-min linger, so the
         * same backlog is not re-aired on every first hearing of a peer heard only now and then.
         */
        const val REOFFER_MEMORY_MS = 60 * 60_000L

        /**
         * How long a first hearing waits before it re-offers: long enough for the router to apply the ✓✓s the hearing
         * frame carried, so a probe it acknowledges is not re-aired; short against the 45-min linger that bounds the
         * re-offer anyway.
         */
        const val REOFFER_SETTLE_MS = 2_000L

        /**
         * How many frames the fan-out admits before our own id is read (ADR 2026-10.rp3s): enough for what a user types
         * through a Keystore backoff, too few for relayed re-fans to fill the 32-frame queue and shed it.
         */
        const val PRE_ID_ADMISSIONS = 4

        /** The most re-offered frame ids carried across a restart, newest kept — a few sightings' worth. */
        const val STATE_REOFFER_CAP = 64

        /** The most re-probe schedules carried across a restart, soonest kept — one per silent recipient. */
        const val STATE_REPROBE_CAP = 64

        /**
         * The first byte of an older build's gossip OFFER (`LoraCtl`, ADR 044), burned in `FastFrameCodec`'s registry
         * and dropped on receipt under `loraRetiredCtl` (ADR 2026-10.rp3s).
         */
        const val RETIRED_CTL_TAG: Byte = 0x10

        /**
         * A floor on the pacer loop's wait, so a zero-length wait can never become a busy loop. It computes its wait
         * from a transmit point that can already be in the past while transmitting is still impossible (the board
         * down, its queue full, the window's airtime spent).
         */
        const val IDLE_TICK_MS = 1_000L

        /** [resolveSelfId]'s backoff after a read that threw: doubling from the first to the second. */
        const val SELF_ID_RETRY_MIN_MS = 1_000L
        const val SELF_ID_RETRY_MAX_MS = 60_000L

        /** The ATT write header: one opcode byte and a two-byte handle, so a write carries `mtu - 3` bytes. */
        const val ATT_HEADER_BYTES = 3

        /**
         * Bytes a ToRadio{packet} adds around the Data.payload on the BLE write — the ATT header plus the
         * measured protobuf framing ([MeshtasticProto.PACKET_OVERHEAD]) — so `mtu - this` is the largest
         * payload that still fits one ATT write. Was a hand-set 33 with 6 B of unaccounted slack; at the
         * MTU-255 ESP32 boards that slack was the difference between a one-packet v3 tick and two (ADR 059).
         */
        val TORADIO_OVERHEAD: Int = ATT_HEADER_BYTES + MeshtasticProto.PACKET_OVERHEAD

        /** The smallest ATT MTU a real board negotiates (the ESP32 line's 255); a failed negotiation is caught lower, at `MIN_MTU`. */
        const val MTU_FLOOR = 255

        /**
         * The payload cap in force before a board has reported its MTU: what an [MTU_FLOOR] board takes, never
         * more than [MeshtasticProto.MAX_PAYLOAD]. Ready replaces it with the negotiated figure.
         */
        val PRE_READY_PAYLOAD: Int = minOf(MeshtasticProto.MAX_PAYLOAD, MTU_FLOOR - TORADIO_OVERHEAD)
    }
}

/**
 * Why a slot-0 chat packet was dropped before [PublicChannelPolicy] ever saw it: the user switched the
 * Meshtastic room off. A refusal name rather than a counter of its own, beside `BLOCKED_CONTACT`, because it
 * belongs in the same "heard on the primary and not delivered" tally the rest of the reasons share.
 */
internal const val MESH_POST_ROOM_OFF = "ROOM_OFF"

/**
 * Its sibling for a board pinned to a dedicated RF slot (ADR 067): the room is hidden there, so a slot-0 chat
 * packet has nowhere to go and is dropped at the same point.
 */
internal const val MESH_POST_DEDICATED = "DEDICATED"

/**
 * What a board reports about itself when its session comes up, for the profile to advertise: its node number
 * and — only on firmware that signs — the Curve25519 key it signs under (base64, off its own `NodeInfo`).
 */
internal data class BoardBinding(
    val nodeNum: UInt,
    val signingKey: String?,
)
