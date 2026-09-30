package app.getknit.knit.mesh.lora

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.StateFlow

/**
 * The Android seam under [MeshtasticSession], mirroring `mesh/spool`'s `SpoolDialer`/socket split: the
 * dialer connects, bonds-if-needed, discovers services and negotiates the MTU, handing back an open
 * [GattChannel]; everything protocol-shaped above it (the handshake, drain loop, heartbeat, send
 * correlation) is pure and runs against a fake in unit tests. The only `android.bluetooth.*` importer
 * for this feature is the dialer's implementation in `mesh/bluetooth/meshtastic/`.
 */
internal interface MeshtasticGattDialer {
    /** Whether the Bluetooth adapter is on right now; the session parks on `false` rather than dialling. */
    val adapterOn: StateFlow<Boolean>

    /** The current bond state of [address] — the session widens the first write's timeout while BONDING. */
    fun bondState(address: String): BondState

    /**
     * Connects, discovers, and negotiates the MTU with the board at [address], waiting at most [timeoutMs] for
     * the connection itself. A [DialMode.Direct] dial pauses the mesh scan for its whole window; a
     * [DialMode.Background] one leaves the wait to the controller and pauses it only for the setup after the
     * board has connected (ADR 2026-09.hp88). A background window that runs out is [DialResult.Timeout].
     */
    suspend fun dial(
        address: String,
        mode: DialMode,
        timeoutMs: Long,
    ): DialResult
}

/** The outcome of a [MeshtasticGattDialer.dial]. */
internal sealed interface DialResult {
    data class Opened(
        val channel: GattChannel,
        val mtu: Int,
    ) : DialResult

    /** A GATT failure at a named [phase] (connect/service/mtu/subscribe) with the stack's status code. */
    data class Failed(
        val status: Int,
        val phase: String,
    ) : DialResult

    data object Timeout : DialResult

    data object AdapterOff : DialResult

    data object NoHardware : DialResult
}

/**
 * An open GATT connection to a board. Exactly one operation may be in flight — the session drives ops
 * sequentially, and the implementation also enforces it (a Mutex + one deferred), so the seam is safe
 * regardless. Every op carries an explicit timeout so a firmware that never answers can't wedge the actor.
 */
internal interface GattChannel {
    /** FromNum notifications and disconnects, in arrival order. */
    val events: ReceiveChannel<GattEvent>

    suspend fun subscribeFromNum(timeoutMs: Long): GattResult<Unit>

    suspend fun writeToRadio(
        bytes: ByteArray,
        timeoutMs: Long,
    ): GattResult<Unit>

    /** Reads one FromRadio value; a 0-length result means the board's queue is drained. */
    suspend fun readFromRadio(timeoutMs: Long): GattResult<ByteArray>

    fun close()
}

/** The result of a single [GattChannel] operation. */
internal sealed interface GattResult<out T> {
    data class Ok<T>(
        val value: T,
    ) : GattResult<T>

    /** The stack reported a non-success status (e.g. 5 insufficient-auth, 137 auth-fail, 133 generic). */
    data class Failed(
        val status: Int,
    ) : GattResult<Nothing>

    data object Timeout : GattResult<Nothing>

    /** The connection dropped while the op was in flight. */
    data object Closed : GattResult<Nothing>
}

/** Asynchronous events from the board's GATT connection. */
internal sealed interface GattEvent {
    /** FromNum fired: [fromNum] is the board's packet counter — the phone reads until it catches up. */
    data class Notified(
        val fromNum: UInt,
    ) : GattEvent

    data class Disconnected(
        val status: Int,
    ) : GattEvent
}

/** The bond state of a remote device, as the dialer reads it from the adapter. */
internal enum class BondState { NONE, BONDING, BONDED, UNKNOWN }
