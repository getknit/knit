package app.getknit.knit

import app.getknit.knit.data.message.Conversations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
