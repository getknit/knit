package app.getknit.knit.data.ratchet

import androidx.room3.useReaderConnection
import app.getknit.knit.data.RoomDbTest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Real-SQL coverage for [GroupRootDao.upsert], a hand-written `INSERT … ON CONFLICT DO UPDATE` rather than Room's
 * `@Upsert` (the `GroupDao.upsert` / `PeerDao.upsert` fix: `@Upsert`'s failing INSERT is logged at `E` by
 * SQLCipher). Pins that the swap kept `@Upsert`'s behaviour: a second write for the same group overwrites every
 * non-key column, nulls included, in place.
 */
class GroupRootDaoTest : RoomDbTest() {
    private val dao get() = db.groupRootDao()

    @Test
    fun `a second root write for the same group overwrites every column in place`() =
        runTest {
            dao.upsert(ROOT_A)
            val rowid = rowidOf(GROUP)

            dao.upsert(ROOT_B)

            assertRootEquals(ROOT_B, dao.find(GROUP))
            assertEquals(1, dao.all().size)
            assertEquals("updated where it sat, not deleted and re-inserted", rowid, rowidOf(GROUP))
        }

    @Test
    fun `a null root overwrites a held one, as the entity says`() =
        runTest {
            // The reverse direction of the nullable columns: @Upsert wrote the entity as it stood, nulls and all.
            dao.upsert(ROOT_B)
            dao.upsert(ROOT_A)
            assertRootEquals(ROOT_A, dao.find(GROUP))
        }

    @Test
    fun `a root write for another group is its own row`() =
        runTest {
            dao.upsert(ROOT_A)
            dao.upsert(ROOT_B.copy(groupId = "group-2"))
            assertRootEquals(ROOT_A, dao.find(GROUP))
            assertRootEquals(ROOT_B.copy(groupId = "group-2"), dao.find("group-2"))
        }

    private fun assertRootEquals(
        expected: GroupRootEntity,
        actual: GroupRootEntity?,
    ) {
        assertNotNull(actual)
        actual!!
        assertEquals(expected.groupId, actual.groupId)
        assertArrayEquals(expected.root, actual.root)
        assertEquals(expected.version, actual.version)
        assertEquals(expected.minter, actual.minter)
        assertArrayEquals(expected.prevRoot, actual.prevRoot)
        assertEquals(expected.prevVersion, actual.prevVersion)
        assertEquals(expected.prevExpiresAt, actual.prevExpiresAt)
        assertEquals(expected.firstEligibleAt, actual.firstEligibleAt)
        assertEquals(expected.remintDueAt, actual.remintDueAt)
    }

    private suspend fun rowidOf(groupId: String): Long =
        db.useReaderConnection { connection ->
            connection.usePrepared("SELECT rowid FROM group_roots WHERE groupId = ?") { statement ->
                statement.bindText(1, groupId)
                check(statement.step()) { "no root row for $groupId" }
                statement.getLong(0)
            }
        }

    private companion object {
        const val GROUP = "group-1"

        // Only the mint-grace stamp: the row a group gets before any root exists.
        val ROOT_A = GroupRootEntity(groupId = GROUP, firstEligibleAt = 50L)

        // Every non-key column differs from ROOT_A.
        val ROOT_B =
            GroupRootEntity(
                groupId = GROUP,
                root = ByteArray(32) { 7 },
                version = 2,
                minter = "minter-2",
                prevRoot = ByteArray(32) { 6 },
                prevVersion = 1,
                prevExpiresAt = 9_000L,
                firstEligibleAt = 60L,
                remintDueAt = 70L,
            )
    }
}
