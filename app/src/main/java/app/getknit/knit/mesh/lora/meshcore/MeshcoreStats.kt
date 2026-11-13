package app.getknit.knit.mesh.lora.meshcore

/**
 * The board's transmit headroom as the pacer reads it (ADR 2026-11.gsqu §6; binding rule "sends" 5), from `GET_STATS`
 * CORE `outbound_queue` and PACKETS `sent`. The firmware's packet pool is 16 (`StaticPoolPacketManager(16)`,
 * `MyMesh.cpp:900`) and shared with received packets, so `TABLE_FULL` can come while the queue reads low: `free =
 * POOL − outbound_queue` is an upper bound only. On `TABLE_FULL` the headroom reads 0 until a poll shows progress — the
 * queue fell, or `sent` rose — since the last reading before it, or reads an empty queue, or [maxHeldPolls] polls have
 * held it: a `TABLE_FULL` drawn by received packets on a quiet board shows neither, and a hold with no exit would refuse
 * every send until a reconnect. A hold lifted too early costs one more refused write, which holds again. A counter that
 * goes backwards is a reboot: the pool is empty, and the hold lifts.
 *
 * Pure, and every entry point `@Synchronized`.
 */
internal class MeshcoreHeadroom(
    private val pool: Int = POOL,
    private val maxHeldPolls: Int = MAX_HELD_POLLS,
) {
    private var heldPolls = 0
    private var lastQueue: Int? = null
    private var lastSent: Long? = null
    private var held = false
    private var heldQueue: Int? = null
    private var heldSent: Long? = null

    /** Whether a `TABLE_FULL` holds the headroom at 0. */
    @get:Synchronized
    val holding: Boolean get() = held

    /** One poll's `outbound_queue` and `sent` (null when that read failed): the headroom to emit. */
    @Synchronized
    fun onPoll(
        outboundQueue: Int,
        sent: Long?,
    ): Int {
        val queue = outboundQueue.coerceIn(0, pool)
        val rebooted = sent != null && lastSent?.let { sent < it } == true
        if (held) {
            heldPolls++
            if (rebooted || heldPolls > maxHeldPolls || progressed(queue, sent)) {
                held = false
            } else {
                // The first reading after a hold with nothing known before it is the baseline progress is read against.
                if (heldQueue == null) heldQueue = queue
                if (heldSent == null) heldSent = sent
            }
        }
        lastQueue = queue
        if (sent != null) lastSent = sent
        return if (held) 0 else pool - queue
    }

    /** Whether a held poll shows the board draining: the queue empty or lower than at the hold, or `sent` higher. */
    private fun progressed(
        queue: Int,
        sent: Long?,
    ): Boolean {
        val fell = queue == 0 || heldQueue?.let { queue < it } == true
        return fell || (sent != null && heldSent?.let { sent > it } == true)
    }

    /** The board refused a send `TABLE_FULL`: 0 until a poll shows progress. */
    @Synchronized
    fun onTableFull(): Int {
        if (!held) {
            held = true
            heldPolls = 0
            heldQueue = lastQueue
            heldSent = lastSent
        }
        return 0
    }

    /** A new connection: nothing known. */
    @Synchronized
    fun reset() {
        lastQueue = null
        lastSent = null
        held = false
        heldQueue = null
        heldSent = null
    }

    companion object {
        /** The firmware's packet pool (`StaticPoolPacketManager(16)`, `MyMesh.cpp:900`; bench Q4: ~16 in flight). */
        const val POOL = 16

        /** The most polls a hold outlasts with no progress shown: the next one lifts it. */
        const val MAX_HELD_POLLS = 2
    }
}

/**
 * The band around the board from RADIO stats (ADR 2026-11.gsqu §6): channel utilisation is Δ(`rx_air` + `tx_air`) over a
 * sample ring of at least [spanMs], and our own airtime Δ`tx_air`. The counters are whole seconds, so a 1 s poll reads
 * 0, 100 or 200 %; the 60-s ring keeps the error within ±3.3 points at the brake's 40 %. A counter, the clock or the
 * board's uptime going backwards (a reboot) restarts the ring. `rx_air` is the firmware's estimate over the packets it
 * parsed — no noise, no CRC errors — and a board with client repeat on inflates both figures (the ADR's cost section).
 *
 * A [Reading]'s [Reading.channelUtilPercent] is what Stage 5a-2 hands `LoraPacePolicy.onBandReading` (the brake at its
 * `BUSY_BAND_PERCENT`, 40 %, compared `>=`), timed on the poll that produced it.
 *
 * Pure, and every entry point `@Synchronized`.
 */
internal class MeshcoreAirRing(
    private val spanMs: Long = SPAN_MS,
) {
    private class Sample(
        val atMs: Long,
        val txSecs: Long,
        val rxSecs: Long,
        val uptimeSecs: Long?,
    ) {
        /** Whether a reading at [nowMs] went backwards from this one, in time, in either counter or in uptime: a reboot. */
        fun after(
            nowMs: Long,
            tx: Long,
            rx: Long,
            uptime: Long?,
        ): Boolean = nowMs <= atMs || tx < txSecs || rx < rxSecs || (uptime != null && uptimeSecs != null && uptime < uptimeSecs)
    }

    /** What one reading yields: both percentages over the ring. */
    data class Reading(
        val channelUtilPercent: Float,
        val airUtilTxPercent: Float,
    )

    private val ring = ArrayDeque<Sample>()

    /**
     * One RADIO stats sample at [nowMs], with the CORE stats' [uptimeSecs] of the same poll when it was read; a reading
     * once the ring spans [spanMs], else null.
     */
    @Synchronized
    fun onSample(
        nowMs: Long,
        txAirSecs: Long,
        rxAirSecs: Long,
        uptimeSecs: Long? = null,
    ): Reading? {
        val last = ring.lastOrNull()
        if (last != null && last.after(nowMs, txAirSecs, rxAirSecs, uptimeSecs)) ring.clear()
        ring.addLast(Sample(nowMs, txAirSecs, rxAirSecs, uptimeSecs))
        // Keep exactly one sample at or past the span's start: it is the baseline the reading is taken over.
        while (ring.size > 2 && ring[1].atMs <= nowMs - spanMs) ring.removeFirst()
        val base = ring.first()
        val elapsed = nowMs - base.atMs
        if (elapsed < spanMs) return null
        return Reading(
            channelUtilPercent = percent(txAirSecs - base.txSecs + rxAirSecs - base.rxSecs, elapsed),
            airUtilTxPercent = percent(txAirSecs - base.txSecs, elapsed),
        )
    }

    @Synchronized
    fun reset() = ring.clear()

    private fun percent(
        secs: Long,
        elapsedMs: Long,
    ): Float = (secs * MS_PER_SECOND * FULL / elapsedMs).toFloat().coerceIn(0f, FULL.toFloat())

    companion object {
        /** The ring's shortest span: 60 s, so the whole-second counters read within ±3.3 points at 40 %. */
        const val SPAN_MS = 60_000L
        private const val MS_PER_SECOND = 1_000.0
        private const val FULL = 100.0
    }
}
