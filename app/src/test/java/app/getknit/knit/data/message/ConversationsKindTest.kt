package app.getknit.knit.data.message

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationsKindTest {
    @Test
    fun nearbyRoomIsNearbyKind() {
        assertEquals(ConversationKind.NEARBY, Conversations.kindFor(Conversations.NEARBY))
    }

    @Test
    fun groupIdIsGroupKind() {
        val groupId = Conversations.groupIdFor(listOf("alice", "bob"))
        assertEquals(ConversationKind.GROUP, Conversations.kindFor(groupId))
    }

    @Test
    fun peerNodeIdIsDmKind() {
        // A bare node id is neither the Nearby room nor a "g-" group id.
        assertEquals(ConversationKind.DM, Conversations.kindFor("node1234"))
    }
}
