package app.getknit.knit

import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.bluetooth.AdvertAliases.ContactKey
import app.getknit.knit.mesh.bluetooth.ExpectedTagTable
import app.getknit.knit.mesh.bluetooth.TagSchedule
import app.getknit.knit.mesh.crypto.BlindTag
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.security.SecureRandom
import java.util.Random

/**
 * [TagSchedule] (ADR 2026-11.xp6d): no (slot, restart) is ever aired twice under one key, whatever the wall clock does,
 * across bring-ups and process restarts; a random fallback carries the prefix and resolves to nobody. Every random
 * draw comes from a seeded source.
 */
class TagScheduleTest {
    /** A [SecureRandom] whose bytes are a seeded [Random]'s: deterministic, for tests only. */
    private class SeededRandom(
        seed: Long,
    ) : SecureRandom() {
        private val inner = Random(seed)

        override fun nextBytes(bytes: ByteArray) = inner.nextBytes(bytes)
    }

    /** The persisted marks, shared by every schedule a test builds over it, as local storage is across restarts. */
    private class Store : TagSchedule.MarkStore {
        val marks = HashMap<Long, TagSchedule.Mark>()
        var reads = 0
        var failing = false

        override fun read(generation: Long): TagSchedule.Mark? {
            reads++
            if (failing) throw IOException("read")
            return marks[generation]
        }

        override fun write(
            generation: Long,
            mark: TagSchedule.Mark,
        ) {
            if (failing) throw IOException("write")
            marks[generation] = mark
        }
    }

    private val key = ByteArray(BlindTag.KEY_BYTES) { it.toByte() }
    private val wall = 1_800_000_000_000L
    private val slot = BlindTag.slotOf(wall)
    private val store = Store()

    private fun schedule(seed: Long = 1) = TagSchedule(store, SeededRandom(seed))

    /** Every tag aired, as a list, so a repeat shows as a duplicate. */
    private val aired = ArrayList<List<Byte>>()

    private fun air(
        s: TagSchedule,
        wallMs: Long,
        generation: Long = 1,
        under: ByteArray = key,
    ): TagSchedule.Next = s.next(under, generation, wallMs).also { aired += it.tag.toList() }

    private fun assertNoRepeat() = assertEquals("no tag aired twice", aired.size, aired.toSet().size)

    @Test
    fun aNewGenerationStartsAtTheCurrentSlot() {
        val next = air(schedule(), wall)
        assertTrue(next.resolvable)
        assertArrayEquals(BlindTag.tag(key, slot, 0), next.tag)
        assertEquals(TagSchedule.Mark(slot, 0), store.marks[1])
        assertArrayEquals("a newer generation has no mark of its own", BlindTag.tag(key, slot, 0), air(schedule(), wall, 2).tag)
    }

    @Test
    fun fiveBringUpsInOneSlotNeverRepeat() {
        val s = schedule()
        val nexts = List(5) { air(s, wall + it * 1_000L) }
        assertEquals(listOf(true, true, true, true, false), nexts.map { it.resolvable })
        for (restart in 0 until BlindTag.RESTARTS) assertArrayEquals(BlindTag.tag(key, slot, restart), nexts[restart].tag)
        assertNoRepeat()
        assertTrue("the next slot resolves again", air(s, wall + BlindTag.SLOT_MS).resolvable)
    }

    @Test
    fun aClockSteppingBackAndForthAcrossSlotEdgesNeverRepeats() {
        val s = schedule()
        val steps = listOf(0L, 1L, 2L, 1L, 0L, -3L, 2L, 2L, 3L, 1L, 3L, 4L, 2L, 5L)
        val nexts = steps.map { air(s, wall + it * BlindTag.SLOT_MS + 1) }
        assertNoRepeat()
        // Behind the mark it falls back; once the clock passes the mark it resolves again.
        assertFalse("slot 1 after slot 2 was aired", nexts[3].resolvable)
        assertTrue("slot 3 is above every mark so far", nexts[8].resolvable)
        assertTrue(nexts.last().resolvable)
    }

