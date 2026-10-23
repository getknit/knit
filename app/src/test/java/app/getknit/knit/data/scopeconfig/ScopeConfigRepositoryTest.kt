package app.getknit.knit.data.scopeconfig

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import app.getknit.knit.data.KnitDatabase
import app.getknit.knit.data.RoomDbTest
import app.getknit.knit.mesh.spool.ScopeConfig
import app.getknit.knit.mesh.spool.ScopeConfigPolicy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The scope-config rows against the real SQL: per (conversation, issuer, version), the winner read across them. */
class ScopeConfigRepositoryTest : RoomDbTest() {
    private val repo by lazy { ScopeConfigRepository(db) }

    private companion object {
        /** 2026-10-23T12:00Z. */
        const val NOW = 1_792_756_800_000L
    }

    private fun config(
        issuer: String,
        version: Int,
        conversationId: String = "peer-1",
        spools: List<String> = listOf("wss://$issuer.example/spool/v1?k=t"),
    ) = ScopeConfig(conversationId, null, issuer, spools, maxFrames = 400, ttlMs = 1_000L, maxBlob = 2_048, version = version)

    @Test
    fun `each issuer keeps its newest row and the conversation reads the highest version and issuer`() =
        runTest {
            assertNull(repo.winning("peer-1"))
            assertEquals(ScopeConfigPolicy.Decision.STORE, repo.offer(config("me", 1), receivedAt = 10L))
            assertEquals(ScopeConfigPolicy.Decision.STORE, repo.offer(config("peer-1", 2), receivedAt = 11L))
            assertEquals(config("peer-1", 2), repo.winning("peer-1"))
            // An older config of the peer's, re-served, changes nothing; the same one again is kept as well.
            assertEquals(ScopeConfigPolicy.Decision.KEEP, repo.offer(config("peer-1", 1), receivedAt = 12L))
            assertEquals(ScopeConfigPolicy.Decision.KEEP, repo.offer(config("peer-1", 2), receivedAt = 13L))
            // Our own catch-up to the same version wins the tie only if our id sorts higher ("me" < "peer-1").
            assertEquals(ScopeConfigPolicy.Decision.STORE, repo.offer(config("me", 2), receivedAt = 14L))
            assertEquals("peer-1", repo.winning("peer-1")?.issuer)
            assertEquals(ScopeConfigPolicy.Decision.STORE, repo.offer(config("me", 3), receivedAt = 15L))
            assertEquals(config("me", 3), repo.winning("peer-1"))
            assertEquals(config("me", 3), repo.observeWinning("peer-1").first())
            // Conversations are kept apart.
            assertNull(repo.winning("peer-2"))
            assertEquals(setOf("peer-1"), repo.observeWinners().first().keys)
        }

    @Test
    fun `a reservation writes our own row first and a failed send gives back exactly what it replaced`() =
        runTest {
            assertEquals("nothing of ours yet", emptyList<ScopeConfig>(), checkNotNull(repo.reserve(config("me", 1), at = 1L)).replaced)
            repo.offer(config("peer-1", 4), receivedAt = 2L)
            val second = checkNotNull(repo.reserve(config("me", 5), at = 3L))
            assertEquals(listOf(config("me", 1)), second.replaced)
            assertEquals("the reserved row is read at once", config("me", 5), repo.winning("peer-1"))
            assertNull("not news: nothing is reserved", repo.reserve(config("me", 5), at = 4L))
            repo.release(second)
            assertEquals(config("peer-1", 4), repo.winning("peer-1"))
            assertEquals("our earlier row is back", 1, ourVersion())
            // A release never undoes a later reservation of ours.
            val third = checkNotNull(repo.reserve(config("me", 6), at = 5L))
            val fourth = checkNotNull(repo.reserve(config("me", 7), at = 6L))
            repo.release(third)
            assertEquals(config("me", 7), repo.winning("peer-1"))
            repo.release(fourth)
            assertEquals(6, ourVersion())
        }

    private suspend fun ourVersion(): Int? =
        db
            .scopeConfigDao()
            .configsOf("peer-1")
            .firstOrNull { it.issuer == "me" }
            ?.version

