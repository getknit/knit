package app.getknit.knit.redact

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the review screen's edits do to a photo's covers: [Redaction] is the whole of that state. */
class RedactionTest {
    @Test
    fun aFoundFaceIsGrownAboutItsCentreByThePad() {
        // Centre (120, 125); half sides 40 * 1.3 / 2 = 26 and 50 * 1.3 / 2 = 32.5.
        val cover = Redaction.of(400, 300, listOf(face(100f, 100f, 40f, 50f))).covers.single()

        assertCover(94f, 92.5f, 146f, 157.5f, cover)
        assertTrue(cover.found && cover.covered)
    }

    @Test
    fun aFoundFaceAtTheEdgeIsClippedToThePhoto() {
        // Grown to (-3, -3)–(23, 23), the corner beyond the photo cut off.
        val cover = Redaction.of(400, 300, listOf(face(0f, 0f, 20f, 20f))).covers.single()

        assertCover(0f, 0f, 23f, 23f, cover)
    }

    @Test
    fun aTapOnAFoundCoverUncoversItAndASecondTapCoversItAgain() {
        val found = Redaction.of(400, 300, listOf(face(100f, 100f, 40f, 50f)))

        val uncovered = found.tap(120f, 125f)
        assertFalse(uncovered.covers.single().covered)
        assertEquals(0, uncovered.coveredCount)

        assertEquals(found, uncovered.tap(120f, 125f))
    }

    @Test
    fun aTapOnAnAddedCoverRemovesIt() {
        val added = Redaction(400, 300).tap(200f, 150f)
        assertEquals(1, added.covers.size)

        assertTrue(added.tap(200f, 150f).covers.isEmpty())
    }

    @Test
    fun aTapWhereCoversOverlapLandsOnTheSmallest() {
        val big = Redaction(400, 300).draw(50f, 50f, 250f, 250f)
        val both = big.draw(100f, 100f, 140f, 140f)
        val small = both.covers.last()

        val after = both.tap(120f, 120f)

        assertEquals(listOf(big.covers.single()), after.covers)
        assertFalse(after.covers.any { it.id == small.id })
    }

    @Test
    fun aTapWithinTheSlopLandsOnTheCoverAndOneJustBeyondAddsAnother() {
        val one = Redaction(400, 300).draw(100f, 100f, 140f, 140f)

        assertTrue("4 px right of the edge, slop 5", one.tap(144f, 120f, slop = 5f).covers.isEmpty())
        assertEquals("6 px right of the edge, slop 5", 2, one.tap(146f, 120f, slop = 5f).covers.size)
    }

    @Test
    fun aTapOnTheOpenPhotoAddsACoverTheSizeOfTheMedianFoundFaceCentredOnIt() {
        // Grown faces 26 x 39, 52 x 65 and 130 x 13 wide by tall: medians 52 wide, 39 tall.
        val found = Redaction.of(1000, 800, listOf(face(10f, 10f, 20f, 30f), face(300f, 10f, 40f, 50f), face(600f, 10f, 100f, 10f)))

        val added = found.tap(500f, 400f).covers.last()

        assertFalse(added.found)
        assertCover(474f, 380.5f, 526f, 419.5f, added)
    }

    @Test
    fun withNoFacesFoundATapAddsAFaceShapedCoverOfAShareOfTheShortSide() {
        // 800 * 0.08 = 64 wide, 64 * 1.25 = 80 tall.
        val added = Redaction(1000, 800).tap(500f, 400f).covers.single()

        assertCover(468f, 360f, 532f, 440f, added)
    }

    @Test
    fun aTapAtTheEdgeShiftsTheAddedCoverInsideThePhotoRatherThanCuttingIt() {
        val added = Redaction(1000, 800).tap(990f, 5f).covers.single()

        assertCover(936f, 0f, 1000f, 80f, added)
    }

    @Test
    fun aDragAddsItsRectangleWhicheverCornerItStartsAtClippedToThePhoto() {
        val drawn = Redaction(400, 300).draw(380f, 120f, 450f, 60f).covers.single()

        assertCover(380f, 60f, 400f, 120f, drawn)
        assertFalse(drawn.found)
    }

