package app.getknit.knit.mesh.sim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [PassBy]'s sidewalk and the range properties, without stacks. */
class PassByTest {
    private val sidewalk = PassBy.of(seed = 7, pairs = 5, dropM = 21.0)

    @Test
    fun positionsArePureAndSeeded() {
        val again = PassBy.of(seed = 7, pairs = 5, dropM = 21.0)
        val times = listOf(30_000L, 0L, 12_500L, 90_000L, 500L)
        for (walker in 0 until 10) {
            for (t in times) assertEquals(sidewalk.position(walker, t), again.position(walker, t))
        }
        val other = PassBy.of(seed = 8, pairs = 5, dropM = 21.0)
        assertTrue("another seed draws other speeds", (0 until 10).any { other.speedsMps[it] != sidewalk.speedsMps[it] })
    }

    @Test
    fun eachPairStartsOutOfReachAndCrosses() {
        for (pair in 0 until 5) {
            val (a, b) = sidewalk.walkers(pair)
            val depart = sidewalk.departsMs[pair]
            assertTrue("pair $pair starts beyond the drop", sidewalk.position(a, depart).distanceTo(sidewalk.position(b, depart)) > 21.0)
            val after = sidewalk.apartAfterMs(pair, 21.0)
            val east = sidewalk.position(a, after)
            val west = sidewalk.position(b, after)
            assertTrue("pair $pair has crossed: $east $west", east.x > west.x)
            assertTrue("pair $pair is apart again", east.distanceTo(west) >= 21.0 - EPSILON_M)
            val meet = depart + (after - depart) / 2
            assertTrue("pair $pair comes within reach", sidewalk.position(a, meet).distanceTo(sidewalk.position(b, meet)) < 21.0)
        }
    }

    @Test
    fun lanesNeverReachEachOther() {
        for (t in 0L..120_000L step 500L) {
            for (w in 0 until 10) {
                for (v in w + 1 until 10) {
                    if (w / 2 == v / 2) continue
                    val d = sidewalk.position(w, t).distanceTo(sidewalk.position(v, t))
                    assertTrue("walkers $w and $v of different pairs are $d m apart at $t", d > 21.0)
                }
            }
        }
    }

    @Test
    fun aDiscLinksEachPassForItsTimeInRange() {
        // One speed for all, no spread: each pair is in dial range for 2 × √(15² − 2²) m of closing at 2.6 m/s.
        val even = PassBy.of(seed = 3, pairs = 3, dropM = 21.0, speedMps = 1.3, spreadMps = 0.0)
        val radio = DiscRadio(connectM = 15.0, dropM = 21.0, dialMs = 0)
        val log = PairLog()
        var atMs = 0L
        while (atMs <= 120_000L) {
            log.record(atMs, radio.step(atMs, (0 until 6).map { even.position(it, atMs) }))
            atMs += TICK_MS
        }
        val contacts = log.contacts(atMs)
        assertEquals("one contact a pair, none across lanes", setOf(Link(0, 1), Link(2, 3), Link(4, 5)), contacts.keys)
        // In range from 15 m closing to 21 m opening: √(15² − 2²) + √(21² − 2²) metres of closing at 2.6 m/s.
        val expectedMs = (StrictMath.sqrt(15.0 * 15 - 4) + StrictMath.sqrt(21.0 * 21 - 4)) / 2.6 * 1_000
        for ((link, list) in contacts) {
            val contact = list.single()
            assertEquals("link $link holds for its time in range", expectedMs, contact.lengthMs.toDouble(), 2.0 * TICK_MS)
        }
    }

    @Test
    fun rangeDefaultsToTheOutingsAndTheDropFollowsTheRange() {
        val base = SimScenario(seed = 1, walkers = 2, durationMs = 0, fieldM = 0.0)
        val unset = SimProperties.withRange(base, rangeM = null, dropM = null)
        assertEquals(25.0, unset.connectM, 0.0)
        assertEquals(35.0, unset.dropM, 0.0)
        val ranged = SimProperties.withRange(base, rangeM = 15.0, dropM = null)
        assertEquals(15.0, ranged.connectM, 0.0)
        assertEquals(21.0, ranged.dropM, EPSILON_M)
        val both = SimProperties.withRange(base, rangeM = 15.0, dropM = 18.0)
        assertEquals(18.0, both.dropM, 0.0)
    }

    private companion object {
        const val TICK_MS = 500L
        const val EPSILON_M = 1e-9
    }
}
