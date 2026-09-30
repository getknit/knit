package app.getknit.knit.data.message

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

// A data-access interface: one method per query, so the count naturally exceeds detekt's interface limit.
@Suppress("TooManyFunctions")
@Dao
interface MessageDao {
    // There is deliberately no `SELECT * FROM messages` and no whole-thread read here: an accepted thread
    // has no retention cap, so any such query would grow without bound. The chat screen reads a window
    // (below), the list screens read per-thread summaries (further down), and search reads through the
    // `messages_fts` index under a LIMIT ([searchBodies]).

    /**
     * The newest [limit] messages in a thread, **newest first** — the chat screen's window (ADR: the thread
     * reads a window, not the whole conversation). A room runs to its 2,000-row retention cap and an accepted
     * thread has no cap at all (the sweep never trims it), so reading a whole thread was what made a cold open
     * slow, and would only have got slower.
     *
     * The `id` tiebreak matches [deleteOldestInConversation]'s and is what makes the window *stable*: rows
     * sharing a `sentAt` would otherwise be free to reshuffle across the boundary as the limit grows, so a
     * message could appear twice or vanish while scrolling back. Ordering is served whole by the
     * `(conversationId, sentAt, id)` index, so there is no sort and the scan stops at [limit].
     *
     * Callers want oldest-first; [app.getknit.knit.data.MessageRepository.observeNewestMessages] reverses.
     */
    @Query(
        "SELECT * FROM messages WHERE conversationId = :conversationId " +
            "ORDER BY sentAt DESC, id DESC LIMIT :limit",
    )
    fun observeNewestForConversation(
        conversationId: String,
        limit: Int,
    ): Flow<List<MessageEntity>>

    /**
     * How many messages sit at or newer than [id] in its thread — the window size that just reaches it, so
     * tapping a reply quote can pull an older message into the window in one round trip rather than paging
     * blindly toward it. Zero when [id] is not stored (the subquery yields null and nothing compares true).
     */
    @Query(
        "SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId " +
            "AND sentAt >= (SELECT sentAt FROM messages WHERE id = :id)",
    )
    suspend fun depthOf(
        conversationId: String,
        id: String,
    ): Int

    /**
     * The whole stored row for [id], or null once it is gone — lets the message-details screen follow a
     * single message (and close itself when that message is deleted out from under it).
     */
    @Query("SELECT * FROM messages WHERE id = :id")
    fun observeById(id: String): Flow<MessageEntity?>

    @Upsert
    suspend fun upsert(message: MessageEntity)

    /**
     * Inserts [message] unless a row with its id already exists, returning the new rowid or -1 when it was
     * left alone. The inbound delivery path's write: a re-served frame is the same signed bytes, so the
     * first row written for an id is the only one that ever should be (it keeps the plane the message first
     * arrived on and whatever was added to the row since — see `InboundPipeline.deliverChat`).
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(message: MessageEntity): Long

    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE id = :id)")
    suspend fun exists(id: String): Boolean

    /**
     * The [MessageEntity.recipientId] of the stored message [id], or null when it's a broadcast/group
     * message OR no such message is held. Lets [markReceived] reject a receipt whose sender isn't the
     * message's addressed DM recipient (a forged-ack guard); broadcast/group keep the best-effort tick.
     */
    @Query("SELECT recipientId FROM messages WHERE id = :id")
    suspend fun recipientOf(id: String): String?

    /** The conversation the stored message [id] belongs to, or null when it isn't held. */
    @Query("SELECT conversationId FROM messages WHERE id = :id")
    suspend fun conversationOf(id: String): String?

    /** The `sentAt` of the stored message [id], or null when it isn't held — a status notice read as a clock. */
    @Query("SELECT sentAt FROM messages WHERE id = :id")
    suspend fun sentAtOf(id: String): Long?

