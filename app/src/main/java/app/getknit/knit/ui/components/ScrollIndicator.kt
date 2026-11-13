package app.getknit.knit.ui.components

import android.view.ViewConfiguration
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.ScrollableState
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
 * A thin scroll thumb along the end edge of a scrolling container, shown while it scrolls and faded out shortly after,
 * the way the platform's View scrollbar behaves. Compose draws none of its own on Android.
 *
 * Display only: it takes no touches, adds no semantics (TalkBack already announces a list's position), and draws
 * nothing while the content fits. Apply it to the scrolling container itself (`LazyColumn(modifier = …)`), so the
 * thumb sits outside the list's clip and overscroll stretch.
 *
 * A lazy list only measures the items on screen, so its content height is an estimate from their average height: the
 * thumb can shift a little when an item of an unusual height scrolls in. That is foundation's
 * [ScrollableState.scrollIndicatorState], not something to correct here.
 *
 * @param state the container's state; a [ScrollableState] that reports no `scrollIndicatorState` draws nothing.
 * @param color the thumb's colour. Unspecified takes the local content colour, made light enough to read as a
 *   position rather than a control.
 */
fun Modifier.verticalScrollIndicator(
    state: ScrollableState,
    color: Color = Color.Unspecified,
): Modifier = this then ScrollIndicatorElement(state, color)

/** A thumb's place along its track, in pixels from the track's start. */
internal data class ScrollThumb(
    val start: Float,
    val length: Float,
)

/**
 * Where the thumb sits on a track [trackLength] long, or null when there is nothing to scroll. The thumb's share of
 * the track is the viewport's share of the content, never shorter than [minLength] so a long list keeps a thumb you
 * can see, and its start moves from the top to the bottom of the track as [scrollOffset] goes from 0 to the most the
 * content can scroll.
 */
internal fun scrollThumb(
    scrollOffset: Int,
    contentSize: Int,
    viewportSize: Int,
    trackLength: Float,
    minLength: Float,
): ScrollThumb? {
    if (viewportSize <= 0 || contentSize <= viewportSize || trackLength <= 0f) return null
    val length = (trackLength * viewportSize / contentSize).coerceIn(minOf(minLength, trackLength), trackLength)
    val fraction = (scrollOffset.toFloat() / (contentSize - viewportSize)).coerceIn(0f, 1f)
    return ScrollThumb(start = (trackLength - length) * fraction, length = length)
}

private data class ScrollIndicatorElement(
    val state: ScrollableState,
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
    private var state: ScrollableState,
    private var color: Color,
) : Modifier.Node(),
    DrawModifierNode,
    CompositionLocalConsumerModifierNode {
    private val visibility = Animatable(0f)
    private var watcher: Job? = null

    override fun onAttach() {
        watch()
    }

    fun update(
        state: ScrollableState,
        color: Color,
    ) {
        if (state !== this.state) {
            this.state = state
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

    override fun ContentDrawScope.draw() {
        drawContent()
        val alpha = visibility.value
        if (alpha <= 0f) return
        val indicator = state.scrollIndicatorState ?: return
        val inset = TRACK_INSET.toPx()
        val thumb =
            scrollThumb(
                scrollOffset = indicator.scrollOffset,
                contentSize = indicator.contentSize,
                viewportSize = indicator.viewportSize,
                trackLength = size.height - 2 * inset,
                minLength = MIN_THUMB_LENGTH.toPx(),
            ) ?: return
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
