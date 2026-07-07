package app.getknit.knit.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/** The seeded chat list renders the Nearby room and the seeded 1:1 DM threads. */
@RunWith(AndroidJUnit4::class)
class ChatListInstrumentedTest : SeededUiTest() {
    @Test
    fun showsSeededNearbyRoomAndDmThreads() {
        launch()

        awaitTag("chat_row_nearby")
        compose.onNodeWithTag("chat_row_nearby").assertIsDisplayed()

        // Seeded 1:1 DM with Sam Rivera (node id samr1v00); the row's title is folded into its
        // contentDescription (the row uses clearAndSetSemantics), not a separate text node.
        awaitTag("chat_row_samr1v00")
        awaitContentDescription("Sam Rivera")
    }
}
