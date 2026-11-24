package app.getknit.knit.data

import app.getknit.knit.data.blob.BlobEntity
import app.getknit.knit.data.forward.ForwardEntity
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.data.sticker.Sticker
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The sticker tray's store (ADR 2026-11.rk6d) against the real SQL: saving, the cap, the recents read off the messages,
 * and the one property that makes a saved sticker worth keeping — its bytes outlive the message that brought them, and
 * go once nothing names them.
 */
class StickerRepositoryTest : RoomDbTest() {
    private val settings = mockk<SettingsStore>(relaxed = true)

    private lateinit var blobs: BlobRepository

    private fun repo() = StickerRepository(db.stickerDao(), blobs, db)

    @Before
    fun wire() {
        every { settings.ownAvatarHash } returns flowOf(null)
        blobs =
            BlobRepository(
                blobs = db.blobDao(),
                messages = db.messageDao(),
                peers = db.peerDao(),
                settings = settings,
                verdicts = db.blobVerdictDao(),
                groups = db.groupDao(),
                forward = db.forwardDao(),
                db = db,
                savedFiles = db.savedFileDao(),
                holders = db.attachmentHolderDao(),
            )
    }

    // Through the DAO, not [BlobRepository.insert]: a blob this process stored is kept from every sweep (#288), and these
    // cases are about what a later process's sweep does.
    private suspend fun blob(hash: String) = db.blobDao().insert(BlobEntity(hash, "image/webp", byteArrayOf(1, 2, 3)))

    private suspend fun row(
        id: String,
        hash: String,
        sender: String = ME,
        sentAt: Long = 1L,
        plainHash: String? = null,
        key: String? = null,
        sticker: Boolean = true,
        conversation: String = "peer-1",
    ) = db.messageDao().upsert(
        MessageEntity(
            id = id,
            senderId = sender,
            conversationId = conversation,
            body = "",
            sentAt = sentAt,
            attachmentHash = hash,
            attachmentMime = "image/webp",
            attachmentKey = key,
            attachmentPlainHash = plainHash,
            attachmentSticker = sticker,
        ),
    )

    private fun sticker(
        plainHash: String,
        blobHash: String = plainHash,
    ) = Sticker(plainHash = plainHash, blobHash = blobHash, blobKey = null, mime = "image/webp", animated = false)

    @Test
    fun `a saved sticker is listed newest first, once, and a second save changes nothing`() =
        runTest {
            blob("a")
            blob("b")
            val repo = repo()

            assertEquals(StickerSaveOutcome.Saved, repo.save(sticker("a"), now = 1L))
            assertEquals(StickerSaveOutcome.Saved, repo.save(sticker("b"), now = 2L))
            assertEquals(StickerSaveOutcome.AlreadySaved, repo.save(sticker("a"), now = 3L))

            assertEquals(listOf("b", "a"), repo.observeSaved().first().map { it.plainHash })
            assertEquals(setOf("a", "b"), repo.observeSavedHashes().first())
        }

    @Test
    fun `a full tray refuses the next save and lets nothing go`() =
        runTest {
            val repo = repo()
            repeat(StickerRepository.MAX_SAVED) {
                blob("s$it")
                assertEquals(StickerSaveOutcome.Saved, repo.save(sticker("s$it"), now = it.toLong()))
            }
            blob("one-more")

            assertEquals(StickerSaveOutcome.Full, repo.save(sticker("one-more"), now = 999L))

            val saved = repo.observeSaved().first().map { it.plainHash }
            assertEquals(StickerRepository.MAX_SAVED, saved.size)
            assertFalse("one-more" in saved)
            assertTrue("the oldest was let go", "s0" in saved)
        }

