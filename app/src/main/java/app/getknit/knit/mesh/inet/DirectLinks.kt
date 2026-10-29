package app.getknit.knit.mesh.inet

import app.getknit.knit.fieldlog.FieldEvent
import app.getknit.knit.fieldlog.FieldRecorder
import app.getknit.knit.mesh.crypto.LinkKeys
import app.getknit.knit.mesh.link.LinkSocket
import app.getknit.knit.mesh.protocol.LinkCand
import app.getknit.knit.mesh.protocol.LinkSignal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * How a direct link attempt ended, for the field log and the debug bridge. [NO_SIG]: no connected spool that carries the
 * pair's DM scope advertises sigs, the one lane a signal rides (C-7.8-17), so nothing was sealed or sent. [DECLINED]: the
 * contact's person has links switched off, and said so (L-2-6) — or, on the answering end, this phone said so. On the
 * answering end, an offer held back is recorded too: [OFF] for low-power mode or a pause, which say nothing back, and
 * [BUDGET] for a contact whose answers this hour came to nothing too often.
 */
enum class LinkOutcome { UP, NO_PATH, NO_ANSWER, NO_SIG, NO_PORT, NOT_QUALIFIED, OFF, BACKOFF, BUSY, SUPERSEDED, DECLINED, BUDGET }

/**
 * Why this phone offered a link, for the field log (`link_try`'s `why`): the person's try from the debug bridge
 * ([MANUAL]); a message of ours a contact has not ticked ([TICK]); a DM attachment of ours no spool will take ([FILE]); a
 * DM attachment of theirs we want and nobody brings ([WANT]); a walkie call with no radio path ([WALKIE]).
 */
enum class LinkWhy { MANUAL, TICK, FILE, WANT, WALKIE }

/** The path a link came up on: a candidate's kind, or an address the peer never listed. */
enum class LinkPath { V6_TEMPORARY, V6, HOST, V4_REFLEXIVE, PEER_REFLEXIVE }

/** The network an end was on, as [LinkSignal.nt] names it. */
enum class LinkNet {
    OTHER,
    WIFI,
    CELLULAR,
    ;

    companion object {
        fun of(code: Int): LinkNet =
            when (code) {
                LinkSignal.NET_WIFI -> WIFI
                LinkSignal.NET_CELLULAR -> CELLULAR
                else -> OTHER
            }
    }
}

/** What direct links need from the rest of the app: who qualifies, the spools' sig lane, and their STUN servers. */
interface LinkPeers {
    /**
     * Whether [peerId] may be offered a link now: a confirmed DM session (never our own unanswered reset), a contact
     * this person wrote to or verified, and a profile carrying `Protocol.CAP_INET_LINK` — never this phone itself.
     */
    suspend fun mayOffer(peerId: String): Boolean

    /** Whether a signal to [peerId] would be taken now, as a sig by a connected spool that advertises sigs (C-7.8-17). */
    fun canSignal(peerId: String): Boolean

    /** The connected spools' STUN URLs, already narrowed by `call/StunServers`. */
    fun stunUrls(): List<String>

    /** Seals and sends [signal] to [peerId] as a sig; false when nothing took it (nothing is sealed then). */
    suspend fun send(
        peerId: String,
        signal: LinkSignal,
    ): Boolean
}

/** The lane's two keys for one attempt, this end's send key first: [STANDARD] is `mesh/crypto/LinkKeys` (L-5-1). */
fun interface LaneKeys {
    @Suppress("LongParameterList") // the derivation's inputs, each named by the spec
    fun derive(
        offerer: Boolean,
        kOffer: ByteArray,
        kAnswer: ByteArray,
        linkId: ByteArray,
        offererId: String,
        answererId: String,
    ): Pair<ByteArray, ByteArray>

    companion object {
        val STANDARD =
            LaneKeys { offerer, kOffer, kAnswer, linkId, offererId, answererId ->
                val role = if (offerer) LinkKeys.Role.OFFERER else LinkKeys.Role.ANSWERER
                LinkKeys.lane(role, kOffer, kAnswer, linkId, offererId, answererId)
            }
    }
}

