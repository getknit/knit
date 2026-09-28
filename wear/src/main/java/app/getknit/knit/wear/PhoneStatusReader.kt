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
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import app.getknit.knit.wearstatus.WearStatus
import app.getknit.knit.wearstatus.WearStatusCodec
import app.getknit.knit.wearstatus.WearStatusFrame
import app.getknit.knit.wearstatus.WearStatusUuids
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/**
 * The last good read: the phone's snapshot, when this watch got it (on the watch's own clock) and over which
 * transport — null for a copy stored before the transport was recorded, or by the debug demo.
 */
data class Snapshot(
    val status: WearStatus,
    val fetchedAtMs: Long,
    val via: Via? = null,
)

/**
 * Reads the phone's status once: RFCOMM over the Classic link the watch already holds, else the GATT
 * characteristic over LE, in [ReadRoutes]' order. One read at a time ([lock]), and a good read is cached for
 * [FRESH_MS] so four complications refreshing together cost one connection. The cache is persisted, so a
 * complication asked after the process died still has the last copy to show (aged by [fetchedAtMs]).
 *
 * The phone is found without a scan: the cached address first, then every bonded device, phones first — the
 * one that answers is kept. Device-verified only (there is no host Bluetooth stack); what it hands the
 * complications is rendered by the pure `StatusText`.
 *
 * A read that found no phone holds off the next unforced one for [FAIL_FLOOR_MS], so the four complications,
 * the tile and the app asking together against a phone out of reach cost one round of connects, not six. Its
 * time is persisted ([failedAt]) until the next good read, because it is also what turns an aged reading into
 * "Phone out of reach" ([StatusText.shown]) in whichever process draws it next.
 * Every good read is published on [snapshots], which the status screen draws from.
 */
