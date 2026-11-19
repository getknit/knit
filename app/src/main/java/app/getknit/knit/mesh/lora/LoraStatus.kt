package app.getknit.knit.mesh.lora

import app.getknit.knit.mesh.lora.meshcore.MeshcoreHandle
import app.getknit.knit.mesh.lora.meshcore.MeshcoreNames

/**
 * A snapshot of the LoRa plane for the settings/diagnostics row — the board it is bound to, the live
 * link state, the last signal reading, and the running counters. Derived purely from the transport's
 * existing state, so reading it never perturbs routing.
 */
internal data class LoraStatus(
    val state: LinkState = LinkState.Idle,
    val boardName: String? = null,
    val boardAddress: String? = null,
    val boardNodeNum: UInt? = null,
    /**
     * The freshest signal reading among the radios in [boardsHeard] — a link this plane actually has, never
     * the last packet the board happened to overhear (which on a stock public primary is mostly strangers
     * three to seven hops out at the noise floor). Null once every radio has aged out of the linger.
     */
    val lastSnr: Float? = null,
    val lastRssi: Int? = null,
    val queueFree: Int? = null,
    /** People whose frames have reached us over LoRa — authors, so one an older build's gateway relayed counts too. */
    val heard: Int = 0,
    /** Radios we have heard transmit on our channel. This, not [heard], is "how many boards are in range". */
    val boardsHeard: Int = 0,
    /** The board's own power reading, once its handshake or telemetry has reported one. */
    val battery: BoardBattery? = null,
    /** The board's own duty-cycle measurement, beside [airtime]'s estimate of Knit's share of it. */
    val boardAir: BoardAir? = null,
    /** The airtime ledger: what the plane has spent this window against its one allowance (ADR 2026-10.ug4a). */
    val airtime: AirtimeSnapshot? = null,
    /**
     * Whether the shared-band brake holds Knit room posts now: the board's last channel-utilisation reading at 40 % or
     * more, at most five minutes old, off a dedicated slot (ADR 2026-10.ug4a). DMs still go.
     */
    val braked: Boolean = false,
    /**
     * How old the reading [braked] rests on is, or null when there is none. Both are as of the last publish: the linger
     * sweep republishes once a minute, so the row may read a lifted brake for up to that long.
     */
    val bandReadingAgeMs: Long? = null,
    /**
     * Peers a connected spool was recently a path to — the Internet cover (ADR 2026-09.y5f3): compact DMs to them stay
     * off the air.
     */
    val internetCovered: Int = 0,
    /**
     * Frames waiting in the pacer. Beside [airtime] it is the difference between a quiet plane and a starved
     * one: a spent window holds frames rather than dropping them, so without a depth the dump reads healthy
     * until the queue overflows and the drops arrive all at once (2026-09-04).
     */
    val queued: Int = 0,
    /**
     * What the peers' boards said to our compact DMs (ADR 2026-10.x9fa): how many are marked reachable, unreachable or
     * refused, how many sends wait on an answer and how many offers are parked behind one. About radios, never people.
     */
    val direct: DirectReachCounts = DirectReachCounts(),
    /**
     * The peers whose boards [direct] counts as reachable (Stage 5a-2): what a MeshCore DM notice reads
     * (`LoraFacts.directReach`). Node ids of pinned peers, never a board's address or key; on screen only as a reach state.
     */
    val directReachable: Set<String> = emptySet(),
    /**
     * Whether the band's law holds every frame now (MeshCore's `MeshcoreDutyLaw`, `LoraPacePolicy.lawHeld`; never on
     * Meshtastic, where the law is the firmware's): the plane is silent until the board's trailing hour falls under its duty.
     */
    val lawHeld: Boolean = false,
    /**
     * The board's public channel as a kind that reads it off the board renders it (MeshCore's slot 0, ADR 2026-11.gsqu's
     * Stage 6 amendment), while the board is Ready; null otherwise and on Meshtastic, whose room reads the channel table on
     * [state].
     */
    val publicChannel: PublicChannelFace? = null,
    /**
     * A MeshCore board's own facts for the radio screen (ADR 2026-11.gsqu's Stage 7 amendment), on the MeshCore plane's
     * status alone, null on Meshtastic. Counts, booleans and the board's radio only: never a name, a key, a digest or a
     * setup record's field.
     */
    val meshcore: MeshcoreBoardFace? = null,
)

/**
 * What the radio screen shows of a bound MeshCore board (ADR 2026-11.gsqu's Stage 7 amendment). Every fact but [setup] and
 * [sharesPosition] is the Ready board's and null while it is away; [setup] is read off this phone's setup record, so it is
 * shown while the board is away too; [sharesPosition] is the last `SELF_INFO` this connection or an earlier one to the same
 * address read, null while none was.
 */
