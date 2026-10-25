package app.getknit.knit.data.commons

import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.RoomDbTest
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.mesh.crypto.scope.ScopeCrypto
import app.getknit.knit.mesh.protocol.CommonsPost
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.ReactionContent
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.spool.hex
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The commons tables through the real SQL: a room and everything hanging off it appear and vanish as one. */
class CommonsRepositoryTest : RoomDbTest() {
    private val secret = ByteArray(32) { (it + 1).toByte() }
    private val messages by lazy { MessageRepository(db.messageDao()) }

    private fun repo() = CommonsRepository(db.commonsDao(), messages, db)

    private fun signed(id: String): ByteArray =
        WireCodec.encodeEnvelope(RelayEnvelope(type = FrameType.COMMONS, id = id, senderId = "me", sentAt = 5L, payload = ByteArray(0)))

    private fun row(
        id: String,
        conversationId: String,
    ) = MessageEntity(id = id, senderId = "me", conversationId = conversationId, body = "hi", sentAt = 5L)

    @Test
    fun joinIsKeyedOnTheSecretAndIdempotent() =
        runTest {
            val repo = repo()
            val id = repo.join("wss://a/spool/v1", secret, name = "Home", now = 1L)
            assertEquals(Conversations.COMMONS_PREFIX + hex(ScopeCrypto.commonsScopeId(secret)), id)
            assertEquals(id, repo.join("wss://a/spool/v1", secret, name = "Home", now = 2L))
            assertEquals(1, repo.observeAll().first().size)
            assertEquals("Home", repo.find(id)?.name)
            assertEquals(listOf(id), repo.roots().map { it.conversationId })
        }

    @Test
    fun leaveTakesTheRoomItsOutboxItsMembersAndItsMessagesTogether() =
        runTest {
            val repo = repo()
            val id = repo.join("wss://a/spool/v1", secret, null, now = 1L)
            repo.post(row("m1", id), sig = ByteArray(64), signed = signed("m1"))
            repo.recordMember(id, "peer", now = 3L)
            messages.save(row("m2", id).copy(senderId = "peer"))
            assertEquals(1, repo.frames(id).size)
            assertEquals(listOf("peer"), repo.members(id))

            repo.leave(id)

            assertNull(repo.find(id))
            assertTrue(repo.frames(id).isEmpty())
            assertTrue(repo.members(id).isEmpty())
            assertTrue(repo.allMembers().isEmpty())
            assertFalse(db.messageDao().exists("m1"))
            assertFalse(db.messageDao().exists("m2"))
        }

    @Test
    fun aRoomOnTheRenamedRelayIsReboundAndTheLabUnderItsOldNameIsNot() =
        runTest {
            // ADR 2026-10.qpja: lax is public-1; lab.lax is another relay.
            val repo = repo()
            val house = repo.join("wss://lax.spool.getknit.app/spool/v1?k=t", secret, null, now = 1L)
            val lab = repo.join("wss://lab.lax.spool.getknit.app/spool/v1", ByteArray(32) { (it + 9).toByte() }, null, now = 1L)
            repeat(2) { repo.renameRelays() }
            assertEquals("wss://public-1.spool.getknit.app/spool/v1?k=t", repo.find(house)?.spoolUrl)
            assertEquals("wss://lab.lax.spool.getknit.app/spool/v1", repo.find(lab)?.spoolUrl)
        }

    @Test
    fun leaveBoundToTakesEveryRoomOnThatRelayAndNoOther() =
        runTest {
            val repo = repo()
            val a = repo.join("wss://a/spool/v1", secret, null, now = 1L)
            val b = repo.join("wss://b/spool/v1", ByteArray(32) { (it + 9).toByte() }, null, now = 1L)
            repo.leaveBoundTo("wss://a/spool/v1")
            assertNull(repo.find(a))
            assertEquals(b, repo.find(b)?.conversationId)
        }

    @Test
    fun twoRoomsOnOneRelayAreTwoRowsAndLeavingOneKeepsTheOther() =
        runTest {
            // Spec §7.4: one spool may run several rooms, each from its own invite (C-7.4-18).
            val repo = repo()
            val home = repo.join("wss://a/spool/v1", secret, "Home", now = 1L)
            val team = repo.join("wss://a/spool/v1", ByteArray(32) { (it + 9).toByte() }, "Team", now = 2L)
            messages.save(row("m1", home))
            assertEquals(listOf(home, team), repo.observeAll().first().map { it.conversationId })

            repo.leave(team)

            assertEquals("Home", repo.find(home)?.name)
            assertTrue(db.messageDao().exists("m1"))
            assertNull(repo.find(team))
            repo.leaveBoundTo("wss://a/spool/v1")
            assertNull(repo.find(home))
        }

