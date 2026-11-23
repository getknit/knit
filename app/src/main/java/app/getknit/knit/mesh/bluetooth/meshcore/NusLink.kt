package app.getknit.knit.mesh.bluetooth.meshcore

import app.getknit.knit.mesh.lora.BondState
import app.getknit.knit.mesh.lora.GattResult
import app.getknit.knit.mesh.lora.meshcore.MeshcoreProto
import app.getknit.knit.mesh.lora.meshcore.NusDialResult
import app.getknit.knit.mesh.lora.meshcore.NusEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * The frames a [MeshcoreGatt] channel holds for its session: `NusChannel.frames`' contract, kept off the Android half so
 * it is JVM-tested. Every notify goes into an unlimited [Channel] — `trySend` there never fails, so the binder thread
 * that delivers a notify never blocks and never drops one — and the inbox bounds what the channel can hold instead: past
 * [maxFrames] frames or [maxBytes] bytes it ends the connection, putting a [NusEvent.Disconnected] with
 * [STATUS_OVERFLOW] behind the frames already held, and takes nothing after it. Nothing the board sent is dropped
 * quietly: what is held is delivered, and the end is the session's to see and the dialer's to count.
 *
 * What is held is counted from the last moment the channel was seen empty, at an arrival: the coroutine channel exposes
 * no size, and the producer is one binder thread, so every frame since an empty look is at most what is still queued.
 * The count is therefore an upper bound, never an under-count: a session that reads every frame as it comes resets it
 * at each arrival, and only one that has not caught up once in [maxFrames] arrivals is ended — which is the session the
 * contract means to end. On an ESP32 board one notify comes at most every 60 ms (`SerialBLEInterface.cpp`,
 * `BLE_WRITE_MIN_INTERVAL`), so the frame cap is a minute of the board's fastest output with nobody reading.
 */
internal class NusInbox(
    private val maxFrames: Int = MAX_FRAMES,
    private val maxBytes: Long = MAX_BYTES,
) {
    /** What the session reads: every frame in arrival order, then at most one [NusEvent.Disconnected]. */
    val frames: ReceiveChannel<NusEvent>
        field = Channel<NusEvent>(Channel.UNLIMITED)

    private var heldFrames = 0
    private var heldBytes = 0L
    private var ended = false

    /** What [frame] did with a notify. */
    enum class Take {
        /** Queued for the session. */
        HELD,

        /** Past a cap: the inbox ended the connection, and the caller must close the link and count it. */
        OVERFLOW,

        /** The inbox had already ended; nothing is queued after its [NusEvent.Disconnected]. */
        ENDED,
    }

    /** Queues one notify's [bytes] (already a copy), or ends the inbox when they would pass a cap. */
    @OptIn(ExperimentalCoroutinesApi::class) // isEmpty: the only read of a channel's occupancy that does not consume
    @Synchronized
    fun frame(bytes: ByteArray): Take {
        if (ended) return Take.ENDED
        if (frames.isEmpty) {
            heldFrames = 0
            heldBytes = 0
        }
        if (heldFrames + 1 > maxFrames || heldBytes + bytes.size > maxBytes) {
            end(STATUS_OVERFLOW)
            return Take.OVERFLOW
        }
        heldFrames++
        heldBytes += bytes.size
        frames.trySend(NusEvent.Frame(bytes))
        return Take.HELD
    }

    /** The link went down with [status]: one [NusEvent.Disconnected] after what is held, the first end only. */
    @Synchronized
    fun disconnected(status: Int) {
        if (!ended) end(status)
    }

    private fun end(status: Int) {
        ended = true
        frames.trySend(NusEvent.Disconnected(status))
    }

    /** Closes the session's side: a read past what is held finds the channel closed. */
    fun close() {
        frames.close()
    }

    companion object {
        /** `NusChannel.frames`' cap: about 1,024 frames … */
        const val MAX_FRAMES = 1_024

        /** … or 256 KiB, whichever comes first. */
        const val MAX_BYTES = 256L * 1_024

        /** The [NusEvent.Disconnected] status of a connection the inbox ended. */
        const val STATUS_OVERFLOW = NusLinkPolicy.STATUS_OVERFLOW
    }
}

