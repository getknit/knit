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
    fun anAttachmentNoSpoolWillTakeIsDueAtOnceAndForItsWholeDay() {
        val fresh = now - 1
        assertEquals(listOf("bob"), LinkDemandPolicy.due(listOf(Pending("bob", fresh, LinkWhy.FILE)), now, none, emptySet()))
        // The same row as a plain unticked message waits for the spool.
        assertTrue(LinkDemandPolicy.due(listOf(Pending("bob", fresh)), now, none, emptySet()).isEmpty())
        // Past a message's window, a file no spool carries is still due: nothing else will take it across.
        val hours = now - 6 * 60 * 60_000L
        assertEquals(listOf("bob"), LinkDemandPolicy.due(listOf(Pending("bob", hours, LinkWhy.FILE)), now, none, emptySet()))
        val edge = now - LinkDemandPolicy.FILE_WINDOW_MS
        assertEquals(listOf("bob"), LinkDemandPolicy.due(listOf(Pending("bob", edge, LinkWhy.FILE)), now, none, emptySet()))
        assertTrue(LinkDemandPolicy.due(listOf(Pending("bob", edge - 1, LinkWhy.FILE)), now, none, emptySet()).isEmpty())
    }

    @Test
    fun aFileOfTheirsWeWantIsDueAMinuteAfterItsMessageArrivedAndForItsDay() {
        val arrived = now - LinkDemandPolicy.WANT_WAIT_MS
        assertTrue(LinkDemandPolicy.due(listOf(Pending("bob", arrived + 1, LinkWhy.WANT)), now, none, emptySet()).isEmpty())
        val due = LinkDemandPolicy.offers(listOf(Pending("bob", arrived, LinkWhy.WANT)), now, none, emptySet())
        assertEquals(listOf("bob" to LinkWhy.WANT), due)
        val hours = now - 6 * 60 * 60_000L
        assertEquals(listOf("bob"), LinkDemandPolicy.due(listOf(Pending("bob", hours, LinkWhy.WANT)), now, none, emptySet()))
        val old = now - LinkDemandPolicy.FILE_WINDOW_MS - 1
        assertTrue(LinkDemandPolicy.due(listOf(Pending("bob", old, LinkWhy.WANT)), now, none, emptySet()).isEmpty())
    }

    @Test
    fun aContactThatDeclinedIsOfferedAgainOnlyForSomethingNewer() {
        val declined = now - 5_000L
        val before = Pending("bob", declined - 1, LinkWhy.FILE)
        assertTrue(LinkDemandPolicy.due(listOf(before), now, none, emptySet(), declinedAt = { declined }).isEmpty())
        // Sent the instant it declined is not newer than the decline.
        val same = Pending("bob", declined, LinkWhy.FILE)
        assertTrue(LinkDemandPolicy.due(listOf(same), now, none, emptySet(), declinedAt = { declined }).isEmpty())
        // A file sent after the decline asks once more; the contact's other rows go on resting.
        val after = Pending("bob", declined + 1, LinkWhy.FILE)
        val again = LinkDemandPolicy.offers(listOf(before, after), now, none, emptySet(), declinedAt = { declined })
        assertEquals(listOf("bob" to LinkWhy.FILE), again)
        // Another contact's rows never wait on bob's decline.
        val carol = Pending("carol", declined - 1, LinkWhy.FILE)
        val bobDeclined: (String) -> Long? = { if (it == "bob") declined else null }
        assertEquals(listOf("carol"), LinkDemandPolicy.due(listOf(before, carol), now, none, emptySet(), declinedAt = bobDeclined))
    }

    @Test
    fun aContactsOfferSaysWhyByItsLongestWaitingRow() {
        val rows = listOf(Pending("bob", waited - 1_000, LinkWhy.FILE), Pending("bob", waited - 9_000))
        assertEquals(listOf("bob" to LinkWhy.TICK), LinkDemandPolicy.offers(rows, now, none, emptySet()))
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
        val later = after.at + LinkDemandPolicy.TICK_WAIT_MS
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

    // ---- a slot for a contact that waits ----

    private val idleLong = now - LinkDemandPolicy.EVICT_IDLE_MS

    @Test
    fun withEverySlotTakenTheLinkIdleLongestGivesItsSlotToAContactThatWaits() {
        val waiting = listOf(Pending("carol", waited))
        val idle = mapOf("bob" to idleLong - 5_000, "dave" to idleLong)
        assertEquals("bob", LinkDemandPolicy.evict(waiting, now, none, up = setOf("bob", "dave"), idleSince = idle))
    }

    @Test
    fun noLinkGivesUpItsSlotWhileOneIsFreeOrNobodyWaits() {
        val idle = mapOf("bob" to idleLong - 5_000)
        assertEquals(null, LinkDemandPolicy.evict(listOf(Pending("carol", waited)), now, none, up = setOf("bob"), idleSince = idle))
        // Full, but the only rows are the linked contacts' own.
        assertEquals(null, LinkDemandPolicy.evict(listOf(Pending("bob", waited)), now, none, up = setOf("bob", "dave"), idleSince = idle))
    }

    @Test
    fun aLinkIdleUnderAMinuteOrHeldOrMovingAFileKeepsItsSlot() {
        val waiting = listOf(Pending("carol", waited))
        val fresh = mapOf("bob" to idleLong + 1, "dave" to idleLong + 1)
        assertEquals(null, LinkDemandPolicy.evict(waiting, now, none, up = setOf("bob", "dave"), idleSince = fresh))
        // A held link or one moving a file is not among the idle ones at all: only dave can give way.
        assertEquals("dave", LinkDemandPolicy.evict(waiting, now, none, up = setOf("bob", "dave"), idleSince = mapOf("dave" to idleLong)))
        assertEquals(null, LinkDemandPolicy.evict(waiting, now, none, up = setOf("bob", "dave"), idleSince = emptyMap()))
    }
}