/**
 * The direct links of this phone (ADR 2026-10.a5yj, the Phase 0 spike): it offers a link to a contact, answers a
 * contact's offer, runs each attempt's [LinkSession], and writes one `link_try` line per attempt and one `link_end` per
 * link that came up. Pure above its seams ([LinkDatagrams], [LinkPeers], [LaneKeys]), so `DirectLinksTest` runs two of
 * them against each other over `FakeLinkNet`.
 *
 * An attempt is one link id: the offer is sent once under it, the contact answers it once, and the first ANSWER that
 * opens binds it; a retry is a new link id with fresh keys, never this one again (the lane's nonce rule). Two offers
 * that cross are settled by the node ids: the lower id's offer stands and the other side answers it, and an offer the
 * lower id gets from the higher within [ANSWER_WAIT_MS] of its own link coming up crossed it too (it is dropped, never
 * answered at the cost of that link); an offer that finds its own attempt superseded while it gathered never leaves. A
 * walkie call's [raise] makes both ends offer at once, so the crossing is the rule there, not a rarity. A contact that
 * does not answer within [ANSWER_WAIT_MS], or whose punch finds no path, waits [PunchPolicy.backoffMs] before the next
 * attempt. A contact's offers are answered as often as it uses the links they make: what is budgeted is an answer that
 * came to nothing — a punch that found no path, or a link that closed without carrying any data — at most
 * [EMPTY_ANSWERS_PER_HOUR] an hour per contact, so a contact cannot keep this phone's modem waking for nothing, while one
 * that writes many times an hour is answered every time. One answer per contact is in flight at once. A contact this
 * person does not want to hear from is blocked: its signals are dropped unopened (`InboundPipeline`), never answered.
 * Nothing here runs while [enabled] says no, but an offer that arrives then is recorded, and answered with a DECLINE when
 * the person has links switched off ([declining], `docs/DIRECT_LINKS.md` L-2-6): the offerer stops offering until it has
 * something newer, rather than waiting out a backoff for an answer that will not come. A DECLINE received is kept
 * ([declinedAt]) until the contact's next offer or link.
 */
