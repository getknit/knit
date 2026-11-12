package app.getknit.knit.usbcontrol

import org.junit.Assert.assertEquals
import org.junit.Test

class ChangeWatchTest {
    @Test
    fun aMapDiffNamesEveryKeyAddedRemovedOrMoved() {
        val old = mapOf("a" to 1L, "b" to 2L, "c" to 3L)
        val new = mapOf("a" to 1L, "b" to 5L, "d" to 4L)
        assertEquals(setOf("b", "c", "d"), ChangeWatch.changedKeys(old, new))
        assertEquals(emptySet<String>(), ChangeWatch.changedKeys(old, old))
    }

    @Test
    fun aMapDiffTellsANullValueFromAMissingKey() {
        assertEquals(setOf("a"), ChangeWatch.changedKeys(mapOf("a" to null), emptyMap<String, String?>()))
    }

    @Test
    fun aSetDiffNamesWhatJoinedAndWhatLeft() {
        assertEquals(setOf("x", "z"), ChangeWatch.changedMembers(setOf("x", "y"), setOf("y", "z")))
    }
}
