package app.getknit.knit.mesh.lora.meshcore

import app.getknit.knit.mesh.lora.BoardSetupState
import app.getknit.knit.mesh.lora.BoardState
import app.getknit.knit.mesh.lora.LinkState
import app.getknit.knit.mesh.lora.PairingCause
import app.getknit.knit.mesh.lora.RefusalClass
import app.getknit.knit.mesh.lora.ReplaceToken
import app.getknit.knit.mesh.lora.SendResult
import app.getknit.knit.mesh.lora.SetupPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
        /** A refused bond, or a board that twice showed no NUS service ([PairingCause.NO_SERVICE], Stage 7). */
        val cause: PairingCause = PairingCause.AUTH,
    ) : MeshcoreLinkState

    data class StaleBond(
        val address: String,
        val cause: PairingCause = PairingCause.AUTH,
    ) : MeshcoreLinkState

    /**
     * The board's `FIRMWARE_VER_CODE` is below [MeshcoreProto.MIN_FIRMWARE_VERSION_CODE]: it lacks a command the plane
     * needs, so the session never reads Ready on it (binding rule "client" 4).
     */
    data class FirmwareTooOld(
        val versionCode: Int,
    ) : MeshcoreLinkState
}

/**
 * A MeshCore link state short of Ready as the core's neutral `LinkState` reads it (the field log, the settings row, a
 * `NotReady` answer): the same terminal and transient states, and [MeshcoreLinkState.FirmwareTooOld] — which has no
 * Meshtastic peer — and anything else as idle.
 */
internal fun meshcoreDownState(detail: Any): LinkState =
    when (detail) {
        MeshcoreLinkState.Connecting -> LinkState.Connecting
        MeshcoreLinkState.Bonding -> LinkState.Bonding
        MeshcoreLinkState.Handshaking -> LinkState.Handshaking(null)
        is MeshcoreLinkState.Disconnected -> LinkState.Disconnected(detail.reason, detail.retryAtMs, detail.streak)
        MeshcoreLinkState.Unavailable -> LinkState.Unavailable
        is MeshcoreLinkState.NeedsPairing -> LinkState.NeedsPairing(detail.address, detail.cause)
        is MeshcoreLinkState.StaleBond -> LinkState.StaleBond(detail.address, detail.cause)
        else -> LinkState.Idle
    }

/**
 * The part of a [MeshcoreSession] the plane's MeshCore hooks drive (Stage 5a-2): a seam so the hooks are tested against a
 * fake as well as the real session over `FakeMeshcoreAir`.
 */
internal interface MeshcoreBoardOps {
    /** Air the board spent that no send of ours booked ([MeshcoreAir]). */
    val air: Flow<MeshcoreAir>

    /** The session's counts (never a key, a name or a text). */
    val counters: MeshcoreCounters

    /** Told what the hooks must know before the board reads Ready ([MeshcoreSessionObserver]). */
    var observer: MeshcoreSessionObserver?

    /** The latest read of the bound Knit slot on this connection, or null. */
    fun slotReading(): MeshcoreSlotReading?

    /** Sets the board up for Knit (§7); 5b adds the erase step and persists the record. */
    suspend fun setup(): MeshcoreSetupResult

    /**
     * Puts back what the connected board's setup record says a setup changed, where it is still Knit's, and removes Knit's
     * contacts — only for a record of this board, bonded ([MeshcoreRestoreResult.Mismatch] otherwise, nothing written); then
     * the record goes and consent with it.
     */
    suspend fun restore(): MeshcoreRestoreResult

    /** Unbinds the board at an address: its Restore now if it is the connected one, else its record left pending. */
    suspend fun unbind(address: String): MeshcoreUnbind

    /**
     * [setup] over another board's record at this address (Stage 7): only while that record is still the one [token] was
     * issued for ([MeshcoreSetupResult.DifferentBoard]); every step of a setup runs as ever.
     */
    suspend fun setupReplacing(token: ReplaceToken): MeshcoreSetupResult = MeshcoreSetupResult.NotReady

    /** What a setup would erase on the Ready board, read fresh on the actor (Stage 7); null when it is not Ready. */
    suspend fun setupPreview(): SetupPreview? = null

