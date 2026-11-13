package app.getknit.knit.mesh.lora

import app.getknit.knit.mesh.FastPathDrop
import app.getknit.knit.mesh.InboundFrame
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.MeshPost
import app.getknit.knit.mesh.MeshTransport
import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.PublicChannelSink
import app.getknit.knit.mesh.PublicPostRefusal
import app.getknit.knit.mesh.ReceivedFile
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.mesh.TransportKind
import app.getknit.knit.mesh.isPresenceEvidence
import app.getknit.knit.mesh.link.FastFrameCodec
import app.getknit.knit.mesh.link.FragReassembler
import app.getknit.knit.mesh.protocol.Protocol
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * A [MeshTransport] that carries this phone's user's own small frames over LoRa via a Meshtastic board
 * ([MeshtasticLink]): their **Nearby-room** posts and their DMs, at kilometre range. It is a fast-plane-ONLY
 * child of [app.getknit.knit.mesh.CompositeMeshTransport] (added last, lowest preference), and since ADR
 * 2026-10.rp3s an **endpoint**, not a mesh plane: the board airs only frames this phone's user wrote
 * ([LoraFramePolicy.airs]) — no pocket bridge, no profile beacon, no re-fan of anybody else's frame, no backfill or
 * OFFER gossip, and no room ✓✓.
 *
 * - [neighbors] is always empty, so the reliable flood, custody digest sync, key requests, blob pulls and
 *   the `watchNeighbors` hooks never touch a ~1 kbps link — [send]/[sendFile]/[sendDigest] are no-ops, and so are
 *   [fastSend].
 * - [fastFanout] carries an own room post: it decodes the envelope (never re-encoding it — `sig`/`signed` pass through
 *   [FastFrameCodec] byte-exact), applies [LoraFramePolicy], compacts it via [LoraFrameCodec] and airs it only in one
 *   packet, paced ([LoraPacePolicy]) onto the board as a broadcast.
 * - a DM, its ✓✓ and a DM reaction ride as a **compact pair-sealed unicast** (ADR 2026-10.x9fa): `MeshManager` offers
 *   the plaintext beside the signed copy ([offer], [LoraDirectSink]), this plane seals it ([LoraDirectCrypto]) into
 *   one packet addressed to the recipient's board with `want_ack`, and the destination's ACK, the firmware's retries
 *   and its give-up replace the reach gate, the probe and the re-offer. What a peer's board said is kept per peer
 *   ([LoraDirectPolicy]); the re-probe ([LoraReprobe]) keys on our DMs still without their ✓✓, whatever it said.
 * - inbound broadcast packets are decoded/reassembled and injected into [inbound] exactly like the Wi-Fi Aware fast
 *   plane's `emitFastWire`, so the router's dedup/verify/custody/relay all run unchanged — and a frame heard over
 *   LoRa still floods into the pocket over BLE/NAN, which costs no air. A compact DM addressed to our board is opened
 *   and handed to [LoraDirectInbox] instead: it is never a mesh frame.
 * - [shortRange] is false: a LoRa sighting doesn't imply proximity, so siblings ignore its `reachable` set
 *   and nothing above the composite may read it as *nearby* (`MeshController.neighbors` is the short-range
 *   set for exactly this reason). [reachable] here is keyed on the frame **author**. Only a frame that passes
 *   [app.getknit.knit.mesh.isPresenceEvidence] counts, or a fresh compact DM that authenticated — never a board's
 *   ACK, which says its radio is on, not that its person is there.
 *
 * Two of its limiters outlive the process ([LoraPlaneState]): the auto-reply memory and the re-probe's backoff are
 * read back at [start] and written behind a debounce, because a restart would otherwise answer the same Meshtastic DM
 * twice and re-probe every silent board at the base gap. The airtime ledger does not (ADR 2026-10.ug4a): one rolling
 * allowance, started empty — the firmware enforces the regional duty cycle itself, and the re-probe is the only thing
 * here that airs without a user action. What each peer's board said is not persisted either: after a restart it is
 * unknown, and the first DM's ack decides.
 *
 * [clock] is monotonic (pacing, dedup, linger, deadlines); [wallClock] is the epoch clock a frame's `sentAt` is
 * stamped in, read by the freshness gate, the hearing hook's `rx_time` and by the persisted limiters (a monotonic
 * stamp cannot survive a reboot). Pure/Android-free — the only `android.bluetooth.*` sits behind the
 * [MeshtasticLink]/[MeshtasticGattDialer] seam.
 *
 * The board-neutral plane core (ADR 2026-11.gsqu, Stage 1): it reads the board through [BoardLink], a board kind's
 * numbers through [BoardTraits] and everything only the kind does through [BoardKindHooks] — the inbound dispatch, the
 * slot-0 room, the auto-reply and its persisted memory, the send-time slot and AEAD guards, how the board signs, the
 * setup, teaching the board a key and the answer to a destination without ours. Meshtastic is the only kind, so the
 * constructor still takes the [MeshtasticLink], and it is read in one place: where the core builds the board link and
 * the hooks ([MeshtasticBoardLink], [MeshtasticHooks]), which make the kind's own state objects themselves.
 *
 * Large by suppression, as [MeshtasticSession] is: this is the plane's single owner. The pacer loop, the
 * linger sweep and the published status all read and write one set of mutable fields under one scope, so
 * splitting it would mean a second owner of that state.
 */
