package app.getknit.knit.mesh.bluetooth

import app.getknit.knit.mesh.link.FeedWindow
import app.getknit.knit.mesh.link.LinkFraming
import app.getknit.knit.mesh.link.PaceConfig
import kotlin.math.roundToInt

/**
 * How the Bluetooth plane uses the LE Coded PHY (getknit/knit#29, ADR 2026-10.yvn6) — behind
 * `BuildConfig.BLE_CODED_PHY` (on in every build by default), switched at run time from Diagnostics or
 * `…debug.PHY` (`SettingsStore.debugBlePhyMode`).
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
    /**
     * Or once it has held at or over this for [stepUpFastHoldMs]: a link this strong is plainly back in range, and the
     * 30 s hold is sized for the band just above [stepUpDbm], where a link hovering at the edge would flap (#113).
     */
    val stepUpFastDbm: Int = STEP_UP_FAST_DBM,
    val stepUpFastHoldMs: Long = 10_000,
    /** No automatic switch sooner than this after the last one, so a link at the boundary cannot flap. */
    val minSwitchGapMs: Long = 20_000,
    /**
     * A step-down to Coded this soon after the link's last step-up is a reversal, and doubles AUTO's step-up backoff;
     * a later one (the link stayed fast a while) resets it ([PhyStepper.stepUpBackoffMs], #169).
     */
    val flapWindowMs: Long = 300_000,
    /**
     * The most the step-up backoff grows to: the longest a link that flaps is held on Coded after a step-down before
     * AUTO may step it up again. Only the step-up backs off, never the step-down: a link flapping in a hand swung between
     * a body-blocked and a clear path stepped 14 times in 33 min (#169), and some of those step-downs began a real
     * walk-away that lost the link minutes later. A late step-down at the edge costs the link; a late step-up costs
     * throughput and airtime (S=8 moves about a fifth of 1M, and keeps the shared 2.4 GHz radio on about 8× as long per
     * byte), never the link.
     */
    val stepUpBackoffMaxMs: Long = 300_000,
    /** A request no PHY update answered by then is given up for the link (a controller without Coded answers nothing). */
    val requestTimeoutMs: Long = 3_000,
    /** The link-RSSI read cadence, tightened to [edgeReadMs] once the link is at or under [edgeDbm]. */
    val readMs: Long = 5_000,
    val edgeReadMs: Long = 2_000,
    val edgeDbm: Int = EDGE_DBM,
    /** EWMA weight on each link-RSSI read. */
    val rssiAlpha: Double = 0.5,
    /**
     * A link is fading ([LinkFade], ADR 2026-10.79yb's ninth amendment) once its smoothed link RSSI is at or under this,
     * or once [fadeStaleReads] reads in a row went unanswered (counted, never timed: a sleeping CPU stops the reads,
     * not the clock). Link RSSI, the scale the step thresholds read: −95 sits under the step-down, near where the
     * 2026-10-13 Coded link dropped (−103 at its end). `…debug.PHY --ei fade`.
     */
    val fadeDbm: Int = FADE_DBM,
    val fadeStaleReads: Int = 3,
    /**
     * A fading link stops fading only after answered reads have held its smoothed link RSSI at or over this for
     * [fadeExitHoldMs]: sticky on purpose, since what a fade buys (a walkie chunk also sent through a relay) is cheap.
     */
    val fadeExitDbm: Int = FADE_EXIT_DBM,
    val fadeExitHoldMs: Long = 10_000,
    /** What a Coded advert reading is worth on the 1M scale ([CodedPhyPolicy.effectiveRssi]); `…debug.PHY --ei credit`. */
    val codedCreditDb: Double = CodedPhyPolicy.CODED_RSSI_CREDIT_DB,
    /** The Coded advert's interval while a far peer that just dropped is wanted back ([CodedAdvertPace]). */
    val fastAdvertMs: Int = 250,
    /**
     * How long a drop at range keeps the Coded advert fast on the dialed side, and the scan hunting on the dialing side
     * ([RangeHunt]), unless the peer links again first.
     */
    val fastHoldMs: Long = 180_000,
    /**
     * How long the dialing side keeps hunting a peer it lost at range while Knit is open on a lit screen, from the drop
     * ([RangeHunt]); never less than [fastHoldMs]. A bound, so a peer that left for good is not hunted for as long as
     * the app happens to stay open.
     */
    val huntInUseHoldMs: Long = 1_800_000,
    /**
     * The smoothed 1M advert reading (raw, on the 1M scale, no credit: the presence advert at `TX_POWER_HIGH`, ADR
     * 2026-10.ryak) under which a 1M hit no longer counts against Coded alone ([BlePresenceTracker]'s 1M mark stays
     * put). Past it 1M is beyond the place a 1M link opens, so a peer whose faint 1M advert still got through now and
     * then never became [CodedPhyPolicy.codedOnly], was dialed on 1M and backed off while Coded heard it fine (ADR
     * 2026-10.yvn6, the 2026-10-20 amendment). The promotion floor by default, a guess, not a measured 1M dial limit:
     * the walk trial sizes it. `…debug.PHY --ei oneMWeak` (−200 restores the old rule, every 1M hit resets the mark).
     */
    val oneMWeakDbm: Double = PromotionConfig.DEFAULT_RSSI_FLOOR_DBM.toDouble(),
    /**
     * Whether a cold far sighting — an unlinked peer heard on Coded alone, no drop behind it — starts a bounded hunt or
     * a fast Coded advert ([CodedPhyPolicy.coldHunt], [ColdSightings]). `…debug.PHY --ez coldHunt false` for the trial's A/B.
     */
    val coldHunt: Boolean = true,
    /**
     * The per-peer cooldown of a cold action, and the window of the phone-wide budget: at most one cold hunt or fast
     * window per peer, and at most [fastHoldMs] of each kind across all peers, per this ([ColdSightings]). It bounds
     * what a parked neighbour heard through a wall costs: 3 min of hunting per 30.
     */
    val coldHuntCooldownMs: Long = 1_800_000,
    /**
     * A file's feed rate and chunk on a link on Coded ([CodedPhyPolicy.pace], #114): about what S=8 drained at the edge
     * of the 2026-10-02 walk (~1.1 KB/s), so a frame queued behind a file waits about one chunk, not the file. A peer
     * that reports is windowed on Coded too (ADR 2026-10.5buf), and this pace is then its fallback: an iPhone, an
     * older build, and a receiver gone silent keep it.
     */
    val codedPaceBytesPerSec: Int = 1024,
    val codedChunkBytes: Int = 2048,
    /**
     * A windowed feed's smallest chunk on Coded ([FeedWindow.minChunk]), which sizes its floors: the window never under
     * two of them, four before the first report. Half the 1M/2M floor, the least a dark receiver still reports on
     * ([FeedWindow.SMALLEST_CHUNK_BYTES]): at the edge's ~1.1 KB/s a frame behind a file then waits under three
     * seconds, where the 1M floor held it near four (the simulation). `…debug.PHY --ei codedWindowChunk`.
     */
    val codedWindowChunkBytes: Int = FeedWindow.SMALLEST_CHUNK_BYTES,
    /**
     * The file-feed budget every link on 1M or 2M shares, split among the ones feeding a file at once
     * ([CodedPhyPolicy.pace]); ≤ 0 is unbounded. `…debug.PHY --ei filePace` sweeps it in a trial against the
     * receiver's `rx … in <ms>ms` line (#117).
     */
    val filePaceBytesPerSec: Int = CodedPhyPolicy.BLE_PACE_BYTES_PER_SEC,
    /**
     * The budget that replaces [filePaceBytesPerSec] for a link that has earned the short interval it asked for
     * ([LinkBoostPolicy], #120; each link its own since ADR 2026-10.g24x), on a phone holding at most [boostFullLeLinks]
     * LE connections; ≤ 0 is unbounded. `…debug.LINKTUNE --ei boostPace` sweeps it.
     */
    val boostPaceBytesPerSec: Int = CodedPhyPolicy.BOOST_PACE_BYTES_PER_SEC,
    /** The earned budget on a phone holding more LE connections than that; `…debug.LINKTUNE --ei crowdedPace`. */
    val crowdedBoostPaceBytesPerSec: Int = CodedPhyPolicy.CROWDED_BOOST_PACE_BYTES_PER_SEC,
    /** The most LE connections (the mesh's and every other app's) at which [boostPaceBytesPerSec] holds. */
    val boostFullLeLinks: Int = CodedPhyPolicy.BOOST_FULL_LE_LINKS,
    /**
     * The queue, in ms of the measured drain, a windowed feed keeps ahead of its receiver ([FeedWindow], ADR
     * 2026-10.nsr7's phase 2): the wait a frame behind a file sees. `…debug.LINKTUNE --ei windowQueueMs` sweeps it.
     */
    val feedWindowQueueMs: Long = FeedWindow.QUEUE_MS,
) {
    private companion object {
        // Negative defaults can't be inlined without tripping MagicNumber (as PromotionConfig's floor).
        const val STEP_DOWN_DBM = -82
        const val STEP_UP_DBM = -72
        const val STEP_UP_FAST_DBM = -62
        const val EDGE_DBM = -75
        const val FADE_DBM = -95
        const val FADE_EXIT_DBM = -90
    }
}

