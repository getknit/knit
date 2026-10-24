package app.getknit.knit.mesh.bluetooth

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest

/**
 * The advert's [BleAdvertPayload.FLAG_NO_AWARE] debounce (GitHub #44), asymmetric on purpose: the bit goes up the
 * moment this phone will not run Wi-Fi Aware, and comes down only once it has held clear for [CLEAR_AFTER_MS]. A clear
 * bit is what lets a nearby phone's dormant Aware plane wake for this one, and the "won't run" evidence flickers: a Doze
 * maintenance window lets the radio back for a few seconds, every few minutes. A late clear only delays a peer's wake;
 * a late set would wake it for nothing. The minute is wall time: the transport passes `ElapsedWait.sleep`, so a mesh
 * started or left on a dark phone clears within a minute of its own, never a minute of awake CPU (which can be many
 * minutes of sleep), and the wait never wakes the phone. Pure but for the [sleep] it is handed, so a test drives it on
 * virtual time ([app.getknit.knit.NoAwareFlagTest]).
 */
internal object NoAwareFlag {
    /** How long "will run Aware" must hold before the bit comes down. */
    const val CLEAR_AFTER_MS = 60_000L

    /**
     * Follows [wontRun] until cancelled: [apply] true at once on a rising edge, [apply] false once the value has held
     * false for [clearAfterMs], waited by [sleep] (cancelled by a new value). [advertised] is what the advert carries
     * now, so an unchanged value applies nothing.
     */
    suspend fun follow(
        wontRun: Flow<Boolean>,
        advertised: () -> Boolean,
        apply: (Boolean) -> Unit,
        sleep: suspend (Long) -> Unit,
        clearAfterMs: Long = CLEAR_AFTER_MS,
    ) {
        wontRun.collectLatest { now ->
            if (now) {
                if (!advertised()) apply(true)
            } else if (advertised()) {
                sleep(clearAfterMs)
                apply(false)
            }
        }
    }
}