    /**
     * Flips the delivery tick for [id], recording the [DeliveryPlane] code ([via]) the receipt that did it
     * arrived on. Callers pass the enum through [app.getknit.knit.data.MessageRepository.markReceived].
     *
     * Stays idempotent (a receipt is re-served routinely), and the plane is **first-evidence-wins**: the
     * `CASE` reads the pre-update `received`, so only the receipt that actually flips the tick sets the
     * plane. A duplicate crossing later on another plane leaves the mark alone — it describes how the
     * message first got there, not every route it has since travelled.
     */
    @Query(
        "UPDATE messages SET " +
            "receivedVia = CASE WHEN received = 0 THEN :via ELSE receivedVia END, received = 1 " +
            "WHERE id = :id",
    )
    suspend fun markReceived(
        id: String,
        via: Int,
    )

    /** How many messages in [conversationId] were authored by [me] — nonzero means the user has replied there. */
    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId AND senderId = :me")
    suspend fun countMineIn(
        conversationId: String,
        me: String,
    ): Int

    /** Outgoing DMs to [recipientId] saved while their key was unknown, awaiting retransmit on key arrival. */
    @Query("SELECT * FROM messages WHERE recipientId = :recipientId AND pendingKey = 1")
    suspend fun pendingForRecipient(recipientId: String): List<MessageEntity>

    /**
     * Our own recent DMs to [recipientId] that were flooded but never acked — the re-seal set when the
     * peer resets its ratchet session (a wiped device can no longer open what we sealed to the old
     * session, but custody still holds those frames; re-sealing under the fresh session recovers them).
     * Bounded by [since] (the custody TTL) — anything older is gone from the mesh anyway.
     */
    @Query(
        "SELECT * FROM messages WHERE recipientId = :recipientId AND senderId = :me " +
            "AND received = 0 AND pendingKey = 0 AND sentAt >= :since",
    )
    suspend fun unackedDmsTo(
        recipientId: String,
        me: String,
        since: Long,
    ): List<MessageEntity>

    /** Clears the [MessageEntity.pendingKey] flag once a stuck DM has been sealed and flooded. */
    @Query("UPDATE messages SET pendingKey = 0 WHERE id = :id")
    suspend fun clearPending(id: String)

