package app.getknit.knit.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * [listSpan] read off a real LazyColumn, for the two things a hand-built layout can only assume: offsets are counted
 * from the content's start, past the padding, and a reverse layout counts them from the bottom. A chat thread is a
 * reverse layout with padding; reading either wrong puts the thumb at the wrong end of the thread.
 *
 * Twenty 100 px items in a 500 px list with 12 px of padding at each end (mdpi, so a dp is a px): 2024 px of content,
 * of which the screen shows 500, so 4.88 items' worth, and the other 15.12 lie off it.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w400dp-h800dp-mdpi")
class ListSpanLazyColumnTest {
    @Suppress("DEPRECATION") // junit4.v2 rules swap in StandardTestDispatcher — a test-semantics migration, see roadmap.md
    @get:Rule
    val compose = createComposeRule()

    private lateinit var state: LazyListState

    private fun render(reverseLayout: Boolean) =
        compose.setContent {
            state = rememberLazyListState()
            LazyColumn(
                state = state,
                modifier = Modifier.size(100.dp, 500.dp),
                contentPadding = PaddingValues(vertical = 12.dp),
                reverseLayout = reverseLayout,
            ) {
                items(ITEMS) { Box(Modifier.fillMaxWidth().height(100.dp)) }
            }
        }

    private fun scrollToLast() = compose.runOnIdle { runBlocking { state.scrollToItem(ITEMS - 1) } }

    private fun span() = compose.runOnIdle { checkNotNull(state.layoutInfo.listSpan()) }

    @Test
    fun aListAtItsTopHasEverythingOffScreenBelow() {
        render(reverseLayout = false)
        assertSpan(ListSpan(before = 0f, visible = ON_SCREEN, after = OFF_SCREEN), span())
    }

    @Test
    fun aListAtItsEndHasEverythingOffScreenAbove() {
        render(reverseLayout = false)
        scrollToLast()
        assertSpan(ListSpan(before = OFF_SCREEN, visible = ON_SCREEN, after = 0f), span())
    }

    @Test
    fun aThreadOpenedOnItsNewestMessageHasItsHistoryAbove() {
        // Reverse layout: index 0 is drawn at the bottom, so the thumb starts at the bottom of the track.
        render(reverseLayout = true)
        assertSpan(ListSpan(before = OFF_SCREEN, visible = ON_SCREEN, after = 0f), span())
    }

    @Test
    fun aThreadScrolledBackToItsOldestMessageHasEverythingBelow() {
        render(reverseLayout = true)
        scrollToLast()
        assertSpan(ListSpan(before = 0f, visible = ON_SCREEN, after = OFF_SCREEN), span())
    }

    private fun assertSpan(
        expected: ListSpan,
        actual: ListSpan,
    ) {
        assertEquals("items above the screen in $actual", expected.before, actual.before, TOLERANCE)
        assertEquals("items on screen in $actual", expected.visible, actual.visible, TOLERANCE)
        assertEquals("items below the screen in $actual", expected.after, actual.after, TOLERANCE)
    }

    private companion object {
        const val ITEMS = 20

        // At either end of the list 12 px of the 500 px screen is padding, leaving 488 px of 100 px items.
        const val ON_SCREEN = 4.88f
        const val OFF_SCREEN = ITEMS - ON_SCREEN
        const val TOLERANCE = 0.001f
    }
}
