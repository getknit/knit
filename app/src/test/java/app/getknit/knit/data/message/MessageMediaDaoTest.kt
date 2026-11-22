package app.getknit.knit.data.message

import app.getknit.knit.data.RoomDbTest
import app.getknit.knit.mesh.protocol.LinkPreviewBlob
import app.getknit.knit.ui.chat.AttachmentKind
import app.getknit.knit.ui.chat.attachmentKindOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The real SQL of "Media, links and files" (ADR 2026-11.dgvf): [MessageDao.observeMediaIn], [MessageDao.observeFilesIn]
 * and [MessageDao.observeLinkRowsIn] — one thread, ordinary rows only, blocked senders out, newest first, bounded, and
 * each tab's kinds exactly the bubble's.
 */
class MessageMediaDaoTest : RoomDbTest() {
    private val dao get() = db.messageDao()

    @Test
    fun `observeMediaIn lists one thread's pictures newest first, ties by id, up to the limit`() =
        runTest {
            dao.upsert(row("a", sentAt = 1L, hash = "H1", mime = "image/jpeg"))
            dao.upsert(row("b", sentAt = 2L, hash = "H2", mime = "image/webp"))
            dao.upsert(row("c", sentAt = 2L, hash = "H3", mime = "image/jpeg"))
            dao.upsert(row("d", sentAt = 3L, hash = null)) // text only
            dao.upsert(row("e", sentAt = 4L, hash = "H4", mime = "image/jpeg", conversationId = "elsewhere"))

            assertEquals(listOf("c", "b", "a"), dao.observeMediaIn(THREAD, emptyList(), 10).first().map { it.id })
            assertEquals("the limit cuts the oldest", listOf("c", "b"), dao.observeMediaIn(THREAD, emptyList(), 2).first().map { it.id })
        }

    @Test
    fun `the tabs split attachments exactly as attachmentKindOf does`() =
        runTest {
            // One row per branch of attachmentKindOf, and the edges between them.
            val rows =
                listOf(
                    row("photo", 1L, "H1", mime = "image/jpeg"),
                    row("photoNoMime", 2L, "H2", mime = null),
                    row("video", 3L, "H3", mime = "video/mp4", name = "video.mp4").copy(videoDurationMs = 9_000L),
                    // An .mp4 an older build sent as a file: no description, so a file.
                    row("oldMp4", 4L, "H4", mime = "video/mp4", name = "clip.mp4"),
                    // A described video's MIME is case-sensitive in the Kotlin too.
                    row("upperVideo", 5L, "H5", mime = "VIDEO/MP4", name = "video.mp4").copy(videoDurationMs = 9_000L),
                    row("namedImage", 6L, "H6", mime = "image/png", name = "scan.png"),
                    row("namedNoMime", 7L, "H7", mime = null, name = "notes"),
                    row("voice", 8L, "H8", mime = "audio/aac"),
                    row("card", 9L, "H9", mime = LinkPreviewBlob.MIME),
                    row("namedCardMime", 10L, "H10", mime = LinkPreviewBlob.MIME, name = "card.bin"),
                    row("pdf", 11L, "H11", mime = "application/pdf", name = "map.pdf"),
                    row("unnamedOther", 12L, "H12", mime = "application/octet-stream"),
                )
            rows.forEach { dao.upsert(it) }

            val media =
                dao
                    .observeMediaIn(THREAD, emptyList(), 50)
                    .first()
                    .map { it.id }
                    .toSet()
            val files =
                dao
                    .observeFilesIn(THREAD, emptyList(), 50)
                    .first()
                    .map { it.id }
                    .toSet()

            assertEquals(setOf("photo", "photoNoMime", "video"), media)
            assertEquals(
                setOf("oldMp4", "upperVideo", "namedImage", "namedNoMime", "voice", "namedCardMime", "pdf", "unnamedOther"),
                files,
            )

            // And the one rule the bubble draws by agrees, row by row.
            fun kind(m: MessageEntity) = attachmentKindOf(m.attachmentMime, m.attachmentName, video = m.videoDurationMs != null)
            assertEquals(rows.filter { kind(it) in setOf(AttachmentKind.Photo, AttachmentKind.Video) }.map { it.id }.toSet(), media)
            assertEquals(rows.filter { kind(it) in setOf(AttachmentKind.File, AttachmentKind.Voice) }.map { it.id }.toSet(), files)
        }

