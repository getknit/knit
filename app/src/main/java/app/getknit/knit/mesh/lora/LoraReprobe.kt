package app.getknit.knit.mesh.lora

/**
 * When the plane next re-airs the newest of our DMs to a peer that still has no ✓✓ (ADR 2026-10.rp3s, kept and
 * re-keyed by ADR 2026-10.x9fa): the backed-off re-probe.
 *
 * A compact DM is acknowledged by the destination's **board**, and that ack says nothing about its phone — away from
 * the board, the plane switched off, the app refusing the packet or its window. So the schedule keys on what the app
 * says, our own DMs with no ✓✓, whatever the radio said: per peer with own DMs unacked, the newest is re-aired one
 * [baseMs] after it went out, then at a gap that doubles to [capMs], until it is acked or none is left (the transport
 * [forget]s them). A quiet board may never be heard at all — `BoardQuiet` stretches its NodeInfo and telemetry to
 * hours — and this is what still asks it.
 *
 * Two events move it besides a round. A destination's ack of a fresh DM ([onAcked]) resets the gap to [baseMs], so a
 * board that just took a DM is asked again one base gap later if its phone does not answer; an ack of a re-aired one
 * only moves the next round one earned gap on. A hearing of a peer's board after a failed
 * send ([onHeard]) pulls the next round forward to one gap after the failure, or says a resend may go now — and never
 * resets the gap, since a board on the air is not a phone that read anything.
 *
 * A limiter, so it outlives the process ([stamps] / [restore], `LoraPlaneSnapshot.reprobes`, ADR 2026-09.7svb): a
 * backoff that reset on launch would re-probe every silent peer at the base gap after each restart. Stamps are on the
 * plane's monotonic clock here and converted at the snapshot; the last failure's time is not persisted (a restart
 * reads it as long past). Not cleared by `quiesce`. Every entry point is `@Synchronized`: the offer, the outcome
 * collector, the hearing hook and the linger sweep run on different coroutines.
 */
internal class LoraReprobe(
    private val baseMs: Long = BASE_MS,
    private val capMs: Long = CAP_MS,
) {
    /** One peer's schedule: when it is next due, the gap that got it there, and the DM the last re-probe aired. */
    data class Entry(
        val recipientId: String,
        val dueAt: Long,
        val gapMs: Long,
        val lastFrameId: String? = null,
    )

    private val tracked = HashMap<String, Entry>()

    /** When the last direct send to each peer failed (unreachable or no answer); in memory only. */
    private val failedAt = HashMap<String, Long>()

    /**
     * An own DM to [recipientId] was offered: start their schedule one [baseMs] out. A peer already scheduled keeps the
     * backoff they have earned — a second DM is not evidence they are back.
     */
    @Synchronized
    fun onHeld(
        recipientId: String,
        now: Long,
    ) {
        tracked.getOrPut(recipientId) { Entry(recipientId, now + baseMs, baseMs) }
    }

    /** The peers due a re-probe at [now], soonest first. */
    @Synchronized
    fun due(now: Long): List<String> =
        tracked.values
            .filter { now >= it.dueAt }
            .sortedBy { it.dueAt }
            .map { it.recipientId }

    /** The DM the last re-probe or resend to [recipientId] aired, or null. */
    @Synchronized
    fun lastFrameOf(recipientId: String): String? = tracked[recipientId]?.lastFrameId

    /**
     * A re-probe or resend of [frameId] to [recipientId] reached the queue at [now]. The gap doubles to [capMs] — each
     * unanswered attempt is more evidence the phone is away — unless [lastAcked]: the DM the last attempt aired has
     * its ✓✓ now, which resets the gap to [baseMs].
     */
    @Synchronized
    fun onReprobed(
        recipientId: String,
        frameId: String,
        lastAcked: Boolean,
        now: Long,
    ) {
        val entry = tracked[recipientId] ?: Entry(recipientId, now, baseMs)
        val gap = if (lastAcked) baseMs else minOf(entry.gapMs * 2, capMs)
        tracked[recipientId] = Entry(recipientId, now + gap, gap, frameId)
    }

    /**
     * The destination's board acked a DM to [recipientId]. The board is there; its phone may not be. For a [fresh] offer
     * — a DM the user just wrote — the schedule starts over: the newest DM still without its ✓✓ is asked about one
     * [baseMs] on. For a resend or a re-probe it keeps the gap it has earned and is asked one gap on: a board that acks
     * everything while its phone stays away must not pin the re-probe at the base gap (ADR 2026-10.x9fa). Only a ✓✓ on
     * the re-aired DM ([onReprobed]'s `lastAcked`) resets that.
     */
    @Synchronized
    fun onAcked(
        recipientId: String,
        now: Long,
        fresh: Boolean,
    ) {
        failedAt.remove(recipientId)
        val entry = tracked[recipientId] ?: return
        tracked[recipientId] = if (fresh) entry.copy(dueAt = now + baseMs, gapMs = baseMs) else entry.copy(dueAt = now + entry.gapMs)
    }

    /** A direct send to [recipientId] failed at [now]: unreachable, or no answer. The schedule runs on. */
    @Synchronized
    fun onFailed(
        recipientId: String,
        now: Long,
    ) {
        failedAt[recipientId] = now
    }

    /**
     * [recipientId]'s board was heard after being marked unreachable. True when a resend may go now — the current gap
     * has passed since the last failure (or there was none this process saw) — so one hearing after another cannot
     * re-air the backlog on each. Otherwise the next round is pulled forward to one gap after the failure. The gap is
     * never reset here.
     */
    @Synchronized
    fun onHeard(
        recipientId: String,
        now: Long,
    ): Boolean {
        val entry = tracked[recipientId] ?: return false
        val failed = failedAt[recipientId] ?: return true
        val ready = failed + entry.gapMs
        if (now >= ready) return true
        if (ready < entry.dueAt) tracked[recipientId] = entry.copy(dueAt = ready)
        return false
    }

    /**
     * Not this round — the peer is covered, has a send queued or in flight, refused us, or the board map has not
     * reported: ask again one gap on, without doubling it, since nothing went on the air. Only [forget] ends a
     * schedule.
     */
    @Synchronized
    fun defer(
        recipientId: String,
        now: Long,
    ) {
        tracked[recipientId]?.let { tracked[recipientId] = it.copy(dueAt = now + it.gapMs) }
    }

    /** No own unacked DM to [recipientId] is left, or they hold no board we may send to: nothing to re-probe with. */
    @Synchronized
    fun forget(recipientId: String) {
        tracked.remove(recipientId)
        failedAt.remove(recipientId)
    }

    /** Every schedule, soonest first — what the persisted snapshot carries. */
    @Synchronized
    fun stamps(): List<Entry> = tracked.values.sortedBy { it.dueAt }

    /** Re-applies [stamps] a previous process saved; a gap outside [baseMs]..[capMs] is clamped into it. */
    @Synchronized
    fun restore(stamps: List<Entry>) {
        stamps.forEach { tracked[it.recipientId] = it.copy(gapMs = it.gapMs.coerceIn(baseMs, capMs)) }
    }

    companion object {
        /**
         * The first gap: one airtime window. An absent phone costs at most one DM's airings per window, and a present
         * one has long since sent its ✓✓ (the coalescer holds it 45 s at most).
         */
        const val BASE_MS = 15 * 60_000L

        /** The longest gap between two re-probes to one silent peer: six a day once it has backed off. */
        const val CAP_MS = 4 * 60 * 60_000L
    }
}
