package app.getknit.knit.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** The phone's TURN ranking (docs/SPOOL_PROTOCOL.md C-7.6-51, C-7.6-53, C-7.6-54). */
class TurnRankingTest {
    private var now = 1_000_000L
    private val ranking = TurnRanking(clock = { now })

    private val a = "a.example:3478"
    private val b = "b.example:3478"
    private val c = "c.example:3478"

    @Test
    fun withNoScoreTheSpoolsOrderStands() {
        assertEquals(listOf(a, b, c), ranking.rank(listOf(a, b, c)))
        assertEquals(a, ranking.best(listOf(a, b, c)))
    }

    @Test
    fun scoresWithinTenMillisecondsOfTheBestKeepTheSpoolsOrder() {
        ranking.record(a, 30)
        ranking.record(b, 21)
        ranking.record(c, 45)
        // b is best at 21; a at 30 is within 10 ms of it (21 + 10 = 31), so a keeps its place ahead of b.
        assertEquals(listOf(a, b, c), ranking.rank(listOf(a, b, c)))
        // 31 is the edge itself, still within.
        ranking.record(a, 31)
        assertEquals(listOf(a, b, c), ranking.rank(listOf(a, b, c)))
        // At 32 it is not, and the score decides.
        ranking.record(a, 32)
        assertEquals(listOf(b, a, c), ranking.rank(listOf(a, b, c)))
    }

    @Test
    fun answeredEntriesOutsideTheTieGoByScore() {
        ranking.record(a, 90)
        ranking.record(b, 60)
        ranking.record(c, 10)
        assertEquals(listOf(c, b, a), ranking.rank(listOf(a, b, c)))
    }

    @Test
    fun anEntryThatNeverAnsweredGoesAfterEveryOneThatDidInTheSpoolsOrder() {
        ranking.record(a, null)
        ranking.record(b, null)
        ranking.record(c, 200)
        assertEquals(listOf(c, a, b), ranking.rank(listOf(a, b, c)))
        // An entry not measured at all ranks with the silent ones, in the spool's order.
        assertEquals(listOf(c, a, "d.example:3478", b), ranking.rank(listOf(a, "d.example:3478", b, c)))
    }

    @Test
    fun aRefusedEntryGoesLastForFiveMinutes() {
        ranking.record(a, 10)
        ranking.record(b, 50)
        ranking.refuse(a)
        assertEquals(listOf(b, a), ranking.rank(listOf(a, b)))
        now += TurnRanking.REFUSED_MS - 1
        assertEquals(listOf(b, a), ranking.rank(listOf(a, b)))
        now += 1
        assertEquals(listOf(a, b), ranking.rank(listOf(a, b)))
    }

    @Test
    fun aNetworkChangeDropsTheScoresAndKeepsTheRefusal() {
        ranking.record(a, 10)
        ranking.record(b, 50)
        ranking.record(c, 5)
        ranking.refuse(c)
        ranking.networkChanged()
        assertNull(ranking.score(a))
        assertFalse(ranking.measured(b))
        // With no score, the spool's order, except that the refused one is still last.
        assertEquals(listOf(a, b, c), ranking.rank(listOf(c, a, b)))
    }

    @Test
    fun twoAnswersInOneTurnServerOut() {
        val first = server(a, hosts = listOf(a, b), asked = 0)
        val second = server(c, hosts = listOf(c), asked = 1)
        ranking.record(a, 80)
        ranking.record(c, 20)
        assertSame(second, ranking.choose(listOf(first, second)))
        // Within 10 ms of each other, the spool asked first wins.
        ranking.record(a, 25)
        assertSame(first, ranking.choose(listOf(first, second)))
    }

    @Test
    fun aMeasuredServerRanksBeforeAnUnmeasuredOneAcrossSpools() {
        val unmeasured = server(a, hosts = listOf(a), asked = 0)
        val measured = server(b, hosts = listOf(b), asked = 1)
        ranking.record(b, 400)
        assertSame(measured, ranking.choose(listOf(unmeasured, measured)))
    }

    @Test
    fun withMixedSpoolsTheOneThatNamesItsServersIsChosen() {
        val old = server(a, hosts = emptyList(), asked = 0)
        val named = server(b, hosts = listOf(b), asked = 1)
        assertSame(named, ranking.choose(listOf(old, named)))
        // With old spools only, there is nothing to choose: the call keeps what came.
        assertNull(ranking.choose(listOf(old, server(c, hosts = emptyList(), asked = 1))))
    }

    @Test
    fun theNextServerIsTheBestOtherThanTheRefusedOneOverEveryList() {
        val first = server(a, hosts = listOf(a, b), asked = 0)
        val second = server(c, hosts = listOf(c, a), asked = 1)
        ranking.record(a, 10)
        ranking.record(b, 90)
        ranking.record(c, 40)
        ranking.refuse(a)
        assertEquals(c, ranking.next(listOf(first, second), refused = a))
        assertEquals(3, ranking.named(listOf(first, second)))
        // A spool naming only the refused server has no next one.
        assertNull(ranking.next(listOf(server(a, hosts = listOf(a), asked = 0)), refused = a))
    }

    @Test
    fun thePinPutsItsSlotFirstWhateverItsScoreOrRefusal() {
        ranking.record(a, 10)
        ranking.record(b, 300)
        ranking.refuse(b)
        ranking.pinnedSlot = 2
        assertEquals(listOf(b, a), ranking.rank(listOf(a, b)))
        val first = server(a, hosts = listOf(a, b), asked = 0)
        val pinned = server(b, hosts = listOf(a, b), asked = 1)
        assertSame(pinned, ranking.choose(listOf(first, pinned)))
        // The next server after the pinned one's refusal is never the pinned one again.
        assertEquals(a, ranking.next(listOf(first, pinned), refused = b))
    }

    private fun server(
        entry: String,
        hosts: List<String>,
        asked: Int,
    ) = TurnServer(
        urls = listOf("turn:$entry?transport=udp", "turns:${entry.substringBefore(':')}:443?transport=tcp"),
        user = "1800000000:abc",
        pass = "secret",
        spoolHosts = hosts,
        asked = asked,
    )
}
