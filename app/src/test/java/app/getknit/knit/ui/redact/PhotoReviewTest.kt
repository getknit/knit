package app.getknit.knit.ui.redact

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.data.decodeBoundedFromBytes
import app.getknit.knit.redact.FaceBox
import app.getknit.knit.redact.FaceDetector
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import kotlin.coroutines.CoroutineContext
import kotlin.math.abs

/**
 * One photo's review, from [PhotoReview.open] to [PhotoReview.finish]: nothing is handed back that the person did not
 * see covered, and nothing at all from a review that was cancelled or replaced. Every coroutine runs on the test's
 * scheduler (the search on its background scope, the encode on a dispatcher of the same scheduler), so each
 * interleaving below is forced, not raced.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoReviewTest {
    /** A detector that answers each photo when the test says so, and remembers what it was asked. */
    private class HeldDetector : FaceDetector {
        val answers = mutableMapOf<Bitmap, CompletableDeferred<List<FaceBox>?>>()

        override suspend fun detect(bitmap: Bitmap): List<FaceBox>? = answers.getOrPut(bitmap) { CompletableDeferred() }.await()

        fun answer(
            bitmap: Bitmap,
            faces: List<FaceBox>?,
        ) {
            answers.getOrPut(bitmap) { CompletableDeferred() }.complete(faces)
        }
    }

    private val detector = HeldDetector()

    private fun TestScope.review(
        faces: FaceDetector? = detector,
        work: CoroutineDispatcher = StandardTestDispatcher(testScheduler),
    ) = PhotoReview(backgroundScope, faces, work)

    /** A dispatcher that runs nothing until the test releases it: the test scheduler would run its work at once. */
    private class HeldDispatcher : CoroutineDispatcher() {
        private val held = ArrayDeque<Runnable>()

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            held += block
        }

        fun release() {
            while (held.isNotEmpty()) held.removeFirst().run()
        }
    }

    @Test
    fun aPhotoIsShownWhileItsFacesAreLookedForAndThenWithThemCovered() =
        runTest {
            val review = review()
            val photo = photo()

            review.open(photo)
            runCurrent()
            assertEquals(FaceSearch.Looking, review.pending.value?.search)
            assertFalse("editable while looking", review.pending.value!!.editable)

            detector.answer(photo, listOf(FACE))
            runCurrent()

            val pending = review.pending.value!!
            assertEquals(FaceSearch.Done, pending.search)
            assertEquals(1, pending.redaction.coveredCount)
            assertTrue(
                pending.redaction.covers
                    .single()
                    .found,
            )
        }

    @Test
    fun noEditLandsWhileTheSearchRuns() =
        runTest {
            val review = review()
            review.open(photo())
            runCurrent()

            review.tap(100f, 100f, 0f)
            review.draw(10f, 10f, 80f, 80f)

            assertTrue(
                review.pending.value!!
                    .redaction.covers
                    .isEmpty(),
            )
        }

    @Test
    fun aDetectorThatCannotRunLeavesEveryFaceToThePerson() =
        runTest {
            val photo = photo()
            val noDetector = review(faces = null).apply { open(photo) }
            val noAnswer = review().apply { open(photo) }
            val throwing =
                review(
                    faces =
                        object : FaceDetector {
                            override suspend fun detect(bitmap: Bitmap): List<FaceBox>? = error("the model is gone")
                        },
                ).apply { open(photo) }
            detector.answer(photo, null)
            runCurrent()

            for (review in listOf(noDetector, noAnswer, throwing)) {
                assertEquals(FaceSearch.Unavailable, review.pending.value?.search)
                assertTrue(review.pending.value!!.editable)
            }
        }

    @Test
    fun finishingHandsBackThePhotoCoveredAsShownAndClosesTheReview() =
        runTest {
            val review = review()
            val photo = photo()
            review.open(photo)
            detector.answer(photo, listOf(FACE))
            runCurrent()
            val cover =
                review.pending.value!!
                    .redaction.covers
                    .single()

            val finished = checkNotNull(review.finish())
            val bytes = finished.bytes

            assertEquals("image/jpeg", finished.mime)
            assertEquals(PhotoReview.Purpose.Attach, finished.purpose)
            assertNull(review.pending.value)
            // Under the cover is its fill, the red face averaged with the white around it, not the face's own red.
            val sent = checkNotNull(decodeBoundedFromBytes(bytes, 4096))
            val centre = sent.getPixel(((cover.left + cover.right) / 2).toInt(), ((cover.top + cover.bottom) / 2).toInt())
            val fill = cover.fillColor(photo::getPixel)
            val off =
                maxOf(
                    abs(Color.red(fill) - Color.red(centre)),
                    abs(Color.green(fill) - Color.green(centre)),
                    abs(Color.blue(fill) - Color.blue(centre)),
                )
            assertTrue(
                "the cover's centre decodes to ${Integer.toHexString(centre)}, its fill is ${Integer.toHexString(fill)}",
                off <= LOSSY,
            )
            assertTrue("the fill is still the face's red", Color.green(fill) > 0)
        }

    @Test
    fun aPhotoIsHandedBackForWhatItsOwnReviewWasOpenedFor() =
        runTest {
            val review = review()
            val forTransfer = photo()
            val forChat = photo()
            detector.answer(forTransfer, listOf(FACE))
            detector.answer(forChat, listOf(FACE))

            // A transfer's review replaced by the chat's: what is handed back goes to the chat.
            review.open(forTransfer, PhotoReview.Purpose.Transfer("rally.jpg"))
            review.open(forChat)
            runCurrent()
            assertEquals(PhotoReview.Purpose.Attach, checkNotNull(review.finish()).purpose)

            review.open(forTransfer, PhotoReview.Purpose.Transfer("rally.jpg"))
            runCurrent()
            assertEquals(PhotoReview.Purpose.Transfer("rally.jpg"), checkNotNull(review.finish()).purpose)
        }

    @Test
    fun finishingBeforeTheSearchEndsHandsBackNothing() =
        runTest {
            val review = review()
            review.open(photo())
            runCurrent()

            assertNull(review.finish())
            assertEquals(FaceSearch.Looking, review.pending.value?.search)
        }

    @Test
    fun aReviewCancelledWhileItsBytesAreMadeHandsBackNothing() =
        runTest {
            val work = HeldDispatcher()
            val review = review(work = work)
            val photo = photo()
            review.open(photo)
            detector.answer(photo, listOf(FACE))
            runCurrent()

            // The encode is held on its dispatcher; the person cancels meanwhile, then the encode runs to its end.
            val finishing = async { review.finish() }
            runCurrent()
            assertTrue(review.pending.value!!.sending)
            review.cancel()
            work.release()
            advanceUntilIdle()

            assertNull(finishing.await())
            assertNull(review.pending.value)
        }

    @Test
    fun aPhotoOpenedOverAnotherIsNotOverwrittenByTheFirstsSearch() =
        runTest {
            val review = review()
            val first = photo()
            val second = photo()
            review.open(first)
            runCurrent()
            review.open(second)
            runCurrent()

            detector.answer(first, listOf(FACE, FACE.copy(left = 120f)))
            detector.answer(second, emptyList())
            runCurrent()

            val pending = review.pending.value!!
            assertTrue("the second photo is under review", pending.photo === second)
            assertTrue("the first photo's faces landed on the second", pending.redaction.covers.isEmpty())
        }

    @Test
    fun aCancelledReviewIsNotReopenedByItsSearchFinishingLate() =
        runTest {
            val review = review()
            val photo = photo()
            review.open(photo)
            runCurrent()

            review.cancel()
            detector.answer(photo, listOf(FACE))
            advanceUntilIdle()

            assertNull(review.pending.value)
        }

    /** A white 200 x 150 photo with a red block where [FACE] says the face is. */
    private fun photo(): Bitmap =
        Bitmap.createBitmap(200, 150, Bitmap.Config.ARGB_8888).apply {
            setHasAlpha(false)
            eraseColor(Color.WHITE)
            for (y in 40 until 90) {
                for (x in 50 until 90) setPixel(x, y, Color.RED)
            }
        }

    private companion object {
        /** How far a JPEG encode may move a channel of a flat area. */
        const val LOSSY = 12
        val FACE = FaceBox(50f, 40f, 40f, 50f, 0.9f)
    }
}
