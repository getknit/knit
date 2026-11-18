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

    /** A post in the group chat of [party], one of [Workload.parties]: everyone the walker came with or knows. */
    data class Group(
        override val atMs: Long,
        override val from: Int,
        val party: Int,
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
 *
 * With [groupMeanMs] above 0, each member of a party of [GROUP_CHAT_MIN] or more ([parties]) also posts in its party's
 * group chat [groupMeanMs] apart on average, on a stream of its own, so turning group chats on moves no DM or room post.
 * A mean of 0 turns that kind off; for DMs or the room that redraws the other kind's moments, which share one stream.
 */
object Workload {
    /** The fewest people a group chat is made for: two have their DM. */
    const val GROUP_CHAT_MIN = 3

    fun schedule(
        seed: Long,
        groups: List<List<Int>>,
        durationMs: Long,
        dmMeanMs: Long,
        roomMeanMs: Long,
        friends: Friends = Friends.Group,
        groupMeanMs: Long = 0,
    ): List<SimEvent> {
        val random = Random(seed)
        val events = mutableListOf<SimEvent>()
        val away = awayFriends(groups, friends)
        for ((g, group) in groups.withIndex()) {
            for (walker in group) {
                val companions = group - walker
                val elsewhere = away[g] - walker
                if (dmMeanMs > 0 && (companions.isNotEmpty() || elsewhere.isNotEmpty())) {
                    events += dms(seed, walker, companions, elsewhere, friends, random, dmMeanMs, durationMs)
                }
                if (roomMeanMs > 0) {
                    moments(random, roomMeanMs, durationMs).forEach { events += SimEvent.Room(it, walker, "room w$walker @$it") }
                }
            }
        }
        if (groupMeanMs > 0) events += groupPosts(seed, parties(groups, friends), groupMeanMs, durationMs)
        return events.sortedBy { it.atMs }
    }

    /**
     * Everyone who knows each other before the outing: each circle's walkers under [Friends.Circle], else each walking
     * group. Every walker is in exactly one party. The boot makes each party acquainted, and each party of
     * [GROUP_CHAT_MIN] or more has one group chat.
     */
    fun parties(
        groups: List<List<Int>>,
        friends: Friends,
    ): List<List<Int>> = (friends as? Friends.Circle)?.circles?.map { circle -> circle.flatMap { groups[it] } } ?: groups

    /**
     * What [backlog] messages from each walker sent before the outing would be: each a DM to someone in its party, a room
     * post or a post in its party's group chat, with odds in proportion to the outing's own rates (a mean of 0 never
     * drawn). Bodies are unique and the times 0, since they are sent as the boot links each party.
     */
    fun backlog(
        seed: Long,
        parties: List<List<Int>>,
        backlog: Int,
        dmMeanMs: Long,
        roomMeanMs: Long,
        groupMeanMs: Long,
    ): List<SimEvent> {
        if (backlog <= 0) return emptyList()
        val events = mutableListOf<SimEvent>()
        for ((p, party) in parties.withIndex()) {
            val chat = party.size >= GROUP_CHAT_MIN
            for (walker in party) {
                val others = party - walker
                val dm = if (dmMeanMs > 0 && others.isNotEmpty()) 1.0 / dmMeanMs else 0.0
                val room = if (roomMeanMs > 0) 1.0 / roomMeanMs else 0.0
                val group = if (groupMeanMs > 0 && chat) 1.0 / groupMeanMs else 0.0
                val total = dm + room + group
                if (total <= 0) continue
                val random = SimSeeds.stream(seed, SimSeeds.Purpose.BACKLOG, walker.toLong())
                repeat(backlog) { n ->
                    val body = "backlog w$walker #$n"
                    val draw = random.nextDouble() * total
                    events +=
                        when {
                            draw < dm -> SimEvent.Dm(0, walker, others.random(random), body)
                            draw < dm + room -> SimEvent.Room(0, walker, body)
                            else -> SimEvent.Group(0, walker, p, body)
                        }
                }
            }
        }
        return events
    }

    // Each member of a party with a chat posts on its own process: one more party moves nobody else's posts.
    private fun groupPosts(
        seed: Long,
        parties: List<List<Int>>,
        groupMeanMs: Long,
        durationMs: Long,
    ): List<SimEvent.Group> =
        parties.withIndex().filter { it.value.size >= GROUP_CHAT_MIN }.flatMap { (p, party) ->
            party.flatMap { walker ->
                moments(SimSeeds.stream(seed, SimSeeds.Purpose.GROUP_CHAT, walker.toLong()), groupMeanMs, durationMs)
                    .map { SimEvent.Group(it, walker, p, "group w$walker @$it") }
            }
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
