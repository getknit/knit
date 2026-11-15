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
    fun `the rooms are public, and nothing else is`() {
        assertTrue(Conversations.isPublicRoom(Conversations.NEARBY))
        assertTrue(Conversations.isPublicRoom(Conversations.MESHTASTIC))
        assertTrue(Conversations.isPublicRoom(Conversations.commonsIdFor("ab".repeat(32))))
        assertFalse(Conversations.isPublicRoom(Conversations.groupIdFor(listOf("alice", "bob"))))
        assertFalse(Conversations.isPublicRoom("node1234"))
    }

    @Test
    fun `a commons is its own kind, always accepted, and cannot collide with anything else`() {
        val id = Conversations.commonsIdFor("ab".repeat(32))
        assertEquals(ConversationKind.COMMONS, Conversations.kindFor(id))
        assertTrue(id.startsWith(Conversations.COMMONS_PREFIX))
        assertTrue(id.contains('-'))
        assertFalse(id.length == NodeId.LENGTH)
        assertFalse(id.startsWith(Conversations.GROUP_ID_PREFIX))
        assertFalse(id == Conversations.NEARBY || id == Conversations.MESHTASTIC)
        // A room is never a stranger's request, whoever posted in it.
        assertTrue(Conversations.isAccepted(id, accepted = emptySet(), verifiedNodeIds = emptySet(), authoredConversationIds = emptySet()))
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

    /**
     * The MeshCore room (ADR 2026-11.gsqu's Stage 6 amendment, security N7): `kindFor`'s last arm is `else -> DM`, so an
     * id it did not know would be a DM everywhere — a reply action, a DM notification channel, a peer alias.
     */
    @Test
    fun `the MeshCore room is its own bridged kind, never a DM, and cannot collide with anything else`() {
        val id = Conversations.MESHCORE_PUBLIC
        assertEquals(ConversationKind.MESHCORE, Conversations.kindFor(id))
        assertFalse(Conversations.kindFor(id) == ConversationKind.DM)
        assertTrue(Conversations.kindFor(id).isBridged)
        assertTrue(Conversations.isBridgedRoom(id))
        assertTrue(Conversations.isPublicRoom(id))
        assertTrue(Conversations.isAccepted(id, accepted = emptySet(), verifiedNodeIds = emptySet(), authoredConversationIds = emptySet()))
        assertFalse(id.length == NodeId.LENGTH)
        assertTrue(id.contains('-'))
        assertFalse(id.startsWith(Conversations.GROUP_ID_PREFIX) || id.startsWith(Conversations.COMMONS_PREFIX))
        assertFalse(id == Conversations.NEARBY || id == Conversations.MESHTASTIC)
    }

    @Test
    fun `exactly the two board rooms are bridged`() {
        assertTrue(Conversations.isBridgedRoom(Conversations.MESHTASTIC))
        assertTrue(Conversations.isBridgedRoom(Conversations.MESHCORE_PUBLIC))
        assertFalse(Conversations.isBridgedRoom(Conversations.NEARBY))
        assertFalse(Conversations.isBridgedRoom(Conversations.commonsIdFor("ab".repeat(32))))
        assertFalse(Conversations.isBridgedRoom(Conversations.groupIdFor(listOf("alice", "bob"))))
        assertFalse(Conversations.isBridgedRoom("node1234"))
        assertEquals(
            setOf(ConversationKind.MESHTASTIC, ConversationKind.MESHCORE),
            ConversationKind.entries.filter { it.isBridged }.toSet(),
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
