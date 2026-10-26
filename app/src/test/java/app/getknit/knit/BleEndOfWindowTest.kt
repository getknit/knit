package app.getknit.knit

import app.getknit.knit.legal.repoFile
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Bluetooth transport's wiring for Wi-Fi Aware's dormancy (GitHub #44) that no JVM fake of the radio reaches, pinned
 * on the source as [BluetoothWaitClockTest] pins the waits:
 * - every presence window ends in a publish, so a peer that left drops out of `reachable`, `near` and the Aware
 *   candidate sets with no sighting to republish them (without it the sets froze until the next sighting of anyone, and
 *   a dormant-able Aware plane stayed up for a phone long gone, or a wake fired for one);
 * - the window's end counts the miss before that publish reads it;
 * - `seesNewcomers` goes false at once on the adapter's off edge and at `stop()`, not at a window's end, through
 *   [app.getknit.knit.mesh.bluetooth.NewcomerWatch] (whose guard [NewcomerWatchTest] pins).
 */
class BleEndOfWindowTest {
    private val source = repoFile("app/src/main/java/app/getknit/knit/mesh/bluetooth/BluetoothMeshTransport.kt").readText()

    private fun body(signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("no `$signature` in BluetoothMeshTransport", start >= 0)
        val open = source.indexOf('{', start)
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(open, i + 1)
            }
        }
        error("unbalanced braces after `$signature`")
    }

    @Test
    fun everyPresenceWindowEndsInAPublishAfterTheScanStops() {
        val window = body("private suspend fun scanWindow(")
        val stop = window.indexOf("scanner.stop()")
        val end = window.indexOf("endWindow(")
        assertTrue("scanWindow must end in endWindow, after scanner.stop()", stop >= 0 && end > stop)
        val endWindow = body("private fun endWindow(")
        val miss = endWindow.indexOf("awareCandidates.onWindow(")
        val publish = endWindow.indexOf("publishReachable()")
        assertTrue("endWindow must count the miss, then publish", miss >= 0 && publish > miss)
    }

    @Test
    fun seesNewcomersFallsAtOnceOnTheOffEdges() {
        val stop = body("override fun stop()")
        assertTrue("stop() takes the off edge first", stop.trimStart('{').trim().startsWith("newcomersOff("))
        val off = source.substringAfter("BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF ->")
        val inline = off.substringBefore("scope.launch")
        assertTrue("the off edge clears it inline, before the teardown launches", "newcomersOff(" in inline)
        // The generation guard itself is NewcomerWatchTest's; here, only that the transport goes through it.
        assertTrue("an off edge goes through the watch", "newcomers.off(" in body("private fun newcomersOff("))
        val window = body("private suspend fun scanWindow(")
        val begin = window.indexOf("newcomers.begin()")
        assertTrue("a window takes its token as it starts, before it waits", begin >= 0 && begin < window.indexOf("elapsedWait."))
        val endWindow = body("private fun endWindow(")
        assertTrue("a window's end hands its own token back", "newcomers.end(window, ranClean)" in endWindow)
        assertTrue("an adapter found off before a window says so", "newcomersOff(" in body("private suspend fun waitedBeforeWindow("))
    }
}
