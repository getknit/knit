package app.getknit.knit

import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.ConnectFailReason
import app.getknit.knit.mesh.bluetooth.AdvertRotation
import app.getknit.knit.mesh.bluetooth.BleAdvertPayload
import app.getknit.knit.mesh.bluetooth.DialPlan
import app.getknit.knit.mesh.bluetooth.GattPayloads
import app.getknit.knit.mesh.bluetooth.GattPayloads.Outcome
import app.getknit.knit.mesh.crypto.BlindTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [GattPayloads] — whose GATT payload the transport reads and when: once per address, one at a time,
 * and again only after a wait or a failed dial. Case for case the iOS port's `GattPayloadsTests.swift` (knit-ios ADR
 * 2026-09.xzpt), plus the address cap, the budget across addresses and the yield to a dial this port adds (#154). A
 * blinded payload's rules (ADR 2026-11.89gg) mirror knit-ios ADR 2026-11.prsp's `GattPayloadsTests` case for case: rule 2
 * on tags, rule 4's edge, its clock and jitter, the linked exemption; plus this port's blinded refund, rule 1 on a tag
 * dial and the seal forget left clear-only.
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
        assertEquals(payload, reads.heard(phone).payload)
        // Wall-clock time between scan windows never counts: only what scanned() is told the scan ran.
        assertEquals(emptyList<String>(), reads.scanned(599, linked = emptyList()))
        assertEquals(listOf(phone), reads.scanned(1, linked = emptyList()))
        assertNull("nothing to hear once forgotten", reads.heard(phone).payload)
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
        assertEquals(GattPayloads.Refund.ID, budgeted.confirmed("i1", payload.nodeId, now = 10))
        assertEquals(0, budgeted.budgetUsed(now = 10))
        assertNull("refunded once", budgeted.confirmed("i1", payload.nodeId, now = 20))
        strangerAt("r1", now = 30)
        assertNull("a stranger's charge is never refunded", budgeted.confirmed("r1", payload.nodeId, now = 40))
        assertEquals(1, budgeted.budgetUsed(now = 40))
    }

    @Test
    fun aHelloNamingAnotherNodeOrAtAnotherAddressRefundsNothing() {
        readOn(budgeted, "i1", now = 0)
        assertNull(budgeted.confirmed("i1", NodeId.derive("someone-else"), now = 10))
        assertNull("a real node linked elsewhere vouches for no read here", budgeted.confirmed("i2", payload.nodeId, now = 10))
        assertEquals(1, budgeted.budgetUsed(now = 10))
    }

    @Test
    fun aRefundAfterTheChargeAgedOutIsANoOp() {
        readOn(budgeted, "i1", now = 0)
        assertNull(budgeted.confirmed("i1", payload.nodeId, now = 1000))
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
            assertEquals(GattPayloads.Refund.ID, budgeted.confirmed(address, payload.nodeId, now = i * 100L + 50))
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

    // --- A blinded payload (ADR 2026-11.89gg; knit-ios GattPayloadsTests' "A blinded payload") ---

    private var wall = 1_800_000_000_000L // mid-slot on the wall clock
    private val edge = (BlindTag.slotOf(wall) + 1) * BlindTag.SLOT_MS

    private fun tagOf(seed: Int): String =
        NodeId.fromBytes(
            BlindTag.tag(ByteArray(BlindTag.KEY_BYTES) { seed.toByte() }, NodeId.toBytes(payload.nodeId), slot = 1, restart = 0),
        )

    private val tag = tagOf(7)
    private val nextTag = tagOf(8)
    private val blinded =
        BleAdvertPayload.Parsed(
            tag,
            capabilities = 0xFFF,
            digestCue = 0,
            psm = 0x81,
            flags = BleAdvertPayload.FLAG_BLINDED or BleAdvertPayload.FLAG_DIALS_GATT_PEERS,
        )

    private fun blindReads(
        configuration: GattPayloads.Configuration = GattPayloads.Configuration(),
        seed: Long = 0L,
    ) = GattPayloads(configuration, wallMs = { wall }, jitterSeed = seed)

    private fun readBlinded(
        payloads: GattPayloads,
        address: String = phone,
        now: Long = 0,
    ) {
        assertTrue(payloads.begin(address, now))
        payloads.finish(address, Outcome.Read(blinded), now)
    }

    @Test
    fun aBlindedPayloadIsComparedWithTheHellosToldFormOrItsAnnouncedTagNeverTheId() {
        val r = blindReads()
        readBlinded(r)
        val someone = NodeId.derive("someone")
        assertFalse("its `t` is the payload's tag", r.helloNamed(someone, told = tag, announced = null, address = phone))
        assertFalse("the tag it announced", r.helloNamed(someone, told = null, announced = tag, address = phone))
        assertEquals(blinded, r.payload(of = phone))
        assertTrue("it rotated since the read", r.helloNamed(someone, told = nextTag, announced = null, address = phone))
        assertNull(r.payload(of = phone))
        readBlinded(r)
        assertTrue(
            "a HELLO with no `t` airs its id now, whatever it is called",
            r.helloNamed(tag, told = null, announced = null, address = phone),
        )
    }

    @Test
    fun aClearPayloadIsStillComparedWithTheId() {
        read(phone, now = 0)
        assertFalse("the id it names, whatever the `t`", reads.helloNamed(payload.nodeId, told = tag, announced = null, address = phone))
        assertTrue(reads.helloNamed(NodeId.derive("peer-3"), told = payload.nodeId, announced = null, address = phone))
        assertNull(reads.payload(of = phone))
        assertFalse("nothing held, nothing forgotten", reads.helloNamed(payload.nodeId, told = null, announced = null, address = other))
    }

    @Test
    fun aBlindedPayloadIsForgottenPastItsSlotEdgeWithSlackAndJitter() {
        val defaults = GattPayloads.Configuration()
        assertEquals("an announced rotation's 30 s, and 5 s", 35_000L, defaults.blindedEdgeSlackMs)
        assertEquals(AdvertRotation.MIN_LEAD_MS + GattPayloads.WRITER_CLOCK_SLACK_MS, defaults.blindedEdgeSlackMs)
        assertEquals(30_000L, defaults.blindedEdgeJitterMs)
        val r = blindReads()
        readBlinded(r)
        assertTrue(r.begin(other, now = 0))
        r.finish(other, Outcome.Read(payload), now = 0)
        val inSlack = r.forgetPastEdge(emptySet(), edge + 34_999)
        assertEquals("the slack: an announced rotation may land 30 s late", emptyList<String>(), inSlack)
        assertEquals("by the jitter's end, once", listOf(phone), r.forgetPastEdge(emptySet(), edge + 65_000))
        assertNull(r.payload(of = phone))
        assertEquals("a clear payload is rule 3's alone", payload, r.payload(of = other))
        assertTrue("read again at its next advert", r.begin(phone, now = 1))
    }

    @Test
    fun aBlindedPayloadReadBeforeTheClockWentBackIsForgotten() {
        val r = blindReads()
        readBlinded(r, phone)
        readBlinded(r, other)
        assertEquals("in order", listOf(other, phone), r.forgetPastEdge(emptySet(), wall - 1))
    }

    @Test
    fun eachReadDrawsItsOwnJitterFromTheSeed() {
        val forgets =
            (0L until 8L).map { seed ->
                val r = blindReads(GattPayloads.Configuration(blindedEdgeSlackMs = 0), seed)
                readBlinded(r)
                (edge..edge + 30_000L step 1_000L).firstOrNull { r.forgetPastEdge(emptySet(), it).isNotEmpty() } ?: -1L
            }
        assertFalse("every read is forgotten inside the jitter", -1L in forgets)
        assertTrue("and not all at once", forgets.toSet().size > 1)
        // Per read, not per reader: one seed's second read draws anew.
        val r = blindReads(GattPayloads.Configuration(blindedEdgeSlackMs = 0), seed = 3)
        val draws =
            (0 until 4).map {
                readBlinded(r)
                (edge..edge + 30_000L step 100L).first { w -> r.forgetPastEdge(emptySet(), w).isNotEmpty() }
            }
        assertTrue(draws.toSet().size > 1)
    }

    @Test
    fun aLinkedAddressKeepsItsBlindedPayloadUntilTheLinkEnds() {
        val r = blindReads()
        readBlinded(r)
        assertEquals(emptyList<String>(), r.forgetPastEdge(setOf(phone), edge + 60_000))
        assertEquals(blinded, r.payload(of = phone))
        assertEquals("the first check after the link ends", listOf(phone), r.forgetPastEdge(emptySet(), edge + 65_000))
    }

    @Test
    fun aForgottenBlindedPayloadTakesItsEdgeWithIt() {
        val r = blindReads()
        readBlinded(r)
        r.forget(phone)
        assertEquals(emptyList<String>(), r.forgetPastEdge(emptySet(), edge + 60_000))
        // A clear payload read at the same address afterwards keeps nothing of the old span: no edge forgets it.
        assertTrue(r.begin(phone, now = 1))
        r.finish(phone, Outcome.Read(payload), now = 1)
        assertEquals(emptyList<String>(), r.forgetPastEdge(emptySet(), edge + 65_000))
        assertEquals(payload, r.payload(of = phone))
    }

    @Test
    fun anAdvertPastTheEdgeIsNotSightedByTheOldTagButReadAgain() {
        val r = blindReads()
        readBlinded(r)
        // It carries the slot it was read in, which a dial target built from it is judged by (GattTargets.withheld).
        val readIn = GattPayloads.Heard(blinded, readSlot = BlindTag.slotOf(wall))
        assertEquals(readIn, r.heard(phone))
        wall = edge + 65_000
        assertEquals("a linked address keeps it", readIn, r.heard(phone, spared = setOf(phone)))
        assertEquals(GattPayloads.Heard(null, pastEdge = true), r.heard(phone))
        assertNull(r.payload(of = phone))
        assertEquals("forgotten once", GattPayloads.Heard(null), r.heard(phone))
        assertTrue("its advert is due a read", r.begin(phone, now = 1))
    }

    @Test
    fun aClearPayloadIsNeverPastAnEdge() {
        val r = blindReads()
        assertTrue(r.begin(phone, now = 0))
        r.finish(phone, Outcome.Read(payload), now = 0)
        wall = edge + 10 * BlindTag.SLOT_MS
        assertEquals(GattPayloads.Heard(payload), r.heard(phone))
        assertEquals(emptyList<String>(), r.forgetPastEdge(emptySet()))
    }

    // --- The blinded refund (Android's budget, #154; ADR 2026-11.89gg) ---

    private val blindBudget =
        GattPayloads(
            GattPayloads.Configuration(
                readTimeoutMs = 100,
                retryMs = 60_000,
                strangerRetryMs = 60_000,
                budgetReads = 2,
                budgetWindowMs = 1000,
            ),
            wallMs = { wall },
        )

    @Test
    fun aBlindedChargeIsRefundedByAProvenLinkThatShowsItsTag() {
        readBlinded(blindBudget, "i1")
        assertEquals(GattPayloads.Refund.TAG, blindBudget.confirmed("i1", payload.nodeId, tag = tag, proven = true, now = 10))
        assertEquals(0, blindBudget.budgetUsed(now = 10))
        assertNull("once", blindBudget.confirmed("i1", payload.nodeId, tag = tag, proven = true, now = 20))
    }

    @Test
    fun aBlindedChargeIsNotRefundedUnprovenByAnotherTagOrByTheId() {
        readBlinded(blindBudget, "i1")
        assertNull("unproven", blindBudget.confirmed("i1", payload.nodeId, tag = tag, proven = false, now = 10))
        assertNull("another tag", blindBudget.confirmed("i1", payload.nodeId, tag = nextTag, proven = true, now = 10))
        assertNull("no tag shown", blindBudget.confirmed("i1", payload.nodeId, tag = null, proven = true, now = 10))
        assertNull("a tag is never compared with an id", blindBudget.confirmed("i1", tag, tag = null, proven = true, now = 10))
        assertNull("elsewhere", blindBudget.confirmed("i2", payload.nodeId, tag = tag, proven = true, now = 10))
        assertEquals(1, blindBudget.budgetUsed(now = 10))
    }

    @Test
    fun aClearChargeKeepsItsIdMatchWhateverTagIsShown() {
        assertTrue(blindBudget.begin("c1", now = 0))
        blindBudget.finish("c1", Outcome.Read(payload), now = 0)
        assertNull("an id is never compared with a tag", blindBudget.confirmed("c1", tag, tag = payload.nodeId, proven = true, now = 5))
        assertEquals(GattPayloads.Refund.ID, blindBudget.confirmed("c1", payload.nodeId, tag = tag, proven = false, now = 5))
    }

    @Test
    fun aForgottenBlindedPayloadIsReadAgainAndTheForgetReasonsAreUnchanged() {
        // Rule 1 on a tag dial (ADR 2026-11.89gg) is transport wiring with no host rig (`failConnect` now gets the dialed
        // device for every dial), device-verified only. This pins what it rests on: the reasons that forget, of which a
        // HELLO refusal is none (it says nothing of the PSM), and that a forgotten blinded payload is read again.
        assertTrue(DialPlan.forgetsGattPayload(ConnectFailReason.TIMEOUT))
        assertFalse(DialPlan.forgetsGattPayload(ConnectFailReason.HANDSHAKE))
        val r = blindReads()
        readBlinded(r)
        r.forget(phone)
        assertNull(r.payload(of = phone))
        assertTrue("its next advert reads the new PSM", r.begin(phone, now = 1))
    }

    @Test
    fun theSealForgetLeavesABlindedPayloadAlone() {
        val r = blindReads()
        readBlinded(r)
        assertFalse("a tag dial's silence forgets nothing (§7 is the clear read's)", r.forgetUnsealed(phone))
        assertEquals(blinded, r.payload(of = phone))
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
