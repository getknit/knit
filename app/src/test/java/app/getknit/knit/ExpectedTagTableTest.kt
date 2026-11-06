package app.getknit.knit

import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.bluetooth.AdvertAliases.ContactKey
import app.getknit.knit.mesh.bluetooth.ExpectedTagTable
import app.getknit.knit.mesh.crypto.BlindTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Random

/**
 * [ExpectedTagTable] (ADR 2026-11.xp6d): the window moves with the wall clock exactly as far as the clock moved, either
 * way, and a key two contacts list leaves each its own tags. Keys come from a seeded [Random]; both clocks are fixed
 * numbers the test passes in.
 */
class ExpectedTagTableTest {
    private val random = Random(285)
    private val now = 1_000_000L
    private val wall = 1_800_000_000_000L
    private val slot = BlindTag.slotOf(wall)
    private val bob = NodeId.derive("bob")
    private val carol = NodeId.derive("carol")
    private val mallory = NodeId.derive("mallory")

    private fun key(): ByteArray = ByteArray(BlindTag.KEY_BYTES).also(random::nextBytes)

    private val bobKey = key()
    private val carolKey = key()

    private fun form(
        key: ByteArray,
        atSlot: Long,
        restart: Int = 0,
        owner: String = bob,
    ): String = NodeId.fromBytes(BlindTag.tag(key, NodeId.toBytes(owner), atSlot, restart))

    private fun table(): ExpectedTagTable =
        ExpectedTagTable(previousKeyMaxMs = 7L * 24 * 3_600_000).also {
            it.setKeys(mapOf(bob to ContactKey(bobKey), carol to ContactKey(carolKey)), now, wall)
        }

    @Test
    fun aMultiSlotJumpForwardListsAndDropsExactlyWhatMoved() {
        val t = table()
        val size = t.size
        val hmacs = t.tagsComputed
        t.slide(wall + 5 * BlindTag.SLOT_MS)
        assertEquals("five slots per key, each all restarts", hmacs + 2 * 5 * BlindTag.RESTARTS, t.tagsComputed)
        assertEquals(2L * 5, t.slotSteps)
        assertEquals(size, t.size)
        for (gone in slot - 32..slot - 28) assertNull("slot $gone left", t.lookup(form(bobKey, gone), now))
        assertEquals(bob, t.lookup(form(bobKey, slot - 27), now)?.nodeId)
        for (entered in slot + 3..slot + 7) assertEquals("slot $entered entered", bob, t.lookup(form(bobKey, entered, 3), now)?.nodeId)
        assertNull(t.lookup(form(bobKey, slot + 8), now))
    }

    @Test
    fun aBackwardJumpListsTheOlderSlotsAndDropsTheAheadOnes() {
        val t = table()
        val hmacs = t.tagsComputed
        t.slide(wall - 3 * BlindTag.SLOT_MS)
        assertEquals(hmacs + 2 * 3 * BlindTag.RESTARTS, t.tagsComputed)
        for (back in slot - 35..slot - 33) {
            assertEquals("slot $back came back", carol, t.lookup(form(carolKey, back, owner = carol), now)?.nodeId)
        }
        for (ahead in slot..slot + 2) assertNull("slot $ahead now ahead of the window", t.lookup(form(carolKey, ahead, owner = carol), now))
        assertEquals(carol, t.lookup(form(carolKey, slot - 1, owner = carol), now)?.nodeId)
    }

    @Test
    fun aJumpPastTheWholeWindowRelistsItOnce() {
        val t = table()
        val size = t.size
        val hmacs = t.tagsComputed
        t.slide(wall + 100 * BlindTag.SLOT_MS)
        assertEquals(hmacs + size, t.tagsComputed)
        assertEquals(size, t.size)
        assertNull(t.lookup(form(bobKey, slot), now))
        assertEquals(bob, t.lookup(form(bobKey, slot + 100), now)?.nodeId)
        t.slide(wall + 100 * BlindTag.SLOT_MS)
        assertEquals("the same slot again lists nothing", hmacs + size, t.tagsComputed)
    }

    @Test
    fun aKeyAnotherContactListsLeavesEachFormItsOwnerWhateverTheOrder() {
        // Mallory sent Bob's presence key as her own: as her current key, or as the previous one behind a key of hers.
        for (mallorys in listOf(ContactKey(bobKey), ContactKey(key(), previous = bobKey))) {
            for ((first, second) in listOf(bob to mallory, mallory to bob)) {
                val listing = mapOf(bob to ContactKey(bobKey), mallory to mallorys)
                val t = ExpectedTagTable(previousKeyMaxMs = 7L * 24 * 3_600_000)
                t.setKeys(mapOf(first to listing.getValue(first)), now, wall)
                t.setKeys(mapOf(second to listing.getValue(second), first to listing.getValue(first)), now, wall)
                assertEquals(bob, t.lookup(form(bobKey, slot), now)?.nodeId)
                assertEquals(mallory, t.lookup(form(bobKey, slot, owner = mallory), now)?.nodeId)
                t.slide(wall + BlindTag.SLOT_MS)
                assertEquals("a slot the window gained", bob, t.lookup(form(bobKey, slot + 3), now)?.nodeId)
                assertEquals(mallory, t.lookup(form(bobKey, slot + 3, owner = mallory), now)?.nodeId)
                // Unlisting the copier leaves Bob's tags mapped to him.
                t.setKeys(mapOf(bob to ContactKey(bobKey)), now, wall + BlindTag.SLOT_MS)
                assertEquals(bob, t.lookup(form(bobKey, slot), now)?.nodeId)
                assertEquals(bob, t.lookup(form(bobKey, slot + 3), now)?.nodeId)
                assertNull(t.lookup(form(bobKey, slot, owner = mallory), now))
            }
        }
    }

