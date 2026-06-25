package app.getknit.knit

import app.getknit.knit.ui.util.CropRect
import app.getknit.knit.ui.util.computeAvatarCrop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AvatarCropTest {

    // A representative crop-window diameter; the value is irrelevant to the result since the window
    // and image are both centered (it cancels out), but it must match the scale convention.
    private val d = 800f

    @Test
    fun scaleOneSquareTakesWholeImage() {
        assertEquals(CropRect(0, 0, 1000, 1000), computeAvatarCrop(1000, 1000, d, 1f, 0f, 0f))
    }

    @Test
    fun scaleOneLandscapeMatchesLegacyCenterCrop() {
        // Old AvatarStore center-crop: side = min(1200,800) = 800, left = (1200-800)/2 = 200, top = 0.
        assertEquals(CropRect(200, 0, 800, 800), computeAvatarCrop(1200, 800, d, 1f, 0f, 0f))
    }

    @Test
    fun scaleOnePortraitMatchesLegacyCenterCrop() {
        assertEquals(CropRect(0, 200, 800, 800), computeAvatarCrop(800, 1200, d, 1f, 0f, 0f))
    }

    @Test
    fun zoomedInCentered() {
        // scale 2 halves the captured side and keeps it centered.
        assertEquals(CropRect(250, 250, 500, 500), computeAvatarCrop(1000, 1000, d, 2f, 0f, 0f))
    }

    @Test
    fun panPastRightEdgeClamps() {
        // A large negative x offset would push left to 875; it clamps to srcW - side = 500.
        assertEquals(CropRect(500, 250, 500, 500), computeAvatarCrop(1000, 1000, d, 2f, -1000f, 0f))
    }

    @Test
    fun panPastLeftEdgeClamps() {
        assertEquals(CropRect(0, 250, 500, 500), computeAvatarCrop(1000, 1000, d, 2f, 1000f, 0f))
    }

    @Test
    fun panPastBottomEdgeClampsOnTallSource() {
        // 800x1200, scale 2 -> side 400; a large negative y offset clamps top to srcH - side = 800.
        assertEquals(CropRect(200, 800, 400, 400), computeAvatarCrop(800, 1200, d, 2f, 0f, -2000f))
    }

    @Test
    fun tinySourceYieldsWholeImageNoOverflow() {
        assertEquals(CropRect(0, 0, 100, 100), computeAvatarCrop(100, 100, d, 1f, 0f, 0f))
    }

    @Test
    fun resultIsAlwaysSquareAndInBounds() {
        val sizes = listOf(640 to 480, 480 to 640, 1024 to 1024, 1920 to 1080, 300 to 900)
        val scales = listOf(1f, 1.5f, 2.7f, 5f)
        val offsets = listOf(-5000f, -300f, 0f, 300f, 5000f)
        for ((w, h) in sizes) {
            for (s in scales) {
                for (ox in offsets) {
                    for (oy in offsets) {
                        val r = computeAvatarCrop(w, h, d, s, ox, oy)
                        assertTrue("square", r.width == r.height)
                        assertTrue("left in bounds", r.left >= 0 && r.left + r.width <= w)
                        assertTrue("top in bounds", r.top >= 0 && r.top + r.height <= h)
                        assertTrue("non-empty", r.width >= 1)
                    }
                }
            }
        }
    }
}
