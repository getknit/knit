package app.getknit.knit.data.blob

import app.getknit.knit.data.RoomDbTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real-SQL coverage for the NSFW-verdict cache — round-trip, REPLACE-on-rescan, and the flagged stream. */
class BlobVerdictDaoTest : RoomDbTest() {
    private val dao get() = db.blobVerdictDao()

    @Test
    fun `upsert then find round-trips the verdict`() =
        runTest {
            dao.upsert(BlobVerdictEntity("h1", flagged = true, score = 0.9f))
            val got = dao.find("h1")!!
            assertTrue(got.flagged)
            assertEquals(0.9f, got.score, 0.0001f)
        }

    @Test
    fun `upsert replaces on the same hash`() =
        runTest {
            dao.upsert(BlobVerdictEntity("h1", flagged = false, score = 0.1f))
            dao.upsert(BlobVerdictEntity("h1", flagged = true, score = 0.8f))
            assertTrue(dao.find("h1")!!.flagged)
        }

    @Test
    fun `observeFlaggedHashes returns only flagged hashes`() =
        runTest {
            dao.upsert(BlobVerdictEntity("h1", flagged = true, score = 0.9f))
            dao.upsert(BlobVerdictEntity("h2", flagged = false, score = 0.1f))
            assertEquals(listOf("h1"), dao.observeFlaggedHashes().first())
        }

    @Test
    fun `delete removes the row`() =
        runTest {
            dao.upsert(BlobVerdictEntity("h1", flagged = true, score = 0.9f))
            dao.delete("h1")
            assertNull(dao.find("h1"))
        }
}
