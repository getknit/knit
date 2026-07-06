package app.getknit.knit.data.group

import app.getknit.knit.data.RoomDbTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real-SQL coverage for the group table — the leave tombstone, hard delete, and photo-blob ref count. */
class GroupDaoTest : RoomDbTest() {
    private val dao get() = db.groupDao()

    private fun grp(
        id: String,
        createdAt: Long = 1L,
        photoHash: String? = null,
        left: Boolean = false,
    ) = GroupEntity(
        groupId = id,
        name = "",
        members = GroupMembersStore.encode(listOf("me")),
        createdBy = "me",
        createdAt = createdAt,
        photoHash = photoHash,
        left = left,
    )

    @Test
    fun `upsert then findById round-trips`() =
        runTest {
            dao.upsert(grp("g1", photoHash = "p"))
            assertEquals("p", dao.findById("g1")!!.photoHash)
        }

    @Test
    fun `markLeft sets the leave tombstone without deleting the row`() =
        runTest {
            dao.upsert(grp("g1"))
            dao.markLeft("g1")
            assertTrue(dao.findById("g1")!!.left)
        }

    @Test
    fun `deleteById removes the row entirely`() =
        runTest {
            dao.upsert(grp("g1"))
            dao.deleteById("g1")
            assertNull(dao.findById("g1"))
        }

    @Test
    fun `countByPhotoHash counts groups referencing that photo`() =
        runTest {
            dao.upsert(grp("g1", photoHash = "p1"))
            dao.upsert(grp("g2", photoHash = "p1"))
            dao.upsert(grp("g3", photoHash = "p2"))
            assertEquals(2, dao.countByPhotoHash("p1"))
            assertEquals(0, dao.countByPhotoHash("none"))
        }

    @Test
    fun `observeAll orders by createdAt descending`() =
        runTest {
            dao.upsert(grp("g1", createdAt = 1L))
            dao.upsert(grp("g2", createdAt = 3L))
            dao.upsert(grp("g3", createdAt = 2L))
            assertEquals(listOf("g2", "g3", "g1"), dao.observeAll().first().map { it.groupId })
        }
}
