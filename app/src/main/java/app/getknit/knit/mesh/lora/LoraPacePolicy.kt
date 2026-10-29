package app.getknit.knit.mesh.lora

/**
 * The outbound pacer for the LoRa plane: a bounded queue with a minimum inter-packet gap and a NAK
 * back-off, all pure and clock-driven by the caller (the transport owns the actual `delay`). LoRa is a
 * ~1 kbps shared medium — a 233-B packet is ~2.5 s on air and the board floods each one three hops — so
 * unpaced sends would swamp it and draw duty-cycle refusals.
 *
 * The queue sheds by **class** when full ([FrameClass]): from the lowest class present — the newcomer
 * included, so a frame that is alone at the bottom yields instead of evicting anything — and the **oldest
 * whole frame** within it (never a lone fragment, which would strand a half-delivered message). So a room
 * post never evicts a DM. A rate/duty-cycle NAK widens the gap for
 * a cool-down window, and since ADR 044 an injected [LoraAirtime] holds a rolling budget the queue is drained
 * against — so a frame can also wait because the plane has spent its share of the medium, not just because
 * the gap has not elapsed. Dequeue runs the same class order forwards (see [take]). Tested on the JVM
 * ([app.getknit.knit.mesh.lora.LoraPacePolicyTest]).
 *
 * **Every entry point is `@Synchronized`, because this queue has six writers on five coroutines.** `take`
 * runs on the pacer loop; `enqueue` on whichever coroutine sent (the composite's fan-out, a compact DM's offer,
 * the resend after a hearing, the linger sweep's re-probe, the public post and the auto-reply); `onQueueStatus` and
 * `evictOversize` on the link and queue collectors; `onNak` on the outcomes collector. `ArrayDeque` is not
 * thread-safe, and a structural change under `admitBest`'s index walk nulls the slot it is about to read —
 * field-observed on the lab P7 as `NullPointerException: … OutboundFrame.getBucket()` at
 * `admitBest`, which kills the pacer loop and silences the plane until the app is restarted (nothing
 * restarts that coroutine). The monitor is taken **outside** [LoraAirtime]'s and never the other way round.
 */
