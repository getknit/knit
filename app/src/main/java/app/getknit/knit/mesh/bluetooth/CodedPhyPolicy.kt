package app.getknit.knit.mesh.bluetooth

import kotlin.math.roundToInt

/**
 * How the Bluetooth plane uses the LE Coded PHY (getknit/knit#29, ADR 2026-10.yvn6) — an experiment, dark in release
 * behind `BuildConfig.BLE_CODED_PHY`, switched at run time from Diagnostics or `…debug.PHY` (`SettingsStore.debugBlePhyMode`).
 */
enum class CodedPhyMode {
    /** Today's plane: the legacy presence advert, a legacy scan, links on whatever PHY the stack picks. The baseline. */
    OFF,

    /**
     * A Coded advert and an all-PHY scan; each capable link steps down to Coded S=8 on its link RSSI, and back up to
     * 1M or 2M, whichever the controller picks.
     */
    AUTO,

    /** As [AUTO], but every capable link is held on Coded S=8 whatever its RSSI — the range ceiling of a walk test. */
    CODED,

    /** As [AUTO], but every capable link is held on 1M — Coded discovery with today's links, for an A/B. */
    ONE_M,
    ;

    companion object {
        /** The stored or bridged spelling (`off`, `auto`, `coded`, `1m`), or null when it is none of them. */
        fun parse(value: String?): CodedPhyMode? =
            when (value?.lowercase()) {
                "off" -> OFF
                "auto" -> AUTO
                "coded" -> CODED
                "1m", "one_m" -> ONE_M
                else -> null
            }
    }

    /** The spelling [parse] reads back. */
    val wire: String get() = if (this == ONE_M) "1m" else name.lowercase()
}

/** A link's PHY as the controller reports it ([UNKNOWN] until the first read answers). */
enum class LinkPhy { ONE_M, TWO_M, CODED, UNKNOWN }

/**
 * The field-tunable thresholds of [PhyStepper], in link RSSI (`BluetoothGatt.readRemoteRssi`, which reads ~20 dB
 * stronger than an advert at the same spot — 2026-10-01 spike: link −70 where the adverts read the −90s). The
 * defaults are a first guess for the walk test; `…debug.PHY` overrides them on a debug build.
 */
data class PhyTuning(
    /** Step a 1M link down to Coded once its smoothed link RSSI has been at or under this for [stepDownReads] reads. */
    val stepDownDbm: Int = STEP_DOWN_DBM,
    val stepDownReads: Int = 3,
    /** Step a Coded link back up once its smoothed link RSSI has held at or over this for [stepUpHoldMs]. */
    val stepUpDbm: Int = STEP_UP_DBM,
    val stepUpHoldMs: Long = 30_000,
    /** No automatic switch sooner than this after the last one, so a link at the boundary cannot flap. */
    val minSwitchGapMs: Long = 20_000,
    /** A request no PHY update answered by then is given up for the link (a controller without Coded answers nothing). */
    val requestTimeoutMs: Long = 3_000,
    /** The link-RSSI read cadence, tightened to [edgeReadMs] once the link is at or under [edgeDbm]. */
    val readMs: Long = 5_000,
    val edgeReadMs: Long = 2_000,
    val edgeDbm: Int = EDGE_DBM,
    /** EWMA weight on each link-RSSI read. */
    val rssiAlpha: Double = 0.5,
    /** What a Coded advert reading is worth on the 1M scale ([CodedPhyPolicy.effectiveRssi]); `…debug.PHY --ei credit`. */
    val codedCreditDb: Double = CodedPhyPolicy.CODED_RSSI_CREDIT_DB,
    /** The Coded advert's interval while a far peer that just dropped is wanted back ([CodedAdvertPace]). */
    val fastAdvertMs: Int = 250,
    /** How long a drop at range keeps the Coded advert fast, unless the peer links again first. */
    val fastHoldMs: Long = 180_000,
) {
    private companion object {
        // Negative defaults can't be inlined without tripping MagicNumber (as PromotionConfig's floor).
        const val STEP_DOWN_DBM = -82
        const val STEP_UP_DBM = -72
        const val EDGE_DBM = -75
    }
}

