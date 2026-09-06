package app.getknit.knit.mesh.lora

/**
 * A Meshtastic node's user-visible identity — the pair of names its own screen, every other radio's node
 * list and the Meshtastic app all show, and whether it admits to being read by anybody. Recorded before a
 * setup rewrites it so a restore can put the user's own back rather than guess.
 */
internal data class BoardOwner(
    val longName: String,
    val shortName: String,
    /**
     * `User.is_unmessagable` — the Meshtastic app's *Unmonitored or infrastructure* (ADR 2026-09.emd7).
     * False is what a stock board is and what an absent field reads as, so the default is the honest one
     * for every identity that was not deliberately marked.
     */
    val unmessagable: Boolean = false,
    /**
     * `User.public_key` — the node's Curve25519 key, base64 of the 32 raw bytes, or null when it published
     * none (or a malformed one). Base64 rather than bytes because this is a data class the `nodes` directory
     * holds and tests compare whole. For the bound board's own entry this is what the profile advertises
     * beside the node number, so a contact's phone can verify the posts 2.8 firmware signs for us.
     */
    val publicKey: String? = null,
) {
    /**
     * Whether this identity already *is* [want] in the three fields a `set_owner` write can carry — the two
     * names and the unmonitored mark. Deliberately not whole-object equality: [publicKey] is the board's
     * own, published by the firmware and never written by Knit, so comparing it would leave every board
     * that has one looking permanently unfinished — the setup screen offering an action that can never
     * complete, and every setup re-writing an identity the board already carries.
     */
    fun carries(want: BoardOwner): Boolean = longName == want.longName && shortName == want.shortName && unmessagable == want.unmessagable
}

/**
 * What a board set up for Knit calls itself (ADR 049).
 *
 * A stock board names itself `Meshtastic ab12` after the low two bytes of its node number; Knit keeps that
 * shape and swaps the prefix, so a board on the Knit channel is recognisable at a glance from any other
 * radio's node list without two boards in one pocket becoming indistinguishable. The short name — the
 * 4-character tag the small screens actually have room for — is exactly `Knit`, which is the whole of
 * Meshtastic's `short_name` budget (`char[5]`, one byte of it the terminator).
 *
 * Deliberately **not** the user's display name: a `NodeInfo` is cleartext on the public frequency, and the
 * plane's standing metadata cost (`context/lora-bridge.md`) is already the most this should leak.
 *
 * The identity also carries [BoardOwner.unmessagable] (ADR 2026-09.emd7) — see [honoursUnmessagable].
 *
 * Pure policy over strings, so a test can read both directions.
 */
internal object BoardName {
    /** The long-name prefix; the rest is [suffix], as the firmware's own default does it. */
    const val PREFIX = "Knit"

    /** The short name, in full: four characters is the entire `short_name` field, and `Knit` is four. */
    const val SHORT = "Knit"

    /** The firmware's own default prefix, for a restore with no recorded name to put back. */
    const val STOCK_PREFIX = "Meshtastic"

    /**
     * The identity Knit writes to the board with node number [nodeNum], running [firmware] — its
     * `DeviceMetadata.firmware_version`, which decides only whether the unmonitored mark is part of it
     * ([honoursUnmessagable]).
     */
    fun forNode(
        nodeNum: UInt,
        firmware: String?,
    ): BoardOwner =
        BoardOwner(
            longName = "$PREFIX ${suffix(nodeNum)}",
            shortName = SHORT,
            unmessagable = honoursUnmessagable(firmware),
        )

    /**
     * Whether [firmware] is new enough to store `User.is_unmessagable` — **2.6.9**, where the plumbing
     * landed (`AdminModule::handleSetOwner`, firmware `2e72850d`, released 2025-05-25); 2.6.8 and older
     * drop the field as an unknown one and never echo it back.
     *
     * A version this cannot read counts as **too old**, the opposite of [LoraAirtime.signsPackets]'s
     * reading of the same string, because the costs are the opposite way round. There, guessing wrong
     * spends airtime the board did not need; here, guessing wrong means writing to somebody's hardware on
     * a hunch, and — since a board that drops the field never reports it back — leaving Knit's setup
     * permanently, visibly unfinished on a board that is in fact fine.
     */
    fun honoursUnmessagable(firmware: String?): Boolean {
        val parts = firmware?.trim()?.split('.') ?: return false
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return false
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: return false
        val patch = parts.getOrNull(2)?.toIntOrNull() ?: return false
        if (major != UNMESSAGABLE_MAJOR) return major > UNMESSAGABLE_MAJOR
        if (minor != UNMESSAGABLE_MINOR) return minor > UNMESSAGABLE_MINOR
        return patch >= UNMESSAGABLE_PATCH
    }

    /**
     * The name the firmware would have given this board itself (`NodeDB` builds both out of the last two
     * MAC bytes, which are the low half of [nodeNum]) — what a restore writes when the setup that renamed
     * the board recorded nothing, so an un-recorded board still ends up stock rather than left saying Knit.
     */
    fun stock(nodeNum: UInt): BoardOwner = BoardOwner(longName = "$STOCK_PREFIX ${suffix(nodeNum)}", shortName = suffix(nodeNum))

    /** The four lowercase hex digits both names end in: the low two bytes of [nodeNum]. */
    fun suffix(nodeNum: UInt): String = (nodeNum and SUFFIX_MASK).toInt().toString(HEX_RADIX).padStart(SUFFIX_CHARS, '0')

    /** The firmware release that started storing `User.is_unmessagable`: 2.6.9. */
    private const val UNMESSAGABLE_MAJOR = 2
    private const val UNMESSAGABLE_MINOR = 6
    private const val UNMESSAGABLE_PATCH = 9

    private const val SUFFIX_MASK = 0xFFFFu
    private const val SUFFIX_CHARS = 4
    private const val HEX_RADIX = 16
}
