package app.getknit.knit.ui.chat

import app.getknit.knit.data.message.TransferPhase
import app.getknit.knit.data.message.TransferRecord
import app.getknit.knit.mesh.protocol.TransferPayload
import app.getknit.knit.transfer.TransferRefusal
import app.getknit.knit.transfer.TransferState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The card's view of a transfer row: record facts, live overlay, and the interrupted case in between. */
class TransferViewTest {
    private val record =
        TransferRecord(id = "t1", outgoing = true, name = "Knit.apk", size = 10L, mime = null, phase = TransferPhase.Connecting)
    private val row = record.toEntity(peerId = "bob", selfId = "me", sentAt = 1L)

    @Test
    fun liveStateOverlaysTheRecordAndItsAbsenceReadsAsInterrupted() {
        val live = TransferState("t1", "bob", true, "Knit.apk", 10L, null, TransferPhase.Transferring, bytes = 4L)
        val view = checkNotNull(transferViewFor(row, mapOf("t1" to live)))
        assertEquals(TransferPhase.Transferring, view.phase)
        assertEquals(4L, view.bytes)
        assertFalse(view.interrupted)
        assertTrue("an .apk is installable, so no Open", view.installable)
        assertTrue(view.risky)

        val dead = checkNotNull(transferViewFor(row, emptyMap()))
        assertEquals(TransferPhase.Connecting, dead.phase)
        assertTrue(dead.interrupted)

        val done = record.copy(phase = TransferPhase.Done, savedUri = "content://x").toEntity("bob", "me", 1L)
        val settled = checkNotNull(transferViewFor(done, emptyMap()))
        assertFalse(settled.interrupted)
        assertEquals("content://x", settled.savedUri)
    }

    /**
     * Retry is the sender's, for a transfer that ended short, once, never while it is still running, and only for a
     * file the chat found it can still read.
     */
    @Test
    fun retryIsOfferedOnTheSendersCardForATransferThatEndedShort() {
        val sent = record.copy(sourceUri = "content://picked/1")

        fun retryable(
            r: TransferRecord,
            live: Map<String, TransferState> = emptyMap(),
            readable: Boolean = true,
        ) = checkNotNull(transferViewFor(r.toEntity("bob", "me", 1L), live, sourceReadable = { readable })).retryable

        for (phase in listOf(TransferPhase.Failed, TransferPhase.Expired, TransferPhase.Cancelled)) {
            assertTrue("$phase", retryable(sent.copy(phase = phase)))
        }
        assertTrue("a busy peer", retryable(sent.copy(phase = TransferPhase.Declined, reason = TransferPayload.REASON_BUSY)))
        assertTrue("interrupted by a process death", retryable(sent))

        assertFalse("still running", retryable(sent, mapOf("t1" to TransferState("t1", "bob", true, "Knit.apk", 10L, null, sent.phase))))
        assertFalse("delivered", retryable(sent.copy(phase = TransferPhase.Done)))
        assertFalse("turned down", retryable(sent.copy(phase = TransferPhase.Declined, reason = TransferPayload.REASON_USER)))
        assertFalse("already retried", retryable(sent.copy(phase = TransferPhase.Failed, retried = true)))
        assertFalse("a row from before sources were kept", retryable(record.copy(phase = TransferPhase.Failed)))
        assertFalse("the receiver's card", retryable(sent.copy(outgoing = false, phase = TransferPhase.Failed)))
        assertFalse("a file that can no longer be read", retryable(sent.copy(phase = TransferPhase.Failed), readable = false))
        assertFalse(
            "a file nobody has checked yet",
            checkNotNull(transferViewFor(sent.copy(phase = TransferPhase.Failed).toEntity("bob", "me", 1L), emptyMap())).retryable,
        )
    }

    @Test
    fun aRowWhoseBodyIsNotARecordHasNoCard() {
        assertNull(transferViewFor(row.copy(body = "not a record"), emptyMap()))
    }

    @Test
    fun everyRefusalHasASentence() {
        assertEquals(
            TransferRefusal.entries.size,
            TransferRefusal.entries
                .map(::transferRefusalMessage)
                .distinct()
                .size,
        )
    }
}
