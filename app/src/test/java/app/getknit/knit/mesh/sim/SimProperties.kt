package app.getknit.knit.mesh.sim

import app.getknit.knit.mesh.lab.MeshLab
import java.io.File
import kotlin.math.sqrt

/**
 * The `-Pknit.meshSim…` properties an outing is shaped by (`app/build.gradle.kts` forwards them), read the same way by
 * the outing with stacks ([ParkWalkSimTest]) and the field alone ([FieldSimTest]), so one set of properties names one
 * crowd in both.
 */
internal object SimProperties {
    /** The seed; the simulator's tests run only when it is set. */
    const val SEED = "knit.meshSim"
    const val WALKERS = "knit.meshSimWalkers"
    const val MINUTES = "knit.meshSimMinutes"

    /** `phone` ([MeshLab.PHONE_INBOUND_SLOTS]) or a number: each router's inbound queue drops what finds it full. */
    const val INBOUND = "knit.meshSimInbound"

    /** Minutes to watch custody parity after the gather ([SimScenario.settleMs]). */
    const val SETTLE = "knit.meshSimSettleMinutes"

    /**
     * `false` ends the outing at the walk: a measurement with no gather, settle or oracle. `walk` has everyone walk to a
     * meeting point ([GatherBy.WALK]); unset, the gather lays its links by hand ([GatherBy.RING]).
     */
    const val GATHER = "knit.meshSimGather"

    /** The field's side in metres: 1,000 under `motion=park`, else grown with the crowd from [FIELD_M], when unset. */
    const val FIELD = "knit.meshSimFieldM"

    /** Under `motion=park`: minutes over which units arrive at their gates ([SimScenario.arrivalWindowMs]); 0 when unset. */
    const val ARRIVAL = "knit.meshSimArrivalMinutes"

    /** Under `motion=park`: LATP's exponent ([SimScenario.latp]). */
    const val LATP = "knit.meshSimLatp"

    /** Under `motion=park`: excursions a member of a group starts an hour ([SimScenario.excursionsPerHour]). */
    const val EXCURSIONS = "knit.meshSimExcursions"

    /** The mean seconds between one walker's DMs ([SimScenario.dmMeanMs]); 20 when unset, 0 for none. */
    const val DM_SECONDS = "knit.meshSimDmSeconds"

    /** The mean seconds between one walker's Nearby posts ([SimScenario.roomMeanMs]); 60 when unset, 0 for none. */
    const val ROOM_SECONDS = "knit.meshSimRoomSeconds"

    /** The mean seconds between one member's group-chat posts ([SimScenario.groupMeanMs]); none when unset. */
    const val GROUP_SECONDS = "knit.meshSimGroupSeconds"

    /** Messages each walker sends before the outing, as the boot links its party ([SimScenario.backlog]); none when unset. */
    const val BACKLOG = "knit.meshSimBacklog"

    /** The metres a link is dialled within ([SimScenario.connectM]); 25 when unset. */
    const val RANGE = "knit.meshSimRangeM"

    /** The metres a link holds to ([SimScenario.dropM]); [DROP_PER_RANGE] times [RANGE] when only that is set, else 35. */
    const val DROP = "knit.meshSimDropM"

    /** [PassBySimTest]'s sidewalk ([PassByScenario]): pairs, speed, the sidewalk's width, and what each walker brings. */
    const val PAIRS = "knit.meshSimPairs"
    const val SPEED = "knit.meshSimSpeed"
    const val LATERAL = "knit.meshSimLateralM"
    const val PASS_DMS = "knit.meshSimPassDms"
    const val PASS_PICTURES = "knit.meshSimPassPictures"

    /** Frames each walker carries into its pass for others, and how many contacts wrote them ([PassByScenario.load]). */
    const val PASS_LOAD = "knit.meshSimPassLoad"
    const val PASS_AUTHORS = "knit.meshSimPassAuthors"
    const val PICTURE_KB = "knit.meshSimPictureKb"

    /** Under `ble1m+nan`: the metres Wi-Fi Aware finds a peer within ([NanRadio]); the Bluetooth range when unset. */
    const val NAN_RANGE = "knit.meshSimNanRangeM"

    /** Under `ble1m+nan`: the share of Aware data paths that never form ([NanRadio.FAIL_SHARE] when unset). */
    const val NAN_FAIL = "knit.meshSimNanFail"

