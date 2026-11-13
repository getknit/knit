package app.getknit.knit.ui.components

import android.view.ViewConfiguration
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.getknit.knit.ui.theme.KnitMotion
import app.getknit.knit.ui.theme.LocalReduceMotion
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * A thin scroll thumb along the end edge of a lazy list, shown while it scrolls and faded out shortly after, the way the
 * platform's View scrollbar behaves. Compose draws none of its own on Android.
 *
 * Display only: it takes no touches, adds no semantics (TalkBack already announces a list's position), and draws
 * nothing while the content fits. Apply it to the list itself (`LazyColumn(modifier = …)`), so the thumb sits outside
 * the list's clip and overscroll stretch.
 *
 * The thumb measures the list in items, not pixels. A lazy list only measures the items on screen, so a pixel height
 * for the whole list is a guess from their average, and in a thread where a one-line bubble sits beside a photo ten
 * times its height that guess swings each time a tall item enters or leaves the screen, throwing the thumb around.
 * Counted in items, every quantity moves continuously with the scroll: the thumb passes a tall item more slowly than a
 * short one, but it never jumps. It moves without a scroll only when the list gains or loses items (a page of history,
 * a new message), and then it glides to its new place on the spatial spring ([ThumbGlide]) instead of jumping there.
 *
 * @param state the list's state.
 * @param color the thumb's colour. Unspecified takes the local content colour, made light enough to read as a
 *   position rather than a control.
 */
fun Modifier.verticalScrollIndicator(
    state: LazyListState,
    color: Color = Color.Unspecified,
): Modifier = this then ScrollIndicatorElement(state, color)

/**
 * How much of a list lies above the screen, on it and below it, in items, top to bottom as drawn. An item cut by the
 * screen's edge counts by the share of it on each side.
 */
internal data class ListSpan(
    val before: Float,
    val visible: Float,
    val after: Float,
)

/**
 * This layout's [ListSpan], or null before anything is laid out. The viewport's edges include the content padding,
 * which is still on screen. A reverse layout counts its offsets from the bottom, so what its indices put before the
 * screen is drawn below it.
 *
 * Every laid-out item adds its own share rather than the first and last standing for the rest: the list may lay out an
 * item wholly past an edge (a prefetch, a focus search), and reading that one as the edge's item would skip the share
 * of the item the edge really cuts.
 */
internal fun LazyListLayoutInfo.listSpan(): ListSpan? {
    val items = visibleItemsInfo
    if (items.isEmpty() || totalItemsCount <= 0) return null
    // The items before the first laid-out one lie wholly before both edges.
    var start = items.first().index.toFloat()
    var end = start
    for (item in items) {
        start += item.shareBefore(viewportStartOffset)
        end += item.shareBefore(viewportEndOffset)
    }
    val after = totalItemsCount - end
    return if (reverseLayout) ListSpan(after, end - start, start) else ListSpan(start, end - start, after)
}

/** The share of this item that lies before [edge]: 0 when it starts at or past the edge, 1 when it ends by it. */
private fun LazyListItemInfo.shareBefore(edge: Int): Float = if (size <= 0) 0f else ((edge - offset).toFloat() / size).coerceIn(0f, 1f)

/** A thumb's place along its track, in pixels from the track's start. */
internal data class ScrollThumb(
    val start: Float,
    val length: Float,
)

/**
 * Where the thumb sits on a track [trackLength] long, or null when there is nothing to scroll. The thumb's share of
 * the track is the screen's share of the list, never shorter than [minLength] so a long list keeps a thumb you can
 * see, and its start moves from the top to the bottom of the track as the list scrolls from its first item to its last.
 */
internal fun scrollThumb(
    span: ListSpan,
    trackLength: Float,
    minLength: Float,
): ScrollThumb? {
    val before = span.before.coerceAtLeast(0f)
    val after = span.after.coerceAtLeast(0f)
    if (span.visible <= 0f || before + after <= 0f || trackLength <= 0f) return null
    val length =
        (trackLength * span.visible / (before + span.visible + after)).coerceIn(minOf(minLength, trackLength), trackLength)
    return ScrollThumb(start = (trackLength - length) * before / (before + after), length = length)
}

/**
 * Carries the thumb from where it was to where it belongs when the list's length changes under it (a page of history
 * arriving, a new message), so the change reads as a glide rather than a jump. Between length changes it hands back the
 * target untouched, so the thumb follows a scroll with no lag.
 *
 * Bookkeeping only, so it needs no frame clock: the node runs the glide and passes in how far along it is, from 1
 * (where the thumb was when the length changed) to 0 (where it belongs).
 */
internal class ThumbGlide {
    private var count = UNSEEN
    private var shown: ScrollThumb? = null
    private var fromStart = 0f
    private var fromLength = 0f

    /**
     * Where to draw the thumb whose place is [target] in a list of [count] items, [progress] of the way through the
     * current glide. When [count] differs from the last call's, a new glide starts from the thumb last shown, and
     * [Placement.startGlide] asks the caller to run it from 1 to 0.
     */
    fun place(
        target: ScrollThumb,
        count: Int,
        progress: Float,
    ): Placement {
        val last = shown
        val startGlide = last != null && count != this.count
        if (startGlide) {
            fromStart = last.start - target.start
            fromLength = last.length - target.length
        }
        this.count = count
        val along = if (startGlide) 1f else progress
        val thumb = ScrollThumb(start = target.start + fromStart * along, length = target.length + fromLength * along)
        shown = thumb
        return Placement(thumb, startGlide)
    }

    /** The thumb has left the screen: the next length change has nothing on screen to glide from. */
    fun forget() {
        count = UNSEEN
        shown = null
        fromStart = 0f
        fromLength = 0f
    }

    private companion object {
        const val UNSEEN = -1
    }
}

/** The thumb [ThumbGlide] says to draw, and whether a glide starts with it. */
internal data class Placement(
    val thumb: ScrollThumb,
    val startGlide: Boolean,
)

private data class ScrollIndicatorElement(
    val state: LazyListState,
    val color: Color,
) : ModifierNodeElement<ScrollIndicatorNode>() {
    override fun create() = ScrollIndicatorNode(state, color)

    override fun update(node: ScrollIndicatorNode) = node.update(state, color)

    override fun InspectorInfo.inspectableProperties() {
        name = "verticalScrollIndicator"
        properties["state"] = state
        properties["color"] = color
    }
}

/**
 * A node rather than a composed modifier: the thumb follows the scroll in the draw phase alone, so a scrolling list
 * repaints its thumb without recomposing anything. At rest it reads only its own fade, so a settled list's scroll
 * position never invalidates it.
 */
private class ScrollIndicatorNode(
    private var state: LazyListState,
    private var color: Color,
) : Modifier.Node(),
    DrawModifierNode,
    CompositionLocalConsumerModifierNode {
    private val visibility = Animatable(0f)
    private var watcher: Job? = null
    private val glide = Animatable(0f)
    private val placement = ThumbGlide()
    private var glider: Job? = null

    // A glide asked for by a draw, before its coroutine has snapped the progress to 1: the draws in between hold the
    // thumb where it was rather than read a finished glide's 0 and jump.
    private var glidePending = false

    override fun onAttach() {
        watch()
    }

    override fun onDetach() {
        // Detach cancels the glide wherever it was; start the next attach from a clean slate.
        glidePending = false
        placement.forget()
    }

    fun update(
        state: LazyListState,
        color: Color,
    ) {
        if (state !== this.state) {
            this.state = state
            placement.forget()
            if (isAttached) watch()
        }
        this.color = color
        invalidateDraw()
    }

    // Detach cancels coroutineScope, and with it the watcher; a fresh attach starts a new one.
    private fun watch() {
        watcher?.cancel()
        watcher =
            coroutineScope.launch {
                snapshotFlow { state.isScrollInProgress }.collectLatest { scrolling ->
                    val spec = KnitMotion.effectsFor<Float>(currentValueOf(LocalReduceMotion))
                    if (scrolling) {
                        visibility.animateTo(1f, spec)
                    } else {
                        // The platform scrollbar's own hold before it fades, so a fling's pause doesn't flicker it.
                        delay(ViewConfiguration.getScrollDefaultDelay().toLong())
                        visibility.animateTo(0f, spec)
                    }
                }
            }
    }

    private fun startGlide() {
        glidePending = true
        glider?.cancel()
        glider =
            coroutineScope.launch {
                glide.snapTo(1f)
                glidePending = false
                glide.animateTo(0f, KnitMotion.spatialFor(currentValueOf(LocalReduceMotion)))
            }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val alpha = visibility.value
        // Read on every pass, held or not, so each frame of a glide redraws the thumb.
        val glided = glide.value
        // At rest the thumb reads nothing of the list, so a settled list's changes never redraw it.
        if (alpha <= 0f || !(state.canScrollBackward || state.canScrollForward)) {
            placement.forget()
            return
        }
        val info = state.layoutInfo
        val inset = TRACK_INSET.toPx()
        val target = info.listSpan()?.let { scrollThumb(it, size.height - 2 * inset, MIN_THUMB_LENGTH.toPx()) }
        if (target == null) {
            placement.forget()
            return
        }
        val progress = if (glidePending) 1f else glided
        val placed = placement.place(target, info.totalItemsCount, progress)
        if (placed.startGlide) startGlide()
        val thumb = placed.thumb
        val thickness = THUMB_THICKNESS.toPx()
        val gap = EDGE_GAP.toPx()
        val x = if (layoutDirection == LayoutDirection.Ltr) size.width - gap - thickness else gap
        val thumbColor = color.takeOrElse { currentValueOf(LocalContentColor).copy(alpha = DEFAULT_THUMB_OPACITY) }
        drawRoundRect(
            color = thumbColor,
            topLeft = Offset(x, inset + thumb.start),
            size = Size(thickness, thumb.length),
            cornerRadius = CornerRadius(thickness / 2),
            alpha = alpha,
        )
    }
}

// The platform scrollbar's 4dp width, kept off the screen edge and the list's top and bottom by a hair.
private val THUMB_THICKNESS = 4.dp
private val EDGE_GAP = 2.dp
private val TRACK_INSET = 4.dp

// Long enough to find on a list of hundreds of conversations, short enough to still move on a list of twenty.
private val MIN_THUMB_LENGTH = 32.dp

// Light enough that the thumb reads as a position, not as a control to grab.
private const val DEFAULT_THUMB_OPACITY = 0.4f
