package app.getknit.knit.ui.chat

import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.foundation.text.input.ExpandPolicy
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.placeCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import app.getknit.knit.R
import app.getknit.knit.mesh.protocol.BodyStyle
import app.getknit.knit.mesh.protocol.StyleSpan
import app.getknit.knit.mesh.protocol.StyleSpans

/*
 * The composer's formatting (ADR 2026-10.p95v): a thin adapter between the field's own style tracking and the pure
 * range algebra in [StyleSpans]. The field's `TextFieldState` keeps each style as a tracked range that follows every
 * edit, programmatic ones included (a mention picked, a draft restored, a translation applied), so nothing here
 * shifts offsets by hand. Each style is one exact single-property [SpanStyle] ([composerSpanStyle]), which is how a
 * tracked range is read back as a [BodyStyle]; the mention chips' semibold is drawn by an output transformation and
 * never tracked, so it is never read as bold.
 *
 * This file is also the seam Markdown-lite plugs into later: an `InputTransformation` that closes `**word**` calls
 * [setBodySpans] with [StyleSpans.toggle]'s answer, the same as the toolbar.
 */

/**
 * How a tracked range grows: typing inside a span extends it, typing at either edge does not. So a word typed after
 * a bold one is plain, which Markdown-lite needs, at a cost the ADR names: an IME's autocorrect inside a styled word
 * is a minimal edit and can end the span short of the word.
 */
internal val COMPOSER_EXPAND: ExpandPolicy = ExpandPolicy.InsideOnly

/** The one [SpanStyle] that marks [style] in the composer. Monospace is the font alone: the field has no wash. */
fun composerSpanStyle(style: BodyStyle): SpanStyle =
    when (style) {
        BodyStyle.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
        BodyStyle.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
        BodyStyle.STRIKETHROUGH -> SpanStyle(textDecoration = TextDecoration.LineThrough)
        BodyStyle.MONOSPACE -> SpanStyle(fontFamily = FontFamily.Monospace)
    }

/** The [BodyStyle] this tracked style marks, or null for any style the composer did not add. */
internal fun SpanStyle.bodyStyle(): BodyStyle? = BodyStyle.entries.firstOrNull { composerSpanStyle(it) == this }

/** The formatting tracked in this buffer, canonical against its text. */
fun TextFieldBuffer.bodySpans(): List<StyleSpan> {
    val tracked =
        getSpanStyles(TextRange(0, length)).mapNotNull { range ->
            val style = range.spanStyle.bodyStyle() ?: return@mapNotNull null
            val at = range.textRange
            StyleSpan(at.min, at.max, style)
        }
    return StyleSpans.normalize(asCharSequence(), tracked)
}

/** Replaces this buffer's formatting with [spans], leaving any style the composer did not add alone. */
fun TextFieldBuffer.setBodySpans(spans: List<StyleSpan>) {
    for (range in getSpanStyles(TextRange(0, length))) {
        if (range.spanStyle.bodyStyle() != null) removeStyle(range)
    }
    for (span in StyleSpans.normalize(asCharSequence(), spans)) {
        addStyle(composerSpanStyle(span.style), TextRange(span.start, span.end), COMPOSER_EXPAND)
    }
}

/** The formatting in this field right now, canonical against its text. Snapshot-observable, like [TextFieldState.text]. */
fun TextFieldState.bodySpans(): List<StyleSpan> {
    val body = text
    val tracked =
        textStyles.getSpanStyles(TextRange(0, body.length)).mapNotNull { range ->
            range.item.bodyStyle()?.let { StyleSpan(range.start, range.end, it) }
        }
    return StyleSpans.normalize(body, tracked)
}

/** Whether [style] covers the whole of the selection. False for a collapsed one. */
fun TextFieldState.selectionHas(style: BodyStyle): Boolean = StyleSpans.covers(bodySpans(), style, selection.min, selection.max)

/** Whether any style touches the selection, so "Clear formatting" has something to take off. */
fun TextFieldState.selectionHasAny(): Boolean {
    val sel = selection
    return !sel.collapsed && bodySpans().any { it.start < sel.max && sel.min < it.end }
}