    @Test
    fun aProcessRestartOverTheSameStoreNeverRepeats() {
        air(schedule(seed = 1), wall)
        air(schedule(seed = 2), wall)
        air(schedule(seed = 3), wall + 60_000)
        air(schedule(seed = 4), wall - BlindTag.SLOT_MS)
        air(schedule(seed = 5), wall)
        air(schedule(seed = 6), wall)
        assertNoRepeat()
        assertEquals(TagSchedule.Mark(slot, 3), store.marks[1])
    }

    @Test
    fun aRandomFallbackCarriesThePrefixAndResolvesToNobody() {
        val s = schedule()
        repeat(BlindTag.RESTARTS) { air(s, wall) }
        val fallback = air(s, wall)
        assertFalse(fallback.resolvable)
        assertTrue(BlindTag.hasPrefix(fallback.tag))
        val table = ExpectedTagTable(previousKeyMaxMs = 1_000)
        table.setKeys(mapOf(NodeId.derive("me") to ContactKey(key)), nowMs = 0, wallMs = wall)
        assertNull(table.lookup(NodeId.fromBytes(fallback.tag), 0L))
        assertEquals(NodeId.derive("me"), table.lookup(NodeId.fromBytes(aired.first().toByteArray()), 0L)?.nodeId)
        assertFalse("the diagnostics name no tag", fallback.toString().contains(NodeId.fromBytes(fallback.tag).substring(4)))
    }

    @Test
    fun aMarkLeftInTheFutureIsFlaggedStaleAndANewGenerationStartsNow() {
        for ((i, pushed) in listOf(DAY_MS, 30 * DAY_MS).withIndex()) {
            // Each run its own key generation and key, as two generations never share a key.
            val generation = 10L * (i + 1)
            val old = ByteArray(BlindTag.KEY_BYTES) { (i + 1).toByte() }
            val s = schedule(seed = 10L + i)
            air(s, wall, generation, old)
            assertTrue(air(s, wall + pushed, generation, old).resolvable)
            val back = air(s, wall + 60_000, generation, old)
            assertTrue("the clock came back: the mark is stale", back.stale)
            assertFalse(back.resolvable)
            assertTrue(BlindTag.hasPrefix(back.tag))
            // The wiring starts a new key generation: no mark, so it starts at the current slot.
            val fresh = ByteArray(BlindTag.KEY_BYTES) { (i + 100).toByte() }
            val next = air(s, wall + 60_000, generation + 1, fresh)
            assertTrue(next.resolvable)
            assertFalse(next.stale)
            assertArrayEquals(BlindTag.tag(fresh, slot, 0), next.tag)
        }
        assertNoRepeat()
        val s = schedule()
        air(s, wall, 99)
        assertFalse("a mark within the window's lead is not stale", air(s, wall - BlindTag.SLOT_MS, 99).stale)
    }

    @Test
    fun theStaleEdgeIsTheWindowsAheadEdge() {
        // A mark exactly WINDOW_AHEAD_SLOTS past the clock is still inside a contact's window: not stale. One more is.
        val lead = BlindTag.WINDOW_AHEAD_SLOTS
        val s = schedule()
        air(s, wall + (lead + 1) * BlindTag.SLOT_MS, 1)
        assertFalse("exactly the lead ahead", air(s, wall + BlindTag.SLOT_MS, 1).stale)
        air(s, wall + (lead + 1) * BlindTag.SLOT_MS, 2)
        assertTrue("one past the lead", air(s, wall, 2).stale)
    }

    @Test
    fun theAnnouncedTagIsTheOneAiredAtTheRotation() {
        val s = schedule()
        air(s, wall)
        val announced = s.reserve(key, 1, wall)
        assertArrayEquals(BlindTag.tag(key, slot + 1, 0), announced.tag)
        val bringUp = air(s, wall + 1_000)
        assertFalse("a bring-up inside the announcement lead airs a random tag", bringUp.resolvable)
        val rotation = s.take(1, wall + BlindTag.SLOT_MS)
        assertNotNull(rotation)
        assertArrayEquals("announce, rotate, same bytes", announced.tag, rotation?.tag)
        aired += announced.tag.toList()
        assertNull("taken once", s.take(1, wall + BlindTag.SLOT_MS))
        assertArrayEquals(BlindTag.tag(key, slot + 1, 1), air(s, wall + BlindTag.SLOT_MS).tag)
        assertNoRepeat()
    }

