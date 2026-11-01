package app.getknit.knit.data

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.data.AttachmentStore.IngestResult
import app.getknit.knit.data.video.TranscodeResult
import app.getknit.knit.data.video.VideoEncodePolicy
import app.getknit.knit.data.video.VideoEncoder
import app.getknit.knit.data.video.VideoMeta
import app.getknit.knit.data.video.VideoSource
import app.getknit.knit.mesh.protocol.VideoInfo
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
import org.junit.runner.RunWith

/**
 * The video door (ADR 2026-10.hmfe), with the encode faked: what it stores and stages, what it says when the encode
 * comes out too big or does not read, and that only a Nearby-room send is screened. The encode itself is
 * `VideoTranscoderInstrumentedTest`'s, on a real codec stack.
 */
@RunWith(AndroidJUnit4::class)
class AttachmentStoreVideoTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val blobs = mockk<BlobRepository>(relaxed = true)
    private val screening = mockk<ImageScreeningService>(relaxed = true)
    private val stored = HashMap<String, Pair<String, ByteArray>>()
    private var result: TranscodeResult = TranscodeResult.Unreadable
    private var probed: VideoSource? = null
    private var transcodes = 0
    private val encoder =
        object : VideoEncoder {
            override fun probe(uri: Uri): VideoSource? = probed

            override suspend fun transcode(
                uri: Uri,
                onProgress: (Float) -> Unit,
            ): TranscodeResult {
                transcodes++
                onProgress(1f)
                return result
            }
        }
    private val store = AttachmentStore(context, blobs, screening, mockk(relaxed = true), encoder)
    private val uri = Uri.parse("content://media/picked/video/1")

    init {
        coEvery { blobs.insert(any(), any(), any()) } answers { stored[firstArg()] = secondArg<String>() to thirdArg() }
    }

    private fun encoded(poster: ByteArray = byteArrayOf(1, 2, 3)): TranscodeResult.Success {
        val source = VideoSource(1080, 1920, 0, 40_000, 30f, hasAudio = true)
        return TranscodeResult.Success(
            bytes = ByteArray(2_000_000) { (it % 251).toByte() },
            meta = VideoMeta(durationMs = 40_000, width = 480, height = 854),
            poster = poster,
            plan = VideoEncodePolicy.plan(source, hevcEncoder = true),
            source = source,
            elapsedMs = 9_000,
        )
    }

    @Test
    fun aVideoIsStoredAsAnMp4NamedForOlderBuildsAndCarriesItsDescription() =
        runTest {
            result = encoded()
            val success = store.ingestVideo(uri, screen = false) as IngestResult.Success
            val ingested = success.ingested
            assertEquals("video/mp4", ingested.mime)
            assertEquals("video.mp4", ingested.name)
            assertEquals(2_000_000, ingested.sizeBytes)
            assertEquals(VideoInfo(40_000, 480, 854, byteArrayOf(1, 2, 3)), ingested.video)
            assertEquals("video/mp4", stored.getValue(ingested.hash).first)
            assertFalse(success.flagged)
            coVerify(exactly = 0) { screening.isVideoExplicit(any(), any()) }
        }

    @Test
    fun aPicksLengthIsReadOffItsContainerWithoutAnEncode() =
        runTest {
            // What the composer asks "Send the first 61 seconds?" on, before it spends an encode on the pick.
            probed = VideoSource(1080, 1920, 0, 90_000, 30f, hasAudio = true)
            assertEquals(90_000L, store.videoDurationMs(uri))
            probed = null
            assertNull("a pick that does not read has no length; the encode says what it makes of it", store.videoDurationMs(uri))
            assertEquals("nothing encoded", 0, transcodes)
        }

    @Test
    fun aRoomVideoIsScreenedAndAFlaggedOneReported() =
        runTest {
            result = encoded()
            coEvery { screening.isVideoExplicit(any(), any()) } returns true
            val success = store.ingestVideo(uri, screen = true) as IngestResult.Success
            assertTrue(success.flagged)
        }

    @Test
    fun anEncodeOverTheCeilingIsTooLargeAndAnUnreadableOneSaysSo() =
        runTest {
            result = TranscodeResult.TooLarge
            assertEquals(IngestResult.Failed(IngestResult.Reason.TooLarge), store.ingestVideo(uri, screen = false))
            result = TranscodeResult.Failed(errorCode = 4001)
            assertEquals(IngestResult.Failed(IngestResult.Reason.Unreadable), store.ingestVideo(uri, screen = false))
            assertTrue("nothing stored", stored.isEmpty())
        }
}