    @Test
    fun aDragShorterThanTheFloorOnEitherAxisAddsNothingAndOneAtTheFloorAddsACover() {
        // The floor is 1 % of the 300 px short side: 3 px.
        val photo = Redaction(400, 300)

        assertSame(photo, photo.draw(10f, 10f, 12.9f, 50f))
        assertSame(photo, photo.draw(10f, 10f, 50f, 12.9f))
        assertEquals(1, photo.draw(10f, 10f, 13f, 13f).covers.size)
    }

    @Test
    fun idsStayUniqueAfterARemoval() {
        // The first cover goes, so an id counted from the list's size (1) would collide with the one left (1).
        val two = Redaction(400, 300).draw(0f, 0f, 50f, 50f).draw(100f, 100f, 150f, 150f)
        val removed = two.toggle(two.covers.first().id)

        val again = removed.draw(200f, 200f, 250f, 250f)

        assertEquals(
            again.covers
                .map { it.id }
                .toSet()
                .size,
            again.covers.size,
        )
    }

    @Test
    fun onlyCoveredCoversAreCountedAndAnUnknownIdChangesNothing() {
        val found = Redaction.of(400, 300, listOf(face(10f, 10f, 40f, 40f), face(200f, 10f, 40f, 40f)))

        assertEquals(1, found.toggle(0).coveredCount)
        assertSame(found, found.toggle(99))
    }

    @Test
    fun aCoverOverOneColourIsFilledWithThatColour() {
        val cover = Redaction(100, 100).draw(10f, 10f, 40f, 60f).covers.single()

        assertEquals(0xFF336699.toInt(), cover.fillColor { _, _ -> 0xFF336699.toInt() })
    }

    @Test
    fun aCoverIsFilledWithTheMeanOfWhatItHidesRoundedHalfUp() {
        // Ten columns, five red and five blue: 127.5 of each, rounded up.
        val cover = Redaction(100, 100).draw(0f, 0f, 10f, 10f).covers.single()

        val fill = cover.fillColor { x, _ -> if (x < 5) 0xFFFF0000.toInt() else 0xFF0000FF.toInt() }

        assertEquals(0xFF800080.toInt(), fill)
    }

    @Test
    fun aClearPixelDoesNotDarkenTheMeanAndACoverOverNothingVisibleIsBlack() {
        val cover = Redaction(100, 100).draw(0f, 0f, 10f, 10f).covers.single()

        assertEquals(0xFF0000FF.toInt(), cover.fillColor { x, _ -> if (x < 5) 0x00000000 else 0xFF0000FF.toInt() })
        assertEquals(0xFF000000.toInt(), cover.fillColor { _, _ -> 0x00FFFFFF })
    }

    @Test
    fun aLargeCoverIsReadAtABoundedNumberOfPointsAllOnIt() {
        val cover = Redaction(1000, 1000).draw(0f, 0f, 1000f, 1000f).covers.single()
        val read = mutableListOf<Pair<Int, Int>>()

        cover.fillColor { x, y -> 0xFF000000.toInt().also { read += x to y } }

        assertEquals(Cover.FILL_SAMPLES * Cover.FILL_SAMPLES, read.size)
        assertTrue(
            "a point off the cover: ${read.firstOrNull { (x, y) -> x !in 0 until 1000 || y !in 0 until 1000 }}",
            read.all { (x, y) ->
                x in
                    0 until 1000 &&
                    y in 0 until 1000
            },
        )
    }

    private fun face(
        left: Float,
        top: Float,
        width: Float,
        height: Float,
    ) = FaceBox(left, top, width, height, score = 0.9f)

    private fun assertCover(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        cover: Cover,
    ) {
        val tolerance = 1e-3f
        val close =
            kotlin.math.abs(cover.left - left) <= tolerance && kotlin.math.abs(cover.top - top) <= tolerance &&
                kotlin.math.abs(cover.right - right) <= tolerance && kotlin.math.abs(cover.bottom - bottom) <= tolerance
        assertTrue("expected ($left, $top)–($right, $bottom), got $cover", close)
    }
}
