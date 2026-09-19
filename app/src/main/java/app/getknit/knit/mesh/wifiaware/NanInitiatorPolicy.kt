package app.getknit.knit.mesh.wifiaware

/** What the initiator hold looks like right now (`…debug.NANINIT`, the `init=` field of the state line). */
internal data class NanInitiatorSnapshot(
    val strikes: Int,
    val latched: Boolean,
    /** Milliseconds until the daily probe is due; 0 when it is due now or the role is not held. */
    val probeInMs: Long,
    /** Milliseconds since the last initiate that has not yet been linked, struck or voided; -1 when none. */
    val lastInitiateAgoMs: Long,
    /** Milliseconds a Wi-Fi loss has been pending an `onAvailable`; -1 when none. */
    val lossPendingMs: Long,
) {
    override fun toString(): String =
        if (latched) "held probe=${probeInMs / MINUTE_MS}m" else "$strikes/${NanInitiatorPolicy.STRIKES_TO_LATCH}"

    private companion object {
        const val MINUTE_MS = 60_000L
    }
}

/**
 * Pure, JVM-testable failsafe for the Wi-Fi Aware **initiator** role: notices that this phone's own Wi-Fi
 * (the STA link to its access point) drops whenever we start a data path, and stops us starting them. The
 * fourth of the transport's policies beside [NanConnectPolicy] (one peer's handshake), [NanAttachPolicy]
 * (a failing attach) and [NanResponderPolicy] (a refused responder request).
 *
 * The field shape (ADR 2026-09.bgk3's addendum, work item #78): on a Pixel 3 (blueline, API 31) every NDP we
 * initiate ends 30–100 s later in a firmware REJECT, and within 40 ms of that reject the driver tears its own
 * STA down (`DEAUTH_LEAVING`) — eight Wi-Fi drops in two hours, band-independent, each of which silently kills
 * the app's Aware client. The request never reaches the peer (a byte-swapped publish id inside
 * `system_server`), so nothing we do fixes the cause; but the Pixel 3 holds the largest node id in its mesh,
 * so the tie-break makes it the initiator to everyone, and [NanConnectPolicy] caps its retry at a minute and
 * never gives up. A Wi-Fi drop a minute, for as long as Knit runs.
 *
 * ## What counts
 *
 * The evidence is a **coincidence**, never a failure on its own: the P7/P8/P9 fleet runs long fast-fail streaks
 * against a wedged responder with no STA harm at all, so a [NanConnectPolicy] streak must not feed this. A
 * strike is a Wi-Fi **blip** — [noteWifiLost] followed by [noteWifiAvailable] within [BLIP_MAX_MS] (a phone
 * walking out of range does not come back in seconds) — whose loss fell within [COINCIDENCE_WINDOW_MS] *after*
 * an initiate of ours ([noteInitiate]) that has not been linked since. The initiate need not have been reported
 * failed: a drop in the first seconds of a handshake fails it milliseconds later, but the strike is judged when
 * the Wi-Fi comes back, so requiring the failure first would miss exactly that case. One initiate can earn at
 * most one strike. At [STRIKES_TO_LATCH] the role is **held** ([latched]): the transport stops initiating, and
 * everything else — the responder, discovery, cues, the fast plane — keeps running, so nearby phones can still
 * connect to this one and Bluetooth carries custody.
 *
 * ## What refunds it (the ADR 055 lesson: a bound is worth what its refund path is worth)
 *
 * Exactly one thing in the field: an **initiator link that formed** ([noteInitiatorLink]) — proof the NDP path
 * works on this hardware, so any coincidence was noise. The user may also release it by hand ([reset]), and
 * the transport's build + ROM stamp re-arms it on a new build or a flashed firmware (the journal is keyed on
 * it, as the attach give-up is). Never the Aware availability edge (a blip produces one), `heal()`, `stop()`
 * (a mesh restart is stop + start), a fresh session, or the Wi-Fi coming back — those are the events the
 * fault itself produces.
 *
 * Two events are voided as **not evidence** ([noteRadioOff]): the transport lending the radio to our own Wi-Fi
 * Direct group (`pause()`, which can blip the STA on some chipsets) and Aware going unavailable (a Wi-Fi toggle
 * takes the STA and Aware down together, where the Pixel 3's fault is the *silent* kind — Aware stays
 * available). A pending loss is also dropped on `stop()` ([noteWatchStopped]), because a blip cannot span a
 * restart; the strikes and the latch are not.
 *
 * ## The daily probe
 *
 * A held role is retried once per [PROBE_INTERVAL_MS]: [probeDue] lets `driveSync` take one initiate through
 * the ordinary path, and [noteInitiate] consumes it. A probe that links refunds everything; one that drops
 * the Wi-Fi again is judged [Verdict.AlreadyHeld] and costs one drop a day instead of one a minute; one that
 * merely fails keeps the hold. The probe rides the **wall clock** ([wallNow]) because it is journaled and must
 * survive a reboot; the blip and coincidence windows ride the monotonic clock ([now]) like the rest of the
 * transport. A `probedAt` in the future (the clock went back) counts as due.
 *
 * Strikes live in memory only: a process death between strikes re-learns at most two drops. The latch is what
 * the transport journals.
 */