    /**
     * Whether this phone set the bound board up (Stage 7): read off its record at start and moved by the actor's own reads and
     * writes, so the screen shows it while the board is away too. Null before anything was read.
     */
    val setupState: StateFlow<MeshcoreSetupView?> get() = NO_SETUP_VIEW

    /** This phone's consent for the board each Ready is on, from its setup record: the actor's alone, in its order. */
    val consent: StateFlow<MeshcoreConsent?>

    /** The keys Knit teaches the board, ranked (`MeshcoreContacts`, Stage 5b-2): the only keys sent to or pinned. Any thread. */
    fun setKnitContacts(
        ranked: List<ByteArray>,
        withdrawn: Boolean = false,
    )

    /** Writes [key]'s Knit contact into a free slot, read back, and marks it taught. */
    suspend fun teach(key: ByteArray): ContactWrite

    /** Removes [key]'s contact, read back gone, unless it is taught again by then (lora N1). */
    suspend fun removeUntaught(key: ByteArray): ContactWrite

    /**
     * Whether our board's contact for [from] is a key Knit taught, its zero-hop pin read back on this connection: then our
     * board answers a DM from it along that path, never by flood (`sendAckTo`). Only an air price reads it; no route,
     * hearing or delivery keys on a prefix. Any thread.
     */
    fun pinnedZeroHop(from: app.getknit.knit.mesh.lora.BoardAddr.MeshCore): Boolean = false

    /**
     * The board as the session last published it, while it is Ready (`BoardLink.state`); null otherwise. What the Public
     * post's gate reads at enqueue (ADR 2026-11.gsqu's Stage 6 amendment); the session re-asks it at the write. Any thread.
     */
    val ready: BoardState.Ready? get() = null
}

/** One channel slot as the session keeps it: never a foreign slot's name or secret (binding rule "contacts" 4). */
internal data class MeshcoreSlot(
    val index: Int,
    val empty: Boolean,
    val knit: Boolean,
)

/** The setup state of the board at [address] as the session last read or wrote its record (Stage 7). */
internal data class MeshcoreSetupView(
    val address: String,
    val state: BoardSetupState,
)

private val NO_SETUP_VIEW: StateFlow<MeshcoreSetupView?> = MutableStateFlow<MeshcoreSetupView?>(null).asStateFlow()

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
    /**
     * The address the session that reported this dialled (Stage 5a-2, the `dialled` race): what the board's key is written
     * under, so a late Ready from a board the user has since replaced writes nothing back. Null only in a test's detail.
     */
    val dialled: String? = null,
    /**
     * Slot 0 as the handshake read it (ADR 2026-11.gsqu's Stage 6 amendment): its name, whether its key is the published
     * Public key, whether it holds a channel at all — never its secret. What the Public post's gate and the room's face read;
     * null only in a test's detail.
     */
    val publicSlot: MeshcorePublicSlot? = null,
    /** Whether the board airs a position (`SELF_INFO`, [MeshcoreProto.sharesPosition], Stage 7): never the position itself. */
    val sharesPosition: Boolean = false,
) {
    private val key = publicKey.copyOf()

    /** A copy of the board's 32-byte Ed25519 public key, what the profile will advertise as `meshcoreKey` (Stage 5). */
    val publicKey: ByteArray get() = key.copyOf()

    override fun toString(): String = "MeshcoreDetail(v=$versionCode, slots=${slots.size}, scope=$defaultScope)"
}

/**
 * What the plane's MeshCore hooks hear of a [MeshcoreSession] (ADR 2026-11.gsqu, Stage 5a-2), on the session's actor: each
 * returns at once, suspends nothing and never calls the session back.
 */
internal interface MeshcoreSessionObserver {
    /** The board identified itself ([publicKey], a copy), before this connection's first stats poll. */
    fun onIdentified(publicKey: ByteArray)

    /** The session is about to publish [ready]: the ledger's radio and band go in first. */
    fun beforeReady(ready: BoardState.Ready)