/**
 * What a presence scan window listens on: [ONE_M] is today's legacy scan, [ALL] every PHY the controller has (a Coded
 * advert beside the legacy one), [CODED] the Coded PHY alone — a window that hears no legacy advert at all.
 */
enum class ScanPhys { ONE_M, ALL, CODED }

/** What [CodedPhyPolicy.coldHunt] asks of a cold far sighting: hunt it, advertise fast for it, or nothing. */
enum class ColdAction { HUNT, FAST_ADVERT, NONE }

/** One unlinked peer heard on Coded alone, as [CodedPhyPolicy.coldHunt] judges it. */
data class ColdSighting(
    val peerNodeId: String,
    /** Its smoothed advert RSSI on the 1M scale ([BlePresenceTracker.Snapshot.smoothedRssi]), what every −90 floor reads. */
    val smoothedRssi: Double,
    /** Linked, or a dial to it in flight. */
    val linked: Boolean,
    /** Already hunted ([RangeHunt]) or wanted back by the fast advert ([CodedAdvertPace]). */
    val held: Boolean,
    /** In connect backoff. */
    val backingOff: Boolean,
    /** The promotion would dial it once its dwell ripened ([DialPlan.promotes]); read only on the side that dials. */
    val promotes: Boolean,
)

/** The pure rules behind the Coded PHY experiment: how a Coded sighting is scored, and which address to dial. */
@Suppress("TooManyFunctions") // one decision table for the experiment: scoring, dials, drops, hunts and pace side by side
object CodedPhyPolicy {
    /**
     * What a Coded sighting is worth on the 1M scale every existing RSSI floor is sized for (the −90 of
     * [PromotionConfig], [LonelyDialPolicy], [BleAdmissionPolicy]): the Coded receiver hears ~12 dB deeper, so a
     * Coded advert read at −102 is a peer at the edge of usable Coded range, the place −90 marks on 1M. A receiver's
     * margin, so it holds only while both adverts go out at one power: both are HIGH (ADR 2026-10.ryak). While the 1M
     * advert was MEDIUM, a peer heard on both PHYs read 20 dB above its 1M reading, not 12.
     */
    const val CODED_RSSI_CREDIT_DB = 12.0

