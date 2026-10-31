package app.getknit.knit.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** The bounded, first-ack-wins memory of which DMs their LoRa radio acked (ADR 040's 2026-10-31 amendment). */
class LoraRadioReachedTest {
    private val onTime = LoraRadioReached(atMs = 10L, late = false)

    @Test
    fun anOnTimeAckWinsAndARepeatChangesNothing() {
        val held = LoraRadioReached.record(emptyMap(), "f1", onTime)
        assertSame(
            "a resend's ack is the same DM: the map is handed back untouched",
            held,
            LoraRadioReached.record(held, "f1", LoraRadioReached(99L, late = true)),
        )
        assertEquals(mapOf("f1" to onTime), held)
    }

    @Test
    fun anOnTimeAckReplacesALateOneAndNeverTheReverse() {
        val late = LoraRadioReached(atMs = 50L, late = true)
        val held = LoraRadioReached.record(emptyMap(), "f1", late)
        assertEquals("the re-probe's on-time ack gives the DM its time", mapOf("f1" to onTime), LoraRadioReached.record(held, "f1", onTime))
        val stamped = LoraRadioReached.record(emptyMap(), "f1", onTime)
        assertSame("a late replay never takes an on-time stamp away", stamped, LoraRadioReached.record(stamped, "f1", late))
    }

    @Test
    fun pastTheBoundTheOldestGoesFirst() {
        var held = emptyMap<String, LoraRadioReached>()
        repeat(LoraRadioReached.MAX_HELD + 2) { held = LoraRadioReached.record(held, "f$it", onTime) }
        assertEquals(LoraRadioReached.MAX_HELD, held.size)
        assertEquals("f2", held.keys.first())
        assertEquals("f${LoraRadioReached.MAX_HELD + 1}", held.keys.last())
    }
}
