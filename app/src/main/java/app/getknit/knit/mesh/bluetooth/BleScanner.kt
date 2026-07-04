package app.getknit.knit.mesh.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings

/**
 * Thin wrapper over [BluetoothLeScanner]: a service-UUID-filtered scan whose results are forwarded to
 * [onResult]. The scan **duty cycle** (start window / idle gap, and pausing while a connect is in flight — the
 * "scanning starves connects" contention) is driven by the transport via [start]/[stop] using
 * [app.getknit.knit.mesh.power.PowerPolicy]. Chipset-level [ScanFilter] on the service UUID keeps the callback
 * from waking for non-Knit adverts (battery + callback-flood control). Permission is gated at onboarding, so
 * the radio calls are [SuppressLint] "MissingPermission".
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
            }
        }

    @Volatile
    private var scanning = false

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
                .build()
        val filters = listOf(ScanFilter.Builder().setServiceUuid(BleConstants.SERVICE_UUID).build())
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
