package app.getknit.knit.data.group

import app.getknit.knit.data.RoomDbTest
import app.getknit.knit.data.blob.BlobEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real-SQL coverage for the group table — the leave tombstone, hard delete, the photo-blob ref count and pulls. */
class GroupDaoTest : RoomDbTest() {
    private val dao get() = db.groupDao()

    private fun grp(
        id: String,
        createdAt: Long = 1L,
        photoHash: String? = null,
        left: Boolean = false,
        photoShownHash: String? = photoHash,
    ) = GroupEntity(
        groupId = id,
        name = "",
        members = GroupMembersStore.encode(listOf("me")),
        createdBy = "me",
        createdAt = createdAt,
        photoHash = photoHash,
        left = left,
        photoShownHash = photoShownHash,
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
    fun `countByPhotoHash counts a photo a group shows or has decided on`() =
        runTest {
            // Decided on p2 while p1 still shows (ADR 2026-09.nxcq): both stay referenced.
            dao.upsert(grp("g1", photoHash = "p2", photoShownHash = "p1"))
            assertEquals(1, dao.countByPhotoHash("p1"))
            assertEquals(1, dao.countByPhotoHash("p2"))
        }

    @Test
    fun `awaitingPhoto names the held groups that decided on a photo they do not show yet`() =
        runTest {
            dao.upsert(grp("waiting", photoHash = "p2", photoShownHash = "p1"))
            dao.upsert(grp("bare", photoHash = "p2", photoShownHash = null))
            dao.upsert(grp("shown", photoHash = "p2"))
            dao.upsert(grp("left", photoHash = "p2", photoShownHash = null, left = true))
            dao.upsert(grp("other", photoHash = "p3", photoShownHash = null))
            assertEquals(setOf("waiting", "bare"), dao.awaitingPhoto("p2").map { it.groupId }.toSet())
        }

    @Test
    fun `photoHashesNeedingFetch is the decided photos not shown whose bytes are not local`() =
        runTest {
            db.blobDao().insert(BlobEntity(hash = "landed", mime = "image/jpeg", bytes = byteArrayOf(1)))
            dao.upsert(grp("g1", photoHash = "missing", photoShownHash = "old"))
            dao.upsert(grp("g2", photoHash = "missing", photoShownHash = null))
            dao.upsert(grp("g3", photoHash = "landed", photoShownHash = null))
            dao.upsert(grp("g4", photoHash = "shown"))
            dao.upsert(grp("g5", photoHash = "gone", photoShownHash = null, left = true))
            assertEquals(listOf("missing"), dao.photoHashesNeedingFetch())
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
