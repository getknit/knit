package app.getknit.knit.mesh.bluetooth.meshcore

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.os.SystemClock
import android.util.Log
import app.getknit.knit.mesh.bluetooth.BleConnectArbiter
import app.getknit.knit.mesh.bluetooth.BoardGatt
import app.getknit.knit.mesh.bluetooth.mapOk
import app.getknit.knit.mesh.lora.BondSettle
import app.getknit.knit.mesh.lora.BondState
import app.getknit.knit.mesh.lora.DialMode
import app.getknit.knit.mesh.lora.GattResult
import app.getknit.knit.mesh.lora.meshcore.MeshcoreDialer
import app.getknit.knit.mesh.lora.meshcore.NusChannel
import app.getknit.knit.mesh.lora.meshcore.NusDialResult
import app.getknit.knit.mesh.lora.meshcore.NusEvent
import app.getknit.knit.mesh.power.ElapsedWait
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * The [MeshcoreDialer] for a MeshCore companion over the Nordic UART service (ADR 2026-11.gsqu, Stage 3c): the only
 * `android.bluetooth.*` importer for the kind. It dials through the shared [BoardGatt] core and keeps what is MeshCore's
 * here: the NUS UUIDs ([MeshcoreUuids]), the 176-B MTU floor ([NusLinkPolicy.MIN_MTU]), the `meshcore-dial` arbiter tag,
 * the TX subscribe, the bounded frame inbox ([NusInbox]) and the `MeshcoreGatt` log tag. Nothing builds one before
 * Stage 5, which wires it behind `MESHCORE_PLANE`.
 *
 * The firmware's side (`SerialBLEInterface.cpp`, `companion-v1.18.0`): one companion frame per RX write and per TX notify,
 * no framing byte; RX is Write-only and TX Read/Notify, both under `ENC_MITM`; the board caps its ATT MTU at 176, so a
 * notify carries at most 173 B, and pairs with `ESP_LE_AUTH_REQ_SC_MITM_BOND` and a static passkey (123456 by default,
 * or the one its screen shows; the random-PIN trap is bench Q8). Before authentication it queues nothing for the phone,
 * and after it a four-frame send queue that drops when full, one notify every 60 ms.
 *
 * Pairing is the system's, from the first protected op: Android answers an ATT insufficient-authentication by bonding
 * and retrying the op (`BluetoothGatt`'s auth retry), so the op's callback waits on the user's passkey. On an ESP32 that
 * op is the session's first RX write, which `MeshcoreSession` gives `BONDING_TIMEOUT_MS` and reads terminal when the bond
 * fails; if a board's CCCD needs encryption it is the TX subscribe here, given the same 90 s and read the same way
 * ([NusLinkPolicy.subscribeEnd] → [NusDialResult.BondRefused]). There is no bond receiver: the bond is read by polling
 * [bondState]. An unbonded board is never dialled in the background ([NusLinkPolicy.backgroundAllowed]).
 *
 * Callbacks run inline on a binder thread, in order (see [BoardGatt]). Device-verified only: the decisions are in
 * [NusInbox] and [NusLinkPolicy], which are JVM-tested; this class wires them to the stack.
 */
@SuppressLint("MissingPermission")
internal class MeshcoreGatt(
    context: Context,
    arbiter: BleConnectArbiter,
) : MeshcoreDialer {
    private val core = BoardGatt(context, arbiter, SPEC)
    private val elapsedWait = ElapsedWait(SystemClock::elapsedRealtime)

    /** Connections this dialer ended because the session fell behind the board ([NusInbox], `NusChannel.frames`). */
    val inboxOverflows = AtomicLong()

    override val adapterOn: StateFlow<Boolean> = core.adapterOn

    override fun bondState(address: String): BondState = core.bondState(address)

    override suspend fun dial(
        address: String,
        mode: DialMode,
        timeoutMs: Long,
    ): NusDialResult {
        val device = core.remoteDevice(address) ?: return NusDialResult.NoHardware
        if (!core.adapterEnabled()) return NusDialResult.AdapterOff
        return when (mode) {
            // A direct connect is a high-duty scan of its own: the arbiter covers connect→MTU→discover, and the subscribe
            // too on a bonded board; it is let go before an unbonded subscribe, which may wait on the user's passkey.
            DialMode.Direct -> {
                val hold = core.hold(ARBITER_TAG)
                try {
                    connectAndConfigure(address, device, autoConnect = false, timeoutMs, hold)
                } finally {
                    hold.release()
                }
            }

            // The controller waits at low duty, so the mesh keeps scanning (ADR 2026-09.hp88), and only for a bonded
            // board: the session never asks otherwise, and a caller that did gets a refusal, never a direct dial that
            // would hold the arbiter for a background window.
            DialMode.Background -> {
                if (NusLinkPolicy.backgroundAllowed(bondState(address))) {
                    connectAndConfigure(address, device, autoConnect = true, timeoutMs, held = null)
                } else {
                    Log.w(TAG, "meshcore dial refused: background dial of an unbonded board")
                    NusDialResult.Failed(status = NusLinkPolicy.STATUS_UNBONDED, phase = "bond")
                }
            }
        }
    }

    private suspend fun connectAndConfigure(
        address: String,
        device: BluetoothDevice,
        autoConnect: Boolean,
        timeoutMs: Long,
        held: BoardGatt.Hold?,
    ): NusDialResult =
        core.connect(device, autoConnect, timeoutMs, { NusGattChannel(it, inboxOverflows) }, OUTCOMES) { channel ->
            // A background dial takes the arbiter only once the board has connected, for the setup.
            val hold = held ?: core.hold(ARBITER_TAG)
            try {
                finishSetup(address, channel, hold)
            } finally {
                if (held == null) hold.release()
            }
        }

    private suspend fun finishSetup(
        address: String,
        channel: NusGattChannel,
        hold: BoardGatt.Hold,
    ): NusDialResult {
        val mtu = channel.link.negotiateMtu()
        Log.d(TAG, "meshcore mtu $mtu")
        return when {
            mtu < NusLinkPolicy.MIN_MTU -> NusDialResult.Failed(status = mtu, phase = "mtu")

            !channel.link.discover() -> NusDialResult.Failed(status = -1, phase = "service")

            // NUS is a generic service: a board without it, or without both characteristics, is not a companion. The
            // session backs off once and ends terminal on a second reading in a row (Stage 5a-2).
            !channel.resolveCharacteristics() -> NusDialResult.ServiceAbsent

            else -> subscribeAndOpen(address, channel, mtu, hold)
        }
    }

    private suspend fun subscribeAndOpen(
        address: String,
        channel: NusGattChannel,
        mtu: Int,
        hold: BoardGatt.Hold,
    ): NusDialResult {
        val unbonded = bondState(address) != BondState.BONDED
        // On a board whose CCCD needs encryption this subscribe is what pairs, and it waits on the user for up to 90 s:
        // never with the mesh's scan paused for it.
        if (unbonded) hold.release()
        val subscribed = channel.subscribe(if (unbonded) BONDING_TIMEOUT_MS else SUBSCRIBE_TIMEOUT_MS)
        // A close under an unbonded subscribe is judged on the settled bond, never a BONDING read the disconnect beat.
        val bondAfter =
            if (subscribed == GattResult.Closed && unbonded) {
                BondSettle.settled(elapsedWait, SystemClock::elapsedRealtime) { bondState(address) }
            } else {
                bondState(address)
            }
        NusLinkPolicy.subscribeEnd(subscribed, unbonded, bondAfter)?.let { end ->
            Log.w(TAG, "meshcore dial subscribe $subscribed unbonded=$unbonded")
            return end
        }
        channel.open(mtu)
        Log.d(TAG, "meshcore dial opened mtu=$mtu bonded=${!unbonded}")
        return NusDialResult.Opened(channel, mtu)
    }

    /** One open NUS connection over a [BoardGatt.Link]: RX writes through the link's ops, TX notifies into a [NusInbox]. */
    private class NusGattChannel(
        val link: BoardGatt.Link,
        private val overflows: AtomicLong,
    ) : NusChannel,
        BoardGatt.Route {
        private val inbox = NusInbox()
        override val frames: ReceiveChannel<NusEvent> get() = inbox.frames

        @Volatile
        private var mtu = 0

        private var rx: BluetoothGattCharacteristic? = null
        private var tx: BluetoothGattCharacteristic? = null

        override fun completesWrite(characteristic: UUID): Boolean = characteristic == MeshcoreUuids.RX

        override fun completesDescriptorWrite(descriptor: UUID): Boolean = descriptor == MeshcoreUuids.CCCD

        override fun completesRead(characteristic: UUID): Boolean = false

        override fun onNotified(
            characteristic: UUID,
            value: ByteArray,
        ) {
            if (characteristic != MeshcoreUuids.TX) return
            if (inbox.frame(value) == NusInbox.Take.OVERFLOW) {
                // The session has not caught up in a full inbox: end the connection here, under its own status, rather
                // than grow the queue or drop a frame. The op in flight ends Closed; close() unregisters the client, so
                // no later callback reaches a channel that has ended.
                overflows.incrementAndGet()
                Log.w(TAG, "meshcore inbox overflow: ending the connection")
                link.failPending()
                link.close()
            }
        }

        override fun onDisconnected(status: Int) {
            inbox.disconnected(status)
        }

        fun resolveCharacteristics(): Boolean {
            val service = link.service(MeshcoreUuids.SERVICE) ?: return false
            rx = service.getCharacteristic(MeshcoreUuids.RX)
            tx = service.getCharacteristic(MeshcoreUuids.TX)
            return rx != null && tx != null
        }

        suspend fun subscribe(timeoutMs: Long): GattResult<Unit> =
            link
                .op("subscribe", timeoutMs) {
                    val ch = tx ?: return@op false
                    link.enableNotifications(ch, MeshcoreUuids.CCCD)
                }.mapOk { }

        /** The MTU the dial opened at; writes are sized against it from here on. */
        fun open(mtu: Int) {
            this.mtu = mtu
        }

        override suspend fun write(
            frame: ByteArray,
            timeoutMs: Long,
        ): GattResult<Unit> {
            if (!NusLinkPolicy.writable(frame.size, mtu)) {
                Log.w(TAG, "meshcore write refused: ${frame.size} B over mtu $mtu")
                return GattResult.Failed(NusLinkPolicy.STATUS_TOO_LONG)
            }
            return link
                .op("write", timeoutMs) {
                    val ch = rx ?: return@op false
                    link.writeDefault(ch, frame)
                }.mapOk { }
        }

        override fun close() {
            link.close()
            inbox.close()
        }
    }

    private companion object {
        const val TAG = "MeshcoreGatt"
        const val ARBITER_TAG = "meshcore-dial"

        // Meshtastic's measured settle and retries (a bonded board starts SMP at ACL-up, BoardGatt.Link.negotiateMtu);
        // unmeasured on a MeshCore board, which bonds the same way, so the same span is the safe first guess.
        const val SETTLE_MS = 750L
        const val MTU_RETRY_MS = 1_000L
        const val MTU_ATTEMPTS = 3
        const val MTU_TIMEOUT_MS = 10_000L
        const val DISCOVER_TIMEOUT_MS = 10_000L

        // Asked high; an ESP32 board answers 176, its own cap, and the stack reports the bearer's size.
        const val REQUEST_MTU = 512

        const val SUBSCRIBE_TIMEOUT_MS = 10_000L

        // The pairing window, as MeshtasticSession's and MeshcoreSession's first write: the user reads the passkey off
        // the board (or types 123456) in a system dialog.
        const val BONDING_TIMEOUT_MS = 90_000L

        val SPEC =
            BoardGatt.Spec(
                tag = TAG,
                what = "meshcore gatt",
                requestMtu = REQUEST_MTU,
                minMtu = NusLinkPolicy.MIN_MTU,
                settleMs = SETTLE_MS,
                mtuRetryMs = MTU_RETRY_MS,
                mtuAttempts = MTU_ATTEMPTS,
                mtuTimeoutMs = MTU_TIMEOUT_MS,
                discoverTimeoutMs = DISCOVER_TIMEOUT_MS,
                stackThrew = NusLinkPolicy.STATUS_STACK_THREW,
            )

        // A refusal is Failed, never Timeout, so it takes the session's backoff (as MeshtasticGatt's).
        val OUTCOMES =
            object : BoardGatt.Outcomes<NusDialResult> {
                override fun notStarted(answered: Boolean): NusDialResult =
                    NusDialResult.Failed(status = if (answered) -1 else NusLinkPolicy.STATUS_STACK_THREW, phase = "connectGatt")

                override fun refused(status: Int): NusDialResult = NusDialResult.Failed(status = status, phase = "connect")

                override val timedOut: NusDialResult = NusDialResult.Timeout

                override val adapterOff: NusDialResult = NusDialResult.AdapterOff

                override fun keepsOpen(result: NusDialResult): Boolean = result is NusDialResult.Opened
            }
    }
}

/** The Nordic UART service a MeshCore companion serves (`SerialBLEInterface.cpp`). */
internal object MeshcoreUuids {
    val SERVICE: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")

    /** Written by the phone: one companion frame per write. */
    val RX: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")

    /** Notified by the board: one companion frame per notify. */
    val TX: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")

    /** The standard Client Characteristic Configuration Descriptor. */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}