internal class LoraPacePolicy(
    private val minGapMs: Long = MIN_GAP_MS,
    private val queueCap: Int = QUEUE_CAP_FRAMES,
    private val nakBackoffMs: Long = NAK_BACKOFF_MS,
    /** The airtime ledger; a frame is only taken if its whole cost fits its bucket's rolling budget. */
    val airtime: LoraAirtime = LoraAirtime(),
) {
    private val queue = ArrayDeque<OutboundFrame>()
    private var lastSentAt = Long.MIN_VALUE
    private var boardFree: Int? = null
    private var nakUntil = 0L
    private var airtimeBlockedUntil = 0L

    /** Frames left in the queue that the airtime budget refused on the last [take]; diagnostics only. */
    var lastAirtimeRefusals = 0
        private set

    /**
     * The buckets of frames the airtime budget held for the **first** time on the last [take] — one entry per
     * frame, and a given frame appears at most once in its whole life (see [OutboundFrame.heldForAir]).
     *
     * [lastAirtimeRefusals] answers "how many are stuck right now", which is a level and is re-read every
     * wake; this answers "what just became stuck", which is an event and is what a counter may be built on.
     * Empty on the common path, so a drain that admits everything allocates nothing.
     */
    var lastAirtimeHolds: List<AirBucket> = emptyList()
        private set

    /**
     * How a frame was admitted: [DROPPED_OLDEST] means the queue was full and the oldest frame of the lowest
     * class present was evicted to make room; [REFUSED] means the newcomer itself was that lowest class, alone,
     * and was dropped instead (everything queued outranks it).
     */
    enum class Admission { ACCEPTED, DROPPED_OLDEST, REFUSED }

    val pending: Int @Synchronized get() = queue.size

    @Synchronized
    fun enqueue(frame: OutboundFrame): Admission {
        // A newcomer may fit where everything queued did not — it may spend a bucket the queue was not
        // asking for — so the airtime deferral is a fact about the queue as it stood, not a standing
        // cool-down.
        airtimeBlockedUntil = 0L
        // A snapshot replaces its own older copy before anything else is decided, so a superseded frame can
        // never cost the newcomer a queue slot or make the queue look fuller than it is. Never a part-sent copy:
        // the board already holds its first fragments, and dropping the rest strands them on the air (feb2d5a4).
        lastSuperseded =
            frame.supersedes?.let { key -> queue.removeAllReturningCount { it.supersedes == key && it.sentParts == 0 } } ?: 0
        return admit(frame)
    }

    /**
     * Puts back a frame the pacer took and could not send — the board busy, or the link gone between the take
     * and the write. Unlike [enqueue] it supersedes nothing: the frame is not a newer snapshot, it is the one
     * that was already here, and running the supersede would let a requeued older profile delete a newer one
     * queued meanwhile (ADR 2026-09.xdm2 read the other way round).
     */
    @Synchronized
    fun requeue(frame: OutboundFrame): Admission {
        if (inFlight === frame) inFlight = null
        airtimeBlockedUntil = 0L
        lastSuperseded = 0
        return admit(frame)
    }

    /**
     * Whether a frame queued under [key] is waiting, or on its way now — what keeps one copy of a compact DM per frame
     * id in the queue whether the offer, the resend on hearing or the re-probe would queue it (ADR 2026-10.x9fa; none
     * of them asks a dedup of what was transmitted, which on this plane is not a hearing).
     */
    @Synchronized
    fun hasQueued(key: String): Boolean = inFlight?.supersedes == key || queue.any { it.supersedes == key }

    /**
     * Whether a direct frame to [peerId] is waiting, or on its way now — the transport's "one in flight per unconfirmed
     * peer" asks it beside its own table of sends awaiting an answer (ADR 2026-10.x9fa).
     */
    @Synchronized
    fun hasQueuedTo(peerId: String): Boolean = inFlight?.direct?.peerId == peerId || queue.any { it.direct?.peerId == peerId }

    private fun admit(frame: OutboundFrame): Admission {
        if (queue.size < queueCap) {
            queue.addLast(frame)
            return Admission.ACCEPTED
        }
        // Full: shed from the lowest class present, newcomer included (enum order = priority, highest first).
        val lowest = maxOf(queue.maxOf { it.klass }, frame.klass)
        val victim = queue.indexOfFirst { it.klass == lowest }
        if (victim < 0) {
            // The newcomer alone is the lowest class: it yields — and, never queued, it is handed back like any shed
            // frame, so whatever it held (its dedup slot) is given back by the caller.
            shed += frame
            return Admission.REFUSED
        }
        shed += queue.removeAt(victim)
        queue.addLast(frame)
        return Admission.DROPPED_OLDEST
    }

    // Frames this pacer dropped without sending — shed for a newcomer, a refused newcomer or requeue, or evicted as
    // oversize — waiting for the
    // transport to hand back what they held (a fan-out's dedup slot) or to settle (a compact DM's wait, ADR 2026-10.x9fa).
    private val shed = ArrayList<OutboundFrame>()

    /** Takes the frames dropped unsent since the last call; the caller abandons each ([drainShed] is race-safe). */
    @Synchronized
    fun drainShed(): List<OutboundFrame> {
        if (shed.isEmpty()) return emptyList()
        return shed.toList().also { shed.clear() }
    }

    /**
     * The frame [take] handed out and the pacer has not yet heard the end of ([done] or [requeue]). Off the
     * queue while its first fragment is written, it must still count as queued to [hasQueued], or a resend
     * queues a second copy beside it and a `Busy` requeue then lets both fly.
     */
    private var inFlight: OutboundFrame? = null

    /** The pacer is done with [frame]: sent, dropped or abandoned. */
    @Synchronized
    fun done(frame: OutboundFrame) {
        if (inFlight === frame) inFlight = null
    }

    /**
     * Drops every queued frame with a part larger than [cap] and returns how many went. The board's payload cap
     * is only known once its session is Ready, and frames fanned out while it was still connecting were
     * chunked for the pre-Ready floor; should a board negotiate a smaller MTU than that floor, those parts
     * would fail every write and requeue forever, so they are shed here when the real cap lands.
     */
    @Synchronized
    fun evictOversize(cap: Int): Int {
        val before = queue.size
        queue.removeAll { frame -> frame.remaining.any { it.size > cap }.also { if (it) shed += frame } }
        return before - queue.size
    }

    /**
     * How many queued frames the last [enqueue] superseded. Diagnostics only — a supersession is never a drop
     * (see [OutboundFrame.supersedes]), so nothing counts it against `loraDroppedQueue`.
     */
    var lastSuperseded = 0
        private set

    /**
     * The earliest time the next frame may go out: the min gap since the last send, any NAK cool-down, and —
     * once [take] has found the whole queue over budget — the moment the rolling window next frees air.
     *
     * That last term is what keeps the caller's drain loop asleep. Without it a queue nothing may leave
     * reports a due time already in the past, so the loop computes a zero wait, never suspends, and spins a
     * core flat for as long as the budget stays spent (and, having no suspension point, cannot be cancelled).
     */
    @Synchronized
    fun nextDueAt(): Long =
        maxOf(
            if (lastSentAt == Long.MIN_VALUE) 0L else lastSentAt + minGapMs,
            nakUntil,
            airtimeBlockedUntil,
        )

    /**
     * The next frame to send, or null when the queue is empty, the gap/cool-down has not elapsed, the board
     * has no queue headroom, or every queued frame is over its airtime budget. Stamps the send time so the
     * next frame waits a full gap.
     *
     * Dequeue is **by class, then FIFO within it** — the same order the queue sheds in, run forwards. ADR 039
     * left dequeue purely FIFO, which was right while everything on this plane was something a human had just
     * typed. The bridge (ADR 044) broke that assumption, and since ADR 2026-10.rp3s the bridge is gone, but the
     * order still says what matters most — a DM before a room post before a tick — and running it on the way out
     * too costs nothing.
     *
     * A frame the airtime budget refuses is **skipped, not dropped** — it stays queued for a later window
     * while a frame from a bucket with headroom goes now. Without the skip, one refused PUBLIC post or tick would
     * block everything behind it until its budget recovered. A frame that never gets a window eventually ages
     * out through the ordinary class shedding instead. The one exception: nothing is skipped *past* a live frame
     * the window's total refused, or the smaller frames behind it would take the air it is waiting for, one
     * packet's worth at a time, for as long as they keep coming ([admitBest]).
     */
    @Synchronized
    fun take(now: Long): OutboundFrame? {
        // Cleared on entry, not only where it is filled: this returns early on an empty queue, an unelapsed
        // gap or a full board, and a caller reading [lastAirtimeHolds] after every call would otherwise
        // re-report the last call's holds on every wake — the per-wake counting the flag exists to avoid.
        lastAirtimeHolds = emptyList()
        if (!mayDrain(now)) return null
        val best = admitBest(now)
        if (best < 0) {
            // Every queued frame is over budget: defer to when the window returns some air. Null (an empty
            // ledger, so the frame is simply bigger than the whole allowance) leaves the caller's own floor
            // to pace the retry — that frame ages out through class shedding, not through a window.
            airtimeBlockedUntil = airtime.nextReleaseAt(now) ?: 0L
            return null
        }
        airtimeBlockedUntil = 0L
        lastSentAt = now
        return queue.removeAt(best).also { inFlight = it }
    }

    private fun ArrayDeque<OutboundFrame>.removeAllReturningCount(predicate: (OutboundFrame) -> Boolean): Int {
        val before = size
        removeAll(predicate)
        return before - size
    }

    /** Whether anything at all may leave now: something queued, the gap and cool-downs elapsed, board room. */
    private fun mayDrain(now: Long): Boolean = queue.isNotEmpty() && now >= nextDueAt() && boardFree != 0

    /**
     * The index of the frame to send, or -1 when the budget refuses them all — or when everything it would admit
     * ranks behind a live frame the window's total is holding. Records [lastAirtimeRefusals] and [lastAirtimeHolds]
     * on the way past.
     *
     * A refused frame is skipped rather than blocking the queue (ADR 044), but not past a live frame the **total**
     * refused: air comes back as the window rolls, a packet's worth at a time, and a one-packet room post fits each
     * time while a two-packet DM never does. Field-observed 2026-10-04: a DM queued at 16:28 waited eleven minutes
     * behind the room posts that kept taking that air. So the best-ranked such frame holds every frame ranked behind
     * it (class, then queue order) until the air it needs has come back. Only a [AirBucket.LIVE] frame holds, and only
     * on [LoraAirtime.Refusal.TOTAL]: a frame refused on its own share must never hold live traffic (that share
     * exists so the public room cannot crowd it), and one no window would admit must never be waited for.
     */
    private fun admitBest(now: Long): Int {
        // What is still owed, not the whole frame: a resumed frame's earlier fragments are already on the air and
        // already booked, so charging admission for them again would refuse a frame that fits.
        val refusals = queue.map { airtime.refusal(it.bucket, it.klass, it.admissionSizes(), now, it.signedUpTo) }
        val best = bestAdmitted(refusals)
        val holder = holderOf(refusals)
        // Everything admissible ranks behind the holder when the best of it does: it all waits with the holder.
        val blocked = best >= 0 && holder >= 0 && ranksBehind(best, holder)
        var held: MutableList<AirBucket>? = null
        var waiting = 0
        for (i in queue.indices) {
            if (refusals[i] == null && !blocked) continue
            waiting++
            held = noteHeld(queue[i], held)
        }
        lastAirtimeRefusals = waiting
        lastAirtimeHolds = held ?: emptyList()
        return if (blocked) -1 else best
    }

    /** The best-ranked frame [refusals] admits, or -1: strictly-better class only, so the earliest of a class wins. */
    private fun bestAdmitted(refusals: List<LoraAirtime.Refusal?>): Int {
        var best = -1
        for (i in queue.indices) {
            if (refusals[i] == null && (best < 0 || queue[i].klass < queue[best].klass)) best = i
        }
        return best
    }

    /** The best-ranked live frame the window's total refused, or -1: the one nothing ranked behind may pass. */
    private fun holderOf(refusals: List<LoraAirtime.Refusal?>): Int {
        var holder = -1
        for (i in queue.indices) {
            val holds = refusals[i] == LoraAirtime.Refusal.TOTAL && queue[i].bucket == AirBucket.LIVE
            if (holds && (holder < 0 || queue[i].klass < queue[holder].klass)) holder = i
        }
        return holder
    }

    /** Whether the frame at [i] dequeues after the one at [j]: a lower class, or the same class queued later. */
    private fun ranksBehind(
        i: Int,
        j: Int,
    ): Boolean = queue[i].klass > queue[j].klass || (queue[i].klass == queue[j].klass && i > j)

    /**
     * Notes [frame] as held for air the first time it is, into [held]. Once per frame, not once per wake: the pacer
     * re-asks this question every few seconds, and a counter that ticked on each of them would report the clock
     * rather than the congestion.
     */
    private fun noteHeld(
        frame: OutboundFrame,
        held: MutableList<AirBucket>?,
    ): MutableList<AirBucket>? {
        if (frame.heldForAir) return held
        frame.onAirtimeHeld()
        return (held ?: mutableListOf()).also { it += frame.bucket }
    }

    /**
     * The ledger gave air back before the window rolled it off — a compact DM's ack refunding the airings it did not need
     * (ADR 2026-10.x9fa) — so the deferral [take] set to the window's next release no longer stands.
     */
    @Synchronized
    fun onAirReturned() {
        airtimeBlockedUntil = 0L
    }

    @Synchronized
    fun onQueueStatus(free: Int) {
        boardFree = free
    }

    /** A rate-limit or duty-cycle NAK widens the gap for a cool-down; other NAKs don't pace. */
    @Synchronized
    fun onNak(
        reason: RoutingError,
        now: Long,
    ) {
        if (reason == RoutingError.RATE_LIMIT_EXCEEDED || reason == RoutingError.DUTY_CYCLE_LIMIT) {
            nakUntil = now + nakBackoffMs
        }
    }

    private companion object {
        const val MIN_GAP_MS = 3_000L

        // Raised from 12 with the bridge (ADR 044, gone since ADR 2026-10.rp3s, which kept the cap): a resend after
        // a hearing can still enqueue a small burst behind live traffic, and class shedding — not the cap — is what protects
        // the live frames. Doubled again with the 15-min
        // airtime window (ADR 054): a burst that outruns the window now waits ≤ 15 min for air, and a queue
        // that can hold that wait (≤ ~700 B a frame) sheds nothing a later window would have carried.
        const val QUEUE_CAP_FRAMES = 32
        const val NAK_BACKOFF_MS = 60_000L
    }
}