    /** One poll's CORE uptime and RADIO `tx_air`, read at [wallMs] on the wall clock: the duty law's sample. */
    fun onRadioStats(
        wallMs: Long,
        uptimeSecs: Long,
        txAirSecs: Long,
    )
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
    /**
     * The board is set up: Knit's channel at [slot]; [record] holds what a restore puts back. The erase removed
     * [erasedContacts] contacts and cleared [clearedSlots] channel slots (Stage 5b-2), which no restore brings back.
     */
    data class Done(
        val slot: Int,
        val alreadyPresent: Boolean,
        val record: MeshcoreSetupRecord,
        val erasedContacts: Int = 0,
        val clearedSlots: Int = 0,
        /** The board this setup ran on: its address and its own key's digest, the record's key (security S1). */
        val address: String? = null,
        val digest: String? = null,
    ) : MeshcoreSetupResult

    /** The board has no channel slot but Public: nothing was written. */
    data object NoFreeSlot : MeshcoreSetupResult

    /**
     * A read or a write failed. Nothing was written, or what was written was put back; [left] is what could not be put
     * back, for the caller to persist so a restore can, or null when nothing is left changed.
     */
    data class Failed(
        val reason: String,
        val left: MeshcoreSetupRecord?,
        /** The board [left] is on, as in [Done]. */
        val address: String? = null,
        val digest: String? = null,
    ) : MeshcoreSetupResult

    data object NotReady : MeshcoreSetupResult

    /**
     * Refused before any write (Stage 7): this phone keeps a record at the address for another board's key — the same hardware
     * reset or reflashed, or another board. [token] stands for that record; [MeshcoreBoardOps.setupReplacing] replaces it.
     */
    class DifferentBoard(
        val token: ReplaceToken,
    ) : MeshcoreSetupResult {
        override fun toString(): String = "DifferentBoard"
    }
}

/** The outcome of [MeshcoreSession.restore]: what was put back, and what was found changed by someone else and left. */
internal sealed interface MeshcoreRestoreResult {
    data class Done(
        val slotCleared: Boolean,
        /** What was not Knit's any more and so left as found (by key, never a value: `slot`, `name`, `manualAdd`, `autoadd`). */
        val leftAsFound: List<String>,
        /** What could not be written or verified (`contacts` among them when Knit's could not all be removed). */
        val failed: List<String>,
        /** Knit's contacts removed, each read back gone (Stage 5b-2). */
        val contactsRemoved: Int = 0,
    ) : MeshcoreRestoreResult

    data object NotReady : MeshcoreRestoreResult

    /** The connected board is not the one the record is for (another board at that address), or not bonded: nothing written. */
    data object Mismatch : MeshcoreRestoreResult

    /** This phone keeps no setup record for the connected board. */
    data object NoRecord : MeshcoreRestoreResult
}

/**
 * This phone's consent for one board (the second part; the maintainer's decisions, 2026-11-13): its [address] and key
 * [digest], [granted] from a non-pending record of that board on a bonded link, and the board's own [key] — what the
 * profile's claim publishes while [granted] — with the board's [sig] binding our node id to it (ADR 2026-11.gsqu, Stage
 * 5d), so the key and its proof reach the claim store as one edit from one writer. Its [toString] names neither.
 */
internal class MeshcoreConsent(
    val address: String,
    val digest: String,
    val granted: Boolean,
    key: ByteArray?,
    /**
     * The record could not be read this connection (security N2): the offers close, and the claim stays as the last consent
     * left it; [granted] is false. Read again on the actor with a backoff.
     */
    val unread: Boolean = false,
    /**
     * The board's signature of `MeshcoreBind.message(our node, key)`, verified on the actor (Stage 5d); kept only on a granted
     * consent with a key. Null when this connection's bind has not succeeded: the claim store then keeps a proof it already
     * holds for the same key.
     */
    sig: ByteArray? = null,
) {
    private val bytes = key?.copyOf()
    private val sigBytes = sig?.takeIf { granted && key != null }?.copyOf()

    /**
     * The order this consent was made in, across every session of the process (Stage 7): a Forget fences the claim at the
     * latest one made, so a grant made before it — still on its way through the hooks' collector, or a claim edit retried —
     * can never publish the key after the Forget returns.
     */
    val seq: Long = SEQ.incrementAndGet()

    /** The board's key, null for a withdrawal published with the board away (an unbind). */
    val key: ByteArray? get() = bytes?.copyOf()

    /** The bind's signature over (our node, [key]), or null. */
    val sig: ByteArray? get() = sigBytes?.copyOf()

    override fun toString(): String = "MeshcoreConsent(granted=$granted, signed=${sigBytes != null})"

    companion object {
        private val SEQ = AtomicLong()

        /** The seq of the latest consent made so far: what a Forget fences the claim at. */
        fun latestSeq(): Long = SEQ.get()
    }
}

