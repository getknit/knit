package app.getknit.knit.mesh.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
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
import app.getknit.knit.mesh.lora.BondState
import app.getknit.knit.mesh.lora.GattResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The GATT client core a LoRa board kind dials through (ADR 2026-11.gsqu, Stage 3c): the connect, the MTU settle, the
 * service discovery, the one-op-at-a-time serialisation and [stackCall], extracted from `MeshtasticGatt` so the
 * MeshCore dialer runs the same hard-won code instead of a copy. Everything that differs per kind is passed in and
 * never shared: the numbers and the log tag ([Spec]), which characteristic answers which op and what a notify means
 * ([Route]), what each way a connect can end maps to ([Outcomes], the `Refused` → `Failed` and the
 * `connectGatt`-threw mapping among them), which [BleConnectArbiter] tag a dial holds and over which span ([holding]),
 * and the channel's own close. Meshtastic keeps its log lines under its own tag (`adb logcat -s MeshtasticGatt` is a
 * recipe oracle, `lora-bridge.md`).
 *
 * The adapter is a **provider**, re-fetched per dial and never cached, so an adapter off→on cycle doesn't strand a
 * stale handle. Every GATT op carries an explicit timeout, so a callback that never comes can't wedge an actor.
 *
 * **Callbacks run inline on a binder thread, in order** — not on the main looper, which `MeshtasticGatt`'s KDoc used to
 * claim. [connect] calls the four-argument `connectGatt`, which hands the platform a null `Handler`, and
 * `BluetoothGatt` then runs each callback straight on the binder thread that delivered it (AOSP
 * `BluetoothGatt.runOrQueueCallback`); one client's callbacks are oneway calls on one binder object, so they arrive one
 * at a time and in order. That is why every value a callback hands over is copied before it leaves the callback (the
 * stack may reuse the array), and why the deferreds the callbacks complete are `@Volatile`. Moving delivery onto a
 * dedicated thread means the six-argument overload and re-timing [Spec.settleMs], which was measured against this
 * delivery: a bench job, not a compile.
 *
 * Device-verified only: there is no host GATT stack, so the Android half has no unit test; its decisions sit in the
 * pure session and codecs above it, and in the pure helpers beside each kind.
 */
