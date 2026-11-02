package app.getknit.knit.data

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.mesh.link.FileIntake
import app.getknit.knit.moderation.ImageScreeningService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import kotlin.random.Random

/**
 * How far our own encoder keeps a face under the photo ceiling a receiver holds it to ([FileIntake.MAX_AVATAR_BYTES],
 * #234), with the real codec (native graphics). Noise is the worst a JPEG encoder meets: no two neighbouring pixels
 * alike, nothing to compress. The size goes into the ADR; one over the ceiling fails here.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AvatarStoreSizeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val blobs = mockk<BlobRepository>(relaxed = true)
    private val screening = mockk<ImageScreeningService>(relaxed = true)
    private val stored = mutableListOf<ByteArray>()

    init {
        coEvery { blobs.insert(any(), any(), any()) } answers { stored += thirdArg<ByteArray>() }
        coEvery { screening.isImageExplicit(any()) } returns false
    }

    @Test
    fun aNoisePictureEncodesUnderThePhotoCeiling() =
        runTest {
            val random = Random(234)
            val side = 1_280
            val pixels = IntArray(side * side) { 0xFF000000.toInt() or random.nextInt(0x1000000) }
            val noise = Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888)

            assertNotNull(AvatarStore(context, blobs, screening).saveOwnAvatar(noise))

            val size = stored.single().size
            println("own avatar, noise: $size bytes (photo ceiling ${FileIntake.MAX_AVATAR_BYTES})")
            assertTrue("an honest face over the photo ceiling: $size bytes", size <= FileIntake.MAX_AVATAR_BYTES)
        }
}
