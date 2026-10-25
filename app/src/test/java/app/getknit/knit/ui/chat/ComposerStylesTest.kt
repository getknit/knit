package app.getknit.knit.ui.chat

import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.mesh.protocol.BodyStyle
import app.getknit.knit.mesh.protocol.Mention
import app.getknit.knit.mesh.protocol.StyleSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The composer's formatting through a real text field (ADR 2026-10.p95v): the field's own style tracking carries the
 * spans through the edits a person and the screen make, and the adapter reads them back as [StyleSpan]s. The range
 * algebra itself is pinned in `StyleSpansTest`.
 */
@RunWith(AndroidJUnit4::class)
class ComposerStylesTest {
    @Suppress("DEPRECATION") // junit4.v2 rules swap in StandardTestDispatcher — a test-semantics migration, see roadmap.md
    @get:Rule
    val compose = createComposeRule()

    private fun field(
        state: TextFieldState,
        mentions: List<Mention> = emptyList(),
    ) {
        compose.setContent {
            BasicTextField(
                state = state,
                inputTransformation = MentionChips { mentions },
                modifier = Modifier.testTag("field"),
            )
        }
        compose.onNodeWithTag("field").requestFocus()
    }

    private fun type(text: String) = compose.onNodeWithTag("field").performTextInput(text)

    private fun press(key: Key) = compose.onNodeWithTag("field").performKeyInput { pressKey(key) }

    private fun TextFieldState.select(range: TextRange) = edit { selection = range }

    @OptIn(ExperimentalFoundationApi::class)
    @Test
    fun theFoundationFlagsTheComposerRestsOnAreOn() {
        // Off, `addStyle` with an expand policy throws and the toolbar falls back to the old menu with no items.
        assertTrue(ComposeFoundationFlags.isBasicTextFieldStyledTextEnabled)
        assertTrue(ComposeFoundationFlags.isNewContextMenuEnabled)
    }

    @Test
    fun aStyleTogglesOnOverTheSelectionAndOffAgain() {
        val state = TextFieldState("see you at noon", initialSelection = TextRange(8, 10))

        state.toggleBodyStyle(BodyStyle.BOLD)
        assertEquals(listOf(StyleSpan(8, 10, BodyStyle.BOLD)), state.bodySpans())
        assertTrue(state.selectionHas(BodyStyle.BOLD))

        state.toggleBodyStyle(BodyStyle.BOLD)
        assertEquals(emptyList<StyleSpan>(), state.bodySpans())
    }

    @Test
    fun aSelectionPartlyInAStyleTakesItWhole() {
        val state = TextFieldState("see you at noon", initialSelection = TextRange(4, 7))
        state.toggleBodyStyle(BodyStyle.ITALIC)

        state.select(TextRange(0, 10))
        state.toggleBodyStyle(BodyStyle.ITALIC)

        assertEquals(listOf(StyleSpan(0, 10, BodyStyle.ITALIC)), state.bodySpans())
    }

    @Test
    fun aCollapsedSelectionChangesNothing() {
        val state = TextFieldState("see you", initialSelection = TextRange(3))
        state.toggleBodyStyle(BodyStyle.BOLD)
        assertEquals(emptyList<StyleSpan>(), state.bodySpans())
        assertFalse(state.selectionHasAny())
    }

    @Test
    fun typingInsideASpanGrowsItAndTypingAtItsEndDoesNot() {
        val state = TextFieldState("see you", initialSelection = TextRange(4, 7))
        state.toggleBodyStyle(BodyStyle.BOLD)
        field(state)

        state.select(TextRange(5))
        type("o")
        assertEquals("see yoou", state.text.toString())
        assertEquals(listOf(StyleSpan(4, 8, BodyStyle.BOLD)), state.bodySpans())

        state.select(TextRange(8))
        type("!")
        assertEquals("see yoou!", state.text.toString())
        assertEquals("a word typed after a bold one is plain", listOf(StyleSpan(4, 8, BodyStyle.BOLD)), state.bodySpans())
    }

