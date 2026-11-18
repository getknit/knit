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

    /** `false` ends the outing at the walk: a measurement with no gather, settle or oracle. */
    const val GATHER = "knit.meshSimGather"

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

    fun seed(): Long = checkNotNull(System.getProperty(SEED)).toLong()

    fun walkers(): Int = System.getProperty(WALKERS)?.toInt() ?: DEFAULT_WALKERS

    fun minutes(): Int = System.getProperty(MINUTES)?.toInt() ?: DEFAULT_MINUTES

    /** The `-name` a report's file ends in, or nothing. */
    fun tag(): String = System.getProperty(TAG)?.let { "-$it" }.orEmpty()

    /** Where reports go, made if missing. */
    fun out(): File = File(System.getProperty(OUT) ?: "build/mesh-sim").apply { mkdirs() }

    /**
     * The outing the properties name. The field grows with the crowd, so a bigger run is the same park with more of it,
     * not a denser one, unless [fieldM] says how big it is.
     */
    fun scenario(
        seed: Long,
        walkers: Int,
        minutes: Int,
        fieldM: Double = FIELD_M * sqrt(walkers.toDouble() / DEFAULT_WALKERS),
        motion: String = System.getProperty(MOTION) ?: Motion.WAYPOINT,
    ) = SimScenario(
        seed = seed,
        walkers = walkers,
        durationMs = minutes * MS_PER_MINUTE,
        fieldM = fieldM,
        inboundSlots = System.getProperty(INBOUND)?.let { if (it == "phone") MeshLab.PHONE_INBOUND_SLOTS else it.toInt() },
        settleMs = (System.getProperty(SETTLE)?.toLong() ?: 0L) * MS_PER_MINUTE,
        gather = System.getProperty(GATHER) != "false",
        radio = System.getProperty(RADIO) ?: DiscRadio.NAME,
        motion = motion,
        groups = System.getProperty(GROUPS) ?: GroupSizes.FIXED,
        friends = System.getProperty(FRIENDS) ?: Friends.Group.name,
        awayShare = System.getProperty(AWAY_SHARE)?.toDouble() ?: Friends.Circle.DEFAULT_AWAY_SHARE,
    )
}
