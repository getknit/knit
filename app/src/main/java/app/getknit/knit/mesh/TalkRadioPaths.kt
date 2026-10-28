package app.getknit.knit.mesh

import app.getknit.knit.mesh.spool.ScopeSync

/**
 * The radio evidence a walkie chunk's send decides on (docs/VOICE_CALLS.md C-3.11-6, ADR 2026-10.79yb's tenth
 * amendment): which peers' own frames reached this phone over the radios lately, and through which link. A chunk to a
 * peer that is not a neighbour stays on the radios only while that peer was heard through a link of ours within
 * [windowMs]; past it, or once that link is gone, the chunk is cast through a relay beside its flood.
 *
 * Deliberately not [RelayedPresence], whose 15-minute linger is a statement to a reader of Diagnostics: a field call
 * on 2026-10-18 flooded every chunk into a neighbour that had lost the peer twenty minutes before, because that linger
 * was read as a route. This window is short and its stamp names the link, so a pocket that walked off stops counting
 * within [windowMs] and a link that dropped stops counting at once.
 *
 * Fed with two kinds of proof that the peer's own bytes crossed the radio: a signed frame [InboundPipeline] verified,
 * and a `talk` frame its ratchet opened (unsigned by design, authenticated by the open). A spool's frame and a LoRa
 * one never count, and a frame a neighbour passed on from a spool counts only until [windowMs] lapses — the walkie's
 * own reports (`TalkRoute`) catch a radio path that carries nothing.
 *
 * Read only by the walkie's send path and the call screen's label of it. Pure (`TalkRadioPathsTest`).
 */
class TalkRadioPaths(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val windowMs: Long = WINDOW_MS,
    private val cap: Int = CAP,
) {
    private class Heard(
        val at: Long,
        val via: String,
    )

    private val lock = Any()
    private val heard = LinkedHashMap<String, Heard>()

    /**
     * [authorId]'s own frame, stamped [sentAt] by its author, reached this phone from the link to [hopId] over [kind].
     * A frame older than the window says nothing about now: custody re-serves a peer's frames to every new link for
     * hours after the peer left.
     */
    fun note(
        authorId: String,
        hopId: String,
        kind: TransportKind,
        sentAt: Long,
    ) {
        if (kind == TransportKind.LoRa || kind == TransportKind.DirectLink || ScopeSync.isSpoolSource(hopId)) return
        // One reading for the check and the stamp, so a frame judged fresh is never stamped by a later clock.
        val now = clock()
        if (now - sentAt > windowMs) return
        synchronized(lock) {
            heard.remove(authorId)
            heard[authorId] = Heard(now, hopId)
            // Insertion order is recency order, so the first entry is the stalest.
            while (heard.size > cap) heard.remove(heard.keys.first())
        }
    }

    /** Whether [peerId] was heard through one of [linkIds] within the window. */
    fun pathTo(
        peerId: String,
        linkIds: Set<String>,
    ): Boolean {
        val h = synchronized(lock) { heard[peerId] } ?: return false
        return h.via in linkIds && clock() - h.at <= windowMs
    }

    /** Forgets everything: a stopped mesh carries nothing. */
    fun clear() {
        synchronized(lock) { heard.clear() }
    }

    companion object {
        /**
         * How long one frame of the peer's over the radios keeps its path: two minutes. A two-hop walkie session
         * refreshes it with every burst and report the peer sends, so it lapses only when the peer has gone quiet
         * for that long, and then a chunk going out also goes through a relay until the peer is heard again.
         */
        const val WINDOW_MS = 2 * 60_000L

        /** Most peers held at once; a call needs one. */
        const val CAP = 64
    }
}
