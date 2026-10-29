package app.getknit.knit.mesh.lora

/**
 * What the plane knows about each peer's board as a compact-DM destination, and which of its unicasts are still waiting
 * on an answer (ADR 2026-10.x9fa). Pure and clock-driven by the caller, like [LoraPacePolicy]; tested on the JVM
 * ([app.getknit.knit.mesh.lora.LoraDirectPolicyTest]).
 *
 * **Reach here is about a radio, never a person.** A destination's ACK says its board took the packet, whatever its
 * phone is doing — away, the plane switched off, the app refusing it — so [Reach.REACHABLE] is only "a unicast to this
 * board gets there", lingers out after [lingerMs], and is never read as presence: the author-keyed `reachable` set is
 * fed by an authenticated compact DM *from* the peer, never by an ACK. Nor is it evidence the DM was read: the
 * re-probe keys on our own DMs still without their ✓✓, whatever this says.
 *
 * - [Reach.UNKNOWN]: nothing known, as after a restart. A DM goes out, one at a time ([mustWait]), and its outcome
 *   decides.
 * - [Reach.REACHABLE]: the destination acked within [lingerMs]. Offers go straight to the queue.
 * - [Reach.UNREACHABLE]: the firmware gave up, or nothing answered by the deadline. Offers are held — their DMs stay
 *   with `LoraDirectSource` — until the board is heard ([onHeard], the only edge out besides an ack) or the re-probe
 *   gets an ack back.
 * - [Reach.REFUSED]: the destination's board refused what reached it (a `NO_CHANNEL` after a Restore). Held until
 *   their profile changes ([onProfileChanged]); hearing them does not clear it, since they are plainly on the air. It
 *   lapses to [Reach.UNKNOWN] after [refusedLapseMs] (the re-probe's cap), so a board set up again without a profile
 *   edit is asked once more rather than never.
 *
 * The in-flight table maps a packet handle to what it carried, so an outcome can be resolved; each entry has a deadline
 * ([deadlineMs]), so every direct send ends, and the table is bounded ([MAX_FLIGHTS], oldest ended first). Offers that
 * must wait behind an unconfirmed peer's one send are parked here, bounded per peer.
 *
 * Every entry point is `@Synchronized`: the offer runs on whichever coroutine sealed, the outcome collector, the
 * deadline loop, the hearing hook and the re-probe each on their own. Never call it holding the pacer's monitor.
 */
