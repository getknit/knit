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
        listOf(FrameType.TYPING, FrameType.BLOB_REQ, FrameType.KEY_REQ).forEach { assertFalse(it, DoorbellPolicy.rings(it)) }
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
}
