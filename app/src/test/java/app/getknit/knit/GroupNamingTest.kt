package app.getknit.knit

import app.getknit.knit.data.message.groupNameForDisplay
import app.getknit.knit.data.message.groupTitle
import app.getknit.knit.data.message.joinNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun aReceivedNameIsCleanedWhereItIsDrawn() {
        val title = groupTitle("Weekend\ncrew\u202E", listOf("a", "b"), selfId = "a", fallback = "Group") { it }
        assertEquals("Weekend crew", title)
        assertEquals("Weekend crew", groupNameForDisplay("Weekend\ncrew\u202E"))
        assertEquals("x".repeat(32), groupNameForDisplay("x".repeat(40)))
    }

    @Test
    fun aNameThatCleansToDisplayBlankFallsBackToTheMembers() {
        val names = mapOf("a" to "Me", "b" to "Bob", "c" to "Carol")
        // Only invisibles: a key-empty name. Only controls: a key, but nothing left once cleaned (contract rev. 2).
        for (stored in listOf("\u3164", "\u2800\u200B", "\u0001\u0007")) {
            assertEquals("Bob & Carol", groupTitle(stored, listOf("a", "b", "c"), selfId = "a", fallback = "Group") { names.getValue(it) })
            assertNull(groupNameForDisplay(stored))
        }
    }
}