internal class LoraDirectPolicy(
    private val lingerMs: Long = LoraMeshTransport.REACHABLE_LINGER_MS,
    /** How long a refusal holds a peer whose profile never changes: the re-probe's longest gap, then one more try. */
    private val refusedLapseMs: Long = LoraReprobe.CAP_MS,
) {
    enum class Reach { UNKNOWN, REACHABLE, UNREACHABLE, REFUSED }

    private class PeerState(
        val reach: Reach,
        val at: Long,
    )

    private val peers = HashMap<String, PeerState>()

    /**
     * One direct send the board took and has not answered yet. Every airing it may cost was booked at [sentAt]
     * ([AIRINGS] × [airMs]); an ack refunds the ones that did not fly.
     */
    class Flight(
        val handle: UInt,
        val send: DirectSend,
        /** One airing's cost, as booked: what a refund gives back per airing. */
        val airMs: Long,
        val sentAt: Long,
        /** When the plane stops waiting and ends it as [PacketOutcome.NoOutcome]. */
        val deadline: Long,
        /**
         * The firmware's **shortest** gap between two airings of it (a quiet band, `CWmin`), for counting the retries an
         * ack says flew: shortest, so an ack over-counts retries rather than under-counts them.
         */
        val retransmitMs: Long,
    )

    private val flights = LinkedHashMap<UInt, Flight>()

    /**
     * Flights that ended with no answer, by handle, so a destination's ack arriving after all — the board's to-phone
     * queue replayed after a BLE drop — still marks the board reachable. Bounded at [MAX_FLIGHTS], oldest dropped.
     */
    private val ended = LinkedHashMap<UInt, DirectSend>()

    /** An offer parked behind an unconfirmed peer's send in flight, replayed through the offer's gates when it settles. */
    class Parked(
        val frameId: String,
        val sentAtMs: Long,
        val plaintext: ByteArray,
        val kind: DirectKind,
    )

    private val parked = HashMap<String, ArrayDeque<Parked>>()

    /** [peerId]'s reach at [now]: [Reach.REACHABLE] lingers out to [Reach.UNKNOWN]. */
    @Synchronized
    fun reachOf(
        peerId: String,
        now: Long,
    ): Reach {
        val state = peers[peerId] ?: return Reach.UNKNOWN
        val lapse =
            when (state.reach) {
                Reach.REACHABLE -> lingerMs
                Reach.REFUSED -> refusedLapseMs
                else -> return state.reach
            }
        if (now - state.at > lapse) {
            peers.remove(peerId)
            return Reach.UNKNOWN
        }
        return state.reach
    }

    /** Whether an offer to [peerId] is held: they are marked unreachable or refused. */
    @Synchronized
    fun holds(
        peerId: String,
        now: Long,
    ): Boolean = reachOf(peerId, now).let { it == Reach.UNREACHABLE || it == Reach.REFUSED }

    /**
     * Whether an offer to [peerId] must wait for the outcome of a send already on its way to them: one direct send in
     * flight per peer not known reachable, so a burst to a board that is off costs one airing and its retries, not
     * one per DM. [queuedTo] is whether the pacer still holds one for them; the table answers for the rest.
     */
    @Synchronized
    fun mustWait(
        peerId: String,
        now: Long,
        queuedTo: Boolean,
    ): Boolean = reachOf(peerId, now) != Reach.REACHABLE && (queuedTo || flights.values.any { it.send.peerId == peerId })

    /** The destination's own board acked: a unicast to it gets there, for now. */
    @Synchronized
    fun onAcked(
        peerId: String,
        now: Long,
    ) {
        if (peers[peerId]?.reach == Reach.REFUSED) return
        peers[peerId] = PeerState(Reach.REACHABLE, now)
    }

    /** The firmware gave up, or nothing answered in time. Never a refusal's mark, which only a new profile lifts. */
    @Synchronized
    fun onUnreachable(
        peerId: String,
        now: Long,
    ) {
        if (peers[peerId]?.reach == Reach.REFUSED) return
        peers[peerId] = PeerState(Reach.UNREACHABLE, now)
    }

    /** The destination's board refused what reached it: held until their profile changes. */
    @Synchronized
    fun onRefused(
        peerId: String,
        now: Long,
    ) {
        peers[peerId] = PeerState(Reach.REFUSED, now)
    }

    /**
     * [peerId]'s board was heard on the air. True only on the edge out of [Reach.UNREACHABLE] — the one moment a held
     * backlog is worth asking about again — and a refused or already-clear peer is left as it is.
     */
    @Synchronized
    fun onHeard(peerId: String): Boolean {
        if (peers[peerId]?.reach != Reach.UNREACHABLE) return false
        peers.remove(peerId)
        return true
    }

    /** [peerId]'s pinned profile changed (a new version or another board): a refusal under the old one no longer stands. */
    @Synchronized
    fun onProfileChanged(peerId: String) {
        if (peers[peerId]?.reach == Reach.REFUSED) peers.remove(peerId)
    }

    // --- the in-flight table ---

    /** Records a send the board took. Returns the oldest flight it had to end to stay under [MAX_FLIGHTS], if any. */
    @Synchronized
    fun launch(flight: Flight): Flight? {
        flights[flight.handle] = flight
        if (flights.size <= MAX_FLIGHTS) return null
        val oldest = flights.keys.first()
        return flights.remove(oldest)
    }

    /** The flight [handle] names, ended: the caller settles it. Null for a handle this table is not waiting on. */
    @Synchronized
    fun resolve(handle: UInt): Flight? = flights.remove(handle)

    /** Whether [handle] is a direct send still waiting, without ending it (an implicit ack does not end the wait). */
    @Synchronized
    fun isWaiting(handle: UInt): Boolean = handle in flights

    /** Whether a send of [frameId] is still waiting on its answer: the "one copy per frame id" half the pacer cannot see. */
    @Synchronized
    fun hasFrame(frameId: String): Boolean = flights.values.any { it.send.frameId == frameId }

    /** The flights whose deadline has passed at [now], ended: each becomes a [PacketOutcome.NoOutcome]. */
    @Synchronized
    fun expired(now: Long): List<Flight> {
        val due = flights.values.filter { now >= it.deadline }
        due.forEach {
            flights.remove(it.handle)
            remember(it)
        }
        return due
    }

    /**
     * What [handle] carried, if it ended with no answer and is still remembered — taken once: a late answer is read
     * once. Its airings are booked already; the caller only moves the marks.
     */
    @Synchronized
    fun ended(handle: UInt): DirectSend? = ended.remove(handle)

    private fun remember(flight: Flight) {
        ended[flight.handle] = flight.send
        while (ended.size > MAX_FLIGHTS) ended.remove(ended.keys.first())
    }

    /** The soonest deadline, or null with nothing in flight: what the deadline loop sleeps until. */
    @Synchronized
    fun nextDeadline(): Long? = flights.values.minOfOrNull { it.deadline }

    /** How many sends are waiting on an answer. */
    @Synchronized
    fun inFlight(): Int = flights.size

    /** Every flight, ended — the board is gone. The caller books what the board may still spend on them. */
    @Synchronized
    fun clearFlights(): List<Flight> {
        val gone = flights.values.toList()
        flights.clear()
        gone.forEach(::remember)
        return gone
    }

    // --- offers parked behind an unconfirmed peer's send ---

    /** Parks [offer] for [peerId]; past [MAX_PARKED_PER_PEER] the oldest goes (its DM stays with the source). */
    @Synchronized
    fun park(
        peerId: String,
        offer: Parked,
    ) {
        val queue = parked.getOrPut(peerId) { ArrayDeque() }
        queue.removeAll { it.frameId == offer.frameId }
        queue.addLast(offer)
        while (queue.size > MAX_PARKED_PER_PEER) queue.removeFirst()
    }

    /** Takes what is parked for [peerId]: to offer again on an ack, or to drop on a failure. */
    @Synchronized
    fun unpark(peerId: String): List<Parked> = parked.remove(peerId)?.toList() ?: emptyList()

    /** Forgets every mark and parked offer — the board is gone, and with it the route each mark was about. */
    @Synchronized
    fun clearPeers() {
        peers.clear()
        parked.clear()
    }

    /** The reach map's counts at [now], for `…debug.LORA`. */
    @Synchronized
    fun counts(now: Long): DirectReachCounts {
        val reaches = peers.keys.toList().map { reachOf(it, now) }
        return DirectReachCounts(
            reachable = reaches.count { it == Reach.REACHABLE },
            unreachable = reaches.count { it == Reach.UNREACHABLE },
            refused = reaches.count { it == Reach.REFUSED },
            inFlight = flights.size,
            parked = parked.values.sumOf { it.size },
        )
    }

    companion object {
        /**
         * How many times the firmware re-sends an unacknowledged `want_ack` unicast after the first airing. Firmware 2.8
         * makes `NUM_RELIABLE_UNICAST_ATTEMPTS = 5` attempts for a unicast its phone originated (`NextHopRouter.h:115`,
         * v2.8.0 / v2.8.1) and reports `MAX_RETRANSMIT` about one gap after the last; 2.7.26 made three. The plane
         * assumes 2.8's five on every board: on a 2.7 board that over-books, the conservative error.
         */
        const val RETRANSMITS = 4

        /** Every airing one direct send may cost: the send and each retry. What it is admitted and booked against. */
        const val AIRINGS = 1 + RETRANSMITS

        /** The firmware's contention windows, as powers of two (`RadioInterface.h`: `CWmin = 3`, `CWmax = 8`). */
        const val CW_MIN = 3
        const val CW_MAX = 8

        /** `RadioInterface::PROCESSING_TIME_MSEC`: the firmware's allowance to build, process and rebuild a packet. */
        const val PROCESSING_MS = 4_500L

        /**
         * The firmware's gap between two airings of a reliable packet that takes [airMs] on the air, at a contention
         * window of [cw] (`RadioInterface::getRetransmissionMsec`, `RadioInterface.cpp:813-826`): twice the airtime, then
         * `2^CW + 2·CWmax + 2^((CWmax+CWmin)/2)` slots of [slotMs], then the processing time. The firmware picks `CW`
         * off the channel's utilisation, between [CW_MIN] (a quiet band) and [CW_MAX] (a saturated one).
         */
        fun retransmitMs(
            airMs: Long,
            slotMs: Double,
            cw: Int,
        ): Long {
            val slots = (1 shl cw) + 2 * CW_MAX + (1 shl ((CW_MAX + CW_MIN) / 2))
            return 2 * airMs + (slots * slotMs).toLong() + PROCESSING_MS
        }

        /** Slack past the firmware's give-up before the plane stops waiting: the BLE hop, and a board slow to report. */
        const val ANSWER_SLACK_MS = 10_000L

        /**
         * How long the plane waits on a direct send of [airMs] before ending it as no outcome: every airing at the
         * **longest** gap ([CW_MAX]) and the slack, so the firmware's own answer always comes first — about two minutes
         * for a full packet at LongFast (ADR 2026-10.x9fa).
         */
        fun deadlineMs(
            airMs: Long,
            slotMs: Double,
        ): Long = AIRINGS * retransmitMs(airMs, slotMs, CW_MAX) + ANSWER_SLACK_MS

        /**
         * How many retries had flown when an answer came [elapsedMs] after the send: one per [retransmitMs] gap (the
         * shortest, so this errs high), at most [RETRANSMITS]. What an ack keeps booked on top of the first airing.
         */
        fun retriesBefore(
            elapsedMs: Long,
            retransmitMs: Long,
        ): Int = (elapsedMs / retransmitMs.coerceAtLeast(1)).toInt().coerceIn(0, RETRANSMITS)

        /**
         * A nominal routing ACK as the destination's board airs it: a `Routing` with no error, its request id and the
         * `Data` framing. Booked by the receiver per compact DM it opened, unsigned, since our board sent it on our
         * behalf.
         */
        const val ACK_PAYLOAD_BYTES = 8

        /** The most sends waiting on an answer at once, and the most ended ones remembered for a late answer. */
        const val MAX_FLIGHTS = 32

        /** The most offers parked behind one peer's send; the DMs among the rest stay with the source. */
        const val MAX_PARKED_PER_PEER = 8
    }
}

/** The reach map's counts for `…debug.LORA` (ADR 2026-10.x9fa). */
internal data class DirectReachCounts(
    val reachable: Int = 0,
    val unreachable: Int = 0,
    val refused: Int = 0,
    val inFlight: Int = 0,
    val parked: Int = 0,
)
