package app.getknit.knit.ui.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Robolectric for the app's own `list_join` resource, read in English and in a language with another separator. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS")
class ListJoinTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun englishJoinsWithACommaAndASpace() {
        assertEquals("", joinedList(context, emptyList()))
        assertEquals("Climbing", joinedList(context, listOf("Climbing")))
        assertEquals("Climbing, Lab", joinedList(context, listOf("Climbing", "Lab")))
        assertEquals("TURN, walkie, files", joinedList(context, listOf("TURN", "walkie", "files")))
    }

    @Test
    fun aNameThatLooksLikeAFormatSpecComesThroughLiterally() {
        // Peer display names reach the join: they are only ever arguments, never the format.
        assertEquals("%1\$s, 100%%, %s", joinedList(context, listOf("%1\$s", "100%%", "%s")))
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun chineseJoinsWithTheEnumerationComma() {
        assertEquals("Climbing、Lab", joinedList(context, listOf("Climbing", "Lab")))
        assertEquals("TURN、对讲、文件", joinedList(context, listOf("TURN", "对讲", "文件")))
    }
}
