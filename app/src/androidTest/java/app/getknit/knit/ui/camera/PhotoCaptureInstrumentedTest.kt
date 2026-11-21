package app.getknit.knit.ui.camera

import android.Manifest
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.Display
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.getknit.knit.data.PHOTO_MAX_DIMENSION
import app.getknit.knit.data.decodeWholeOriented
import app.getknit.knit.data.exifOrientation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The in-app camera's capture on a real camera through CameraX, which no JVM test reaches: an [ImageCapture] taken in
 * memory as the photo screen takes it, each frame's JPEG copied out by the screen's own [jpegBytes], and the result
 * read the way the store reads it — whole, with an EXIF orientation that parses, and upright once that is applied.
 *
 * Several shots in a row on one binding, because [jpegBytes] must close every frame: CameraX holds only a few, and a
 * frame left open stalls the next capture.
 *
 *     ./gradlew :app:pixel7api33DebugAndroidTest \
 *         -Pandroid.testInstrumentationRunnerArguments.class=app.getknit.knit.ui.camera.PhotoCaptureInstrumentedTest
 */
class PhotoCaptureInstrumentedTest {
    @get:Rule
    val camera: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun shotsInARowEachComeOutAsAWholeJpegTheStoreReadsUpright() {
        val capture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
        // The photo screen stamps the display's rotation on the use case before each shot; so does this.
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        capture.targetRotation = display.rotation
        val metrics = context.resources.displayMetrics
        val portrait = metrics.heightPixels > metrics.widthPixels
        CameraFixtures.bound(context, capture) {
            repeat(SHOTS) { shot ->
                val jpeg = shoot(capture)
                assertNotNull("shot $shot came back", jpeg)
                val orientation = exifOrientation(jpeg!!)
                assertNotNull("shot $shot: its EXIF parses", orientation)
                val shown = decodeWholeOriented(jpeg, PHOTO_MAX_DIMENSION)
                assertNotNull("shot $shot decodes whole", shown)
                Log.i(TAG, "shot $shot: ${jpeg.size} B, orientation $orientation, shown ${shown!!.width} x ${shown.height}")
                // The camera records the turn in EXIF (or turns the pixels itself); either way the picture the store
                // decodes is the way up the phone was held.
                val held = if (portrait) "portrait" else "landscape"
                assertEquals("shot $shot is upright for a $held phone", portrait, shown.height > shown.width)
            }
        }
    }

    /** One in-memory capture, its JPEG copied out by [jpegBytes] on the main thread, as the photo screen does it. */
    private fun shoot(capture: ImageCapture): ByteArray? {
        val done = CountDownLatch(1)
        var jpeg: ByteArray? = null
        var failure: ImageCaptureException? = null
        capture.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    jpeg = jpegBytes(image)
                    done.countDown()
                }

                override fun onError(exception: ImageCaptureException) {
                    failure = exception
                    done.countDown()
                }
            },
        )
        assertTrue("the capture finished within $CAPTURE_S s", done.await(CAPTURE_S, TimeUnit.SECONDS))
        failure?.let { throw AssertionError("capture failed: ${it.imageCaptureError}", it) }
        return jpeg
    }

    private companion object {
        const val TAG = "PhotoCaptureTest"
        const val SHOTS = 4
        const val CAPTURE_S = 30L
    }
}
