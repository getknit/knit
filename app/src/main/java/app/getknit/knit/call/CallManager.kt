package app.getknit.knit.call

import app.getknit.knit.data.forward.ForwardRepository
import app.getknit.knit.data.message.CallRecord
import app.getknit.knit.fieldlog.FieldEvent
import app.getknit.knit.fieldlog.FieldRecorder
import app.getknit.knit.mesh.protocol.CallPayload
import app.getknit.knit.mesh.protocol.DirectInvite
import app.getknit.knit.mesh.protocol.FrameId
import app.getknit.knit.mesh.spool.ScopeRegistry
import app.getknit.knit.mesh.spool.TurnHosts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * Where one call stands. [Calling]/[Ringing] are the caller's; [Alerting] the callee's; the rest both sides'.
 * [Reconnecting] is a connected call whose path was lost, inside its reconnect window (§3.8).
 */
enum class CallPhase { Calling, Ringing, Alerting, Connecting, Connected, Reconnecting, Ended }

/** The one call this phone is in (or last was), as the call surfaces draw it. */
data class CallState(
    val id: String,
    val peerId: String,
    val outgoing: Boolean,
    val phase: CallPhase,
    val startedAt: Long,
    val connectedAt: Long? = null,
    val muted: Boolean = false,
    // Why it ended (a [CallPayload] REASON_*), once [phase] is [CallPhase.Ended].
    val reason: Int? = null,
    // Once ended: the peer's own terminal phase ended it, and it was an incoming call that stopped ringing unanswered
    // (C-3.4-2) — what the system is told it was (§3.13).
    val endedByPeer: Boolean = false,
    val missed: Boolean = false,
    // §3.11: a walkie-talkie session — no media; the user holds to talk and each burst streams over the mesh in chunks.
    val walkie: Boolean = false,
    // The walkie took over a live call whose path was lost or never came.
    val degraded: Boolean = false,
    // §2.7: the peer's OFFER or ANSWER said it holds a cast ticket, so a spool that sells casts carries ours too.
    val peerCastTicketed: Boolean = false,
    // C-3.11-10: the peer's walkie OFFER or ANSWER said it reports each burst it hears (`CallPayload.hr`).
    val peerReportsHeard: Boolean = false,
    // C-3.3-5: an outgoing call with no RINGING back, and nothing this phone can see reaches the peer ([CallReach]).
    // Held once the call ends for that reason, so the screen can say why.
    val unreachable: Boolean = false,
    // C-3.3-6: an outgoing call that something reaches, yet no RINGING came back within [CallTimings.ringingMs]: the
    // link is unproven — the peer's phone may hear us and not be heard (a stack wedged outbound).
    val unproven: Boolean = false,
)

/**
 * One chunk of the peer's walkie burst in the connected session [callId] (§3.11), stamped [at] on this phone's clock:
 * its frame was [frameBytes] and stamped [sentAt] by its sender (0: unknown), for the walkie's diagnostics, and a relay
 * cast it rather than a radio when [viaRelay] (C-3.11-10).
 */
class TalkIn(
    val callId: String,
    val chunk: TalkChunk,
    val at: Long,
    val sentAt: Long = 0L,
    val frameBytes: Int = 0,
    val viaRelay: Boolean = false,
)

/** The peer's [report] of a burst of ours in the connected walkie session [callId] (C-3.11-10). */
class TalkReportIn(
    val callId: String,
    val report: TalkReport,
)

/**
 * Why [CallManager.start] dialled nothing. [NO_LIVE]: this build takes no live calls ([CallMedia.None]) and the call
 * could not be a walkie — the peer runs none, or this build runs none either. [NO_RELAY]: "Relay all calls" is on and
 * nothing would issue this phone TURN, so a live call could gather no candidate at all (C-3.7-3).
 */
enum class CallRefusal { BUSY, SELF, NO_LIVE, NO_RELAY }

/**
 * A call that rang out unanswered, or never rang: its offer arrived stale (C-3.4-2), or "Relay all calls" had nothing
 * to relay through ([noRelay], C-3.7-3), which the notification says.
 */
class MissedCall(
    val peerId: String,
    val at: Long,
    val noRelay: Boolean = false,
)

/**
 * One call's record for the thread (§3.12), emitted once when the call ends on this phone — or, for an offer that
 * never rang, when it is recorded as missed. [startedAt] is this phone's clock when the call began here.
 */
class CallLogged(
    val peerId: String,
    val startedAt: Long,
    val record: CallRecord,
)

/** Every timer a call runs on (docs/VOICE_CALLS.md §3.3–3.4); injectable so tests run them in virtual time. */
data class CallTimings(
    // C-3.3-1: the caller withdraws with CANCEL timeout after this long without an ANSWER.
    val ringOutMs: Long = 45_000L,
    // C-3.3-5: a caller with no RINGING that has seen nothing reach the peer for this long withdraws with CANCEL no_route.
    // Long enough for a spool to fan an OFFER out and a RINGING back, or a Bluetooth dial to land.
    val unreachMs: Long = 15_000L,
    // C-3.3-5: how often a caller with no RINGING looks again at whether anything reaches the peer.
    val reachLookMs: Long = 2_000L,
    // C-3.3-6: a RINGING over a live link is back within a second or two; a caller still without one by now calls the
    // path unproven, and a ring-out with none ends no_route rather than unanswered.
    val ringingMs: Long = 8_000L,
    // How long the callee alerts on its own: the caller's CANCEL can be lost, so a ring never outlives this.
    val alertMs: Long = 50_000L,
    // C-3.3-3: Connecting that has not reached ICE connected by now ends with END no_route.
    val connectMs: Long = 20_000L,
    // §3.8: how long a connected call whose path was lost has to come back before it ends no_route. The caller's
    // window runs from the loss; the callee's restarts with each restart it answers.
    val reconnectWindowMs: Long = 20_000L,
    // §3.8: the caller's first ICE restart waits this long after the loss, so a blip ICE heals on its own costs no frame.
    val restartDelayMs: Long = 2_000L,
    // §3.8: the caller's second and last restart, this long after the first, while the call is still lost.
    val restartRetryMs: Long = 8_000L,
    // C-3.4-1: an OFFER whose signed sentAt is further than this from now never alerts.
    val staleOfferMs: Long = 60_000L,
    // §3.10: a callee's "joining" older than this on arrival is not acted on — custody can hand the caller a RINGING
    // that was lost to the callee's own radio hand-over long after the callee gave its join up.
    val directCommitMaxAgeMs: Long = 8_000L,
    // C-3.3-3 for a call whose Wi-Fi Direct leg is on its way: a join takes ~6 s, a retried one longer.
    val directConnectMs: Long = 30_000L,
)

/**
 * The state machine of a 1:1 voice call (experimental; docs/VOICE_CALLS.md §3, ADR 2026-10.3tee). Pure — the mesh
 * ([CallSignals], [TurnSource]) and WebRTC ([CallMedia]) are seams, so it runs two-sided on the JVM in
 * `CallManagerTest`, like `transfer/TransferManager`.
 *
 * One call at a time. Signaling is a sealed `CTL_CALL` DM per phase, which can arrive late, twice or out of order, so
 * every phase is idempotent on `(id, ph, gen)`, an offer alerts only inside `sentAt ± 60 s`, and a terminal phase for
 * a call we never saw is remembered so its late offer never rings. Those tombstones outlive the process through
 * [tombs] (C-3.4-3): a spool keeps a DM scope's frames for 48 h, and a process death in between must not ring them.
 *
 * **[relayAll]** is the user's "Relay all calls" (C-3.7-3), read as each call opens its media: TURN only, no host or
 * server-reflexive candidate in anything we send, and no Wi-Fi Direct leg, whose pair would show the peer our address.
 * With nothing to relay through — no source would issue TURN ([TurnSource.turnAvailable]) and no issued credential is
 * still live — such a call could never connect, so it is never begun: [start] refuses a live call
 * ([CallRefusal.NO_RELAY]) and [CallKind.AUTO] places a walkie where the peer runs one, a live offer is declined
 * `no_route` without ringing and left as a missed call, and a fetch that still yields no TURN ends the call before its
 * OFFER or ANSWER goes out.
 *
 * **[onSignal] never waits on the network or the media.** It runs on the inbound path — a spool connection's only
 * reader, or the router's single collector — so it decides under [mutex], sends the small replies (RINGING, a busy
 * DECLINE) and returns; a TURN fetch and every media step run on [scope]. A `turncred` the spool sends back could
 * otherwise never be read by the reader that is waiting for it.
 *
 * **A call between neighbours can ride a Wi-Fi Direct group** ([direct], §3.10): the caller's OFFER names a group it
 * will host, the callee answers that it is joining, and the caller hosts only then, so a callee that cannot (or an
 * older build) costs nothing. The group is one more network the media prefers; the Internet path, when there is one,
 * stays its fallback. While the leg is up its lane carries every signal beside the mesh, so a pair whose only mesh
 * link was the Wi-Fi Aware radio the group took over still hears ANSWER, CANDS and END.
 *
 * **A call with no live path is a walkie-talkie** ([walkie], §3.11, ADR 2026-10.79yb): one dialled where no live path
 * looks possible offers a walkie session, which carries no media at all, and a live call whose path is lost (or never
 * came) drops to one instead of ending — when both sides said they can, by the walkie bit on the OFFER and the ANSWER.
 * That drop is parked behind [dropToWalkie] (off): a peer off every radio got a session that could carry nothing, so
 * a lost live call ends no_route, and neither OFFER nor ANSWER carries the bit.
 *
 * **A build may take one kind only** (ADR 2026-10.swvp): with [media] [CallMedia.None] it takes no live calls, so every
 * call it places is a walkie ([CallRefusal.NO_LIVE] where that cannot be) and a live offer is declined as media it
 * cannot play, as a build with no walkie declines a walkie offer.
 *
 * **The person may take no incoming calls of a kind** ([takesIncoming], C-3.3-7): an offer of a kind they switched
 * off is answered `DECLINE rsn=timeout`, the caller's "No answer", without ringing, a record or a missed call — busy or
 * not, so it says nothing about a call in progress. A glare offer is still taken: this phone was calling that peer.
 *
 * **A call to a peer nothing reaches says so** ([reach], C-3.3-5): until a RINGING comes back, the caller looks every
 * [CallTimings.reachLookMs] at whether any link, mesh path, Wi-Fi Aware sighting or shared spool reaches the peer, flags
 * the call [CallState.unreachable] while none does, and withdraws it with `CANCEL no_route` once none has for
 * [CallTimings.unreachMs] — never on the inbound path, and a RINGING ends the watch. One that something reaches but
 * that has no RINGING after [CallTimings.ringingMs] is [CallState.unproven] (C-3.3-6), and if none ever comes its
 * ring-out ends no_route: with no RINGING there is no evidence the peer's phone rang at all.
 *
 * **Every call this phone sees leaves a line in the field log** ([field], ADR 2026-10.a239's amendment): a
 * [FieldEvent.CallStart] as it begins and one [FieldEvent.CallEnd] as it ends — an offer refused or never rung
 * included — written beside the thread's record, never instead of it, and never on anything slower than a queue.
 *
 * **A call relays through one TURN server, the nearest** ([ranking], docs/SPOOL_PROTOCOL.md C-7.6-51 to C-7.6-54):
 * each spool that names its servers is asked for the one [TurnRanking] ranks best, and of the credentials that come
 * back from such spools the media is handed one. Where that server refuses the allocation (486 or 508) before this
 * side's OFFER or ANSWER has gone out, and gave it no relay, the side gathers once more on the next server and sends
 * that description instead; after it, a refusal only marks the server. A call over spools that name no servers keeps
 * up to two, as before.
 *
 * **A connected call whose path is lost reconnects by ICE restart** (§3.8): it is [CallPhase.Reconnecting] for
 * [CallTimings.reconnectWindowMs], in which the caller — only the caller, so two restarts never cross — sends
 * `OFFER gen+1` twice at most, and the callee answers each under the same call id without ringing. A dead peer, a
 * reset session and a dead network all end the same way: the window runs out and the call ends no_route.
 */
