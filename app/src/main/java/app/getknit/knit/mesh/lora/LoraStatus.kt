package app.getknit.knit.mesh.lora

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
     * Whether a post may leave through it now: the board is set up for Knit on this phone (its name is `Knit xxxx` and the
     * connection consents, so no post leads with anybody's real name) and the slot holds a channel (not empty, not Knit's).
     * Structural: the room switch, the floor and the air are answered per post.
     */
    val postable: Boolean,
)
