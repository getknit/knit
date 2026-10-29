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
    private val whys = mutableListOf<LinkWhy>()
    private val proofRounds = mutableListOf<Set<String>>()

    private fun TestScope.demand(
        rows: List<Pending>,
        active: () -> Boolean = { true },
        up: () -> Set<String> = { emptySet() },
        declinedAt: (String) -> Long? = { null },
        answer: suspend (String) -> LinkOutcome,
    ) = LinkDemand(
        pending = { since, until -> rows.also { windows += since to until } },
        offer = { peer, why ->
            offers += peer
            whys += why
            answer(peer)
        },
        up = up,
        lastUpAt = { null },
        radioLinked = { false },
        active = active,
        scope = backgroundScope,
        clock = { testScheduler.currentTime },
        prove = { peers -> proofRounds += peers },
        declinedAt = declinedAt,
    )

    @Test
    fun aRoundOffersSayingItsRowsWhyAndSkipsWhatADeclinedContactHasAlreadyRefused() =
        runTest {
            demand(listOf(Pending("bob", 0L, LinkWhy.FILE))) { LinkOutcome.UP }.round()
            runCurrent()
            assertEquals(listOf(LinkWhy.FILE), whys)
            demand(listOf(Pending("carol", 0L, LinkWhy.FILE)), declinedAt = { 1L }) { LinkOutcome.UP }.round()
            runCurrent()
            assertEquals("carol declined after that file", listOf("bob"), offers)
        }

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
            val d = demand(listOf(Pending("bob", 0L))) { LinkOutcome.NO_SIG }
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
    fun aRoundReadsAFilesWholeDayAndNothingWhileThePlaneIsOff() =
        runTest {
            var running = false
            val d = demand(listOf(Pending("bob", 0L)), active = { running }) { LinkOutcome.UP }
            advanceTo(LinkDemandPolicy.WINDOW_MS)
            d.round()
            assertTrue("the plane is off: nothing read", windows.isEmpty())
            running = true
            d.round()
            val now = testScheduler.currentTime
            // A file's day, up to now: a stuck attachment is due at once, so the policy, not the read, applies each row's
            // wait and window.
            assertEquals(listOf(now - LinkDemandPolicy.FILE_WINDOW_MS to now), windows)
        }

    @Test
    fun everyRoundAsksTheProofOverTheLinksUpEvenWithNothingToOffer() =
        runTest {
            var linked = emptySet<String>()
            var running = true
            val d = demand(emptyList(), active = { running }, up = { linked }) { LinkOutcome.UP }
            d.round()
            assertTrue("no link up, yet a proof round ran", proofRounds.isEmpty())
            linked = setOf("bob")
            d.round()
            d.round()
            // An up contact is never due, so the offer's early exits would have skipped it: the proof goes first.
            assertEquals(listOf(setOf("bob"), setOf("bob")), proofRounds)
            assertTrue(offers.isEmpty())
            running = false
            d.round()
            assertEquals("low-power mode or the plane off asks nothing", 2, proofRounds.size)
        }

    @Test
    fun aFailedProofRoundStillOffers() =
        runTest {
            val d =
                LinkDemand(
                    pending = { _, _ -> listOf(Pending("carol", 0L)) },
                    offer = { peer, _ ->
                        offers += peer
                        LinkOutcome.UP
                    },
                    up = { setOf("bob") },
                    lastUpAt = { null },
                    radioLinked = { false },
                    active = { true },
                    scope = backgroundScope,
                    clock = { testScheduler.currentTime },
                    prove = { error("the database went away") },
                )
            advanceTo(LinkDemandPolicy.TICK_WAIT_MS)
            d.round()
            runCurrent()
            assertEquals(listOf("carol"), offers)
        }

    private fun TestScope.advanceTo(t: Long) {
        testScheduler.advanceTimeBy(t - testScheduler.currentTime)
    }
}
