package app.getknit.knit.data.ratchet

import androidx.room3.useReaderConnection
import app.getknit.knit.data.RoomDbTest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Real-SQL coverage for [GroupRatchetDao]'s three upserts. Each is a hand-written `INSERT … ON CONFLICT DO UPDATE`
 * rather than Room's `@Upsert` (the `GroupDao.upsert` / `PeerDao.upsert` fix: `@Upsert`'s failing INSERT is
 * logged at `E` by SQLCipher). These pin that the swap kept `@Upsert`'s behaviour exactly: a second write to the
 * same full primary key overwrites every non-key column (a column missing from the SET list fails the field-by-field
 * read-back), lands in place (same rowid, never a delete and re-insert), and a write differing in any one key
 * column is a row of its own.
 */
class GroupRatchetDaoTest : RoomDbTest() {
    private val dao get() = db.groupRatchetDao()

    // --- group_send_chains: PK (groupId, epoch) ---

    @Test
    fun `a second send chain write to the same epoch overwrites every column in place`() =
        runTest {
            dao.upsertSendChain(SEND_A)
            val rowid = sendRowid(GROUP, epoch = 3)

            dao.upsertSendChain(SEND_B)

            assertSendEquals(SEND_B, dao.sendChains(GROUP).single())
            assertEquals("updated where it sat, not deleted and re-inserted", rowid, sendRowid(GROUP, epoch = 3))
        }

    @Test
    fun `a send chain write under another epoch or group is its own row`() =
        runTest {
            dao.upsertSendChain(SEND_A)
            dao.upsertSendChain(SEND_B.copy(epoch = 4))
            dao.upsertSendChain(SEND_B.copy(groupId = OTHER_GROUP))

            assertEquals(listOf(4, 3), dao.sendChains(GROUP).map { it.epoch })
            assertSendEquals(SEND_A, dao.sendChains(GROUP).single { it.epoch == 3 })
            assertSendEquals(SEND_B.copy(groupId = OTHER_GROUP), dao.sendChains(OTHER_GROUP).single())
        }

    // --- group_recv_chains: PK (groupId, senderId, epoch, mintedAt) ---

    @Test
    fun `a second receive chain write to the same mint overwrites every column in place`() =
        runTest {
            dao.upsertRecvChain(RECV_A)
            val rowid = recvRowid(RECV_A)

            dao.upsertRecvChain(RECV_B)

            assertRecvEquals(RECV_B, dao.recvChains(GROUP, SENDER, epoch = 2).single())
            assertEquals("updated where it sat, not deleted and re-inserted", rowid, recvRowid(RECV_A))
        }

    @Test
    fun `a receive chain write differing in one key column is its own row and leaves the first untouched`() =
        runTest {
            dao.upsertRecvChain(RECV_A)
            val rowid = recvRowid(RECV_A)

            // A wiped sender's re-mint of the same epoch number: another mintedAt, both eras kept.
            dao.upsertRecvChain(RECV_B.copy(mintedAt = 2_000L))
            dao.upsertRecvChain(RECV_B.copy(epoch = 5))
            dao.upsertRecvChain(RECV_B.copy(senderId = "another-sender"))
            dao.upsertRecvChain(RECV_B.copy(groupId = OTHER_GROUP))

            val sameEpoch = dao.recvChains(GROUP, SENDER, epoch = 2)
            assertEquals(listOf(2_000L, 1_000L), sameEpoch.map { it.mintedAt })
            assertRecvEquals(RECV_A, sameEpoch.single { it.mintedAt == 1_000L })
            assertEquals(rowid, recvRowid(RECV_A))
            assertRecvEquals(RECV_B.copy(epoch = 5), dao.recvChains(GROUP, SENDER, epoch = 5).single())
            assertRecvEquals(RECV_B.copy(senderId = "another-sender"), dao.recvChains(GROUP, "another-sender", epoch = 2).single())
            assertRecvEquals(RECV_B.copy(groupId = OTHER_GROUP), dao.recvChains(OTHER_GROUP, SENDER, epoch = 2).single())
        }

    // --- group_key_sends: PK (groupId, memberId) ---

    @Test
    fun `a second seed outbox write for the same member overwrites every column in place`() =
        runTest {
            dao.upsertKeySend(KEY_SEND_A)
            val rowid = keySendRowid(GROUP, MEMBER)

            dao.upsertKeySend(KEY_SEND_B)

            assertEquals(KEY_SEND_B, dao.keySend(GROUP, MEMBER))
            assertEquals(1, dao.keySends(GROUP).size)
            assertEquals("updated where it sat, not deleted and re-inserted", rowid, keySendRowid(GROUP, MEMBER))
        }