    @Test
    fun oneKeyUnderTwoGenerationsOfAContactStaysResolvedThroughLaterListings() {
        // A fork: `PresenceKeyPolicy.receive` compares bytes only within one generation, so the store holds Bob's key twice.
        val other = key()
        val forked = mapOf(bob to ContactKey(listOf(bobKey, other, bobKey)), carol to ContactKey(carolKey))
        val t = ExpectedTagTable(previousKeyMaxMs = 7L * 24 * 3_600_000)
        t.setKeys(forked, now, wall)
        val resolved = {
            assertEquals(bob, t.lookup(form(bobKey, slot), now)?.nodeId)
            assertEquals(bob, t.lookup(form(bobKey, slot - 32, 2), now)?.nodeId)
        }
        resolved()
        val hmacs = t.tagsComputed
        t.setKeys(forked, now, wall)
        resolved()
        // Another contact's change, then Bob's own listing without the fork.
        t.setKeys(forked + (carol to ContactKey(key())), now, wall)
        resolved()
        t.setKeys(forked + (bob to ContactKey(listOf(bobKey, other))), now, wall)
        resolved()
        // Last, so a fork that darkened its own tags fails on staying resolved above, the claim this test makes.
        assertEquals("it stands at its newest place", true, t.lookup(form(bobKey, slot), now)?.current)
        assertEquals("Bob's two keys and Carol's two, each built once", 4L, t.builds)
        val oneKey = (BlindTag.WINDOW_PAST_SLOTS + 1 + BlindTag.WINDOW_AHEAD_SLOTS).toInt() * BlindTag.RESTARTS
        assertEquals("only Carol's new key listed", hmacs + oneKey, t.tagsComputed)
    }

    @Test
    fun everyKeyOfTheStoresListIsListedAndAHitLapsesOnlyTheOlderOnesAfterTheCap() {
        // The store's three keys, newest first: a next key gone ahead, the current one, the previous one (xp6d step 5a).
        val week = 7L * 24 * 3_600_000
        val next = key()
        val previous = key()
        val one = ExpectedTagTable(previousKeyMaxMs = week).also { it.setKeys(mapOf(bob to ContactKey(bobKey)), now, wall) }
        val t = ExpectedTagTable(previousKeyMaxMs = week)
        t.setKeys(mapOf(bob to ContactKey(listOf(next, bobKey, previous))), now, wall)
        assertEquals("all three listed", 3 * one.size, t.size)
        // A tag under the current key caps the previous one, never the next one.
        assertEquals(bob, t.lookup(form(bobKey, slot), now)?.nodeId)
        assertEquals("capped, not gone", bob, t.lookup(form(previous, slot), now)?.nodeId)
        t.lapse(now + week)
        assertNull("older than the one heard, past its cap", t.lookup(form(previous, slot), now + week))
        assertEquals("newer than the one heard", bob, t.lookup(form(next, slot), now + week)?.nodeId)
        // The same listing again keeps the lapsed key out, and costs no HMAC.
        val hmacs = t.tagsComputed
        t.setKeys(mapOf(bob to ContactKey(listOf(next, bobKey, previous))), now + week + 1, wall)
        assertNull(t.lookup(form(previous, slot), now + week + 1))
        assertEquals(hmacs, t.tagsComputed)
    }

    @Test
    fun aHitUnderTheNextKeyAloneLeavesTheCurrentKeyResolving() {
        // The security review of step 5a: another contact of Bob holds his next key and can air one tag under it.
        val week = 7L * 24 * 3_600_000
        val next = key()
        val t = ExpectedTagTable(previousKeyMaxMs = week)
        t.setKeys(mapOf(bob to ContactKey(listOf(next, bobKey))), now, wall)
        assertEquals(bob, t.lookup(form(next, slot), now)?.nodeId)
        assertEquals("Bob's own tag still resolves", bob, t.lookup(form(bobKey, slot), now + 1)?.nodeId)
        t.lapse(now + week - 1)
        assertEquals("until a key generation has passed", bob, t.lookup(form(bobKey, slot), now + week - 1)?.nodeId)
        // A second hit under the next key does not push the cap back.
        t.lookup(form(next, slot), now + week - 1)
        t.lapse(now + week)
        assertNull(t.lookup(form(bobKey, slot), now + week))
    }

    @Test
    fun aKeyTheStoresListDropsIsUnlistedAtOnceAndTheListIsNeverCappedByTime() {
        val next = key()
        val t = ExpectedTagTable(previousKeyMaxMs = 1_000)
        t.setKeys(mapOf(bob to ContactKey(listOf(next, bobKey))), now, wall)
        t.lapse(now + 10_000)
        assertEquals("the store's list is bounded by the store, not the cap", bob, t.lookup(form(bobKey, slot), now)?.nodeId)
        t.setKeys(mapOf(bob to ContactKey(listOf(next))), now + 20_000, wall)
        assertNull("dropped by the store: unlisted at once", t.lookup(form(bobKey, slot), now))
    }
}
