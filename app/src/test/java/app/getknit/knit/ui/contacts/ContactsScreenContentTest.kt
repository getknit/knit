package app.getknit.knit.ui.contacts

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
class ContactsScreenContentTest {
    @get:Rule
    val compose = createComposeRule()

    private val contacts =
        listOf(
            Contact(nodeId = "a", displayName = "Alice", avatarHash = null, online = true),
            Contact(nodeId = "b", displayName = "Bob", avatarHash = null, online = false),
        )

    @Test
    fun selectingOneContactThenConfirmingStartsADm() {
        var picked: String? = null
        compose.setContent {
            KnitTheme {
                ContactsScreenContent(
                    contacts = contacts,
                    onBack = {},
                    onPickSingle = { picked = it },
                    onCreateGroup = {},
                    onGroupFull = {},
                )
            }
        }

        compose.onNodeWithTag("contact_a").performClick() // select
        compose.onNodeWithTag("contacts_fab").performClick() // confirm
        assertEquals("a", picked)
    }

    @Test
    fun selectingTwoContactsThenConfirmingCreatesAGroup() {
        var members: List<String>? = null
        compose.setContent {
            KnitTheme {
                ContactsScreenContent(
                    contacts = contacts,
                    onBack = {},
                    onPickSingle = {},
                    onCreateGroup = { members = it },
                    onGroupFull = {},
                )
            }
        }

        compose.onNodeWithTag("contact_a").performClick()
        compose.onNodeWithTag("contact_b").performClick()
        compose.onNodeWithTag("contacts_fab").performClick()
        assertEquals(setOf("a", "b"), members?.toSet())
    }
}
