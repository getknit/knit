package app.getknit.knit.data

import app.getknit.knit.mesh.protocol.LinkPreviewBlob
import app.getknit.knit.mesh.sha256Hex
import app.getknit.knit.moderation.ImageScreeningService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Net-new coverage for [MeshBlobStore.saveIncoming] and [MeshBlobStore.ingest] — the receive-side ingest that
 * is the **sole** NSFW screen for every plaintext blob the mesh or the spool pulls (a relayed avatar, a group
 * photo, a Nearby-room attachment). Its screening skip used to read the serving peer's `LinkFraming.FileHeaderWire.mime`, which
 * `BlobExchange.onRequest` lets any neighbour choose, so a hostile holder could declare `audio/aac` and
 * turn screening off for its own blob (knit/knit-next#30). These tests pin the replacement rule: the mime
 * and the E2E key come from **our own** message row, and the wire value decides nothing.
 *
 * Plain JVM, no Robolectric: [sha256Hex] is pure, the collaborators are mockk'd so `BitmapFactory` is never
 * reached, and `android.util.Log` is neutralized by `testOptions.unitTests.isReturnDefaultValues`.
 */
class MeshBlobStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val blobs = mockk<BlobRepository>(relaxed = true)
    private val messages = mockk<MessageRepository>(relaxed = true)
    private val imageScreening = mockk<ImageScreeningService>(relaxed = true)

    private val bytes = "pretend-this-is-an-explicit-jpeg".toByteArray()
    private val hash = sha256Hex(bytes)

    /** What [blobs] holds, so the store's own `fileFor` read-back sees whatever the ingest just inserted. */
    private val stored = mutableMapOf<String, Pair<String, ByteArray>>()

    @Before
    fun backBlobRepositoryWithAMap() {
        coEvery { blobs.insertArrived(any(), any(), any()) } answers { stored[firstArg()] = secondArg<String>() to thirdArg() }
        coEvery { blobs.bytes(any()) } answers { stored[firstArg<String>()]?.second }
        coEvery { blobs.mimeFor(any()) } answers { stored[firstArg<String>()]?.first }
    }

    private fun store() = MeshBlobStore(blobs, messages, imageScreening, File(tmp.root, "blobtx"))

    /** A staging file exactly as `FramedLink.finalizeIncomingFile` would leave it for the ingest. */
    private fun staged(payload: ByteArray = bytes): File = File(tmp.newFolder(), "incoming").apply { writeBytes(payload) }

    /** What our own row says about [hash]: its mime, and whether the blob is a sealed E2E attachment. */
    private fun row(
        mime: String?,
        key: String? = null,
    ) {
        coEvery { messages.attachmentMimeForHash(hash) } returns mime
        coEvery { messages.attachmentKeyForHash(hash) } returns key
    }

    @Test
    fun `a spoofed audio header cannot suppress screening of a blob no row names`() =
        runTest {
            row(mime = null) // an avatar or group photo: no message row names it at all

            store().saveIncoming(hash, "audio/aac", staged().absolutePath)

            // The attack: with the gate reading the wire, this call never happened and the adoption gates
            // (adoptAdvertisedAvatar / adoptAdvertisedGroupPhoto) read isImageFlagged == false.
            coVerify(exactly = 1) { imageScreening.screenImage(hash, bytes) }
            // Nothing local overrides the type, so the header stands as the fallback for the stored mime.
            coVerify(exactly = 1) { blobs.insertArrived(hash, "audio/aac", bytes) }
        }

    @Test
    fun `a room attachment is screened even when its own cleartext mime claims audio`() =
        runTest {
            // A Nearby-room attachment is never re-sealed, so its attachmentMime rides in the clear and
            // lands in the row verbatim — the row alone would only move the spoof to the message's author.
            row(mime = "audio/aac", key = null)

            store().saveIncoming(hash, "audio/aac", staged().absolutePath)

            coVerify(exactly = 1) { imageScreening.screenImage(hash, bytes) }
        }

    @Test
    fun `a sealed voice note still skips the image classifier`() =
        runTest {
            row(mime = "audio/aac", key = "YmFzZTY0LWtleQ==")

            store().saveIncoming(hash, "audio/aac", staged().absolutePath)

            coVerify(exactly = 0) { imageScreening.screenImage(any(), any()) }
        }

    @Test
    fun `a sealed file skips the classifier here, and is screened after it is decrypted`() =
        runTest {
            // The skip generalized from audio to every sealed non-image (ADR 2026-09.qq2r). The reason is the
            // same and it is not "files are unscreenable": what is stored here is *ciphertext*, which no image
            // decoder can read, so the call would buy a failed decode and a meaningless cached verdict. The
            // real screen for this blob happens in InboundPipeline.onObtained, on the decrypted plaintext and
            // blind to the mime — which is what catches an image mislabelled as a file.
            row(mime = "application/pdf", key = "YmFzZTY0LWtleQ==")

            store().saveIncoming(hash, "application/pdf", staged().absolutePath)

            coVerify(exactly = 0) { imageScreening.screenImage(any(), any()) }
        }

    @Test
    fun `a key-less blob claiming a file type is screened anyway`() =
        runTest {
            // Both halves of the test matter. Without the key requirement, an author could mark their own
            // room attachment application/pdf — the room's mime rides in the clear, straight into the row —
            // and switch screening off for it. The room offers no file picker, so this costs nothing real.
            row(mime = "application/pdf", key = null)

            store().saveIncoming(hash, "application/pdf", staged().absolutePath)

            coVerify(exactly = 1) { imageScreening.screenImage(hash, bytes) }
        }

    @Test
    fun `the stored mime is our own row's, not the serving peer's claim`() =
        runTest {
            row(mime = "image/webp", key = "YmFzZTY0LWtleQ==")

            store().saveIncoming(hash, "audio/aac", staged().absolutePath)

            coVerify(exactly = 1) { blobs.insertArrived(hash, "image/webp", bytes) }
            coVerify(exactly = 1) { imageScreening.screenImage(hash, bytes) }
        }

    @Test
    fun `a malformed hash is dropped without storing or screening`() =
        runTest {
            val src = staged()

            assertNull(store().saveIncoming("../etc/passwd", "image/webp", src.absolutePath))

            assertFileGone(src)
            coVerify(exactly = 0) { blobs.insertArrived(any(), any(), any()) }
            coVerify(exactly = 0) { imageScreening.screenImage(any(), any()) }
        }

    @Test
    fun `bytes that do not match the claimed hash are dropped without storing or screening`() =
        runTest {
            row(mime = null)
            val src = staged("different-bytes-entirely".toByteArray())

            assertNull(store().saveIncoming(hash, "image/webp", src.absolutePath))

            assertFileGone(src)
            coVerify(exactly = 0) { blobs.insertArrived(any(), any(), any()) }
            coVerify(exactly = 0) { imageScreening.screenImage(any(), any()) }
        }

    private fun assertFileGone(file: File) = assertFalse("staging copy must be deleted", file.exists())

    @Test
    fun `a room card is opened and screened as a card, not decoded as an image`() =
        runTest {
            row(LinkPreviewBlob.MIME, key = null)

            store().saveIncoming(hash, "image/jpeg", staged().absolutePath)

            coVerify(exactly = 1) { imageScreening.screenAttachment(hash, bytes, LinkPreviewBlob.MIME, isRoom = true) }
            coVerify(exactly = 0) { imageScreening.screenImage(any(), any()) }
        }

    @Test
    fun `a sealed card skips the screen here like any sealed non-image`() =
        runTest {
            // Ciphertext: the container is opened and screened after decryption, in InboundPipeline.onObtained.
            row(LinkPreviewBlob.MIME, key = "a2V5")

            store().saveIncoming(hash, "image/jpeg", staged().absolutePath)

            coVerify(exactly = 0) { imageScreening.screenAttachment(any(), any(), any(), any()) }
            coVerify(exactly = 0) { imageScreening.screenImage(any(), any()) }
        }

    @Test
    fun `a peer claiming the card mime for a blob no row names is still screened as an image`() =
        runTest {
            // The header is the asker's choice; only our own row can route a blob into the card screen.
            row(mime = null)

            store().saveIncoming(hash, LinkPreviewBlob.MIME, staged().absolutePath)

            coVerify(exactly = 1) { imageScreening.screenImage(hash, bytes) }
            coVerify(exactly = 0) { imageScreening.screenAttachment(any(), any(), any(), any()) }
        }

    // --- the spool's door: `ingest`, which `MeshManager.scopeBlobs().save` calls with bytes already checked ---

    @Test
    fun `a group photo or avatar the spool fetched is screened before the obtained hook runs`() =
        runTest {
            // #109: the spool used to store these with a bare insert, so the adopt checks in
            // InboundPipeline.onObtained found no verdict and showed an explicit image with filtering on.
            row(mime = null)

            store().ingest(hash, "image/jpeg", bytes)

            coVerify(exactly = 1) { blobs.insertArrived(hash, "image/jpeg", bytes) }
            coVerify(exactly = 1) { imageScreening.screenImage(hash, bytes) }
        }

    @Test
    fun `a received blob is screened before it is held, so held means screened`() =
        runTest {
            // A profile or group frame handled on another coroutine reads "held" as "has a verdict": with the
            // insert first, one landing in between adopted an avatar or showed a photo the screen then refused.
            row(mime = null)
            coEvery { imageScreening.screenImage(hash, bytes) } answers {
                assertFalse("the verdict is written before the bytes are held", hash in stored)
            }

            store().ingest(hash, "image/jpeg", bytes)

            coVerify(exactly = 1) { imageScreening.screenImage(hash, bytes) }
            assertTrue(hash in stored)
        }

    @Test
    fun `a blob reads as storing while it is screened, and not once it is held`() =
        runTest {
            // The re-ask reads this (BlobExchange): past the link's end a blob is no longer arriving, and with the
            // screen ahead of the insert it is not held either until the classifier answers.
            row(mime = null)
            val store = store()
            var seenWhileScreening = false
            coEvery { imageScreening.screenImage(hash, bytes) } answers { seenWhileScreening = store.storing(hash) }

            store.saveIncoming(hash, "image/jpeg", staged().absolutePath)

            assertTrue("storing while the screen runs", seenWhileScreening)
            assertFalse("not storing once held", store.storing(hash))
        }

    @Test
    fun `a blob that fails its hash is not left reading as storing`() =
        runTest {
            row(mime = null)
            val store = store()

            assertNull(store.saveIncoming(hash, "image/jpeg", staged("different-bytes-entirely".toByteArray()).absolutePath))

            assertFalse(store.storing(hash))
        }

    @Test
    fun `a spool-fetched attachment is stored under the mime our own decrypted row names`() =
        runTest {
            // Since ADR 035 a sealed frame carries no mime, so the fetcher's hint is its image/jpeg default —
            // and a voice note stored as image/jpeg would render as a broken photo instead of a waveform.
            row(mime = "audio/aac", key = "YmFzZTY0LWtleQ==")

            store().ingest(hash, "image/jpeg", bytes)

            coVerify(exactly = 1) { blobs.insertArrived(hash, "audio/aac", bytes) }
            // Sealed non-image: ciphertext here, screened after decryption in InboundPipeline.onObtained.
            coVerify(exactly = 0) { imageScreening.screenImage(any(), any()) }
        }

    @Test
    fun `a room card the spool fetched is screened as a card`() =
        runTest {
            row(LinkPreviewBlob.MIME, key = null)

            store().ingest(hash, "image/jpeg", bytes)

            coVerify(exactly = 1) { imageScreening.screenAttachment(hash, bytes, LinkPreviewBlob.MIME, isRoom = true) }
            coVerify(exactly = 0) { imageScreening.screenImage(any(), any()) }
        }
}
