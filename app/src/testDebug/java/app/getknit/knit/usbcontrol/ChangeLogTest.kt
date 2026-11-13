@file:Suppress("MagicNumber") // positions and capacities are the test's subject

package app.getknit.knit.usbcontrol

import app.getknit.knit.data.changes.DataChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangeLogTest {
    private var epochs = 0
    private val log = ChangeLog(capacity = 3) { "e${++epochs}" }

    @Test
    fun nothingIsKeptWhileNotRecording() {
        log.sink.emit(DataChange.Message("m1", "c"))
        log.record(ChangeKey.Self)
        assertEquals(ChangeCursor("e1", 0), log.latest())
    }

    @Test
    fun aRepositoryWriteIsKeptAsItsKeyInOrder() {
        log.setRecording(true)
        val start = log.latest()
        log.sink.emit(DataChange.Message("m1", "c"))
        log.sink.emit(DataChange.ThreadTrimmed("c"))
        log.sink.emit(DataChange.Peers)
        assertEquals(
            listOf(1L to ChangeKey.Message("m1", "c"), 2L to ChangeKey.Thread("c"), 3L to ChangeKey.Contacts),
            log.after(start),
        )
        assertEquals(listOf(3L to ChangeKey.Contacts), log.after(log.latest().copy(seq = 2)))
        assertEquals(emptyList<Pair<Long, ChangeKey>>(), log.after(log.latest()))
    }

    @Test
    fun aBlobWriteIsKeptAsItsAttachmentKey() {
        assertEquals(ChangeKey.Attachment("h"), ChangeKey.of(DataChange.Blob("h")))
    }

    @Test
    fun aCursorOutOfTheRingFromAnotherEpochOrAheadCannotBeResumed() {
        log.setRecording(true)
        val start = log.latest()
        repeat(4) { log.record(ChangeKey.Chat("c$it")) }
        assertNull("key 1 has left a ring of 3", log.after(start))
        assertEquals(3, log.after(start.copy(seq = 1))?.size)
        assertNull(log.after(start.copy(seq = 9)))
        assertNull(log.after(ChangeCursor("other", 2)))
    }

    @Test
    fun recordingAgainStartsANewEpochOverAnEmptyRing() {
        log.setRecording(true)
        log.record(ChangeKey.Self)
        val before = log.latest()
        log.setRecording(false)
        log.record(ChangeKey.Self)
        log.setRecording(true)
        val after = log.latest()
        assertNotEquals(before.epoch, after.epoch)
        assertEquals("the off-time write was never kept", before.seq, after.seq)
        assertNull(log.after(before))
        assertEquals(emptyList<Pair<Long, ChangeKey>>(), log.after(after))
    }

    @Test
    fun aListenerIsWokenByAKey() {
        val signal = log.listen()
        log.setRecording(true)
        assertTrue(signal.tryReceive().isSuccess)
        log.record(ChangeKey.Self)
        log.record(ChangeKey.Self)
        assertTrue("conflated: one wake for both", signal.tryReceive().isSuccess)
        assertTrue(signal.tryReceive().isFailure)
        log.unlisten(signal)
        log.record(ChangeKey.Self)
        assertTrue(signal.tryReceive().isFailure)
    }
}
