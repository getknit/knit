package app.getknit.knit

import app.getknit.knit.data.message.groupTitle
import app.getknit.knit.data.message.joinNames
import org.junit.Assert.assertEquals
import org.junit.Test

class GroupNamingTest {
    @Test
    fun joinsNamesWithCommasAndAmpersand() {
        assertEquals("", joinNames(emptyList()))
        assertEquals("Alice", joinNames(listOf("Alice")))
        assertEquals("Alice & Bob", joinNames(listOf("Alice", "Bob")))
        assertEquals("Alice, Bob & Carol", joinNames(listOf("Alice", "Bob", "Carol")))
    }

    @Test
    fun explicitNameWins() {
        val title = groupTitle("Weekend crew", listOf("a", "b", "c"), selfId = "a", fallback = "Group") { it }
        assertEquals("Weekend crew", title)
    }

    @Test
    fun unnamedGroupGeneratesFromOtherMembersExcludingSelf() {
        // Resolver maps node id -> a display name; self (a) is excluded from the generated title.
        val names = mapOf("a" to "Me", "b" to "Bob", "c" to "Carol")
        val title = groupTitle("", listOf("a", "b", "c"), selfId = "a", fallback = "Group") { names.getValue(it) }
        assertEquals("Bob & Carol", title)
    }

    @Test
    fun blankNameAndNoOthersFallsBackToPlaceholder() {
        val title = groupTitle("", listOf("a"), selfId = "a", fallback = "Group") { it }
        assertEquals("Group", title)
    }
}
