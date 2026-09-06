package app.getknit.knit.mesh

/**
 * Puts one line of text on the bound board's primary (slot 0) channel — the Meshtastic room's outbound half.
 *
 * The mirror of [MeshPostSink], pointing the other way: that seam exists so `mesh/lora/` can deliver a post
 * without knowing how a row is written, and this exists so [MeshManager] can reach a radio without knowing
 * one is there. Implemented by `mesh/lora/LoraMeshTransport`, and injected as a lambda that defaults to
 * refusing — a build with no board, and every test that does not care, then needs no wiring at all.
 *
 * **The author's own phone calls it, and only that phone.** A post typed in the room leaves through the
 * board paired to the phone it was typed on; it is never handed to another phone, never flooded, never
 * custodied. A phone with no board cannot post, and says so, rather than posting into Knit and hoping a
 * pocket-mate carries it.
 */
interface PublicChannelSink {
    /**
     * Transmits one post on the board's primary channel, or says why not.
     *
     * Returns null once the post is queued on the board, else the [PublicPostRefusal] — counted, and handed
     * back so the composer can show it: the board is not ready, slot 0 is the Knit channel, the 30 s floor
     * has not elapsed, the line does not fit one packet, or the airtime bucket is spent.
     *
     * The line a stock client reads is composed behind this seam (`mesh/lora/PublicPostPolicy.onAirText`),
     * so the caller does not have to know what a Meshtastic client's 200-byte convention is. It is the
     * author's [body] and nothing else: no name rides out with it (ADR 2026-09.9469).
     */
    suspend fun postToPublicChannel(body: String): PublicPostRefusal?
}

/**
 * Why a post typed in the Meshtastic room did not reach the air from this device. Public rather than
 * `internal` to `mesh/lora/`, because [MeshController.sendPublicPost] hands it to the composer, which
 * says it to the user.
 */
enum class PublicPostRefusal {
    /** No LoRa plane in this build, or no sink wired — the default every fake and board-less build gets. */
    NO_BOARD,

    /** A board is bound but its session is not up. */
    NOT_READY,

    /**
     * The user switched the Meshtastic room off (`SettingsStore.loraRoomEnabled`), so this phone has no room
     * to post from. Unreachable from the composer, which goes with the room's row — this is the net under the
     * routes that do not draw one.
     */
    ROOM_OFF,

    /** Slot 0 on this board *is* the Knit channel (the lab shape), so there is no primary to post on. */
    KNIT_ON_PRIMARY,

    /** Inside the per-board floor: one post per 30 s, so a room cannot become a transmitter. */
    TOO_SOON,

    /** Longer than one packet carries even after trimming — should be unreachable, kept so it is visible. */
    TOO_LARGE,

    /** The public bucket's share of the rolling window is spent. */
    NO_AIR,

    /** The board or the mesh refused the packet. */
    NAK,
}

/** What became of a post typed in the Meshtastic room ([MeshController.sendPublicPost]). */
sealed interface PublicPostOutcome {
    /** Queued on this phone's board. The only outcome that stores a row. */
    data object Queued : PublicPostOutcome

    /** Refused by on-device content filtering before it reached the board. Nothing stored. */
    data object Blocked : PublicPostOutcome

    /** The board would not take it, for [reason]. Nothing stored; the draft stays the user's to keep. */
    data class Refused(
        val reason: PublicPostRefusal,
    ) : PublicPostOutcome
}
