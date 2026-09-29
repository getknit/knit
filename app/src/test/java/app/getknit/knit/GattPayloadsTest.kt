package app.getknit.knit

import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.bluetooth.BleAdvertPayload
import app.getknit.knit.mesh.bluetooth.GattPayloads
import app.getknit.knit.mesh.bluetooth.GattPayloads.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [GattPayloads] — whose GATT payload the transport reads and when: once per address, one at a time,
 * and again only after a wait or a failed dial. Case for case the iOS port's `GattPayloadsTests.swift` (knit-ios ADR
 * 2026-09.xzpt), plus the address cap this port adds.
 */
class GattPayloadsTest {
    private val reads =
        GattPayloads(GattPayloads.Configuration(readTimeoutMs = 100, retryMs = 1000, strangerRetryMs = 5000, maxAddresses = 3))
    private val phone = "phone"
    private val other = "other"
    private val payload = BleAdvertPayload.Parsed(NodeId.derive("peer-2"), capabilities = 0xFF, digestCue = 0, psm = 0x81)

    @Test
    fun theL2capConnectWatchdogBoundsARead() {
        val defaults = GattPayloads.Configuration()
        assertEquals(12_000L, defaults.readTimeoutMs)
        assertEquals(30_000L, defaults.retryMs)
        assertEquals(600_000L, defaults.strangerRetryMs)
        assertEquals(64, defaults.maxAddresses)
    }

    @Test
    fun anAddressIsReadOnceAndWhatItServedIsKept() {
        assertNull(reads.payload(of = phone))
        assertTrue(reads.begin(phone, now = 0))
        assertEquals(phone, reads.current?.address)
        assertEquals(100L, reads.current?.deadline)
        reads.finish(phone, Outcome.Read(payload), now = 40)
        assertNull(reads.current)
        assertEquals(payload, reads.payload(of = phone))
        assertFalse("read once", reads.begin(phone, now = 50))
    }

    @Test
    fun oneReadRunsAtATime() {
        assertTrue(reads.begin(phone, now = 0))
        assertFalse(reads.begin(other, now = 10))
        reads.finish(phone, Outcome.Failed, now = 20)
        assertTrue(reads.begin(other, now = 30))
    }

    @Test
    fun aFailedReadWaitsItsRetryAndAStrangerWaitsLonger() {
        assertTrue(reads.begin(phone, now = 0))
        reads.finish(phone, Outcome.Failed, now = 0)
        assertFalse(reads.begin(phone, now = 999))
        assertTrue(reads.begin(phone, now = 1000))
        reads.finish(phone, Outcome.Stranger, now = 1000)
        assertFalse(reads.begin(phone, now = 5999))
        assertTrue(reads.begin(phone, now = 6000))
        assertNull(reads.payload(of = phone))
    }

    @Test
    fun anOutcomeForAReadGivenUpOnIsIgnored() {
        assertTrue(reads.begin(phone, now = 0))
        reads.finish(other, Outcome.Read(payload), now = 10)
        assertEquals(phone, reads.current?.address)
        assertNull(reads.payload(of = other))
    }

    @Test
    fun aCancelledReadLeavesNoWait() {
        assertTrue(reads.begin(phone, now = 0))
        reads.cancel()
        assertNull(reads.current)
        assertTrue(reads.begin(phone, now = 1))
    }

    @Test
    fun aForgottenAddressIsReadAgainAtOnce() {
        assertTrue(reads.begin(phone, now = 0))
        reads.finish(phone, Outcome.Read(payload), now = 0)
        reads.forget(phone)
        assertNull(reads.payload(of = phone))
        assertTrue(reads.begin(phone, now = 1))
        reads.finish(phone, Outcome.Stranger, now = 1)
        reads.forget(phone)
        assertTrue("forgetting ends the wait too", reads.begin(phone, now = 2))
    }

    @Test
    fun forgettingTheAddressBeingReadLetsTheReadRunOn() {
        assertTrue(reads.begin(phone, now = 0))
        reads.forget(phone)
        assertEquals(phone, reads.current?.address)
        reads.finish(phone, Outcome.Read(payload), now = 5)
        assertEquals(payload, reads.payload(of = phone))
    }

    @Test
    fun theLeastRecentlyUsedAddressGoesPastTheCap() {
        // An iPhone's address rotates: the payloads of addresses it no longer uses must not pile up.
        listOf("a", "b", "c").forEach { read(it, now = 0) }
        assertEquals(3, reads.payloadCount)
        reads.payload(of = "a") // a is still advertising; b is the eldest now
        read("d", now = 0)
        assertEquals(3, reads.payloadCount)
        assertEquals(payload, reads.payload(of = "a"))
        assertNull(reads.payload(of = "b"))
        assertEquals(payload, reads.payload(of = "d"))
    }

    @Test
    fun theWaitsAreCappedToo() {
        listOf("a", "b", "c", "d").forEach {
            assertTrue(reads.begin(it, now = 0))
            reads.finish(it, Outcome.Stranger, now = 0)
        }
        assertTrue("the eldest wait went past the cap", reads.begin("a", now = 1))
        reads.cancel()
        assertFalse(reads.begin("d", now = 1))
    }

    private fun read(
        address: String,
        now: Long,
    ) {
        assertTrue(reads.begin(address, now))
        reads.finish(address, Outcome.Read(payload), now)
    }
}
