package app.getknit.knit.mesh.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Rings the GATT doorbell of the peer on one Bluetooth link (ADR 2026-09.dqvb): a write without response to
 * [DoorbellPolicy.DOORBELL_UUID], over the ACL the link's L2CAP channel already runs on. The transport makes one per
 * link whose HELLO carries [app.getknit.knit.mesh.protocol.Protocol.CAP_DOORBELL] — an iPhone that dialed us — and
 * [poke]s it after each frame that rings ([DoorbellPolicy.rings]); [DoorbellPolicy.Schedule] decides when.
 *
 * One coroutine owns the GATT client and the schedule, woken through a conflated channel, so a poke never blocks the
 * writer and a ring asked for during the lookup waits for it. The trailing ring of a burst is that loop's timeout.
 *
 * **The lookup** runs at the first ring. `connectGatt` on a device with an open LE link attaches a client to that
 * link rather than dialing (AOSP `gatt_connect`), which is why the attach window is [ATTACH_TIMEOUT_MS] and the link
 * is re-checked ([isLive]) just before: if the ACL is already gone, the call would dial the peer's address instead,
 * and closing the client on the timeout cancels that dial. The client holds the ACL while it is open, so it never
 * outlives the link — [close] ends it with the link. A peer with no doorbell (or one that takes no write without a
 * response, which is the only kind a suspended app can take without holding the link up) is not asked again on this
 * link, and its client is closed at once; the open CoC keeps the ACL up without it.
 *
 * Callbacks arrive on a binder thread (the four-arg `connectGatt` passes no Handler); each lookup gets its own
 * [Client], whose deferreds are made before the call, so nothing is handed between threads through a plain field.
 *
 * Device-verified only — there is no host GATT stack — like [app.getknit.knit.mesh.bluetooth.meshtastic.MeshtasticGatt];
 * the rules it follows are [DoorbellPolicy]'s, which are JVM-tested.
 */
