package app.getknit.knit.mesh.link

import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.protocol.Protocol
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Who must prove the node id its link HELLO claims, shared by every link plane and the inbound pipeline (ADR
 * 2026-11.264w, knit-next #232). One per process.
 *
 * A node id is **proof-capable** to this phone when its stored profile carries [Protocol.CAP_LINK_PROOF] (the profile is
 * signed by its author, so nobody else can strip the bit) or when a link has proven that id since the app started. On
 * every plane:
 *
 * - **R1.** An unproven HELLO claiming a proof-capable id is refused ([refused]).
 * - **R2.** A proven link is replaced only by a link that proves the same id, under the plane's own replace rules.
 * - **R3.** A link that proves X displaces every live unproven link claiming X, on every plane ([proven] tells them).
 * - **R4.** When an id becomes proof-capable, every live unproven link claiming it is closed ([claimClosed]).
 *
 * [isProofCapable] is memory only, so an admission never waits on the database: [seed] loads the profile half at mesh
 * start, and [profileCapabilities] follows every stored profile after it. A plane that admitted a claim before the seed
 * landed is told to close it when the seed does, exactly as R4 closes one admitted before a profile arrived.
 *
 * The counters (`linksProven`, `linkProofFailed`, `linkClaimRefused`, the names knit-ios reports) and the one log line
 * per refusal live here, so every plane counts and logs a case the same way. A line names the plane, the claimed id and
 * the reason, never a key or a signature. Pure, so it is a JVM test ([app.getknit.knit.mesh.link.LinkClaimsTest]).
 */
class LinkClaims(
    private val metrics: MeshMetrics,
    private val log: (String) -> Unit = {},
) {
    private val profiled = ConcurrentHashMap.newKeySet<String>()

    // Ids proven this run, oldest first, at most [MAX_PROVEN]: fresh identities are free, and each one that proves itself
    // would otherwise hold an entry for the life of the process. Guarded by itself.
    private val provenThisRun = LinkedHashSet<String>()
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val releaseListeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val displaceListeners = CopyOnWriteArrayList<(String) -> Unit>()

    /** Whether an unproven HELLO claiming [nodeId] must be refused (R1). Never reads the database. */
    fun isProofCapable(nodeId: String): Boolean = nodeId in profiled || isProvenThisRun(nodeId)

    /**
     * A link that proved [nodeId] was registered on [plane]: counts it, makes the id proof-capable for the rest of the
     * run, and tells every plane, so each closes any unproven link it holds claiming the id (R3). Called after the link
     * is in its plane's table, so that plane's own listener finds the proven link there and leaves it. [displaced] says
     * the registration itself replaced an unproven link claiming the id (R3 on its own plane): that link is counted under
     * `linkClaimRefused` here, once, since no listener ever sees it (contract revision 6), and see [onDisplaced].
     */
    fun proven(
        nodeId: String,
        plane: String,
        displaced: Boolean = false,
    ) {
        metrics.onLinkProven()
        if (displaced) metrics.onLinkClaimRefused()
        synchronized(provenThisRun) {
            provenThisRun.remove(nodeId) // re-added as the newest
            provenThisRun.add(nodeId)
            if (provenThisRun.size > MAX_PROVEN) provenThisRun.remove(provenThisRun.first())
        }
        log("link proven: $plane $nodeId${if (displaced) " (displaced an unproven link)" else ""}")
        tell(nodeId)
        if (displaced) for (listener in displaceListeners) listener(nodeId)
    }

    /**
     * The stored profile row for [nodeId] now holds [capabilities] (`InboundPipeline.storeProfileRow`, after its
     * upsert). A gain of the bit tells every plane (R4). A newer profile without it (a downgraded install) takes the id
     * out of the profile half; a proof this run still counts.
     */
    fun profileCapabilities(
        nodeId: String,
        capabilities: Long?,
    ) {
        if (capabilities != null && capabilities and Protocol.CAP_LINK_PROOF != 0L) {
            if (profiled.add(nodeId)) tell(nodeId)
        } else if (profiled.remove(nodeId) && !isProvenThisRun(nodeId)) {
            for (listener in releaseListeners) listener(nodeId)
        }
    }

    /** At mesh start: the ids whose stored profile carries the bit. Each new one tells every plane (R4). */
    fun seed(nodeIds: Collection<String>) {
        for (id in nodeIds) if (profiled.add(id)) tell(id)
    }

    /**
     * A plane's R3/R4 hook: [listener] gets each id that just became proof-capable or proved itself, and closes the live
     * unproven links it holds claiming that id, counting each through [claimClosed]. It is called on the caller's thread
     * (an inbound frame, a handshake), so it only schedules the close and never blocks. Close the result at plane stop.
     */
    fun onCapable(listener: (String) -> Unit): AutoCloseable {
        listeners.add(listener)
        return AutoCloseable { listeners.remove(listener) }
    }

    /**
     * [listener] gets each id that stopped being proof-capable (its stored profile lost the bit: a downgraded install),
     * so a plane can lift a refusal's backoff it keyed on the id. Called on the caller's thread; never blocks.
     */
    fun onReleased(listener: (String) -> Unit): AutoCloseable {
        releaseListeners.add(listener)
        return AutoCloseable { releaseListeners.remove(listener) }
    }

    /**
     * [listener] gets each id whose unproven link a proof just displaced: the proven link took its place on the same
     * plane ([proven] with `displaced`), or a plane closed one for an id proven this run ([claimClosed]). The neighbour
     * set keys on the id, so it never shows that swap; the mesh re-runs the id's link-up work on it (the real peer's
     * profile push, digest, owed ticks and blob asks, none of which the squatter could take for it). Called on the
     * caller's thread; never blocks.
     */
    fun onDisplaced(listener: (String) -> Unit): AutoCloseable {
        displaceListeners.add(listener)
        return AutoCloseable { displaceListeners.remove(listener) }
    }

    /** R1: an unproven HELLO claiming [nodeId] was refused on [plane] for [why]. */
    fun refused(
        nodeId: String,
        plane: String,
        why: String,
    ) {
        metrics.onLinkClaimRefused()
        log("link claim refused: $plane $nodeId ($why)")
    }

    /**
     * R3 or R4: a live unproven link claiming [nodeId] on [plane] was closed, the id having proved itself or become
     * proof-capable. A link is counted once, on the plane that closes it; one a proven link replaced on its own plane is
     * counted by [proven] instead.
     */
    fun claimClosed(
        nodeId: String,
        plane: String,
    ) {
        metrics.onLinkClaimRefused()
        log("link claim closed: $plane $nodeId (proof-capable)")
        if (isProvenThisRun(nodeId)) for (listener in displaceListeners) listener(nodeId)
    }

    /** A link's proof failed on [plane] for [why]; [nodeId] is the id its HELLO claimed, null when it named none. */
    fun proofFailed(
        nodeId: String?,
        plane: String,
        why: String,
    ) {
        metrics.onLinkProofFailed()
        log("link proof failed: $plane ${nodeId ?: "?"} ($why)")
    }

    private fun isProvenThisRun(nodeId: String): Boolean = synchronized(provenThisRun) { nodeId in provenThisRun }

    private fun tell(nodeId: String) {
        for (listener in listeners) listener(nodeId)
    }

    companion object {
        /**
         * How many ids proven this run are remembered. A peer we hold a profile for needs none of them (its profile
         * carries the bit); this keeps the rest bounded under a churn of fresh identities.
         */
        const val MAX_PROVEN = 4096
    }
}
