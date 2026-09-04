package app.getknit.knit.data.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [PeerRename] is the body of every rename notice, old rows included, so what these pin is the boundary
 * between the two forms: an encoded pair round-trips exactly, and anything else is a bare previous name
 * from before the new name was stored — never a crash, never a mangled line.
 */
class PeerRenameTest {
    @Test
    fun bothNamesRoundTrip() {
        val body = PeerRename.encode("I am a songwriter", "Bushybramblepatch")
        assertEquals(PeerRename(from = "I am a songwriter", to = "Bushybramblepatch"), PeerRename.decode(body))
    }

    @Test
    fun namesThatLookLikeJsonRoundTrip() {
        // A display name is peer-chosen text, capped in length and nothing else: quotes, braces and
        // backslashes must survive the container rather than break it.
        val from = "{\"from\":\"x\"}"
        val to = "Back\\slash \"quoted\" }"
        assertEquals(PeerRename(from = from, to = to), PeerRename.decode(PeerRename.encode(from, to)))
    }

    @Test
    fun aBareBodyIsALegacyPreviousNameWithNoNewName() {
        // A row written before the new name was stored holds only the old one; the renderer then falls
        // back to the live label, exactly as every rename row did at the time.
        val legacy = PeerRename.decode("I am a songwriter")
        assertEquals("I am a songwriter", legacy.from)
        assertNull(legacy.to)
    }

    @Test
    fun aClearedNameDecodesAsUnknownRatherThanEmpty() {
        // A peer who cleared their name has nothing to be called by yet; the live label (their alias)
        // fills the second half instead of a blank.
        assertNull(PeerRename.decode(PeerRename.encode("Old", "")).to)
        assertNull(PeerRename.decode(PeerRename.encode("Old", "   ")).to)
        assertNull(PeerRename.decode("{\"from\":\"Old\",\"to\":\"\"}").to)
    }

    @Test
    fun anUnexpectedButValidJsonBodyIsStillTreatedAsABareName() {
        // Not the pair's shape, so not the pair: a JSON string, array or object without `from` all read
        // as the literal previous name rather than throwing inside a composable.
        for (odd in listOf("\"Old\"", "[1,2]", "{}", "{\"to\":\"New\"}")) {
            assertEquals(odd, PeerRename(from = odd), PeerRename.decode(odd))
        }
    }
}
