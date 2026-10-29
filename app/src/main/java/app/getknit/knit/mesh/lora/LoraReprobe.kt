package app.getknit.knit.mesh.lora

/**
 * When the plane next re-airs a held DM to each recipient it has not heard (ADR 2026-10.rp3s): the backed-off
 * re-probe.
 *
 * The reach gate (ADR 2026-10.a4pm) holds a DM to an unheard recipient in custody and lets one DM per window out as a
 * probe, whose ✓✓ makes the recipient heard; their first hearing then re-offers the rest. Until ADR 2026-10.rp3s a
 * quiet recipient was made heard again by something nobody sent on purpose — their board's profile beacon, their
 * gateway's OFFER and serves. Those are gone, so a recipient who stays silent (their board was off when the probe
 * flew, and they have nothing to say) is heard by nothing, and the DMs held behind the lost probe would wait for a
 * radio or spool contact. This is the replacement: per recipient with own DMs held, the **newest** held one is
 * re-aired as a probe, first one [baseMs] after the hold, then at a gap that doubles to [capMs], until the recipient
 * is heard, a DM to them is acked (either resets the gap to [baseMs]), or no own unacked DM to them is left (the
 * transport [forget]s them; custody's TTL is what ends that in the worst case).
 *
 * Never a NodeInfo or telemetry packet as the hearing: `BoardQuiet` stretches those to hours, and the handshake
 * replays a synthetic NodeDB with stale readings. A ✓✓ from the recipient is the hearing, through the plane's
 * ordinary `noteReachable`.
 *
 * A limiter, so it outlives the process ([stamps] / [restore], `LoraPlaneSnapshot.reprobes`, ADR 2026-09.7svb): a
 * backoff that reset on launch would re-probe every silent recipient at the base gap after each restart. Stamps are
 * on the plane's monotonic clock here and converted at the snapshot. Not cleared by `quiesce`, like [LoraDmProbe].
 * Every entry point is `@Synchronized`: the fan-out (holding a DM) and the linger sweep (re-probing) run on
 * different coroutines.
 */
internal class LoraReprobe(
    private val baseMs: Long = LoraMeshTransport.DM_PROBE_WINDOW_MS,
    private val capMs: Long = CAP_MS,
) {
    /** One recipient's schedule: when it is next due, the gap that got it there, and the DM the last re-probe aired. */
    data class Entry(
        val recipientId: String,
        val dueAt: Long,
        val gapMs: Long,
        val lastFrameId: String? = null,
    )

    private val tracked = HashMap<String, Entry>()

    /**
     * An own DM to [recipientId] was held, went out as the window's probe, or flew to them while they were heard:
     * start their schedule one [baseMs] out. Nothing is re-probed while they stay heard (the transport defers), so
     * the last case costs no air — it is there for a DM lost on the way to a heard recipient, who has no first hearing
     * to repair it until they linger out. A recipient already scheduled keeps the backoff they have earned — a second
     * DM is not evidence they are back.
     */
    @Synchronized
    fun onHeld(
        recipientId: String,
        now: Long,
    ) {
        tracked.getOrPut(recipientId) { Entry(recipientId, now + baseMs, baseMs) }
    }

    /** The recipients due a re-probe at [now], soonest first. */
    @Synchronized
    fun due(now: Long): List<String> =
        tracked.values
            .filter { now >= it.dueAt }
            .sortedBy { it.dueAt }
            .map { it.recipientId }

    /**
     * A re-probe of [frameId] to [recipientId] reached the queue at [now]. The gap doubles to [capMs] — each
     * unanswered probe is more evidence the recipient is away — unless the DM the last one aired is no longer among
     * the [held] ones: then it was acked (or aged out of custody), which resets the gap to [baseMs].
     */
    @Synchronized
    fun onReprobed(
        recipientId: String,
        frameId: String,
        held: Set<String>,
        now: Long,
    ) {
        val entry = tracked[recipientId] ?: Entry(recipientId, now, baseMs)
        val acked = entry.lastFrameId != null && entry.lastFrameId !in held
        val gap = if (acked) baseMs else minOf(entry.gapMs * 2, capMs)
        tracked[recipientId] = Entry(recipientId, now + gap, gap, frameId)
    }

    /**
     * Not this round — the recipient is heard, covered, has a copy still queued, is not known to hold a board (no
     * `loraNode` pinned, or the holder source has not reported yet), or the probe window is spent: ask again one gap
     * on, without doubling it, since nothing went on the air. Only [forget] ends a schedule.
     */
    @Synchronized
    fun defer(
        recipientId: String,
        now: Long,
    ) {
        tracked[recipientId]?.let { tracked[recipientId] = it.copy(dueAt = now + it.gapMs) }
    }

    /** [recipientId] was heard on this plane: the next re-probe, if one is still needed, waits a single [baseMs]. */
    @Synchronized
    fun onHeard(
        recipientId: String,
        now: Long,
    ) {
        tracked[recipientId]?.let { tracked[recipientId] = Entry(recipientId, now + baseMs, baseMs) }
    }

    /** The gap [recipientId]'s schedule stands at, or null when they are not scheduled. */
    @Synchronized
    fun gapOf(recipientId: String): Long? = tracked[recipientId]?.gapMs

    /** No own unacked DM to [recipientId] is left: nothing to re-probe with. */
    @Synchronized
    fun forget(recipientId: String) {
        tracked.remove(recipientId)
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
        /** The longest gap between two re-probes to one silent recipient: six a day once it has backed off. */
        const val CAP_MS = 4 * 60 * 60_000L
    }
}
