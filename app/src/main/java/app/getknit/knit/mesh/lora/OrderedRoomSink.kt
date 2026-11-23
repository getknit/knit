package app.getknit.knit.mesh.lora

import app.getknit.knit.mesh.runRound
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Hands a plane's heard room posts to the room one at a time, **in the order the board handed them over** (ADR
 * 2026-11.gsqu's Stage 6 amendment, part 6c): both bridged rooms, the Meshtastic one's replay at connect and the MeshCore
 * one's offline-queue drain. It replaces a launch per post, which let two posts of one drain reach the room, and be
 * stamped there, in either order.
 *
 * The two rules the launch kept still hold:
 * - **[offer] never suspends**, so the core's packet collector never waits on the room (its single-owner rule, and the
 *   `onRadioReached` rule that nothing it calls comes back into the plane).
 * - **A post handed over is delivered** whatever the session does next: the drain runs on [scope], the plane's, never
 *   the session's.
 *
 * One drain at a time, started by the [offer] that finds none running and ending when the queue is empty, so an idle
 * plane holds no coroutine. A delivery that throws is reported ([onFailed]) and the next one goes on.
 *
 * **Bounded at [capacity]**, a refusal counted and logged by the caller ([onDropped]), never silent. The launches it
 * replaces were unbounded: one coroutine per post, however fast they came. A bound only bites when posts arrive faster
 * than the room stores them for [capacity] posts on end. The largest honest burst is a board's queue handed over at
 * connect (MeshCore's offline queue holds 16 by the source default and 256 on the measured board, Q6; a Meshtastic
 * board's to-phone queue is smaller), drained one round trip at a time while the room stores each in milliseconds; on
 * the air a hostile radio is held to the preset's airtime. So [CAPACITY] is past any drain and a burst that reaches it
 * is a stalled room or a forged flood, where shedding the newest is the safe answer. Pure: no `android.*`.
 */
internal class OrderedRoomSink<T : Any>(
    private val scope: CoroutineScope,
    private val onDropped: () -> Unit,
    private val onFailed: (Exception) -> Unit,
    private val capacity: Int = CAPACITY,
    private val deliver: suspend (T) -> Unit,
) {
    private val queue = ConcurrentLinkedQueue<T>()
    private val size = AtomicInteger()
    private val draining = AtomicBoolean()

    /** Queues [item] behind every earlier one, or refuses it when [capacity] are waiting. Never suspends. */
    fun offer(item: T): Boolean {
        if (size.incrementAndGet() > capacity) {
            size.decrementAndGet()
            onDropped()
            return false
        }
        queue.add(item)
        if (draining.compareAndSet(false, true)) scope.launch { drain() }
        return true
    }

    /**
     * Takes the queue to empty, one post at a time. A post's [Exception] costs that post ([runRound]). Anything else that
     * ends the drain — an [Error] out of the room, a cancellation of this drain alone — hands the queue on before it goes
     * on up: the flag is reset and, with posts still waiting, a new drain is started through the same hand-off as [offer]'s,
     * so the room loses the post in hand and never goes deaf (lora-reviewer's 6c note). The throw is not swallowed: an
     * `Error` still reaches the scope's handler, and a cancelled plane's scope starts nothing new.
     */
    private suspend fun drain() {
        try {
            while (true) {
                val item = queue.poll()
                if (item == null) {
                    draining.set(false)
                    // An offer between the empty poll and the reset found a drain running and started none: take it up
                    // here, unless that offer's own drain already did (only one drain ever polls).
                    if (queue.isEmpty() || !draining.compareAndSet(false, true)) return
                    continue
                }
                size.decrementAndGet()
                // One post's failure costs that post alone; the plane's own cancellation still ends the drain.
                runRound(onFailed) { deliver(item) }
            }
        } catch (
            @Suppress("TooGenericExceptionCaught") t: Throwable, // handed on and rethrown: never swallowed
        ) {
            draining.set(false)
            if (!queue.isEmpty() && draining.compareAndSet(false, true)) scope.launch { drain() }
            throw t
        }
    }

    companion object {
        /** Posts waiting for the room before the newest are refused: past any board's queue at connect (see the class). */
        const val CAPACITY = 512
    }
}
