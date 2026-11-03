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
 * [BUDGET] for a contact whose answers this hour came to nothing too often. [METERED]: a radio links the pair and one end's
 * network is metered, so no link is made beside it (L-7-11) — the offerer's answer said so, or this end's network is.
 */
enum class LinkOutcome { UP, NO_PATH, NO_ANSWER, NO_SIG, NO_PORT, NOT_QUALIFIED, OFF, BACKOFF, BUSY, SUPERSEDED, DECLINED, BUDGET, METERED }

/**
 * Why this phone offered a link, for the field log (`link_try`'s `why`): the person's try from the debug bridge
 * ([MANUAL]); a message of ours a contact has not ticked ([TICK]); a DM attachment of ours no spool will take ([FILE]); a
 * DM attachment of theirs we want and nobody brings ([WANT]); a walkie call with no radio path ([WALKIE]); an attachment
 * of ours a radio would carry slowly, beside which a free link carries it ([BULK], L-7-11).
 */
enum class LinkWhy { MANUAL, TICK, FILE, WANT, WALKIE, BULK }

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

    /**
     * The bytes queued ahead on the busiest socket of the spools a signal to [peerId] would leave through, or null with
     * none: for the field log, since a signal waits behind them, and past a spool's queue limit is refused there.
     */
    fun queuedAhead(peerId: String): Long? = null
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
 * attempt. A link that loses its path while a file crosses it is not counted as up ([lastUpAt]), so the demand offers
 * again for what it was moving: at once after a move to another network ([onRouteChanged]), after the same backoff
 * after a silence, counted over the links that died that way in a row. A contact's offers are answered as often as it
 * uses the links they make: what is budgeted is an answer that came to nothing — a punch that found no path, or a link
 * that closed without carrying any data — at most [EMPTY_ANSWERS_PER_HOUR] an hour per contact, so a contact cannot keep
 * this phone's modem waking for nothing, while one that writes many times an hour is answered every time. One answer per
 * contact is in flight at once. A contact this person does not want to hear from is blocked: its signals are dropped
 * unopened (`InboundPipeline`), never answered.
 * Nothing here runs while [enabled] says no, but an offer that arrives then is recorded, and answered with a DECLINE when
 * the person has links switched off ([declining], `docs/DIRECT_LINKS.md` L-2-6): the offerer stops offering until it has
 * something newer, rather than waiting out a backoff for an answer that will not come. A DECLINE received is kept
 * ([declinedAt]) until the contact's next offer or link.
 *
 * **Beside a radio** (L-7-11, #236). Each signal says whether its sender's network is metered ([metered], read as it is
 * signed: L-2-7), and a link is **free** when neither end's was: read once, at the offer and the answer, never again,
 * since a new network closes the link anyway ([onRouteChanged]). A link to a peer a radio links ([radioLinked]) is made
 * only free — there the radio carries everything for nothing, and a link is worth its data only for attachments when
 * neither end pays for it. So an answering end on a metered network declines such an offer with `m` set, and an offerer
 * whose answer (or own network) is metered ends the attempt before its punch, both recorded [LinkOutcome.METERED]; a
 * metered refusal received is kept ([meteredAt]), and an attachment's [raiseBulk] waits [METERED_RETRY_MS] past it.
 * [freeLinkTo] says which up links are free. A link to a peer no radio links is made as before, metered or not.
 */
