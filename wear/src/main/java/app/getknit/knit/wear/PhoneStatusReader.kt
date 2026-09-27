package app.getknit.knit.wear

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import app.getknit.knit.wearstatus.WearStatus
import app.getknit.knit.wearstatus.WearStatusCodec
import app.getknit.knit.wearstatus.WearStatusUuids
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** The last good read: the phone's snapshot and when this watch got it, on the watch's own clock. */
data class Snapshot(
    val status: WearStatus,
    val fetchedAtMs: Long,
)

/**
 * Reads the phone's status characteristic: connect to a bonded phone over LE, discover, read once, close. One
 * read at a time ([lock]), and a good read is cached for [FRESH_MS] so four complications refreshing together
 * cost one connection. The cache is persisted, so a complication asked after the process died still has the
 * last copy to show (aged by [fetchedAtMs]).
 *
 * The phone is found without a scan: the cached address first, then every bonded device, phones first — the
 * one that exposes the service is kept. Device-verified only (there is no host GATT stack); what it hands the
 * complications is rendered by the pure `StatusText`.
 *
 * A read that found no phone holds off the next unforced one for [FAIL_FLOOR_MS], so the four complications,
 * the tile and the app asking together against a phone out of reach cost one round of connects, not six.
 * Every good read is published on [snapshots], which the status screen draws from.
 */
@SuppressLint("MissingPermission") // BLUETOOTH is install-time on API 30; CONNECT is checked in [read]
object PhoneStatusReader {
    private val lock = Mutex()
    private val latest = MutableStateFlow<Snapshot?>(null)
    private var seeded = false

    @Volatile private var lastFailureMs = 0L

    /** The last good snapshot, seeded from the persisted copy, then every good read as it lands. */
    fun snapshots(context: Context): StateFlow<Snapshot?> {
        synchronized(this) {
            if (!seeded) {
                seeded = true
                latest.compareAndSet(null, cached(context.applicationContext))
            }
        }
        return latest
    }

    /** Last good snapshot, reading the persisted copy if this process has none yet. */
    fun cached(context: Context): Snapshot? {
        val prefs = prefs(context)
        val bytes = prefs.getString(KEY_BYTES, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        val status = WearStatusCodec.decode(bytes) ?: return null
        return Snapshot(status, prefs.getLong(KEY_FETCHED_AT, 0L))
    }

    /** A snapshot no older than [maxAgeMs] if the phone answers, else the last good one (possibly null). */
    suspend fun read(
        context: Context,
        maxAgeMs: Long = FRESH_MS,
    ): Snapshot? =
        lock.withLock {
            val app = context.applicationContext
            val now = System.currentTimeMillis()
            cached(app)?.takeIf { now - it.fetchedAtMs in 0..maxAgeMs }?.let { return@withLock it }
            if (maxAgeMs > 0 && now - lastFailureMs in 0 until FAIL_FLOOR_MS) return@withLock cached(app)
            fetch(app)?.let { (address, bytes) ->
                val snapshot = store(app, bytes, address, System.currentTimeMillis())
                if (snapshot != null) return@withLock snapshot
                Log.w(TAG, "phone answered with a snapshot this build cannot read (${bytes.size} B)")
            }
            lastFailureMs = System.currentTimeMillis()
            cached(app)
        }

    /** Persists and publishes [bytes] as read at [nowMs]; null (and nothing stored) if they do not decode. */
    fun store(
        context: Context,
        bytes: ByteArray,
        address: String?,
        nowMs: Long,
    ): Snapshot? {
        val status = WearStatusCodec.decode(bytes) ?: return null
        prefs(context).edit {
            putString(KEY_BYTES, Base64.encodeToString(bytes, Base64.NO_WRAP))
            putLong(KEY_FETCHED_AT, nowMs)
            address?.let { putString(KEY_ADDRESS, it) }
        }
        return Snapshot(status, nowMs).also {
            lastFailureMs = 0L
            latest.value = it
            StatusHistory.record(context, it)
        }
    }

    private suspend fun fetch(context: Context): Pair<String, ByteArray>? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "no BLUETOOTH_CONNECT grant; open the app on the watch")
            return null
        }
        val adapter: BluetoothAdapter =
            context.getSystemService(BluetoothManager::class.java)?.adapter?.takeIf { it.isEnabled } ?: return null
        val remembered = prefs(context).getString(KEY_ADDRESS, null)
        val bonded =
            runCatching { adapter.bondedDevices.orEmpty() }
                .getOrDefault(emptySet())
                .sortedByDescending { it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.PHONE }
        val candidates =
            (listOfNotNull(remembered?.let { runCatching { adapter.getRemoteDevice(it) }.getOrNull() }) + bonded)
                .distinctBy { it.address }
        for (device in candidates) {
            for ((attempt, transport) in transportsFor(device).withIndex()) {
                if (attempt > 0) delay(RETRY_MS)
                val bytes = OneShotRead(context, device, transport).run()
                Log.d(TAG, "read ${device.address} over ${transportName(transport)}: ${bytes?.size ?: "nothing"}")
                if (bytes != null) return device.address to bytes
            }
        }
        Log.i(TAG, "no bonded device answered (${candidates.size} tried)")
        return null
    }

    /**
     * LE first — it is the link that works on the lab's Pixel Watch 3, whose bond to the phone carries no LE keys
     * yet still reads the service over LE while the phone is connectable (the mesh advert, or the server's own
     * while paused). LE twice: one read in six on that pair failed with a fast 133 on the first connect, the
     * stack's generic error, which a retry after [RETRY_MS] clears. GATT over BR/EDR (the watch already holds an
     * encrypted BR/EDR link) is the last resort for a classic or dual-mode bond; it failed with 133 against a
     * Pixel 9 on 2026-09-26, at the cost of 0.2 s.
     */
    private fun transportsFor(device: BluetoothDevice): List<Int> =
        when (device.type) {
            BluetoothDevice.DEVICE_TYPE_LE -> listOf(BluetoothDevice.TRANSPORT_LE, BluetoothDevice.TRANSPORT_LE)
            else -> listOf(BluetoothDevice.TRANSPORT_LE, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.TRANSPORT_BREDR)
        }

    private fun transportName(transport: Int) = if (transport == BluetoothDevice.TRANSPORT_LE) "LE" else "BR/EDR"

    private fun prefs(context: Context) = context.getSharedPreferences("phone_status", Context.MODE_PRIVATE)

    private const val TAG = "KnitWear"
    private const val KEY_BYTES = "bytes"
    private const val KEY_FETCHED_AT = "fetched_at"
    private const val KEY_ADDRESS = "address"
    private const val RETRY_MS = 750L
    const val FRESH_MS = 60_000L
    const val FAIL_FLOOR_MS = 45_000L
}

