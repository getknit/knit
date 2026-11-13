package app.getknit.knit.mesh.lora.meshcore

import app.getknit.knit.mesh.lora.RefusalClass
import app.getknit.knit.mesh.lora.SendResult
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * A MeshCore link's lifecycle: the `detail` of a `BoardState.Down` (ADR 2026-11.gsqu, Stage 3b). Terminal states
 * ([NeedsPairing], [StaleBond], [FirmwareTooOld]) stop retrying until the session is started again.
 */
internal sealed interface MeshcoreLinkState {
    data object Idle : MeshcoreLinkState

    data object Connecting : MeshcoreLinkState

    /** The stack is pairing (the board shows its PIN, or uses 123456); the first protected write waits this out. */
    data object Bonding : MeshcoreLinkState

    data object Handshaking : MeshcoreLinkState

    data class Disconnected(
        val reason: String,
        val retryAtMs: Long,
        val streak: Int,
    ) : MeshcoreLinkState

    /** The adapter is off (or absent). */
    data object Unavailable : MeshcoreLinkState

    data class NeedsPairing(
        val address: String,
    ) : MeshcoreLinkState

    data class StaleBond(
        val address: String,
    ) : MeshcoreLinkState

    /**
     * The board's `FIRMWARE_VER_CODE` is below [MeshcoreProto.MIN_FIRMWARE_VERSION_CODE]: it lacks a command the plane
     * needs, so the session never reads Ready on it (binding rule "client" 4).
     */
    data class FirmwareTooOld(
        val versionCode: Int,
    ) : MeshcoreLinkState
}

/** One channel slot as the session keeps it: never a foreign slot's name or secret (binding rule "contacts" 4). */
internal data class MeshcoreSlot(
    val index: Int,
    val empty: Boolean,
    val knit: Boolean,
)

/** The latest read of the bound Knit slot: whether it still held Knit's channel, and when (the session's clock). */
internal data class MeshcoreSlotReading(
    val index: Int,
    val knit: Boolean,
    val atMs: Long,
)

/**
 * A Ready MeshCore board's own facts: the `detail` of its `BoardState.Ready`. Counts and own-board facts only — never
 * the advert name (which may be a person's), a foreign slot's name, a position, or the BLE PIN `DEVICE_INFO` carries.
 */
internal class MeshcoreDetail(
    publicKey: ByteArray,
    /** `FIRMWARE_VER_CODE`. */
    val versionCode: Int,
    /** The firmware's version string, as `DEVICE_INFO` reports it. */
    val version: String?,
    /** The board floods under a default scope, which the firmware applies to every flood; never written by Knit. */
    val defaultScope: Boolean,
    /** `multi_acks`: the extra acks our board airs for a direct DM to us (`getExtraAckTransmitCount`). */
    val multiAcks: Int,
    /** The board repeats others' packets itself (client repeat, `DEVICE_INFO` v9+); null when it did not say. */
    val clientRepeat: Boolean?,
    /** The frequency it is programmed to, in kHz, when it reported a sane radio. */
    val freqKhz: Long?,
    /** How many contacts the board holds at most (`DEVICE_INFO`'s `MAX_CONTACTS / 2`, doubled). */
    val contactCapacity: Int,
    /** Every channel slot the board has, as the handshake read them. */
    val slots: List<MeshcoreSlot>,
    /** Whether the board is already named as Knit names it (`Knit abcd`). */
    val namedForKnit: Boolean,
    /** `manual_add_contacts` as the board reported it. */
    val manualAdd: Int,
) {
    private val key = publicKey.copyOf()

    /** A copy of the board's 32-byte Ed25519 public key, what the profile will advertise as `meshcoreKey` (Stage 5). */
    val publicKey: ByteArray get() = key.copyOf()

    override fun toString(): String = "MeshcoreDetail(v=$versionCode, slots=${slots.size}, scope=$defaultScope)"
}

/**
 * A drained message, as a `BoardPacket.kind` (binding rule "client" 3: the session discards none). [type] says what it
 * was; [slot] its channel slot (null for a contact text); [pathLen] the raw receive `path_len` — `0xFF` direct, else
 * hops in the low six bits — which the kind's hearing rule reads (Stage 5); [live] whether it was drained after this
 * connection's first `NO_MORE` (earlier is the board's offline queue replayed); [cobs] whether a contact text decoded
 * as COBS, in which case the packet's payload is the decoded bytes and [text] the text as it arrived.
 */
