package app.getknit.knit.data.message

import app.getknit.knit.data.RoomDbTest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MessageDao.conversationsOfAttachment] against the real SQL: a picture forwarded under its sealed identity (ADR
 * 2026-10.mptm) — sent to a group first, then into a DM — is named by both conversations, and a holder's member-first
 * serve reads them all (ADR 2026-10.nsr7), never the first row alone.
 */
class AttachmentConversationsTest : RoomDbTest() {
    private val dao get() = db.messageDao()

    private fun row(
        id: String,
        conversationId: String,
        hash: String,
        sentAt: Long = 1L,
    ) = MessageEntity(id = id, senderId = "me", conversationId = conversationId, body = "", sentAt = sentAt, attachmentHash = hash)

    @Test
    fun `every conversation a forwarded picture is in is named, once each`() =
        runTest {
            dao.upsert(row("g1", "group-1", "H1", sentAt = 1L))
            dao.upsert(row("d1", "peer", "H1", sentAt = 2L))
            dao.upsert(row("d2", "peer", "H1", sentAt = 3L))
            dao.upsert(row("x", "other", "H2"))

            val named = dao.conversationsOfAttachment("H1")
            assertEquals(setOf("group-1", "peer"), named.toSet())
            assertEquals("once each", 2, named.size)
            assertTrue(dao.conversationsOfAttachment("H3").isEmpty())
        }
}
