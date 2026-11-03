package app.getknit.knit

import android.bluetooth.BluetoothDevice
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import app.getknit.knit.mesh.bluetooth.BleConstants
import app.getknit.knit.mesh.bluetooth.BleScanner
import app.getknit.knit.mesh.bluetooth.ScanPhys
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [BleScanner]: the scanner is fetched afresh at every start (a handle cached across an adapter toggle detached the
 * scan for good), a stop reaches the instance its scan started on, a failed scan is no longer scanning, and the
 * settings and filters each scan asks for. Robolectric for the framework's real `ScanSettings` / `ScanFilter`
 * builders; the scanner is a mock.
 */
@RunWith(RobolectricTestRunner::class)
class BleScannerTest {
    private class Radio {
        val filters = mutableListOf<List<ScanFilter>>()
        val settings = mutableListOf<ScanSettings>()
        val callbacks = mutableListOf<ScanCallback>()
        var throws = false
        val scanner =
            mockk<BluetoothLeScanner>(relaxed = true).also { s ->
                every { s.startScan(any<List<ScanFilter>>(), any(), any<ScanCallback>()) } answers {
                    if (throws) error("BT adapter is not turned ON")
                    filters += arg<List<ScanFilter>>(0)
                    settings += arg<ScanSettings>(1)
                    callbacks += arg<ScanCallback>(2)
                }
            }
    }

    private var provided: Radio? = Radio()
    private val results = mutableListOf<ScanResult>()
    private val failures = mutableListOf<Int>()
    private val logs = mutableListOf<String>()

    private fun scanner(
        extended: Boolean = false,
        matchesServiceUuid: Boolean = false,
    ) = BleScanner(
        scannerProvider = { provided?.scanner },
        onResult = { results += it },
        log = { logs += it },
        extended = extended,
        matchesServiceUuid = matchesServiceUuid,
        onFailed = { failures += it },
    )

    @Test
    fun theScannerIsFetchedAfreshAtEachStart() {
        val first = provided!!
        val s = scanner()
        s.start(ScanSettings.SCAN_MODE_BALANCED)
        s.stop()
        val second = Radio()
        provided = second // the adapter went off and on: a new handle
        s.start(ScanSettings.SCAN_MODE_BALANCED)
        assertEquals(1, first.settings.size)
        assertEquals("the new handle, not the cached one", 1, second.settings.size)
    }

    @Test
    fun aStopReachesTheInstanceTheScanStartedOn() {
        val first = provided!!
        val s = scanner()
        s.start(ScanSettings.SCAN_MODE_BALANCED)
        provided = Radio()
        s.stop()
        verify(exactly = 1) { first.scanner.stopScan(first.callbacks.single()) }
        assertFalse(s.isScanning)
    }

    @Test
    fun withTheAdapterOffNothingStartsAndAStopIsANoOp() {
        provided = null
        val s = scanner()
        s.start(ScanSettings.SCAN_MODE_BALANCED)
        assertFalse(s.isScanning)
        s.stop()
    }

    @Test
    fun aRunningScanIsNotStartedTwice() {
        val s = scanner()
        s.start(ScanSettings.SCAN_MODE_BALANCED)
        s.start(ScanSettings.SCAN_MODE_LOW_POWER)
        assertEquals(1, provided!!.settings.size)
    }

    @Test
    fun aFailedScanIsNoLongerScanningAndCanStartAgain() {
        val radio = provided!!
        val s = scanner()
        s.start(ScanSettings.SCAN_MODE_BALANCED)
        assertTrue(s.isScanning)
        radio.callbacks.single().onScanFailed(ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED)
        assertFalse(s.isScanning)
        assertEquals(listOf(ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED), failures)
        s.start(ScanSettings.SCAN_MODE_BALANCED)
        assertEquals(2, radio.settings.size)
    }

    @Test
    fun aStartThatThrowsIsLoggedAndLeavesNoScan() {
        provided!!.throws = true
        val s = scanner()
        s.start(ScanSettings.SCAN_MODE_BALANCED)
        assertFalse(s.isScanning)
        assertTrue(logs.single().startsWith("startScan threw"))
        s.stop() // nothing was started: no stopScan
        verify(exactly = 0) { provided!!.scanner.stopScan(any<ScanCallback>()) }
    }

    @Test
    fun resultsAndBatchesReachTheTransport() {
        val s = scanner()
        s.start(ScanSettings.SCAN_MODE_BALANCED)
        val cb = provided!!.callbacks.single()
        val a = mockk<ScanResult>()
        val b = mockk<ScanResult>()
        cb.onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, a)
        cb.onBatchScanResults(mutableListOf(b, a))
        assertEquals(listOf(a, b, a), results)
    }

    @Test
    fun thePresenceScanIsLegacyOn1mUntilCodedAsksForMore() {
        val s = scanner()
        s.start(ScanSettings.SCAN_MODE_LOW_POWER)
        provided!!.settings.single().let {
            assertEquals(ScanSettings.SCAN_MODE_LOW_POWER, it.scanMode)
            assertEquals(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it.callbackType)
            assertTrue("legacy", it.legacy)
        }
        s.stop()
        s.phys = ScanPhys.ALL
        s.start(ScanSettings.SCAN_MODE_BALANCED)
        provided!!.settings.last().let {
            assertFalse(it.legacy)
            assertEquals(ScanSettings.PHY_LE_ALL_SUPPORTED, it.phy)
        }
        s.stop()
        s.phys = ScanPhys.CODED
        s.start(ScanSettings.SCAN_MODE_BALANCED)
        provided!!.settings.last().let {
            assertFalse(it.legacy)
            assertEquals(BluetoothDevice.PHY_LE_CODED, it.phy)
        }
    }

    @Test
    fun theSideScanIsExtendedOn1mOnly() {
        scanner(extended = true).start(ScanSettings.SCAN_MODE_BALANCED)
        provided!!.settings.single().let {
            assertFalse(it.legacy)
            assertEquals(BluetoothDevice.PHY_LE_1M, it.phy)
        }
    }

    @Test
    fun theScanFiltersOnServiceDataAndOnlyThePresenceScanAlsoOnTheUuidList() {
        scanner().start(ScanSettings.SCAN_MODE_BALANCED)
        provided!!.filters.single().single().let {
            assertEquals(BleConstants.SERVICE_UUID, it.serviceDataUuid)
            assertNull(it.serviceUuid)
        }
        val gatt = Radio()
        provided = gatt
        scanner(matchesServiceUuid = true).start(ScanSettings.SCAN_MODE_BALANCED)
        val (data, uuid) = gatt.filters.single()
        assertEquals(BleConstants.SERVICE_UUID, data.serviceDataUuid)
        assertSame(null, uuid.serviceDataUuid)
        assertEquals("an iPhone lists the UUID with no service data", BleConstants.SERVICE_UUID, uuid.serviceUuid)
    }
}