class DirectLinks(
    private val selfId: suspend () -> String,
    private val datagrams: LinkDatagrams,
    private val peers: LinkPeers,
    private val keys: LaneKeys = LaneKeys.STANDARD,
    private val enabled: () -> Boolean,
    private val scope: CoroutineScope,
    // Whether an offer gets a DECLINE back (L-2-6): the person has links switched off. Low-power mode and a pause make
    // [enabled] false too, but say nothing: they pass, and the offerer's backoff waits them out.
    private val declining: () -> Boolean = { false },
    private val clock: () -> Long,
    private val streams: LinkStream = LinkStream.arq(scope, clock),
    // What takes each link's stream once the link is up — the transport that runs `FramedLink` over it, told whether
    // this end offered (it writes the first HELLO). Null (Phase 0), the stream is drained here and every DATA is use.
    private val onStream: ((peerId: String, socket: LinkSocket, offerer: Boolean) -> Unit)? = null,
    // Whether that transport is moving a file over the link to a peer now: a link mid-file never idles out.
    private val linkBusy: (peerId: String) -> Boolean = { false },
    private val fieldLog: FieldRecorder = FieldRecorder { },
    private val log: (String) -> Unit = {},
    private val random: java.util.Random = SecureRandom(),
) {
    private class Attempt(
        val linkId: ByteArray,
        val kOffer: ByteArray,
        val answer: CompletableDeferred<LinkSignal?> = CompletableDeferred(),
    )

    private val attempts = ConcurrentHashMap<String, Attempt>()
    private val failures = ConcurrentHashMap<String, Int>()
    private val nextTryAt = ConcurrentHashMap<String, Long>()

    // Per contact: when each answer that came to nothing ended, and whether an answer to it is in flight now.
    private val empties = ConcurrentHashMap<String, MutableList<Long>>()
    private val answering: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // When the up link this end offered to each contact came up: a contact's offer soon after it crossed ours.
    private val offeredUpAt = ConcurrentHashMap<String, Long>()

    // Per contact: when it last declined an offer of ours, cleared by its next offer or link; and when this phone sent each
    // of its recent declines (at most [DECLINES_PER_HOUR]).
    private val declined = ConcurrentHashMap<String, Long>()
    private val declines = ConcurrentHashMap<String, MutableList<Long>>()

    // A live session's asks ([raise]) per contact: the last one, and the first of the run it belongs to.
    private val lastAskAt = ConcurrentHashMap<String, Long>()
    private val asksSince = ConcurrentHashMap<String, Long>()

    // Taken by [offer] placing its attempt and checking it before the send, and by [answer] deciding a crossing: each
    // reads the other's half whole — an attempt placed, or an answer under way — never one half-made.
    private val crossing = Any()

    /** One attempt's result as its `link_try` line has it, kept per peer for the debug bridge and Diagnostics. */
    class Try(
        val at: Long,
        val offerer: Boolean,
        val outcome: LinkOutcome,
        val path: LinkPath?,
        val peerNet: LinkNet?,
        val ms: Long,
        val rttMs: Long?,
        val why: LinkWhy? = null,
    )

    private val lastTries = ConcurrentHashMap<String, Try>()

    /** The last attempt with each peer, offered or answered. */
    fun lastTries(): Map<String, Try> = HashMap(lastTries)

    /**
     * Debug-only (the gap measurement): links made while this is set never idle out or close for silence, and send no
     * keepalive, so an idle gap is idle on the wire. Memory only; a restart clears it. Both ends must set it before the
     * link is made.
     */
    @Volatile
    var quiet: Boolean = false

    /** One gap of [measureGaps]: the outbound echo's round trip and the asked-for inbound echo's arrival, null when lost. */
    class Gap(
        val gapMs: Long,
        val outRttMs: Long?,
        val inMs: Long?,
        val roamed: Boolean,
    )

    private val gaps = java.util.concurrent.CopyOnWriteArrayList<Gap>()

    @Volatile
    private var measuring = false

    /** The gaps measured so far, and whether a measurement is still running. */
    fun gaps(): Pair<List<Gap>, Boolean> = gaps.toList() to measuring

    /**
     * Measures how long the up link to [peerId] survives silence (debug builds; ADR 2026-10.a5yj's keepalive question).
     * For each gap in [gapsMs], in order: nothing for the gap, then one echo out (does the peer's side still admit us?),
     * then one echo asked of the peer after the same silence on both ends (does ours still admit it?). Runs on its own;
     * [gaps] reads the results. False when no quiet link to [peerId] is up, or a measurement is already running.
     */
    fun measureGaps(
        peerId: String,
        gapsMs: List<Long>,
    ): Boolean {
        val session = links.value[peerId]?.takeIf { it.state.value == LinkSession.State.UP } ?: return false
        if (!quiet || measuring) return false
        measuring = true
        gaps.clear()
        scope.launch {
            try {
                for (gap in gapsMs) {
                    kotlinx.coroutines.delay(gap)
                    val out = session.echo(ECHO_WAIT_MS)
                    val inbound = session.echoAfter(gap, ECHO_WAIT_MS)
                    gaps += Gap(gap, out, inbound?.first?.minus(gap), inbound?.second == true)
                    log(
                        "link gap ${gap / SECOND_MS}s out=${out ?: "lost"} in=${inbound?.first?.minus(
                            gap,
                        ) ?: "lost"} roamed=${inbound?.second}",
                    )
                    if (session.state.value != LinkSession.State.UP) break
                }
            } finally {
                measuring = false
            }
        }
        return true
    }

    /** One [measureBulk] run: [bytes] written and acknowledged in [ms], and the stream's counters at its end. */
    class Bulk(
        val bytes: Long,
        val ms: Long,
        val done: Boolean,
        val segments: Long,
        val retransmits: Long,
        val srttMs: Long?,
        val cwnd: Int,
        val lossEvents: Long = 0,
        val timeouts: Long = 0,
        val spurious: Long = 0,
    )

    @Volatile
    private var lastBulk: Bulk? = null

    /** The last [measureBulk] result, and what each link's stream has delivered here ([onStream] unset), by peer. */
    fun bulk(): Pair<Bulk?, Map<String, Long>> = lastBulk to HashMap(drained)

    private val drained = ConcurrentHashMap<String, Long>()

    /**
     * Measures the stream over the up link to [peerId] (debug builds; the reliability layer's trial): writes [bytes] of
     * noise, waits until all of it is acknowledged, and keeps the time with the stream's counters for [bulk]. The peer's
     * end must drain what it reads, as it does while no transport takes its streams. False when no link to [peerId] is up
     * on our own stream, or a measurement is already running.
     */
    fun measureBulk(
        peerId: String,
        bytes: Int,
    ): Boolean {
        // Never under a transport: noise written into its stream would break the framing it reads.
        if (onStream != null) return false
        val arq = sockets[peerId] as? LinkArq ?: return false
        if (measuring) return false
        measuring = true
        lastBulk = null
        scope.launch {
            try {
                val before = arq.stats()
                val started = clock()
                val noise = ByteArray(BULK_CHUNK).also(random::nextBytes)
                var left = bytes
                while (left > 0) {
                    val n = minOf(left, noise.size)
                    arq.write(noise, 0, n)
                    left -= n
                }
                arq.flush()
                val done = arq.awaitDrained()
                val after = arq.stats()
                lastBulk =
                    Bulk(
                        bytes = after.bytesAcked - before.bytesAcked,
                        ms = clock() - started,
                        done = done,
                        segments = after.segmentsSent - before.segmentsSent,
                        retransmits = after.retransmits - before.retransmits,
                        srttMs = after.srttMs,
                        cwnd = after.cwnd,
                        lossEvents = after.lossEvents - before.lossEvents,
                        timeouts = after.timeouts - before.timeouts,
                        spurious = after.spurious - before.spurious,
                    )
                log("link bulk ${peerId.take(SHORT)} ${lastBulk?.bytes}B in ${lastBulk?.ms}ms done=$done")
            } catch (e: java.io.IOException) {
                log("link bulk ${peerId.take(SHORT)} ended: ${e.message}")
            } finally {
                measuring = false
            }
        }
        return true
    }

    // The stream of each link up now, by peer.
    private val sockets = ConcurrentHashMap<String, LinkSocket>()

    private val _links = MutableStateFlow<Map<String, LinkSession>>(emptyMap())

    /** The links up now, by peer. */
    val links: StateFlow<Map<String, LinkSession>> = _links.asStateFlow()

    // When each contact's last link to us ended: what [lastUpAt] reads once it is down.
    private val lastDownAt = ConcurrentHashMap<String, Long>()

    /**
     * When a link to [peerId] was last up: now while one is, else when the last one ended; null when none has been up
     * since this phone started. What a frame sent before it had its chance on (`LinkDemandPolicy`).
     */
    fun lastUpAt(peerId: String): Long? = if (links.value.containsKey(peerId)) clock() else lastDownAt[peerId]

    /**
     * When [peerId] last declined an offer of ours (L-2-6), or null: none since its last offer to us or link with us. Its
     * person has links off, so nothing older than the decline is offered for again (`LinkDemandPolicy`).
     */
    fun declinedAt(peerId: String): Long? = declined[peerId]

    /**
     * Offers [peerId] a link and runs the attempt to its outcome. [force] skips the backoff (the debug bridge's try);
     * [why] is what called for it, for the field log.
     */
    @Suppress("ReturnCount", "LongMethod") // a ladder of refusals ahead of the attempt, and its three answers
    suspend fun offer(
        peerId: String,
        force: Boolean = false,
        why: LinkWhy = LinkWhy.MANUAL,
    ): LinkOutcome {
        refusal(peerId, force)?.let { return it }
        val attempt = Attempt(bytes(LinkSignal.ID_BYTES), bytes(LinkSignal.KEY_BYTES))
        val placed = synchronized(crossing) { peerId !in answering && attempts.putIfAbsent(peerId, attempt) == null }
        if (!placed) return LinkOutcome.BUSY
        val started = clock()
        val port = datagrams.open() ?: return finish(peerId, attempt, LinkOutcome.NO_PORT)
        val own = LinkGather.candidates(port, peers.stunUrls(), clock, random)
        // Crossed while it gathered: the contact's own offer was answered here, which supersedes this one, or is being.
        // Sent anyway, it would reach a contact whose link to us may be up by then, and cost it that link.
        if (synchronized(crossing) { attempt.answer.isCompleted || peerId in answering }) {
            port.close()
            return finish(peerId, attempt, LinkOutcome.SUPERSEDED)
        }
        val offer = signal(LinkSignal.PHASE_OFFER, attempt.linkId, attempt.kOffer, own)
        if (!peers.send(peerId, offer)) {
            port.close()
            return finish(peerId, attempt, LinkOutcome.NO_SIG)
        }
        val answer = withTimeoutOrNull(ANSWER_WAIT_MS) { attempt.answer.await() }
        if (answer == null) {
            port.close()
            val superseded = attempt.answer.isCompleted
            val outcome = if (superseded) LinkOutcome.SUPERSEDED else LinkOutcome.NO_ANSWER
            if (!superseded) record(peerId, true, outcome, null, null, clock() - started, own, why = why)
            return finish(peerId, attempt, outcome)
        }
        if (answer.ph == LinkSignal.PHASE_DECLINE) {
            // Its person has links off: nothing older than now is offered for again, and no backoff — this is not the
            // network's doing, and the next thing worth a link asks once more.
            port.close()
            attempt.kOffer.fill(0)
            declined[peerId] = clock()
            record(peerId, true, LinkOutcome.DECLINED, null, LinkNet.of(answer.nt), clock() - started, own, why = why)
            return finish(peerId, attempt, LinkOutcome.DECLINED)
        }
        val (send, recv) = keys.derive(true, attempt.kOffer, answer.k, attempt.linkId, selfId(), peerId)
        attempt.kOffer.fill(0)
        answer.k.fill(0)
        val (session, socket) = session(peerId, port, send, recv, attempt.linkId, answer, own)
        val outcome = run(peerId, session, socket, offerer = true, started, LinkNet.of(answer.nt), own, why = why)
        return finish(peerId, attempt, outcome)
    }

    // Why [offer] would not start an attempt to [peerId] now, or null when it would.
    @Suppress("ReturnCount") // a ladder of refusals, each its own outcome
    private suspend fun refusal(
        peerId: String,
        force: Boolean,
    ): LinkOutcome? {
        if (!enabled() || peerId == selfId()) return LinkOutcome.OFF
        if (links.value[peerId]?.state?.value == LinkSession.State.UP) return LinkOutcome.UP
        if (peerId in answering) return LinkOutcome.BUSY
        if (!force && clock() < (nextTryAt[peerId] ?: 0L)) return LinkOutcome.BACKOFF
        if (!peers.mayOffer(peerId)) return LinkOutcome.NOT_QUALIFIED
        if (!peers.canSignal(peerId)) return LinkOutcome.NO_SIG
        return null
    }

    /** One signal from a contact, opened by the pipeline (`onLinkSignal`); never waits. */
    fun onSignal(
        senderId: String,
        signal: LinkSignal,
    ) {
        if (signal.id.size != LinkSignal.ID_BYTES) return
        val keyed = signal.k.size == LinkSignal.KEY_BYTES
        when (signal.ph) {
            LinkSignal.PHASE_OFFER -> {
                if (keyed) scope.launch { answer(senderId, signal) }
            }

            // An ANSWER or a DECLINE ends the attempt it names; a DECLINE carries no key (L-2-6).
            LinkSignal.PHASE_ANSWER, LinkSignal.PHASE_DECLINE -> {
                if (keyed == (signal.ph == LinkSignal.PHASE_ANSWER)) {
                    attempts[senderId]?.takeIf { it.linkId.contentEquals(signal.id) }?.answer?.complete(signal)
                }
            }
        }
    }

    /**
     * Holds the up link to [peerId] through idle pauses (a live session such as a walkie: keepalives every
     * [LinkSession.HOLD_KEEPALIVE_MS] instead of an idle close), or releases it to idle out. False when none is up.
     */
    fun hold(
        peerId: String,
        on: Boolean,
    ): Boolean {
        val session = links.value[peerId]?.takeIf { it.state.value == LinkSession.State.UP } ?: return false
        session.hold(on)
        return true
    }

    /**
     * A live session's ask for its link to [peerId] — a walkie call's, through `MeshTransport.holdLink`, renewed every
     * few seconds from the ring to the hang-up (ADR 2026-10.a5yj's walkie amendment): an up link is held for
     * [HOLD_TTL_MS] past this ask, so the hold ends on its own once the asks stop; with none up, an attempt starts —
     * through [offer]'s own refusals, backoff and the contact's answer budget — unless one is under way. Never waits.
     * True while a link is held.
     */
    fun raise(peerId: String): Boolean {
        val now = clock()
        // A run of asks is one session's: asks that stopped for longer than a hold start a new run.
        val last = lastAskAt.put(peerId, now)
        if (last == null || now - last > HOLD_TTL_MS) asksSince[peerId] = now
        val session = links.value[peerId]?.takeIf { it.state.value == LinkSession.State.UP }
        if (session != null) {
            session.holdFor(HOLD_TTL_MS)
            return true
        }
        // A contact that declined during this run said so for this session; the next session asks once more (L-2-6).
        val declinedThisRun = declined[peerId]?.let { it >= (asksSince[peerId] ?: now) } == true
        if (enabled() && !declinedThisRun && !attempts.containsKey(peerId)) scope.launch { offer(peerId, why = LinkWhy.WALKIE) }
        return false
    }

    /** A frame or a file moved over the link to [peerId] (its transport says so): the link is in use. */
    fun noteUse(peerId: String) {
        links.value[peerId]?.noteUse()
    }

    /** Closes the link to [peerId], if one is up, with a BYE. */
    fun close(peerId: String) {
        links.value[peerId]?.close()
    }

    /** Closes every link and drops every pending attempt (the mesh is stopping). */
    fun stop() {
        attempts.values.forEach { it.answer.complete(null) }
        links.value.values.forEach { it.close() }
    }

    /** A new default network: an old route's failures say nothing about this one, so every backoff starts over. */
    fun onRouteChanged() {
        failures.clear()
        nextTryAt.clear()
    }

    @Suppress("ReturnCount") // a ladder of refusals ahead of the answer
    private suspend fun answer(
        offererId: String,
        offer: LinkSignal,
    ) {
        val me = selfId()
        if (offererId == me) return
        // An offer is the contact's word that its links are on: a decline of theirs is history.
        declined.remove(offererId)
        if (!enabled()) return passOn(offererId, offer)
        // Two offers crossed: the lower id's stands. Ours stands — theirs is dropped, and they answer ours — whether ours
        // still waits or its link came up in the last [ANSWER_WAIT_MS]: theirs may have left before they answered ours,
        // and answering it would close the link they hold. Later, an offer is a link they lost, and replaces ours.
        val ours =
            synchronized(crossing) {
                val ours = attempts[offererId]
                if (me < offererId && (ours != null || offeredLately(offererId))) return
                if (!answering.add(offererId)) return
                ours
            }
        try {
            if (!mayAnswer(offererId)) return held(offererId, offer, LinkOutcome.BUDGET)
            ours?.answer?.complete(null)
            answerNow(offererId, offer)
        } finally {
            answering.remove(offererId)
        }
    }

    // An offer this phone will not answer, links being off here: a DECLINE when its person switched them off and the
    // contact's declines this hour are not spent (L-2-6), else nothing back; recorded either way.
    private suspend fun passOn(
        offererId: String,
        offer: LinkSignal,
    ) {
        if (!declining() || !mayDecline(offererId)) return held(offererId, offer, LinkOutcome.OFF)
        noteDecline(offererId)
        val decline = LinkSignal(LinkSignal.PHASE_DECLINE, offer.id.copyOf(), ByteArray(0), nt = datagrams.networkKind())
        val sent = peers.send(offererId, decline)
        held(offererId, offer, if (sent) LinkOutcome.DECLINED else LinkOutcome.NO_SIG)
    }

    // An offer this phone did not answer, recorded as its `link_try` line: no candidates offered, no time spent.
    private fun held(
        offererId: String,
        offer: LinkSignal,
        outcome: LinkOutcome,
    ) {
        offer.k.fill(0)
        record(offererId, false, outcome, null, LinkNet.of(offer.nt), 0L, emptyList())
    }

    // Whether the link up to [peerId] is one this end offered, up for less than [ANSWER_WAIT_MS].
    private fun offeredLately(peerId: String): Boolean =
        links.value.containsKey(peerId) && offeredUpAt[peerId]?.let { clock() - it < ANSWER_WAIT_MS } == true

    // The answer itself, once [answer] has let it through: gather, derive, start punching, send the ANSWER, run.
    private suspend fun answerNow(
        offererId: String,
        offer: LinkSignal,
    ) {
        links.value[offererId]?.close(LinkSession.EndReason.LOCAL, sayBye = false)
        val started = clock()
        val port = datagrams.open() ?: return
        val own = LinkGather.candidates(port, peers.stunUrls(), clock, random)
        val kAnswer = bytes(LinkSignal.KEY_BYTES)
        val (send, recv) = keys.derive(false, offer.k, kAnswer, offer.id, offererId, selfId())
        offer.k.fill(0)
        val (session, socket) = session(offererId, port, send, recv, offer.id, offer, own)
        val reply = signal(LinkSignal.PHASE_ANSWER, offer.id, kAnswer, own)
        kAnswer.fill(0)
        session.start()
        if (!peers.send(offererId, reply)) {
            session.close(LinkSession.EndReason.LOCAL, sayBye = false)
            record(offererId, false, LinkOutcome.NO_SIG, null, LinkNet.of(offer.nt), clock() - started, own)
            return
        }
        run(offererId, session, socket, offerer = false, started, LinkNet.of(offer.nt), own, alreadyStarted = true)
    }

    @Suppress("LongParameterList") // the attempt's parts, each from a different step of it
    private suspend fun session(
        peerId: String,
        port: LinkPort,
        send: ByteArray,
        recv: ByteArray,
        linkId: ByteArray,
        peerSignal: LinkSignal,
        own: List<LinkCandidate>,
    ): Pair<LinkSession, LinkSocket> {
        val theirs = peerSignal.c.mapNotNull(::candidate)
        val targets = PunchPolicy.targets(theirs, localV6 = own.any { it.v6 }, localV4 = own.any { !it.v6 })
        val lane = LinkLane(send, recv, LinkLane.aad(linkId, selfId(), peerId))
        send.fill(0)
        recv.fill(0)
        // Under a transport only its frames and files are use ([noteUse]); drained here, every DATA is.
        val dataIsUse = onStream == null
        val session =
            if (quiet) {
                LinkSession(
                    peerId,
                    port,
                    lane,
                    targets,
                    scope,
                    clock,
                    idleMs = FOREVER_MS,
                    silenceMs = FOREVER_MS,
                    dataIsUse = dataIsUse,
                    busy = { linkBusy(peerId) },
                )
            } else {
                LinkSession(peerId, port, lane, targets, scope, clock, dataIsUse = dataIsUse, busy = { linkBusy(peerId) })
            }
        // Before the session starts, so nothing the peer sends once the path is up is missed.
        return session to streams.open(session)
    }

    // A stream no transport takes yet (Phase 0): read and counted, so the peer's writes are never held at the window.
    private fun drain(
        peerId: String,
        socket: LinkSocket,
    ) {
        val arq = socket as? LinkArq
        scope.launch {
            val buf = ByteArray(BULK_CHUNK)
            while (true) {
                val n = arq?.read(buf, 0, buf.size) ?: withContext(Dispatchers.IO) { socket.input.read(buf) }
                if (n < 0) break
                drained.merge(peerId, n.toLong(), Long::plus)
            }
        }
    }

    // Punches, records the try, and on success holds the link in [links] until it closes, then records its end.
    @Suppress("LongParameterList") // the attempt's parts, each from a different step of it
    private suspend fun run(
        peerId: String,
        session: LinkSession,
        socket: LinkSocket,
        offerer: Boolean,
        startedAt: Long,
        peerNet: LinkNet,
        own: List<LinkCandidate>,
        alreadyStarted: Boolean = false,
        why: LinkWhy? = null,
    ): LinkOutcome {
        if (!alreadyStarted) session.start()
        val outcome = session.awaitOutcome()
        if (outcome !is LinkSession.Outcome.Up) {
            record(peerId, offerer, LinkOutcome.NO_PATH, null, peerNet, clock() - startedAt, own, why = why)
            if (!offerer) noteEmpty(peerId)
            return LinkOutcome.NO_PATH
        }
        val path = outcome.kind?.let { LinkPath.valueOf(it.name) } ?: LinkPath.PEER_REFLEXIVE
        record(peerId, offerer, LinkOutcome.UP, path, peerNet, clock() - startedAt, own, outcome.rttMs, why)
        val upAt = clock()
        sockets[peerId] = socket
        if (offerer) offeredUpAt[peerId] = upAt else offeredUpAt.remove(peerId)
        declined.remove(peerId)
        _links.update { it + (peerId to session) }
        onStream?.invoke(peerId, socket, offerer) ?: drain(peerId, socket)
        scope.launch {
            session.state.first { it == LinkSession.State.CLOSED }
            sockets.remove(peerId, socket)
            _links.update { if (it[peerId] === session) it - peerId else it }
            lastDownAt[peerId] = clock()
            val (bytesIn, bytesOut) = session.bytes()
            val reason = session.endReason ?: LinkSession.EndReason.LOCAL
            log("link end ${peerId.take(SHORT)} $reason after ${clock() - upAt}ms in=$bytesIn out=$bytesOut")
            fieldLog.record(FieldEvent.LinkEnd(peerId, offerer, reason, clock() - upAt, bytesIn, bytesOut))
            if (!offerer && !session.carriedData) noteEmpty(peerId)
        }
        return LinkOutcome.UP
    }

    private fun finish(
        peerId: String,
        attempt: Attempt,
        outcome: LinkOutcome,
    ): LinkOutcome {
        attempts.remove(peerId, attempt)
        when (outcome) {
            LinkOutcome.UP -> {
                failures.remove(peerId)
                nextTryAt.remove(peerId)
            }

            LinkOutcome.NO_ANSWER, LinkOutcome.NO_PATH -> {
                val n = failures.merge(peerId, 1, Int::plus) ?: 1
                nextTryAt[peerId] = clock() + PunchPolicy.backoffMs(n)
            }

            // Refused before anything went out, or superseded: nothing the contact's network did.
            else -> {}
        }
        return outcome
    }

    // Whether [peerId]'s answers that came to nothing in the last hour leave room for one more.
    private fun mayAnswer(peerId: String): Boolean {
        val times = empties[peerId] ?: return true
        val now = clock()
        synchronized(times) {
            times.removeAll { now - it >= HOUR_MS }
            return times.size < EMPTY_ANSWERS_PER_HOUR
        }
    }

    private fun mayDecline(peerId: String): Boolean {
        val times = declines[peerId] ?: return true
        val now = clock()
        synchronized(times) {
            times.removeAll { now - it >= HOUR_MS }
            return times.size < DECLINES_PER_HOUR
        }
    }

    private fun noteDecline(peerId: String) {
        val times = declines.getOrPut(peerId) { mutableListOf() }
        synchronized(times) { times += clock() }
    }

    private fun noteEmpty(peerId: String) {
        val times = empties.getOrPut(peerId) { mutableListOf() }
        synchronized(times) { times += clock() }
    }

    @Suppress("LongParameterList") // one argument per column of the line
    private fun record(
        peerId: String,
        offerer: Boolean,
        outcome: LinkOutcome,
        path: LinkPath?,
        peerNet: LinkNet?,
        ms: Long,
        own: List<LinkCandidate>,
        rttMs: Long? = null,
        why: LinkWhy? = null,
    ) {
        log("link $outcome ${if (offerer) "→" else "←"} ${peerId.take(SHORT)} ${path ?: ""} ${ms}ms ${why ?: ""}")
        lastTries[peerId] = Try(clock(), offerer, outcome, path, peerNet, ms, rttMs, why)
        fieldLog.record(
            FieldEvent.LinkTry(
                peer = peerId,
                out = offerer,
                outcome = outcome,
                path = path,
                net = LinkNet.of(datagrams.networkKind()),
                peerNet = peerNet,
                ms = ms,
                rttMs = rttMs,
                v6 = own.any { it.v6 },
                reflexive = own.any { it.kind == LinkCandidate.Kind.V4_REFLEXIVE },
                host = own.any { it.kind == LinkCandidate.Kind.HOST },
                why = why,
            ),
        )
    }

    private fun signal(
        phase: Int,
        linkId: ByteArray,
        k: ByteArray,
        own: List<LinkCandidate>,
    ) = LinkSignal(
        ph = phase,
        id = linkId.copyOf(),
        k = k.copyOf(),
        c = own.map { LinkCand(it.address, it.port, it.kind.code) },
        nt = datagrams.networkKind(),
    )

    private fun bytes(n: Int) = ByteArray(n).also(random::nextBytes)

    companion object {
        /** How long an offer waits for its answer: two signals and the contact's gathering, with room to spare. */
        const val ANSWER_WAIT_MS = 15_000L

        /** Most answers to one contact in an hour that may come to nothing (no path, or a link that carried no data). */
        const val EMPTY_ANSWERS_PER_HOUR = 6

        /**
         * How long a live session's [raise] holds a link past the ask: the walkie's 25 s (docs/VOICE_CALLS.md C-3.11-8),
         * two of its 10 s renewals and some, so one renewal late keeps it and a hang-up lets it go within this.
         */
        const val HOLD_TTL_MS = 25_000L

        /** How many DECLINEs a contact gets from this phone an hour (L-2-6): its offers past that pass unanswered. */
        const val DECLINES_PER_HOUR = 6

        private const val HOUR_MS = 60 * 60_000L
        private const val SECOND_MS = 1_000L

        // A bulk measurement's write, and a drain's read, at a time.
        private const val BULK_CHUNK = 64 * 1024

        /** How long an echo's reply, or an asked-for echo past its silence, is waited for. */
        const val ECHO_WAIT_MS = 5_000L

        // A quiet link's keepalive and silence bound: never, within any trial's life.
        private const val FOREVER_MS = 365L * 24 * 60 * 60_000L
        private const val SHORT = 6

        /** [cand] as a candidate this phone may send to, or null when its kind is unknown. */
        fun candidate(cand: LinkCand): LinkCandidate? = LinkCandidate.Kind.fromCode(cand.t)?.let { LinkCandidate(cand.a, cand.p, it) }
    }
}
