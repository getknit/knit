package app.getknit.knit

import app.getknit.knit.mesh.power.ElapsedWait
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ElapsedWait] against virtual time standing in for the monotonic clock (what `delay` reads) and a separate elapsed
 * clock that can jump ahead of it — the CPU suspended while the elapsed clock kept counting (ADR 2026-10.pj9w).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ElapsedWaitTest {
    /** Elapsed = virtual time + whatever the "phone" slept through. */
    private class Clock(
        private val scope: TestScope,
    ) {
        var slept = 0L

        fun now(): Long = scope.currentTime + slept
    }

    @Test
    fun withoutSuspendASleepLastsItsLength() =
        runTest {
            val clock = Clock(this)
            val wait = ElapsedWait(clock::now, checkMs = 2_000)
            val done = launch { wait.sleep(12_000) }
            advanceTimeBy(11_999)
            assertFalse(done.isCompleted)
            advanceTimeBy(1)
            runCurrent()
            assertTrue(done.isCompleted)
            assertEquals(12_000, currentTime)
        }

    @Test
    fun aSleepThroughSuspendEndsWithinOneCheckOfWaking() =
        runTest {
            val clock = Clock(this)
            val wait = ElapsedWait(clock::now, checkMs = 2_000)
            var endedAt = -1L
            launch {
                wait.sleep(12_000)
                endedAt = currentTime
            }
            advanceTimeBy(1_000)
            clock.slept = 30_000 // the phone slept 30 s; the monotonic clock saw none of it
            advanceTimeBy(5_000)
            // A plain delay(12_000) would still have 11 s to go; this one ends at its first look after waking.
            assertEquals(2_000, endedAt)
        }

    @Test
    fun receiveWithinReturnsTheElementBeforeTheDeadline() =
        runTest {
            val wait = ElapsedWait(Clock(this)::now, checkMs = 2_000)
            val ch = Channel<Unit>(Channel.CONFLATED)
            val got = async { wait.receiveWithin(ch, 60_000) }
            advanceTimeBy(7_000)
            ch.trySend(Unit)
            runCurrent()
            assertEquals(Unit, got.await())
            assertEquals(7_000, currentTime)
        }

    @Test
    fun receiveWithinTimesOutOnTheElapsedClock() =
        runTest {
            val clock = Clock(this)
            val wait = ElapsedWait(clock::now, checkMs = 2_000)
            val ch = Channel<Unit>(Channel.CONFLATED)
            var result: Unit? = Unit
            var endedAt = -1L
            launch {
                result = wait.receiveWithin(ch, 60_000)
                endedAt = currentTime
            }
            advanceTimeBy(3_000)
            clock.slept = 58_000
            advanceTimeBy(5_000)
            assertNull(result)
            assertEquals(4_000, endedAt) // the look at 4 s sees 62 s elapsed, past the 60 s deadline
        }

    @Test
    fun aWakeSentAsAStepEndsIsNotLost() =
        runTest {
            val wait = ElapsedWait(Clock(this)::now, checkMs = 2_000)
            val ch = Channel<Unit>(Channel.CONFLATED)
            val got = async { wait.receiveWithin(ch, 10_000) }
            advanceTimeBy(2_000)
            ch.trySend(Unit) // due at the same instant as the first step's timeout
            runCurrent()
            assertEquals(Unit, got.await())
            assertTrue(ch.tryReceive().isFailure) // taken once, by the wait
        }

    @Test
    fun aDeadlineAlreadyPassedTakesOnlyWhatIsWaiting() =
        runTest {
            val wait = ElapsedWait(Clock(this)::now)
            val ch = Channel<Unit>(Channel.CONFLATED)
            assertNull(wait.receiveWithin(ch, 0))
            ch.trySend(Unit)
            assertEquals(Unit, wait.receiveWithin(ch, 0))
            assertEquals(0, currentTime)
        }
}
