package app.getknit.knit.data

import android.content.Context
import app.getknit.knit.mesh.protocol.LinkPreviewBlob
import app.getknit.knit.mesh.sha256Hex
import app.getknit.knit.moderation.ImageScreeningService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The third ingest door: a card is stored as the blob its message will reference, decodable and never screened here. */
class AttachmentStoreLinkPreviewTest {
    private val blobs = mockk<BlobRepository>(relaxed = true)
    private val screening = mockk<ImageScreeningService>(relaxed = true)
    private val store = AttachmentStore(mockk<Context>(relaxed = true), blobs, screening)
    private val stored = HashMap<String, Pair<String, ByteArray>>()

    init {
        coEvery { blobs.insert(any(), any(), any()) } answers { stored[firstArg()] = secondArg<String>() to thirdArg() }
    }

    @Test
    fun aCardIsStoredUnderItsOwnHashAndMimeWithItsFactsOnTheStagedObject() =
        runTest {
            val blob = LinkPreviewBlob(LinkPreviewBlob.VERSION, "https://www.example.com/a", "Title", "Desc")

            val result = store.ingestLinkPreview(blob) as AttachmentStore.IngestResult.Success

            assertFalse("screened at fetch, never here", result.flagged)
            assertEquals(sha256Hex(blob.encode()), result.ingested.hash)
            assertEquals(LinkPreviewBlob.MIME, result.ingested.mime)
            assertNull(result.ingested.name)
            assertNull(result.ingested.voice)
            assertEquals("example.com", result.ingested.link?.host)
            assertEquals("Title", result.ingested.link?.title)
            val (mime, bytes) = stored.getValue(result.ingested.hash)
            assertEquals(LinkPreviewBlob.MIME, mime)
            assertEquals(blob, LinkPreviewBlob.decodeOrNull(bytes))
            coVerify(exactly = 0) { screening.isImageExplicit(any()) }
        }

    @Test
    fun aContainerOverTheBoundIsRefused() =
        runTest {
            val huge =
                LinkPreviewBlob(
                    LinkPreviewBlob.VERSION,
                    "https://example.com/",
                    "T",
                    image = ByteArray(LinkPreviewBlob.MAX_BYTES),
                    imageMime = "image/jpeg",
                )

            val result = store.ingestLinkPreview(huge)

            assertTrue(result is AttachmentStore.IngestResult.Failed)
            assertTrue(stored.isEmpty())
        }
}