@Suppress("LargeClass") // one phone's links whole: offer, answer, punch, record, and the debug builds' measurements
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
    // Whether this phone's network is metered now (`InternetGate.isMetered`): read once per offer and once per answer,
    // into the signal's `m` (L-2-7) and the link's free stamp, and never again for that link.
    private val metered: () -> Boolean = { false },
    // Whether a radio links this phone to a peer now (`MeshTransport.radioLinked`): a link beside one is made only free.
    private val radioLinked: (peerId: String) -> Boolean = { false },
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

    // Per contact: how many links in a row went silent while a file crossed them, what the next offer's backoff counts.
    // A link that ends any other way clears it, as a new default network does.
    private val lostMidFile = ConcurrentHashMap<String, Int>()

    // Per contact: when each answer that came to nothing ended, and whether an answer to it is in flight now.
    private val empties = ConcurrentHashMap<String, MutableList<Long>>()
    private val answering: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // When the up link this end offered to each contact came up: a contact's offer soon after it crossed ours.
    private val offeredUpAt = ConcurrentHashMap<String, Long>()

    // Per contact: when it last declined an offer of ours, cleared by its next offer or link; and when this phone sent each
    // of its recent declines (at most [DECLINES_PER_HOUR]).
    private val declined = ConcurrentHashMap<String, Long>()
    private val declines = ConcurrentHashMap<String, MutableList<Long>>()

    // Per contact: when it last refused a link beside a radio because a network was metered (a DECLINE with `m`, or an
    // ANSWER with `m` this end dropped), cleared by a free link with it.
    private val meteredRefusals = ConcurrentHashMap<String, Long>()

    // The up link to each contact that is free ([freeLinkTo]): both ends unmetered when it was made.
    private val freeUp = ConcurrentHashMap<String, LinkSession>()

    // The contacts a [raiseBulk] attempt is launched for and not yet ended.
    private val raising: MutableSet<String> = ConcurrentHashMap.newKeySet()

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

    // When each contact's last link to us ended, but for one that lost its path mid-file: what [lastUpAt] reads once it
    // is down.
    private val lastDownAt = ConcurrentHashMap<String, Long>()

    /**
     * When a link to [peerId] was last up: now while one is, else when the last one ended; null when none has been up
     * since this phone started. What a frame sent before it had its chance on (`LinkDemandPolicy`). A link that lost its
     * path while a file crossed it — it went silent, or this phone moved to another network — is not counted: it had no
     * chance at what it was moving, so a row it was raised for calls for a link again.
     */
    fun lastUpAt(peerId: String): Long? = if (links.value.containsKey(peerId)) clock() else lastDownAt[peerId]

    /**
     * When [peerId] last declined an offer of ours (L-2-6), or null: none since its last offer to us or link with us. Its
     * person has links off, so nothing older than the decline is offered for again (`LinkDemandPolicy`).
     */
    fun declinedAt(peerId: String): Long? = declined[peerId]

    /**
     * When [peerId] last refused a link beside a radio because a network was metered (L-7-11), or null: none since its
     * last free link with us. Nothing older than it is offered for again beside a radio (`LinkDemandPolicy`).
     */
    fun meteredAt(peerId: String): Long? = meteredRefusals[peerId]

    /** Whether the link up to [peerId] is free: neither end's network was metered when it was made (L-7-11). */
    fun freeLinkTo(peerId: String): Boolean =
        links.value[peerId]?.let { it.state.value == LinkSession.State.UP && freeUp[peerId] === it } == true

    /**
     * An attachment of ours to [peerId] that a radio would carry slowly asks for a free link to carry it (L-7-11, the
     * composite's `raiseFreeLink`): true when one is up, or an attempt to [peerId] is under way, or one starts now —
     * [LinkWhy.BULK], through [offer]'s own rules. False, so the radio carries it at once, when this phone's network is
     * metered, a link that is not free is up, the contact does not qualify or no sig lane would carry the offer, it is
     * waiting out a backoff, it declined (L-2-6) or refused as metered in the last [METERED_RETRY_MS], or
     * [LinkDemandPolicy.MAX_LINKS] links are up or being offered. Never waits for the attempt.
     */
    suspend fun raiseBulk(peerId: String): Boolean {
        bulkVerdict(peerId)?.let { return it }
        // Marked before the attempt is placed, so a copy waiting on [bulkUnderWay] never reads the gap as an attempt ended.
        raising += peerId
        scope.launch {
            try {
                offer(peerId, why = LinkWhy.BULK)
            } finally {
                raising -= peerId
            }
        }
        return true
    }

    /**
     * Whether the pair qualifies for a link [peerId] may raise for an attachment it holds (L-7-11): the contact may be
     * offered one and a sig lane would carry the offer. Nothing of this end's own state counts — its network, its
     * switch, its backoff: the holder raises on its own, and an asker that counts on a link that does not come only
     * widens later, where one that does not count on one that comes takes a second copy.
     */
    suspend fun mayRaiseBulk(peerId: String): Boolean = peerId != selfId() && peers.mayOffer(peerId) && peers.canSignal(peerId)

    /** Whether a free link to [peerId] is up, or an attempt to it is being made or answered: a waiting copy's answer. */
    fun bulkUnderWay(peerId: String): Boolean =
        freeLinkTo(peerId) || peerId in raising || attempts.containsKey(peerId) || peerId in answering

    // [raiseBulk]'s answer without starting anything: true when a free link is up or an attempt is under way, false when
    // none will come, null when one may start now.
    @Suppress("ReturnCount") // a ladder of refusals, each sending the file over the radio at once
    private suspend fun bulkVerdict(peerId: String): Boolean? {
        if (bulkUnderWay(peerId)) return true
        if (!enabled() || metered() || peerId == selfId()) return false
        if (links.value.containsKey(peerId) || bulkHeldBack(peerId)) return false
        if (!peers.mayOffer(peerId) || !peers.canSignal(peerId)) return false
        return null
    }

    // Whether [peerId] waits before a [raiseBulk] offers it a link: its backoff, a decline (L-2-6) or a recent metered
    // refusal of ours to it (L-7-11), or every link slot taken.
    private fun bulkHeldBack(peerId: String): Boolean {
        val now = clock()
        return now < (nextTryAt[peerId] ?: 0L) ||
            declined.containsKey(peerId) ||
            meteredRefusals[peerId]?.let { now - it < METERED_RETRY_MS } == true ||
            links.value.size + attempts.size >= LinkDemandPolicy.MAX_LINKS
    }

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
        val gatheredMs = clock() - started
        val ownMetered = metered()
        // Crossed while it gathered: the contact's own offer was answered here, which supersedes this one, or is being.
        // Sent anyway, it would reach a contact whose link to us may be up by then, and cost it that link.
        if (synchronized(crossing) { attempt.answer.isCompleted || peerId in answering }) {
            port.close()
            return finish(peerId, attempt, LinkOutcome.SUPERSEDED)
        }
        val offer = signal(LinkSignal.PHASE_OFFER, attempt.linkId, attempt.kOffer, own, ownMetered)
        val queued = peers.queuedAhead(peerId)
        if (!peers.send(peerId, offer)) {
            // Gathered, and then no spool took it: most likely its socket's queue was past the limit (an upload under
            // way), which only this line tells a field log from a missing sig lane.
            port.close()
            record(peerId, true, LinkOutcome.NO_SIG, null, null, clock() - started, own, why = why, queued = queued)
            return finish(peerId, attempt, LinkOutcome.NO_SIG)
        }
        // Where an attempt's seconds went: gathering, sealing and handing the offer to the spools, then the wait.
        val sentMs = clock() - started
        log("link offer → ${peerId.take(SHORT)} gathered in ${gatheredMs}ms (${own.size} candidates), sent at ${sentMs}ms")
        val answer = withTimeoutOrNull(ANSWER_WAIT_MS) { attempt.answer.await() }
        if (answer == null) {
            port.close()
            val superseded = attempt.answer.isCompleted
            val outcome = if (superseded) LinkOutcome.SUPERSEDED else LinkOutcome.NO_ANSWER
            if (!superseded) record(peerId, true, outcome, null, null, clock() - started, own, why = why, sentMs = sentMs, queued = queued)
            return finish(peerId, attempt, outcome)
        }
        log("link answer ← ${peerId.take(SHORT)} at ${clock() - started}ms${meteredTag(answer)}")
        // Beside a radio, a link is made only free (L-7-11): a metered answer, or this end's network, ends it here.
        val free = !(ownMetered || answer.m)
        val refused = refusalOf(peerId, answer, free)
        if (refused != null) {
            port.close()
            attempt.kOffer.fill(0)
            answer.k.fill(0)
            noteRefusal(peerId, refused, answer)
            record(peerId, true, refused, null, LinkNet.of(answer.nt), clock() - started, own, why = why, sentMs = sentMs, queued = queued)
            return finish(peerId, attempt, refused)
        }
        val (send, recv) = keys.derive(true, attempt.kOffer, answer.k, attempt.linkId, selfId(), peerId)
        attempt.kOffer.fill(0)
        answer.k.fill(0)
        val (session, socket) = session(peerId, port, send, recv, attempt.linkId, answer, own)
        val outcome =
            run(
                peerId,
                session,
                socket,
                offerer = true,
                started,
                LinkNet.of(answer.nt),
                own,
                why = why,
                sentMs = sentMs,
                queued = queued,
                free = free,
            )
        return finish(peerId, attempt, outcome)
    }

    // An offer to [peerId] refused before its punch: links off (L-2-6) — nothing older than now is offered for again, and
    // no backoff, since this is not the network's doing and the next thing worth a link asks once more — or, beside a
    // radio, a metered network (L-7-11), kept apart when it was the contact's.
    private fun noteRefusal(
        peerId: String,
        outcome: LinkOutcome,
        answer: LinkSignal,
    ) {
        when {
            outcome == LinkOutcome.DECLINED -> declined[peerId] = clock()
            answer.m -> meteredRefusals[peerId] = clock()
        }
    }

    // How [answer] ends an offer to [peerId] before its punch, or null when it does not: METERED for a DECLINE that says
    // so or an ANSWER whose link would not be [free] beside a radio (L-7-11), DECLINED for any other DECLINE (L-2-6).
    private fun refusalOf(
        peerId: String,
        answer: LinkSignal,
        free: Boolean,
    ): LinkOutcome? =
        when (answer.ph) {
            LinkSignal.PHASE_DECLINE -> if (answer.m) LinkOutcome.METERED else LinkOutcome.DECLINED
            LinkSignal.PHASE_ANSWER -> LinkOutcome.METERED.takeIf { !free && radioLinked(peerId) }
            else -> null
        }

    private fun meteredTag(signal: LinkSignal): String = if (signal.m) " (metered)" else ""

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
        // Beside a radio a link is made only free (L-7-11): from a metered network, nothing to offer.
        if (metered() && radioLinked(peerId)) return LinkOutcome.METERED
        if (!peers.mayOffer(peerId)) return LinkOutcome.NOT_QUALIFIED
        if (!peers.canSignal(peerId)) return LinkOutcome.NO_SIG
        return null
    }

    /**
     * One signal from a contact, opened by the pipeline (`onLinkSignal`); never waits. [sentAt] is the frame's stamp by
     * the sender's clock, logged as the signal's age here: what the spools' lanes took, give or take the clocks' skew.
     */
    fun onSignal(
        senderId: String,
        signal: LinkSignal,
        sentAt: Long? = null,
    ) {
        if (signal.id.size != LinkSignal.ID_BYTES) return
        sentAt?.let { log("link signal ${signal.ph} ← ${senderId.take(SHORT)} ${clock() - it}ms after its stamp") }
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
    fun close(
        peerId: String,
        reason: LinkSession.EndReason = LinkSession.EndReason.LOCAL,
    ) {
        links.value[peerId]?.close(reason)
    }

    /**
     * The up links that could give their slot to a contact waiting for one, by peer: since when each has been idle
     * ([LinkSession.idleSince]). A held link (a walkie's) and one moving a file are never among them.
     */
    fun idleSince(): Map<String, Long> = buildMap { links.value.forEach { (peer, session) -> session.idleSince()?.let { put(peer, it) } } }

    /** Closes every link and drops every pending attempt (the mesh is stopping). */
    fun stop() {
        attempts.values.forEach { it.answer.complete(null) }
        links.value.values.forEach { it.close() }
    }

    /**
     * A new default network: an old route's failures say nothing about this one, so every backoff starts over; and a link
     * whose port is on the network the phone left closes now ([LinkSession.EndReason.ROUTE]) rather than wait out its
     * silence, with a BYE that network may still carry, so the next offer goes out from the new one at once.
     */
    fun onRouteChanged() {
        failures.clear()
        nextTryAt.clear()
        lostMidFile.clear()
        links.value.values.forEach { if (!it.onDefaultNetwork()) it.close(LinkSession.EndReason.ROUTE) }
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
        // Read once for this answer: its `m`, the link's free stamp, and whether it is made at all beside a radio.
        val ownMetered = metered()
        if (ownMetered && radioLinked(offererId)) return passMetered(offererId, offer)
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
            answerNow(offererId, offer, ownMetered)
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

    // An offer from a contact a radio links, while this phone's network is metered: no link is made beside the radio
    // (L-7-11). A DECLINE with `m` says so — the offerer stops at once, and its links stay on for anything else — within
    // the same hourly budget as any decline; past it, nothing, and the offer waits out its answer.
    private suspend fun passMetered(
        offererId: String,
        offer: LinkSignal,
    ) {
        if (!mayDecline(offererId)) return held(offererId, offer, LinkOutcome.METERED)
        noteDecline(offererId)
        val decline = LinkSignal(LinkSignal.PHASE_DECLINE, offer.id.copyOf(), ByteArray(0), nt = datagrams.networkKind(), m = true)
        val sent = peers.send(offererId, decline)
        held(offererId, offer, if (sent) LinkOutcome.METERED else LinkOutcome.NO_SIG)
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
        ownMetered: Boolean,
    ) {
        links.value[offererId]?.close(LinkSession.EndReason.LOCAL, sayBye = false)
        val started = clock()
        val port = datagrams.open() ?: return
        val own = LinkGather.candidates(port, peers.stunUrls(), clock, random)
        val kAnswer = bytes(LinkSignal.KEY_BYTES)
        val (send, recv) = keys.derive(false, offer.k, kAnswer, offer.id, offererId, selfId())
        offer.k.fill(0)
        val (session, socket) = session(offererId, port, send, recv, offer.id, offer, own)
        val reply = signal(LinkSignal.PHASE_ANSWER, offer.id, kAnswer, own, ownMetered)
        kAnswer.fill(0)
        session.start()
        if (!peers.send(offererId, reply)) {
            session.close(LinkSession.EndReason.LOCAL, sayBye = false)
            record(offererId, false, LinkOutcome.NO_SIG, null, LinkNet.of(offer.nt), clock() - started, own)
            return
        }
        val free = !ownMetered && !offer.m
        run(offererId, session, socket, offerer = false, started, LinkNet.of(offer.nt), own, alreadyStarted = true, free = free)
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
                    // Nothing keeps a measured path warm: a keepalive would refresh the very mappings the gaps time.
                    holdKeepaliveMs = FOREVER_MS,
                    holdSilenceMs = FOREVER_MS,
                    dataIsUse = dataIsUse,
                    busy = { linkBusy(peerId) },
                    onPathUpgrade = pathUpgraded(peerId),
                )
            } else {
                LinkSession(
                    peerId,
                    port,
                    lane,
                    targets,
                    scope,
                    clock,
                    dataIsUse = dataIsUse,
                    busy = { linkBusy(peerId) },
                    onPathUpgrade = pathUpgraded(peerId),
                )
            }
        // Before the session starts, so nothing the peer sends once the path is up is missed.
        return session to streams.open(session)
    }

    // A link to [peerId] moved onto a path on this phone's network after it came up another way (L-4-3).
    private fun pathUpgraded(peerId: String): (LinkCandidate.Kind?, LinkCandidate.Kind, Long) -> Unit =
        { from, to, afterMs ->
            log("link ${peerId.take(SHORT)}: path ${LinkPath.valueOf(to.name)} (from ${pathOf(from)}, +${afterMs}ms)")
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

    // Punches, records the try, and on success holds the link in [links] until it closes ([holdUntilClosed]).
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
        sentMs: Long? = null,
        queued: Long? = null,
        free: Boolean = false,
    ): LinkOutcome {
        if (!alreadyStarted) session.start()
        val outcome = session.awaitOutcome()
        if (outcome !is LinkSession.Outcome.Up) {
            val ms = clock() - startedAt
            record(peerId, offerer, LinkOutcome.NO_PATH, null, peerNet, ms, own, why = why, sentMs = sentMs, queued = queued)
            if (!offerer) noteEmpty(peerId)
            return LinkOutcome.NO_PATH
        }
        val path = pathOf(outcome.kind)
        record(peerId, offerer, LinkOutcome.UP, path, peerNet, clock() - startedAt, own, outcome.rttMs, why, sentMs, queued)
        val upAt = clock()
        sockets[peerId] = socket
        if (offerer) offeredUpAt[peerId] = upAt else offeredUpAt.remove(peerId)
        declined.remove(peerId)
        // Stamped before the link is listed, so its transport never sees it up and not yet free.
        if (free) {
            freeUp[peerId] = session
            meteredRefusals.remove(peerId)
        } else {
            freeUp.remove(peerId)
        }
        _links.update { it + (peerId to session) }
        onStream?.invoke(peerId, socket, offerer) ?: drain(peerId, socket)
        scope.launch { holdUntilClosed(peerId, session, socket, offerer, upAt) }
        return LinkOutcome.UP
    }

    // Holds the up link in [links] until it closes, then records its end.
    private suspend fun holdUntilClosed(
        peerId: String,
        session: LinkSession,
        socket: LinkSocket,
        offerer: Boolean,
        upAt: Long,
    ) {
        session.state.first { it == LinkSession.State.CLOSED }
        val reason = session.endReason ?: LinkSession.EndReason.LOCAL
        // Stamped before the link leaves [links], so [lastUpAt] never reads an older end in between.
        if (session.endedBusy && reason in LOST_PATH) pathLost(peerId, reason) else ended(peerId)
        sockets.remove(peerId, socket)
        freeUp.remove(peerId, session)
        _links.update { if (it[peerId] === session) it - peerId else it }
        val (bytesIn, bytesOut) = session.bytes()
        val midFile = if (session.endedBusy) " mid-file" else ""
        val path = pathOf(session.pathKind)
        log("link end ${peerId.take(SHORT)} $reason$midFile after ${clock() - upAt}ms in=$bytesIn out=$bytesOut on $path")
        fieldLog.record(FieldEvent.LinkEnd(peerId, offerer, reason, clock() - upAt, bytesIn, bytesOut, path))
        if (!offerer && !session.carriedData) noteEmpty(peerId)
    }

    // A link to [peerId] ended having had its chance at what it carried: rows older than now wait for something newer.
    private fun ended(peerId: String) {
        lastDownAt[peerId] = clock()
        lostMidFile.remove(peerId)
    }

    // A link to [peerId] lost its path while a file crossed it: [lastUpAt] keeps the end before it, so the file's row calls
    // for a link again. After a move to another network at once; after a silence once the backoff of the links lost this
    // way in a row is waited out, so a path that drops every file is not raised again every minute for a day.
    private fun pathLost(
        peerId: String,
        reason: LinkSession.EndReason,
    ) {
        if (reason != LinkSession.EndReason.SILENT) return
        val n = lostMidFile.merge(peerId, 1, Int::plus) ?: 1
        nextTryAt[peerId] = clock() + PunchPolicy.backoffMs(n)
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
        sentMs: Long? = null,
        queued: Long? = null,
    ) {
        val rtt = rttMs?.let { " rtt ${it}ms" }.orEmpty()
        log("link $outcome ${if (offerer) "→" else "←"} ${peerId.take(SHORT)} ${path ?: ""} ${ms}ms$rtt ${why ?: ""}")
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
                sentMs = sentMs,
                queuedBytes = queued,
            ),
        )
    }

    private fun signal(
        phase: Int,
        linkId: ByteArray,
        k: ByteArray,
        own: List<LinkCandidate>,
        meteredNow: Boolean,
    ) = LinkSignal(
        ph = phase,
        id = linkId.copyOf(),
        k = k.copyOf(),
        c = own.map { LinkCand(it.address, it.port, it.kind.code) },
        nt = datagrams.networkKind(),
        m = meteredNow,
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

        /**
         * How long an attachment's [raiseBulk] leaves a contact alone after it refused a link beside a radio as metered
         * (L-7-11): a burst of photos sends one offer, not one each, and a network that stopped being metered is tried
         * again within this.
         */
        const val METERED_RETRY_MS = 10 * 60_000L

        // The ends of a link that lost its path, rather than closed: mid-file, it had no chance at the file.
        private val LOST_PATH = setOf(LinkSession.EndReason.SILENT, LinkSession.EndReason.ROUTE)

        private const val HOUR_MS = 60 * 60_000L
        private const val SECOND_MS = 1_000L

        // A bulk measurement's write, and a drain's read, at a time.
        private const val BULK_CHUNK = 64 * 1024

        /** How long an echo's reply, or an asked-for echo past its silence, is waited for. */
        const val ECHO_WAIT_MS = 5_000L

        // A quiet link's keepalive and silence bound: never, within any trial's life.
        private const val FOREVER_MS = 365L * 24 * 60 * 60_000L
        private const val SHORT = 6

        /** The path a target of [kind] is, or [LinkPath.PEER_REFLEXIVE] for an address the peer never listed. */
        fun pathOf(kind: LinkCandidate.Kind?): LinkPath = kind?.let { LinkPath.valueOf(it.name) } ?: LinkPath.PEER_REFLEXIVE

        /** [cand] as a candidate this phone may send to, or null when its kind is unknown. */
        fun candidate(cand: LinkCand): LinkCandidate? = LinkCandidate.Kind.fromCode(cand.t)?.let { LinkCandidate(cand.a, cand.p, it) }
    }
}
