package app.getknit.knit.data

import app.getknit.knit.data.message.ConversationActivity
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.data.message.MessageDao
import app.getknit.knit.data.message.MessageEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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

    /**
     * The newest [limit] messages in a thread, oldest first — the chat screen's window. Reversing here keeps
     * the ascending shape every consumer of [observeMessages] already expects; `asReversed` is a view, not a
     * copy. See [MessageDao.observeNewestForConversation] for why the window exists and why it is stable.
     */
    fun observeNewestMessages(
        conversationId: String,
        limit: Int,
    ): Flow<List<MessageEntity>> = dao.observeNewestForConversation(conversationId, limit).map { it.asReversed() }

    /** How deep [id] sits from the newest end of its thread — the window that just reaches a quoted message. */
    suspend fun depthOf(
        conversationId: String,
        id: String,
    ): Int = dao.depthOf(conversationId, id)

    /** Everyone who has posted an ordinary message in [conversationId] — the @-mention candidates. */
    fun observeSendersIn(conversationId: String): Flow<List<String>> = dao.observeSendersIn(conversationId)

    /** One message by id, null once it is deleted — backs the message-details screen. */
    fun observeMessage(id: String): Flow<MessageEntity?> = dao.observeById(id)

    suspend fun save(message: MessageEntity) = dao.upsert(message)

    /**
     * Writes [message] only if no row with its id exists yet; true when it was inserted. The inbound
     * delivery write — first-write-wins, so a custody re-serve of a message we already hold never rewrites
     * the row (its arrival plane included).
     */
    suspend fun saveIfAbsent(message: MessageEntity): Boolean = dao.insertIfAbsent(message) != -1L

    suspend fun exists(id: String): Boolean = dao.exists(id)

    /** The DM recipient of message [id], or null for a broadcast/group message or one we don't hold. */
    suspend fun recipientOf(id: String): String? = dao.recipientOf(id)

    suspend fun conversationOf(id: String): String? = dao.conversationOf(id)

    /**
     * Flips message [id]'s delivery tick, noting the plane the receipt arrived on ([via]). Idempotent, and
     * the plane is written only by the receipt that first flips the tick — see [MessageDao.markReceived].
     * This is the enum↔code boundary: the column stores [DeliveryPlane.code].
     */
    suspend fun markReceived(
        id: String,
        via: DeliveryPlane,
    ) = dao.markReceived(id, via.code)

    /** Outgoing DMs to [recipientId] that are still awaiting the recipient's key before they can be sent. */
    suspend fun pendingForRecipient(recipientId: String): List<MessageEntity> = dao.pendingForRecipient(recipientId)

    suspend fun unackedDmsTo(
        recipientId: String,
        me: String,
        since: Long,
    ): List<MessageEntity> = dao.unackedDmsTo(recipientId, me, since)

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

    /** MIME a stored message gives attachment [hash] — how a landed blob is recognised as a voice note. */
    suspend fun attachmentMimeForHash(hash: String): String? = dao.attachmentMimeForHash(hash)

    /** Records the locally-derived voice-note duration/waveform on every row naming attachment [hash]. */
    suspend fun setVoiceMeta(
        hash: String,
        durationMs: Int?,
        peaks: String?,
    ) = dao.setVoiceMeta(hash, durationMs, peaks)

    suspend fun hashesNeedingFetch(): List<String> = dao.hashesNeedingFetch()

    /** Whether [me] authored a message naming attachment [hash] and it has been acked (spec §9.5's defer gate). */
    suspend fun attachmentAcked(
        hash: String,
        me: String,
    ): Boolean = dao.attachmentAcked(hash, me)

    /** Distinct conversations the local user ([me]) has authored in — the "threads I started" accepted signal. */
    suspend fun conversationsIAuthoredIn(me: String): List<String> = dao.conversationsIAuthoredIn(me)

    /** Every distinct conversation with any message — the candidate set for the pending-request count. */
    suspend fun distinctConversations(): List<String> = dao.distinctConversations()

    /**
     * Node ids [me] has exchanged messages with in both directions — the open-to-chat cue's "we have already
     * met" set (see [MessageDao.observeAcquaintedPeers] for what counts as an exchange).
     */
    fun observeAcquaintedPeers(me: String): Flow<List<String>> =
        dao.observeAcquaintedPeers(
            me = me,
            nearbyId = Conversations.NEARBY,
            groupPattern = Conversations.GROUP_ID_PREFIX + "%",
        )

    /** Distinct senders who have posted in [conversationId] — a group is accepted once a known peer is among them. */
    suspend fun sendersIn(conversationId: String): List<String> = dao.sendersIn(conversationId)

    /** Whether [conversationId] holds any ordinary message — the gate for writing a peer status notice. */
    suspend fun hasMessagesIn(conversationId: String): Boolean = dao.hasMessagesIn(conversationId)

    /**
     * Bounds the local `messages` table so a Sybil flood can't exhaust storage. Unlike the convergent
     * `forward_store`, `messages` is pure local state (no content digest), so this is plain GC — no mutex, no
     * transaction, a partial sweep is harmless. [protected] holds the conversation ids exempt from wholesale
     * eviction (accepted / verified / user-authored — the same set the notify gate treats as "not a request").
     *  - a public **room** — Nearby, and the bridged Meshtastic channel — is capped by count and age;
     *  - a **protected** thread keeps a generous per-thread cap only, never wholesale-deleted;
     *  - a **stranger's request** thread keeps only its newest few and is dropped once stale; and the number of
     *    live request threads is itself capped (a DM-flood is many one-message threads), oldest-by-activity first.
     *
     * The bridged room must take the room rule and not the default one: it is nobody's request thread, so the
     * stale-drop branch below would delete a whole neighbourhood's history a week after the board came off,
     * and the newest-few cap would leave it fifty posts deep. It is also the higher-volume of the two rooms —
     * its authors are a whole region rather than whoever is in radio range.
     */
    suspend fun sweepRetention(
        now: Long,
        protected: Set<String>,
    ) {
        for (room in ROOMS) {
            dao.deleteOlderThan(room, now - nearbyMaxAgeMs)
            dao.deleteOldestInConversation(room, nearbyMaxMessages)
        }

        val pending = mutableListOf<ConversationActivity>()
        for (conv in dao.conversationActivity()) {
            val id = conv.conversationId
            if (id in ROOMS) continue // trimmed above

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
        /** The public rooms, which take the count-and-age cap rather than the per-thread request rules. */
        val ROOMS = setOf(Conversations.NEARBY, Conversations.MESHTASTIC)

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
