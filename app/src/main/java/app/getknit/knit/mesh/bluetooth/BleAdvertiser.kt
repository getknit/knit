package app.getknit.knit.mesh.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser

/**
 * Thin wrapper over [BluetoothLeAdvertiser] for the coordination plane: connectable advertising of the
 * versioned service UUID plus the [BleAdvertPayload] service data (nodeId + capabilities + digest cue + L2CAP
 * PSM). Connectable so an initiator can open the L2CAP channel; low-power mode since it is always on. Callers
 * re-[update] the service data whenever the digest cue changes. Permission is gated at onboarding and the
 * transport self-degrades on denial, so the radio calls are [SuppressLint] "MissingPermission".
 */
@SuppressLint("MissingPermission")
internal class BleAdvertiser(
    // A **provider**, not a cached instance — same reason as [BleScanner]: `getBluetoothLeAdvertiser()` is null
    // while the adapter is off and stale across an off→on cycle, so re-fetching on every [update] is what lets
    // advertising survive an adapter toggle / BT-stack restart without a process restart.
    private val advertiserProvider: () -> BluetoothLeAdvertiser?,
    private val log: (String) -> Unit,
) {
    private val callback =
        object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) {
                active = false
                log("advertise start failed: $errorCode")
            }

            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                log("advertising")
            }
        }

    @Volatile
    private var active = false

    // The advertiser instance the live advert was started on, so [stop] and the pre-restart stop in [update]
    // target the same one across a post-toggle re-acquire.
    private var current: BluetoothLeAdvertiser? = null

    /** (Re)start advertising with fresh [serviceData]; a no-op if the device has no advertiser. */
    fun update(serviceData: ByteArray) {
        val adv = advertiserProvider() ?: return // re-acquired fresh each update (survives an adapter off→on cycle)
        if (active) runCatching { (current ?: adv).stopAdvertising(callback) }
        val settings =
            AdvertiseSettings
                .Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
                .setConnectable(true)
                .setTimeout(0)
                .build()
        val data =
            AdvertiseData
                .Builder()
                .addServiceUuid(BleConstants.SERVICE_UUID)
                .addServiceData(BleConstants.SERVICE_UUID, serviceData)
                .build()
        runCatching { adv.startAdvertising(settings, data, callback) }
            .onSuccess {
                active = true
                current = adv
            }.onFailure { log("advertise start threw: ${it.message}") }
    }

    fun stop() {
        val adv = current ?: return
        if (active) runCatching { adv.stopAdvertising(callback) }
        active = false
        current = null
    }
}