    /** Under `ble1m+nan`: the median ms Wi-Fi Aware takes to find a peer ([NanRadio.DISCOVER_MEDIAN_MS] when unset). */
    const val NAN_DISCOVER = "knit.meshSimNanDiscoverMs"

    /** Which [Radio] makes the links ([Radio.NAMES]); `disc` when unset. */
    const val RADIO = "knit.meshSimRadio"

    /** How the walkers move ([Motion.NAMES]); `rwp` when unset. */
    const val MOTION = "knit.meshSimMotion"

    /** The group sizes ([GroupSizes.of]): a number, `street`, or `fixed` (threes) when unset. */
    const val GROUPS = "knit.meshSimGroups"

    /** Whom the walkers DM ([Friends.NAMES]); `group` when unset. */
    const val FRIENDS = "knit.meshSimFriends"

    /** Under `circles`, the share of DMs that go to the circle's other groups (0 to 1); a half when unset. */
    const val AWAY_SHARE = "knit.meshSimAwayShare"

    /** A `.movements` trace to replay in place of a motion ([TraceMobility]): the field alone only. */
    const val TRACE = "knit.meshSimTrace"

    /** The directory the report goes to, in place of `build/mesh-sim` (relative to the app module). */
    const val OUT = "knit.meshSimOut"

    /** A word the report's file name ends in: the build or the radio a run measured, to tell two runs apart. */
    const val TAG = "knit.meshSimTag"

    /** A link holds this much further than it is dialled, when only [RANGE] is set: today's 35 m over 25 m. */
    const val DROP_PER_RANGE = 1.4
    const val DEFAULT_WALKERS = 12
    const val DEFAULT_MINUTES = 4
    const val MS_PER_MINUTE = 60_000L
    private const val MS_PER_SECOND = 1_000L

    /** Twelve walkers in 150 m square: about one other walker in dial range of each, on average. */
    const val FIELD_M = 150.0

    /** A sparse park: a square kilometre, where 48 walkers are about one in two hectares. */
    const val PARK_FIELD_M = 1_000.0

    fun seed(): Long = checkNotNull(System.getProperty(SEED)).toLong()

    fun walkers(): Int = System.getProperty(WALKERS)?.toInt() ?: DEFAULT_WALKERS

    fun minutes(): Int = System.getProperty(MINUTES)?.toInt() ?: DEFAULT_MINUTES

    /** The `-name` a report's file ends in, or nothing. */
    fun tag(): String = System.getProperty(TAG)?.let { "-$it" }.orEmpty()

    /** Where reports go, made if missing. */
    fun out(): File = File(System.getProperty(OUT) ?: "build/mesh-sim").apply { mkdirs() }

    /**
     * The outing the properties name. The field grows with the crowd, so a bigger run is the same park with more of it,
     * not a denser one, unless [fieldM] or [FIELD] says how big it is; a [Motion.PARK] park is [PARK_FIELD_M] across.
     */
    fun scenario(
        seed: Long,
        walkers: Int,
        minutes: Int,
        motion: String = System.getProperty(MOTION) ?: Motion.WAYPOINT,
        fieldM: Double = System.getProperty(FIELD)?.toDouble() ?: defaultFieldM(walkers, motion),
    ) = SimScenario(
        seed = seed,
        walkers = walkers,
        durationMs = minutes * MS_PER_MINUTE,
        fieldM = fieldM,
        inboundSlots = System.getProperty(INBOUND)?.let { if (it == "phone") MeshLab.PHONE_INBOUND_SLOTS else it.toInt() },
        settleMs = (System.getProperty(SETTLE)?.toLong() ?: 0L) * MS_PER_MINUTE,
        gather = System.getProperty(GATHER) != "false",
        gatherBy = if (System.getProperty(GATHER) == GatherBy.WALK) GatherBy.WALK else GatherBy.RING,
        radio = System.getProperty(RADIO) ?: DiscRadio.NAME,
        motion = motion,
        groups = System.getProperty(GROUPS) ?: GroupSizes.FIXED,
        friends = System.getProperty(FRIENDS) ?: Friends.Group.name,
        awayShare = System.getProperty(AWAY_SHARE)?.toDouble() ?: Friends.Circle.DEFAULT_AWAY_SHARE,
    ).let(::withPark).let(::withWorkload).let(::withRange)

