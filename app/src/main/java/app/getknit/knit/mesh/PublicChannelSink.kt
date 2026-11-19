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
     * has not elapsed, the line does not fit one packet, or the window is past its half ceiling (ADR 2026-10.ug4a).
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

    /**
     * The board is pinned to a dedicated RF slot (ADR 067), where no public radio can hear it
     * and the room is hidden. Like [ROOM_OFF], the net under a route that outlived the row.
     */
    DEDICATED,

    /** Slot 0 on this board *is* the Knit channel (the lab shape), so there is no primary to post on. */
    KNIT_ON_PRIMARY,

    /**
     * The board is not set up for Knit on this phone (MeshCore, ADR 2026-11.gsqu's Stage 6 amendment): this phone holds no
     * consent for it. Its name is asked apart since ADR 2026-11.f4d3 ([NAME_CHANGED], [NAME_UNPOSTABLE]).
     */
    NOT_SET_UP,

    /**
     * The MeshCore board's name is not the one the post was consented under (ADR 2026-11.f4d3): the firmware puts the board's
     * own name, its owner's handle, in front of every post, and another app renamed it — at the composer's post, or while the
     * post waited for the air (then it ends at the write, unaired). The composer shows the new name and asks again.
     */
    NAME_CHANGED,

    /**
     * The MeshCore board's name cannot lead a Public post (ADR 2026-11.f4d3): another app wrote one holding `": "`, a
     * control or bidi override, or bytes that are not UTF-8, which receivers would split or draw as something else. Rename
     * the board.
     */
    NAME_UNPOSTABLE,

    /**
     * The board's public slot holds no channel (MeshCore's slot 0 cleared: a post would air under the all-zero key and reach
     * nobody, ADR 2026-11.gsqu's Stage 6 amendment).
     */
    NO_CHANNEL,

    /** Inside the per-board floor: one post per 30 s, so a room cannot become a transmitter. */
    TOO_SOON,

    /** Longer than one packet carries even after trimming — should be unreachable, kept so it is visible. */
    TOO_LARGE,

    /** The rolling window, this post's cost included, would pass half its allowance (`Ceiling.HALF`, ADR 2026-10.ug4a). */
    NO_AIR,

    /** The board or the mesh refused the packet. */
    NAK,
}

/**
 * What a MeshCore board's Public channel did with a post typed in the MeshCore room (ADR 2026-11.gsqu's Stage 6
 * amendment) — the MeshCore twin of [PublicChannelSink.postToPublicChannel]'s answer, carrying what that one cannot: the
 * id the own row takes. A MeshCore post's receivers key it on the stamp fixed at enqueue and the text as they read it
 * (`FrameId.forMeshcorePost`), so the row is written under that id and an echo of it, re-aired after the board's seen
 * ring forgot it, dedups there. Public, beside the sink, so nothing above the seam learns a MeshCore type.
 */
sealed interface PublicChannelAnswer {
    /** Queued on the board; the own row is written under [rowId]. */
    data class Queued(
        val rowId: String,
    ) : PublicChannelAnswer

    /** The board would not take it, for [reason]. Nothing stored. */
    data class Refused(
        val reason: PublicPostRefusal,
    ) : PublicChannelAnswer
}

/**
 * A MeshCore room post the board queued and then gave up without airing (`LoraMeshTransport.publicPostsEnded`, ADR
 * 2026-11.gsqu's Stage 6 amendment), by its own row's [rowId]. [neverSent] is the plane's `STALE`: it waited past its
 * freshness at the write and was never handed to the radio. Otherwise it ended at the write — refused there, or the
 * write's answer never came — and **may** have aired: it is worded so, and nothing ever resends it.
 */
data class PublicPostEnd(
    val rowId: String,
    val neverSent: Boolean,
)

/** What became of a post typed in a bridged board room ([MeshController.sendPublicPost]). */
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
