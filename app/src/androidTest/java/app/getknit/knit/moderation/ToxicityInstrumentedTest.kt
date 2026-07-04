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

            // Identity attack -> blocked.
            assertTrue(moderator.classify("those people are subhuman and should be wiped out").flagged)
        }
}
