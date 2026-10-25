@file:OptIn(ExperimentalCoroutinesApi::class) // UnconfinedTestDispatcher / setMain / advanceTimeBy are experimental kotlinx APIs

package app.getknit.knit.ui.devview

import app.getknit.knit.data.forward.ForwardDao
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.mesh.FakeMeshController
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.MeshStartGate
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The HUD's reads (ADR 2026-10.s7wg): the custody count, a SQLCipher query, on the first tick and every fifth after;
 * the posture as the bridge's `STATE` reads it; and a new rate window for each subscription.
 *
 * `runCurrent`, never `advanceUntilIdle`: the ticker is periodic, so idle never comes.
 */
class MeshHudViewModelTest {
    private val mesh = FakeMeshController()
    private val metrics = MeshMetrics()

    // The clock each custody count was asked at, so the cadence is read off the calls themselves.
    private val countedAt = mutableListOf<Long>()
    private val forward =
        mockk<ForwardDao> {
            coEvery { count(any()) } answers {
                countedAt += firstArg<Long>()
                countedAt.size
            }
        }
    private val enabled = MutableStateFlow(true)
    private val pausedUntil = MutableStateFlow<Long?>(null)
    private val settings =
        mockk<SettingsStore> {
            every { meshEnabled } returns enabled
            every { meshPausedUntil } returns pausedUntil
        }
    private val gate = MeshStartGate()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.vm() = MeshHudViewModel(mesh, metrics, forward, settings, gate, clock = { BASE + testScheduler.currentTime })

    @Test
    fun theCustodyCountIsReadOnTheFirstTickAndOnlyEveryFifthAfter() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.reading.collect {} }
            runCurrent()
            assertEquals(listOf(BASE), countedAt)
            assertEquals(1, vm.reading.value?.custody)

            // Ticks one to four read the radios and the counters, and not the database.
            advanceTimeBy(4 * TICK_MS)
            runCurrent()
            assertEquals(listOf(BASE), countedAt)

            advanceTimeBy(TICK_MS)
            runCurrent()
            assertEquals(listOf(BASE, BASE + 5 * TICK_MS), countedAt)
            assertEquals(2, vm.reading.value?.custody)
        }

    @Test
    fun aStoppedMeshOutranksAPauseWhichOutranksADeferredStart() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.reading.collect {} }
            runCurrent()
            assertEquals(MeshPosture.Running, vm.reading.value?.posture)

            gate.record(accepted = false)
            pausedUntil.value = BASE + 60 * TICK_MS
            enabled.value = false
            advanceTimeBy(TICK_MS)
            runCurrent()
            assertEquals(MeshPosture.Stopped, vm.reading.value?.posture)

            enabled.value = true
            advanceTimeBy(TICK_MS)
            runCurrent()
            assertEquals(MeshPosture.Paused, vm.reading.value?.posture)

            // A deadline already past is no pause (MeshPause.activeDeadline).
            pausedUntil.value = BASE
            advanceTimeBy(TICK_MS)
            runCurrent()
            assertEquals(MeshPosture.StartDeferred, vm.reading.value?.posture)
        }

    @Test
    fun theFramesMoveOverTheTicksAndAFreshSubscriptionStartsAFreshWindow() =
        runTest {
            val vm = vm()
            val first = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.reading.collect {} }
            runCurrent()
            assertNull("one sample measures nothing", vm.reading.value?.rates)

            repeat(3) { metrics.onDelivered() }
            advanceTimeBy(TICK_MS)
            runCurrent()
            assertEquals(HudRates(FrameCounts(delivered = 3, sent = 0, dropped = 0), spanMs = TICK_MS), vm.reading.value?.rates)

            // Off screen for ten minutes, past the stateIn's stop timeout: the next look is a new window, not a gap read
            // as a minute.
            first.cancel()
            advanceTimeBy(10 * 60 * TICK_MS)
            metrics.onDelivered()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.reading.collect {} }
            runCurrent()
            val fresh = checkNotNull(vm.reading.value)
            // The new subscription's first tick. The first one ticked on until the 5 s stop, reading custody at tick 5, so
            // this is the test's third count; its window holds this one sample.
            assertEquals(3, fresh.custody)
            assertNull(fresh.rates)
        }

    private companion object {
        const val BASE = 1_700_000_000_000L
        const val TICK_MS = 1_000L
    }
}
