package app.getknit.knit.mesh

/**
 * The peer resets our replacement floor refused, one per peer, held whole until the floor ends (ADR 2026-11.sfms, #251).
 *
 * A peer's `FLAG_RESET` init that lands inside the one-minute floor after the last replacement we adopted from it
 * (`RatchetSessions.RESET_REPLACEMENT_MIN_INTERVAL_MS`, ADR 023) is refused, and refused for good unless somebody
 * brings it back: the router marked its id seen before the decrypt, custody stored it and our digest now advertises it,
 * so no neighbour re-pushes it; it is a ctl with no message row, so nothing re-seals it; and the resetter, unconfirmed,
 * has its own heuristic floored for six hours. The engine names that refusal (`RatchetEngine.OpenOutcome.Failed.RESET_FLOORED`,
 * only for an era we never held), `InboundPipeline.onRatchetFailure` holds the frame here, and `MeshManager`'s 60 s tick
 * re-delivers it once the floor is open.
 *
 * The frame is held whole — its [HeldFrame.wire] and envelope, as `PendingGroupKeys` holds a seed — never by id: custody's
 * quota, its byte cap or a `relay = false` delivery may never have kept a copy to read back. Only a signed frame is
 * held (the flag is not in a v2 frame's AEAD; the signature is what makes it the peer's). One entry per peer, the
 * newest by the sender's `sentAt` winning: a later reset of the same peer supersedes an earlier one, which it replaced
 * on the sender's side too.
 *
 * Each entry carries the [Held.generation] of the peer's session as the open that refused it began
 * (`RatchetSessions.rootGeneration`). Any root change since — an adoption from that peer, a race, our own re-root, a
 * forget — means the init may name a root the peer has left, and re-opening it would split the pair: the entry is
 * dropped, never re-run ([valid]). While an entry stands, the follow-up frames the resetter seals under its reset
 * carry that reset's init and fail here (an unflagged init inside the incidental floor); they are explained, never
 * reset evidence ([explains]), and the re-open replays them from custody.
 *
 * In memory like the floor it waits on: a restart forgets both, and with them the re-open. That residue is accepted
 * (the ADR). Pure; every method is `@Synchronized`.
 */
class FlooredResets {
    /** One held reset: the frame, its init's ephemeral (what a successful re-open leaves anchored), the era it was refused in. */
    class Held(
        val frame: HeldFrame,
        val initEph: ByteArray,
        val generation: Long,
    )

    private val byPeer = HashMap<String, Held>()

    /**
     * Holds [held] for [peerId] unless the entry already there was sent later (the sender's `sentAt`; an equal stamp
     * replaces, so a re-delivery of the same frame refreshes its generation). Returns whether it holds [held] now.
     */
    @Synchronized
    fun hold(
        peerId: String,
        held: Held,
    ): Boolean {
        val current = byPeer[peerId]
        if (current != null && current.frame.env.sentAt > held.frame.env.sentAt) return false
        byPeer[peerId] = held
        return true
    }

    /** The entry for [peerId], if any. */
    @Synchronized
    fun get(peerId: String): Held? = byPeer[peerId]

    /** Every peer with an entry, a snapshot. */
    @Synchronized
    fun peers(): List<String> = byPeer.keys.toList()

    /** Removes [held] for [peerId] only if it is still the entry there: a newer hold since keeps its own. */
    @Synchronized
    fun take(
        peerId: String,
        held: Held,
    ): Boolean {
        if (byPeer[peerId] !== held) return false
        byPeer.remove(peerId)
        return true
    }

    /**
     * Whether [peerId]'s entry, if any, still stands at [generation] (the peer's session now); a stale one is dropped
     * here. Null when there is none, or none any more.
     */
    @Synchronized
    fun valid(
        peerId: String,
        generation: Long,
    ): Held? {
        val held = byPeer[peerId] ?: return null
        if (held.generation == generation) return held
        byPeer.remove(peerId)
        return null
    }

    /**
     * Whether a frame from [peerId] that failed to open while carrying the init [initEph] is explained by the reset held
     * for that peer at [generation]: the resetter's own follow-up, sealed under the root we have not adopted yet. Never
     * reset evidence while it stands.
     */
    fun explains(
        peerId: String,
        initEph: ByteArray?,
        generation: Long,
    ): Boolean = initEph != null && valid(peerId, generation)?.initEph?.contentEquals(initEph) == true
}
