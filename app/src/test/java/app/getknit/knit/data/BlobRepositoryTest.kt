package app.getknit.knit.data

import app.getknit.knit.data.blob.BlobEntity
import app.getknit.knit.data.blob.BlobVerdictEntity
import app.getknit.knit.data.forward.ForwardEntity
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.group.GroupMembersStore
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.moderation.ImageModerator
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cross-table blob GC (the load-bearing part of [BlobRepository]) had no direct test. Exercises
 * `deleteIfUnreferenced`'s four reference checks + own-avatar guard and `deleteOrphans` against the real DB.
 * The image-moderation methods (Bitmap/TFLite) are intentionally out of scope.
 */
class BlobRepositoryTest : RoomDbTest() {
    private val settings = mockk<SettingsStore>(relaxed = true)
    private val imageModerator = mockk<ImageModerator>(relaxed = true)

    private fun repo() =
        BlobRepository(
            blobs = db.blobDao(),
            messages = db.messageDao(),
            peers = db.peerDao(),
            settings = settings,
            verdicts = db.blobVerdictDao(),
            imageModerator = imageModerator,
            groups = db.groupDao(),
            forward = db.forwardDao(),
            db = db,
        )

    private fun ownAvatar(hash: String?) {
        every { settings.ownAvatarHash } returns flowOf(hash)
    }

    private suspend fun blob(hash: String) = db.blobDao().insert(BlobEntity(hash, "image/jpeg", byteArrayOf(1, 2, 3)))

    @Test
    fun `deletes a blob and its verdict when nothing references it`() =
        runTest {
            ownAvatar(null)
            blob("h1")
            db.blobVerdictDao().upsert(BlobVerdictEntity("h1", flagged = true, score = 0.9f))

            repo().deleteIfUnreferenced("h1")

            assertFalse(db.blobDao().exists("h1"))
            assertNull(db.blobVerdictDao().find("h1"))
        }

    @Test
    fun `keeps a blob referenced by a message attachment`() =
        runTest {
            ownAvatar(null)
            blob("h1")
            db.messageDao().upsert(
                MessageEntity(id = "m1", senderId = "s", conversationId = "c", body = "", sentAt = 1L, attachmentHash = "h1"),
            )

            repo().deleteIfUnreferenced("h1")

            assertTrue(db.blobDao().exists("h1"))
        }

    @Test
    fun `keeps a blob referenced by a peer avatar`() =
        runTest {
            ownAvatar(null)
            blob("h1")
            db.peerDao().upsert(PeerEntity(nodeId = "p", avatarHash = "h1"))

            repo().deleteIfUnreferenced("h1")

            assertTrue(db.blobDao().exists("h1"))
        }

    @Test
    fun `keeps a blob referenced by a group photo`() =
        runTest {
            ownAvatar(null)
            blob("h1")
            db.groupDao().upsert(
                GroupEntity(
                    groupId = "g",
                    name = "",
                    members = GroupMembersStore.encode(listOf("me")),
                    createdBy = "me",
                    createdAt = 1L,
                    photoHash = "h1",
                ),
            )

            repo().deleteIfUnreferenced("h1")

            assertTrue(db.blobDao().exists("h1"))
        }

    @Test
    fun `keeps a blob referenced by a carried store-and-forward frame`() =
        runTest {
            ownAvatar(null)
            blob("h1")
            db.forwardDao().insert(
                ForwardEntity(
                    id = "f1",
                    recipientId = null,
                    groupId = null,
                    senderId = "s",
                    type = "chat",
                    origin = 0,
                    signed = ByteArray(0),
                    sig = ByteArray(0),
                    sentAt = 1L,
                    receivedAt = 1L,
                    expiresAt = Long.MAX_VALUE,
                    attachmentHash = "h1",
                ),
            )

            repo().deleteIfUnreferenced("h1")

            assertTrue(db.blobDao().exists("h1"))
        }

    @Test
    fun `keeps the device's own avatar blob even when otherwise unreferenced`() =
        runTest {
            ownAvatar("h1")
            blob("h1")

            repo().deleteIfUnreferenced("h1")

            assertTrue(db.blobDao().exists("h1"))
        }

    @Test
    fun `a null hash is a no-op`() =
        runTest {
            ownAvatar(null)
            repo().deleteIfUnreferenced(null) // must not throw
        }

    @Test
    fun `deleteOrphans removes unreferenced blobs but keeps the own avatar`() =
        runTest {
            ownAvatar("own")
            blob("orphan")
            blob("own")

            repo().deleteOrphans()

            assertFalse(db.blobDao().exists("orphan"))
            assertTrue(db.blobDao().exists("own"))
        }

    @Test
    fun `insert then read round-trips bytes, mime, and existence`() =
        runTest {
            val repo = repo()
            assertFalse(repo.exists("h1"))
            repo.insert("h1", "image/webp", byteArrayOf(9, 8, 7))

            assertTrue(repo.exists("h1"))
            assertEquals("image/webp", repo.mimeFor("h1"))
            assertArrayEquals(byteArrayOf(9, 8, 7), repo.bytes("h1"))
            assertNull(repo.bytes("missing"))
        }

    @Test
    fun `observeHashes reflects stored blobs`() =
        runTest {
            val repo = repo()
            repo.insert("h1", "image/jpeg", byteArrayOf(1))
            repo.insert("h2", "image/jpeg", byteArrayOf(2))
            assertEquals(setOf("h1", "h2"), repo.observeHashes().first().toSet())
        }

    @Test
    fun `isImageFlagged and observeFlaggedHashes reflect the cached verdict`() =
        runTest {
            val repo = repo()
            db.blobVerdictDao().upsert(BlobVerdictEntity("bad", flagged = true, score = 0.95f))
            db.blobVerdictDao().upsert(BlobVerdictEntity("ok", flagged = false, score = 0.1f))

            assertTrue(repo.isImageFlagged("bad"))
            assertFalse(repo.isImageFlagged("ok"))
            assertFalse(repo.isImageFlagged("unknown"))
            assertEquals(listOf("bad"), repo.observeFlaggedHashes().first())
        }
}
