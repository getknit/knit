package app.getknit.knit.mesh.bluetooth

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest

/**
 * The advert's [BleAdvertPayload.FLAG_AWARE] debounce (GitHub #44, ADR 2026-10.2t49), asymmetric on purpose: the bit
 * comes down the moment this phone will not run Wi-Fi Aware, and goes up only once "will run" has held for
 * [SET_AFTER_MS]. A set bit is what lets a nearby phone's dormant Aware plane wake for this one, and the "won't run"
 * evidence flickers: a Doze maintenance window lets the radio back for a few seconds, every few minutes. A late set
 * only delays a peer's wake; an early one would wake it for nothing. The minute is wall time: the transport passes
 * `ElapsedWait.sleep`, so a mesh started or left on a dark phone sets it within a minute of its own, never a minute of
 * awake CPU (which can be many minutes of sleep), and the wait never wakes the phone. Pure but for the [sleep] it is
 * handed, so a test drives it on virtual time ([app.getknit.knit.AwareFlagTest]).
 */
internal object AwareFlag {
    /** How long "will run Aware" must hold before the bit goes up. */
    const val SET_AFTER_MS = 60_000L

    /**
     * Follows [wontRun] until cancelled: [apply] false at once on a rising edge, [apply] true once the value has held
     * false for [setAfterMs], waited by [sleep] (cancelled by a new value). [advertised] is whether the advert carries
     * [BleAdvertPayload.FLAG_AWARE] now, so an unchanged value applies nothing.
     */
    suspend fun follow(
        wontRun: Flow<Boolean>,
        advertised: () -> Boolean,
        apply: (Boolean) -> Unit,
        sleep: suspend (Long) -> Unit,
        setAfterMs: Long = SET_AFTER_MS,
    ) {
        wontRun.collectLatest { now ->
            if (now) {
                if (advertised()) apply(false)
            } else if (!advertised()) {
                sleep(setAfterMs)
                apply(true)
            }
        }
    }
}
