package app.getknit.knit.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The era, floor, bound and age of the sealed DM re-ack ledger (#176, ADR 2026-10.8hn6). Pure: the clock is a
 * parameter. The custody half of the rule, and who records, are `InboundPipelineTest`'s.
 */
class DmReAckLedgerTest {
    @Test
    fun aReceiptAnswersARedeliveryUnderItsOwnRootInsideTheFloor() {
        val ledger = DmReAckLedger()
        ledger.record("dm", rootTag = 7L, receiptId = "r1", now = T)

        assertEquals("r1", ledger.heldReceipt("dm", currentRootTag = 7L, now = T + DmReAckLedger.FLOOR_MS - 1))
        assertNull("past the floor", ledger.heldReceipt("dm", 7L, T + DmReAckLedger.FLOOR_MS))
    }

    @Test
    fun anotherRootOrNoSessionReSeals() {
        val ledger = DmReAckLedger()
        ledger.record("dm", rootTag = 7L, receiptId = "r1", now = T)

        assertNull("a reset moved the root", ledger.heldReceipt("dm", currentRootTag = 8L, now = T + 1))
        assertNull("no session held now", ledger.heldReceipt("dm", currentRootTag = null, now = T + 1))
        assertNull("never acked", ledger.heldReceipt("other", 7L, T + 1))
    }

    @Test
    fun theFloorDoublesPerSameEraReSealUpToFourHours() {
        assertEquals(15 * MIN, DmReAckLedger.floorMs(0))
        assertEquals(30 * MIN, DmReAckLedger.floorMs(1))
        assertEquals(4 * 60 * MIN, DmReAckLedger.floorMs(4))
        assertEquals(4 * 60 * MIN, DmReAckLedger.floorMs(40))

        val ledger = DmReAckLedger()
        var now = T
        ledger.record("dm", 7L, "r0", now)
        repeat(6) { i ->
            now += DmReAckLedger.floorMs(i)
            ledger.record("dm", 7L, "r${i + 1}", now)
        }
        assertEquals(4, ledger.entry("dm")?.step)
        assertEquals("r6", ledger.heldReceipt("dm", 7L, now + DmReAckLedger.MAX_FLOOR_MS - 1))
    }

    @Test
    fun aNewEraStartsItsOwnFloor() {
        val ledger = DmReAckLedger()
        ledger.record("dm", 7L, "r0", T)
        ledger.record("dm", 7L, "r1", T + DmReAckLedger.FLOOR_MS)
        assertEquals(1, ledger.entry("dm")?.step)

        ledger.record("dm", 8L, "r2", T + DmReAckLedger.FLOOR_MS + 1)
        assertEquals(0, ledger.entry("dm")?.step)
        assertEquals(8L, ledger.entry("dm")?.rootTag)
    }

    @Test
    fun anEntryAgesOutATtlAfterItsLastReceipt() {
        val ledger = DmReAckLedger(ttlMs = HOUR)
        ledger.record("dm", 7L, "r1", T)

        assertNull(ledger.heldReceipt("dm", 7L, T + HOUR))
        assertNull("and is dropped", ledger.entry("dm"))
        assertEquals(0, ledger.size)
    }

    /** Our clock stepped back past the receipt's stamp: read as past the floor, so the re-arm is not held back by the jump. */
    @Test
    fun aClockSteppedBackBeforeTheReceiptReSeals() {
        val ledger = DmReAckLedger()
        ledger.record("dm", 7L, "r1", T)

        assertNull(ledger.heldReceipt("dm", 7L, T - 1))
        assertNull("and the entry is dropped", ledger.entry("dm"))
    }

    @Test
    fun theBoundEvictsTheOldestReceiptFirst() {
        val ledger = DmReAckLedger(cap = 3)
        ledger.record("a", 7L, "ra", T)
        ledger.record("b", 7L, "rb", T + 1)
        ledger.record("c", 7L, "rc", T + 2)
        // A re-seal of the oldest moves it to the newest end.
        ledger.record("a", 7L, "ra2", T + 3)
        ledger.record("d", 7L, "rd", T + 4)

        assertEquals(3, ledger.size)
        assertNull("b was the oldest receipt", ledger.entry("b"))
        assertEquals("ra2", ledger.entry("a")?.receiptId)
        assertEquals("rd", ledger.heldReceipt("d", 7L, T + 5))
    }

    private companion object {
        const val T = 1_000_000_000L
        const val MIN = 60_000L
        const val HOUR = 60 * MIN
    }
}
