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
 * ledger to keep here. Only the debounce shape is shared, and it is mirrored here in style rather than reused. A
 * DM's signed ✓✓ is bounded per era by [DmReAckLedger] on either side of this hold: the flush seals only the ids that
 * ledger does not find answered, keeps a compact tick for every id, and records what it sealed, so one DM draws one
 * signed receipt whichever of its copies lands first (#300, ADR 2026-10.8hn6's 2026-11-06 amendment).
 *
 * In-memory only, like AckSync's ledger: a process death inside the hold loses the pending acks. Only a
 * re-delivery of the DM re-holds and re-ticks it, and since ADR 018 that is no longer a matter of course: we
 * custody our own inbound DMs, so the digests agree and no carrier re-serves one we hold. It takes real
 * divergence (our copy evicted, a wipe) or a plane that re-offers without a digest (LoRa). A cancelled caller no
 * longer cuts the hold short (ADR 2026-10.f82h); a process death still can (#56).
 *
 * A second, **ride-only** hold ([holdRiding]) keeps the ids whose compact ✓✓ the board could not deliver — held for an
 * unreachable board, refused for air, or flown with no answer (ADR 2026-10.x9fa's 2026-11-19 amendment). Those never
 * flush on a timer: against an unreachable board that would offer a tick the plane drops every [holdMs]. They ride the
 * next DM the user writes to that author ([takeRiding], inline, the cheapest path there is), or go as one compact tick
 * when that author's board next acks one of our DMs ([releaseRiding]: the link is up, perhaps only briefly). Bounded
 * per author at [maxBatch] and aged out at [rideTtlMs]: past that the author's own re-probe re-delivers their DM, and
 * its re-ack is held afresh.
 *
 * Pure — [now] and [flushScope] are injected; the JVM tests drive it on a virtual clock.
 */
class DmAckCoalescer(
    private val now: () -> Long = { System.currentTimeMillis() },
    /** Answers [ackIds] toward the author: ONE `CTL_RECEIPT` over the ids still unanswered, originated; owns its own fallback. */
    private val flush: suspend (authorId: String, ackIds: List<String>) -> Unit,
    /** Where the hold timer runs; null (the default) leaves [flushDue] to a caller's heartbeat. */
    private val flushScope: () -> CoroutineScope? = { null },
    private val holdMs: Long = HOLD_MS,
    private val maxBatch: Int = MAX_LORA_TICK_ACKS,
    private val pendingCap: Int = PENDING_CAP,
    private val ttlMs: Long = TTL_MS,
    /** How long a ride-only id waits for a ride: the window a compact copy is taken in (`LORA_DIRECT_SEND_WINDOW_MS`). */
    private val rideTtlMs: Long = LORA_DIRECT_SEND_WINDOW_MS,
    /** Cap on ride-only ids across all authors — beyond it the largest author's oldest go (security-reviewer). */
    private val ridingCap: Int = PENDING_CAP,
) {
    /** Acks accumulating toward one author: frameId → recordedAt in arrival order; guarded by `synchronized(this)`. */
    private class Batch {
        val ids = LinkedHashMap<String, Long>()
        var wakeArmed = false
    }

    private val pending = ConcurrentHashMap<String, Batch>()

    /** Ride-only ids toward one author: frameId → when it went ride-only, oldest first; guarded by `synchronized(this)`. */
    private class Riders {
        val ids = LinkedHashMap<String, Long>()
    }

    private val riding = ConcurrentHashMap<String, Riders>()

    /**
     * When each ride-only id that left the hold (taken to ride a DM, or released on a tick) first went ride-only, so one
     * that comes back — given back unridden, or reported undelivered again — keeps its age rather than restarting it: no
     * ride that keeps failing, nor a late or forged ack that keeps releasing (security-reviewer), holds an id past
     * [rideTtlMs]. Bounded at [ridingCap] and aged out like the hold; an id that landed is simply never held again.
     */
    private val firstHeld = ConcurrentHashMap<String, Long>()

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
        // A re-delivery re-holds the id for a fresh tick: it no longer waits for a ride, or it would be answered twice.
        riding[authorId]?.let { riders -> synchronized(riders) { riders.ids.remove(frameId) } }
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

    /**
     * Flushes every batch whose oldest id has waited out the hold, after ageing out anything past [ttlMs] (and
     * ride-only ids past [rideTtlMs], which never flush here).
     */
    suspend fun flushDue() {
        sweep()
        sweepRiding()
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

    // --- the ride-only hold (ADR 2026-10.x9fa's 2026-11-19 amendment) ---

    /**
     * Keeps [ids], ✓✓ owed to [authorId] whose compact copy the board did not deliver, for a ride: the next DM written to
     * that author ([takeRiding]) or that author's next board ack ([releaseRiding]), never a timer. An id the timed hold
     * still has is left to it, which will tick it anyway. Past [maxBatch] the oldest goes. Never suspends and does no
     * I/O, so the plane's coroutines may call it (via `MeshManager.onLoraDirectUndelivered`). A concurrent
     * [releaseRiding] may detach the map between the lookup and the lock, and an id landed in a detached one would be
     * lost, so it retries into a fresh one ([hold]'s guard). Past [ridingCap] across all authors, the largest author's
     * oldest go: one author minting ids cannot grow the hold, nor push out everyone else's.
     */
    fun holdRiding(
        authorId: String,
        ids: Collection<String>,
        since: Long? = null,
    ) {
        // An id with no first age kept starts from [since] (the tick's own offer), never from now: a memo swept or evicted
        // meanwhile must not buy it another whole window (security-reviewer).
        val at = since ?: now()
        holdRidingAt(authorId, ids.map { it to (firstHeld.remove(it) ?: at) })
    }

    private fun holdRidingAt(
        authorId: String,
        ids: List<Pair<String, Long>>,
    ) {
        if (ids.isEmpty()) return
        val timed = pending[authorId]?.let { batch -> synchronized(batch) { batch.ids.keys.toSet() } }.orEmpty()
        val fresh = ids.filter { (id, _) -> id !in timed }
        if (fresh.isEmpty()) return
        while (true) {
            val riders = riding.computeIfAbsent(authorId) { Riders() }
            val landed =
                synchronized(riders) {
                    if (riding[authorId] !== riders) return@synchronized false
                    fresh.forEach { (id, at) -> riders.ids.putIfAbsent(id, at) }
                    while (riders.ids.size > maxBatch) riders.ids.remove(riders.ids.keys.first())
                    true
                }
            if (landed) break
        }
        trimRiding()
    }

    /** Evicts the largest author's oldest ride-only ids until the hold is back under [ridingCap]; no two monitors held. */
    private fun trimRiding() {
        while (riding.values.sumOf { synchronized(it) { it.ids.size } } > ridingCap) {
            val (authorId, riders) =
                riding.entries.maxByOrNull { (_, r) -> synchronized(r) { r.ids.size } }?.toPair() ?: return
            val evicted =
                synchronized(riders) {
                    val oldest = riders.ids.keys.firstOrNull() ?: return@synchronized false
                    riders.ids.remove(oldest)
                    if (riders.ids.isEmpty()) riding.remove(authorId, riders)
                    true
                }
            if (!evicted) riding.remove(authorId, riders)
        }
    }

    /** How many ride-only ids wait for [authorId], after ageing out what is past [rideTtlMs]. */
    fun ridingFor(authorId: String): Int {
        val riders = riding[authorId] ?: return 0
        return synchronized(riders) {
            agedOut(riders)
            riders.ids.size
        }
    }

    /**
     * Removes up to [max] of the oldest ride-only ids owed to [authorId], for the caller to carry inline on a DM it is
     * sending to them. What it could not carry after all goes back with [giveBackRiding], never [giveBack]: the timed
     * hold would tick it on its own.
     */
    fun takeRiding(
        authorId: String,
        max: Int,
    ): List<String> {
        if (max <= 0) return emptyList()
        val riders = riding[authorId] ?: return emptyList()
        val taken =
            synchronized(riders) {
                agedOut(riders)
                val entries = riders.ids.entries
                val oldest = entries.take(max).map { (id, at) -> id to at }
                oldest.forEach { (id, _) -> riders.ids.remove(id) }
                if (riders.ids.isEmpty()) riding.remove(authorId, riders)
                oldest
            }
        remember(taken)
        return taken.map { it.first }
    }

    /** Keeps [left]'s first-held ages for [firstHeld], bounded at [ridingCap], oldest dropped. */
    private fun remember(left: List<Pair<String, Long>>) {
        left.forEach { (id, at) -> firstHeld[id] = at }
        while (firstHeld.size > ridingCap) {
            firstHeld.entries.minByOrNull { it.value }?.let { firstHeld.remove(it.key, it.value) } ?: break
        }
    }

    /**
     * [ids] owed to [authorId] are known to have landed — the destination's board acked the compact tick that carried them,
     * on time or late: they leave the ride-only hold for good, and are never released again on their own tick's ack
     * (security-reviewer: a late ack of a released tick would otherwise release its own ids anew, without end).
     */
    fun dropRiding(
        authorId: String,
        ids: Collection<String>,
    ) {
        if (ids.isEmpty()) return
        ids.forEach { firstHeld.remove(it) }
        val riders = riding[authorId] ?: return
        synchronized(riders) {
            riders.ids.keys.removeAll(ids.toSet())
            if (riders.ids.isEmpty()) riding.remove(authorId, riders)
        }
    }

    /** Restores ride-only ids a [takeRiding] could not carry; they wait for the next ride, keeping the age they had. */
    fun giveBackRiding(
        authorId: String,
        ids: List<String>,
    ) {
        val at = now()
        holdRidingAt(authorId, ids.map { it to (firstHeld.remove(it) ?: at) })
    }

    /**
     * [authorId]'s board just acked one of our DMs: what waited for a ride goes now, with whatever the timed hold has for
     * them, as one flush (their compact ticks; the ledger keeps a second signed receipt from being sealed). Nothing waits
     * for a ride: nothing happens, and the timed hold keeps its own time. Returns how many ride-only ids went.
     */
    suspend fun releaseRiding(authorId: String): Int {
        val riders = riding.remove(authorId) ?: return 0
        val held =
            synchronized(riders) {
                agedOut(riders)
                riders.ids.entries.map { (id, at) -> id to at }
            }
        if (held.isEmpty()) return 0
        remember(held)
        val ids = held.map { it.first }
        val timed = pending.remove(authorId)?.let { batch -> synchronized(batch) { batch.ids.keys.toList() } }.orEmpty()
        flush(authorId, (ids + timed).distinct())
        return ids.size
    }

    /** The ride-only ids owed to [authorId], oldest first (diagnostics and tests). */
    fun riding(authorId: String): List<String> {
        val riders = riding[authorId] ?: return emptyList()
        return synchronized(riders) { riders.ids.keys.toList() }
    }

    /** Drops what in [riders] is past [rideTtlMs]; called holding its monitor. */
    private fun agedOut(riders: Riders) {
        val cutoff = now() - rideTtlMs
        riders.ids.entries.removeAll { it.value < cutoff }
    }

    private fun sweepRiding() {
        val cutoff = now() - rideTtlMs
        firstHeld.entries.removeAll { it.value < cutoff }
        riding.entries.removeAll { (_, riders) ->
            synchronized(riders) {
                agedOut(riders)
                riders.ids.isEmpty()
            }
        }
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
         * Most ids one coalesced batch holds before it flushes early, and the cap on the signed tick it originates, room
         * riders included. The signed tick no longer airs (ADR 2026-10.x9fa): its compact copies carry the batch's DM ids
         * alone, chunked at `LORA_DIRECT_TICK_ACKS` (nine) a packet, so a full batch costs two compact packets. The figure
         * dates from when the signed tick itself rode a three-packet LoRa hop. Well under the receiver's `MAX_RECEIPT_ACKS`.
         */
        const val MAX_LORA_TICK_ACKS = 12

        /** Cap on pending ids across all authors — beyond it the oldest batch flushes early, never drops. */
        const val PENDING_CAP = 500

        /** A pending ack older than this is forgotten: the DM it acks has aged out of custody (ADR 006's 24 h). */
        const val TTL_MS = 24 * 60 * 60_000L
    }
}
