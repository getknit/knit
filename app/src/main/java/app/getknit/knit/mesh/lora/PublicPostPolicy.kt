package app.getknit.knit.mesh.lora

/**
 * Turns a Knit user's post into the line of text that goes on the board's primary channel — the Meshtastic
 * room's outbound half, and the mirror of [PublicChannelPolicy]. Pure, so the whole rule is JVM-testable
 * against fabricated posts ([app.getknit.knit.mesh.lora.PublicPostPolicyTest]).
 *
 * **Deliberately not a filter.** [PublicChannelPolicy] exists to decide what may come *in*; everything that
 * may go *out* was typed by a person into a room labelled as a radio channel, behind a consent sheet, and
 * screened by the room moderator on the way. What is left is a formatting question, and the two questions
 * are kept apart because conflating them is how a guard on one direction quietly starts governing the other.
 */
internal object PublicPostPolicy {
    /**
     * Meshtastic's client convention for a text message, in **bytes of UTF-8**.
     *
     * Not the wire cap. The router will carry a 237-byte `Data` ([MeshtasticProto.LORA_DATA_MAX]) and the
     * phone API a 231-byte payload ([MeshtasticProto.MAX_PAYLOAD]); every stock client nonetheless composes
     * against 200, so a post that stays under it is one every reader's app can quote, forward and reply to
     * without truncating it themselves.
     *
     * The composer caps the draft at this figure too, and not only here, because [onAirText] trims
     * *silently*: a sentence cut in half would go out with nothing on the author's screen to say so.
     */
    const val MAX_ON_AIR_BYTES = 200

    /**
     * The most a `TEXT_MESSAGE_APP` post can carry and still be **signed** by a 2.8 board, in bytes of UTF-8
     * — [MeshtasticProto.maxSignedPayload] for the text port: 166, one more than a Knit frame's 165 because
     * the text portnum is a one-byte varint. Above it the firmware sends the post unsigned rather than
     * refusing it (`Router::perhapsEncode`), silently, which is why the composer is capped here on a board
     * that signs: a reader with the author's key in their contact's profile verifies every post, or the cap
     * is not doing its job.
     */
    val MAX_SIGNED_TEXT_BYTES: Int = MeshtasticProto.maxSignedPayload(MeshtasticProto.PORT_TEXT_MESSAGE)

    /**
     * The byte budget for a post through a board that does or does not sign ([signing] —
     * `LoraFacts.signs` at the composer, `LoraAirtime.signing` at the transport, the same fact): the
     * signable cap on a 2.8 board, the client convention on an older one. One rule for both ends, so the
     * composer never admits a byte the transport would trim.
     */
    fun onAirBudget(signing: Boolean): Int = if (signing) MAX_SIGNED_TEXT_BYTES else MAX_ON_AIR_BYTES

    /**
     * The line as a stock client will read it: the words that were typed, and nothing else.
     *
     * **No author name goes on the air** (ADR 2026-09.9469). The line used to open `"Alice: "` — ADR 049's
     * one deliberate exception to keeping the user's name off the public band — because the bridged design
     * put a whole pocket's posts on air through one gateway board, where nothing else told two Knit speakers
     * apart. Since ADR 2026-09.26q3 the room is a local mirror and each user posts through their **own**
     * board, so the board is the identity: a Knit reader lines the node number up with a contact
     * (`PeerRepository.findByLoraNode`) and a stock client shows the board's own `Knit abcd`. The name bought
     * nothing the node number does not, and cost a human name in cleartext on a frequency whose traffic
     * public MQTT servers archive.
     *
     * Trimmed to [maxBytes] — [onAirBudget] for the board at hand — **on a codepoint boundary**: a byte-count
     * cut through the middle of a multi-byte character would put a replacement glyph on somebody else's
     * screen, and an emoji is four bytes of a 200-byte budget.
     */
    fun onAirText(
        body: String,
        maxBytes: Int = MAX_ON_AIR_BYTES,
    ): String = trimToUtf8(body, maxBytes)

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
