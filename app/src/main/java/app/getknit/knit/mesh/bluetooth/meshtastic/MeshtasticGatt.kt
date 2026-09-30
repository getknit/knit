package app.getknit.knit.mesh.bluetooth.meshtastic

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import app.getknit.knit.mesh.bluetooth.BleConnectArbiter
import app.getknit.knit.mesh.lora.BondState
import app.getknit.knit.mesh.lora.DialMode
import app.getknit.knit.mesh.lora.DialResult
import app.getknit.knit.mesh.lora.GattChannel
import app.getknit.knit.mesh.lora.GattEvent
import app.getknit.knit.mesh.lora.GattResult
import app.getknit.knit.mesh.lora.MeshtasticGattDialer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The **only** `android.bluetooth.*` importer for the LoRa feature: a [MeshtasticGattDialer] that connects
 * to a Meshtastic board, discovers its service, negotiates the MTU, and hands back an [AndroidGattChannel].
 * Everything protocol-shaped runs pure above it (`mesh/lora/MeshtasticSession`). Mirrors the mesh BLE
 * plane's hard-won idioms: the adapter is a **provider** (re-fetched per dial, never cached, so an adapter
 * off→on cycle doesn't strand us), and every GATT op carries an explicit timeout with the `settled`-race
 * watchdog so a callback that never comes can't wedge the actor.
 *
 * Two ways to dial ([DialMode], ADR 2026-09.hp88). A **direct** dial (`autoConnect = false`) is what a board
 * that just dropped gets: fast, but a high-duty scan for its whole window, so it holds [BleConnectArbiter] and the
 * mesh's own scan pauses for it. A **background** dial (`autoConnect = true`) is what a board that stayed away
 * gets: its address goes on the controller's accept list and the controller connects when the board next
 * advertises, at low duty, so the arbiter is taken only for the setup after that. autoConnect is slow or dead on
 * some stacks, which is why the session keeps an hourly direct dial as the net rather than trusting it alone.
 *
 * Callbacks land on the **main looper** — we call the four-arg `connectGatt`, which delivers on the
 * caller's looper. A `HandlerThread` named `meshtastic-gatt` used to be started here and never passed to
 * `connectGatt`, so it only leaked a thread; it is gone. Moving delivery onto a dedicated thread means the
 * six-arg overload (also the non-deprecated one), and it re-times the MTU settle below, which was tuned
 * against main-looper delivery — so it needs a board on the bench, not a compile.
 *
 * Device-verified only — there is no host GATT stack, so this class has no unit test; its logic lives behind
 * the pure session/codec, which do.
 */
