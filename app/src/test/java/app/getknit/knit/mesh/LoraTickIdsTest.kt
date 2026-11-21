package app.getknit.knit.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The compact ✓✓ tick → ids map an undelivered tick is read back through (ADR 2026-10.x9fa's 2026-11-19 amendment). */
class LoraTickIdsTest {
    @Test
    fun aRecordedTickIsTakenOnceWithItsAuthorAndIds() {
        val ticks = LoraTickIds()
        ticks.record("t1", "alice", listOf("m1", "m2"), now = 0)
        val tick = ticks.take("t1", now = 1)
        assertEquals("alice", tick?.authorId)
        assertEquals(listOf("m1", "m2"), tick?.ids)
        assertNull("a second report of the same tick finds nothing", ticks.take("t1", now = 2))
        assertNull("a tick never recorded finds nothing", ticks.take("t9", now = 2))
    }

    @Test
    fun anUndeliveredTickIsReportedOnceAndStaysForALateAck() {
        val ticks = LoraTickIds()
        ticks.record("t1", "alice", listOf("m1"), now = 0)
        assertEquals(listOf("m1"), ticks.undelivered("t1", now = 1)?.ids)
        assertNull("reported once", ticks.undelivered("t1", now = 2))
        assertEquals("still there for its late ack", listOf("m1"), ticks.take("t1", now = 3)?.ids)
        assertNull(ticks.undelivered("t1", now = 4))
    }

    @Test
    fun anEmptyTickIsNeverRecorded() {
        val ticks = LoraTickIds()
        ticks.record("t1", "alice", emptyList(), now = 0)
        assertEquals(0, ticks.size())
    }

    @Test
    fun pastTheCapTheOldestTickGoes() {
        val ticks = LoraTickIds(cap = 2)
        ticks.record("t1", "alice", listOf("m1"), now = 0)
        ticks.record("t2", "alice", listOf("m2"), now = 1)
        ticks.record("t3", "bob", listOf("b1"), now = 2)
        assertEquals(2, ticks.size())
        assertNull(ticks.take("t1", now = 3))
        assertEquals(listOf("b1"), ticks.take("t3", now = 3)?.ids)
    }

    @Test
    fun aTickPastTheTtlIsGoneButOneExactlyThatOldIsKept() {
        val ticks = LoraTickIds(ttlMs = 10_000)
        ticks.record("t1", "alice", listOf("m1"), now = 0)
        ticks.record("t2", "alice", listOf("m2"), now = 0)
        assertEquals(listOf("m1"), ticks.take("t1", now = 10_000)?.ids)
        assertNull(ticks.take("t2", now = 10_001))
        assertEquals(0, ticks.size())
    }
}
