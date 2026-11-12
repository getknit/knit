package app.getknit.knit.data

import app.getknit.knit.data.changes.DataChange
import app.getknit.knit.data.changes.DataChangeSink
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.group.GroupMembersStore
import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.message.StatusNotices
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.data.ratchet.GroupRatchetRepository
import app.getknit.knit.data.reaction.ReactionEntity
import app.getknit.knit.data.settings.InboundSettings
import app.getknit.knit.identity.IdentitySource
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The change seam (`data/changes`): each repository write reports the row it touched, once, after the write, and a
 * write that changed nothing reports nothing. Real Room, so a count the hooks branch on is SQLite's own.
 */
class RepositoryChangesTest : RoomDbTest() {
    private val seen = mutableListOf<DataChange>()
    private val sink = DataChangeSink { seen += it }

    private val messages by lazy { MessageRepository(db.messageDao(), nearbyMaxMessages = 2, changes = sink) }

    private fun msg(
        id: String,
        conversation: String = PEER,
        sentAt: Long = 1L,
        sender: String = PEER,
    ) = MessageEntity(id = id, senderId = sender, conversationId = conversation, body = "", sentAt = sentAt, received = false)

    @Test
    fun aMessageWriteReportsItsRowAndAReReceivedRowReportsNothing() =
        runTest {
            messages.save(msg("m1"))
            messages.saveIfAbsent(msg("m2"))
            messages.saveIfAbsent(msg("m2"))
            messages.markReceived("m1", DeliveryPlane.Bluetooth)
            messages.clearPending("m1")
            assertEquals(
                listOf(
                    DataChange.Message("m1", PEER),
                    DataChange.Message("m2", PEER),
                    DataChange.Message("m1", null),
                    DataChange.Message("m1", null),
                ),
                seen,
            )
        }

    @Test
    fun aDeleteNamesTheThreadItLeftAndAClearedThreadIsTrimmed() =
        runTest {
            messages.save(msg("m1"))
            seen.clear()
            messages.delete("m1")
            messages.deleteByConversation(PEER)
            assertEquals(listOf(DataChange.Message("m1", PEER), DataChange.ThreadTrimmed(PEER)), seen)
        }

    @Test
    fun theSweepReportsOnlyTheThreadsItTrimmed() =
        runTest {
            (1..3).forEach { messages.save(msg("n$it", conversation = NEARBY, sentAt = NOW - it, sender = "s$it")) }
            messages.save(msg("p1", conversation = PEER, sentAt = NOW))
            seen.clear()
            messages.sweepRetention(NOW, protected = setOf(PEER), knownSenders = setOf("s1", "s2", "s3"))
            assertEquals("the room over its cap of 2, not the protected thread", listOf(DataChange.ThreadTrimmed(NEARBY)), seen)
            seen.clear()
            messages.sweepRetention(NOW, protected = setOf(PEER), knownSenders = setOf("s1", "s2", "s3"))
            assertEquals("a sweep that deletes nothing says nothing", emptyList<DataChange>(), seen)
        }

    @Test
    fun aReactionReportsItsMessageOnlyWhenWhatItShowsChanged() =
        runTest {
            val reactions = ReactionRepository(db.reactionDao(), db, changes = sink)
            reactions.apply(ReactionEntity("m1", "r", "👍", 2L))
            reactions.apply(ReactionEntity("m1", "r", "👍", 3L)) // newer, same emoji
            reactions.apply(ReactionEntity("m1", "r", "❤️", 1L)) // stale
            reactions.apply(ReactionEntity("m1", "r", null, 4L)) // retracted
            assertEquals(listOf(DataChange.Message("m1", null), DataChange.Message("m1", null)), seen)
        }

    @Test
    fun aGroupWriteReportsItsThread() =
        runTest {
            val groups =
                GroupRepository(db.groupDao(), messages, db, GroupRatchetRepository(db.groupRatchetDao(), clock = { 1L }), changes = sink)
            val members = GroupMembersStore.encode(listOf("me", PEER, "c"))
            val group = GroupEntity(groupId = GROUP, name = "", members = members, createdBy = "me", createdAt = 1L)
            groups.upsert(group)
            groups.recordDeparture(GROUP, "nobody", 5L) // not a member: nothing recorded
            groups.recordDeparture(GROUP, PEER, 5L)
            groups.leave(GROUP)
            assertEquals(
                listOf(
                    DataChange.Conversation(GROUP),
                    DataChange.Message(StatusNotices.memberLeft(GROUP, PEER, 5L).id, GROUP), // the "left" notice, in the transaction
                    DataChange.Conversation(GROUP),
                    DataChange.ThreadTrimmed(GROUP),
                    DataChange.Conversation(GROUP),
                ),
                seen,
            )
        }

    @Test
    fun aPeerWriteReportsThatPeerAndASweepReportsThemAll() =
        runTest {
            val profile = mockk<InboundSettings>(relaxed = true)
            every { profile.displayName } returns flowOf("")
            val identity = mockk<IdentitySource>(relaxed = true)
            coEvery { identity.nodeId() } returns "me"
            val peers = PeerRepository(db.peerDao(), profile, identity, maxPeers = 1, changes = sink)
            peers.upsert(PeerEntity(nodeId = "a", lastSeenAt = 1L))
            peers.setVerified("a", true)
            peers.upsert(PeerEntity(nodeId = "b", lastSeenAt = 2L))
            peers.upsert(PeerEntity(nodeId = "c", lastSeenAt = 3L))
            seen.clear()
            peers.sweepCap(protected = emptySet())
            assertEquals(listOf<DataChange>(DataChange.Peers), seen)
            seen.clear()
            peers.sweepCap(protected = emptySet())
            assertEquals("nothing over the cap, nothing said", emptyList<DataChange>(), seen)
        }

    private companion object {
        const val PEER = "peer-node"
        const val NEARBY = "nearby"
        const val GROUP = "g-test"
        const val NOW = 1_000_000L
    }
}
