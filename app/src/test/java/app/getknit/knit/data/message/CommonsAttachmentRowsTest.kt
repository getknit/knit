package app.getknit.knit.data.message

import app.getknit.knit.data.RoomDbTest
import app.getknit.knit.data.blob.BlobEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The commons' two reads over the real [MessageDao] SQL (spec §7.4, ADR 2026-10.xhfr): a commons row is never a radio
 * fetch row — its bytes live on the room's relay alone — and the room's own read lists what other members posted and
 * this phone lacks, past what the room's pass already settled.
 */
class CommonsAttachmentRowsTest : RoomDbTest() {
    private val dao get() = db.messageDao()

    @Test
    fun `a commons row is never a radio fetch row, and the room's own query lists other members' missing attachments`() =
        runTest {
            val room = Conversations.commonsIdFor("ab".repeat(32))
            dao.upsert(msg("c1", conversationId = room, attachmentHash = "H1", sender = "peer", sentAt = 10L))
            dao.upsert(msg("c2", conversationId = room, attachmentHash = "H1", sender = "carol", sentAt = 12L)) // one entry per hash
            dao.upsert(msg("c3", conversationId = room, attachmentHash = "H2", sender = "me", sentAt = 11L)) // ours: pushed, not fetched
            dao.upsert(msg("c4", conversationId = room, attachmentHash = "H3", sender = "peer", sentAt = 3L)) // past the room's window
            dao.upsert(msg("c5", conversationId = room, attachmentHash = "H4", sender = "peer", sentAt = 13L)) // held below
            dao.upsert(msg("n1", attachmentHash = "H5", sender = "peer", sentAt = 14L)) // the Nearby room's: another thread
            db.blobDao().insert(BlobEntity(hash = "H4", mime = "image/jpeg", bytes = ByteArray(0)))

            assertEquals(listOf("H5"), dao.attachmentFetchRows(me = "me", commonsGlob = COMMONS_GLOB).map { it.hash })
            // Nor what a tap asks the radios for, or what a delivery judges a want on.
            assertEquals(emptyList<AttachmentFetchRow>(), dao.attachmentFetchRowsFor("H1", me = "me", commonsGlob = COMMONS_GLOB))
            assertEquals(
                listOf(CommonsAttachmentRow("H1", null, 12L)),
                dao.commonsAttachmentsMissing(room, me = "me", since = 5L, skip = emptyList(), limit = 32),
            )
            // What the room's pass settled or quarantined stays out of the read, so the limit counts live candidates.
            assertEquals(
                emptyList<CommonsAttachmentRow>(),
                dao.commonsAttachmentsMissing(room, me = "me", since = 5L, skip = listOf("H1"), limit = 32),
            )
        }

    private fun msg(
        id: String,
        conversationId: String = Conversations.NEARBY,
        attachmentHash: String,
        sender: String,
        sentAt: Long,
    ) = MessageEntity(
        id = id,
        senderId = sender,
        conversationId = conversationId,
        body = "",
        sentAt = sentAt,
        attachmentHash = attachmentHash,
    )

    private companion object {
        const val COMMONS_GLOB = Conversations.COMMONS_PREFIX + "*"
    }
}