@SuppressLint("MissingPermission")
internal class BleDoorbell(
    private val context: Context,
    private val device: BluetoothDevice,
    private val nodeId: String,
    private val scope: CoroutineScope,
    private val isLive: () -> Boolean,
    private val onRang: () -> Unit,
    private val now: () -> Long,
) {
    private val pokes = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null

    // The loop's own state: only the loop coroutine reads or writes these.
    private val schedule = DoorbellPolicy.Schedule()
    private val lookups = DoorbellPolicy.Lookups()
    private var client: Client? = null
    private var doorbell: BluetoothGattCharacteristic? = null
    private var absent = false

    fun start() {
        job = scope.launch(Dispatchers.IO) { loop() }
    }

    /** A frame that rings went out on the link. Never blocks. */
    fun poke() {
        pokes.trySend(Unit)
    }

    /** The link ended: stop, and let the GATT client go with it. */
    fun close() {
        job?.cancel()
    }

    private suspend fun loop() {
        try {
            while (currentCoroutineContext().isActive) {
                val due = schedule.dueAt
                val poked =
                    if (due == null) {
                        pokes.receive()
                        true
                    } else {
                        withTimeoutOrNull((due - now()).coerceAtLeast(0L)) { pokes.receive() } != null
                    }
                val t = now()
                if (if (poked) schedule.afterFrame(t) else schedule.onDue(t)) ring()
            }
        } finally {
            dropClient()
        }
    }

    private suspend fun ring() {
        val ch = lookUp() ?: return
        val c = client ?: return
        if (write(c, ch)) {
            onRang()
            Log.d(TAG, "bt ring $nodeId")
        } else {
            // Rings are seconds apart and nothing else runs on this client, so a refused write is a client whose
            // last callback never came (it stays busy for good) or that lost the link: drop it and look again.
            Log.i(TAG, "bt doorbell wedged $nodeId")
            dropClient()
            lookups.failed(now())
        }
    }

    /** The doorbell to write, looking it up first if this link has not; null when there is none to ring now. */
    private suspend fun lookUp(): BluetoothGattCharacteristic? {
        val held = client
        if (held != null && held.stale) {
            // The client dropped, or the peer's services changed: what it found no longer holds.
            dropClient()
            if (held.disconnected) lookups.failed(now())
        }
        doorbell?.let { return it }
        if (absent || !lookups.mayTry(now()) || !isLive()) return null
        val c = Client()
        client = c
        return when (val phase = c.open()) {
            null -> {
                val ch = c.doorbell()
                if (ch == null) {
                    absent = true
                    dropClient()
                    Log.i(TAG, "bt doorbell absent $nodeId")
                } else {
                    doorbell = ch
                    Log.i(TAG, "bt doorbell found $nodeId (${device.address})")
                }
                ch
            }

            else -> {
                dropClient()
                lookups.failed(now())
                Log.i(TAG, "bt doorbell lookup failed $nodeId ($phase)")
                null
            }
        }
    }

    private fun write(
        c: Client,
        ch: BluetoothGattCharacteristic,
    ): Boolean {
        val g = c.gatt ?: return false
        // A revoked grant or an unbound service throws; either is a refused ring, never a crash on the scope.
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(ch, VALUE, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    ch.value = VALUE
                    g.writeCharacteristic(ch)
                }
            }
        }.getOrDefault(false)
    }

    private fun dropClient() {
        client?.close()
        client = null
        doorbell = null
    }

    /** One GATT client, from its attach to its close; the callbacks complete what the loop awaits. */
    private inner class Client : BluetoothGattCallback() {
        @Volatile
        var gatt: BluetoothGatt? = null

        private val attached = CompletableDeferred<Boolean>()
        private val discovered = CompletableDeferred<Boolean>()

        /** The link under the client went down after it attached. */
        @Volatile
        var disconnected = false
            private set

        @Volatile
        private var servicesChanged = false

        /** What this client found can no longer be trusted. */
        val stale: Boolean get() = disconnected || servicesChanged

        /** Attaches and discovers; null on success, else the phase that failed. */
        @Suppress("DEPRECATION")
        suspend fun open(): String? {
            // `DEPRECATION`: compileSdk 37.1 deprecates every `connectGatt(Context, …)` overload in favour of the
            // API-37 `connectGatt(BluetoothGattConnectionSettings, Executor, callback)`, eight releases above minSdk 29.
            gatt = runCatching { device.connectGatt(context, false, this, BluetoothDevice.TRANSPORT_LE) }.getOrNull()
                ?: return "connectGatt"
            if (withTimeoutOrNull(ATTACH_TIMEOUT_MS) { attached.await() } != true) return "attach"
            if (runCatching { gatt?.discoverServices() }.getOrNull() != true) return "discover"
            if (withTimeoutOrNull(DISCOVER_TIMEOUT_MS) { discovered.await() } != true) return "discover"
            return null
        }

        /** The doorbell among the discovered services, if the peer serves one that takes a write without response. */
        fun doorbell(): BluetoothGattCharacteristic? =
            gatt
                ?.getService(DoorbellPolicy.SERVICE_UUID)
                ?.getCharacteristic(DoorbellPolicy.DOORBELL_UUID)
                ?.takeIf { it.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 }

        fun close() {
            val g = gatt ?: return
            gatt = null
            // disconnect() drops this client's hold on the link (or cancels a dial the attach turned into); close()
            // unregisters it. The adapter may be off and the service unbound, so neither may throw out of here.
            runCatching { g.disconnect() }
            runCatching { g.close() }
        }

        override fun onConnectionStateChange(
            g: BluetoothGatt,
            status: Int,
            newState: Int,
        ) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    attached.complete(true)
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    disconnected = true
                    attached.complete(false)
                    discovered.complete(false)
                }
            }
        }

        override fun onServicesDiscovered(
            g: BluetoothGatt,
            status: Int,
        ) {
            discovered.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        // API 31+; never called below it.
        override fun onServiceChanged(g: BluetoothGatt) {
            servicesChanged = true
        }
    }

    private companion object {
        const val TAG = BluetoothMeshTransport.TAG

        // An attach to a live link reports connected within milliseconds; past this the ACL is gone and the call is
        // dialing instead, which the timeout's close cancels.
        const val ATTACH_TIMEOUT_MS = 2_000L
        const val DISCOVER_TIMEOUT_MS = 10_000L

        // The doorbell's value means nothing; one byte is the least a write carries.
        val VALUE = byteArrayOf(1)
    }
}
