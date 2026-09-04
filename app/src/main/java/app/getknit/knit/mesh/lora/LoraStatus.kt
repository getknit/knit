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
    /** People whose frames have reached us over LoRa — authors, so relayed and backfilled ones count too. */
    val heard: Int = 0,
    /** Radios we have heard transmit on our channel. This, not [heard], is "how many boards are in range". */
    val boardsHeard: Int = 0,
    /** The board's own power reading, once its handshake or telemetry has reported one. */
    val battery: BoardBattery? = null,
    /** The board's own duty-cycle measurement, beside [airtime]'s estimate of Knit's share of it. */
    val boardAir: BoardAir? = null,
    /** The airtime ledger: what the plane has spent this window against what it allows itself (ADR 044/054). */
    val airtime: AirtimeSnapshot? = null,
    /** Whether this phone speaks for its pocket on the hop, or another board here does (ADR 044). */
    val role: LoraGatewayPolicy.Role = LoraGatewayPolicy.Role.ACTIVE,
    /** Peers a short-range plane holds a live link to — the election's input, so [role] can be explained. */
    val pocketLinks: Int = 0,
    /** LoRa gateways heard within the staleness window — the election's other input. */
    val gatewaysHeard: Int = 0,
    /**
     * Peers a short-range plane has merely *sighted*. Nothing routes on this; it sits beside [pocketLinks]
     * so the gap between "heard" and "linked" — the state that silenced a board in the field — is visible.
     */
    val pocketSightings: Int = 0,
)