/**
 * The pacing class of a queued frame, highest first: a sealed DM outranks ambient room traffic, the Nearby room comes
 * next, and our own delivery tick ([TICK], ADR 054 — a compact ✓✓ its originator offered as `DirectKind.RECEIPT`) is
 * what the queue sheds first: feedback, not content, and it heals on re-delivery. The profile bootstrap and the
 * gossip OFFER classes went with the bridge (ADR 2026-10.rp3s).
 *
 * This is the **queue-shedding** order only. What a frame costs against the rolling budget is [AirBucket], which is
 * orthogonal: a public post rides ROOM class here and spends [AirBucket.PUBLIC] there. [LoraAirtime] reads one class
 * beyond the bucket: a [TICK] never spends the last share of a window.
 */
internal enum class FrameClass { DM, ROOM, TICK }

/**
 * Where a queued frame is going, which decides both the channel it is written to and the guard it must pass.
 *
 * The two are kept apart deliberately (§5 of work item #37). `boundSlotIsKnit` exists to stop Knit's own
 * cleartext frames reaching the public channel *by accident* — the failure it guards against is silence being
 * safer than a mistake — while [Public] is a deliberate, consented path to index 0 whose guard asks the
 * opposite question. Sharing code between them would let a fix to one quietly relax the other.
 *
 * They do share the **queue**: one duty-cycle ledger, one 3 s inter-packet gap, one NAK back-off, one
 * `queueFree` backpressure. A public post that bypassed the pacer would be a second transmitter on the same
 * radio, which is how a politeness ceiling stops meaning anything.
 */
