package app.getknit.knit.mesh.lora

/**
 * The LoRa plane's state as the connection header reports it — the board's counterpart of
 * `RelayPlane`: one answer for the whole device, coarse on purpose (the header has room for a glyph;
 * *which* board, its signal and its channel stay on the LoRa radio screen).
 */
enum class LoraPlane {
    /** The plane is switched off, or no board is bound — the header says nothing about LoRa at all. */
    Off,

    /** A board is bound but the link is not up (connecting, reconnecting, pairing needed, Bluetooth off). */
    Down,

    /** The board session is ready: frames are crossing the air. */
    Live,
}

/**
 * What the chat UI reasons about the LoRa plane, flattened out of `LoraStatus` + settings: the header
 * [plane], and whether private messages ride it ([dms] — `SettingsStore.loraDmEnabled`, true only while
 * the plane is on). One flow rather than two so every ViewModel takes a single `Flow<LoraFacts>`.
 */
data class LoraFacts(
    val plane: LoraPlane = LoraPlane.Off,
    val dms: Boolean = false,
    /** The board's battery while [plane] is [LoraPlane.Live] (the Profile row shows it); never a reach input. */
    val battery: BoardBattery? = null,
    /**
     * Whether the plane has spent its airtime window (ADR 054): a DM to a peer only the board can hear will
     * wait for air, and the chat says so instead of looking delivered-and-ignored. A threshold, not a
     * percentage, so a busy plane does not re-emit the facts on every packet. False unless [plane] is Live.
     */
    val airtimeSpent: Boolean = false,
    /**
     * The board's primary (slot 0) channel as the Meshtastic room names it — its own name, else the preset's
     * (`LongFast`, `MediumFast`, …) — while [plane] is [LoraPlane.Live]; null otherwise, and null on a board
     * that has neither named it nor reported its preset. The room's title and the chat-list row read it.
     */
    val primaryChannel: String? = null,
    /**
     * Whether a post typed in the Meshtastic room can leave this device now: the link is live and slot 0 is
     * not the Knit channel itself. Structural only — the 30 s floor and the airtime budget are answered per
     * send, by the outcome the board hands back.
     */
    val canPost: Boolean = false,
    /**
     * Whether slot 0 carries a key every radio on the band already has
     * ([PublicChannelPolicy.primaryKeyIsPublic]) — what the room's notice and composer hint say about its
     * privacy: *unencrypted* when true, *not end-to-end encrypted* when the user keyed it themselves.
     *
     * True by default and while the link is down, because a room drawn like every other thread in Knit is
     * read as private unless it says otherwise, and the cost of the two mistakes is not symmetric.
     */
    val primaryKeyIsPublic: Boolean = true,
    /**
     * Whether the bound board's firmware signs the posts it sends for us (`LoraAirtime.signing`: 2.8 and
     * later, or its own `has_xeddsa`). The Meshtastic room's composer reads it to cap a post at the size the
     * board can still sign, so every post leaves signed ([PublicPostPolicy.onAirBudget]). True until a board
     * says otherwise, the same default the airtime governor keeps: unknown is the strict case, and a
     * pre-2.8 board reports its version within the handshake and gets the client cap back.
     */
    val signs: Boolean = true,
)

/**
 * The [LoraPlane] for the settings + link state. Off outranks the link: a board that is still connected
 * while the user switches the plane off is on its way down, and Off is what they asked for.
 */
internal fun loraPlaneFor(
    enabled: Boolean,
    bound: Boolean,
    state: LinkState,
): LoraPlane =
    when {
        !enabled || !bound -> LoraPlane.Off
        state is LinkState.Ready -> LoraPlane.Live
        else -> LoraPlane.Down
    }
