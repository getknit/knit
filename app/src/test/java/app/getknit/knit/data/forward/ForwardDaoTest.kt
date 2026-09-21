package app.getknit.knit.data.forward

import app.getknit.knit.data.RoomDbTest
import app.getknit.knit.data.blob.BlobEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
            assertEquals(1, dao.count(now = 0L))
            assertEquals("first", dao.allRows().single().senderId)
        }

    @Test
    fun `liveRowsTo returns the newest live chat frames addressed to one recipient`() =
        runTest {
            dao.insert(fwd("old", recipientId = "bob", sentAt = 1L))
            dao.insert(fwd("mid", recipientId = "bob", sentAt = 5L))
            dao.insert(fwd("new", recipientId = "bob", sentAt = 9L))
            dao.insert(fwd("expired", recipientId = "bob", sentAt = 10L, expiresAt = 50L))
            dao.insert(fwd("other", recipientId = "carol", sentAt = 11L))
            dao.insert(fwd("room", recipientId = null, sentAt = 12L))
            dao.insert(fwd("receipt", recipientId = "bob", sentAt = 13L).copy(type = "receipt"))
            assertEquals(listOf("new", "mid"), dao.liveRowsTo("bob", now = 100L, limit = 2).map { it.id })
            assertEquals(listOf("new", "mid", "old"), dao.liveRowsTo("bob", now = 100L, limit = 10).map { it.id })
            assertTrue(dao.liveRowsTo("nobody", now = 100L, limit = 10).isEmpty())
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

            dao.evictOldestBySender("A", 1, now = 0L) // oldest by sentAt: a0
            assertFalse(dao.exists("a0"))
            assertTrue(dao.exists("a1") && dao.exists("a2") && dao.exists("b0"))

            dao.evictOldestBySender("A", 1, now = 0L) // now both sentAt=5; id ASC picks a1 before a2
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

            dao.evictOldestByGroup("g1", 1, now = 0L) // only g1's oldest (g_old); g2 + broadcast untouched
            dao.evictOldestBroadcast(1, now = 0L) // only broadcast's oldest (bc_old)

            assertEquals(listOf("bc_new", "g_new", "g_other"), dao.allIds().sorted())
        }

    @Test
    fun `evictOldest trims globally by sentAt then id`() =
        runTest {
            dao.insert(fwd("x2", senderId = "A", sentAt = 2L))
            dao.insert(fwd("x1", senderId = "B", sentAt = 1L)) // globally oldest, ignores sender bucket
            dao.insert(fwd("x3", senderId = "A", sentAt = 3L))

            dao.evictOldest(2, now = 0L) // x1 (sentAt 1) then x2 (sentAt 2)
            assertEquals(listOf("x3"), dao.allIds())
        }

    @Test
    fun `count-by-bucket discriminates broadcast from DM, group, and non-chat`() =
        runTest {
            dao.insert(fwd("bc", recipientId = null)) // chat, null recipient + group → broadcast
            dao.insert(fwd("dm", recipientId = "peer")) // chat with a DM recipient → not broadcast
            dao.insert(fwd("gp", recipientId = null, groupId = "g1")) // chat in a group → not broadcast
            dao.insert(fwd("rx", recipientId = null).copy(type = "receipt")) // null/null but not chat → not broadcast

            assertEquals(1, dao.countBroadcast(now = 0L)) // only "bc"
            assertEquals(1, dao.countByGroup("g1", now = 0L))
            assertEquals(4, dao.countBySender("peer", now = 0L)) // every row shares the default sender
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
    fun `quota counts and evictions are live-filtered — expired residue is invisible (work item #8)`() =
        runTest {
            // Buckets mix TTL classes, so an expired short-TTL row can be NEWER by sentAt than a live long-TTL
            // row. If the count saw the residue (here pushing sender A "over quota") the eviction would remove
            // the oldest LIVE row — a row a swept node keeps — de-converging the live sets the digest folds.
            dao.insert(fwd("live_old", senderId = "A", sentAt = 1L, expiresAt = 9_000L))
            dao.insert(fwd("dead_new", senderId = "A", sentAt = 5L, expiresAt = 100L)) // expired at now=1_000
            dao.insert(fwd("live_new", senderId = "A", sentAt = 9L, expiresAt = 9_000L))

            assertEquals("residue must not count against the quota", 2, dao.countBySender("A", now = 1_000L))
            assertEquals(2, dao.count(now = 1_000L))

            dao.evictOldest(1, now = 1_000L) // oldest LIVE row globally is live_old — never dead_new
            assertFalse(dao.exists("live_old"))
            assertTrue("an expired row is left for the sweep, not the eviction", dao.exists("dead_new"))
            assertTrue(dao.exists("live_new"))
        }

    @Test
    fun `liveIdExpiries projects the live (id, expiresAt) set — the digest rebuild source`() =
        runTest {
            dao.insert(fwd("dead", expiresAt = 100L))
            dao.insert(fwd("edge", expiresAt = 1_000L)) // expiresAt == now → still live (>=)
            dao.insert(fwd("live", expiresAt = 9_000L))

            assertEquals(
                setOf(ForwardIdExpiry("edge", 1_000L), ForwardIdExpiry("live", 9_000L)),
                dao.liveIdExpiries(now = 1_000L).toSet(),
            )
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

    /**
     * The Your mesh screen's "carrying now": relayed chat frames from other people that are not addressed to
     * us — so our own sends (origin 1), a DM *to* us (custodied too, ADR 018), a non-chat type and an expired
     * row are all out, while a DM to someone else, a group frame and a broadcast post are all in. Room
     * re-emits the count on a later insert.
     */
    @Test
    fun `observeCarriedForOthers counts only live relayed chat frames from others not addressed to me`() =
        runTest {
            dao.insert(fwd("dm_other", senderId = "alice", recipientId = "carol"))
            dao.insert(fwd("group", senderId = "alice", recipientId = null, groupId = "g1"))
            dao.insert(fwd("room", senderId = "alice", recipientId = null))
            dao.insert(fwd("mine", senderId = "me", recipientId = "carol").copy(origin = 1))
            dao.insert(fwd("to_me", senderId = "alice", recipientId = "me"))
            dao.insert(fwd("receipt", senderId = "alice", recipientId = "carol").copy(type = "receipt"))
            dao.insert(fwd("expired", senderId = "alice", recipientId = "carol", expiresAt = 50L))
            // A frame we authored but that somehow sits as origin 0 (a wipe-and-re-serve) is still not "for others".
            dao.insert(fwd("mine_relayed", senderId = "me", recipientId = "carol"))
            assertEquals(3, dao.observeCarriedForOthers(me = "me", now = 100L).first())

            dao.insert(fwd("dm_other_2", senderId = "bob", recipientId = "carol"))
            assertEquals(4, dao.observeCarriedForOthers(me = "me", now = 100L).first())
        }

    @Test
    fun `liveGroupChatRows is the group chat from others, live, newest arrival first (work item #63)`() =
        runTest {
            dao.insert(fwd("g_old", senderId = "alice", recipientId = null, groupId = "g1", sentAt = 1L))
            dao.insert(fwd("g_new", senderId = "bob", recipientId = null, groupId = "g2", sentAt = 5L))
            dao.insert(fwd("g_mine", senderId = "me", recipientId = null, groupId = "g1", sentAt = 6L)) // own send
            dao.insert(fwd("g_gone", senderId = "alice", recipientId = null, groupId = "g1", sentAt = 7L, expiresAt = 50L))
            dao.insert(fwd("g_update", senderId = "alice", recipientId = null, groupId = "g1", sentAt = 8L).copy(type = "groupupdate"))
            dao.insert(fwd("dm", senderId = "alice", recipientId = "me", sentAt = 9L))
            dao.insert(fwd("room", senderId = "alice", recipientId = null, sentAt = 10L))

            assertEquals(listOf("g_new", "g_old"), dao.liveGroupChatRows(me = "me", now = 100L).map { it.id })
            assertTrue(dao.liveGroupChatRows(me = "me", now = 10_001L).isEmpty())
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
