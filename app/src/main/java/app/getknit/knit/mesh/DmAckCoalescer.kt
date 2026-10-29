package app.getknit.knit.mesh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Holds the delivery receipts we owe for DMs that arrived over a **slow plane** (the LoRa board), so that a
 * burst of messages from one author becomes one sealed `CTL_RECEIPT` and a reply we send meanwhile can carry
 * the acks for free (ADR 054).
 *
 * Why it exists: over LoRa a sealed ✓✓ costs as much air as the message it acks (~3 s of a 45-s window), and
 * `InboundPipeline` re-acks a DM on every re-delivery — so a casual conversation spent half its airtime on
 * ticks. Holding them for [holdMs] turns N ticks into one, and [take] lets the next outbound DM to that
 * author carry them inline instead.
 *
 * Deliberately **not** folded into [AckSync]: that class encodes the broadcast/group rules (ADR 033/053 — a
 * live-link form, verbatim retries, a done-ledger). A DM tick has none of those: nothing retries it (it is
 * custodied once), and re-acking on re-delivery is the LoRa healing channel (ADR 039 §3), so there is no
 * ledger to keep. Only the debounce shape is shared, and it is mirrored here in style rather than reused. Off the
 * board a re-delivery's sealed re-ack is bounded per era by [DmReAckLedger]; a flush here records nothing there, so
 * a DM acked over LoRa costs at most one more receipt on a later re-delivery off it (ADR 2026-10.8hn6).
 *
 * In-memory only, like AckSync's ledger: a process death inside the hold loses the pending acks. Only a
 * re-delivery of the DM re-holds and re-ticks it, and since ADR 018 that is no longer a matter of course: we
 * custody our own inbound DMs, so the digests agree and no carrier re-serves one we hold. It takes real
 * divergence (our copy evicted, a wipe) or a plane that re-offers without a digest (LoRa). A cancelled caller no
 * longer cuts the hold short (ADR 2026-10.f82h); a process death still can (#56).
 * Pure — [now] and [flushScope] are injected; the JVM tests drive it on a virtual clock.
 */
class DmAckCoalescer(
    private val now: () -> Long = { System.currentTimeMillis() },
    /** Seals ONE `CTL_RECEIPT` covering [ackIds] toward the author and originates it; owns its own fallback. */
    private val flush: suspend (authorId: String, ackIds: List<String>) -> Unit,
    /** Where the hold timer runs; null (the default) leaves [flushDue] to a caller's heartbeat. */
    private val flushScope: () -> CoroutineScope? = { null },
    private val holdMs: Long = HOLD_MS,
    private val maxBatch: Int = MAX_LORA_TICK_ACKS,
    private val pendingCap: Int = PENDING_CAP,
    private val ttlMs: Long = TTL_MS,
) {
    /** Acks accumulating toward one author: frameId → recordedAt in arrival order; guarded by `synchronized(this)`. */
    private class Batch {
        val ids = LinkedHashMap<String, Long>()
        var wakeArmed = false
    }

    private val pending = ConcurrentHashMap<String, Batch>()

    /**
     * Records that we owe [authorId] a receipt for [frameId]. A repeated id (a re-delivery inside the hold) is
     * held once. The hold is anchored on the batch's **oldest** id, so a chatty peer bounds the tick's delay
     * at [holdMs] rather than pushing it out; a full batch flushes at once, and past [pendingCap] the oldest
     * author's batch flushes early rather than anything being dropped.
     */
    suspend fun hold(
        authorId: String,
        frameId: String,
    ) {
        while (true) {
            val batch = pending.computeIfAbsent(authorId) { Batch() }
            var full = false
            var armWake = false
            val inserted =
                synchronized(batch) {
                    // A concurrent flush may have detached this batch between computeIfAbsent and here;
                    // an id landed in a detached batch would be lost, so retry into a fresh one.
                    if (pending[authorId] !== batch) return@synchronized false
                    batch.ids.putIfAbsent(frameId, now())
                    full = batch.ids.size >= maxBatch
                    if (!full && !batch.wakeArmed) {
                        batch.wakeArmed = true
                        armWake = true
                    }
                    true
                }
            if (!inserted) continue
            when {
                full -> {
                    flushAuthor(authorId)
                }

                pendingTotal() >= pendingCap -> {
                    flushOldest()
                }

                armWake -> {
                    flushScope()?.launch {
                        delay(holdMs)
                        flushDue()
                    }
                }
            }
            return
        }
    }

    /** Flushes every batch whose oldest id has waited out the hold, after ageing out anything past [ttlMs]. */
    suspend fun flushDue() {
        sweep()
        val cutoff = now() - holdMs
        pending.keys.toList().forEach { authorId ->
            val batch = pending[authorId] ?: return@forEach
            val due =
                synchronized(batch) {
                    batch.ids.values
                        .firstOrNull()
                        ?.let { it <= cutoff } == true
                }
            if (due) flushAuthor(authorId)
        }
    }

    /** Flushes [authorId]'s batch now, whatever its age: one tick covering every pending id. */
    suspend fun flushAuthor(authorId: String) {
        val batch = pending.remove(authorId) ?: return
        val ids = synchronized(batch) { batch.ids.keys.toList() }
        if (ids.isEmpty()) return
        flush(authorId, ids)
    }

    /** When the earliest pending batch falls due, or null when nothing is pending — for a caller that paces itself. */
    fun nextDueAt(): Long? =
        pending.values
            .mapNotNull { batch -> synchronized(batch) { batch.ids.values.firstOrNull() } }
            .minOrNull()
            ?.plus(holdMs)

    /**
     * Removes up to [max] of the oldest ids owed to [authorId] so the caller can carry them inline on a frame
     * it is sending anyway (the piggyback). An emptied batch is dropped, which cancels its standalone tick;
     * a partly-taken one keeps its hold for the rest. The caller [giveBack]s them if its send falls through.
     */
    fun take(
        authorId: String,
        max: Int,
    ): List<String> {
        val batch = pending[authorId] ?: return emptyList()
        return synchronized(batch) {
            val taken = batch.ids.keys.take(max)
            taken.forEach { batch.ids.remove(it) }
            if (batch.ids.isEmpty()) pending.remove(authorId, batch)
            taken
        }
    }

    /** Restores ids a [take] could not carry after all; they re-enter the hold from now. */
    suspend fun giveBack(
        authorId: String,
        ids: List<String>,
    ) {
        ids.forEach { hold(authorId, it) }
    }

    /** The ids currently owed to [authorId], oldest first (diagnostics and tests). */
    fun pending(authorId: String): List<String> {
        val batch = pending[authorId] ?: return emptyList()
        return synchronized(batch) { batch.ids.keys.toList() }
    }

    private fun pendingTotal(): Int = pending.values.sumOf { synchronized(it) { it.ids.size } }

    private suspend fun flushOldest() {
        val oldest =
            pending.entries.minByOrNull { (_, batch) ->
                synchronized(batch) { batch.ids.values.firstOrNull() ?: Long.MAX_VALUE }
            } ?: return
        flushAuthor(oldest.key)
    }

    /** Ages out ids past [ttlMs] — a DM that old has left custody, so its tick would ack nothing. */
    private fun sweep() {
        val cutoff = now() - ttlMs
        pending.entries.removeAll { (_, batch) ->
            synchronized(batch) {
                batch.ids.entries.removeAll { it.value < cutoff }
                batch.ids.isEmpty()
            }
        }
    }

    companion object {
        /** How long a LoRa-arrived DM's ✓✓ waits for company — the same figure as [AckSync.TICK_BATCH_DEBOUNCE_MS]. */
        const val HOLD_MS = 45_000L

        /**
         * Most ids one coalesced tick carries: 316 B for the single form plus 23 B an id keeps the frame inside
         * the LoRa hop's real 3-packet ceiling (pinned by `CoordinationPlaneSizeBudgetTest`); a fuller batch
         * flushes early. Well under the receiver's `MAX_RECEIPT_ACKS`.
         */
        const val MAX_LORA_TICK_ACKS = 12

        /** Cap on pending ids across all authors — beyond it the oldest batch flushes early, never drops. */
        const val PENDING_CAP = 500

        /** A pending ack older than this is forgotten: the DM it acks has aged out of custody (ADR 006's 24 h). */
        const val TTL_MS = 24 * 60 * 60_000L
    }
}
