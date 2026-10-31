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
 * - [Reach.REFUSED]: the destination's board refused what reached it (a `NO_CHANNEL` after a Restore, which a board
 *   that still holds our key never sends: it decodes the PKI DM on index 0 whatever its Knit channel). Held until
 *   their profile changes ([onProfileChanged]); hearing them does not clear it, since they are plainly on the air. It
 *   lapses to [Reach.UNKNOWN] after [refusedLapseMs] (the re-probe's cap), so a board set up again without a profile
 *   edit is asked once more rather than never.
 * - [Reach.NO_KEY]: our own board refused to send to them (`PKI_SEND_FAIL_PUBLIC_KEY`, 39: it holds no key for their
 *   board, ADR 2026-10.aajj) and nothing Knit may do about it is left — the `add_contact` it wrote did not take, or it
 *   may write none ([LoraContactPolicy]). Nothing aired. Held like a refusal, and lifted by a changed profile, a key the
 *   board learns for them ([onKeyLearned]) or the same lapse.
 *
 * Two holds that are not marks keep "one send per unconfirmed peer" while nothing is queued or in flight: a frame our
 * board refused for want of a key, waiting on the `add_contact` written for it ([holdForContact]), and the one re-offer
 * after the destination's `PKI_UNKNOWN_PUBKEY` (35), waiting out a retransmit gap while our board's `NodeInfo` reaches
 * it ([scheduleRetry]). Both are frames, not offers: they carry the gates their own path asked.
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
    enum class Reach { UNKNOWN, REACHABLE, UNREACHABLE, REFUSED, NO_KEY }

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
        /**
         * The sealed packet and the gates its path asked: what the one re-offer after a `PKI_UNKNOWN_PUBKEY` re-queues,
         * and what a `39` that arrives only as a late NAK (its `queueStatus` timed out) holds for an `add_contact`.
         */
        val packet: ByteArray? = null,
        val gate: RideGate = RideGate(),
        /** The NodeInfo stream's stamp when the send was issued ([MeshtasticLink.userSeq]): what a late `39` is read against. */
        val userSeqAtSend: Long = 0,
    )

    private val flights = LinkedHashMap<UInt, Flight>()

    /**
     * Flights that ended with no answer — past their deadline, with the board gone, or pushed out of a full table — by
     * handle, so a destination's ack arriving after all (the board's to-phone queue replayed after a BLE drop) still marks
     * the board reachable. Bounded at [MAX_FLIGHTS], oldest dropped.
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

    /** A frame our board refused for want of a key, per peer, held while the `add_contact` written for it lands. */
    private val contactHeld = HashMap<String, OutboundFrame>()

    /** The one re-offer after a destination's `PKI_UNKNOWN_PUBKEY`, per peer, due once its gap has passed. */
    private class Retry(
        val at: Long,
        val frame: OutboundFrame,
    )

    private val retries = HashMap<String, Retry>()

    /**
     * Packet handles whose `39` was already acted on: the `queueStatus` and the self-NAK both report it, in either order,
     * and the second must not write a second contact or count a second refusal. Bounded at [MAX_FLIGHTS].
     */
    private val noKeyHandles = LinkedHashSet<UInt>()

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
                Reach.REFUSED, Reach.NO_KEY -> refusedLapseMs
                else -> return state.reach
            }
        if (now - state.at > lapse) {
            peers.remove(peerId)
            return Reach.UNKNOWN
        }
        return state.reach
    }

    /** Whether an offer to [peerId] is held: they are marked unreachable, refused, or our board holds no key for them. */
    @Synchronized
    fun holds(
        peerId: String,
        now: Long,
    ): Boolean = reachOf(peerId, now).let { it == Reach.UNREACHABLE || it == Reach.REFUSED || it == Reach.NO_KEY }

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
    ): Boolean =
        peerId in contactHeld ||
            peerId in retries ||
            (reachOf(peerId, now) != Reach.REACHABLE && (queuedTo || flights.values.any { it.send.peerId == peerId }))

    /** The destination's own board acked: a unicast to it gets there, for now. */
    @Synchronized
    fun onAcked(
        peerId: String,
        now: Long,
    ) {
        if (peers[peerId]?.reach == Reach.REFUSED) return
        peers[peerId] = PeerState(Reach.REACHABLE, now)
    }

    /**
     * The firmware gave up, or nothing answered in time. Never a refusal's or a missing key's mark, which only a new
     * profile, a learned key or the lapse lifts.
     */
    @Synchronized
    fun onUnreachable(
        peerId: String,
        now: Long,
    ) {
        val reach = peers[peerId]?.reach
        if (reach == Reach.REFUSED || reach == Reach.NO_KEY) return
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

    /**
     * [peerId]'s pinned profile changed (a new version, another board, another key or another rank): a refusal or a
     * missing key under the old one no longer stands.
     */
    @Synchronized
    fun onProfileChanged(peerId: String) {
        val reach = peers[peerId]?.reach
        if (reach == Reach.REFUSED || reach == Reach.NO_KEY) peers.remove(peerId)
    }

    /**
     * Our board refused a send to [peerId] for want of their key and nothing Knit may do about it is left (ADR
     * 2026-10.aajj): held, counted by the caller, never silent.
     */
    @Synchronized
    fun onNoKey(
        peerId: String,
        now: Long,
    ) {
        peers[peerId] = PeerState(Reach.NO_KEY, now)
    }

    /** Our board's NodeDB showed a key for [peerId]'s board: a missing-key mark is lifted. True on that edge alone. */
    @Synchronized
    fun onKeyLearned(peerId: String): Boolean {
        if (peers[peerId]?.reach != Reach.NO_KEY) return false
        peers.remove(peerId)
        return true
    }

    /**
     * Whether the `39` on packet [handle] is the first word about it — its `queueStatus` and its self-NAK both carry it,
     * in either order — and records it. False for the second.
     */
    @Synchronized
    fun firstNoKey(handle: UInt): Boolean {
        if (!noKeyHandles.add(handle)) return false
        while (noKeyHandles.size > MAX_FLIGHTS) noKeyHandles.remove(noKeyHandles.first())
        return true
    }

    /**
     * Whether [handle]'s `39` was already acted on — a compact DM's — so its late self-NAK is that refusal. False for any
     * other handle: the auto-reply and a room post draw a self-NAK too (`Router::abortSendAndNak` runs for every refused
     * send), and theirs is never a compact DM's missing key.
     */
    @Synchronized
    fun handledNoKey(handle: UInt): Boolean = handle in noKeyHandles

    // --- frames held for a key: the add_contact's, and the re-offer after the destination lacked ours ---

    /** Holds [frame], refused by our board for want of [peerId]'s key, while the `add_contact` written for it lands. */
    @Synchronized
    fun holdForContact(
        peerId: String,
        frame: OutboundFrame,
    ) {
        contactHeld[peerId] = frame
    }

    /** Takes the frame held for [peerId]'s `add_contact`, or null when there is none (the board went meanwhile). */
    @Synchronized
    fun releaseContact(peerId: String): OutboundFrame? = contactHeld.remove(peerId)

    /** Holds [frame] for one re-offer to [peerId] at [at]: the destination lacked our key, and our board sent it. */
    @Synchronized
    fun scheduleRetry(
        peerId: String,
        at: Long,
        frame: OutboundFrame,
    ) {
        retries[peerId] = Retry(at, frame)
    }

    /** The re-offers due at [now], taken: each goes back through the pacer and its own gates. */
    @Synchronized
    fun dueRetries(now: Long): List<OutboundFrame> {
        val due = retries.filterValues { now >= it.at }
        due.keys.forEach(retries::remove)
        return due.values.map { it.frame }
    }

    // --- the in-flight table ---

    /**
     * Records a send the board took. Returns the oldest flight it had to end to stay under [MAX_FLIGHTS], if any —
     * remembered like one that ran out its deadline, so the destination's ack that may still come for it moves the marks
     * as a late answer rather than being dropped.
     */
    @Synchronized
    fun launch(flight: Flight): Flight? {
        flights[flight.handle] = flight
        if (flights.size <= MAX_FLIGHTS) return null
        val oldest = flights.keys.first()
        return flights.remove(oldest)?.also(::remember)
    }

    /** The flight [handle] names, ended: the caller settles it. Null for a handle this table is not waiting on. */
    @Synchronized
    fun resolve(handle: UInt): Flight? = flights.remove(handle)

    /** Whether [handle] is a direct send still waiting, without ending it (an implicit ack does not end the wait). */
    @Synchronized
    fun isWaiting(handle: UInt): Boolean = handle in flights

    /**
     * Whether a send of [frameId] is still waiting on its answer, on an `add_contact` or on its re-offer's gap: the "one
     * copy per frame id" half the pacer cannot see.
     */
    @Synchronized
    fun hasFrame(frameId: String): Boolean =
        flights.values.any { it.send.frameId == frameId } ||
            contactHeld.values.any { it.direct?.frameId == frameId } ||
            retries.values.any { it.frame.direct?.frameId == frameId }

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

    /** The soonest deadline or re-offer, or null with nothing waiting: what the deadline loop sleeps until. */
    @Synchronized
    fun nextDeadline(): Long? = (flights.values.map { it.deadline } + retries.values.map { it.at }).minOrNull()

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

    /**
     * Forgets every mark, parked offer and held frame — the board is gone, and with it the route each was about. A held
     * frame's DM stays with the source for the re-probe; a held ✓✓ or reaction rode its signed copy.
     */
    @Synchronized
    fun clearPeers() {
        peers.clear()
        parked.clear()
        contactHeld.clear()
        retries.clear()
    }

    /** The reach map's counts at [now], for `…debug.LORA`. */
    @Synchronized
    fun counts(now: Long): DirectReachCounts {
        val reaches = peers.keys.toList().map { reachOf(it, now) }
        return DirectReachCounts(
            reachable = reaches.count { it == Reach.REACHABLE },
            unreachable = reaches.count { it == Reach.UNREACHABLE },
            refused = reaches.count { it == Reach.REFUSED },
            noKey = reaches.count { it == Reach.NO_KEY },
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
         * What a 2.8.1 board appends to an ACK when it holds the key of the node it acks (`AckProof.cpp:133-146`): the
         * `Routing.ack_proof` field, its tag and length and `ACK_PROOF_SIZE` (8, `CryptoEngine.h:31`, `mesh.options`).
         * A board that took a compact DM decrypted it with the sender's key, so every one of its ACKs carries it.
         */
        const val ACK_PROOF_FIELD_BYTES = 2 + 8

        /**
         * A routing ACK as the destination's board airs it: a `Routing` with no error, its request id, the `Data` framing
         * (8 B) and the ack proof ([ACK_PROOF_FIELD_BYTES]). Channel-keyed on the board's primary (index 0: a PKI DM is
         * reported there, and `ROUTING_APP` is never PKI'd), unsigned. Booked by the receiver for each compact DM
         * addressed to it that its board acked on its behalf ([boardAcks]), opened or not (ADR 2026-10.x9fa's PKI
         * amendment).
         */
        const val ACK_PAYLOAD_BYTES = 8 + ACK_PROOF_FIELD_BYTES

        /**
         * A nominal `NodeInfo` our board sends a destination that answered `PKI_UNKNOWN_PUBKEY` (35): its `User` with a
         * 32-byte key and a `Knit abcd` name, about 70 B, a unicast and so unsigned. Booked when the board's own `User`
         * is not to hand (ADR 2026-10.aajj).
         */
        const val NODEINFO_USER_BYTES = 72

        /**
         * The largest compact DM one packet carries on a board whose `Data` payload cap is [maxPayload] (ADR 2026-10.x9fa,
         * its PKI amendment). Two limits, and they do not stack: the BLE write (`want_ack` makes the `ToRadio` two bytes
         * longer than a broadcast's, [MeshtasticProto.WANT_ACK_FIELD_BYTES]) and the firmware's PKI size check (an encoded
         * `Data` plus the 16-byte header and the 12-byte PKC overhead within 255 B, `Router.cpp:1349` at v2.8.1, so
         * [MeshtasticProto.MAX_PAYLOAD] less [MeshtasticProto.PKC_OVERHEAD_BYTES]). 219 B at every MTU a board
         * negotiates. Reserved always: every compact DM that airs on 2.8 is PKI. The one cap the transport and
         * `MeshManager`'s plaintext budget both read.
         */
        fun payloadCap(maxPayload: Int): Int =
            minOf(
                MeshtasticProto.MAX_PAYLOAD - MeshtasticProto.PKC_OVERHEAD_BYTES,
                maxPayload - MeshtasticProto.WANT_ACK_FIELD_BYTES,
            )

        /**
         * Whether our own board — node [ownNode] — ACKed [packet], a decoded unicast addressed to it, as it handed it to
         * the phone: so whether the window owes that ACK's air. Firmware `ReliableRouter::sniffReceived`
         * (`ReliableRouter.cpp:123-158`, 332b5b0a2): a packet that asked for `want_ack` is ACKed (a compact DM carries no
         * `request_id` or `reply_id`); one that did not is ACKed only when the sender's next-hop routing named us as the
         * hop to carry it and it still had hops left — a hop-zero ACK that stops that sender's own retries. A unicast with
         * neither draws nothing. Duplicates the board ACKs again without handing them over (`NextHopRouter.cpp:163-170`)
         * are not seen here and stay unbooked. [ownNode]'s last byte is never 0 (`NodeDB::getLastByteOfNodeNum`), so an
         * absent `next_hop` never matches.
         */
        fun boardAcks(
            packet: ReceivedPacket,
            ownNode: UInt,
        ): Boolean = packet.wantAck || (packet.hopLimit > 0 && packet.nextHop == lastByteOf(ownNode))

        /** A node's relay byte as the firmware writes it in `next_hop` / `relay_node`: its low byte, 0xFF for a 0. */
        fun lastByteOf(node: UInt): Int = (node and LAST_BYTE_MASK).toInt().takeIf { it != 0 } ?: LAST_BYTE_MASK.toInt()

        private const val LAST_BYTE_MASK = 0xFFu

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
    /** Peers our board refused to send to for want of their key, with nothing left to try (ADR 2026-10.aajj). */
    val noKey: Int = 0,
    val inFlight: Int = 0,
    val parked: Int = 0,
)
