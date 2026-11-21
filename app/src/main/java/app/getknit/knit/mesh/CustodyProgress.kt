package app.getknit.knit.mesh

/**
 * What custody can tell a plane about one peer's exchanges, for a hold-off keyed on progress rather than on two equal
 * digests (the Wi-Fi Aware hold-off, android order step 8): full stores never show equal digests again, so a throttle that
 * resets only on those would hold every full pair at its ceiling. Every read is O(1), never suspends and never calls back,
 * so a transport may ask it from its own lock.
 *
 * Every mark is a stamp off one counter shared by all peers, so a peer whose word custody dropped (its memo evicted, or
 * a restart) reads lower than before, never higher: compare a later mark with `>`, and "not higher" means nothing moved.
 * A peer custody keeps no word for (an unproven link, which leaves nothing behind, or one never seen) reads 0 on every
 * mark and never [fresh], which is today's throttle. [NONE] answers that for everyone, so a plane left unwired keeps it.
 */
interface CustodyProgress {
    /**
     * Moves when custody keeps a frame that arrived from [peer] over a proven link, or a later digest of [peer]'s judges a
     * release to it kept. A bare release never moves it.
     */
    fun progressMark(peer: String): Long = 0

    /**
     * Starts watching [peer] after an exchange that moved nothing: [fresh] turns true once custody newly keeps a live frame
     * that [peer]'s last digest lacked, that is neither pending nor marked not kept for it, and that did not arrive from it.
     */
    fun watchFresh(peer: String) = Unit

    /** Whether the watch [watchFresh] started has seen such a frame since. */
    fun fresh(peer: String): Boolean = false

    /** Moves each time custody takes a custody digest from [peer] over a proven link: whether an exchange carried one. */
    fun digestSeen(peer: String): Long = 0

    /**
     * The plane that carries custody's paced answer to [peer] (`ForwardSync`'s, §2), or null when none is live: the plane
     * its last round went over ([MeshTransport.carrier]), or before its first round the plane that held the peer when its
     * digest came. While it names a plane, that plane holds its link to [peer] not idle between rounds; another plane's
     * link is not held for it. Lock-free.
     */
    fun answerCarrier(peer: String): TransportKind? = null

    /**
     * [peer]'s [plane] path ended while [answerCarrier] named [plane] for a live answer to it (its window ran out, or the link was
     * lost): what was pending on that plane ends unjudged, since it may never have crossed (§6, `CarryLedger.answerCut`). A
     * path that closed at quiescence never calls this.
     */
    fun answerCut(
        peer: String,
        plane: TransportKind,
    ) = Unit

    companion object {
        /** Never moved, never fresh, no answer live: the throttle a plane runs today. */
        val NONE: CustodyProgress = object : CustodyProgress {}
    }
}
