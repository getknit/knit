package app.getknit.knit.mesh.sim

import kotlin.math.ln
import kotlin.random.Random

/** One thing a walker does with the app, at a simulated time. */
sealed interface SimEvent {
    val atMs: Long
    val from: Int
    val body: String

    /** A DM to a friend: one the walker came with, or one of its circle's ([Friends]). */
    data class Dm(
        override val atMs: Long,
        override val from: Int,
        val to: Int,
        override val body: String,
    ) : SimEvent

    /** A post in the Nearby room, for whoever is around. */
    data class Room(
        override val atMs: Long,
        override val from: Int,
        override val body: String,
    ) : SimEvent
}

/**
 * What the walkers send, drawn ahead of the run so a seed fixes it: each walker DMs a friend at random moments
 * [dmMeanMs] apart on average, and posts in the room [roomMeanMs] apart on average. Exponential gaps: a person does
 * not message on a timer. Every body is unique, so a delivery is found by it.
 *
 * Who a DM goes to is [Friends]': under [Friends.Group] a friend from the walker's own group, under [Friends.Circle] one
 * from its circle's other groups at the circle's away share. A walker with companions DMs at the same moments under both,
 * whoever it DMs: its companion is drawn off the shared stream as before, and the circle's away picks, and the moments of
 * one with no companions, come from streams of their own.
 */
object Workload {
    fun schedule(
        seed: Long,
        groups: List<List<Int>>,
        durationMs: Long,
        dmMeanMs: Long,
        roomMeanMs: Long,
        friends: Friends = Friends.Group,
    ): List<SimEvent> {
        val random = Random(seed)
        val events = mutableListOf<SimEvent>()
        val away = awayFriends(groups, friends)
        for ((g, group) in groups.withIndex()) {
            for (walker in group) {
                val companions = group - walker
                val elsewhere = away[g] - walker
                if (companions.isNotEmpty() || elsewhere.isNotEmpty()) {
                    events += dms(seed, walker, companions, elsewhere, friends, random, dmMeanMs, durationMs)
                }
                moments(random, roomMeanMs, durationMs).forEach { events += SimEvent.Room(it, walker, "room w$walker @$it") }
            }
        }
        return events.sortedBy { it.atMs }
    }

    // One walker's DMs: each to a companion, or under a circle to its other groups at the away share.
    private fun dms(
        seed: Long,
        walker: Int,
        companions: List<Int>,
        elsewhere: List<Int>,
        friends: Friends,
        random: Random,
        dmMeanMs: Long,
        durationMs: Long,
    ): List<SimEvent.Dm> {
        val pick = SimSeeds.stream(seed, SimSeeds.Purpose.FRIEND_PICK, walker.toLong())
        val share = (friends as? Friends.Circle)?.awayShare ?: 0.0
        // One with no companions (only a circle gives it friends) draws its moments apart from the shared stream, so it
        // shifts nobody else's.
        val clock = if (companions.isEmpty()) SimSeeds.stream(seed, SimSeeds.Purpose.LONE_DMS, walker.toLong()) else random
        return moments(clock, dmMeanMs, durationMs).map { atMs ->
            // The companion is drawn off the shared stream as it always was, whether or not the DM goes away.
            val companion = if (companions.isEmpty()) null else companions.random(random)
            val away = elsewhere.isNotEmpty() && (companion == null || pick.nextDouble() < share)
            SimEvent.Dm(atMs, walker, if (away) elsewhere.random(pick) else checkNotNull(companion), "dm w$walker @$atMs")
        }
    }

    // Per group, the walkers of its circle's other groups: nobody under [Friends.Group].
    private fun awayFriends(
        groups: List<List<Int>>,
        friends: Friends,
    ): List<List<Int>> {
        val away = MutableList(groups.size) { emptyList<Int>() }
        if (friends is Friends.Circle) {
            for (circle in friends.circles) {
                for (g in circle) away[g] = (circle - g).flatMap { groups[it] }
            }
        }
        return away
    }

    // A Poisson process's arrival times inside the run. Two draws in one millisecond would share a body: skip the second.
    private fun moments(
        random: Random,
        meanMs: Long,
        durationMs: Long,
    ): List<Long> {
        val out = mutableListOf<Long>()
        var atMs = 0L
        while (true) {
            atMs += (-ln(1.0 - random.nextDouble()) * meanMs).toLong().coerceAtLeast(1)
            if (atMs >= durationMs) return out
            out += atMs
        }
    }
}