    @Test
    fun backspacingAMentionChipInsideBoldLeavesTheRestBold() {
        val state = TextFieldState("hi @Walter ok", initialSelection = TextRange(0, 13))
        state.toggleBodyStyle(BodyStyle.BOLD)
        state.select(TextRange(10))
        field(state, listOf(Mention("n1", "Walter")))

        press(Key.Backspace)

        assertEquals("hi  ok", state.text.toString())
        assertEquals(listOf(StyleSpan(0, 6, BodyStyle.BOLD)), state.bodySpans())
    }

    @Test
    fun aMentionPickedBeforeABoldWordMovesTheBoldWithIt() {
        // `insertMention` is a programmatic edit, which no input transformation sees: the field must still track it.
        val state = TextFieldState("@W see", initialSelection = TextRange(3, 6))
        state.toggleBodyStyle(BodyStyle.BOLD)

        state.edit { replace(0, 2, "@Walter") }

        assertEquals(listOf(StyleSpan(8, 11, BodyStyle.BOLD)), state.bodySpans())
    }

    @Test
    fun clearingTheFieldOrReplacingItsTextDropsEveryStyle() {
        val state = TextFieldState("see you", initialSelection = TextRange(0, 3))
        state.toggleBodyStyle(BodyStyle.BOLD)
        state.clearText()
        assertEquals("a sent draft leaves no style behind for the next one", emptyList<StyleSpan>(), state.bodySpans())

        state.setTextAndBodySpans("again", listOf(StyleSpan(0, 5, BodyStyle.ITALIC)))
        // A share-sheet prefill or an applied translation sets the text this way.
        state.setTextAndPlaceCursorAtEnd("translated")
        assertEquals(emptyList<StyleSpan>(), state.bodySpans())
    }

    @Test
    fun aRestoredDraftComesBackWithItsStylesAndTheCursorAtTheEnd() {
        val state = TextFieldState()
        val spans = listOf(StyleSpan(0, 4, BodyStyle.BOLD), StyleSpan(5, 8, BodyStyle.MONOSPACE))

        state.setTextAndBodySpans("half sentence", spans)

        assertEquals("half sentence", state.text.toString())
        assertEquals(spans, state.bodySpans())
        assertEquals(TextRange(13), state.selection)
    }

    @Test
    fun clearFormattingTakesEveryStyleOffTheSelectionOnly() {
        val state = TextFieldState("one two three")
        state.setTextAndBodySpans("one two three", listOf(StyleSpan(0, 13, BodyStyle.BOLD), StyleSpan(4, 7, BodyStyle.ITALIC)))
        state.select(TextRange(4, 7))
        assertTrue(state.selectionHasAny())

        state.clearBodyStyles()

        assertEquals(listOf(StyleSpan(0, 4, BodyStyle.BOLD), StyleSpan(7, 13, BodyStyle.BOLD)), state.bodySpans())
    }

    @Test
    fun aStyleTheComposerDidNotAddIsNeitherReadNorRemoved() {
        val state = TextFieldState("see you")
        val semibold = SpanStyle(fontWeight = FontWeight.SemiBold)
        state.edit { addStyle(semibold, 0, 3) }

        assertEquals(emptyList<StyleSpan>(), state.bodySpans())
        state.setTextAndBodySpans("see you", emptyList())
        state.edit { addStyle(semibold, 0, 3) }
        state.edit { setBodySpans(listOf(StyleSpan(4, 7, BodyStyle.BOLD))) }
        assertEquals(listOf(StyleSpan(4, 7, BodyStyle.BOLD)), state.bodySpans())
        assertEquals(
            "setBodySpans leaves a foreign style where it was",
            1,
            state.textStyles.getSpanStyles(TextRange(0, 7)).count { it.item == semibold },
        )
    }

    @Test
    fun aRotationKeepsTheComposersFormatting() {
        val restoration = StateRestorationTester(compose)
        var state: TextFieldState? = null
        restoration.setContent { state = rememberComposerState() }
        val spans = listOf(StyleSpan(4, 7, BodyStyle.STRIKETHROUGH))
        compose.runOnIdle { checkNotNull(state).setTextAndBodySpans("see you", spans) }
        val before = state

        restoration.emulateSavedInstanceStateRestore()

        compose.runOnIdle {
            val after = checkNotNull(state)
            assertNotSame("a fresh state, rebuilt from the saved one", before, after)
            assertEquals("see you", after.text.toString())
            assertEquals(spans, after.bodySpans())
        }
    }
}
