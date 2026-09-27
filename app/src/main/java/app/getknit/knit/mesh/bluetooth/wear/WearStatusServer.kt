package app.getknit.knit.mesh.bluetooth.wear

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import app.getknit.knit.mesh.MeshPause
import app.getknit.knit.mesh.wear.WearInputs
import app.getknit.knit.mesh.wear.WearStatusPolicy
import app.getknit.knit.mesh.wear.WearStatusSource
import app.getknit.knit.wearstatus.WearStatusCodec
import app.getknit.knit.wearstatus.WearStatusFrame
import app.getknit.knit.wearstatus.WearStatusUuids
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * The mesh status a bonded Wear OS watch reads, served two ways: one [WearStatusCodec] snapshot, computed at
 * read time from the last [WearInputs] [WearStatusSource] emitted. Nothing here touches the mesh wire.
 *
 * **RFCOMM first** (ADR 2026-09.wetm, third amendment): a secure server socket under [WearStatusUuids.RFCOMM]'s
 * SDP record, one [WearStatusFrame] per connection. It rides the Classic link a paired watch already holds, so
 * it takes no LE connection — the host's GATT table (eight slots on a Pixel 9, one per LE link, the mesh's
 * L2CAP links included) was full on a busy mesh phone and every LE connect from the watch was dropped "out of
 * resources" — needs no advert, and is encrypted by the existing bond instead of a fresh LE pairing per read.
 *
 * **LE GATT is the fallback**, kept until other watches show which transport their bond carries: one
 * read-only characteristic. Its reachability is borrowed from the mesh, except during a pause. A Pixel Watch 3
 * bonded to its phone over BR/EDR reaches it over LE only while the phone is LE-connectable, which the mesh's
 * presence advert makes it; GATT over the BR/EDR link fails (133). A pause takes that advert down, so for
 * exactly the length of a pause this server raises its own set — legacy, connectable, empty, one-second
 * interval. Only while paused, so it never contends with the mesh's presence and side-channel sets for a
 * controller slot (they are down then).
 *
 * Only a bonded device is answered: the RFCOMM socket is the authenticated, encrypted kind (a stranger's
 * connect would raise a pairing prompt), the characteristic demands an encrypted link
 * (`PERMISSION_READ_ENCRYPTED`, so the stack refuses an unencrypted read before we see it), and both handlers
 * re-check the bond, so a stranger that pairs through a prompt still gets nothing unless the user accepted it.
 *
 * Follows the BLE plane's idioms: the adapter is a provider, never a cached handle (an adapter off → on
 * cycle kills a cached server, as it did the cached scanner), the server is reopened on `STATE_ON`, and
 * every radio call is `runCatching`-wrapped so a revoked grant degrades to "no server" rather than a crash.
 * The radio permission is the onboarding's (`ui/Permissions.kt`), hence the class-level suppression.
 *
 * Prototype, dark in release behind `BuildConfig.WEAR_STATUS` (the DI hands `MeshService` null then).
 * Device-verified only: there is no host GATT stack; the snapshot logic is [WearStatusPolicy], which is tested.
 */
