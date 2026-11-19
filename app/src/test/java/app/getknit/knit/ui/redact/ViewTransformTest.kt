package app.getknit.knit.ui.redact

import org.junit.Assert.assertEquals
import org.junit.Test

/** The review screen's zoom and pan: where a photo pixel sits on the view, before and after a pinch. */
class ViewTransformTest {
    @Test
    fun aFittedPhotoIsWhollyOnTheViewAndCentred() {
        // 1000 x 500 in 400 x 800: width-bound, scale 0.4, 200 tall, so 300 above and below.
        val fit = ViewTransform.fit(1000, 500, 400f, 800f)

        assertEquals(ViewTransform(0.4f, 0f, 300f), fit)
    }

    @Test
    fun aViewPointMapsToThePhotoAndBack() {
        val t = ViewTransform(2.5f, -40f, 12f)

        assertEquals(37f, t.toPhotoX(t.toViewX(37f)), 1e-4f)
        assertEquals(81f, t.toPhotoY(t.toViewY(81f)), 1e-4f)
        assertEquals(-40f + 10f * 2.5f, t.toViewX(10f), 0f)
    }

    @Test
    fun zoomingKeepsThePhotoPixelUnderTheFingersWhereItWas() {
        val t = ViewTransform(1f, 20f, 30f)
        val underFingers = t.toPhotoX(200f) to t.toPhotoY(100f)

        val zoomed = t.zoomedAround(200f, 100f, factor = 3f, minScale = 1f, maxScale = 8f)

        assertEquals(3f, zoomed.scale, 0f)
        assertEquals(underFingers.first, zoomed.toPhotoX(200f), 1e-4f)
        assertEquals(underFingers.second, zoomed.toPhotoY(100f), 1e-4f)
    }

    @Test
    fun zoomStopsAtItsBoundsAndStillKeepsThePixelUnderTheFingers() {
        val t = ViewTransform(2f, 0f, 0f)

        val tooFar = t.zoomedAround(50f, 50f, factor = 10f, minScale = 1f, maxScale = 8f)
        val tooNear = t.zoomedAround(50f, 50f, factor = 0.1f, minScale = 1f, maxScale = 8f)

        assertEquals(8f, tooFar.scale, 0f)
        assertEquals(t.toPhotoX(50f), tooFar.toPhotoX(50f), 1e-4f)
        assertEquals(1f, tooNear.scale, 0f)
    }

    @Test
    fun aPhotoNarrowerThanTheViewIsCentredWhateverThePan() {
        // 100 x 100 at scale 2 is 200 wide in a 400 wide view: 100 either side.
        val clamped = ViewTransform(2f, 333f, 0f).clamped(100, 100, viewWidth = 400f, viewHeight = 200f)

        assertEquals(100f, clamped.offsetX, 0f)
    }

    @Test
    fun aPhotoWiderThanTheViewCannotBePannedPastEitherEdge() {
        // 100 wide at scale 8 is 800 in a 400 view: offsets run from -400 to 0.
        val t = ViewTransform(8f, 0f, 0f)

        assertEquals(0f, t.panned(50f, 0f).clamped(100, 100, 400f, 400f).offsetX, 0f)
        assertEquals(-400f, t.panned(-900f, 0f).clamped(100, 100, 400f, 400f).offsetX, 0f)
        assertEquals(-123f, t.panned(-123f, 0f).clamped(100, 100, 400f, 400f).offsetX, 0f)
    }
}
