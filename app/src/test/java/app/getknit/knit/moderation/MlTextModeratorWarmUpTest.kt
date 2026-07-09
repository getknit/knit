package app.getknit.knit.moderation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [MlTextModerator.warmUp] pre-loads the toxicity model off the send path so the first real send on a
 * cold start doesn't freeze on the one-time load. It must never throw — a missing/broken asset degrades
 * to allow-all, exactly as [MlTextModerator.classify] does — so the app never hard-fails on startup.
 * (Robolectric-hosted only for the `Context`; the assets are pointed at non-existent paths so the test
 * stays off the real ~15 MB model and native TFLite.)
 */
@RunWith(AndroidJUnit4::class)
class MlTextModeratorWarmUpTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun moderatorWithMissingAssets() =
        MlTextModerator(
            context,
            modelAsset = "moderation/does-not-exist.tflite",
            tokenizerAsset = "moderation/does-not-exist.json",
            labelsAsset = "moderation/does-not-exist.txt",
        )

    @Test
    fun warmUpDegradesGracefullyWhenAssetsAreMissing() =
        runTest {
            val moderator = moderatorWithMissingAssets()

            moderator.warmUp() // must return normally even though the engine can't load

            // Engine failed to load → classify allow-alls (and the `loaded` flag means it won't retry).
            assertTrue(moderator.classify("anything at all").allowed)
        }
}
