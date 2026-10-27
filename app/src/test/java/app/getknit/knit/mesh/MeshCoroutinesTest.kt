package app.getknit.knit.mesh

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * [everyRound]: one round that throws costs that round, never the loop (#206). [runCatchingUnlessCancelled]: the
 * pipeline's guards count a failure and let the caller's own cancellation through (ADR 2026-10.f82h). Virtual time
 * throughout.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MeshCoroutinesTest {
    @Test
    fun aRoundThatThrowsIsHandedOverAndTheNextRunsOnTime() =
        runTest {
            val boom = IOException("disk full")
            val failures = mutableListOf<Exception>()
            var rounds = 0
            backgroundScope.everyRound(INTERVAL, onFailure = { failures += it }) {
                rounds++
                if (rounds == 1) throw boom
            }

            advanceTimeBy(INTERVAL)
            runCurrent()
            assertEquals(1, rounds)
            assertEquals(1, failures.size)
            assertSame(boom, failures.single())

            advanceTimeBy(INTERVAL)
            runCurrent()
            assertEquals(2, rounds)
            assertEquals(1, failures.size)
        }

    @Test
    fun noRoundRunsBeforeTheFirstInterval() =
        runTest {
            var rounds = 0
            backgroundScope.everyRound(INTERVAL, onFailure = {}) { rounds++ }

            advanceTimeBy(INTERVAL - 1)
            runCurrent()
            assertEquals(0, rounds)

            advanceTimeBy(1)
            runCurrent()
            assertEquals(1, rounds)
        }

    @Test
    fun aStrayCancellationFromARoundIsAFailedRoundWhileTheLoopIsActive() =
        runTest {
            val failures = mutableListOf<Exception>()
            var rounds = 0
            backgroundScope.everyRound(INTERVAL, onFailure = { failures += it }) {
                rounds++
                if (rounds == 1) throw CancellationException("a child's timeout leaked out")
            }

            advanceTimeBy(INTERVAL)
            runCurrent()
            advanceTimeBy(INTERVAL)
            runCurrent()

            assertEquals(2, rounds)
            assertEquals(1, failures.size)
            assertTrue(failures.single() is CancellationException)
        }

    @Test
    fun cancellingTheLoopEndsItWithoutAFailure() =
        runTest {
            val failures = mutableListOf<Exception>()
            var rounds = 0
            val loop = backgroundScope.everyRound(INTERVAL, onFailure = { failures += it }) { rounds++ }

            advanceTimeBy(INTERVAL)
            runCurrent()
            assertEquals(1, rounds)

            loop.cancel()
            advanceTimeBy(INTERVAL * 3)
            runCurrent()

            assertTrue(loop.isCancelled)
            assertEquals(1, rounds)
            assertEquals(emptyList<Exception>(), failures)
        }

    @Test
    fun cancellingTheLoopMidRoundEndsItWithoutAFailure() =
        runTest {
            val failures = mutableListOf<Exception>()
            var started = 0
            val loop =
                backgroundScope.everyRound(INTERVAL, onFailure = { failures += it }) {
                    started++
                    delay(INTERVAL) // a round still running when the session ends
                }

            advanceTimeBy(INTERVAL)
            runCurrent()
            assertEquals(1, started)

            loop.cancel()
            advanceTimeBy(INTERVAL * 3)
            runCurrent()

            assertTrue(loop.isCancelled)
            assertEquals(1, started)
            assertEquals(emptyList<Exception>(), failures)
        }

    @Test
    fun aGuardHandsBackAFailureAndAStrayCancellationAlike() =
        runTest {
            val boom = IOException("decrypt failed")
            assertSame(boom, runCatchingUnlessCancelled { throw boom }.exceptionOrNull())
            val stray = CancellationException("an inner withTimeout")
            assertSame(stray, runCatchingUnlessCancelled { throw stray }.exceptionOrNull())
            assertEquals(7, runCatchingUnlessCancelled { 7 }.getOrNull())
        }

    /** The caller cancelled mid-guard: rethrown, so the drop after the guard never runs (#141 finding 11). */
    @Test
    fun aGuardRethrowsItsCallersOwnCancellation() =
        runTest {
            val parked = CompletableDeferred<Unit>()
            var counted = false
            val caller =
                launch {
                    runCatchingUnlessCancelled {
                        parked.complete(Unit)
                        delay(INTERVAL)
                    }.onFailure { counted = true }
                }
            parked.await()
            caller.cancel()
            advanceTimeBy(INTERVAL * 2)
            runCurrent()

            assertTrue(caller.isCancelled)
            assertFalse("a cancellation is not a dropped frame", counted)
        }

    private companion object {
        const val INTERVAL = 60_000L
    }
}