internal class NanInitiatorPolicy(
    private val now: () -> Long,
    private val wallNow: () -> Long,
    private val blipMaxMs: Long = BLIP_MAX_MS,
    private val coincidenceWindowMs: Long = COINCIDENCE_WINDOW_MS,
    private val strikesToLatch: Int = STRIKES_TO_LATCH,
    private val probeIntervalMs: Long = PROBE_INTERVAL_MS,
) {
    /** What a Wi-Fi `onAvailable` amounted to. */
    enum class Verdict {
        /** Not a blip, or a blip with no initiate of ours behind it. */
        None,

        /** A coincidence counted; the role is still open. */
        Strike,

        /** This coincidence latched the role. */
        Latched,

        /** A coincidence while the role was already held — the daily probe dropped the Wi-Fi again. */
        AlreadyHeld,
    }

    private var lastInitiateAt = 0L
    private var lostAt = 0L
    private var probedAt = 0L

    var strikes: Int = 0
        @Synchronized get
        private set

    var latched: Boolean = false
        @Synchronized get
        private set

    /** The journal says this build/ROM held the role, last probed at [probedAt] (wall clock). ORs with memory. */
    @Synchronized
    fun restore(probedAt: Long) {
        latched = true
        this.probedAt = probedAt
    }

    /**
     * An initiate of ours is in the air (`requestNetwork` accepted). Returns true when it is the daily probe of
     * a held role, which it consumes — the caller re-journals the probe time.
     */
    @Synchronized
    fun noteInitiate(): Boolean {
        lastInitiateAt = now()
        if (!latched) return false
        probedAt = wallNow()
        return true
    }

    /**
     * An initiator link of ours formed: the NDP path works on this hardware, so every strike was noise. Returns
     * whether the role had been held — the caller clears the journal.
     */
    @Synchronized
    fun noteInitiatorLink(): Boolean {
        val wasHeld = latched
        strikes = 0
        lastInitiateAt = 0L
        latched = false
        return wasHeld
    }

    /** Our Wi-Fi network went away. */
    @Synchronized
    fun noteWifiLost() {
        lostAt = now()
    }

    /** A Wi-Fi network is up. Judged against the pending loss, if any — see the class doc for the rule. */
    @Synchronized
    fun noteWifiAvailable(): Verdict {
        val lost = lostAt
        lostAt = 0L
        val init = lastInitiateAt
        val evidence =
            lost != 0L && // not the registration echo, nor an available with no loss before it
                now() - lost <= blipMaxMs && // back quickly enough to be a blip, not a walk out of range
                init != 0L && init <= lost && lost - init <= coincidenceWindowMs // charged to an initiate of ours
        if (!evidence) return Verdict.None
        lastInitiateAt = 0L // one strike per initiate
        return when {
            latched -> {
                Verdict.AlreadyHeld
            }

            ++strikes < strikesToLatch -> {
                Verdict.Strike
            }

            else -> {
                latched = true
                probedAt = wallNow() // the first probe is a day out, not on the next tick
                Verdict.Latched
            }
        }
    }

    /** The radio went away for a reason of ours or the user's — nothing in flight is evidence. */
    @Synchronized
    fun noteRadioOff() {
        lastInitiateAt = 0L
        lostAt = 0L
    }

    /** The Wi-Fi watch is unregistering (`stop()`): a blip cannot span it. Strikes and the latch stay. */
    @Synchronized
    fun noteWatchStopped() {
        lostAt = 0L
    }

    /** Whether a held role is due its daily probe. Always false while the role is open. */
    @Synchronized
    fun probeDue(): Boolean {
        if (!latched) return false
        val t = wallNow()
        return t - probedAt >= probeIntervalMs || probedAt > t
    }

    /** The user's "Try again": everything to zero. */
    @Synchronized
    fun reset() {
        strikes = 0
        latched = false
        lastInitiateAt = 0L
        lostAt = 0L
        probedAt = 0L
    }

    @Synchronized
    fun snapshot(): NanInitiatorSnapshot {
        val t = now()
        return NanInitiatorSnapshot(
            strikes = strikes,
            latched = latched,
            probeInMs = if (latched && !probeDue()) probeIntervalMs - (wallNow() - probedAt) else 0L,
            lastInitiateAgoMs = if (lastInitiateAt == 0L) -1L else t - lastInitiateAt,
            lossPendingMs = if (lostAt == 0L) -1L else t - lostAt,
        )
    }

    companion object {
        /**
         * How quickly the Wi-Fi must be back for its loss to read as a blip. The field drops re-associated
         * within four seconds; a phone leaving its access point's range does not come back in fifteen.
         */
        const val BLIP_MAX_MS = 15_000L

        /**
         * How long after an initiate of ours a blip is still charged to it. The firmware's negotiation on the
         * Pixel 3 ran 30–100 s past the request, well beyond the transport's 15 s handshake timeout.
         */
        const val COINCIDENCE_WINDOW_MS = 120_000L

        /** Coincidences that hold the role. Three drops is the cost of learning; one a day is the cost after. */
        const val STRIKES_TO_LATCH = 3

        /** How often a held role is probed with one ordinary initiate. */
        const val PROBE_INTERVAL_MS = 24L * 60 * 60_000
    }
}
