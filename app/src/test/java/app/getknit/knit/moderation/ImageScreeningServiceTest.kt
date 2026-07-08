package app.getknit.knit.moderation

import android.graphics.Bitmap
import app.getknit.knit.data.RoomDbTest
import app.getknit.knit.data.blob.BlobVerdictEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Net-new coverage for the moderation-hub logic extracted from `BlobRepository` (finding #16): the verdict
 * cache (idempotent per hash), the flagged reads, and the send-side screen. Runs under Robolectric (via
 * [RoomDbTest]) so `BitmapFactory` decodes on the JVM and the real in-memory `blob_verdicts` DAO executes.
 */
class ImageScreeningServiceTest : RoomDbTest() {
    /** A stub classifier that returns a fixed verdict and counts how many times it ran. */
    private class FakeImageModerator(
        private val verdict: ImageVerdict,
    ) : ImageModerator {
        var calls = 0

        override suspend fun classify(bitmap: Bitmap): ImageVerdict {
            calls++
            return verdict
        }
    }

    // A real PNG (Robolectric decodes via ImageIO on the host JVM, so it must be a genuinely decodable image),
    // generated here so decodeBoundedFromBytes yields a non-null bitmap and the classify/cache path runs.
    private val png: ByteArray =
        ByteArrayOutputStream().also { ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB), "png", it) }.toByteArray()

    private fun service(moderator: ImageModerator) = ImageScreeningService(moderator, db.blobVerdictDao())

    @Test
    fun `screenImage classifies a miss once and caches the verdict`() =
        runTest {
            val moderator = FakeImageModerator(ImageVerdict(allowed = false, score = 0.8f))
            val svc = service(moderator)

            svc.screenImage("h", png)

            assertEquals("classified exactly once", 1, moderator.calls)
            val cached = db.blobVerdictDao().find("h")
            assertEquals(true, cached?.flagged)
            assertEquals(0.8f, cached?.score)
        }

    @Test
    fun `screenImage is idempotent per hash and never re-classifies a cached blob`() =
        runTest {
            db.blobVerdictDao().upsert(BlobVerdictEntity("h", flagged = true, score = 0.9f))
            val moderator = FakeImageModerator(ImageVerdict(allowed = true, score = 0.0f))

            service(moderator).screenImage("h", png)

            assertEquals("cache hit short-circuits before the classifier", 0, moderator.calls)
            // The pre-existing verdict is left untouched (not overwritten by the would-be new one).
            assertEquals(true, db.blobVerdictDao().find("h")?.flagged)
        }

    @Test
    fun `isImageFlagged reflects the cached verdict`() =
        runTest {
            db.blobVerdictDao().upsert(BlobVerdictEntity("bad", flagged = true, score = 0.95f))
            db.blobVerdictDao().upsert(BlobVerdictEntity("ok", flagged = false, score = 0.1f))
            val svc = service(FakeImageModerator(ImageVerdict(allowed = true)))

            assertTrue(svc.isImageFlagged("bad"))
            assertFalse(svc.isImageFlagged("ok"))
            assertFalse("no cached verdict → not flagged", svc.isImageFlagged("unknown"))
        }

    @Test
    fun `observeFlaggedHashes emits only flagged hashes`() =
        runTest {
            db.blobVerdictDao().upsert(BlobVerdictEntity("bad", flagged = true, score = 0.95f))
            db.blobVerdictDao().upsert(BlobVerdictEntity("ok", flagged = false, score = 0.1f))

            assertEquals(listOf("bad"), service(FakeImageModerator(ImageVerdict(allowed = true))).observeFlaggedHashes().first())
        }

    @Test
    fun `isImageExplicit returns the verdict flag and caches nothing`() =
        runTest {
            val svc = service(FakeImageModerator(ImageVerdict(allowed = false, score = 0.7f)))

            assertTrue(svc.isImageExplicit(png))
            assertNull("the send-side screen caches no verdict", db.blobVerdictDao().find("h"))
        }
}
