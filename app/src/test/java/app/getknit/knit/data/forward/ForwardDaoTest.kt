package app.getknit.knit.data.forward

import app.getknit.knit.data.RoomDbTest
import app.getknit.knit.data.blob.BlobEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Executes the **real** [ForwardDao] SQL against an in-memory Room DB (finding #5). Until now the custody
 * eviction/quota/TTL/anti-join queries were verified only against `ForwardRepositoryTest`'s hand-mirrored
 * `FakeForwardDao` — a `HashMap` that re-implements the ordering in Kotlin, so a divergence between the fake
 * and the SQLite would pass. These tests run the actual queries, and in particular exercise
 * [ForwardDao.attachmentHashesNeedingFetch] (the `blobs` anti-join the fake explicitly punts on) and the
 * `DELETE ... WHERE id IN (SELECT ... ORDER BY sentAt ASC, id ASC LIMIT :n)` evictions whose frame-global
 * `(sentAt, id)` ordering is what keeps every node's carried set convergent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ForwardDaoTest : RoomDbTest() {
    private val dao get() = db.forwardDao()

    @Test
    fun `insert ignores a duplicate frame id (OnConflict IGNORE)`() =
        runTest {
            dao.insert(fwd("f1", senderId = "first"))
            dao.insert(fwd("f1", senderId = "second")) // same id — must be silently ignored
            assertEquals(1, dao.count())
            assertEquals("first", dao.allRows().single().senderId)
        }

    @Test
    fun `deleteExpired removes only rows strictly past their frame-global expiry`() =
        runTest {
            dao.insert(fwd("gone", expiresAt = 999L))
            dao.insert(fwd("keep", expiresAt = 1_000L)) // expiresAt == now is NOT expired (query is `< :now`)
            assertEquals(1, dao.deleteExpired(now = 1_000L))
            assertEquals(listOf("keep"), dao.allIds())
        }

    @Test
    fun `evictOldestBySender trims that bucket oldest-first by sentAt then id`() =
        runTest {
            dao.insert(fwd("a0", senderId = "A", sentAt = 1L))
            dao.insert(fwd("a1", senderId = "A", sentAt = 5L)) // ties a2 on sentAt — id breaks it
            dao.insert(fwd("a2", senderId = "A", sentAt = 5L))
            dao.insert(fwd("b0", senderId = "B", sentAt = 1L)) // other bucket — must be untouched

            dao.evictOldestBySender("A", 1) // oldest by sentAt: a0
            assertFalse(dao.exists("a0"))
            assertTrue(dao.exists("a1") && dao.exists("a2") && dao.exists("b0"))

            dao.evictOldestBySender("A", 1) // now both sentAt=5; id ASC picks a1 before a2
            assertFalse(dao.exists("a1"))
            assertEquals(listOf("a2", "b0"), dao.allIds().sorted())
        }

    @Test
    fun `evictOldestByGroup and evictOldestBroadcast stay within their bucket`() =
        runTest {
            dao.insert(fwd("g_old", groupId = "g1", recipientId = null, sentAt = 1L))
            dao.insert(fwd("g_new", groupId = "g1", recipientId = null, sentAt = 9L))
            dao.insert(fwd("g_other", groupId = "g2", recipientId = null, sentAt = 1L))
            dao.insert(fwd("bc_old", recipientId = null, sentAt = 1L)) // broadcast: chat, null recipient+group
            dao.insert(fwd("bc_new", recipientId = null, sentAt = 9L))

            dao.evictOldestByGroup("g1", 1) // only g1's oldest (g_old); g2 + broadcast untouched
            dao.evictOldestBroadcast(1) // only broadcast's oldest (bc_old)

            assertEquals(listOf("bc_new", "g_new", "g_other"), dao.allIds().sorted())
        }

    @Test
    fun `evictOldest trims globally by sentAt then id`() =
        runTest {
            dao.insert(fwd("x2", senderId = "A", sentAt = 2L))
            dao.insert(fwd("x1", senderId = "B", sentAt = 1L)) // globally oldest, ignores sender bucket
            dao.insert(fwd("x3", senderId = "A", sentAt = 3L))

            dao.evictOldest(2) // x1 (sentAt 1) then x2 (sentAt 2)
            assertEquals(listOf("x3"), dao.allIds())
        }

    @Test
    fun `count-by-bucket discriminates broadcast from DM, group, and non-chat`() =
        runTest {
            dao.insert(fwd("bc", recipientId = null)) // chat, null recipient + group → broadcast
            dao.insert(fwd("dm", recipientId = "peer")) // chat with a DM recipient → not broadcast
            dao.insert(fwd("gp", recipientId = null, groupId = "g1")) // chat in a group → not broadcast
            dao.insert(fwd("rx", recipientId = null).copy(type = "receipt")) // null/null but not chat → not broadcast

            assertEquals(1, dao.countBroadcast()) // only "bc"
            assertEquals(1, dao.countByGroup("g1"))
            assertEquals(4, dao.countBySender("peer")) // every row shares the default sender
        }

    @Test
    fun `attachmentHashesNeedingFetch excludes held blobs and nulls and dedupes (the anti-join)`() =
        runTest {
            dao.insert(fwd("f1", attachmentHash = "H1"))
            dao.insert(fwd("f2", attachmentHash = "H2")) // H2 is already held below → excluded
            dao.insert(fwd("f3", attachmentHash = "H1")) // duplicate reference → H1 must appear once
            dao.insert(fwd("f4", attachmentHash = null)) // null → excluded
            db.blobDao().insert(BlobEntity(hash = "H2", mime = "image/jpeg", bytes = ByteArray(0)))

            assertEquals(listOf("H1"), dao.attachmentHashesNeedingFetch())
            assertEquals(2, dao.countByAttachmentHash("H1"))
        }

    @Test
    fun `liveRows and liveIds honor the expiresAt floor and receivedAt ordering`() =
        runTest {
            dao.insert(fwd("dead", expiresAt = 100L).copy(receivedAt = 1L)) // expired at now=1_000
            dao.insert(fwd("edge", expiresAt = 1_000L).copy(receivedAt = 2L)) // expiresAt == now → still live (>=)
            dao.insert(fwd("live", expiresAt = 9_000L).copy(receivedAt = 7L))

            assertEquals(setOf("edge", "live"), dao.liveIds(now = 1_000L).toSet())
            assertEquals(listOf("live", "edge"), dao.liveRows(now = 1_000L).map { it.id }) // receivedAt DESC
        }

    @Test
    fun `recipientOf, exists, and delete address rows by id`() =
        runTest {
            dao.insert(fwd("dm", recipientId = "bob"))
            dao.insert(fwd("bc", recipientId = null))

            assertEquals("bob", dao.recipientOf("dm"))
            assertEquals(null, dao.recipientOf("bc"))
            assertEquals(null, dao.recipientOf("missing"))

            dao.delete("dm")
            assertFalse(dao.exists("dm"))
            assertTrue(dao.exists("bc"))
        }

    /** Builds a [ForwardEntity] with convergence-relevant fields caller-set and the rest defaulted. */
    private fun fwd(
        id: String,
        senderId: String = "peer",
        sentAt: Long = 1L,
        recipientId: String? = "peer",
        groupId: String? = null,
        expiresAt: Long = 10_000L,
        attachmentHash: String? = null,
    ) = ForwardEntity(
        id = id,
        recipientId = recipientId,
        groupId = groupId,
        senderId = senderId,
        type = "chat", // the one non-chat test uses .copy(type = ...) (keeps this builder ≤ 8 params)
        origin = 0,
        signed = ByteArray(0),
        sig = ByteArray(0),
        sentAt = sentAt,
        receivedAt = sentAt, // tests that vary receivedAt use .copy() (keeps this builder ≤ 8 params)
        expiresAt = expiresAt,
        attachmentHash = attachmentHash,
    )
}