internal enum class Destination {
    /**
     * Knit's own secondary channel — the bound slot, guarded by `boundSlotIsKnit`: a broadcast frame, and since ADR
     * 2026-10.x9fa a compact DM ([OutboundFrame.direct]) addressed to one board on that same slot, never slot 0.
     */
    Knit,

    /** The board's own primary — index 0, `TEXT_MESSAGE_APP`, guarded by `PublicChannelPolicy`. */
    Public,

    /**
     * One Meshtastic user's DM answered — index 0, `TEXT_MESSAGE_APP`, addressed to [OutboundFrame.to],
     * guarded by [DmAutoReplyPolicy]. The same write as [Public] with one node's number in `to`, which is
     * what makes the firmware encrypt it to that node rather than to the channel.
     */
    Reply,
}

/**
 * The answers [LoraMeshTransport.fanout] and its siblings got from the send-time gates, carried so the frame
 * can be asked the same questions again when it finally reaches the air.
 *
 * The queue is a **time-delayed commitment**: a frame waits here behind the 3 s pacing floor, a full board
 * queue and a spent airtime share, which together can hold it for a whole window. Every gate the enqueuing
 * path passed was answered about the mesh as it was at that moment, and the field failure this exists for is
 * the four seconds between them — a peer's Wi-Fi Aware link came up while its receipts sat in this queue, and
 * the plane spent its whole allowance re-delivering what that link had already carried.
 *
 * Each field is null/false on a path that never asked its question, because re-asking must only ever repeat a
 * gate, never add one: the public post and the auto-reply ask none of these, and a re-probe flies because its peer
 * is *not* confirmed, so asking it [reachHeld] would refuse exactly the frame the re-probe exists to send.
 */
