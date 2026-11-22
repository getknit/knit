package app.getknit.knit.mesh.lab

import app.getknit.knit.mesh.CarriedFrame
import app.getknit.knit.mesh.ForwardStore
import app.getknit.knit.mesh.protocol.RelayEnvelope
import kotlinx.coroutines.CompletableDeferred
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * A node's custody writes, holdable: from [hold] on, every `store` of a frame [hold]'s predicate matches waits until
 * [release] — a phone whose database is slow to commit (a busy pool, a core taken away mid-write) while its radio is
 * not. Only the stack's own writes go through it (`LabNode.boot` hands the manager [over]); a scenario's reads of the
 * store do not. Kept across restarts, like the rest of the node's persistent state.
 *
 * **A scenario that holds the gate releases it before it ends.** A frame the router admitted is delivered to the end
 * under `NonCancellable` (ADR 2026-10.f82h), so a delivery parked here outlives the node's stop: `LabNode.restart` and
 * `MeshLab.close` join the session scope, and that join waits on it until its `SCOPE_JOIN_MS` bound runs out. To stop a
 * node mid-delivery, stop the manager, then [release], then restart (`SpoolWorkerStopLabTest`). `MeshLab.close`
 * releases every node's gate first, as a net under a scenario that failed while holding, never as its release.
 *
 * It can also fail the stack's custody id read ([failLiveIds]): the digest a link-up or a re-offer round sends throws
 * with it, so a scenario can make a session loop's round fail (#206). The scenario's own reads (`LabNode.custodyIds`)
 * go to the raw repository, untouched.
 */
class LabCustodyGate {
    @Volatile
    private var matches: ((RelayEnvelope) -> Boolean)? = null

    @Volatile
    private var opened = CompletableDeferred<Unit>()

    private val waiting = AtomicInteger()

    @Volatile
    private var liveIdsFail = false

    /**
     * How many writes are waiting at the gate right now — the event a scenario holds its next step on. A write leaves
     * the count as it leaves the gate, before it is stored: await the send that made it, not this, to know it landed.
     */
    val parked: Int get() = waiting.get()

    /**
     * Every write the stack's custody kept as a new row with a proven neighbour as its source, in the order they
     * returned: the event custody's re-offer back-off reads as an offer that drew (the custody-carry-policy contract's
     * §7), seen at the write, so a row kept and evicted again by a later write still counts. A new row only: the gate
     * asks the store whether it held the id before the write, since `ForwardStore.store` answers true for an id it holds
     * (`ForwardRepository`'s insert ignores the duplicate) and only `ForwardSync.onSeen`'s own `has` check keeps the
     * stack from writing one.
     */
    val keptFrom = CopyOnWriteArrayList<KeptWrite>()

    /**
     * The node's order of events, shared with its transport's `LabTransport.order`: each [KeptWrite] takes its [KeptWrite.seq]
     * here once the store has the row. Kept across restarts with the gate, so it never runs back. Taken after `store`
     * resumes, so a write numbered while the row was being stored reads as before the keep, though the row was there.
     */
    val order = AtomicLong()

    /** One kept write: the frame [id] custody kept as a new row, from the neighbour [source], [seq]th in the node's order. */
    class KeptWrite(
        val source: String,
        val id: String,
        val seq: Long = 0,
    )

    /** From now on, a custody write of a frame [which] answers true for waits for [release]. */
    fun hold(which: (RelayEnvelope) -> Boolean) {
        check(matches == null) { "the gate is already holding: release it first" }
        opened = CompletableDeferred()
        matches = which
    }

    /** From now on until [healLiveIds], the stack's custody id read throws an [IOException]: a database read that failed. */
    fun failLiveIds() {
        liveIdsFail = true
    }

    /** The custody id read answers again. */
    fun healLiveIds() {
        liveIdsFail = false
    }

    /** Lets every waiting write through and stops holding. */
    fun release() {
        matches = null
        opened.complete(Unit)
    }

    /** [store] with its writes going through this gate. */
    fun over(store: ForwardStore): ForwardStore =
        object : ForwardStore by store {
            override suspend fun store(
                frame: CarriedFrame,
                origin: Int,
                now: Long,
                source: String?,
            ): Boolean {
                // The predicate before the latch: [hold] sets them in the other order, so a match reads its own latch.
                if (matches?.invoke(frame.envelope) == true) {
                    waiting.incrementAndGet()
                    try {
                        opened.await()
                    } finally {
                        waiting.decrementAndGet()
                    }
                }
                val had = source != null && store.has(frame.envelope.id)
                return store.store(frame, origin, now, source).also { kept ->
                    if (kept && source != null && !had) keptFrom += KeptWrite(source, frame.envelope.id, order.incrementAndGet())
                }
            }

            override suspend fun liveIds(now: Long): List<String> {
                if (liveIdsFail) throw IOException("lab: the custody id read failed")
                return store.liveIds(now)
            }
        }
}