/**
 * What a presence scan window listens on: [ONE_M] is today's legacy scan, [ALL] every PHY the controller has (a Coded
 * advert beside the legacy one), [CODED] the Coded PHY alone — a window that hears no legacy advert at all.
 */
enum class ScanPhys { ONE_M, ALL, CODED }

/** The pure rules behind the Coded PHY experiment: how a Coded sighting is scored, and which address to dial. */
object CodedPhyPolicy {
    /**
     * What a Coded sighting is worth on the 1M scale every existing RSSI floor is sized for (the −90 of
     * [PromotionConfig], [LonelyDialPolicy], [BleAdmissionPolicy]): the Coded receiver hears ~12 dB deeper, so a
     * Coded advert read at −102 is a peer at the edge of usable Coded range, the place −90 marks on 1M.
     */
    const val CODED_RSSI_CREDIT_DB = 12.0

    /** How recently a peer's 1M advert must have been heard for a dial to prefer it over its Coded one. */
    const val ONE_M_FRESH_MS = 8_000L

    /**
     * One peer's RSSI on the 1M scale: the stronger of its 1M reading and its Coded reading plus the credit; null
     * when neither is known. With the experiment off there are no Coded readings, so this is the 1M reading unchanged.
     */
    fun effectiveRssi(
        rssi1m: Double?,
        rssiCoded: Double?,
        creditDb: Double = CODED_RSSI_CREDIT_DB,
    ): Double? {
        val coded = rssiCoded?.plus(creditDb)
        return when {
            rssi1m == null -> coded
            coded == null -> rssi1m
            else -> maxOf(rssi1m, coded)
        }
    }

    /**
     * Whether only the Coded PHY hears this peer now: a Coded advert was heard, and no 1M one was, or the last 1M one
     * trails the last Coded one by more than [ONE_M_FRESH_MS]. Measured as that *lag*, never as the 1M advert's age:
     * between scan windows a close peer's two sightings go stale together, and it is no more Coded-only for that.
     */
    fun codedOnly(
        oneMSeenAgoMs: Long?,
        codedSeenAgoMs: Long?,
    ): Boolean = codedSeenAgoMs != null && (oneMSeenAgoMs == null || oneMSeenAgoMs - codedSeenAgoMs > ONE_M_FRESH_MS)

    /**
     * Whether a dial should use the peer's Coded address rather than its 1M one: only when the peer is [codedOnly] —
     * a link that can open on 1M opens there (faster, and the stack's own choice).
     */
    fun dialCoded(
        oneMSeenAgoMs: Long?,
        codedSeenAgoMs: Long?,
    ): Boolean = codedOnly(oneMSeenAgoMs, codedSeenAgoMs)

    /**
     * Whether a link that just went down on its own (not evicted or stopped) should make this phone's Coded advert fast
     * for a while. Only on the side that is dialed — the smaller id; the larger dials back ([drives]) and needs the
     * other's advert, not its own — and only when the link was on Coded or at the step-down edge as it went, the place
     * a peer that drops is just out of reach. A Coded advert once a second gave the far dialer of the 2026-10-01 walk
     * one link in four dials; [PhyTuning.fastAdvertMs] gives it four times the chances.
     */
    fun fastAdvertAfterDrop(
        localNodeId: String,
        peerNodeId: String,
        phy: LinkPhy,
        linkRssi: Int?,
        tuning: PhyTuning,
    ): Boolean = !drives(localNodeId, peerNodeId) && (phy == LinkPhy.CODED || (linkRssi != null && linkRssi <= tuning.stepDownDbm))

    /** [ms] as an advertising interval in the stack's 0.625 ms units, no shorter than the 100 ms it allows. */
    fun advertIntervalUnits(ms: Int): Int = maxOf((ms / ADVERT_UNIT_MS).roundToInt(), MIN_ADVERT_INTERVAL_UNITS)

    private const val ADVERT_UNIT_MS = 0.625
    private const val MIN_ADVERT_INTERVAL_UNITS = 160

