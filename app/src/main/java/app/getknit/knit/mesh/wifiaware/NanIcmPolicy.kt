package app.getknit.knit.mesh.wifiaware

/**
 * Whether the Wi-Fi Aware configs this node files may ask for **Instant Communication Mode**, and on which band —
 * pure, like the sibling `Nan*Policy` objects, so the rule is a table test (GitHub getknit/knit#44).
 *
 * Why it exists. ICM parks NAN on one channel for 30 s per config update (the framework's
 * `config_wifiAwareInstantCommunicationModeDurationMillis`), and the transport used to ask for it on 2.4 GHz in every
 * publish and subscribe config: that is 2.4 GHz channel 6 (2437 MHz), shared with Bluetooth's front end and, for a STA
 * on any other channel, taken out of its airtime. A Pixel 8a lost ~80 % of its Wi-Fi throughput and its A2DP audio cut
 * out in crowds while Knit ran (#44). The Bluetooth plane already backs off while audio streams; this is the Aware
 * plane's half: no ICM while Bluetooth audio streams, none with nobody to cue, and — in debug builds only, until a
 * cross-band delivery trial passes — the band the STA already sits on, so ICM costs it no channel switch.
 *
 * What it says nothing about: *when* a config is filed. The relight (`needsIcmRelight`), the re-arm cadence
 * ([NanLonelyPolicy]), the watchdogs and the hardware probe are the transport's, untouched; `instantSupported` false
 * (no hardware ICM, or `…debug.NANICM --ez on false`) means no ICM at all, above this policy.
 *
 * Framework facts it rests on (AOSP packages/modules/Wifi, read 2026-10-24): the band is asked, not granted —
 * `getAwareInstantCommunicationChannel` maps 5 GHz to 5745 when the chip lists it among its NAN instant-mode channels,
 * else 5220, else 2437, and caches the answer; when several sessions ask inside one 30 s window, 5 GHz wins; a session
 * whose config has ICM disabled does not count. Only `dumpsys wifiaware`'s `instantModeChannel` says what was granted.
 */
object NanIcmPolicy {
    /** The band a config asks for; the transport maps it to `ScanResult.WIFI_BAND_*` in one place. */
    enum class Band { B24, B5 }

    /** Why the verdict is what it is: the first rule that matched. */
    enum class Reason { OVERRIDE, AUDIO, LONELY, STA_CHANNEL, NO_STA, DEFAULT }

    /** The phone's own Wi-Fi (STA), as the passive `TRANSPORT_WIFI` watch knows it. */
    sealed interface Sta {
        /** The watch is not registered, or has not heard back yet: never read as "no STA". */
        data object Unknown : Sta

        /** The watch is registered, settled, and no Wi-Fi network is up. */
        data object None : Sta

        /** A Wi-Fi network is up on [freqMhz]. */
        data class On(
            val freqMhz: Int,
        ) : Sta
    }

    data class Inputs(
        /** `…debug.NANICM --es band` (null in release, and after `--es band auto`). */
        val override: Band?,
        /** Bluetooth audio is streaming (the Bluetooth plane's `radioContended`). */
        val audioStreaming: Boolean,
        /** Nobody to cue — the transport's `cueTarget` is empty, [NanLonelyPolicy]'s "lonely". */
        val lonely: Boolean,
        /**
         * The configs filed in the first ICM window after a Tier-1 cure cycled the session while a sync was owed: read
         * as not lonely, so a dozing pair re-finds each other with ICM lit as it did before this policy.
         */
        val postCureWindow: Boolean,
        val sta: Sta,
        /** Whether 5745 MHz is usable for Aware (`getUsableChannels`); null when unknown. */
        val band5PrimaryUsable: Boolean?,
        /** The 5 GHz picks are live (debug builds only, until the cross-band delivery trial passes). */
        val fiveGhzRules: Boolean,
    )

    /** [allowed]: an ICM-enabled config may be built; [band] is set only then. */
    data class Verdict(
        val allowed: Boolean,
        val band: Band?,
        val reason: Reason,
    )

    /** The 2.4 GHz social channel (6): what 2.4 GHz ICM is granted on. */
    const val CH6_MHZ = 2437

    /** 5 GHz channel 149: what 5 GHz ICM is granted on when the chip lists it. */
    const val CH149_MHZ = 5745

    /** 5 GHz channel 44: the framework's 5 GHz fallback when 149 is not usable for instant mode. */
    const val CH44_MHZ = 5220