@SuppressLint("MissingPermission")
internal class BoardGatt(
    context: Context,
    private val arbiter: BleConnectArbiter,
    private val spec: Spec,
) {
    /**
     * A kind's numbers and names. [tag] is the logcat tag and [what] the word each [stackCall] warning starts with, so
     * a kind's lines stay where its recipes grep for them. The MTU is asked for at [requestMtu] after [settleMs], retried
     * [mtuAttempts] times [mtuRetryMs] apart with each exchange given [mtuTimeoutMs], and anything under [minMtu] is the
     * kind's to refuse. [stackThrew] is the [GattResult.Failed] status an op reports when the stack threw.
     */
    class Spec(
        val tag: String,
        val what: String,
        val requestMtu: Int,
        val minMtu: Int,
        val settleMs: Long,
        val mtuRetryMs: Long,
        val mtuAttempts: Int,
        val mtuTimeoutMs: Long,
        val discoverTimeoutMs: Long,
        val stackThrew: Int,
    )

    /**
     * A kind's half of the callback demultiplexing: which characteristic or descriptor completes the op in flight, what
     * a notify means, and what a disconnect tells the channel. Implemented by the kind's channel, which also owns the
     * close the core calls when a dial does not open.
     */
    interface Route {
        /** A write to [characteristic] completes the op in flight. */
        fun completesWrite(characteristic: UUID): Boolean

        /** A write to [descriptor] completes the op in flight. */
        fun completesDescriptorWrite(descriptor: UUID): Boolean

        /** A read of [characteristic] completes the op in flight. */
        fun completesRead(characteristic: UUID): Boolean

        /** A notify on [characteristic]; [value] is already a copy, the channel's to keep. On the binder thread. */
        fun onNotified(
            characteristic: UUID,
            value: ByteArray,
        )

        /** The link went down, with the stack's status; called before the op in flight completes `Closed`. */
        fun onDisconnected(status: Int)

        /** Ends the channel: the core calls it on every dial that does not open, cancellation and a throw included. */
        fun close()
    }

    /** What each way a connect can end means in a kind's own dial result. */
    interface Outcomes<R> {
        /** `connectGatt` handed back no client: [answered] is false when the call threw rather than returned null. */
        fun notStarted(answered: Boolean): R

        /** The stack reported the link down before it was ever up, with its GATT status. */
        fun refused(status: Int): R

        val timedOut: R

        val adapterOff: R

        /** Whether [result] keeps the link open; every other result closes it. */
        fun keepsOpen(result: R): Boolean
    }

    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter // provider, not a cached handle

    private val _adapterOn = MutableStateFlow(adapter?.isEnabled == true)
    val adapterOn: StateFlow<Boolean> = _adapterOn.asStateFlow()

    // ACTION_STATE_CHANGED comes from system_server, which a not-exported receiver hears (9utz).
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
        }.onFailure { Log.w(spec.tag, "adapter-state receiver not registered: ${it.message}") }
    }

    fun bondState(address: String): BondState =
        when (stackCall("bondState", null) { adapter?.getRemoteDevice(address)?.bondState }) {
            BluetoothDevice.BOND_BONDED -> BondState.BONDED
            BluetoothDevice.BOND_BONDING -> BondState.BONDING
            BluetoothDevice.BOND_NONE -> BondState.NONE
            else -> BondState.UNKNOWN
        }

    /**
     * The device at [address], or null with no adapter. Not through [stackCall]: a malformed address throws, and what a
     * dial throws, the session's loop catches.
     */
    fun remoteDevice(address: String): BluetoothDevice? = adapter?.getRemoteDevice(address)

    fun adapterEnabled(): Boolean = adapter?.isEnabled == true

    /** Runs [block] holding the arbiter under [tag], so the mesh's scan pauses for it (`BleConnectArbiter` counts holds). */
    suspend fun <T> holding(
        tag: String,
        block: suspend () -> T,
    ): T {
        arbiter.begin(tag)
        try {
            return block()
        } finally {
            arbiter.end(tag)
        }
    }

    /**
     * Takes the arbiter under [tag] until the returned [Hold] is released, for a dial that must let go part-way: the
     * MeshCore dialer releases it before a subscribe that may wait on the user's passkey.
     */
    fun hold(tag: String): Hold {
        arbiter.begin(tag)
        return Hold(tag)
    }

    /** One arbiter hold; [release] ends it once, however many times it is called. */
    inner class Hold internal constructor(
        private val tag: String,
    ) {
        private val held = AtomicBoolean(true)

        fun release() {
            if (held.compareAndSet(true, false)) arbiter.end(tag)
        }
    }

    /**
     * Connects to [device], waits at most [timeoutMs] for the link or for the adapter to go off, and runs [setup] on the
     * channel [channel] built over the new [Link]. The channel is the link's [Route] before `connectGatt` is called, so a
     * callback that lands at once has somewhere to go.
     *
     * Every exit but one [Outcomes.keepsOpen] accepts closes the channel, cancellation and a throw included: a `stop()`
     * or a board swap inside the wait used to leave the connect registered with the stack — up to 30 s for a direct
     * dial, and for a background one until the board next came into range, reconnecting a session nobody owned.
     *
     * `DEPRECATION`: compileSdk 37.1 deprecates every `connectGatt(Context, …)` overload in favour of the API-37
     * `connectGatt(BluetoothGattConnectionSettings, Executor, callback)`, which is eight releases above minSdk 29 — this
     * is still the only form that reaches the boards we support.
     */
    @Suppress("DEPRECATION")
    suspend fun <C : Route, R> connect(
        device: BluetoothDevice,
        autoConnect: Boolean,
        timeoutMs: Long,
        channel: (Link) -> C,
        outcomes: Outcomes<R>,
        setup: suspend (C) -> R,
    ): R {
        val link = Link()
        val ch = channel(link)
        link.route = ch
        var answered = false
        val gatt =
            stackCall("connectGatt", null) {
                device.connectGatt(appContext, autoConnect, link.callback, BluetoothDevice.TRANSPORT_LE).also { answered = true }
            } ?: return outcomes.notStarted(answered)
        link.attach(gatt)
        var result: R?
        var opened = false
        try {
            result =
                when (val wait = link.awaitConnected(timeoutMs, adapterOn)) {
                    ConnectWait.Connected -> setup(ch)
                    is ConnectWait.Refused -> outcomes.refused(wait.status)
                    ConnectWait.TimedOut -> outcomes.timedOut
                    ConnectWait.AdapterOff -> outcomes.adapterOff
                }
            opened = outcomes.keepsOpen(result)
            return result
        } finally {
            if (!opened) ch.close()
        }
    }

    /**
     * One call into the stack, [fallback] if it throws. Bluetooth going off kills the binder under a live
     * `BluetoothGatt`, and `writeCharacteristic` then throws `RuntimeException(DeadObjectException)` (Pixel 9,
     * 2026-11-06); uncaught, that left the link dead and reading Ready. Any other throw from the stack is the same failed
     * op. Logged under the kind's tag as `<what> <op> threw`.
     */
    fun <T> stackCall(
        what: String,
        fallback: T,
        call: () -> T,
    ): T =
        try {
            call()
        } catch (
            @Suppress("TooGenericExceptionCaught") e: RuntimeException, // whatever the stack throws, the op failed
        ) {
            Log.w(spec.tag, "${spec.what} $what threw: $e")
            fallback
        }

    /** One GATT client connection: the callback, the connect/MTU/discover waits and the serialised ops. */
    inner class Link internal constructor() {
        @Volatile
        internal var route: Route? = null

        @Volatile
        private var gatt: BluetoothGatt? = null
        private val opLock = Mutex()

        @Volatile
        private var pending: CompletableDeferred<GattResult<ByteArray>>? = null

        // Set on the actor's thread, completed on the binder thread: @Volatile so the callback sees the newest one.
        @Volatile
        private var mtuResult: CompletableDeferred<Int>? = null

        @Volatile
        private var discoverResult: CompletableDeferred<Boolean>? = null

        // Made with the link, before `connectGatt` is called, so a connect that lands at once is not missed.
        private val connectResult = CompletableDeferred<ConnectWait>()

        internal fun attach(g: BluetoothGatt) {
            gatt = g
        }

        internal val callback =
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
                            route?.onDisconnected(status)
                            pending?.complete(GattResult.Closed)
                        }
                    }
                }

                // The size is trusted here, not the status: the stack reports the bearer's *current* ATT MTU either
                // way, so a refused exchange on a link some other GATT client already negotiated still carries the
                // real number (b407293d). [negotiateMtu] decides whether what came back is usable.
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

                // Gated: on 33+ the platform calls the new form, and a second delivery here would complete twice.
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
                    if (route?.completesWrite(ch.uuid) == true) pending?.complete(resultOf(status))
                }

                override fun onDescriptorWrite(
                    g: BluetoothGatt,
                    descriptor: BluetoothGattDescriptor,
                    status: Int,
                ) {
                    if (route?.completesDescriptorWrite(descriptor.uuid) == true) pending?.complete(resultOf(status))
                }

                override fun onCharacteristicChanged(
                    g: BluetoothGatt,
                    ch: BluetoothGattCharacteristic,
                    value: ByteArray,
                ) = notified(ch, value)

                // Gated like the read: on 33+ a notify would otherwise be delivered twice.
                @Deprecated("Pre-33 signature", ReplaceWith(""))
                override fun onCharacteristicChanged(
                    g: BluetoothGatt,
                    ch: BluetoothGattCharacteristic,
                ) {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        @Suppress("DEPRECATION")
                        notified(ch, ch.value ?: ByteArray(0))
                    }
                }
            }

        private fun resultOf(status: Int): GattResult<ByteArray> =
            if (status == BluetoothGatt.GATT_SUCCESS) GattResult.Ok(ByteArray(0)) else GattResult.Failed(status)

        private fun completeRead(
            ch: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (route?.completesRead(ch.uuid) == true) {
                pending?.complete(if (status == BluetoothGatt.GATT_SUCCESS) GattResult.Ok(value.copyOf()) else GattResult.Failed(status))
            }
        }

        // Copied here, inside the callback: the array is the stack's, and the channel keeps what it is handed.
        private fun notified(
            ch: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            route?.onNotified(ch.uuid, value.copyOf())
        }

        /**
         * Waits up to [timeoutMs] for the link to come up, or for the adapter to go off — a background wait can run for
         * most of an hour, and the session has to see the adapter go rather than a window running out.
         */
        internal suspend fun awaitConnected(
            timeoutMs: Long,
            adapterOn: StateFlow<Boolean>,
        ): ConnectWait =
            // stretches: the controller dials through sleep; a late give-up delays hp88's hourly direct net and holds the arbiter.
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
         * Negotiates the ATT MTU, letting the link settle first and retrying a refused exchange rather than failing the
         * whole dial on one. The last size the stack reported, or −1; the kind decides whether it is enough.
         *
         * A bonded board starts its SMP handshake the moment the ACL is up, and `STATE_CONNECTED` reaches us *before*
         * the link is encrypted — so an Exchange MTU sent the instant we are called lands mid-handshake. Against a
         * Meshtastic 2.8 alpha board that lost the race every time: the board answered late, the stack had stopped
         * listening (`ATT - Ignore wrong response. Receives (03)`), and the app saw the default 23 with a non-success
         * status. BlueZ negotiates 517 on the same board because it does not touch ATT until the link is encrypted.
         * [Spec.settleMs] buys that same ordering, and the retries cover a board slower than one settle.
         */
        suspend fun negotiateMtu(): Int {
            var last = -1
            repeat(spec.mtuAttempts) { attempt ->
                // stretches: a sub-second settle before the MTU exchange; a sleep only slows the setup.
                delay(if (attempt == 0) spec.settleMs else spec.mtuRetryMs)
                last = requestMtuOnce(spec.requestMtu, spec.mtuTimeoutMs)
                if (last >= spec.minMtu) return last
                Log.d(spec.tag, "mtu exchange refused (got $last), attempt ${attempt + 1}/${spec.mtuAttempts}")
            }
            return last
        }

        private suspend fun requestMtuOnce(
            mtu: Int,
            timeoutMs: Long,
        ): Int {
            val d = CompletableDeferred<Int>()
            mtuResult = d
            if (!stackCall("requestMtu", false) { gatt?.requestMtu(mtu) == true }) return -1
            // stretches: give-up on a setup op; a lost callback holds the arbiter, so the mesh scan, up to ~43 s.
            return withTimeoutOrNull(timeoutMs) { d.await() } ?: -1
        }

        /** Discovers the services, true once the stack says it did. */
        suspend fun discover(): Boolean {
            val d = CompletableDeferred<Boolean>()
            discoverResult = d
            if (!stackCall("discoverServices", false) { gatt?.discoverServices() == true }) return false
            // stretches: give-up on a setup op; a lost callback holds the arbiter, so the mesh scan, up to ~43 s.
            return withTimeoutOrNull(spec.discoverTimeoutMs) { d.await() } ?: false
        }

        /** The discovered service [uuid], or null when the board has none. */
        fun service(uuid: UUID): BluetoothGattService? = gatt?.getService(uuid)

        /** Turns notifications on for [ch]: the local registration, then its [cccd] written. Issue inside an [op]. */
        fun enableNotifications(
            ch: BluetoothGattCharacteristic,
            cccd: UUID,
        ): Boolean {
            val g = gatt ?: return false
            g.setCharacteristicNotification(ch, true)
            val descriptor = ch.getDescriptor(cccd) ?: return false
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(descriptor)
                }
            }
        }

        /**
         * Writes [bytes] to [ch] as a Write Request (`WRITE_TYPE_DEFAULT`), answered by the board. Never a Write Command:
         * a board with no `WRITE_NR` on the characteristic, or one that requires encryption, drops a command with no
         * error. Issue inside an [op].
         */
        fun writeDefault(
            ch: BluetoothGattCharacteristic,
            bytes: ByteArray,
        ): Boolean {
            val g = gatt ?: return false
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    ch.value = bytes
                    g.writeCharacteristic(ch)
                }
            }
        }

        /** Reads [ch]. Issue inside an [op]. */
        fun read(ch: BluetoothGattCharacteristic): Boolean = gatt?.readCharacteristic(ch) == true

        /**
         * Serializes one GATT op: issues [issue], awaits its callback, or Timeout/Closed/Failed. A throw from the stack is
         * a Failed like a refusal, under [Spec.stackThrew], so the session's write-failure path ends the session.
         */
        suspend fun op(
            what: String,
            timeoutMs: Long,
            issue: () -> Boolean,
        ): GattResult<ByteArray> =
            opLock.withLock {
                val d = CompletableDeferred<GattResult<ByteArray>>()
                pending = d
                try {
                    when (stackCall(what, null) { issue() }) {
                        null -> {
                            GattResult.Failed(spec.stackThrew)
                        }

                        false -> {
                            GattResult.Failed(-1)
                        }

                        true -> {
                            // stretches: give-up on one GATT op; an op that can wait on the user (a pairing) runs with no
                            // arbiter held, and one under a dial's hold is a bounded setup op (MeshCore's bonded subscribe, 10 s).
                            withTimeoutOrNull(timeoutMs) { d.await() } ?: GattResult.Timeout
                        }
                    }
                } finally {
                    pending = null
                }
            }

        /** Ends the op in flight as [GattResult.Closed]: the channel is ending the link itself, which no callback reports. */
        fun failPending() {
            pending?.complete(GattResult.Closed)
        }

        /** Disconnects and unregisters the client. Apart: a dead stack refusing the disconnect must not cost the close. */
        fun close() {
            runCatching { gatt?.disconnect() }
            runCatching { gatt?.close() }
            gatt = null
        }
    }

    /** How a connect wait ended. */
    internal sealed interface ConnectWait {
        data object Connected : ConnectWait

        /** The stack reported the link down before it was ever up, with its GATT status. */
        data class Refused(
            val status: Int,
        ) : ConnectWait

        data object TimedOut : ConnectWait

        data object AdapterOff : ConnectWait
    }
}

/** A byte op's result as a [T], every failure carried as it was. */
internal fun <T> GattResult<ByteArray>.mapOk(block: (ByteArray) -> T): GattResult<T> =
    when (this) {
        is GattResult.Ok -> GattResult.Ok(block(value))
        is GattResult.Failed -> this
        GattResult.Timeout -> GattResult.Timeout
        GattResult.Closed -> GattResult.Closed
    }