internal data class RideGate(
    /** The addressee whose live link on a better plane makes this frame redundant, or null if never gated on one. */
    val recipientId: String? = null,
    /** Wall-clock instant past which the fan-out freshness gate would refuse this frame, or null when exempt. */
    val freshUntil: Long? = null,
    /**
     * Who wrote this frame, on a path that asks [LoraFramePolicy.airs] (ADR 2026-10.rp3s): the send refuses it unless
     * it is ours. Carried rather than settled at enqueue because a frame fanned before our own id was read could not
     * be judged then — a room post typed during a Keystore backoff must still ride once the id lands, and a relayed
     * frame fanned in that window must not.
     */
    val authorId: String? = null,
    /**
     * Whether the enqueuing path asked the direct gates of a compact DM (ADR 2026-10.x9fa): DMs still switched on,
     * [recipientId] still a pinned board holder with the capability at the same address, and the bound slot not 0.
     * True on every direct frame.
     */
    val direct: Boolean = false,
    /**
     * Whether the enqueuing path held this direct frame back from a peer marked unreachable or refused, and so must
     * drop it if the peer is marked while it waits. True on an offer; false on the resend after a hearing and the
     * re-probe, which fly precisely to ask an unconfirmed peer again (ADR 2026-10.x9fa).
     */
    val reachHeld: Boolean = false,
    /**
     * The frame a resend or a re-probe re-airs, asked again at send whether it is still unacked (ADR 2026-10.x9fa): a
     * ✓✓ that landed while it waited makes it redundant. Never behind a timer, and null on an offer, whose author just
     * wrote it.
     */
    val unackedFrameId: String? = null,
)

