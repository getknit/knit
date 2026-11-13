package app.getknit.knit.ui.components

import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The scroll thumb's place on its track, from a lazy list's layout counted in items. The layouts here are hand-built
 * with round sizes, so each expected span and position is plain arithmetic; [ListSpanLazyColumnTest] pins the same
 * reading against a real LazyColumn.
 */
class ScrollThumbTest {
    @Test
    fun aListThatFitsOnScreenHasNoThumb() {
        assertNull(scrollThumb(ListSpan(before = 0f, visible = 10f, after = 0f), trackLength = 800f, minLength = 32f))
    }

    @Test
    fun aListWithAnySliverOffScreenHasAThumb() {
        assertNotNull(scrollThumb(ListSpan(before = 0f, visible = 10f, after = 0.01f), trackLength = 800f, minLength = 32f))
    }

    @Test
    fun aListWithNothingOnScreenHasNoThumb() {
        // Before the first layout nothing is on screen; the thumb's share of the track would be 0, raised to a
        // minimum-length thumb drawn before there is anything to scroll.
        assertNull(scrollThumb(ListSpan(before = 0f, visible = 0f, after = 40f), trackLength = 800f, minLength = 32f))
    }

    @Test
    fun aContainerTooShortForItsInsetsHasNoThumb() {
        // The track is the container's height less its insets, so a container under 8dp leaves none; a thumb there
        // would come out zero or negative in length.
        assertNull(scrollThumb(ListSpan(before = 0f, visible = 10f, after = 30f), trackLength = 0f, minLength = 32f))
        assertNull(scrollThumb(ListSpan(before = 0f, visible = 10f, after = 30f), trackLength = -4f, minLength = 32f))
    }

    @Test
    fun theThumbIsTheScreensShareOfTheList() {
        // A quarter of the items are on screen, so the thumb is a quarter of the track.
        val thumb = scrollThumb(ListSpan(before = 0f, visible = 10f, after = 30f), trackLength = 800f, minLength = 32f)
        assertEquals(ScrollThumb(start = 0f, length = 200f), thumb)
    }

    @Test
    fun scrolledToTheEndTheThumbMeetsTheTracksEnd() {
        val thumb = scrollThumb(ListSpan(before = 30f, visible = 10f, after = 0f), trackLength = 800f, minLength = 32f)
        assertEquals(ScrollThumb(start = 600f, length = 200f), thumb)
    }

    @Test
    fun halfwayDownTheThumbIsHalfwayAlongWhatItCanTravel() {
        val thumb = scrollThumb(ListSpan(before = 15f, visible = 10f, after = 15f), trackLength = 800f, minLength = 32f)
        assertEquals(ScrollThumb(start = 300f, length = 200f), thumb)
    }

    @Test
    fun aVeryLongListKeepsTheMinimumThumbAndStillReachesTheEnd() {
        // 10 of 1000 items on screen would be an 8 px thumb; it stays at the minimum and travels the rest.
        val thumb = scrollThumb(ListSpan(before = 990f, visible = 10f, after = 0f), trackLength = 800f, minLength = 32f)
        assertEquals(ScrollThumb(start = 768f, length = 32f), thumb)
    }

    @Test
    fun aMinimumLongerThanTheTrackIsCutToTheTrack() {
        val thumb = scrollThumb(ListSpan(before = 0f, visible = 10f, after = 30f), trackLength = 20f, minLength = 32f)
        assertEquals(ScrollThumb(start = 0f, length = 20f), thumb)
    }

    @Test
    fun aNegativeCountPinsTheThumbToThatEnd() {
        // Float sums can land a hair under zero at an edge; the thumb stays on the track.
        val thumb = scrollThumb(ListSpan(before = -0.001f, visible = 10f, after = 30f), trackLength = 800f, minLength = 32f)
        assertEquals(0f, checkNotNull(thumb).start, 0f)
    }

    @Test
    fun anItemCutByAnEdgeCountsByItsShareOnEachSide() {
        // 100 px items, a 400 px screen scrolled 150 px: item 1 is half above the top, item 5 half below the bottom.
        val span = layoutAt(List(10) { 100 }, scroll = 150, viewport = 400).listSpan()
        assertEquals(ListSpan(before = 1.5f, visible = 4f, after = 4.5f), span)
    }

