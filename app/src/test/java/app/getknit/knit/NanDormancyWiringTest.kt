package app.getknit.knit

import app.getknit.knit.legal.repoFile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `WifiAwareTransport`'s wiring for dormancy (GitHub #44) that no JVM rig reaches, pinned on the source as
 * [BleEndOfWindowTest] pins the Bluetooth half. [NanDormancyPolicyTest] pins each rule; these pin that the transport
 * hands it the right input:
 * - the dormancy close refunds nothing (ADR 055: a refund this cycle could drive is getknit/Knit#9's binder leak);
 * - the availability receiver judges a broadcast against the dormancy close, so its own NAN-down echo refunds nothing;
 * - a duty window is never judged empty while a session cycle settles in it (the reviewer's wedge);
 * - the responder's give-up cycle never runs, nor spends its budget, while dormant;
 * - deep idle's edges re-read the evidence through a receiver of their own, registered and unregistered beside the
 *   availability receiver, and never through [handleAvailabilityChanged]'s refunds and teardown.
 */
class NanDormancyWiringTest {
    private val source = repoFile("app/src/main/java/app/getknit/knit/mesh/wifiaware/WifiAwareTransport.kt").readText()

    private fun body(signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("no `$signature` in WifiAwareTransport", start >= 0)
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
    fun theDormancyCloseRefundsNothing() {
        val close = body("private fun goDormant(")
        for (refund in listOf("clearAttachBackoff", "noteAttachSucceeded", "lastAvailable =", "lastReattachAt =", "responderCycles =")) {
            assertFalse("goDormant must not touch `$refund`", refund in close)
        }
        assertFalse("never recorded as a session cycle", "FieldEvent.NanCycle" in close)
    }

    @Test
    fun theReceiverJudgesABroadcastAgainstTheDormancyClose() {
        val receiver = body("private fun handleAvailabilityChanged(")
        val edgeLine = receiver.lines().first { "availabilityEdge(" in it }
        assertTrue("availabilityEdge is handed dormantClosedAt", edgeLine.trimEnd().endsWith("dormantClosedAt)"))
        val edge = receiver.indexOf("availabilityEdge(")
        val write = receiver.indexOf("lastAvailable = available")
        assertTrue("the echo is judged before lastAvailable is written", edge in 0 until write)
    }

    @Test
    fun aDutyWindowSeesACycleSettlingInIt() {
        assertTrue("cycleSettling = sessionCycleSettleStartedAt != 0L" in body("private fun dormancyTick("))
    }

    @Test
    fun theResponderGiveUpCycleSitsOutADormantPlane() {
        val refile = body("private fun refileResponder(")
        val guard = refile.indexOf("!_dormant.value")
        val cycle = refile.indexOf("responderCycles++")
        assertTrue("the give-up branch is gated on dormancy before it spends a cycle", guard in 0 until cycle)
    }

    @Test
    fun deepIdleEdgesReReadTheEvidenceThroughTheirOwnReceiver() {
        val receiverDecl = source.indexOf("private val idleReceiver =")
        assertTrue("no idleReceiver in WifiAwareTransport", receiverDecl >= 0)
        val receiver = body("private val idleReceiver =")
        assertTrue("an idle edge re-reads the evidence", "refreshAwareEvidence()" in receiver)
        for (wrong in listOf("handleAvailabilityChanged", "clearAttachBackoff", "attach()", "teardownPeer")) {
            assertFalse("an idle edge must not run `$wrong`", wrong in receiver)
        }
        val register = body("private fun registerIdleWatch(")
        assertTrue(
            "registered for the idle broadcast, not exported, guarded",
            "PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED" in register &&
                "ContextCompat.RECEIVER_NOT_EXPORTED" in register &&
                "runCatching" in register,
        )
        val start = body("override fun start(")
        assertTrue("registered beside availability", " registerAvailability()" in start && " registerIdleWatch()" in start)
        val stop = body("override fun stop(")
        assertTrue(
            "unregistered beside availability",
            " unregisterAvailability()" in stop && " unregisterIdleWatch()" in stop,
        )
    }

    @Test
    fun theAdvertAndTheHealthReadTheirOwnReaders() {
        val refresh = body("private fun refreshAwareEvidence(")
        assertTrue("the advert's reader takes the facts, deep idle included", "NanDormancyPolicy.wontRun(facts," in refresh)
        assertTrue("the health's reader never sees deep idle", "dormantHealth(NanDormancyPolicy.radioOff(facts)" in refresh)
        // An expression body: its declaration runs to the blank line after it, which [body]'s braces cannot find.
        val helperAt = source.indexOf("private fun dormantHealth(")
        assertTrue("no dormantHealth helper in WifiAwareTransport", helperAt >= 0)
        val helper = source.substring(helperAt, source.indexOf("\n\n", helperAt))
        assertTrue(
            "the availability path's health helper never sees deep idle either",
            "NanDormancyPolicy.dormantHealth(NanDormancyPolicy.radioOff(radioFacts(now))" in helper,
        )
    }
}
