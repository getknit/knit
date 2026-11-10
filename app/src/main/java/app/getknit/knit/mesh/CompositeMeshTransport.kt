package app.getknit.knit.mesh

import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.time.TimeSource

/**
 * A [MeshTransport] that runs several sibling transports **simultaneously** behind the single-transport seam,
 * so `MeshManager`/`MeshRouter` and every collaborator stay unchanged. [children] are in descending
 * send-preference order — put Bluetooth (persistent, cheap links) before Wi-Fi Aware (ephemeral, one NDP at a
 * time), so a peer reachable over both is served over Bluetooth.
 *
 * How the interface members combine:
 * - [neighbors]/[reachable]: the union of the children's sets, **collapsed to one [Peer] per nodeId** (the
 *   richer advertised protoVersion/capabilities wins). Collapsing by nodeId is what makes
 *   `MeshManager.watchNeighbors` fire its newcomer hooks exactly once even when a peer appears on both planes.
 * - [health]: Healthy if **any** child is Healthy (the node can still mesh if one radio works); otherwise
 *   ForegroundOnly if a plane is up but discovers only on screen (the API 29-32 location app-op, ADR
 *   2026-09.535d), else Degraded if any plane is on-but-failing (seized), else Unavailable when every plane
 *   is switched off/absent. A plane that rides the Internet ([MeshTransport.viaInternet]) has no say: a contact's
 *   live direct link must not hide radios the user turned off.
 * - [inbound]/[incomingFiles]/[incomingDigests]: merged. A frame that arrives over both planes is de-duped
 *   downstream by `MeshRouter`'s `SeenSet` (10-min TTL), so simultaneous multi-path delivery is safe and free.
 * - [send]: for a specific peer, route to the preferred child holding a live link to it; for a broadcast
 *   (`to == null`), reach each merged neighbor once over its preferred child.
 * - [fastFanout]/[fastSend]: the coordination-plane fast path goes to children that have one ([hasFastPlane]:
 *   Wi-Fi Aware, Bluetooth with its side channel, LoRa); a link-based child without one instead gets a normal
 *   [send] over its persistent links, which already reaches every live neighbor at once.
 *
 * A 0- or 1-child list is handled gracefully (empty ⇒ inert + Degraded; single ⇒ transparent pass-through),
 * so DI can gate each plane on hardware support and hand over whatever is present.
 *
 * Because neighbors are collapsed by nodeId, a peer that is continuously reachable (e.g. a persistent Bluetooth
 * link, or a NAN→BT handoff that never leaves the merged set) fires `MeshManager.watchNeighbors`' newcomer
 * hooks only **once**. Store-and-forward convergence relies on the digest re-offer re-running (NAN gets that for
 * free from its flapping ephemeral links), so `MeshManager.reofferToNeighborsPeriodically` re-runs those hooks
 * on a timer for currently-linked neighbors — the anti-entropy a non-flapping link needs.
 */
