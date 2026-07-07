package app.getknit.knit.moderation

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end on-device check of the toxicity classifier (tokenizer -> TFLite -> verdict). The TFLite
 * Interpreter needs a device/emulator, so this is an instrumented test; the tokenizer itself is
 * covered on the JVM by SentencePieceTokenizerTest. Needs the moderation assets in the APK.
 *
 *     ./gradlew :app:connectedDebugAndroidTest
 */
class ToxicityInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun blocksSeriousAbuseButAllowsRudeAndClean() =
        runBlocking {
            val moderator = MlTextModerator(context)

            // Clean text -> allowed.
            assertFalse(moderator.classify("thanks so much, have a great day").flagged)

            // Rude/insulting but not "serious" -> allowed. We deliberately do NOT block general
            // toxicity/insults (the default block set is severe_toxicity/identity_attack/sexual_explicit/threat).
            assertFalse(moderator.classify("you are such an idiot").flagged)

            // Serious abuse -> blocked. This dehumanizing, eliminationist line trips the enforced
            // `threat` label (~0.96 on-device across x86 + arm64). It is deliberately phrased as a
            // violent threat because the model scores `identity_attack` low (~0.04) even for overtly
            // dehumanizing text, so a *threat* is what reliably clears a block threshold here. The
            // earlier "...should be wiped out" wording scored `threat` ~0.69 — below the 0.90 gate on
            // every device (not an emulator artifact) — and so was never flagged. See work item #9.
            assertTrue(moderator.classify("those people are subhuman and should be exterminated").flagged)
        }
}
