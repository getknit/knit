package app.getknit.knit.notifications

/**
 * Capped, in-order buffer of the most recent inbound messages backing the single MessagingStyle
 * notification. Keeping the last few messages lets a busy room update one notification that still
 * shows recent context from several senders. Pure (no Android deps) so it is unit-tested directly.
 */
class NotificationHistory(private val capacity: Int = DEFAULT_CAPACITY) {

    private val items = ArrayDeque<NotifMessage>()

    /** Appends [message], evicting the oldest beyond [capacity], and returns the current snapshot. */
    fun add(message: NotifMessage): List<NotifMessage> {
        items.addLast(message)
        while (items.size > capacity) items.removeFirst()
        return items.toList()
    }

    fun snapshot(): List<NotifMessage> = items.toList()

    fun isEmpty(): Boolean = items.isEmpty()

    fun clear() = items.clear()

    private companion object {
        const val DEFAULT_CAPACITY = 8
    }
}