internal class MeshcorePacket(
    val type: Type,
    val slot: Int?,
    val pathLen: Int,
    val txtType: Int?,
    val dataType: Int?,
    val senderTimestamp: Long?,
    val live: Boolean,
    val cobs: Boolean,
    text: ByteArray?,
) {
    enum class Type { CONTACT_TEXT, CHANNEL_TEXT, CHANNEL_DATA }

    private val raw = text?.copyOf()

    /** The contact text as it arrived (before COBS), or the channel text; null for channel data. */
    val text: ByteArray? get() = raw?.copyOf()

    /** A direct reception (`path_len 0xFF`): through a repeater too, for channel data (ADR 2026-11.gsqu §5). */
    val direct: Boolean get() = pathLen == MeshcoreProto.PATH_UNKNOWN

    override fun toString(): String = "MeshcorePacket($type, slot=$slot, pathLen=$pathLen, live=$live)"
}

/**
 * Air our board spent that no send of ours asked for, which the session reports for the core to book (binding rule
 * "sends" 7, Stage 5). Never a DM replayed from the offline queue: its ack aired while we were away.
 */
internal sealed interface MeshcoreAir {
    val atMs: Long

    /**
     * A DM attempt of ours ([handle]) went out as a flood: our contact had no path (`RESP_SENT` flag 1). The airing
     * itself was booked when it was sent; Stage 5 books only the flood's surcharge (every repeater's re-airing), never a
     * second airing.
     */
    data class DirectFlooded(
        val handle: UInt,
        val bytes: Int,
        override val atMs: Long,
    ) : MeshcoreAir

    /** A flooded DM to us reached our board live, and it answered with one flooded PATH return, not a direct ack. */
    data class PathReturn(
        override val atMs: Long,
    ) : MeshcoreAir
}

/** What a setup changed on the board, for Stage 7 to persist — the prior advert name under SQLCipher, it may be a person's. */
internal class MeshcoreSetupRecord(
    /** The slot Knit's channel is in. */
    val slot: Int,
    /** Whether this setup wrote the slot (false: it already held Knit's channel). */
    val slotWritten: Boolean,
    priorName: ByteArray?,
    /** `manual_add_contacts` before the setup set it to 1, or null when it already was. */
    val priorManualAdd: Int?,
    /** `autoadd_config` before the setup cleared chat auto-add and overwrite-oldest, or null when neither was set. */
    val priorAutoadd: Int?,
) {
    private val name = priorName?.copyOf()

    /** The advert name before the setup renamed the board, or null when it was already Knit's. */
    val priorName: ByteArray? get() = name?.copyOf()

    /** Whether the setup changed anything a restore has to put back. */
    val changedAnything: Boolean get() = slotWritten || name != null || priorManualAdd != null || priorAutoadd != null

    override fun toString(): String =
        "MeshcoreSetupRecord(slot=$slot, slotWritten=$slotWritten, name=${name != null}, manualAdd=$priorManualAdd, autoadd=$priorAutoadd)"
}

/** The outcome of [MeshcoreSession.setup]. */
internal sealed interface MeshcoreSetupResult {
    /** The board is set up: Knit's channel at [slot]; [record] holds what a restore puts back. */
    data class Done(
        val slot: Int,
        val alreadyPresent: Boolean,
        val record: MeshcoreSetupRecord,
    ) : MeshcoreSetupResult

    /** Every slot but Public is taken by another channel: nothing was written. */
    data object NoFreeSlot : MeshcoreSetupResult

    /**
     * A read or a write failed. Nothing was written, or what was written was put back; [left] is what could not be put
     * back, for the caller to persist so a restore can, or null when nothing is left changed.
     */
    data class Failed(
        val reason: String,
        val left: MeshcoreSetupRecord?,
    ) : MeshcoreSetupResult

    data object NotReady : MeshcoreSetupResult
}

/** The outcome of [MeshcoreSession.restore]: what was put back, and what was found changed by someone else and left. */
internal sealed interface MeshcoreRestoreResult {
    data class Done(
        val slotCleared: Boolean,
        /** What was not Knit's any more and so left as found (by key, never a value: `slot`, `name`, `manualAdd`, `autoadd`). */
        val leftAsFound: List<String>,
        /** What could not be written or verified. */
        val failed: List<String>,
    ) : MeshcoreRestoreResult

    data object NotReady : MeshcoreRestoreResult
}

/** The outcome of a contact write ([MeshcoreSession.teach], [MeshcoreSession.removeContact], [MeshcoreSession.resetPath]). */
internal enum class ContactWrite {
    /** Written and read back. */
    WRITTEN,

