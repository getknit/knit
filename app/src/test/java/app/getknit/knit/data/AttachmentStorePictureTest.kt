package app.getknit.knit.data

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.moderation.ImageScreeningService
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * [AttachmentStore.pictureOf] on a source too large to read whole, from a provider that names no type (a `file://` here):
 * with Cover faces on, a large still photo is reviewed rather than sent as it is, so its first bytes decide (ADR
 * 2026-10.whkk, security review 2026-11-19).
 */
@RunWith(AndroidJUnit4::class)
class AttachmentStorePictureTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store =
        AttachmentStore(context, mockk(relaxed = true), mockk<ImageScreeningService>(relaxed = true), mockk(relaxed = true))
    private val dir = File(context.cacheDir, "picture-${System.nanoTime()}").apply { mkdirs() }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    // [head] then zeros, past the store's 8 MiB read cap, under a name with no extension.
    private fun oversized(head: ByteArray): Uri {
        val file = File(dir, "pick-${head.size}-${System.nanoTime()}")
        file.outputStream().use { out ->
            out.write(head)
            out.write(ByteArray(PAST_THE_CAP))
        }
        return Uri.fromFile(file)
    }

    @Test
    fun aLargeUntypedJpegIsStillAndIsReviewed() =
        runTest {
            val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(12)
            assertEquals(AttachmentStore.Picture.STILL, store.pictureOf(oversized(jpeg)))
        }

    @Test
    fun aLargeUntypedGifIsMoving() =
        runTest {
            val gif = "GIF89a".toByteArray(Charsets.US_ASCII) + ByteArray(10)
            assertEquals(AttachmentStore.Picture.MOVING, store.pictureOf(oversized(gif)))
        }

    @Test
    fun aLargeUntypedFileThatIsNoImageIsNone() =
        runTest {
            val zip = byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(12)
            assertEquals(AttachmentStore.Picture.NONE, store.pictureOf(oversized(zip)))
        }
}

// One byte past `AttachmentStore.MAX_BYTES` (8 MiB, private to the store).
private const val PAST_THE_CAP = 8 * 1024 * 1024 + 1