    /**
     * The sidewalk the properties name ([PassBySimTest]). Its range defaults to [PassByScenario.DEFAULT_RANGE_M], a phone
     * in a pocket, not the outing's 25 m, and its radio to `ble1m`; [RANGE], [DROP] and [RADIO] set them as for an outing.
     */
    fun passBy(seed: Long): PassByScenario {
        val defaults = PassByScenario(seed)
        val range = System.getProperty(RANGE)?.toDouble() ?: defaults.connectM
        return defaults.copy(
            pairs = System.getProperty(PAIRS)?.toInt() ?: defaults.pairs,
            speedMps = System.getProperty(SPEED)?.toDouble() ?: defaults.speedMps,
            lateralM = System.getProperty(LATERAL)?.toDouble() ?: defaults.lateralM,
            dms = System.getProperty(PASS_DMS)?.toInt() ?: defaults.dms,
            pictures = System.getProperty(PASS_PICTURES)?.toInt() ?: defaults.pictures,
            load = System.getProperty(PASS_LOAD)?.toInt() ?: defaults.load,
            loadAuthors = System.getProperty(PASS_AUTHORS)?.toInt() ?: defaults.loadAuthors,
            pictureBytes = System.getProperty(PICTURE_KB)?.let { it.toInt() * PassByScenario.BYTES_PER_KB } ?: defaults.pictureBytes,
            radio = System.getProperty(RADIO) ?: defaults.radio,
            connectM = range,
            dropM = System.getProperty(DROP)?.toDouble() ?: (range * DROP_PER_RANGE),
            nanRangeM = System.getProperty(NAN_RANGE)?.toDouble(),
            nanFailShare = System.getProperty(NAN_FAIL)?.toDouble(),
            nanDiscoverMs = System.getProperty(NAN_DISCOVER)?.toLong(),
        )
    }

    /** [scenario] with the range [RANGE] and [DROP] set, as [scenario] applies them; its own when neither is. */
    fun withRange(
        scenario: SimScenario,
        rangeM: Double? = System.getProperty(RANGE)?.toDouble(),
        dropM: Double? = System.getProperty(DROP)?.toDouble(),
    ): SimScenario {
        val connect = rangeM ?: scenario.connectM
        val drop = dropM ?: if (rangeM != null) rangeM * DROP_PER_RANGE else scenario.dropM
        return scenario.copy(
            connectM = connect,
            dropM = drop,
            nanRangeM = System.getProperty(NAN_RANGE)?.toDouble() ?: scenario.nanRangeM,
            nanFailShare = System.getProperty(NAN_FAIL)?.toDouble() ?: scenario.nanFailShare,
            nanDiscoverMs = System.getProperty(NAN_DISCOVER)?.toLong() ?: scenario.nanDiscoverMs,
        )
    }

    // The park's visitors, as the properties shape them.
    private fun withPark(scenario: SimScenario) =
        scenario.copy(
            arrivalWindowMs = (System.getProperty(ARRIVAL)?.toLong() ?: 0L) * MS_PER_MINUTE,
            latp = System.getProperty(LATP)?.toDouble() ?: ParkConfig.DEFAULT_LATP,
            excursionsPerHour = System.getProperty(EXCURSIONS)?.toDouble() ?: ParkConfig.DEFAULT_EXCURSIONS_PER_HOUR,
        )

    // What the walkers send, and what they bring, as the properties set it; the scenario's own defaults when unset.
    private fun withWorkload(scenario: SimScenario) =
        scenario.copy(
            dmMeanMs = System.getProperty(DM_SECONDS)?.let { it.toLong() * MS_PER_SECOND } ?: scenario.dmMeanMs,
            roomMeanMs = System.getProperty(ROOM_SECONDS)?.let { it.toLong() * MS_PER_SECOND } ?: scenario.roomMeanMs,
            groupMeanMs = System.getProperty(GROUP_SECONDS)?.let { it.toLong() * MS_PER_SECOND } ?: scenario.groupMeanMs,
            backlog = System.getProperty(BACKLOG)?.toInt() ?: scenario.backlog,
        )

    private fun defaultFieldM(
        walkers: Int,
        motion: String,
    ): Double = if (motion == Motion.PARK) PARK_FIELD_M else FIELD_M * sqrt(walkers.toDouble() / DEFAULT_WALKERS)
}
