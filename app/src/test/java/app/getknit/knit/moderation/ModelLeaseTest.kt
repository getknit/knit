package app.getknit.knit.moderation

import app.getknit.knit.data.settings.ModelLoadState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ModelLease]'s state machine on virtual time: one load per residency, the idle release, the reload
 * through the guard, and the two cancellation shapes that used to spend a moderator for the process.
 *
 * The lease's reaper rides [TestScope.backgroundScope] so `runTest` does not wait on it, and the clock is
 * the scheduler's, or the reaper would re-sleep against a wall clock that never moves.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelLeaseTest {
    private val idle = 10 * 60_000L

    private class Rig(
        scope: CoroutineScope,
        clock: () -> Long,
        idleMs: Long,
        private val loader: suspend () -> String? = { "engine" },
    ) {
        var loads = 0
        val closed = mutableListOf<String>()
        val lease =
            ModelLease(
                scope = scope,
                idleMs = idleMs,
                clock = clock,
                load = {
                    loads++
                    loader()
                },
                close = { closed += it },
            )
    }

    private fun TestScope.rig(loader: suspend () -> String? = { "engine" }) =
        Rig(backgroundScope, { testScheduler.currentTime }, idle, loader)

    @Test
    fun `loads once and hands the same engine to every use`() =
        runTest {
            val rig = rig()
            val seen = List(3) { rig.lease.use { it } }
            assertEquals(listOf("engine", "engine", "engine"), seen)
            assertEquals(1, rig.loads)
            assertTrue(rig.lease.isLoaded)
            assertTrue(rig.lease.isResident)
        }

    @Test
    fun `releases the engine after the idle window and reloads on the next use`() =
        runTest {
            val rig = rig()
            rig.lease.use { }
            advanceTimeBy(idle)
            runCurrent()

            assertEquals(listOf("engine"), rig.closed)
            assertFalse(rig.lease.isResident)
            assertFalse(rig.lease.isLoaded)

            assertEquals("engine", rig.lease.use { it })
            assertEquals(2, rig.loads)
            assertTrue(rig.lease.isResident)
        }

    @Test
    fun `a use inside the window pushes the release back`() =
        runTest {
            val rig = rig()
            rig.lease.use { }
            advanceTimeBy(idle - 1)
            rig.lease.use { }
            advanceTimeBy(1)
            runCurrent()
            assertTrue("touched at idle-1, so not released at idle", rig.closed.isEmpty())

            advanceTimeBy(idle - 1)
            runCurrent()
            assertEquals(listOf("engine"), rig.closed)
            assertEquals(1, rig.loads)
        }

    @Test
    fun `a load that returns nothing is never retried and never reaped`() =
        runTest {
            // A missing asset, a Java-level failure, or a latched model: spent for the process, exactly as
            // the moderators behaved before the lease — and how the poison-pill survives an idle release.
            val rig = rig { null }
            assertEquals(null, rig.lease.use { it })
            advanceTimeBy(3 * idle)
            runCurrent()
            assertEquals(null, rig.lease.use { it })

            assertEquals(1, rig.loads)
            assertTrue(rig.lease.isLoaded)
            assertFalse(rig.lease.isResident)
            assertTrue(rig.closed.isEmpty())
        }

    @Test
    fun `a close that throws still drops the engine`() =
        runTest {
            var loads = 0
            val lease =
                ModelLease<String>(
                    scope = backgroundScope,
                    idleMs = idle,
                    clock = { testScheduler.currentTime },
                    load = {
                        loads++
                        "engine"
                    },
                    close = { error("native close failed") },
                )
            lease.use { }
            advanceTimeBy(idle)
            runCurrent()
            assertFalse(lease.isResident)
            lease.use { }
            assertEquals(2, loads)
        }

    @Test
    fun `unload closes now and the next use reloads`() =
        runTest {
            val rig = rig()
            rig.lease.use { }
            rig.lease.unload()
            assertEquals(listOf("engine"), rig.closed)
            assertFalse(rig.lease.isResident)
            assertFalse(rig.lease.isLoaded)

            rig.lease.use { }
            assertEquals(2, rig.loads)
            // The old reaper was cancelled with the unload; only the new residency's reaper fires.
            advanceTimeBy(idle)
            runCurrent()
            assertEquals(listOf("engine", "engine"), rig.closed)
        }

    @Test
    fun `an unload racing a load waits for it and closes what it loaded`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val rig =
                rig {
                    gate.await()
                    "engine"
                }
            val user = launch { rig.lease.use { } }
            yield()
            val unloader = launch { rig.lease.unload() }
            yield()
            assertTrue(rig.closed.isEmpty())

            gate.complete(Unit)
            user.join()
            unloader.join()
            assertEquals(listOf("engine"), rig.closed)
            assertFalse(rig.lease.isResident)
        }

    @Test
    fun `a caller cancelled during the load leaves the lease free to load again`() =
        runTest {
            // Back out of a chat while the load sits in the guard's DataStore round trips: the next caller
            // must load, not find screening silently off for the rest of the process.
            val gate = CompletableDeferred<Unit>()
            val rig =
                rig {
                    gate.await()
                    "engine"
                }
            val job = launch { rig.lease.use { } }
            yield()
            job.cancel()
            job.join()
            assertFalse(rig.lease.isLoaded)
            assertFalse(rig.lease.isResident)

            gate.complete(Unit)
            assertEquals("engine", rig.lease.use { it })
            assertEquals(2, rig.loads)
        }

    @Test
    fun `a load that throws spends the lease like one that returns nothing`() =
        runTest {
            val rig = rig { error("leaked out of the loader") }
            assertEquals(null, rig.lease.use { it })
            assertEquals(null, rig.lease.use { it })
            assertEquals(1, rig.loads)
            assertTrue(rig.lease.isLoaded)
        }

    @Test
    fun `a cancellation thrown by the load is not swallowed`() =
        runTest {
            val rig = rig { throw CancellationException("scope gone") }
            val result = runCatching { rig.lease.use { } }
            assertTrue(result.exceptionOrNull() is CancellationException)
            assertFalse(rig.lease.isLoaded)
        }

    @Test
    fun `the reaper dies with its scope`() =
        runTest {
            val scope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob())
            val rig = Rig(scope, { testScheduler.currentTime }, idle)
            rig.lease.use { }
            scope.cancel()
            advanceTimeBy(2 * idle)
            runCurrent()
            // Nobody will release it now; the engine stays, which is the pre-lease behaviour, not a leak.
            assertTrue(rig.closed.isEmpty())
            assertTrue(rig.lease.isResident)
        }

    @Test
    fun `a reload goes through the guard again`() =
        runTest {
            // ADR 037's marker brackets every load, not only the first in a process: a reload that faults
            // natively must latch exactly as a first load does.
            val journal = FakeModelLoadJournal()
            // Offset: a marker at virtual time 0 would read as "no marker" (pendingSince == 0L).
            val guard = ModelLoadGuard(journal, { null }, STAMP) { EPOCH + testScheduler.currentTime }
            val rig = rig { guard.guard(ModelLoadGuard.TOXICITY) { "engine" } }

            rig.lease.use { }
            advanceTimeBy(idle)
            runCurrent()
            rig.lease.use { }

            assertEquals(2, rig.loads)
            assertEquals(2, journal.writes.count { it.pendingSince != 0L })
            assertEquals(ModelLoadState(STAMP, pendingSince = 0L, fails = 0), journal.state(ModelLoadGuard.TOXICITY))
        }

    @Test
    fun `a latched model stays off across the idle window`() =
        runTest {
            val journal = FakeModelLoadJournal()
            journal.states[ModelLoadGuard.TOXICITY] = MutableStateFlow(ModelLoadState(STAMP, 0L, ModelLoadPolicy.MAX_FAILS))
            val guard = ModelLoadGuard(journal, { null }, STAMP) { EPOCH + testScheduler.currentTime }
            val rig = rig { guard.guard(ModelLoadGuard.TOXICITY) { "engine" } }

            assertEquals(null, rig.lease.use { it })
            advanceTimeBy(3 * idle)
            runCurrent()
            assertEquals(null, rig.lease.use { it })

            assertEquals(1, rig.loads)
            assertTrue(journal.writes.isEmpty())
            assertTrue(rig.closed.isEmpty())
        }

    private companion object {
        const val STAMP = "16|Pixel/rel/1"
        const val EPOCH = 1_700_000_000_000L
    }
}
