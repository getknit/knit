package app.getknit.knit.mesh.power

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Snapshot of the device power conditions that drive the mesh discovery duty cycle. Defaults are
 * deliberately optimistic ([interactive] = true) so the first discovery iteration runs aggressively
 * until [PowerMonitor] seeds the real state.
 */
data class PowerState(
    val interactive: Boolean = true,
    val charging: Boolean = false,
    val batteryLow: Boolean = false,
)

/**
 * How the discovery loop should behave for a given [PowerState]: scan for [scanWindowMs], then idle
 * for [baseIntervalMs] before the transport applies its neighbor-count backoff.
 */
data class DutyCycle(
    val scanWindowMs: Long,
    val baseIntervalMs: Long,
)

/**
 * Pure mapping from [PowerState] to the discovery [DutyCycle]. Advertising is left always-on by the
 * transport (moderate posture: peers can still relay through a backgrounded device); only the
 * discovery cadence changes here. Being interactive or charging scans aggressively (charging
 * overrides battery concerns); a screen-off device on battery backs off, more so when battery is low.
 */
object PowerPolicy {

    fun dutyCycle(state: PowerState): DutyCycle = when {
        state.interactive || state.charging -> DutyCycle(ACTIVE_WINDOW_MS, ACTIVE_INTERVAL_MS)
        state.batteryLow -> DutyCycle(IDLE_WINDOW_MS, LOW_BATTERY_INTERVAL_MS)
        else -> DutyCycle(IDLE_WINDOW_MS, IDLE_INTERVAL_MS)
    }

    /**
     * How long to idle after a scan window before the next discovery burst, given the current
     * [neighborCount] and how long the node has been isolated ([lonelyForMs], 0 when not isolated).
     *
     * With neighbors, the connected mesh duty-cycles and backs off as it grows (the original
     * `baseIntervalMs * (1 + neighborCount)`). An **isolated** node instead prioritizes rejoining and
     * scans on a short [LONELY_IDLE_MS] gap; while interactive or charging it does so for as
     * long as it stays alone, but a screen-off-on-battery node caps that aggressive phase to
     * [LONELY_AGGRESSIVE_WINDOW_MS] and then relaxes to the power-policy idle to bound drain when no
     * peers are around at all.
     */
    fun idleAfterScan(state: PowerState, neighborCount: Int, lonelyForMs: Long): Long {
        if (neighborCount > 0) return dutyCycle(state).baseIntervalMs * (1 + neighborCount)
        val aggressive = state.interactive || state.charging || lonelyForMs < LONELY_AGGRESSIVE_WINDOW_MS
        return if (aggressive) LONELY_IDLE_MS else dutyCycle(state).baseIntervalMs
    }

    // Screen on or charging: the original aggressive cadence.
    private const val ACTIVE_WINDOW_MS = 12_000L
    private const val ACTIVE_INTERVAL_MS = 30_000L

    // Screen off on battery: the main saver — shorter scans, much longer gaps.
    private const val IDLE_WINDOW_MS = 8_000L
    private const val IDLE_INTERVAL_MS = 120_000L

    // Screen off and battery low: most relaxed.
    private const val LOW_BATTERY_INTERVAL_MS = 300_000L

    // Isolated (zero-neighbor) reconnect cadence: scan often so a node that moved back into range
    // rejoins quickly instead of waiting out a 2–5 min duty-cycle gap — but NOT back-to-back. BLE
    // scanning and a GATT/L2CAP connection handshake share the one radio; near-continuous scanning
    // (the original 5s) starves the very handshake that ends the isolation, so an isolated node could
    // discover a peer yet never connect to it (STATUS_RADIO_ERROR / ESTABLISH_GATT_CONNECTION_FAILED).
    // 12s leaves a radio-quiet gap after each ~12s scan for an inbound handshake to complete; the
    // transport additionally pauses scanning outright while its own connect is in flight. See "BLE
    // radio contention" in AGENTS.md.
    private const val LONELY_IDLE_MS = 12_000L

    // On battery with the screen off, only stay in the aggressive isolated cadence this long before
    // relaxing — bounds drain for a node that is simply alone (e.g. left in a drawer).
    private const val LONELY_AGGRESSIVE_WINDOW_MS = 3 * 60_000L
}

/**
 * Holds the current [PowerState] as an observable [StateFlow]. [PowerMonitor] writes it from system
 * broadcasts; the transport reads it to drive the discovery loop. Kept Android-free so it stays
 * unit-testable and outside the GMS layer.
 */
class PowerStateSource {
    private val _state = MutableStateFlow(PowerState())
    val state: StateFlow<PowerState> = _state.asStateFlow()

    fun update(transform: (PowerState) -> PowerState) {
        _state.update(transform)
    }
}