    @Test
    fun aFailingStoreAirsNothing() {
        val s = schedule()
        air(s, wall)
        store.failing = true
        assertTrue(runCatching { s.next(key, 1, wall + BlindTag.SLOT_MS) }.exceptionOrNull() is IOException)
        assertTrue("an unread generation fails on its read", runCatching { s.next(key, 2, wall) }.exceptionOrNull() is IOException)
        store.failing = false
        assertEquals("the failed write left the mark", TagSchedule.Mark(slot, 0), store.marks[1])
        assertArrayEquals(BlindTag.tag(key, slot + 1, 0), air(s, wall + BlindTag.SLOT_MS).tag)
    }

    @Test
    fun theStoreIsReadOncePerGeneration() {
        val s = schedule()
        repeat(3) { air(s, wall + it * BlindTag.SLOT_MS) }
        assertEquals(1, store.reads)
    }

    @Test
    fun onlyTheFirstAnnouncementForASlotReserves() {
        val s = schedule()
        val first = s.reserve(key, 1, wall)
        val again = s.reserve(key, 1, wall + 20_000)
        assertArrayEquals("a late link inside the lead is told the same tag", first.tag, again.tag)
        assertEquals("one restart spent", TagSchedule.Mark(slot + 1, 0), store.marks[1])
    }

    @Test
    fun anAnnouncementForALaterSlotReplacesTheHeldOne() {
        val s = schedule()
        val stale = s.reserve(key, 1, wall)
        val later = s.reserve(key, 1, wall + BlindTag.SLOT_MS)
        assertArrayEquals("the later slot's own tag, not the held one", BlindTag.tag(key, slot + 2, 0), later.tag)
        val taken = s.take(1, wall + 2 * BlindTag.SLOT_MS)
        assertArrayEquals(later.tag, taken?.tag)
        assertFalse("the replaced reservation is never aired", stale.tag.contentEquals(taken?.tag))
    }

    @Test
    fun aReservationTakenLateIsDroppedAndNextAirsAnUnusedPair() {
        val s = schedule()
        val announced = s.reserve(key, 1, wall)
        assertArrayEquals("taken in its slot", announced.tag, s.take(1, wall + BlindTag.SLOT_MS)?.tag)
        val late = s.reserve(key, 1, wall + BlindTag.SLOT_MS)
        assertNull("taken a slot late: dropped", s.take(1, wall + 3 * BlindTag.SLOT_MS))
        assertNull("and gone", s.take(1, wall + 2 * BlindTag.SLOT_MS))
        val next = air(s, wall + 3 * BlindTag.SLOT_MS)
        assertArrayEquals(BlindTag.tag(key, slot + 3, 0), next.tag)
        aired += announced.tag.toList()
        aired += late.tag.toList()
        assertNoRepeat()
    }

    private companion object {
        const val DAY_MS = 24 * 3_600_000L
    }

    @Test
    fun droppedReservationsAreNeverTaken() {
        // Step 5b: a new own key, and adapter OFF, drop every reservation on the worker step that sees them.
        val s = schedule()
        val reserved = s.reserve(key, 1, wall)
        s.dropReservations()
        assertNull(s.take(1, wall + BlindTag.SLOT_MS))
        assertEquals("the reservation's mark stays written, so nothing repeats", TagSchedule.Mark(slot + 1, 0), store.marks[1])
        assertFalse(s.next(key, 1, wall + BlindTag.SLOT_MS).tag.contentEquals(reserved.tag))
    }

    @Test
    fun aRandomTagCarriesThePrefixAndWritesNoMark() {
        val r = schedule().random()
        assertFalse(r.resolvable)
        assertFalse(r.stale)
        assertTrue(BlindTag.hasPrefix(r.tag))
        assertTrue(store.marks.isEmpty())
    }
}
