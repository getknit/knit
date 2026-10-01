package app.getknit.knit.mesh.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.BluetoothLeAdvertiser
import android.os.ParcelUuid

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
 * For the presence advert ([presenceParams]) `setLegacyMode` is required, not incidental: extended advertising
 * is invisible to legacy-only scanners (e.g. the API-30 lab device), and legacy mode keeps the payload on the
 * same 31-byte budget [BleAdvertPayload] is sized for. The same wrapper also raises the side channel's sets
 * ([sideParams], [BleSideChannel]): non-connectable, non-scannable **extended** sets under their own
 * [serviceUuid], whose payload is swapped in place exactly as the presence cue is. Permission is gated at
 * onboarding and the transport self-degrades on denial, so the radio calls are [SuppressLint] "MissingPermission".
 */
@SuppressLint("MissingPermission")
internal class BleAdvertiser(
    // A **provider**, not a cached instance — same reason as [BleScanner]: `getBluetoothLeAdvertiser()` is null
    // while the adapter is off and stale across an off→on cycle, so re-fetching on every [update] is what lets
    // advertising survive an adapter toggle / BT-stack restart without a process restart.
    private val advertiserProvider: () -> BluetoothLeAdvertiser?,
    private val log: (String) -> Unit,
    private val serviceUuid: ParcelUuid = BleConstants.SERVICE_UUID,
    private val params: AdvertisingSetParameters = presenceParams(),
    // The start outcome (`AdvertisingSetCallback.ADVERTISE_SUCCESS` or the failure code), for a caller that
    // degrades on it — the side channel drops a slot on TOO_MANY_ADVERTISERS and goes dark on FEATURE_UNSUPPORTED.
    private val onStartStatus: (Int) -> Unit = {},
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
                        onStartStatus(status)
                        return
                    }
                    onStartStatus(status)
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
        starting = true
        pendingData = null
        current = adv
        // Throws synchronously for a payload past the controller's advertising-data maximum (an extended set on
        // a controller without the feature reads that maximum as 31), so the failure is reported like any other.
        runCatching { adv.startAdvertisingSet(params, dataFor(serviceData), null, null, null, callback) }
            .onFailure {
                starting = false
                current = null
                log("advertising set start threw: ${it.message}")
                onStartStatus(AdvertisingSetCallback.ADVERTISE_FAILED_INTERNAL_ERROR)
            }
    }

    /** Whether a set is up or coming up — the side channel reads it to count its live slots. */
    val active: Boolean get() = synchronized(lock) { advertisingSet != null || starting }

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
            .addServiceData(serviceUuid, serviceData)
            .build()

    companion object {
        /** The always-on presence cue: legacy, connectable, slow. */
        fun presenceParams(): AdvertisingSetParameters =
            AdvertisingSetParameters
                .Builder()
                .setLegacyMode(true) // legacy PDUs so legacy-only scanners (e.g. the API-30 device) can see us
                .setConnectable(true) // an initiator opens the L2CAP channel to us
                .setScannable(true) // legacy connectable adverts are inherently scannable
                .setInterval(AdvertisingSetParameters.INTERVAL_HIGH) // ~1s: always-on, low power (was LOW_POWER)
                .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_MEDIUM)
                .build()

        /**
         * A side-channel page set ([BleSideChannel]): extended (the page is past the legacy 31-byte budget),
         * non-connectable and non-scannable (the presence cue is what an initiator dials; a page carries no PSM),
         * 1M on both PHYs (2M would reach less far than the presence advert at the same power, so a peer could be
         * sighted with the flag and never hear a page), ~250 ms so a LOW_POWER scanner's 512 ms window sees
         * about two events, MEDIUM power so a page reaches no further than the sighting that gated it.
         */
        fun sideParams(): AdvertisingSetParameters =
            AdvertisingSetParameters
                .Builder()
                .setLegacyMode(false)
                .setConnectable(false)
                .setScannable(false)
                .setPrimaryPhy(BluetoothDevice.PHY_LE_1M)
                .setSecondaryPhy(BluetoothDevice.PHY_LE_1M)
                .setInterval(AdvertisingSetParameters.INTERVAL_MEDIUM)
                .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_MEDIUM)
                .build()

        /**
         * The Coded PHY experiment's second presence set (ADR 2026-10.yvn6): the presence payload again, extended and
         * connectable (an initiator dials its address, and the link opens on Coded), Coded on both PHYs — primary
         * advertising on Coded is always S=8 — at the presence cadence. [txPower] is HIGH by default, the point being
         * reach; a walk test can pin it to the presence advert's MEDIUM to compare PHYs alone (`…debug.PHY`).
         */
        fun codedParams(txPower: Int = AdvertisingSetParameters.TX_POWER_HIGH): AdvertisingSetParameters =
            AdvertisingSetParameters
                .Builder()
                .setLegacyMode(false)
                .setConnectable(true)
                .setScannable(false)
                .setPrimaryPhy(BluetoothDevice.PHY_LE_CODED)
                .setSecondaryPhy(BluetoothDevice.PHY_LE_CODED)
                .setInterval(AdvertisingSetParameters.INTERVAL_HIGH)
                .setTxPowerLevel(txPower)
                .build()
    }
}
