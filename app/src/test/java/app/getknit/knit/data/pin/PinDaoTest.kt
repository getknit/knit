package app.getknit.knit.data.pin

import app.getknit.knit.data.RoomDbTest
import app.getknit.knit.data.message.MessageEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Executes the **real** [PinDao] SQL, in the style of `ReactionDaoTest`: the thread read is a join against `messages`
 * (there is deliberately no FK), so it narrows to one thread, hides tombstones and drops a pin whose message is gone;
 * the orphan sweep is the reactions' anti-join with its age floor.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PinDaoTest : RoomDbTest() {
    private val dao get() = db.pinDao()

    private suspend fun message(
        id: String,
        conversationId: String = "bob",
        senderId: String = "bob",
    ) = db.messageDao().upsert(MessageEntity(id = id, senderId = senderId, conversationId = conversationId, body = id, sentAt = 1L))

    @Test
    fun `observePinnedIn returns one thread's live pins, newest pin first, with their messages`() =
        runTest {
            message("m1")
            message("m2")
            message("m3")
            message("other", conversationId = "carol", senderId = "carol")
            dao.upsert(PinEntity("m1", pinnedBy = "me", updatedAt = 10L))
            dao.upsert(PinEntity("m2", pinnedBy = "me", updatedAt = 30L))
            dao.upsert(PinEntity("m3", pinnedBy = null, updatedAt = 40L)) // unpinned → hidden
            dao.upsert(PinEntity("other", pinnedBy = "me", updatedAt = 50L)) // another thread → excluded
            dao.upsert(PinEntity("gone", pinnedBy = "me", updatedAt = 60L)) // no message on this phone → excluded

            val pins = dao.observePinnedIn("bob").first()

            assertEquals(listOf("m2", "m1"), pins.map { it.message.id })
            assertEquals(listOf(30L, 10L), pins.map { it.pinnedAt })
            assertEquals(listOf("me", "me"), pins.map { it.pinnedBy })
            assertEquals("m2", pins.first().message.body)
        }

    @Test
    fun `a same-millisecond tie orders by message id so the bar never flips`() =
        runTest {
            message("a")
            message("b")
            dao.upsert(PinEntity("a", pinnedBy = "me", updatedAt = 5L))
            dao.upsert(PinEntity("b", pinnedBy = "me", updatedAt = 5L))

            assertEquals(listOf("b", "a"), dao.observePinnedIn("bob").first().map { it.message.id })
            assertEquals(listOf("a", "b"), dao.livePinIdsIn("bob"))
        }

    @Test
    fun `livePinIdsIn lists one thread's live pins oldest first`() =
        runTest {
            message("m1")
            message("m2")
            message("m3")
            dao.upsert(PinEntity("m1", pinnedBy = "me", updatedAt = 20L))
            dao.upsert(PinEntity("m2", pinnedBy = "me", updatedAt = 10L))
            dao.upsert(PinEntity("m3", pinnedBy = null, updatedAt = 30L))

            assertEquals(listOf("m2", "m1"), dao.livePinIdsIn("bob"))
        }

    @Test
    fun `upsert replaces on the message id`() =
        runTest {
            dao.upsert(PinEntity("m1", pinnedBy = "me", updatedAt = 1L))
            dao.upsert(PinEntity("m1", pinnedBy = null, updatedAt = 2L)) // same key → replace, not a second row

            assertEquals(PinEntity("m1", pinnedBy = null, updatedAt = 2L), dao.find("m1"))
        }

    @Test
    fun `conversationOf names a held message's thread and nothing for one this phone lacks`() =
        runTest {
            message("m1", conversationId = "g-hikers")

            assertEquals("g-hikers", dao.conversationOf("m1"))
            assertNull(dao.conversationOf("missing"))
        }

    @Test
    fun `deleteForMessage drops only that message's pin`() =
        runTest {
            dao.upsert(PinEntity("m1", pinnedBy = "me", updatedAt = 1L))
            dao.upsert(PinEntity("m2", pinnedBy = "me", updatedAt = 1L))

            dao.deleteForMessage("m1")

            assertNull(dao.find("m1"))
            assertEquals(PinEntity("m2", pinnedBy = "me", updatedAt = 1L), dao.find("m2"))
        }

    @Test
    fun `deleteOrphansOlderThan reclaims only old pins whose message is gone`() =
        runTest {
            message("m_live")
            dao.upsert(PinEntity("m_live", pinnedBy = "me", updatedAt = 1L)) // message present → kept
            dao.upsert(PinEntity("m_gone_old", pinnedBy = "me", updatedAt = 1L)) // orphan, old → deleted
            dao.upsert(PinEntity("m_gone_tomb", pinnedBy = null, updatedAt = 1L)) // an old orphan tombstone → deleted
            dao.upsert(PinEntity("m_gone_new", pinnedBy = "me", updatedAt = 9L)) // orphan but newer than the floor

            dao.deleteOrphansOlderThan(olderThan = 5L)

            assertTrue(dao.find("m_live") != null)
            assertNull(dao.find("m_gone_old"))
            assertNull(dao.find("m_gone_tomb"))
            assertTrue(dao.find("m_gone_new") != null)
        }
}