/** What an unbind did with the board's setup record ([MeshcoreBoardOps.unbind]). */
internal enum class MeshcoreUnbind {
    /** Restore's clearing read back, and the record is gone. */
    CLEARED,

    /** The board was not reachable, or its Restore did not finish: the cleanup runs at its next connection. */
    PENDING,

    /** No record for that board: nothing to clean up. */
    NOTHING,

    /**
     * The connected board is not the one the record at its address is for (another key: the hardware reset or reflashed, or
     * another board; Stage 7): Knit cannot clean it. The record is left pending, and the consent withdrawn.
     */
    MISMATCH,

    /**
     * The record could not be marked pending (a failed or timed-out write, lora N3): its next connection would read it as
     * consent again. The caller keeps the binding and asks again.
     */
    FAILED,
}

/** The outcome of a contact write ([MeshcoreSession.teach], [MeshcoreSession.removeContact], [MeshcoreSession.resetPath]). */
internal enum class ContactWrite {
    /** Written and read back. */
    WRITTEN,

    /** A teach of a key the board already holds, left as it was (a board not set up for Knit, or already Knit's record). */
    ALREADY_PRESENT,

    /** A teach with no free slot confirmed: nothing written, so nobody's contact is evicted. */
    NO_FREE_SLOT,

    /** The key is not on the board (a remove, a reset). */
    NOT_FOUND,

    /** A read, a write or its verification failed or timed out. */
    FAILED,