    @Test
    fun `a saved sticker keeps its bytes after its message is deleted, and they go once it is removed`() =
        runTest {
            blob("cipher")
            row("m1", "cipher", sender = "peer-1", plainHash = "plain", key = "a2V5")
            val repo = repo()
            repo.save(Sticker("plain", "cipher", "a2V5", "image/webp", animated = false), now = 1L)

            // Delete-for-me, then the next mesh start's sweep: the tray's row is the reference that keeps the bytes.
            db.messageDao().deleteById("m1")
            blobs.deleteIfUnreferenced("cipher")
            blobs.deleteOrphans()
            assertTrue("a saved sticker's bytes were collected", db.blobDao().exists("cipher"))
            assertEquals(listOf("plain"), repo.observeSaved().first().map { it.plainHash })

            assertTrue(repo.remove("plain"))

            assertFalse("an unsaved sticker no message names kept its bytes", db.blobDao().exists("cipher"))
            assertFalse("a second remove found a row", repo.remove("plain"))
        }

    @Test
    fun `removing a sticker its message still names keeps the bytes`() =
        runTest {
            blob("h")
            row("m1", "h", sender = "peer-1")
            val repo = repo()
            repo.save(sticker("h"), now = 1L)

            repo.remove("h")

            assertTrue(db.blobDao().exists("h"))
            assertEquals(emptyList<Sticker>(), repo.observeSaved().first())
        }

    @Test
    fun `a sticker saved from a message request is not request-only bytes`() =
        runTest {
            blob("req")
            row("r1", "req", sender = STRANGER, conversation = STRANGER)
            assertEquals(listOf("req"), blobs.requestOnlyBlobs(listOf(STRANGER), ME).map { it.hash })

            repo().save(sticker("req"), now = 1L)

            assertEquals(emptyList<String>(), blobs.requestOnlyBlobs(listOf(STRANGER), ME).map { it.hash })
            blobs.dropRequestOnly(listOf("req"), listOf(STRANGER), ME)
            assertTrue(db.blobDao().exists("req"))
        }

    @Test
    fun `a saved sticker whose frame is still carried is not counted as carrier bytes`() =
        runTest {
            blob("carried")
            db.forwardDao().insert(
                ForwardEntity(
                    id = "f1",
                    recipientId = null,
                    groupId = null,
                    senderId = "peer-1",
                    type = "chat",
                    origin = 0,
                    signed = ByteArray(0),
                    sig = ByteArray(0),
                    sentAt = 1L,
                    receivedAt = 1L,
                    expiresAt = Long.MAX_VALUE,
                    attachmentHash = "carried",
                ),
            )
            assertEquals("a carried blob nothing else names is carrier bytes", 3L, db.blobDao().carrierOnlyBlobBytes())

            repo().save(sticker("carried"), now = 1L)

            assertEquals(0L, db.blobDao().carrierOnlyBlobBytes())
        }

    @Test
    fun `recents are my own sticker sends, one per picture under its newest identity, newest first`() =
        runTest {
            listOf("room-a", "cipher-1", "cipher-2", "theirs", "photo", "gone").forEach { if (it != "gone") blob(it) }
            // A room post is named by its own hash; the same picture sealed twice is one entry, under the newer send.
            row("m1", "room-a", sentAt = 10L, conversation = "nearby")
            row("m2", "cipher-1", sentAt = 20L, plainHash = "plain-b", key = "k1")
            row("m3", "cipher-2", sentAt = 30L, plainHash = "plain-b", key = "k2")
            // Not mine, not a sticker, or bytes not held: never offered.
            row("m4", "theirs", sender = "peer-1", sentAt = 40L)
            row("m5", "photo", sentAt = 50L, sticker = false)
            row("m6", "gone", sentAt = 60L)

            val recent = repo().observeRecent(ME).first()

            assertEquals(listOf("plain-b", "room-a"), recent.map { it.plainHash })
            assertEquals("cipher-2", recent.first().blobHash)
            assertEquals("k2", recent.first().blobKey)
        }

    @Test
    fun `recents stop at their limit`() =
        runTest {
            repeat(StickerRepository.RECENT_LIMIT + 3) {
                blob("h$it")
                row("m$it", "h$it", sentAt = it.toLong())
            }

            val recent = repo().observeRecent(ME).first()

            assertEquals(StickerRepository.RECENT_LIMIT, recent.size)
            assertEquals("h${StickerRepository.RECENT_LIMIT + 2}", recent.first().plainHash)
        }

    private companion object {
        const val ME = "me"
        const val STRANGER = "stranger"
    }
}
