package app.getknit.knit.mesh.bluetooth

/**
 * Whether one Bluetooth link this phone can show is dead should go down (#203, ADR 2026-10.697m): one per link, fed
 * by the transport with the link's [SocketStamps] and its PHY handle's reads, asked by one loop that sleeps until the
 * earliest link's [msUntilDue].
 *
 * Why: on 2026-10-19 a Redmi 13C's stack lost a link's disconnect. The peer logged EOF at 17:07:39; this side kept the
 * link for 36 minutes, until its process died, with its RSSI reads frozen, its writer blocked and three calls through
 * it never ringing. Nothing in Knit read the silence: a `KEEPALIVE` was read and ignored, and no shipped build sent one.
 *
 * Three ways a link is shown dead, each sized so a healthy link never meets it (idle, dozing either side, Coded at
 * range, mid-file, paced):
 * - **Probes, toward a peer that answers.** Each side writes one `KEEPALIVE` at link-up ([announce]); no shipped build
 *   sends one, so receiving it proves the peer runs this rule and marks the link [answers]. A peer that has sent a
 *   `KEEPALIVE` answers each one it receives ([onKeepaliveIn]), except one that meets an announce or probe of its own
 *   still waiting for a keepalive back: that one is its answer, or a probe that crossed it. So two that cross are each
 *   other's answer, an answer is never answered, and every exchange ends however long each keepalive took to cross.
 *   (The design pass's rule — no answer within [Config.answerMs] of writing one — answers an answer whose legs each took
 *   over half that wait, a keepalive behind a full stack buffer, and the pair then bounce for good; it also left a
 *   second probe sent while the first one's answer was crossing unmatched for ever.) On such a link, once no byte has
 *   come in for [Config.quietMs], the first probe rides the next record this side queues to the link anyway ([carry]) —
 *   in practice the 60 s custody re-offer — so the peer, woken by that record already, answers in the same wake and the
 *   probe costs a dozing peer no wake-up (the 2026-10-19 hour asleep: about 40 extra an hour at `bf93901f`). No carrier
 *   within [Config.carrierWaitMs] of being due, on the uptime clock, and it goes on its own ([Trigger.FALLBACK]): the
 *   re-offer is one loop nothing restarts if it throws. An edge still probes at once. No byte in for [Config.answerMs]
 *   after the probe's write returned writes a second, on the timer; after [Config.probes] unanswered, it closes: about
 *   150–210 s from the last byte on this side's awake clock — a live peer's answer wakes this CPU, so the wait for it
 *   runs on the elapsed clock soundly.
 * - **Its own controller, toward a peer that does not answer, on a link with a PHY handle** ([Local]): no byte in for
 *   [Config.quietMs] after the handle's GATT client was told the connection ended. Toward a peer that answers it is
 *   ignored, and only the probes close: the stack can be wrong both ways. Unanswered link-RSSI reads are no evidence at
 *   all: after a 4-minute process freeze a P8's reads timed out 27 in a row on two healthy links (the 2026-10-19 trial).
 *   Never `LinkFade.fading`: its −95 dBm half fades weak but live links on purpose.
 * - **A write that never returns** ([Config.writeStallMs]): the stack's buffer takes a write until its credits run out,
 *   and a segment (≤ 8 KiB) that has not drained in two minutes has no link under it. The only bound toward a peer
 *   with neither of the above.
 *
 * A `CAP_DOORBELL` link (an iPhone) is [exempt] from all three: iOS suspends a backgrounded app with its link up and
 * silent by design, and resumes it for a GATT write, not for L2CAP data (ADR 2026-09.dqvb), so it gets no announce, no
 * probe, no answer and no stall close; its bytes are what they were.
 *
 * Pure and clock-injected: every `now` is the transport's `elapsedRealtime`, and [uptime] its `uptimeMillis`, which the
 * carrier wait alone reads; synchronized, because the reader, the writer, the send paths and the loop all call in.
 */