@SuppressLint("MissingPermission")
internal class WearStatusServer(
    context: Context,
    private val source: WearStatusSource,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter // provider, not a cached handle

    @Volatile private var inputs: WearInputs? = null

    @Volatile private var server: BluetoothGattServer? = null

    @Volatile private var rfcomm: BluetoothServerSocket? = null
    private var collector: Job? = null

    // The pause advert (see the class note). Guarded by this object's monitor, like [open]/[close].
    private var wantAdvert = false
    private var advertiser: BluetoothLeAdvertiser? = null

    private val advertCallback =
        object : AdvertisingSetCallback() {
            override fun onAdvertisingSetStarted(
                set: AdvertisingSet?,
                txPower: Int,
                status: Int,
            ) {
                if (status == ADVERTISE_SUCCESS) {
                    Log.i(TAG, "pause advert up")
                } else {
                    Log.w(TAG, "pause advert refused: $status")
                    synchronized(this@WearStatusServer) { advertiser = null }
                }
            }
        }
    private var receiverRegistered = false

    private val stateReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) {
                when (intent?.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    // Close first: a fast off → on bounce can skip the off broadcast, and a server opened on the
                    // old adapter instance answers nothing.
                    BluetoothAdapter.STATE_ON -> {
                        close()
                        open()
                    }

                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                        close()
                    }
                }
            }
        }

    /** Idempotent; main thread (`MeshService.startMesh`). */
    fun start() {
        if (collector != null) return
        collector =
            scope.launch {
                // Latest, so a new emission cancels the wait for the old deadline.
                source.inputs.collectLatest { latest ->
                    inputs = latest
                    val until = if (latest.enabled) MeshPause.activeDeadline(latest.pausedUntil, clock()) else null
                    setAdvert(until != null)
                    // A pause that lapses on the clock may not move any input before the mesh is back up.
                    if (until != null) {
                        delay(until - clock())
                        setAdvert(false)
                    }
                }
            }
        receiverRegistered =
            runCatching {
                ContextCompat.registerReceiver(
                    appContext,
                    stateReceiver,
                    IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
            }.onFailure { Log.w(TAG, "adapter-state receiver not registered: ${it.message}") }.isSuccess
        open()
    }

    /** Idempotent; main thread (`MeshService.onDestroy`). */
    fun stop() {
        collector?.cancel()
        collector = null
        if (receiverRegistered) {
            runCatching { appContext.unregisterReceiver(stateReceiver) }
            receiverRegistered = false
        }
        close()
        inputs = null
    }

    @Synchronized
    private fun open() {
        if (adapter?.isEnabled != true) return
        openRfcomm()
        openGatt()
    }

    /** Holds the monitor. */
    private fun openRfcomm() {
        if (rfcomm != null) return
        val ss =
            runCatching { adapter?.listenUsingRfcommWithServiceRecord(SDP_NAME, WearStatusUuids.RFCOMM) }
                .onFailure { Log.w(TAG, "RFCOMM listen failed: ${it.message}") }
                .getOrNull() ?: return
        rfcomm = ss
        scope.launch(Dispatchers.IO) { acceptLoop(ss) }
        Log.i(TAG, "status RFCOMM open")
    }

    /** Holds the monitor. */
    private fun openGatt() {
        if (server != null) return
        val opened =
            runCatching { bluetoothManager?.openGattServer(appContext, callback) }
                .onFailure { Log.w(TAG, "openGattServer failed: ${it.message}") }
                .getOrNull() ?: return
        val service = BluetoothGattService(WearStatusUuids.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                WearStatusUuids.STATUS,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED,
            ),
        )
        if (runCatching { opened.addService(service) }.getOrDefault(false)) {
            server = opened
            Log.i(TAG, "status service open")
            if (wantAdvert) startAdvert()
        } else {
            Log.w(TAG, "addService refused")
            runCatching { opened.close() }
        }
    }

    @Synchronized
    private fun close() {
        stopAdvert()
        rfcomm?.let {
            rfcomm = null
            runCatching { it.close() } // unblocks the accept loop
            Log.i(TAG, "status RFCOMM closed")
        }
        val s = server ?: return
        server = null
        runCatching {
            s.clearServices()
            s.close()
        }
        Log.i(TAG, "status service closed")
    }

    private fun acceptLoop(ss: BluetoothServerSocket) {
        while (rfcomm === ss) {
            val client = runCatching { ss.accept() }.getOrNull() ?: break
            scope.launch(Dispatchers.IO) { serve(client) }
        }
        if (rfcomm === ss) Log.w(TAG, "RFCOMM accept failed; the server stays down until Bluetooth cycles")
    }

    /**
     * One frame to one bonded watch, then linger for the watch to close: a close right behind the write could
     * drop the frame's tail. [LINGER_MS] bounds a watch that never closes.
     */
    private fun serve(client: BluetoothSocket) {
        val linger =
            scope.launch {
                delay(LINGER_MS)
                runCatching { client.close() }
            }
        try {
            if (client.remoteDevice.bondState != BluetoothDevice.BOND_BONDED) {
                Log.d(TAG, "RFCOMM read refused: not bonded")
                return
            }
            val bytes = snapshot() ?: return // nothing written: the watch reads the end of the stream
            client.outputStream.apply {
                write(WearStatusFrame.frame(bytes))
                flush()
            }
            Log.d(TAG, "RFCOMM read served (${bytes.size} B)")
            val input = client.inputStream
            var next = input.read()
            while (next >= 0) next = input.read() // until the watch closes
        } catch (_: IOException) {
            // The watch closed (the normal end) or the linger did.
        } finally {
            linger.cancel()
            runCatching { client.close() }
        }
    }

    @Synchronized
    private fun setAdvert(on: Boolean) {
        wantAdvert = on
        if (on) startAdvert() else stopAdvert()
    }

    /** Holds the monitor. Needs the server up: an advert that leads nowhere is only a cost. */
    private fun startAdvert() {
        if (advertiser != null || server == null) return
        val adv = adapter?.bluetoothLeAdvertiser ?: return
        val params =
            AdvertisingSetParameters
                .Builder()
                .setLegacyMode(true)
                .setConnectable(true)
                .setScannable(true) // a legacy connectable set must be scannable
                .setInterval(AdvertisingSetParameters.INTERVAL_HIGH)
                .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_MEDIUM)
                .build()
        runCatching { adv.startAdvertisingSet(params, AdvertiseData.Builder().build(), null, null, null, advertCallback) }
            .onSuccess { advertiser = adv }
            .onFailure { Log.w(TAG, "pause advert start threw: ${it.message}") }
    }

    /** Holds the monitor. */
    private fun stopAdvert() {
        val adv = advertiser ?: return
        advertiser = null
        runCatching { adv.stopAdvertisingSet(advertCallback) }
        Log.i(TAG, "pause advert down")
    }

    private fun snapshot(): ByteArray? = inputs?.let { WearStatusCodec.encode(WearStatusPolicy.of(it, clock())) }

    private val callback =
        object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(
                device: BluetoothDevice,
                status: Int,
                newState: Int,
            ) {
                // Every LE link on the phone lands here, mesh peers included; only a bonded central can be a watch.
                if (device.bondState != BluetoothDevice.BOND_BONDED) return
                val what = if (newState == BluetoothProfile.STATE_CONNECTED) "connected" else "disconnected"
                Log.d(TAG, "bonded central $what (status $status)")
            }

            override fun onCharacteristicReadRequest(
                device: BluetoothDevice,
                requestId: Int,
                offset: Int,
                characteristic: BluetoothGattCharacteristic,
            ) {
                val s = server ?: return
                val (status, value) =
                    when {
                        characteristic.uuid != WearStatusUuids.STATUS -> {
                            BluetoothGatt.GATT_READ_NOT_PERMITTED to null
                        }

                        device.bondState != BluetoothDevice.BOND_BONDED -> {
                            BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION to null
                        }

                        else -> {
                            val bytes = snapshot()
                            when {
                                bytes == null -> BluetoothGatt.GATT_FAILURE to null
                                offset > bytes.size -> BluetoothGatt.GATT_INVALID_OFFSET to null
                                else -> BluetoothGatt.GATT_SUCCESS to bytes.copyOfRange(offset, bytes.size)
                            }
                        }
                    }
                if (status != BluetoothGatt.GATT_SUCCESS) Log.d(TAG, "read refused: $status")
                runCatching { s.sendResponse(device, requestId, status, offset, value) }
            }
        }

    private companion object {
        const val TAG = "KnitWear"
        const val SDP_NAME = "Knit mesh status"
        const val LINGER_MS = 3_000L
    }
}
