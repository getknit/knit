package app.getknit.knit.mesh.power

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.transformLatest

/**
 * Whether low-power mode is offered at all. False since GitHub #44 (decided 2026-10-24): Settings › Wi-Fi Aware is the
 * person's switch for the plane, and an idle Wi-Fi Aware plane detaches by itself (`NanDormancyPolicy`), so the mode's
 * one Aware lever is not offered on its own. While false the Settings row is hidden and [LowPowerSource.posture] is off
 * whatever the switch's key, Battery Saver or the charger say; the code stays, and a phone that had the mode on moves
 * to the Wi-Fi Aware switch once (`AwareSwitch`).
 */
const val LOW_POWER_OFFERED = false

/** What holds low-power mode on: the user's switch, or Android's Battery Saver (ADR 2026-10.d44b). */
enum class LowPowerReason { SWITCH, BATTERY_SAVER }

/**
 * The mesh's low-power posture: an **overlay** the planes read, never a write to the user's own settings, so it lifts
 * leaving the phone exactly as it was. [reason] is null when the mode is off. Phase 1 (ADR 2026-10.d44b, its
 * amendments) has two levers, the two the battery trials showed paying or costing nothing to give up: Wi-Fi Aware
 * off ([awareOff], applied by `SwitchableTransport`) and no motion heal ([motionHeal], `MeshService`). The phone
 * still relays fully over Bluetooth.
 */
data class LowPowerPosture(
    val reason: LowPowerReason?,
) {
    val active: Boolean get() = reason != null

    /** Wi-Fi Aware is switched off: 20–42 mA of a still phone's current in the Phase 0 trials. */
    val awareOff: Boolean get() = active

    /** The significant-motion sensor stays armed and a move heals both radios. The heartbeat and resume heals stay. */
    val motionHeal: Boolean get() = !active

    companion object {
        val OFF = LowPowerPosture(reason = null)
    }
}

/**
 * The pure rule: the switch, or Battery Saver, on battery. Charging lifts it while the switch stays on: the wall pays
 * (ADR 2026-09.kb68).
 */
object LowPowerPolicy {
    fun posture(
        switchOn: Boolean,
        batterySaver: Boolean,
        charging: Boolean,
        // [LOW_POWER_OFFERED]: not offered, the mode is off whatever its inputs say.
        offered: Boolean = LOW_POWER_OFFERED,
    ): LowPowerPosture =
        when {
            !offered -> LowPowerPosture.OFF
            charging -> LowPowerPosture.OFF
            switchOn -> LowPowerPosture(LowPowerReason.SWITCH)
            batterySaver -> LowPowerPosture(LowPowerReason.BATTERY_SAVER)
            else -> LowPowerPosture.OFF
        }

    /**
     * How long a lift that happens on the charger waits before it applies: the charger's own edge, and Battery Saver
     * switching itself off on a plug-in (AOSP turns it off at once and a sticky one back on at the unplug). Lifting
     * brings Wi-Fi Aware back with a fresh attach, and a charger that flaps (a loose cable, a wireless pad) must not
     * cycle the plane every few seconds: an attach the chipset refuses strands binder objects and is spent from the
     * plane's leak budget (ADR 052, ADR 055). Unplugging, and the user's own switch, apply at once.
     */
    const val CHARGE_HOLD_MS = 60_000L

    /**
     * How long any other lift the user did not make waits before it applies. At an unplug the charger's broadcast can
     * land a moment before a sticky Battery Saver comes back on, and for that moment the inputs read "off".
     */
    const val LIFT_SETTLE_MS = 5_000L
}

/**
 * Whether Android's Battery Saver is on (`PowerManager.isPowerSaveMode`), written by [PowerMonitor] while the mesh
 * runs. Kept beside the posture, not in [PowerState]: no radio's cadence reads it, only [LowPowerSource]. Built with
 * the platform's answer ([initial]), never a guess: a posture read before [PowerMonitor]'s seed would otherwise say
 * off on a Battery Saver phone and let Wi-Fi Aware attach at launch, only to stop it a moment later.
 */
class BatterySaverState(
    initial: Boolean = false,
) {
    private val _on = MutableStateFlow(initial)
    val on: StateFlow<Boolean> = _on.asStateFlow()

    fun set(on: Boolean) {
        _on.value = on
    }
}

