package app.getknit.knit

import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetCallback.ADVERTISE_FAILED_INTERNAL_ERROR
import android.bluetooth.le.AdvertisingSetCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS
import android.bluetooth.le.AdvertisingSetCallback.ADVERTISE_SUCCESS
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.BluetoothLeAdvertiser
import app.getknit.knit.mesh.bluetooth.BleAdvertiser
import app.getknit.knit.mesh.bluetooth.BleConstants
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [BleAdvertiser.reassert] and the enable callback (#112): a live set is enabled again, a failed start is brought back
 * up with the newest bytes, and nothing raises a stopped set or reports a stale one. [BleAdvertiser.setInterval]
 * (ADR 2026-10.yvn6, amendment 3): a live set's interval moves in place — disable, parameters, enable — and stays on
 * the air whatever the stack makes of it. Robolectric for the framework's
 * real `AdvertiseData` / `AdvertisingSetParameters` builders; the advertiser and the set are mocks.
 */
@RunWith(RobolectricTestRunner::class)
class BleAdvertiserTest {
    private val callbacks = mutableListOf<AdvertisingSetCallback>()
    private val starts = mutableListOf<AdvertiseData>()
    private val enables = mutableListOf<Pair<Boolean, Int>>()
    private var advertiserPresent = true

    private val startParams = mutableListOf<AdvertisingSetParameters>()
    private val refusedIntervals = mutableListOf<Pair<Int, Int>>()

    private val radio =
        mockk<BluetoothLeAdvertiser>(relaxed = true).also { adv ->
            every { adv.startAdvertisingSet(any(), any(), any(), any(), any(), any<AdvertisingSetCallback>()) } answers {
                startParams += arg<AdvertisingSetParameters>(0)
                starts += arg<AdvertiseData>(1)
                callbacks += arg<AdvertisingSetCallback>(5)
            }
        }

    private var clock = 0L

    // The Coded set, the one whose interval moves.
    private val coded =
        BleAdvertiser(
            { radio.takeIf { advertiserPresent } },
            log = {},
            params = BleAdvertiser.codedParams(),
            onEnableStatus = { enabled, status -> enables += enabled to status },
            onIntervalRefused = { interval, status -> refusedIntervals += interval to status },
            now = { clock },
        )

    private val advertiser =
        BleAdvertiser(
            { radio.takeIf { advertiserPresent } },
            log = {},
            onEnableStatus = { enabled, status -> enables += enabled to status },
        )

    private fun liveSet(): AdvertisingSet {
        val set = mockk<AdvertisingSet>(relaxed = true)
        callbacks.last().onAdvertisingSetStarted(set, 0, ADVERTISE_SUCCESS)
        return set
    }

    private fun AdvertiseData.bytes(): ByteArray = serviceData.getValue(BleConstants.SERVICE_UUID)

    @Test
    fun aLiveSetIsEnabledAgain() {
        advertiser.update(byteArrayOf(1))
        val set = liveSet()
        assertTrue(advertiser.reassert())
        verify(exactly = 1) { set.enableAdvertising(true, 0, 0) }
        assertEquals("no second start for a live set", 1, starts.size)
    }

    @Test
    fun nothingIsAskedWhileAStartIsInFlight() {
        advertiser.update(byteArrayOf(1))
        assertFalse(advertiser.reassert())
        assertEquals(1, starts.size)
    }

    @Test
    fun aFailedStartIsBroughtBackUpWithTheNewestBytes() {
        advertiser.update(byteArrayOf(1))
        advertiser.update(byteArrayOf(2)) // parked while the start is in flight
        callbacks.last().onAdvertisingSetStarted(null, 0, ADVERTISE_FAILED_TOO_MANY_ADVERTISERS)
        assertTrue(advertiser.reassert())
        assertEquals(2, starts.size)
        assertArrayEquals(byteArrayOf(2), starts.last().bytes())
    }

    @Test
    fun aStoppedSetStaysDown() {
        advertiser.update(byteArrayOf(1))
        val set = liveSet()
        advertiser.stop()
        assertFalse(advertiser.reassert())
        verify(exactly = 0) { set.enableAdvertising(any(), any(), any()) }
        assertEquals(1, starts.size)
    }

    @Test
    fun aSetWhoseStartFailedStaysDownOnceStopped() {
        advertiser.update(byteArrayOf(1))
        callbacks.last().onAdvertisingSetStarted(null, 0, ADVERTISE_FAILED_TOO_MANY_ADVERTISERS)
        advertiser.stop()
        assertFalse(advertiser.reassert())
        assertEquals(1, starts.size)
    }

    @Test
    fun noAdvertiserNoAsk() {
        advertiser.update(byteArrayOf(1))
        callbacks.last().onAdvertisingSetStarted(null, 0, ADVERTISE_FAILED_TOO_MANY_ADVERTISERS)
        advertiserPresent = false // the adapter went off
        assertFalse(advertiser.reassert())
        assertEquals(1, starts.size)
    }

    @Test
    fun theLiveSetsEnableOutcomesAreReportedAndAStaleSetsAreNot() {
        advertiser.update(byteArrayOf(1))
        val set = liveSet()
        val cb = callbacks.last()
        cb.onAdvertisingEnabled(set, true, ADVERTISE_FAILED_INTERNAL_ERROR) // the stack's refused re-enable
        cb.onAdvertisingEnabled(set, true, ADVERTISE_SUCCESS)
        cb.onAdvertisingEnabled(set, false, ADVERTISE_SUCCESS)
        cb.onAdvertisingEnabled(mockk(relaxed = true), true, ADVERTISE_FAILED_INTERNAL_ERROR) // not ours
        advertiser.stop()
        cb.onAdvertisingEnabled(set, true, ADVERTISE_FAILED_INTERNAL_ERROR) // late, after the stop
        assertEquals(
            listOf(true to ADVERTISE_FAILED_INTERNAL_ERROR, true to ADVERTISE_SUCCESS, false to ADVERTISE_SUCCESS),
            enables,
        )
    }

    @Test
    fun aLiveSetsIntervalMovesInPlace() {
        coded.update(byteArrayOf(1))
        val set = liveSet()
        val cb = callbacks.last()
        coded.setInterval(FAST)
        verify(exactly = 1) { set.enableAdvertising(false, 0, 0) }
        assertFalse("the change has the set off on purpose", coded.reassert())
        cb.onAdvertisingEnabled(set, false, ADVERTISE_SUCCESS)
        verify(exactly = 1) { set.setAdvertisingParameters(match { it.interval == FAST && it.primaryPhy == CODED_PHY }) }
        cb.onAdvertisingParametersUpdated(set, 1, ADVERTISE_SUCCESS)
        verify(exactly = 1) { set.enableAdvertising(true, 0, 0) }
        cb.onAdvertisingEnabled(set, true, ADVERTISE_SUCCESS)
        assertEquals("our own disable is not reported", listOf(true to ADVERTISE_SUCCESS), enables)
        assertEquals("same set, no restart", 1, starts.size)
        assertTrue(coded.reassert())
    }

    @Test
    fun aRefusedParameterWriteStaysOnTheAirOnTheOldInterval() {
        coded.update(byteArrayOf(1))
        val set = liveSet()
        val cb = callbacks.last()
        coded.setInterval(FAST)
        cb.onAdvertisingEnabled(set, false, ADVERTISE_SUCCESS)
        cb.onAdvertisingParametersUpdated(set, 1, ADVERTISE_FAILED_INTERNAL_ERROR)
        verify(exactly = 1) { set.enableAdvertising(true, 0, 0) }
        cb.onAdvertisingEnabled(set, true, ADVERTISE_SUCCESS)
        assertEquals(listOf(FAST to ADVERTISE_FAILED_INTERNAL_ERROR), refusedIntervals)
        verify(exactly = 1) { set.enableAdvertising(false, 0, 0) } // nothing retries on its own
        coded.setInterval(FAST) // asked again: tried again
        verify(exactly = 2) { set.enableAdvertising(false, 0, 0) }
    }

    @Test
    fun aRefusedDisableLeavesTheSetAsItWas() {
        coded.update(byteArrayOf(1))
        val set = liveSet()
        coded.setInterval(FAST)
        callbacks.last().onAdvertisingEnabled(set, false, ADVERTISE_FAILED_INTERNAL_ERROR)
        verify(exactly = 0) { set.setAdvertisingParameters(any()) }
        assertEquals(listOf(FAST to ADVERTISE_FAILED_INTERNAL_ERROR), refusedIntervals)
        assertTrue("the set is back to the presence turns' care", coded.reassert())
    }

    @Test
    fun anIntervalAskedForMidStartIsAppliedOnceTheSetIsUp() {
        coded.update(byteArrayOf(1))
        coded.setInterval(FAST)
        assertEquals(BleAdvertiser.CODED_INTERVAL, startParams.single().interval)
        val set = liveSet()
        verify(exactly = 1) { set.enableAdvertising(false, 0, 0) }
    }

    @Test
    fun aColdSetStartsOnTheWantedInterval() {
        coded.setInterval(FAST)
        coded.update(byteArrayOf(1))
        assertEquals(FAST, startParams.single().interval)
        val set = liveSet()
        verify(exactly = 0) { set.enableAdvertising(any(), any(), any()) }
    }

    @Test
    fun anIntervalMovedAgainMidChangeIsAppliedWhenItEnds() {
        coded.update(byteArrayOf(1))
        val set = liveSet()
        val cb = callbacks.last()
        coded.setInterval(FAST)
        cb.onAdvertisingEnabled(set, false, ADVERTISE_SUCCESS)
        coded.setInterval(BleAdvertiser.CODED_INTERVAL) // the peer came back mid-change
        cb.onAdvertisingParametersUpdated(set, 1, ADVERTISE_SUCCESS)
        cb.onAdvertisingEnabled(set, true, ADVERTISE_SUCCESS)
        verify(exactly = 2) { set.enableAdvertising(false, 0, 0) }
    }

    @Test
    fun aChangeTheStackStoppedAnsweringIsPutBackOnByReassert() {
        coded.update(byteArrayOf(1))
        val set = liveSet()
        coded.setInterval(FAST) // the disable goes out and its answer never comes
        clock = 4_999
        assertFalse(coded.reassert())
        clock = 5_000
        assertTrue(coded.reassert())
        verify(exactly = 1) { set.enableAdvertising(true, 0, 0) }
        assertTrue("and it is an ordinary set again", coded.reassert())
    }

    @Test
    fun withIntervalKeepsEveryOtherField() {
        val before = BleAdvertiser.codedParams()
        val after = BleAdvertiser.withInterval(before, FAST)
        assertEquals(FAST, after.interval)
        assertEquals(before.isConnectable, after.isConnectable)
        assertEquals(before.isScannable, after.isScannable)
        assertEquals(before.isLegacy, after.isLegacy)
        assertEquals(before.primaryPhy, after.primaryPhy)
        assertEquals(before.secondaryPhy, after.secondaryPhy)
        assertEquals(before.txPowerLevel, after.txPowerLevel)
    }

    private companion object {
        const val FAST = 400 // 250 ms
        const val CODED_PHY = 3 // BluetoothDevice.PHY_LE_CODED
    }
}
