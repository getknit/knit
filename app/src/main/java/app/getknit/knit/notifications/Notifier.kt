package app.getknit.knit.notifications

import app.getknit.knit.identity.displayNameFor

/** One inbound message, resolved into the fields a MessagingStyle line needs. */
data class NotifMessage(
    val senderId: String,
    val senderName: String,
    val body: String,
    val sentAt: Long,
    val avatarPath: String?,
)

/**
 * Posts "new message" notifications. Kept behind an interface so [app.getknit.knit.mesh.MeshManager]
 * stays free of Android notification APIs (and so the message-resolution logic can be unit-tested via
 * [incomingNotification] without Robolectric). The Android implementation is [MessageNotifier].
 */
interface Notifier {

    /** Registers the notification channel. Safe to call repeatedly; called once at startup. */
    fun createChannel()

    /** Records [incoming] and (re)posts the single room notification. */
    fun notify(incoming: NotifMessage, selfId: String, selfName: String, selfAvatarPath: String?)

    /** Toggles whether the chat is on screen; turning it on cancels + clears the notification. */
    fun setChatVisible(visible: Boolean)

    /** Clears accumulated state and dismisses the notification (chat opened / read). */
    fun clear()

    /** Drops accumulated state without dismissing (notification swiped away). */
    fun onDismissed()
}

/**
 * Builds the notification payload for an inbound chat message, or `null` when it must not notify —
 * the message is our own ([senderId] == [selfId]) or has a blank body. The sender name falls back to
 * a friendly alias derived from the node id when the peer profile is unknown or blank, mirroring the
 * chat UI's resolution.
 */
fun incomingNotification(
    senderId: String,
    body: String,
    sentAt: Long,
    selfId: String,
    peerName: String?,
    peerAvatarPath: String?,
): NotifMessage? {
    if (senderId == selfId) return null
    if (body.isBlank()) return null
    return NotifMessage(
        senderId = senderId,
        senderName = displayNameFor(peerName, senderId),
        body = body,
        sentAt = sentAt,
        avatarPath = peerAvatarPath,
    )
}
