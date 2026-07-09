package app.getknit.knit.mesh.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.BluetoothLeAdvertiser

/**
 * Thin wrapper over [BluetoothLeAdvertiser] for the coordination plane: connectable advertising of the
 * [BleAdvertPayload] service data (nodeId + capabilities + digest cue + L2CAP PSM) under the versioned service
 * UUID. Connectable so an initiator can open the L2CAP channel; low-power (slow) interval since it is always on.
 * Callers re-[update] the service data whenever the digest cue — or the PSM — changes.
 *
 * **Uses the advertising-**set** API ([BluetoothLeAdvertiser.startAdvertisingSet]) so a data change is an
 * **in-place** [AdvertisingSet.setAdvertisingData], NOT a stop-then-start.** The legacy [AdvertiseData] path this
 * replaced had no in-place update, so [update] stopped then immediately restarted advertising reusing one
 * [android.bluetooth.le.AdvertiseCallback]; because `stopAdvertising` is async, the restart could land while the
 * old set was still up and be rejected `ADVERTISE_FAILED_ALREADY_STARTED`, leaving the **old** payload on air.
 * When [update] carries a **new PSM** (the responder re-`openServer()`'d, e.g. across an adapter cycle), that
 * race stranded the *old* PSM in the advert while the socket moved — so an unlinked initiator dialed a dead PSM
 * and its L2CAP connect silently timed out, indefinitely (a linked peer holds its socket, so it never noticed).
 * The set API's atomic data update closes that divergence: the advertised PSM can never lag the server socket.
 *
 * [setLegacyMode] is required, not incidental: extended advertising is invisible to legacy-only scanners
 * (e.g. the API-30 lab device), and legacy mode keeps the payload on the same 31-byte budget [BleAdvertPayload]
 * is sized for. Permission is gated at onboarding and the transport self-degrades on denial, so the radio calls
 * are [SuppressLint] "MissingPermission".
 */
@SuppressLint("MissingPermission")
internal class BleAdvertiser(
    // A **provider**, not a cached instance — same reason as [BleScanner]: `getBluetoothLeAdvertiser()` is null
    // while the adapter is off and stale across an off→on cycle, so re-fetching on every [update] is what lets
    // advertising survive an adapter toggle / BT-stack restart without a process restart.
    private val advertiserProvider: () -> BluetoothLeAdvertiser?,
    private val log: (String) -> Unit,
) {
    // All mutable state below is guarded by [lock]: [update]/[stop] run on the mesh scope while the callback
    // fires on a binder thread, and they race over the set handle + the start-in-flight bookkeeping.
    private val lock = Any()

    // The live set, once [AdvertisingSetCallback.onAdvertisingSetStarted] hands it back — the handle we push
    // in-place data updates to. Null before the first start, after a stop, or after a failed/lost start.
    private var advertisingSet: AdvertisingSet? = null

    // A start is in flight (startAdvertisingSet called, onAdvertisingSetStarted not yet back). A data [update]
    // arriving in this window can't touch the set yet, so it parks in [pendingData] to be applied on start.
    private var starting = false
    private var pendingData: ByteArray? = null

    // The advertiser the live set was started on, so [stop] targets the same one across a post-toggle re-acquire.
    private var current: BluetoothLeAdvertiser? = null

    private val callback =
        object : AdvertisingSetCallback() {
            override fun onAdvertisingSetStarted(
                set: AdvertisingSet?,
                txPower: Int,
                status: Int,
            ) {
                synchronized(lock) {
                    starting = false
                    if (status != ADVERTISE_SUCCESS || set == null) {
                        log("advertising set start failed: $status")
                        advertisingSet = null
                        current = null
                        pendingData = null
                        return
                    }
                    advertisingSet = set
                    log("advertising")
                    // Apply the newest payload that arrived while we were mid-start (a fresher cue/PSM), so the
                    // advert reflects the latest state and not the now-stale bytes we started with.
                    pendingData?.let { latest ->
                        pendingData = null
                        runCatching { set.setAdvertisingData(dataFor(latest)) }
                            .onFailure { log("advertising data update threw: ${it.message}") }
                    }
                }
            }

            override fun onAdvertisingDataSet(
                set: AdvertisingSet?,
                status: Int,
            ) {
                if (status != ADVERTISE_SUCCESS) log("advertising data set failed: $status")
            }

            override fun onAdvertisingSetStopped(set: AdvertisingSet?) {
                synchronized(lock) { advertisingSet = null }
            }
        }

    /** (Re)start advertising, or update the live advert **in place**, with fresh [serviceData]; a no-op if the
     *  device has no advertiser. */
    fun update(serviceData: ByteArray) {
        val adv = advertiserProvider() ?: return // re-acquired fresh each update (survives an adapter off→on cycle)
        synchronized(lock) {
            val set = advertisingSet
            when {
                // Live set: atomic in-place data swap — no stop/start, so the PSM can never lag the socket.
                set != null -> {
                    runCatching { set.setAdvertisingData(dataFor(serviceData)) }
                        .onFailure { log("advertising data update threw: ${it.message}") }
                }

                // Start in flight: the set isn't ours yet; park the freshest bytes to apply on start.
                starting -> {
                    pendingData = serviceData
                }

                // Cold: bring the set up with this payload.
                else -> {
                    start(adv, serviceData)
                }
            }
        }
    }

    /** Holds [lock]. */
    private fun start(
        adv: BluetoothLeAdvertiser,
        serviceData: ByteArray,
    ) {
        val params =
            AdvertisingSetParameters
                .Builder()
                .setLegacyMode(true) // legacy PDUs so legacy-only scanners (e.g. the API-30 device) can see us
                .setConnectable(true) // an initiator opens the L2CAP channel to us
                .setScannable(true) // legacy connectable adverts are inherently scannable
                .setInterval(AdvertisingSetParameters.INTERVAL_HIGH) // ~1s: always-on, low power (was LOW_POWER)
                .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_MEDIUM)
                .build()
        starting = true
        pendingData = null
        current = adv
        runCatching { adv.startAdvertisingSet(params, dataFor(serviceData), null, null, null, callback) }
            .onFailure {
                starting = false
                current = null
                log("advertising set start threw: ${it.message}")
            }
    }

    fun stop() {
        val adv = current ?: return
        synchronized(lock) {
            if (advertisingSet != null || starting) runCatching { adv.stopAdvertisingSet(callback) }
            advertisingSet = null
            starting = false
            pendingData = null
            current = null
        }
    }

    // Service data only — no separate service-UUID list AD. The service-data AD already carries the 16-bit UUID,
    // and dropping the redundant list AD frees the 4 bytes the 16-byte raw nodeId needs to keep the payload inside
    // the 31-byte legacy budget (see [BleAdvertPayload]). Scanners filter on the service data instead (see
    // [BleScanner]).
    private fun dataFor(serviceData: ByteArray): AdvertiseData =
        AdvertiseData
            .Builder()
            .addServiceData(BleConstants.SERVICE_UUID, serviceData)
            .build()
}
