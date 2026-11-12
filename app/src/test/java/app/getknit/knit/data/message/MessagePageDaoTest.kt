package app.getknit.knit.data.message

import app.getknit.knit.data.RoomDbTest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The real SQL of a thread's pages ([MessageDao.newestIn], [MessageDao.olderThan]): the debug USB control's `history`. */
class MessagePageDaoTest : RoomDbTest() {
    private val dao get() = db.messageDao()

    @Test
    fun `olderThan pages a thread back without overlap or skip, ties broken by id`() =
        runTest {
            // b and c share sentAt 2, and the second page ends between them: the cursor (2, "c") sits inside the tie, so
            // only the id half of the cursor brings b back. A `sentAt <` cursor alone would drop it.
            listOf("a" to 1L, "b" to 2L, "c" to 2L, "d" to 3L, "e" to 4L, "f" to 5L).forEach { (id, at) -> dao.upsert(row(id, THREAD, at)) }
            // Another thread's row inside the tie, sorting below the cursor's id: only the thread filter keeps it out.
            dao.upsert(row("0other", Conversations.NEARBY, 2L))

            val first = dao.newestIn(THREAD, 2)
            assertEquals(listOf("f", "e"), first.map { it.id })
            val second = dao.olderThan(THREAD, first.last().sentAt, first.last().id, 2)
            assertEquals(listOf("d", "c"), second.map { it.id })
            val third = dao.olderThan(THREAD, second.last().sentAt, second.last().id, 2)
            assertEquals("the tie at sentAt 2 is crossed by id, and other threads stay out", listOf("b", "a"), third.map { it.id })
            assertTrue(dao.olderThan(THREAD, 1L, "a", 2).isEmpty())
        }

    private fun row(
        id: String,
        conversationId: String,
        sentAt: Long,
    ) = MessageEntity(id = id, senderId = "s", conversationId = conversationId, body = "", sentAt = sentAt, received = false)

    private companion object {
        const val THREAD = "thread"
    }
}
