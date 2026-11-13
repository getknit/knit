package app.getknit.knit.mesh.crypto

/**
 * Keeps every copy of one sealed profile version one length (ADR 2026-11.gsqu, Stage 4 review). The MeshCore key goes to
 * direct contacts only, so without this a keyed copy is 35 bytes longer on the compact layout (58 on the named one) than
 * the copies every other confirmed session gets, and every relay, radio and carrier that sees the burst of `CHAT` frames,
 * each naming its recipient, could tell contacts from strangers, and date an accept or a block by a copy that grew or
 * shrank. A keyless copy carries [MessageContent.pad] instead, sized so its sealed plaintext, in the layout the
 * recipient's seal picks ([sealBytes]), is exactly as long as the keyed one. The ciphertext adds the same tag to both;
 * the ratchet header around it varies by a byte or two per DM, as every DM's does.
 *
 * Sized by encoding rather than by arithmetic ([PresenceKeyFrame]'s way): two layouts, map counts and CBOR's two header
 * widths each move the answer, and the encoder that seals is the one that measures. Where CBOR cannot spell the exact gap
 * (a byte-string header grows from one byte to two at 24), the pad lands one byte long, never short. Pure.
 */
internal object MeshcoreProfilePad {
    /** Far past the gap any key leaves: 35 B compact, 58 B named. */
    private const val MAX_PAD = 128

    /** [keyless] padded to [keyed]'s sealed length in the layout [v3] picks; [keyless] itself if no pad is needed. */
    fun padded(
        keyless: MessageContent,
        keyed: MessageContent,
        v3: Boolean,
    ): MessageContent {
        val target = keyed.sealBytes(v3)
        val bare = keyless.sealBytes(v3)
        // Two layouts never compare: a content the compact codec refuses seals named (it cannot happen for a checked key).
        if (bare.scheme != target.scheme || bare.plaintext.size >= target.plaintext.size) return keyless
        return (0..MAX_PAD)
            .asSequence()
            .map { keyless.copy(pad = it) }
            .firstOrNull { it.sealBytes(v3).plaintext.size >= target.plaintext.size } ?: keyless
    }
}
