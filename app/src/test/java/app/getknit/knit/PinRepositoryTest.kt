package app.getknit.knit

import app.getknit.knit.data.PinOutcome
import app.getknit.knit.data.PinRepository
import app.getknit.knit.data.RoomDbTest
import app.getknit.knit.data.message.MessageEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives [PinRepository] against a **real** in-memory Room DB: pin and unpin as a wanted state (so a repeated tap is a
 * no-op), the per-chat limit, the thread-wide stamp floor, and unpinning a blocked sender.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PinRepositoryTest : RoomDbTest() {
    private fun repo() = PinRepository(db.pinDao(), db)

    private suspend fun message(
        id: String,
        conversationId: String = "bob",
        senderId: String = "bob",
    ) = db.messageDao().upsert(MessageEntity(id = id, senderId = senderId, conversationId = conversationId, body = id, sentAt = 1L))

    private suspend fun pinnedIds(conversationId: String = "bob") = repo().observePinnedIn(conversationId).first().map { it.message.id }

    @Test
    fun setPinnedPinsAndUnpinsAndAnUnpinDeletesTheRow() =
        runTest {
            message("m1")
            val repo = repo()

            assertEquals(PinOutcome.Pinned, repo.setPinned("m1", pinned = true, now = 10))
            assertEquals(listOf("m1"), pinnedIds())

            assertEquals(PinOutcome.Unpinned, repo.setPinned("m1", pinned = false, now = 20))
            assertTrue(pinnedIds().isEmpty())
            assertFalse("an unpin leaves no row behind", db.pinDao().isPinned("m1"))
        }

    @Test
    fun aRepeatedTapIsANoOpRatherThanAToggle() =
        runTest {
            listOf("m1", "m2", "m3", "m4").forEach { message(it) }
            val repo = repo()
            repo.setPinned("m1", pinned = true, now = 10)
            repo.setPinned("m2", pinned = true, now = 20)
            repo.setPinned("m3", pinned = true, now = 30)

            // The bar's ✕ tapped twice before the bar caught up: the second does nothing, rather than re-pinning m3
            // with a new stamp.
            assertEquals(PinOutcome.Unpinned, repo.setPinned("m3", pinned = false, now = 40))
            assertEquals(PinOutcome.Unchanged, repo.setPinned("m3", pinned = false, now = 41))
            assertEquals(listOf("m2", "m1"), pinnedIds())

            // A stale long-press Pin on a message already pinned neither moves its stamp nor evicts anything.
            repo.setPinned("m3", pinned = true, now = 50)
            assertEquals(PinOutcome.Unchanged, repo.setPinned("m3", pinned = true, now = 51))
            assertEquals(listOf("m3", "m2", "m1"), pinnedIds())
            val newest = repo().observePinnedIn("bob").first().first()
            assertEquals(50L, newest.pinnedAt)
        }

    @Test
    fun aPinPastTheLimitUnpinsTheThreadsOldest() =
        runTest {
            listOf("m1", "m2", "m3", "m4").forEach { message(it) }
            message("elsewhere", conversationId = "carol")
            val repo = repo()
            repo.setPinned("elsewhere", pinned = true, now = 1) // another thread's pin counts against nothing here

            assertEquals(PinOutcome.Pinned, repo.setPinned("m1", pinned = true, now = 10))
            assertEquals(PinOutcome.Pinned, repo.setPinned("m2", pinned = true, now = 20))
            assertEquals(PinOutcome.Pinned, repo.setPinned("m3", pinned = true, now = 30))
            assertEquals(PinRepository.MAX_PER_CHAT, pinnedIds().size)

            assertEquals(PinOutcome.PinnedReplacingOldest, repo.setPinned("m4", pinned = true, now = 40))

            assertEquals(listOf("m4", "m3", "m2"), pinnedIds())
            assertFalse("the oldest's row is gone", db.pinDao().isPinned("m1"))
            assertEquals(listOf("elsewhere"), pinnedIds("carol"))
        }

    @Test
    fun aPinMadeAfterTheClockWasPutRightIsNewerThanOneMadeWhileItRanAhead() =
        runTest {
            listOf("x", "a", "b", "c").forEach { message(it) }
            val repo = repo()
            val day = 24L * 60 * 60 * 1000
            repo.setPinned("x", pinned = true, now = 1_000 + day) // the clock was a day ahead
            repo.setPinned("a", pinned = true, now = 1_000) // ...then put right
            repo.setPinned("b", pinned = true, now = 2_000)

            assertEquals("each later pin sorts newer", listOf("b", "a", "x"), pinnedIds())

            // So the next pin lets X go, the oldest the person made, not A, which the snackbar would misdescribe.
            assertEquals(PinOutcome.PinnedReplacingOldest, repo.setPinned("c", pinned = true, now = 3_000))
            assertEquals(listOf("c", "b", "a"), pinnedIds())
        }

    @Test
    fun aMessageThisPhoneDoesNotHoldIsLeftAlone() =
        runTest {
            val repo = repo()
            assertEquals(PinOutcome.Unchanged, repo.setPinned("missing", pinned = true, now = 10))
            assertEquals(PinOutcome.Unchanged, repo.setPinned("missing", pinned = false, now = 10))
            assertFalse(db.pinDao().isPinned("missing"))
        }

    @Test
    fun unpinFromFreesTheBlockedSendersPlaces() =
        runTest {
            message("sam", conversationId = "g-hikers", senderId = "sam")
            message("ana", conversationId = "g-hikers", senderId = "ana")
            message("mallory", conversationId = "g-hikers", senderId = "mallory")
            message("next", conversationId = "g-hikers", senderId = "sam")
            val repo = repo()
            repo.setPinned("sam", pinned = true, now = 10)
            repo.setPinned("ana", pinned = true, now = 20)
            repo.setPinned("mallory", pinned = true, now = 30)

            repo.unpinFrom("mallory")

            // The thread holds two pins now, as the bar shows, so the next pin evicts nothing.
            assertEquals(listOf("ana", "sam"), pinnedIds("g-hikers"))
            assertEquals(PinOutcome.Pinned, repo.setPinned("next", pinned = true, now = 40))
            assertEquals(listOf("next", "ana", "sam"), pinnedIds("g-hikers"))
        }
}
