package app.getknit.knit

import app.getknit.knit.data.ReactionRepository
import app.getknit.knit.data.RoomDbTest
import app.getknit.knit.data.group.GroupMembersStore
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.reaction.ReactionEntity
import app.getknit.knit.ui.group
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives [ReactionRepository] against a **real** in-memory Room DB (finding #5 — the last-writer-wins guard and the
 * orphan anti-join were previously verified only against a hand-mirrored `FakeReactionDao`). [ReactionRepository.apply]
 * now runs its read-compare-upsert in a transaction so a local reaction tap can't race the inbound echo of the same
 * reaction and clobber a newer write; the LWW behaviour asserted here is unchanged, and now executes over the real SQL.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReactionRepositoryTest : RoomDbTest() {
    private fun repo() = ReactionRepository(db.reactionDao(), db)

    private fun reaction(
        reactor: String,
        emoji: String?,
        at: Long,
    ) = ReactionEntity(messageId = "m1", reactorNodeId = reactor, emoji = emoji, updatedAt = at)

    @Test
    fun newerWinsAndStalerLoses() =
        runTest {
            val repo = repo()

            repo.apply(reaction("alice", "👍", at = 100))
            repo.apply(reaction("alice", "❤️", at = 50)) // older — ignored
            assertEquals("👍", repo.currentEmoji("m1", "alice"))

            repo.apply(reaction("alice", "❤️", at = 150)) // newer — replaces
            assertEquals("❤️", repo.currentEmoji("m1", "alice"))
        }

    @Test
    fun retractThenStaleAddStaysRetracted() =
        runTest {
            val repo = repo()

            repo.apply(reaction("alice", "👍", at = 100))
            repo.apply(reaction("alice", null, at = 200)) // retract
            assertNull(repo.currentEmoji("m1", "alice"))

            repo.apply(reaction("alice", "👍", at = 120)) // a late, older "add" must not resurrect it
            assertNull(repo.currentEmoji("m1", "alice"))
        }

    // `apply`'s answer is what a reaction notifies on (ADR 2026-10.59fu): only a write that changed what the reactor
    // shows. Each false here is a re-delivery that must not notify twice.
    @Test
    fun applyReportsOnlyAChangeOfTheVisibleReaction() =
        runTest {
            val repo = repo()

            assertTrue("a first reaction", repo.apply(reaction("alice", "👍", at = 100)))
            assertFalse("the same frame again", repo.apply(reaction("alice", "👍", at = 100)))
            assertFalse("a stale replace", repo.apply(reaction("alice", "❤️", at = 50)))
            assertFalse("a newer stamp, the same emoji", repo.apply(reaction("alice", "👍", at = 120)))
            assertTrue("a replace", repo.apply(reaction("alice", "❤️", at = 150)))
            assertTrue("a retraction", repo.apply(reaction("alice", null, at = 200)))
            assertFalse("a second retraction", repo.apply(reaction("alice", null, at = 250)))
            assertFalse("a retraction of nothing", repo.apply(reaction("bob", null, at = 300)))
            assertTrue("an add after a retraction", repo.apply(reaction("alice", "😂", at = 400)))
        }

    @Test
    fun theNewestReactionOnOwnMessagesBreaksASameInstantTieById() =
        runTest {
            val repo = repo()
            db.groupDao().upsert(group(groupId = "g-1", members = listOf(ME, "bob", "carol")))
            listOf("m-a", "m-b").forEach {
                db.messageDao().upsert(MessageEntity(id = it, senderId = ME, conversationId = "g-1", body = it, sentAt = 1L))
            }
            repo.apply(ReactionEntity("m-a", "bob", "👍", updatedAt = 10L))
            repo.apply(ReactionEntity("m-b", "bob", "❤️", updatedAt = 10L))
            repo.apply(ReactionEntity("m-a", "carol", "😂", updatedAt = 10L))

            val head = repo.observeNewestOnOwn(ME, emptyList()).first().getValue("g-1")

            assertEquals("m-b", head.target.id)
            assertEquals("bob", head.reactorNodeId)
            assertEquals("❤️", head.emoji)
        }

    /**
     * #301: a thread's reactions are drawn only from someone in it — us, the DM's peer, a group's members and departed,
     * anyone in a room — so a reaction stored before its target landed, by someone outside the thread, never shows.
     */
    @Test
    fun aThreadsReactionsAreOnlyThoseOfSomeoneInIt() =
        runTest {
            val repo = repo()
            db.groupDao().upsert(
                group(groupId = "g-1", members = listOf(ME, "bob")).copy(departed = GroupMembersStore.encode(listOf("dan"))),
            )
            db.messageDao().upsert(
                MessageEntity(id = "dm", senderId = ME, recipientId = "alice", conversationId = "alice", body = "dm", sentAt = 1L),
            )
            db.messageDao().upsert(MessageEntity(id = "gm", senderId = ME, conversationId = "g-1", body = "gm", sentAt = 1L))
            db.messageDao().upsert(MessageEntity(id = "room", senderId = "carol", body = "room", sentAt = 1L))
            listOf("dm", "gm", "room").forEach { id ->
                listOf(ME, "alice", "bob", "dan", "mallory").forEachIndexed { i, who ->
                    repo.apply(
                        ReactionEntity(
                            id,
                            who,
                            "👍",
                            updatedAt =
                                i + 1L,
                        ),
                    )
                }
            }

            fun reactors(conversationId: String) =
                suspend {
                    repo
                        .observeReactionsIn(conversationId, ME)
                        .first()
                        .map { it.messageId to it.reactorNodeId }
                        .toSet()
                }

            assertEquals(setOf("dm" to ME, "dm" to "alice"), reactors("alice")())
            assertEquals(setOf("gm" to ME, "gm" to "bob", "gm" to "dan"), reactors("g-1")())
            assertEquals(
                listOf(ME, "alice", "bob", "dan", "mallory").map { "room" to it }.toSet(),
                reactors(Conversations.NEARBY)(),
            )
        }

    /**
     * #301's room arms: a commons (matched by [Conversations.COMMONS_PREFIX]) and the Meshtastic room let anyone react,
     * in the chat and on the details screen alike. The DM beside them is the control: the same reactor, not its peer,
     * stays out of it.
     */
    @Test
    fun aCommonsPostShowsAnyonesReactionABridgedRoomPostOnlyOursAndADmItsPeers() =
        runTest {
            val repo = repo()
            val commons = Conversations.commonsIdFor("5c09e1")
            db.messageDao().upsert(MessageEntity(id = "commons", senderId = "carol", conversationId = commons, body = "c", sentAt = 1L))
            db.messageDao().upsert(
                MessageEntity(id = "mesh", senderId = ME, conversationId = Conversations.MESHTASTIC, body = "m", sentAt = 1L),
            )
            db.messageDao().upsert(
                MessageEntity(id = "core", senderId = ME, conversationId = Conversations.MESHCORE_PUBLIC, body = "m", sentAt = 1L),
            )
            db.messageDao().upsert(
                MessageEntity(id = "dm", senderId = ME, recipientId = "alice", conversationId = "alice", body = "dm", sentAt = 1L),
            )
            listOf("commons", "mesh", "core", "dm").forEach { id ->
                listOf(ME, "alice", "zed").forEachIndexed { i, who -> repo.apply(ReactionEntity(id, who, "👍", updatedAt = i + 1L)) }
            }

            suspend fun inThread(conversationId: String) =
                repo
                    .observeReactionsIn(conversationId, ME)
                    .first()
                    .map { it.reactorNodeId }
                    .toSet()

            suspend fun onMessage(id: String) =
                repo
                    .observeReactionsFor(id, ME)
                    .first()
                    .map { it.reactorNodeId }
                    .toSet()

            val everyone = setOf(ME, "alice", "zed")
            assertEquals("the commons thread", everyone, inThread(commons))
            assertEquals("the commons post's details", everyone, onMessage("commons"))
            // A bridged board room takes no tapback (ADR 2026-10.xhfr) and its rows' ids are public: nobody else's reaction
            // shows there (ADR 2026-11.gsqu's Stage 6 amendment, security review round 1).
            assertEquals("the Meshtastic thread", setOf(ME), inThread(Conversations.MESHTASTIC))
            assertEquals("the Meshtastic post's details", setOf(ME), onMessage("mesh"))
            assertEquals("the MeshCore thread", setOf(ME), inThread(Conversations.MESHCORE_PUBLIC))
            assertEquals("the MeshCore post's details", setOf(ME), onMessage("core"))
            assertEquals("the DM thread", setOf(ME, "alice"), inThread("alice"))
            assertEquals("the DM's details", setOf(ME, "alice"), onMessage("dm"))
        }

    @Test
    fun observeExcludesRetractedReactions() =
        runTest {
            val repo = repo()

            repo.apply(reaction("alice", "👍", at = 10))
            repo.apply(reaction("bob", "👍", at = 20))
            repo.apply(reaction("bob", null, at = 30)) // bob retracts

            val live = repo.observeReactions().first()
            assertEquals(1, live.size)
            assertEquals("alice", live.single().reactorNodeId)
        }

    @Test
    fun deleteOrphansReapsOldOrphansOnly() =
        runTest {
            val repo = repo()
            val day = 24L * 60 * 60 * 1000
            val now = 10 * day
            // The real deleteOrphansOlderThan anti-joins `messages`, so a present message keeps its reaction.
            db.messageDao().upsert(MessageEntity(id = "kept-msg", senderId = "s", body = "", sentAt = 1L))

            // Old orphan (no message) -> reaped; recent orphan within the grace window -> kept; an old
            // reaction whose message still exists -> kept regardless of age.
            repo.apply(ReactionEntity("old-orphan", "alice", "👍", updatedAt = now - 2 * day))
            repo.apply(ReactionEntity("fresh-orphan", "alice", "👍", updatedAt = now - 1000))
            repo.apply(ReactionEntity("kept-msg", "alice", "👍", updatedAt = now - 5 * day))

            repo.deleteOrphans(now, ME)

            val live =
                repo
                    .observeReactions()
                    .first()
                    .map { it.messageId }
                    .toSet()
            assertEquals(setOf("fresh-orphan", "kept-msg"), live)
        }

    private companion object {
        const val ME = "me-node"
    }
}
