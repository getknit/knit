package app.getknit.knit

import app.getknit.knit.mesh.bluetooth.AdvertRotation
import app.getknit.knit.mesh.bluetooth.RotationTurn
import app.getknit.knit.mesh.crypto.BlindTag
import app.getknit.knit.mesh.power.ElapsedWait
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RotationTurn] (ADR 2026-11.xp6d's writer, step 5b's W4; the stage-1 review's blocking spin): every pass suspends on
 * the single-threaded worker, a rotation that does not install looks again a recheck later (never at once), one that
 * throws does the same, and with the radio down the turn stops until a wake. Virtual time on one test thread, which is
 * exactly the confinement the worker gives: a pass that never suspended would starve the other coroutine here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RotationTurnTest {
    private val slotStart = 2_000_000L * BlindTag.SLOT_MS
    private val edge = slotStart + BlindTag.SLOT_MS
    private val rotation = AdvertRotation().also { it.onRotated(slotStart) }
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var attempts = 0
    private var announces = 0
    private var failures = 0
    private var radioUp = true

    private fun TestScope.turn(
        announce: () -> Unit = {},
        rotate: () -> Boolean,
    ) = RotationTurn(
        rotation,
        wake,
        ElapsedWait({ edge + testScheduler.currentTime }),
        wallMs = { edge + testScheduler.currentTime },
        holding = { false },
        settleLeftMs = { 0L },
        holdMs = { 180_000L },
        radioUp = { radioUp },
        announce = {
            announces++
            // As for rotations: a spin regression fails here instead of hanging the test fork.
            if (announces > 50) throw AssertionError("spin: $announces announcements")
            announce()
        },
        rotate = {
            attempts++
            // A spin regression fails here instead of hanging the test fork.
            if (attempts > 50) throw AssertionError("spin: $attempts rotation attempts")
            rotate()
        },
        onFailure = { failures++ },
    )

    @Test
    fun aRotationThatDoesNotInstallLooksAgainOnlyAfterARecheck() =
        runTest {
            // The radio went down between the rotation's begin and its install, and came straight back up.
            val job = launch { turn { false }.run() }
            var other = 0
            val sibling = launch { repeat(3) { other++ } }
            runCurrent()
            assertEquals("one attempt, then a wait", 1, attempts)
            assertTrue("the worker stayed free", sibling.isCompleted)
            assertEquals(3, other)
            advanceTimeBy(4 * AdvertRotation.HOLD_RECHECK_MS + 1)
            assertEquals("one attempt per recheck", 5, attempts)
            job.cancel()
            runCurrent()
            assertTrue("a stop's cancel is seen", job.isCompleted)
        }

    @Test
    fun aRotationThatThrowsDoesNotSpin() =
        runTest {
            val job = launch { turn { error("the server would not close") }.run() }
            runCurrent()
            assertEquals(1, attempts)
            assertEquals(1, failures)
            advanceTimeBy(2 * AdvertRotation.HOLD_RECHECK_MS + 1)
            assertEquals(3, attempts)
            assertEquals(3, failures)
            job.cancel()
            runCurrent()
            assertTrue(job.isCompleted)
        }

    @Test
    fun withTheRadioDownTheTurnStopsUntilAWake() =
        runTest {
            radioUp = false
            val job = launch { turn { false }.run() }
            runCurrent()
            assertEquals(1, attempts)
            assertFalse(rotation.turning)
            advanceTimeBy(10 * BlindTag.SLOT_MS)
            assertEquals("no tick while nothing is on the air", 1, attempts)
            // A bring-up puts a blinded form back on the air and wakes the turn.
            radioUp = true
            rotation.onRotated(slotStart)
            wake.trySend(Unit)
            runCurrent()
            assertEquals(2, attempts)
            job.cancel()
        }

    @Test
    fun anInstalledRotationWaitsForTheNextEdge() =
        runTest {
            val job =
                launch {
                    turn {
                        rotation.onRotated(edge + testScheduler.currentTime)
                        true
                    }.run()
                }
            runCurrent()
            assertEquals(1, attempts)
            assertEquals("a late wake rotates unannounced", 0, announces)
            advanceTimeBy(BlindTag.SLOT_MS / 2)
            assertEquals("nothing more inside the new slot", 1, attempts)
            assertEquals(0, announces)
            advanceTimeBy(BlindTag.SLOT_MS)
            assertEquals("the next edge rotates again", 2, attempts)
            assertEquals("one announcement for that slot, at its lead", 1, announces)
            advanceTimeBy(BlindTag.SLOT_MS)
            assertEquals(3, attempts)
            assertEquals("one announcement per slot", 2, announces)
            job.cancel()
        }

    @Test
    fun anAnnouncementThatThrowsIsMarkedMadeAndNotRetriedAtOnce() =
        runTest {
            val job =
                launch {
                    turn(announce = { error("the mark store refused") }) {
                        rotation.onRotated(edge + testScheduler.currentTime)
                        true
                    }.run()
                }
            runCurrent()
            assertEquals(1, attempts)
            // Up to the lead before the next edge: the announcement runs, throws, and goes to onFailure once.
            advanceTimeBy(BlindTag.SLOT_MS - AdvertRotation.LEAD_MS)
            runCurrent()
            assertEquals(1, announces)
            assertEquals(1, failures)
            assertTrue("marked announced whatever it managed", rotation.view().announced)
            advanceTimeBy(AdvertRotation.LEAD_MS - 1)
            assertEquals("no second announcement for the slot", 1, announces)
            assertEquals(1, failures)
            assertEquals(1, attempts)
            advanceTimeBy(1)
            runCurrent()
            assertEquals("the edge still rotates", 2, attempts)
            job.cancel()
        }
}
