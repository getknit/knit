package app.getknit.knit

import app.getknit.knit.mesh.bluetooth.DoorbellPolicy
import app.getknit.knit.mesh.bluetooth.DoorbellPolicy.RING_INTERVAL_MS
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** Unit tests for [DoorbellPolicy] — which links ring, which frames, and when (ADR 2026-09.dqvb). */
class DoorbellPolicyTest {
    @Test
    fun theGattIdentifiersArePinned() {
        // Cross-platform law with the iOS port (knit-ios `LinkTests.theGATTIdentifiersArePinned`): never change them.
        assertEquals("0000fe30-0000-1000-8000-00805f9b34fb", DoorbellPolicy.SERVICE_UUID.toString())
        assertEquals("f34c056b-5830-4243-a888-01f92f49e446", DoorbellPolicy.DOORBELL_UUID.toString())
        assertEquals("848eedcd-a2e3-4fb2-86f9-e2c80821a497", DoorbellPolicy.PAYLOAD_UUID.toString())
    }

    @Test
    fun onlyAHelloCarryingTheBitIsRung() {
        assertTrue(DoorbellPolicy.serves(0x9L or Protocol.CAP_DOORBELL)) // the iPhone port's caps plus the bit
        assertFalse(DoorbellPolicy.serves(0x9L))
        assertFalse(DoorbellPolicy.serves(Protocol.LOCAL_CAPABILITIES)) // an Android phone never is
    }

    @Test
    fun everyFrameRingsButTheLinksUpkeep() {
        listOf(
            FrameType.CHAT,
            FrameType.RECEIPT,
            FrameType.REACTION,
            FrameType.PROFILE,
            FrameType.GROUP_UPDATE,
            FrameType.GROUP_LEAVE,
            "somefuturetype",
        ).forEach { assertTrue(it, DoorbellPolicy.rings(it)) }
        listOf(FrameType.TYPING, FrameType.BLOB_REQ, FrameType.BLOB_HAVE, FrameType.BLOB_HAS, FrameType.KEY_REQ)
            .forEach { assertFalse(it, DoorbellPolicy.rings(it)) }
        assertTrue("a frame whose type could not be read rings", DoorbellPolicy.rings(null))
    }

    @Test
    fun theFirstFrameRingsAtOnce() {
        val schedule = DoorbellPolicy.Schedule()
        assertTrue(schedule.afterFrame(1_000L))
        assertEquals(1_000L, schedule.rangAt)
        assertNull(schedule.dueAt)
    }

    @Test
    fun aBurstCostsARingAtItsStartAndOneAfterIt() {
        val schedule = DoorbellPolicy.Schedule()
        assertTrue(schedule.afterFrame(0L))
        assertFalse(schedule.afterFrame(100L))
        assertFalse(schedule.afterFrame(2_000L))
        assertFalse(schedule.afterFrame(4_900L))
        assertEquals(RING_INTERVAL_MS, schedule.dueAt)
        assertFalse("not due yet", schedule.onDue(RING_INTERVAL_MS - 1))
        assertTrue(schedule.onDue(RING_INTERVAL_MS))
        assertNull(schedule.dueAt)
        assertFalse("rung once, then nothing is owed", schedule.onDue(RING_INTERVAL_MS + 1))
    }

    @Test
    fun aFrameAfterTheIntervalRingsAtOnce() {
        val schedule = DoorbellPolicy.Schedule()
        assertTrue(schedule.afterFrame(0L))
        assertTrue(schedule.afterFrame(RING_INTERVAL_MS))
        assertNull(schedule.dueAt)
    }

    @Test
    fun aFrameThatBeatsTheOwedRingTakesItsPlace() {
        // The loop's timeout and a poke can land together: whichever runs first rings, and the other finds nothing owed.
        val schedule = DoorbellPolicy.Schedule()
        schedule.afterFrame(0L)
        schedule.afterFrame(1_000L)
        assertTrue(schedule.afterFrame(RING_INTERVAL_MS + 1))
        assertFalse(schedule.onDue(RING_INTERVAL_MS + 2))
    }

