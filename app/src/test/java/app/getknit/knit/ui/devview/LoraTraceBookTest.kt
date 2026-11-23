package app.getknit.knit.ui.devview

import app.getknit.knit.mesh.lora.BoardKind
import app.getknit.knit.mesh.lora.HeardRadio
import app.getknit.knit.mesh.lora.LoraTraceEvent
import app.getknit.knit.mesh.lora.TraceOutcome
import app.getknit.knit.mesh.lora.TraceReason
import app.getknit.knit.mesh.lora.TraceRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The developer view's LoRa trace (ADR 2026-10.s7wg, its slice-3 amendment): nothing kept while the view is off, off empties
 * it, and its bounds hold against whoever is in radio range — the first event of each kind stays, a different payload under
 * one id is flagged, a flood of heard ids never evicts what this phone originated, and a by-handle answer finds its row.
 */
class LoraTraceBookTest {
    private fun book(
        originated: Int = LoraTraceBook.ORIGINATED_CAPACITY,
        heard: Int = LoraTraceBook.HEARD_CAPACITY,
        handles: Int = LoraTraceBook.HANDLE_CAPACITY,
    ) = LoraTraceBook(originated, heard, handles, clock = { ON_AT }).apply { setActive(true) }

    @Test
    fun nothingIsKeptWhileOffAndSwitchingOffEmptiesIt() {
        val book = LoraTraceBook(clock = { ON_AT })
        assertFalse(book.on)
        book.note(ID, heard(1))
        assertNull("off keeps nothing", book.of(ID))
        assertNull(book.facts().onSince)

        book.setActive(true)
        assertTrue(book.on)
        assertEquals(ON_AT, book.facts().onSince)
        book.note(ID, queued(1))
        book.note(ID, aired(2, handle = 7))
        assertNotNull(book.of(ID))

        book.setActive(false)
        assertFalse(book.on)
        assertNull("off empties it", book.of(ID))
        book.noteByHandle(7, ended(3))
        book.setActive(true)
        assertNull("nothing survives a switch-off, its handles included", book.of(ID))
        book.noteByHandle(7, ended(4))
        assertNull(book.of(ID))
    }

    @Test
    fun theFirstEventOfEachKindSurvivesAFloodOfRepeats() {
        val book = book()
        val firstQueued = queued(2)
        val firstAired = aired(3, handle = 1)
        val firstEnded = ended(4)
        book.note(ID, firstQueued)
        book.note(ID, firstAired)
        book.note(ID, firstEnded)
        book.note(ID, requeued(5))
        repeat(24) { i ->
            val at = 10L + i
            book.note(ID, queued(at))
            book.note(ID, aired(at, handle = 100L + i))
            book.note(ID, ended(at))
            book.note(ID, requeued(at))
        }

        val record = book.of(ID)!!
        val events = record.events
        assertSame(firstQueued, events.first { it is LoraTraceEvent.Queued })
        assertSame(firstAired, events.first { it is LoraTraceEvent.Aired })
        assertSame(firstEnded, events.first { it is LoraTraceEvent.Ended })
        assertEquals(1, events.count { it is LoraTraceEvent.Requeued })
        assertEquals(24, record.requeues)
        assertEquals(LoraTraceBook.KIND_CAP, events.count { it is LoraTraceEvent.Aired })
        assertEquals("the newest stays beside the first", 33L, events.last { it is LoraTraceEvent.Aired }.atMs)
        assertEquals(3 * (25 - LoraTraceBook.KIND_CAP), record.dropped)
        assertEquals("oldest first", events.sortedBy { it.atMs }.map { it.atMs }, events.map { it.atMs })
    }

