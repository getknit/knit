package app.getknit.knit.mesh.inet

import app.getknit.knit.mesh.inet.LinkDemandPolicy.Pending
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The demand's runner (`docs/DIRECT_LINKS.md` L-7-7): one offer per contact at a time, a refusal that sent nothing rests
 * its contact, and nothing is read while the link plane is not running.
 */
@OptIn(ExperimentalCoroutinesApi::class) // the test scheduler's clock and runCurrent
class LinkDemandTest {
    private val offers = mutableListOf<String>()
    private val windows = mutableListOf<Pair<Long, Long>>()

    private fun TestScope.demand(
        rows: List<Pending>,
        active: () -> Boolean = { true },
        answer: suspend (String) -> LinkOutcome,
    ) = LinkDemand(
        pending = { since, until -> rows.also { windows += since to until } },
        offer = { peer ->
            offers += peer
            answer(peer)
        },
        up = { emptySet() },
        lastUpAt = { null },
        radioLinked = { false },
        active = active,
        scope = backgroundScope,
        clock = { testScheduler.currentTime },
    )

    @Test
    fun aContactIsOfferedOnceWhileItsOfferIsInFlight() =
        runTest {
            val answered = CompletableDeferred<LinkOutcome>()
            val d = demand(listOf(Pending("bob", 0L)), answer = { answered.await() })
            advanceTo(LinkDemandPolicy.TICK_WAIT_MS)
            d.round()
            runCurrent()
            d.round()
            runCurrent()
            assertEquals(listOf("bob"), offers)
            // Settled on its own backoff (a punch that failed): the next round may offer again.
            answered.complete(LinkOutcome.NO_ANSWER)
            runCurrent()
            d.round()
            runCurrent()
            assertEquals(listOf("bob", "bob"), offers)
        }

    @Test
    fun aRefusalThatSentNothingRestsTheContact() =
        runTest {
            val d = demand(listOf(Pending("bob", 0L))) { LinkOutcome.NO_CAST }
            advanceTo(LinkDemandPolicy.TICK_WAIT_MS)
            d.round()
            runCurrent()
            d.round()
            runCurrent()
            assertEquals(listOf("bob"), offers)
            advanceTo(testScheduler.currentTime + LinkDemand.QUIET_MS)
            d.round()
            runCurrent()
            assertEquals(listOf("bob", "bob"), offers)
        }

    @Test
    fun aRoundReadsTheWholeWindowAndNothingWhileThePlaneIsOff() =
        runTest {
            var running = false
            val d = demand(listOf(Pending("bob", 0L)), active = { running }) { LinkOutcome.UP }
            advanceTo(LinkDemandPolicy.WINDOW_MS)
            d.round()
            assertTrue("the plane is off: nothing read", windows.isEmpty())
            running = true
            d.round()
            val now = testScheduler.currentTime
            // Up to now: a stuck attachment is due at once, so the policy, not the read, applies each row's wait.
            assertEquals(listOf(now - LinkDemandPolicy.WINDOW_MS to now), windows)
        }

    private fun TestScope.advanceTo(t: Long) {
        testScheduler.advanceTimeBy(t - testScheduler.currentTime)
    }
}
