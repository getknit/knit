package app.getknit.knit

import app.getknit.knit.notifications.NotifMessage
import app.getknit.knit.notifications.NotificationHistory
import app.getknit.knit.notifications.incomingNotification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationTest {

    private fun msg(id: String) =
        NotifMessage(senderId = id, senderName = id, body = "hi $id", sentAt = 0L, avatarPath = null)

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
    fun incomingNotificationSkipsOwnMessages() {
        val result = incomingNotification(
            senderId = "me",
            body = "hello",
            sentAt = 1L,
            selfId = "me",
            peerName = "Me",
            peerAvatarPath = null,
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
            peerAvatarPath = null,
        )
        assertNull(result)
    }

    @Test
    fun incomingNotificationFallsBackToNodeIdWhenNameMissingOrBlank() {
        val unknown = incomingNotification(
            senderId = "node123",
            body = "hi",
            sentAt = 1L,
            selfId = "me",
            peerName = null,
            peerAvatarPath = null,
        )
        assertEquals("node123", unknown?.senderName)

        val blankNamed = incomingNotification(
            senderId = "node123",
            body = "hi",
            sentAt = 1L,
            selfId = "me",
            peerName = "",
            peerAvatarPath = null,
        )
        assertEquals("node123", blankNamed?.senderName)
    }

    @Test
    fun incomingNotificationCarriesNameAvatarAndBody() {
        val result = incomingNotification(
            senderId = "bob",
            body = "hey there",
            sentAt = 42L,
            selfId = "me",
            peerName = "Bob",
            peerAvatarPath = "/cache/bob.jpg",
        )
        assertEquals(
            NotifMessage(
                senderId = "bob",
                senderName = "Bob",
                body = "hey there",
                sentAt = 42L,
                avatarPath = "/cache/bob.jpg",
            ),
            result,
        )
    }
}
