package app.getknit.knit

import app.getknit.knit.data.ReactionRepository
import app.getknit.knit.data.reaction.ReactionDao
import app.getknit.knit.data.reaction.ReactionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReactionRepositoryTest {

    /** In-memory [ReactionDao] mirroring the real query semantics (observeAll hides tombstones). */
    private class FakeReactionDao : ReactionDao {
        private val rows = mutableMapOf<Pair<String, String>, ReactionEntity>()
        private val live = MutableStateFlow<List<ReactionEntity>>(emptyList())

        /** Message ids that count as "stored"; a reaction whose messageId is absent is an orphan. */
        var storedMessageIds: Set<String> = emptySet()

        override fun observeAll(): Flow<List<ReactionEntity>> = live

        override suspend fun updatedAtFor(messageId: String, reactorNodeId: String): Long? =
            rows[messageId to reactorNodeId]?.updatedAt

        override suspend fun emojiFor(messageId: String, reactorNodeId: String): String? =
            rows[messageId to reactorNodeId]?.emoji

        override suspend fun upsert(reaction: ReactionEntity) {
            rows[reaction.messageId to reaction.reactorNodeId] = reaction
            live.value = rows.values.filter { it.emoji != null }.sortedBy { it.updatedAt }
        }

        override suspend fun deleteForMessage(messageId: String) {
            rows.keys.filter { it.first == messageId }.forEach { rows.remove(it) }
            live.value = rows.values.filter { it.emoji != null }.sortedBy { it.updatedAt }
        }

        override suspend fun deleteOrphansOlderThan(olderThan: Long) {
            rows.entries
                .filter { (key, value) -> value.updatedAt < olderThan && key.first !in storedMessageIds }
                .map { it.key }
                .forEach { rows.remove(it) }
            live.value = rows.values.filter { it.emoji != null }.sortedBy { it.updatedAt }
        }
    }

    private fun reaction(reactor: String, emoji: String?, at: Long) =
        ReactionEntity(messageId = "m1", reactorNodeId = reactor, emoji = emoji, updatedAt = at)

    @Test
    fun newerWinsAndStalerLoses() = runTest {
        val repo = ReactionRepository(FakeReactionDao())

        repo.apply(reaction("alice", "👍", at = 100))
        repo.apply(reaction("alice", "❤️", at = 50)) // older — ignored
        assertEquals("👍", repo.currentEmoji("m1", "alice"))

        repo.apply(reaction("alice", "❤️", at = 150)) // newer — replaces
        assertEquals("❤️", repo.currentEmoji("m1", "alice"))
    }

    @Test
    fun retractThenStaleAddStaysRetracted() = runTest {
        val repo = ReactionRepository(FakeReactionDao())

        repo.apply(reaction("alice", "👍", at = 100))
        repo.apply(reaction("alice", null, at = 200)) // retract
        assertNull(repo.currentEmoji("m1", "alice"))

        repo.apply(reaction("alice", "👍", at = 120)) // a late, older "add" must not resurrect it
        assertNull(repo.currentEmoji("m1", "alice"))
    }

    @Test
    fun observeExcludesRetractedReactions() = runTest {
        val repo = ReactionRepository(FakeReactionDao())

        repo.apply(reaction("alice", "👍", at = 10))
        repo.apply(reaction("bob", "👍", at = 20))
        repo.apply(reaction("bob", null, at = 30)) // bob retracts

        val live = repo.observeReactions().first()
        assertEquals(1, live.size)
        assertEquals("alice", live.single().reactorNodeId)
    }

    @Test
    fun deleteOrphansReapsOldOrphansOnly() = runTest {
        val dao = FakeReactionDao()
        val repo = ReactionRepository(dao)
        val day = 24L * 60 * 60 * 1000
        val now = 10 * day
        dao.storedMessageIds = setOf("kept-msg")

        // Old orphan (no message) -> reaped; recent orphan within the grace window -> kept; an old
        // reaction whose message still exists -> kept regardless of age.
        repo.apply(ReactionEntity("old-orphan", "alice", "👍", updatedAt = now - 2 * day))
        repo.apply(ReactionEntity("fresh-orphan", "alice", "👍", updatedAt = now - 1000))
        repo.apply(ReactionEntity("kept-msg", "alice", "👍", updatedAt = now - 5 * day))

        repo.deleteOrphans(now)

        val live = repo.observeReactions().first().map { it.messageId }.toSet()
        assertEquals(setOf("fresh-orphan", "kept-msg"), live)
    }
}
