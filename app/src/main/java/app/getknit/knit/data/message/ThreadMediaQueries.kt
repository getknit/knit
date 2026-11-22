package app.getknit.knit.data.message

import androidx.room3.Query
import app.getknit.knit.mesh.protocol.LinkPreviewBlob
import kotlinx.coroutines.flow.Flow

/**
 * The reads of the chat's "Media, links and files" screen (ADR 2026-11.dgvf), one per tab, kept apart from [MessageDao]
 * for detekt's LargeClass as [BridgedRoomQueries] is. [MessageDao] inherits them and Room generates them as its own:
 * this is no DAO of its own, and nothing but [MessageDao] implements it.
 */
interface ThreadMediaQueries {
    /**
     * The newest [limit] ordinary messages in a thread carrying a photo or a video, newest first, from senders not in
     * [blocked]: the Media tab of the chat's "Media, links and files" screen (ADR 2026-11.dgvf). [MEDIA_ROW] is
     * `attachmentKindOf`'s Photo and Video in SQL, so the limit counts only what the tab shows: a thread of files
     * never starves its pictures, nor the other way round ([observeFilesIn]). Status notices never carry an
     * attachment, and a Direct Send record ([MessageEntity.KIND_FILE_TRANSFER]) keeps its bytes outside the blob
     * store, so both stay out by `kind`.
     *
     * Served by the `(conversationId, sentAt, id)` index walked backward, with no sort, stopping at [limit]: a thread
     * rich in pictures is cut short, and one that is mostly text is walked whole. There is no index on the attachment
     * for the planner to prefer, on purpose (the ADR), and the screen reads one tab at a time. An empty [blocked]
     * expands to `NOT IN ()`, which reads as true.
     */
    @Query(
        "SELECT * FROM messages WHERE conversationId = :conversationId AND kind = 0 " +
            "AND attachmentHash IS NOT NULL AND $MEDIA_ROW AND senderId NOT IN (:blocked) " +
            "ORDER BY sentAt DESC, id DESC LIMIT :limit",
    )
    fun observeMediaIn(
        conversationId: String,
        blocked: Collection<String>,
        limit: Int,
    ): Flow<List<MessageEntity>>

    /**
     * [observeMediaIn]'s sibling for the Files tab: every other attachment but a link card (which the Links tab draws
     * on its link), so `attachmentKindOf`'s File and Voice. The same index walk, the same bound.
     */
    @Query(
        "SELECT * FROM messages WHERE conversationId = :conversationId AND kind = 0 " +
            "AND attachmentHash IS NOT NULL AND NOT $MEDIA_ROW AND NOT $LINK_CARD_ROW AND senderId NOT IN (:blocked) " +
            "ORDER BY sentAt DESC, id DESC LIMIT :limit",
    )
    fun observeFilesIn(
        conversationId: String,
        blocked: Collection<String>,
        limit: Int,
    ): Flow<List<MessageEntity>>

    /**
     * The newest [limit] ordinary messages in a thread whose body may hold a link, newest first, from senders not
     * in [blocked]: the Links tab of "Media, links and files" (ADR 2026-11.dgvf). The `LIKE`s are a coarse,
     * case-insensitive prefilter for what `findUrls` reads (`http(s)://` or a bare `www.`), which still decides
     * what is a link; [hideFlagged] leaves out text the on-device moderator flagged and replies quoting flagged
     * words, the same clause as [MessageDao.searchBodies]. The same index walk as [observeMediaIn].
     */
    @Query(
        "SELECT * FROM messages WHERE conversationId = :conversationId AND kind = 0 " +
            "AND (body LIKE '%http%' OR body LIKE '%www.%') " +
            "AND (:hideFlagged = 0 OR (moderation = 0 AND replyToFlagged = 0)) " +
            "AND senderId NOT IN (:blocked) " +
            "ORDER BY sentAt DESC, id DESC LIMIT :limit",
    )
    fun observeLinkRowsIn(
        conversationId: String,
        blocked: Collection<String>,
        hideFlagged: Boolean,
        limit: Int,
    ): Flow<List<MessageEntity>>
}

// `attachmentKindOf` (ui/chat/AttachmentKind.kt) in SQL, for "Media, links and files" (ADR 2026-11.dgvf): its Video
// (a described video MIME), its Photo (no name, an image MIME or none) and its Link (no name, the card's MIME). Each is
// true or false, never NULL, so a `NOT` of one is safe. `GLOB` is case-sensitive, as `startsWith` is. Kept in step with
// the Kotlin by `MessageMediaDaoTest` ("the tabs split attachments exactly as attachmentKindOf does").
private const val VIDEO_ROW = "(videoDurationMs IS NOT NULL AND IFNULL(attachmentMime, '') GLOB 'video/*')"
private const val PHOTO_ROW = "(attachmentName IS NULL AND (attachmentMime IS NULL OR attachmentMime GLOB 'image/*'))"
private const val MEDIA_ROW = "($VIDEO_ROW OR $PHOTO_ROW)"
private const val LINK_CARD_ROW = "(attachmentName IS NULL AND IFNULL(attachmentMime, '') = '${LinkPreviewBlob.MIME}')"
