package app.getknit.knit.data.message

import androidx.room3.useWriterConnection
import app.getknit.knit.data.RoomDbTest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A translation kept on its message's row (ADR 2026-10.t2h6's 2026-10-29 amendment): [MessageDao.setTranslation] runs
 * against the real SQL, keyed by the message, never across rows like a voice note's transcript.
 */
class MessageTranslationDaoTest : RoomDbTest() {
    private val dao get() = db.messageDao()

    private fun msg(
        id: String,
        sentAt: Long,
        moderation: Int = MessageEntity.MODERATION_NONE,
    ) = MessageEntity(id = id, senderId = "ana", conversationId = "ana", body = "¿vienes?", sentAt = sentAt, moderation = moderation)

    @Test
    fun `setTranslation keeps a translation on its row alone and leaves the body and its verdict as they were`() =
        runTest {
            dao.upsert(msg("t1", sentAt = 1L, moderation = MessageEntity.MODERATION_TEXT_FLAGGED))
            dao.upsert(msg("t2", sentAt = 2L))

            dao.setTranslation("t1", "coming?", "es", "en", flagged = true, guessed = true)

            val kept = checkNotNull(dao.findById("t1"))
            assertEquals("coming?", kept.translation)
            assertEquals("es", kept.translationFrom)
            assertEquals("en", kept.translationTo)
            assertTrue(kept.translationFlagged)
            assertTrue(kept.translationGuessed)
            assertEquals("¿vienes?", kept.body)
            assertEquals(MessageEntity.MODERATION_TEXT_FLAGGED, kept.moderation)
            assertNull(checkNotNull(dao.findById("t2")).translation)
        }

    @Test
    fun `a translation into another language replaces the one kept`() =
        runTest {
            dao.upsert(msg("t1", sentAt = 1L))
            dao.setTranslation("t1", "coming?", "es", "en", flagged = true, guessed = false)
            dao.setTranslation("t1", "kommst du?", "es", "de", flagged = false, guessed = false)

            val kept = checkNotNull(dao.findById("t1"))
            assertEquals("kommst du?", kept.translation)
            assertEquals("es", kept.translationFrom)
            assertEquals("de", kept.translationTo)
            assertFalse(kept.translationFlagged)
            assertFalse(kept.translationGuessed)
        }

    @Test
    fun `the same translation again writes no row, so nothing observing the chat wakes`() =
        runTest {
            dao.upsert(msg("t1", sentAt = 1L))
            dao.setTranslation("t1", "coming?", "es", "en", flagged = false, guessed = true)
            val before = rowsWritten()

            dao.setTranslation("t1", "coming?", "es", "en", flagged = false, guessed = true)
            assertEquals("an identical repeat changes no row", before, rowsWritten())

            // A positive control: one field differs, and the row is written.
            dao.setTranslation("t1", "coming?", "es", "en", flagged = false, guessed = false)
            assertTrue(rowsWritten() > before)
        }

    // SQLite's count of rows written on the writer connection since it opened, triggers included.
    private suspend fun rowsWritten(): Long =
        db.useWriterConnection { connection ->
            connection.usePrepared("SELECT total_changes()") { statement ->
                check(statement.step())
                statement.getLong(0)
            }
        }
}
