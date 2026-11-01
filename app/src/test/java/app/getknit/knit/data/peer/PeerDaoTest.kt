package app.getknit.knit.data.peer

import androidx.room3.useReaderConnection
import app.getknit.knit.data.RoomDbTest
import app.getknit.knit.data.blob.BlobEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
                    openToChat = true,
                ),
            )
            val got = dao.findByNodeId("a")!!
            assertEquals("Ada", got.name)
            assertEquals("KEY", got.pubKey)
            assertTrue(got.verified)
            assertEquals("tag", got.deviceTag)
            assertTrue(got.openToChat)
        }

    @Test
    fun `findByLoraNode picks the newest claimant and ignores peers with none`() =
        runTest {
            // A board that changed hands is named by two profiles until the old holder's next one drops it;
            // the newer claim is the one a heard post is lined up with.
            dao.upsert(PeerEntity(nodeId = "old", loraNode = 0xdeadbeefL, updatedAt = 5L))
            dao.upsert(PeerEntity(nodeId = "new", loraNode = 0xdeadbeefL, updatedAt = 9L))
            dao.upsert(PeerEntity(nodeId = "other", loraNode = 1L, updatedAt = 99L))
            dao.upsert(PeerEntity(nodeId = "none", updatedAt = 100L))
            assertEquals("new", dao.findByLoraNode(0xdeadbeefL)!!.nodeId)
            assertEquals("other", dao.findByLoraNode(1L)!!.nodeId)
            assertNull(dao.findByLoraNode(2L))
        }

    @Test
    fun `the photo re-arm wants advertised photos not held, for the peers there is a reason to show, newest heard first`() =
        runTest {
            // ADR 2026-11.4ckq, #258: bounded to anchors, verified peers, conversations and the recently heard; never a
            // blocked peer, a photo refused over its ceiling, or one screening flagged while filtering is on.
            fun row(
                id: String,
                hash: String?,
                seen: Long,
                verified: Boolean = false,
            ) = PeerEntity(nodeId = id, verified = verified, lastSeenAt = seen, advertisedAvatar = hash, advertisedKnown = true)
            dao.upsert(row("contact", "h-contact", seen = 1L))
            dao.upsert(row("verified", "h-verified", seen = 2L, verified = true))
            dao.upsert(row("talker", "h-talker", seen = 3L))
            dao.upsert(row("recent", "h-recent", seen = 100L))
            dao.upsert(row("stranger", "h-stranger", seen = 4L))
            dao.upsert(row("held", "h-held", seen = 100L))
            dao.upsert(row("none", null, seen = 100L))
            dao.upsert(row("blocked", "h-blocked", seen = 100L))
            dao.upsert(row("over", "h-over", seen = 100L))
            dao.upsert(row("flagged", "h-flagged", seen = 100L))
            db.blobDao().insert(BlobEntity("h-held", "image/jpeg", byteArrayOf(1)))
            db.photoRefusalDao().insert(
                app.getknit.knit.data.blob
                    .PhotoRefusalEntity("h-over", size = 2_000_000L),
            )
            // A holder passed over for a hash refuses nothing: the hash is still wanted from the others.
            db.photoRefusalDao().insert(
                app.getknit.knit.data.blob
                    .PhotoRefusalEntity("h-recent", holder = "liar", size = 0L),
            )
            db.blobVerdictDao().upsert(
                app.getknit.knit.data.blob
                    .BlobVerdictEntity("h-flagged", flagged = true, score = 1f),
            )
            db.messageDao().insertIfAbsent(
                app.getknit.knit.data.message.MessageEntity(
                    id = "m",
                    senderId = "talker",
                    conversationId = "talker",
                    body = "hi",
                    sentAt = 1L,
                ),
            )

            val wanted = dao.facesNeedingFetch(setOf("contact"), setOf("blocked"), filtering = true, heardSince = 50L, limit = 10)
            // Every peer with a reason of its own ahead of one only heard lately, whatever the stamps say: a crowd of
            // strangers heard today never takes a contact's place under the cap.
            assertEquals(
                listOf("talker", "verified", "contact", "recent"),
                wanted.map { it.nodeId },
            )
            assertEquals("h-talker", wanted.first().hash)
            assertEquals(
                listOf("talker", "verified"),
                dao.facesNeedingFetch(setOf("contact"), setOf("blocked"), filtering = true, heardSince = 50L, limit = 2).map { it.nodeId },
            )
            // Filtering off, a flagged photo is a photo like any other.
            assertTrue(
                dao.facesNeedingFetch(emptySet(), emptySet(), filtering = false, heardSince = 50L, limit = 10).any {
                    it.nodeId ==
                        "flagged"
                },
            )
        }

    @Test
    fun `a landing photo settles the rows that advertise it and do not show it, and a dangling face is cleared`() =
        runTest {
            dao.upsert(PeerEntity(nodeId = "waits", avatarHash = "old", advertisedAvatar = "h", advertisedKnown = true))
            dao.upsert(PeerEntity(nodeId = "shows", avatarHash = "h", advertisedAvatar = "h", advertisedKnown = true))
            dao.upsert(PeerEntity(nodeId = "other", advertisedAvatar = "x", advertisedKnown = true))
            assertEquals(listOf("waits"), dao.awaitingAvatar("h").map { it.nodeId })
            assertEquals(2, dao.countByAdvertisedAvatar("h"))

            db.blobDao().insert(BlobEntity("old", "image/jpeg", byteArrayOf(1)))
            // #256: "shows" names bytes that are gone; "waits" names bytes that are held.
            assertEquals(1, dao.clearDanglingFaces())
            assertNull(dao.findByNodeId("shows")!!.avatarHash)
            assertEquals("old", dao.findByNodeId("waits")!!.avatarHash)
            assertEquals("the advertised photo stays: it is the want", "h", dao.findByNodeId("shows")!!.advertisedAvatar)
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

    /** The face sweep (#234) finds a face whose held blob is over the limit, clears every peer showing it, and only those. */
    @Test
    fun `avatarsLongerThan finds faces over the limit and clearAvatars clears exactly those`() =
        runTest {
            db.blobDao().insert(BlobEntity("big", "image/jpeg", ByteArray(LIMIT + 1)))
            db.blobDao().insert(BlobEntity("edge", "image/jpeg", ByteArray(LIMIT)))
            dao.upsert(PeerEntity(nodeId = "a", avatarHash = "big"))
            dao.upsert(PeerEntity(nodeId = "b", avatarHash = "big"))
            dao.upsert(PeerEntity(nodeId = "c", avatarHash = "edge"))
            dao.upsert(PeerEntity(nodeId = "d", avatarHash = "not-held"))

            val over = dao.avatarsLongerThan(LIMIT.toLong())
            assertEquals(listOf("big"), over)
            assertEquals(2, dao.clearAvatars(over))

            assertNull(dao.findByNodeId("a")!!.avatarHash)
            assertNull(dao.findByNodeId("b")!!.avatarHash)
            assertEquals("edge", dao.findByNodeId("c")!!.avatarHash)
            assertEquals("not-held", dao.findByNodeId("d")!!.avatarHash)
            assertNotNull("the pin stays", dao.findByNodeId("a"))
        }

    @Test
    fun `upsert replaces the row on the same nodeId (a profile update)`() =
        runTest {
            dao.upsert(PeerEntity(nodeId = "a", name = "Ada", updatedAt = 1L))
            dao.upsert(PeerEntity(nodeId = "a", name = "Ada Renamed", updatedAt = 2L))
            assertEquals("Ada Renamed", dao.findByNodeId("a")!!.name)
            assertEquals(1, dao.observeAll().first().size)
        }

    /**
     * [PeerDao.upsert] is a hand-written `INSERT … ON CONFLICT DO UPDATE`, not Room's `@Upsert` (the same fix
     * as `DraftDao.upsert`, work item 60): this pins that a second profile for the same nodeId
     * lands as an update of the row it found — same rowid, every column overwritten but `lastSeenAt`, which keeps
     * the larger stamp — never a delete-and-reinsert or a second row.
     */
    @Test
    fun `a second profile for the same nodeId updates that row in place`() =
        runTest {
            dao.upsert(PeerEntity(nodeId = "a", name = "Ada", status = "away", updatedAt = 1L))
            val rowid = rowidOf("a")

            dao.upsert(PeerEntity(nodeId = "a", name = "Ada Renamed", status = "online", updatedAt = 2L))

            assertEquals(PeerEntity(nodeId = "a", name = "Ada Renamed", status = "online", updatedAt = 2L), dao.findByNodeId("a"))
            assertEquals("updated where it sat, not deleted and re-inserted", rowid, rowidOf("a"))
        }

    @Test
    fun `an upsert never moves lastSeenAt back, so a row read before a stamp landed cannot undo it`() =
        runTest {
            val read = PeerEntity(nodeId = "a", name = "Ada", lastSeenAt = 10L)
            dao.upsert(read)
            dao.markSeen("a", at = 50L, olderThan = 50L)
            dao.upsert(read.copy(name = "Ada Renamed")) // a read-copy-write carrying the stamp it read
            assertEquals(50L, dao.findByNodeId("a")!!.lastSeenAt)
            assertEquals("Ada Renamed", dao.findByNodeId("a")!!.name)
            dao.upsert(read.copy(lastSeenAt = 70L))
            assertEquals(70L, dao.findByNodeId("a")!!.lastSeenAt)
        }

    @Test
    fun `markSeen writes only past the gate and says how many rows it moved`() =
        runTest {
            dao.upsert(PeerEntity(nodeId = "a", lastSeenAt = 100L))
            assertEquals("inside the gate: no row", 0, dao.markSeen("a", at = 150L, olderThan = 90L))
            assertEquals(100L, dao.findByNodeId("a")!!.lastSeenAt)
            assertEquals(1, dao.markSeen("a", at = 300L, olderThan = 200L))
            assertEquals(300L, dao.findByNodeId("a")!!.lastSeenAt)
            assertEquals("no row to move", 0, dao.markSeen("ghost", at = 300L, olderThan = 200L))
        }

    @Test
    fun `namesAll carries the facts the fade judges a row by`() =
        runTest {
            dao.upsert(PeerEntity(nodeId = "a", name = "Ada", verified = true, lastSeenAt = 42L))
            assertEquals(listOf(PeerName("a", "Ada", verified = true, lastSeenAt = 42L)), dao.namesAll())
        }

    private suspend fun rowidOf(nodeId: String): Long =
        db.useReaderConnection { connection ->
            connection.usePrepared("SELECT rowid FROM peers WHERE nodeId = ?") { statement ->
                statement.bindText(1, nodeId)
                check(statement.step()) { "no peer row for $nodeId" }
                statement.getLong(0)
            }
        }

    @Test
    fun `observeAll orders by name ascending`() =
        runTest {
            dao.upsert(PeerEntity(nodeId = "a", name = "Zed"))
            dao.upsert(PeerEntity(nodeId = "b", name = "Amy"))
            assertEquals(listOf("Amy", "Zed"), dao.observeAll().first().map { it.name })
        }

    @Test
    fun `verifiedNodeIds returns only verified peers`() =
        runTest {
            dao.upsert(PeerEntity(nodeId = "v", pubKey = "K", verified = true))
            dao.upsert(PeerEntity(nodeId = "u", pubKey = "K", verified = false))
            assertEquals(listOf("v"), dao.verifiedNodeIds())
        }

    @Test
    fun `countCappable, oldestCappable and deleteCappable spare verified and protected peers`() =
        runTest {
            dao.upsert(PeerEntity(nodeId = "verified", verified = true, updatedAt = 1L))
            dao.upsert(PeerEntity(nodeId = "protected", verified = false, updatedAt = 2L))
            dao.upsert(PeerEntity(nodeId = "old", verified = false, updatedAt = 3L))
            dao.upsert(PeerEntity(nodeId = "new", verified = false, updatedAt = 4L))

            val protectedIds = listOf("protected")
            assertEquals(2, dao.countCappable(protectedIds)) // "old" + "new" (verified + protected excluded)

            val oldest = dao.oldestCappable(protectedIds, over = 1) // the single oldest cappable → "old"
            assertEquals(listOf("old"), oldest)
            assertEquals(1, dao.deleteCappable(oldest + "verified")) // a verified id among them stays
            assertNull(dao.findByNodeId("old"))
            assertNotNull(dao.findByNodeId("new"))
            assertNotNull(dao.findByNodeId("verified"))
            assertNotNull(dao.findByNodeId("protected"))
        }

    private companion object {
        const val LIMIT = 1_024
    }
}
