package app.getknit.knit.mesh.sim

import kotlin.math.ln
import kotlin.random.Random

/** One thing a walker does with the app, at a simulated time. */
sealed interface SimEvent {
    val atMs: Long
    val from: Int
    val body: String

    /** A DM to a friend the walker came with. */
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
 * What the walkers send, drawn ahead of the run so a seed fixes it: each walker DMs a friend from its own
 * group at random moments [dmMeanMs] apart on average, and posts in the room [roomMeanMs] apart on average.
 * Exponential gaps: a person does not message on a timer. Every body is unique, so a delivery is found by it.
 */
object Workload {
    fun schedule(
        seed: Long,
        groups: List<List<Int>>,
        durationMs: Long,
        dmMeanMs: Long,
        roomMeanMs: Long,
    ): List<SimEvent> {
        val random = Random(seed)
        val events = mutableListOf<SimEvent>()
        for (group in groups) {
            for (walker in group) {
                val friends = group - walker
                if (friends.isNotEmpty()) {
                    moments(random, dmMeanMs, durationMs).forEach { atMs ->
                        events += SimEvent.Dm(atMs, walker, friends.random(random), "dm w$walker @$atMs")
                    }
                }
                moments(random, roomMeanMs, durationMs).forEach { events += SimEvent.Room(it, walker, "room w$walker @$it") }
            }
        }
        return events.sortedBy { it.atMs }
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
