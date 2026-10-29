package app.getknit.knit.data

import app.getknit.knit.data.ratchet.LoraDirectSeenEntity
import app.getknit.knit.data.ratchet.LoraDirectSeenRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LoraDirectSeenRepository] over the real Room SQL (the [RoomDbTest] pattern, ADR 2026-10.x9fa): the mark a compact LoRa
 * copy leaves is written once and never overwritten, and the sweep's edge is inclusive of the retention, on our own clock.
 */
class LoraDirectSeenRepositoryTest : RoomDbTest() {
    private val repo by lazy { LoraDirectSeenRepository(db) }

    @Test
    fun aRowAtTheRetentionEdgeIsKeptAndOneMillisecondOlderIsSwept() =
        runTest {
            val now = 10 * LoraDirectSeenRepository.RETENTION_MS
            assertTrue(repo.recordIfAbsent("edge", "alice", now - LoraDirectSeenRepository.RETENTION_MS, ratchetDone = false))
            assertTrue(repo.recordIfAbsent("older", "alice", now - LoraDirectSeenRepository.RETENTION_MS - 1, ratchetDone = true))
            assertTrue(repo.recordIfAbsent("fresh", "alice", now, ratchetDone = false))

            assertEquals("only the row past the edge", 1, repo.sweep(now))
            assertNotNull(repo.find("edge"))
            assertNull(repo.find("older"))
            assertNotNull(repo.find("fresh"))
        }

    @Test
    fun aDuplicateInsertIsRefusedAndLeavesTheFirstMarkStanding() =
        runTest {
            val first = LoraDirectSeenEntity("f1", "alice", arrivedAt = 5, ratchetDone = false)
            assertTrue(db.loraDirectSeenDao().insertIfAbsent(first) != -1L)
            assertEquals(-1L, db.loraDirectSeenDao().insertIfAbsent(first.copy(peerId = "mallory", arrivedAt = 9, ratchetDone = true)))
            assertFalse("the repository reports it as held", repo.recordIfAbsent("f1", "mallory", 9, ratchetDone = true))
            assertEquals("the first sender's mark, as written", first, repo.find("f1"))

            repo.markRatchetDone("f1")
            assertEquals(first.copy(ratchetDone = true), repo.find("f1"))
        }
}