/**
 * One connect → discover → read → close against [device], every step under one [TIMEOUT_MS]. Null for a device
 * that is not a Knit phone (no service), is out of reach, or refuses the read. Callbacks land on a binder
 * thread; each step completes a one-shot deferred, and a disconnect completes them all so nothing waits out
 * the timeout on a dead link.
 */
@SuppressLint("MissingPermission")
private class OneShotRead(
    private val context: Context,
    private val device: BluetoothDevice,
    private val transport: Int,
) {
    private val connected = CompletableDeferred<Boolean>()
    private val discovered = CompletableDeferred<Boolean>()
    private val value = CompletableDeferred<ByteArray?>()

    private val callback =
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(
                gatt: BluetoothGatt,
                status: Int,
                newState: Int,
            ) {
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    connected.complete(true)
                } else {
                    connected.complete(false)
                    discovered.complete(false)
                    value.complete(null)
                }
            }

            override fun onServicesDiscovered(
                gatt: BluetoothGatt,
                status: Int,
            ) {
                discovered.complete(status == BluetoothGatt.GATT_SUCCESS)
            }

            // API 33+.
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                bytes: ByteArray,
                status: Int,
            ) {
                value.complete(bytes.takeIf { status == BluetoothGatt.GATT_SUCCESS })
            }

            // API 30-32 (Wear OS 3).
            @Deprecated("Deprecated in API 33")
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                @Suppress("DEPRECATION")
                value.complete(characteristic.value?.takeIf { status == BluetoothGatt.GATT_SUCCESS })
            }
        }

    suspend fun run(): ByteArray? {
        val gatt =
            runCatching { device.connectGatt(context, false, callback, transport) }.getOrNull()
                ?: return null
        try {
            return withTimeoutOrNull(TIMEOUT_MS) {
                if (!connected.await()) return@withTimeoutOrNull null
                if (!gatt.discoverServices() || !discovered.await()) return@withTimeoutOrNull null
                val characteristic =
                    gatt.getService(WearStatusUuids.SERVICE)?.getCharacteristic(WearStatusUuids.STATUS)
                        ?: return@withTimeoutOrNull null
                if (!gatt.readCharacteristic(characteristic)) return@withTimeoutOrNull null
                value.await()
            }
        } finally {
            runCatching {
                gatt.disconnect()
                gatt.close()
            }
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
