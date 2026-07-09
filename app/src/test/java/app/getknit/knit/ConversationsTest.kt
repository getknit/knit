package app.getknit.knit

import app.getknit.knit.data.message.Conversations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationsTest {
    @Test
    fun broadcastMessageBelongsToTheNearbyRoom() {
        // No recipient => the public room, regardless of who sent it or who's looking.
        assertEquals(Conversations.NEARBY, Conversations.idFor(senderId = "a", recipientId = null, selfId = "a"))
        assertEquals(Conversations.NEARBY, Conversations.idFor(senderId = "b", recipientId = null, selfId = "a"))
    }

    @Test
    fun dmIsKeyedByTheOtherParty() {
        // A message I sent to b: my thread is keyed by the recipient.
        assertEquals("b", Conversations.idFor(senderId = "a", recipientId = "b", selfId = "a"))
        // A message I received from b: my thread is keyed by the sender.
        assertEquals("b", Conversations.idFor(senderId = "b", recipientId = "a", selfId = "a"))
    }

    @Test
    fun bothEndpointsKeyTheThreadByTheirPeer() {
        // a sends to b. On a's device the thread is "b"; on b's device it's "a" — each by the peer.
        val onSender = Conversations.idFor(senderId = "a", recipientId = "b", selfId = "a")
        val onRecipient = Conversations.idFor(senderId = "a", recipientId = "b", selfId = "b")
        assertEquals("b", onSender)
        assertEquals("a", onRecipient)
    }

    @Test
    fun broadcastIsForEveryoneButADmIsOnlyForItsRecipient() {
        assertTrue(Conversations.isForMe(recipientId = null, selfId = "a"))
        assertTrue(Conversations.isForMe(recipientId = "a", selfId = "a"))
        assertFalse(Conversations.isForMe(recipientId = "b", selfId = "a"))
    }

    @Test
    fun groupIdWinsOverRecipientAndSelf() {
        // A group message belongs to the group's thread no matter who sent it or who's looking.
        assertEquals("grp-1", Conversations.idFor(senderId = "a", recipientId = null, selfId = "a", groupId = "grp-1"))
        assertEquals("grp-1", Conversations.idFor(senderId = "b", recipientId = null, selfId = "a", groupId = "grp-1"))
    }

    @Test
    fun isGroupMemberChecksTheRoster() {
        assertTrue(Conversations.isGroupMember(listOf("a", "b", "c"), selfId = "a"))
        assertFalse(Conversations.isGroupMember(listOf("b", "c"), selfId = "a"))
    }

    @Test
    fun groupIdIsOrderAgnosticAndDedupingSoTheSameSetResolvesToOneGroup() {
        val a = Conversations.groupIdFor(listOf("alice", "bob", "carol"))
        val reordered = Conversations.groupIdFor(listOf("carol", "alice", "bob"))
        val withDupes = Conversations.groupIdFor(listOf("bob", "carol", "alice", "bob"))
        assertEquals(a, reordered)
        assertEquals(a, withDupes)
    }

    @Test
    fun differentMemberSetsGetDifferentGroupIds() {
        val abc = Conversations.groupIdFor(listOf("alice", "bob", "carol"))
        val abd = Conversations.groupIdFor(listOf("alice", "bob", "dave"))
        val ab = Conversations.groupIdFor(listOf("alice", "bob"))
        assertNotEquals(abc, abd)
        assertNotEquals(abc, ab)
    }

    @Test
    fun groupIdCannotCollideWithNodeIdsOrTheRoom() {
        val id = Conversations.groupIdFor(listOf("alice", "bob"))
        // The "g-" prefix (with a hyphen) keeps it out of the base32 node-id space (no hyphen) and != NEARBY.
        assertTrue(id.startsWith(Conversations.GROUP_ID_PREFIX))
        assertNotEquals(Conversations.NEARBY, id)
    }

    // --- isAccepted: the single-source-of-truth "known chat vs. message request" predicate ---

    @Test
    fun nearbyRoomIsAlwaysAccepted() {
        // The public room is never a message request, even with every signal empty.
        assertTrue(
            Conversations.isAccepted(
                Conversations.NEARBY,
                accepted = emptySet(),
                verifiedNodeIds = emptySet(),
                authoredConversationIds = emptySet(),
            ),
        )
    }

    @Test
    fun anExplicitlyAcceptedConversationIsAccepted() {
        assertTrue(
            Conversations.isAccepted(
                "peer1",
                accepted = setOf("peer1"),
                verifiedNodeIds = emptySet(),
                authoredConversationIds = emptySet(),
            ),
        )
    }

    @Test
    fun aVerifiedDmPeerIsAccepted() {
        // A DM's conversationId IS the peer node id, so out-of-band verification is a set lookup.
        assertTrue(
            Conversations.isAccepted(
                "peer1",
                accepted = emptySet(),
                verifiedNodeIds = setOf("peer1"),
                authoredConversationIds = emptySet(),
            ),
        )
    }

    @Test
    fun aConversationTheUserHasAuthoredInIsAccepted() {
        assertTrue(
            Conversations.isAccepted(
                "peer1",
                accepted = emptySet(),
                verifiedNodeIds = emptySet(),
                authoredConversationIds = setOf("peer1"),
            ),
        )
    }

    @Test
    fun aStrangerDmMatchingNoSignalIsARequest() {
        // Not Nearby, not accepted, peer not verified, never replied to => a pending request.
        assertFalse(
            Conversations.isAccepted(
                "stranger",
                accepted = setOf("someoneElse"),
                verifiedNodeIds = setOf("someoneElse"),
                authoredConversationIds = setOf("someoneElse"),
            ),
        )
    }

    @Test
    fun aGroupIsNotAcceptedByPeerSignalsWhenNoSendersAreSupplied() {
        // A "g-" group id can't appear in the verified-node set, so without the thread's senders in hand a
        // group stays a request until it is explicitly accepted or replied to (the per-conversation checks
        // that don't pass senders rely on this).
        val groupId = Conversations.groupIdFor(listOf("alice", "bob"))
        assertFalse(
            Conversations.isAccepted(
                groupId,
                accepted = emptySet(),
                verifiedNodeIds = setOf("alice", "bob"),
                authoredConversationIds = emptySet(),
            ),
        )
        // But an explicit accept does accept the group.
        assertTrue(
            Conversations.isAccepted(
                groupId,
                accepted = setOf(groupId),
                verifiedNodeIds = emptySet(),
                authoredConversationIds = emptySet(),
            ),
        )
    }

    @Test
    fun aGroupIsAcceptedOnceAKnownPeerHasPostedInIt() {
        // A known peer speaking in the group — not mere membership — makes it a real chat: a sender that is
        // verified / accepted / previously DM'd accepts the whole group.
        val groupId = Conversations.groupIdFor(listOf("alice", "bob", "me"))
        // A verified sender.
        assertTrue(
            Conversations.isAccepted(
                groupId,
                accepted = emptySet(),
                verifiedNodeIds = setOf("alice"),
                authoredConversationIds = emptySet(),
                groupSenders = setOf("alice"),
            ),
        )
        // A sender whose DM we previously accepted.
        assertTrue(
            Conversations.isAccepted(
                groupId,
                accepted = setOf("bob"),
                verifiedNodeIds = emptySet(),
                authoredConversationIds = emptySet(),
                groupSenders = setOf("bob"),
            ),
        )
        // A sender we've previously DM'd (authored to).
        assertTrue(
            Conversations.isAccepted(
                groupId,
                accepted = emptySet(),
                verifiedNodeIds = emptySet(),
                authoredConversationIds = setOf("alice"),
                groupSenders = setOf("alice"),
            ),
        )
    }

    @Test
    fun aKnownPeerMerelyInTheRosterButSilentDoesNotAcceptTheGroup() {
        // "alice" is verified but has not posted — only stranger "stranger1" has spoken. Membership alone
        // isn't enough: the group stays a request until a known peer actually sends.
        val groupId = Conversations.groupIdFor(listOf("alice", "stranger1", "me"))
        assertFalse(
            Conversations.isAccepted(
                groupId,
                accepted = emptySet(),
                verifiedNodeIds = setOf("alice"),
                authoredConversationIds = emptySet(),
                groupSenders = setOf("stranger1"),
            ),
        )
    }

    @Test
    fun aGroupOnlyStrangersHaveSpokenInStaysARequest() {
        // No sender is a known peer, so supplying the senders changes nothing — still a request.
        val groupId = Conversations.groupIdFor(listOf("stranger1", "stranger2", "me"))
        assertFalse(
            Conversations.isAccepted(
                groupId,
                accepted = setOf("someoneElse"),
                verifiedNodeIds = setOf("someoneElse"),
                authoredConversationIds = setOf("someoneElse"),
                groupSenders = setOf("stranger1", "stranger2"),
            ),
        )
    }
}
