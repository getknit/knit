package app.getknit.knit.data.forward

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import app.getknit.knit.data.message.NeededAttachment
import kotlinx.coroutines.flow.Flow

/**
 * An `(id, expiresAt)` projection of a live carried frame — the rebuild source for the
 * [app.getknit.knit.mesh.StoreDigest] content digest, which needs the expiry alongside the id to drive its
 * lazy TTL-boundary fold (work item #8).
 */
data class ForwardIdExpiry(
    val id: String,
    val expiresAt: Long,
)

/**
 * Carry-store queries. **Every quota/eviction/digest query is live-filtered (`expiresAt >= :now`)**: an
 * expired-but-unswept row is per-node state (sweep ticks phase independently), so any bounding rule that
 * reads it would evict different *live* rows on different nodes and de-converge the content digest — the
 * convergent-custody rule from AGENTS.md. Expired rows are invisible to everything observable and exist only
 * until [deleteExpired] (the periodic sweep, now pure storage GC) reclaims them; the physical table is thus
 * bounded by the caps plus at most one sweep period of lapsed residue.
 *
 * Two eviction keys, both read off the frame and the held set alone, so every node evicts alike (ADR 006): a sender,
 * group or room bucket over its quota keeps its newest-N by `(sentAt, id)` (`evictOldestBy*`), and the global cap
 * takes one row at a time from the largest sender bucket ([evictFairest], ADR 2026-10.7mqm).
 */
@Dao
@Suppress("TooManyFunctions") // a handful of small carry-store queries; splitting them would obscure, not clarify
interface ForwardDao {
    /** Stores [row]; keyed by frame id, so a frame we already carry is silently ignored (dedup). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: ForwardEntity)

    /** Every non-expired carried frame, newest first — the push-on-contact source. */
    @Query("SELECT * FROM forward_store WHERE expiresAt >= :now ORDER BY receivedAt DESC")
    suspend fun liveRows(now: Long): List<ForwardEntity>

    /**
     * The newest live chat frames addressed to [recipientId] that someone other than [me] wrote — the relayed
     * half of the long-range re-offer (ADR 2026-10.a4pm). Our own half is chosen by id ([liveRowsByIds]), so
     * acked DMs and own sealed ctls no longer take slots an unacked DM needs.
     */
    @Query(
        "SELECT * FROM forward_store WHERE recipientId = :recipientId AND senderId != :me AND type = 'chat' " +
            "AND expiresAt >= :now ORDER BY sentAt DESC, id DESC LIMIT :limit",
    )
    suspend fun liveRowsToFromOthers(
        recipientId: String,
        me: String,
        now: Long,
        limit: Int,
    ): List<ForwardEntity>

    /**
     * The live rows among [ids], newest first; an id not held or expired by [now] is simply absent. Callers bound
     * [ids] (the re-offer asks for a dozen): every id is one bound parameter, and SQLite caps those per statement.
     */
    @Query("SELECT * FROM forward_store WHERE id IN (:ids) AND expiresAt >= :now ORDER BY sentAt DESC, id DESC")
    suspend fun liveRowsByIds(
        ids: List<String>,
        now: Long,
    ): List<ForwardEntity>

