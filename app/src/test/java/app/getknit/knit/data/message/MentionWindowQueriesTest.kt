package app.getknit.knit.data.message

import app.getknit.knit.MentionVouchCases
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.RoomDbTest
import app.getknit.knit.mesh.protocol.Mention
import app.getknit.knit.ui.chat.MENTION_WINDOW
import app.getknit.knit.ui.chat.mentionWindowEdge
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Executes the **real** [MentionWindowQueries] SQL against the pure rule it mirrors (ADR 2026-11.p6dk's mention window). */
class MentionWindowQueriesTest : RoomDbTest() {
    private val dao get() = db.messageDao()

    @Test
    fun `observeMentionWindowEdge answers every mention-vouch window case as the pure rule does`() =
        runTest {
            // ADR 2026-11.p6dk: the window covers every stored row of the thread, so the table answers it, and it must
            // place the edge exactly where `mentionWindowEdge` does over the same rows, the vector's cases included.
            val me = MentionVouchCases.W
            MentionVouchCases.WINDOWS.forEachIndexed { i, case ->
                val thread = "window-$i"
                for (row in case.rows) {
                    val mentions = List(row.mentions) { Mention(MentionVouchCases.A, "Alice") }
                    dao.upsert(
                        msg(row.id + "-$i", conversationId = thread, sender = row.senderId, sentAt = row.sentAt)
                            .copy(mentions = MentionStore.encode(mentions)),
                    )
                }
                val rows = case.rows.map { it.copy(id = it.id + "-$i") }
                val blocked = case.blocked.toSet()
                assertEquals(
                    case.label,
                    mentionWindowEdge(rows, me, blocked),
                    dao.observeMentionWindowEdge(thread, me, blocked, MENTION_WINDOW - 1).first(),
                )
                // And through the seam the chat screen reads, which takes the window's size, not its last offset.
                assertEquals(
                    case.label,
                    mentionWindowEdge(rows, me, blocked),
                    MessageRepository(dao).observeMentionWindowEdge(thread, me, blocked, MENTION_WINDOW).first(),
                )
            }
            // Another thread's rows never count toward this one's window.
            assertNull(dao.observeMentionWindowEdge("window-none", me, emptySet(), MENTION_WINDOW - 1).first())
        }

    @Test
    fun `observeMentionWindowEdge moves as a counted row lands and ignores ours and plain ones`() =
        runTest {
            val me = MentionVouchCases.W
            val one = MentionStore.encode(listOf(Mention(MentionVouchCases.A, "Alice")))
            for (i in 1..3) dao.upsert(msg("m$i", conversationId = "thread", sender = "peer", sentAt = i.toLong()).copy(mentions = one))
            assertEquals(MessagePlace(2L, "m2"), dao.observeMentionWindowEdge("thread", me, emptySet(), 1).first())
            dao.upsert(msg("ours", conversationId = "thread", sender = me, sentAt = 10L).copy(mentions = one))
            dao.upsert(msg("plain", conversationId = "thread", sender = "peer", sentAt = 11L))
            assertEquals(MessagePlace(2L, "m2"), dao.observeMentionWindowEdge("thread", me, emptySet(), 1).first())
            dao.upsert(msg("m4", conversationId = "thread", sender = "peer", sentAt = 4L).copy(mentions = one))
            assertEquals(MessagePlace(3L, "m3"), dao.observeMentionWindowEdge("thread", me, emptySet(), 1).first())
            // A blocked sender's rows leave the count: with "peer" blocked, nothing is left to fill it.
            dao.upsert(msg("b1", conversationId = "thread", sender = "other", sentAt = 20L).copy(mentions = one))
            assertEquals(MessagePlace(4L, "m4"), dao.observeMentionWindowEdge("thread", me, emptySet(), 1).first())
            assertNull(dao.observeMentionWindowEdge("thread", me, setOf("peer"), 1).first())
            assertEquals(MessagePlace(3L, "m3"), dao.observeMentionWindowEdge("thread", me, setOf("other"), 1).first())
        }

    private fun msg(
        id: String,
        conversationId: String,
        sender: String,
        sentAt: Long,
    ) = MessageEntity(id = id, senderId = sender, recipientId = null, conversationId = conversationId, body = "", sentAt = sentAt)
}
