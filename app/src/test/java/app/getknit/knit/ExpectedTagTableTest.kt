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
 * way, and a tag two contacts' keys list resolves to nobody. Keys come from a seeded [Random]; both clocks are fixed
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
    ): String = NodeId.fromBytes(BlindTag.tag(key, atSlot, restart))

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
        for (gone in slot - 32..slot - 28) assertNull("slot $gone left", t.lookup(form(bobKey, gone)))
        assertEquals(bob, t.lookup(form(bobKey, slot - 27))?.nodeId)
        for (entered in slot + 3..slot + 7) assertEquals("slot $entered entered", bob, t.lookup(form(bobKey, entered, 3))?.nodeId)
        assertNull(t.lookup(form(bobKey, slot + 8)))
    }

    @Test
    fun aBackwardJumpListsTheOlderSlotsAndDropsTheAheadOnes() {
        val t = table()
        val hmacs = t.tagsComputed
        t.slide(wall - 3 * BlindTag.SLOT_MS)
        assertEquals(hmacs + 2 * 3 * BlindTag.RESTARTS, t.tagsComputed)
        for (back in slot - 35..slot - 33) assertEquals("slot $back came back", carol, t.lookup(form(carolKey, back))?.nodeId)
        for (ahead in slot..slot + 2) assertNull("slot $ahead now ahead of the window", t.lookup(form(carolKey, ahead)))
        assertEquals(carol, t.lookup(form(carolKey, slot - 1))?.nodeId)
    }

    @Test
    fun aJumpPastTheWholeWindowRelistsItOnce() {
        val t = table()
        val size = t.size
        val hmacs = t.tagsComputed
        t.slide(wall + 100 * BlindTag.SLOT_MS)
        assertEquals(hmacs + size, t.tagsComputed)
        assertEquals(size, t.size)
        assertNull(t.lookup(form(bobKey, slot)))
        assertEquals(bob, t.lookup(form(bobKey, slot + 100))?.nodeId)
        t.slide(wall + 100 * BlindTag.SLOT_MS)
        assertEquals("the same slot again lists nothing", hmacs + size, t.tagsComputed)
    }

    @Test
    fun aKeyTwoContactsHoldResolvesToNeitherWhateverTheOrder() {
        val t = table()
        val bobs = form(bobKey, slot)
        // Mallory sent Bob's presence key as her own, listed in a later call than his.
        t.setKeys(mapOf(bob to ContactKey(bobKey), carol to ContactKey(carolKey), mallory to ContactKey(bobKey)), now, wall)
        assertNull(t.lookup(bobs))
        t.slide(wall + BlindTag.SLOT_MS)
        assertNull("still nobody after the window moved", t.lookup(bobs))
        assertNull(t.lookup(form(bobKey, slot + 3)))
        assertEquals(3L, t.collisionHits)
        assertEquals((BlindTag.WINDOW_PAST_SLOTS + 1 + BlindTag.WINDOW_AHEAD_SLOTS).toInt() * BlindTag.RESTARTS, t.collided)
        // Unlisting the copier leaves Bob's tags mapped to him.
        t.setKeys(mapOf(bob to ContactKey(bobKey), carol to ContactKey(carolKey)), now, wall + BlindTag.SLOT_MS)
        assertEquals(bob, t.lookup(bobs)?.nodeId)
        assertEquals(bob, t.lookup(form(bobKey, slot + 3))?.nodeId)
        assertEquals(0, t.collided)
    }

    @Test
    fun aKeyListedAsAnothersPreviousCollidesToo() {
        val t = table()
        val bobs = form(bobKey, slot)
        // Mallory, listed in a later call, names Bob's key as her previous one.
        t.setKeys(
            mapOf(bob to ContactKey(bobKey), carol to ContactKey(carolKey), mallory to ContactKey(key(), previous = bobKey)),
            now,
            wall,
        )
        assertNull(t.lookup(bobs))
        assertNull(t.lookup(form(bobKey, slot - 32, 2)))
        assertEquals(carol, t.lookup(form(carolKey, slot))?.nodeId)
    }
}