class CompositeMeshTransport(
    private val children: List<MeshTransport>,
    private val scope: CoroutineScope,
    private val metrics: MeshMetrics? = null,
    // Injected logger (the class stays Android-free): DI passes android.util.Log; tests default silent.
    private val log: (String) -> Unit = {},
) : MeshTransport {
    override val hasFastPlane: Boolean = children.any { it.hasFastPlane }

    override val highThroughput: Boolean = children.any { it.highThroughput }

    override val neighbors: StateFlow<Set<Peer>> =
        if (children.isEmpty()) {
            MutableStateFlow(emptySet())
        } else {
            combine(children.map { it.neighbors }) { sets -> mergePeers(sets) }
                .stateIn(scope, SharingStarted.Eagerly, emptySet())
        }

    override val reachable: StateFlow<Set<Peer>> =
        if (children.isEmpty()) {
            MutableStateFlow(emptySet())
        } else {
            combine(children.map { it.reachable }) { sets -> mergePeers(sets) }
                .stateIn(scope, SharingStarted.Eagerly, emptySet())
        }

    /**
     * The [TransportKind]s of the short-range children — read off [MeshTransport.shortRange] rather than
     * restated, so a consumer telling a proximity plane from a long-range one can never drift from the flag
     * the transports themselves declare. Presentation only (the Diagnostics screen's radio tags); nothing
     * routes on it.
     */
    val shortRangeKinds: Set<TransportKind> = children.filter { it.shortRange }.mapTo(mutableSetOf()) { it.kind }

    /**
     * The merged [reachable] set restricted to **short-range** children (BLE/NAN) — the set that means the
     * peer's *own* radio was seen, so it is both "physically near" and "directly connected". Used by
     * [app.getknit.knit.mesh.spool.AttachmentDeferPolicy] (a LoRa-only sighting can't carry an image and must
     * not defer its upload) and by everything in the app that says *nearby* / *online*
     * ([MeshController.neighbors]). Falls back to the full [reachable] when every child is short-range (the
     * common two-radio case), so nothing changes for a LoRa-less build.
     */
    val shortRangeReachable: StateFlow<Set<Peer>> =
        run {
            val shortRangeChildren = children.filter { it.shortRange }
            when {
                shortRangeChildren.isEmpty() -> {
                    MutableStateFlow(emptySet())
                }

                shortRangeChildren.size == children.size -> {
                    reachable
                }

                else -> {
                    combine(shortRangeChildren.map { it.reachable }) { sets -> mergePeers(sets) }
                        .stateIn(scope, SharingStarted.Eagerly, emptySet())
                }
            }
        }

    /**
     * The merged [neighbors] restricted to **short-range** children: the peers a phone radio holds a live link to now,
     * never a LoRa board's (it has none) or a contact's direct Internet link. What *a radio path to them* means — the
     * walkie's label and route ([MeshManager.talkRadioPath]) and the hand-off credit of Your mesh — where [neighbors]
     * is what *a link to send on* means.
     */
    val shortRangeNeighbors: StateFlow<Set<Peer>> =
        run {
            val shortRangeChildren = children.filter { it.shortRange }
            when {
                shortRangeChildren.isEmpty() -> {
                    MutableStateFlow(emptySet())
                }

                shortRangeChildren.size == children.size -> {
                    neighbors
                }

                else -> {
                    combine(shortRangeChildren.map { it.neighbors }) { sets -> mergePeers(sets) }
                        .stateIn(scope, SharingStarted.Eagerly, emptySet())
                }
            }
        }

    /** Whether a phone radio holds a live link to [nodeId] now ([shortRangeNeighbors]). */
    fun shortRangeLinked(nodeId: String): Boolean = shortRangeNeighbors.value.any { it.nodeId == nodeId }

    override fun radioLinked(nodeId: String): Boolean = shortRangeLinked(nodeId)

    // The children whose health is the mesh's: every radio, never a plane that rides the Internet ([viaInternet]).
    private val healthChildren = children.filterNot { it.viaInternet }

    override val health: StateFlow<TransportHealth> =
        if (healthChildren.isEmpty()) {
            MutableStateFlow(TransportHealth.Degraded)
        } else {
            combine(healthChildren.map { it.health }) { hs ->
                when {
                    // The node can still mesh if any radio is up.
                    hs.any { it == TransportHealth.Healthy } -> TransportHealth.Healthy

                    // A radio that works whenever Knit is on screen outranks one that is failing: "open Knit"
                    // is a cure the user holds, "radio busy" is a wait.
                    hs.any { it == TransportHealth.ForegroundOnly } -> TransportHealth.ForegroundOnly

                    // At least one radio is on but failing (seized) — a fault, not a user-off state, so it
                    // outranks Unavailable: "radio busy" is the more informative message.
                    hs.any { it == TransportHealth.Degraded } -> TransportHealth.Degraded

                    // Every radio is switched off / absent — actionable "turn on Wi-Fi or Bluetooth".
                    else -> TransportHealth.Unavailable
                }
            }.stateIn(scope, SharingStarted.Eagerly, TransportHealth.Healthy)
        }

    /**
     * A per-radio status line for each child, in preference order (Bluetooth first) — the Diagnostics screen's
     * window into the individual planes that [neighbors]/[reachable]/[health] otherwise merge away. Read-only;
     * derived purely from the children's existing flows, so it never perturbs routing.
     */
    val statuses: StateFlow<List<TransportStatus>> =
        if (children.isEmpty()) {
            MutableStateFlow(emptyList())
        } else {
            combine(
                children.map { child ->
                    combine(
                        child.neighbors,
                        child.reachable,
                        child.health,
                        child.radioContended,
                        child.initiatorHeld,
                    ) { linked, nearby, health, contended, held ->
                        TransportStatus(child.kind, health, linked.size, nearby.size, contended, held)
                    }.combine(child.switchedOff) { status, off -> status.copy(switchedOff = off) }
                        .combine(child.dormant) { status, dormant -> status.copy(dormant = dormant) }
                        .combine(child.advertBlinded) { status, blinded -> status.copy(blindedAdvert = blinded) }
                },
            ) { it.toList() }.stateIn(scope, SharingStarted.Eagerly, emptyList())
        }

    /**
     * nodeId → what protects each live link to it, by the plane it runs on ([MeshTransport.linkSecurity], ADR
     * 2026-11.8mhs): the Diagnostics screen narrows it to the kinds a row's section claims, as it does [peerTransports].
     * Read-only and presentation only; derived from the children's own flows, so it never perturbs routing.
     */
    val linkSecurityByKind: StateFlow<Map<String, Map<TransportKind, LinkSecurity>>> =
        if (children.isEmpty()) {
            MutableStateFlow(emptyMap())
        } else {
            combine(children.map { it.linkSecurity }) { maps ->
                val byNode = HashMap<String, MutableMap<TransportKind, LinkSecurity>>()
                children.forEachIndexed { i, child ->
                    maps[i].forEach { (nodeId, security) -> byNode.getOrPut(nodeId) { mutableMapOf() }[child.kind] = security }
                }
                byNode.mapValues { it.value.toMap() }
            }.stateIn(scope, SharingStarted.Eagerly, emptyMap())
        }

    /**
     * nodeId → the set of planes it's currently reachable over, so the Diagnostics screen can tag a node
     * BLE / NAN / LoRa. Keyed off each child's [reachable] (not [neighbors]) to match the reach
     * classification, which reads the smoothed [reachable] set, and to avoid Wi-Fi Aware's ≤1 flapping
     * live-link set.
     *
     * A **long-range** kind here names the plane a frame arrived over, never proximity and never the peer's
     * own hardware: a LoRa entry means somebody's board put that author's frame on air, which may be a
     * gateway relaying for a peer with no board at all (ADR 044). [shortRangeKinds] is how a consumer tells
     * the two apart — the Diagnostics screen shows long-range kinds only under *Reachable long-range*.
     */
    val peerTransports: StateFlow<Map<String, Set<TransportKind>>> =
        if (children.isEmpty()) {
            MutableStateFlow(emptyMap())
        } else {
            combine(children.map { it.reachable }) { sets ->
                val byNode = HashMap<String, MutableSet<TransportKind>>()
                children.forEachIndexed { i, child ->
                    sets[i].forEach { byNode.getOrPut(it.nodeId) { mutableSetOf() }.add(child.kind) }
                }
                byNode.mapValues { it.value.toSet() }
            }.stateIn(scope, SharingStarted.Eagerly, emptyMap())
        }

    /** Every [viaInternet] child's free links (L-7-11): the peers an attachment may cross a link to beside a radio. */
    override val freeLinked: StateFlow<Set<String>> =
        children.filter { it.viaInternet }.let { links ->
            if (links.isEmpty()) {
                MutableStateFlow(emptySet())
            } else {
                combine(links.map { it.freeLinked }) { sets -> sets.flatMapTo(HashSet()) { it } }
                    .stateIn(scope, SharingStarted.Eagerly, emptySet())
            }
        }

    /**
     * The node ids an attachment has a fast plane to now, beside whatever else links them: a live link on a
     * [highThroughput] child (Wi-Fi Aware), or a free direct link to a peer a radio links too (L-7-11). A neighbour already
     * linked over Bluetooth that gains either changes the merged [neighbors] not at all, so this is the one place that
     * edge shows: `MeshManager.watchFastLinks` steps the fast-only blob wants on it (ADR 2026-10.js2p). A link to a peer
     * no radio links is left out: it is a neighbour edge of its own. Not on the [MeshTransport] seam; nothing routes on it.
     */
    val fastLinked: StateFlow<Set<String>> =
        run {
            val fast = children.filter { it.highThroughput }
            val radios = children.filter { it.shortRange }
            val links = children.filter { it.viaInternet }
            if (fast.isEmpty() && links.isEmpty()) {
                MutableStateFlow(emptySet())
            } else {
                val ids: (List<Set<Peer>>) -> Set<String> = { sets -> sets.flatMapTo(HashSet()) { set -> set.map { it.nodeId } } }
                combine(
                    combine(fast.map { it.neighbors } + flowOf(emptySet())) { ids(it.toList()) },
                    combine(radios.map { it.neighbors } + flowOf(emptySet())) { ids(it.toList()) },
                    combine(links.map { it.freeLinked } + flowOf(emptySet())) { sets -> sets.flatMapTo(HashSet()) { it } },
                ) { aware, radio, free -> aware + free.intersect(radio) }
                    .stateIn(scope, SharingStarted.Eagerly, emptySet())
            }
        }

    // Stamp each frame with the child it came off (InboundFrame.kind) before the merge erases that — the
    // delivery path records it as the message's DeliveryPlane. Presentation only: the router never reads it.
    override val inbound: Flow<InboundFrame> =
        if (children.isEmpty()) {
            emptyFlow()
        } else {
            children.map { child -> child.inbound.map { it.copy(kind = child.kind) } }.merge()
        }

    override val incomingFiles: Flow<ReceivedFile> =
        if (children.isEmpty()) emptyFlow() else children.map { child -> child.incomingFiles.map { it.copy(via = child.kind) } }.merge()

    override val incomingDigests: Flow<ReceivedDigest> =
        if (children.isEmpty()) emptyFlow() else children.map { child -> child.incomingDigests.map { it.copy(via = child.kind) } }.merge()

    init {
        // Suppress the on-demand data-path sync in a lower-preference child for any peer a higher-preference
        // child already holds a **live link** to — e.g. don't run a Wi-Fi Aware NDP sync to a peer that's on a
        // Bluetooth link, since it gets its data over Bluetooth. Recomputed whenever any child's live-link set
        // changes, so a peer that drops off the preferred plane is un-suppressed and the fallback plane resumes.
        if (children.size > 1) {
            scope.launch {
                combine(children.map { it.neighbors }) { it }.collect { sets ->
                    for (i in children.indices) {
                        val covered = HashSet<String>()
                        for (j in 0 until i) sets[j].forEach { covered.add(it.nodeId) }
                        children[i].suppressDataPath(covered)
                    }
                }
            }
            // Reverse hint: tell each child which peers the *other* planes can currently see, so a duty-cycling
            // radio (Bluetooth) can boost its scan to catch a peer another plane sighted first (the NAN→BT
            // early-warning). Keyed off `reachable` (coordination-plane presence), not live links.
            scope.launch {
                combine(children.map { it.reachable }) { it }.collect { sets ->
                    for (i in children.indices) {
                        val foreign = HashSet<String>()
                        // Only SHORT-RANGE siblings' sightings imply real proximity; a LoRa sighting (a peer
                        // kilometres away) must not make BLE chase it or end NAN's loneliness.
                        for (j in children.indices) if (j != i && children[j].shortRange) sets[j].forEach { foreign.add(it.nodeId) }
                        children[i].onForeignReachable(foreign)
                    }
                }
            }
            // The narrower hint beside it: which peers the *other* short-range planes hear near ([MeshTransport.near]),
            // the Wi-Fi Aware wedge watchdog's process-kill corroboration (ADR 2026-09.9dnk). Never `reachable`: a
            // Coded-PHY advert at −101 dBm keeps a walk-away peer reachable over Bluetooth far past an NDP's range, and
            // corroborated a kill that way (P9, 2026-10-20). LoRa is excluded exactly as above.
            scope.launch {
                combine(children.map { it.near }) { it }.collect { sets ->
                    for (i in children.indices) {
                        val foreign = HashSet<String>()
                        for (j in children.indices) if (j != i && children[j].shortRange) sets[j].forEach { foreign.add(it.nodeId) }
                        children[i].onForeignNear(foreign)
                    }
                }
            }
            // Which phones the other short-range planes say can run Wi-Fi Aware, near (a dormant Aware plane wakes for
            // a new one) and heard (it stays attached while one is), and whether any of them watches for newcomers at
            // all (GitHub #44). One combine of each child's three flows; the three are separate StateFlow writes, so a
            // push can pair a new near with the previous heard for a moment. The Aware side tolerates it: it wakes only on
            // a rising edge of near and stays while either set is non-empty (NanDormancyPolicy).
            scope.launch {
                combine(children.map { child -> awareCandidatesOf(child) }) { it }.collect { reports ->
                    for (i in children.indices) {
                        val near = HashSet<String>()
                        val heard = HashSet<String>()
                        var sees = false
                        for (j in children.indices) {
                            if (j == i || !children[j].shortRange) continue
                            near += reports[j].near
                            heard += reports[j].heard
                            sees = sees || reports[j].seesNewcomers
                        }
                        children[i].onForeignAwareCandidates(near, heard, sees)
                    }
                }
            }
        }
    }

    override fun start() {
        children.forEach { it.start() }
    }

    override fun stop() {
        children.forEach { it.stop() }
    }

    override fun heal() {
        children.forEach { it.heal() }
    }

    override fun releaseInitiatorHold() {
        children.forEach { it.releaseInitiatorHold() }
    }

    override fun pause() {
        children.forEach { it.pause() }
    }

    override fun resume() {
        children.forEach { it.resume() }
    }

    override suspend fun send(
        wire: WireEnvelope,
        to: Peer?,
    ) {
        if (to != null) {
            childHoldingLinkTo(to.nodeId)?.send(wire, to) // prefer BT; no-op if no child holds the link
            return
        }
        // Broadcast (originate): reach each merged neighbor exactly once, over its preferred child.
        val done = HashSet<String>()
        for (child in children) {
            val targets = child.neighbors.value.filter { done.add(it.nodeId) }
            for (peer in targets) child.send(wire, peer)
        }
    }

    /**
     * Forwards the bulk-transfer hint to **every** child (each applies its own gates; only an on-demand
     * plane arms anything). True if any armed — the serve path uses it to decide whether a fast-plane link
     * is worth a grace wait.
     */
    override fun expectBulkTransfer(nodeId: String): Boolean {
        var armed = false
        for (child in children) if (child.expectBulkTransfer(nodeId)) armed = true
        return armed
    }

    /** A live call's hold, forwarded to every child like [expectBulkTransfer]; true if any holds or raises a link. */
    override fun holdLink(nodeId: String): Boolean {
        var held = false
        for (child in children) if (child.holdLink(nodeId)) held = true
        return held
    }

    /** Asked of each [viaInternet] child: its own links, not a merged set a dispatch behind. */
    override fun freeLinkTo(nodeId: String): Boolean = children.any { it.viaInternet && it.freeLinkTo(nodeId) }

    /** The [viaInternet] child's: only a direct link is raised for an attachment beside a radio (L-7-11). */
    override suspend fun raiseFreeLink(
        nodeId: String,
        blobKey: String,
    ): Boolean = children.firstOrNull { it.viaInternet }?.raiseFreeLink(nodeId, blobKey) ?: false

    override suspend fun freeLinkMay(
        nodeId: String,
        blobKey: String,
    ): Boolean = children.firstOrNull { it.viaInternet }?.freeLinkMay(nodeId, blobKey) ?: false

    override fun freeLinkRaising(nodeId: String): Boolean = children.any { it.viaInternet && it.freeLinkRaising(nodeId) }

    override suspend fun freeLinkComing(
        nodeId: String,
        blobKey: String,
    ): Boolean = children.firstOrNull { it.viaInternet }?.freeLinkComing(nodeId, blobKey) ?: false

    /** The high-throughput child's answer: the plane an attachment would ride, whichever child carries the ask. */
    override fun bulkRouteTo(nodeId: String): BulkRoute = children.firstOrNull { it.highThroughput }?.bulkRouteTo(nodeId) ?: BulkRoute.None

    /**
     * The union over every plane: a blob arriving on any link is arriving, and when two planes stream the same
     * blob the copy furthest along is the one the chat shows (#115).
     */
    override fun arrivingFiles(): Map<String, ArrivingFile> = children.flatMap { it.arrivingFiles().values }.furthestByKey()

    /** Whichever child's link delivered it; the others hold nothing under that peer and key. */
    override fun fileTaken(
        fromNodeId: String,
        key: String,
    ) {
        children.forEach { it.fileTaken(fromNodeId, key) }
    }

    /** Any plane: a peer streaming to us on either link has fed us whatever sits behind that file. */
    override fun fileArrivingFrom(nodeId: String): Boolean = children.any { it.fileArrivingFrom(nodeId) }

    override fun fileArrivingEtaMs(nodeId: String): Long? = children.mapNotNull { it.fileArrivingEtaMs(nodeId) }.maxOrNull()

    override fun cancelQueuedFile(
        nodeId: String,
        key: String,
    ): Boolean = children.any { it.cancelQueuedFile(nodeId, key) }

    /** Any plane: the serve went to whichever child held the link, and a re-ask must see it wherever it sits. */
    override fun fileInFlightTo(
        nodeId: String,
        key: String,
    ): Boolean = children.any { it.fileInFlightTo(nodeId, key) }

    /** Whichever plane feeds it: one link streams a key to a peer at a time. */
    override fun fileFeed(
        nodeId: String,
        key: String,
    ): FileFeed? = children.firstNotNullOfOrNull { it.fileFeed(nodeId, key) }

    /** The plane a file to [nodeId] would go out on now ([fileChildFor]) says how fast it would go. */
    override fun expectedFileRate(nodeId: String): Int = fileChildFor(nodeId)?.expectedFileRate(nodeId) ?: 0

    /** Every plane: the report names a key, and only the link streaming it takes it. */
    override fun onFileProgress(
        nodeId: String,
        key: String,
        held: Long,
        ms: Long,
    ) {
        children.forEach { it.onFileProgress(nodeId, key, held, ms) }
    }

    override val fileProgress: Flow<FileProgress> = children.map { it.fileProgress }.merge()

    override val fileFeedsChanged: Flow<Unit> = children.map { it.fileFeedsChanged }.merge()

    /**
     * The load of the plane an attachment to [nodeId] would ride: the high-throughput child when it holds a link to
     * [nodeId] (an attachment takes it, [sendFile]), else the preference-order link holder.
     */
    override fun feedingLoad(nodeId: String): Int = fileChildFor(nodeId)?.feedingLoad(nodeId) ?: 0

    /** The link holder's answer: a frame, and a file off the high-throughput plane, ride its link. */
    override fun slowLinkTo(nodeId: String): Boolean = childHoldingLinkTo(nodeId)?.slowLinkTo(nodeId) ?: false

    override suspend fun pairView(nodeId: String): PairView? = children.firstNotNullOfOrNull { it.pairView(nodeId) }

    override fun fileRidesInternet(nodeId: String): Boolean = fileChildFor(nodeId)?.viaInternet ?: false

    /**
     * The child an attachment to [nodeId] would go out on now: a live high-throughput link, else a free direct link (L-7-11),
     * else the link holder. Per peer: whether the free link carries a given blob is the pair's rule ([pairView]), which
     * [sendFile] asks per key and the blob exchange per want.
     */
    private fun fileChildFor(nodeId: String): MeshTransport? =
        children.firstOrNull { c -> c.highThroughput && c.neighbors.value.any { it.nodeId == nodeId } }
            ?: freeLinkChildTo(nodeId)
            ?: childHoldingLinkTo(nodeId)

    // The [viaInternet] child holding a free link to [nodeId], or null.
    private fun freeLinkChildTo(nodeId: String): MeshTransport? = children.firstOrNull { it.viaInternet && it.freeLinkTo(nodeId) }

    // The free link to [nodeId] when it would carry the attachment [key] (the pair's rule, as its sendFile reads it).
    private suspend fun freeLinkFor(
        nodeId: String,
        key: String,
    ): MeshTransport? = freeLinkChildTo(nodeId)?.takeIf { it.pairView(nodeId)?.admitsBlob(key) == true }

    // The link holder other than [except]: where a copy a free link refused goes instead.
    private fun holderOtherThan(
        nodeId: String,
        except: MeshTransport,
    ): MeshTransport? = children.firstOrNull { c -> c !== except && c.neighbors.value.any { it.nodeId == nodeId } }

    /**
     * Files route like frames — preference-order link holder — EXCEPT attachment blobs, which prefer the
     * [highThroughput] plane: ANY attachment rides its live link when one exists (a link that's already up
     * costs nothing extra, and an NDP moves it orders of magnitude faster than L2CAP), and a **large** one
     * (≥ [BULK_MIN_BYTES] — the size of the transcoded wire blob, NOT the user's source file) additionally
     * arms an on-demand bring-up ([expectBulkTransfer]) and waits ≤ [BULK_GRACE_MS] for the link **off the
     * caller's coroutine** (sendFile is invoked inline from the serial inbound dispatch — the router's
     * single collector — so suspending here would stall every frame on both radios), then falls back to the
     * link holder (Bluetooth). Each invocation resolves to exactly one route, so a file is never sent twice;
     * a child refusing the enqueue (link died in the check→enqueue window) falls back the same way instead
     * of silently losing the file. Below the size gate the NDP *setup* (~1-4 s) costs more than BLE's whole
     * transfer, so small blobs don't arm — and avatars/digests keep today's path byte-for-byte. Every
     * decision is logged (`file route: …`) so "which plane and why" is one grep away. A free direct link (ADR
     * 2026-10.a5yj, `docs/DIRECT_LINKS.md` L-7-11) is the second fast plane: it carries an attachment it admits at any
     * size, and beside a radio a large one raises it when Wi-Fi Aware will not arm, waited for the same way.
     */
    override suspend fun sendFile(
        file: File,
        to: Peer,
        meta: FileMeta,
    ): Boolean =
        if (meta.kind != FileKind.ATTACHMENT) {
            childHoldingLinkTo(to.nodeId)?.sendFile(file, to, meta) ?: false
        } else {
            sendAttachment(file, to, meta)
        }

    // [sendFile]'s ladder for an attachment.
    private suspend fun sendAttachment(
        file: File,
        to: Peer,
        meta: FileMeta,
    ): Boolean {
        val fast = children.firstOrNull { it.highThroughput }
        // A resume sends only the rest of the file (#116): the size gate and the log read the bytes still to send
        // (the link's own `file …` line names the offset).
        val bytes = file.length() - meta.offset.coerceIn(0L, file.length())
        val tag = "${meta.kind}/${meta.key} ${bytes}B → ${to.nodeId}"
        if (meta.fastOnly) return sendFastOnly(fast, file, to, meta, tag)
        overLivePlane(fast, file, to, meta, tag)?.let { return it }
        // A small blob isn't worth RAISING an NDP for (BLE finishes before the setup would); a big one
        // arms the on-demand bring-up unless the fast plane declines (stale sighting / cooldown / off).
        val small = bytes < BULK_MIN_BYTES
        if (!small && armed(fast, file, to, meta, tag)) return true // scheduled: the waiter commits to exactly one plane
        log("file route: $tag ${if (small) "below ${BULK_MIN_BYTES}B arm gate" else "fast plane won't arm"} — link holder")
        return childHoldingLinkTo(to.nodeId)?.sendFile(file, to, meta) ?: false
    }

    // An attachment's live fast plane, whatever its size, or null with none: a fast link already up (e.g. a custody sync in
    // flight), falling back on a teardown race; else a free direct link (L-7-11) that admits it, beside a radio or not,
    // a copy it refuses going on to the next holder.
    private suspend fun overLivePlane(
        fast: MeshTransport?,
        file: File,
        to: Peer,
        meta: FileMeta,
        tag: String,
    ): Boolean? {
        if (fast != null && fast.neighbors.value.any { it.nodeId == to.nodeId }) {
            log("file route: $tag over fast link")
            return fast.sendFile(file, to, meta) ||
                (childHoldingLinkTo(to.nodeId)?.sendFile(file, to, meta) ?: false)
        }
        val link = freeLinkFor(to.nodeId, meta.key) ?: return null
        log("file route: $tag over free direct link")
        return link.sendFile(file, to, meta) || (holderOtherThan(to.nodeId, link)?.sendFile(file, to, meta) ?: false)
    }

    /**
     * Arms an on-demand fast plane for a copy and schedules its wait ([awaitPlaneThenSend]): Wi-Fi Aware first — whichever
     * side of the pair is larger initiates the data path, and both sides mark (the requester at pull time, this serve side
     * here) — else, beside a radio, a free direct link raised for this blob (L-7-11). False when neither will come.
     */
    private suspend fun armed(
        fast: MeshTransport?,
        file: File,
        to: Peer,
        meta: FileMeta,
        tag: String,
    ): Boolean {
        val what = if (meta.fastOnly) " (fast-only)" else ""
        if (fast != null && fast.expectBulkTransfer(to.nodeId)) {
            log("file route: $tag armed$what — waiting ≤${BULK_GRACE_MS}ms for the fast link")
            awaitPlaneThenSend(fast, linkedTo(fast, to.nodeId), file, to, meta, tag, "fast link")
            return true
        }
        val link = children.firstOrNull { it.viaInternet }
        if (link == null || !shortRangeLinked(to.nodeId) || !link.raiseFreeLink(to.nodeId, meta.key)) return false
        log("file route: $tag raising a free direct link$what — waiting ≤${BULK_GRACE_MS}ms")
        awaitPlaneThenSend(link, freeLinkReady(link, to.nodeId), file, to, meta, tag, "free direct link")
        return true
    }

    /**
     * [sendFile] for an asker that takes the bytes over a fast route only ([FileMeta.fastOnly], ADR 2026-10.js2p): a live
     * fast link carries them whatever their size; else a free direct link that admits them (L-7-11); else, when the asker's
     * file would ride a contact's direct Internet link ([fileChildFor] is a [viaInternet] child: no radio holds it), that
     * link carries them (ADR 2026-10.a5yj — the asker asked us fast-only over it only from an unmetered network,
     * `BlobExchange`); else a fast plane is armed for them whatever their size and waited on as any large blob is ([armed]).
     * Never Bluetooth — not on a refused enqueue, not when the grace runs out, not when no plane will arm: the asker's user
     * has not agreed to wait on Bluetooth for these bytes. A copy given up after it was accepted calls [FileMeta.onDropped].
     */
    private suspend fun sendFastOnly(
        fast: MeshTransport?,
        file: File,
        to: Peer,
        meta: FileMeta,
        tag: String,
    ): Boolean {
        // A peer a radio holds too, with no free link, resolves to the radio in [fileChildFor], and Bluetooth stays refused.
        val live =
            fast?.takeIf { f -> f.neighbors.value.any { it.nodeId == to.nodeId } }
                ?: freeLinkFor(to.nodeId, meta.key)
                ?: fileChildFor(to.nodeId)?.takeIf { it.viaInternet }
        if (live != null) {
            val over = if (live === fast) "fast link" else "direct link"
            val sent = live.sendFile(file, to, meta)
            if (sent) {
                log("file route: $tag over $over (fast-only)")
            } else {
                log("file route: $tag fast-only — dropped ($over enqueue refused)")
                metrics?.onFastOnlyDropped()
            }
            return sent
        }
        if (armed(fast, file, to, meta, tag)) return true
        log("file route: $tag fast-only — dropped (fast plane won't arm)")
        metrics?.onFastOnlyDropped()
        return false
    }

    // Whether [plane] holds a live link to [nodeId], as a flow: what an armed fast plane's wait reads (true: go; null: wait).
    private fun linkedTo(
        plane: MeshTransport,
        nodeId: String,
    ): Flow<Boolean?> = plane.neighbors.map { set -> set.any { it.nodeId == nodeId }.takeIf { it } }

    // A raised free link's wait (L-7-11): true once [link] holds a free link to [nodeId]; false as soon as the attempt ended
    // without one — the contact refused it as metered, the punch found no path, a link that is not free came up — so the
    // radio takes the file then, not when the grace runs out; null while it is under way. Read every [FREE_LINK_POLL_MS].
    private fun freeLinkReady(
        link: MeshTransport,
        nodeId: String,
    ): Flow<Boolean?> =
        flow {
            while (true) {
                emit(
                    when {
                        link.freeLinkTo(nodeId) -> true
                        !link.freeLinkRaising(nodeId) -> false
                        else -> null
                    },
                )
                delay(FREE_LINK_POLL_MS)
            }
        }

    /**
     * The armed-grace tail of [sendFile], detached so the serial inbound dispatch never suspends on it: waits up to
     * [BULK_GRACE_MS] for [ready] to say true — an armed fast plane's link, or a free direct link raised for this blob —
     * or false (none will come), then sends over [plane] once, else drops a fast-only copy, else hands it to the link
     * holder other than [plane].
     */
    @Suppress("LongParameterList") // the copy, and the plane it waits for under its own name
    private fun awaitPlaneThenSend(
        plane: MeshTransport,
        ready: Flow<Boolean?>,
        file: File,
        to: Peer,
        meta: FileMeta,
        tag: String,
        name: String,
    ) {
        scope.launch {
            val started = TimeSource.Monotonic.markNow()
            val verdict = withTimeoutOrNull(BULK_GRACE_MS) { ready.first { it != null } }
            val linked = verdict == true
            if (verdict == null) metrics?.onBulkGraceTimeout()
            val over = linked && plane.sendFile(file, to, meta)
            val waited = started.elapsedNow().inWholeMilliseconds
            if (over) {
                log("file route: $tag over $name after ${waited}ms")
            } else if (meta.fastOnly) {
                log("file route: $tag fast-only — dropped ($name ${missed(linked, verdict)})")
                metrics?.onFastOnlyDropped()
                meta.onDropped?.invoke()
            } else {
                log("file route: $tag $name ${missed(linked, verdict)} after ${waited}ms — link holder")
                holderOtherThan(to.nodeId, plane)?.sendFile(file, to, meta)
            }
        }
    }

    // Why a wait's plane did not take its copy, for the `file route:` line.
    private fun missed(
        linked: Boolean,
        verdict: Boolean?,
    ): String =
        when {
            linked -> "enqueue refused"
            verdict == false -> "will not come"
            else -> "grace expired"
        }

    override suspend fun sendDigest(
        to: Peer,
        ids: List<String>,
    ) {
        childHoldingLinkTo(to.nodeId)?.sendDigest(to, ids)
    }

    override fun fastFanout(wire: WireEnvelope) {
        for (child in children) {
            if (child.hasFastPlane) {
                child.fastFanout(wire) // coordination-plane blast (Wi-Fi Aware)
            } else {
                scope.launch { child.send(wire, null) } // link-based child: its normal flood is already fast
            }
        }
    }

    override fun fastSend(
        wire: WireEnvelope,
        to: Peer,
    ) {
        for (child in children) {
            if (child.hasFastPlane) {
                child.fastSend(wire, to)
            } else if (child.neighbors.value.any { it.nodeId == to.nodeId }) {
                scope.launch { child.send(wire, to) }
            }
        }
    }

    /** Every child hears the Internet cover; only a plane with no data path acts on it (ADR 2026-09.y5f3). */
    override fun coveredByInternet(peers: Set<String>) {
        for (child in children) child.coveredByInternet(peers)
    }

    override fun onPresenceKeys(keys: Map<String, List<ByteArray>>) {
        for (child in children) child.onPresenceKeys(keys)
    }

    // The own key and the TAG_NEXT predicate go to the Bluetooth plane alone (ADR 2026-11.xp6d, step 5b): no other plane
    // airs a tag, and a key handed to a plane that has no use for it is one more place it lives.
    override fun onOwnPresenceKey(key: OwnPresenceKey?) {
        for (child in children) if (child.kind == TransportKind.Bluetooth) child.onOwnPresenceKey(key)
    }

    override fun onOwnPresenceKeySource(read: () -> OwnPresenceKey?) {
        for (child in children) if (child.kind == TransportKind.Bluetooth) child.onOwnPresenceKeySource(read)
    }

    override fun onTagNextRecipients(hears: (nodeId: String) -> Boolean) {
        for (child in children) if (child.kind == TransportKind.Bluetooth) child.onTagNextRecipients(hears)
    }

    override fun onPresenceKeyPassRequester(request: () -> Unit) {
        for (child in children) if (child.kind == TransportKind.Bluetooth) child.onPresenceKeyPassRequester(request)
    }

    // A live frame (a walkie chunk) rides the high-throughput child when it holds a link to [to]: an NDP's latency is
    // a Wi-Fi radio's, not a connection interval's. Everything else keeps the Bluetooth-first order of [send].
    override suspend fun sendLive(
        wire: WireEnvelope,
        to: Peer,
    ) {
        val fast = liveChildFor(to.nodeId)
        if (fast != null) fast.send(wire, to) else send(wire, to)
    }

    /** The answer of the child [sendLive] would use, not the Bluetooth-first holder [slowLinkTo] asks. */
    override fun liveLinkFading(nodeId: String): Boolean =
        (liveChildFor(nodeId) ?: childHoldingLinkTo(nodeId))?.liveLinkFading(nodeId) ?: false

    /** The [highThroughput] child holding a live link to [nodeId], which a live frame prefers; null when none does. */
    private fun liveChildFor(nodeId: String): MeshTransport? =
        children.firstOrNull { c -> c.highThroughput && c.neighbors.value.any { it.nodeId == nodeId } }

    /** First child (preference order) that currently holds a live data-path link to [nodeId], or null. */
    private fun childHoldingLinkTo(nodeId: String): MeshTransport? =
        children.firstOrNull { c -> c.neighbors.value.any { it.nodeId == nodeId } }

    /** One child's word on Aware candidates at one instant ([MeshTransport.onForeignAwareCandidates]). */
    private data class AwareCandidates(
        val near: Set<String>,
        val heard: Set<String>,
        val seesNewcomers: Boolean,
    )

    private fun awareCandidatesOf(child: MeshTransport): Flow<AwareCandidates> =
        combine(child.awareCandidatesNear, child.awareCandidatesHeard, child.seesNewcomers, ::AwareCandidates)

    private fun mergePeers(sets: Array<Set<Peer>>): Set<Peer> {
        val best = HashMap<String, Peer>()
        for (set in sets) for (peer in set) best.merge(peer.nodeId, peer, ::richer)
        return best.values.toSet()
    }

    // A peer seen over two planes keeps the richer advertised hint (unauthenticated, routing-only): higher
    // protoVersion wins, tie-break on higher capabilities.
    private fun richer(
        a: Peer,
        b: Peer,
    ): Peer =
        when {
            a.protoVersion != b.protoVersion -> if (a.protoVersion > b.protoVersion) a else b
            else -> if (a.capabilities >= b.capabilities) a else b
        }

    internal companion object {
        // How long the serve path waits for an armed fast-plane link before falling back to the link holder.
        // Healthy NDP bring-up is 1-3 s with a free slot; a contended slot pays the current serve's
        // contended linger (2 s) + teardown + settle (1.5 s) + handshake (~1-3 s) — field-measured 2026-07-04
        // at ~8.3 s for receiver #2 of a two-receiver room, so 10 s lets the second pull ride NAN instead of
        // missing the freed slot by a hair. Kept under the NAN handshake timeout (15 s) so a genuinely
        // failing handshake falls back here rather than shadowing it.
        const val BULK_GRACE_MS = 10_000L

        // Attachments below this don't ARM an on-demand fast-plane link (they still ride one that's already
        // up): for a small blob, BLE finishes in ~2-3 s — less than the NDP setup it would replace — and
        // skipping the arm spares the single NDI the per-file bring-up/teardown churn. Compared against the
        // TRANSCODED wire blob, not the user's source file — a 1-5 MB GIF lands at ~150-250 KB as a 480px
        // q70 animated WebP (field-observed 176/233 KB), which is why the original 256 KiB gate routed
        // "large GIFs" over BLE. 128 KiB captures those while stickers/thumbnails stay on BLE.
        const val BULK_MIN_BYTES = 128L * 1024

        // How often a raised free link's wait reads whether the link came, or cannot ([freeLinkReady]).
        const val FREE_LINK_POLL_MS = 200L
    }
}
