package app.getknit.knit.ui.redact

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.redact.FaceBox
import app.getknit.knit.redact.Redaction
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * The review screen turns touches into edits in the photo's own pixels: a tap where it landed on the photo, a drag as
 * the rectangle it swept, and a face chosen through accessibility as that face. Nothing is editable while the search
 * runs. The edits themselves are [Redaction]'s (`RedactionTest`); this pins the mapping from the view.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp")
class RedactReviewScreenContentTest {
    @Suppress("DEPRECATION") // junit4.v2 rules swap in StandardTestDispatcher — a test-semantics migration, see roadmap.md
    @get:Rule
    val compose = createComposeRule()

    private val photo = ImageBitmap(PHOTO_WIDTH, PHOTO_HEIGHT)
    private val taps = mutableListOf<Triple<Float, Float, Float>>()
    private val draws = mutableListOf<List<Float>>()
    private val toggles = mutableListOf<Int>()
    private var done = 0

    private fun render(
        redaction: Redaction = Redaction(PHOTO_WIDTH, PHOTO_HEIGHT),
        search: FaceSearch = FaceSearch.Done,
    ) {
        compose.setContent {
            KnitTheme {
                RedactReviewScreenContent(
                    photo = photo,
                    redaction = redaction,
                    search = search,
                    onTap = { x, y, slop -> taps += Triple(x, y, slop) },
                    onDraw = { x0, y0, x1, y1 -> draws += listOf(x0, y0, x1, y1) },
                    onToggle = { toggles += it },
                    onDone = { done++ },
                    onCancel = {},
                )
            }
        }
    }

    /** The photo fitted to the node it is drawn in, as the screen fits it. */
    private fun fitted(): ViewTransform {
        val size = compose.onNodeWithTag("redact_photo").fetchSemanticsNode().size
        return ViewTransform.fit(PHOTO_WIDTH, PHOTO_HEIGHT, size.width.toFloat(), size.height.toFloat())
    }

    @Test
    fun aTapOnThePhotoIsReportedWhereItLandedInPhotoPixels() {
        render()
        val t = fitted()

        compose.onNodeWithTag("redact_photo").performTouchInput { click(Offset(t.toViewX(300f), t.toViewY(200f))) }

        val (x, y, slop) = taps.single()
        assertNear(300f, x)
        assertNear(200f, y)
        assertTrue("a finger's slop, in photo pixels: $slop", slop > 0f)
        assertTrue(draws.isEmpty())
    }

    @Test
    fun aDragIsReportedAsTheRectangleItSweptInPhotoPixels() {
        render()
        val t = fitted()

        compose.onNodeWithTag("redact_photo").performTouchInput {
            swipe(Offset(t.toViewX(100f), t.toViewY(80f)), Offset(t.toViewX(260f), t.toViewY(240f)), durationMillis = 300)
        }

        val drawn = draws.single()
        assertNear(100f, drawn[0])
        assertNear(80f, drawn[1])
        assertNear(260f, drawn[2])
        assertNear(240f, drawn[3])
        assertTrue("a drag is not also a tap", taps.isEmpty())
    }

    @Test
    fun aFaceIsNamedAndChosenThroughAccessibility() {
        render(Redaction.of(PHOTO_WIDTH, PHOTO_HEIGHT, listOf(FaceBox(100f, 100f, 60f, 80f, 0.9f))))

        val node = compose.onNodeWithTag("redact_cover_0").fetchSemanticsNode()
        assertEquals(listOf("Face 1"), node.config[SemanticsProperties.ContentDescription])
        assertEquals("Covered", node.config[SemanticsProperties.StateDescription])

        compose.onNodeWithTag("redact_cover_0").performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(listOf(0), toggles)
    }

    @Test
    fun aSmallFaceIsStillATouchTargetCentredOnIt() {
        val redaction = Redaction.of(PHOTO_WIDTH, PHOTO_HEIGHT, listOf(FaceBox(300f, 200f, 8f, 10f, 0.9f)))
        render(redaction)
        val t = fitted()
        val cover = redaction.covers.single()
        assertTrue("the test's face is already a target", cover.height * t.scale < MIN_TARGET_DP * density())

        val bounds = compose.onNodeWithTag("redact_cover_0").fetchSemanticsNode().boundsInRoot
        val photo = compose.onNodeWithTag("redact_photo").fetchSemanticsNode().boundsInRoot

        assertTrue("${bounds.width} px wide", bounds.width >= MIN_TARGET_DP * density() - 1f)
        assertTrue("${bounds.height} px tall", bounds.height >= MIN_TARGET_DP * density() - 1f)
        assertNear(photo.left + t.toViewX(cover.left + cover.width / 2), bounds.center.x)
        assertNear(photo.top + t.toViewY(cover.top + cover.height / 2), bounds.center.y)
    }

    @Test
    fun theStatusIsSpokenWhenItChanges() {
        render(search = FaceSearch.Looking)

        val node = compose.onNodeWithTag("redact_status").fetchSemanticsNode()

        assertEquals(LiveRegionMode.Polite, node.config[SemanticsProperties.LiveRegion])
    }

    @Test
    fun aPinchOnlyMovesTheViewAndAddsNothing() {
        render()
        val t = fitted()
        val centre = Offset(t.toViewX(300f), t.toViewY(200f))

        compose.onNodeWithTag("redact_photo").performTouchInput {
            pinch(centre - Offset(20f, 0f), centre - Offset(120f, 0f), centre + Offset(20f, 0f), centre + Offset(120f, 0f))
        }

        assertTrue("a pinch was read as a tap: $taps", taps.isEmpty())
        assertTrue("a pinch was read as a drag: $draws", draws.isEmpty())
    }

    @Test
    fun whileTheSearchRunsNothingIsEditableAndDoneWaits() {
        render(search = FaceSearch.Looking)
        val t = fitted()

        compose.onNodeWithTag("redact_photo").performTouchInput { click(Offset(t.toViewX(300f), t.toViewY(200f))) }
        compose.onNodeWithTag("redact_done").assertIsNotEnabled()

        assertTrue(taps.isEmpty())
    }

    @Test
    fun onceTheSearchHasRunDoneIsTaken() {
        render(search = FaceSearch.Unavailable)

        compose.onNodeWithTag("redact_done").assertIsEnabled().performClick()

        assertEquals(1, done)
    }

    private fun density(): Float =
        compose
            .onNodeWithTag("redact_photo")
            .fetchSemanticsNode()
            .layoutInfo.density.density

    private fun assertNear(
        want: Float,
        got: Float,
    ) {
        // A view pixel is a little over one photo pixel at this fit (600 photo px across a 360 dp view at mdpi).
        assertTrue("expected $want, got $got", abs(want - got) <= 2f)
    }

    private companion object {
        const val PHOTO_WIDTH = 600
        const val PHOTO_HEIGHT = 400
        const val MIN_TARGET_DP = 48f
    }
}