    /** How long a dial may run before the transport's watchdog gives its slot up: [CONNECT_TIMEOUT_MS] on 1M. */
    fun connectTimeoutMs(viaCoded: Boolean): Long = if (viaCoded) CODED_CONNECT_TIMEOUT_MS else CONNECT_TIMEOUT_MS

    /** An ordinary dial's watchdog. */
    const val CONNECT_TIMEOUT_MS = 12_000L

    /**
     * A dial to a peer's Coded address gets longer. While a direct connect is pending, Android's initiator listens on
     * Coded for 15 ms in every 60 ms (AOSP `le_impl.h`, `kScanWindowCodedFast`), and a far peer's Coded advert is faint
     * and sparse, so 12 s gives a dial at the edge only a few chances: on the 2026-10-01 walk three of four Coded dials
     * died at the watchdog, and the one that linked took 7.4 s to its LE Connection Complete. This stays under the
     * stack's own direct-connect timeout (30 s, `connection_manager.cc`), so the watchdog still owns the failure.
     */
    const val CODED_CONNECT_TIMEOUT_MS = 25_000L

    /**
     * Whether the L2CAP responder counts a dialer as sighted for [BleAdmissionPolicy.decide] — [snap] is the dialer's
     * presence, null when the scan holds none. While the experiment runs ([codedOn]) a dialer heard on Coded alone is
     * counted as unsighted: its faint, sparse Coded hits are enough to hold it in presence but rarely enough to promote
     * it, so refusing it on the tie-break ("we dial it") left a far pair each waiting on the other (the 2026-10-01
     * walk). A dialer heard on 1M is judged exactly as before (ADR 2026-09.shzv).
     */
    fun sightedForAdmission(
        snap: BlePresenceTracker.Snapshot?,
        codedOn: Boolean,
    ): Boolean = snap != null && !(codedOn && codedOnly(snap.oneMSeenAgoMs, snap.codedSeenAgoMs))

    /**
     * What the next presence scan window listens on. While the experiment runs, a phone with no link, or with an
     * unlinked peer heard on Coded alone, gives every other window to Coded entirely — an all-PHY window splits its
     * time, and a far peer's Coded hits are sparse — and never two in a row, so a legacy-only peer (an iPhone, a
     * controller without Coded) is still heard every other window. Otherwise every window is all-PHY.
     */
    fun scanPhys(
        codedOn: Boolean,
        alone: Boolean,
        codedOnlyUnlinked: Boolean,
        lastWasCoded: Boolean,
    ): ScanPhys =
        when {
            !codedOn -> ScanPhys.ONE_M
            (alone || codedOnlyUnlinked) && !lastWasCoded -> ScanPhys.CODED
            else -> ScanPhys.ALL
        }

    /** Which side of a link drives its PHY: the larger node id, the side that dials. The other only watches. */
    fun drives(
        localNodeId: String,
        peerNodeId: String,
    ): Boolean = localNodeId > peerNodeId
}

/**
 * One link's PHY steps: fed the controller's PHY reports ([onPhy]) and link-RSSI reads, asked what to request
 * ([decide]). Pure and clock-injected; thread-safe because the reports arrive on a binder thread.
 */
