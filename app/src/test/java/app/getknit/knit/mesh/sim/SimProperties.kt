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
    const val DEFAULT_WALKERS = 12
    const val DEFAULT_MINUTES = 4
    const val MS_PER_MINUTE = 60_000L

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
        arrivalWindowMs = (System.getProperty(ARRIVAL)?.toLong() ?: 0L) * MS_PER_MINUTE,
        latp = System.getProperty(LATP)?.toDouble() ?: ParkConfig.DEFAULT_LATP,
        excursionsPerHour = System.getProperty(EXCURSIONS)?.toDouble() ?: ParkConfig.DEFAULT_EXCURSIONS_PER_HOUR,
        radio = System.getProperty(RADIO) ?: DiscRadio.NAME,
        motion = motion,
        groups = System.getProperty(GROUPS) ?: GroupSizes.FIXED,
        friends = System.getProperty(FRIENDS) ?: Friends.Group.name,
        awayShare = System.getProperty(AWAY_SHARE)?.toDouble() ?: Friends.Circle.DEFAULT_AWAY_SHARE,
    )

    private fun defaultFieldM(
        walkers: Int,
        motion: String,
    ): Double = if (motion == Motion.PARK) PARK_FIELD_M else FIELD_M * sqrt(walkers.toDouble() / DEFAULT_WALKERS)
}