    @Test
    fun `a thread of pictures never starves its files, nor its files its pictures`() =
        runTest {
            // The file is older than a whole window of photos: a shared window would have shown no files at all.
            dao.upsert(row("file", sentAt = 1L, hash = "F", mime = "application/pdf", name = "map.pdf"))
            (2L..11L).forEach { at -> dao.upsert(row("p$at", sentAt = at, hash = "P$at", mime = "image/jpeg")) }

            assertEquals(listOf("file"), dao.observeFilesIn(THREAD, emptyList(), limit = 3).first().map { it.id })
            assertEquals(listOf("p11", "p10", "p9"), dao.observeMediaIn(THREAD, emptyList(), limit = 3).first().map { it.id })
        }

    @Test
    fun `the attachment reads leave out status notices, Direct Send records and blocked senders`() =
        runTest {
            dao.upsert(row("ok", sentAt = 1L, hash = "H1", mime = "image/jpeg"))
            dao.upsert(row("okFile", sentAt = 1L, hash = "H5", mime = "application/pdf", name = "a.pdf"))
            dao.upsert(row("photo", sentAt = 2L, hash = "H2", mime = "image/jpeg").copy(kind = MessageEntity.KIND_GROUP_PHOTO))
            dao.upsert(
                row(
                    "transfer",
                    sentAt = 3L,
                    hash = "H3",
                    mime = "application/pdf",
                    name = "b.pdf",
                ).copy(kind = MessageEntity.KIND_FILE_TRANSFER),
            )
            dao.upsert(row("blocked", sentAt = 4L, hash = "H4", mime = "image/jpeg", sender = "troll"))
            dao.upsert(row("blockedFile", sentAt = 4L, hash = "H6", mime = "application/pdf", name = "c.pdf", sender = "troll"))

            assertEquals(listOf("ok"), dao.observeMediaIn(THREAD, listOf("troll"), 10).first().map { it.id })
            assertEquals(listOf("okFile"), dao.observeFilesIn(THREAD, listOf("troll"), 10).first().map { it.id })
        }

    @Test
    fun `observeLinkRowsIn prefilters bodies that may hold a link, in any case`() =
        runTest {
            dao.upsert(row("https", sentAt = 1L, body = "see https://example.org/x"))
            dao.upsert(row("upper", sentAt = 2L, body = "HTTP://EXAMPLE.ORG"))
            dao.upsert(row("www", sentAt = 3L, body = "try WWW.example.org"))
            dao.upsert(row("plain", sentAt = 4L, body = "no link here"))
            dao.upsert(row("notice", sentAt = 5L, body = "https://example.org").copy(kind = MessageEntity.KIND_PEER_RENAMED))
            dao.upsert(row("other", sentAt = 6L, body = "https://example.org", conversationId = "elsewhere"))
            dao.upsert(row("blocked", sentAt = 7L, body = "https://example.org", sender = "troll"))

            assertEquals(
                listOf("www", "upper", "https"),
                dao.observeLinkRowsIn(THREAD, listOf("troll"), hideFlagged = false, limit = 10).first().map { it.id },
            )
        }

    @Test
    fun `observeLinkRowsIn leaves out flagged text and replies quoting it only while hiding flagged`() =
        runTest {
            dao.upsert(row("clean", sentAt = 1L, body = "https://example.org/a"))
            dao.upsert(row("flagged", sentAt = 2L, body = "https://example.org/b").copy(moderation = MessageEntity.MODERATION_TEXT_FLAGGED))
            dao.upsert(row("quoting", sentAt = 3L, body = "https://example.org/c").copy(replyToFlagged = true))

            assertEquals(listOf("clean"), dao.observeLinkRowsIn(THREAD, emptyList(), hideFlagged = true, limit = 10).first().map { it.id })
            assertEquals(
                listOf("quoting", "flagged", "clean"),
                dao.observeLinkRowsIn(THREAD, emptyList(), hideFlagged = false, limit = 10).first().map { it.id },
            )
        }

    private fun row(
        id: String,
        sentAt: Long,
        hash: String? = null,
        mime: String? = null,
        name: String? = null,
        body: String = "",
        sender: String = "s",
        conversationId: String = THREAD,
    ) = MessageEntity(
        id = id,
        senderId = sender,
        conversationId = conversationId,
        body = body,
        sentAt = sentAt,
        received = true,
        attachmentHash = hash,
        attachmentMime = mime,
        attachmentName = name,
    )

    private companion object {
        const val THREAD = "thread"
    }
}
