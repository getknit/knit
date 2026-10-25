package app.getknit.knit.data.draft

import androidx.room3.useReaderConnection
import app.getknit.knit.data.RoomDbTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Executes the **real** [DraftDao] SQL. The one statement worth a database is [DraftDao.upsert]: it is a
 * hand-written `INSERT … ON CONFLICT DO UPDATE` rather than Room's `@Upsert`, so this pins that a second
 * write to the same conversation lands as an update of the row it found — same rowid, new text and stamp —
 * and never as a delete-and-reinsert or a second row (work item 60).
 */
class DraftDaoTest : RoomDbTest() {
    private val dao get() = db.draftDao()

    @Test
    fun `a first write inserts, and a second to the same conversation updates that row in place`() =
        runTest {
            dao.upsert(CHAT, "half a", styles = null, updatedAt = 1L)
            val rowid = rowidOf(CHAT)

            dao.upsert(CHAT, "half a sentence", styles = "[[0,4,0]]", updatedAt = 2L)

            assertEquals(listOf(DraftEntity(CHAT, "half a sentence", 2L, "[[0,4,0]]")), dao.observeAll().first())
            assertEquals("updated where it sat, not deleted and re-inserted", rowid, rowidOf(CHAT))
            assertEquals(DraftContent("half a sentence", "[[0,4,0]]"), dao.find(CHAT))

            dao.upsert(CHAT, "half a sentence", styles = null, updatedAt = 3L)
            assertEquals("formatting taken off is taken off the row", DraftContent("half a sentence", null), dao.find(CHAT))
        }

    @Test
    fun `writes to different conversations keep their own rows`() =
        runTest {
            dao.upsert(CHAT, "mine", styles = null, updatedAt = 1L)
            dao.upsert(OTHER, "theirs", styles = null, updatedAt = 1L)
            dao.deleteFor(CHAT)

            assertEquals(listOf(DraftEntity(OTHER, "theirs", 1L)), dao.observeAll().first())
        }

    private suspend fun rowidOf(conversationId: String): Long =
        db.useReaderConnection { connection ->
            connection.usePrepared("SELECT rowid FROM drafts WHERE conversationId = ?") { statement ->
                statement.bindText(1, conversationId)
                check(statement.step()) { "no draft row for $conversationId" }
                statement.getLong(0)
            }
        }

    private companion object {
        const val CHAT = "peer-1"
        const val OTHER = "peer-2"
    }
}