    /**
     * The live group chat frames authored by anyone but [me] — the custody replay's set (`ForwardStore.liveGroupChatFrames`).
     * Filtered here rather than after a decode of every live row: a phone in no group answers with nothing.
     */
    @Query(
        "SELECT * FROM forward_store WHERE groupId IS NOT NULL AND type = 'chat' AND senderId != :me " +
            "AND expiresAt >= :now ORDER BY receivedAt DESC",
    )
    suspend fun liveGroupChatRows(
        me: String,
        now: Long,
    ): List<ForwardEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM forward_store WHERE id = :id)")
    suspend fun exists(id: String): Boolean

    /** The live row [id], or null if not held or expired by [now]. */
    @Query("SELECT * FROM forward_store WHERE id = :id AND expiresAt >= :now")
    suspend fun liveRow(
        id: String,
        now: Long,
    ): ForwardEntity?

    /** The carried DM's cleartext recipient, or null if not held — gates the recipient-authenticated purge. */
    @Query("SELECT recipientId FROM forward_store WHERE id = :id")
    suspend fun recipientOf(id: String): String?

    @Query("DELETE FROM forward_store WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM forward_store WHERE expiresAt < :now")
    suspend fun deleteExpired(now: Long): Int

    /** Deletes every row whose `signed` is longer than [max] bytes: what a build before the byte cap stored (#227). */
    @Query("DELETE FROM forward_store WHERE length(signed) > :max")
    suspend fun deleteLongerThan(max: Int): Int

    /**
     * Deletes every row of [type] whose `signed` is longer than [max] bytes: the profiles a build before the profile cap
     * stored (#234). [type] is `FrameType.PROFILE`'s wire string, the value the column holds.
     */
    @Query("DELETE FROM forward_store WHERE type = :type AND length(signed) > :max")
    suspend fun deleteTypeLongerThan(
        type: String,
        max: Int,
    ): Int

    /** Live carried frames — the global-cap count (an expired-unswept row must not push a live one out). */
    @Query("SELECT COUNT(*) FROM forward_store WHERE expiresAt >= :now")
    suspend fun count(now: Long): Int

    /**
     * Live chat frames held for OTHER people — the Your mesh screen's "carrying now": relayed (origin 0 =
     * `ForwardStore.ORIGIN_RELAY`, never our own sends), authored by someone else, and not addressed to us
     * (a DM to us is custodied too, ADR 018). Group and broadcast-room frames count — we hold them for the
     * other members / whoever joins. A sealed receipt or reaction rides as `chat` and a carrier cannot tell
     * it from a message, so it counts as one. Room re-emits on every `forward_store` write; [now] is fixed
     * per subscription, so the caller re-subscribes on a ticker to age an expired-but-unswept row out
     * between the 10-min sweeps.
     */
    @Query(
        "SELECT COUNT(*) FROM forward_store WHERE origin = 0 AND type = 'chat' AND senderId != :me " +
            "AND (recipientId IS NULL OR recipientId != :me) AND expiresAt >= :now",
    )
    fun observeCarriedForOthers(
        me: String,
        now: Long,
    ): Flow<Int>

    /** Every carried frame id, expired residue included — diagnostics/tests only (the digest folds live ids). */
    @Query("SELECT id FROM forward_store")
    suspend fun allIds(): List<String>

    /** How many carried frames reference [hash] as their image blob — pins the blob against GC while any do. */
    @Query("SELECT COUNT(*) FROM forward_store WHERE attachmentHash = :hash")
    suspend fun countByAttachmentHash(hash: String): Int

    /**
     * Content hashes referenced by a carried frame whose bytes aren't in the `blobs` table yet, each with the author of
     * the earliest frame naming it — the carrier's side of the "still-missing blobs" set (mirrors
     * [app.getknit.knit.data.message.MessageDao.attachmentFetchRows]).
     * Re-requested on startup / neighbour-join so a carrier keeps trying to pull the image it's custodying. Live rows only,
     * by [liveIds]' predicate: a frame past its TTL is one no node carries any more, and until the prune swept it the
     * 60 s tick re-armed a full-budget want from it (#195, the mesh-lab review of ADR 2026-10.2drc).
     */
    @Query(
        "SELECT DISTINCT f.attachmentHash AS hash, " +
            "(SELECT s.senderId FROM forward_store AS s WHERE s.attachmentHash = f.attachmentHash AND s.expiresAt >= :now " +
            "ORDER BY s.sentAt, s.id LIMIT 1) AS senderId " +
            "FROM forward_store AS f " +
            "WHERE f.attachmentHash IS NOT NULL AND f.expiresAt >= :now AND f.attachmentHash NOT IN (SELECT hash FROM blobs)",
    )
    suspend fun attachmentsNeedingFetch(now: Long): List<NeededAttachment>

    /**
     * One row per carried frame naming an attachment not in the `blobs` table yet: whom it is for, when it was carried,
     * its author and its group — [attachmentsNeedingFetch] per frame, for the carrier's pull deferral (ADR 2026-10.g24x).
     * A frame with no cleartext recipient — a group's, for the roster it carries, or a room post's, for the size it
     * declares (ADR 2026-10.hmfe) — brings its signed bytes; a DM's are never loaded. A room post's only while no
     * message row names its hash: the tick reads the declared size only for hashes no row covers (`unopenedForUs`),
     * and a delivered, untapped room video would otherwise reload its poster-sized frame on every re-arm. Live rows only,
     * as [attachmentsNeedingFetch]; each with its frame's `sentAt`, the clock a DM's pull deadline keys on (#195), and
     * whether a row named its hash (`rowCovered`, the CASE's other half).
     *
     * Because the CASE reads `messages`, the re-arm reads this *before* the rows (`MeshManager.rewantMissingBlobsLocked`,
     * #141's finding 6): read after, a row written between the two reads was judged by neither and the post wanted plain.
     * That order closes every insert before the last read; a room post inserted after it is judged by the same declared
     * size; a sealed frame's row after it is held until the next re-arm. A row deleted between the reads leaves a
     * `rowCovered` post with no size, which the re-arm holds until the next one loads it.
     */
    @Query(
        "SELECT attachmentHash AS hash, recipientId, receivedAt, senderId, groupId, sentAt, " +
            "CASE WHEN recipientId IS NULL AND (groupId IS NOT NULL OR NOT EXISTS " +
            "(SELECT 1 FROM messages AS m WHERE m.attachmentHash = forward_store.attachmentHash)) " +
            "THEN signed END AS signed, " +
            "EXISTS (SELECT 1 FROM messages AS m WHERE m.attachmentHash = forward_store.attachmentHash) AS rowCovered " +
            "FROM forward_store " +
            "WHERE attachmentHash IS NOT NULL AND expiresAt >= :now AND attachmentHash NOT IN (SELECT hash FROM blobs)",
    )
    suspend fun carriedAttachments(now: Long): List<CarriedAttachmentRow>

    /**
     * Every carried frame including expired-but-unswept rows, newest first — the `debug.STORE` bridge dump.
     * Deliberately wider than what the digest folds (live rows only), so a dump can show the benign lapsed
     * residue awaiting the sweep alongside the live set the cue plane actually advertises.
     */
    @Query("SELECT * FROM forward_store ORDER BY receivedAt DESC")
    suspend fun allRows(): List<ForwardEntity>

    /** Ids of every non-expired carried frame — advertised on link-up so a peer replies with only what we lack. */
    @Query("SELECT id FROM forward_store WHERE expiresAt >= :now")
    suspend fun liveIds(now: Long): List<String>

    /** `(id, expiresAt)` of every non-expired carried frame — the digest rebuild source (init, sweep, eviction). */
    @Query("SELECT id, expiresAt FROM forward_store WHERE expiresAt >= :now")
    suspend fun liveIdExpiries(now: Long): List<ForwardIdExpiry>

    /** How many live carried frames a single [senderId] accounts for — enforces the per-sender quota. */
    @Query("SELECT COUNT(*) FROM forward_store WHERE senderId = :senderId AND expiresAt >= :now")
    suspend fun countBySender(
        senderId: String,
        now: Long,
    ): Int

    /** How many live carried frames a single [groupId] accounts for — enforces the per-group quota. */
    @Query("SELECT COUNT(*) FROM forward_store WHERE groupId = :groupId AND expiresAt >= :now")
    suspend fun countByGroup(
        groupId: String,
        now: Long,
    ): Int

    /**
     * How many live carried broadcast-room *chat* frames are held — enforces the broadcast quota. Reactions,
     * receipts, and profiles share the null recipient/group shape, so the [ForwardEntity.type] discriminator
     * is required to count only broadcast chat and not starve those metadata frames on the broadcast quota.
     */
    @Query(
        "SELECT COUNT(*) FROM forward_store " +
            "WHERE type = 'chat' AND recipientId IS NULL AND groupId IS NULL AND expiresAt >= :now",
    )
    suspend fun countBroadcast(now: Long): Int

    /**
     * Drops **one** live row under global-cap pressure: the oldest `(sentAt, id)` frame of the sender holding the most
     * live frames, and on a tie for most, the sender whose oldest frame is oldest (ADR 2026-10.7mqm). A
     * sender's bucket size is read from the held set, which is the same on every node that holds the same frames, so
     * every node evicts the same row and the content digests stay convergent (ADR 006). It is one row per call
     * because the sizes move with every eviction: the caller repeats it once per row over the cap.
     *
     * Not the globally oldest frame any more: under that rule five free identities stamping a few hundred frames
     * just inside the future window evicted every honest frame on every node at once, and every honest frame for
     * the next five minutes in the same write that stored it (the malicious-fork review's C2). Here a flood only
     * ever displaces the largest buckets, which are its own. Live-filtered on both sides, because buckets mix TTL
     * classes: an expired-unswept row counted or chosen here would make the choice depend on the local sweep phase
     * (work item #8). Grouping and ordering compare ids and sender ids bytewise (SQLite's default `BINARY`).
     */
    @Query(
        "DELETE FROM forward_store WHERE id = (" +
            "SELECT f.id FROM forward_store AS f " +
            "JOIN (SELECT senderId, COUNT(*) AS n FROM forward_store WHERE expiresAt >= :now GROUP BY senderId) AS b " +
            "ON b.senderId = f.senderId " +
            "WHERE f.expiresAt >= :now " +
            "ORDER BY b.n DESC, f.sentAt ASC, f.id ASC LIMIT 1)",
    )
    suspend fun evictFairest(now: Long)

    /** Evicts the [n] oldest-by-sentAt **live** frames from [senderId]'s bucket (keeps its newest per the quota). */
    @Query(
        "DELETE FROM forward_store WHERE id IN " +
            "(SELECT id FROM forward_store WHERE senderId = :senderId AND expiresAt >= :now " +
            "ORDER BY sentAt ASC, id ASC LIMIT :n)",
    )
    suspend fun evictOldestBySender(
        senderId: String,
        n: Int,
        now: Long,
    )

    /** Evicts the [n] oldest-by-sentAt **live** frames from [groupId]'s bucket (keeps its newest per the quota). */
    @Query(
        "DELETE FROM forward_store WHERE id IN " +
            "(SELECT id FROM forward_store WHERE groupId = :groupId AND expiresAt >= :now " +
            "ORDER BY sentAt ASC, id ASC LIMIT :n)",
    )
    suspend fun evictOldestByGroup(
        groupId: String,
        n: Int,
        now: Long,
    )

    /** Evicts the [n] oldest-by-sentAt **live** broadcast-room chat frames (keeps the newest per the quota). */
    @Query(
        "DELETE FROM forward_store WHERE id IN " +
            "(SELECT id FROM forward_store WHERE type = 'chat' AND recipientId IS NULL AND groupId IS NULL " +
            "AND expiresAt >= :now ORDER BY sentAt ASC, id ASC LIMIT :n)",
    )
    suspend fun evictOldestBroadcast(
        n: Int,
        now: Long,
    )
}

/**
 * Room projection for [ForwardDao.carriedAttachments]: one carried frame naming a blob we lack, and a group frame's
 * signed bytes, which the reader decodes for the roster it carries (ADR 2026-10.js2p). Not a data class: a byte array has
 * no value equality.
 */
class CarriedAttachmentRow(
    val hash: String,
    val recipientId: String?,
    val receivedAt: Long,
    val senderId: String,
    val groupId: String?,
    /** The frame's own clock, which custody's TTL and a DM's pull deadline key on (#195). */
    val sentAt: Long,
    /** A group frame's or a room post's signed bytes (its roster, its declared size); null for a DM, never loaded. */
    val signed: ByteArray?,
    /** Whether a message row named [hash] when this was read: a room post's [signed] is then not loaded (#141). */
    val rowCovered: Boolean,
)
