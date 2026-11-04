package app.getknit.knit

import app.getknit.knit.mesh.bluetooth.SideCarousel
import app.getknit.knit.mesh.bluetooth.SideCarousel.Drop
import app.getknit.knit.mesh.bluetooth.SideCarousel.Kind
import app.getknit.knit.mesh.bluetooth.SideCarousel.Offer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [SideCarousel] — the BLE side channel's page schedule, on a virtual clock. */
class SideCarouselTest {
    private var now = 1_000L
    private val drops = mutableListOf<Drop>()
    private val config =
        SideCarousel.Config(
            dwellMs = 1_000,
            lingerMs = 500,
            freshMs = 3_000,
            typingFreshMs = 800,
            capacity = 3,
            slots = 2,
        )
    private val carousel = SideCarousel({ now }, config) { drops += it }

    private var seq = 0

    private fun part(tag: String = "p${seq++}"): ByteArray = tag.toByteArray()

    private fun offer(
        parts: List<ByteArray>,
        kind: Kind = Kind.CONTENT,
        coalesceKey: String? = null,
        key: String = "k${seq++}",
    ): Offer = carousel.offer(parts, kind, coalesceKey, key)

    private fun tick(advanceMs: Long = 0): Boolean {
        now += advanceMs
        return carousel.tick()
    }

    @Test
    fun aQueuedFrameGoesOnAirOnTheNextTickAndStaysForItsDwell() {
        val p = part()
        assertEquals(Offer.QUEUED, offer(listOf(p)))
        assertTrue(tick())
        assertSame(p, carousel.pages()[0])
        assertNull(carousel.pages()[1])
        assertEquals(1, carousel.onAir())
        assertEquals(0, carousel.queued())
        // Nothing waiting: the part lingers past its dwell rather than the set going down…
        assertFalse(tick(config.dwellMs))
        assertSame(p, carousel.pages()[0])
        // …until the linger runs out.
        assertTrue(tick(config.lingerMs))
        assertNull(carousel.pages()[0])
        assertEquals(0, carousel.onAir())
    }

    @Test
    fun aWaitingFrameTakesTheSlotAsSoonAsTheDwellIsUp() {
        val first = List(3) { part() }
        first.forEach { offer(listOf(it)) }
        tick()
        assertEquals(listOf(first[0], first[1]), carousel.pages())
        assertEquals(1, carousel.queued())
        // Before the dwell: nothing moves, however much is waiting.
        assertFalse(tick(config.dwellMs - 1))
        // At the dwell both parts yield and the third goes up; the other slot stays empty.
        assertTrue(tick(1))
        assertSame(first[2], carousel.pages()[0])
        assertNull(carousel.pages()[1])
    }

    @Test
    fun aMultiPartFrameAirsTogetherAndAStartedFrameFinishesFirst() {
        val two = listOf(part(), part())
        offer(two)
        tick()
        assertEquals("both halves up at once", two, carousel.pages())
        // A three-part frame with two singles behind it: both singles go first (fewer parts), then the frame
        // takes both slots, and once started its last part comes before a newcomer offered meanwhile.
        val three = List(3) { part() }
        offer(three)
        val single = part()
        offer(listOf(single))
        val later = part()
        offer(listOf(later))
        tick(config.dwellMs)
        assertEquals(listOf(single, later), carousel.pages())
        tick(config.dwellMs)
        assertEquals(listOf(three[0], three[1]), carousel.pages())
        val newcomer = part()
        offer(listOf(newcomer))
        tick(config.dwellMs)
        assertEquals("the started frame finishes before the newcomer", listOf(three[2], newcomer), carousel.pages())
    }

    @Test
    fun fewerPartFramesGoFirstAndFifoBreaksTies() {
        val big = List(3) { part() }
        offer(big)
        val a = part()
        now += 1
        offer(listOf(a))
        now += 1
        val b = part()
        offer(listOf(b))
        tick()
        assertEquals("one-part frames first, in offer order", listOf(a, b), carousel.pages())
        tick(config.dwellMs)
        assertEquals(listOf(big[0], big[1]), carousel.pages())
    }

    @Test
    fun anUnstartedFrameGoesStale() {
        val two = List(2) { part() }
        offer(two)
        offer(listOf(part()))
        offer(listOf(part()))
        tick() // two singles up; the two-part frame waits
        assertEquals(1, carousel.queued())
        tick(config.freshMs)
        assertEquals(listOf(Drop.STALE), drops)
        assertEquals(0, carousel.queued())
    }

    @Test
    fun aStartedFrameNeverGoesStale() {
        val one = SideCarousel({ now }, config.copy(slots = 1)) { drops += it }
        val two = List(2) { part() }
        one.offer(two, Kind.CONTENT, null, "two")
        one.tick() // part 0 up; part 1 waits behind it, started
        assertEquals(1, one.queued())
        now += config.freshMs * 2
        one.tick()
        assertTrue("a started frame is never shed", drops.isEmpty())
        assertSame(two[1], one.pages()[0])
    }

    @Test
    fun aTypingCueCoalescesPerKey() {
        val a = part()
        assertEquals(Offer.QUEUED, offer(listOf(a), Kind.TYPING, "typing:alice"))
        val b = part()
        assertEquals("a queued cue is replaced by the fresher one", Offer.REPLACED, offer(listOf(b), Kind.TYPING, "typing:alice"))
        tick()
        assertSame(b, carousel.pages()[0])
        assertEquals("a cue already on air absorbs the next", Offer.COALESCED, offer(listOf(part()), Kind.TYPING, "typing:alice"))
        assertEquals("another sender's cue is its own", Offer.QUEUED, offer(listOf(part()), Kind.TYPING, "typing:bob"))
        assertEquals(1, carousel.queued())
    }

