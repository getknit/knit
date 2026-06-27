package app.getknit.knit.moderation

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * On-device checks for the toxicity classifier. The HuggingFace tokenizer's native runs on a
 * device/emulator only, so this is an instrumented test (not a JVM unit test).
 *
 *     ./gradlew :app:connectedDebugAndroidTest
 */
class ToxicityInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** On-device token ids must equal the training-time (HuggingFace) ids — goldens verified against
     *  the HF tokenizer and DJL-on-JVM in the detoxify-mobile conversion repo. */
    @Test
    fun tokenizerMatchesTrainingIds() {
        val tokenizer = context.assets.open("moderation/tokenizer.json").use {
            HuggingFaceTokenizer.newInstance(it, mapOf("addSpecialTokens" to "true"))
        }
        assertArrayEquals(longArrayOf(2, 2218, 71, 24932, 3), tokenizer.encode("shut up moron").ids)
        assertArrayEquals(longArrayOf(2, 57, 21, 374, 208, 187, 3), tokenizer.encode("Have a great day!").ids)
    }

    /** End-to-end: tokenizer -> TFLite -> verdict. */
    @Test
    fun flagsToxicAllowsClean() = runBlocking {
        val moderator = MlTextModerator(context)
        assertTrue(moderator.classify("shut up, you worthless idiot").flagged)
        assertFalse(moderator.classify("thanks so much, have a great day!").flagged)
    }
}
