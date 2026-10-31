package app.getknit.knit.data

import app.getknit.knit.data.blob.AttachmentHolderDao
import app.getknit.knit.data.blob.BlobDao
import app.getknit.knit.data.blob.BlobEntity
import app.getknit.knit.data.blob.BlobVerdictEntity
import app.getknit.knit.data.forward.ForwardEntity
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.group.GroupMembersStore
import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.data.settings.SettingsStore
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
 * `deleteIfUnreferenced`'s four reference checks + own-avatar guard and `deleteOrphans` against the real DB,
 * including the atomic verdict-row (and saved-copy row) deletion. The image *screening* itself now lives in
 * [app.getknit.knit.moderation.ImageScreeningService] (see `ImageScreeningServiceTest`).
 */
class BlobRepositoryTest : RoomDbTest() {
    private val settings = mockk<SettingsStore>(relaxed = true)

    private fun repo(
        blobs: BlobDao = db.blobDao(),
        holders: AttachmentHolderDao = db.attachmentHolderDao(),
    ) = BlobRepository(
        blobs = blobs,
        messages = db.messageDao(),
        peers = db.peerDao(),
        settings = settings,
        verdicts = db.blobVerdictDao(),
        groups = db.groupDao(),
        forward = db.forwardDao(),
        db = db,
        savedFiles = db.savedFileDao(),
        holders = holders,
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
            db.savedFileDao().upsert("h1", "content://docs/document/report.pdf", 1L)
            db.attachmentHolderDao().record("h1", "peer", DeliveryPlane.Nearby.code, 1L)

            repo().deleteIfUnreferenced("h1")

            assertFalse(db.blobDao().exists("h1"))
            assertNull(db.blobVerdictDao().find("h1"))
            assertNull("where it was saved goes with the blob (ADR 2026-09.7ad3)", db.savedFileDao().uriFor("h1"))
            // The first proof stands, so a LoRa one lands only if the Nearby row went with the blob.
            db.attachmentHolderDao().record("h1", "peer", DeliveryPlane.LoRa.code, 2L)
            db.messageDao().upsert(MessageEntity(id = "m1", senderId = "me", body = "", sentAt = 1L, attachmentHash = "h1"))
            assertFalse(
                "the proof its recipient holds it goes with the blob (ADR 2026-10.vy27)",
                db.attachmentHolderDao().heldInPair("h1", "me", "peer", DeliveryPlane.shortRangeCodes),
            )
        }

    /**
     * The proof table's plane rule (ADR 2026-10.vy27, a5yj): a phone radio's delivery or answer proves a hold, and so does
     * a contact's direct link's — the recipient holds the bytes whichever carried them. A board carries no blob and the
     * spool is the upload the proof saves, so neither writes a row, and an unknown plane proves nothing.
     */
    @Test
    fun `a radio or a direct link proves a hold, and LoRa, the spool and an unknown plane do not`() =
        runTest {
            val repo = repo()
            val planes =
                listOf(DeliveryPlane.Bluetooth, DeliveryPlane.WifiAware, DeliveryPlane.DirectLink) to
                    listOf(DeliveryPlane.LoRa, DeliveryPlane.Internet, DeliveryPlane.Unknown)
            for (plane in planes.first + planes.second) {
                val hash = "h-${plane.name}"
                val row = MessageEntity(id = "m-${plane.name}", senderId = "me", body = "", sentAt = 1L, attachmentHash = hash)
                db.messageDao().upsert(row)
                repo.recordHolder(hash, "peer", plane, 1L)
            }

            for (plane in planes.first) assertTrue("$plane proves", repo.heldInPair("h-${plane.name}", "me", "peer"))
            for (plane in planes.second) assertFalse("$plane does not", repo.heldInPair("h-${plane.name}", "me", "peer"))
        }