@Suppress("TooManyFunctions", "LongParameterList", "LargeClass")
internal class LoraMeshTransport(
    private val selfId: suspend () -> String,
    link: MeshtasticLink,
    private val config: Flow<LoraConfig?>,
    /** Seals and opens a compact DM (ADR 2026-10.x9fa); [LoraDirectCrypto.None] seals nothing. */
    private val crypto: LoraDirectCrypto = LoraDirectCrypto.None,
    /** Where an opened compact DM is delivered (`InboundPipeline.deliverLoraDirect`). */
    private val inbox: LoraDirectInbox = LoraDirectInbox.None,
    /** Our own DMs still without their ✓✓, as plaintext — what the resend after a hearing and the re-probe re-air. */
    private val source: LoraDirectSource = LoraDirectSource.None,
    /**
     * The pinned contacts whose profile names a board, by node id, with their capability ([LoraBoardHolder]): the only
     * peers a compact DM goes to, and the boards the hearing hook listens for. Null means no source, and no DM goes.
     */
    private val boardMap: LoraBoardMap? = null,
    /** Delivers a post heard on the board's primary channel into the Meshtastic room — [app.getknit.knit.mesh.MeshPostSink]. */
    onPublicPost: suspend (MeshPost) -> Unit = {},
    /**
     * The bound board's node number and key ([BoardBinding]), reported each time its session comes up. What the
     * profile advertises so a contact's phone can line a heard post up with this one and verify it
     * (`SettingsStore.loraBoard`); persisted by the caller, so a link drop does not unsay it and only a
     * different board changes it.
     */
    onBoardBound: suspend (BoardBinding) -> Unit = {},
    /**
     * One of our compact DMs ([DirectKind.MESSAGE], by its row id) reached its recipient's **radio**: the destination's
     * own board acked it — its phone may be away, and the board's to-phone queue can still drop it — so the chat shows
     * "reached their LoRa radio" until the ✓✓ (ADR 040's 2026-10-31 amendment). [late] is an ack that came after the send
     * ended, replayed from our board's to-phone queue, which may be minutes old: the caller shows no time for it. An ack
     * whose proof our board found forged ([AckProof.INVALID]) never comes here; an implicit ack, a receipt's or a
     * reaction's never does either.
     *
     * Called on the one `board.outcomes` collector, between moving the marks and releasing what was parked behind the
     * send: it must return at once, take no lock that waits, launch nothing and never call back into this plane — a
     * stall there drops the answers queued behind it, and a re-entry would reorder the parked offers.
     */
    private val onRadioReached: (frameId: String, late: Boolean) -> Unit = { _, _ -> },
    private val scope: CoroutineScope,
    private val metrics: MeshMetrics,
    /**
     * Where this plane's persisted limiters go when the process dies — the auto-reply memory and the re-probe's backoff
     * ([LoraPlaneState]) — so a restart does not answer a sender the last process answered or re-probe every silent board
     * at the base gap.
     */
    private val state: LoraPlaneState = LoraPlaneState.None,
    private val clock: () -> Long,
    private val wallClock: () -> Long = System::currentTimeMillis,
    /**
     * When this process started, on [wallClock]: an own room post written before it is not one the user just typed, and
     * the fan-out never re-airs it (ADR 2026-10.ug4a). The process's own start in production (`di/MeshModule`); this
     * instance's construction by default.
     */
    private val processStartWall: Long = wallClock(),
    private val log: (String) -> Unit = {},
    // A board on a dedicated slot lifts the politeness ceiling, the setup's whole point (ADR 067's amendment). The board
    // kind sets the gap of this default only; an injected pace keeps its own (ADR 2026-11.gsqu).
    private val pace: LoraPacePolicy =
        LoraPacePolicy(minGapMs = BoardTraits.MESHTASTIC.minGapMs, airtime = LoraAirtime(dedicatedUnlocksDuty = true)),
    /**
     * How often [lingerSweepLoop] re-reads the heard set. The field value always; the mesh-in-a-box lab shortens it,
     * because a scenario that moves the calendar past the linger needs the sweep to notice inside an await.
     */
    private val lingerSweepMs: Long = LINGER_SWEEP_MS,
) : MeshTransport,
    LoraPlaneStatus,
    PublicChannelSink,
    LoraDirectSink {
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

    /** The board kind's numbers (ADR 2026-11.gsqu, Stage 1a): Meshtastic's, the one kind there is. */
    private val traits: BoardTraits = BoardTraits.MESHTASTIC

    /** The board as the core reads it: [link] seen through the neutral seam, with no buffering of its own (Stage 1b). */
    private val board: BoardLink = MeshtasticBoardLink(link)

    /**
     * The plane core as the kind's hooks reach it (Stage 1c): read lazily, so the fields it names are set by the time a
     * hook calls in.
     */
    private val core =
        object : PlaneCore {
            override val airtime: LoraAirtime get() = pace.airtime
            override val config: LoraConfig? get() = currentConfig
            override val maxPayload: Int get() = this@LoraMeshTransport.maxPayload
            override val restored: Boolean get() = this@LoraMeshTransport.restored.value

            override fun enqueue(
                parts: List<ByteArray>,
                label: String,
                ceiling: Ceiling,
                destination: Destination,
                to: BoardAddr?,
            ): Boolean = this@LoraMeshTransport.enqueue(parts, label, ceiling, destination, to = to)

            override fun limitersMoved() {
                stateWake.trySend(Unit)
            }

            override fun onBoardKeyHeard(from: BoardAddr) = this@LoraMeshTransport.onBoardKeyHeard(from)
        }

    /** What only the board kind does (Stage 1b and 1c): made here, per instance, with the kind's own state inside. */
    private val hooks: BoardKindHooks = MeshtasticHooks(link, board, core, scope, metrics, clock, log, onPublicPost, onBoardBound)

    /** A compact DM's budget on this kind ([LoraDirectSink.budgetFor]): fixed, so any coroutine reads it. */
    private val directBudget: LoraDirectBudget = traits.directBudget()

    private val _status = MutableStateFlow(LoraStatus())

    /** Serialises [publishStatus]; outermost, never taken under the pacer's or the ledger's monitor. */
    private val statusLock = Any()

    /** A snapshot for the LoRa settings row + the `…debug.LORA` bridge; derived, never routing-affecting. */
    override val status = _status.asStateFlow()

    /**
     * The fan-out's dedup, keyed on the first 8 bytes of the Ed25519 sig ([dedupKey]): one airing of our own room post
     * for as long as it may air — until its freshness deadline on [wallClock] ([LoraFanoutSeen], #239) — so neither the
     * composite fanning it twice nor a re-serve between the router's ten-minute window and [LoraFramePolicy.FRESH_MS]
     * airs it twice. The fan-out's alone since ADR 2026-10.rp3s. A compact DM never consults it: one copy per frame id
     * is [LoraPacePolicy.hasQueued] and the in-flight table (ADR 2026-10.x9fa).
     */
    private val sigSeen = LoraFanoutSeen()

    /**
     * The backed-off re-probe per peer with own DMs still without their ✓✓ (ADR 2026-10.rp3s, re-keyed by ADR
     * 2026-10.x9fa): what asks a quiet board's phone again, whatever its board said. Persisted with the other limiters
     * and, like the kind's auto-reply memory, not cleared by [quiesce] — see [LoraReprobe].
     */
    private val reprobe = LoraReprobe()

    /**
     * What each peer's board said to a compact DM, and the unicasts still waiting on an answer (ADR 2026-10.x9fa). In
     * memory only: after a restart every board is unknown and the first DM's ack decides. Cleared by [quiesce].
     */
    private val direct = LoraDirectPolicy(maxFlights = traits.maxFlights)

    /** The re-probe's schedules, soonest first: for the JVM tests, which read what a round decided. */
    internal fun reprobeSchedule(): List<LoraReprobe.Entry> = reprobe.stamps()

    /** Poked when a direct send starts waiting, so the deadline loop re-reads the soonest deadline. */
    private val flightWake = Channel<Unit>(Channel.CONFLATED)
    private val fragSeq = AtomicInteger()

    // Keyed on the sending board, every undecoded `from` in one bucket as before ([NoSender]).
    private val reassembler = FragReassembler<Any>(now = clock, capacity = FRAG_CAP, timeoutMs = FRAG_TIMEOUT_MS)

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
    private val boardsHeardAt = ConcurrentHashMap<BoardAddr, RxQuality>()
    private val heardPeers = ConcurrentHashMap<String, Peer>()

    // Peers a higher-preference plane holds a **live link** to right now (BLE/NAN, via suppressDataPath). The link
    // cover reads this — a compact DM's offer, its send-time LINKED re-ask and the re-probe's defer — and never a
    // sighting: a peer merely heard advertising is being carried by nothing.
    @Volatile
    private var linkedPeers: Set<String> = emptySet()

    /** [linkedPeers], for the `mesh/lab` harness to wait on the composite's hand-off (`LabNode.awaitBoardSawLinks`). */
    internal val linkIds: Set<String> get() = linkedPeers

    // Peers a connected spool was recently a path to (`coveredByInternet`, ADR 2026-09.y5f3), as ScopeSync last
    // published them: its single writer. A cover for compact DMs only — the spool carries the signed copy for free.
    // Guarded by [coverLock], with [liftedPeers] and [covered].
    private var internetPeers: Set<String> = emptySet()

    // Peers in [internetPeers] whose live compact frame reached us over the air since (#294, ADR 2026-09.y5f3's
    // 2026-11-04 amendment): their sender aired it because, by its own reckoning, no relay joins us, so the relay's
    // word for them is stale. A lift lasts until the relay itself drops the peer. In memory only, never persisted.
    private var liftedPeers: Set<String> = emptySet()

    /** The cover every reader asks: [internetPeers] less [liftedPeers]. Written under [coverLock], read without it. */
    @Volatile
    private var covered: Set<String> = emptySet()

    /** Guards the two cover sets and the lapse computed off them; never held across [publishStatus] or a launch. */
    private val coverLock = Any()

    @Volatile
    private var currentConfig: LoraConfig? = null

    /** The last [boardMap], or null until it has reported: a peer missing from it is not sent to, for now. */
    @Volatile
    private var boardHolders: Map<String, LoraBoardHolder>? = null

    /** [boardHolders]' entry for [peerId], for the `mesh/lab` harness to wait on the board map's hand-off (as [linkIds]). */
    internal fun boardHolderOf(peerId: String): LoraBoardHolder? = boardHolders?.get(peerId)

    /** [boardHolders] the other way round, for the hearing hook: which pinned peer a board on the air belongs to. */
    @Volatile
    private var peerByBoard: Map<BoardAddr, String> = emptyMap()

    /**
     * Frames fanned before our own id was read, admitted so far (ADR 2026-10.rp3s): capped at [PRE_ID_ADMISSIONS], so a
     * Keystore stall cannot fill the queue with relayed re-fans that would shed an own room post typed meanwhile.
     * Charged only for a frame that reaches the queue ([admitFanout], [reservePreId]).
     */
    private val preIdAdmitted = AtomicInteger()

    // Largest Data.payload a single board packet may carry, sized DOWN from the negotiated BLE MTU on
    // session-up so a full fragment's ToRadio write fits one ATT op (ESP32 boards commonly cap at MTU 255).
    @Volatile
    // The pre-Ready floor, not the protocol maximum: frames fanned out while the board is still connecting are
    // chunked with this and drained the moment the session is Ready, so it has to fit the smallest MTU a real
    // board negotiates. It used to be MAX_PAYLOAD — every one of those frames then came back TOO_LARGE.
    private var maxPayload: Int = traits.floorPayload

    // Our own node id, resolved off [selfId] (a suspend read) by [start]. Until it lands, every gate that asks
    // "is this frame addressed to us?" answers no, so [pacerLoop] takes nothing before it does (#141).
    private val selfIdKnown = MutableStateFlow<String?>(null)
    private val selfIdCached: String? get() = selfIdKnown.value

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
     * Flipped once the persisted limiter state has been applied — or has failed to load, which must cost the plane a
     * re-probe schedule rather than its voice. What can put a packet on the air waits on it ([awaitRestored]): the
     * re-probe must not run on a schedule the last process had already backed off, and the auto-reply's once-a-day
     * memory must be back before a sender the last process answered is answered again. [LoraPlaneState.None] flips it on the first pass.
     */
    private val restored = MutableStateFlow(false)

    /** Poked whenever something spends a limiter; [stateSaveLoop] debounces the write behind it. */
    private val stateWake = Channel<Unit>(Channel.CONFLATED)
    private val jobs = mutableListOf<Job>()

    override fun start() {
        // First, and before anything may transmit: the re-probe's backoff and the auto-reply's memory the last process
        // earned.
        jobs += scope.launch { restoreState() }
        jobs += scope.launch { stateSaveLoop() }
        jobs += scope.launch { resolveSelfId() }
        jobs += scope.launch { config.collect(::onConfig) }
        jobs += scope.launch { board.state.collect(::onBoardState) }
        jobs += scope.launch { board.packets.collect(::onLoraPacket) }
        // Nudge the pacer too: a board that was full is the one state [waitForNextSend] can only wait out on
        // its floor, and this is the event that ends it.
        jobs +=
            scope.launch {
                board.headroom.collect { free ->
                    pace.onQueueStatus(free)
                    if (free > 0) wake.trySend(Unit)
                }
            }
        jobs += scope.launch { board.outcomes.collect(::onOutcome) }
        jobs += scope.launch { board.battery.collect { publishStatus() } }
        jobs += scope.launch { board.boardAir.collect(::onBoardAir) }
        jobs += scope.launch { pacerLoop() }
        jobs += scope.launch { lingerSweepLoop() }
        jobs += scope.launch { deadlineLoop() }
        // Only while a board is configured: an unconfigured plane reads no contacts, and losing the board stops it.
        boardMap?.let { source -> jobs += scope.launch { configured.collectLatest { on -> if (on) collectBoardMap(source) } } }
    }

    /**
     * A reading off the board's live self-telemetry (ADR 2026-10.ug4a), about once a minute: its channel utilisation
     * feeds the shared-band brake. Timed on this plane's [clock] when it lands — the session stamps each reading only so
     * a repeat of the same figures is still a new value. A null (the link re-handshaking or stopped) changes nothing:
     * the last reading ages out of the brake on its own, and [quiesce] forgets it with the board. Every reading wakes the
     * pacer, since a new one is the only event that lifts the brake before its staleness does. The edge in and out of
     * the brake is logged (`lora band busy` / `lora band clear`).
     */
    private fun onBoardAir(air: BoardAir?) {
        if (air != null) {
            val now = clock()
            val was = pace.braked(now)
            pace.onBandReading(air.channelUtilPercent, now)
            val braked = pace.braked(now)
            if (braked != was) log("lora band ${if (braked) "busy" else "clear"} channelUtil=${air.channelUtilPercent}")
            wake.trySend(Unit)
        }
        publishStatus()
    }

    /**
     * Keeps [boardHolders] current. A failing source is caught, logged (`lora board holders failed`) and counted
     * (`loraBoardHoldersFailed`) rather than left to take the scope down or die unseen, and asked again every
     * [BOARD_MAP_RETRY_MS]: the last map it gave stays in force meanwhile, and with none no DM goes and the re-probe only
     * defers (ADR 2026-10.rp3s, carried into ADR 2026-10.x9fa). A source that completes is asked again on the same
     * cadence, which costs one read. Runs only while a board is configured; [quiesce] stops it with the board.
     */
    private suspend fun collectBoardMap(source: LoraBoardMap) {
        while (true) {
            runRound(onFailure = { e ->
                metrics.onLoraBoardHoldersFailed()
                log("lora board holders failed: ${e.javaClass.simpleName}: ${e.message}")
            }) { source.collect(::onBoardMap) }
            delay(BOARD_MAP_RETRY_MS)
        }
    }

    /**
     * A new board map. A peer whose entry changed — a new profile version, another board — has the refusal it may hold
     * lifted ([LoraDirectPolicy.onProfileChanged]): the profile it refused under is gone. Two peers naming one board
     * cannot both be in a map built with the newest-claim rule; should they be, the hearing hook takes the first.
     */
    private fun onBoardMap(map: Map<String, LoraBoardHolder>) {
        val previous = boardHolders
        map.forEach { (peer, holder) -> if (previous?.get(peer) != holder) direct.onProfileChanged(peer) }
        boardHolders = map
        peerByBoard = buildMap { map.forEach { (peer, holder) -> putIfAbsent(holder.addr, peer) } }
        publishStatus()
    }

    override fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        // Taken before [quiesce], and written on the surviving scope: this is the orderly half of what
        // [stateSaveLoop] covers on a kill — the re-probe's backoff and the auto-reply's memory.
        endFlights()
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
        board.stop()
        lastHeardAt.clear()
        heardPeers.clear()
        // Takes the signal readings with it: they belong to radios this session heard, and a stale one used
        // to survive a restart and read as the state of a link that had not been re-established yet.
        boardsHeardAt.clear()
        // The band reading was about the air around the board that is gone (ADR 2026-10.ug4a).
        pace.forgetBand()
        _reachable.value = emptySet()
        _health.value = TransportHealth.Unavailable
        // The board is gone and with it the route every mark was about (ADR 2026-10.x9fa).
        endFlights()
        direct.clearPeers()
        // A lift was this board's hearing; the next board has heard nothing yet. Only adds cover, so nothing lapses and
        // no catch-up runs; `stop()` and the next status publish show it.
        synchronized(coverLock) {
            liftedPeers = emptySet()
            covered = internetPeers
        }
    }

    /**
     * Stops waiting on every direct send. Each stays booked for every airing, as at send: the board may go on retrying
     * what it took, link or no link. A late destination ack for one still moves the marks ([onUnawaited]).
     */
    private fun endFlights() {
        direct.clearFlights()
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
     * Parks until [restoreState] has applied the previous process's limiters. The pacer waits here, and the re-probe
     * asks [restored] before a round: a re-probe sent before its persisted backoff is back would re-ask every silent
     * board at the base gap on each launch. The auto-reply cannot wait here — its sender is judged and stamped at
     * receipt, on the packet collector — so it asks [restored] itself and refuses (`NOT_READY`, unstamped) until the
     * memory is back, or a DM the last process answered would be answered twice. That copy goes unanswered: the board's
     * to-phone queue gives a packet up once the phone has read it, so it never comes again. The sender's next DM is.
     */
    private suspend fun awaitRestored() {
        restored.first { it }
    }

    // --- limiters that outlive the process (LoraPlaneState) ---

    /**
     * Reads the limiter state the last process left and applies it, then releases [awaitRestored] — always,
     * including on a store that is missing, slow or broken. A plane that stays silent because a preferences
     * read failed would be a far worse trade than the re-probe schedule and auto-reply memory it is waiting for.
     */
    private suspend fun restoreState() {
        // Once per instance. A stop/start inside one process still holds the live limiters, and re-reading
        // would replace them with a blob up to one debounce older — or, if [stop]'s write has not landed yet,
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
        // An older build's ledger (`air`) is not read back (ADR 2026-10.ug4a): the window starts empty.
        hooks.restoreState(snapshot, shift)
        reprobe.restore(
            snapshot.reprobes.map { LoraReprobe.Entry(it.id, shift.toMono(it.dueAtWall), it.gapMs, it.lastFrameId) },
        )
        log("lora state restored: autoReplied=${snapshot.autoReplied.size} reprobes=${snapshot.reprobes.size}")
    }

    private fun snapshotState(): LoraPlaneSnapshot {
        val now = clock()
        val nowWall = wallClock()
        val shift = WallShift(now, nowWall)
        val holders = boardHolders
        return LoraPlaneSnapshot(
            savedAtWall = nowWall,
            // The kind's limiters: on Meshtastic the DM auto-reply's once-a-day memory (ADR 2026-09.4n5p).
            autoReplied = hooks.snapshotState(shift),
            // And the re-probe's backoff (ADR 2026-10.rp3s): forgotten, every restart would re-probe each silent
            // recipient at the base gap again. Under the cap, direct board holders first and then the soonest, so a
            // holder's earned backoff is never the one dropped (ADR 2026-10.ug4a).
            reprobes =
                LoraReprobe
                    .capped(reprobe.stamps(), STATE_REPROBE_CAP, holders?.let { map -> { peer -> map[peer]?.direct == true } })
                    .map { ReprobeStamp(it.recipientId, shift.toWall(it.dueAt), it.gapMs, it.lastFrameId) },
        )
    }

    /**
     * Persists the limiters a beat after something moves them: a re-probe's schedule, an auto-reply's stamp. Conflated
     * and debounced rather than written per event; a process killed inside the debounce loses that one change.
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
     * meaning (don't bring up a redundant sync) is moot; what it is read for here is the **link cover**: a compact DM
     * to a linked peer stays off the air ([offer]), is refused at send if the link came up while it waited
     * (`StaleAtSend.LINKED`), and the re-probe defers it. The only feed of [linkedPeers]. It used to move the gateway
     * election and withdraw co-pocket hearings too; both left with the bridge (ADR 2026-10.rp3s).
     */
    override fun suppressDataPath(peers: Set<String>) {
        linkedPeers = peers
    }

    /**
     * ScopeSync's present set (ADR 2026-09.y5f3). A lift ([onDirectPacket]) survives every change but the relay losing
     * that peer. A peer whose cover lapses gets a catch-up at once (#294): a DM the cover kept off the air has no other
     * way back to it but the re-probe, a window or more out.
     */
    override fun coveredByInternet(peers: Set<String>) {
        val lapsed =
            synchronized(coverLock) {
                val before = covered
                liftedPeers = liftedPeers intersect peers
                internetPeers = peers
                covered = peers - liftedPeers
                before - covered
            }
        publishStatus()
        if (lapsed.isNotEmpty() && mayTransmit()) scope.launch { lapsed.forEach { catchUp(it, why = "cover lapsed") } }
    }

    /**
     * Lifts the relay cover for [peerId] after a live compact frame from them, true when it was covered. Their sender
     * aired it only because no relay joined us by its reckoning; until our relay itself loses them, what we owe them —
     * the ✓✓ the delivery is about to coalesce, our next DM — goes on the air too (#294). Accepted cost: duplicate air
     * while they stay present on the relay, paced by what the two users write.
     */
    private fun liftCover(peerId: String): Boolean {
        val lifted =
            synchronized(coverLock) {
                if (peerId !in covered) return@synchronized false
                liftedPeers = liftedPeers + peerId
                covered = internetPeers - liftedPeers
                true
            }
        if (lifted) {
            log("lora cover lifted $peerId")
            publishStatus()
        }
        return lifted
    }

    /**
     * Sets the connected board up for Knit, or puts it back ([ProvisionMode.Restore]) — the kind's setup
     * ([BoardKindHooks.provision], ADR 045), which also keeps a key from being taught to the board inside its admin
     * transaction. Requires a Ready link.
     */
    override suspend fun provisionKnitChannel(
        mode: ProvisionMode,
        previous: BoardSettings?,
    ): ProvisionResult = hooks.provision(mode, previous)

    // --- outbound (fast plane only) ---

    override fun fastFanout(wire: WireEnvelope) = fanout(wire, "fanout")

    /**
     * The one fan-out: the composite's coordination-plane blast ([fastFanout]) — an own room post, and [LoraFramePolicy]
     * is the single gate for what rides.
     *
     * The admit rule first (ADR 2026-10.rp3s): a frame somebody else wrote never rides — the composite re-calls this
     * on every relay, plane-agnostic on purpose. Until our own id is read the rule cannot be asked, so the frame is
     * judged on its kind alone and carries its author ([RideGate.authorId]) for [staleReason] to ask once the pacer —
     * which waits for the id — takes it: an own room post typed during a Keystore backoff still rides, a relayed one
     * queued then never does.
     */
    private fun fanout(
        wire: WireEnvelope,
        label: String,
    ) {
        if (!mayTransmit()) return // no board of our own
        val env = WireCodec.decodeEnvelope(wire.signed) ?: return
        val self = selfIdCached
        if (self != null && !LoraFramePolicy.airs(env.senderId, self)) return // somebody else's: this board is not a relay
        if (!LoraFramePolicy.eligible(env)) return
        if (!LoraFramePolicy.isFresh(env, wallClock())) {
            metrics.onLoraSuppressed() // a custody re-serve of an old frame — custody's business, not a live plane's
            return
        }
        // Written before this process started (ADR 2026-10.ug4a): not something the user just typed, but an own post
        // re-served back to us — a neighbour's custody or a spool pull, after a restart emptied the router's seen window
        // and [sigSeen] — and a crash loop would re-air it on every launch. Its own radios and custody still carry it.
        if (LoraFramePolicy.predates(env, processStartWall)) {
            metrics.onLoraSuppressed()
            log("lora not re-aired $label:${env.id}: written before this process started")
            return
        }
        admitFanout(wire, env, label, preId = self == null)
    }

    /**
     * The fan-out past its gates: encode, the one-packet rule for a room post, the signature dedup, and the queue.
     *
     * [preId] marks a frame fanned before our own id was read: it takes one of the [PRE_ID_ADMISSIONS] slots, and only
     * here — past the freshness check, the encode and the one-packet rule — so a stale or oversize frame never uses up
     * the slot an own post typed during the backoff needs. A slot is given back if the dedup or the queue refuses the
     * frame.
     */
    private fun admitFanout(
        wire: WireEnvelope,
        env: RelayEnvelope,
        label: String,
        preId: Boolean = false,
    ) {
        val parts = encodeOrNull(wire, "$label:${env.type}") ?: return
        // A room post rides only in one packet (ADR 2026-10.rp3s): the room is read by everyone the flood reaches, so
        // a post that needs two is two packets of a shared window spent on one line nobody is waiting for, while the
        // radios and the spool carry it anyway. Asked after encoding, because the 0x05 deflate makes the fit
        // depend on the words, not on a length. Its own counter, apart from `loraTooBig` (past three packets). The
        // limit is the board kind's ([BoardTraits.roomPartLimit]): one on Meshtastic.
        if (LoraFramePolicy.isBroadcastRoomChat(env) && parts.size > traits.roomPartLimit) {
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
        val now = wallClock()
        val freshUntil = freshUntilFor(env)
        // Held until the post could no longer air (#239); a type with no deadline — none rides here — for the window.
        if (!sigSeen.add(key, freshUntil ?: (now + SIG_TTL_MS), now)) {
            if (preId) preIdAdmitted.decrementAndGet()
            metrics.onLoraSuppressed() // already fanned over LoRa while it is fresh
            return
        }
        val accepted =
            enqueue(
                parts,
                "$label:${env.type}",
                // The fan-out's gates are answered about the mesh as it is *now*; the frame may not fly for a whole
                // window. Carried so [staleReason] can ask them again on the way out.
                gate = RideGate(freshUntil = freshUntil, authorId = env.senderId),
                sigKey = key,
            )
        if (preId && !accepted) preIdAdmitted.decrementAndGet() // the queue refused it: nothing of the cap was spent
    }

    /**
     * Takes one of the [PRE_ID_ADMISSIONS] slots, or says none is left. Check-and-take in one step: fan-outs run on
     * different coroutines, and an increment-then-check let a refused frame hold a slot nobody used.
     */
    private fun reservePreId(): Boolean {
        while (true) {
            val taken = preIdAdmitted.get()
            if (taken >= PRE_ID_ADMISSIONS) return false
            if (preIdAdmitted.compareAndSet(taken, taken + 1)) return true
        }
    }

    /**
     * One key for every copy of one compact DM the plane might queue — the offer's, the resend's and the re-probe's —
     * so each can see a copy still waiting ([LoraPacePolicy.hasQueued]) and not queue a second (ADR 2026-10.x9fa).
     * Room posts keep none: nothing re-airs them.
     */
    private fun frameKey(frameId: String): String = "frame:$frameId"

    /**
     * Refuses everything (ADR 2026-10.rp3s). The targeted path carried the room's ✓✓ to a far author (AckSync's
     * cleartext receipt and its sealed escalation) — a packet from every reader of every room post, and the one
     * thing on this plane nobody typed. A room tick now gets home by a link or the spool, or not at all; a DM's ✓✓
     * rides compact, through [offer]. Overridden only to say so: the seam's default is the same no-op.
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
     * frame's dedup slot on a frame that never went out.
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

    /** A [FrameClass.ROOM] frame: a Knit room post, or (at [Ceiling.HALF]) the Meshtastic room's post or the auto-reply. */
    private fun enqueue(
        parts: List<ByteArray>,
        label: String,
        ceiling: Ceiling = Ceiling.FULL,
        destination: Destination = Destination.Knit,
        gate: RideGate = RideGate(),
        to: BoardAddr? = null,
        sigKey: String? = null,
    ): Boolean = enqueue(OutboundFrame(parts, label, FrameClass.ROOM, ceiling, destination, to, gate = gate, sigKey = sigKey))

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

    // --- compact DMs: one pair-sealed unicast to the recipient's board (ADR 2026-10.x9fa) ---

    /**
     * The compact copy of a DM, a DM reaction or the coalesced ✓✓ that `MeshManager` just sealed for [recipientId] —
     * beside its signed copy, which custody, the spool and the links carry as ever. One packet goes to the recipient's
     * board with `want_ack`, or nothing does; nothing here reads [plaintext].
     *
     * The gates, each re-asked before the first byte ([staleReason]): DMs switched on; [recipientId] a pinned board
     * holder whose profile carries the capability; neither a live link nor a spool carrying them; the bound slot not
     * 0 (the Knit channel is where Knit's frames go; the firmware PKI-encrypts a unicast on any slot); one copy per
     * [frameId] queued or in flight; the peer not marked unreachable, refused or keyless (held — the DM stays with
     * [source] for the re-probe and the next hearing); one send in flight per peer not known reachable (the rest parked
     * behind it); and the seal fitting one PKI packet ([LoraDirectPolicy.payloadCap]).
     */
    override fun offer(
        recipientId: String,
        frameId: String,
        sentAtMs: Long,
        plaintext: ByteArray,
        kind: DirectKind,
    ) {
        val now = clock()
        val holder = directHolder(recipientId, kind, now) ?: return
        if (!clearToOffer(recipientId, frameId, now)) return
        if (direct.mustWait(recipientId, now, pace.hasQueuedTo(recipientId))) {
            direct.park(recipientId, LoraDirectPolicy.Parked(frameId, sentAtMs, plaintext, kind))
            publishStatus()
            return
        }
        val packet = sealOrNull(recipientId, frameId, sentAtMs, plaintext) ?: return
        enqueueDirect(
            packet,
            DirectSend(recipientId, holder.addr, frameId, kind, airings = traits.airings),
            RideGate(recipientId = recipientId, direct = true, reachHeld = true),
        )
    }

    /**
     * [recipientId]'s board, when DMs ride this plane and their pinned profile names one with the capability — null when
     * not: no board, or a build that would ack and throw it away. Every DM of ours to a board holder goes on the
     * re-probe's schedule first, whatever comes of the offer: the ack is the board's, the ✓✓ the phone's, and only the
     * second ends it. A map that has not reported cannot say no yet.
     */
    private fun directHolder(
        recipientId: String,
        kind: DirectKind,
        now: Long,
    ): LoraBoardHolder? {
        if (!mayTransmit() || currentConfig?.dms != true) return null
        val map = boardHolders
        val holder = map?.get(recipientId)
        if (kind == DirectKind.MESSAGE && (map == null || holder?.direct == true)) {
            reprobe.onHeld(recipientId, now)
            stateWake.trySend(Unit)
        }
        return holder?.takeIf { it.direct }
    }

    /**
     * The offer's own gates past the board: the covers, slot 0, a copy already waiting, and the peer's mark — each
     * counted under its name where it is one.
     */
    private fun clearToOffer(
        recipientId: String,
        frameId: String,
        now: Long,
    ): Boolean =
        when {
            skipCovered(recipientId) -> {
                false
            }

            hooks.directSlotRefused(currentConfig?.channelIndex) -> {
                metrics.onLoraDirectSlotZero()
                false
            }

            copyWaiting(frameId) -> {
                false
            }

            direct.holds(recipientId, now) -> {
                metrics.onLoraDirectHeld()
                log("lora direct held $frameId -> $recipientId")
                false
            }

            else -> {
                true
            }
        }

    /**
     * The one packet for [plaintext] to [peerId], or null: no pair key, or past one packet at the current payload cap
     * (`loraDirectTooLong` — the signed copy still travels the other planes).
     */
    private fun sealOrNull(
        peerId: String,
        frameId: String,
        sentAtMs: Long,
        plaintext: ByteArray,
    ): ByteArray? {
        val packet = crypto.seal(peerId, frameId, sentAtMs, plaintext)
        if (packet == null) {
            log("lora direct unsealed $frameId -> $peerId")
            return null
        }
        if (packet.size > directPayloadCap()) {
            metrics.onLoraDirectTooLong()
            log("lora direct too long $frameId -> $peerId ${packet.size}B")
            return null
        }
        return packet
    }

    /**
     * The largest compact DM one packet carries here ([BoardTraits.directPayloadCap]: the seal's bound with the link's
     * framing already subtracted — on Meshtastic [LoraDirectPolicy.payloadCap], the firmware's PKI size check under the
     * broadcast cap less what `want_ack` adds to the `ToRadio`). A seal past it would draw `TOO_LARGE` from our own
     * board and never fly (ADR 2026-10.x9fa's PKI amendment). `MeshManager` sizes its plaintext by the same function at
     * the floor ([budgetFor]).
     */
    private fun directPayloadCap(): Int = traits.directPayloadCap(maxPayload)

    /**
     * The compact budget to [recipientId] on this board ([LoraDirectSink.budgetFor], ADR 2026-11.gsqu Stage 1a.4): the
     * kind's [BoardTraits.directBudget], the same function as [directPayloadCap] at the kind's floor — never the holder,
     * Ready or the live payload cap. Immutable, so `MeshManager` may ask from any coroutine.
     */
    override fun budgetFor(recipientId: String): LoraDirectBudget = directBudget

    private fun enqueueDirect(
        packet: ByteArray,
        send: DirectSend,
        gate: RideGate,
    ): Boolean {
        // One priority for every compact frame, the ✓✓ included (ADR 2026-10.ug4a): it is shed before a DM, never
        // dequeued after one, and spends the same window.
        val label = "direct:${send.kind.name.lowercase()}:${send.frameId}"
        return enqueue(
            OutboundFrame(
                listOf(packet),
                label,
                FrameClass.DIRECT,
                Ceiling.FULL,
                Destination.Knit,
                supersedes = frameKey(send.frameId),
                gate = gate,
                direct = send,
            ),
        )
    }

    /** A copy of [frameId] is queued, being written, or on the air awaiting its answer: a second would air twice. */
    private fun copyWaiting(frameId: String): Boolean = pace.hasQueued(frameKey(frameId)) || direct.hasFrame(frameId)

    /**
     * Re-airs [dm], one of our DMs still without its ✓✓, to [peerId] — the resend after a hearing, or the re-probe. Not
     * held for an unreachable mark (asking again is the point), but every other gate of the offer is asked, and the
     * pacer asks again at send whether [dm] is still unacked. [flushAfter] are the older ones to follow once the
     * destination acks this one.
     */
    private fun enqueueResend(
        peerId: String,
        dm: DirectDm,
        flushAfter: List<String>,
    ): Boolean {
        if (!mayTransmit() || currentConfig?.dms != true) return false
        val holder = boardHolders?.get(peerId)
        if (holder == null || !holder.direct) return false
        if (peerId in linkedPeers || peerId in covered) return false
        if (hooks.directSlotRefused(currentConfig?.channelIndex)) return false
        if (copyWaiting(dm.frameId)) return false
        if (direct.reachOf(peerId, clock()).keepsResendsOff()) return false
        if (direct.mustWait(peerId, clock(), pace.hasQueuedTo(peerId))) return false
        val packet = sealOrNull(peerId, dm.frameId, dm.sentAtMs, dm.plaintext) ?: return false
        return enqueueDirect(
            packet,
            DirectSend(
                peerId,
                holder.addr,
                dm.frameId,
                DirectKind.MESSAGE,
                resend = true,
                flushAfter = flushAfter,
                airings = traits.airings,
            ),
            RideGate(recipientId = peerId, direct = true, unackedFrameId = dm.frameId),
        )
    }

    /**
     * [peerId]'s board was heard on the air (ADR 2026-10.x9fa). Only the edge out of an unreachable mark matters — or,
     * when [lifted], a relay cover this hearing just lifted — and then [catchUp] decides.
     */
    private suspend fun onBoardHeard(
        peerId: String,
        lifted: Boolean = false,
    ) {
        val edge = direct.onHeard(peerId)
        if (edge) {
            log("lora direct heard $peerId")
            publishStatus()
        }
        if (edge || lifted) catchUp(peerId, why = if (lifted) "lifted" else "heard")
    }

    /**
     * Re-airs what a fact says LoRa now carries to [peerId] (#294, ADR 2026-10.x9fa's 2026-11-04 amendment): their board
     * heard after a failure, their relay cover lapsed or lifted, or their board acking a fresh DM ([skip], that one).
     * One backoff for every trigger: nothing goes inside the re-probe's gap since the last failure this process saw
     * ([LoraReprobe.onHeard], which pulls the round forward instead), and what goes is booked as a re-probe
     * ([LoraReprobe.onReprobed]). Only DMs no board took ([DirectDm.boardTook]: one that did waits on its phone, which
     * is the re-probe's question) and none with a copy still waiting. The newest goes now, the ≤ 2 older after its
     * destination acks it; each asks [LoraDirectSource.isUnacked] again at send, never behind a timer.
     */
    private suspend fun catchUp(
        peerId: String,
        skip: String? = null,
        why: String,
    ) {
        if (!mayTransmit() || currentConfig?.dms != true) return
        val now = clock()
        if (!reprobe.onHeard(peerId, now)) return
        val held =
            source
                .unackedTo(peerId, RESEND_LIMIT, untakenOnly = true, maxBytes = directBudget.plaintextBytes)
                .filter { it.frameId != skip && !it.boardTook && !copyWaiting(it.frameId) }
        val newest = held.firstOrNull() ?: return
        if (!enqueueResend(peerId, newest, flushAfter = held.drop(1).map { it.frameId })) {
            log("lora catch-up refused $peerId")
            return
        }
        // Behind a fresh DM's board ack ([skip]) the board is there and the gap was just reset (`onAcked`): this is no
        // evidence the phone is away, so it books at the base gap rather than doubling — or a burst's acks would push the
        // re-probe out to its 4 h cap. Every other trigger books as a re-probe does.
        val reset = skip != null || lastAcked(peerId, newest.frameId)
        reprobe.onReprobed(peerId, newest.frameId, lastAcked = reset, now)
        stateWake.trySend(Unit)
        log("lora resend ${newest.frameId} -> $peerId ($why, +${held.size - 1} after its ack)")
    }

    /** Whether the DM the last re-probe or resend to [peerId] aired has its ✓✓ now — what resets the backoff. */
    private suspend fun lastAcked(
        peerId: String,
        next: String,
    ): Boolean {
        val last = reprobe.lastFrameOf(peerId) ?: return false
        return last != next && !source.isUnacked(last)
    }

    /**
     * The re-probe round (ADR 2026-10.rp3s, re-keyed by ADR 2026-10.x9fa, [LoraReprobe]): for each peer whose schedule is
     * due, re-air the newest of our DMs to them still without its ✓✓ — whatever their board said, since its ack never
     * meant the phone read it. Run on the linger sweep, so only with a board configured, and only once the last
     * process's limiters and our own id are back.
     */
    private suspend fun reprobeDue() {
        if (!restored.value || !mayTransmit() || currentConfig?.dms != true) return
        selfIdCached ?: return
        reprobe.due(clock()).forEach { reprobeOne(it) }
    }

    private suspend fun reprobeOne(peerId: String) {
        val now = clock()
        val map = boardHolders
        val holder = map?.get(peerId)
        when {
            // The map has not reported: "not now", never "never" — a restored schedule outlives a slow first emission.
            map == null -> {
                reprobe.defer(peerId, now)
            }

            // No board, or no capability: nothing on the air may go to them. A Stage-1 schedule restored for such a peer
            // ends here, on its first due round; a later DM to them puts them back if their profile gains it.
            holder == null || !holder.direct -> {
                reprobe.forget(peerId)
                stateWake.trySend(Unit)
            }

            peerId in linkedPeers || peerId in covered -> {
                reprobe.defer(peerId, now)
            }

            // Refused by their board, or our board holds no key for it (ADR 2026-10.aajj): asking again changes nothing
            // until the profile changes, the key is heard or the mark lapses.
            direct.reachOf(peerId, now).keepsResendsOff() -> {
                reprobe.defer(peerId, now)
            }

            // A send is queued or waiting on its answer: that is this round's question already.
            direct.mustWait(peerId, now, pace.hasQueuedTo(peerId)) || pace.hasQueuedTo(peerId) -> {
                reprobe.defer(peerId, now)
            }

            else -> {
                reprobeNewest(peerId, now)
            }
        }
    }

    private suspend fun reprobeNewest(
        peerId: String,
        now: Long,
    ) {
        val newest = source.unackedTo(peerId, 1, untakenOnly = false, maxBytes = directBudget.plaintextBytes).firstOrNull()
        if (newest == null) {
            reprobe.forget(peerId) // acked, or past the window: nothing left to ask with
            stateWake.trySend(Unit)
            return
        }
        if (!enqueueResend(peerId, newest, flushAfter = emptyList())) {
            reprobe.defer(peerId, now)
            return
        }
        reprobe.onReprobed(peerId, newest.frameId, lastAcked = lastAcked(peerId, newest.frameId), now)
        metrics.onLoraReprobed()
        stateWake.trySend(Unit)
        log("lora reprobe ${newest.frameId} -> $peerId")
    }

    /**
     * A direct send the board took ([sendMessage]): it now waits on its answer, until its deadline at the latest. One
     * airing is priced at the [price] it was booked at — the packet, the PKC overhead and the frame's own cliff — so the refund finds
     * those bookings and the gap and the deadline are the firmware's for the packet that flies.
     */
    private fun launchFlight(
        frame: OutboundFrame,
        send: DirectSend,
        handle: UInt,
        packet: ByteArray,
        price: PacketPrice,
        bookedAt: Long,
        userSeq: Long,
    ) {
        val air = pace.airtime.timeOnAirMs(packet.size, price)
        val slot = pace.airtime.slotTimeMs()
        val flight =
            LoraDirectPolicy.Flight(
                handle = handle,
                send = send,
                airMs = air,
                sentAt = bookedAt,
                deadline = bookedAt + traits.deadlineMs(air, slot),
                retransmitMs = traits.retransmitMs(air, slot, traits.cwMin),
                packet = packet,
                gate = frame.gate,
                userSeqAtSend = userSeq,
            )
        direct.launch(flight)?.let { evicted -> settle(evicted, PacketOutcome.NoOutcome(evicted.handle)) }
        flightWake.trySend(Unit)
        metrics.onLoraDirectSent()
        if (send.resend) metrics.onLoraDirectResent()
    }

    /**
     * Gives back the airings an answered send was booked for and did not need: all but the first and the retries that
     * had flown by [now], counted at the shortest gap so the refund errs small. Of the airings the frame carried
     * ([DirectSend.airings]), the ones its send booked.
     */
    private fun refundUnflown(
        flight: LoraDirectPolicy.Flight,
        now: Long,
    ) {
        val flown = 1 + traits.retriesBefore(now - flight.sentAt, flight.retransmitMs)
        val back = pace.airtime.refund(flight.sentAt, flight.airMs, flight.send.airings - flown)
        if (back > 0) {
            pace.onAirReturned()
            wake.trySend(Unit) // air came back: a frame the window held may fit now
        }
    }

    /**
     * What became of a packet after the board took it. A broadcast's late refusal is today's NAK; a direct send's
     * answer settles it, except an implicit ack, which is counted and never ends the wait — a relay or our own board
     * hearing it repeated says nothing about its destination (ADR 2026-10.x9fa).
     */
    private fun onOutcome(outcome: PacketOutcome) {
        if (!direct.isWaiting(outcome.id)) {
            onUnawaited(outcome)
            return
        }
        if (outcome is PacketOutcome.Acked && !outcome.byDestination) {
            metrics.onLoraDirectImplicitAck()
            return
        }
        direct.resolve(outcome.id)?.let { settle(it, outcome) }
    }

    /**
     * An outcome for a handle nothing waits on: a broadcast's late refusal, or the answer to a compact DM that already
     * ended with none — the destination's ack replayed from the board's to-phone queue after a BLE drop. That one still
     * moves the marks (the board did take it), and books nothing: every airing was booked when it ended.
     */
    private fun onUnawaited(outcome: PacketOutcome) {
        val now = clock()
        when (outcome) {
            is PacketOutcome.Acked -> {
                if (!outcome.byDestination) return
                val send = direct.ended(outcome.id) ?: return
                direct.onAcked(send.peerId, now)
                hooks.onDelivered(send.addr)
                if (send.kind == DirectKind.MESSAGE) {
                    reprobe.onAcked(send.peerId, now, fresh = !send.resend)
                    radioReached(send, outcome, late = true)
                }
                stateWake.trySend(Unit)
                publishStatus()
                log("lora direct late ack ${send.frameId} -> ${send.peerId}")
            }

            is PacketOutcome.Refused -> {
                onUnawaitedRefusal(outcome, now)
            }

            // A give-up or silence for a send that already ended changes nothing: it is booked and marked.
            is PacketOutcome.Unreachable, is PacketOutcome.NoOutcome -> {}
        }
    }

    /**
     * A refusal nothing waits on. Our board's `39` arriving after its `queueStatus` already said so is that same refusal,
     * acted on once (ADR 2026-10.aajj): attributed, never a pacing NAK. Only a compact DM's: the self-NAK firmware sends
     * for every refused send (`Router.cpp:591`, `:400`) also follows an auto-reply or a room post refused with `39`, and
     * that one is an ordinary late NAK, never `loraDirectNoKey`. Their board's `PKI_UNKNOWN_PUBKEY` after the
     * send ended is still our board's `NodeInfo` on the air, and never a refusal; any other refusal by their board holds
     * the peer as ever.
     */
    private fun onUnawaitedRefusal(
        outcome: PacketOutcome.Refused,
        now: Long,
    ) {
        // The order is the core's (ADR 2026-11.gsqu, Stage 1a.3): a handled missing key first, then the ended send, then
        // their lack of our key, then any other refusal by their board. What each means is the kind's classifier's.
        val refusal = traits.refusalOf(outcome)
        if (refusal == RefusalClass.OWN_NO_KEY && direct.handledNoKey(outcome.id)) {
            log("lora nak id=${outcome.id} reason=${outcome.reason} (late, no key)")
            return
        }
        val send = direct.ended(outcome.id)
        when {
            send == null -> {
                onLateNak(outcome)
            }

            refusal == RefusalClass.PEER_LACKS_KEY -> {
                metrics.onLoraDirectPeerLacksOurKey()
                hooks.bookKeyReply(now)
            }

            refusal == RefusalClass.DEST_REFUSED -> {
                direct.onRefused(send.peerId, now)
                publishStatus()
            }
        }
    }

    /**
     * Tells the chat our DM [send] reached its recipient's radio ([onRadioReached]), unless our board found the ack's
     * proof forged: the ack's `from` is a cleartext header anyone with the channel key can write, and a claim shown to
     * the user needs more than that. An absent proof (an older firmware, ham mode) still counts — the residual trust ADR
     * 040's 2026-10-31 amendment records. A resend or a re-probe carries the same frame id, so its ack counts the same.
     */
    private fun radioReached(
        send: DirectSend,
        ack: PacketOutcome.Acked,
        late: Boolean,
    ) {
        if (ack.proof == AckProof.INVALID) {
            log("lora direct ack proof invalid ${send.frameId} -> ${send.peerId}")
            return
        }
        runCatching { onRadioReached(send.frameId, late) }.onFailure { log("lora direct radio reached failed: $it") }
    }

    /**
     * A late refusal of a send nothing waits on: counted, and paced by its reason alone through the Meshtastic reading of
     * a NAK ([LoraPacePolicy.onNak], the classifier's) — whoever sent it, as before the seam.
     */
    private fun onLateNak(outcome: PacketOutcome.Refused) {
        metrics.onLoraNak(outcome.reason.name)
        log("lora nak id=${outcome.id} reason=${outcome.reason} (late)")
        pace.onNak(outcome.reason, clock())
    }

    /**
     * Ends one direct send on [outcome]: settles what it was booked for, moves the peer's mark, and lets go of what waited
     * on it. A destination's ack refunds the airings that had not flown by then and marks the board reachable; a give-up
     * or no answer keeps every airing booked and marks it unreachable, never reachable; a refusal by the destination holds the peer
     * until their profile changes, one by our own board (a duty-cycle NAK) only paces.
     */
    private fun settle(
        flight: LoraDirectPolicy.Flight,
        outcome: PacketOutcome,
    ) {
        val now = clock()
        val send = flight.send
        val peer = send.peerId
        when (outcome) {
            is PacketOutcome.Acked -> {
                refundUnflown(flight, now)
                metrics.onLoraDirectAcked()
                direct.onAcked(peer, now)
                // Their board took a PKI packet under the key ours holds: should ours lose it again, it may be taught once more.
                hooks.onDelivered(send.addr)
                if (send.kind == DirectKind.MESSAGE) {
                    reprobe.onAcked(peer, now, fresh = !send.resend)
                    radioReached(send, outcome, late = false)
                }
                log("lora direct acked ${send.frameId} -> $peer")
                direct.unpark(peer).forEach { offer(peer, it.frameId, it.sentAtMs, it.plaintext, it.kind) }
                if (send.flushAfter.isNotEmpty()) scope.launch { flush(peer, send.flushAfter) }
                // Their board just took a DM the user wrote: anything older no board took goes behind it (#294). Never
                // on a resend's ack, which carries its own flushAfter.
                if (send.kind == DirectKind.MESSAGE && !send.resend) {
                    scope.launch { catchUp(peer, skip = send.frameId, why = "after ack") }
                }
            }

            is PacketOutcome.Refused -> {
                settleRefused(flight, outcome, now)
            }

            is PacketOutcome.Unreachable, is PacketOutcome.NoOutcome -> {
                // Every airing stays booked, as it was at send: the firmware flew them all, or may have.
                if (outcome is PacketOutcome.Unreachable) metrics.onLoraDirectUnreachable() else metrics.onLoraDirectNoOutcome()
                direct.onUnreachable(peer, now)
                reprobe.onFailed(peer, now)
                log("lora direct unreachable ${send.frameId} -> $peer (${outcome::class.simpleName})")
                // Left to the source: the DMs among them are re-aired by the next hearing or the re-probe.
                direct.unpark(peer)
            }
        }
        stateWake.trySend(Unit)
        publishStatus()
    }

    /**
     * A refusal of a send in flight. Our board's want of their key, reported only as a late self-NAK because the send's
     * `queueStatus` timed out: nothing aired, every airing comes back, and the `add_contact` path takes the frame (ADR
     * 2026-10.aajj). Their board's want of ours (`PKI_UNKNOWN_PUBKEY`): never a refusal — our board answers with our
     * `NodeInfo`, which heals the pair within seconds — so it is re-offered once, a retransmit gap on. Any other refusal
     * by their board holds the peer until their profile changes; one by our own board (a duty-cycle NAK) only paces.
     */
    private fun settleRefused(
        flight: LoraDirectPolicy.Flight,
        outcome: PacketOutcome.Refused,
        now: Long,
    ) {
        val send = flight.send
        val peer = send.peerId
        when (val refusal = traits.refusalOf(outcome)) {
            RefusalClass.OWN_NO_KEY -> {
                refundAll(flight)
                flight.packet?.let { onNoKey(frameOf(flight, send, it), flight.handle, flight.userSeqAtSend) }
            }

            RefusalClass.PEER_LACKS_KEY -> {
                refundUnflown(flight, now)
                onPeerLacksOurKey(flight, now)
            }

            else -> {
                refundUnflown(flight, now)
                metrics.onLoraNak(outcome.reason.name)
                log("lora direct refused ${send.frameId} -> $peer reason=${outcome.reason} byDestination=${outcome.byDestination}")
                if (refusal == RefusalClass.DEST_REFUSED) direct.onRefused(peer, now)
                if (refusal == RefusalClass.PACE) pace.onPaceRefusal(now)
                direct.unpark(peer)
            }
        }
    }

    /**
     * Their board answered `PKI_UNKNOWN_PUBKEY` (35): it holds no key for ours, and our board has just sent it our
     * `NodeInfo` in reply (`ReliableRouter.cpp:162-168` at v2.8.1) — air the window owes, booked here. The frame goes
     * once more after one retransmit gap, through the pacer and its own gates, with what was parked still waiting behind
     * it; a second 35 to that re-offer is a give-up, as the firmware's own would be (ADR 2026-10.aajj).
     */
    private fun onPeerLacksOurKey(
        flight: LoraDirectPolicy.Flight,
        now: Long,
    ) {
        val send = flight.send
        val peer = send.peerId
        metrics.onLoraDirectPeerLacksOurKey()
        hooks.bookKeyReply(now)
        val packet = flight.packet
        if (send.afterPeerKey || packet == null) {
            metrics.onLoraDirectUnreachable()
            direct.onUnreachable(peer, now)
            reprobe.onFailed(peer, now)
            log("lora direct peer lacks our key ${send.frameId} -> $peer again: unreachable")
            direct.unpark(peer)
            return
        }
        val retry =
            DirectSend(
                peer,
                send.addr,
                send.frameId,
                send.kind,
                resend = send.resend,
                flushAfter = send.flushAfter,
                afterPeerKey = true,
                airings = send.airings,
            )
        direct.scheduleRetry(peer, now + flight.retransmitMs, frameOf(flight, retry, packet))
        flightWake.trySend(Unit)
        log("lora direct peer lacks our key ${send.frameId} -> $peer: re-offer in ${flight.retransmitMs}ms")
    }

    /** A queued frame again for [flight]'s sealed [packet], as [send], under the gates its own path asked. */
    private fun frameOf(
        flight: LoraDirectPolicy.Flight,
        send: DirectSend,
        packet: ByteArray,
    ): OutboundFrame =
        OutboundFrame(
            listOf(packet),
            "direct:${send.kind.name.lowercase()}:${send.frameId}",
            FrameClass.DIRECT,
            Ceiling.FULL,
            Destination.Knit,
            supersedes = frameKey(send.frameId),
            gate = flight.gate,
            direct = send,
        )

    /** Gives back every airing [flight] was booked for: our own board refused it, so none flew. */
    private fun refundAll(flight: LoraDirectPolicy.Flight) {
        if (pace.airtime.refund(flight.sentAt, flight.airMs, flight.send.airings) > 0) {
            pace.onAirReturned()
            wake.trySend(Unit)
        }
    }

    /** Our bound board's address, or null with no session up. */
    private fun ownBoard(): BoardAddr? = (board.state.value as? BoardState.Ready)?.own

    // --- a missing key on our board: the add_contact (ADR 2026-10.aajj) ---

    /**
     * Our own board refused [frame] before it aired, for want of the destination board's key ([RefusalClass.OWN_NO_KEY],
     * packet [handle]): acted on once per handle, whichever of its synchronous answer and its late refusal came first.
     * Nothing flew and nothing was booked. The frame is **held**, never given up, while the one thing that can help is
     * tried: the kind teaching the board the key ([BoardKindHooks.teachKey]; on Meshtastic an `add_contact` for a pinned
     * contact's board), after which it is re-offered through the pacer and its own gates. When none may be written the
     * peer is marked [LoraDirectPolicy.Reach.NO_KEY]: held, counted (`loraDirectNoKey`), never silent, and the DM stays
     * with the source for the re-probe.
     */
    private fun onNoKey(
        frame: OutboundFrame,
        handle: UInt,
        sentAfter: Long,
    ) {
        val send = frame.direct ?: return
        if (!direct.firstNoKey(handle)) return
        pace.done(frame) // held here now, not by the pacer
        metrics.onLoraDirectNoKey()
        val peer = send.peerId
        log("lora direct no key ${send.frameId} -> $peer id=$handle")
        val own = ownBoard()
        val holder = boardHolders?.get(peer)
        if (own == null || hooks.provisioning) {
            // Between sessions, or inside a setup's admin transaction: ask again once it is over.
            requeue(frame)
            return
        }
        if (holder == null || holder.addr != send.addr) {
            abandon(frame) // their board changed while it waited: the offer's gates decide anew
            return
        }
        when (val teach = hooks.teachKey(peer, holder, own, sentAfter)) {
            is KeyTeach.Write -> {
                direct.holdForContact(peer, frame)
                // A coroutine on the plane's scope: the write waits on the board's answer.
                scope.launch { onKeyWritten(peer, teach.write()) }
            }

            KeyTeach.Refused -> {
                direct.onNoKey(peer, clock())
                abandon(frame)
            }
        }
        publishStatus()
    }

    /**
     * What became of the key written for [peerId] ([KeyWrite]), for the frame held for it: taken or unconfirmed, it goes
     * again through the pacer (a refused admission hands it to the shed, which [enqueue] abandons, so what was parked
     * behind it is offered again); never written, it is owed; refused by our board, the peer is marked keyless and the
     * frame let go.
     */
    private fun onKeyWritten(
        peerId: String,
        written: KeyWrite,
    ) {
        when (written) {
            KeyWrite.WRITTEN, KeyWrite.UNCONFIRMED -> {
                direct.releaseContact(peerId)?.let { enqueue(it) }
            }

            KeyWrite.NOT_WRITTEN -> {
                direct.releaseContact(peerId)?.let(::requeue)
            }

            KeyWrite.REFUSED -> {
                direct.onNoKey(peerId, clock())
                direct.releaseContact(peerId)?.let(::abandon)
            }
        }
        publishStatus()
    }

    /**
     * The older unacked DMs a catch-up promised to follow once its destination acked the newest. Read untaken, as the
     * catch-up chose them: the resend just acked is taken now, and counted against the limit it would hide the oldest.
     */
    private suspend fun flush(
        peerId: String,
        frameIds: List<String>,
    ) {
        val held =
            source.unackedTo(peerId, RESEND_LIMIT, untakenOnly = true, maxBytes = directBudget.plaintextBytes).associateBy { it.frameId }
        frameIds.mapNotNull(held::get).forEach { enqueueResend(peerId, it, flushAfter = emptyList()) }
    }

    /**
     * Ends every direct send that outlived its deadline as [PacketOutcome.NoOutcome]: every send ends, even one whose
     * answer the board never sent or the link lost (ADR 2026-10.x9fa). Also queues the one re-offer after a destination
     * lacked our key once its gap has passed (ADR 2026-10.aajj). Sleeps until the soonest of either, woken when a new
     * one starts waiting; parked while no board is configured, which [quiesce] has emptied anyway.
     */
    private suspend fun deadlineLoop() {
        while (scope.isActive) {
            awaitConfigured()
            val next = direct.nextDeadline()
            if (next == null) {
                flightWake.receive()
            } else {
                withTimeoutOrNull((next - clock()).coerceAtLeast(1)) { flightWake.receive() }
            }
            runRound({ log("lora deadline round failed: ${it.javaClass.simpleName}") }) {
                direct.expired(clock()).forEach { settle(it, PacketOutcome.NoOutcome(it.handle)) }
                // The one re-offer after a destination lacked our key, its gap passed: back through the pacer. A refused
                // admission is abandoned by `enqueue` (its shed), so what was parked behind it is offered again.
                direct.dueRetries(clock()).forEach { frame ->
                    log("lora resend ${frame.direct?.frameId} -> ${frame.direct?.peerId} (our key sent)")
                    enqueue(frame)
                }
            }
        }
    }

    /**
     * A compact DM off the air (ADR 2026-10.x9fa). Read on the bound Knit slot or on index 0 — 2.5+ firmware reports a
     * PKI-decrypted DM there whatever slot it used, and since 2.8 PKI-encrypts every unicast its phone sends, that is
     * where every compact DM from a 2.8 board lands (the PKI amendment) — and only when addressed to our own board: a
     * unicast to another board is not ours to open. Not gated on our own DM switch: our board has already acknowledged
     * it, and dropping it here would turn the sender's delivered radio into a lost message. Opened and live, it lifts the
     * peer's relay cover first ([liftCover], #294); then it is delivered, the peer's reach is noted if it is fresh, and
     * only then is their board heard, so a ✓✓ it carried is applied before a resend asks what is still unacked.
     */
    private fun onDirectPacket(
        packet: BoardPacket,
        bound: Int?,
    ) {
        val ready = board.state.value as? BoardState.Ready ?: return
        // Addressed to our board, and not from it: our own unicast echoed back is nobody's DM to us. The slots it may
        // arrive on are the kind's ([BoardKindHooks.route]).
        if (!packet.toUs || packet.from == ready.own) return
        // A radio sending Knit frames on the bound slot counts at once, as every Knit packet there does. One on index 0 —
        // where firmware 2.8 reports every PKI DM, which every compact DM now is — counts once it has authenticated
        // below: index 0 is the public slot too, and only a pinned peer's seal says the radio is a Knit one.
        if (packet.channel == bound) noteBoard(packet)
        // Our board acked it on our behalf, before the phone could open it — a stranger's and a forged one too: air the
        // window owes (ADR 2026-10.x9fa), booked by the kind when its board did send one.
        hooks.bookDirectAck(packet, ready.own, onBound = packet.channel == bound)
        val opened = openDirect(packet) ?: return
        if (packet.channel != bound) noteBoard(packet)
        metrics.onLoraDirectDelivered()
        log("lora rx direct ${opened.frameId} from ${opened.peerId}")
        // Lifted before delivery, so the ✓✓ the delivery coalesces passes skipCovered. Any authenticated compact frame
        // (a DM, a ✓✓, a reaction), but only a live reception: never off MQTT or out of a replayed queue (#294).
        val lifted = isLive(packet) && liftCover(opened.peerId)
        scope.launch {
            runCatching { inbox.deliver(opened) }
                .onSuccess { log("lora direct delivered ${opened.frameId} from ${opened.peerId}") }
                .onFailure { log("lora direct delivery failed: ${it.javaClass.simpleName}") }
            // Author-keyed reach, as a fresh room post feeds it: an authenticated DM from them, never their board's ACK.
            val age = wallClock() - opened.sentAtMs
            if (age in -Protocol.MAX_FUTURE_SKEW_MS..LoraFramePolicy.FRESH_MS) noteReachable(Peer(opened.peerId))
            onBoardHeard(opened.peerId, lifted)
        }
    }

    /** [packet] opened, or null — a stranger's, a forged one, one out of its window or not one at all, each counted. */
    private fun openDirect(packet: BoardPacket): OpenedDirect? =
        when (val result = crypto.open(packet.payload)) {
            is LoraDirectOpen.Opened -> {
                result.direct
            }

            LoraDirectOpen.Stranger -> {
                metrics.onLoraDirectStranger()
                null
            }

            LoraDirectOpen.AuthFailed -> {
                metrics.onLoraDirectAuthFailed()
                null
            }

            LoraDirectOpen.ClockRefused -> {
                metrics.onLoraDirectClockRefused()
                null
            }

            LoraDirectOpen.Malformed -> {
                metrics.onFastDropped(FastPathDrop.DECODE_FAILED)
                null
            }
        }

    /**
     * The hearing hook (ADR 2026-10.x9fa): a packet off a pinned contact's board, on any portnum, is evidence the board is
     * on the air — but only a live one. The board's own airing, an MQTT uplink, a packet with no RSSI (the NodeDB replay
     * at every handshake) and one whose `rx_time` is minutes old (the to-phone queue replayed at reconnect) are not.
     * Slot-0 traffic from that board counts: it is RF from it all the same.
     */
    private fun noteHeard(packet: BoardPacket) {
        val ready = board.state.value as? BoardState.Ready ?: return
        val from = packet.from ?: return
        if (from == ready.own || !isLive(packet)) return
        val peer = peerByBoard[from] ?: return
        // Only the edge out of an unreachable mark does anything; a busy contact's every packet must not cost a coroutine.
        if (direct.reachOf(peer, clock()) != LoraDirectPolicy.Reach.UNREACHABLE) return
        scope.launch { onBoardHeard(peer) }
    }

    /**
     * Whether [packet] came to us over the air just now, by the board kind's hearing rule ([BoardTraits.heardLive]): on
     * Meshtastic an RSSI (the NodeDB replay at every handshake has none), not an MQTT uplink, and an `rx_time`, when
     * present, within [HEARING_MAX_AGE_MS] (the to-phone queue replayed at reconnect). What the hearing hook and the
     * cover's lift both read.
     */
    private fun isLive(packet: BoardPacket): Boolean = traits.heardLive(packet, wallClock())

    /**
     * A pinned contact's board was heard announcing its key ([PlaneCore.onBoardKeyHeard]; on Meshtastic a `NodeInfo`,
     * ADR 2026-10.aajj): our board stores a key it lacks off one, so a missing-key mark on that peer lifts, and the newest
     * DM still without its ✓✓ goes again — once; if the board still has no key, its refusal marks the peer again with
     * nothing written. Never behind a timer.
     */
    private fun onBoardKeyHeard(from: BoardAddr) {
        val peer = peerByBoard[from] ?: return
        if (!direct.onKeyLearned(peer)) return
        log("lora direct key heard for $peer")
        publishStatus()
        scope.launch {
            val newest =
                source.unackedTo(peer, 1, untakenOnly = false, maxBytes = directBudget.plaintextBytes).firstOrNull() ?: return@launch
            if (enqueueResend(peer, newest, flushAfter = emptyList())) log("lora resend ${newest.frameId} -> $peer (key heard)")
        }
    }

    /**
     * The two covers for a compact DM (ADR 054, ADR 2026-09.y5f3), each counted under its own name; true means the
     * caller must not put a DM to [peerId] on air. A peer a higher-preference plane holds a **live link** to — links,
     * never sightings: a sighting is not a data path, and refusing on one would take away a far peer's only route
     * (ADR 044's field amendment) — or a peer a connected spool was recently a path to, gets the signed copy there for
     * free. A DM to us is the first case: it is already delivered.
     */
    private fun skipCovered(peerId: String): Boolean {
        if (peerId == selfIdCached || peerId in linkedPeers) {
            metrics.onLoraSkippedLinked()
            return true
        }
        if (peerId in covered) {
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
     * floor, a full board queue, a spent window and a busy band can hold a frame for a whole window — and the
     * answers move underneath it. Field-observed 2026-09-09: a phone that had been alone for hours met two
     * peers on Wi-Fi Aware, queued their receipts in the four seconds before the second link came up, and
     * then spent its entire 45-second allowance putting on the air what that link had already delivered. The
     * far side confirmed it, receiving one frame id over Wi-Fi Aware and the same one over LoRa ten minutes
     * later.
     *
     * Only [RideGate]'s own questions are asked, so this can never refuse a frame its path did not already
     * gate: the public post and the auto-reply carry none, and a resend or re-probe never carries the unreachable
     * hold. The "still unacked?" re-ask is last: it is the one that reads the database.
     */
    private suspend fun staleReason(frame: OutboundFrame): StaleAtSend? {
        val gate = frame.gate
        // The admit rule, for a frame fanned before our own id was read (ADR 2026-10.rp3s). The pacer waits for the
        // id, so this always has one to ask with.
        if (gate.authorId != null && !LoraFramePolicy.airs(gate.authorId, selfIdCached)) return StaleAtSend.NOT_OWN
        val to = gate.recipientId
        if (to != null) (coverReason(to) ?: directStaleReason(frame, to))?.let { return it }
        if (gate.freshUntil != null && wallClock() > gate.freshUntil) return StaleAtSend.STALE
        val unacked = gate.unackedFrameId
        if (unacked != null && !source.isUnacked(unacked)) return StaleAtSend.ACKED
        return null
    }

    /**
     * The covers, asked again at send: links, never sightings — the same reading as [skipCovered], and for the same
     * field reason.
     */
    private fun coverReason(to: String): StaleAtSend? =
        when {
            to == selfIdCached || to in linkedPeers -> StaleAtSend.LINKED
            to in covered -> StaleAtSend.INTERNET
            else -> null
        }

    /** A compact DM's own gates, asked again at send (ADR 2026-10.x9fa); none for a frame that never asked them. */
    private fun directStaleReason(
        frame: OutboundFrame,
        to: String,
    ): StaleAtSend? {
        if (!frame.gate.direct) return null
        val holder = boardHolders?.get(to)
        val addressed = frame.direct?.addr
        return when {
            currentConfig?.dms != true || holder == null || !holder.direct || holder.addr != addressed -> StaleAtSend.NOT_DIRECT
            hooks.directSlotRefused(currentConfig?.channelIndex) -> StaleAtSend.SLOT_ZERO
            frame.gate.reachHeld && direct.holds(to, clock()) -> StaleAtSend.HELD
            else -> null
        }
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
     * Puts this phone's user's post on the board's public channel — [PublicChannelSink], the Meshtastic room's outbound
     * half — or says why not: the kind's ([BoardKindHooks.postToPublicChannel], Stage 1c.1).
     */
    override suspend fun postToPublicChannel(body: String): PublicPostRefusal? = hooks.postToPublicChannel(body)

    // --- the pacer ---

    private suspend fun pacerLoop() {
        // Nothing may be taken off the queue until the last process's limiters are back: this is the one place all
        // outbound air passes through, so it is the one that has to wait.
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
            if (board.state.value !is BoardState.Ready) {
                board.state.first { it is BoardState.Ready }
                continue
            }
            val frame =
                pace.take(clock()).also {
                    // Reported after every take, admitted or not: a DM can flow while a room post is held, and it
                    // is the *held* frame that has to be visible — nothing else counts it until the queue fills and
                    // sheds it as an ordinary drop. Only after a real take because both counts clear on entry to
                    // `take` and nowhere else: reading them on a wake that took nothing re-reports the previous
                    // wake's holds.
                    repeat(pace.lastAirtimeHolds) { metrics.onLoraAirtimeHeld() }
                    repeat(pace.lastBandBrakes) { metrics.onLoraBandBraked() }
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
        // the slot guard ([slotRefusal]) stops Knit's own frames reaching the public channel by accident, while the
        // kind's own frames had their guards applied where they were queued (`postToPublicChannel`, the auto-reply),
        // where a refusal could still be counted and reported. All that is left for them is which channel and port.
        if (frame.destination != Destination.Knit) {
            hooks.sendKindFrame(frame) { message, purpose -> sendMessage(message, frame, purpose) }
            return
        }
        // The board went away between the take and here — a GATT drop, or the reconnect backoff still running.
        // The frame is owed either way, so it goes back on the queue: nothing else holds a copy, and the
        // pacer would otherwise eat one every `minGapMs` for the whole outage. Field-observed on a board whose
        // link flapped five times in twenty minutes: a 93-second backoff destroyed six queued frames, and the
        // log blamed the channel setup because this read a missing link as the wrong slot.
        val ready = board.state.value as? BoardState.Ready
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
        val skipped = slotRefusal(frame, ready, ch)
        if (skipped != null) {
            metrics.onLoraSuppressed()
            log("lora send skipped: $skipped")
            abandon(frame)
            return
        }
        // Resume where the board left off. A frame refused part-way is requeued whole, and re-sending the
        // fragments it already holds would both duplicate them on the air and book their airtime again — the
        // ledger only grows on a retry, so that inflates the hourly budget until it refuses the whole plane.
        val purpose = frame.direct?.let { SendPurpose.DirectDm(ch, it.addr) } ?: SendPurpose.KnitFrame(ch)
        for (message in frame.remaining) {
            // OWED (requeued, or held for a key) and ENDED alike stop the frame here; the next take resumes an owed one.
            if (sendMessage(message, frame, purpose) != FragmentOutcome.SENT) return
        }
        metrics.onLoraSent()
        if (frame.fragmented) metrics.onLoraFragSent()
        log("lora tx ${frame.label} parts=${frame.messages.size}")
    }

    /**
     * Why bound slot [ch] must not carry [frame], in the words `lora send skipped` logs, or null to send it: the kind's
     * reading of its channel table ([BoardKindHooks.checkSlot], Stage 1c.3). A kind with no answer is refused — a slot
     * nobody can vouch for is one Knit's cleartext frames must not reach.
     */
    private fun slotRefusal(
        frame: OutboundFrame,
        ready: BoardState.Ready,
        ch: Int,
    ): String? =
        when (val check = hooks.checkSlot(frame, ready, ch)) {
            null -> "slot $ch cannot be checked: the board kind has no answer for it"
            SlotCheck.Clear -> null
            is SlotCheck.Refused -> check.why
        }

    /**
     * Sends one fragment and says what became of its frame ([FragmentOutcome]): taken, still owed (requeued for a busy
     * board or a session that ended under it, or held for a missing key), or ended (a NAK, a give-up). A compact DM goes
     * to its board with `want_ack`, priced unsigned, every airing it may cost booked here and the unflown ones refunded on
     * its ack (ADR 2026-10.x9fa).
     */
    private suspend fun sendMessage(
        message: ByteArray,
        frame: OutboundFrame,
        purpose: SendPurpose,
    ): FragmentOutcome {
        // The board writes what the purpose says (ADR 2026-11.gsqu, Stage 1b.1) and stamps the NodeInfo stream as the
        // send is issued: a key streamed after it raced a `39`; one before is stale.
        val sent = board.send(message, purpose)
        val userSeq = sent.preSend
        val result = sent.result
        val refusal = traits.refusalOf(result)
        // Our own board holds no key for the destination's board and refused it before it aired (ADR 2026-10.aajj):
        // reported first as the queueStatus's res, its self-NAK later — or, rarely, the other way round. Held, never
        // given up: the add_contact path decides what becomes of it.
        if (refusal == RefusalClass.OWN_NO_KEY && frame.direct != null) {
            refusedHandle(result)?.let { handle ->
                onNoKey(frame, handle, userSeq)
                return FragmentOutcome.OWED
            }
        }
        return when (result) {
            is SendResult.Queued -> {
                pace.onQueueStatus(result.queue.free)
                onTaken(frame, message, result.id, userSeq)
                FragmentOutcome.SENT
            }

            // The write went out and no queueStatus came back in time. A broadcast is given up as ever; a compact DM may
            // well be on the air under the handle the session assigned, so it is booked and awaited like a taken one
            // rather than abandoned — releasing what was parked behind it would put a second send in flight.
            is SendResult.Timeout -> {
                if (frame.direct == null) {
                    log("lora tx ${frame.label} gave up: $result")
                    abandon(frame)
                    FragmentOutcome.ENDED
                } else {
                    log("lora tx ${frame.label} unconfirmed, awaited as id=${result.id}")
                    onTaken(frame, message, result.id, userSeq)
                    FragmentOutcome.SENT
                }
            }

            is SendResult.Nak -> {
                metrics.onLoraNak(result.reason.name)
                log("lora nak id=${result.id} reason=${result.reason} label=${frame.label}")
                if (refusal == RefusalClass.PACE) pace.onPaceRefusal(clock())
                abandon(frame) // a no-op once a fragment has flown
                FragmentOutcome.ENDED
            }

            SendResult.Busy -> {
                requeue(frame)
                FragmentOutcome.OWED
            }

            // The session ended with this unsent, or its write failed: the frame is owed, like one the link was already gone
            // for at [sendFrame] (`Timeout` is the answer for a write that likely went). It waits out the outage, resumes at
            // its cursor, and has its gates asked again when it flies. Nothing was booked, and a compact DM keeps what is
            // parked behind it parked.
            is SendResult.NotReady -> {
                requeue(frame)
                FragmentOutcome.OWED
            }

            else -> {
                log("lora tx ${frame.label} gave up: $result")
                abandon(frame)
                FragmentOutcome.ENDED
            }
        }
    }

    /** The handle of a send the board refused at once, or null for a result that refused nothing. */
    private fun refusedHandle(result: SendResult): UInt? =
        when (result) {
            is SendResult.Rejected -> result.id
            is SendResult.Nak -> result.id
            else -> null
        }

    /**
     * The board took [message] (or may have, on a timeout): book it, move the frame's cursor and, for a compact DM,
     * start waiting on its answer. A compact DM is booked for every airing it may cost, at once (ADR 2026-10.x9fa): a
     * window must not hand the retries of a send still in flight to the next frame's admission. Its ack refunds the
     * airings that did not fly.
     */
    private fun onTaken(
        frame: OutboundFrame,
        message: ByteArray,
        handle: UInt,
        userSeq: Long,
    ) {
        val now = clock()
        val send = frame.direct
        // At the frame's own price, the one its admission asked (OutboundFrame.price, #243): a broadcast at its slot's
        // cliff; a unicast — a compact DM and the auto-reply alike — unsigned and with the PKC overhead the firmware adds
        // to every PKI unicast (ADR 2026-10.x9fa's PKI amendment). Read once, so the flight's airing is the booked one.
        val price = frame.price(pace.airtime)
        if (send == null) {
            pace.airtime.record(message.size, now, price)
        } else {
            // The airings the frame carries, the number its admission asked (OutboundFrame.admissionSizes).
            repeat(send.airings) { pace.airtime.record(message.size, now, price) }
        }
        frame.onPartSent()
        send?.let { launchFlight(frame, it, handle, message, price, now, userSeq) }
        // The ledger just moved, and a send is the only thing that spends it — so republish here
        // rather than leave the chat's saturation notice and the radio screen's percentage waiting
        // on the 60 s linger sweep. Cheap: the pacer's 3 s floor bounds how often this can run, and
        // `LoraStatusRepository` reduces the snapshot to a threshold before any UI sees it.
        publishStatus()
    }

    private fun requeue(frame: OutboundFrame) {
        // Put back, never re-admitted: a requeue runs no supersede, or an older snapshot put back here would delete
        // the newer one queued while it was out (ADR 2026-09.xdm2).
        if (pace.requeue(frame) != LoraPacePolicy.Admission.ACCEPTED) metrics.onLoraDroppedQueue()
        abandonShed()
    }

    /**
     * Hands back what a frame that never flew was holding: its fan-out dedup slot, which records a transmission that
     * did not happen and would turn the next fan-out of the same bytes away. A compact DM that never flew lets go of
     * the offers parked behind it, through the offer's gates again — it was the one send its unconfirmed peer was
     * allowed, and no outcome will ever come for it (ADR 2026-10.x9fa). A no-op once a fragment has gone — that frame
     * did fly, in part. [LoraFanoutSeen.reopen] hands the slot back every time: a frame refused at send however often
     * has still never flown (#239). Called from every path that drops an unsent frame: the send-time re-ask,
     * a NAK or give-up on the first fragment, a missing config or a slot that is not Knit's, and the pacer's shed and
     * oversize evictions ([abandonShed]).
     */
    private fun abandon(frame: OutboundFrame) {
        if (frame.sentParts > 0) return
        frame.sigKey?.let(sigSeen::reopen)
        frame.direct?.let { send ->
            // Off the pacer's books first, or the replayed offers would see this frame still on its way and park again.
            pace.done(frame)
            direct.unpark(send.peerId).forEach { offer(send.peerId, it.frameId, it.sentAtMs, it.plaintext, it.kind) }
        }
    }

    /** [abandon]s whatever the pacer dropped unsent since the last look. */
    private fun abandonShed() {
        pace.drainShed().forEach(::abandon)
    }

    // --- inbound ---

    /**
     * One packet off the board, dispatched by the kind ([BoardKindHooks.route], Stage 1c.0): a compact DM is opened
     * ([onDirectPacket]) and its board heard only once delivered; every other packet is a hearing first ([noteHeard]),
     * then a Knit frame on the bound slot, a burned tag (counted `loraRetiredCtl`, never `UNKNOWN_TAG`, ADR
     * 2026-10.rp3s — the radio is still counted: a board in range is a board in range), or the kind's own traffic.
     */
    private fun onLoraPacket(packet: BoardPacket) {
        val bound = currentConfig?.channelIndex
        val route = hooks.route(packet, bound)
        when (route) {
            PacketRoute.DROPPED -> {
                return
            }

            PacketRoute.DIRECT -> {
                onDirectPacket(packet, bound)
                return
            }

            else -> {
                noteHeard(packet)
            }
        }
        when (route) {
            PacketRoute.KIND -> {
                hooks.onKindPacket(packet)
            }

            PacketRoute.KNIT -> {
                noteBoard(packet)
                onFramePacket(packet)
            }

            PacketRoute.RETIRED -> {
                noteBoard(packet)
                metrics.onLoraRetiredCtl()
            }

            else -> {}
        }
    }

    /** One inbound mesh frame off the air: reassemble, decode, dedup, and hand it to the router. */
    private fun onFramePacket(packet: BoardPacket) {
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
        val own = ownBoard()
        if (isPresenceEvidence(env, wallClock()) && (own == null || packet.from != own)) {
            noteReachable(Peer(env.senderId))
        }
        metrics.onLoraReceived()
        if (fragmented) metrics.onLoraReassembled()
        if (LoraFramePolicy.isDmForm(env)) metrics.onLoraDmReceived()
        _inbound.tryEmit(InboundFrame(wire, env, fromNodeId = env.senderId))
        log("lora rx ${env.type} id=${env.id} from ${env.senderId}")
    }

    /** Returns the complete frame for [packet]: itself if a whole `0x03`/`0x05` frame, else reassembled from `0x04` parts. */
    private fun reassemble(packet: BoardPacket): ByteArray? =
        when (packet.payload[0]) {
            FastFrameCodec.TAG_COMPACT, FastFrameCodec.TAG_TRANSCODED -> {
                packet.payload
            }

            FastFrameCodec.TAG_FRAG -> {
                val frag = FastFrameCodec.parseFragment(packet.payload) ?: return null
                reassembler.accept(packet.from ?: NoSender, frag)?.takeIf { it.isNotEmpty() && FastFrameCodec.isFrameTag(it[0]) }
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
    private fun noteBoard(packet: BoardPacket) {
        val from = packet.from ?: return // undecoded `from`; counting it would invent a radio
        if (from == ownBoard()) return // our own board's echo
        val heard = RxQuality(packet.rxSnr, packet.rxRssi, clock())
        boardsHeardAt.compute(from) { _, previous -> previous?.refreshedBy(heard) ?: heard }
        publishStatus()
    }

    /**
     * [peer] wrote a fresh frame that reached us over LoRa: author-keyed reach, lingering [REACHABLE_LINGER_MS]. Nothing
     * re-airs on it any more — the compact DM's own hearing hook ([onBoardHeard]) is what asks a board again, and it
     * keys on the board, not on who wrote what (ADR 2026-10.x9fa).
     */
    private fun noteReachable(peer: Peer) {
        val now = clock()
        lastHeardAt[peer.nodeId] = now
        heardPeers[peer.nodeId] = peer
        recomputeReachable(now)
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
            board.start(cfg.address)
        }
        publishStatus()
    }

    private fun onBoardState(state: BoardState) {
        _health.value =
            when (state) {
                is BoardState.Ready -> TransportHealth.Healthy
                is BoardState.Down -> state.health
            }
        if (state is BoardState.Ready) {
            maxPayload = state.payloadCap
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
            // The kind's half: how its board prices a packet, the `lora ready` line, the binding (Stage 1c.4).
            hooks.onReady(state)
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
     * test would pass on the unlocked code too. Keep the `board.state` read inside the lock.
     */
    private fun publishStatus() =
        synchronized(statusLock) {
            // Read inside the lock (ADR 2026-10.a239's amendment); the kind renders its half of the row from it.
            val boardState = board.state.value
            // The freshest reading among the radios still within the linger — the row's "last heard", now bounded
            // to radios this plane actually talks to. Empty once they all age out, which is the honest answer.
            val lastRx = boardsHeardAt.values.maxByOrNull { it.atMs }
            val now = clock()
            _status.value =
                hooks.statusOf(
                    boardState,
                    LoraStatus(
                        boardAddress = currentConfig?.address,
                        lastSnr = lastRx?.snr,
                        lastRssi = lastRx?.rssi,
                        queueFree = board.headroomNow,
                        queued = pace.pending,
                        heard = _reachable.value.size,
                        boardsHeard = boardsHeardAt.size,
                        battery = board.battery.value,
                        boardAir = board.boardAir.value,
                        airtime = pace.airtime.snapshot(now),
                        braked = pace.braked(now),
                        bandReadingAgeMs = pace.bandReadAt()?.let { now - it },
                        internetCovered = covered.size,
                        direct = direct.counts(now),
                    ),
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
        return buildString(n * 2) { for (i in 0 until n) append("%02x".format(Locale.ROOT, wire.sig[i])) }
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
         * The binding limit in practice, ahead of the window's half ceiling ([Ceiling.HALF], ADR 2026-10.ug4a), and
         * that ordering is the point:
         * a refusal a user can predict ("wait a moment") beats one that depends on what the rest of the plane
         * happened to be doing. It also bounds the worst case a bridge can inflict on a shared community band
         * — a whole pocket of Knit users is still one voice every 30 s, not one each.
         */
        const val PUBLIC_POST_FLOOR_MS = 30_000L

        const val INBOUND_BUFFER = 64

        /**
         * The fan-out dedup's hold for a frame with no freshness deadline ([LoraFramePolicy.freshnessExempt]): none
         * rides the fan-out today, every room post holds until its own deadline ([LoraFanoutSeen], #239).
         */
        const val SIG_TTL_MS = 10 * 60_000L
        const val SIG_KEY_BYTES = 8
        const val FRAG_CAP = 16
        const val FRAG_TIMEOUT_MS = 60_000L // LoRa parts are seconds apart; the NAN 5 s default would drop them
        const val FRAG_ID_MASK = 0xFFFF
        const val REACHABLE_LINGER_MS = 45 * 60_000L
        const val LINGER_SWEEP_MS = 60_000L

        // Limiters that outlive the process (LoraPlaneState).

        /**
         * How long the plane waits for its persisted limiters before transmitting anyway. Generous against a
         * cold DataStore read and still far inside the board's own BLE handshake, so in practice nothing ever
         * waits on it — it exists so that a store which never answers costs one window rather than the plane.
         */
        const val STATE_LOAD_TIMEOUT_MS = 5_000L

        /** How long a spend waits for its neighbours before the snapshot is written. */
        const val STATE_SAVE_DEBOUNCE_MS = 2_000L

        /**
         * How many frames the fan-out admits before our own id is read (ADR 2026-10.rp3s): enough for what a user types
         * through a Keystore backoff, too few for relayed re-fans to fill the 32-frame queue and shed it.
         */
        const val PRE_ID_ADMISSIONS = 4

        /** The most re-probe schedules carried across a restart, soonest kept — one per silent recipient. */
        const val STATE_REPROBE_CAP = 64

        /**
         * How many of our unacked DMs a hearing re-airs (ADR 2026-10.x9fa): the newest at once, the older two only after
         * the destination acks it — a board heard is not a phone that reads.
         */
        const val RESEND_LIMIT = 3

        /** How often a failed board-map source is asked again; the last map it gave stays in force meanwhile. */
        const val BOARD_MAP_RETRY_MS = 5 * 60_000L

        /**
         * How old a packet's `rx_time` may be and still be a hearing (ADR 2026-10.x9fa): older, the board heard it a
         * while ago and is replaying its to-phone queue at reconnect.
         */
        const val HEARING_MAX_AGE_MS = 3 * 60_000L

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
         * measured protobuf framing ([MeshtasticProto.PACKET_OVERHEAD]) — so `mtu - this` is the payload cap the
         * plane chunks to at a board's MTU. Not a hard one-ATT-write bound: the stated `hop_limit` (and a compact DM's
         * `want_ack`) add two bytes each past it, and the ToRadio characteristic takes up to 512 B by long write on
         * NimBLE and nRF52 boards, so a full packet's write is split by the stack and the cap stays where it was
         * measured. Was a hand-set 33 with 6 B of unaccounted slack; at the MTU-255 ESP32 boards that slack was the
         * difference between a one-packet v3 tick and two (ADR 059).
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

/** The reassembly key of a fragment whose board could not say who sent it: every such fragment shares one, as before. */
private object NoSender

/**
 * Whether a peer's mark keeps the resend after a hearing and the re-probe off the air: their board refused us, or ours
 * holds no key for theirs (ADR 2026-10.aajj). Asking again changes neither until the profile changes, a key is heard or
 * the mark lapses.
 */
private fun LoraDirectPolicy.Reach.keepsResendsOff(): Boolean =
    this == LoraDirectPolicy.Reach.REFUSED || this == LoraDirectPolicy.Reach.NO_KEY

/**
 * What a board reports about itself when its session comes up, for the profile to advertise: its node number and its
 * Curve25519 key (base64, off its own `NodeInfo`) when the number is the key's CRC32 — whether or not the board signs
 * (#243). The key verifies a signed post and lets a contact's phone teach its board ours (ADR 2026-10.aajj).
 */
internal data class BoardBinding(
    val nodeNum: UInt,
    val key: String?,
)
