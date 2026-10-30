package app.getknit.knit.mesh.lora

/**
 * The outbound pacer for the LoRa plane: a bounded queue with a minimum inter-packet gap and a NAK
 * back-off, all pure and clock-driven by the caller (the transport owns the actual `delay`). LoRa is a
 * ~1 kbps shared medium — a 233-B packet is ~2.5 s on air and the board floods each one three hops — so
 * unpaced sends would swamp it and draw duty-cycle refusals.
 *
 * The queue sheds by **class** when full ([FrameClass], [OutboundFrame.shedRank]): from the lowest rank present — the
 * newcomer included, so a frame that is alone at the bottom yields instead of evicting anything — and the **oldest
 * whole frame** within it (never a lone fragment, which would strand a half-delivered message). So a room post never
 * evicts a DM, and inside [FrameClass.DIRECT] a ✓✓ goes before a DM or a reaction the user wrote (ADR 2026-10.ug4a).
 * A rate/duty-cycle NAK widens the gap for a cool-down window, and since ADR 044 an injected [LoraAirtime] holds a
 * rolling budget the queue is drained against — so a frame can also wait because the plane has spent the window, not
 * just because the gap has not elapsed. Dequeue runs the class order forwards (see [take]). And since ADR 2026-10.ug4a
 * the **shared-band brake** ([braked]) keeps Knit room posts queued while the board reports a busy channel. Tested on
 * the JVM ([app.getknit.knit.mesh.lora.LoraPacePolicyTest]).
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
    /** The airtime ledger; a frame is only taken if its whole cost fits under its [Ceiling]. */
    val airtime: LoraAirtime = LoraAirtime(),
    /** How old a channel-utilisation reading may be and still brake ([BRAKE_FRESH_MS]). */
    private val brakeFreshMs: Long = BRAKE_FRESH_MS,
) {
    private val queue = ArrayDeque<OutboundFrame>()
    private var lastSentAt = Long.MIN_VALUE
    private var boardFree: Int? = null
    private var nakUntil = 0L
    private var airtimeBlockedUntil = 0L

    /** When the brake lifts on its own, the reading it rests on gone stale, while only braked frames were left. */
    private var brakeBlockedUntil = 0L

    /**
     * The board's last channel-utilisation reading, in percent, and when it was taken on the caller's clock: what [braked]
     * reads. Only the board's live self-telemetry feeds it (ADR 2026-10.ug4a) — never the handshake's `NodeInfo`, whose
     * `device_metrics` can be hours old.
     */
    private var bandUtilPercent: Float? = null
    private var bandReadAt = 0L

    /** Frames left in the queue that the airtime budget refused on the last [take]; diagnostics only. */
    var lastAirtimeRefusals = 0
        private set

    /**
     * How many frames the airtime budget held for the **first** time on the last [take]: a given frame counts at most once
     * in its whole life (see [OutboundFrame.heldForAir]).
     *
     * [lastAirtimeRefusals] answers "how many are stuck right now", which is a level and is re-read every
     * wake; this answers "what just became stuck", which is an event and is what a counter may be built on.
     */
    var lastAirtimeHolds = 0
        private set

    /** How many Knit room posts the shared-band brake held for the **first** time on the last [take] ([OutboundFrame.heldForBand]). */
    var lastBandBrakes = 0
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
        // A newcomer may fit where everything queued did not — a DM where only braked room posts waited, a small frame
        // where a large one did not fit — so both deferrals are facts about the queue as it stood, not standing cool-downs.
        airtimeBlockedUntil = 0L
        brakeBlockedUntil = 0L
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
        brakeBlockedUntil = 0L
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
        // Full: shed from the lowest rank present, newcomer included — a room post, then a ✓✓, then a DM or a reaction.
        val lowest = maxOf(queue.maxOf { it.shedRank }, frame.shedRank)
        val victim = queue.indexOfFirst { it.shedRank == lowest }
        if (victim < 0) {
            // The newcomer alone is the lowest rank: it yields — and, never queued, it is handed back like any shed
            // frame, so whatever it held (its dedup slot, a compact DM's parked offers) is given back by the caller.
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
     * The earliest time the next frame may go out: the min gap since the last send, any NAK cool-down, and — once
     * [take] has found nothing it may send — the soonest the answer can change by itself: the rolling window freeing
     * air for a frame it refused, or the brake's reading going stale for a room post it braked.
     *
     * That last term is what keeps the caller's drain loop asleep. Without it a queue nothing may leave
     * reports a due time already in the past, so the loop computes a zero wait, never suspends, and spins a
     * core flat for as long as the budget stays spent (and, having no suspension point, cannot be cancelled).
     * A braked-only queue is the same shape: the brake lifts on a new reading ([onBandReading], which the caller
     * wakes the loop for) or when the last one ages out, and on nothing in between.
     */
    @Synchronized
    fun nextDueAt(): Long =
        maxOf(
            if (lastSentAt == Long.MIN_VALUE) 0L else lastSentAt + minGapMs,
            nakUntil,
            soonestOf(airtimeBlockedUntil, brakeBlockedUntil),
        )

    /** The earlier of two deferrals, 0 meaning "none": a queue holding both kinds moves when the first one lifts. */
    private fun soonestOf(
        a: Long,
        b: Long,
    ): Long =
        when {
            a == 0L -> b
            b == 0L -> a
            else -> minOf(a, b)
        }

    /**
     * The board's channel utilisation, [percent] of the last minute heard busy — its TX, its RX and the noise together,
     * our own airings included (`airtime.h`, firmware 2.8) — read at [now]. Only its live self-telemetry, about once a
     * minute: the handshake's `NodeInfo` carries `device_metrics` that can be hours old and never reaches here. A new
     * reading is the one event that lifts a brake before its staleness does, so the braked deferral ends with it.
     */
    @Synchronized
    fun onBandReading(
        percent: Float?,
        now: Long,
    ) {
        if (percent == null) return
        bandUtilPercent = percent
        bandReadAt = now
        brakeBlockedUntil = 0L
    }

    /** The board is gone ([LoraMeshTransport]'s quiesce): its reading was about the band around it, and goes with it. */
    @Synchronized
    fun forgetBand() {
        bandUtilPercent = null
        brakeBlockedUntil = 0L
    }

    /** When the reading [braked] rests on was taken, or null when there is none — diagnostics. */
    @Synchronized
    fun bandReadAt(): Long? = if (bandUtilPercent == null) null else bandReadAt

    /**
     * Whether the shared band is too busy for a Knit room post at [now] (ADR 2026-10.ug4a): the board's last reading at
     * or over [BUSY_BAND_PERCENT] — the firmware's `max_channel_util_percent`, compared `>=` as it does, where it stops
     * even its own automatic traffic — no more than [brakeFreshMs] old, and the board
     * not on a dedicated RF slot, where any congestion is Knit's own and ADR 067 lifts the manners this is.
     * Asks [LoraAirtime.dedicated] under this monitor, the one lock order.
     */
    @Synchronized
    fun braked(now: Long): Boolean {
        val util = bandUtilPercent ?: return false
        return util >= BUSY_BAND_PERCENT && now - bandReadAt <= brakeFreshMs && !airtime.dedicated()
    }

    /**
     * The next frame to send, or null when the queue is empty, the gap/cool-down has not elapsed, the board
     * has no queue headroom, or nothing queued may go: over the window, or a room post the brake holds. Stamps the
     * send time so the next frame waits a full gap.
     *
     * Dequeue is **by class, then FIFO within it** — the same order the queue sheds in, run forwards: [FrameClass.DIRECT]
     * (a DM, its ✓✓, a reaction, a resend, a re-probe) before [FrameClass.ROOM] (a room post, the Meshtastic room, the
     * auto-reply).
     *
     * A frame the airtime budget refuses is **skipped, not dropped** — it stays queued for a later window while a
     * frame that fits goes now. Without the skip, one refused Meshtastic-room post would block everything behind it
     * until the window recovered. A frame that never gets a window eventually ages out through the ordinary class
     * shedding instead. The one exception: nothing is skipped *past* a direct frame the window's total refused, or the
     * smaller frames behind it would take the air it is waiting for, one packet's worth at a time, for as long as they
     * keep coming ([admitBest]). A room post the brake holds is skipped the same way and holds nothing.
     */
    @Synchronized
    fun take(now: Long): OutboundFrame? {
        // Cleared on entry, not only where they are filled: this returns early on an empty queue, an unelapsed
        // gap or a full board, and a caller reading them after every call would otherwise re-report the last
        // call's holds on every wake — the per-wake counting the flags exist to avoid.
        lastAirtimeHolds = 0
        lastBandBrakes = 0
        if (!mayDrain(now)) return null
        val best = admitBest(now)
        if (best < 0) {
            // Nothing may go: defer to the soonest the answer changes by itself. Air returning, for frames the window
            // refused — null there (an empty ledger, so the frame is simply bigger than the whole allowance) leaves the
            // caller's own floor to pace the retry, and that frame ages out through class shedding. The reading going
            // stale, for room posts the brake holds — a new reading ends it sooner, through [onBandReading].
            airtimeBlockedUntil = if (lastAirtimeRefusals > 0) airtime.nextReleaseAt(now) ?: 0L else 0L
            brakeBlockedUntil = if (lastBraked > 0) bandReadAt + brakeFreshMs + 1 else 0L
            return null
        }
        airtimeBlockedUntil = 0L
        brakeBlockedUntil = 0L
        lastSentAt = now
        return queue.removeAt(best).also { inFlight = it }
    }

    /** Frames the brake skipped on the last [take]'s walk: a level, read only to set the braked deferral. */
    private var lastBraked = 0

    private fun ArrayDeque<OutboundFrame>.removeAllReturningCount(predicate: (OutboundFrame) -> Boolean): Int {
        val before = size
        removeAll(predicate)
        return before - size
    }

    /** Whether anything at all may leave now: something queued, the gap and cool-downs elapsed, board room. */
    private fun mayDrain(now: Long): Boolean = queue.isNotEmpty() && now >= nextDueAt() && boardFree != 0

    /**
     * The index of the frame to send, or -1 when nothing may go: the budget refuses everything not braked, the brake
     * holds the rest, or everything it would admit ranks behind a direct frame the window's total is holding. Records
     * [lastAirtimeRefusals], [lastAirtimeHolds] and [lastBandBrakes] on the way past.
     *
     * A refused frame is skipped rather than blocking the queue (ADR 044), but not past a direct frame the **total**
     * refused: air comes back as the window rolls, a packet's worth at a time, and a one-packet room post fits each
     * time while a frame that needs more never does. Field-observed 2026-10-04: a DM queued at 16:28 waited eleven
     * minutes behind the room posts that kept taking that air. So the best-ranked such frame holds every frame ranked
     * behind it (class, then queue order) until the air it needs has come back. Only a [FrameClass.DIRECT] frame holds,
     * and only on [LoraAirtime.Refusal.TOTAL] (ADR 2026-10.ug4a): a Meshtastic-room post refused on its
     * [LoraAirtime.Refusal.CEILING] must never hold Knit traffic (the ceiling exists so the foreign room cannot crowd
     * it), one no window would admit must never be waited for, and a room post the brake holds is waiting for the
     * band, not for air. Narrowing the holder to direct frames is safe because every room post is one packet: no room
     * post can be starved by smaller ones the way a larger DM was.
     */
    private fun admitBest(now: Long): Int {
        val braking = braked(now)
        val brakedAt = BooleanArray(queue.size) { braking && queue[it].braked }
        // What is still owed, not the whole frame: a resumed frame's earlier fragments are already on the air and
        // already booked, so charging admission for them again would refuse a frame that fits. A braked frame is not
        // asked at all: it is not waiting for air, and its refusal must neither hold nor count as one.
        val refusals =
            queue.mapIndexed { i, frame ->
                if (brakedAt[i]) null else airtime.refusal(frame.ceiling, frame.admissionSizes(), now, frame.signedUpTo)
            }
        val best = bestAdmitted(refusals, brakedAt)
        val holder = holderOf(refusals)
        // Everything admissible ranks behind the holder when the best of it does: it all waits with the holder.
        val blocked = best >= 0 && holder >= 0 && ranksBehind(best, holder)
        var airHolds = 0
        var bandHolds = 0
        var waiting = 0
        var braked = 0
        for (i in queue.indices) {
            val frame = queue[i]
            when {
                brakedAt[i] -> {
                    braked++
                    if (!frame.heldForBand) {
                        frame.onBandBraked()
                        bandHolds++
                    }
                }

                refusals[i] != null || blocked -> {
                    waiting++
                    if (!frame.heldForAir) {
                        frame.onAirtimeHeld()
                        airHolds++
                    }
                }
            }
        }
        lastAirtimeRefusals = waiting
        lastAirtimeHolds = airHolds
        lastBandBrakes = bandHolds
        lastBraked = braked
        return if (blocked) -1 else best
    }

    /**
     * The best-ranked frame [refusals] admits that the brake does not hold, or -1: strictly-better class only, so the
     * earliest of a class wins.
     */
    private fun bestAdmitted(
        refusals: List<LoraAirtime.Refusal?>,
        brakedAt: BooleanArray,
    ): Int {
        var best = -1
        for (i in queue.indices) {
            if (brakedAt[i] || refusals[i] != null) continue
            if (best < 0 || queue[i].klass < queue[best].klass) best = i
        }
        return best
    }

    /** The best-ranked direct frame the window's total refused, or -1: the one nothing ranked behind may pass. */
    private fun holderOf(refusals: List<LoraAirtime.Refusal?>): Int {
        var holder = -1
        for (i in queue.indices) {
            val holds = refusals[i] == LoraAirtime.Refusal.TOTAL && queue[i].klass == FrameClass.DIRECT
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

    companion object {
        private const val MIN_GAP_MS = 3_000L

        // Raised from 12 with the bridge (ADR 044, gone since ADR 2026-10.rp3s, which kept the cap): a resend after
        // a hearing can still enqueue a small burst behind live traffic, and class shedding — not the cap — is what protects
        // the live frames. Doubled again with the 15-min
        // airtime window (ADR 054): a burst that outruns the window now waits ≤ 15 min for air, and a queue
        // that can hold that wait (≤ ~700 B a frame) sheds nothing a later window would have carried.
        private const val QUEUE_CAP_FRAMES = 32
        private const val NAK_BACKOFF_MS = 60_000L

        /**
         * The channel utilisation at which a Knit room post waits (ADR 2026-10.ug4a): the firmware's
         * `max_channel_util_percent` (40, `src/airtime.h:230`), compared `>=` as `isTxAllowedChannelUtil` does — the
         * figure at which it stops even its own non-polite automatic traffic, so Knit holds a typed room post only where
         * the firmware holds back everything optional of its own. Still stricter than stock: the firmware never gates
         * text a client originates, so a stock client would send the post. On a busy metro slot, at or over 40 % most of
         * the time, Knit room posts may never cross LoRa and drop as `STALE` after `FRESH_MS`; the radios, custody and
         * the spool still carry them.
         */
        const val BUSY_BAND_PERCENT = 40f

        /**
         * How old a reading may be and still brake. The board reports one about once a minute; five of them missed
         * is a board that stopped reporting, and a brake on its last word would hold room posts for nothing.
         */
        const val BRAKE_FRESH_MS = 5 * 60_000L
    }
}

/**
 * The pacing priority of a queued frame, highest first (ADR 2026-10.ug4a): [DIRECT] dequeues before [ROOM], and a full
 * queue sheds [ROOM] first. Two, where there were three: ADR 054's `TICK` folded into [DIRECT], because the ✓✓ is one
 * unsigned unicast packet now, and losing it costs a re-probe of the whole DM later. Inside [DIRECT] a ✓✓ is still
 * shed before a DM or a reaction ([OutboundFrame.shedRank]) — so a ✓✓ never pushes out something the user typed — but
 * dequeued in its turn.
 *
 * What a frame may spend is [Ceiling], which is orthogonal: the Meshtastic room's post and the auto-reply ride [ROOM]
 * here and [Ceiling.HALF] there.
 */
internal enum class FrameClass {
    /** A compact DM, its ✓✓, a DM reaction, a resend after a hearing, a re-probe: one unicast to one board. */
    DIRECT,

    /** A Knit room post, the Meshtastic room's post and the DM auto-reply: a broadcast, or the room's one unicast. */
    ROOM,
}

/**
 * Where a queued frame is going, which decides both the channel it is written to and the guard it must pass.
 *
 * The two are kept apart deliberately (§5 of work item #37). `boundSlotIsKnit` exists to stop Knit's own
 * cleartext frames reaching the public channel *by accident* — the failure it guards against is silence being
 * safer than a mistake — while [Public] is a deliberate, consented path to index 0 whose guard asks the
 * opposite question. Sharing code between them would let a fix to one quietly relax the other.
 *
 * They do share the **queue**: one airtime ledger, one 3 s inter-packet gap, one NAK back-off, one
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
 * queue, a spent window and a busy band, which together can hold it for a whole window. Every gate the enqueuing
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

    /**
     * The plane was bound to slot 0 while a compact DM waited (ADR 2026-10.x9fa): Knit's frames belong on the Knit
     * channel. Not about PKI: firmware 2.8 PKI-encrypts a unicast on any slot (the PKI amendment).
     */
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
    /** How far into the window's one allowance this frame may take the total; see [Ceiling]. */
    val ceiling: Ceiling = Ceiling.FULL,
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
     * can see a copy still waiting; the single budget of ADR 2026-10.ug4a kept that one-copy rule.
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
     * how a 99 %-spent budget looked healthy in the field. This flag is what lets that be counted once
     * per frame rather than once per pacer wake; see [LoraPacePolicy.lastAirtimeHolds].
     */
    var heldForAir: Boolean = false
        private set

    /** Whether the shared-band brake has ever held this frame; counted once per frame, like [heldForAir]. */
    var heldForBand: Boolean = false
        private set

    /** The fragments still owed to the board; the whole frame until one of them is refused part-way. */
    val remaining: List<ByteArray> get() = if (sentParts == 0) messages else messages.drop(sentParts)

    fun onPartSent() {
        sentParts++
    }

    fun onAirtimeHeld() {
        heldForAir = true
    }

    fun onBandBraked() {
        heldForBand = true
    }

    /**
     * Whether the shared-band brake applies to this frame (ADR 2026-10.ug4a): a Knit room post — a broadcast on the
     * bound slot. Never a compact DM, nor the Meshtastic room's post or the auto-reply: those are the band's own kind of
     * traffic, already floored at 30 s and capped at half the window.
     */
    val braked: Boolean get() = klass == FrameClass.ROOM && destination == Destination.Knit

    /**
     * Where this frame stands when a full queue sheds, lowest first: a room post (2), then a ✓✓ (1), then a DM or a
     * reaction (0) — so the ✓✓ the queue can rebuild from the next delivery goes before a message the user typed
     * (ADR 2026-10.ug4a). Dequeue reads [klass] alone.
     */
    val shedRank: Int
        get() =
            when {
                klass == FrameClass.ROOM -> SHED_ROOM
                direct?.kind == DirectKind.RECEIPT -> SHED_RECEIPT
                else -> SHED_MESSAGE
            }

    val fragmented: Boolean get() = messages.size > 1

    /**
     * The firmware's cliff for these packets, for [LoraAirtime] — at admission and at the booking alike. A broadcast is
     * signed under its port's cliff by a 2.8 board (a Meshtastic-room post one byte further, on the text port); a
     * unicast is not ([LoraAirtime.UNSIGNED]): `Router::perhapsEncode` signs only a broadcast, a `NodeInfo` or a
     * position unless the board is in ham mode, so neither a compact DM nor the auto-reply carries a signature. A compact
     * DM from a board in ham mode ([DirectSend.licensed]) is the exception: no PKI there, and the unicast is signed when
     * it fits (`Router.cpp:1271` at v2.8.1, ADR 2026-10.aajj).
     */
    val signedUpTo: Int
        get() =
            when {
                direct?.licensed == true -> MeshtasticProto.MAX_SIGNED_PAYLOAD
                direct != null || destination == Destination.Reply -> LoraAirtime.UNSIGNED
                destination == Destination.Public -> MeshtasticProto.maxSignedPayload(MeshtasticProto.PORT_TEXT_MESSAGE)
                else -> MeshtasticProto.MAX_SIGNED_PAYLOAD
            }

    /**
     * What the pacer admits this frame against: what is still owed, and for a compact DM that one packet as often as
     * the firmware may put it on the air — the send and every retry ([LoraDirectPolicy.AIRINGS]). Admitting one
     * airing and then retrying three times would spend air no admission asked for (ADR 2026-10.x9fa).
     */
    fun admissionSizes(): List<Int> {
        val sizes = remaining.map { it.size + pricedExtraBytes }
        return if (direct == null) sizes else List(LoraDirectPolicy.AIRINGS) { sizes.first() }
    }

    /**
     * Bytes the firmware adds on the air that [messages] do not carry, priced at admission, at booking and in a compact
     * DM's flight alike: the PKC overhead of a PKI unicast — the DM auto-reply (ADR 2026-10.ug4a) and every compact DM.
     * Firmware 2.8 PKI-encrypts a unicast its phone originates on any channel, the Knit secondary included, and refuses
     * it (`39`) rather than fall back to the channel key, so a compact DM that airs at all airs PKI (ADR 2026-10.x9fa's
     * PKI amendment). Only a board in ham mode skips PKI ([DirectSend.licensed]); it signs instead ([signedUpTo]).
     */
    val pricedExtraBytes: Int
        get() =
            when {
                direct != null -> if (direct.licensed) 0 else MeshtasticProto.PKC_OVERHEAD_BYTES
                destination == Destination.Reply -> MeshtasticProto.PKC_OVERHEAD_BYTES
                else -> 0
            }

    private companion object {
        const val SHED_ROOM = 2
        const val SHED_RECEIPT = 1
        const val SHED_MESSAGE = 0
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
    /**
     * Our board was in ham mode (`User.is_licensed`) when this was queued: the firmware neither PKI-encrypts it nor can
     * refuse it for a missing key, and signs it when it fits — priced so ([OutboundFrame.signedUpTo]). Every other
     * board PKI-encrypts it (ADR 2026-10.aajj).
     */
    val licensed: Boolean = false,
    /**
     * The one re-offer after the destination's board answered `PKI_UNKNOWN_PUBKEY` (35) and our board sent it our
     * `NodeInfo` (ADR 2026-10.aajj): a second 35 to it is a give-up, never another re-offer.
     */
    val afterPeerKey: Boolean = false,
)