    @Test
    fun `a seed outbox write for another member or group is its own row`() =
        runTest {
            dao.upsertKeySend(KEY_SEND_A)
            dao.upsertKeySend(KEY_SEND_B.copy(memberId = "another-member"))
            dao.upsertKeySend(KEY_SEND_B.copy(groupId = OTHER_GROUP))

            assertEquals(KEY_SEND_A, dao.keySend(GROUP, MEMBER))
            assertEquals(KEY_SEND_B.copy(memberId = "another-member"), dao.keySend(GROUP, "another-member"))
            assertEquals(KEY_SEND_B.copy(groupId = OTHER_GROUP), dao.keySend(OTHER_GROUP, MEMBER))
        }

    private fun assertSendEquals(
        expected: GroupSendChainEntity,
        actual: GroupSendChainEntity?,
    ) {
        assertNotNull(actual)
        actual!!
        assertEquals(expected.groupId, actual.groupId)
        assertEquals(expected.epoch, actual.epoch)
        assertArrayEquals(expected.seed, actual.seed)
        assertArrayEquals(expected.chainKey, actual.chainKey)
        assertEquals(expected.count, actual.count)
        assertEquals(expected.mintedAt, actual.mintedAt)
        assertArrayEquals(expected.export, actual.export)
        assertEquals(expected.updatedAt, actual.updatedAt)
    }

    private fun assertRecvEquals(
        expected: GroupRecvChainEntity,
        actual: GroupRecvChainEntity?,
    ) {
        assertNotNull(actual)
        actual!!
        assertEquals(expected.groupId, actual.groupId)
        assertEquals(expected.senderId, actual.senderId)
        assertEquals(expected.epoch, actual.epoch)
        assertEquals(expected.mintedAt, actual.mintedAt)
        assertArrayEquals(expected.chainKey, actual.chainKey)
        assertEquals(expected.next, actual.next)
        assertEquals(expected.lastUsedAt, actual.lastUsedAt)
    }

    private suspend fun sendRowid(
        groupId: String,
        epoch: Int,
    ): Long =
        db.useReaderConnection { connection ->
            connection.usePrepared("SELECT rowid FROM group_send_chains WHERE groupId = ? AND epoch = ?") { statement ->
                statement.bindText(1, groupId)
                statement.bindLong(2, epoch.toLong())
                check(statement.step()) { "no send chain row for $groupId/$epoch" }
                statement.getLong(0)
            }
        }

    private suspend fun recvRowid(row: GroupRecvChainEntity): Long =
        db.useReaderConnection { connection ->
            connection.usePrepared(
                "SELECT rowid FROM group_recv_chains WHERE groupId = ? AND senderId = ? AND epoch = ? AND mintedAt = ?",
            ) { statement ->
                statement.bindText(1, row.groupId)
                statement.bindText(2, row.senderId)
                statement.bindLong(3, row.epoch.toLong())
                statement.bindLong(4, row.mintedAt)
                check(statement.step()) { "no receive chain row for $row" }
                statement.getLong(0)
            }
        }

    private suspend fun keySendRowid(
        groupId: String,
        memberId: String,
    ): Long =
        db.useReaderConnection { connection ->
            connection.usePrepared("SELECT rowid FROM group_key_sends WHERE groupId = ? AND memberId = ?") { statement ->
                statement.bindText(1, groupId)
                statement.bindText(2, memberId)
                check(statement.step()) { "no seed outbox row for $groupId/$memberId" }
                statement.getLong(0)
            }
        }

    private companion object {
        const val GROUP = "group-1"
        const val OTHER_GROUP = "group-2"
        const val SENDER = "sender-1"
        const val MEMBER = "member-1"

        val SEND_A =
            GroupSendChainEntity(
                groupId = GROUP,
                epoch = 3,
                seed = ByteArray(32) { 1 },
                chainKey = ByteArray(32) { 2 },
                count = 0,
                mintedAt = 100L,
                export = ByteArray(32) { 3 },
                updatedAt = 100L,
            )

        // Every non-key column differs from SEND_A.
        val SEND_B =
            SEND_A.copy(
                seed = ByteArray(0),
                chainKey = ByteArray(32) { 12 },
                count = 7,
                mintedAt = 200L,
                export = ByteArray(32) { 13 },
                updatedAt = 300L,
            )

        val RECV_A =
            GroupRecvChainEntity(
                groupId = GROUP,
                senderId = SENDER,
                epoch = 2,
                mintedAt = 1_000L,
                chainKey = ByteArray(32) { 4 },
                next = 0,
                lastUsedAt = 1_000L,
            )

        // Every non-key column differs from RECV_A.
        val RECV_B = RECV_A.copy(chainKey = ByteArray(32) { 14 }, next = 9, lastUsedAt = 5_000L)

        val KEY_SEND_A =
            GroupKeySendEntity(groupId = GROUP, memberId = MEMBER, sentEpoch = 1, sentAt = 10L, ackedEpoch = 0, ackedAt = 0L)

        // Every non-key column differs from KEY_SEND_A, and the two epochs differ from each other, so a SET list that
        // swapped sentEpoch and ackedEpoch would read back wrong.
        val KEY_SEND_B = KEY_SEND_A.copy(sentEpoch = 3, sentAt = 20L, ackedEpoch = 2, ackedAt = 30L)
    }
}
