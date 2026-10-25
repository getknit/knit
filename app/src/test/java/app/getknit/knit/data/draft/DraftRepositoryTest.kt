@file:OptIn(ExperimentalCoroutinesApi::class) // StandardTestDispatcher / advanceTimeBy are experimental kotlinx APIs

package app.getknit.knit.data.draft

import app.getknit.knit.mesh.protocol.BodyStyle
import app.getknit.knit.mesh.protocol.StyleSpan
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Drives [DraftRepository] against a counting fake DAO on a virtual clock — plain JVM, no Room: what is
 * worth pinning here is the scheduling around three one-row statements (one write per typing pause, blank
 * deletes rather than stores, and a [DraftRepository.clear] that a write already in the queue cannot undo).
 * The hand-written upsert SQL itself runs for real in [DraftDaoTest].
 */
class DraftRepositoryTest {
    private class FakeDraftDao : DraftDao {
        private val stored = MutableStateFlow(emptyMap<String, DraftEntity>())
        var upserts = 0

        val rows: Map<String, String> get() = stored.value.mapValues { (_, row) -> row.text }

        fun stampOf(conversationId: String): Long? = stored.value[conversationId]?.updatedAt

        fun stylesOf(conversationId: String): String? = stored.value[conversationId]?.styles

        override suspend fun find(conversationId: String): DraftContent? =
            stored.value[conversationId]?.let { DraftContent(it.text, it.styles) }

        override fun observeAll(): Flow<List<DraftEntity>> = stored.map { it.values.toList() }

        override suspend fun upsert(
            conversationId: String,
            text: String,
            styles: String?,
            updatedAt: Long,
        ) {
            upserts++
            stored.value = stored.value + (conversationId to DraftEntity(conversationId, text, updatedAt, styles))
        }

        override suspend fun deleteFor(conversationId: String) {
            stored.value = stored.value - conversationId
        }
    }

    private val dao = FakeDraftDao()
    private var clock = 1_000L

    // The repository is given `backgroundScope` because in production its scope is the application's: it
    // outlives the screen that scheduled the write.
    private fun TestScope.repo() = DraftRepository(dao, backgroundScope, StandardTestDispatcher(testScheduler), DEBOUNCE_MS) { clock }

    /**
     * The user stops typing for longer than the debounce. `advanceTimeBy` and not `advanceUntilIdle`: the
     * latter runs only the *foreground* test coroutine's work, so it would never reach a write scheduled on
     * the background (application) scope — and every assertion below would read an empty table.
     */
    private fun TestScope.typingPause() {
        testScheduler.advanceTimeBy(DEBOUNCE_MS + 1)
        testScheduler.runCurrent()
    }

    @Test
    fun `a burst of typing writes once, and writes the last thing typed`() =
        runTest {
            val drafts = repo()

            "half a sentence".forEachIndexed { i, _ -> drafts.save(CHAT, Draft("half a sentence".take(i + 1))) }
            typingPause()

            assertEquals(1, dao.upserts)
            assertEquals("half a sentence", dao.rows[CHAT])
        }

    @Test
    fun `the write is still pending until the typing pause elapses`() =
        runTest {
            val drafts = repo()

            drafts.save(CHAT, Draft("hi"))
            testScheduler.advanceTimeBy(DEBOUNCE_MS - 1)
            assertNull("nothing written mid-word", dao.rows[CHAT])

            typingPause()
            assertEquals("hi", dao.rows[CHAT])
        }

    @Test
    fun `emptying the field deletes the row rather than storing nothing`() =
        runTest {
            val drafts = repo()

            drafts.save(CHAT, Draft("hi"))
            typingPause()
            drafts.save(CHAT, Draft("   "))
            typingPause()

            assertNull(dao.rows[CHAT])
        }

    @Test
    fun `clear drops a write that had not landed yet`() =
        runTest {
            val drafts = repo()

            drafts.save(CHAT, Draft("sent already"))
            drafts.clear(CHAT) // the send beat the typing pause
            typingPause()

            assertEquals("the pending write was cancelled, not just overwritten", 0, dao.upserts)
            assertNull(dao.rows[CHAT])
        }

    @Test
    fun `clear leaves other threads alone`() =
        runTest {
            val drafts = repo()

            drafts.save(CHAT, Draft("mine"))
            drafts.save(OTHER, Draft("theirs"))
            typingPause()
            drafts.clear(CHAT)
            typingPause()

            assertNull(dao.rows[CHAT])
            assertEquals("theirs", dao.rows[OTHER])
        }

    @Test
    fun `load reads back what was left, and empty where nothing was`() =
        runTest {
            val drafts = repo()

            drafts.save(CHAT, Draft("half a sentence"))
            typingPause()

            assertEquals(Draft("half a sentence"), drafts.load(CHAT))
            assertEquals(Draft.EMPTY, drafts.load(OTHER))
        }

    @Test
    fun `a draft's formatting is written beside its text and read back with it`() =
        runTest {
            val drafts = repo()
            val bold = listOf(StyleSpan(0, 4, BodyStyle.BOLD))

            drafts.save(CHAT, Draft("half a sentence", bold))
            typingPause()

            assertEquals("[[0,4,0]]", dao.stylesOf(CHAT))
            assertEquals(Draft("half a sentence", bold), drafts.load(CHAT))
        }

    @Test
    fun `a draft without formatting stores no styles`() =
        runTest {
            val drafts = repo()

            drafts.save(CHAT, Draft("plain"))
            typingPause()

            assertNull(dao.stylesOf(CHAT))
        }

    @Test
    fun `a write is stamped with the clock, and the stamp moves with the next edit`() =
        runTest {
            val drafts = repo()

            drafts.save(CHAT, Draft("hi"))
            typingPause()
            assertEquals(1_000L, dao.stampOf(CHAT))

            clock = 2_000L
            drafts.save(CHAT, Draft("hi again"))
            typingPause()
            assertEquals("the chat list compares this against the newest message", 2_000L, dao.stampOf(CHAT))
        }

    @Test
    fun `all emits the drafts keyed by conversation`() =
        runTest {
            val drafts = repo()

            drafts.save(CHAT, Draft("mine"))
            drafts.save(OTHER, Draft("theirs"))
            typingPause()

            val byConversation = drafts.all.first()
            assertEquals(setOf(CHAT, OTHER), byConversation.keys)
            assertEquals("mine", byConversation[CHAT]?.text)
        }

    private companion object {
        const val CHAT = "peer-1"
        const val OTHER = "peer-2"
        const val DEBOUNCE_MS = 500L
    }
}
