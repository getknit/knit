package app.getknit.knit

import app.getknit.knit.mesh.bluetooth.NewcomerWatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [NewcomerWatch] (GitHub #44, ADR 2026-10.2t49): an off edge (adapter off, `stop()`) clears `seesNewcomers` at once
 * and a window that began before it never writes true at its end; a later window writes whether its scan ran clean.
 * The interleavings are driven in order on one thread: the watch's one lock makes each call atomic, so an order of calls
 * is all a second thread could produce.
 */
class NewcomerWatchTest {
    private val changes = mutableListOf<Pair<Boolean, String>>()
    private val watch = NewcomerWatch { sees, why -> changes += sees to why }

    @Test
    fun aWindowEndingAfterAnOffEdgeItSpannedStaysFalse() {
        assertTrue(watch.end(watch.begin(), ranClean = true))
        val window = watch.begin()
        assertTrue("the off edge writes false at once", watch.off("adapter off"))
        assertFalse(watch.sees.value)
        assertFalse("a window that began before the edge never writes", watch.end(window, ranClean = true))
        assertFalse(watch.sees.value)
        assertEquals(listOf(true to "", false to "adapter off"), changes)
    }

    @Test
    fun aWindowBegunAfterTheOffEdgeWritesTrue() {
        watch.off("stop")
        val window = watch.begin()
        assertTrue(watch.end(window, ranClean = true))
        assertTrue(watch.sees.value)
        assertEquals(listOf(true to ""), changes)
    }

    @Test
    fun aWindowThatDidNotRunCleanWritesFalseWithNoOffEdge() {
        watch.end(watch.begin(), ranClean = true)
        assertTrue(watch.end(watch.begin(), ranClean = false))
        assertFalse(watch.sees.value)
        assertEquals(listOf(true to "", false to NewcomerWatch.NOT_REGISTERED), changes)
    }

    @Test
    fun twoOverlappingWindowsAcrossOneOffEdge() {
        val before = watch.begin()
        watch.off("adapter off")
        val after = watch.begin()
        assertTrue("the window begun after the edge writes", watch.end(after, ranClean = true))
        assertTrue(watch.sees.value)
        // The earlier window ends last, and clean: it must not write, true or false, over the later one's answer.
        assertFalse(watch.end(before, ranClean = false))
        assertTrue(watch.sees.value)
        assertFalse(watch.end(before, ranClean = true))
        assertTrue(watch.sees.value)
        assertEquals(listOf(true to ""), changes)
    }

    @Test
    fun anUnchangedValueNeitherEmitsNorReports() {
        assertFalse("false to start, so an off edge changes nothing", watch.off("stop"))
        assertFalse(watch.end(watch.begin(), ranClean = false))
        assertTrue(changes.isEmpty())
        assertTrue(watch.end(watch.begin(), ranClean = true))
        assertFalse(watch.end(watch.begin(), ranClean = true))
        assertTrue(watch.off("adapter off"))
        assertFalse(watch.off("adapter off"))
        assertEquals(listOf(true to "", false to "adapter off"), changes)
    }

    @Test
    fun theFlowAlreadyHoldsTheNewValueWhenAChangeIsReported() {
        val agreed = mutableListOf<Boolean>()
        lateinit var watch: NewcomerWatch
        val held = mutableListOf<Boolean>()
        watch =
            NewcomerWatch { sees, _ ->
                agreed += watch.sees.value == sees
                held += Thread.holdsLock(watch.lock)
            }
        watch.end(watch.begin(), ranClean = true)
        watch.off("stop")
        assertEquals("the log line never runs ahead of the flow", listOf(true, true), agreed)
        assertEquals("the log line is written under the lock, in decision order", listOf(true, true), held)
    }
}