/** Toggles [style] over the selection: off when it already covers all of it, else on. A collapsed selection is left alone. */
fun TextFieldState.toggleBodyStyle(style: BodyStyle) {
    val sel = selection
    if (sel.collapsed) return
    edit { setBodySpans(StyleSpans.toggle(bodySpans(), style, sel.min, sel.max)) }
}

/** Takes every style off the selection. */
fun TextFieldState.clearBodyStyles() {
    val sel = selection
    if (sel.collapsed) return
    edit { setBodySpans(StyleSpans.clear(bodySpans(), sel.min, sel.max)) }
}

/**
 * Puts [text] in the field with [spans] over it, cursor at the end, in one edit: a draft restored with its formatting
 * reports as one change, never as text first and styles after (ADR 2026-09.qtg9's 2026-10-24 amendment).
 */
fun TextFieldState.setTextAndBodySpans(
    text: String,
    spans: List<StyleSpan>,
) {
    edit {
        replace(0, length, text)
        setBodySpans(spans)
        placeCursorAtEnd()
    }
}

/**
 * Saves the composer's text, selection and undo history ([TextFieldState.Saver]) and its formatting, which the stock
 * saver leaves out: without it a rotation would keep the words and drop their styles.
 */
object ComposerStateSaver : Saver<TextFieldState, Any> {
    override fun SaverScope.save(value: TextFieldState): Any? {
        val base = with(TextFieldState.Saver) { save(value) } ?: return null
        return listOf(base, StyleSpans.pack(value.bodySpans()))
    }

    override fun restore(value: Any): TextFieldState? {
        val (base, packed) = value as List<*>
        val state = base?.let(TextFieldState.Saver::restore) ?: return null
        val spans = StyleSpans.unpack(packed as ByteArray?)
        if (spans.isNotEmpty()) state.edit { setBodySpans(spans) }
        return state
    }
}

/** The composer's [TextFieldState], saved with its formatting ([ComposerStateSaver]). */
@Composable
fun rememberComposerState(): TextFieldState = rememberSaveable(saver = ComposerStateSaver) { TextFieldState() }

/** The toolbar's words, resolved where a composable can read them: the menu's builder is not one. */
data class FormattingLabels(
    val bold: String,
    val italic: String,
    val strikethrough: String,
    val monospace: String,
    val clear: String,
) {
    fun of(style: BodyStyle): String =
        when (style) {
            BodyStyle.BOLD -> bold
            BodyStyle.ITALIC -> italic
            BodyStyle.STRIKETHROUGH -> strikethrough
            BodyStyle.MONOSPACE -> monospace
        }
}

@Composable
fun formattingLabels(): FormattingLabels =
    FormattingLabels(
        bold = stringResource(R.string.chat_format_bold),
        italic = stringResource(R.string.chat_format_italic),
        strikethrough = stringResource(R.string.chat_format_strikethrough),
        monospace = stringResource(R.string.chat_format_monospace),
        clear = stringResource(R.string.chat_format_clear),
    )

/** A format item's key in the text toolbar. */
data class FormatMenuKey(
    val style: BodyStyle,
)

/** "Clear formatting"'s key in the text toolbar. */
data object ClearFormatMenuKey

/**
 * Adds the formatting items to the field's text toolbar while words are selected: Bold, Italic, Strikethrough and
 * Monospace, each a toggle over the selection ([toggleBodyStyle]), then "Clear formatting" when a style touches it.
 * Nothing with a bare cursor, so the toolbar over a caret stays Paste alone.
 */
fun Modifier.formattingMenu(
    state: TextFieldState,
    labels: FormattingLabels,
): Modifier =
    appendTextContextMenuComponents {
        if (state.selection.collapsed) return@appendTextContextMenuComponents
        separator()
        for (style in BodyStyle.entries) {
            item(key = FormatMenuKey(style), label = labels.of(style)) {
                state.toggleBodyStyle(style)
                close()
            }
        }
        if (state.selectionHasAny()) {
            item(key = ClearFormatMenuKey, label = labels.clear) {
                state.clearBodyStyles()
                close()
            }
        }
    }