/**
 * Why a queued frame was refused at the moment it reached the air rather than sent. One per [RideGate]
 * question, because "the queue held it until the answer changed" is only actionable if it says *which*
 * answer: [LINKED] and [INTERNET] mean the plane is doing its job, [STALE] means the queue is running deeper than the
 * freshness window, [NOT_OWN] that a frame fanned before our own id was known turned out to be somebody else's, and
 * the rest that a compact DM's own gates moved (ADR 2026-10.x9fa).
 */
internal enum class StaleAtSend {
    /** A better plane linked to the addressee while this waited; the link has carried it. */
    LINKED,

    /** The addressee turned up on a connected spool while this waited; the spool carries it (ADR 2026-09.y5f3). */
    INTERNET,

    /**
     * A compact DM whose addressee stopped being a direct board holder while it waited: DMs switched off, their pinned
     * profile dropped the board or the capability, or names another board now (ADR 2026-10.x9fa).
     */
    NOT_DIRECT,

    /** A compact DM offered to a peer that was marked unreachable or refused while it waited (ADR 2026-10.x9fa). */
    HELD,

    /** A resend or re-probe of a DM whose ✓✓ landed while it waited (ADR 2026-10.x9fa). */
    ACKED,

    /** The plane was bound to slot 0 while a compact DM waited: there the firmware would PKI-encrypt it (ADR 2026-10.x9fa). */
    SLOT_ZERO,

    /** It aged past [LoraFramePolicy.FRESH_MS] in the queue; it is custody's business now, not a live plane's. */
    STALE,

    /**
     * Not ours (ADR 2026-10.rp3s): fanned before our own id was read, and the id now says somebody else wrote it.
     * This plane airs only its own user's frames.
     */
    NOT_OWN,
}

