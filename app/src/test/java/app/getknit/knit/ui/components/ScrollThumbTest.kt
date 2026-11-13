package app.getknit.knit.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The scroll thumb's place on its track, from the numbers foundation's `ScrollIndicatorState` reports. The track,
 * viewport and content are round pixel counts so each expected position is plain arithmetic.
 */
class ScrollThumbTest {
    @Test
    fun contentThatFitsItsViewportHasNoThumb() {
        assertNull(scrollThumb(scrollOffset = 0, contentSize = 1000, viewportSize = 1000, trackLength = 800f, minLength = 32f))
    }

    @Test
    fun contentOnePixelTallerThanItsViewportHasAThumb() {
        assertNotNull(scrollThumb(scrollOffset = 0, contentSize = 1001, viewportSize = 1000, trackLength = 800f, minLength = 32f))
    }

    @Test
    fun anUnmeasuredViewportHasNoThumb() {
        // Before the first layout the viewport can read 0 beside an estimated content size; its share of the track would
        // be 0, raised to a minimum-length thumb drawn before there is anything on screen to scroll.
        assertNull(scrollThumb(scrollOffset = 0, contentSize = 4000, viewportSize = 0, trackLength = 800f, minLength = 32f))
    }

    @Test
    fun aContainerTooShortForItsInsetsHasNoThumb() {
        // The track is the container's height less its insets, so a container under 8dp leaves none; a thumb there
        // would come out zero or negative in length.
        assertNull(scrollThumb(scrollOffset = 0, contentSize = 4000, viewportSize = 1000, trackLength = 0f, minLength = 32f))
        assertNull(scrollThumb(scrollOffset = 0, contentSize = 4000, viewportSize = 1000, trackLength = -4f, minLength = 32f))
    }

    @Test
    fun theThumbIsTheViewportsShareOfTheTrack() {
        // A quarter of the content is on screen, so the thumb is a quarter of the track.
        val thumb = scrollThumb(scrollOffset = 0, contentSize = 4000, viewportSize = 1000, trackLength = 800f, minLength = 32f)
        assertEquals(ScrollThumb(start = 0f, length = 200f), thumb)
    }

    @Test
    fun scrolledToTheEndTheThumbMeetsTheTracksEnd() {
        val thumb = scrollThumb(scrollOffset = 3000, contentSize = 4000, viewportSize = 1000, trackLength = 800f, minLength = 32f)
        assertEquals(ScrollThumb(start = 600f, length = 200f), thumb)
    }

    @Test
    fun halfwayDownTheThumbIsHalfwayAlongWhatItCanTravel() {
        val thumb = scrollThumb(scrollOffset = 1500, contentSize = 4000, viewportSize = 1000, trackLength = 800f, minLength = 32f)
        assertEquals(ScrollThumb(start = 300f, length = 200f), thumb)
    }

    @Test
    fun aVeryLongListKeepsTheMinimumThumbAndStillReachesTheEnd() {
        // 1000 of 100000 px on screen would be an 8 px thumb; it stays at the minimum and travels the rest.
        val thumb = scrollThumb(scrollOffset = 99000, contentSize = 100000, viewportSize = 1000, trackLength = 800f, minLength = 32f)
        assertEquals(ScrollThumb(start = 768f, length = 32f), thumb)
    }

    @Test
    fun aMinimumLongerThanTheTrackIsCutToTheTrack() {
        // A very short container: the thumb never overflows the track it is drawn on.
        val thumb = scrollThumb(scrollOffset = 0, contentSize = 4000, viewportSize = 1000, trackLength = 20f, minLength = 32f)
        assertEquals(ScrollThumb(start = 0f, length = 20f), thumb)
    }

    @Test
    fun anOffsetPastEitherEndPinsTheThumbToThatEnd() {
        // An estimated content size can shrink under the current offset for a frame; the thumb stays on the track.
        val past = scrollThumb(scrollOffset = 5000, contentSize = 4000, viewportSize = 1000, trackLength = 800f, minLength = 32f)
        val before = scrollThumb(scrollOffset = -10, contentSize = 4000, viewportSize = 1000, trackLength = 800f, minLength = 32f)
        assertEquals(ScrollThumb(start = 600f, length = 200f), past)
        assertEquals(ScrollThumb(start = 0f, length = 200f), before)
    }
}