    @Test
    fun aTypingCueIsStaleSooner() {
        offer(listOf(part()))
        offer(listOf(part()))
        tick()
        offer(listOf(part()), Kind.TYPING, "typing:alice")
        tick(config.typingFreshMs)
        assertEquals(listOf(Drop.STALE), drops)
    }

    @Test
    fun theSameFrameOfferedTwiceIsADuplicate() {
        assertEquals(Offer.QUEUED, offer(listOf(part()), key = "same"))
        assertEquals(Offer.DUPLICATE, offer(listOf(part()), key = "same"))
        assertEquals(1, carousel.queued())
    }

    @Test
    fun overflowShedsTheOldestUnstartedFrameNeverOneOnAir() {
        val onAir = List(2) { part() }
        onAir.forEach { offer(listOf(it)) }
        tick()
        val queued = List(config.capacity) { part() }
        queued.forEach { assertEquals(Offer.QUEUED, offer(listOf(it))) }
        assertEquals(config.capacity, carousel.queued())
        assertEquals(Offer.QUEUED, offer(listOf(part())))
        assertEquals(listOf(Drop.OVERFLOW), drops)
        assertEquals(config.capacity, carousel.queued())
        assertEquals("the on-air parts are untouched", onAir, carousel.pages())
    }

    @Test
    fun overflowRefusesWhenEverythingQueuedHasStarted() {
        val tight = SideCarousel({ now }, config.copy(capacity = 1, slots = 1)) { drops += it }
        assertEquals(Offer.QUEUED, tight.offer(listOf(part(), part()), Kind.CONTENT, null, "a"))
        tight.tick() // part 0 up; the frame stays queued for part 1 and has started
        assertEquals(Offer.OVERFLOW, tight.offer(listOf(part()), Kind.CONTENT, null, "b"))
        assertTrue(drops.isEmpty())
    }

    @Test
    fun anUnusableSlotIsNeverFilledAndDropsWhatItHeld() {
        val a = part()
        val b = part()
        offer(listOf(a))
        offer(listOf(b))
        tick()
        assertEquals(listOf(a, b), carousel.pages())
        carousel.setSlotUsable(1, false)
        assertNull(carousel.pages()[1])
        offer(listOf(part()))
        tick(config.dwellMs)
        assertNull("still skipped", carousel.pages()[1])
        carousel.setSlotUsable(1, true)
        val d = part()
        offer(listOf(d))
        assertTrue(tick())
        assertSame(d, carousel.pages()[1])
    }

    @Test
    fun theDeadlineIsTheEarliestThingTheTickWouldDo() {
        assertNull(carousel.nextDeadlineMs())
        val a = part()
        offer(listOf(a))
        tick()
        assertEquals("nothing waiting: dwell + linger", now + config.dwellMs + config.lingerMs, carousel.nextDeadlineMs())
        offer(listOf(part()))
        assertEquals("something waiting: the dwell", now + config.dwellMs, carousel.nextDeadlineMs())
        offer(listOf(part()), Kind.TYPING, "typing:x")
        assertEquals("a typing cue's freshness comes sooner", now + config.typingFreshMs, carousel.nextDeadlineMs())
    }

    @Test
    fun clearEmptiesEverythingButKeepsTheSeenWindow() {
        offer(listOf(part()), key = "seen")
        tick()
        carousel.clear()
        assertEquals(listOf(null, null), carousel.pages())
        assertEquals(0, carousel.queued())
        assertEquals(Offer.DUPLICATE, offer(listOf(part()), key = "seen"))
    }

    @Test
    fun theVersionMovesOnlyWhenAPageDoes() {
        val v0 = carousel.version
        offer(listOf(part()))
        assertEquals("an offer alone changes no page", v0, carousel.version)
        tick()
        val v1 = carousel.version
        assertTrue(v1 > v0)
        tick(1)
        assertEquals(v1, carousel.version)
    }

    @Test
    fun aRotationTakesEveryPageOffAndDropsEveryStartedFrame() {
        // ADR 2026-11.xp6d's writer (step 5b): nothing on the air at the edge, and no part of a frame begun before it,
        // reaches the fresh sets. Frames never started stay queued.
        val b1 = part()
        val b2 = part()
        val b3 = part()
        val c = part()
        val d = part()
        offer(listOf(b1, b2, b3)) // a three-part frame: its parts share one fragment id
        offer(listOf(c))
        tick()
        assertEquals("the one-part frame first, then the first part of the three", listOf(c, b1), carousel.pages())
        offer(listOf(d)) // queued behind the started frame, never started
        val v = carousel.version
        assertEquals(2, carousel.rotate())
        assertTrue(carousel.version > v)
        assertEquals(listOf(Drop.ROTATED), drops)
        assertEquals(listOf<ByteArray?>(null, null), carousel.pages())
        tick()
        assertEquals("only the unstarted frame airs after the edge", listOf(d, null), carousel.pages())
    }

    @Test
    fun noFragmentedFrameAirsOnBothSidesOfARotation() {
        val parts = List(4) { part() }
        offer(parts)
        val before = HashSet<ByteArray>()
        tick()
        carousel.pages().filterNotNull().forEach(before::add)
        carousel.rotate()
        val after = HashSet<ByteArray>()
        repeat(10) {
            tick(config.dwellMs + config.lingerMs)
            carousel.pages().filterNotNull().forEach(after::add)
        }
        assertTrue("parts aired before the edge", before.isNotEmpty())
        assertTrue("no part of the frame airs after it", after.none { a -> parts.any { it === a } })
    }

    @Test
    fun aRotationWithNothingOnTheAirDropsNothing() {
        assertEquals(0, carousel.rotate())
        tick()
        assertEquals(0, carousel.onAir())
    }
}
