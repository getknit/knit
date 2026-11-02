package app.getknit.knit.mesh

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The block list's diff (ADR 2026-11.nsbf): the first value is diffed against the list last acted on (a baseline when
 * none was recorded), then each id joining or leaving gets its hook, and what was acted on is recorded.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BlockListWatchTest {
    private val blocked = MutableStateFlow(setOf("a"))
    private val blocks = mutableListOf<String>()
    private val unblocks = mutableListOf<String>()
    private val failures = mutableListOf<Exception>()
    private var acted: Set<String>? = null

    private fun TestScope.watch(
        onBlocked: suspend (String) -> Unit = { blocks += it },
        onUnblocked: suspend (String) -> Unit = { unblocks += it },
    ) {
        BlockListWatch(blocked, { acted }, { acted = it }, onBlocked, onUnblocked, { failures += it }).start(backgroundScope)
        runCurrent()
    }

    @Test
    fun withNothingRecordedTheFirstValueIsABaseline() =
        runTest {
            watch()

            assertTrue("a block from before the first record is the start sweep's", blocks.isEmpty())
            assertTrue(unblocks.isEmpty())
            assertEquals(setOf("a"), acted)
        }

    @Test
    fun theFirstValueIsDiffedAgainstTheListLastActedOn() =
        runTest {
            // "b" was unblocked and "a" blocked while the mesh was stopped.
            acted = setOf("b")

            watch()

            assertEquals(listOf("a"), blocks)
            assertEquals(listOf("b"), unblocks)
            assertEquals(setOf("a"), acted)
        }

    @Test
    fun anIdJoiningIsBlockedAndOneLeavingIsUnblocked() =
        runTest {
            watch()

            blocked.value = setOf("a", "b")
            runCurrent()
            blocked.value = setOf("b")
            runCurrent()

            assertEquals(listOf("b"), blocks)
            assertEquals(listOf("a"), unblocks)
            assertEquals(setOf("b"), acted)
        }

    @Test
    fun anEqualValueIsNoChange() =
        runTest {
            watch()

            // A DataStore write to any key re-emits every projection of the store.
            blocked.value = setOf("a")
            runCurrent()

            assertTrue(blocks.isEmpty())
            assertTrue(unblocks.isEmpty())
        }

    @Test
    fun aHookThatThrowsCostsItsOwnIdAloneAndIsTriedAgainAtTheNextChange() =
        runTest {
            var failing = true
            watch(onBlocked = { if (it == "b" && failing) error("boom") else blocks += it })

            blocked.value = setOf("a", "b", "c")
            runCurrent()
            assertEquals(listOf("c"), blocks)
            assertEquals(1, failures.size)
            assertEquals("the failed id is recorded as not acted on", setOf("a", "c"), acted)

            failing = false
            blocked.value = setOf("a", "b", "c", "d")
            runCurrent()

            assertEquals(listOf("c", "b", "d"), blocks)
            assertEquals(setOf("a", "b", "c", "d"), acted)
        }

    @Test
    fun anUnblockHookThatThrowsKeepsItsIdForTheNextChange() =
        runTest {
            var failing = true
            blocked.value = setOf("a", "b")
            watch(onUnblocked = { if (failing) error("boom") else unblocks += it })

            blocked.value = setOf("b")
            runCurrent()
            assertEquals("the failed unblock is recorded as not acted on", setOf("a", "b"), acted)

            failing = false
            blocked.value = setOf("b", "c")
            runCurrent()

            assertEquals(listOf("a"), unblocks)
            assertEquals(setOf("b", "c"), acted)
        }

    /**
     * #270: the mesh stops while a block's hook runs, after its effect and before the record the watch used to write only
     * then. The block is recorded before its hook, so the unblock made while the mesh is down is diffed against a list
     * that holds the person, and acted on at the next start. (A block recorded and never acted on is the storage sweep's.)
     */
    @Test
    fun aStopInsideABlocksHookKeepsTheBlockRecordedSoTheUnblockMadeWhileDownIsActedOn() =
        runTest {
            // The session the first watch runs on, stopped mid-hook as `MeshManager.stop` cancels it.
            val session = Job(backgroundScope.coroutineContext[Job])
            val stuck = CompletableDeferred<Unit>()
            BlockListWatch(
                blocked,
                { acted },
                { acted = it },
                onBlocked = {
                    blocks += it
                    stuck.await()
                },
                onUnblocked = { unblocks += it },
                onFailure = { failures += it },
            ).start(backgroundScope + session)
            runCurrent()

            blocked.value = setOf("a", "b")
            runCurrent()
            assertEquals("the hook ran", listOf("b"), blocks)
            session.cancel()
            runCurrent()
            assertEquals("the block was recorded before its hook", setOf("a", "b"), acted)

            blocked.value = setOf("a")
            watch()

            assertEquals(listOf("b"), unblocks)
            assertEquals(setOf("a"), acted)
        }
}