    /** The board is not set up for Knit (no Knit slot and name): nothing of the user's is written or removed (5b-2 review B1). */
    NOT_SET_UP,

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
 * each means to the plane. [MeshcoreTraits.refusalOf] reads [classOf].
 *
 * **The firmware's codes are read by context in the session, before they reach here** (ADR 2026-11.gsqu §3 and §4,
 * `MyMesh.cpp:1132-1236` at `companion-v1.18.0`), so the one code a context changes never arrives ambiguous:
 * - `TABLE_FULL` is no free packet on every send (`composeMsgPacket` / `sendGroupData` returned nothing) — and on a DM
 *   also a text over `MAX_TEXT_LEN`, which the encoder never writes (it refuses past 157 B) — so it is
 *   [SendResult.Busy], never a refusal;
 * - `NOT_FOUND` on a DM is no contact for the prefix: [RefusalClass.OWN_NO_KEY], and the session unverifies the pin;
 * - `NOT_FOUND` on a channel **text** (the Public post) is also no free packet (`sendGroupMessage` failing answers it):
 *   [SendResult.Busy];
 * - `NOT_FOUND` on channel **data** (a room post) is a slot the board no longer has: [SLOT_NOT_KNIT], the slot read
 *   again before the next post;
 * - `ILLEGAL_ARG` is a payload past the board's limit or the reserved data type: [SendResult.TooLarge];
 * - `UNSUPPORTED_CMD`, `BAD_STATE`, `FILE_IO` and every own code but the two below: [RefusalClass.OTHER].
 *
 * The firmware refuses nothing for duty (no [RefusalClass.PACE]) and reports no destination's refusal (no
 * [RefusalClass.DEST_REFUSED] or [RefusalClass.PEER_LACKS_KEY]): a DM a far board drops is silence.
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
     * A Public post off a board not set up for Knit at the write (ADR 2026-11.gsqu's Stage 6 amendment): no consent on this
     * connection, or its name is no longer `Knit xxxx` — the firmware would put that name in front of the post.
     */
    const val NOT_SET_UP = -14

    /** A Public post to a slot 0 that holds no channel, or Knit's own, at the write (Stage 6). */
    const val NO_PUBLIC_SLOT = -15

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

    /** A whole `GET_CONTACTS` listing read (Stage 5b-2: one a Ready on a board set up for Knit, one per setup and restore). */
    val contactListings = AtomicLong()

    /** A listing short of its `CONTACTS_START` count: never acted on whole. */
    val listingsShort = AtomicLong()

    /** A Ready reconcile run from a listing. */
    val reconciles = AtomicLong()

    /** A Ready reconcile skipped: the listing failed or came short (the per-key re-assert ran instead). */
    val reconcilesSkipped = AtomicLong()

    /** A Ready reconcile owed on a board this connection does not consent for, dropped (`meshcoreReconcilesDropped`). */
    val reconcilesDropped = AtomicLong()

    /** Contacts a reconcile removed (not taught). */
    val reconcileRemoved = AtomicLong()

    /** Taught contacts a reconcile overwrote to Knit's record. */
    val reconcileOverwritten = AtomicLong()

    /** Taught keys a reconcile wrote fresh. */
    val reconcileTaught = AtomicLong()

    /** Reconcile steps that did not read back. */
    val reconcileFailed = AtomicLong()

    /** Removals, overwrites or writes the per-Ready bounds or the capacity left for later. */
    val reconcileDeferred = AtomicLong()

    /** Taught keys whose prefix a listed contact of another key shares, kept from sends for the connection (security B3). */
    val prefixContests = AtomicLong()

    /** A setup refused because this phone keeps a record at the address for another board's key (Stage 7). */
    val differentBoards = AtomicLong()

    /** A Restore or cleanup refused: the connected board is not the one its record is for, or not bonded (security S1). */
    val restoreMismatches = AtomicLong()

    /** A record at the connected board's address for another board's key: no consent, never acted on. */
    val consentMismatches = AtomicLong()

    /** A handshake whose record read kept failing: no consent that connection. */
    val consentReadFailures = AtomicLong()

    /** A board not bonded at its consent read or setup: inert for Knit (no consent, no Restore, no cleanup). */
    val unbondedInert = AtomicLong()

    /** A board whose signature of the consent challenge does not verify under its key (a clone), or whose key changed after. */
    val keyUnproven = AtomicLong()

    /** A `SELF_INFO` mid-connection reporting another key than the connection identified with: the session ended. */
    val keyChanged = AtomicLong()

    /** A consent challenge the board did not answer (a lost notify, BAD_STATE, no such command): asked again later. */
    val proofUnanswered = AtomicLong()

    /**
     * A bind (Stage 5d: the board's signature over our node id and its key, for the sealed profile's `meshcoreSig`) the board
     * did not answer — a lost notify, `BAD_STATE`, another client's `SIGN_START` or `SIGN_FINISH` in the shared buffer — or
     * asked with no node id to bind: the consent goes out without it, and it is asked again on its own backoff.
     */
    val bindUnanswered = AtomicLong()

    /** A bind whose signature does not verify under the proven key (another client's bytes in the buffer, or a re-key). */
    val bindRefused = AtomicLong()

    /** A setup record read for a Restore or an unbind that failed: answered as no answer, never as "no record". */
    val recordReadFailures = AtomicLong()

    /** A setup record write or clear that failed: no consent rests on it. */
    val recordWriteFailures = AtomicLong()

    /** Contacts a setup's erase removed. */
    val setupErasedContacts = AtomicLong()

    /** Channel slots a setup's erase cleared. */
    val setupClearedSlots = AtomicLong()

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

    /**
     * A targeted re-pin a path push queued, run to its end and read back zero-hop (`meshcoreRepinsDone`, ADR 2026-11.gsqu's
     * Stage 5c-2b amendment): written or found already pinned. What a scenario waits on after a `0x81` before the next send.
     */
    val repinsDone = AtomicLong()

    /**
     * Live teaches (a key the taught set gained while Ready, or a contact the board deleted) written and read back
     * (`meshcoreLiveTeachesWritten`), failed (`meshcoreLiveTeachesFailed`, re-queued once), and left to the next Ready's
     * reconcile past a key's daily bound (`meshcoreLiveTeachesDeferred`). ADR 2026-11.gsqu's Stage 5c-2b amendment.
     */
    val liveTeachesWritten = AtomicLong()
    val liveTeachesFailed = AtomicLong()
    val liveTeachesDeferred = AtomicLong()

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

    /** A command the link would not carry in one write, refused alone rather than ending the session. */
    val commandsRefused = AtomicLong()
}