    @Test
    fun anItemTallerThanTheScreenCountsTheShareOfItOnScreen() {
        // A 1000 px photo between two 100 px rows, scrolled so the 500 px screen shows its middle half.
        val span = layoutAt(listOf(100, 1000, 100), scroll = 350, viewport = 500).listSpan()
        assertEquals(ListSpan(before = 1.25f, visible = 0.5f, after = 1.25f), span)
    }

    @Test
    fun anItemLaidOutWhollyPastAnEdgeAddsNothing() {
        // A prefetched item beyond the bottom edge: the bottom is still 88% of the way through item 4, not at item 5.
        val laidOut = (0..5).map { Item(index = it, offset = it * 100, size = 100) }
        val span = Layout(laidOut, viewportStartOffset = 0, viewportEndOffset = 488, totalItemsCount = 20).listSpan()
        assertSpan(ListSpan(before = 0f, visible = 4.88f, after = 15.12f), checkNotNull(span))
    }

    @Test
    fun aReverseLayoutDrawsWhatItsIndicesPutFirstAtTheBottom() {
        // A thread opened on its newest message: indices 0..4 on screen, drawn at the bottom, the history above.
        val laidOut = (0..4).map { Item(index = it, offset = it * 100, size = 100) }
        val span = Layout(laidOut, 0, 500, totalItemsCount = 20, reverseLayout = true).listSpan()
        assertEquals(ListSpan(before = 15f, visible = 5f, after = 0f), span)
    }

    @Test
    fun nothingLaidOutHasNoSpan() {
        assertNull(Layout(emptyList(), 0, 500, totalItemsCount = 0).listSpan())
    }

    @Test
    fun theThumbNeverJumpsWhileScrollingPastItemsOfVeryDifferentHeights() {
        // The reason the thumb counts items: a thread of one-line bubbles, photos and long messages. Measured in pixels
        // from the average height on screen, the thumb leapt by hundreds of px as a photo came into view. Counted in
        // items it moves continuously, here by under a px for each px the list scrolls.
        val heights = List(5) { listOf(60, 60, 700, 60, 300, 60, 60, 1200) }.flatten()
        val viewport = 2000
        val track = 1900f
        var previous: ScrollThumb? = null
        var largestStep = 0f
        for (scroll in 0..heights.sum() - viewport) {
            val thumb = checkNotNull(scrollThumb(checkNotNull(layoutAt(heights, scroll, viewport).listSpan()), track, 32f))
            previous?.let { largestStep = maxOf(largestStep, abs(thumb.start - it.start), abs(thumb.length - it.length)) }
            previous = thumb
        }
        assertTrue("largest step for a 1 px scroll was $largestStep px", largestStep <= MAX_STEP_PER_PX)
        // And it still covers the whole track: the last position ends at the track's end.
        val last = checkNotNull(previous)
        assertEquals(track, last.start + last.length, 0.01f)
    }

    private fun assertSpan(
        expected: ListSpan,
        actual: ListSpan,
    ) {
        assertEquals("before", expected.before, actual.before, 0.001f)
        assertEquals("visible", expected.visible, actual.visible, 0.001f)
        assertEquals("after", expected.after, actual.after, 0.001f)
    }

    /** The layout a list of [heights] reports scrolled [scroll] px down a [viewport] px screen with no padding. */
    private fun layoutAt(
        heights: List<Int>,
        scroll: Int,
        viewport: Int,
    ): LazyListLayoutInfo {
        val laidOut = mutableListOf<LazyListItemInfo>()
        var top = 0
        heights.forEachIndexed { index, height ->
            if (top + height > scroll && top < scroll + viewport) laidOut += Item(index, offset = top - scroll, size = height)
            top += height
        }
        return Layout(laidOut, viewportStartOffset = 0, viewportEndOffset = viewport, totalItemsCount = heights.size)
    }

    private data class Item(
        override val index: Int,
        override val offset: Int,
        override val size: Int,
    ) : LazyListItemInfo {
        override val key: Any get() = index
    }

    private class Layout(
        override val visibleItemsInfo: List<LazyListItemInfo>,
        override val viewportStartOffset: Int,
        override val viewportEndOffset: Int,
        override val totalItemsCount: Int,
        override val reverseLayout: Boolean = false,
    ) : LazyListLayoutInfo

    private companion object {
        // Counted in items this thread's largest step is 0.79 px; the average-height estimate it replaced stepped 91 px.
        const val MAX_STEP_PER_PX = 2f
    }
}
