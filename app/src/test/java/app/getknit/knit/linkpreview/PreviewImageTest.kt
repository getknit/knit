package app.getknit.knit.linkpreview

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.mesh.protocol.LinkPreviewBlob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** The card picture's decode + re-encode under Robolectric's native graphics: bounded, typed, and picky about size. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PreviewImageTest {
    private fun png(
        width: Int,
        height: Int,
    ): ByteArray =
        ByteArrayOutputStream()
            .also { ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", it) }
            .toByteArray()

    @Test
    fun anOpaquePictureComesBackAsABoundedJpeg() {
        val shrunk = requireNotNull(PreviewImage.shrink(png(1600, 900)))
        assertEquals("image/jpeg", shrunk.mime)
        assertTrue(shrunk.bytes.isNotEmpty())
        assertTrue(shrunk.bytes.size <= LinkPreviewBlob.IMAGE_MAX_BYTES)
        assertNotNull(
            "the result is itself decodable",
            app.getknit.knit.data
                .decodeBoundedFromBytes(shrunk.bytes, PreviewImage.MAX_DIM),
        )
    }

    @Test
    fun aTinyPictureAndGarbageYieldNothing() {
        assertNull("a favicon or tracking pixel is not a preview", PreviewImage.shrink(png(32, 32)))
        assertNull(PreviewImage.shrink(png(400, 20)))
        assertNull(PreviewImage.shrink(byteArrayOf(1, 2, 3)))
    }
}
