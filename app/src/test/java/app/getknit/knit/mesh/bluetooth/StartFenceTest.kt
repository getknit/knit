package app.getknit.knit.mesh.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * [StartFence]: what `BluetoothMeshTransport.start()`, `stop()`, the adapter's `STATE_ON` and `registerLink` share, so a
 * start body that loses the race with a stop raises nothing (#292).
 */
class StartFenceTest {
    @Test
    fun aRaiseAfterTheStopNeverRuns() {
        val fence = StartFence()
        val token = fence.open()
        fence.close() // the Pause's second stop, landing while the start body was still suspended
        var raised = false
        assertFalse(fence.ifLive(token) { raised = true })
        assertFalse("a start body that wakes after its stop must raise nothing", raised)
    }

    @Test
    fun aRaiseOnTheLiveSessionRuns() {
        val fence = StartFence()
        val token = fence.open()
        var raised = false
        assertTrue(fence.ifLive(token) { raised = true })
        assertTrue(raised)
    }

    @Test
    fun aNewerStartSpendsTheOlderToken() {
        val fence = StartFence()
        val first = fence.open()
        fence.close()
        val second = fence.open() // the resume's start, with the first body still suspended
        var firstRaised = false
        var secondRaised = false
        assertFalse(fence.ifLive(first) { firstRaised = true })
        assertTrue(fence.ifLive(second) { secondRaised = true })
        assertFalse("the first session's late body must not raise over the second", firstRaised)
        assertTrue(secondRaised)
    }

    @Test
    fun isCurrentFollowsOpenAndClose() {
        val fence = StartFence()
        assertFalse(fence.isLive)
        assertNull(fence.liveToken())
        val first = fence.open()
        assertTrue(fence.isLive)
        assertTrue(fence.isCurrent(first))
        assertEquals(first, fence.liveToken())
        fence.close()
        assertFalse(fence.isLive)
        assertFalse("a link registered after the stop must find its session gone", fence.isCurrent(first))
        assertNull(fence.liveToken())
        val second = fence.open()
        assertFalse("a link from the older session is not the new session's", fence.isCurrent(first))
        assertTrue(fence.isCurrent(second))
    }

    /**
     * The stop's `close()` lands while the start body is raising: it must wait for the raise, so the teardown that
     * follows it sees every job the raise assigned. Each party is held at its arrival: the raise until the closer is
     * seen blocked on the fence, the closer by the fence itself. No sleep is a sync point; the waits only bound a hang.
     */
    @Test
    fun aCloseWhileARaiseRunsWaitsForItAndSeesItsEffects() {
        val fence = StartFence()
        val token = fence.open()
        val raiseEntered = CountDownLatch(1)
        val releaseRaise = CountDownLatch(1)
        val effect = AtomicBoolean(false) // what the raise assigns (a job handle, in the transport)
        val seenByCloser = AtomicReference<Boolean?>(null)
        val raiser =
            thread(name = "start-body") {
                fence.ifLive(token) {
                    raiseEntered.countDown()
                    releaseRaise.await(AWAIT_MS, MILLISECONDS)
                    effect.set(true)
                }
            }
        assertTrue("the raise never began", raiseEntered.await(AWAIT_MS, MILLISECONDS))
        val closerArrived = CountDownLatch(1)
        val closer =
            thread(name = "stop") {
                closerArrived.countDown()
                fence.close()
                seenByCloser.set(effect.get()) // the teardown's first read after close() returns
            }
        assertTrue("the closer never arrived", closerArrived.await(AWAIT_MS, MILLISECONDS))
        // The closer can only be BLOCKED on a monitor, and the fence's is the only one it takes: it is waiting for the raise.
        assertTrue("close() did not wait for the raise in progress (state ${closer.state})", awaitBlocked(closer))
        assertNull("close() returned while the raise was still running", seenByCloser.get())
        releaseRaise.countDown()
        closer.join(AWAIT_MS)
        raiser.join(AWAIT_MS)
        assertFalse("the closer hung", closer.isAlive)
        assertEquals("close() returned before the raise's effects were visible", true, seenByCloser.get())
        assertFalse(fence.isCurrent(token))
    }

    private fun awaitBlocked(t: Thread): Boolean {
        val until = System.nanoTime() + MILLISECONDS.toNanos(AWAIT_MS)
        while (System.nanoTime() < until) {
            if (t.state == Thread.State.BLOCKED) return true
            if (!t.isAlive) return false
            Thread.sleep(POLL_MS) // a poll of the condition, never a sync point (testing.md, shape 7)
        }
        return false
    }

    private companion object {
        /** Sized for the one-vCPU runner (testing.md, shape 7); a hang fails with the state it saw. */
        const val AWAIT_MS = 10_000L
        const val POLL_MS = 1L
    }
}
