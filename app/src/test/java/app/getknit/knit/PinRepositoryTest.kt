package app.getknit.knit

import app.getknit.knit.data.PinOutcome
import app.getknit.knit.data.PinRepository
import app.getknit.knit.data.RoomDbTest
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.pin.PinEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives [PinRepository] against a **real** in-memory Room DB, as `ReactionRepositoryTest` does for reactions: the
 * last-writer-wins guard a shared pin will land through, and the local toggle with its per-chat limit.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PinRepositoryTest : RoomDbTest() {
    private fun repo() = PinRepository(db.pinDao(), db)

    private suspend fun message(
        id: String,
        conversationId: String = "bob",
    ) = db.messageDao().upsert(MessageEntity(id = id, senderId = "bob", conversationId = conversationId, body = id, sentAt = 1L))

    private suspend fun pinnedIds(conversationId: String = "bob") = repo().observePinnedIn(conversationId).first().map { it.message.id }

    @Test
    fun applyKeepsTheNewerAndDropsTheStaler() =
        runTest {
            val repo = repo()

            assertTrue(repo.apply(PinEntity("m1", pinnedBy = "alice", updatedAt = 100)))
            assertFalse("an older unpin loses", repo.apply(PinEntity("m1", pinnedBy = null, updatedAt = 50)))
            assertEquals("alice", db.pinDao().find("m1")?.pinnedBy)

            assertTrue("a newer unpin wins", repo.apply(PinEntity("m1", pinnedBy = null, updatedAt = 150)))
            assertFalse("a stale pin cannot resurrect it", repo.apply(PinEntity("m1", pinnedBy = "alice", updatedAt = 120)))
            assertNull(db.pinDao().find("m1")?.pinnedBy)
        }

    @Test
    fun applyReportsNoChangeForADuplicateOrARepin() =
        runTest {
            val repo = repo()

            assertTrue(repo.apply(PinEntity("m1", pinnedBy = "alice", updatedAt = 100)))
            assertFalse("the same stamp again is a duplicate", repo.apply(PinEntity("m1", pinnedBy = "alice", updatedAt = 100)))
            assertFalse("pinned by someone else, still pinned", repo.apply(PinEntity("m1", pinnedBy = "bob", updatedAt = 200)))
            assertEquals(PinEntity("m1", pinnedBy = "bob", updatedAt = 200), db.pinDao().find("m1"))
        }

    @Test
    fun toggleSwitchesBetweenPinnedAndUnpinned() =
        runTest {
            message("m1")
            val repo = repo()

            assertEquals(PinOutcome.Pinned, repo.toggle("m1", me = "me", now = 10))
            assertEquals(listOf("m1"), pinnedIds())
            assertEquals("me", db.pinDao().find("m1")?.pinnedBy)

            assertEquals(PinOutcome.Unpinned, repo.toggle("m1", me = "me", now = 20))
            assertTrue(pinnedIds().isEmpty())
            // Unpinned is a tombstone, not a deleted row: a later shared pin needs the stamp to compare against.
            assertEquals(PinEntity("m1", pinnedBy = null, updatedAt = 20), db.pinDao().find("m1"))
        }

    @Test
    fun aTapTakesEffectEvenWhenTheClockWentBack() =
        runTest {
            message("m1")
            val repo = repo()

            repo.toggle("m1", me = "me", now = 1_000)
            assertEquals(PinOutcome.Unpinned, repo.toggle("m1", me = "me", now = 500))
            assertEquals(PinEntity("m1", pinnedBy = null, updatedAt = 1_001), db.pinDao().find("m1"))
            assertEquals(PinOutcome.Pinned, repo.toggle("m1", me = "me", now = 400))
            assertEquals(1_002L, db.pinDao().find("m1")?.updatedAt)
        }

    @Test
    fun aPinPastTheLimitUnpinsTheThreadsOldest() =
        runTest {
            listOf("m1", "m2", "m3", "m4").forEach { message(it) }
            message("elsewhere", conversationId = "carol")
            val repo = repo()
            repo.toggle("elsewhere", me = "me", now = 1) // another thread's pin counts against nothing here

            assertEquals(PinOutcome.Pinned, repo.toggle("m1", me = "me", now = 10))
            assertEquals(PinOutcome.Pinned, repo.toggle("m2", me = "me", now = 20))
            assertEquals(PinOutcome.Pinned, repo.toggle("m3", me = "me", now = 30))
            assertEquals(PinRepository.MAX_PER_CHAT, pinnedIds().size)

            assertEquals(PinOutcome.PinnedReplacingOldest, repo.toggle("m4", me = "me", now = 40))

            assertEquals(listOf("m4", "m3", "m2"), pinnedIds())
            assertNull("the oldest is unpinned, as a tombstone", db.pinDao().find("m1")?.pinnedBy)
            assertEquals(listOf("elsewhere"), pinnedIds("carol"))
        }

    @Test
    fun aMessageThisPhoneDoesNotHoldIsLeftAlone() =
        runTest {
            assertEquals(PinOutcome.Unchanged, repo().toggle("missing", me = "me", now = 10))
            assertNull(db.pinDao().find("missing"))
        }

    @Test
    fun deleteOrphansKeepsAFreshOrphanForADay() =
        runTest {
            val repo = repo()
            val day = 24L * 60 * 60 * 1000
            repo.apply(PinEntity("old", pinnedBy = "me", updatedAt = 1))
            repo.apply(PinEntity("fresh", pinnedBy = "me", updatedAt = day))

            repo.deleteOrphans(now = day + 2)

            assertNull(db.pinDao().find("old"))
            assertEquals("me", db.pinDao().find("fresh")?.pinnedBy)
        }
}