/** A whole frame queued for the LoRa hop: its already-encoded fragment messages, a diagnostic label, its class. */
internal class OutboundFrame(
    val messages: List<ByteArray>,
    val label: String,
    val klass: FrameClass = FrameClass.ROOM,
    /** Which rolling budget this frame spends from; see [AirBucket]. */
    val bucket: AirBucket = AirBucket.LIVE,
    /** Which channel it is written to, and so which guard it must pass; see [Destination]. */
    val destination: Destination = Destination.Knit,
    /** Whom it is addressed to: the broadcast address for everything but a [Destination.Reply]. */
    val to: UInt = MeshtasticProto.BROADCAST,
    /**
     * A key identifying **the thing this frame is a snapshot of**, so that enqueuing a newer copy discards the
     * older one still waiting. Null for a frame that is an event rather than a state.
     *
     * The OFFER and the profile were the snapshots this existed for (ADR 2026-09.xdm2), and both left this plane
     * with the bridge (ADR 2026-10.rp3s). What still keys on it is a compact DM (`frameKey`, ADR 2026-10.x9fa): every
     * copy of one frame the offer, the resend or the re-probe might queue is one copy, so [LoraPacePolicy.hasQueued]
     * can see a copy still waiting. Stage 3's single budget must keep that one-copy rule.
     *
     * **A supersession is never a drop.** Nothing is lost that a newer frame does not already carry, so it
     * must not move `loraDroppedQueue`, whose job is to say when the plane shed something it wanted.
     */
    val supersedes: String? = null,
    /** The enqueue-time gates worth asking again on the way out; see [RideGate]. */
    val gate: RideGate = RideGate(),
    /** What a compact DM is and whom it is for (ADR 2026-10.x9fa), or null for a broadcast or a public post. */
    val direct: DirectSend? = null,
    /**
     * The sig-dedup slot the fan-out took for this frame, or null on every other path (ADR 2026-10.rp3s: only the
     * fan-out reads and writes it). Reopened when the frame is refused at send, for any [StaleAtSend] reason: it
     * never flew, and the slot would turn the next fan-out of the same bytes away. `SeenSet.reopen` lets one more copy
     * through per id per window, so a frame refused twice inside ten minutes keeps its slot the second time.
     */
    val sigKey: String? = null,
) {
    /**
     * How many of [messages] the board has already taken. A board that runs out of queue part-way through a
     * fragmented frame refuses the rest, and the frame is requeued **whole** — so without this cursor it
     * restarts at fragment 0, putting fragments the board already has back on a ~1 kbps medium and booking
     * their airtime a second time. Since the ledger only ever grows on a retry, a frame that keeps hitting a
     * full board inflates the hourly budget without bound until it refuses everything else on the plane.
     */
    var sentParts: Int = 0
        private set

    /**
     * Whether the airtime budget has ever made this frame wait. Held frames are **not** dropped — they stay
     * queued for a later window — so the plane can be starved while every drop counter reads zero, which is
     * how a 99 %-spent BRIDGE bucket looked healthy in the field. This flag is what lets that be counted once
     * per frame rather than once per pacer wake; see [LoraPacePolicy.lastAirtimeHolds].
     */
    var heldForAir: Boolean = false
        private set

    /** The fragments still owed to the board; the whole frame until one of them is refused part-way. */
    val remaining: List<ByteArray> get() = if (sentParts == 0) messages else messages.drop(sentParts)

    fun onPartSent() {
        sentParts++
    }

    fun onAirtimeHeld() {
        heldForAir = true
    }

    val fragmented: Boolean get() = messages.size > 1

    /**
     * The firmware's cliff for these packets, for [LoraAirtime]: a broadcast on Knit's port is signed under it by a 2.8
     * board, a direct unicast never is ([LoraAirtime.UNSIGNED]).
     */
    val signedUpTo: Int get() = if (direct != null) LoraAirtime.UNSIGNED else MeshtasticProto.MAX_SIGNED_PAYLOAD

    /**
     * What the pacer admits this frame against: what is still owed, and for a compact DM that one packet as often as
     * the firmware may put it on the air — the send and every retry ([LoraDirectPolicy.AIRINGS]). Admitting one
     * airing and then retrying three times would spend air no admission asked for (ADR 2026-10.x9fa).
     */
    fun admissionSizes(): List<Int> {
        val sizes = remaining.map { it.size }
        return if (direct == null) sizes else List(LoraDirectPolicy.AIRINGS) { sizes.first() }
    }
}

/**
 * A compact DM in the queue (ADR 2026-10.x9fa): the peer it is for, the board it is addressed to as the gates found it,
 * the frame id its outcome resolves, and what kind of thing it is. The bytes are already sealed — the transport never
 * reads them.
 */
internal class DirectSend(
    val peerId: String,
    val addr: BoardAddr,
    val frameId: String,
    val kind: DirectKind,
    /** Re-aired by a resend after a hearing, or by the re-probe — counted `loraDirectResent`, and never held. */
    val resend: Boolean = false,
    /**
     * The newest DM's resend after a hearing carries the ≤ 2 older unacked ids to flush once its destination acks it
     * (ADR 2026-10.x9fa), each asked again then whether it is still unacked.
     */
    val flushAfter: List<String> = emptyList(),
)
