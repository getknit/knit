package app.getknit.knit

import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.bluetooth.ConnectBackoffEntry
import app.getknit.knit.mesh.bluetooth.DialPlan
import app.getknit.knit.mesh.bluetooth.HeldStreaks
import app.getknit.knit.mesh.bluetooth.ProvenDialStreak
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [HeldStreaks] alone (ADR 2026-09.hj4a's amendment of 2026-11-06). The churn it bounds, walked through the booking and the
 * link's end, is in [BlindReaderTest].
 */
class HeldStreaksTest {
    private val stranger = NodeId.derive("stranger")

    @Test
    fun aLinkClearsOnlyTheKeysItEarnedAndOnlyPastTheConfirmAge() {
        val held = Any()
        val heldRecord = DialPlan.InFlight(DialPlan.DialKind.ORDINARY, stranger, ADDRESS, linkedAtStart = true)
        val book = hashMapOf("earned" to ConnectBackoffEntry(streak = 2), "another address" to ConnectBackoffEntry(streak = 2))
        val streaks = HeldStreaks<Any>()
        streaks.onDialFailed(heldRecord, "earned") { held }
        streaks.ended(held, ProvenDialStreak.CONFIRM_BY_AGE_MS - 1, book)
        assertEquals("too young to clear, and forgotten", 2, book.size)
        streaks.ended(held, ProvenDialStreak.CONFIRM_BY_AGE_MS, book)
        assertEquals(2, book.size)
        streaks.onDialFailed(heldRecord, "earned") { held }
        streaks.ended(held, ProvenDialStreak.CONFIRM_BY_AGE_MS, book)
        assertEquals(setOf("another address"), book.keys)
    }

    @Test
    fun onlyADialThatBeganHeldEarnsAndOnlyWhileItsLinkStands() {
        val held = Any()
        val book = hashMapOf("key" to ConnectBackoffEntry(streak = 1))
        val streaks = HeldStreaks<Any>()
        val crossing = DialPlan.InFlight(DialPlan.DialKind.ORDINARY, stranger, ADDRESS, linkedAtStart = false)
        streaks.onDialFailed(crossing, "key") { held }
        streaks.onDialFailed(null, "key") { held }
        streaks.onDialFailed(crossing.copy(linkedAtStart = true), "key") { null }
        streaks.ended(held, ProvenDialStreak.CONFIRM_BY_AGE_MS, book)
        assertEquals("nothing earned", setOf("key"), book.keys)
    }

    @Test
    fun aReplacedLinkHandsWhatItEarnedToItsSuccessor() {
        val first = Any()
        val second = Any()
        val book = hashMapOf("key" to ConnectBackoffEntry(streak = 1))
        val streaks = HeldStreaks<Any>()
        streaks.onDialFailed(DialPlan.InFlight(DialPlan.DialKind.ORDINARY, stranger, ADDRESS, linkedAtStart = true), "key") { first }
        streaks.moved(first, second, fromHeldMs = 0)
        streaks.ended(first, ProvenDialStreak.CONFIRM_BY_AGE_MS, book)
        assertEquals("the peer is still held", setOf("key"), book.keys)
        streaks.ended(second, ProvenDialStreak.CONFIRM_BY_AGE_MS, book)
        assertEquals(emptySet<String>(), book.keys)
    }

    @Test
    fun aReplacementChainIsAgedWhole() {
        val held = DialPlan.InFlight(DialPlan.DialKind.ORDINARY, stranger, ADDRESS, linkedAtStart = true)
        val age = ProvenDialStreak.CONFIRM_BY_AGE_MS

        fun chain(
            firstMs: Long,
            secondMs: Long,
        ): Set<String> {
            val first = Any()
            val second = Any()
            val book = hashMapOf("key" to ConnectBackoffEntry(streak = 1))
            val streaks = HeldStreaks<Any>()
            streaks.onDialFailed(held, "key") { first }
            streaks.moved(first, second, firstMs)
            streaks.ended(second, secondMs, book)
            return book.keys
        }
        assertEquals("ten minutes, then a young successor", emptySet<String>(), chain(firstMs = 600_000, secondMs = 1))
        assertEquals("a chain under the age in total clears nothing", setOf("key"), chain(firstMs = age / 2, secondMs = age / 2 - 1))
    }

    @Test
    fun anAgeSurvivesEveryLinkOfAChain() {
        val links = List(3) { Any() }
        val book = hashMapOf("key" to ConnectBackoffEntry(streak = 1))
        val streaks = HeldStreaks<Any>()
        val held = DialPlan.InFlight(DialPlan.DialKind.ORDINARY, stranger, ADDRESS, linkedAtStart = true)
        streaks.onDialFailed(held, "key") { links[0] }
        streaks.moved(links[0], links[1], fromHeldMs = 600_000)
        streaks.moved(links[1], links[2], fromHeldMs = 1)
        streaks.ended(links[2], heldMs = 1, book)
        assertEquals("ten minutes, then two young successors", emptySet<String>(), book.keys)
    }

    @Test
    fun aLinkThatEarnedNothingStillPassesOnItsAge() {
        val first = Any()
        val second = Any()
        val book = hashMapOf("key" to ConnectBackoffEntry(streak = 1))
        val streaks = HeldStreaks<Any>()
        streaks.moved(first, second, fromHeldMs = 600_000)
        streaks.onDialFailed(DialPlan.InFlight(DialPlan.DialKind.ORDINARY, stranger, ADDRESS, linkedAtStart = true), "key") { second }
        streaks.ended(second, heldMs = 1, book)
        assertEquals(emptySet<String>(), book.keys)
    }

    @Test
    fun aLinkPastTheCapKeepsItsNewestKeysAndLeavesTheOldestToItsBackoff() {
        val held = Any()
        val record = DialPlan.InFlight(DialPlan.DialKind.ORDINARY, stranger, ADDRESS, linkedAtStart = true)
        val keys = (1..17).map { "key$it" }
        val book = keys.associateWithTo(HashMap()) { ConnectBackoffEntry(streak = 1) }
        val streaks = HeldStreaks<Any>()
        keys.forEach { key -> streaks.onDialFailed(record, key) { held } }
        streaks.ended(held, ProvenDialStreak.CONFIRM_BY_AGE_MS, book)
        assertEquals("the 17th landed, and the first stands uncleared", setOf("key1"), book.keys)
    }

    @Test
    fun aKeyFailedAgainCountsAsNewestAtTheCap() {
        val held = Any()
        val record = DialPlan.InFlight(DialPlan.DialKind.ORDINARY, stranger, ADDRESS, linkedAtStart = true)
        val keys = (1..17).map { "key$it" }
        val book = keys.associateWithTo(HashMap()) { ConnectBackoffEntry(streak = 1) }
        val streaks = HeldStreaks<Any>()
        keys.take(16).forEach { key -> streaks.onDialFailed(record, key) { held } }
        streaks.onDialFailed(record, "key1") { held }
        streaks.onDialFailed(record, "key17") { held }
        streaks.ended(held, ProvenDialStreak.CONFIRM_BY_AGE_MS, book)
        assertEquals("key1 was failed again, so key2 is the oldest", setOf("key2"), book.keys)
    }

    private companion object {
        const val ADDRESS = "5A:C4:2F:B3:C8:6D"
    }
}
