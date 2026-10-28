package app.getknit.knit.mesh.inet

import app.getknit.knit.mesh.inet.LinkDemandPolicy.Pending
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The spool first, a link as the last resort (`docs/DIRECT_LINKS.md` L-7-7): who [LinkDemandPolicy.due] offers a link. */
class LinkDemandPolicyTest {
    private val now = 10_000_000L
    private val waited = now - LinkDemandPolicy.TICK_WAIT_MS
    private val none: (String) -> Boolean = { false }

    @Test
    fun aMessageIsDueOnlyOnceTheSpoolHasHadItsTickWait() {
        assertTrue(LinkDemandPolicy.due(listOf(Pending("bob", waited + 1)), now, none, emptySet()).isEmpty())
        assertEquals(listOf("bob"), LinkDemandPolicy.due(listOf(Pending("bob", waited)), now, none, emptySet()))
    }

    @Test
    fun aMessageOlderThanTheWindowIsTheSpoolsAlone() {
        val old = now - LinkDemandPolicy.WINDOW_MS - 1
        assertTrue(LinkDemandPolicy.due(listOf(Pending("bob", old)), now, none, emptySet()).isEmpty())
    }

    // Each clause on its own, with room under the cap and a neighbour still offered, so deleting it goes red.
    private val bobAndCarol = listOf(Pending("bob", waited), Pending("carol", waited))

    @Test
    fun aContactARadioReachesIsNotOffered() {
        assertEquals(listOf("carol"), LinkDemandPolicy.due(bobAndCarol, now, radioLinked = { it == "bob" }, up = emptySet(), maxLinks = 10))
    }

    @Test
    fun aContactALinkIsUpToIsNotOffered() {
        assertEquals(listOf("carol"), LinkDemandPolicy.due(bobAndCarol, now, none, up = setOf("bob"), maxLinks = 10))
    }

    @Test
    fun aContactAnOfferIsInFlightToIsNotOfferedAgain() {
        assertEquals(listOf("carol"), LinkDemandPolicy.due(bobAndCarol, now, none, emptySet(), offering = setOf("bob"), maxLinks = 10))
    }

    @Test
    fun aMessageSentBeforeTheLastLinkWasUpHadItsChance() {
        val linkEnded = waited + 1
        val before = Pending("bob", linkEnded - 2)
        assertTrue(LinkDemandPolicy.due(listOf(before), now, none, emptySet(), lastUpAt = { linkEnded }).isEmpty())
        // A message written after the link went, and waited on as long: offered again.
        val after = Pending("bob", linkEnded + 1)
        val later = after.sentAt + LinkDemandPolicy.TICK_WAIT_MS
        assertEquals(listOf("bob"), LinkDemandPolicy.due(listOf(before, after), later, none, emptySet(), lastUpAt = { linkEnded }))
    }

    @Test
    fun aContactRestingAfterARefusalWaitsItOut() {
        val rows = listOf(Pending("bob", waited))
        assertTrue(LinkDemandPolicy.due(rows, now, none, emptySet(), quietUntil = mapOf("bob" to now + 1)).isEmpty())
        assertEquals(listOf("bob"), LinkDemandPolicy.due(rows, now, none, emptySet(), quietUntil = mapOf("bob" to now)))
    }

    @Test
    fun theLongestWaitingGoFirstUpToTheLinkCap() {
        val rows =
            listOf(
                Pending("bob", waited - 3_000),
                Pending("carol", waited - 9_000),
                Pending("bob", waited - 1_000),
                Pending("dave", waited - 6_000),
            )
        assertEquals(listOf("carol", "dave"), LinkDemandPolicy.due(rows, now, none, emptySet()))
        // One link up already: room for one more, the longest-waiting.
        assertEquals(listOf("carol"), LinkDemandPolicy.due(rows, now, none, up = setOf("erin")))
        assertTrue(LinkDemandPolicy.due(rows, now, none, up = setOf("erin"), offering = setOf("frank")).isEmpty())
    }
}