    /**
     * How long a scan may listen on 1M past a peer's last 1M hit, with Coded still hearing it, before the peer counts as
     * heard on Coded alone ([codedOnly]).
     */
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
     * Whether only the Coded PHY hears this peer now: Coded kept hearing it for more than [ONE_M_FRESH_MS] of 1M
     * listening after its last 1M hit — [codedLagMs] is [BlePresenceTracker.Snapshot.codedLagMs]. A lag, never the 1M
     * advert's age: between scan windows a close peer's two sightings go stale together. And 1M *listening*, never wall
     * time: a Coded-only window hears no 1M advert, so on the wall clock it made every close peer Coded-only by its end,
     * and links in one room opened on Coded (the 2026-10-02 bench, ADR 2026-10.yvn6).
     */
    fun codedOnly(codedLagMs: Long?): Boolean = codedLagMs != null && codedLagMs > ONE_M_FRESH_MS

    /**
     * Whether a dial should use the peer's Coded address rather than its 1M one: only when the peer is [codedOnly] —
     * a link that can open on 1M opens there (faster, and the stack's own choice).
     */
    fun dialCoded(codedLagMs: Long?): Boolean = codedOnly(codedLagMs)

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
        dialsBack: Boolean = drives(localNodeId, peerNodeId),
    ): Boolean = !dialsBack && droppedAtRange(phy, linkRssi, tuning)

    /**
     * The other half of [fastAdvertAfterDrop]: whether this phone should hunt the peer whose link just went down on its
     * own — on the side that dials back ([drives]), for the same drop at range. The fast advert is only worth what the
     * dialer's scan hears of it, and a dialer that still held another link sat at the settled floor with no Coded
     * window and never heard its lost peer again (the 2026-10-04 walk: about 6 min to each relink, both by the far
     * peer's lonely dial). A [RangeHunt] holds the peer, and the scan runs [hunts]' cadence while it is wanted.
     */
    fun huntAfterDrop(
        localNodeId: String,
        peerNodeId: String,
        phy: LinkPhy,
        linkRssi: Int?,
        tuning: PhyTuning,
        dialsBack: Boolean = drives(localNodeId, peerNodeId),
    ): Boolean = dialsBack && droppedAtRange(phy, linkRssi, tuning)

    /** Whether a link went down where its peer is just out of reach: on Coded, or at or under the step-down as it went. */
    private fun droppedAtRange(
        phy: LinkPhy,
        linkRssi: Int?,
        tuning: PhyTuning,
    ): Boolean = phy == LinkPhy.CODED || (linkRssi != null && linkRssi <= tuning.stepDownDbm)

    /**
     * Whether the presence scan hunts: the lonely gap ([app.getknit.knit.mesh.power.PowerPolicy.HUNT_IDLE_MS]) and every
     * other window on Coded, whatever links the phone holds, never at the settled floor. Only while the experiment runs
     * ([codedOn]), and then while a peer lost at range is wanted back ([lostWanted], from [RangeHunt]) or while Knit is
     * open on a lit screen ([inUse]): the person is looking for somebody, and the screen costs far more than the
     * receiver. A phone in a pocket hunts only for the [PhyTuning.fastHoldMs] after a drop.
     */
    fun hunts(
        codedOn: Boolean,
        lostWanted: Boolean,
        inUse: Boolean,
    ): Boolean = codedOn && (lostWanted || inUse)

    /**
     * What a cold far sighting asks of this phone (the 2026-10-20 amendment): an unlinked peer heard on Coded alone
     * ([ScanPlan.far]) with no drop behind it got only the settled cadence and a 1 s Coded advert, so a first meeting at
     * range waited on luck. The same two halves as a drop at range, on the same sides: the side that dials ([drives])
     * [ColdAction.HUNT]s it, the dialed side makes its Coded advert fast ([ColdAction.FAST_ADVERT]).
     *
     * Only while the experiment runs ([codedOn]), [PhyTuning.coldHunt] is set and the low-power posture is off
     * ([lowPower], ADR 2026-10.d44b: a cold hunt is radio duty nobody asked for); never for a peer [ColdSighting.linked]
     * (or dialed), already hunted or wanted back ([ColdSighting.held]) or [ColdSighting.backingOff]; and only at or
     * above [floorDbm] on the 1M scale, the floor below which neither the ordinary dial nor [LonelyDialPolicy] dials it,
     * so a hunt can end in one. A hunt also needs the promotion that would dial the peer to have room for it
     * ([ColdSighting.promotes]: a free slot, or it beats the weakest held link by the hysteresis), else its windows buy
     * nothing. The per-peer cooldown and the phone-wide bounds are [ColdSightings.tryStart]'s, the last gate, so they are
     * spent only on an action taken.
     *
     * One-sided by nature: each phone acts on what it hears, so a pair where only one side hears the other cold acts on
     * that side alone (the dialed side's fast advert, or the dialing side's hunt of a slow one).
     */
    @Suppress("LongParameterList") // the hunt's inputs, each a different source in the transport
    fun coldHunt(
        localNodeId: String,
        sighting: ColdSighting,
        codedOn: Boolean,
        lowPower: Boolean,
        tuning: PhyTuning,
        floorDbm: Int = PromotionConfig.DEFAULT_RSSI_FLOOR_DBM,
        dialsBack: Boolean = drives(localNodeId, sighting.peerNodeId),
    ): ColdAction =
        when {
            !codedOn || !tuning.coldHunt || lowPower -> ColdAction.NONE
            sighting.linked || sighting.held || sighting.backingOff -> ColdAction.NONE
            sighting.smoothedRssi < floorDbm -> ColdAction.NONE
            !dialsBack -> ColdAction.FAST_ADVERT
            sighting.promotes -> ColdAction.HUNT
            else -> ColdAction.NONE
        }

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
    ): Boolean = snap != null && !(codedOn && codedOnly(snap.codedLagMs))

    /**
     * What the next presence scan window listens on. While the experiment runs, a phone with no link, or with an
     * unlinked peer heard on Coded alone, gives every other window to Coded entirely — an all-PHY window splits its
     * time, and a far peer's Coded hits are sparse — and never two in a row, so a legacy-only peer (an iPhone, a
     * controller without Coded) is still heard every other window. Otherwise every window is all-PHY.
     *
     * [farWanted] gives the same windows to a phone that holds links but is looking for somebody it cannot hear on 1M:
     * it [hunts], or another plane sees a peer it would dial that Bluetooth has not sighted (the scan's chase). Before
     * the 2026-10-04 amendment such a phone listened on Coded only in the slice an all-PHY window gives it, so a far
     * peer it had never heard on Coded alone stayed unheard.
     */
    fun scanPhys(
        codedOn: Boolean,
        alone: Boolean,
        codedOnlyUnlinked: Boolean,
        lastWasCoded: Boolean,
        farWanted: Boolean = false,
    ): ScanPhys =
        when {
            !codedOn -> ScanPhys.ONE_M
            (alone || codedOnlyUnlinked || farWanted) && !lastWasCoded -> ScanPhys.CODED
            else -> ScanPhys.ALL
        }

    /**
     * The average file-feed budget for this phone's L2CAP CoC links on 1M or 2M, shared by every one feeding a file at
     * once (NAN stays unbounded). A blob otherwise bursts into the BT-stack TX queue ahead of any later text frame and
     * saturates the ACL, so chat stalls until the transfer completes and the reverse direction is starved. Holding the
     * feed BELOW what the air drains keeps that queue shallow, so interleaved frames reach the wire promptly and reverse
     * traffic gets connection-event budget. One controller's air carries every link, so three feeds at once share one
     * budget rather than each taking it whole. 4 KiB/s sits under the slowest drain measured on 1M (4.8 KB/s across
     * three Pixel pairs, 2026-10-03), where 28 KiB/s left a text sent mid-photo waiting one to four minutes (#117, ADR
     * 2026-10.8jwn). Tune against the receiver's `rx …/… <N>B in <ms>ms` line (FileIntake): the sender's `file … in`
     * line times only the hand-off to the stack, so it reads size ÷ pace.
     */
    const val BLE_PACE_BYTES_PER_SEC = 4 * 1024

    /**
     * The budget of a link that has earned a short connection interval ([LinkBoostPolicy], #120). A link at
     * `CONNECTION_PRIORITY_HIGH` settles at 15 ms or less and drained 22.5–29 KB/s from the Pixel 9 (three packets in
     * flight, about two per event, 2026-10-06); 18 KiB/s is 85% of the slowest, and on that trial a 700 KB photo crossed
     * in 41–42 s with every text behind it in 3.4 s or less. It holds up to [BOOST_FULL_LE_LINKS] LE connections.
     */
    const val BOOST_PACE_BYTES_PER_SEC = 18 * 1024

    /**
     * The host splits the controller's LE buffers evenly across every LE connection, idle ones and other apps' included,
     * so a phone holding more of them keeps fewer of each link's packets in flight: the Pixel 9's 13 buffers gave 3 a
     * link over 4 connections and would give 1 over 7. One packet per 15 ms event drains about 16 KB/s, under the full
     * budget, so past [BOOST_FULL_LE_LINKS] a boosted link earns this instead (about 75% of that). A guess until the
     * trial's crowded-phone cell measures it (#120).
     */
    const val CROWDED_BOOST_PACE_BYTES_PER_SEC = 12 * 1024

    /** The LE connections the full boosted budget was measured at: the Pixel 9's mesh link, a ring and two others. */
    const val BOOST_FULL_LE_LINKS = 4

    /** The smallest chunk a 1M/2M feed takes, as small as the Coded default's. */
    private const val MIN_FILE_CHUNK_BYTES = 2048

    /** How many seconds of feed one chunk on a 1M/2M link carries: the Coded default's ratio, 2 KiB at 1 KiB/s. */
    private const val CHUNK_FEED_SECONDS = 2

    /**
     * The pace a file is fed to a Bluetooth link at, by the PHY the link was last read on. A link on 1M or 2M takes its
     * share of [PhyTuning.filePaceBytesPerSec]: the budget split evenly among the [feeding] links on 1M or 2M streaming
     * a file right now, this one included (ADR 2026-10.8jwn), in chunks of about [CHUNK_FEED_SECONDS] of that share, so a frame
     * queued mid-file waits about one chunk on the feed side. While this link has [earned] a short connection interval
     * (#120; each link its own since ADR 2026-10.g24x) the budget is [PhyTuning.boostPaceBytesPerSec] instead, or
     * [PhyTuning.crowdedBoostPaceBytesPerSec] on a phone holding more than [PhyTuning.boostFullLeLinks] LE connections
     * ([leLinks]), split the same way. A link on
     * Coded S=8 drains far slower, and at the 1M pace a whole photo reached the stack in seconds and every frame after it
     * waited minutes behind it (#114). So Coded takes [PhyTuning.codedPaceBytesPerSec] in [PhyTuning.codedChunkBytes]
     * chunks, outside the split, earned or not. A link not yet read (or with no PHY handle: the experiment off) is paced
     * as 1M. A feed to a peer that reports is clocked by its reports on any PHY ([BleFeedPolicy.windowed], ADR
     * 2026-10.5buf), and this pace is then its fallback for a receiver gone silent.
     */
    fun pace(
        phy: LinkPhy,
        tuning: PhyTuning,
        feeding: Int = 1,
        earned: Boolean = false,
        leLinks: Int = 1,
    ): PaceConfig =
        if (phy == LinkPhy.CODED) {
            PaceConfig(tuning.codedPaceBytesPerSec, tuning.codedChunkBytes.coerceIn(1, LinkFraming.FILE_CHUNK_BYTES))
        } else {
            filePace(budget(tuning, earned, leLinks), feeding)
        }

    /** The 1M/2M budget a set of feeds shares: the base, or the boosted one for this phone's LE connection count. */
    fun budget(
        tuning: PhyTuning,
        earned: Boolean,
        leLinks: Int,
    ): Int =
        when {
            !earned -> tuning.filePaceBytesPerSec
            leLinks <= tuning.boostFullLeLinks -> tuning.boostPaceBytesPerSec
            else -> tuning.crowdedBoostPaceBytesPerSec
        }

    /** One of [feeding] 1M/2M feeds sharing [budget] B/s, in chunks of [CHUNK_FEED_SECONDS] of its share; unbounded at ≤ 0. */
    private fun filePace(
        budget: Int,
        feeding: Int,
    ): PaceConfig {
        if (budget <= 0) return PaceConfig(0, LinkFraming.FILE_CHUNK_BYTES)
        val share = (budget / feeding.coerceAtLeast(1)).coerceAtLeast(1)
        val chunk = share.toLong() * CHUNK_FEED_SECONDS
        return PaceConfig(share, chunk.coerceIn(MIN_FILE_CHUNK_BYTES.toLong(), LinkFraming.FILE_CHUNK_BYTES.toLong()).toInt())
    }

    /**
     * Which side of a live link steps its PHY: the larger of the two node ids the HELLOs proved. The other only watches.
     * Real ids, never advertised forms, now and after the blinded advert (ADR 2026-11.xp6d): across an old↔new link the
     * old side reads only real ids, so judging one end on tags would have both ends step. Not who dials: between two
     * clear phones that is the same side, but who dials back or hunts after a drop is [dialsBack]'s, on forms.
     */
    fun drives(
        localNodeId: String,
        peerNodeId: String,
    ): Boolean = localNodeId > peerNodeId

    /**
     * Whether I dial [peerNodeId] back after a drop, or hunt it cold: the `dialsBack` of [fastAdvertAfterDrop],
     * [huntAfterDrop] and [coldHunt] (ADR 2026-11.xp6d, "Who dials"). [AdvertOrder.iDial] on my current form ([mine])
     * against the peer's last known advertised form ([theirs]), which is the classic order when that form was clear;
     * with no form known (a link never sighted, such as an inbound iPhone), the classic order on the real ids.
     */
    internal fun dialsBack(
        mine: AdvertOrder.Mine,
        theirs: AdvertOrder.Form?,
        localNodeId: String,
        peerNodeId: String,
    ): Boolean = theirs?.let { AdvertOrder.iDial(mine.current, it) } ?: drives(localNodeId, peerNodeId)

    /**
     * [dialsBack] at a link's drop: on the form the link recorded for its peer ([linkForm]: a sighting while linked, or
     * the HELLO's honoured `t`), else the one presence still holds ([heard]), else the classic order on the real ids. At
     * a drop at range (sparse Coded hits, a floored scan) presence has usually lost the peer, and a mixed clear↔blinded
     * pair read on the real ids would have nobody hunting usefully (bluetooth's part-1 review of ADR 2026-11.xp6d).
     */
    internal fun dialsBackAfterDrop(
        mine: AdvertOrder.Mine,
        linkForm: AdvertOrder.Form?,
        heard: AdvertOrder.Form?,
        localNodeId: String,
        peerNodeId: String,
    ): Boolean = dialsBack(mine, linkForm ?: heard, localNodeId, peerNodeId)
}

