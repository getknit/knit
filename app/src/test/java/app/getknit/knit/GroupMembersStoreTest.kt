package app.getknit.knit

import app.getknit.knit.data.group.GroupMembersStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupMembersStoreTest {
    @Test
    fun roundTripsMemberList() {
        val members = listOf("alice000", "bob00000", "carol000")
        assertEquals(members, GroupMembersStore.decode(GroupMembersStore.encode(members)))
    }

    @Test
    fun emptyListRoundTrips() {
        assertEquals(emptyList<String>(), GroupMembersStore.decode(GroupMembersStore.encode(emptyList())))
    }

    @Test
    fun malformedDecodesToEmptyList() {
        assertTrue(GroupMembersStore.decode("not json").isEmpty())
        assertTrue(GroupMembersStore.decode("").isEmpty())
    }
}