@SuppressLint("MissingPermission") // BLUETOOTH is install-time on API 30; CONNECT is checked in [read]
object PhoneStatusReader {
    private val lock = Mutex()
    private val latest = MutableStateFlow<Snapshot?>(null)
    private var seeded = false

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
        val via = prefs.getString(KEY_VIA, null)?.let { name -> Via.entries.firstOrNull { it.name == name } }
        return Snapshot(status, prefs.getLong(KEY_FETCHED_AT, 0L), via)
    }

    /** When a read last found no phone, if no good read has landed since; 0 for none. */
    fun failedAt(context: Context): Long = prefs(context).getLong(KEY_FAILED_AT, 0L)

    /**
     * Whether an unforced [read] now would try the phone: the cache is older than [FRESH_MS] (or absent) and no
     * read found the phone missing within [FAIL_FLOOR_MS]. [StatusRefresh] asks before it starts a job, so a
     * surface that a read's own landing redraws does not start another.
     */
    fun due(
        context: Context,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean = due(cached(context)?.fetchedAtMs, failedAt(context), nowMs)

    internal fun due(
        fetchedAtMs: Long?,
        failedAtMs: Long,
        nowMs: Long,
    ): Boolean =
        (fetchedAtMs == null || nowMs - fetchedAtMs !in 0..FRESH_MS) &&
            nowMs - failedAtMs !in 0 until FAIL_FLOOR_MS

    /** A snapshot no older than [maxAgeMs] if the phone answers, else the last good one (possibly null). */
    suspend fun read(
        context: Context,
        maxAgeMs: Long = FRESH_MS,
    ): Snapshot? =
        lock.withLock {
            val app = context.applicationContext
            val now = System.currentTimeMillis()
            cached(app)?.takeIf { now - it.fetchedAtMs in 0..maxAgeMs }?.let { return@withLock it }
            if (maxAgeMs > 0 && now - failedAt(app) in 0 until FAIL_FLOOR_MS) return@withLock cached(app)
            fetch(app)?.let { (address, via, bytes) ->
                val snapshot = store(app, bytes, address, System.currentTimeMillis(), via)
                if (snapshot != null) return@withLock snapshot
                Log.w(TAG, "phone answered with a snapshot this build cannot read (${bytes.size} B)")
            }
            prefs(app).edit { putLong(KEY_FAILED_AT, System.currentTimeMillis()) }
            cached(app)
        }

    /** Persists and publishes [bytes] as read at [nowMs]; null (and nothing stored) if they do not decode. */
    fun store(
        context: Context,
        bytes: ByteArray,
        address: String?,
        nowMs: Long,
        via: Via? = null,
    ): Snapshot? {
        val status = WearStatusCodec.decode(bytes) ?: return null
        prefs(context).edit {
            putString(KEY_BYTES, Base64.encodeToString(bytes, Base64.NO_WRAP))
            putLong(KEY_FETCHED_AT, nowMs)
            address?.let { putString(KEY_ADDRESS, it) }
            if (via != null) putString(KEY_VIA, via.name) else remove(KEY_VIA)
            remove(KEY_FAILED_AT)
        }
        return Snapshot(status, nowMs, via).also {
            latest.value = it
            StatusHistory.record(context, it)
        }
    }

    private data class Fetched(
        val address: String,
        val via: Via,
        val bytes: ByteArray,
    )

    private suspend fun fetch(context: Context): Fetched? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "no BLUETOOTH_CONNECT grant; open the app on the watch")
            return null
        }
        val adapter: BluetoothAdapter =
            context.getSystemService(BluetoothManager::class.java)?.adapter?.takeIf { it.isEnabled } ?: return null
        val remembered = prefs(context).getString(KEY_ADDRESS, null)
        val lastGood = cached(context)?.via
        val bonded =
            runCatching { adapter.bondedDevices.orEmpty() }
                .getOrDefault(emptySet())
                .sortedByDescending { it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.PHONE }
        val candidates =
            (listOfNotNull(remembered?.let { runCatching { adapter.getRemoteDevice(it) }.getOrNull() }) + bonded)
                .distinctBy { it.address }
        for (device in candidates) {
            val routes = ReadRoutes.plan(leOnly = device.type == BluetoothDevice.DEVICE_TYPE_LE, lastGood = lastGood)
            for ((attempt, via) in routes.withIndex()) {
                if (attempt > 0) delay(RETRY_MS)
                val started = System.currentTimeMillis()
                val bytes =
                    when (via) {
                        Via.Classic -> RfcommRead(device).run()
                        Via.Le -> OneShotRead(context, device, BluetoothDevice.TRANSPORT_LE).run()
                    }
                val took = System.currentTimeMillis() - started
                Log.d(TAG, "read ${device.address} over ${via.label}: ${bytes?.size ?: "nothing"} in $took ms")
                if (bytes != null) {
                    // Info, not debug: which transport a watch's bond carries is what the field data is for.
                    Log.i(TAG, "phone answered over ${via.label} (attempt ${attempt + 1} of ${routes.size})")
                    return Fetched(device.address, via, bytes)
                }
            }
        }
        Log.i(TAG, "no bonded device answered (${candidates.size} tried)")
        return null
    }

    private fun prefs(context: Context) = context.getSharedPreferences("phone_status", Context.MODE_PRIVATE)

    private const val TAG = "KnitWear"
    private const val KEY_BYTES = "bytes"
    private const val KEY_FETCHED_AT = "fetched_at"
    private const val KEY_ADDRESS = "address"
    private const val KEY_VIA = "via"
    private const val KEY_FAILED_AT = "failed_at"
    private const val RETRY_MS = 750L
    const val FRESH_MS = 60_000L
    const val FAIL_FLOOR_MS = 45_000L
}

/**
 * One RFCOMM connect → frame → close against [device]'s status record, under one [TIMEOUT_MS]: the connect
 * runs the SDP lookup and rides the Classic link the watch already holds, so it needs no LE connection and no
 * advert. Null for a device without the record (not a Knit phone, or a phone build that predates it), out of
 * reach, or with nothing to give. The socket blocks, so a watchdog closes it on the deadline.
 */
@SuppressLint("MissingPermission")
private class RfcommRead(
    private val device: BluetoothDevice,
) {
    suspend fun run(): ByteArray? =
        withContext(Dispatchers.IO) {
            val socket: BluetoothSocket =
                runCatching { device.createRfcommSocketToServiceRecord(WearStatusUuids.RFCOMM) }.getOrNull()
                    ?: return@withContext null
            val watchdog =
                launch {
                    delay(TIMEOUT_MS)
                    runCatching { socket.close() }
                }
            try {
                socket.connect()
                WearStatusFrame.read(socket.inputStream)
            } catch (_: IOException) {
                null
            } finally {
                watchdog.cancel()
                runCatching { socket.close() }
            }
        }

    private companion object {
        const val TIMEOUT_MS = 8_000L
    }
}

/**
 * One LE connect → discover → read → close against [device], every step under one [TIMEOUT_MS]. Null for a device
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
