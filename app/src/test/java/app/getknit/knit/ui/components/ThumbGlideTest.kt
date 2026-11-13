package app.getknit.knit.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How the thumb crosses a change in the list's length (a page of history, a new message): it starts from where it
 * was, glides to where it belongs as the node's animation runs its progress from 1 to 0, and between length changes
 * follows the scroll with no lag. The progress values stand in for that animation's frames.
 */
class ThumbGlideTest {
    private val glide = ThumbGlide()

    @Test
    fun theFirstPlacementDrawsTheTargetAndStartsNoGlide() {
        assertEquals(Placement(NEAR_TOP, startGlide = false), glide.place(NEAR_TOP, count = 100, progress = 0f))
    }

    @Test
    fun withoutALengthChangeTheThumbFollowsTheScrollExactly() {
        // Outside a glide the node passes 0: the thumb lands on each new target with no lag behind the scroll.
        glide.place(NEAR_TOP, count = 100, progress = 0f)
        assertEquals(Placement(LOWER, startGlide = false), glide.place(LOWER, count = 100, progress = 0f))
    }

    @Test
    fun aLengthChangeHoldsTheThumbWhereItWasAndAsksForAGlide() {
        // A page of history arrives: the target drops to LOWER, but this frame still shows the thumb at NEAR_TOP.
        glide.place(NEAR_TOP, count = 100, progress = 0f)
        assertEquals(Placement(NEAR_TOP, startGlide = true), glide.place(LOWER, count = 200, progress = 0f))
    }

    @Test
    fun theGlidePassesHalfwayAndEndsOnTheTarget() {
        glide.place(NEAR_TOP, count = 100, progress = 0f)
        glide.place(LOWER, count = 200, progress = 1f)
        assertEquals(Placement(HALFWAY, startGlide = false), glide.place(LOWER, count = 200, progress = 0.5f))
        assertEquals(Placement(LOWER, startGlide = false), glide.place(LOWER, count = 200, progress = 0f))
    }

    @Test
    fun aScrollDuringAGlideMovesTheThumbWithIt() {
        // Halfway through the glide the reader scrolls 100 px further: the thumb moves the same 100 px.
        glide.place(NEAR_TOP, count = 100, progress = 0f)
        glide.place(LOWER, count = 200, progress = 1f)
        val scrolled = ScrollThumb(start = LOWER.start + 100f, length = LOWER.length)
        val placed = glide.place(scrolled, count = 200, progress = 0.5f)
        assertEquals(ScrollThumb(start = HALFWAY.start + 100f, length = HALFWAY.length), placed.thumb)
    }

    @Test
    fun aSecondLengthChangeMidGlideStartsFromWhereTheThumbIsNow() {
        // Another page lands before the first glide ends: the new glide starts from the halfway thumb on screen, not
        // from where the first one started or was going.
        glide.place(NEAR_TOP, count = 100, progress = 0f)
        glide.place(LOWER, count = 200, progress = 1f)
        glide.place(LOWER, count = 200, progress = 0.5f)
        val further = ScrollThumb(start = 700f, length = 10f)
        assertEquals(Placement(HALFWAY, startGlide = true), glide.place(further, count = 300, progress = 0.5f))
        assertEquals(Placement(further, startGlide = false), glide.place(further, count = 300, progress = 0f))
    }

    @Test
    fun afterTheThumbLeavesTheScreenALengthChangeDoesNotGlide() {
        // The thumb faded out; when it next shows, nothing on screen marks where it was, so it starts on its target.
        glide.place(NEAR_TOP, count = 100, progress = 0f)
        glide.forget()
        assertEquals(Placement(LOWER, startGlide = false), glide.place(LOWER, count = 200, progress = 0f))
    }

    @Test
    fun forgettingMidGlideLeavesAStaleProgressNothingToMove() {
        // Detached mid-glide, the node's animation stays where the spring was (here 0.4) and the next attach passes
        // that until a new length change. With the glide's starting offset forgotten too, the thumb sits on its target.
        glide.place(NEAR_TOP, count = 100, progress = 0f)
        glide.place(LOWER, count = 200, progress = 1f)
        glide.place(LOWER, count = 200, progress = 0.5f)
        glide.forget()
        glide.place(LOWER, count = 200, progress = 0.4f)
        assertEquals(Placement(LOWER, startGlide = false), glide.place(LOWER, count = 200, progress = 0.4f))
    }

    private companion object {
        val NEAR_TOP = ScrollThumb(start = 100f, length = 40f)
        val LOWER = ScrollThumb(start = 500f, length = 20f)
        val HALFWAY = ScrollThumb(start = 300f, length = 30f)
    }
}
