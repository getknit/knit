package app.getknit.knit.mesh.lora

/**
 * How long a draft may be before it risks not riding the LoRa hop — the composer's "long message" hint (ADR 040). Both
 * budgets are now **one packet** at the MTU-255 floor: a room post rides only in one (ADR 2026-10.rp3s), and a DM rides
 * only as one compact pair-sealed unicast (ADR 2026-10.x9fa) — a PKI packet, which firmware 2.8 caps at 219 B (its
 * PKC overhead under the 228-B floor's `want_ack` write), less the 51 bytes of header and GCM tag around its
 * plaintext, which leaves 168 B for the v3 compact content (the PKI amendment; 175 B before). Everything
 * above the body is fixed cost, so a body budget is the honest way to say it before the send. The budgets are pinned by
 * `CoordinationPlaneSizeBudgetTest`, which builds real content at exactly these sizes with incompressible text, so the
 * hint never over-promises; a longer draft is not carried over LoRa at all (the signed copy still travels the radios,
 * custody and the spool). The reserves are what a quoted reply (two ids, an author, a 120-char snippet) and an
 * attachment reference (hash + key + MIME) add inside the same frame — both larger than a one-packet budget leaves, so a
 * reply or an attachment never rides LoRa. Pure — the composer calls it per keystroke.
 */
object LoraSizeHint {
    /**
     * A plaintext Nearby-room post, which rides LoRa only in one packet (ADR 2026-10.rp3s): what one 228-B packet holds
     * of incompressible text, with a few bytes' margin (measured 109 B of random printable ASCII, 2026-10-29). Prose
     * deflates, so a 150–250 B post often fits one packet too: for it the hint errs toward warning, which is why its
     * words are a hedge. A reply or an attachment reserve is larger than this, so a room reply or attachment is never
     * carried at all.
     */
    const val ROOM_BODY_BYTES = 100

    /**
     * A DM's body **and** the inline acks it may carry (`MeshManager.inlineAcksFor` spends what the body leaves, at
     * `INLINE_ACK_BYTES` each), as compact content in one PKI packet at the MTU-255 floor (ADR 2026-10.x9fa and its
     * PKI amendment): 168 B of plaintext, less the v3 layout's field headers, with a few bytes' margin. Measured with
     * random printable ASCII and random four-byte emoji, with and without the full four inline acks: a 160-B body is
     * 164 B of plaintext (2026-10-30, `CoordinationPlaneSizeBudgetTest -i`), so the 12 B the PKI reserve took came out
     * of the old margin and the budget stands. No X3DH init rides the compact form, so the first DM of a session costs
     * what the hundredth does.
     */
    const val DM_BODY_BYTES = 160

    /** What a quoted reply costs beside the body (`ReplyRef`: message id, author id, name, 120-char snippet). */
    const val REPLY_RESERVE_BYTES = 260

    /** What an attachment reference costs beside the body (content hash, sealed key, MIME). */
    const val ATTACHMENT_RESERVE_BYTES = 170

    /** The body bytes left of [base] once a reply and/or an attachment ride along; never negative. */
    fun budget(
        base: Int,
        replying: Boolean,
        attached: Boolean,
    ): Int =
        (base - (if (replying) REPLY_RESERVE_BYTES else 0) - (if (attached) ATTACHMENT_RESERVE_BYTES else 0))
            .coerceAtLeast(0)

    /** The UTF-8 length of [text] without encoding it (surrogate pairs count 4, a lone surrogate 3). */
    fun utf8Length(text: CharSequence): Int {
        var bytes = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            bytes +=
                when {
                    c.code < ONE_BYTE_MAX -> {
                        1
                    }

                    c.code < TWO_BYTE_MAX -> {
                        2
                    }

                    c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate() -> {
                        i++
                        PAIR_BYTES
                    }

                    else -> {
                        BMP_BYTES
                    }
                }
            i++
        }
        return bytes
    }

    private const val ONE_BYTE_MAX = 0x80
    private const val TWO_BYTE_MAX = 0x800

    /** A supplementary code point (a surrogate pair) encodes as four bytes; any other char above U+07FF as three. */
    private const val PAIR_BYTES = 4
    private const val BMP_BYTES = 3
}
