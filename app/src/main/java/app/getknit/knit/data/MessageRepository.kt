package app.getknit.knit.data

import app.getknit.knit.data.message.ConversationActivity
import app.getknit.knit.data.message.ConversationSender
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.data.message.MessageDao
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.search.SearchQuery
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Single source of truth for chat messages. Retention caps ([sweepRetention]) bound the table against a
 * Sybil flood — the rooms and strangers' request threads, which anyone in radio range can write into. An
 * accepted thread is unbounded on purpose: it is the user's own history. The caps are constructor params
 * (with production defaults) so tests can drive tiny ones.
 *
 * No reader here returns a whole table or a whole thread. The chat screen reads a window
 * ([observeNewestMessages]) and the list screens read per-thread summaries ([observeNewestPerConversation]
 * and its companions), so a thread's length costs nothing at open.
 */
class MessageRepository(
    private val dao: MessageDao,
    private val nearbyMaxMessages: Int = DEFAULT_NEARBY_MAX_MESSAGES,
    private val nearbyMaxAgeMs: Long = DEFAULT_NEARBY_MAX_AGE_MS,
    private val roomMaxPerStranger: Int = DEFAULT_ROOM_MAX_PER_STRANGER,
    private val maxPerPendingThread: Int = DEFAULT_MAX_PER_PENDING_THREAD,
    private val pendingThreadMaxAgeMs: Long = DEFAULT_PENDING_THREAD_MAX_AGE_MS,
    private val maxPendingThreads: Int = DEFAULT_MAX_PENDING_THREADS,
) {
    /**
     * The newest [limit] messages in a thread, oldest first — the chat screen's window. Reversing here keeps
     * the ascending shape the screen folds; `asReversed` is a view, not a copy. See
     * [MessageDao.observeNewestForConversation] for why the window exists and why it is stable.
     */
    fun observeNewestMessages(
        conversationId: String,
        limit: Int,
    ): Flow<List<MessageEntity>> = dao.observeNewestForConversation(conversationId, limit).map { it.asReversed() }

    /**
     * Ordinary messages whose body matches the typed query [raw], newest first and at most [limit], in the
     * threads [conversations] (an allow-list — the caller's visible threads, never every thread), from
     * senders not in [blocked]; [hideFlagged] leaves out text the on-device moderator flagged. Empty for a
     * query with nothing to ask ([SearchQuery.toMatch]) or for no threads at all. Bounded and served by the
     * `messages_fts` index — see [MessageDao.searchBodies].
     */
    suspend fun search(
        raw: String,
        conversations: Collection<String>,
        blocked: Set<String>,
        hideFlagged: Boolean,
        limit: Int,
    ): List<MessageEntity> {
        val match = SearchQuery.toMatch(raw) ?: return emptyList()
        if (conversations.isEmpty()) return emptyList()
        return dao.searchBodies(match, conversations, blocked, hideFlagged, limit)
    }

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
     * The `sentAt` of message [id], or null when we don't hold it. Read for the deterministic leave/rejoin
     * notice rows ([app.getknit.knit.data.message.StatusNotices.leaveId] / `rejoinId`), whose `sentAt` is
     * the member's own clock for that roster event.
     */
    suspend fun sentAtOf(id: String): Long? = dao.sentAtOf(id)

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

    /** Marks attachment [hash] animated on every row naming it — the "GIF" label's locally-derived flag. */
    suspend fun markAttachmentAnimated(hash: String) = dao.markAttachmentAnimated(hash)

    suspend fun hashesNeedingFetch(): List<String> = dao.hashesNeedingFetch()

    /**
     * Whether the message naming attachment [hash] crossed a **short-range radio** between [me] and
     * [peer] — our send that they acked over one, or their send that reached us over one (spec §9.5's
     * defer gate). The plane set lives here so the mesh layer's call site names no [DeliveryPlane] at all.
     */
    suspend fun attachmentCarriedByRadio(
        hash: String,
        me: String,
        peer: String,
    ): Boolean = dao.attachmentCarriedByRadio(hash, me, peer, DeliveryPlane.shortRangeCodes)

    /**
     * Whether [me] authored a message naming attachment [hash] at all, acked or not — what tells the
     * defer gate's grace apart from a carried frame or an avatar, which never had an ack coming.
     */
    suspend fun attachmentAuthoredHere(
        hash: String,
        me: String,
    ): Boolean = dao.attachmentAuthoredHere(hash, me)

    /** Distinct conversations the local user ([me]) has authored in — the "threads I started" accepted signal. */
    suspend fun conversationsIAuthoredIn(me: String): List<String> = dao.conversationsIAuthoredIn(me)

    /** The live form of [conversationsIAuthoredIn], for the screens that partition chats from requests. */
    fun observeConversationsIAuthoredIn(me: String): Flow<List<String>> = dao.observeConversationsIAuthoredIn(me)

    /** Every distinct conversation with any message — the candidate set for the pending-request count. */
    suspend fun distinctConversations(): List<String> = dao.distinctConversations()

    /**
     * The live form of [distinctConversations], minus threads whose every row is from a sender in [blocked]
     * — the conversations a list screen may show a row for. Any kind of row counts.
     */
    fun observeConversations(blocked: Set<String> = emptySet()): Flow<List<String>> = dao.observeDistinctConversations(blocked)

    /**
     * The newest row of the given [kinds] in every conversation, keyed by conversation id, ignoring senders
     * in [blocked] — what a list row shows and sorts on. See [MessageDao.observeNewestPerConversation] for
     * the shape that keeps it cheap. Map order is the DAO's, newest thread first.
     */
    fun observeNewestPerConversation(
        kinds: Set<Int>,
        blocked: Set<String>,
    ): Flow<Map<String, MessageEntity>> =
        dao.observeNewestPerConversation(kinds, blocked).map { heads -> heads.associateBy { it.conversationId } }

    /**
     * Who has posted an ordinary message in each group, keyed by group id, ignoring senders in [blocked] —
     * the "a known peer has spoken here" half of [Conversations.isAccepted] for every group at once.
     */
    fun observeGroupSenders(blocked: Set<String>): Flow<Map<String, Set<String>>> =
        dao.observeGroupSenders(Conversations.GROUP_ID_PREFIX + "*", blocked).map { rows -> rows.senderSets() }

    private fun List<ConversationSender>.senderSets(): Map<String, Set<String>> =
        groupBy({ it.conversationId }, { it.senderId }).mapValues { (_, senders) -> senders.toSet() }

    /** The unread badge for one thread — see [MessageDao.countUnreadIn] for what counts. */
    suspend fun countUnreadIn(
        conversationId: String,
        since: Long,
        me: String,
        blocked: Set<String>,
    ): Int = dao.countUnreadIn(conversationId, since, me, blocked)

    /** The channel the newest heard post in [conversationId] named, or null — the Meshtastic room's stand-in title. */
    fun observeNewestOriginChannel(conversationId: String): Flow<String?> = dao.observeNewestOriginChannel(conversationId)

    /** The newest `sentAt` in [conversationId], null for an empty thread. */
    suspend fun newestSentAt(conversationId: String): Long? = dao.newestSentAt(conversationId)

    /** How many ordinary messages anyone but [me] has sent — see [MessageDao.countFromOthers]. */
    suspend fun countFromOthers(me: String): Int = dao.countFromOthers(me)

    /** How many rows [me] is the sender of — see [MessageDao.countMine]. */
    suspend fun countMine(me: String): Int = dao.countMine(me)

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
     *  - a public **room** — Nearby, and the bridged Meshtastic channel — is capped by age, then per stranger,
     *    then by count with strangers evicted first (see below);
     *  - a **protected** thread is never trimmed, by count or by age: it is the user's own history, and the
     *    chat screen reads it through a bounded window (ADR 2026-09.hd5n) while the list screens read
     *    per-thread summaries, so its length costs nothing at open;
     *  - a **stranger's request** thread keeps only its newest few and is dropped once stale; and the number of
     *    live request threads is itself capped (a DM-flood is many one-message threads), oldest-by-activity first.
     *
     * The bridged room must take the room rule and not the default one: it is nobody's request thread, so the
     * stale-drop branch below would delete a whole neighbourhood's history a week after the board came off,
     * and the newest-few cap would leave it fifty posts deep. It is also the higher-volume of the two rooms —
     * its authors are a whole region rather than whoever is in radio range.
     *
     * A room's count cap used to be newest-first alone, which made it the flood's friend: 2,000 posts from
     * fresh identities — free, on this mesh — evicted every honest post in the room, contacts' included. The
     * cap now reads who wrote what. [knownSenders] are the people the user already talks to (the accepted,
     * verified and DM'd peers, and the user); everyone else is a stranger, and a stranger keeps at most
     * [roomMaxPerStranger] newest posts — the same per-identity quota custody applies. When the room is still
     * over [nearbyMaxMessages], strangers' oldest posts go first; only a room over cap on known senders alone
     * trims a known sender's post. Sybil is not solved here — ten identities still fill a room — and is not
     * meant to be: `mesh/IngressBudget` bounds how fast one *link* can hand those over, and this rule decides
     * what the bounded flood is allowed to displace.
     */
    suspend fun sweepRetention(
        now: Long,
        protected: Set<String>,
        knownSenders: Set<String> = emptySet(),
    ) {
        // The fixed rooms plus every commons with history: a commons is a room by the same rule (its authors
        // are a whole relay's membership, and it is nobody's request thread), but its ids are dynamic.
        val activity = dao.conversationActivity()
        val rooms = ROOMS + activity.map { it.conversationId }.filter { Conversations.isPublicRoom(it) }
        for (room in rooms) {
            dao.deleteOlderThan(room, now - nearbyMaxAgeMs)
            for (sender in dao.sendersOverIn(room, roomMaxPerStranger)) {
                if (sender !in knownSenders) dao.deleteOldestBySenderInConversation(room, sender, roomMaxPerStranger)
            }
            val over = dao.countIn(room) - nearbyMaxMessages
            if (over > 0) dao.deleteOldestFromStrangersIn(room, knownSenders, over)
            dao.deleteOldestInConversation(room, nearbyMaxMessages) // the last resort: over cap on known senders alone
        }

        val pending = mutableListOf<ConversationActivity>()
        for (conv in activity) {
            val id = conv.conversationId
            // Rooms were trimmed above; a protected thread is never trimmed.
            if (id in rooms || id in protected) continue

            if (conv.lastSentAt < now - pendingThreadMaxAgeMs) {
                dao.deleteByConversation(id) // a stale request thread — drop it wholesale
            } else {
                if (conv.count > maxPerPendingThread) {
                    dao.deleteOldestInConversation(id, maxPerPendingThread)
                }
                pending += conv
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

        /**
         * Newest room posts kept per stranger — custody's per-identity quota
         * ([app.getknit.knit.data.forward.ForwardRepository.DEFAULT_MAX_PER_SENDER]) applied to what is kept.
         */
        const val DEFAULT_ROOM_MAX_PER_STRANGER = 200

        /** A stranger's request thread keeps at most this many newest messages. */
        const val DEFAULT_MAX_PER_PENDING_THREAD = 50

        /** A request thread with no activity in this long is dropped wholesale. */
        const val DEFAULT_PENDING_THREAD_MAX_AGE_MS = 7L * 24 * 60 * 60_000 // 7 days

        /** Cap on the number of live request threads (a Sybil DM-flood is many one-message threads). */
        const val DEFAULT_MAX_PENDING_THREADS = 100
    }
}