internal data class MeshcoreBoardFace(
    /** The firmware version string, cleaned for one line (`MeshcoreHooks.cleanVersion`), as the bridge shows it. */
    val firmware: String? = null,
    val freqKhz: Long? = null,
    val spreadFactor: Int? = null,
    val bandwidthHz: Int? = null,
    /** The coding rate's denominator: 5 for 4/5. */
    val codingRate: Int? = null,
    /** Whether the board floods under a default scope (set by the user's own app, never by Knit): "set" or "none". */
    val defaultScope: Boolean? = null,
    /** The board's battery in millivolts, as MeshCore reports it: no percent and no "powered" (the firmware says neither). */
    val batteryMv: Int? = null,
    /**
     * Whether the board puts a position on the air (`advert_loc_policy`, or location telemetry open to anyone; refs #285).
     * Knit never writes either: the screen and the Public post's consent sheet only say so.
     */
    val sharesPosition: Boolean? = null,
    val setup: BoardSetupState = BoardSetupState.UNKNOWN,
    /**
     * The Ready board's advert name, its owner's handle (ADR 2026-11.f4d3): raw bytes, which may be a person's name — clean
     * it before drawing it (`TextLimits.cleanReceived`), and never log it. Null while the board is away.
     */
    val handle: MeshcoreHandle? = null,
    /** Whether [handle] is Knit's default for the board's key, byte for byte ([MeshcoreNames.isDefault]); false while away. */
    val namedDefault: Boolean = false,
    /**
     * Whether [handle] breaks the postable rules ([MeshcoreNames.check]: a `": "`, a control or bidi override, invalid UTF-8,
     * empty or too long), which another app may have written: Public posts are refused (`NAME_UNPOSTABLE`) until it is renamed.
     */
    val handleUnpostable: Boolean = false,
    /** Knit's default name for the Ready board's key (`Knit abcd`, [MeshcoreNames.forKey]): what "Use Knit abcd" renames to. */
    val defaultName: String? = null,
) {
    /** Counts and booleans only: never the handle or the default name, which names the board's key. */
    override fun toString(): String =
        "MeshcoreBoardFace(firmware=$firmware, freqKhz=$freqKhz, sf=$spreadFactor, bw=$bandwidthHz, cr=$codingRate, " +
            "scope=$defaultScope, batteryMv=$batteryMv, sharesPosition=$sharesPosition, setup=$setup, " +
            "handle=${handle != null}, namedDefault=$namedDefault, unpostable=$handleUnpostable)"
}

/**
 * What a MeshCore rename ([LoraPlaneStatus.renameMeshcore], ADR 2026-11.f4d3) came to. Nothing about the name itself: the
 * debug bridge's reply carries this alone, and is logged whole.
 */
internal enum class MeshcoreRenameResult {
    /** Written, read back as the name asked for, and the Ready published with it. */
    DONE,

    /** The name breaks the postable rules ([MeshcoreNames.check]): nothing written. */
    INVALID,

    /** The board is not set up for Knit on this phone (no consent on this connection, or no Knit slot): nothing written. */
    NOT_SET_UP,

    /** The bound board is not Ready. */
    NOT_READY,

    /** The write was not answered, or the board read back another name. */
    FAILED,

    /** No MeshCore plane in this build, or no MeshCore board bound. */
    NO_BOARD,
}

/**
 * Whether this phone set the bound MeshCore board up for Knit (ADR 2026-11.gsqu's Stage 7 amendment), read from its setup
 * record and, while the board is up, the board's proof: never [NONE] for what could not be read.
 */
internal enum class BoardSetupState {
    /** No record for the board at that address, or a record for another board there: Knit's setup is not on it. */
    NONE,

    /** A record of this board, not pending, and — while the board is up — proven and consenting on this connection. */
    SET_UP,

    /** A cleanup is owed: an unbind, a Forget or a setup cut off left its record pending; it runs at the next connection. */
    PENDING,

    /** Not known: the record could not be read, or the board did not answer its proof, or nothing was read yet. */
    UNKNOWN,
}

/**
 * What a MeshCore setup is about to erase (ADR 2026-11.gsqu's Stage 7 amendment), read fresh off the Ready board when the
 * confirmation opens: [contacts] on the board and [slots] channel slots in use besides Public and Knit's. Counts only; null
 * for a count the board did not answer, never 0 for unknown. The screen words it "about N": another client can add or remove
 * between this read and the erase.
 */
internal data class SetupPreview(
    val contacts: Long?,
    val slots: Int?,
    /**
     * Whether the setup's name step will run (ADR 2026-11.f4d3): the board will be renamed `Knit abcd`. False for a board
     * this phone already set up (a re-setup keeps its handle) and for one already carrying the default name.
     */
    val renames: Boolean = false,
)

/**
 * A board's public channel as the plane's face shows it (MeshCore's slot 0, ADR 2026-11.gsqu's Stage 6 amendment): what the
 * slot holds, never a constant `Public` — a slot cleared or re-keyed by the user is what it is.
 */
internal data class PublicChannelFace(
    /** The slot's own name, cleaned for one line; null when it has none. */
    val name: String?,
    /** Whether its key is the firmware's published Public key, which every radio and every uploader holds. */
    val keyIsPublic: Boolean,
    /**
     * Whether a post may leave through it now: the board is set up for Knit on this phone (the connection consents, and its
     * name passes the postable rules, ADR 2026-11.f4d3) and the slot holds a channel (not empty, not Knit's).
     * Structural: the room switch, the floor and the air are answered per post.
     */
    val postable: Boolean,
)
