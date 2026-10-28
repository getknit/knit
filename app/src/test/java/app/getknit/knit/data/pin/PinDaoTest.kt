package app.getknit.knit.data.pin

import app.getknit.knit.data.RoomDbTest
import app.getknit.knit.data.message.MessageEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Executes the **real** [PinDao] SQL, in the style of `ReactionDaoTest`: the thread reads are a join against `messages`
 * (there is deliberately no FK), so they narrow to one thread and drop a pin whose message is gone; the startup sweep
 * reclaims those rows and pins on a blocked sender's messages.
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
    fun `observePinnedIn returns one thread's pins, newest pin first, with their messages`() =
        runTest {
            message("m1")
            message("m2")
            message("other", conversationId = "carol", senderId = "carol")
            dao.insert(PinEntity("m1", pinnedAt = 10L))
            dao.insert(PinEntity("m2", pinnedAt = 30L))
            dao.insert(PinEntity("other", pinnedAt = 50L)) // another thread → excluded
            dao.insert(PinEntity("gone", pinnedAt = 60L)) // no message on this phone → excluded

            val pins = dao.observePinnedIn("bob").first()

            assertEquals(listOf("m2", "m1"), pins.map { it.message.id })
            assertEquals(listOf(30L, 10L), pins.map { it.pinnedAt })
            assertEquals("m2", pins.first().message.body)
        }

    @Test
    fun `a same-millisecond tie orders by message id so the bar never flips`() =
        runTest {
            message("a")
            message("b")
            dao.insert(PinEntity("a", pinnedAt = 5L))
            dao.insert(PinEntity("b", pinnedAt = 5L))

            assertEquals(listOf("b", "a"), dao.observePinnedIn("bob").first().map { it.message.id })
            assertEquals(listOf("a", "b"), dao.pinIdsIn("bob"))
        }

    @Test
    fun `pinIdsIn lists one thread's pins oldest first`() =
        runTest {
            message("m1")
            message("m2")
            message("elsewhere", conversationId = "carol")
            dao.insert(PinEntity("m1", pinnedAt = 20L))
            dao.insert(PinEntity("m2", pinnedAt = 10L))
            dao.insert(PinEntity("elsewhere", pinnedAt = 5L))

            assertEquals(listOf("m2", "m1"), dao.pinIdsIn("bob"))
        }

    @Test
    fun `newestPinIn is the thread's highest stamp and null for a thread with no pins`() =
        runTest {
            message("m1")
            message("m2")
            message("elsewhere", conversationId = "carol")
            dao.insert(PinEntity("m1", pinnedAt = 20L))
            dao.insert(PinEntity("m2", pinnedAt = 70L))
            dao.insert(PinEntity("elsewhere", pinnedAt = 900L)) // another thread's stamp is not this one's floor

            assertEquals(70L, dao.newestPinIn("bob"))
            assertNull(dao.newestPinIn("dave"))
        }

    @Test
    fun `conversationOf names a held message's thread and nothing for one this phone lacks`() =
        runTest {
            message("m1", conversationId = "g-hikers")

            assertEquals("g-hikers", dao.conversationOf("m1"))
            assertNull(dao.conversationOf("missing"))
        }

    @Test
    fun `delete removes only that message's pin and says whether there was one`() =
        runTest {
            dao.insert(PinEntity("m1", pinnedAt = 1L))
            dao.insert(PinEntity("m2", pinnedAt = 1L))

            assertEquals(1, dao.delete("m1"))
            assertEquals(0, dao.delete("m1"))

            assertFalse(dao.isPinned("m1"))
            assertTrue(dao.isPinned("m2"))
        }

    @Test
    fun `deleteFromSender unpins everything one sender wrote, in every thread`() =
        runTest {
            message("mal-1", conversationId = "g-hikers", senderId = "mallory")
            message("mal-2", conversationId = "g-climbers", senderId = "mallory")
            message("sam-1", conversationId = "g-hikers", senderId = "sam")
            listOf("mal-1", "mal-2", "sam-1").forEach { dao.insert(PinEntity(it, pinnedAt = 1L)) }

            dao.deleteFromSender("mallory")

            assertFalse(dao.isPinned("mal-1"))
            assertFalse(dao.isPinned("mal-2"))
            assertTrue(dao.isPinned("sam-1"))
        }

    @Test
    fun `deleteOrphans reclaims pins whose message is gone or whose sender is blocked`() =
        runTest {
            message("m_live")
            message("m_blocked", senderId = "mallory")
            listOf("m_live", "m_blocked", "m_gone").forEach { dao.insert(PinEntity(it, pinnedAt = 1L)) }

            dao.deleteOrphans(blocked = setOf("mallory"))

            assertTrue(dao.isPinned("m_live"))
            assertFalse(dao.isPinned("m_blocked"))
            assertFalse(dao.isPinned("m_gone"))
        }

    @Test
    fun `deleteOrphans with nobody blocked reclaims only the orphans`() =
        runTest {
            message("m_live", senderId = "mallory")
            dao.insert(PinEntity("m_live", pinnedAt = 1L))
            dao.insert(PinEntity("m_gone", pinnedAt = 1L))

            dao.deleteOrphans(blocked = emptySet())

            assertTrue(dao.isPinned("m_live"))
            assertFalse(dao.isPinned("m_gone"))
        }
}