/**
 * The one reading of low-power mode every plane and surface shares: [switchOn] (`LowPowerSwitch.on`),
 * [batterySaver] and the charger turned into a [LowPowerPosture] by [LowPowerPolicy]. [posture] emits nothing until
 * the switch's first read, so `SwitchableTransport` waits for it and a phone in low-power mode never attaches Wi-Fi
 * Aware at launch only to drop it a moment later. A lift the user's own switch did not make is held: for
 * [LowPowerPolicy.CHARGE_HOLD_MS] on the charger, for [LowPowerPolicy.LIFT_SETTLE_MS] off it. The first reading is not
 * a lift and passes at once, and the posture coming on is never held.
 */
class LowPowerSource(
    switchOn: Flow<Boolean>,
    batterySaver: Flow<Boolean>,
    power: Flow<PowerState>,
    scope: CoroutineScope,
    // Injected logger (the class stays Android-free): DI passes android.util.Log; tests default silent.
    private val log: (String) -> Unit = {},
    // [LOW_POWER_OFFERED]; a parameter so the posture's rules stay testable while the mode is not offered.
    private val offered: Boolean = LOW_POWER_OFFERED,
) {
    private data class Inputs(
        val switchOn: Boolean,
        val batterySaver: Boolean,
        val charging: Boolean,
    )

    // Written only inside the one transformLatest below, after its hold: what was last emitted, and the switch then.
    private var emitted: LowPowerPosture? = null
    private var emittedSwitch = false

    @OptIn(ExperimentalCoroutinesApi::class) // transformLatest: a newer reading cancels the hold an older one began
    val posture: SharedFlow<LowPowerPosture> =
        combine(switchOn, batterySaver, power.map { it.charging }) { on, saver, charging -> Inputs(on, saver, charging) }
            .distinctUntilChanged()
            .transformLatest { inputs ->
                val next = LowPowerPolicy.posture(inputs.switchOn, inputs.batterySaver, inputs.charging, offered)
                val lifting = emitted?.active == true && !next.active
                val userLifted = emittedSwitch && !inputs.switchOn
                val hold =
                    when {
                        !lifting || userLifted -> 0L
                        inputs.charging -> LowPowerPolicy.CHARGE_HOLD_MS
                        else -> LowPowerPolicy.LIFT_SETTLE_MS
                    }
                if (hold > 0) delay(hold)
                emitted = next
                emittedSwitch = inputs.switchOn
                emit(next)
            }.distinctUntilChanged()
            .onEach { log("low-power posture: ${it.reason?.name ?: "off"}") }
            .shareIn(scope, SharingStarted.Eagerly, replay = 1)

    /** The posture as last read, or [LowPowerPosture.OFF] before the switch's first read. */
    val current: LowPowerPosture get() = posture.replayCache.firstOrNull() ?: LowPowerPosture.OFF
}

/**
 * The Wi-Fi Aware switch `SwitchableTransport` reads: off while the person's own switch ([userOn], Settings › Wi-Fi
 * Aware, `AwareSwitch`) is off, a debug build's switch ([debugOff], `SettingsStore.debugNanOff`, false in release) is on,
 * or the low-power [posture] says so (never, while [LOW_POWER_OFFERED] is false). It speaks only once all three have
 * been read, which is what holds the plane's first start until the switch's and the posture's first reads.
 */
fun awareSwitchedOff(
    userOn: Flow<Boolean>,
    debugOff: Flow<Boolean>,
    posture: Flow<LowPowerPosture>,
): Flow<Boolean> = combine(userOn, debugOff, posture) { user, debug, low -> !user || debug || low.awareOff }.distinctUntilChanged()

/**
 * "This phone will not run Wi-Fi Aware", which the Bluetooth advert carries so a nearby phone knows not to wake its own
 * Aware for this one (GitHub #44): Knit has the plane switched off ([switchedOff], [awareSwitchedOff]'s answer), or the
 * plane itself says so ([planeWontRun]: the radio off by evidence, or attaching given up). Never true for a plane that is
 * merely dormant or lent to a Wi-Fi Direct group. A phone without the plane builds a constant true instead.
 */
fun awareWontRun(
    switchedOff: Flow<Boolean>,
    planeWontRun: Flow<Boolean>,
): Flow<Boolean> = combine(switchedOff, planeWontRun) { off, plane -> off || plane }.distinctUntilChanged()
