package app.getknit.knit.mesh.power

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select

/**
 * Waits measured on the elapsed clock, which keeps counting while the CPU is suspended (ADR 2026-10.pj9w).
 *
 * A coroutine `delay` runs on the monotonic clock, which stops in suspend, so on a phone that sleeps between events a
 * 12 s wait lasts until the CPU has been awake for 12 s: the Pixel 3 ran the Bluetooth transport's 60 s cadence at a
 * median 110 s and up to 159 s. These waits read [now] (the caller's `elapsedRealtime`) at least every [checkMs] of
 * awake time, so a wait that came due while the phone slept ends within [checkMs] of the CPU next running. They never
 * wake a suspended phone themselves: that would take an alarm, and an alarm costs more than the lateness it saves.
 *
 * Pure but for `delay`, so a test drives it with virtual time and a clock that jumps ([ElapsedWaitTest]).
 */
class ElapsedWait(
    private val now: () -> Long,
    private val checkMs: Long = CHECK_MS,
) {
    /** Suspends until [ms] have passed on the elapsed clock. */
    suspend fun sleep(ms: Long) {
        val deadline = now() + ms
        while (true) {
            val left = deadline - now()
            if (left <= 0) return
            delay(minOf(left, checkMs))
        }
    }

    /**
     * The next element of [channel], or null once [ms] have passed on the elapsed clock. A step that times out never
     * takes an element — each step is a `select`, so a wake sent as a step ends stays in the channel for the next one.
     */
    @OptIn(ExperimentalCoroutinesApi::class) // onTimeout: the select clause has been stable in behaviour since 1.7
    suspend fun <T : Any> receiveWithin(
        channel: ReceiveChannel<T>,
        ms: Long,
    ): T? {
        val deadline = now() + ms
        while (true) {
            val left = deadline - now()
            if (left <= 0) return channel.tryReceive().getOrNull()
            val got =
                select {
                    channel.onReceive { it }
                    onTimeout(minOf(left, checkMs)) { null }
                }
            if (got != null) return got
        }
    }

    companion object {
        /**
         * How much awake time may pass between looks at the clock. Short enough that a dial watchdog (12 s) or a
         * dwell wake lands within two seconds of the CPU running, long enough that a minute's wait costs thirty
         * comparisons, not a busy loop. A look does no other work.
         */
        const val CHECK_MS = 2_000L
    }
}
