package app.getknit.knit.mesh.crypto

/**
 * Keeps every copy of one sealed profile version one length (ADR 2026-11.gsqu, Stage 4 review). The MeshCore key and its
 * proof (`meshcoreKey`, `meshcoreSig`, Stage 5d) go to direct contacts only, so without this a keyed copy is 102
 * bytes longer on the compact layout (160 on the named one) than the copies every other confirmed session gets, and
 * every relay, radio and carrier that sees the burst of `CHAT` frames, each naming its recipient, could tell contacts from
 * strangers, and date an accept or a block by a copy that grew or shrank. A keyless copy carries [MessageContent.pad]
 * instead, sized so its sealed plaintext, in the layout the recipient's seal picks ([sealBytes]), is exactly as long as the
 * keyed one. The ciphertext adds the same tag to both; the ratchet header around it varies by a byte or two per DM, as
 * every DM's does.
 *
 * Sized by encoding rather than by arithmetic ([PresenceKeyFrame]'s way): two layouts, map counts and CBOR's two header
 * widths each move the answer, and the encoder that seals is the one that measures. Where CBOR cannot spell the exact gap
 * (a byte-string header grows from one byte to two at 24), the pad lands one byte long, never short.
 *
 * **A miss is loud** (Stage 5d, crypto B1): no pad up to [MAX_PAD] reaching the keyed length, or a keyed copy that seals in
 * the other layout, answers null, and the caller counts it (`MeshMetrics`' `sealedProfilePadMisses`) rather than send a
 * keyless copy of a telling length as if it were padded. Before 5d the bound was 128 and the named layout's keyed copy,
 * signature included, needs 156: the miss fell back to the bare keyless copy in silence. Pure.
 */
internal object MeshcoreProfilePad {
    /**
     * Past the widest gap a claim leaves: 102 B compact and 160 B named with the key and the signature, so a pad of
     * 156 there (99 compact). Kept below 256 so the pad's own byte-string header stays two bytes.
     */
    const val MAX_PAD = 255

    /**
     * [keyless] padded to [keyed]'s sealed length in the layout [v3] picks; [keyless] itself if no pad is needed; null when
     * no pad reaches it (a miss, which the caller must count and never hide).
     */
    fun padded(
        keyless: MessageContent,
        keyed: MessageContent,
        v3: Boolean,
    ): MessageContent? {
        val target = keyed.sealBytes(v3)
        val bare = keyless.sealBytes(v3)
        // Two layouts never compare: a content the compact codec refuses seals named (it cannot happen for a checked key).
        if (bare.scheme != target.scheme) return null
        if (bare.plaintext.size >= target.plaintext.size) return keyless
        return (0..MAX_PAD)
            .asSequence()
            .map { keyless.copy(pad = it) }
            .firstOrNull { it.sealBytes(v3).plaintext.size >= target.plaintext.size }
    }
}
