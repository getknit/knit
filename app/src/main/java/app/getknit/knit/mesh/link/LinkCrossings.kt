package app.getknit.knit.mesh.link

import app.getknit.knit.mesh.SeenSet
import java.util.concurrent.ConcurrentHashMap

/**
 * Which frames have already crossed which link, in either direction, so a frame is written to one L2CAP
 * stream at most once.
 *
 * Two paths hand the Bluetooth plane the same frame for the same link: the router's flood copy
 * (`MeshRouter` → `CompositeMeshTransport.send`) and the fast path's link copy (`fastFanout`, the immediate
 * one), and a relayed frame's fast copy also goes back over the link it arrived on. The receiver's `SeenSet`
 * drops every copy after the first, so the second write buys nothing but airtime. This memo lets the
 * transport skip it.
 *
 * **The invariant that keeps this a byte saving and not a delivery change:** the window is the router
 * `SeenSet`'s ([SeenSet.DEFAULT_TTL_MS]), and a frame is marked on the way *in* as well as out, so anything
 * the memo suppresses is a frame the far end has already handed its router inside a window where the router
 * would drop it again. It is keyed per link and forgotten with the link ([forget]): a fresh link to the same
 * peer starts clean, because the peer may have restarted with an empty `SeenSet` and a wiped store — the
 * custody re-serve after its digest exchange must still get through.
 *
 * Android-free and shared with the lab's `LabTransport`, which is how the box stays the Bluetooth plane as
 * shipped.
 */
class LinkCrossings(
    private val ttlMillis: Long = SeenSet.DEFAULT_TTL_MS,
    private val maxPerLink: Int = MAX_PER_LINK,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val perLink = ConcurrentHashMap<String, SeenSet>()

    // Orders [firstCrossingIf]'s read of its link with [forget], so a mark judged for a link that was current lands
    // before the forget that follows its end, never after it in the next link's memo.
    private val lock = Any()

    /**
     * Records that [key] crosses the link to [peerId] now. True the first time inside the window — write
     * it; false for a repeat — skip it, the far end has it.
     */
    fun firstCrossing(
        peerId: String,
        key: String,
    ): Boolean = perLink.computeIfAbsent(peerId) { SeenSet(maxPerLink, ttlMillis, clock) }.add(key)

    /**
     * [firstCrossing], only while [current] says the link that carried [key] is still the one held for [peerId]; false,
     * and nothing marked, once it is not. For a mark made by a link that may have ended or been replaced meanwhile (a
     * Bluetooth reader that waited for room, #331, ADR 2026-11.43f7): [current] is read under the lock [forget] takes,
     * so its owner, which takes the link out of its table before it forgets the peer, never finds a stale mark in the
     * memo it starts the next link on.
     */
    fun firstCrossingIf(
        peerId: String,
        key: String,
        current: () -> Boolean,
    ): Boolean = synchronized(lock) { current() && firstCrossing(peerId, key) }

    /** The link to [peerId] went down (or came up anew): whatever crossed it no longer counts. */
    fun forget(peerId: String) {
        synchronized(lock) { perLink.remove(peerId) }
    }

    fun clear() = perLink.clear()

    companion object {
        /** Per link: comfortably above the custody cap plus a busy room's minute, tiny either way. */
        const val MAX_PER_LINK = 1024
    }
}