    @Test
    fun theFirstHeardSurvivesAFloodOfCopies() {
        val book = book()
        val firstHeard = heard(1)
        book.note(HEARD_ID, firstHeard)
        repeat(24) { book.note(HEARD_ID, heard(10L + it)) }

        val record = book.of(HEARD_ID)!!
        assertSame(firstHeard, record.events.first())
        assertEquals(LoraTraceBook.HEARD_CAP, record.events.size)
        assertEquals(25 - LoraTraceBook.HEARD_CAP, record.heardMore)
        assertFalse(record.originated)
    }

    @Test
    fun aHeardUnderAnIdThisPhoneSentUnderIsCountedAndNeverKept() {
        val book = book(originated = 2)
        book.note(ID, queued(1))
        book.note(ID, heard(2))
        book.note(ID, heard(3))

        val record = book.of(ID)!!
        assertEquals("another radio's claim never lands on our row", listOf(1L), record.events.map { it.atMs })
        assertEquals(2, record.heardOnOwn)

        // ...and it keeps nothing alive: the row is still the eldest when two newer sends arrive.
        book.note("own-2", queued(4))
        book.note(ID, heard(5))
        book.note("own-3", queued(6))
        assertNull(book.of(ID))
    }

    @Test
    fun aDifferentPayloadUnderOneIdIsFlaggedAndTheSameOneIsNot() {
        val book = book()
        book.note(ID, heard(1, tag = 0x1111))
        book.note(ID, heard(2, tag = 0x1111))
        assertEquals(0, book.of(ID)!!.differentPayload)

        book.note(ID, heard(3, tag = 0x2222))
        assertEquals(1, book.of(ID)!!.differentPayload)
        assertEquals("the first copy stays first", 0x1111, (book.of(ID)!!.events.first() as LoraTraceEvent.Heard).payloadTag)
    }

    @Test
    fun aFloodOfHeardIdsNeverEvictsWhatThisPhoneOriginatedAndEvictionsAreCounted() {
        val book = book(originated = 4, heard = 8)
        (1..4).forEach { book.note("own-$it", queued(it.toLong())) }

        (1..300).forEach { book.note("heard-$it", heard(it.toLong())) }

        (1..4).forEach { assertTrue("own-$it kept", book.of("own-$it")!!.originated) }
        assertNull("the oldest heard id went", book.of("heard-1"))
        assertNotNull("the newest heard id stays", book.of("heard-300"))
        assertEquals(300 - 8, book.facts().evicted)
    }

    @Test
    fun anIdHeardBeforeThisPhoneSendsUnderItIsPromotedAndItsHeardsDropIntoTheCount() {
        val book = book(originated = 4, heard = 2)
        book.note(ID, heard(1))
        book.note(ID, queued(2))
        (1..10).forEach { book.note("heard-$it", heard(10L + it)) }

        val record = book.of(ID)!!
        assertTrue(record.originated)
        assertEquals(listOf(2L), record.events.map { it.atMs })
        assertEquals(1, record.heardOnOwn)
    }

    @Test
    fun aSendRefusedBeforeTheQueueIsKeptOnTheProtectedSide() {
        val book = book(originated = 4, heard = 2)
        book.note(ID, refused(1))
        (1..10).forEach { book.note("heard-$it", heard(10L + it)) }

        val record = book.of(ID)!!
        assertTrue(record.originated)
        assertEquals(TraceReason.NO_BOARD, (record.events.single() as LoraTraceEvent.Refused).reason)
    }

    @Test
    fun theLeastRecentlyNotedIdIsEvictedAndAReadMovesNothing() {
        val book = book(heard = 2)
        book.note("a", heard(1))
        book.note("b", heard(2))
        book.of("a") // a read must not keep "a"
        book.note("c", heard(3))
        assertNull(book.of("a"))

        book.note("b", heard(4)) // a note does keep "b"
        book.note("d", heard(5))
        assertNotNull(book.of("b"))
        assertNull(book.of("c"))
    }

