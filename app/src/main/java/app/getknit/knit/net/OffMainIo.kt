package app.getknit.knit.net

import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Keeps a direct link's socket work off the main thread. A session's close sends its BYE on whatever thread closes it,
 * and the mesh stops on the main thread (the diagnostics screen's Restart, a pause, the service's end): Android's
 * StrictMode answers a datagram sent there with a `NetworkOnMainThreadException`, which killed the app whenever a link
 * was up (a field report on 2.12.1 and 2.13.0-alpha.1).
 *
 * Work asked for on the main thread ([onMain]) is queued on [executor] instead, one task at a time in the order asked,
 * so a BYE queued by a close still goes out before the socket it rides is closed. Work asked for anywhere else runs
 * where it was asked, as it always did.
 */
internal class OffMainIo(
    private val onMain: () -> Boolean,
    private val executor: Executor,
) {
    /** Runs [send] here and returns its answer; on the main thread queues it and returns true (it was handed on). */
    fun send(send: () -> Boolean): Boolean {
        if (!onMain()) return send()
        // Guarded like the close: the thread is every port's, and the port promises never to throw.
        executor.execute { runCatching(send) }
        return true
    }

    /** Runs [close] here, or on the main thread queues it behind every send queued before it. */
    fun close(close: () -> Unit) {
        if (onMain()) executor.execute { runCatching(close) } else close()
    }

    companion object {
        /**
         * One daemon thread for every port: serial, so each port's queued work keeps its order. It only ever carries a
         * close's BYE and the close itself, a datagram each, so one thread is plenty.
         */
        val shared: Executor by lazy {
            Executors.newSingleThreadExecutor { task -> Thread(task, "knit-link-offmain").apply { isDaemon = true } }
        }
    }
}
