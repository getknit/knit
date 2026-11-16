package app.getknit.knit.data.receipt

import app.getknit.knit.data.RoomDbTest
import app.getknit.knit.data.message.MessageEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Executes the **real** [ReadMarkDao] SQL behind the reader's outbox of read receipts (ADR 2026-11.z6jw): what
 * counts as owed, what a root change owes again, and what the prune keeps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReadMarkRepositoryTest : RoomDbTest() {
    private val repo get() = ReadMarkRepository(db.readMarkDao(), db)

    private suspend fun seed(vararg ids: String) =
        ids.forEach { db.messageDao().upsert(MessageEntity(id = it, senderId = "ann", conversationId = "ann", body = "", sentAt = 1L)) }

    private fun mark(
        id: String,
        readAt: Long,
        author: String = "ann",
    ) = ReadMarkEntity(messageId = id, authorId = author, conversationId = author, readAt = readAt)

    @Test
    fun `a mark keeps the first read, and owed is untold`() =
        runTest {
            seed("m1", "m2")
            repo.mark(listOf(mark("m1", 10L), mark("m2", 5L)))
            repo.mark(listOf(mark("m1", 99L)))

            assertEquals(listOf("m2", "m1"), repo.owedFor("ann", limit = 10))
            assertEquals(listOf("m2"), repo.owedFor("ann", limit = 1))
            assertEquals(setOf("ann"), repo.owedAuthors())

            repo.markTold(listOf("m2"), at = 20L, rootTag = 7L)

            assertEquals(listOf("m1"), repo.owedFor("ann", limit = 10))
            assertEquals(setOf("m1"), repo.owedAmong(listOf("m1", "m2", "unknown")))
            // A told read still sets its bit on a later receipt toward the author: readAmong counts it.
            assertEquals(setOf("m1", "m2"), repo.readAmong(listOf("m1", "m2", "unknown")))
        }

    @Test
    fun `a root change owes again only what was told under another root and read since the cutoff`() =
        runTest {
            seed("a", "b", "c", "d")
            repo.mark(listOf(mark("a", 10L), mark("b", 10L), mark("c", 1L), mark("d", 10L, author = "sam")))
            repo.markTold(listOf("a"), at = 11L, rootTag = 1L)
            repo.markTold(listOf("b"), at = 11L, rootTag = 2L)
            repo.markTold(listOf("c"), at = 11L, rootTag = 1L)
            repo.markTold(listOf("d"), at = 11L, rootTag = 1L)

            val back = repo.reOweNotUnder("ann", liveRoot = 2L, since = 5L)

            // a: other root, recent → owed again. b: told under the live root. c: read before the cutoff. d: another author.
            assertEquals(1, back)
            assertEquals(listOf("a"), repo.owedFor("ann", limit = 10))
            assertEquals(emptyList<String>(), repo.owedFor("sam", limit = 10))
        }

    @Test
    fun `turning the setting off forgets owed reads and keeps told ones`() =
        runTest {
            seed("o", "t")
            repo.mark(listOf(mark("o", 10L), mark("t", 10L)))
            repo.markTold(listOf("t"), at = 11L, rootTag = 1L)

            repo.dropOwed()

            assertEquals(emptySet<String>(), repo.owedAuthors())
            assertEquals(setOf("t"), repo.readAmong(listOf("o", "t")))
        }

    @Test
    fun `blocking an author forgets only the reads still owed to them`() =
        runTest {
            seed("a1", "a2", "s1")
            repo.mark(listOf(mark("a1", 10L), mark("a2", 10L), mark("s1", 10L, author = "sam")))
            repo.markTold(listOf("a2"), at = 11L, rootTag = 1L)

            repo.dropOwedFor("ann")

            assertEquals(setOf("sam"), repo.owedAuthors())
            assertEquals(setOf("a2", "s1"), repo.readAmong(listOf("a1", "a2", "s1")))
        }

    @Test
    fun `prune drops old owed and told marks on their own clocks, and orphans`() =
        runTest {
            seed("owedOld", "owedNew", "toldOld", "toldNew")
            repo.mark(
                listOf(
                    mark("owedOld", 10L),
                    mark("owedNew", 100L),
                    mark("toldOld", 10L),
                    mark("toldNew", 60L),
                    mark("orphan", 100L),
                ),
            )
            repo.markTold(listOf("toldOld", "toldNew"), at = 101L, rootTag = 1L)

            repo.prune(owedBefore = 50L, toldBefore = 30L)

            assertEquals(setOf("owedNew", "toldNew"), repo.readAmong(listOf("owedOld", "owedNew", "toldOld", "toldNew", "orphan")))
        }
}
