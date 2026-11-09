package app.getknit.knit.ui.preview

import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * Whether any `@Preview` cuts its own content off on a small phone, in long text or at a large font: the shape of
 * GitHub #62, where the caller's card sat in a fixed-height box with no scroll and lost its last line.
 *
 * An audit, not a gate: it runs only when `-Pknit.previewLayout=<dir>` names a directory holding the manifest that
 * `scripts/gen-screenshot-tests.py --manifest` writes (`app/build.gradle.kts` leaves it out otherwise); drive it
 * with `scripts/preview-layout-audit.py`, which collates what each case writes to `<dir>/layout/`. Every preview
 * composes once per [Variant] inside a clipped frame of that size (a component, of that width), under
 * [LocalInspectionMode] as the screenshot render does, and the unmerged semantics tree is walked for three findings:
 *
 * - **cut off**: a text's lines, or the box of a node with a description (or of a scroll container's viewport), show
 *   less than they measure, and no scroll container above can bring the rest into view;
 * - **overflows**: a text's layout overflows its box with no ellipsis (an editable field, which scrolls, is skipped);
 * - **overlaps**: two texts, neither inside the other, cover each other within the same scroll container.
 *
 * A preview only shows the states and sample data it draws, so a screen whose previews use short names misses what a
 * long one does; give it a worst-case preview. Text is measured by the platform (NATIVE graphics) with the fonts
 * Robolectric bundles, not a phone's, so a line that fits within a pixel or two of its box can differ there. A dialog or a
 * popup is a window of its own, outside the frame, so what it draws is not audited.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-rUS-w411dp-h2400dp-xxhdpi") // tall enough for a long component at the large font
