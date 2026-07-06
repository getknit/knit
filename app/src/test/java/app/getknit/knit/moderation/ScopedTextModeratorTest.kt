package app.getknit.knit.moderation

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The public room runs the full hybrid pass; DMs/groups run toxicity only. This verifies the scope routes
 * to the right underlying moderator (and only that one).
 */
class ScopedTextModeratorTest {
    private class Recording(
        private val verdict: TextVerdict,
    ) : TextModerator {
        val seen = mutableListOf<String>()

        override suspend fun classify(text: String): TextVerdict {
            seen += text
            return verdict
        }
    }

    private val roomVerdict = TextVerdict(allowed = false, category = TextVerdict.Category.PROFANITY)
    private val directVerdict = TextVerdict(allowed = false, category = TextVerdict.Category.TOXICITY)

    @Test
    fun theRoomScopeRoutesToTheRoomModeratorOnly() =
        runTest {
            val room = Recording(roomVerdict)
            val direct = Recording(TextVerdict.ALLOWED)
            val scoped = ScopedTextModerator(room, direct)

            val verdict = scoped.classify("hi", isRoom = true)

            assertEquals(roomVerdict, verdict)
            assertEquals(listOf("hi"), room.seen)
            assertTrue("the direct moderator is not consulted for the room", direct.seen.isEmpty())
        }

    @Test
    fun theDmGroupScopeRoutesToTheDirectModeratorOnly() =
        runTest {
            val room = Recording(TextVerdict.ALLOWED)
            val direct = Recording(directVerdict)
            val scoped = ScopedTextModerator(room, direct)

            val verdict = scoped.classify("hi", isRoom = false)

            assertEquals(directVerdict, verdict)
            assertEquals(listOf("hi"), direct.seen)
            assertTrue("the room (profanity) pass is skipped in private threads", room.seen.isEmpty())
        }
}