class PhyStepper(
    private val tuning: () -> PhyTuning,
) {
    /** What to ask the controller for now. */
    enum class Action {
        STAY,

        /** Coded S=8 both ways: the range end. */
        REQUEST_CODED,

        /** 1M alone — the pinned [CodedPhyMode.ONE_M], an A/B against today's PHY. */
        REQUEST_ONE_M,

        /**
         * 1M or 2M, the controller's pick — AUTO's step-up. Asking 1M alone pinned the link there for the rest of its
         * life, where it could otherwise have run on the 2M the stack upgrades links to by itself.
         */
        REQUEST_FAST,

        GIVE_UP,
    }

    @get:Synchronized
    var phy: LinkPhy = LinkPhy.UNKNOWN
        private set

    @get:Synchronized
    var gaveUp: Boolean = false
        private set

    @get:Synchronized
    var switches: Int = 0
        private set

    @get:Synchronized
    var smoothedRssi: Double? = null
        private set

    // The PHYs that answer the request in flight, null when none is.
    private var pending: Set<LinkPhy>? = null
    private var pendingAt = 0L
    private var lastSwitchAt: Long? = null
    private var weakReads = 0
    private var strongSince: Long? = null

    /**
     * The controller reported [reported] (a read, an update this side asked for, or one the peer asked for). An
     * update that answers our request with a PHY it did not allow, or with a failure, means the pair cannot make it:
     * given up.
     * Returns whether the PHY changed.
     */
    @Synchronized
    fun onPhy(
        reported: LinkPhy,
        succeeded: Boolean,
        now: Long,
    ): Boolean {
        val asked = pending
        if (asked != null) {
            pending = null
            if (!succeeded || reported !in asked) gaveUp = true
        }
        val changed = succeeded && reported != phy && phy != LinkPhy.UNKNOWN
        if (changed) {
            switches++
            lastSwitchAt = now
            weakReads = 0
            strongSince = null
        }
        if (succeeded) phy = reported
        return changed
    }

    /** A link-RSSI read came back: smoothed, and counted toward a step either way. */
    @Synchronized
    fun onRssi(
        rssi: Int,
        now: Long,
    ) {
        val t = tuning()
        val s = smoothedRssi?.let { t.rssiAlpha * rssi + (1 - t.rssiAlpha) * it } ?: rssi.toDouble()
        smoothedRssi = s
        weakReads = if (s <= t.stepDownDbm) weakReads + 1 else 0
        strongSince = if (s >= t.stepUpDbm) strongSince ?: now else null
    }

    /** What to ask for under [mode] at [now]; a request it returns is pending until [onPhy] answers it. */
    @Synchronized
    fun decide(
        mode: CodedPhyMode,
        now: Long,
    ): Action {
        val t = tuning()
        if (gaveUp || mode == CodedPhyMode.OFF || phy == LinkPhy.UNKNOWN) return Action.STAY
        if (pending != null) return pendingVerdict(t, now)
        val want = target(mode, t, now) ?: return Action.STAY
        val answers = answering(want)
        if (phy in answers) return Action.STAY
        pending = answers
        pendingAt = now
        return want
    }

    /** The PHYs a report may name and still have done what [request] asked. */
    private fun answering(request: Action): Set<LinkPhy> =
        when (request) {
            Action.REQUEST_CODED -> setOf(LinkPhy.CODED)
            Action.REQUEST_FAST -> setOf(LinkPhy.ONE_M, LinkPhy.TWO_M)
            else -> setOf(LinkPhy.ONE_M)
        }

    /** A request is out: wait for its answer until the timeout, then give the link up. */
    private fun pendingVerdict(
        t: PhyTuning,
        now: Long,
    ): Action {
        if (now - pendingAt < t.requestTimeoutMs) return Action.STAY
        pending = null
        gaveUp = true
        return Action.GIVE_UP
    }

    /** The request [mode] wants now, or null for none; AUTO waits out the minimum gap since the last switch. */
    private fun target(
        mode: CodedPhyMode,
        t: PhyTuning,
        now: Long,
    ): Action? =
        when (mode) {
            CodedPhyMode.CODED -> Action.REQUEST_CODED
            CodedPhyMode.ONE_M -> Action.REQUEST_ONE_M
            else -> autoTarget(t, now)?.takeIf { lastSwitchAt.let { at -> at == null || now - at >= t.minSwitchGapMs } }
        }

    private fun autoTarget(
        t: PhyTuning,
        now: Long,
    ): Action? {
        val up = strongSince
        return when {
            phy != LinkPhy.CODED && weakReads >= t.stepDownReads -> Action.REQUEST_CODED
            phy == LinkPhy.CODED && up != null && now - up >= t.stepUpHoldMs -> Action.REQUEST_FAST
            else -> null
        }
    }

    /** How long until the next link-RSSI read: tighter at the edge, where a walk-away has seconds to spare. */
    @Synchronized
    fun nextReadMs(): Long {
        val t = tuning()
        val s = smoothedRssi
        return if (s != null && s <= t.edgeDbm) t.edgeReadMs else t.readMs
    }
}
