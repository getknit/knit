package app.getknit.knit.ui.util

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A count shown at 0 reads its own zero string, because Android files 0 under a Portuguese plural's `one` form; and
 * the zero strings say in English exactly what the plurals said at 0, so English is unchanged.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CountTextTest {
    @Suppress("DEPRECATION") // junit4.v2 rules swap in StandardTestDispatcher — a test-semantics migration, see roadmap.md
    @get:Rule
    val compose = createComposeRule()

    private val resources get() = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    @Config(qualifiers = "pt-rBR")
    fun atZeroAPortugueseCountReadsItsZeroStringAndAboveZeroItsPlural() {
        // The premise: left to the plural, 0 takes the singular form 1 takes ("0 conversa").
        assertEquals(
            resources.getQuantityString(R.plurals.relays_scope_count, 1, 0),
            resources.getQuantityString(R.plurals.relays_scope_count, 0, 0),
        )
        assertNotEquals(
            resources.getString(R.string.relays_scope_count_zero, 0),
            resources.getQuantityString(R.plurals.relays_scope_count, 0, 0),
        )
        compose.setContent {
            Column {
                Text(countText(R.plurals.relays_scope_count, R.string.relays_scope_count_zero, 0), Modifier.testTag("zero"))
                Text(countText(R.plurals.relays_scope_count, R.string.relays_scope_count_zero, 1), Modifier.testTag("one"))
                Text(countText(R.plurals.relays_scope_count, R.string.relays_scope_count_zero, 2), Modifier.testTag("two"))
                Text(countLabel(R.plurals.your_mesh_nearby, R.string.your_mesh_nearby_zero, 0), Modifier.testTag("label0"))
                Text(countLabel(R.plurals.your_mesh_nearby, R.string.your_mesh_nearby_zero, 1), Modifier.testTag("label1"))
            }
        }
        compose.onNodeWithTag("zero").assertTextEquals(resources.getString(R.string.relays_scope_count_zero, 0))
        compose.onNodeWithTag("one").assertTextEquals(resources.getQuantityString(R.plurals.relays_scope_count, 1, 1))
        compose.onNodeWithTag("two").assertTextEquals(resources.getQuantityString(R.plurals.relays_scope_count, 2, 2))
        compose.onNodeWithTag("label0").assertTextEquals(resources.getString(R.string.your_mesh_nearby_zero))
        compose.onNodeWithTag("label1").assertTextEquals(resources.getQuantityString(R.plurals.your_mesh_nearby, 1))
    }

    @Test
    @Config(qualifiers = "en-rUS")
    fun inEnglishEveryZeroStringSaysWhatItsPluralSaidAtZero() {
        val counted =
            mapOf(
                R.plurals.diagnostics_transport_heard to R.string.diagnostics_transport_heard_zero,
                R.plurals.lora_boards_heard to R.string.lora_boards_heard_zero,
                R.plurals.relays_scope_count to R.string.relays_scope_count_zero,
                R.plurals.chat_group_member_count to R.string.chat_group_member_count_zero,
            )
        counted.forEach { (plural, zero) ->
            assertEquals(resources.getQuantityString(plural, 0, 0), resources.getString(zero, 0))
        }
        assertEquals(
            resources.getQuantityString(R.plurals.your_mesh_nearby, 0),
            resources.getString(R.string.your_mesh_nearby_zero),
        )
    }
}
