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
 * 2026-09.xzpt), plus the address cap, the budget across addresses and the yield to a dial this port adds (#154).
 */
class GattPayloadsTest {
    private val reads =
        GattPayloads(
            GattPayloads.Configuration(
                readTimeoutMs = 100,
                retryMs = 1000,
                strangerRetryMs = 5000,
                quietMs = 600,
                maxAddresses = 3,
                // Wide enough that no other case meets it: the budget has cases of its own, on [budgeted].
                budgetReads = 100,
                budgetWindowMs = 10_000,
            ),
        )

    // Two charged reads per second, and every address waits a minute after a stranger, so only the budget refuses.
    private val budgeted =
        GattPayloads(
            GattPayloads.Configuration(
                readTimeoutMs = 100,
                retryMs = 60_000,
                strangerRetryMs = 60_000,
                quietMs = 600,
                maxAddresses = 64,
                budgetReads = 2,
                budgetWindowMs = 1000,
            ),
        )
    private val phone = "phone"
    private val other = "other"
    private val payload = BleAdvertPayload.Parsed(NodeId.derive("peer-2"), capabilities = 0xFF, digestCue = 0, psm = 0x81)

    @Test
    fun theL2capConnectWatchdogBoundsARead() {
        val defaults = GattPayloads.Configuration()
        assertEquals(12_000L, defaults.readTimeoutMs)
        assertEquals(30_000L, defaults.retryMs)
        assertEquals(600_000L, defaults.strangerRetryMs)
        assertEquals(60_000L, defaults.quietMs)
        assertEquals(64, defaults.maxAddresses)
        assertEquals(12, defaults.budgetReads)
        assertEquals(600_000L, defaults.budgetWindowMs)
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

    @Test
    fun aPayloadUnheardForTheQuietSpanOfScanningIsForgotten() {
        read(phone, now = 0)
        assertEquals(emptyList<String>(), reads.scanned(599, linked = emptyList()))
        assertEquals(payload, reads.payload(of = phone))
        assertEquals(listOf(phone), reads.scanned(1, linked = emptyList()))
        assertNull(reads.payload(of = phone))
        assertTrue("read again at its next advert", reads.begin(phone, now = 1))
    }

    @Test
    fun anAdvertRestartsTheQuietClockAndOnlyScanningTimeCounts() {
        read(phone, now = 0)
        reads.scanned(500, linked = emptyList())
        assertEquals(payload, reads.heard(phone))
        // Wall-clock time between scan windows never counts: only what scanned() is told the scan ran.
        assertEquals(emptyList<String>(), reads.scanned(599, linked = emptyList()))
        assertEquals(listOf(phone), reads.scanned(1, linked = emptyList()))
        assertNull("nothing to hear once forgotten", reads.heard(phone))
    }

    @Test
    fun aLinkedAddressIsNeverQuiet() {
        read(phone, now = 0)
        read(other, now = 0)
        assertEquals(listOf(other), reads.scanned(10_000, linked = listOf(phone)))
        assertEquals(payload, reads.payload(of = phone))
        // The link counts as hearing it: the quiet span starts over when the link goes.
        assertEquals(emptyList<String>(), reads.scanned(599, linked = emptyList()))
        assertEquals(listOf(phone), reads.scanned(1, linked = emptyList()))
    }

    @Test
    fun aReadStartsTheQuietClockAtItsEnd() {
        reads.scanned(5_000, linked = emptyList())
        read(phone, now = 0)
        assertEquals(emptyList<String>(), reads.scanned(599, linked = emptyList()))
        assertEquals(listOf(phone), reads.scanned(1, linked = emptyList()))
    }

    @Test
    fun pastTheBudgetANewStrangerAddressIsNotDue() {
        // A radio rotating random addresses under 0xFE30: each address is new, so no per-address wait holds it.
        strangerAt("r1", now = 0)
        strangerAt("r2", now = 10)
        assertEquals(2, budgeted.budgetUsed(now = 20))
        assertTrue(budgeted.budgetSpent(now = 20))
        assertFalse("the budget is spent", budgeted.begin("r3", now = 20))
        assertNull(budgeted.current)
    }

    @Test
    fun theBudgetFreesAsTheWindowSlides() {
        strangerAt("r1", now = 0)
        strangerAt("r2", now = 500)
        assertFalse(budgeted.begin("r3", now = 999))
        assertEquals("the first charge left the window", 1, budgeted.budgetUsed(now = 1000))
        assertTrue(budgeted.begin("r3", now = 1000))
        budgeted.finish("r3", Outcome.Failed, now = 1000)
        assertFalse(budgeted.begin("r4", now = 1499))
        assertTrue(budgeted.begin("r4", now = 1500))
    }

    @Test
    fun aReadThatFoundAPayloadIsCharged() {
        // The payload is unauthenticated: a read that found one proves nothing until a link vouches for it.
        assertTrue(budgeted.begin("i1", now = 0))
        budgeted.finish("i1", Outcome.Read(payload), now = 0)
        assertEquals(1, budgeted.budgetUsed(now = 0))
    }

    @Test
    fun aConfirmingHelloRefundsTheReadOnce() {
        readOn(budgeted, "i1", now = 0)
        assertTrue(budgeted.confirmed("i1", payload.nodeId, now = 10))
        assertEquals(0, budgeted.budgetUsed(now = 10))
        assertFalse("refunded once", budgeted.confirmed("i1", payload.nodeId, now = 20))
        strangerAt("r1", now = 30)
        assertFalse("a stranger's charge is never refunded", budgeted.confirmed("r1", payload.nodeId, now = 40))
        assertEquals(1, budgeted.budgetUsed(now = 40))
    }

    @Test
    fun aHelloNamingAnotherNodeOrAtAnotherAddressRefundsNothing() {
        readOn(budgeted, "i1", now = 0)
        assertFalse(budgeted.confirmed("i1", NodeId.derive("someone-else"), now = 10))
        assertFalse("a real node linked elsewhere vouches for no read here", budgeted.confirmed("i2", payload.nodeId, now = 10))
        assertEquals(1, budgeted.budgetUsed(now = 10))
    }

    @Test
    fun aRefundAfterTheChargeAgedOutIsANoOp() {
        readOn(budgeted, "i1", now = 0)
        assertFalse(budgeted.confirmed("i1", payload.nodeId, now = 1000))
        assertEquals("never below zero", 0, budgeted.budgetUsed(now = 1000))
        strangerAt("r1", now = 1000)
        assertEquals(1, budgeted.budgetUsed(now = 1000))
    }

    @Test
    fun aForgerServingValidPayloadsAtRotatingAddressesSpendsTheBudget() {
        // A payload naming an id above every real one is never dialed, so no HELLO ever refunds its reads.
        readOn(budgeted, "f1", now = 0)
        readOn(budgeted, "f2", now = 20)
        assertTrue(budgeted.budgetSpent(now = 40))
        assertFalse(budgeted.begin("f3", now = 40))
    }

    @Test
    fun anHonestIphoneThatLinksCostsNothingNet() {
        // Its address rotates about every 15 min; each new address is read, dialed and linked.
        listOf("i1", "i2", "i3", "i4").forEachIndexed { i, address ->
            readOn(budgeted, address, now = i * 100L)
            assertTrue(budgeted.confirmed(address, payload.nodeId, now = i * 100L + 50))
        }
        assertEquals(0, budgeted.budgetUsed(now = 400))
        assertTrue(budgeted.begin("i5", now = 400))
    }

    @Test
    fun aCancelledReadIsNotCharged() {
        repeat(3) {
            assertTrue(budgeted.begin("r$it", now = 0))
            budgeted.cancel()
        }
        assertEquals(0, budgeted.budgetUsed(now = 0))
    }

    @Test
    fun aForgetChargesNothingAndTheReReadIsChargedOnItsOwn() {
        // A dial failure, a HELLO naming another node and the quiet rule all forget for free; the read they make due is
        // charged when it ends, and the earlier read's charge stays to age out.
        readOn(budgeted, "held", now = 0)
        budgeted.forget("held")
        assertEquals(1, budgeted.budgetUsed(now = 0))
        readOn(budgeted, "held", now = 10)
        assertEquals(2, budgeted.budgetUsed(now = 10))
        assertEquals(1, budgeted.budgetUsed(now = 1000))
        assertEquals(listOf("held"), budgeted.scanned(600, linked = emptyList()))
        assertEquals("a quiet forget is free", 1, budgeted.budgetUsed(now = 1000))
        assertEquals(0, budgeted.budgetUsed(now = 1010))
    }

    @Test
    fun aDueReadYieldsToARipeDial() {
        reads.yieldTo(dial = true)
        assertFalse("a dial is waiting", reads.begin(phone, now = 0))
        reads.yieldTo(dial = false)
        assertTrue(reads.begin(phone, now = 0))
        reads.yieldTo(dial = true)
        assertEquals("a read in flight runs on", phone, reads.current?.address)
        reads.finish(phone, Outcome.Read(payload), now = 10)
        assertEquals(payload, reads.payload(of = phone))
        assertFalse("and the next one waits for the dial", reads.begin(other, now = 10))
    }

    @Test
    fun aCancelClearsAWaitingDial() {
        reads.yieldTo(dial = true)
        reads.cancel()
        assertTrue(reads.begin(phone, now = 0))
    }

    // --- The seal flag's one-shot forget (#286 contract §7, revision 1) ---

    private fun readPayload(
        address: String,
        served: BleAdvertPayload.Parsed,
        now: Long,
    ) {
        assertTrue(reads.begin(address, now))
        reads.finish(address, Outcome.Read(served), now)
    }

    @Test
    fun aPayloadWithoutTheSealFlagIsForgottenOnceAndItsReReadIsDue() {
        readPayload(phone, payload, now = 0)
        assertTrue(reads.forgetUnsealed(phone))
        assertNull(reads.payload(of = phone))
        assertTrue("the next advert reads it afresh", reads.begin(phone, now = 1))
    }

    @Test
    fun theSameUnsealedPayloadReadAgainIsNotForgottenAgain() {
        readPayload(phone, payload, now = 0)
        assertTrue(reads.forgetUnsealed(phone))
        // The peer really does not seal: the re-read serves the same node, PSM and flags (its cue moved meanwhile).
        readPayload(phone, payload.copy(digestCue = 7), now = 10)
        assertFalse("one re-read, not one per dial", reads.forgetUnsealed(phone))
        assertEquals(7, reads.payload(of = phone)?.digestCue)
    }

    @Test
    fun aDifferentUnsealedPayloadAtTheAddressIsForgottenOnceMore() {
        readPayload(phone, payload, now = 0)
        assertTrue(reads.forgetUnsealed(phone))
        readPayload(phone, payload.copy(psm = 0x83), now = 10)
        assertTrue("a new PSM is a new payload", reads.forgetUnsealed(phone))
    }

    @Test
    fun aPayloadThatCarriesTheSealFlagIsNeverForgottenByIt() {
        readPayload(phone, payload.copy(flags = BleAdvertPayload.FLAG_LINK_SEAL), now = 0)
        assertFalse(reads.forgetUnsealed(phone))
        assertEquals(BleAdvertPayload.FLAG_LINK_SEAL, reads.payload(of = phone)?.flags)
        assertFalse("nothing held, nothing forgotten", reads.forgetUnsealed(other))
    }

    @Test
    fun theUpgradedPeersReReadIsKept() {
        readPayload(phone, payload, now = 0)
        assertTrue(reads.forgetUnsealed(phone))
        readPayload(phone, payload.copy(flags = BleAdvertPayload.FLAG_LINK_SEAL), now = 10)
        assertFalse(reads.forgetUnsealed(phone))
        assertTrue(reads.payload(of = phone)!!.linkSeal)
    }

    private fun readOn(
        payloads: GattPayloads,
        address: String,
        now: Long,
    ) {
        assertTrue(payloads.begin(address, now))
        payloads.finish(address, Outcome.Read(payload), now)
    }

    private fun strangerAt(
        address: String,
        now: Long,
    ) {
        assertTrue(budgeted.begin(address, now))
        budgeted.finish(address, Outcome.Stranger, now)
    }

    private fun read(
        address: String,
        now: Long,
    ) {
        assertTrue(reads.begin(address, now))
        reads.finish(address, Outcome.Read(payload), now)
    }
}
