package app.getknit.knit.data.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The newer phone's record of a contact's ask (ADR 2026-11.29m7's amendment) and the notice-shaped row it becomes. */
class UpdateRequestRecordTest {
    private val record = UpdateRequestRecord(requestId = "r1", versionCode = 21_400_900L, phase = UpdateRequestPhase.Waiting)

    @Test
    fun encodeThenDecodeRoundTripsAndGarbageDecodesToNull() {
        assertEquals(record, UpdateRequestRecord.decode(record.encode()))
        UpdateRequestPhase.entries.forEach { assertEquals(it, UpdateRequestRecord.decode(record.copy(phase = it).encode())?.phase) }
        assertNull(UpdateRequestRecord.decode("not json"))
        assertNull(UpdateRequestRecord.decode("{\"requestId\":\"r1\"}"))
        assertNull("a phase this build does not know", UpdateRequestRecord.decode(record.encode().replace("Waiting", "Later")))
    }

    @Test
    fun theRowIsANoticeShapedDmRowFromTheAsker() {
        val row = record.toEntity(peerId = "peer", selfId = "me", askedAt = 5L)
        assertEquals("upd:peer:r1", row.id)
        assertEquals("peer", row.senderId)
        assertEquals("me", row.recipientId)
        assertEquals("peer", row.conversationId)
        assertEquals(5L, row.sentAt)
        assertEquals(MessageEntity.KIND_APP_UPDATE_REQUEST, row.kind)
        assertTrue(row.received)
        assertTrue(row.isStatusNotice)
        assertEquals(record, UpdateRequestRecord.decode(row.body))
        // Each phase upserts the one row.
        assertEquals(row.id, record.copy(phase = UpdateRequestPhase.Sent).toEntity("peer", "me", 5L).id)
    }
}
