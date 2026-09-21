package app.getknit.knit.data.blob

import app.getknit.knit.data.RoomDbTest
import app.getknit.knit.data.forward.ForwardEntity
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.peer.PeerEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Executes the **real** [BlobDao] SQL (finding #5), focused on the two multi-table queries a hand fake can't
 * faithfully reproduce: [BlobDao.orphanHashes] (the 4-way anti-join across `messages`/`peers`/`groups`/
 * `forward_store` that keeps a custodied image pinned against GC while its frame is carried) and
 * [BlobDao.carrierOnlyBlobBytes] (the carrier-only footprint budget).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BlobDaoTest : RoomDbTest() {
    private val dao get() = db.blobDao()

    @Test
    fun `insert dedups by content hash, keeping the first bytes`() =
        runTest {
            dao.insert(BlobEntity(hash = "H", mime = "image/jpeg", bytes = byteArrayOf(1, 2, 3)))
            dao.insert(BlobEntity(hash = "H", mime = "image/png", bytes = byteArrayOf(9))) // same hash → ignored

            assertTrue(dao.exists("H"))
            assertArrayEquals(byteArrayOf(1, 2, 3), dao.bytes("H"))
            assertEquals("image/jpeg", dao.mimeFor("H"))
        }

    @Test
    fun `bytes and delete address blobs by hash`() =
        runTest {
            dao.insert(BlobEntity(hash = "H", mime = "image/jpeg", bytes = byteArrayOf(1)))
            dao.delete("H")
            assertFalse(dao.exists("H"))
            assertEquals(null, dao.bytes("H"))
        }

    @Test
    fun `observeSizes reports the asked hashes we hold, with their byte lengths`() =
        runTest {
            dao.insert(BlobEntity(hash = "A", mime = "image/jpeg", bytes = byteArrayOf(1)))
            dao.insert(BlobEntity(hash = "B", mime = "image/jpeg", bytes = byteArrayOf(2, 3, 4)))
            dao.insert(BlobEntity(hash = "C", mime = "image/jpeg", bytes = byteArrayOf(5, 6)))
            val rows = dao.observeSizes(listOf("A", "C", "missing")).first().associate { it.hash to it.size }
            // The size is what decides whether an attachment can cross an Internet relay, so the
            // projection has to report the real byte length, not merely that the row exists. B was not
            // asked for and "missing" is not held: neither gets a row, which is how the chat tells an
            // attachment still on its way from one whose bytes have landed.
            assertEquals(mapOf("A" to 1, "C" to 2), rows)
        }

    @Test
    fun `orphanHashes excludes blobs referenced by a message, peer, group, or carried frame`() =
        runTest {
            listOf("H_msg", "H_peer", "H_group", "H_fwd", "H_orphan1", "H_orphan2").forEach {
                dao.insert(BlobEntity(hash = it, mime = "image/jpeg", bytes = byteArrayOf(0)))
            }
            db.messageDao().upsert(message("m1", attachmentHash = "H_msg"))
            db.peerDao().upsert(PeerEntity(nodeId = "p1", avatarHash = "H_peer"))
            db.groupDao().upsert(group("g1", photoHash = "H_group"))
            db.forwardDao().insert(carried("f1", attachmentHash = "H_fwd"))

            assertEquals(setOf("H_orphan1", "H_orphan2"), dao.orphanHashes().toSet())
        }

    @Test
    fun `carrierOnlyBlobBytes sums blobs held only for custody, and COALESCEs empty to zero`() =
        runTest {
            assertEquals(0L, dao.carrierOnlyBlobBytes()) // COALESCE(SUM(...), 0) on an empty table

            dao.insert(BlobEntity(hash = "C", mime = "image/jpeg", bytes = ByteArray(5))) // carried only → counted
            dao.insert(BlobEntity(hash = "M", mime = "image/jpeg", bytes = ByteArray(3))) // carried AND a message → not
            dao.insert(BlobEntity(hash = "U", mime = "image/jpeg", bytes = ByteArray(7))) // referenced by nobody → not
            db.forwardDao().insert(carried("fc", attachmentHash = "C"))
            db.forwardDao().insert(carried("fm", attachmentHash = "M"))
            db.messageDao().upsert(message("mm", attachmentHash = "M"))

            assertEquals(5L, dao.carrierOnlyBlobBytes()) // only C's 5 bytes
        }

    private fun message(
        id: String,
        attachmentHash: String?,
    ) = MessageEntity(id = id, senderId = "s", body = "", sentAt = 1L, attachmentHash = attachmentHash)

    private fun group(
        id: String,
        photoHash: String?,
    ) = GroupEntity(groupId = id, name = "", members = "[]", createdBy = "s", createdAt = 0L, photoHash = photoHash)

    private fun carried(
        id: String,
        attachmentHash: String?,
    ) = ForwardEntity(
        id = id,
        recipientId = "peer",
        groupId = null,
        senderId = "peer",
        type = "chat",
        origin = 0,
        signed = ByteArray(0),
        sig = ByteArray(0),
        sentAt = 1L,
        receivedAt = 1L,
        expiresAt = 10_000L,
        attachmentHash = attachmentHash,
    )
}
