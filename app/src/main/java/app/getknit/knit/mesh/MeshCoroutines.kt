package app.getknit.knit.mesh

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * Last-resort handler for an **uncaught** throw in a top-level mesh coroutine, so a stray exception is
 * logged instead of crashing the process (app scope) or vanishing silently (session scope). It is honored
 * only for a coroutine launched directly on a [kotlinx.coroutines.SupervisorJob] scope — which the mesh's
 * two scopes are: the app-lifetime scope (`di/MeshModule.kt`, which the transports and each `FramedLink`
 * writer run on) and `MeshManager`'s per-session scope (the router/heal/watch collectors).
 *
 * It is a backstop, not the fix for any specific bug — e.g. an oversized frame can no longer reach the
 * writer (KeyExchange chunks its batches and `FramedLink` drops a rejected record), but if some other
 * coroutine ever throws unexpectedly, this keeps the app alive with a diagnosable log line. The coroutine
 * that threw is still gone, so a periodic loop runs its rounds through [everyRound] rather than lean on it.
 */
val meshExceptionHandler =
    CoroutineExceptionHandler { _, t ->
        Log.w("MeshScope", "uncaught exception in a mesh coroutine", t)
    }

/**
 * Launches a loop that runs [round] every [intervalMs], the first one interval after launch, until this scope is
 * cancelled. A round that throws is handed to [onFailure] and the next one runs on time: one bad round costs that
 * round, never the loop for the rest of the session (#206). A [CancellationException] the round lets out while the
 * loop is still active (a leaked timeout, a cancelled child's await) is a failed round like any other; only the
 * loop's own cancellation ends it. An [Error] is not caught and reaches [meshExceptionHandler].
 */
internal fun CoroutineScope.everyRound(
    intervalMs: Long,
    onFailure: (Exception) -> Unit,
    round: suspend () -> Unit,
): Job =
    launch {
        while (true) {
            delay(intervalMs)
            runRound(onFailure) { round() }
        }
    }

/**
 * Runs one [round], handing a throw to [onFailure] instead of letting it end the caller: [everyRound]'s round, and a
 * pass of a long-lived collector (`MeshManager.watchNeighbors`) whose next emission must still be acted on (#206). A
 * [CancellationException] while the caller is still active is a failure like any other; the caller's own cancellation
 * is rethrown. An [Error] is not caught.
 */
internal suspend inline fun runRound(
    onFailure: (Exception) -> Unit,
    round: () -> Unit,
) {
    try {
        round()
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
        onFailure(e)
    } catch (
        @Suppress("TooGenericExceptionCaught") e: Exception, // any failure costs this round alone
    ) {
        onFailure(e)
    }
}

/**
 * [runCatching] for a guard that must never throw out of the inbound path (`InboundPipeline.onDeliver`'s dispatch
 * backstop, `verifyInbound`, the decrypt arms) and must never swallow its caller's own cancellation either. The rule
 * is [runRound]'s: a [CancellationException] while the caller is still active (an inner `withTimeout`, a cancelled
 * child's await) is a failure like any other and comes back as one; the caller's own cancellation is rethrown, so it
 * is never counted as a drop and never reaches the reset heuristic (#141 finding 11, ADR 2026-10.f82h). No caller
 * sees the second kind today: a delivery the router admitted runs under `NonCancellable`, and so does each frame of
 * `MeshManager`'s custody replays. It is the rule for any caller added later.
 *
 * Catches every [Throwable] the rest of the way, exactly as the [runCatching] it replaces did.
 */
internal suspend inline fun <T> runCatchingUnlessCancelled(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
        Result.failure(e)
    } catch (
        @Suppress("TooGenericExceptionCaught") e: Throwable, // the drop it guards counts any failure
    ) {
        Result.failure(e)
    }
