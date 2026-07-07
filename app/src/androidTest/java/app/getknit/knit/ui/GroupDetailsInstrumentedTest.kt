package app.getknit.knit.ui

import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The seeded group opens from the chat list and its details screen renders the roster. The group id is
 * derived from the (runtime) member set, so it can't be deep-linked by route — navigate through the UI:
 * chat list row -> group chat -> details via the top-bar avatar.
 */
@RunWith(AndroidJUnit4::class)
class GroupDetailsInstrumentedTest : SeededUiTest() {
    @Test
    fun opensSeededGroupRoster() {
        launch()

        awaitContentDescription("Trailhead Crew")
        compose.onAllNodesWithContentDescription("Trailhead Crew", substring = true).onFirst().performClick()

        awaitTag("chat_group_avatar")
        compose.onNodeWithTag("chat_group_avatar").performClick()

        // Group details: Sam Rivera (samr1v00) is a seeded member, and the group title renders.
        awaitTag("group_member_samr1v00")
        awaitText("Trailhead Crew")
    }
}
