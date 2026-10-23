package app.getknit.knit.data.settings

/**
 * A Meshtastic board set up for Knit (ADR 045): it carries the Knit channel in a secondary slot, its own
 * broadcasts are stretched, and it no longer repeats strangers' traffic.
 *
 * The values here are the board's own as they stood *before* the setup, kept so restoring can put those back
 * instead of guessing at the firmware's. A zero interval means the value was never recorded, which the
 * restore writes as "let the firmware substitute its default"; [rebroadcastMode] 0 is a real value (ALL).
 * An empty [longName] / [shortName] likewise means no name was recorded, which the restore answers with the
 * one the firmware itself would have given the board.
 *
 * Primitive by design: [SettingsStore] is a data-layer class and stays free of `mesh/` types; the LoRa
 * ViewModel maps this onto `mesh.lora.BoardSettings` at the seam.
 */
data class KnitBoardSetup(
    val address: String,
    val nodeInfoSecs: Int,
    val positionSecs: Int,
    val smartPosition: Boolean,
    val telemetrySecs: Int,
    val rebroadcastMode: Int,
    /** The board's own `User.long_name` before the setup renamed it for Knit (ADR 049); empty if unrecorded. */
    val longName: String = "",
    /** The board's own `User.short_name`, likewise. */
    val shortName: String = "",
    /**
     * The board's own `Config.LoRaConfig.channel_num` before the setup — 0 on every board that was left on
     * the shared public frequency, which is all of them unless the dedicated setup pinned a slot
     * (ADR 067). Recorded so a restore hands the radio back to whatever the user had, not to an assumed 0.
     */
    val channelNum: Int = 0,
)
