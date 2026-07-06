package app.getknit.knit.ui.profile

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** Save mirrors the ViewModel's `isDirty`: disabled with no unsaved edits, enabled (and firing) once dirty. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfileScreenContentTest {
    @get:Rule
    val compose = createComposeRule()

    private fun form(isDirty: Boolean) =
        ProfileFormState(
            name = "Alice",
            status = "Hiking",
            nodeId = "node-abc",
            alias = "Cool Fox",
            avatarHash = null,
            contentFilteringEnabled = true,
            isDirty = isDirty,
        )

    private fun render(
        isDirty: Boolean,
        onSave: () -> Unit = {},
    ) {
        compose.setContent {
            KnitTheme {
                ProfileScreenContent(
                    form = form(isDirty),
                    batteryExempt = true,
                    onBack = {},
                    onNameChange = {},
                    onNameCommit = {},
                    onStatusChange = {},
                    onStatusCommit = {},
                    onToggleContentFiltering = {},
                    onPickPhoto = {},
                    onClearPhoto = {},
                    onAllowBattery = {},
                    onSave = onSave,
                )
            }
        }
    }

    @Test
    fun saveIsDisabledWithNoUnsavedEdits() {
        render(isDirty = false)
        compose.onNodeWithTag("profile_save").assertIsNotEnabled()
    }

    @Test
    fun saveIsEnabledAndFiresWhenDirty() {
        var saves = 0
        render(isDirty = true, onSave = { saves++ })

        compose.onNodeWithTag("profile_save").assertIsEnabled()
        // The save button is the last item in a vertically-scrolled column, so bring it on-screen before clicking.
        compose.onNodeWithTag("profile_save").performScrollTo().performClick()
        assertEquals(1, saves)
    }
}