/**
 * The MeshCore link's decisions, Android-free: the MTU floor, what a write may carry, when a background dial is allowed,
 * and what a failed TX subscribe means for pairing (ADR 2026-11.gsqu, Stage 3c binding rules "GATT and pairing").
 */
internal object NusLinkPolicy {
    /**
     * The lowest ATT MTU a MeshCore link opens at: the companion's `MAX_FRAME_SIZE`, which is also the MTU an ESP32
     * board caps itself to (`BLEDevice::setMTU(MAX_FRAME_SIZE)`, `SerialBLEInterface.cpp`; bench Q8). Under it a full
     * frame fits in neither direction.
     */
    const val MIN_MTU = MeshcoreProto.MAX_FRAME

    /** The ATT header a write or notify spends out of the MTU. */
    const val ATT_HEADER = 3

    // Our own statuses, beside the stack's (all positive) and the `-1` of an op the stack refused.

    /** The stack threw instead of answering an op (Bluetooth going away under a live client). */
    const val STATUS_STACK_THREW = -2

    /** A write longer than `mtu − 3`, refused before the stack could turn it into a long write. */
    const val STATUS_TOO_LONG = -3

    /** The inbox passed its cap and ended the connection ([NusInbox]). */
    const val STATUS_OVERFLOW = -4

    /** The link closed under the subscribe while the board stayed unbonded. */
    const val STATUS_CLOSED_UNBONDED = -5

    /** The subscribe's callback never came. */
    const val STATUS_TIMEOUT = -6

    /** A background dial asked for an unbonded board, which this dialer refuses. */
    const val STATUS_UNBONDED = -7

    /**
     * Insufficient authentication (5), insufficient encryption (15), and the stack's auth failure (137, `GATT_AUTH_FAIL`):
     * the statuses `MeshcoreSession` reads as a bond refused on its first write.
     */
    val AUTH_STATUSES = setOf(5, 15, 137)

    /**
     * Whether a [size]-byte frame goes out as one Write Request at [mtu]. A longer one is refused rather than handed to
     * Android, which would turn it into a Prepare/Execute long write the board's `onWrite` takes as two frames, or none.
     */
    fun writable(
        size: Int,
        mtu: Int,
    ): Boolean = size <= mtu - ATT_HEADER

    /**
     * Whether a [bond] allows a background (`autoConnect`) dial. Never for an unbonded board: the controller's connect
     * runs with no app to pair it, and a pairing prompt has to come from a dial the user is waiting on.
     */
    fun backgroundAllowed(bond: BondState): Boolean = bond == BondState.BONDED

    /**
     * What a TX subscribe's [result] makes of the dial: null when it is on, else the dial's end. On an ESP32 companion
     * the CCCD is writable unencrypted (Arduino-ESP32's `BLE2902` keeps `BLEDescriptor`'s open read/write permissions) and
     * the first protected op is the session's first RX write; a board whose CCCD needs encryption (possibly an nRF52's
     * Bluefruit `BLEUart` under `SECMODE_ENC_WITH_MITM`: unverified, Q8 left nRF52 untested) pairs here instead, so a
     * refused or vanished bond is read as the session reads its first write's: terminal, never a backoff that prompts again.
     * [bondAfter] is the bond read settled ([app.getknit.knit.mesh.lora.BondSettle]) after a close, never a `BONDING` the
     * disconnect overtook.
     */
    fun subscribeEnd(
        result: GattResult<Unit>,
        startedUnbonded: Boolean,
        bondAfter: BondState,
    ): NusDialResult? {
        val auth = result is GattResult.Failed && result.status in AUTH_STATUSES
        val unpaired = result == GattResult.Closed && startedUnbonded && bondAfter == BondState.NONE
        return when {
            result is GattResult.Ok -> null
            auth -> NusDialResult.BondRefused(result.status)
            unpaired -> NusDialResult.BondRefused(STATUS_CLOSED_UNBONDED)
            result is GattResult.Failed -> subscribeFailed(result.status)
            result == GattResult.Timeout -> subscribeFailed(STATUS_TIMEOUT)
            else -> subscribeFailed(-1)
        }
    }

    private fun subscribeFailed(status: Int) = NusDialResult.Failed(status = status, phase = "subscribe")
}
