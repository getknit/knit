package app.getknit.knit.data.peer

import app.getknit.knit.data.RoomDbTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real-SQL coverage for the peer table — the TOFU pin/verified flag + the avatar-blob ref count for GC. */
class PeerDaoTest : RoomDbTest() {
    private val dao get() = db.peerDao()

    @Test
    fun `upsert then findByNodeId round-trips the pinned key and profile fields`() =
        runTest {
            dao.upsert(
                PeerEntity(
                    nodeId = "a",
                    name = "Ada",
                    status = "hi",
                    avatarHash = "av",
                    pubKey = "KEY",
                    verified = true,
                    deviceTag = "tag",
                    updatedAt = 5L,
                ),
            )
            val got = dao.findByNodeId("a")!!
            assertEquals("Ada", got.name)
            assertEquals("KEY", got.pubKey)
            assertTrue(got.verified)
            assertEquals("tag", got.deviceTag)
        }

    @Test
    fun `setVerified flips only the verified flag`() =
        runTest {
            dao.upsert(PeerEntity(nodeId = "a", pubKey = "KEY", verified = false))
            dao.setVerified("a", true)
            assertTrue(dao.findByNodeId("a")!!.verified)
            dao.setVerified("a", false)
            assertFalse(dao.findByNodeId("a")!!.verified)
        }

    @Test
    fun `countByAvatarHash counts peers referencing that avatar blob`() =
        runTest {
            dao.upsert(PeerEntity(nodeId = "a", avatarHash = "h1"))
            dao.upsert(PeerEntity(nodeId = "b", avatarHash = "h1"))
            dao.upsert(PeerEntity(nodeId = "c", avatarHash = "h2"))
            assertEquals(2, dao.countByAvatarHash("h1"))
            assertEquals(1, dao.countByAvatarHash("h2"))
            assertEquals(0, dao.countByAvatarHash("none"))
        }

    @Test
    fun `upsert replaces the row on the same nodeId (a profile update)`() =
        runTest {
            dao.upsert(PeerEntity(nodeId = "a", name = "Ada", updatedAt = 1L))
            dao.upsert(PeerEntity(nodeId = "a", name = "Ada Renamed", updatedAt = 2L))
            assertEquals("Ada Renamed", dao.findByNodeId("a")!!.name)
            assertEquals(1, dao.observeAll().first().size)
        }

    @Test
    fun `observeAll orders by name ascending`() =
        runTest {
            dao.upsert(PeerEntity(nodeId = "a", name = "Zed"))
            dao.upsert(PeerEntity(nodeId = "b", name = "Amy"))
            assertEquals(listOf("Amy", "Zed"), dao.observeAll().first().map { it.name })
        }
}
