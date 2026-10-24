package app.getknit.knit.mesh.wifiaware

import app.getknit.knit.mesh.power.PowerPolicy
import app.getknit.knit.mesh.power.PowerState

/**
 * How often a Wi-Fi Aware node with **nobody to cue** re-arms its subscribe — pure, like the sibling
 * `Nan*Policy` objects, with the transport's constants passed in so the curve is a table test.
 *
 * Why it exists. With no cue targets `NanSyncPolicy.needsRediscovery` is true on every tick (an empty
 * snapshot is "blind"), so the discovery loop re-armed subscribe every `REARM_COOLDOWN_MS` for as long as the
 * node stayed alone. When this policy was written (ADR 2026-09.kb68) every re-arm's config also asked for Instant
 * Communication Mode, so a 15 s cadence kept ICM lit around the clock for a phone alone in a bag. Since GitHub #44
 * a lonely node's configs ask for no ICM at all ([NanIcmPolicy]'s LONELY rule; the post-cure window after a Tier-1
 * cure is its one exception), so a re-arm no longer lights it: what the cadence still costs is the subscribe
 * close/reopen itself — the session lifecycle this plane treats as wedge-prone (`onSessionConfigFailed`) — and the
 * wakes that go with it. The Bluetooth plane has had the answer since its own lonely-node fix
 * (`PowerPolicy.idleAfterScan`): hunt hard for [PowerPolicy.LONELY_AGGRESSIVE_WINDOW_MS], then relax to the
 * screen-off duty cycle unless the charger is in. This is that rule for the NAN loop — with one exception
 * of its own: **a screen-on node stays aggressive here.** [PowerPolicy.lonelyRelaxed] is true for a screen-on
 * node past the window since ADR 2026-09.w3xk (the BLE scan relaxes it to a 60 s gap), but the only relaxed
 * tick this policy has for it is the interactive duty cycle's 30 s. That was chosen when a re-arm lit ICM for 30 s and
 * the tick saved nothing; with ICM gone from lonely configs the trade has moved (a screen-on lonely re-arm now costs a
 * subscribe cycle, not ICM), but whether a relaxed interactive cadence pays is ADR 2026-09.kb68's device trial to re-run
 * (owed by #44's change), not something this comment can settle — it stays deferred, not implied.
 *
 * What it deliberately does not do: change the tick or the cooldown while a cue target exists (the sync,
 * watchdog and ICM-relight paths are untouched), add a subscribe variant, decide what a config asks of ICM
 * ([NanIcmPolicy] does), or share a clock with the wedge watchdog. Relaxed, the cooldown sits [rearmCooldownMs]
 * under the tick so a timed tick always clears the loop's `>` gate and a stray poke-wake can add at most one extra
 * re-arm per window.
 */
object NanLonelyPolicy {
    /** The loop's cadence while lonely: how long to sleep, and how long since the last re-arm before the next. */
    data class Cadence(
        val tickMs: Long,
        val rearmCooldownMs: Long,
        val relaxed: Boolean,
    )

    /**
     * The loneliness clock as the loop observes it: 0 while cue targets exist; otherwise the stamp already
     * held, or [now] on the first empty observation. Lazy on purpose — nothing at the five sites that remove
     * a cue target needs to know, and "last put" would be eaten by the 150 s linger before the window began.
     */
    fun lonelySince(
        cueTargetsEmpty: Boolean,
        prev: Long,
        now: Long,
    ): Long =
        when {
            !cueTargetsEmpty -> 0L
            prev != 0L -> prev
            else -> now
        }

    /**
     * [lonelyTickMs] and [rearmCooldownMs] are the transport's aggressive constants (8 s / 15 s today);
     * [lonelyForMs] is `now - lonelySince`. Not relaxed: exactly the old behaviour, the tick doubled when
     * screen-off on battery. Relaxed: the tick is the screen-off duty cycle's base interval (120 s, 300 s on
     * low battery) and the cooldown that minus [rearmCooldownMs] — one subscribe cycle per tick instead of one
     * every 15 s (and, before #44's ICM rule, ICM lit a quarter or a tenth of the time instead of all of it).
     */
    fun cadence(
        power: PowerState,
        lonelyForMs: Long,
        lonelyTickMs: Long,
        rearmCooldownMs: Long,
    ): Cadence {
        // Screen on never relaxes here (see the class comment): the shared rule stopped saying so in
        // ADR 2026-09.w3xk, so the clause lives on this side now. `NanLonelyPolicyTest` pins the divergence.
        if (power.interactive || !PowerPolicy.lonelyRelaxed(power, lonelyForMs)) {
            val tick = if (power.interactive || power.charging) lonelyTickMs else lonelyTickMs * 2
            return Cadence(tickMs = tick, rearmCooldownMs = rearmCooldownMs, relaxed = false)
        }
        val tick = PowerPolicy.dutyCycle(power).baseIntervalMs
        return Cadence(tickMs = tick, rearmCooldownMs = (tick - rearmCooldownMs).coerceAtLeast(rearmCooldownMs), relaxed = true)
    }
}