    @Test
    fun aSteadyStreamRingsAtMostTwelveTimesAMinute() {
        val schedule = DoorbellPolicy.Schedule()
        var rings = 0
        for (t in 0L until 60_000L step 250L) {
            if (schedule.onDue(t)) rings++
            if (schedule.afterFrame(t)) rings++
        }
        assertEquals(12, rings)
    }

    @Test
    fun aFailedLookupIsRetriedAMinuteLaterAndThreeTimesAtMost() {
        val lookups = DoorbellPolicy.Lookups()
        assertTrue(lookups.mayTry(0L))
        lookups.failed(0L)
        assertFalse(lookups.mayTry(DoorbellPolicy.LOOKUP_RETRY_MS - 1))
        assertTrue(lookups.mayTry(DoorbellPolicy.LOOKUP_RETRY_MS))
        lookups.failed(DoorbellPolicy.LOOKUP_RETRY_MS)
        lookups.failed(2 * DoorbellPolicy.LOOKUP_RETRY_MS)
        assertFalse(lookups.mayTry(10 * DoorbellPolicy.LOOKUP_RETRY_MS))
    }

    @Test
    fun aPutBackAfterTheAskIsAskedAgainAtOnce() {
        val balanced = DoorbellPolicy.Balanced()
        assertFalse("discovery's own update comes before the ask", balanced.onParams(420))
        balanced.asked()
        assertTrue("BlueZ's 420 ms put back", balanced.onParams(420))
        assertFalse(balanced.onParams(DoorbellPolicy.BALANCED_TIMEOUT_MS))
        assertFalse("the 5 s arrived, so the settle check asks nothing", balanced.onSettle())
    }

    @Test
    fun theSettleCheckAsksAgainWhenNoReportShowedTheFiveSeconds() {
        val silent = DoorbellPolicy.Balanced()
        assertFalse("nothing asked, nothing to settle", silent.onSettle())
        silent.asked()
        assertTrue("the hidden callback never came", silent.onSettle())

        val putBack = DoorbellPolicy.Balanced()
        putBack.asked()
        assertTrue(putBack.onParams(720))
        assertTrue("the re-ask did not land either", putBack.onSettle())
    }

    @Test
    fun theReasksAreBudgetedPerLookup() {
        val balanced = DoorbellPolicy.Balanced()
        balanced.asked()
        repeat(DoorbellPolicy.MAX_BALANCED_REASKS) { assertTrue(balanced.onParams(720)) }
        assertFalse("a central that keeps lowering it is not fought", balanced.onParams(720))
        assertFalse(balanced.onSettle())
        assertTrue("the next lookup's ask starts a fresh budget", DoorbellPolicy.Balanced().apply { asked() }.onParams(720))
    }

    @Test
    fun aFailedUpdateIsNeitherAPutBackNorAReading() {
        // The Pixel 3 on the rig, 01:03:11: the re-ask met the ask still in flight and failed with 0x2A, reporting the
        // 420 ms the link still ran at. That spent the last re-ask before a real put-back could need it.
        val balanced = DoorbellPolicy.Balanced()
        balanced.asked()
        assertTrue(balanced.onParams(420))
        assertFalse(balanced.onParams(420, succeeded = false))
        assertTrue("the budget is intact for a real put-back", balanced.onParams(420))

        val stuck = DoorbellPolicy.Balanced()
        stuck.asked()
        assertTrue(stuck.onParams(720))
        assertFalse(stuck.onParams(5_000, succeeded = false))
        assertTrue("a failure is not the 5 s, so the settle check still asks", stuck.onSettle())
    }

    // --- Two 0xFE30 services, and a lookup that finds nothing while the peer's server settles (ADR 2026-11.89gg) ---

    /** A service as the search sees it: its UUID and the characteristics (UUID → properties) it holds. */
    private data class Service(
        val uuid: UUID,
        val characteristics: Map<UUID, Int>,
    )

    private data class Found(
        val service: Service,
        val properties: Int,
    )

    private val other = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    private val writeNoResponse = 0x04 // BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE

    private fun find(
        services: List<Service>,
        uuid: UUID,
        accepts: (Found) -> Boolean = { true },
    ): Found? = DoorbellPolicy.characteristicIn(services, Service::uuid, { s -> s.characteristics[uuid]?.let { Found(s, it) } }, accepts)