    /** Removes a single message locally (used by the long-press "Delete message" action). */
    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: String)

    /** Removes every message in a thread (used when leaving a group, so the thread vanishes). */
    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteByConversation(conversationId: String)

    /** How many messages still reference [hash] — guards deleting a shared, content-addressed blob. */
    @Query("SELECT COUNT(*) FROM messages WHERE attachmentHash = :hash")
    suspend fun countByAttachmentHash(hash: String): Int

    /**
     * The (base64) per-attachment key stored with a message referencing the ciphertext [hash], if any —
     * used to decrypt a just-pulled E2E attachment blob so its plaintext can be screened. Null for a
     * plaintext (broadcast) attachment or when no such message is stored (e.g. we only relayed the blob).
     */
    @Query(
        "SELECT attachmentKey FROM messages " +
            "WHERE attachmentHash = :hash AND attachmentKey IS NOT NULL LIMIT 1",
    )
    suspend fun attachmentKeyForHash(hash: String): String?

    /**
     * The MIME of any stored message referencing the ciphertext [hash] — how the inbound derivation decides
     * whether a just-pulled blob is a voice note without decrypting it first. Null when no message row names
     * the hash (a relayed blob, or an avatar, which writes no message row at all).
     */
    @Query(
        "SELECT attachmentMime FROM messages " +
            "WHERE attachmentHash = :hash AND attachmentMime IS NOT NULL LIMIT 1",
    )
    suspend fun attachmentMimeForHash(hash: String): String?

    /**
     * Records the locally-derived voice-note description for every message naming the ciphertext [hash].
     * Keyed by hash rather than message id because the same voice note can be quoted into more than one row
     * (a re-send, or a forward), and each of those bubbles needs the same waveform — deriving once and
     * writing across them all is why this is content-addressed like the blob itself.
     */
    @Query("UPDATE messages SET voiceDurationMs = :durationMs, voicePeaks = :peaks WHERE attachmentHash = :hash")
    suspend fun setVoiceMeta(
        hash: String,
        durationMs: Int?,
        peaks: String?,
    )

    /**
     * Records that the attachment [hash] is animated on every message naming it — keyed by hash for
     * [setVoiceMeta]'s reason. Only rows not already marked are touched, so a re-landed blob writes nothing
     * and wakes no observer of the table.
     */
    @Query("UPDATE messages SET attachmentAnimated = 1 WHERE attachmentHash = :hash AND attachmentAnimated = 0")
    suspend fun markAttachmentAnimated(hash: String)

    /** Attachment hashes referenced by stored messages whose bytes aren't in the `blobs` table yet. */
    @Query(
        "SELECT DISTINCT attachmentHash FROM messages " +
            "WHERE attachmentHash IS NOT NULL AND attachmentHash NOT IN (SELECT hash FROM blobs)",
    )
    suspend fun hashesNeedingFetch(): List<String>

    /**
     * Whether the message naming ciphertext [hash] crossed a **short-range radio** between [me] and
     * [peer] — the evidence `AttachmentDeferPolicy` needs before holding an upload back from the Internet
     * plane. [planes] is [DeliveryPlane.shortRangeCodes]; Room's `@Query` can't name the enum, the way
     * `sendersIn` can't name [MessageEntity.KIND_NORMAL].
     *
     * One fact, read from whichever end of the DM this node is. A row [me] authored needs its tick as well
     * as its plane, because [receivedVia] there describes the *receipt* coming back. A row [peer] authored
     * needs only the plane, because that column already names the plane the message itself arrived on and
     * the tick on it is the peer's business, not ours. Without the second reading a recipient re-uploaded
     * every photo it had just pulled off a BLE link.
     *
     * The plane is what makes this *radio* evidence rather than mere delivery. [receivedVia] is
     * first-evidence-wins (see [markReceived] and the inbound persist), so it names the plane the message
     * actually first crossed: a hop that rode a spool or a board leaves this false, and the attachment is
     * pushed. Every other uncertain case reads false too and therefore pushes — a hash we hold no row for
     * between this pair at all (a carried frame, whose bytes are nobody's message here, or an avatar, which
     * writes no message row), and a row written by a build older than the column, whose
     * [DeliveryPlane.Unknown] is in no plane set.
     */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM messages WHERE attachmentHash = :hash AND (" +
            "(senderId = :me AND received = 1 AND receivedVia IN (:planes)) OR " +
            "(senderId = :peer AND receivedVia IN (:planes))))",
    )
    suspend fun attachmentCarriedByRadio(
        hash: String,
        me: String,
        peer: String,
        planes: List<Int>,
    ): Boolean

    /**
     * Whether [me] authored any message naming the ciphertext [hash] at all — acked or not. The subject
     * of `AttachmentDeferPolicy`'s grace, and nothing else: for a short while after a send, a missing ack
     * says only that the receipt has not come back yet, and this is what separates that from everything
     * that is not waiting on an ack of ours — the peer's own send (its row names them), a **carried**
     * frame (no message row here at all) and an **avatar** (a `PeerEntity` and no message row). The
     * peer's send may still defer, but on [attachmentCarriedByRadio]'s evidence rather than on a grace.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE attachmentHash = :hash AND senderId = :me)")
    suspend fun attachmentAuthoredHere(
        hash: String,
        me: String,
    ): Boolean

    /**
     * Node ids the local user ([me]) has **exchanged** ordinary messages with: someone we have both sent
     * something to and heard something back from, which is what the open-to-chat cue treats as already
     * knowing a person (`presence/OpenToChatPolicy.qualifying`). Observed, so a first reply drops its
     * author out of the cue's candidate set without waiting for a mesh restart.
     *
     * Two shapes, unioned. A **DM** counts when the thread holds a message of ours *and* one of theirs — a
     * DM's [MessageEntity.conversationId] is the other party's node id, so "one of theirs" is the row whose
     * sender *is* that id. A **group** counts when both of us have posted in the same thread; posting into a
     * shared room is the send and their post is the receipt, so the mixed cases ("I DM'd them, they replied
     * in the group") already reduce to this. Mere membership is not enough — a stranger who adds us to a
     * group, or one who posts in a group we have never spoken in, stays someone we do not know, the same
     * sender-keyed rule [sendersIn] uses.
     *
     * Status notices are excluded (`kind = 0` is [MessageEntity.KIND_NORMAL]; Room's `@Query` can't
     * reference the constant): a notice's sender is the event's *subject*, so counting one would let a peer
     * who only ever renamed themselves pass for a conversation. [nearbyId] and [groupPattern] keep the
     * broadcast room and the group ids out of the DM half — pass [Conversations.NEARBY] and
     * [Conversations.GROUP_ID_PREFIX] + `%`, which [app.getknit.knit.data.MessageRepository] does.
     */
    @Query(
        "SELECT m.conversationId AS peerId FROM messages AS m " +
            "WHERE m.kind = 0 AND m.senderId = :me " +
            "AND m.conversationId <> :nearbyId AND m.conversationId NOT LIKE :groupPattern " +
            "AND EXISTS (SELECT 1 FROM messages AS theirs WHERE theirs.kind = 0 " +
            "AND theirs.conversationId = m.conversationId AND theirs.senderId = m.conversationId) " +
            "UNION " +
            "SELECT g.senderId AS peerId FROM messages AS g " +
            "WHERE g.kind = 0 AND g.senderId <> :me AND g.conversationId LIKE :groupPattern " +
            "AND EXISTS (SELECT 1 FROM messages AS mine WHERE mine.kind = 0 " +
            "AND mine.conversationId = g.conversationId AND mine.senderId = :me)",
    )
    fun observeAcquaintedPeers(
        me: String,
        nearbyId: String,
        groupPattern: String,
    ): Flow<List<String>>

    /**
     * Distinct conversations the local user ([me]) has authored a message in — the "threads I started" signal.
     * A post heard on the Meshtastic radio sits in our sender column by convention (`originNode` is what says
     * whose words they are), so it is excluded: overhearing a channel is not starting a thread in it.
     */
    @Query("SELECT DISTINCT conversationId FROM messages WHERE senderId = :me AND originNode IS NULL")
    suspend fun conversationsIAuthoredIn(me: String): List<String>

    /**
     * The live form of [conversationsIAuthoredIn] — the same rule, for the screens that partition threads
     * into chats and requests and must keep agreeing with the notify gate as messages land.
     */
    @Query("SELECT DISTINCT conversationId FROM messages WHERE senderId = :me AND originNode IS NULL")
    fun observeConversationsIAuthoredIn(me: String): Flow<List<String>>

    /** Every distinct conversation id with any message — the candidate set for counting pending requests. */
    @Query("SELECT DISTINCT conversationId FROM messages")
    suspend fun distinctConversations(): List<String>

    /**
     * The live form of [distinctConversations], minus threads whose every row is from a sender in [blocked]:
     * the set of conversations the chat list and the requests inbox may show a row for. Any kind counts —
     * a thread holding only a notice is still a thread. Served whole by the `(conversationId, kind, senderId)`
     * index, so it never touches a row. An empty [blocked] expands to `NOT IN ()`, which SQLite reads as true.
     */
    @Query("SELECT DISTINCT conversationId FROM messages WHERE senderId NOT IN (:blocked)")
    fun observeDistinctConversations(blocked: Collection<String>): Flow<List<String>>

    /**
     * Distinct node ids that have sent a message in [conversationId] — a group is "known" once one is.
     *
     * Status notices are excluded (`kind = 0` is [MessageEntity.KIND_NORMAL]; Room's `@Query` can't
     * reference the constant). A notice's `senderId` is the event's **subject**, not an author, so
     * counting it here would let a peer who has never spoken — one who merely renamed themselves, or
     * left — satisfy the "a known peer has posted here" half of [Conversations.isAccepted] and quietly
     * promote a message request into an accepted chat.
     */
    @Query("SELECT DISTINCT senderId FROM messages WHERE conversationId = :conversationId AND kind = 0")
    suspend fun sendersIn(conversationId: String): List<String>

    /**
     * The live form of [sendersIn], for the chat screen's @-mention autocomplete. Same `kind = 0` rule and
     * the same reason for it. It exists because the screen reads a *window* of the thread: deriving the
     * candidates from the loaded rows would quietly drop everyone who last spoke further back than the
     * window reaches, so who you can mention would depend on how far you had scrolled.
     */
    @Query("SELECT DISTINCT senderId FROM messages WHERE conversationId = :conversationId AND kind = 0")
    fun observeSendersIn(conversationId: String): Flow<List<String>>

    /**
     * Whether [conversationId] holds any ordinary message (`kind = 0`, [MessageEntity.KIND_NORMAL]) —
     * the gate for writing a peer status notice into a DM thread. A `profile` frame floods the whole
     * mesh, so without this a stranger's rename would conjure a thread into the chat list; status rows
     * don't count, or one notice would license the next.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE conversationId = :conversationId AND kind = 0)")
    suspend fun hasMessagesIn(conversationId: String): Boolean

    /**
     * Ordinary messages (`kind = 0`) whose body matches the FTS4 expression [match], in [conversations], from
     * senders not in [blocked], newest first, at most [limit] — the Messages section of search. [match] is
     * built by `SearchQuery.toMatch` and never from raw input: it is only ever `term* term*`, which both
     * FTS query syntaxes read the same way. [conversations] is an allow-list, the chat list's own visible
     * threads, so a stranger's request thread is never searched (ADR 009); [hideFlagged] drops text the
     * on-device moderator flagged, mirroring the thread's collapse of those bubbles.
     *
     * Shape matters for cost, as everywhere in this file. The subselect makes SQLite drive from the FTS
     * index — the match set, then one rowid seek into `messages` per hit — rather than scan `messages` and
     * ask the index per row; the filters run on those rows only, and the sort is over the filtered hits
     * before the LIMIT. Bounded and index-served, so it honours the header rule. An empty [blocked] expands
     * to `NOT IN ()`, which reads as true. One-shot on purpose: as a `Flow` it would re-run on every write to
     * `messages` anywhere, because the FTS table's invalidation is the content table's.
     */
    @Query(
        "SELECT * FROM messages WHERE rowid IN " +
            "(SELECT rowid FROM messages_fts WHERE messages_fts MATCH :match) " +
            "AND kind = 0 AND (:hideFlagged = 0 OR moderation = 0) " +
            "AND senderId NOT IN (:blocked) AND conversationId IN (:conversations) " +
            "ORDER BY sentAt DESC, id DESC LIMIT :limit",
    )
    suspend fun searchBodies(
        match: String,
        conversations: Collection<String>,
        blocked: Collection<String>,
        hideFlagged: Boolean,
        limit: Int,
    ): List<MessageEntity>

    /**
     * The newest row per conversation whose `kind` is in [kinds] and whose sender is not in [blocked] — the
     * chat list's preview, time, tick and sort key for every thread at once, without reading any thread.
     * [kinds] are [MessageEntity.KIND_NORMAL] and friends (Room's `@Query` can't reference the constants):
     * the chat list passes normal + file-transfer rows (the two kinds that speak for a row), the requests
     * inbox normal rows only. A thread with no such row is absent here but still present in
     * [observeDistinctConversations].
     *
     * Shape matters for cost. The driver is the table's distinct conversation set (a covering-index walk),
     * and for each conversation the correlated subquery walks the `(conversationId, sentAt, id)` index
     * backward and stops at the first row that passes the kind/blocked filters — one row in the common case.
     * The `id DESC` tiebreak is [observeNewestForConversation]'s, so a burst of same-instant messages
     * cannot flip a row's preview between emissions. The rows are returned newest-first. (The SQLite
     * `SELECT *, MAX(sentAt) … GROUP BY` idiom would pick an arbitrary row on a tie and fetch every row.)
     */
    @Query(
        "SELECT * FROM messages WHERE id IN (" +
            "SELECT (SELECT n.id FROM messages AS n WHERE n.conversationId = c.conversationId " +
            "AND n.kind IN (:kinds) AND n.senderId NOT IN (:blocked) " +
            "ORDER BY n.sentAt DESC, n.id DESC LIMIT 1) " +
            "FROM (SELECT DISTINCT conversationId FROM messages) AS c) " +
            "ORDER BY sentAt DESC, id DESC",
    )
    fun observeNewestPerConversation(
        kinds: Collection<Int>,
        blocked: Collection<String>,
    ): Flow<List<MessageEntity>>

    /**
     * Every (group, author) pair for ordinary messages in group threads — [sendersIn]'s rule (`kind = 0`,
     * and the same reason for it) for every group at once, so the chat list and the requests inbox can ask
     * "has a known peer posted here" with the sweep's and the notify gate's answer. Senders in [blocked] are
     * left out, as their messages are everywhere else on those screens.
     *
     * [groupGlob] is [Conversations.GROUP_ID_PREFIX] + `*`, which [app.getknit.knit.data.MessageRepository]
     * supplies. It is `GLOB`, not `LIKE`, on purpose: the indices collate BINARY, so `LIKE 'g-%'` cannot use
     * one and walks the whole table, while `GLOB 'g-*'` is a range over the group rows of the
     * `(conversationId, kind, senderId)` index and never touches a row.
     */
    @Query(
        "SELECT DISTINCT conversationId, senderId FROM messages " +
            "WHERE kind = 0 AND conversationId GLOB :groupGlob AND senderId NOT IN (:blocked)",
    )
    fun observeGroupSenders(
        groupGlob: String,
        blocked: Collection<String>,
    ): Flow<List<ConversationSender>>

    /**
     * The chat list's unread badge for one thread: ordinary messages (`kind = 0`) newer than the read
     * watermark [since] that are not ours and not from a sender in [blocked]. "Ours" is `senderId = me`
     * with no `originNode` — a post heard on the Meshtastic radio sits in our sender column by convention
     * and is somebody else's words, so it counts. A range seek on `(conversationId, sentAt, id)` reaches
     * only the rows newer than the watermark, which for a thread that is read up to date is none.
     */
    @Query(
        "SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId AND sentAt > :since " +
            "AND kind = 0 AND NOT (senderId = :me AND originNode IS NULL) AND senderId NOT IN (:blocked)",
    )
    suspend fun countUnreadIn(
        conversationId: String,
        since: Long,
        me: String,
        blocked: Collection<String>,
    ): Int

    /**
     * The channel the newest heard post in [conversationId] was tagged with, or null when none was — the
     * Meshtastic room's title while the board is away (`meshRoomChannel`). Our own typed posts carry no
     * channel and are stepped over; a blank name is no name.
     */
    @Query(
        "SELECT originChannel FROM messages WHERE conversationId = :conversationId " +
            "AND originChannel IS NOT NULL AND TRIM(originChannel) <> '' " +
            "ORDER BY sentAt DESC, id DESC LIMIT 1",
    )
    fun observeNewestOriginChannel(conversationId: String): Flow<String?>

    /** The newest `sentAt` in [conversationId], or null for an empty thread — the notification's mark-read watermark. */
    @Query("SELECT MAX(sentAt) FROM messages WHERE conversationId = :conversationId")
    suspend fun newestSentAt(conversationId: String): Long?

    /**
     * How many ordinary messages (`kind = 0`) somebody other than [me] has sent — the review prompt's
     * "has this person heard from anyone" count. A heard Meshtastic post sits in our sender column, so it
     * is not counted here (and is counted by [countMine]); the prompt's policy has always read it that way.
     */
    @Query("SELECT COUNT(*) FROM messages WHERE senderId <> :me AND kind = 0")
    suspend fun countFromOthers(me: String): Int

    /** How many rows [me] is the sender of, notices and heard posts included — see [countFromOthers]. */
    @Query("SELECT COUNT(*) FROM messages WHERE senderId = :me")
    suspend fun countMine(me: String): Int

    /** Per-conversation row count + newest sentAt, for the retention sweep's cap / age / thread-count decisions. */
    @Query("SELECT conversationId, MAX(sentAt) AS lastSentAt, COUNT(*) AS count FROM messages GROUP BY conversationId")
    suspend fun conversationActivity(): List<ConversationActivity>

    /** Keeps only the newest [keep] messages (by sentAt) in [conversationId], deleting the rest. */
    @Query(
        "DELETE FROM messages WHERE conversationId = :conversationId AND id NOT IN " +
            "(SELECT id FROM messages WHERE conversationId = :conversationId ORDER BY sentAt DESC, id DESC LIMIT :keep)",
    )
    suspend fun deleteOldestInConversation(
        conversationId: String,
        keep: Int,
    )

    /** Deletes messages in [conversationId] older than [cutoff] (frame-global sentAt). */
    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND sentAt < :cutoff")
    suspend fun deleteOlderThan(
        conversationId: String,
        cutoff: Long,
    )

    /** Senders holding more than [keep] rows in [conversationId] — the room sweep's per-sender candidates. */
    @Query("SELECT senderId FROM messages WHERE conversationId = :conversationId GROUP BY senderId HAVING COUNT(*) > :keep")
    suspend fun sendersOverIn(
        conversationId: String,
        keep: Int,
    ): List<String>

    /** Keeps only [senderId]'s newest [keep] messages in [conversationId]; the `id` tiebreak matches [deleteOldestInConversation]. */
    @Query(
        "DELETE FROM messages WHERE conversationId = :conversationId AND senderId = :senderId AND id NOT IN " +
            "(SELECT id FROM messages WHERE conversationId = :conversationId AND senderId = :senderId " +
            "ORDER BY sentAt DESC, id DESC LIMIT :keep)",
    )
    suspend fun deleteOldestBySenderInConversation(
        conversationId: String,
        senderId: String,
        keep: Int,
    )

    /** Row count of [conversationId], notices included — the room sweep's over-cap arithmetic. */
    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId")
    suspend fun countIn(conversationId: String): Int

    /**
     * Deletes the oldest [limit] messages in [conversationId] whose sender is **not** in [keepFrom] — the
     * room sweep's first tier, so a flood of strangers' posts evicts strangers' posts before a contact's.
     */
    @Query(
        "DELETE FROM messages WHERE conversationId = :conversationId AND id IN " +
            "(SELECT id FROM messages WHERE conversationId = :conversationId AND senderId NOT IN (:keepFrom) " +
            "ORDER BY sentAt ASC, id ASC LIMIT :limit)",
    )
    suspend fun deleteOldestFromStrangersIn(
        conversationId: String,
        keepFrom: Set<String>,
        limit: Int,
    )
}

/** Room projection for [MessageDao.conversationActivity]: a thread's id, newest message time, and row count. */
data class ConversationActivity(
    val conversationId: String,
    val lastSentAt: Long,
    val count: Int,
)

/** Room projection for [MessageDao.observeGroupSenders]: one row per distinct (group, author) pair. */
data class ConversationSender(
    val conversationId: String,
    val senderId: String,
)
