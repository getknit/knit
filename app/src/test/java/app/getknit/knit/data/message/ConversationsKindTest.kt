package app.getknit.knit.data.message

import app.getknit.knit.identity.NodeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationsKindTest {
    @Test
    fun nearbyRoomIsNearbyKind() {
        assertEquals(ConversationKind.NEARBY, Conversations.kindFor(Conversations.NEARBY))
    }

    @Test
    fun bridgedMeshtasticRoomIsItsOwnKind() {
        assertEquals(ConversationKind.MESHTASTIC, Conversations.kindFor(Conversations.MESHTASTIC))
    }

    @Test
    fun `the bridged room id cannot collide with a node id or a group id`() {
        // kindFor's last arm is `else -> DM`, so an id that slipped past its own test would silently become a
        // DM everywhere — the peer directory would give it an alias, and the notify router a DM channel. Two
        // properties keep that impossible: a node id is exactly NodeId.LENGTH base32 characters, and neither
        // namespace admits a hyphen.
        assertFalse(Conversations.MESHTASTIC.length == NodeId.LENGTH)
        assertTrue(Conversations.MESHTASTIC.contains('-'))
        assertFalse(Conversations.MESHTASTIC.startsWith(Conversations.GROUP_ID_PREFIX))
        assertFalse(Conversations.MESHTASTIC == Conversations.NEARBY)
    }

    @Test
    fun `both rooms are public, and nothing else is`() {
        assertTrue(Conversations.isPublicRoom(Conversations.NEARBY))
        assertTrue(Conversations.isPublicRoom(Conversations.MESHTASTIC))
        assertFalse(Conversations.isPublicRoom(Conversations.groupIdFor(listOf("alice", "bob"))))
        assertFalse(Conversations.isPublicRoom("node1234"))
    }

    @Test
    fun `the bridged room is never a message request`() {
        // Nobody opted into it and nobody can accept it, so the requests inbox must never hold it — the
        // notify gate reads exactly this predicate.
        assertTrue(
            Conversations.isAccepted(
                Conversations.MESHTASTIC,
                accepted = emptySet(),
                verifiedNodeIds = emptySet(),
                authoredConversationIds = emptySet(),
            ),
        )
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
