package app.getknit.knit.moderation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.data.settings.ModelLoadJournal
import app.getknit.knit.data.settings.ModelLoadState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class MlTextModeratorWarmUpTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun TestScope.moderatorWithMissingAssets(guard: ModelLoadGuard? = null) =
        MlTextModerator(
            context,
            modelAsset = "moderation/does-not-exist.tflite",
            tokenizerAsset = "moderation/does-not-exist.json",
            labelsAsset = "moderation/does-not-exist.txt",
            guard = guard,
            scope = backgroundScope,
        )

    @Test
    fun warmUpDegradesGracefullyWhenAssetsAreMissing() =
        runTest {
            val moderator = moderatorWithMissingAssets()

            moderator.warmUp() // must return normally even though the engine can't load

            // Engine failed to load → classify allow-alls (and the lease's attempted flag means it won't retry).
            assertTrue(moderator.classify("anything at all").allowed)
            assertFalse(moderator.isResident)
        }

    /**
     * A failed load is spent for the process: the idle reaper only ever releases a *real* engine, so
     * nothing here comes back through the guard after ten minutes — the journal sees the one attempt.
     */
    @Test
    fun aFailedLoadIsNotRetriedAfterTheIdleWindow() =
        runTest {
            val journal = CountingJournal()
            val moderator = moderatorWithMissingAssets(guard = ModelLoadGuard(journal, { null }, STAMP))

            moderator.warmUp()
            val attemptsAfterFirst = journal.writes.count { it.pendingSince != 0L }
            advanceTimeBy(3 * ModelLease.DEFAULT_IDLE_MS)
            runCurrent()
            moderator.warmUp()
            assertTrue(moderator.classify("still nothing to load").allowed)

            assertEquals(1, attemptsAfterFirst)
            assertEquals(1, journal.writes.count { it.pendingSince != 0L })
        }

    /**
     * The poison-pill's product-visible half (ADR 037): a latched model is never touched, and the
     * moderator degrades to the state it already reaches when the assets are missing. Asserted through
     * the journal rather than a stubbed guard, so it exercises the real decision.
     */
    @Test
    fun aLatchedModelIsNeverLoadedAndClassifyAllowAlls() =
        runTest {
            val journal = LatchedJournal()
            val moderator =
                MlTextModerator(
                    context,
                    modelAsset = "moderation/does-not-exist.tflite",
                    tokenizerAsset = "moderation/does-not-exist.json",
                    labelsAsset = "moderation/does-not-exist.txt",
                    guard = ModelLoadGuard(journal, { null }, STAMP),
                    scope = backgroundScope,
                )

            moderator.warmUp()

            assertTrue(moderator.classify("anything at all").allowed)
            // Nothing was written: a latched read leaves the record exactly as it found it.
            assertTrue(journal.writes.isEmpty())
        }

    private class CountingJournal : ModelLoadJournal {
        val writes = mutableListOf<ModelLoadState>()
        private val state = MutableStateFlow(ModelLoadState(STAMP, 0L, 0))

        override fun observeModelLoad(model: String): Flow<ModelLoadState> = state

        override suspend fun modelLoadState(model: String) = state.value

        override suspend fun setModelLoadState(
            model: String,
            state: ModelLoadState,
        ) {
            writes += state
            this.state.value = state
        }
    }

    private class LatchedJournal : ModelLoadJournal {
        val writes = mutableListOf<ModelLoadState>()
        private val latched = MutableStateFlow(ModelLoadState(STAMP, 0L, ModelLoadPolicy.MAX_FAILS))

        override fun observeModelLoad(model: String): Flow<ModelLoadState> = latched

        override suspend fun modelLoadState(model: String) = latched.value

        override suspend fun setModelLoadState(
            model: String,
            state: ModelLoadState,
        ) {
            writes += state
            latched.value = state
        }
    }

    private companion object {
        const val STAMP = "16|test"
    }
}