    /** A teach of a key the board already holds: nothing written, the caller decides (Stage 5 snapshots it). */
    ALREADY_PRESENT,

    /** A teach with no free slot confirmed: nothing written, so nobody's contact is evicted. */
    NO_FREE_SLOT,

    /** The key is not on the board (a remove, a reset). */
    NOT_FOUND,

    /** A read, a write or its verification failed or timed out. */
    FAILED,

    NOT_READY,
}

/** The outcome of [MeshcoreSession.readContact]. */
internal sealed interface ContactRead {
    class Found(
        val record: ContactRecord,
    ) : ContactRead

    data object NotFound : ContactRead

    data object Failed : ContactRead

    data object NotReady : ContactRead
}

/**
 * The refusals a MeshCore send answers with ([SendResult.Rejected.res]) beside the firmware's own `ERR` codes, and what
 * each means to the plane. Stage 5's `MeshcoreTraits.refusalOf` reads [classOf].
 */
internal object MeshcoreRefusal {
    /** A DM to a board whose key no contact plumbing taught the session: nothing to address it by. */
    const val NO_RECORD = -10

    /** A DM whose zero-hop pin failed or timed out in this connection: nothing was written (binding rule "sends" 1). */
    const val UNPINNED = -11

    /** A room post to a slot that no longer reads as Knit's channel (binding rule "contacts" 2). */
    const val SLOT_NOT_KNIT = -12

    /** A purpose this kind has no form for (the auto-reply, a Meshtastic admin write, a non-MeshCore address). */
    const val UNSUPPORTED = -13

    /**
     * What a synchronous refusal means: our own board without the destination's contact — no record, a pin that failed,
     * the board's `NOT_FOUND` — is [RefusalClass.OWN_NO_KEY]; anything else refused is [RefusalClass.OTHER].
     */
    fun classOf(result: SendResult): RefusalClass? =
        when (result) {
            is SendResult.Rejected -> {
                when (result.res) {
                    NO_RECORD, UNPINNED, MeshcoreProto.ERR_NOT_FOUND -> RefusalClass.OWN_NO_KEY
                    else -> RefusalClass.OTHER
                }
            }

            else -> {
                null
            }
        }
}

/** The names Knit writes onto a board (binding rule "contacts" 3): derived from a MeshCore key, never a node id or a person. */
internal object MeshcoreNames {
    private const val PREFIX = "Knit "
    private const val TAG_BYTES = 2
    private const val BYTE_MASK = 0xFF

    /** `Knit abcd`: the first two bytes of [key] in lower-case hex — the board's own name, and a taught contact's. */
    fun forKey(key: ByteArray): String =
        PREFIX + key.take(TAG_BYTES).joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and BYTE_MASK) }
}

/**
 * The session's counters: counts only, for the debug bridge and the field log Stage 5 wires (never a key, a name or a
 * text). Read from any thread.
 */
internal class MeshcoreCounters {
    /** An answer nobody of ours was waiting for: another client's, or ours after its wait ended. */
    val unexpected = AtomicLong()

    /** A frame [MeshcoreProto.decode] refused. */
    val rejected = AtomicLong()

    /** `ADVERT` / `NEW_ADVERT` pushes, dropped. */
    val adverts = AtomicLong()

    /** Every other push this build reads no further (`LOG_RX_DATA`, `CONTACTS_FULL` …). */
    val otherPushes = AtomicLong()

    /** A DM attempt of ours that the board flooded (`meshcoreDirectFlooded`): a breach of the zero-hop pin. */
    val directFlooded = AtomicLong()

    /** A pin that failed or timed out. */
    val pinFailures = AtomicLong()

    /** A path push or deletion for a key Knit did not teach: left alone. */
    val foreignPathPushes = AtomicLong()

    /** A path push inside a key's rate limit: unverified only. */
    val repinsCoalesced = AtomicLong()

    /** A frame handler that threw: the frame is dropped, the actor goes on. */
    val handlerFailures = AtomicLong()

    /** A bare `OK` / `ERR` seen beside our own during an own post: another client's, so the post's answer was ambiguous. */
    val strayAnswers = AtomicLong()

    /**
     * A content-matched request answered by more than one bare `ERR` before its barrier's `CURR_TIME` and no success:
     * whose was whose cannot be told, so it read as no answer.
     */
    val ambiguousAnswers = AtomicLong()

    /** A drained message pushed out of the full packets backlog before its collector read it. */
    val packetsDropped = AtomicLong()
}
