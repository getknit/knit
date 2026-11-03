package app.getknit.knit.mesh.crypto

import app.getknit.knit.mesh.protocol.PresenceKeyPayload

/**
 * The plaintext of a `CTL_PRESENCE_KEY` frame (ADR 2026-11.xp6d), padded to one size. Bluetooth and Wi-Fi Aware links
 * are readable to a listener in radio range until links are encrypted (#286), so a frame that carried the next key and
 * one that did not must not differ in length (security 1): [content] fills [PresenceKeyPayload.pad] until the named
 * plaintext is exactly [PLAINTEXT_BYTES] long, the length of a 120-character text DM in the same named layout. The
 * envelope around it varies by a byte or two with the ratchet header's numbers, as every DM's does.
 *
 * What the padding buys, and what not: a ctl 11 travels `relay = false`, so on a cleartext link a listener compares it
 * with the other point-to-point sealed frames (the live-link ticks), not with flooded text DMs; one size hides whether the
 * next key rode along, never that a v2 point-to-point frame of this size crossed the link (links are encrypted in #286).
 *
 * An iOS port pads to the same length: the size is what a listener compares, so both platforms must agree on it.
 */
object PresenceKeyFrame {
    /** Every ctl 11 plaintext is this long (the named `MessageContent` layout, before the AEAD's tag). */
    const val PLAINTEXT_BYTES = 128

    // CBOR: the pad's text key "p" (one header byte and one letter) and a byte string's header — one byte below 24
    // bytes, two below 256.
    private const val PAD_KEY_BYTES = 2
    private const val SHORT_HEADER_MAX = 23
    private const val MAX_PAD = 255

    /**
     * The padded content handing over [keys] — one key, or two with the higher generation as the next pair — lowest
     * generation first. A pad that cannot land exactly (one gap length CBOR cannot spell: a 23-byte pad's header is one
     * byte and a 24-byte pad's two) lands one byte long; only a generation of fewer than nine encoded bytes can make that
     * gap, and our own generations are a millisecond clock, nine bytes each.
     */
    fun content(keys: List<Pair<Long, ByteArray>>): MessageContent {
        require(keys.size in 1..2) { "a presence-key frame carries one or two keys" }
        val (generation, key) = keys[0]
        val next = keys.getOrNull(1)
        val bare =
            PresenceKeyPayload(generation = generation, key = key, nextGeneration = next?.first, nextKey = next?.second)
        val unpadded = MessageContent(body = "", ctl = MessageContent.CTL_PRESENCE_KEY, pk = bare).encode().size
        val gap = PLAINTEXT_BYTES - unpadded - PAD_KEY_BYTES
        val padLength =
            when {
                gap < 1 -> null

                // already at the size (a payload this build never makes): no pad field at all
                gap - 1 <= SHORT_HEADER_MAX -> gap - 1

                gap - 2 > SHORT_HEADER_MAX -> gap - 2

                else -> SHORT_HEADER_MAX + 1 // the one length CBOR cannot spell: a byte over, never under
            }?.coerceAtMost(MAX_PAD)
        return MessageContent(
            body = "",
            ctl = MessageContent.CTL_PRESENCE_KEY,
            pk =
                PresenceKeyPayload(
                    generation = generation,
                    key = key,
                    nextGeneration = next?.first,
                    nextKey = next?.second,
                    pad = padLength?.let(::ByteArray),
                ),
        )
    }
}