    @Test
    fun aBlindingIphonesPayloadAndDoorbellAreFoundInTheirOwnServices() {
        // knit-ios ADR 2026-11.prsp: D (the doorbell alone) added first, P (the payload alone) beside it.
        val d = Service(DoorbellPolicy.SERVICE_UUID, mapOf(DoorbellPolicy.DOORBELL_UUID to writeNoResponse))
        val p = Service(DoorbellPolicy.SERVICE_UUID, mapOf(DoorbellPolicy.PAYLOAD_UUID to 0x02))
        assertEquals(p, find(listOf(d, p), DoorbellPolicy.PAYLOAD_UUID)?.service)
        assertEquals(d, find(listOf(p, d), DoorbellPolicy.DOORBELL_UUID) { it.properties and writeNoResponse != 0 }?.service)
        assertTrue(DoorbellPolicy.servesKnit(listOf(d, p), Service::uuid))
    }

    @Test
    fun aClearIphonesCombinedServiceStillServesBoth() {
        val both = mapOf(DoorbellPolicy.DOORBELL_UUID to writeNoResponse, DoorbellPolicy.PAYLOAD_UUID to 0x02)
        val combined = Service(DoorbellPolicy.SERVICE_UUID, both)
        assertEquals(combined, find(listOf(combined), DoorbellPolicy.PAYLOAD_UUID)?.service)
        assertEquals(combined, find(listOf(combined), DoorbellPolicy.DOORBELL_UUID)?.service)
    }

    @Test
    fun onlyAKnitServiceIsSearchedAndTheDoorbellMustTakeAWriteWithoutResponse() {
        val stranger = Service(other, mapOf(DoorbellPolicy.PAYLOAD_UUID to 0x02, DoorbellPolicy.DOORBELL_UUID to writeNoResponse))
        assertNull(find(listOf(stranger), DoorbellPolicy.PAYLOAD_UUID))
        assertFalse(DoorbellPolicy.servesKnit(listOf(stranger), Service::uuid))
        val writeOnly = Service(DoorbellPolicy.SERVICE_UUID, mapOf(DoorbellPolicy.DOORBELL_UUID to 0x08))
        val good = Service(DoorbellPolicy.SERVICE_UUID, mapOf(DoorbellPolicy.DOORBELL_UUID to writeNoResponse))
        val wnr: (Found) -> Boolean = { it.properties and writeNoResponse != 0 }
        assertNull(find(listOf(stranger, writeOnly), DoorbellPolicy.DOORBELL_UUID, wnr))
        val found = find(listOf(writeOnly, good), DoorbellPolicy.DOORBELL_UUID, wnr)
        assertEquals("whichever service holds the one that takes it", good, found?.service)
    }

    @Test
    fun theSettleBoundIsTenSeconds() {
        assertEquals(10_000L, DoorbellPolicy.DOORBELL_SETTLE_MS)
    }

    @Test
    fun nothingFoundWhileSettlingIsRetried() {
        assertEquals(DoorbellPolicy.Missing.RETRY, DoorbellPolicy.missing(failed = false, settlingSince = 5_000, now = 5_000))
        assertEquals(DoorbellPolicy.Missing.RETRY, DoorbellPolicy.missing(failed = false, settlingSince = 5_000, now = 14_999))
    }

    @Test
    fun nothingFoundPastTheBoundIsAbsent() {
        assertEquals(DoorbellPolicy.Missing.ABSENT, DoorbellPolicy.missing(failed = false, settlingSince = 5_000, now = 15_000))
        val unknown = DoorbellPolicy.missing(failed = false, settlingSince = null, now = 0)
        assertEquals("nothing to settle", DoorbellPolicy.Missing.ABSENT, unknown)
    }

    @Test
    fun aFailureIsAlwaysRetried() {
        assertEquals(DoorbellPolicy.Missing.RETRY, DoorbellPolicy.missing(failed = true, settlingSince = null, now = 0))
        assertEquals(DoorbellPolicy.Missing.RETRY, DoorbellPolicy.missing(failed = true, settlingSince = 0, now = 1_000_000))
    }
}