@SuppressLint("MissingPermission")
internal class MeshtasticGatt(
    context: Context,
    private val arbiter: BleConnectArbiter,
) : MeshtasticGattDialer {
    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter // provider, not a cached handle

    private val _adapterOn = MutableStateFlow(adapter?.isEnabled == true)
    override val adapterOn = _adapterOn.asStateFlow()

    private val stateReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) {
                if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                    _adapterOn.value = adapter?.isEnabled == true
                }
            }
        }

    init {
        runCatching {
            ContextCompat.registerReceiver(
                appContext,
                stateReceiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.onFailure { Log.w(TAG, "adapter-state receiver not registered: ${it.message}") }
    }

    override fun bondState(address: String): BondState =
        when (adapter?.getRemoteDevice(address)?.bondState) {
            BluetoothDevice.BOND_BONDED -> BondState.BONDED
            BluetoothDevice.BOND_BONDING -> BondState.BONDING
            BluetoothDevice.BOND_NONE -> BondState.NONE
            else -> BondState.UNKNOWN
        }

    override suspend fun dial(
        address: String,
        mode: DialMode,
        timeoutMs: Long,
    ): DialResult {
        val device = adapter?.getRemoteDevice(address) ?: return DialResult.NoHardware
        if (adapter?.isEnabled != true) return DialResult.AdapterOff
        return when (mode) {
            // The arbiter slot covers the whole connect→MTU→discover window: a direct connect is a high-duty
            // scan of its own, and the mesh's scan would starve it.
            DialMode.Direct -> withArbiter { connectAndConfigure(device, autoConnect = false, timeoutMs) }

            // The controller waits for the board off its accept list at low duty, which starves nothing, so the
            // mesh keeps scanning for however long that takes (ADR 2026-09.hp88); only the setup after the board
            // has connected pauses it.
            DialMode.Background -> connectAndConfigure(device, autoConnect = true, timeoutMs)
        }
    }

    private inline fun <T> withArbiter(block: () -> T): T {
        arbiter.begin(ARBITER_TAG)
        try {
            return block()
        } finally {
            arbiter.end(ARBITER_TAG)
        }
    }

    // `DEPRECATION`: compileSdk 37.1 deprecates every `connectGatt(Context, …)` overload in favour of the
    // API-37 `connectGatt(BluetoothGattConnectionSettings, Executor, callback)`, which is eight releases
    // above minSdk 29 — this is still the only form that reaches the boards we support.
    @Suppress("DEPRECATION")
    private suspend fun connectAndConfigure(
        device: BluetoothDevice,
        autoConnect: Boolean,
        timeoutMs: Long,
    ): DialResult {
        val channel = AndroidGattChannel()
        val gatt =
            device.connectGatt(appContext, autoConnect, channel.callback, BluetoothDevice.TRANSPORT_LE)
                ?: return DialResult.Failed(status = -1, phase = "connectGatt")
        channel.attach(gatt)
        // Every exit but Opened closes the client, cancellation included: a stop() or a board swap inside the
        // wait used to leave the connect registered with the stack — up to 30 s for a direct dial, and for a
        // background one until the board next came into range, reconnecting a session nobody owned.
        try {
            // A refusal is Failed, never Timeout, so it takes the session's backoff: a background connect the
            // stack turns down at once (an address not in its cache is status 133 on some stacks) would
            // otherwise read as a spent window and be re-dialled straight away, in a loop.
            val result =
                when (val wait = channel.awaitConnected(timeoutMs, adapterOn)) {
                    ConnectWait.Connected -> if (autoConnect) withArbiter { finishSetup(channel) } else finishSetup(channel)
                    is ConnectWait.Refused -> DialResult.Failed(status = wait.status, phase = "connect")
                    ConnectWait.TimedOut -> DialResult.Timeout
                    ConnectWait.AdapterOff -> DialResult.AdapterOff
                }
            if (result !is DialResult.Opened) channel.close()
            return result
        } catch (e: CancellationException) {
            channel.close()
            throw e
        }
    }

    private suspend fun finishSetup(channel: AndroidGattChannel): DialResult {
        val mtu = channel.negotiateMtu()
        if (mtu < MIN_MTU) {
            channel.close()
            return DialResult.Failed(status = mtu, phase = "mtu")
        }
        if (!channel.discover(DISCOVER_TIMEOUT_MS) || !channel.resolveCharacteristics()) {
            channel.close()
            return DialResult.Failed(status = -1, phase = "service")
        }
        Log.d(TAG, "dial opened mtu=$mtu")
        return DialResult.Opened(channel, mtu)
    }

    /** One open GATT connection; serializes ops through a Mutex and a single completable per op. */
    private class AndroidGattChannel : GattChannel {
        private val eventsChannel = Channel<GattEvent>(Channel.UNLIMITED)
        override val events = eventsChannel

        @Volatile
        private var gatt: BluetoothGatt? = null
        private val opLock = Mutex()

        @Volatile
        private var pending: CompletableDeferred<GattResult<ByteArray>>? = null

        private var mtuResult: CompletableDeferred<Int>? = null

        // Made with the channel, before `connectGatt` is called, so a connect that lands at once is not missed.
        private val connectResult = CompletableDeferred<ConnectWait>()
        private var discoverResult: CompletableDeferred<Boolean>? = null

        private var fromRadio: BluetoothGattCharacteristic? = null
        private var toRadio: BluetoothGattCharacteristic? = null
        private var fromNum: BluetoothGattCharacteristic? = null

        fun attach(g: BluetoothGatt) {
            gatt = g
        }

        val callback =
            object : BluetoothGattCallback() {
                override fun onConnectionStateChange(
                    g: BluetoothGatt,
                    status: Int,
                    newState: Int,
                ) {
                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            connectResult.complete(ConnectWait.Connected)
                        }

                        BluetoothProfile.STATE_DISCONNECTED -> {
                            connectResult.complete(ConnectWait.Refused(status))
                            eventsChannel.trySend(GattEvent.Disconnected(status))
                            pending?.complete(GattResult.Closed)
                        }
                    }
                }

                // The size is trusted here, not the status: the stack reports the bearer's *current* ATT
                // MTU either way, so a refused exchange on a link some other GATT client already negotiated
                // still carries the real number. [negotiateMtu] decides whether what came back is usable.
                override fun onMtuChanged(
                    g: BluetoothGatt,
                    mtu: Int,
                    status: Int,
                ) {
                    mtuResult?.complete(mtu)
                }

                override fun onServicesDiscovered(
                    g: BluetoothGatt,
                    status: Int,
                ) {
                    discoverResult?.complete(status == BluetoothGatt.GATT_SUCCESS)
                }

                // API 33+ read; the deprecated form below covers 29–32.
                override fun onCharacteristicRead(
                    g: BluetoothGatt,
                    ch: BluetoothGattCharacteristic,
                    value: ByteArray,
                    status: Int,
                ) = completeRead(ch, value, status)

                @Deprecated("Pre-33 signature", ReplaceWith(""))
                override fun onCharacteristicRead(
                    g: BluetoothGatt,
                    ch: BluetoothGattCharacteristic,
                    status: Int,
                ) {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        @Suppress("DEPRECATION")
                        completeRead(ch, ch.value ?: ByteArray(0), status)
                    }
                }

                override fun onCharacteristicWrite(
                    g: BluetoothGatt,
                    ch: BluetoothGattCharacteristic,
                    status: Int,
                ) {
                    if (ch.uuid == MeshtasticUuids.TO_RADIO) {
                        pending?.complete(
                            if (status ==
                                BluetoothGatt.GATT_SUCCESS
                            ) {
                                GattResult.Ok(ByteArray(0))
                            } else {
                                GattResult.Failed(status)
                            },
                        )
                    }
                }

                override fun onDescriptorWrite(
                    g: BluetoothGatt,
                    descriptor: BluetoothGattDescriptor,
                    status: Int,
                ) {
                    if (descriptor.uuid == MeshtasticUuids.CCCD) {
                        pending?.complete(
                            if (status ==
                                BluetoothGatt.GATT_SUCCESS
                            ) {
                                GattResult.Ok(ByteArray(0))
                            } else {
                                GattResult.Failed(status)
                            },
                        )
                    }
                }

                override fun onCharacteristicChanged(
                    g: BluetoothGatt,
                    ch: BluetoothGattCharacteristic,
                    value: ByteArray,
                ) = onFromNum(ch, value)

                @Deprecated("Pre-33 signature", ReplaceWith(""))
                override fun onCharacteristicChanged(
                    g: BluetoothGatt,
                    ch: BluetoothGattCharacteristic,
                ) {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        @Suppress("DEPRECATION")
                        onFromNum(ch, ch.value ?: ByteArray(0))
                    }
                }
            }

        private fun completeRead(
            ch: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (ch.uuid == MeshtasticUuids.FROM_RADIO) {
                pending?.complete(if (status == BluetoothGatt.GATT_SUCCESS) GattResult.Ok(value.copyOf()) else GattResult.Failed(status))
            }
        }

        private fun onFromNum(
            ch: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (ch.uuid == MeshtasticUuids.FROM_NUM) {
                val counter =
                    if (value.size >= UINT_BYTES) {
                        ByteBuffer
                            .wrap(value)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .int
                            .toUInt()
                    } else {
                        0u
                    }
                eventsChannel.trySend(GattEvent.Notified(counter))
            }
        }

        /**
         * Waits up to [timeoutMs] for the link to come up, or for the adapter to go off — a background wait can
         * run for most of an hour, and the session has to see the adapter go rather than a window running out.
         */
        suspend fun awaitConnected(
            timeoutMs: Long,
            adapterOn: StateFlow<Boolean>,
        ): ConnectWait =
            withTimeoutOrNull(timeoutMs) {
                coroutineScope {
                    val off =
                        async {
                            adapterOn.first { !it }
                            ConnectWait.AdapterOff
                        }
                    select {
                        connectResult.onAwait { it }
                        off.onAwait { it }
                    }.also { off.cancel() }
                }
            } ?: ConnectWait.TimedOut

        /**
         * Negotiates the ATT MTU, letting the link settle first and retrying a refused exchange rather than
         * failing the whole dial on one.
         *
         * A bonded board starts its SMP handshake the moment the ACL is up, and `STATE_CONNECTED` reaches us
         * *before* the link is encrypted — so an Exchange MTU sent the instant we are called lands mid-
         * handshake. Against a 2.8 alpha board that lost the race every time: the board answered late, the
         * stack had stopped listening (`ATT - Ignore wrong response. Receives (03)`), and the app saw the
         * default 23 with a non-success status. BlueZ negotiates 517 on the same board because it does not
         * touch ATT until the link is encrypted. [SETTLE_MS] buys that same ordering, and the retries cover a
         * board slower than one settle.
         */
        suspend fun negotiateMtu(): Int {
            var last = -1
            repeat(MTU_ATTEMPTS) { attempt ->
                delay(if (attempt == 0) SETTLE_MS else MTU_RETRY_MS)
                last = requestMtuOnce(REQUEST_MTU, MTU_TIMEOUT_MS)
                if (last >= MIN_MTU) return last
                Log.d(TAG, "mtu exchange refused (got $last), attempt ${attempt + 1}/$MTU_ATTEMPTS")
            }
            return last
        }

        private suspend fun requestMtuOnce(
            mtu: Int,
            timeoutMs: Long,
        ): Int {
            val d = CompletableDeferred<Int>()
            mtuResult = d
            if (gatt?.requestMtu(mtu) != true) return -1
            return withTimeoutOrNull(timeoutMs) { d.await() } ?: -1
        }

        suspend fun discover(timeoutMs: Long): Boolean {
            val d = CompletableDeferred<Boolean>()
            discoverResult = d
            if (gatt?.discoverServices() != true) return false
            return withTimeoutOrNull(timeoutMs) { d.await() } ?: false
        }

        fun resolveCharacteristics(): Boolean {
            val service = gatt?.getService(MeshtasticUuids.SERVICE) ?: return false
            toRadio = service.getCharacteristic(MeshtasticUuids.TO_RADIO)
            fromRadio = service.getCharacteristic(MeshtasticUuids.FROM_RADIO)
            fromNum = service.getCharacteristic(MeshtasticUuids.FROM_NUM)
            return toRadio != null && fromRadio != null && fromNum != null
        }

        override suspend fun subscribeFromNum(timeoutMs: Long): GattResult<Unit> =
            op(timeoutMs) {
                val ch = fromNum ?: return@op false
                val g = gatt ?: return@op false
                g.setCharacteristicNotification(ch, true)
                val cccd = ch.getDescriptor(MeshtasticUuids.CCCD) ?: return@op false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    run {
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        g.writeDescriptor(cccd)
                    }
                }
            }.map { }

        override suspend fun writeToRadio(
            bytes: ByteArray,
            timeoutMs: Long,
        ): GattResult<Unit> =
            op(timeoutMs) {
                val ch = toRadio ?: return@op false
                val g = gatt ?: return@op false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeCharacteristic(ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    run {
                        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        ch.value = bytes
                        g.writeCharacteristic(ch)
                    }
                }
            }.map { }

        override suspend fun readFromRadio(timeoutMs: Long): GattResult<ByteArray> =
            op(timeoutMs) {
                val ch = fromRadio ?: return@op false
                gatt?.readCharacteristic(ch) == true
            }

        override fun close() {
            runCatching {
                gatt?.disconnect()
                gatt?.close()
            }
            gatt = null
            eventsChannel.close()
        }

        /** Serializes one GATT op: issues [issue], awaits its callback, or Timeout/Closed/Failed. */
        private suspend fun op(
            timeoutMs: Long,
            issue: () -> Boolean,
        ): GattResult<ByteArray> =
            opLock.withLock {
                val d = CompletableDeferred<GattResult<ByteArray>>()
                pending = d
                if (!issue()) {
                    pending = null
                    return@withLock GattResult.Failed(-1)
                }
                val result = withTimeoutOrNull(timeoutMs) { d.await() } ?: GattResult.Timeout
                pending = null
                result
            }

        private fun <T> GattResult<ByteArray>.map(block: (ByteArray) -> T): GattResult<T> =
            when (this) {
                is GattResult.Ok -> GattResult.Ok(block(value))
                is GattResult.Failed -> this
                GattResult.Timeout -> GattResult.Timeout
                GattResult.Closed -> GattResult.Closed
            }
    }

    /** How a connect wait ended. */
    private sealed interface ConnectWait {
        data object Connected : ConnectWait

        /** The stack reported the link down before it was ever up, with its GATT status. */
        data class Refused(
            val status: Int,
        ) : ConnectWait

        data object TimedOut : ConnectWait

        data object AdapterOff : ConnectWait
    }

    private companion object {
        const val TAG = "MeshtasticGatt"
        const val ARBITER_TAG = "lora-dial"
        const val MTU_TIMEOUT_MS = 10_000L

        // The Exchange MTU must not race the SMP handshake a bonded board starts at ACL-up; see
        // AndroidGattChannel.negotiateMtu. Measured on a Heltec V4 / 2.8 alpha: encryption completes ~200 ms
        // after STATE_CONNECTED reaches us, so the settle clears it with room to spare.
        const val SETTLE_MS = 750L
        const val MTU_RETRY_MS = 1_000L
        const val MTU_ATTEMPTS = 3
        const val DISCOVER_TIMEOUT_MS = 10_000L
        const val REQUEST_MTU = 512

        // A floor to catch a failed negotiation (default ATT MTU 23); a real board negotiates 255+ and the
        // transport sizes its fragments DOWN to whatever this is, so no single write ever needs splitting.
        const val MIN_MTU = 128
        const val UINT_BYTES = 4
    }
}
