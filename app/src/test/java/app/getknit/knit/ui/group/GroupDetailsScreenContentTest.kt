package app.getknit.knit.ui.group

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GroupDetailsScreenContentTest {
    @get:Rule
    val compose = createComposeRule()

    private fun state() =
        GroupDetailsUiState(
            groupId = "g-1",
            title = "Weekend Trip",
            photoHash = null,
            members =
                listOf(
                    GroupMemberRow(nodeId = "me", displayName = "You", avatarHash = null, online = false, isSelf = true),
                    GroupMemberRow(nodeId = "a", displayName = "Alice", avatarHash = null, online = true, isSelf = false),
                ),
            exists = true,
        )

    @Test
    fun tappingANonSelfMemberOpensTheirProfile() {
        var opened: String? = null
        compose.setContent {
            KnitTheme {
                GroupDetailsScreenContent(
                    state = state(),
                    onBack = {},
                    onOpenMemberProfile = { opened = it },
                    onSetPhoto = {},
                    onRename = {},
                    onLeave = {},
                )
            }
        }

        compose.onNodeWithTag("group_member_a").assertIsDisplayed()
        compose.onNodeWithTag("group_member_a").performClick()
        assertEquals("a", opened)
    }
}
