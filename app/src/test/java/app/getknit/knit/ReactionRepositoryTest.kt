package app.getknit.knit

import app.getknit.knit.data.ReactionRepository
import app.getknit.knit.data.RoomDbTest
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.reaction.ReactionEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

            repo.deleteOrphans(now)

            val live =
                repo
                    .observeReactions()
                    .first()
                    .map { it.messageId }
                    .toSet()
            assertEquals(setOf("fresh-orphan", "kept-msg"), live)
        }
}
