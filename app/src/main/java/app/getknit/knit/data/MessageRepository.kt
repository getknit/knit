package app.getknit.knit.data

import app.getknit.knit.data.message.ConversationActivity
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.MessageDao
import app.getknit.knit.data.message.MessageEntity
import kotlinx.coroutines.flow.Flow

/**
 * Single source of truth for chat messages. Retention caps ([sweepRetention]) bound the table against a
 * Sybil flood; they are constructor params (with production defaults) so tests can drive tiny caps.
 */
class MessageRepository(
    private val dao: MessageDao,
    private val nearbyMaxMessages: Int = DEFAULT_NEARBY_MAX_MESSAGES,
    private val nearbyMaxAgeMs: Long = DEFAULT_NEARBY_MAX_AGE_MS,
    private val maxPerAcceptedThread: Int = DEFAULT_MAX_PER_ACCEPTED_THREAD,
    private val maxPerPendingThread: Int = DEFAULT_MAX_PER_PENDING_THREAD,
    private val pendingThreadMaxAgeMs: Long = DEFAULT_PENDING_THREAD_MAX_AGE_MS,
    private val maxPendingThreads: Int = DEFAULT_MAX_PENDING_THREADS,
) {
    fun observeMessages(): Flow<List<MessageEntity>> = dao.observeAll()

    /** Messages in a single thread (the broadcast room or a 1:1 DM), oldest first. */
    fun observeMessages(conversationId: String): Flow<List<MessageEntity>> = dao.observeForConversation(conversationId)

    suspend fun save(message: MessageEntity) = dao.upsert(message)

    suspend fun exists(id: String): Boolean = dao.exists(id)

    /** The DM recipient of message [id], or null for a broadcast/group message or one we don't hold. */
    suspend fun recipientOf(id: String): String? = dao.recipientOf(id)

    /** How many messages in [conversationId] the local user authored ([me]) — nonzero ⇒ they initiated/replied there. */
    suspend fun countMineIn(
        conversationId: String,
        me: String,
    ): Int = dao.countMineIn(conversationId, me)

    suspend fun markReceived(id: String) = dao.markReceived(id)

    /** Outgoing DMs to [recipientId] that are still awaiting the recipient's key before they can be sent. */
    suspend fun pendingForRecipient(recipientId: String): List<MessageEntity> = dao.pendingForRecipient(recipientId)

    /** Clears the pending-key flag once a stuck DM has finally been sealed and flooded. */
    suspend fun clearPending(id: String) = dao.clearPending(id)

    /** Deletes a single message from this device only. */
    suspend fun delete(id: String) = dao.deleteById(id)

    /** Deletes all messages in a thread from this device only (used when leaving a group). */
    suspend fun deleteByConversation(conversationId: String) = dao.deleteByConversation(conversationId)

    /** Number of messages still referencing [hash] (0 once an attachment's last message is gone). */
    suspend fun countByAttachmentHash(hash: String): Int = dao.countByAttachmentHash(hash)

    /** Base64 per-attachment key for an encrypted attachment by its ciphertext [hash], if stored. */
    suspend fun attachmentKeyForHash(hash: String): String? = dao.attachmentKeyForHash(hash)

    suspend fun hashesNeedingFetch(): List<String> = dao.hashesNeedingFetch()

    /** Distinct conversations the local user ([me]) has authored in — the "threads I started" accepted signal. */
    suspend fun conversationsIAuthoredIn(me: String): List<String> = dao.conversationsIAuthoredIn(me)

    /** Every distinct conversation with any message — the candidate set for the pending-request count. */
    suspend fun distinctConversations(): List<String> = dao.distinctConversations()

    /**
     * Bounds the local `messages` table so a Sybil flood can't exhaust storage. Unlike the convergent
     * `forward_store`, `messages` is pure local state (no content digest), so this is plain GC — no mutex, no
     * transaction, a partial sweep is harmless. [protected] holds the conversation ids exempt from wholesale
     * eviction (accepted / verified / user-authored — the same set the notify gate treats as "not a request").
     *  - the public **Nearby** room (ambient, highest-volume) is capped by count and age;
     *  - a **protected** thread keeps a generous per-thread cap only, never wholesale-deleted;
     *  - a **stranger's request** thread keeps only its newest few and is dropped once stale; and the number of
     *    live request threads is itself capped (a DM-flood is many one-message threads), oldest-by-activity first.
     */
    suspend fun sweepRetention(
        now: Long,
        protected: Set<String>,
    ) {
        dao.deleteOlderThan(Conversations.NEARBY, now - nearbyMaxAgeMs)
        dao.deleteOldestInConversation(Conversations.NEARBY, nearbyMaxMessages)

        val pending = mutableListOf<ConversationActivity>()
        for (conv in dao.conversationActivity()) {
            val id = conv.conversationId
            if (id == Conversations.NEARBY) continue // trimmed above

            when {
                id in protected -> {
                    if (conv.count > maxPerAcceptedThread) {
                        dao.deleteOldestInConversation(id, maxPerAcceptedThread)
                    }
                }

                conv.lastSentAt < now - pendingThreadMaxAgeMs -> {
                    dao.deleteByConversation(id) // a stale request thread — drop it wholesale
                }

                else -> {
                    if (conv.count > maxPerPendingThread) {
                        dao.deleteOldestInConversation(id, maxPerPendingThread)
                    }
                    pending += conv
                }
            }
        }
        if (pending.size > maxPendingThreads) {
            pending
                .sortedByDescending { it.lastSentAt }
                .drop(maxPendingThreads)
                .forEach { dao.deleteByConversation(it.conversationId) }
        }
    }

    private companion object {
        /** Newest broadcast-room messages retained locally (ambient chatter — the primary unbounded vector). */
        const val DEFAULT_NEARBY_MAX_MESSAGES = 2_000

        /** Broadcast-room messages older than this are reclaimed regardless of count. */
        const val DEFAULT_NEARBY_MAX_AGE_MS = 30L * 24 * 60 * 60_000 // 30 days

        /** Generous per-thread cap for an accepted/known conversation (never wholesale-deleted). */
        const val DEFAULT_MAX_PER_ACCEPTED_THREAD = 5_000

        /** A stranger's request thread keeps at most this many newest messages. */
        const val DEFAULT_MAX_PER_PENDING_THREAD = 50

        /** A request thread with no activity in this long is dropped wholesale. */
        const val DEFAULT_PENDING_THREAD_MAX_AGE_MS = 7L * 24 * 60 * 60_000 // 7 days

        /** Cap on the number of live request threads (a Sybil DM-flood is many one-message threads). */
        const val DEFAULT_MAX_PENDING_THREADS = 100
    }
}