/**
 * One link's PHY steps: fed the controller's PHY reports ([onPhy]) and link-RSSI reads, asked what to request
 * ([decide]). Pure and clock-injected; thread-safe because the reports arrive on a binder thread.
 */
class PhyStepper(
    private val tuning: () -> PhyTuning,
) {
    /** Which step-up hold a link's smoothed RSSI is running: none, [PhyTuning.stepUpHoldMs] or [PhyTuning.stepUpFastHoldMs]. */
    enum class StepUpHold { NONE, SLOW, FAST }

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

    /** Step-downs to Coded within [PhyTuning.flapWindowMs] of the link's last step-up, over the link's life. */
    @get:Synchronized
    var reversals: Int = 0
        private set

    /**
     * The least time a link must have spent on Coded since its last step-down before AUTO steps it up (#169):
     * [PhyTuning.minSwitchGapMs] until the link has flapped — today's rule, which [decide]'s minimum gap already holds
     * — doubled by each reversal, up to [PhyTuning.stepUpBackoffMaxMs]. The step-up holds still apply on top, and may
     * run during it. Per link: a relink starts over.
     */
    @get:Synchronized
    val stepUpBackoffMs: Long get() = backoffMs(tuning(), backoffLevel)

    // The PHYs that answer the request in flight, null when none is.
    private var pending: Set<LinkPhy>? = null
    private var pendingAt = 0L
    private var lastSwitchAt: Long? = null
    private var weakReads = 0
    private var strongSince: Long? = null
    private var fastSince: Long? = null

    // When the link last moved off Coded, and last moved onto it (null while it opened there and never left).
    private var steppedUpAt: Long? = null
    private var steppedDownAt: Long? = null
    private var backoffLevel = 0

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
            if (reported == LinkPhy.CODED) {
                onSteppedDown(now)
            } else if (phy == LinkPhy.CODED) {
                steppedUpAt = now
            }
            switches++
            lastSwitchAt = now
            weakReads = 0
            strongSince = null
            fastSince = null
        }
        if (succeeded) phy = reported
        return changed
    }

    /**
     * The link moved onto Coded at [now]: a reversal if it left Coded less than [PhyTuning.flapWindowMs] ago, which
     * doubles the step-up backoff (up to its cap); otherwise the link held fast a while, and the backoff starts over.
     */
    private fun onSteppedDown(now: Long) {
        val t = tuning()
        val up = steppedUpAt
        if (up != null && now - up < t.flapWindowMs) {
            reversals++
            if (backoffLevel < MAX_BACKOFF_LEVEL && backoffMs(t, backoffLevel) < t.stepUpBackoffMaxMs) backoffLevel++
        } else {
            backoffLevel = 0
        }
        steppedDownAt = now
    }

    /**
     * A link-RSSI read came back: smoothed, and counted toward a step either way. A read that falls under
     * [PhyTuning.stepUpFastDbm] but not [PhyTuning.stepUpDbm] ends the fast hold and leaves the slow one running from
     * where it started. Returns the step-up hold the read moved the link into, or null when it stayed in the same one.
     */
    @Synchronized
    fun onRssi(
        rssi: Int,
        now: Long,
    ): StepUpHold? {
        val t = tuning()
        val before = hold()
        val s = smoothedRssi?.let { t.rssiAlpha * rssi + (1 - t.rssiAlpha) * it } ?: rssi.toDouble()
        smoothedRssi = s
        weakReads = if (s <= t.stepDownDbm) weakReads + 1 else 0
        strongSince = if (s >= t.stepUpDbm) strongSince ?: now else null
        fastSince = if (s >= t.stepUpFastDbm) fastSince ?: now else null
        return hold().takeIf { it != before }
    }

    private fun hold(): StepUpHold =
        when {
            fastSince != null -> StepUpHold.FAST
            strongSince != null -> StepUpHold.SLOW
            else -> StepUpHold.NONE
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

    /**
     * AUTO's step rule. A Coded link steps up after either hold: [PhyTuning.stepUpFastHoldMs] at
     * [PhyTuning.stepUpFastDbm] or stronger, [PhyTuning.stepUpHoldMs] at [PhyTuning.stepUpDbm]. [decide] runs once per
     * read (every 5 s above [PhyTuning.edgeDbm]), so a 10 s hold is met on the third strong read. A link that has
     * flapped waits out [stepUpBackoffMs] on Coded first (#169); the step-down never waits on it.
     */
    private fun autoTarget(
        t: PhyTuning,
        now: Long,
    ): Action? =
        when {
            phy != LinkPhy.CODED && weakReads >= t.stepDownReads -> Action.REQUEST_CODED
            phy == LinkPhy.CODED && heldStrong(t, now) && backedOff(t, now) -> Action.REQUEST_FAST
            else -> null
        }

    /** Whether the link has been on Coded for [stepUpBackoffMs] since its last step-down; true if it opened there. */
    private fun backedOff(
        t: PhyTuning,
        now: Long,
    ): Boolean = steppedDownAt == null || held(steppedDownAt, backoffMs(t, backoffLevel), now)

    private fun heldStrong(
        t: PhyTuning,
        now: Long,
    ): Boolean = held(strongSince, t.stepUpHoldMs, now) || held(fastSince, t.stepUpFastHoldMs, now)

    private fun held(
        since: Long?,
        holdMs: Long,
        now: Long,
    ): Boolean = since != null && now - since >= holdMs

    /** How long until the next link-RSSI read: tighter at the edge, where a walk-away has seconds to spare. */
    @Synchronized
    fun nextReadMs(): Long {
        val t = tuning()
        val s = smoothedRssi
        return if (s != null && s <= t.edgeDbm) t.edgeReadMs else t.readMs
    }

    private companion object {
        // Bounds the doublings: under a zero minimum gap (a debug tuning) the backoff never reaches its cap.
        const val MAX_BACKOFF_LEVEL = 16

        /** The step-up backoff at [level] reversals: the minimum gap, doubled per level, capped at the tuning's maximum. */
        fun backoffMs(
            t: PhyTuning,
            level: Int,
        ): Long =
            if (level == 0) {
                t.minSwitchGapMs
            } else {
                minOf(t.minSwitchGapMs shl level, maxOf(t.stepUpBackoffMaxMs, t.minSwitchGapMs))
            }
    }
}
