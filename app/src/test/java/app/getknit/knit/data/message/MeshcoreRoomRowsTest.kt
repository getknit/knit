package app.getknit.knit.data.message

import app.getknit.knit.data.RoomDbTest
import app.getknit.knit.mesh.MeshPostOrigin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The MeshCore room's rows against the **real** [MessageDao] SQL (ADR 2026-11.gsqu's Stage 6 amendment): a heard post
 * under the sentinel `originNode` is never read as the user's own, and an own post's end is marked on its row alone.
 */
class MeshcoreRoomRowsTest : RoomDbTest() {
    private val dao get() = db.messageDao()
    private val room = Conversations.MESHCORE_PUBLIC

    private fun row(
        id: String,
        conversationId: String = room,
        sender: String = ME,
        originNode: Long? = null,
        kind: Int = MessageEntity.KIND_NORMAL,
    ) = MessageEntity(
        id = id,
        senderId = sender,
        conversationId = conversationId,
        body = "words",
        sentAt = 11L,
        receivedVia = DeliveryPlane.LoRa.code,
        originNode = originNode,
        kind = kind,
    )

    /**
     * Security B1: our sender column by convention, never our words — not authored here, not an own send, unread, and no
     * rule reading `originNode IS NULL` takes it for ours.
     */
    @Test
    fun `a heard post under the sentinel is never read as the user's own`() =
        runTest {
            val heard = row("heard", originNode = MeshPostOrigin.MESHCORE_NODE)
            dao.upsert(heard)

            assertFalse(heard.writtenBy(ME))
            assertTrue(dao.conversationsIAuthoredIn(ME).isEmpty())
            assertTrue(dao.observeConversationsIAuthoredIn(ME).first().isEmpty())
            assertTrue(dao.recentOwnSends(ME, limit = 10).isEmpty())
            assertEquals(1, dao.countUnreadIn(room, since = 0L, me = ME, blocked = emptySet()))
        }

    @Test
    fun `an ended post marks only our own post in that room, never sent apart from unconfirmed`() =
        runTest {
            dao.upsert(row("stale"))
            dao.upsert(row("write"))
            dao.upsert(row("heard", originNode = MeshPostOrigin.MESHCORE_NODE))
            dao.upsert(row("meshtastic", conversationId = Conversations.MESHTASTIC))
            dao.upsert(row("notice", kind = MessageEntity.KIND_GROUP_CREATED))

            assertEquals(1, dao.markPublicPostEnded("stale", room, ME, neverSent = true))
            assertEquals(1, dao.markPublicPostEnded("write", room, ME, neverSent = false))
            // Never a heard post, another room's, a notice, or another sender's.
            assertEquals(0, dao.markPublicPostEnded("heard", room, ME, neverSent = true))
            assertEquals(0, dao.markPublicPostEnded("meshtastic", room, ME, neverSent = true))
            assertEquals(0, dao.markPublicPostEnded("notice", room, ME, neverSent = true))
            assertEquals(0, dao.markPublicPostEnded("write", room, "someone else", neverSent = true))
            // A post already marked never sent is not softened to unconfirmed.
            assertEquals(0, dao.markPublicPostEnded("stale", room, ME, neverSent = false))

            val stale = checkNotNull(dao.findById("stale"))
            assertTrue(stale.pendingKey)
            assertEquals(DeliveryPlane.LoRa.code, stale.receivedVia)
            val write = checkNotNull(dao.findById("write"))
            assertFalse(write.pendingKey)
            assertEquals(DeliveryPlane.Unknown.code, write.receivedVia)
            assertFalse(checkNotNull(dao.findById("heard")).pendingKey)
            assertFalse(checkNotNull(dao.findById("meshtastic")).pendingKey)
            // No recipient, so no resend path ever reads the mark.
            assertTrue(dao.pendingForRecipient(ME).isEmpty())
        }

    private companion object {
        const val ME = "me"
    }
}
