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
 * The caps are for what strangers can write; an accepted thread has none, and the last test pins that.
 */
class MessageRetentionTest : RoomDbTest() {
    private fun repo() =
        MessageRepository(
            db.messageDao(),
            nearbyMaxMessages = 3,
            nearbyMaxAgeMs = 1_000L,
            maxPerPendingThread = 2,
            pendingThreadMaxAgeMs = 1_000L,
            maxPendingThreads = 2,
        )

    private suspend fun put(
        id: String,
        conversationId: String,
        sentAt: Long,
        sender: String = "them",
        originNode: Long? = null,
    ) = db.messageDao().upsert(
        MessageEntity(
            id = id,
            senderId = sender,
            recipientId = null,
            conversationId = conversationId,
            body = "",
            sentAt = sentAt,
            originNode = originNode,
        ),
    )

    private suspend fun ids(conversationId: String) =
        db
            .messageDao()
            .observeNewestForConversation(conversationId, 100)
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
    fun `a commons is capped like a room, never dropped as a stale request`() =
        runTest {
            val now = 10_000L
            val room = Conversations.commonsIdFor("ef".repeat(32))
            (1..5).forEach { put("c$it", room, sentAt = now - it) }
            put("old", room, sentAt = now - 5_000L)
            // Three unaccepted DM threads beside it: the request rule keeps the newest two threads, and the
            // room must not be counted among them.
            put("a", "convA", sentAt = now - 1)
            put("b", "convB", sentAt = now - 2)
            put("c", "convC", sentAt = now - 3)

            repo().sweepRetention(now, protected = emptySet())

            assertFalse(db.messageDao().exists("old")) // age-swept, like Nearby
            assertEquals(3, ids(room).size) // the room's count cap (a request thread would be trimmed to 2)
            assertTrue(ids("convA").isNotEmpty())
            assertTrue(ids("convB").isNotEmpty())
            assertTrue(ids("convC").isEmpty()) // the room did not take one of the two request-thread slots
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
            assertEquals(5, ids("friend").size) // protected: never trimmed
        }

    @Test
    fun `a protected thread is never trimmed, however large or stale`() =
        runTest {
            val now = 10_000L
            // More rows than every count cap (nearbyMaxMessages = 3, maxPerPendingThread = 2) and older than
            // every age cap (nearbyMaxAgeMs = pendingThreadMaxAgeMs = 1_000): every rule that trims anything
            // would fire here. An accepted thread is the user's own history, and none of them may.
            (1..10).forEach { put("f$it", "friend", sentAt = now - 5_000L - it) }

            repo().sweepRetention(now, protected = setOf("friend"))

            assertEquals((1..10).map { "f$it" }.toSet(), ids("friend"))
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

    @Test
    fun `a stranger keeps only their newest few room posts, a known sender is not capped that way`() =
        runTest {
            val now = 10_000L
            (1..4).forEach { put("m$it", Conversations.NEARBY, sentAt = now - it, sender = "mallory") }
            (1..4).forEach { put("f$it", Conversations.NEARBY, sentAt = now - 10 - it, sender = "friend") } // older than all of Mallory's

            // Room cap is 3, so the count rule still bites after the per-stranger trim — raise it out of the way
            // to see the per-stranger rule alone.
            MessageRepository(db.messageDao(), nearbyMaxMessages = 100, roomMaxPerStranger = 2)
                .sweepRetention(now, protected = emptySet(), knownSenders = setOf("friend"))

            assertEquals(setOf("m1", "m2"), ids(Conversations.NEARBY).filter { it.startsWith("m") }.toSet()) // newest two
            assertEquals(4, ids(Conversations.NEARBY).count { it.startsWith("f") }) // a known sender keeps all four
        }

    @Test
    fun `over the room cap, strangers' oldest posts go before a known sender's`() =
        runTest {
            val now = 10_000L
            // Newest-first alone would keep m1..m3 and drop both of the friend's. The friend's are the oldest
            // in the room on purpose.
            put("f1", Conversations.NEARBY, sentAt = now - 20, sender = "friend")
            put("f2", Conversations.NEARBY, sentAt = now - 19, sender = "friend")
            (1..3).forEach { put("m$it", Conversations.NEARBY, sentAt = now - it, sender = "mallory") }

            MessageRepository(db.messageDao(), nearbyMaxMessages = 3, roomMaxPerStranger = 100)
                .sweepRetention(now, protected = emptySet(), knownSenders = setOf("friend"))

            assertEquals(setOf("f1", "f2", "m1"), ids(Conversations.NEARBY))
        }

    @Test
    fun `a room over cap on known senders alone still trims to the cap, oldest first`() =
        runTest {
            val now = 10_000L
            (1..5).forEach { put("f$it", Conversations.NEARBY, sentAt = now - it, sender = "friend") }

            repo().sweepRetention(now, protected = emptySet(), knownSenders = setOf("friend"))

            assertEquals(setOf("f1", "f2", "f3"), ids(Conversations.NEARBY)) // the last resort keeps the newest three
        }

    @Test
    fun `with no known senders the room cap is newest-first, as before`() =
        runTest {
            val now = 10_000L
            (1..5).forEach { put("n$it", Conversations.NEARBY, sentAt = now - it, sender = "s$it") }

            repo().sweepRetention(now, protected = emptySet())

            assertEquals(setOf("n1", "n2", "n3"), ids(Conversations.NEARBY))
        }

    /**
     * A bridged room's heard posts sit in our own sender column by convention, and we are always a known sender: before
     * ADR 2026-11.gsqu's Stage 6 amendment (security N6) a busy channel was trimmed only with the user's own posts, so a
     * flood evicted those first. Every heard row counts as a stranger's, in both bridged rooms.
     */
    @Test
    fun `in a bridged room the heard posts go before the user's own, whatever their sender column says`() =
        runTest {
            val now = 10_000L
            for ((room, heard) in listOf(Conversations.MESHTASTIC to 0x1234abcdL, Conversations.MESHCORE_PUBLIC to -1L)) {
                // The user's two posts are the oldest in the room on purpose: newest-first alone would drop them both.
                put("$room-own1", room, sentAt = now - 20, sender = "me")
                put("$room-own2", room, sentAt = now - 19, sender = "me")
                (1..3).forEach { put("$room-h$it", room, sentAt = now - it, sender = "me", originNode = heard) }
            }

            MessageRepository(db.messageDao(), nearbyMaxMessages = 3, roomMaxPerStranger = 100)
                .sweepRetention(now, protected = emptySet(), knownSenders = setOf("me"))

            for (room in listOf(Conversations.MESHTASTIC, Conversations.MESHCORE_PUBLIC)) {
                assertEquals(room, setOf("$room-own1", "$room-own2", "$room-h1"), ids(room))
            }
        }
}
