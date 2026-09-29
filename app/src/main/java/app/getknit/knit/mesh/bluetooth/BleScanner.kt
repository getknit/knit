package app.getknit.knit.mesh.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid

/**
 * Thin wrapper over [BluetoothLeScanner]: a service-data-filtered scan whose results are forwarded to
 * [onResult]. The scan **duty cycle** (start window / idle gap, and pausing while a connect is in flight — the
 * "scanning starves connects" contention) is driven by the transport via [start]/[stop] using
 * [app.getknit.knit.mesh.power.PowerPolicy]. Chipset-level [ScanFilter] on the service data for our UUID keeps
 * the callback from waking for non-Knit adverts (battery + callback-flood control). With [matchesServiceUuid]
 * (the presence scan, while `BuildConfig.BLE_GATT_PEERS` is on) a second filter, OR'd with the first, matches an
 * advert that lists the UUID with no service data: a foreground iPhone, which cannot advertise service data and
 * serves its payload over GATT instead ([BleGattPayloadReader], companion change A3). Permission is gated at
 * onboarding, so the radio calls are [SuppressLint] "MissingPermission".
 */
@SuppressLint("MissingPermission")
internal class BleScanner(
    // A **provider**, not a cached instance: `BluetoothAdapter.getBluetoothLeScanner()` returns null while the
    // adapter is off and its handle goes stale across an off→on cycle, so re-fetching on every [start] is what
    // lets the scan survive an adapter toggle / BT-stack restart (and the app-started-while-off case) without a
    // process restart. Caching it once silently detaches the scanner from the stack forever — see
    // BluetoothMeshTransport's construction site.
    private val scannerProvider: () -> BluetoothLeScanner?,
    private val onResult: (ScanResult) -> Unit,
    private val log: (String) -> Unit,
    private val serviceUuid: ParcelUuid = BleConstants.SERVICE_UUID,
    // The side channel's scan ([BleSideChannel]): extended results (`setLegacy(false)` — a legacy scan cannot
    // decode an ADV_EXT_IND) on the 1M PHY only (PHY_LE_ALL_SUPPORTED would time-share the window with Coded).
    private val extended: Boolean = false,
    // The presence scan's second filter, on the UUID in a service-UUID list: how an iPhone is found. Never the side
    // channel's, whose pages always carry service data.
    private val matchesServiceUuid: Boolean = false,
    private val onFailed: (Int) -> Unit = {},
) {
    private val callback =
        object : ScanCallback() {
            override fun onScanResult(
                callbackType: Int,
                result: ScanResult,
            ) {
                onResult(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach(onResult)
            }

            override fun onScanFailed(errorCode: Int) {
                scanning = false
                log("scan failed: $errorCode")
                onFailed(errorCode)
            }
        }

    @Volatile
    private var scanning = false

    /** Whether a scan is registered right now (false again after `onScanFailed` or [stop]). */
    val isScanning: Boolean get() = scanning

    // The scanner instance the live scan was started on, so [stop] targets the same one even if the provider
    // would now hand back a different (post-toggle) instance.
    private var active: BluetoothLeScanner? = null

    fun start(scanMode: Int) {
        if (scanning) return
        val s = scannerProvider() ?: return // re-acquired fresh each start (survives an adapter off→on cycle)
        val settings =
            ScanSettings
                .Builder()
                .setScanMode(scanMode)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .apply { if (extended) setLegacy(false).setPhy(BluetoothDevice.PHY_LE_1M) }
                .build()
        // Filter on the presence of service data for our UUID, not a service-UUID list AD — an Android advert
        // carries no such list AD (it was dropped to make budget room for the 16-byte raw nodeId; see
        // [BleAdvertiser]). An empty data/mask matches any advert with service data for the UUID, i.e. every
        // Android Knit peer of our version. Keeps the callback from waking for non-Knit adverts (battery +
        // callback-flood control). The UUID-list filter is the iPhone's (see the class doc); `0xFE30` is not
        // Knit's alone, so what it matches is only a candidate until its GATT payload is read.
        val filters =
            buildList {
                add(ScanFilter.Builder().setServiceData(serviceUuid, byteArrayOf(), byteArrayOf()).build())
                if (matchesServiceUuid) add(ScanFilter.Builder().setServiceUuid(serviceUuid).build())
            }
        runCatching { s.startScan(filters, settings, callback) }
            .onSuccess {
                scanning = true
                active = s
            }.onFailure { log("startScan threw: ${it.message}") }
    }

    fun stop() {
        val s = active ?: return
        if (scanning) runCatching { s.stopScan(callback) }
        scanning = false
        active = null
    }
}
