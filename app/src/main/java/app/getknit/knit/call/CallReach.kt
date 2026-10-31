package app.getknit.knit.call

/**
 * Whether anything this phone can see could carry a call's signals to a peer now (docs/VOICE_CALLS.md §3.3, C-3.3-5):
 * the seam [CallManager] asks while an outgoing call has had no RINGING back, so a call to a peer nothing reaches says
 * so and ends `no_route` instead of ringing out its 45 s. A read of state already in memory, never a wait on the
 * network. [Assumed] is a build or a test with no view of the mesh: every peer reads as reachable, as before.
 */
fun interface CallReach {
    suspend fun reachable(peerId: String): Boolean

    companion object {
        val Assumed = CallReach { true }
    }
}

/** The evidence [CallReach] reads (C-3.3-5). Pure (`CallReachTest`). */
object CallReachRule {
    /**
     * [peerId] is reachable when it is a live [neighbours] link of this phone's; or its own frames came through other
     * phones lately ([heardIndirectly]) and this phone has a link for a flood to start on; or a Wi-Fi Aware sighting a
     * link can be raised on ([sighted]); or its own recent traffic reached a connected spool we share a scope on
     * ([spoolPresent], `spoolPresentPeers`, so a relay the peer is not on never counts).
     *
     * A LoRa board's reach is not evidence: the plane carries one-packet room posts and compact DMs only, never a
     * call's OFFER or a walkie chunk.
     */
    fun reachable(
        peerId: String,
        neighbours: Set<String>,
        heardIndirectly: Set<String>,
        sighted: Boolean,
        spoolPresent: Set<String>,
    ): Boolean =
        peerId in neighbours ||
            (neighbours.isNotEmpty() && peerId in heardIndirectly) ||
            sighted ||
            peerId in spoolPresent
}
