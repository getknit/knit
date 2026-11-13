package app.getknit.knit.mesh.lora.meshcore

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicInteger

/**
 * One call into the session's actor and its one answer ([reply]), whichever of three things settles it first: the actor
 * starting it ([run]), its caller's deadline passing before that ([claimForTimeout]), or the actor refusing it unserved
 * ([refuse]: the inbox closed under it, or the op ended without an answer).
 *
 * The deadline claims and answers in one step, and [refuse] skips an op the deadline took. Before this, the deadline's
 * claim and its answer were two steps on the caller's coroutine, and the actor's `serve` — finding the op already claimed,
 * so never running it — answered it `notReady` in its `finally` between them: the caller then read `NotReady` for a
 * send it had timed out (the Stage 5 carry-forward must-fix).
 *
 * [C] is the connection the op runs on, generic so the state machine is tested without one.
 */
internal class SessionOp<C, T>(
    private val op: suspend (C) -> T,
    private val notReady: () -> T,
) {
    /** The op's one answer. */
    val reply = CompletableDeferred<T>()

    private val state = AtomicInteger(OPEN)

    /** Runs the op on the actor, unless its deadline already took it: a late op never starts. */
    suspend fun run(c: C) {
        if (!state.compareAndSet(OPEN, STARTED)) return
        reply.complete(op(c))
    }

    /**
     * The deadline passed: if the op has neither started nor been refused, it never will be, and its answer is [timedOut].
     * True when it won.
     */
    fun claimForTimeout(timedOut: () -> T): Boolean {
        if (!state.compareAndSet(OPEN, TIMED_OUT)) return false
        reply.complete(timedOut())
        return true
    }

    /**
     * The actor is done with it unanswered: [notReady], unless something answered already or its deadline took it (that
     * answer is the deadline's to give). Claimed in the same step as the deadline claims, never read and then acted on —
     * a read of "open" followed by an answer lost to a deadline that claimed in between. A started op that threw is
     * answered here.
     */
    fun refuse() {
        if (state.compareAndSet(OPEN, REFUSED) || state.get() == STARTED) reply.complete(notReady())
    }

    private companion object {
        const val OPEN = 0
        const val STARTED = 1
        const val TIMED_OUT = 2
        const val REFUSED = 3
    }
}