    /** First match wins (the rule table in `NanIcmPolicyTest`). */
    fun decide(inputs: Inputs): Verdict {
        // The override bypasses audio and loneliness on purpose: a band trial needs a lonely, audio-playing phone
        // to light ICM on a chosen band.
        inputs.override?.let { return on(it, Reason.OVERRIDE) }
        // Audio wins over the post-cure window: the cutouts are the user's, the cure only ours.
        if (inputs.audioStreaming) return Verdict(allowed = false, band = null, reason = Reason.AUDIO)
        if (inputs.lonely && !inputs.postCureWindow) return Verdict(allowed = false, band = null, reason = Reason.LONELY)
        return bandFor(inputs.sta, inputs.fiveGhzRules, inputs.band5PrimaryUsable)
    }

    /** Rules 4 to 7: ICM is allowed, and the band is the STA's channel where it has one Knit may share. */
    private fun bandFor(
        sta: Sta,
        fiveGhzRules: Boolean,
        band5PrimaryUsable: Boolean?,
    ): Verdict {
        val freq = (sta as? Sta.On)?.freqMhz
        return when {
            freq == CH6_MHZ -> on(Band.B24, Reason.STA_CHANNEL)

            !fiveGhzRules -> on(Band.B24, Reason.DEFAULT)

            freq == CH149_MHZ -> on(Band.B5, Reason.STA_CHANNEL)

            // 5 GHz on a STA at channel 44 only when the chip is known not to list 149, so the grant falls to 5220.
            freq == CH44_MHZ && band5PrimaryUsable == false -> on(Band.B5, Reason.STA_CHANNEL)

            // No STA to share a channel with: 5 GHz keeps ICM off the band Bluetooth lives on. Unknown never takes it.
            sta == Sta.None -> on(Band.B5, Reason.NO_STA)

            else -> on(Band.B24, Reason.DEFAULT)
        }
    }

    /**
     * The STA as the watch holds it. [registered] false is [Sta.Unknown]; so is an empty watch that has not [settled]
     * (a registration's echo for a network already up takes a beat) and a set of networks none of whose frequency is
     * known yet. With more than one Wi-Fi network up, one on 2437 decides first, then one on 5745, then 5220;
     * otherwise the first known frequency, which takes the default rule. [frequencies] holds 0 for "not known yet".
     */
    fun staOf(
        registered: Boolean,
        settled: Boolean,
        frequencies: Collection<Int>,
    ): Sta {
        if (!registered) return Sta.Unknown
        if (frequencies.isEmpty()) return if (settled) Sta.None else Sta.Unknown
        val known = frequencies.filter { it > 0 }
        val pick =
            listOf(CH6_MHZ, CH149_MHZ, CH44_MHZ).firstOrNull { it in known }
                ?: known.firstOrNull()
                ?: return Sta.Unknown
        return Sta.On(pick)
    }

    private fun on(
        band: Band,
        reason: Reason,
    ) = Verdict(allowed = true, band = band, reason = reason)

    /**
     * How long ICM was lit by this node's own configs, as an estimate: the union of the windows that each ICM-enabled
     * config it sent opens ([windowMs] from its send). It is ours, not the radio's: the granted state is `dumpsys
     * wifiaware`'s `instantModeChannel`, which another app's session or a refused update can make differ. Fed on the
     * transport's handler thread, read by the field log's sampler; synchronized for that split. Times are the
     * monotonic clock's.
     */
    class LitMeter(
        private val windowMs: Long,
    ) {
        private var startedAt = 0L
        private var closedMs = 0L
        private var litFrom = 0L
        private var litUntil = 0L
        private var configs = 0

        /** A fresh plane: nothing lit, counting from [now]. */
        @Synchronized
        fun reset(now: Long) {
            startedAt = now
            closedMs = 0L
            litFrom = 0L
            litUntil = 0L
            configs = 0
        }

        /** An ICM-enabled config went out at [now]: its window runs [windowMs] from here, overlapping windows merge. */
        @Synchronized
        fun onConfig(now: Long) {
            configs++
            if (litUntil != 0L && now <= litUntil) {
                litUntil = now + windowMs
                return
            }
            if (litUntil != 0L) closedMs += litUntil - litFrom
            litFrom = now
            litUntil = now + windowMs
        }

        /** Lit time since [reset], to [now]. */
        @Synchronized
        fun litMs(now: Long): Long = closedMs + if (litUntil == 0L) 0L else (minOf(now, litUntil) - litFrom).coerceAtLeast(0L)

        /** Time since [reset]. */
        @Synchronized
        fun upMs(now: Long): Long = (now - startedAt).coerceAtLeast(0L)

        /** ICM-enabled configs sent since [reset]. */
        @Synchronized
        fun configs(): Int = configs

        /** Whether a config of ours is holding ICM lit at [now]. */
        @Synchronized
        fun litAt(now: Long): Boolean = litUntil != 0L && now < litUntil
    }
}