    @Test
    fun aByHandleAnswerFindsTheRowOfAnyAiringAndAnUnknownHandleIsDropped() {
        val book = book()
        book.note(ID, aired(1, handle = 10))
        book.note(ID, aired(2, handle = 11))

        book.noteByHandle(11, ended(3))
        book.noteByHandle(99, ended(4))

        assertEquals(
            listOf(3L),
            book
                .of(ID)!!
                .events
                .filterIsInstance<LoraTraceEvent.Ended>()
                .map { it.atMs },
        )
        assertNull("an unknown handle makes no row", book.of("99"))
    }

    @Test
    fun handlesAreBoundedNewestKept() {
        val book = book(handles = 2)
        book.note(ID, aired(1, handle = 1))
        book.note(ID, aired(2, handle = 2))
        book.note(ID, aired(3, handle = 3))

        book.noteByHandle(1, ended(4))
        book.noteByHandle(3, ended(5))

        assertEquals(
            listOf(5L),
            book
                .of(ID)!!
                .events
                .filterIsInstance<LoraTraceEvent.Ended>()
                .map { it.atMs },
        )
    }

    /**
     * A smoke test, not proof: a book without its monitor would throw here only on some schedules. Its thread safety is the
     * one lock every method takes.
     */
    @Test
    fun twoWritersAndAReaderNeverThrow() {
        val book = book(originated = 16, heard = 16, handles = 16)
        val pool = Executors.newFixedThreadPool(3)
        val start = CountDownLatch(1)
        val errors = ConcurrentLinkedQueue<Throwable>()

        fun run(body: (Int) -> Unit) =
            pool.submit {
                start.await()
                try {
                    repeat(5_000) { body(it) }
                } catch (t: Throwable) {
                    errors += t
                }
            }
        val writers =
            listOf(
                run { i -> book.note("id-${i % 40}", if (i % 2 == 0) heard(i.toLong()) else aired(i.toLong(), handle = i.toLong())) },
                run { i -> book.noteByHandle(i.toLong(), ended(i.toLong())) },
                run { i -> book.of("id-${i % 40}")?.events?.size },
            )
        start.countDown()
        writers.forEach { it.get(30, TimeUnit.SECONDS) }
        pool.shutdownNow()

        assertTrue(errors.toString(), errors.isEmpty())
    }

    private fun heard(
        at: Long,
        tag: Int? = null,
    ) = LoraTraceEvent.Heard(
        atMs = at,
        board = BoardKind.MESHTASTIC,
        route = TraceRoute.ROOM,
        slot = 0,
        from = "!a1b2c3d4",
        fromVouched = false,
        packetId = 0x1234L,
        radio =
            HeardRadio(
                snrDeci = 65,
                rssi = -97,
                hops = 1,
                direct = false,
                hopStart = 3,
                hopLimit = 2,
                viaMqtt = false,
                live = true,
                boardRxMs = null,
            ),
        parts = 1,
        payloadTag = tag,
        notes = emptySet(),
    )

    private fun queued(at: Long) = LoraTraceEvent.Queued(at, BoardKind.MESHTASTIC, TraceRoute.FRAME, parts = 1, bytes = 120, to = null)

    private fun aired(
        at: Long,
        handle: Long,
    ) = LoraTraceEvent.Aired(
        at,
        BoardKind.MESHTASTIC,
        slot = 1,
        handles = listOf(handle),
        bookedMs = 400,
        part = 0,
        parts = 1,
        heldForAir = false,
        heldForBand = false,
    )

    private fun ended(at: Long) = LoraTraceEvent.Ended(at, BoardKind.MESHTASTIC, TraceOutcome.ACKED)

    private fun requeued(at: Long) = LoraTraceEvent.Requeued(at, BoardKind.MESHTASTIC, TraceReason.BUSY)

    private fun refused(at: Long) = LoraTraceEvent.Refused(at, BoardKind.MESHTASTIC, TraceReason.NO_BOARD)

    private companion object {
        const val ID = "frame-1"
        const val HEARD_ID = "heard-frame-1"
        const val ON_AT = 1_700_000_000_000L
    }
}
