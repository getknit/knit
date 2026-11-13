package app.getknit.knit.mesh.lora.meshcore

import app.getknit.knit.mesh.lora.BondState
import app.getknit.knit.mesh.lora.DialMode
import app.getknit.knit.mesh.lora.GattResult
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.StateFlow

/**
 * The Android seam under [MeshcoreSession] (ADR 2026-11.gsqu, Stage 3): the NUS analogue of `MeshtasticGattDialer` /
 * `GattChannel`, which stay Meshtastic-shaped. The dialer connects, bonds if needed, discovers the Nordic UART service
 * (`6E400001-B5A3-F393-E0A9-E50E24DCCA9E`), negotiates the MTU and subscribes to TX, handing back an open [NusChannel];
 * everything protocol-shaped above it is pure and runs against `FakeNusChannel`. Its implementation (Stage 3c,
 * `mesh/bluetooth/meshcore/`) is the only `android.bluetooth.*` importer for this kind.
 */
internal interface MeshcoreDialer {
    /** Whether the Bluetooth adapter is on right now; the session parks on `false` rather than dialling. */
    val adapterOn: StateFlow<Boolean>

    /** The bond state of [address]: the session widens the first write's timeout while not bonded. */
    fun bondState(address: String): BondState

    /**
     * Connects to the board at [address] and opens its NUS channel, waiting at most [timeoutMs] for the connection. The
     * modes are `MeshtasticGattDialer.dial`'s (ADR 2026-09.hp88); an unbonded board is never dialled in the background.
     */
    suspend fun dial(
        address: String,
        mode: DialMode,
        timeoutMs: Long,
    ): NusDialResult
}

/** The outcome of a [MeshcoreDialer.dial]. */
internal sealed interface NusDialResult {
    /** Open, at ATT [mtu]: a write longer than `mtu − 3` is refused by the channel, never split. */
    data class Opened(
        val channel: NusChannel,
        val mtu: Int,
    ) : NusDialResult

    /** A GATT failure at a named [phase] (connect/service/mtu/subscribe) with the stack's status code. */
    data class Failed(
        val status: Int,
        val phase: String,
    ) : NusDialResult

    data object Timeout : NusDialResult

    data object AdapterOff : NusDialResult

    data object NoHardware : NusDialResult
}

/**
 * An open NUS connection to a MeshCore companion: one companion frame per RX write and per TX notify, no framing byte
 * (`SerialBLEInterface.cpp`). [frames] carries every notify, in arrival order, copied: the board writes each answer and
 * push to every connected interface (`MultiSerialInterface`), so what arrives is not only answers to this channel's
 * writes. A write may be answered before it completes, so the session registers what it expects before writing.
 */
internal interface NusChannel {
    /**
     * Every TX notify and the disconnect, in arrival order, nothing the board sent dropped. Bounded by contract for the
     * implementation (Stage 3c): at most about 1,024 frames or 256 KiB held. On overflow it ends the connection — a
     * [NusEvent.Disconnected] with its own status, counted — and never drops a frame silently: a board (or a client
     * sharing it) that floods faster than the session reads is a session to end, not a queue to grow without limit.
     */
    val frames: ReceiveChannel<NusEvent>

    /** Writes one frame to RX (`WRITE_TYPE_DEFAULT`, never a long write), waiting at most [timeoutMs]. */
    suspend fun write(
        frame: ByteArray,
        timeoutMs: Long,
    ): GattResult<Unit>

    fun close()
}

/** What arrives on a [NusChannel]. */
internal sealed interface NusEvent {
    /** One TX notify: one companion frame. */
    class Frame(
        val bytes: ByteArray,
    ) : NusEvent

    data class Disconnected(
        val status: Int,
    ) : NusEvent
}
