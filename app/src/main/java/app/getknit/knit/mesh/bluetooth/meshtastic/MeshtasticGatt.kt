package app.getknit.knit.mesh.bluetooth.meshtastic

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.util.Log
import app.getknit.knit.mesh.bluetooth.BleConnectArbiter
import app.getknit.knit.mesh.bluetooth.BoardGatt
import app.getknit.knit.mesh.bluetooth.mapOk
import app.getknit.knit.mesh.lora.BondState
import app.getknit.knit.mesh.lora.DialMode
import app.getknit.knit.mesh.lora.DialResult
import app.getknit.knit.mesh.lora.GattChannel
import app.getknit.knit.mesh.lora.GattEvent
import app.getknit.knit.mesh.lora.GattResult
import app.getknit.knit.mesh.lora.MeshtasticGattDialer
import app.getknit.knit.mesh.lora.MeshtasticSession
import kotlinx.coroutines.channels.Channel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * The [MeshtasticGattDialer] for a Meshtastic board: connects, negotiates the MTU, discovers its service, and hands back
 * an [AndroidGattChannel]. Everything protocol-shaped runs pure above it (`mesh/lora/MeshtasticSession`). The connect,
 * the MTU settle, discovery, the op serialisation and [BoardGatt.stackCall] are the shared [BoardGatt] core (ADR
 * 2026-11.gsqu, Stage 3c); what is Meshtastic's stays here as parameters: the numbers, the `lora-dial` arbiter tag and
 * span, which characteristic answers which op, FromNum, the result mapping and the `MeshtasticGatt` log tag, which
 * recipes grep (`lora-bridge.md`). The adapter is a provider re-fetched per dial; every op has a timeout; the connect,
 * every GATT op and the bond read go through `stackCall`, so a stack that has gone away is a failed op, never a throw
 * into the actor. [dial]'s own adapter reads do not; what [dial] throws, the session's loop catches.
 *
 * Two ways to dial ([DialMode], ADR 2026-09.hp88). A **direct** dial (`autoConnect = false`) is what a board
 * that just dropped gets: fast, but a high-duty scan for its whole window, so it holds [BleConnectArbiter] and the
 * mesh's own scan pauses for it. A **background** dial (`autoConnect = true`) is what a board that stayed away
 * gets: its address goes on the controller's accept list and the controller connects when the board next
 * advertises, at low duty, so the arbiter is taken only for the setup after that. autoConnect is slow or dead on
 * some stacks, which is why the session keeps an hourly direct dial as the net rather than trusting it alone.
 *
 * Callbacks run inline on a **binder thread**, in order, not on the main looper (see [BoardGatt]): the four-argument
 * `connectGatt` passes the platform a null `Handler`. [SETTLE_MS] was tuned against that delivery.
 *
 * Device-verified only — there is no host GATT stack, so this class has no unit test; its logic lives behind
 * the pure session/codec, which do.
 */
