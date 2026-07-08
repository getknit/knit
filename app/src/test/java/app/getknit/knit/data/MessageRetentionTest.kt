package app.getknit.knit.data

import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.MessageEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives [MessageRepository.sweepRetention] against a real in-memory DB with tiny caps — the local-storage
 * bound that stops a Sybil DM/broadcast flood from growing the (otherwise uncapped) `messages` table forever.
 */
class MessageRetentionTest : RoomDbTest() {
    private fun repo() =
        MessageRepository(
            db.messageDao(),
            nearbyMaxMessages = 3,
            nearbyMaxAgeMs = 1_000L,
            maxPerAcceptedThread = 100,
            maxPerPendingThread = 2,
            pendingThreadMaxAgeMs = 1_000L,
            maxPendingThreads = 2,
        )

    private suspend fun put(
        id: String,
        conversationId: String,
        sentAt: Long,
        sender: String = "them",
    ) = db.messageDao().upsert(
        MessageEntity(id = id, senderId = sender, recipientId = null, conversationId = conversationId, body = "", sentAt = sentAt),
    )

    private suspend fun ids(conversationId: String) =
        db
            .messageDao()
            .observeForConversation(conversationId)
            .first()
            .map { it.id }
            .toSet()

    @Test
    fun `Nearby is capped by count and age`() =
        runTest {
            val now = 10_000L
            (1..5).forEach { put("n$it", Conversations.NEARBY, sentAt = now - it) } // 5 recent
            put("stale", Conversations.NEARBY, sentAt = now - 5_000L) // older than nearbyMaxAgeMs

            repo().sweepRetention(now, protected = emptySet())

            assertFalse(db.messageDao().exists("stale")) // age-swept
            assertEquals(3, ids(Conversations.NEARBY).size) // count-capped to the newest 3
        }

    @Test
    fun `a stale unaccepted thread is dropped wholesale, a protected one is kept`() =
        runTest {
            val now = 10_000L
            put("s1", "stranger", sentAt = now - 5_000L) // stale + unprotected → a request
            put("k1", "friend", sentAt = now - 5_000L) // equally stale but PROTECTED

            repo().sweepRetention(now, protected = setOf("friend"))

            assertTrue(ids("stranger").isEmpty()) // stranger request thread gone
            assertEquals(setOf("k1"), ids("friend")) // protected thread retained
        }

    @Test
    fun `an unaccepted thread is capped to its newest few, a protected one is not`() =
        runTest {
            val now = 10_000L
            (1..5).forEach { put("p$it", "stranger", sentAt = now - it) }
            (1..5).forEach { put("f$it", "friend", sentAt = now - it) }

            repo().sweepRetention(now, protected = setOf("friend"))

            assertEquals(2, ids("stranger").size) // capped to maxPerPendingThread
            assertEquals(5, ids("friend").size) // protected: kept (< maxPerAcceptedThread)
        }

    @Test
    fun `the number of live request threads is capped, oldest-by-activity evicted`() =
        runTest {
            val now = 10_000L
            put("a", "convA", sentAt = now - 1) // newest activity
            put("b", "convB", sentAt = now - 2)
            put("c", "convC", sentAt = now - 3) // oldest → evicted (maxPendingThreads = 2)

            repo().sweepRetention(now, protected = emptySet())

            assertTrue(ids("convC").isEmpty())
            assertTrue(ids("convA").isNotEmpty())
            assertTrue(ids("convB").isNotEmpty())
        }
}
