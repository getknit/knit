package app.getknit.knit

import app.getknit.knit.identity.Alias
import app.getknit.knit.notifications.NotifMessage
import app.getknit.knit.notifications.NotificationHistory
import app.getknit.knit.notifications.incomingNotification
import app.getknit.knit.notifications.mentionNotification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationTest {

    private val bobAvatar = byteArrayOf(1, 2, 3, 4)

    private fun msg(id: String, conversationId: String = "nearby") =
        NotifMessage(senderId = id, senderName = id, body = "hi $id", sentAt = 0L, conversationId = conversationId, avatarBytes = null)

    @Test
    fun historyKeepsOnlyMostRecentInOrder() {
        val history = NotificationHistory(capacity = 3)
        history.add(msg("a"))
        history.add(msg("b"))
        history.add(msg("c"))
        val snapshot = history.add(msg("d"))

        assertEquals(listOf("b", "c", "d"), snapshot.map { it.senderId })
        assertEquals(snapshot, history.snapshot())
    }

    @Test
    fun historyAddReturnsCurrentSnapshot() {
        val history = NotificationHistory(capacity = 8)
        assertTrue(history.isEmpty())

        val afterFirst = history.add(msg("a"))
        assertEquals(listOf("a"), afterFirst.map { it.senderId })

        history.clear()
        assertTrue(history.isEmpty())
    }

    @Test
    fun historyRemoveDropsOnlyMatchingConversation() {
        val history = NotificationHistory(capacity = 8)
        history.add(msg("a", conversationId = "x"))
        history.add(msg("b", conversationId = "y"))
        history.add(msg("c", conversationId = "x"))

        assertTrue(history.remove("x"))
        assertEquals(listOf("b"), history.snapshot().map { it.senderId })
        // Removing a conversation with nothing buffered reports no change.
        assertFalse(history.remove("x"))
    }

    @Test
    fun incomingNotificationSkipsOwnMessages() {
        val result = incomingNotification(
            senderId = "me",
            body = "hello",
            sentAt = 1L,
            selfId = "me",
            peerName = "Me",
            peerAvatarBytes = null,
            conversationId = "nearby",
        )
        assertNull(result)
    }

    @Test
    fun incomingNotificationSkipsBlankBody() {
        val result = incomingNotification(
            senderId = "bob",
            body = "   ",
            sentAt = 1L,
            selfId = "me",
            peerName = "Bob",
            peerAvatarBytes = null,
            conversationId = "bob",
        )
        assertNull(result)
    }

    @Test
    fun incomingNotificationFallsBackToAliasWhenNameMissingOrBlank() {
        val expectedAlias = Alias.aliasFor("node123")
        // The alias replaces the raw id and is never the id itself.
        assertNotEquals("node123", expectedAlias)

        val unknown = incomingNotification(
            senderId = "node123",
            body = "hi",
            sentAt = 1L,
            selfId = "me",
            peerName = null,
            peerAvatarBytes = null,
            conversationId = "node123",
        )
        assertEquals(expectedAlias, unknown?.senderName)

        val blankNamed = incomingNotification(
            senderId = "node123",
            body = "hi",
            sentAt = 1L,
            selfId = "me",
            peerName = "",
            peerAvatarBytes = null,
            conversationId = "node123",
        )
        assertEquals(expectedAlias, blankNamed?.senderName)
    }

    @Test
    fun incomingNotificationCarriesNameAvatarBodyAndConversation() {
        val result = incomingNotification(
            senderId = "bob",
            body = "hey there",
            sentAt = 42L,
            selfId = "me",
            peerName = "Bob",
            peerAvatarBytes = bobAvatar,
            conversationId = "bob",
        )
        assertEquals(
            NotifMessage(
                senderId = "bob",
                senderName = "Bob",
                body = "hey there",
                sentAt = 42L,
                conversationId = "bob",
                avatarBytes = bobAvatar,
            ),
            result,
        )
    }

    @Test
    fun mentionNotificationMatchesIncomingNotification() {
        // The mention path delegates to the same resolution rules; assert parity on the key cases.
        assertNull(
            mentionNotification(
                senderId = "me", body = "yo @me", sentAt = 1L, selfId = "me",
                peerName = "Me", peerAvatarBytes = null, conversationId = "nearby",
            ),
        )
        assertNull(
            mentionNotification(
                senderId = "bob", body = "  ", sentAt = 1L, selfId = "me",
                peerName = "Bob", peerAvatarBytes = null, conversationId = "nearby",
            ),
        )
        assertEquals(
            incomingNotification(
                senderId = "bob", body = "hi @me", sentAt = 5L, selfId = "me",
                peerName = "Bob", peerAvatarBytes = bobAvatar, conversationId = "nearby",
            ),
            mentionNotification(
                senderId = "bob", body = "hi @me", sentAt = 5L, selfId = "me",
                peerName = "Bob", peerAvatarBytes = bobAvatar, conversationId = "nearby",
            ),
        )
    }
}