@SuppressLint("MissingPermission")
internal class MeshtasticGatt(
    context: Context,
    arbiter: BleConnectArbiter,
) : MeshtasticGattDialer {
    private val core = BoardGatt(context, arbiter, SPEC)

    override val adapterOn = core.adapterOn

    override fun bondState(address: String): BondState = core.bondState(address)

    override suspend fun dial(
        address: String,
        mode: DialMode,
        timeoutMs: Long,
    ): DialResult {
        val device = core.remoteDevice(address) ?: return DialResult.NoHardware
        if (!core.adapterEnabled()) return DialResult.AdapterOff
        return when (mode) {
            // The arbiter slot covers the whole connect→MTU→discover window: a direct connect is a high-duty
            // scan of its own, and the mesh's scan would starve it.
            DialMode.Direct -> core.holding(ARBITER_TAG) { connectAndConfigure(device, autoConnect = false, timeoutMs) }

            // The controller waits for the board off its accept list at low duty, which starves nothing, so the
            // mesh keeps scanning for however long that takes (ADR 2026-09.hp88); only the setup after the board
            // has connected pauses it.
            DialMode.Background -> connectAndConfigure(device, autoConnect = true, timeoutMs)
        }
    }

    private suspend fun connectAndConfigure(
        device: BluetoothDevice,
        autoConnect: Boolean,
        timeoutMs: Long,
    ): DialResult =
        core.connect(device, autoConnect, timeoutMs, ::AndroidGattChannel, OUTCOMES) { channel ->
            if (autoConnect) core.holding(ARBITER_TAG) { finishSetup(channel) } else finishSetup(channel)
        }

    private suspend fun finishSetup(channel: AndroidGattChannel): DialResult {
        val mtu = channel.link.negotiateMtu()
        if (mtu < MIN_MTU) return DialResult.Failed(status = mtu, phase = "mtu")
        if (!channel.link.discover()) return DialResult.Failed(status = -1, phase = "service")
        // Discovery succeeded and the service is not there: not Meshtastic firmware, or a cache from before a reflash.
        // The session backs off the first reading and ends terminal on a second in a row (ADR 2026-11.gsqu, Stage 3d).
        if (!channel.resolveCharacteristics()) {
            Log.w(TAG, "dial service absent: not Meshtastic firmware, or a stale GATT cache")
            return DialResult.ServiceAbsent
        }
        Log.d(TAG, "dial opened mtu=$mtu")
        return DialResult.Opened(channel, mtu)
    }

    /** One open GATT connection; serializes ops through the core's [BoardGatt.Link.op]. */
    private class AndroidGattChannel(
        val link: BoardGatt.Link,
    ) : GattChannel,
        BoardGatt.Route {
        private val eventsChannel = Channel<GattEvent>(Channel.UNLIMITED)
        override val events = eventsChannel

        private var fromRadio: BluetoothGattCharacteristic? = null
        private var toRadio: BluetoothGattCharacteristic? = null
        private var fromNum: BluetoothGattCharacteristic? = null

        override fun completesWrite(characteristic: UUID): Boolean = characteristic == MeshtasticUuids.TO_RADIO

        override fun completesDescriptorWrite(descriptor: UUID): Boolean = descriptor == MeshtasticUuids.CCCD

        override fun completesRead(characteristic: UUID): Boolean = characteristic == MeshtasticUuids.FROM_RADIO

        override fun onNotified(
            characteristic: UUID,
            value: ByteArray,
        ) {
            if (characteristic != MeshtasticUuids.FROM_NUM) return
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

        override fun onDisconnected(status: Int) {
            eventsChannel.trySend(GattEvent.Disconnected(status))
        }

        fun resolveCharacteristics(): Boolean {
            val service = link.service(MeshtasticUuids.SERVICE) ?: return false
            toRadio = service.getCharacteristic(MeshtasticUuids.TO_RADIO)
            fromRadio = service.getCharacteristic(MeshtasticUuids.FROM_RADIO)
            fromNum = service.getCharacteristic(MeshtasticUuids.FROM_NUM)
            return toRadio != null && fromRadio != null && fromNum != null
        }

        override suspend fun subscribeFromNum(timeoutMs: Long): GattResult<Unit> =
            link
                .op("subscribe", timeoutMs) {
                    val ch = fromNum ?: return@op false
                    link.enableNotifications(ch, MeshtasticUuids.CCCD)
                }.mapOk { }

        override suspend fun writeToRadio(
            bytes: ByteArray,
            timeoutMs: Long,
        ): GattResult<Unit> =
            link
                .op("write", timeoutMs) {
                    val ch = toRadio ?: return@op false
                    link.writeDefault(ch, bytes)
                }.mapOk { }

        override suspend fun readFromRadio(timeoutMs: Long): GattResult<ByteArray> =
            link.op("read", timeoutMs) {
                val ch = fromRadio ?: return@op false
                link.read(ch)
            }

        override fun close() {
            link.close()
            eventsChannel.close()
        }
    }

    private companion object {
        const val TAG = "MeshtasticGatt"
        const val ARBITER_TAG = "lora-dial"
        const val MTU_TIMEOUT_MS = 10_000L

        // The Exchange MTU must not race the SMP handshake a bonded board starts at ACL-up; see
        // BoardGatt.Link.negotiateMtu. Measured on a Heltec V4 / 2.8 alpha: encryption completes ~200 ms
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

        val SPEC =
            BoardGatt.Spec(
                tag = TAG,
                what = "lora gatt",
                requestMtu = REQUEST_MTU,
                minMtu = MIN_MTU,
                settleMs = SETTLE_MS,
                mtuRetryMs = MTU_RETRY_MS,
                mtuAttempts = MTU_ATTEMPTS,
                mtuTimeoutMs = MTU_TIMEOUT_MS,
                discoverTimeoutMs = DISCOVER_TIMEOUT_MS,
                stackThrew = MeshtasticSession.STATUS_STACK_THREW,
            )

        // A refusal is Failed, never Timeout, so it takes the session's backoff: a background connect the stack turns
        // down at once (an address not in its cache is status 133 on some stacks) would otherwise read as a spent
        // window and be re-dialled straight away, in a loop.
        val OUTCOMES =
            object : BoardGatt.Outcomes<DialResult> {
                override fun notStarted(answered: Boolean): DialResult =
                    DialResult.Failed(status = if (answered) -1 else MeshtasticSession.STATUS_STACK_THREW, phase = "connectGatt")

                override fun refused(status: Int): DialResult = DialResult.Failed(status = status, phase = "connect")

                override val timedOut: DialResult = DialResult.Timeout

                override val adapterOff: DialResult = DialResult.AdapterOff

                override fun keepsOpen(result: DialResult): Boolean = result is DialResult.Opened
            }
    }
}
