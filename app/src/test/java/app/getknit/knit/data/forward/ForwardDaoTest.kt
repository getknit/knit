package app.getknit.knit.data.forward

import app.getknit.knit.data.RoomDbTest
import app.getknit.knit.data.blob.BlobEntity
import app.getknit.knit.data.message.NeededAttachment
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
 * [ForwardDao.attachmentsNeedingFetch] (the `blobs` anti-join the fake explicitly punts on) and the
 * evictions — the buckets' newest-N by `(sentAt, id)` and the global cap's largest-bucket-first (ADR 2026-10.7mqm) —
 * whose frame-global keys are what keep every node's carried set convergent.
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
    fun `liveRowsToFromOthers leaves out our own frames to that recipient`() =
        runTest {
            dao.insert(fwd("mine", senderId = "me", recipientId = "bob", sentAt = 9L))
            dao.insert(fwd("carols", senderId = "carol", recipientId = "bob", sentAt = 5L))
            dao.insert(fwd("daves", senderId = "dave", recipientId = "bob", sentAt = 7L))
            dao.insert(fwd("expired", senderId = "carol", recipientId = "bob", sentAt = 8L, expiresAt = 50L))
            dao.insert(fwd("to-erin", senderId = "carol", recipientId = "erin", sentAt = 6L))
            dao.insert(fwd("receipt", senderId = "carol", recipientId = "bob", sentAt = 10L).copy(type = "receipt"))
            dao.insert(fwd("room", senderId = "carol", recipientId = null, sentAt = 11L))
            assertEquals(listOf("daves", "carols"), dao.liveRowsToFromOthers("bob", me = "me", now = 100L, limit = 10).map { it.id })
            assertEquals(listOf("daves"), dao.liveRowsToFromOthers("bob", me = "me", now = 100L, limit = 1).map { it.id })
        }

    @Test
    fun `liveRowsByIds returns the live rows among the ids, newest first`() =
        runTest {
            dao.insert(fwd("a", sentAt = 1L))
            dao.insert(fwd("b", sentAt = 5L))
            dao.insert(fwd("expired", sentAt = 9L, expiresAt = 50L))
            dao.insert(fwd("unasked", sentAt = 7L))
            assertEquals(listOf("b", "a"), dao.liveRowsByIds(listOf("a", "b", "expired", "missing"), now = 100L).map { it.id })
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
    fun `deleteLongerThan removes only rows whose signed bytes are longer than the cap`() =
        runTest {
            // #227: the sweep's reclaim of rows a build without the byte cap stored. Exactly the cap stays.
            dao.insert(fwd("over").copy(signed = ByteArray(101)))
            dao.insert(fwd("at").copy(signed = ByteArray(100)))
            dao.insert(fwd("under"))
            assertEquals(1, dao.deleteLongerThan(100))
            assertEquals(listOf("at", "under"), dao.allIds().sorted())
            assertEquals(0, dao.deleteLongerThan(100))
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
    fun `evictFairest takes the oldest frame of the sender holding the most`() =
        runTest {
            // ADR 2026-10.7mqm: not the globally oldest frame (h1), which a flood used to win every time.
            dao.insert(fwd("h1", senderId = "honest", sentAt = 1L))
            dao.insert(fwd("s2", senderId = "sybil", sentAt = 3L))
            dao.insert(fwd("s1", senderId = "sybil", sentAt = 2L))
            dao.insert(fwd("s3", senderId = "sybil", sentAt = 4L))

            dao.evictFairest(now = 0L)
            assertEquals(listOf("h1", "s2", "s3"), dao.allIds().sorted())
            dao.evictFairest(now = 0L) // sybil still holds two to honest's one
            assertEquals(listOf("h1", "s3"), dao.allIds().sorted())
            dao.evictFairest(now = 0L) // one each: the bucket whose oldest frame is oldest loses it
            assertEquals(listOf("s3"), dao.allIds())
        }

    @Test
    fun `evictFairest breaks a tie for most by the oldest frame, then its id`() =
        runTest {
            dao.insert(fwd("b1", senderId = "B", sentAt = 5L))
            dao.insert(fwd("b2", senderId = "B", sentAt = 9L))
            dao.insert(fwd("a1", senderId = "A", sentAt = 6L))
            dao.insert(fwd("a2", senderId = "A", sentAt = 7L))
            dao.insert(fwd("c1", senderId = "C", sentAt = 1L)) // the oldest frame of all, in the smaller bucket

            dao.evictFairest(now = 0L)
            assertEquals("B's oldest (5) is older than A's (6)", listOf("a1", "a2", "b2", "c1"), dao.allIds().sorted())

            dao.insert(fwd("d2", senderId = "D", sentAt = 3L))
            dao.insert(fwd("d1", senderId = "D", sentAt = 3L))
            dao.evictFairest(now = 0L) // A and D hold two each; D's oldest is older, and of its two at 3 the lower id goes
            assertEquals(listOf("a1", "a2", "b2", "c1", "d2"), dao.allIds().sorted())
        }

    @Test
    fun `evictFairest counts and chooses live rows only`() =
        runTest {
            // Expired residue would make A the largest bucket on an unswept node only (work item #8).
            dao.insert(fwd("a_dead1", senderId = "A", sentAt = 1L, expiresAt = 100L))
            dao.insert(fwd("a_dead2", senderId = "A", sentAt = 2L, expiresAt = 100L))
            dao.insert(fwd("a_live", senderId = "A", sentAt = 3L, expiresAt = 9_000L))
            dao.insert(fwd("b_old", senderId = "B", sentAt = 4L, expiresAt = 9_000L))
            dao.insert(fwd("b_new", senderId = "B", sentAt = 5L, expiresAt = 9_000L))

            dao.evictFairest(now = 1_000L)
            assertFalse("B holds two live rows to A's one", dao.exists("b_old"))
            assertTrue(dao.exists("a_live"))
            assertTrue("an expired row is left for the sweep, not the eviction", dao.exists("a_dead1") && dao.exists("a_dead2"))
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
    fun `attachmentsNeedingFetch excludes held blobs and nulls and dedupes (the anti-join)`() =
        runTest {
            dao.insert(fwd("f1", attachmentHash = "H1", senderId = "relayed", sentAt = 9L))
            dao.insert(fwd("f2", attachmentHash = "H2")) // H2 is already held below → excluded
            dao.insert(fwd("f3", attachmentHash = "H1", sentAt = 1L)) // duplicate → H1 once, under the earliest sender
            dao.insert(fwd("f4", attachmentHash = null)) // null → excluded
            db.blobDao().insert(BlobEntity(hash = "H2", mime = "image/jpeg", bytes = ByteArray(0)))

            assertEquals(listOf(NeededAttachment("H1", "peer")), dao.attachmentsNeedingFetch(now = 0L))
            assertEquals(2, dao.countByAttachmentHash("H1"))
        }

    @Test
    fun `the carrier's attachment reads see live frames only, by liveIds' expiry predicate`() =
        runTest {
            // #195: a frame past its TTL is carried by nobody, and the 60 s tick re-armed a full-budget want from it until
            // the prune swept the row.
            dao.insert(fwd("dead", attachmentHash = "H1", expiresAt = 100L))
            dao.insert(fwd("edge", attachmentHash = "H2", expiresAt = 1_000L)) // expiresAt == now → still live (>=)
            // receivedAt apart from sentAt, so the projection is pinned to the frame's own clock.
            dao.insert(fwd("live", attachmentHash = "H3", expiresAt = 9_000L, sentAt = 5L).copy(receivedAt = 7L))
            // A dead frame naming H3 too, earlier and by someone else: the author a re-arm asks is the live frame's.
            dao.insert(fwd("dead-earlier", senderId = "gone", attachmentHash = "H3", expiresAt = 100L, sentAt = 1L))

            assertEquals(setOf("H2", "H3"), dao.attachmentsNeedingFetch(now = 1_000L).mapTo(HashSet()) { it.hash })
            assertEquals("peer", dao.attachmentsNeedingFetch(now = 1_000L).single { it.hash == "H3" }.senderId)
            assertEquals(setOf("H2", "H3"), dao.carriedAttachments(now = 1_000L).mapTo(HashSet()) { it.hash })
            assertEquals("each with its frame's sentAt", 5L, dao.carriedAttachments(now = 1_000L).single { it.hash == "H3" }.sentAt)
        }

    @Test
    fun `carriedAttachments lists each frame naming a missing blob with its recipient, carry time, author and group`() =
        runTest {
            dao.insert(fwd("dm", attachmentHash = "H1", recipientId = "bob").copy(receivedAt = 7L))
            dao.insert(fwd("room", attachmentHash = "H1", recipientId = null).copy(receivedAt = 9L))
            dao.insert(fwd("group", attachmentHash = "H1", recipientId = null, groupId = "g-1").copy(receivedAt = 11L))
            dao.insert(fwd("held", attachmentHash = "H2", recipientId = "bob")) // H2 is held below → excluded
            dao.insert(fwd("text", attachmentHash = null, recipientId = "bob")) // no attachment → excluded
            db.blobDao().insert(BlobEntity(hash = "H2", mime = "image/jpeg", bytes = ByteArray(0)))

            assertEquals(
                setOf(
                    listOf("H1", "bob", 7L, "peer", null),
                    listOf("H1", null, 9L, "peer", null),
                    listOf("H1", null, 11L, "peer", "g-1"),
                ),
                dao
                    .carriedAttachments(
                        now = 0L,
                    ).mapTo(HashSet()) { listOf(it.hash, it.recipientId, it.receivedAt, it.senderId, it.groupId) },
            )
            // Only a frame with no cleartext recipient brings its bytes: a group's for its roster (ADR 2026-10.js2p), a
            // room post's for its declared size (ADR 2026-10.hmfe). A DM's are never loaded.
            assertEquals(
                mapOf("dm" to true, "room" to false, "group" to false),
                dao.carriedAttachments(now = 0L).associate { row ->
                    val kind =
                        when {
                            row.groupId != null -> "group"
                            row.recipientId != null -> "dm"
                            else -> "room"
                        }
                    kind to (row.signed == null)
                },
            )
        }

    @Test
    fun `a room post a message row already names brings no bytes, while a group frame always does`() =
        runTest {
            // The tick reads a room post's declared size only for a hash no row covers (ADR 2026-10.hmfe): a delivered,
            // untapped room video must not reload its poster-sized frame on every re-arm. A group's roster is read anyway.
            dao.insert(fwd("room", attachmentHash = "H3", recipientId = null))
            dao.insert(fwd("group", attachmentHash = "H3", recipientId = null, groupId = "g-1"))
            db.messageDao().upsert(
                app.getknit.knit.data.message
                    .MessageEntity(id = "m", senderId = "peer", body = "", sentAt = 1L, attachmentHash = "H3"),
            )

            assertEquals(
                mapOf("room" to true, "group" to false),
                dao.carriedAttachments(now = 0L).associate { (if (it.groupId != null) "group" else "room") to (it.signed == null) },
            )
            // And each says a row named it, so the re-arm can tell a post it brought no size for (#141's finding 6).
            assertTrue(dao.carriedAttachments(now = 0L).all { it.rowCovered })
            db.messageDao().deleteById("m")
            assertEquals(
                "no row: the room post's bytes come back, and nothing is row-covered",
                listOf(false to false, false to false),
                dao.carriedAttachments(now = 0L).map { (it.signed == null) to it.rowCovered },
            )
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

            dao.evictOldestBySender("A", 1, now = 1_000L) // A's oldest LIVE row is live_old — never dead_new
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
