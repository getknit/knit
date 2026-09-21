package app.getknit.knit.moderation

import androidx.core.graphics.createBitmap
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * On-device check that the 17 MB image model maps out of the APK's stored entry, classifies, releases on
 * [NsfwImageModerator.unload] and reloads. A blank pixel is not a content check — the model's verdicts are
 * not pinned here — it is the proof that the mapping and the reload work on real hardware.
 *
 *     ./gradlew :app:connectedDebugAndroidTest
 */
class NsfwInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun mapsClassifiesReleasesAndReloads() {
        val scope = CoroutineScope(SupervisorJob())
        try {
            runBlocking {
                val moderator = NsfwImageModerator(context, scope = scope)
                val blank = createBitmap(1, 1)

                assertFalse(moderator.classify(blank).flagged)
                assertTrue("the model did not load from the APK", moderator.isResident)

                moderator.unload()
                assertFalse(moderator.isResident)

                assertFalse(moderator.classify(blank).flagged)
                assertTrue(moderator.isResident)
            }
        } finally {
            scope.cancel()
        }
    }
}
