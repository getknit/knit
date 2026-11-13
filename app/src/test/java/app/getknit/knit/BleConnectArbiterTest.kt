package app.getknit.knit

import app.getknit.knit.mesh.bluetooth.BleConnectArbiter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleConnectArbiterTest {
    @Test
    fun busyWhileAnyHolderIsActive() {
        val arbiter = BleConnectArbiter()
        assertFalse(arbiter.busy.value)
        arbiter.begin("lora")
        assertTrue(arbiter.busy.value)
        arbiter.end("lora")
        assertFalse(arbiter.busy.value)
    }

    @Test
    fun staysBusyUntilTheLastHolderEnds() {
        val arbiter = BleConnectArbiter()
        arbiter.begin("a")
        arbiter.begin("b")
        arbiter.end("a")
        assertTrue("still one holder", arbiter.busy.value)
        arbiter.end("b")
        assertFalse(arbiter.busy.value)
    }

    @Test
    fun endingAnUnknownTagIsHarmless() {
        val arbiter = BleConnectArbiter()
        arbiter.end("never-began")
        assertFalse(arbiter.busy.value)
    }

    /**
     * Holds are counted per tag (ADR 2026-11.gsqu, Stage 3c): a cancelled dial's `end` runs while the next dial under the
     * same tag already holds, and must not clear that newer hold. This replaced the old set semantics, where a repeated
     * begin was idempotent and one end cleared both.
     */
    @Test
    fun aRepeatedBeginIsCountedSoOneEndLeavesTheNewerHold() {
        val arbiter = BleConnectArbiter()
        arbiter.begin("a")
        arbiter.begin("a")
        arbiter.end("a")
        assertTrue("the second hold under the tag is still live", arbiter.busy.value)
        arbiter.end("a")
        assertFalse(arbiter.busy.value)
    }

    @Test
    fun aCancelledDialsEndCannotClearAnotherTagsOrItsSuccessorsHold() {
        val arbiter = BleConnectArbiter()
        arbiter.begin("meshcore-dial") // the dial a stop() cancels
        arbiter.begin("lora-dial")
        arbiter.begin("meshcore-dial") // its successor, started before the cancelled one's finally ran
        arbiter.end("meshcore-dial")
        assertTrue(arbiter.busy.value)
        arbiter.end("lora-dial")
        assertTrue("the successor still holds", arbiter.busy.value)
        arbiter.end("meshcore-dial")
        assertFalse(arbiter.busy.value)
    }

    @Test
    fun anExtraEndNeverGoesNegative() {
        val arbiter = BleConnectArbiter()
        arbiter.end("a")
        arbiter.end("a")
        arbiter.begin("a")
        assertTrue("an end with no hold left was ignored, not banked", arbiter.busy.value)
        arbiter.end("a")
        assertFalse(arbiter.busy.value)
    }
}