    /**
     * A version ahead of this phone's clock is held, not refused, and not read until the clock reaches it; the issuer's
     * readable row stays the winner meanwhile, and the deferred row survives a restart (a fresh repository over the
     * same rows) because it is a row.
     */
    @Test
    fun `a version ahead of the clock is held beside the readable one and read once the clock reaches it`() =
        runTest {
            var now = NOW
            val clocked = ScopeConfigRepository(db) { now }
            val minute = ScopeConfigPolicy.minutesOf(NOW)
            assertEquals(ScopeConfigPolicy.Decision.STORE, clocked.offer(config("peer-1", minute), receivedAt = 1L))
            val ahead = config("peer-1", minute + 30, spools = listOf("wss://ahead.example/spool/v1"))
            assertEquals(ScopeConfigPolicy.Decision.STORE, clocked.offer(ahead, receivedAt = 2L))
            assertEquals(config("peer-1", minute), clocked.winning("peer-1"))
            assertEquals(config("peer-1", minute), clocked.observeWinning("peer-1").first())
            now += 20 * 60_000L
            val restarted = ScopeConfigRepository(db) { now }
            assertEquals("held across the restart, read once the clock reaches it", ahead, restarted.winning("peer-1"))
            assertEquals(ahead, restarted.observeWinning("peer-1").first())
            assertEquals(mapOf("peer-1" to ahead), restarted.observeWinners().first())
            // A griefer's Int.MAX_VALUE row is held too, and never read.
            restarted.offer(config("zed", Int.MAX_VALUE), receivedAt = 3L)
            now += 100L * 365 * 24 * 60 * 60_000L
            assertEquals(ahead, restarted.winning("peer-1"))
        }

    /**
     * The flow's re-check: no write happens when a held row's minute comes, so [ScopeConfigRepository.observeWinning]
     * must look again on its own. On the test clock (`NOW` plus virtual time), the row ahead becomes the last emission
     * at exactly its eligible instant and not a millisecond before.
     *
     * Room runs on the test scheduler here (`setQueryCoroutineContext`, the `InMemoryMessages` pattern), not its own
     * threads, and both rows are written before the collector starts: the clock is the only thing that can move the
     * answer. Over Room's own threads an invalidation refresh restarted the loop mid-advance (unit-stress, run 3 of 6).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `observeWinning hands over to the row ahead at the instant the clock reaches it`() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val onScheduler =
                Room
                    .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), KnitDatabase::class.java)
                    .allowMainThreadQueries()
                    .setQueryCoroutineContext(dispatcher)
                    .build()
            try {
                val clocked = ScopeConfigRepository(onScheduler) { NOW + testScheduler.currentTime }
                val minute = ScopeConfigPolicy.minutesOf(NOW)
                val readable = config("peer-1", minute)
                // Eligible 30 minutes on: its version minus the slack names NOW's minute plus 30 (NOW is on a minute).
                val ahead =
                    config("peer-1", minute + 30 + ScopeConfigPolicy.VERSION_SLACK_MINUTES, spools = listOf("wss://ahead.example/spool/v1"))
                assertEquals(NOW + 30 * 60_000L, ScopeConfigPolicy.eligibleAt(ahead))
                clocked.offer(ahead, receivedAt = 1L)
                clocked.offer(readable, receivedAt = 2L)

                val seen = mutableListOf<ScopeConfig?>()
                backgroundScope.launch(dispatcher) { clocked.observeWinning("peer-1").collect { seen += it } }
                runCurrent()
                assertEquals("seen: $seen", listOf(readable), seen)

                advanceTimeBy(30 * 60_000L - 1)
                assertEquals("seen: $seen", readable, seen.last())
                advanceTimeBy(1)
                runCurrent()
                assertEquals("seen: $seen", listOf(readable, ahead), seen)
            } finally {
                onScheduler.close()
            }
        }

    @Test
    fun `the spool list keeps its order and its token through the column`() =
        runTest {
            val spools = listOf("wss://b.example/spool/v1", "wss://a.example/spool/v1?k=secret")
            repo.offer(config("peer-1", 1, spools = spools), receivedAt = 1L)
            assertEquals(spools, repo.winning("peer-1")?.spools)
        }

    @Test
    fun `a node is declared once, however many configs it sends`() =
        runTest {
            assertEquals(emptySet<String>(), repo.declared())
            repo.noteDeclared("peer-1", at = 5L)
            repo.noteDeclared("peer-1", at = 9L)
            repo.noteDeclared("peer-2", at = 7L)
            assertEquals(setOf("peer-1", "peer-2"), repo.declared())
            assertEquals(setOf("peer-1", "peer-2"), repo.observeDeclared().first())
        }
}
