package app.getknit.knit.ui.scan

import android.Manifest
import android.graphics.Rect
import android.media.Image
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.getknit.knit.ui.camera.CameraFixtures
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The QR scanner's frame handling on a real camera through CameraX: the analysis frames a camera HAL actually delivers,
 * whose padded rows and strides are what the scanner this replaced got wrong and crashed on (the F-Droid report against
 * 2.2.1, see [QrDecoder]), handed to the screen's own [scan].
 *
 * The first case scans the camera's own frames, which hold no code: none throws, none reads as a code, and each is
 * closed, which the analyzer shows by going on delivering them. The second draws a code into a frame laid out exactly
 * as the camera lays its frames out, rows, strides and all, and reads it back. The emulator's frames are tightly packed
 * (640 x 480, row stride 640, pixel stride 1), so that half proves something only on a phone whose camera pads its rows:
 * FTL's and Device Farm's.
 *
 *     ./gradlew :app:pixel7api33DebugAndroidTest \
 *         -Pandroid.testInstrumentationRunnerArguments.class=app.getknit.knit.ui.scan.QrScanInstrumentedTest
 */
class QrScanInstrumentedTest {
    @get:Rule
    val camera: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theCamerasOwnFramesScanToNothingWithoutThrowingAndKeepComing() {
        val frames = AtomicInteger()
        val found = AtomicReference<String?>()
        val thrown = AtomicReference<Throwable?>()
        val enough = CountDownLatch(FRAMES)
        val analysis = analysis()
        val executor = Executors.newSingleThreadExecutor()
        try {
            analysis.setAnalyzer(executor) { image ->
                runCatching { scan(image) }
                    .onSuccess { text -> text?.let { found.compareAndSet(null, it) } }
                    .onFailure { thrown.compareAndSet(null, it) }
                frames.incrementAndGet()
                enough.countDown()
            }
            CameraFixtures.bound(context, analysis) {
                // With only the latest frame kept, a frame scan left open would hold the analyzer at the first.
                assertTrue("${frames.get()} of $FRAMES frames in $FRAMES_S s", enough.await(FRAMES_S, TimeUnit.SECONDS))
            }
        } finally {
            analysis.clearAnalyzer()
            executor.shutdown()
        }
        assertNull("scan threw", thrown.get())
        assertNull("a frame with no code read as one", found.get())
    }

    @Test
    fun aCodeDrawnIntoAFrameLaidOutAsTheCamerasAreScans() {
        val layout = cameraLayout()
        Log.i(TAG, "camera frame: $layout")
        val frame = FrameOf(layout, qrLuma(layout, TEXT))
        assertEquals(TEXT, scan(frame))
        assertTrue("scan closed the frame", frame.closed)
    }

    /** The geometry of one frame the camera's analysis use case delivers, read off it before it is closed. */
    private fun cameraLayout(): Layout {
        val layout = AtomicReference<Layout>()
        val got = CountDownLatch(1)
        val analysis = analysis()
        val executor = Executors.newSingleThreadExecutor()
        try {
            analysis.setAnalyzer(executor) { image ->
                image.use {
                    val plane = it.planes.first()
                    val seen = Layout(it.width, it.height, plane.rowStride, plane.pixelStride, plane.buffer.remaining())
                    if (layout.compareAndSet(null, seen)) got.countDown()
                }
            }
            CameraFixtures.bound(context, analysis) {
                assertTrue("a frame within $FRAMES_S s", got.await(FRAMES_S, TimeUnit.SECONDS))
            }
        } finally {
            analysis.clearAnalyzer()
            executor.shutdown()
        }
        return layout.get()
    }

    private fun analysis(): ImageAnalysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()

    /**
     * A white Y plane of [layout]'s size, padding included, with a QR code of [text] drawn into its centre at the
     * plane's own strides: only the bytes the camera would hold pixels in are pixels.
     */
    private fun qrLuma(
        layout: Layout,
        text: String,
    ): ByteArray {
        val side = minOf(layout.width, layout.height) * 2 / 3
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, side, side)
        val luma = ByteArray(layout.bytes) { WHITE }
        val left = (layout.width - side) / 2
        val top = (layout.height - side) / 2
        for (y in 0 until side) {
            for (x in 0 until side) {
                if (matrix[x, y]) luma[(top + y) * layout.rowStride + (left + x) * layout.pixelStride] = BLACK
            }
        }
        return luma
    }

    private data class Layout(
        val width: Int,
        val height: Int,
        val rowStride: Int,
        val pixelStride: Int,
        val bytes: Int,
    )

    /** A frame with [layout]'s geometry and [luma] for its Y plane; only what [scan] reads is there. */
    private class FrameOf(
        private val layout: Layout,
        luma: ByteArray,
    ) : ImageProxy {
        private val plane =
            object : ImageProxy.PlaneProxy {
                private val buffer = ByteBuffer.wrap(luma)

                override fun getRowStride() = layout.rowStride

                override fun getPixelStride() = layout.pixelStride

                override fun getBuffer(): ByteBuffer = buffer
            }

        @Volatile
        var closed = false
            private set

        override fun close() {
            closed = true
        }

        override fun getCropRect() = Rect(0, 0, layout.width, layout.height)

        override fun setCropRect(rect: Rect?) = Unit

        override fun getFormat() = android.graphics.ImageFormat.YUV_420_888

        override fun getHeight() = layout.height

        override fun getWidth() = layout.width

        override fun getPlanes(): Array<ImageProxy.PlaneProxy> = arrayOf(plane)

        override fun getImageInfo(): ImageInfo = throw UnsupportedOperationException("scan reads no image info")

        @OptIn(ExperimentalGetImage::class)
        override fun getImage(): Image? = null
    }

    private companion object {
        const val TAG = "QrScanTest"
        const val TEXT = "knit://contact/test-vector-qr-frame"
        const val FRAMES = 30
        const val FRAMES_S = 60L
        const val WHITE = 0xFF.toByte()
        const val BLACK: Byte = 0
    }
}