    @Test
    fun `a saved copy is remembered, replaced by a later save, and forgotten on request`() =
        runTest {
            val repo = repo()

            repo.rememberSavedCopy("h1", "content://docs/document/a.pdf", 1L)
            repo.rememberSavedCopy("h1", "content://docs/document/b.pdf", 2L)
            assertEquals("content://docs/document/b.pdf", repo.savedCopy("h1"))

            repo.forgetSavedCopy("h1")
            assertNull(repo.savedCopy("h1"))
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

    /**
     * ADR 2026-09.nxcq: a group keeps advertising the photo screening refused, so the row still names it; its
     * bytes go and its verdict stays, which is what stops every re-pull path from fetching it again.
     */
    @Test
    fun `a refused group photo loses its bytes and keeps its verdict`() =
        runTest {
            ownAvatar(null)
            blob("h1")
            db.blobVerdictDao().upsert(BlobVerdictEntity("h1", flagged = true, score = 0.9f))
            db.groupDao().upsert(
                GroupEntity(
                    groupId = "g",
                    name = "",
                    members = GroupMembersStore.encode(listOf("me")),
                    createdBy = "me",
                    createdAt = 1L,
                    photoHash = "h1",
                    photoShownHash = "h0",
                ),
            )

            repo().dropRefusedGroupPhoto("h1")

            assertFalse(db.blobDao().exists("h1"))
            assertEquals(true, db.blobVerdictDao().find("h1")?.flagged)
        }

    @Test
    fun `a refused group photo another surface still needs keeps its bytes`() =
        runTest {
            ownAvatar(null)
            blob("h1")
            db.peerDao().upsert(PeerEntity(nodeId = "p", avatarHash = "h1"))

            repo().dropRefusedGroupPhoto("h1")

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
    fun `observeSizes reflects the asked blobs and their sizes`() =
        runTest {
            val repo = repo()
            repo.insert("h1", "image/jpeg", byteArrayOf(1))
            repo.insert("h2", "image/jpeg", byteArrayOf(2, 3))
            repo.insert("h3", "image/jpeg", byteArrayOf(4, 5, 6))
            assertEquals(mapOf("h1" to 1, "h2" to 2), repo.observeSizes(setOf("h1", "h2", "absent")).first())
        }

    @Test
    fun `observeSizes answers an empty ask without a query`() =
        runTest {
            // A thread with nothing to size must not hold a subscription to the blobs table at all — the
            // repository answers the empty set itself rather than asking Room for `IN ()`. A DAO that
            // refuses every ask proves the query was never built.
            val dao = mockk<BlobDao>()
            every { dao.observeSizes(any()) } throws AssertionError("queried the blobs table for nothing")
            assertEquals(emptyMap<String, Int>(), repo(blobs = dao).observeSizes(emptySet()).first())
        }

    @Test
    fun `observeLinkHolders names only the holders a direct link proved`() =
        runTest {
            // ADR 2026-10.a5yj: the chat's link mark reads the proof's plane, so a radio's proof of the same file
            // must not read as the link's.
            val repo = repo()
            repo.recordHolder("h1", "me", DeliveryPlane.DirectLink, 1L)
            repo.recordHolder("h1", "bob", DeliveryPlane.Nearby, 2L)
            repo.recordHolder("h2", "bob", DeliveryPlane.Bluetooth, 3L)
            repo.recordHolder("h3", "bob", DeliveryPlane.DirectLink, 4L)
            repo.recordHolder("h4", "bob", DeliveryPlane.DirectLink, 5L)

            assertEquals(mapOf("h1" to setOf("me"), "h3" to setOf("bob")), repo.observeLinkHolders(setOf("h1", "h2", "h3")).first())
        }

    @Test
    fun `observeLinkHolders answers an empty ask without a query`() =
        runTest {
            // As observeSizes: a thread with no attachment holds no subscription to the holders table.
            val dao = mockk<AttachmentHolderDao>()
            every { dao.observeOnPlane(any(), any()) } throws AssertionError("queried the holders table for nothing")
            assertEquals(emptyMap<String, Set<String>>(), repo(holders = dao).observeLinkHolders(emptySet()).first())
        }

    @Test
    fun `observeHolders names every proven holder of the asked hashes with the plane its proof came over`() =
        runTest {
            // The chat's one read of the holders table: the link mark keeps the link's rows, and the sender's relay
            // marker ends on any of them, a radio's included.
            val repo = repo()
            repo.recordHolder("h1", "me", DeliveryPlane.DirectLink, 1L)
            repo.recordHolder("h1", "bob", DeliveryPlane.Nearby, 2L)
            repo.recordHolder("h2", "bob", DeliveryPlane.Bluetooth, 3L)
            repo.recordHolder("h3", "bob", DeliveryPlane.DirectLink, 4L)

            assertEquals(
                mapOf(
                    "h1" to mapOf("me" to DeliveryPlane.DirectLink.code, "bob" to DeliveryPlane.Nearby.code),
                    "h2" to mapOf("bob" to DeliveryPlane.Bluetooth.code),
                ),
                repo.observeHolders(setOf("h1", "h2", "h4")).first(),
            )
        }

    @Test
    fun `observeHolders answers an empty ask without a query`() =
        runTest {
            val dao = mockk<AttachmentHolderDao>()
            every { dao.observeOnPlanes(any(), any()) } throws AssertionError("queried the holders table for nothing")
            assertEquals(emptyMap<String, Map<String, Int>>(), repo(holders = dao).observeHolders(emptySet()).first())
        }
}
