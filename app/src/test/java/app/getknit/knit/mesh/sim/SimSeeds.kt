package app.getknit.knit.mesh.sim

import kotlin.random.Random

/**
 * Every seeded draw in the simulator, keyed by what it is for. A draw is made from a generator of its own, seeded by a
 * hash of the run's seed, its [Purpose] and the ids it belongs to (a walker, a group, a pair, a leg), so a draw added for
 * one purpose never shifts another: a walker's formation offsets never move its group's route, and one more walker moves
 * no other walker's path.
 */
object SimSeeds {
    /** What a stream draws for. The code is part of the key: never renumber one. */
    enum class Purpose(
        val code: Long,
    ) {
        GROUP_ROUTE(1),
        FORMATION(2),
        GROUP_SIZES(3),
        CIRCLES(4),
        FRIEND_PICK(5),
        LONE_DMS(6),
    }

    /** A hash of [parts], in order: every input bit moves every output bit, so neighbouring keys draw unrelated streams. */
    fun key(vararg parts: Long): Long = parts.fold(0L) { h, v -> mix(h * PAIR_MIX + v) }

    /** The generator for [purpose]'s draws on [seed], for the thing [ids] name. */
    fun stream(
        seed: Long,
        purpose: Purpose,
        vararg ids: Long,
    ): Random = Random(key(seed, purpose.code, *ids))

    // SplitMix64's finaliser.
    private fun mix(x: Long): Long {
        var z = x + GOLDEN
        z = (z xor (z ushr 30)) * MIX_1
        z = (z xor (z ushr 27)) * MIX_2
        return z xor (z ushr 31)
    }

    private const val PAIR_MIX = 1_000_003L
    private const val GOLDEN = -0x61c8864680b583ebL
    private const val MIX_1 = -0x40a7b892e31b1a47L
    private const val MIX_2 = -0x6b2fb644ecceee15L
}
