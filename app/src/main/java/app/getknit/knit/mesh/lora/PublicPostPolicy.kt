package app.getknit.knit.mesh.lora

/**
 * Turns a Knit user's post into the line of text that goes on the foreign mesh's public channel — the
 * LongFast bridge's outbound half, and the mirror of [LongFastPolicy]. Pure, so the whole rule is JVM-testable
 * against fabricated posts ([app.getknit.knit.mesh.lora.PublicPostPolicyTest]).
 *
 * **Deliberately not a filter.** [LongFastPolicy] exists to decide what may come *in*; everything that may go
 * *out* was typed by a person into a room labelled public, behind a consent sheet, and screened by the room
 * moderator on the way. What is left is a formatting question, and the two questions are kept apart because
 * conflating them is how a guard on one direction quietly starts governing the other.
 *
 * There is no `isStockPrimary` here on purpose: the transmit side calls [LongFastPolicy.isStockPrimary]
 * unchanged. Writing a Knit user's cleartext words into a renamed or re-keyed primary would put them in
 * somebody's private group, which is the same wrong as reading one — one rule, stated once, so the two cannot
 * drift apart.
 */
internal object PublicPostPolicy {
    /**
     * Meshtastic's client convention for a text message, in **bytes of UTF-8**.
     *
     * Not the wire cap. The router will carry a 237-byte `Data` ([MeshtasticProto.LORA_DATA_MAX]) and the
     * phone API a 231-byte payload ([MeshtasticProto.MAX_PAYLOAD]); every stock client nonetheless composes
     * against 200, so a post that stays under it is one every reader's app can quote, forward and reply to
     * without truncating it themselves.
     */
    const val MAX_ON_AIR_BYTES = 200

    /**
     * The line as it will appear on a stock client: `"Alex: hello"`, or the bare body when the author has no
     * display name.
     *
     * **The prefix is why this room needed a consent sheet.** ADR 049 keeps the user's name off the public
     * band — the board is `Knit abcd` to everyone listening, never the person — and this is the single,
     * deliberate exception to that, made per-room and per-user rather than by the board's standing broadcast.
     * Without it every Knit user behind one board is indistinguishable from every other, which on a channel
     * whose whole content is conversation makes the bridge useless in the direction that matters.
     *
     * Trimmed to [MAX_ON_AIR_BYTES] **on a codepoint boundary**: a byte-count cut through the middle of a
     * multi-byte character would put a replacement glyph on somebody else's screen, and an emoji is four bytes
     * of a 200-byte budget. The name is never what gets cut — a post trimmed to nothing but its author's name
     * says less than a truncated sentence does.
     */
    fun onAirText(
        name: String?,
        body: String,
    ): String {
        val prefix =
            name
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { "$it: " }
                .orEmpty()
        val room = MAX_ON_AIR_BYTES - LoraSizeHint.utf8Length(prefix)
        // A name long enough to leave no room for the body is not a post; drop the prefix rather than the words.
        if (room <= 0) return trimToUtf8(body, MAX_ON_AIR_BYTES)
        return prefix + trimToUtf8(body, room)
    }

    /**
     * [text] cut to at most [maxBytes] of UTF-8, never mid-character.
     *
     * Walks the string rather than encoding it and slicing the bytes back, so a surrogate pair is one
     * indivisible four-byte step and there is no window in which a half-character exists to be kept.
     */
    fun trimToUtf8(
        text: String,
        maxBytes: Int,
    ): String {
        if (LoraSizeHint.utf8Length(text) <= maxBytes) return text
        var bytes = 0
        var i = 0
        while (i < text.length) {
            val pair = text[i].isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()
            val step = LoraSizeHint.utf8Length(if (pair) text.substring(i, i + 2) else text.substring(i, i + 1))
            if (bytes + step > maxBytes) break
            bytes += step
            i += if (pair) 2 else 1
        }
        return text.substring(0, i)
    }
}

/**
 * Why a post typed in the bridged room did not reach the air from **this** device.
 *
 * A refusal is ordinary rather than an error: on a two-board pocket exactly one device transmits any given
 * post and the other refuses, and a boardless phone refuses every one. The counters exist so that "my post
 * went nowhere" has an answer on the device that was supposed to carry it.
 */
internal enum class PublicPostRefusal {
    /** No board, or its session is not up. */
    NOT_READY,

    /** Knit is bound to index 0 on this board, so there is no public primary here to post on. */
    KNIT_ON_PRIMARY,

    /** The primary is renamed or re-keyed — somebody's private group, and never ours to write into. */
    NOT_STOCK_PRIMARY,

    /** Inside the per-gateway floor: one post per 30 s, so a room cannot become a transmitter. */
    TOO_SOON,

    /** Longer than one packet carries even after trimming — should be unreachable, kept so it is visible. */
    TOO_LARGE,

    /** The public bucket's share of the rolling window is spent. */
    NO_AIR,

    /** The board or the mesh refused the packet. */
    NAK,
}
