package app.getknit.knit

import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetCallback.ADVERTISE_FAILED_INTERNAL_ERROR
import android.bluetooth.le.AdvertisingSetCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS
import android.bluetooth.le.AdvertisingSetCallback.ADVERTISE_SUCCESS
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
 * up with the newest bytes, and nothing raises a stopped set or reports a stale one. Robolectric for the framework's
 * real `AdvertiseData` / `AdvertisingSetParameters` builders; the advertiser and the set are mocks.
 */
@RunWith(RobolectricTestRunner::class)
class BleAdvertiserTest {
    private val callbacks = mutableListOf<AdvertisingSetCallback>()
    private val starts = mutableListOf<AdvertiseData>()
    private val enables = mutableListOf<Pair<Boolean, Int>>()
    private var advertiserPresent = true

    private val radio =
        mockk<BluetoothLeAdvertiser>(relaxed = true).also { adv ->
            every { adv.startAdvertisingSet(any(), any(), any(), any(), any(), any<AdvertisingSetCallback>()) } answers {
                starts += arg<AdvertiseData>(1)
                callbacks += arg<AdvertisingSetCallback>(5)
            }
        }

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
}