@Suppress("LargeClass", "TooManyFunctions") // one call's whole state machine, direct leg and reach watch; two-sided tested
class CallManager(
    private val signals: CallSignals,
    private val turn: TurnSource,
    private val media: CallMedia,
    private val scope: CoroutineScope,
    private val selfId: suspend () -> String,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val timings: CallTimings = CallTimings(),
    private val newId: () -> String = { FrameId.new() },
    private val log: (String) -> Unit = {},
    private val direct: CallDirect = CallDirect.None,
    private val walkie: CallWalkie = CallWalkie.None,
    private val dropToWalkie: Boolean = false,
    private val relayAll: () -> Boolean = { false },
    private val tombs: CallTombJournal = CallTombJournal.None,
    private val reach: CallReach = CallReach.Assumed,
    private val takesIncoming: (walkie: Boolean) -> Boolean = { true },
    private val field: FieldRecorder = FieldRecorder.NONE,
    private val ranking: TurnRanking = TurnRanking(),
) : CallControl {
    private class Live(
        val id: String,
        val peerId: String,
        val outgoing: Boolean,
        val startedAt: Long,
        var phase: CallPhase,
        // The caller's SDP, on the callee's side.
        val remoteOffer: String? = null,
    ) {
        var session: CallSession? = null
        var relay = false
        var connectedAt: Long? = null
        var muted = false
        var reason: Int? = null
        var byPeer = false
        var missed = false

        // The one phase timer — ring-out, alert, connect or the reconnect window; arming one replaces the last.
        var timer: Job? = null

        // §3.8: the negotiation generation held — the caller's last restart, the callee's last answered one.
        var gen = 0

        // The callee's TURN prefetch, started when the offer alerts so answering does not wait for it.
        var servers: Deferred<IceSet>? = null

        // C-7.6-53/54: the one ranked TURN server the media gathers from, every credential the fetch brought, a
        // refusal of [turn] seen before our description left (its code), whether this side has tried to move on and
        // did, whether its field line is written, and the session a move retired, whose events are dropped.
        var ice: List<TurnServer> = emptyList()
        var turn: TurnServer? = null
        var pool: List<TurnServer> = emptyList()
        var turnRefused: Int? = null
        var turnMoved = false
        var turnFellBack = false
        var turnRecorded = false
        var retired: CallSession? = null

        // Whether our OFFER/ANSWER has gone out; late candidates wait for it, so a CANDS never overtakes it.
        var described = false
        var lateCands: List<String>? = null
        var candsSent = false

        // Whether the peer's SDP is applied; a CANDS that beats it waits here (bounded).
        var remoteApplied = false
        val earlyCands = mutableListOf<String>()

        // §3.10: the group this call may ride — the one we offered to host, or the caller's on the callee — and
        // whether this side has committed to it (the callee is joining, the caller hosting). One attempt per call.
        var invite: DirectInvite? = null
        var directCommitted = false

        // §3.11: this is a walkie session (no media); it became one from a live call; this side offered the drop to
        // one; and both sides can take it, so a lost path degrades rather than ends.
        var walkie = false
        var degraded = false
        var offeredWalkie = false
        var walkieOk = false

        // §2.7: the peer's last OFFER or ANSWER carried `ct`.
        var peerCastTicketed = false

        // C-3.11-10: the peer's walkie OFFER or ANSWER carried `hr`.
        var peerReportsHeard = false

        // C-3.3-5: the caller's look at whether anything reaches the peer, until a RINGING; and what it last saw.
        var reachWatch: Job? = null
        var unreachable = false
        var unproven = false

        // For the field log's line (FieldEvent.CallEnd): the caller's first proof the peer heard it, the answer, the
        // times a connected call lost its path, and what its media gathered from.
        var ringingAt: Long? = null
        var answeredAt: Long? = null
        var drops = 0
        var turnServers: Int? = null
        var stunUrls: Int? = null
    }

    private class Send(
        val peerId: String,
        val payload: CallPayload,
    )

    // What a call's media gathers from ([servers]: at most one ranked TURN server, else up to two, then the STUN entry)
    // and every TURN credential the fetch brought back ([pool]), which C-7.6-54's next server is looked for in.
    private class IceSet(
        val servers: List<TurnServer>,
        val pool: List<TurnServer>,
    )

    // A terminal phase seen for a call id (its own [CallPayload.ph] and reason), and when on this phone's clock.
    private class Tomb(
        val ph: Int,
        val rsn: Int?,
        val at: Long,
    )

    private val mutex = Mutex()
    private var live: Live? = null
    private val seen = bounded<String, Unit>()
    private val tombstones = bounded<String, Tomb>()

    private val _state = MutableStateFlow<CallState?>(null)

    /** The current (or last) call, or null before the first. */
    override val state: StateFlow<CallState?> = _state.asStateFlow()

    private val missedCalls = Channel<MissedCall>(MISSED_BUFFER, BufferOverflow.DROP_OLDEST)

    /** Calls this phone missed, for the one collector that notifies them; buffered so none is lost before it subscribes. */
    val missed: Flow<MissedCall> = missedCalls.receiveAsFlow()

    private val loggedCalls = Channel<CallLogged>(LOGGED_BUFFER, BufferOverflow.DROP_OLDEST)

    /** Every call as it ends here, for the one collector that writes its row in the thread (§3.12, `call/CallLog`). */
    val logged: Flow<CallLogged> = loggedCalls.receiveAsFlow()

    private val talkIn = Channel<TalkIn>(TALK_BUFFER, BufferOverflow.DROP_OLDEST)

    /** The peer's walkie chunks for the connected session (§3.11), for the one collector that plays them. */
    val talk: Flow<TalkIn> = talkIn.receiveAsFlow()

    private val reportsIn = Channel<TalkReportIn>(TALK_BUFFER, BufferOverflow.DROP_OLDEST)

    /** The peer's reports of our bursts in the connected walkie session (C-3.11-10), for the one collector that judges them. */
    val talkReports: Flow<TalkReportIn> = reportsIn.receiveAsFlow()

    /** When a call between neighbours raises its Wi-Fi Direct group (§3.10); a debug-bridge toggle, in memory. */
    @Volatile
    var directMode: DirectMode = DirectMode.PREWARM

    // The last TURN credentials a spool issued, for a call dialled while the spool is unreachable (see [servers]).
    @Volatile
    private var lastServers: List<TurnServer> = emptyList()

    // C-3.4-3 across a process death: a tombstone added since the last save, for the one writer to [tombs].
    private val tombsDirty = Channel<Unit>(Channel.CONFLATED)

    // The tombstones [tombs] kept from an earlier process, merged in once. [onSignal] waits for it before its first
    // decision, so a late OFFER for a call that ended before a restart is judged against them.
    private val restored: Deferred<Unit> = scope.async { restoreTombs() }

    init {
        // What a call's lane carries in: the same signals as the mesh (deduplicated with their mesh copies), and the
        // peer's group candidates. Declared last, so the collector never sees a field before it is set.
        scope.launch { direct.inbound.collect { onLane(it) } }
        if (tombs !== CallTombJournal.None) {
            scope.launch {
                restored.await()
                for (ignored in tombsDirty) saveTombs()
            }
        }
    }

    // ---- user actions ----

    /**
     * Dials [peerId], as a walkie session when [kind] says so or, for [CallKind.AUTO], when the peer runs one and no
     * live path looks possible (§3.11). Null once the call is under way; the setup runs on [scope], never the caller's.
     */
    suspend fun start(
        peerId: String,
        kind: CallKind = CallKind.AUTO,
    ): CallRefusal? {
        if (peerId == selfId()) return CallRefusal.SELF
        val asWalkie =
            when (kind) {
                CallKind.LIVE -> false
                CallKind.WALKIE -> walkie.enabled
                CallKind.AUTO -> prefersWalkie(peerId)
            }
        if (!asWalkie && !liveEnabled) return CallRefusal.NO_LIVE
        if (!asWalkie && relayBlocked()) return CallRefusal.NO_RELAY
        val l =
            mutex.withLock {
                if (live?.phase?.let { it != CallPhase.Ended } == true) return CallRefusal.BUSY
                Live(id = newId(), peerId = peerId, outgoing = true, startedAt = clock(), phase = CallPhase.Calling).also {
                    it.walkie = asWalkie
                    live = it
                    armRingOut(it)
                    watchReach(it)
                    publish()
                    began(it)
                }
            }
        log("call ${l.id} to ${l.peerId}${if (asWalkie) " (walkie)" else ""}")
        setup(l) { if (asWalkie) dialWalkie(l) else dial(l) }
        return null
    }

    /** Whether this build runs the walkie at all (§3.11): the chat offers a mesh call beside the live one. */
    val walkieEnabled: Boolean get() = walkie.enabled

    /** Whether this build takes live calls at all (ADR 2026-10.swvp): the chat offers a live call beside the mesh one. */
    val liveEnabled: Boolean get() = media !== CallMedia.None

    /**
     * Whether a call to [peerId] is better placed as a walkie right now (§3.11): the peer runs one and no live path
     * looks possible — none ever does in a build without live calls. What [CallKind.AUTO] decides on, and which call
     * the chat puts first.
     */
    suspend fun prefersWalkie(peerId: String): Boolean =
        walkie.enabled && walkie.capable(peerId) && (!liveEnabled || relayBlocked() || !walkie.liveLikely(peerId))

    /**
     * C-3.7-3: "Relay all calls" is on and a live call would have nothing to relay through — no source would issue TURN
     * now and no credential issued before is still live (what [fallback] would reuse). Asks nothing; safe on the
     * inbound path.
     */
    private fun relayBlocked(): Boolean = relayAll() && !turn.turnAvailable() && liveCredentials().isEmpty()

    /** The last issued TURN credentials that can still allocate, by their coturn expiry in the user name. */
    private fun liveCredentials(): List<TurnServer> {
        val nowS = clock() / MS_PER_S
        return lastServers.filter { it.expiresS() - CREDENTIAL_MARGIN_S > nowS }
    }

    // A coturn REST credential's expiry, in Unix seconds, from its user name (S-7.6-6); 0 when it carries none.
    private fun TurnServer.expiresS(): Long = user.substringBefore(':').toLongOrNull() ?: 0L

    /** Answers the call [id] is alerting for; false when it no longer is. */
    override suspend fun answer(id: String): Boolean {
        val l =
            mutex.withLock {
                val l = live?.takeIf { it.id == id && !it.outgoing && it.phase == CallPhase.Alerting } ?: return false
                l.answeredAt = clock()
                if (l.walkie) {
                    connectWalkie(l)
                } else {
                    l.phase = CallPhase.Connecting
                    armConnect(l)
                    publish()
                }
                l
            }
        setup(l) { if (l.walkie) answerWalkie(l) else answerCall(l) }
        return true
    }

    /** Refuses the call [id] is alerting for, telling the peer [reason] (busy when the system refused it, §3.13). */
    override suspend fun decline(
        id: String,
        reason: Int,
    ) {
        val sends =
            mutex.withLock {
                val l = live?.takeIf { it.id == id && !it.outgoing && it.phase == CallPhase.Alerting } ?: return
                finish(l, reason, CallPayload.PHASE_DECLINE)
            }
        deliver(sends)
    }

    /** Ends the current call, whatever its phase: a CANCEL before an answer, a DECLINE while alerting, else END. */
    override suspend fun hangUp() {
        val sends =
            mutex.withLock {
                val l = live?.takeIf { it.phase != CallPhase.Ended } ?: return
                finish(l, CallPayload.REASON_USER, endPhase(l))
            }
        deliver(sends)
    }

    override suspend fun setMuted(muted: Boolean) =
        mutex.withLock {
            val l = live?.takeIf { it.phase != CallPhase.Ended } ?: return@withLock
            l.muted = muted
            l.session?.setMuted(muted)
            publish()
        }

    /** The live call's selected candidate pair, `local/remote` (e.g. `relay/host`), for the debug bridge. */
    suspend fun route(): String? = mutex.withLock { live?.takeIf { it.phase != CallPhase.Ended }?.session }?.route()

    // ---- the mesh's side ----

    /**
     * One `CTL_CALL` from [senderId], an accepted contact (the pipeline checks). Returns without waiting on anything
     * slow: its one wait is the process's first, for the tombstones [tombs] kept, a read of a local file.
     */
    suspend fun onSignal(
        senderId: String,
        payload: CallPayload,
        sentAt: Long,
    ) {
        val me = selfId()
        restored.await()
        val sends =
            mutex.withLock {
                // C-3.3-4: custody and the spool both re-deliver; the second copy changes nothing.
                if (seen.put("${payload.id}/${payload.ph}/${payload.gen}", Unit) != null) return
                when (payload.ph) {
                    CallPayload.PHASE_OFFER -> onOffer(senderId, payload, sentAt, me)
                    CallPayload.PHASE_RINGING -> onRinging(senderId, payload, sentAt)
                    CallPayload.PHASE_ANSWER -> onAnswer(senderId, payload, sentAt)
                    CallPayload.PHASE_DECLINE, CallPayload.PHASE_CANCEL, CallPayload.PHASE_END -> onTerminal(senderId, payload)
                    CallPayload.PHASE_CANDS -> onCands(senderId, payload)
                    else -> emptyList()
                }
            }
        deliver(sends)
    }

    /**
     * One chunk of [senderId]'s walkie burst ([TalkWire], a `talk` frame's opened plaintext, §3.11): passed on only
     * while this phone's call is a connected walkie session with that peer and the chunk names it. Returns at once; a
     * talk frame is never re-served, and the player orders and dedups by burst and chunk. [sentAt] and [frameBytes]
     * are the frame's, measured for the diagnostics only; [viaRelay] is whether a spool cast it (C-3.11-10). A report of
     * one of our bursts ([TalkWire.decodeReport]) goes to [talkReports] under the same rule instead.
     */
    suspend fun onTalk(
        senderId: String,
        bytes: ByteArray,
        sentAt: Long = 0L,
        frameBytes: Int = 0,
        viaRelay: Boolean = false,
    ) {
        TalkWire.decodeReport(bytes)?.let { (tag, report) ->
            sessionWith(senderId, tag)?.let { reportsIn.trySend(TalkReportIn(it, report)) }
            return
        }
        val (tag, chunk) = TalkWire.decode(bytes) ?: return
        val id = sessionWith(senderId, tag) ?: return
        talkIn.trySend(TalkIn(id, chunk, clock(), sentAt, frameBytes, viaRelay))
    }

    // The connected walkie session with [senderId] that a chunk or report tagged [tag] names, if there is one.
    private suspend fun sessionWith(
        senderId: String,
        tag: Int,
    ): String? =
        mutex.withLock {
            live?.takeIf { it.peerId == senderId && it.walkie && it.phase == CallPhase.Connected && TalkWire.tagOf(it.id) == tag }?.id
        }

    @Suppress("ReturnCount") // a ladder of refusals ahead of the one admit
    private fun onOffer(
        sender: String,
        p: CallPayload,
        sentAt: Long,
        me: String,
    ): List<Send> {
        if (p.gen != 0) return onRestartOffer(sender, p, sentAt)
        val now = clock()
        if (neverRings(sender, p, sentAt, now)) return emptyList()
        val sdp = p.sdp
        // §3.11: a walkie offer carries no media to vet; a build that runs no walkie declines it as one it cannot play, and
        // a build that takes no live calls declines a live offer the same way.
        val media =
            if (isWalkieOnly(p)) {
                walkie.enabled
            } else {
                liveEnabled && p.m and CallPayload.MEDIA_AUDIO != 0 && sdp != null && SdpGuard.accepts(sdp)
            }
        if (!media) {
            bury(p.id, CallPayload.PHASE_DECLINE, CallPayload.REASON_MEDIA)
            refused(sender, p, CallEndReason.MEDIA)
            return listOf(Send(sender, CallPayload(id = p.id, ph = CallPayload.PHASE_DECLINE, rsn = CallPayload.REASON_MEDIA)))
        }
        val current = live?.takeIf { it.phase != CallPhase.Ended }
        declinedUnrung(sender, p, current, now)?.let { return it }
        current?.let { return onOfferWhileLive(it, sender, p, sdp, me, now) }
        val l = Live(p.id, sender, outgoing = false, startedAt = now, phase = CallPhase.Alerting, remoteOffer = sdp)
        live = l
        takeKind(l, p)
        l.peerCastTicketed = p.ct
        l.peerReportsHeard = p.hr
        if (!l.walkie) {
            l.invite = directInviteOf(p)
            l.servers = scope.async { servers(sender) }
        }
        armTimer(l, timings.alertMs) { finish(it, CallPayload.REASON_TIMEOUT, missed = true) }
        began(l)
        // §3.10: join the caller's group while this rings, so an answer connects over it at once. The RINGING that
        // says so leaves before the join takes the radio from the mesh (the leg waits out a grace first).
        val joining = directMode == DirectMode.PREWARM && commitJoin(l, prewarm = true)
        publish()
        log("call ${p.id} from $sender: alerting${if (joining) ", joining its group" else ""}${if (l.walkie) " (walkie)" else ""}")
        return listOf(Send(sender, CallPayload(id = p.id, ph = CallPayload.PHASE_RINGING, dx = directVerdict(l))))
    }

    /** An offer this phone declines without ringing — a kind switched off, or nothing to relay through — or null. */
    private fun declinedUnrung(
        sender: String,
        p: CallPayload,
        current: Live?,
        now: Long,
    ): List<Send>? = switchedOff(sender, p, current) ?: relayRefused(sender, p, current, now)

    /**
     * C-3.3-7: the no-answer DECLINE for an offer of a kind the person switched off, or null to go on. Busy or not, so
     * the answer says nothing about a call in progress; a glare offer ([current] our own call to [sender]) is taken.
     */
    private fun switchedOff(
        sender: String,
        p: CallPayload,
        current: Live?,
    ): List<Send>? {
        if (takesIncoming(isWalkieOnly(p)) || (current != null && isGlare(current, sender))) return null
        bury(p.id, CallPayload.PHASE_DECLINE, CallPayload.REASON_TIMEOUT)
        refused(sender, p, CallEndReason.OFF)
        log("call ${p.id} from $sender: ${if (isWalkieOnly(p)) "mesh" else "live"} calls are off; no answer")
        return listOf(Send(sender, CallPayload(id = p.id, ph = CallPayload.PHASE_DECLINE, rsn = CallPayload.REASON_TIMEOUT)))
    }

    /**
     * C-3.7-3: a live offer this phone could never connect, with "Relay all calls" on and nothing to relay through. It
     * never rings: declined `no_route`, the caller's "No route between the phones", and left as a missed call (as a
     * stale offer is, C-3.4-2), so the person sees who tried and, calling back, why it cannot connect. Null to go on,
     * and while a call is live, which answers busy or glare first.
     */
    private fun relayRefused(
        sender: String,
        p: CallPayload,
        current: Live?,
        now: Long,
    ): List<Send>? {
        if (current != null || isWalkieOnly(p) || !relayBlocked()) return null
        bury(p.id, CallPayload.PHASE_DECLINE, CallPayload.REASON_NO_ROUTE)
        missedCalls.trySend(MissedCall(sender, now, noRelay = true))
        loggedCalls.trySend(CallLogged(sender, now, CallRecord(id = p.id, outgoing = false, walkie = false, missed = true)))
        refused(sender, p, CallEndReason.NO_ROUTE, missed = true)
        log("call ${p.id} from $sender: relay all calls, but no TURN server; declined")
        return listOf(Send(sender, CallPayload(id = p.id, ph = CallPayload.PHASE_DECLINE, rsn = CallPayload.REASON_NO_ROUTE)))
    }

    /** §3.11: an offer with the walkie bit and no audio is a walkie session; with both, a live call that may drop to one. */
    private fun isWalkieOnly(p: CallPayload): Boolean = p.m and CallPayload.MEDIA_AUDIO == 0 && p.m and CallPayload.MEDIA_WALKIE != 0

    /** The callee's side of §3.11: what [p] offered, and whether this build takes the drop to a walkie it offers. */
    private fun takeKind(
        l: Live,
        p: CallPayload,
    ) {
        l.walkie = isWalkieOnly(p)
        l.walkieOk = dropToWalkie && !l.walkie && walkie.enabled && p.m and CallPayload.MEDIA_WALKIE != 0
    }

    /** What a live OFFER or ANSWER says it carries: audio, and the walkie bit when this side can drop to one (§3.11). */
    private fun liveMedia(walkieOk: Boolean): Int = CallPayload.MEDIA_AUDIO or (if (walkieOk) CallPayload.MEDIA_WALKIE else 0)

    /** `CallPayload.ct` for an OFFER or ANSWER carrying media [m]: only beside the walkie bit, and only when it is true. */
    private fun castFlag(m: Int): Boolean = m and CallPayload.MEDIA_WALKIE != 0 && walkie.castTicketed()

    /** A live OFFER or ANSWER [p] with the media it says it carries ([liveMedia]) and the cast flag beside it (§2.7). */
    private fun withLiveMedia(
        p: CallPayload,
        walkieOk: Boolean,
    ): CallPayload = liveMedia(walkieOk).let { m -> p.copy(m = m, ct = castFlag(m)) }

    /** [p]'s Wi-Fi Direct invite, when this side may take it up at all and every part of it is usable. */
    private fun directInviteOf(p: CallPayload): DirectInvite? = p.dw?.takeIf { !relayAll() && it.isUsable() }

    /** The callee's side: joins [l]'s group now, when it was offered one and this phone can. True when it did. */
    private fun commitJoin(
        l: Live,
        prewarm: Boolean,
    ): Boolean {
        val invite = l.invite ?: return false
        if (l.directCommitted || !direct.canJoin(l.peerId, prewarm)) return false
        l.directCommitted = true
        direct.join(l.id, l.peerId, invite)
        return true
    }

    private fun directVerdict(l: Live): Int? = if (l.directCommitted) CallPayload.DIRECT_JOINING else null

    /**
     * The caller's side: hosts [l]'s group on the callee's first fresh "joining" ([p] a RINGING or an ANSWER). Only
     * then: an older build, a callee that could not join, or a glare loser never says it, and nothing is raised.
     */
    private fun commitHost(
        l: Live,
        p: CallPayload,
        sentAt: Long,
    ) {
        val invite = l.invite ?: return
        if (p.dx != CallPayload.DIRECT_JOINING || l.directCommitted) return
        if (clock() - sentAt > timings.directCommitMaxAgeMs) {
            log("call ${l.id}: the peer's join is stale; not hosting")
            return
        }
        l.directCommitted = true
        log("call ${l.id}: the peer is joining; hosting the group")
        direct.host(l.id, l.peerId, invite)
    }

    /**
     * An offer that must not ring: one for a call already ended (C-3.4-3) or one outside `sentAt ± 60 s` (C-3.4-1).
     * It is a missed call unless the caller withdrew it by hand first (C-3.4-2). True when it was such an offer.
     */
    private fun neverRings(
        sender: String,
        p: CallPayload,
        sentAt: Long,
        now: Long,
    ): Boolean {
        val tomb = tombstones[p.id]
        if (tomb == null && abs(sentAt - now) <= timings.staleOfferMs) return false
        // C-3.3-7: a kind switched off here is no missed call, however late it lands.
        val withdrawn = tomb != null && (tomb.ph != CallPayload.PHASE_CANCEL || tomb.rsn == CallPayload.REASON_USER)
        val missed = !withdrawn && takesIncoming(isWalkieOnly(p))
        if (missed) {
            val at = minOf(sentAt, now)
            missedCalls.trySend(MissedCall(sender, at))
            loggedCalls.trySend(CallLogged(sender, at, CallRecord(id = p.id, outgoing = false, walkie = isWalkieOnly(p), missed = true)))
        }
        refused(sender, p, CallEndReason.STALE, missed = missed)
        if (tomb == null) bury(p.id, CallPayload.PHASE_OFFER, null)
        log("call ${p.id} from $sender: ${if (tomb == null) "stale offer" else "offer after its end"}")
        return true
    }

    /** C-3.5-1: [current] is our own unanswered call to [sender], whose offer crossed it. */
    private fun isGlare(
        current: Live,
        sender: String,
    ): Boolean =
        current.outgoing && current.peerId == sender &&
            (current.phase == CallPhase.Calling || current.phase == CallPhase.Ringing)

    /** An offer while [current] is live: busy (C-3.3-2), or glare with the same peer (C-3.5-1). */
    private fun onOfferWhileLive(
        current: Live,
        sender: String,
        p: CallPayload,
        sdp: String?,
        me: String,
        now: Long,
    ): List<Send> {
        if (!isGlare(current, sender)) {
            bury(p.id, CallPayload.PHASE_DECLINE, CallPayload.REASON_BUSY)
            refused(sender, p, CallEndReason.BUSY)
            return listOf(Send(sender, CallPayload(id = p.id, ph = CallPayload.PHASE_DECLINE, rsn = CallPayload.REASON_BUSY)))
        }
        if (me < sender) {
            // Ours survives: the peer drops its own offer and answers ours.
            bury(p.id, CallPayload.PHASE_OFFER, null)
            log("call ${p.id} from $sender: glare, ours (${current.id}) survives")
            return emptyList()
        }
        // Theirs survives: drop ours without a word — a CANCEL could race their offer on a peer that sees them out
        // of order — and answer theirs at once, without alerting. Both users asked to talk.
        log("call ${p.id} from $sender: glare, answering theirs over ours (${current.id})")
        drop(current)
        field.record(
            endLine(current, CallPayload.REASON_USER, current.phase, clock(), unreached = false).copy(reason = CallEndReason.GLARE),
        )
        val l = Live(p.id, sender, outgoing = false, startedAt = now, phase = CallPhase.Connecting, remoteOffer = sdp)
        live = l
        l.answeredAt = now
        // The surviving offer's kind is the call's (§3.11): a walkie one connects at once, there being no media to wait on.
        takeKind(l, p)
        began(l)
        if (l.walkie) {
            connectWalkie(l)
            setup(l) { answerWalkie(l) }
            return emptyList()
        }
        l.invite = directInviteOf(p)
        armConnect(l)
        publish()
        setup(l) { answerCall(l) }
        return emptyList()
    }

    private fun onRinging(
        sender: String,
        p: CallPayload,
        sentAt: Long,
    ): List<Send> {
        val l = live?.takeIf { it.id == p.id && it.peerId == sender && it.outgoing } ?: return emptyList()
        if (l.phase == CallPhase.Ended) return emptyList()
        if (l.phase == CallPhase.Calling) {
            l.phase = CallPhase.Ringing
            l.ringingAt = clock()
            stopReachWatch(l)
            publish()
        }
        commitHost(l, p, sentAt)
        return emptyList()
    }

    private fun onAnswer(
        sender: String,
        p: CallPayload,
        sentAt: Long,
    ): List<Send> {
        val l = live?.takeIf { it.id == p.id && it.peerId == sender && it.outgoing } ?: return emptyList()
        if (p.gen != 0) return onRestartAnswer(l, p)
        if (l.phase != CallPhase.Calling && l.phase != CallPhase.Ringing) return emptyList()
        // An ANSWER whose RINGING was lost proves the peer reachable just the same.
        stopReachWatch(l)
        val now = clock()
        if (l.ringingAt == null) l.ringingAt = now
        l.answeredAt = now
        l.peerCastTicketed = p.ct
        l.peerReportsHeard = p.hr
        if (l.walkie) {
            if (p.m and CallPayload.MEDIA_WALKIE == 0) return finish(l, CallPayload.REASON_MEDIA, CallPayload.PHASE_END)
            connectWalkie(l)
            log("call ${l.id}: answered (walkie)")
            return emptyList()
        }
        val sdp = p.sdp
        if (sdp == null || !SdpGuard.accepts(sdp)) return finish(l, CallPayload.REASON_MEDIA, CallPayload.PHASE_END)
        // §3.11: the callee echoes the walkie bit only when it, too, can drop to one.
        l.walkieOk = l.offeredWalkie && p.m and CallPayload.MEDIA_WALKIE != 0
        commitHost(l, p, sentAt)
        l.phase = CallPhase.Connecting
        armConnect(l)
        publish()
        log("call ${l.id}: answered")
        setup(l) { applyAnswer(l, sdp) }
        return emptyList()
    }

    /**
     * §3.8: the caller's ICE restart of the call we are in. Taken only for that call, from its caller, newer than the
     * last one answered and inside the reconnect window by its signed `sentAt`; anything else changes nothing — a
     * restart never rings and is never declined. A callee still connected answers without leaving [CallPhase.Connected]
     * (its media may never report the loss its caller saw); one already reconnecting gets a fresh window.
     */
    private fun onRestartOffer(
        sender: String,
        p: CallPayload,
        sentAt: Long,
    ): List<Send> {
        val l = live?.takeIf { it.takesRestart(sender, p, sentAt) }
        val sdp = p.sdp?.takeIf { SdpGuard.accepts(it) }
        if (l == null || sdp == null) {
            log("call ${p.id}: restart ${p.gen} not taken")
            return emptyList()
        }
        l.gen = p.gen
        if (l.phase == CallPhase.Reconnecting) reconnect(l)
        log("call ${l.id}: restart ${p.gen} from the caller")
        setup(l) { reanswer(l, p.gen, sdp) }
        return emptyList()
    }

    /** C-3.8-4: [p] restarts this callee's own call, from its caller, newer than the last answered and fresh. */
    private fun Live.takesRestart(
        sender: String,
        p: CallPayload,
        sentAt: Long,
    ): Boolean =
        id == p.id && peerId == sender && !outgoing && !walkie && reconnectable() && p.gen > gen &&
            abs(clock() - sentAt) <= timings.reconnectWindowMs

    /** §3.8: the callee's answer to our restart — only the latest one: an answer to a superseded restart is ignored. */
    private fun onRestartAnswer(
        l: Live,
        p: CallPayload,
    ): List<Send> {
        val sdp = p.sdp?.takeIf { p.gen == l.gen && !l.walkie && l.reconnectable() && SdpGuard.accepts(it) } ?: return emptyList()
        log("call ${l.id}: restart ${p.gen} answered")
        setup(l) {
            val session = mutex.withLock { l.session?.takeIf { current(l) } } ?: return@setup
            // A refused answer ends nothing: the window does, unless the next restart gets through.
            if (!session.accept(sdp)) log("call ${l.id}: restart ${p.gen}'s answer refused")
        }
        return emptyList()
    }

    private fun onTerminal(
        sender: String,
        p: CallPayload,
    ): List<Send> {
        val l = live
        if (l != null && l.id == p.id && l.peerId == sender) {
            if (l.phase == CallPhase.Ended) return emptyList()
            val missed = !l.outgoing && l.phase == CallPhase.Alerting && p.ph == CallPayload.PHASE_CANCEL
            return finish(l, p.rsn ?: CallPayload.REASON_USER, byPeer = true, missed = missed)
        }
        // A call we never saw, or one already replaced: remembered, so its late offer never rings (C-3.4-3).
        if (p.id !in tombstones) bury(p.id, p.ph, p.rsn)
        return emptyList()
    }

    private fun onCands(
        sender: String,
        p: CallPayload,
    ): List<Send> {
        val l = live?.takeIf { it.id == p.id && it.peerId == sender && it.phase != CallPhase.Ended } ?: return emptyList()
        val lines = p.cand.orEmpty().filter { it.isNotBlank() }
        if (p.gen != 0 || lines.isEmpty()) return emptyList()
        addOrHold(l, lines)
        return emptyList()
    }

    /** The peer's candidate lines into [l]'s media, or held (bounded) until the peer's SDP is applied. */
    private fun addOrHold(
        l: Live,
        lines: List<String>,
    ) {
        val session = l.session
        if (session != null && l.remoteApplied) {
            session.addCandidates(lines)
        } else {
            l.earlyCands += lines.take((MAX_EARLY_CANDS - l.earlyCands.size).coerceAtLeast(0))
        }
    }

    /** One event off a call's lane (§3.10): a signal goes the mesh's way in, candidates straight to the media. */
    private suspend fun onLane(event: LaneEvent) {
        when (event) {
            // A call's first OFFER never rides a lane: the lane exists only once an offer was answered. A restart can.
            is LaneEvent.Signal -> {
                if (event.payload.ph != CallPayload.PHASE_OFFER || event.payload.gen != 0) {
                    onSignal(event.peerId, event.payload, clock())
                }
            }

            is LaneEvent.Candidates -> {
                mutex.withLock {
                    val l = live?.takeIf { it.id == event.callId && it.peerId == event.peerId && it.phase != CallPhase.Ended }
                    val lines = event.lines.filter { it.isNotBlank() }
                    if (l != null && lines.isNotEmpty()) addOrHold(l, lines)
                }
            }
        }
    }

    // ---- setup, off the caller's and the inbound path ----

    /**
     * The ICE servers a call gathers from: TURN, then STUN.
     *
     * TURN is fresh credentials when a spool issues them; else the last ones while they can still start an allocation;
     * else those servers as plain STUN, which needs no credential. A phone that just changed networks dials before its
     * spool has reconnected, and with no STUN either its offer carries only addresses nothing outside its carrier can
     * reach — and the peer's relay accepts traffic only from addresses the offer named (device-observed 2026-10-07, a
     * Pixel 9 seconds onto LTE). Reusing a live credential bends C-7.6-11's fetch-per-call SHOULD only when fetching has
     * just failed.
     *
     * STUN is gathered on every call, TURN or not (C-7.6-20, ADR 2026-10.xkp4): what connected spools advertise in
     * HELLO beside whatever the fallback reduced to, held to [StunServers]' rule, without repeats, at most
     * [StunServers.MAX] — one credential-free server. That is what lets a phone no spool issues TURN to offer its
     * public address, the one a paying peer's relay can permit.
     */
    private suspend fun servers(peerId: String): IceSet {
        val fresh = turn.turnServers(peerId, ranking::best)
        val (issued, derived) = if (fresh.isNotEmpty()) fresh.also { lastServers = it } to emptyList() else fallback()
        val relays = oneServer(issued)
        val stun = StunServers.pick(derived + turn.stunUrls())
        log("call ICE: ${relays.size} TURN server(s), ${stun.size} STUN url(s)")
        return IceSet(if (stun.isEmpty()) relays else relays + TurnServer(urls = stun, user = "", pass = ""), issued)
    }

    /**
     * C-7.6-53: where a credential in [issued] comes from a spool that names its servers, the one [ranking] chooses
     * alone, and the rest dropped unused (no allocation is made until the media asks); else [issued] as it is. Reused
     * live credentials go through it too, so a reuse takes only the server the ranking would choose now.
     */
    private fun oneServer(issued: List<TurnServer>): List<TurnServer> {
        val chosen = ranking.choose(issued) ?: return issued
        log("turn: ${chosen.describe()}, ${scoreOf(chosen)}")
        return listOf(chosen)
    }

    // "score 23 ms" or "unmeasured": a server's standing on this network, by its entry, for a log line.
    private fun scoreOf(server: TurnServer): String = server.entry?.let(ranking::score)?.let { "score $it ms" } ?: "unmeasured"

    /** No credential issued: the last ones while they can still allocate, else their STUN half (see [servers]). */
    private fun fallback(): Pair<List<TurnServer>, List<String>> {
        val cached = lastServers
        val live = liveCredentials()
        if (live.isNotEmpty()) {
            log("call TURN: none issued, reusing ${live.size} live credential(s)")
            return live to emptyList()
        }
        val stun = cached.flatMap(::stunOnly)
        if (stun.isNotEmpty()) log("call TURN: none issued, STUN only from ${stun.size} known url(s)")
        return emptyList<TurnServer>() to stun
    }

    /** [server]'s UDP URLs as `stun:` ones, without the credential — what a binding request needs. */
    private fun stunOnly(server: TurnServer): List<String> =
        server.urls
            .filter { it.startsWith("turn:") && !it.contains("transport=tcp") }
            .map { "stun:" + it.removePrefix("turn:").substringBefore('?') }
            .distinct()

    /**
     * Runs one setup step of [l] on [scope]. A step that throws (the media layer, a TURN fetch) ends the call at once
     * and tells the peer, rather than leaving it to its phase timer.
     */
    private fun setup(
        l: Live,
        step: suspend () -> Unit,
    ) {
        scope.launch {
            try {
                step()
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception, // a setup step never throws into the app scope
            ) {
                log("call ${l.id}: setup failed: ${e.javaClass.simpleName}")
                deliver(mutex.withLock { if (current(l)) finish(l, CallPayload.REASON_ERROR, endPhase(l)) else emptyList() })
            }
        }
    }

    @Suppress("ReturnCount") // a ladder of exits: the call ended under each slow step
    private suspend fun dial(l: Live) {
        // §3.10: a group we would host for a neighbour. Minting raises nothing; the callee's "joining" does.
        val invite = if (!relayAll()) direct.invite(l.id, l.peerId) else null
        // §3.11: offer the drop to a walkie to a peer that runs one; it takes effect only if its ANSWER echoes it.
        val offersWalkie = dropToWalkie && walkie.enabled && walkie.capable(l.peerId)
        mutex.withLock {
            if (!current(l)) return
            l.invite = invite
            l.offeredWalkie = offersWalkie
        }
        val ice = servers(l.peerId)
        val opened = openFor(l, ice) ?: return
        val (session, gathered) = afterRefusal(l, ice, opened, opened.offer()) { it.offer() } ?: return
        val sdp = l.toPeer(gathered)
        val offer =
            mutex.withLock {
                if (!current(l)) return
                if (sdp == null || !SdpGuard.accepts(sdp)) {
                    // Nothing reached the peer yet, so nothing is owed it.
                    finish(l, CallPayload.REASON_MEDIA)
                    return
                }
                // A relay-only call goes direct nowhere: a group pair would show the peer our real address.
                if (l.relay) l.invite = null
                withLiveMedia(CallPayload(id = l.id, ph = CallPayload.PHASE_OFFER, sdp = sdp, dw = l.invite), l.offeredWalkie)
            }
        log("call ${l.id}: offering (${l.ice.iceSummary()}, relay=${l.relay}, direct=${offer.dw != null})")
        if (!signals.sendCallSignal(l.peerId, offer)) {
            mutex.withLock { if (current(l)) finish(l, CallPayload.REASON_ERROR) }
            return
        }
        described(l)
    }

    @Suppress("ReturnCount") // a ladder of exits, as dial's: the call ended under each slow step
    private suspend fun answerCall(l: Live) {
        mutex.withLock {
            if (!current(l)) return
            // §3.10: a callee that did not join while it rang (answer mode, or a phone that may not join off screen)
            // joins now. A relay-only answer never does.
            if (!relayAll()) commitJoin(l, prewarm = false)
        }
        val ice = l.servers?.await() ?: servers(l.peerId)
        val remoteOffer = checkNotNull(l.remoteOffer)
        val opened = openFor(l, ice) ?: return
        val (session, gathered) = afterRefusal(l, ice, opened, opened.answer(remoteOffer)) { it.answer(remoteOffer) } ?: return
        val sdp = l.toPeer(gathered)
        val sends =
            mutex.withLock {
                if (!current(l)) return
                if (sdp == null || !SdpGuard.accepts(sdp)) {
                    return@withLock finish(l, CallPayload.REASON_MEDIA, CallPayload.PHASE_DECLINE)
                }
                remoteApplied(l, session)
                val answer =
                    withLiveMedia(CallPayload(id = l.id, ph = CallPayload.PHASE_ANSWER, sdp = sdp, dx = directVerdict(l)), l.walkieOk)
                listOf(Send(l.peerId, answer))
            }
        if (!deliver(sends)) {
            mutex.withLock { if (current(l)) finish(l, CallPayload.REASON_ERROR) }
            return
        }
        if (sends.any { it.payload.ph == CallPayload.PHASE_ANSWER }) described(l)
    }

    /** §3.11: a walkie session's OFFER — no media, no ICE servers, no group: only that it is a walkie. */
    private suspend fun dialWalkie(l: Live) {
        val offer =
            CallPayload(
                id = l.id,
                ph = CallPayload.PHASE_OFFER,
                m = CallPayload.MEDIA_WALKIE,
                ct = castFlag(CallPayload.MEDIA_WALKIE),
                hr = true,
            )
        if (!mutex.withLock { current(l) }) return
        log("call ${l.id}: offering a walkie session")
        if (!signals.sendCallSignal(l.peerId, offer)) {
            mutex.withLock { if (current(l)) finish(l, CallPayload.REASON_ERROR) }
            return
        }
        described(l)
    }

    /** §3.11: the callee's ANSWER to a walkie session, which [connectWalkie] already connected on this side. */
    private suspend fun answerWalkie(l: Live) {
        val answer =
            CallPayload(
                id = l.id,
                ph = CallPayload.PHASE_ANSWER,
                m = CallPayload.MEDIA_WALKIE,
                ct = castFlag(CallPayload.MEDIA_WALKIE),
                hr = true,
            )
        if (!mutex.withLock { current(l) }) return
        if (!deliver(listOf(Send(l.peerId, answer)))) mutex.withLock { if (current(l)) finish(l, CallPayload.REASON_ERROR) }
    }

    private suspend fun applyAnswer(
        l: Live,
        sdp: String,
    ) {
        val session = mutex.withLock { l.session?.takeIf { current(l) } } ?: return
        val accepted = session.accept(sdp)
        val sends =
            mutex.withLock {
                if (!current(l)) return
                if (!accepted) return@withLock finish(l, CallPayload.REASON_MEDIA, CallPayload.PHASE_END)
                remoteApplied(l, session)
                emptyList()
            }
        deliver(sends)
    }

    /**
     * Opens [l]'s media, or closes it again at once when the call ended meanwhile. A relay-only call [servers] give no
     * TURN to (C-3.7-3) could gather nothing, so it ends here instead, before its OFFER or ANSWER: a caller's peer has
     * heard nothing yet and is owed nothing, a callee tells its caller.
     */
    private suspend fun openFor(
        l: Live,
        ice: IceSet,
    ): CallSession? {
        val servers = ice.servers
        val relay = relayAll()
        if (relay && servers.none(TurnServer::isTurn)) {
            log("call ${l.id}: relay all calls, but no TURN server; ending before the media")
            val tell = if (l.outgoing) null else endPhase(l)
            deliver(mutex.withLock { if (current(l)) finish(l, CallPayload.REASON_NO_ROUTE, tell) else emptyList() })
            return null
        }
        val goesDirect = !relay && mutex.withLock { l.invite != null }
        // Which session an event came from, so a session C-7.6-54 retired is not heard from again.
        val from = AtomicReference<CallSession?>(null)
        val session = media.open(servers, relay, goesDirect) { event -> scope.launch { onMedia(l, from.get(), event) } }
        from.set(session)
        val kept =
            mutex.withLock {
                if (!current(l)) return@withLock false
                l.session = session
                l.relay = relay
                l.ice = servers
                l.turn = servers.firstOrNull { it.ranked }
                l.pool = ice.pool
                // C-7.6-20's shape: TURN entries carry a credential, the one STUN entry carries its URLs and none.
                l.turnServers = servers.count { it.user.isNotEmpty() }
                l.stunUrls = servers.filter { it.user.isEmpty() }.sumOf { it.urls.size }
                session.setMuted(l.muted)
                true
            }
        if (!kept) session.close()
        return session.takeIf { kept }
    }

    /**
     * §3.8: one ICE restart of [l] — the ICE servers fetched again (a long call's TURN credential may have run out),
     * fresh ICE credentials, `OFFER gen+1`. One that yields no offer is skipped; the window goes on. Once made, the offer
     * goes out even if ICE healed meanwhile: its answer is what brings the connection back to stable.
     */
    private suspend fun restart(l: Live) {
        val session = mutex.withLock { l.session?.takeIf { current(l) && l.phase == CallPhase.Reconnecting } } ?: return
        val ice = servers(l.peerId)
        val servers = ice.servers
        mutex.withLock { if (current(l) && l.session === session) l.restarted(ice) }
        val sdp = session.restart(servers)?.let { if (l.relay) SdpGuard.stripToRelay(it) else it }
        val offer =
            mutex.withLock {
                if (!current(l) || l.session !== session) return
                if (sdp == null || !SdpGuard.accepts(sdp)) {
                    log("call ${l.id}: restart made no offer")
                    return
                }
                l.gen += 1
                CallPayload(id = l.id, ph = CallPayload.PHASE_OFFER, gen = l.gen, sdp = sdp)
            }
        log("call ${l.id}: restarting ICE (gen ${offer.gen}, ${servers.size} ICE server(s))")
        deliver(listOf(Send(l.peerId, offer)))
    }

    /** §3.8: the callee's answer to the caller's restart [gen], unless a newer one overtook it meanwhile. */
    private suspend fun reanswer(
        l: Live,
        gen: Int,
        remoteOffer: String,
    ) {
        val session = mutex.withLock { l.session?.takeIf { current(l) } } ?: return
        val ice = servers(l.peerId)
        mutex.withLock { if (current(l) && l.session === session) l.restarted(ice) }
        val sdp = session.reanswer(remoteOffer, ice.servers)?.let { if (l.relay) SdpGuard.stripToRelay(it) else it }
        val answer =
            mutex.withLock {
                if (!current(l) || l.session !== session || l.gen != gen) return
                if (sdp == null || !SdpGuard.accepts(sdp)) {
                    log("call ${l.id}: restart $gen could not be answered")
                    return
                }
                CallPayload(id = l.id, ph = CallPayload.PHASE_ANSWER, gen = gen, sdp = sdp)
            }
        deliver(listOf(Send(l.peerId, answer)))
    }

    /** Our description is out: the late candidates that waited for it may follow now. */
    private suspend fun described(l: Live) {
        val sends =
            mutex.withLock {
                if (!current(l)) return
                l.described = true
                recordTurn(l)
                l.lateCands?.let { cands(l, it) }.orEmpty()
            }
        deliver(sends)
    }

    /**
     * C-7.6-54, before [l]'s OFFER or ANSWER leaves: [sdp] is what [session] gathered. Where the chosen TURN server
     * refused this side's allocation meanwhile and the description holds no relay candidate, this side gathers once
     * more on the next server in its ranking — a credential in [ice]'s pool, else a new `turnreq` naming it — in a
     * fresh session, closes the refused one, and returns the fresh session with its description. Otherwise [session]
     * and [sdp] as they came; null when the call ended meanwhile.
     *
     * A fresh session, not an ICE restart: libwebrtc reads a session's TURN servers once, and an answerer cannot
     * restart at all; nothing has gone to the peer yet, so the first description it sees is the second gathering's.
     * The refusal is read from [Live.turnRefused], which [onMedia] writes off the media's callback: it lands a few
     * hundred ms into C-3.6-1's 1.5 s, so it is there by now. One that lands in the window's last instant only marks
     * the server, as a refusal after the description does.
     */
    @Suppress("ReturnCount") // a ladder of exits: nothing to move from, nowhere to move to, the call ended meanwhile
    private suspend fun afterRefusal(
        l: Live,
        ice: IceSet,
        session: CallSession,
        sdp: String?,
        describe: suspend (CallSession) -> String?,
    ): Pair<CallSession, String?>? {
        val refused =
            mutex.withLock {
                if (!current(l) || l.session !== session) return null
                val chosen = l.turn
                // Once per call, and never from a server that already gave this side a relay.
                val code = l.turnRefused.takeUnless { l.turnMoved || hasRelay(sdp) }
                if (chosen == null || code == null) return@withLock null
                l.turnMoved = true
                chosen to code
            } ?: return session to sdp
        val (chosen, code) = refused
        val ours = if (l.outgoing) "offer" else "answer"
        val next = chosen.entry?.let { nextServer(l, it) }
        if (next == null) {
            log("call ${l.id}: turn ${chosen.describe()} refused ($code) before our $ours, and no other server")
            return session to sdp
        }
        log("call ${l.id}: turn ${chosen.describe()} refused ($code) before our $ours; gathering on ${next.describe()}")
        mutex.withLock {
            if (!current(l)) return null
            // From here nothing the refused gathering says belongs to the call: not its late candidates, not a second
            // refusal, which would otherwise be read against the next server once it is the call's.
            l.retired = session
            l.lateCands = null
            l.turnRefused = null
        }
        val servers = ice.servers.map { if (it === chosen) next else it }
        val fresh = openFor(l, IceSet(servers, ice.pool + next))
        session.close()
        fresh ?: return null
        mutex.withLock { l.turnFellBack = true }
        return fresh to describe(fresh)
    }

    // C-7.6-54's next server after [refused]: a live credential in hand for it, else a new `turnreq` naming it.
    private suspend fun nextServer(
        l: Live,
        refused: String,
    ): TurnServer? {
        val pool = mutex.withLock { l.pool }
        val entry = ranking.next(pool, refused) ?: return null
        val nowS = clock() / MS_PER_S
        return pool.firstOrNull { it.isTurn && it.entry == entry && it.expiresS() - CREDENTIAL_MARGIN_S > nowS }
            ?: turn.turnServerFor(l.peerId, entry)
    }

    // The field log's one line per call for the TURN server it chose (C-7.6-53), once its description is out.
    private fun recordTurn(l: Live) {
        val chosen = l.turn ?: return
        if (l.turnRecorded) return
        l.turnRecorded = true
        field.record(
            FieldEvent.CallTurn(
                slot = chosen.slot,
                of = chosen.spoolHosts.size,
                scoreMs = chosen.entry?.let(ranking::score),
                fallback = l.turnFellBack,
                ranked = ranking.named(l.pool),
                outgoing = l.outgoing,
            ),
        )
    }

    private suspend fun onMedia(
        l: Live,
        from: CallSession?,
        event: MediaEvent,
    ) {
        val sends =
            mutex.withLock {
                // A session C-7.6-54 replaced before its description left: nothing it says belongs to the call.
                if (!mediaLive(l) || l.retiredFrom(from)) return
                when (event) {
                    MediaEvent.Connected -> {
                        if (l.phase == CallPhase.Connecting) {
                            l.phase = CallPhase.Connected
                            l.connectedAt = clock()
                            log("call ${l.id}: connected")
                        }
                        if (l.phase == CallPhase.Reconnecting) {
                            l.phase = CallPhase.Connected
                            log("call ${l.id}: reconnected")
                        }
                        if (l.phase == CallPhase.Connected) {
                            l.timer?.cancel()
                            l.timer = null
                            publish()
                        }
                        emptyList()
                    }

                    MediaEvent.Disconnected -> {
                        onPathLost(l, failed = false)
                    }

                    MediaEvent.Failed -> {
                        onPathLost(l, failed = true)
                    }

                    is MediaEvent.LateCandidates -> {
                        if (l.described) cands(l, event.lines) else emptyList<Send>().also { l.lateCands = event.lines }
                    }

                    is MediaEvent.DirectCandidates -> {
                        direct.candidates(l.id, event.lines)
                        emptyList()
                    }

                    is MediaEvent.TurnRefused -> {
                        onTurnRefused(l, event)
                        emptyList()
                    }
                }
            }
        deliver(sends)
    }

    // ---- under [mutex] ----

    /**
     * C-7.6-54: an allocation on [l]'s chosen server was refused. The server is marked whenever that comes; before this
     * side's description has gone out it is also what [afterRefusal] moves on from. A refusal from a call that holds no
     * ranked server (spools that name none, or a person's own) marks nothing.
     */
    private fun onTurnRefused(
        l: Live,
        event: MediaEvent.TurnRefused,
    ) {
        val chosen = l.turn ?: return
        val host = TurnHosts.urlHost(event.url)
        if (host != null && chosen.urls.none { TurnHosts.urlHost(it) == host }) return
        chosen.entry?.let(ranking::refuse)
        if (!l.described) l.turnRefused = event.code
        log("call ${l.id}: turn ${chosen.describe()} refused an allocation (${event.code})")
    }

    // Whether [from] is the session C-7.6-54 retired from [this] call (null is a session not yet told its name).
    private fun Live.retiredFrom(from: CallSession?): Boolean = from != null && from === retired

    // [sdp] as it goes to the peer: relay candidates alone under "Relay all calls" (C-3.7-3).
    private fun Live.toPeer(sdp: String?): String? = sdp?.let { if (relay) SdpGuard.stripToRelay(it) else it }

    // A §3.8 restart's fresh servers: the one ranked server it gathers from now is the one a refusal marks.
    private fun Live.restarted(ice: IceSet) {
        this.ice = ice.servers
        turn = ice.servers.firstOrNull { it.ranked }
        pool = ice.pool
    }

    // Whether [sdp] already carries a relay candidate: the chosen server gave this side one, so it stays (C-7.6-54).
    private fun hasRelay(sdp: String?): Boolean =
        sdp?.lineSequence()?.any { it.startsWith("a=candidate:") && it.contains(" typ relay") } == true

    /**
     * [l]'s media lost its path — for good when [failed] (ICE gave up). A connected call reconnects (§3.8), and while
     * it does only its window ends it, since a restart revives a failed connection. Before connecting, a failure ends
     * the call (or waits for a direct leg on its way); a mere disconnect does nothing.
     */
    private fun onPathLost(
        l: Live,
        failed: Boolean,
    ): List<Send> =
        when {
            l.phase == CallPhase.Connected -> {
                l.drops += 1
                reconnect(l)
                emptyList()
            }

            l.phase == CallPhase.Reconnecting || !failed -> {
                emptyList()
            }

            l.phase == CallPhase.Connecting && direct.pending(l.id) -> {
                // With no other route ICE gives up before the group is there; its candidates revive it.
                log("call ${l.id}: ICE failed while the direct leg comes up; waiting for it")
                emptyList()
            }

            else -> {
                fallback(l)
            }
        }

    private fun current(l: Live): Boolean = live === l && l.phase != CallPhase.Ended

    /** [l] connected once and is still up, its path held or being won back (§3.8). */
    private fun Live.reconnectable(): Boolean = phase == CallPhase.Connected || phase == CallPhase.Reconnecting

    /** [l] is current and still has media to hear from: a session the walkie closed may report on its way out (§3.11). */
    private fun mediaLive(l: Live): Boolean = current(l) && !l.walkie

    /** How this side ends [l] toward the peer: a CANCEL before an answer, a DECLINE while alerting, else END. */
    private fun endPhase(l: Live): Int =
        when {
            l.outgoing && (l.phase == CallPhase.Calling || l.phase == CallPhase.Ringing) -> CallPayload.PHASE_CANCEL
            !l.outgoing && l.phase == CallPhase.Alerting -> CallPayload.PHASE_DECLINE
            else -> CallPayload.PHASE_END
        }

    /** The one CANDS this side sends for the call (C-3.6-2), relay-only when the call is. */
    private fun cands(
        l: Live,
        lines: List<String>,
    ): List<Send> {
        val out = (if (l.relay) SdpGuard.relayOnly(lines) else lines).filter { it.isNotBlank() }
        if (l.candsSent || out.isEmpty()) return emptyList()
        l.candsSent = true
        return listOf(Send(l.peerId, CallPayload(id = l.id, ph = CallPayload.PHASE_CANDS, cand = out)))
    }

    private fun remoteApplied(
        l: Live,
        session: CallSession,
    ) {
        l.remoteApplied = true
        if (l.earlyCands.isNotEmpty()) session.addCandidates(l.earlyCands.toList())
        l.earlyCands.clear()
    }

    /** C-3.3-1, and C-3.3-6: a ring-out that never heard a RINGING has no evidence the peer rang, so it is no_route. */
    private fun armRingOut(l: Live) =
        armTimer(l, timings.ringOutMs) {
            when (it.phase) {
                CallPhase.Calling -> finish(it, CallPayload.REASON_NO_ROUTE, CallPayload.PHASE_CANCEL)
                CallPhase.Ringing -> finish(it, CallPayload.REASON_TIMEOUT, CallPayload.PHASE_CANCEL)
                else -> emptyList()
            }
        }

    /**
     * C-3.3-5: while [l] is [CallPhase.Calling], looks every [CallTimings.reachLookMs] at whether anything reaches its
     * peer ([reach], read off the lock), publishes what it saw, and withdraws the call `CANCEL no_route` once nothing has
     * for [CallTimings.unreachMs]. Ends with the phase: a RINGING, an ANSWER or the end stop it. Like a phase timer, the
     * slot is cleared before [finish], so the CANCEL is never cancelled with the coroutine that sends it.
     */
    private fun watchReach(l: Live) {
        l.reachWatch?.cancel()
        l.reachWatch =
            scope.launch {
                var lostSince: Long? = null
                while (true) {
                    if (!mutex.withLock { calling(l) }) return@launch
                    val reached = reach.reachable(l.peerId)
                    val now = clock()
                    lostSince = if (reached) null else lostSince ?: now
                    val sends =
                        mutex.withLock {
                            if (!calling(l)) return@launch
                            noteReach(l, reached, now)
                            val since = lostSince ?: return@withLock null
                            if (now - since < timings.unreachMs) return@withLock null
                            l.reachWatch = null
                            finish(l, CallPayload.REASON_NO_ROUTE, CallPayload.PHASE_CANCEL)
                        }
                    if (sends != null) {
                        deliver(sends)
                        return@launch
                    }
                    delay(timings.reachLookMs)
                }
            }
    }

    private fun calling(l: Live): Boolean = current(l) && l.phase == CallPhase.Calling

    /**
     * What [watchReach] saw for [l] at [now]: unreachable when nothing [reached] its peer (C-3.3-5), unproven when
     * something did and still no RINGING came back past [CallTimings.ringingMs] (C-3.3-6). Published when it changes.
     */
    private fun noteReach(
        l: Live,
        reached: Boolean,
        now: Long,
    ) {
        val unproven = reached && now - l.startedAt >= timings.ringingMs
        if (l.unreachable != reached && l.unproven == unproven) return
        if (l.unreachable == reached) log("call ${l.id}: ${if (reached) "the peer is reachable" else "nothing reaches the peer"}")
        if (unproven && !l.unproven) log("call ${l.id}: the peer is reachable but no RINGING came back")
        l.unreachable = !reached
        l.unproven = unproven
        publish()
    }

    /** A RINGING proved the peer reachable: no more looks, and no flag. */
    private fun stopReachWatch(l: Live) {
        l.reachWatch?.cancel()
        l.reachWatch = null
        l.unreachable = false
        l.unproven = false
    }

    private fun armConnect(l: Live) =
        armTimer(l, if (l.directCommitted || (!l.outgoing && l.invite != null)) timings.directConnectMs else timings.connectMs) {
            if (it.phase == CallPhase.Connecting) fallback(it) else emptyList()
        }

    /**
     * [l] has no live path — it never came, failed, or stayed lost past its grace. It drops to a walkie session when
     * both sides said they can (§3.11), else ends no_route. Nothing is sent: the peer runs its own timer, and its media
     * sees ours close, so both sides land in the walkie without a frame between them.
     */
    private fun fallback(l: Live): List<Send> {
        if (!l.walkieOk) return finish(l, CallPayload.REASON_NO_ROUTE, CallPayload.PHASE_END)
        l.servers?.cancel()
        l.session?.close()
        l.session = null
        direct.end(l.id, null)
        l.walkie = true
        l.degraded = true
        log("call ${l.id}: no live path; a walkie session now")
        connectWalkie(l)
        return emptyList()
    }

    /** [l] is a connected walkie session: no media to wait on, so no timer either. */
    private fun connectWalkie(l: Live) {
        l.timer?.cancel()
        l.timer = null
        l.phase = CallPhase.Connected
        if (l.connectedAt == null) l.connectedAt = clock()
        publish()
    }

    /**
     * §3.8: [l] lost its path (or, on a reconnecting callee, the caller restarted it): [CallPhase.Reconnecting], and
     * one job in the timer slot runs the drop. The caller restarts ICE after [CallTimings.restartDelayMs] and, still
     * lost, once more [CallTimings.restartRetryMs] later; either side falls back ([fallback]: no_route) when
     * [CallTimings.reconnectWindowMs] runs out. A Connected cancels the job with the rest of the phase timers.
     */
    private fun reconnect(l: Live) {
        l.phase = CallPhase.Reconnecting
        publish()
        log("call ${l.id}: path lost; reconnecting")
        l.timer?.cancel()
        l.timer =
            scope.launch {
                if (l.outgoing) {
                    delay(timings.restartDelayMs)
                    restartIfLost(l)
                    delay(timings.restartRetryMs)
                    restartIfLost(l)
                    delay((timings.reconnectWindowMs - timings.restartDelayMs - timings.restartRetryMs).coerceAtLeast(0L))
                } else {
                    delay(timings.reconnectWindowMs)
                }
                val sends =
                    mutex.withLock {
                        if (!current(l) || l.phase != CallPhase.Reconnecting) return@launch
                        l.timer = null
                        fallback(l)
                    }
                deliver(sends)
            }
    }

    /** One of the caller's restarts, on its own coroutine so the window's job never waits on a TURN fetch. */
    private suspend fun restartIfLost(l: Live) {
        if (mutex.withLock { current(l) && l.phase == CallPhase.Reconnecting }) setup(l) { restart(l) }
    }

    /** Replaces [l]'s phase timer. The fire clears the slot first, so [finish] never cancels the coroutine it runs on. */
    private fun armTimer(
        l: Live,
        ms: Long,
        onFire: (Live) -> List<Send>,
    ) {
        l.timer?.cancel()
        l.timer =
            scope.launch {
                delay(ms)
                val sends =
                    mutex.withLock {
                        if (!current(l)) return@launch
                        l.timer = null
                        onFire(l)
                    }
                deliver(sends)
            }
    }

    /**
     * Ends [l] here; returns the frame that tells the peer when [tellPhase] is given. Its id is remembered, so a late
     * copy of any of its frames changes nothing. [byPeer]: the peer's own terminal phase ended it. [missed]: an
     * incoming call that stopped ringing unanswered (C-3.4-2). Either way the call's record goes out (§3.12).
     */
    private fun finish(
        l: Live,
        reason: Int,
        tellPhase: Int? = null,
        byPeer: Boolean = false,
        missed: Boolean = false,
    ): List<Send> {
        val tell = tellPhase?.let { CallPayload(id = l.id, ph = it, rsn = reason) }
        // C-3.3-5/6: withdrawn before any RINGING or ANSWER — nothing reached the peer, or nothing came back from it.
        val unreached = l.outgoing && l.phase == CallPhase.Calling && reason == CallPayload.REASON_NO_ROUTE
        val endedFrom = l.phase
        drop(l, tell)
        l.phase = CallPhase.Ended
        l.reason = reason
        l.byPeer = byPeer
        l.missed = missed
        publish()
        if (missed) missedCalls.trySend(MissedCall(l.peerId, l.startedAt))
        val endedAt = clock()
        val record =
            CallRecord(
                id = l.id,
                outgoing = l.outgoing,
                walkie = l.walkie,
                reason = reason,
                byPeer = byPeer,
                missed = missed,
                connectedAt = l.connectedAt,
                endedAt = endedAt,
                unreached = unreached,
            )
        loggedCalls.trySend(CallLogged(l.peerId, l.startedAt, record))
        field.record(endLine(l, reason, endedFrom, endedAt, unreached))
        log("call ${l.id}: ended ($reason${tellPhase?.let { ", telling the peer with $it" }.orEmpty()})")
        return tell?.let { listOf(Send(l.peerId, it)) }.orEmpty()
    }

    /**
     * Releases everything [l] holds; the setup coroutines see it gone at their next step. Its direct leg ends too,
     * [terminal] down its lane first — the radio goes back to the mesh right after, and the mesh copy of it may be
     * slower than the group was.
     */
    private fun drop(
        l: Live,
        terminal: CallPayload? = null,
    ) {
        l.timer?.cancel()
        l.timer = null
        l.reachWatch?.cancel()
        l.reachWatch = null
        l.servers?.cancel()
        l.session?.close()
        l.session = null
        direct.end(l.id, terminal)
        if (l.id !in tombstones) bury(l.id, CallPayload.PHASE_END, null)
    }

    /** [l] began on this phone: its field-log line (ADR 2026-10.a239's amendment). */
    private fun began(l: Live) = field.record(FieldEvent.CallStart(peer = l.peerId, outgoing = l.outgoing, walkie = l.walkie))

    /** The field log's line for [l], ended at [endedAt] from [endedFrom] for [reason]; every time from its start here. */
    private fun endLine(
        l: Live,
        reason: Int,
        endedFrom: CallPhase,
        endedAt: Long,
        unreached: Boolean,
    ): FieldEvent.CallEnd =
        FieldEvent.CallEnd(
            peer = l.peerId,
            outgoing = l.outgoing,
            walkie = l.walkie,
            reason = CallEndReason.of(reason),
            lastPhase = endedFrom,
            lifeMs = endedAt - l.startedAt,
            byPeer = l.byPeer,
            missed = l.missed,
            degraded = l.degraded,
            ringMs = l.ringingAt?.let { it - l.startedAt },
            answerMs = l.answeredAt?.let { it - l.startedAt },
            setupMs = l.connectedAt?.let { it - l.startedAt },
            talkMs = l.connectedAt?.let { endedAt - it },
            restarts = l.gen,
            drops = l.drops,
            relayOnly = l.relay,
            directOffered = l.invite != null,
            directTaken = l.directCommitted,
            turn = l.turnServers,
            stun = l.stunUrls,
            unreached = unreached,
            unproven = l.unproven,
        )

    /** An offer [p] from [sender] that never became a call here, for [reason]: its one field-log line, of no length. */
    private fun refused(
        sender: String,
        p: CallPayload,
        reason: CallEndReason,
        missed: Boolean = false,
    ) = field.record(
        FieldEvent.CallEnd(
            peer = sender,
            outgoing = false,
            walkie = isWalkieOnly(p),
            reason = reason,
            lastPhase = null,
            lifeMs = 0,
            missed = missed,
        ),
    )

    /** Remembers the terminal phase [ph] of the call [id] (C-3.4-3), for this process and, through [tombs], the next. */
    private fun bury(
        id: String,
        ph: Int,
        rsn: Int?,
    ) {
        tombstones[id] = Tomb(ph, rsn, clock())
        tombsDirty.trySend(Unit)
    }

    /**
     * Merges in what [tombs] kept from an earlier process, once, as the manager starts: those younger than
     * [TOMB_KEEP_MS], oldest first, ahead of any this process added meanwhile, so the bound evicts the oldest. A journal
     * that cannot be read costs this process nothing but them.
     */
    private suspend fun restoreTombs() {
        if (tombs === CallTombJournal.None) return
        val kept =
            try {
                tombs.load()
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception, // a journal never stops a call ringing
            ) {
                log("call tombstones not read: ${e.javaClass.simpleName}")
                emptyList()
            }
        val now = clock()
        val fresh = kept.filter { now - it.at <= TOMB_KEEP_MS }.sortedBy { it.at }
        mutex.withLock {
            val since = tombstones.toList()
            tombstones.clear()
            fresh.forEach { tombstones[it.id] = Tomb(it.ph, it.rsn, it.at) }
            since.forEach { (id, tomb) -> tombstones[id] = tomb }
        }
        if (fresh.isNotEmpty()) log("call tombstones: ${fresh.size} kept from before")
    }

    /** Writes every tombstone held to [tombs], whole; a failed write leaves the journal as it was. */
    private suspend fun saveTombs() {
        val all = mutex.withLock { tombstones.map { (id, t) -> CallTomb(id, t.ph, t.rsn, t.at) } }
        try {
            tombs.save(all)
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception, // a journal never stops a call
        ) {
            log("call tombstones not saved: ${e.javaClass.simpleName}")
        }
    }

    private fun publish() {
        _state.value =
            live?.let {
                CallState(
                    id = it.id,
                    peerId = it.peerId,
                    outgoing = it.outgoing,
                    phase = it.phase,
                    startedAt = it.startedAt,
                    connectedAt = it.connectedAt,
                    muted = it.muted,
                    reason = it.reason,
                    endedByPeer = it.byPeer,
                    missed = it.missed,
                    walkie = it.walkie,
                    degraded = it.degraded,
                    peerCastTicketed = it.peerCastTicketed,
                    peerReportsHeard = it.peerReportsHeard,
                    unreachable = it.unreachable,
                    unproven = it.unproven,
                )
            }
    }

    private suspend fun deliver(sends: List<Send>): Boolean {
        var all = true
        for (send in sends) {
            // The lane too, when the call has one (§3.10): the mesh may have lost the radio its only link rode.
            direct.mirror(send.payload.id, send.payload)
            if (!signals.sendCallSignal(send.peerId, send.payload)) {
                log("call ${send.payload.id}: phase ${send.payload.ph} could not be sealed to ${send.peerId}")
                all = false
            }
        }
        return all
    }

    private companion object {
        // How many call ids (and id/phase/gen keys) are remembered: far more than a phone sees inside a custody TTL.
        const val MAX_REMEMBERED = 256

        // How long a kept tombstone outlives its process (C-3.4-3): as long as a late copy of the call's OFFER can still
        // arrive, from a carrier's custody or a spool's copy of the DM scope.
        val TOMB_KEEP_MS = maxOf(ForwardRepository.DEFAULT_TTL_MS, ScopeRegistry.DEFAULT_TTL_MS)

        // A CANDS can beat the peer's SDP being applied; this many lines wait for it.
        const val MAX_EARLY_CANDS = 64

        const val MISSED_BUFFER = 16
        const val LOGGED_BUFFER = 32

        // Chunks waiting on the player: a minute of a burst at the chunk length, so a busy player drops none.
        const val TALK_BUFFER = 256

        const val MS_PER_S = 1_000L

        // A cached credential is reused only while it can still start an allocation with time to spare.
        const val CREDENTIAL_MARGIN_S = 60L

        fun <K, V> bounded(): MutableMap<K, V> =
            object : LinkedHashMap<K, V>() {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > MAX_REMEMBERED
            }
    }
}