    private fun reaction(
        id: String,
        messageId: String,
        emoji: String?,
        sentAt: Long,
    ): ByteArray =
        WireCodec.encodeEnvelope(
            RelayEnvelope(
                type = FrameType.COMMONS,
                id = id,
                senderId = "me",
                sentAt = sentAt,
                payload =
                    WireCodec.encodePayload(
                        CommonsPost(scope = ScopeCrypto.commonsScopeId(secret), reaction = ReactionContent(messageId, emoji)),
                    ),
            ),
        )

    @Test
    fun aReactionReplacesOurEarlierOneToTheSamePostInTheOutboxAndNothingElse() =
        runTest {
            val repo = repo()
            val id = repo.join("wss://a/spool/v1", secret, null, now = 1L)
            repo.post(row("m1", id), sig = ByteArray(64), signed = signed("m1"))
            repo.postReaction(id, "m1", "r1", 6L, ByteArray(64), reaction("r1", "m1", "👍", 6L))
            repo.postReaction(id, "m2", "r2", 7L, ByteArray(64), reaction("r2", "m2", "🎉", 7L))

            // A replace, then a retraction: each leaves only the newest reaction to m1 to push.
            repo.postReaction(id, "m1", "r3", 8L, ByteArray(64), reaction("r3", "m1", "❤️", 8L))
            assertEquals(setOf("m1", "r2", "r3"), repo.frames(id).map { it.envelope.id }.toSet())
            repo.postReaction(id, "m1", "r4", 9L, ByteArray(64), reaction("r4", "m1", null, 9L))
            assertEquals(setOf("m1", "r2", "r4"), repo.frames(id).map { it.envelope.id }.toSet())
            // No message row for a reaction: the reactions table holds what it shows.
            assertFalse(db.messageDao().exists("r4"))
        }

    @Test
    fun framesSkipAnOutboxRowWhoseBytesNoLongerDecode() =
        runTest {
            val repo = repo()
            val id = repo.join("wss://a/spool/v1", secret, null, now = 1L)
            repo.post(row("good", id), sig = ByteArray(64), signed = signed("good"))
            db.commonsDao().putOutbox(CommonsOutboxEntity("bad", id, sig = ByteArray(64), signed = byteArrayOf(1, 2, 3), sentAt = 5L))
            assertEquals(listOf("good"), repo.frames(id).map { it.envelope.id })
        }

    @Test
    fun aMemberIsOneRowPerNodeStampedWithTheLatestSighting() =
        runTest {
            val repo = repo()
            val id = repo.join("wss://a/spool/v1", secret, null, now = 1L)
            repo.recordMember(id, "peer", now = 3L)
            repo.recordMember(id, "peer", now = 4L)
            assertEquals(listOf(4L), repo.allMembers().map { it.seenAt })
            assertEquals(listOf("peer"), repo.observeMemberIds(id).first())
        }

    /** The first-sighting test a member's accept keys on (ADR 2026-09.adgd): per room, and gone with a leave. */
    @Test
    fun isMemberHoldsFromTheFirstSightingUntilTheRoomIsLeft() =
        runTest {
            val repo = repo()
            val id = repo.join("wss://a/spool/v1", secret, null, now = 1L)
            assertFalse(repo.isMember(id, "peer"))
            repo.recordMember(id, "peer", now = 3L)
            assertTrue(repo.isMember(id, "peer"))
            assertFalse("another node", repo.isMember(id, "other"))
            assertFalse("another room", repo.isMember(Conversations.COMMONS_PREFIX + "00", "peer"))

            repo.leave(id)
            assertFalse(repo.isMember(id, "peer"))
        }

    @Test
    fun theOutboxSweepDropsOnlyWhatTheSpoolHasLongExpired() =
        runTest {
            val repo = repo()
            val id = repo.join("wss://a/spool/v1", secret, null, now = 1L)
            repo.post(row("old", id).copy(sentAt = 1L), sig = ByteArray(64), signed = signed("old"))
            repo.post(row("new", id).copy(sentAt = 10L), sig = ByteArray(64), signed = signed("new"))
            repo.sweepOutbox(before = 5L)
            assertEquals(listOf("new"), repo.frames(id).map { it.envelope.id })
        }
}
