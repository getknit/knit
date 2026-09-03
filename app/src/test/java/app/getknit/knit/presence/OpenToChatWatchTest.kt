package app.getknit.knit.presence

import app.getknit.knit.presence.OpenToChatPolicy.HOLD_MS
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The collector on a virtual clock: the join of the five inputs, one hold timer per batch, write-through persistence. */
@OptIn(ExperimentalCoroutinesApi::class)
class OpenToChatWatchTest {
    private val own = MutableStateFlow(false)
    private val near = MutableStateFlow(emptySet<String>())
    private val open = MutableStateFlow(emptySet<String>())
    private val blocked = MutableStateFlow(emptySet<String>())
    private val acquainted = MutableStateFlow(emptySet<String>())
    private val posts = mutableListOf<List<String>>()
    private val persisted = mutableListOf<OpenToChatWatch.Persisted>()
    private var clears = 0

    private fun TestScope.watch(loaded: OpenToChatWatch.Persisted = OpenToChatWatch.Persisted(emptyMap(), 0L)) =
        OpenToChatWatch(
            ownFlag = own,
            neighborIds = near,
            openIds = open,
            blocked = blocked,
            acquainted = acquainted,
            loadState = { loaded },
            persist = { persisted += it },
            post = { posts += it },
            clear = { clears++ },
            now = { testScheduler.currentTime },
        ).also { it.start(backgroundScope) }

    private fun TestScope.settle(ms: Long = HOLD_MS + 1) {
        runCurrent()
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun aProfileRowArrivingAfterTheSightingStillCues() =
        runTest {
            watch()
            own.value = true
            near.value = setOf("a")
            settle()
            assertTrue("sighted but no row yet: nothing to name", posts.isEmpty())
            open.value = setOf("a")
            settle()
            assertEquals(listOf(listOf("a")), posts)
            assertEquals(setOf("a"), persisted.single().named.keys)
            // The row landed at the end of the first settle (HOLD_MS + 1); the cue is due one hold later.
            assertEquals("stamped at the post", 2 * HOLD_MS + 1, persisted.single().lastPostAt)
        }

    @Test
    fun aRoomFillingOverTheHoldIsOneCueInArrivalOrder() =
        runTest {
            watch()
            own.value = true
            open.value = setOf("a", "b", "c")
            near.value = setOf("a")
            runCurrent()
            advanceTimeBy(5_000)
            near.value = setOf("a", "b")
            runCurrent()
            advanceTimeBy(5_000)
            near.value = setOf("a", "b", "c")
            settle(HOLD_MS - 10_000 + 1)
            assertEquals(listOf(listOf("a", "b", "c")), posts)
        }

    @Test
    fun theOwnFlagFallingEdgeClearsTheCueOnceAndDropsTheBatch() =
        runTest {
            watch()
            own.value = true
            near.value = setOf("a")
            open.value = setOf("a")
            runCurrent()
            own.value = false
            runCurrent()
            assertEquals(1, clears)
            settle()
            assertTrue(posts.isEmpty())
            own.value = false
            runCurrent()
            assertEquals("no edge, no clear", 1, clears)
        }

    @Test
    fun aBlockedPeerIsNeverNamed() =
        runTest {
            watch()
            own.value = true
            blocked.value = setOf("a")
            near.value = setOf("a", "b")
            open.value = setOf("a", "b")
            settle()
            assertEquals(listOf(listOf("b")), posts)
        }

    @Test
    fun aPeerWeHaveAlreadyMessagedWithIsNeverNamed() =
        runTest {
            watch()
            own.value = true
            acquainted.value = setOf("a")
            near.value = setOf("a", "b")
            open.value = setOf("a", "b")
            settle()
            assertEquals(listOf(listOf("b")), posts)
        }

    @Test
    fun replyingToSomeoneMidHoldDropsThemFromTheBatch() =
        runTest {
            // The messages table re-runs its queries on the insert, so the reply lands in `acquainted`
            // before the hold expires — and naming someone we just answered would be absurd.
            watch()
            own.value = true
            near.value = setOf("a", "b")
            open.value = setOf("a", "b")
            runCurrent()
            acquainted.value = setOf("a")
            settle()
            assertEquals(listOf(listOf("b")), posts)
        }

    @Test
    fun aRememberedStampInsideTheCooldownBlocksTheCueAcrossARestart() =
        runTest {
            watch(OpenToChatWatch.Persisted(named = mapOf("a" to listOf(0L)), lastPostAt = 0L))
            own.value = true
            near.value = setOf("a")
            open.value = setOf("a")
            settle()
            assertTrue(posts.isEmpty())
            assertTrue(persisted.isEmpty())
        }
}