class PreviewClippingTest(
    private val wrapper: String,
    private val owner: String,
    private val method: String,
    private val screen: Boolean,
    private val variant: Variant,
) {
    /** A size, locale and font scale every preview is checked at: a screen preview's frame, a component's width. */
    enum class Variant(
        val widthDp: Int,
        val heightDp: Int,
        val locale: Locale,
        val fontScale: Float,
    ) {
        /** A 5-inch phone: the height #62 and the caller card's small preview fought over. */
        Small(360, 640, Locale.US, 1f),

        /** The same phone in en-XA, every string about 40 % longer: what translation does to a layout. */
        SmallLongText(360, 640, Locale.forLanguageTag("en-XA"), 1f),

        /** A typical phone at the system's "Large" font and above. */
        LargeFont(411, 891, Locale.US, 1.5f),
    }

    @Suppress("DEPRECATION") // junit4.v2 rules swap in StandardTestDispatcher — a test-semantics migration, see roadmap.md
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun nothingIsCutOff() {
        val findings =
            runCatching {
                compose.mainClock.autoAdvance = false
                compose.setContent { Framed() }
                repeat(SETTLE_FRAMES) { compose.mainClock.advanceTimeByFrame() }
                audit(compose.onNodeWithTag(FRAME, useUnmergedTree = true).fetchSemanticsNode())
            }.getOrElse { listOf("did not render: ${it.javaClass.simpleName}: ${it.message?.lineSequence()?.first()}") }
        File(outputDir(), "layout").apply { mkdirs() }.resolve("$wrapper@$variant.txt").writeText(findings.joinToString("") { "$it\n" })
        assertTrue(findings.joinToString("\n", "$wrapper at $variant:\n"), findings.isEmpty())
    }

    @Composable
    private fun Framed() {
        val base = LocalContext.current
        val shown = LocalConfiguration.current
        val configuration =
            remember(shown) {
                Configuration(shown).apply {
                    setLocale(variant.locale)
                    fontScale = variant.fontScale
                    screenWidthDp = variant.widthDp
                    screenHeightDp = variant.heightDp
                }
            }
        val resources = remember(base, configuration) { base.createConfigurationContext(configuration).resources }
        val context =
            remember(base, resources) {
                object : ContextWrapper(base) {
                    override fun getResources(): Resources = resources
                }
            }
        CompositionLocalProvider(
            LocalContext provides context,
            LocalResources provides resources,
            LocalConfiguration provides configuration,
            // The configured context's density carries the platform's non-linear font scaling (API 34+), which grows large
            // text less than small text; a linear 1.5x would draw headlines bigger than any phone does.
            LocalDensity provides Density(context),
            LocalInspectionMode provides true,
        ) {
            // A screen fills the phone it is given; a component is as tall as its content, as its screenshot is, so only
            // its width is the variant's (a fixed height would squeeze a long list's last row and blame the list).
            val size =
                if (screen) {
                    Modifier.requiredSize(variant.widthDp.dp, variant.heightDp.dp)
                } else {
                    Modifier.requiredWidth(variant.widthDp.dp) // as tall as it needs, up to the window: a list must be bounded
                }
            Box(size.clipToBounds().testTag(FRAME)) {
                InvokePreview(owner, method)
            }
        }
    }

    private class Shown(
        val node: SemanticsNode,
        val label: String,
        val bounds: Rect,
        val scroller: SemanticsNode?,
    )

    /** The scroll containers above a node, nearest first on each axis: what can bring a cut-off part into view. */
    private class Scrollers(
        val x: SemanticsNode? = null,
        val y: SemanticsNode? = null,
    ) {
        fun under(node: SemanticsNode) =
            Scrollers(
                if (SemanticsProperties.HorizontalScrollAxisRange in node.config) node else x,
                if (SemanticsProperties.VerticalScrollAxisRange in node.config) node else y,
            )
    }

    /** One walk of the unmerged tree under the frame, collecting findings. */
    private class Audit {
        val findings = mutableListOf<String>()
        private val texts = mutableListOf<Shown>()

        fun walk(
            node: SemanticsNode,
            scrollers: Scrollers = Scrollers(),
        ) {
            val label = label(node)
            if (label != null && node.isSqueezedFlat()) {
                findings += "cut off: \"$label\" squeezed to ${node.size.width}×${node.size.height} px"
            } else if ((label != null || node.scrolls()) && node.isDrawn()) {
                check(node, label, scrollers)
            }
            val below = scrollers.under(node)
            node.children.forEach { walk(it, below) }
        }

        private fun check(
            node: SemanticsNode,
            label: String?,
            scrollers: Scrollers,
        ) {
            // A text is judged by its lines, not its box: a wrapped line fills its constraint's width, and a segmented
            // button shifts that box past its own edge for the check while the centred words sit well inside.
            val full = textLayout(node)?.let { inkOf(node, it) } ?: Rect(node.positionInRoot, node.size.toSize())
            val box = node.boundsInRoot
            // How much of [full] shows on each axis, 0 when none: a box in view can still have every line clipped.
            val shownWidth = (minOf(box.right, full.right) - maxOf(box.left, full.left)).coerceAtLeast(0f)
            val shownHeight = (minOf(box.bottom, full.bottom) - maxOf(box.top, full.top)).coerceAtLeast(0f)
            if (isCut(full, box.isEmpty, shownWidth, shownHeight, scrollers)) {
                val what = label?.let { "\"$it\"" } ?: "a scrolling area's viewport"
                findings += "cut off: $what shows ${shownWidth.px()}×${shownHeight.px()} of ${full.width.px()}×${full.height.px()} px"
            }
            if (label == null) return
            textLayout(node)?.let(::overflow)?.let { findings += "overflows: \"$label\" $it" }
            if (SemanticsProperties.Text in node.config && shownWidth > 0f && shownHeight > 0f) {
                texts += Shown(node, label, box.intersect(full), scrollers.y ?: scrollers.x)
            }
        }

        /** Whether what shows falls short of [full] on an axis no scroll container above can bring into view. */
        private fun isCut(
            full: Rect,
            outOfView: Boolean,
            shownWidth: Float,
            shownHeight: Float,
            scrollers: Scrollers,
        ): Boolean {
            // A node out of view entirely has empty bounds on both axes, so either axis's scroll may be what hid it. Read
            // from the node's own bounds: its lines can all be clipped while its box is still in view.
            if (outOfView) return scrollers.x == null && scrollers.y == null
            return (scrollers.x == null && shownWidth < full.width - TOLERANCE_PX) ||
                (scrollers.y == null && shownHeight < full.height - TOLERANCE_PX)
        }

        fun overlaps() {
            for (i in texts.indices) {
                for (j in i + 1 until texts.size) overlap(texts[i], texts[j])
            }
        }

        private fun overlap(
            a: Shown,
            b: Shown,
        ) {
            if (a.scroller?.id != b.scroller?.id || a.node.contains(b.node) || b.node.contains(a.node)) return
            val both = a.bounds.intersect(b.bounds)
            if (both.width > TOLERANCE_PX && both.height > TOLERANCE_PX) {
                findings += "overlaps: \"${a.label}\" and \"${b.label}\" by ${both.width.px()}×${both.height.px()} px"
            }
        }
    }

    private fun audit(frame: SemanticsNode): List<String> =
        Audit().run {
            walk(frame)
            overlaps()
            findings
        }

    companion object {
        private const val PROPERTY = "knit.previewLayout"
        private const val FRAME = "preview-clipping-frame"

        /** A second of frames, so an enter animation that inspection mode does not settle has finished. */
        private const val SETTLE_FRAMES = 60

        /** Two pixels at xxhdpi, under a dp: rounding between a node's size and its clipped bounds. */
        private const val TOLERANCE_PX = 2f
        private const val LABEL_CHARS = 60

        private fun outputDir() =
            File(requireNotNull(System.getProperty(PROPERTY)) { "run through scripts/preview-layout-audit.py, which sets $PROPERTY" })

        private fun Float.px() = Math.round(this)

        private fun label(node: SemanticsNode): String? {
            val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
            val described = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
            return (text ?: described)?.takeIf { it.isNotBlank() }?.replace('\n', ' ')?.let {
                if (it.length > LABEL_CHARS) it.take(LABEL_CHARS - 1) + "…" else it
            }
        }

        /**
         * What a text's own layout cuts off, or null. Read from its lines, not `hasVisualOverflow`: the layout the
         * semantics action hands back keeps the paragraph at the constraint's width, so a wrap-content text reads as
         * overflowing its own size. An ellipsized line is the layout saying it shortened the text on purpose.
         */
        private fun overflow(layout: TextLayoutResult): String? {
            if ((0 until layout.lineCount).any(layout::isLineEllipsized)) return null
            val wide = (0 until layout.lineCount).maxOfOrNull { layout.getLineRight(it) - layout.getLineLeft(it) } ?: 0f
            val tall = layout.multiParagraph.height
            val cut = wide > layout.size.width + TOLERANCE_PX || tall > layout.size.height + TOLERANCE_PX
            return when {
                layout.multiParagraph.didExceedMaxLines -> "loses lines past its max with no ellipsis"
                cut -> "needs ${wide.px()}×${tall.px()} px in ${layout.size.width}×${layout.size.height}"
                else -> null
            }
        }

        /**
         * Where a text's lines are in the root, when its box is wider than they are: a wrapped text fills its constraint's
         * width. The layout's line positions are in its paragraph's frame, which is the node's only when the paragraph
         * fills it; a text narrower than its paragraph is one whose box is already tight around the words, so null keeps
         * the box. The lines are also relative to the text's own layout, and the node's position is its outermost
         * semantics modifier's: a `clickable` or `semantics` before a `padding` puts the two apart, so a node a different
         * size from its text keeps the box too. An `offset` that moves the text without resizing it is not seen.
         */
        private fun inkOf(
            node: SemanticsNode,
            layout: TextLayoutResult,
        ): Rect? {
            val sameBox =
                abs(node.size.width - layout.size.width) <= TOLERANCE_PX &&
                    abs(node.size.height - layout.size.height) <= TOLERANCE_PX
            if (layout.lineCount == 0 || !sameBox || layout.multiParagraph.width > layout.size.width + TOLERANCE_PX) return null
            val lines = 0 until layout.lineCount
            val at = node.positionInRoot
            return Rect(
                at.x + lines.minOf(layout::getLineLeft),
                at.y + layout.getLineTop(0),
                at.x + lines.maxOf(layout::getLineRight),
                at.y + layout.getLineBottom(layout.lineCount - 1),
            ).takeUnless { it.isEmpty }
        }

        /** The text's layout, except an editable field's: a field scrolls its own text. */
        private fun textLayout(node: SemanticsNode): TextLayoutResult? {
            if (SemanticsActions.SetText in node.config) return null
            val layouts = mutableListOf<TextLayoutResult>()
            node.config
                .getOrNull(SemanticsActions.GetTextLayoutResult)
                ?.action
                ?.invoke(layouts)
            return layouts.firstOrNull()
        }

        private fun SemanticsNode.scrolls() =
            SemanticsProperties.VerticalScrollAxisRange in config || SemanticsProperties.HorizontalScrollAxisRange in config

        private fun SemanticsNode.isDrawn() = layoutInfo.isPlaced && size.width > 0 && size.height > 0

        /** A placed text its parent left no room for: #62 at its worst, the line gone rather than cut. */
        private fun SemanticsNode.isSqueezedFlat() =
            layoutInfo.isPlaced && SemanticsProperties.Text in config && (size.width == 0 || size.height == 0)

        private fun SemanticsNode.contains(other: SemanticsNode): Boolean {
            var up = other.parent
            while (up != null) {
                if (up.id == id) return true
                up = up.parent
            }
            return false
        }

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}@{4}")
        fun previews(): List<Array<Any>> =
            Json.parseToJsonElement(File(outputDir(), "manifest.json").readText()).jsonArray.flatMap { entry ->
                val e = entry.jsonObject
                Variant.entries.map { variant ->
                    arrayOf(
                        e.getValue("wrapper").jsonPrimitive.content,
                        e.getValue("class").jsonPrimitive.content,
                        e.getValue("method").jsonPrimitive.content,
                        e
                            .getValue("screen")
                            .jsonPrimitive.content
                            .toBoolean(),
                        variant,
                    )
                }
            }
    }
}
