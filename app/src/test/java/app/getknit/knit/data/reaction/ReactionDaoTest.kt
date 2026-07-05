package app.getknit.knit.data.reaction

import app.getknit.knit.data.RoomDbTest
import app.getknit.knit.data.message.MessageEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Executes the **real** [ReactionDao] SQL (finding #5). The headline is [ReactionDao.deleteOrphansOlderThan] —
 * an anti-join against `messages` (there is deliberately no FK cascade) gated by an age floor that spares a
 * reaction which legitimately arrived before its target message via out-of-order mesh delivery.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReactionDaoTest : RoomDbTest() {
    private val dao get() = db.reactionDao()

    @Test
    fun `deleteOrphansOlderThan reclaims only old reactions whose message is gone`() =
        runTest {
            db.messageDao().upsert(MessageEntity(id = "m_live", senderId = "s", body = "", sentAt = 1L))
            dao.upsert(ReactionEntity("m_live", "u", emoji = "👍", updatedAt = 1L)) // message present → kept
            dao.upsert(ReactionEntity("m_gone_old", "u", emoji = "👍", updatedAt = 1L)) // orphan, old → deleted
            dao.upsert(ReactionEntity("m_gone_new", "u", emoji = "👍", updatedAt = 9L)) // orphan but newer than floor

            dao.deleteOrphansOlderThan(olderThan = 5L)

            val remaining = dao.observeAll().first().map { it.messageId }
            assertEquals(setOf("m_live", "m_gone_new"), remaining.toSet())
        }

    @Test
    fun `observeAll hides retraction tombstones (emoji IS NULL)`() =
        runTest {
            dao.upsert(ReactionEntity("m1", "u", emoji = "👍", updatedAt = 1L))
            dao.upsert(ReactionEntity("m2", "u", emoji = null, updatedAt = 1L)) // retracted → hidden from the UI stream
            assertEquals(listOf("m1"), dao.observeAll().first().map { it.messageId })
        }

    @Test
    fun `upsert replaces on the composite key (messageId, reactorNodeId)`() =
        runTest {
            dao.upsert(ReactionEntity("m1", "u", emoji = "👍", updatedAt = 1L))
            dao.upsert(ReactionEntity("m1", "u", emoji = "❤️", updatedAt = 2L)) // same PK → replace, not a 2nd row

            assertEquals("❤️", dao.emojiFor("m1", "u"))
            assertEquals(2L, dao.updatedAtFor("m1", "u"))
            assertEquals(1, dao.observeAll().first().size)
        }

    @Test
    fun `deleteForMessage drops every reaction on a message`() =
        runTest {
            dao.upsert(ReactionEntity("m1", "u1", emoji = "👍", updatedAt = 1L))
            dao.upsert(ReactionEntity("m1", "u2", emoji = "❤️", updatedAt = 1L))
            dao.upsert(ReactionEntity("m2", "u1", emoji = "👍", updatedAt = 1L))

            dao.deleteForMessage("m1")

            assertEquals(listOf("m2"), dao.observeAll().first().map { it.messageId })
            assertTrue(dao.emojiFor("m1", "u1") == null)
        }
}