class LinkSilence(
    /** A `CAP_DOORBELL` link: never announced, probed, answered or closed here. */
    val exempt: Boolean,
    /**
     * The uptime clock (CLOCK_MONOTONIC, stopped while the CPU sleeps): the clock the re-offer's `delay` runs on, so a
     * carrier is waited for as long as the re-offer would take, and this side's own doze never trips the fallback.
     */
    private val uptime: () -> Long,
    private val config: Config = Config(),
) {
    /** The bounds, with why each is what it is on [LinkSilence]'s constants. */
    data class Config(
        val quietMs: Long = QUIET_MS,
        val answerMs: Long = ANSWER_MS,
        val probes: Int = PROBES,
        val writeStallMs: Long = WRITE_STALL_MS,
        val carrierWaitMs: Long = CARRIER_WAIT_MS,
    )

    /** What sent a probe, as its `bt link probe … via=` names it (the transport names a carrier's kind itself). */
    enum class Trigger {
        /** Rode a record this side queued anyway ([carry]). */
        CARRIER,

        /** The second probe, an answer wait after the first. */
        TIMER,

        /** An edge from the stack. */
        EDGE,

        /** Due for [Config.carrierWaitMs] of uptime with no carrier. */
        FALLBACK,
    }

    /** Why a link was closed, as its `bt link silent` line names it. */
    enum class Why(
        val wire: String,
        /** Shown by this phone's own controller, not by the peer's silence: what `bt stack suspect` follows. */
        val local: Boolean,
    ) {
        PROBES("probes", local = false),
        LOST("phy lost", local = true),
        WRITE("write stall", local = false),
    }

    sealed interface Action {
        data object None : Action

        /** Write one `KEEPALIVE` now: the [n]th probe of this silence, sent by [via]. */
        data class Probe(
            val n: Int,
            val via: Trigger,
        ) : Action

        /** Close the link: [why], [quietMs] after its last byte in. */
        data class Close(
            val why: Why,
            val quietMs: Long,
        ) : Action
    }

    /**
     * What the link's PHY handle says since its last byte in: whether its GATT client was told the connection ended after
     * that byte. Null on a link with no handle.
     */
    data class Local(
        val lost: Boolean,
    )

    private enum class Kind { ANNOUNCE, PROBE, ANSWER }

    /** Whether the peer has sent a `KEEPALIVE` on this link, and so answers one. */
    @get:Synchronized
    var answers = false
        private set

    // Our keepalives queued and not yet written, in the writer's order (the link's queue is first in, first out).
    private val queued = ArrayDeque<Kind>()

    // How many of our announces and probes no keepalive in has matched yet: one that comes while any waits is their
    // answer, or a probe that crossed one of them, and is not answered.
    private var awaiting = 0

    // This silence's probes: how many were queued, when the last one's write returned, and when the run began (its first
    // probe, or an edge). A byte in after the run began ends it.
    private var probesSent = 0
    private var probeWrittenAt: Long? = null
    private var runSince: Long? = null

    // A hint that the connection may be gone (an ACL disconnect for the link's address, its PHY client's loss): probe now.
    private var edgeAt: Long? = null

    // When a held-link dial last started a probe ([onHeldDial]).
    private var heldDialAt: Long? = null

    // When, on [uptime], a look first found the first probe due for the silence that began at [dueRxAt]; null before.
    // Not a probe: nothing counts until one is queued. A byte in starts a new silence, and a new wait.
    private var dueSinceUp: Long? = null
    private var dueRxAt: Long? = null

    /** The link came up: true when this side writes its announce now (not when an answer to the peer's went first). */
    @Synchronized
    fun announce(): Boolean {
        if (exempt || queued.isNotEmpty()) return false
        queued.addLast(Kind.ANNOUNCE)
        awaiting++
        return true
    }

    /**
     * A `KEEPALIVE` came in: the peer answers from here on. True when this side writes one back now — when no announce or
     * probe of ours waits for one, so this one is the peer's own probe (or announce).
     */
    @Synchronized
    fun onKeepaliveIn(): Boolean {
        if (exempt) return false
        answers = true
        if (awaiting > 0) {
            awaiting--
            return false
        }
        queued.addLast(Kind.ANSWER)
        return true
    }

    /** A keepalive of ours went to the socket at [now]: a probe's wait for its answer starts here. */
    @Synchronized
    fun onKeepaliveWritten(now: Long) {
        if (queued.removeFirstOrNull() == Kind.PROBE) probeWrittenAt = now
    }

    /**
     * This side just queued a record to the link (a digest, a frame, a file), at [now] with its last byte in at [rxAt]:
     * a [Action.Probe] when the first probe of a silence is due, to queue right behind it. Decided here, at the enqueue,
     * on the whole condition — the peer answers, no probe of this silence went yet, none is queued, and the link has been
     * quiet for [Config.quietMs] — so nothing is armed: a byte that came first means no probe, and an unsent one never
     * counts.
     */
    @Synchronized
    fun carry(
        now: Long,
        rxAt: Long,
    ): Action {
        if (exempt || !answers || Kind.PROBE in queued) return Action.None
        endRunIfAnswered(rxAt)
        return if (probesSent == 0 && now - rxAt >= config.quietMs) probe(now, Trigger.CARRIER) else Action.None
    }

    /** The connection may be gone (an edge from the stack, which names no handle): probe now, never close on it alone. */
    @Synchronized
    fun onEdge(now: Long): Boolean {
        if (exempt) return false
        if (edgeAt == null) edgeAt = now
        return true
    }

    /**
     * A dialer took the held-link row against this link (ADR 2026-11.xp6d, step 5b's W5): the peer redialing it may have
     * lost it, so probe now, as an edge does. True only when this starts a probe run: a peer that answers, no run or
     * probe going, and none started by a held dial in the last [Config.answerMs], so dials (forged ones too, at any rate)
     * cost at most one probe per answer wait and never close the link by themselves. Never on an [exempt] link.
     */
    @Synchronized
    fun onHeldDial(now: Long): Boolean {
        if (exempt || !answers || probing()) return false
        val last = heldDialAt
        if (last != null && now - last < config.answerMs) return false
        heldDialAt = now
        edgeAt = now
        return true
    }

    /** Whether a probe run is going or about to: an edge noted, a probe sent or one queued. */
    private fun probing(): Boolean = edgeAt != null || probesSent > 0 || Kind.PROBE in queued

    /**
     * What to do at [now], given the link's last byte in at [rxAt], the write it sits in since [blockedSince] (null
     * between writes) and its PHY handle's [local] reading. A [Action.Probe] is taken as queued.
     */
    @Synchronized
    fun step(
        now: Long,
        rxAt: Long,
        blockedSince: Long?,
        local: Local?,
    ): Action {
        if (exempt) return Action.None
        val quiet = now - rxAt
        if (blockedSince != null && now - blockedSince >= config.writeStallMs) return Action.Close(Why.WRITE, quiet)
        endRunIfAnswered(rxAt)
        // Toward an older peer our controller's word is all there is. Toward one that answers it is ignored: a stack refuses
        // reads, and an ATT timeout reports a disconnect to every client while the CoC keeps the ACL up, so only the probes
        // close (the bluetooth-reviewer's finding). A client's loss still probes early, as an edge (the transport's).
        if (!answers) localClose(quiet, local)?.let { return it }
        return if (!answers || Kind.PROBE in queued) Action.None else probeStep(now, rxAt, quiet)
    }

    /** A byte in after this silence's run began ends it. */
    private fun endRunIfAnswered(rxAt: Long) {
        val began = runSince ?: edgeAt
        if (began != null && rxAt > began) endRun()
    }

    /** Its own controller's close, once the link has been quiet long enough for one; else null. */
    private fun localClose(
        quiet: Long,
        local: Local?,
    ): Action? =
        when {
            local == null || quiet < config.quietMs -> null
            local.lost -> Action.Close(Why.LOST, quiet)
            else -> null
        }

    /** The probes' next step on a link whose peer answers and with no probe of ours queued. */
    private fun probeStep(
        now: Long,
        rxAt: Long,
        quiet: Long,
    ): Action {
        if (probesSent == 0) return firstProbe(now, rxAt, quiet)
        val sinceProbe = probeWrittenAt?.let { now - it }
        return when {
            sinceProbe == null || sinceProbe < config.answerMs -> Action.None
            probesSent < config.probes -> probe(now, Trigger.TIMER)
            quiet >= config.quietMs -> Action.Close(Why.PROBES, quiet)
            else -> Action.None
        }
    }

    /**
     * The first probe from the loop: at once on an edge; otherwise only once it has waited [Config.carrierWaitMs] of
     * uptime for a carrier since a look first found it due. Before that, [carry] sends it.
     */
    private fun firstProbe(
        now: Long,
        rxAt: Long,
        quiet: Long,
    ): Action {
        if (edgeAt != null) return probe(now, Trigger.EDGE)
        if (quiet < config.quietMs) return Action.None
        val since = dueSince(rxAt)
        return if (uptime() - since >= config.carrierWaitMs) probe(now, Trigger.FALLBACK) else Action.None
    }

    /** When, on [uptime], the first probe of the silence since [rxAt] was first found due: now, at the first look. */
    private fun dueSince(rxAt: Long): Long {
        val known = dueSinceUp
        if (known != null && dueRxAt == rxAt) return known
        return uptime().also {
            dueSinceUp = it
            dueRxAt = rxAt
        }
    }

    /**
     * How long until [step] may act with nothing new coming in, or null for never ([exempt]). A write that starts
     * blocking, a PHY client's loss and a keepalive written all come without a look, so: a link not sitting in a
     * write is looked at again within [Config.writeStallMs], and the transport looks again when its PHY handle or the
     * writer reports.
     */
    @Synchronized
    fun msUntilDue(
        now: Long,
        rxAt: Long,
        blockedSince: Long?,
        local: Local?,
    ): Long? {
        if (exempt) return null
        val quietDue = rxAt + config.quietMs
        var due = (blockedSince ?: now) + config.writeStallMs
        if (readingCloses(local)) due = minOf(due, quietDue)
        probeDueAt(now, rxAt, quietDue)?.let { due = minOf(due, it) }
        return (due - now).coerceAtLeast(0L)
    }

    /**
     * Whether [local] shows the link dead once it is quiet: only toward a peer that does not answer, where a loss can
     * close. Toward one that answers it would put the next look in the past, every second through the probes' waits.
     */
    private fun readingCloses(local: Local?): Boolean = !answers && local?.lost == true

    /** When the probes' next step falls due, or null: a peer that does not answer, or a probe queued and not yet written. */
    private fun probeDueAt(
        now: Long,
        rxAt: Long,
        quietDue: Long,
    ): Long? {
        if (!answers || Kind.PROBE in queued) return null
        val began = runSince ?: edgeAt
        val written = probeWrittenAt
        return when {
            // A byte ended the run: the next look ends it, and the next silence starts from that byte.
            began != null && rxAt > began -> quietDue

            probesSent == 0 -> firstProbeDueAt(now, rxAt, quietDue)

            // The writer reports the write, and the loop looks again then.
            written == null -> null

            probesSent < config.probes -> written + config.answerMs

            else -> maxOf(written + config.answerMs, quietDue)
        }
    }

    /**
     * When the loop's first probe falls due: now on an edge; while the link is not yet quiet, the moment it is, for one look
     * that starts the carrier wait; once due, the end of that wait, its uptime left counted as elapsed time (a wait that
     * spans a sleep ends early, and the next look reads the uptime again). Never the quiet's deadline once it has passed,
     * which would look every second while a carrier is awaited.
     */
    private fun firstProbeDueAt(
        now: Long,
        rxAt: Long,
        quietDue: Long,
    ): Long =
        when {
            edgeAt != null -> now
            now < quietDue -> quietDue
            else -> now + (dueSince(rxAt) + config.carrierWaitMs - uptime()).coerceAtLeast(0L)
        }

    /** This silence's next probe, sent by [via], queued now. */
    private fun probe(
        now: Long,
        via: Trigger,
    ): Action {
        probesSent++
        queued.addLast(Kind.PROBE)
        awaiting++
        if (runSince == null) runSince = now
        return Action.Probe(probesSent, via)
    }

    private fun endRun() {
        probesSent = 0
        probeWrittenAt = null
        runSince = null
        edgeAt = null
        dueSinceUp = null
        dueRxAt = null
    }

    companion object {
        /**
         * No byte in for this long before a probe or a local close. Above the peer's 60 s custody re-offer, which every
         * shipped build writes to every link, empty custody included, so an awake idle pair never probes; a dozing
         * peer's re-offer stretches (pj9w measured a median 110 s), and costs one keepalive each way per silence.
         */
        const val QUIET_MS = 90_000L

        /**
         * How long a probe waits for any byte back once its write returned. A peer that answers wakes for the L2CAP
         * data, so its answer lands in well under this even dozing.
         */
        const val ANSWER_MS = 30_000L

        /** Probes unanswered before the close: two, so one answer slow to cross costs nothing. */
        const val PROBES = 2

        /**
         * One `write()` this long is a dead link: a segment is at most 8 KiB (`BluetoothSocketLink`), and a Coded link
         * at its edge drains that in seconds; a feed is paced or windowed between writes, never inside one.
         */
        const val WRITE_STALL_MS = 120_000L

        /**
         * How long a due first probe waits for a carrier, on the uptime clock, before it goes on its own: two of the 60 s
         * re-offers this side sends every link, on the clock their `delay` runs on, so a live re-offer always lands first.
         */
        const val CARRIER_WAIT_MS = 120_000L
    }
}
