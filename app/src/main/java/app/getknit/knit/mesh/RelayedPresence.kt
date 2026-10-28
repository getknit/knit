package app.getknit.knit.mesh

import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireEnvelope
import app.getknit.knit.mesh.spool.ScopeSync
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** When we last heard a peer's own frame over the radio mesh, and which neighbour handed it to us. */
data class RelayedHeard(
    val heardAt: Long,
    val via: String,
)

/** The empty [MeshController.heardIndirectly], shared so the interface default and a stopped mesh agree. */
val NO_RELAYED: StateFlow<Map<String, RelayedHeard>> = MutableStateFlow(emptyMap())

/**
 * The short-range planes' author-presence tracker — the evidence behind the "Reachable indirectly" tier
 * (`ui/Reach.kt`, ADR 2026-10.fw8g): peers whose own recent frames reached us across the BLE / Wi-Fi Aware
 * mesh through some *other* phone. `MeshController.neighbors` only holds peers our own radio sighted, so a
 * peer two hops out whose DMs and receipts were arriving all along used to read as merely Known.
 *
 * The third of three such trackers and shaped like the other two (LoRa's `noteReachable`, the spool's
 * `ScopeStatus.peerSeenAt`, ADR 2026-09.2ajk): stamped with **our** clock at acceptance, read through one
 * function ([indirectPeers]) that applies the linger with the reader's clock, so a sweep running late after
 * the CPU slept never shows a stale peer. [note] runs once per first-seen frame, after `InboundPipeline`
 * verified it; [isRelayedRadioEvidence] is the rule.
 *
 * [stop] closes it as well as clearing it, under the same lock every write takes: `MeshManager.stop()` cancels
 * the session without waiting for it, so a frame already past `verifyInbound` can still reach [note] after the
 * clear — and a paused mesh would then show that peer for a whole linger, with the sweep that would have dropped
 * it gone with the session. A closed tracker records nothing until [start].
 *
 * Presentation only — never a delivery, routing or custody gate. Nothing in `mesh/` reads [heard]; a caller
 * that lets it decide where a frame goes has invented a routing table on evidence that was never meant to
 * carry one.
 *
 * Pure — no Android — so it is unit-tested directly and runs unchanged inside the `mesh/lab` nodes.
 */
class RelayedPresence(
    // Our own node id, so our own frames looping back never count. Suspend because `Identity.nodeId()` is;
    // asked only once a frame has passed the cheap checks.
    private val selfId: suspend () -> String,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val cap: Int = RELAYED_CAP,
) {
    private val _heard = MutableStateFlow<Map<String, RelayedHeard>>(emptyMap())

    // Guards every write and [open], so a [note] racing [stop] either lands before the clear or not at all.
    private val lock = Any()

    // Open from construction, so a pipeline built without a mesh (tests) records; MeshManager closes it on stop.
    private var open = true

    /** Author → the latest stamp, swept on the metrics tick; read through [indirectPeers], never raw. */
    val heard: StateFlow<Map<String, RelayedHeard>> = _heard.asStateFlow()

    /** Records [env]'s author as heard via [fromNodeId] when the frame is evidence ([isRelayedRadioEvidence]). */
    suspend fun note(
        wire: WireEnvelope,
        env: RelayEnvelope,
        fromNodeId: String,
        kind: TransportKind,
    ) {
        val now = clock()
        if (!isRelayedRadioEvidence(wire, env, fromNodeId, kind, now)) return
        if (env.senderId == selfId()) return
        val stamp = RelayedHeard(heardAt = now, via = fromNodeId)
        synchronized(lock) {
            if (!open) return
            val held = _heard.value
            _heard.value =
                if (env.senderId in held || held.size < cap) {
                    held + (env.senderId to stamp)
                } else {
                    // A full table drops its stalest author; a mesh with this many relayed authors in one linger
                    // is far past anything the Diagnostics list can show anyway.
                    held - held.minBy { it.value.heardAt }.key + (env.senderId to stamp)
                }
        }
    }

    /** Drops stamps past [lingerMs]: bounds memory only — [indirectPeers] applies the linger itself at read. */
    fun sweep(lingerMs: Long = PRESENCE_LINGER_MS) {
        val now = clock()
        synchronized(lock) { _heard.value = _heard.value.filterValues { now - it.heardAt <= lingerMs } }
    }

    /** Records again — the mesh started. */
    fun start() {
        synchronized(lock) { open = true }
    }

    /** Forgets everything and records nothing more: a stopped mesh hears nothing, so it claims no path. */
    fun stop() {
        synchronized(lock) {
            open = false
            _heard.value = emptyMap()
        }
    }
}

/**
 * Whether a frame that arrived over the radio mesh proves its **author** is reachable through another phone.
 * Every condition is load-bearing:
 *  - **not LoRa, not the spool** — both have their own tracker, and both hand frames over keyed on the
 *    author anyway. The lab's transports report [TransportKind.Other], so this names the planes it excludes
 *    rather than the ones it admits.
 *  - **the author is not the hop** — a frame its author handed us directly is a link or a sighting, which
 *    `neighbors` already covers. This also drops, with no code of its own, every path that cannot name a
 *    hop: a BLE side-channel page and a NAN fast-frame whose hop is unknown both report the author as the
 *    source, as do the `MeshManager` custody replays and a `typing` cue (single-hop by design).
 *  - **signed** — only a frame `verifyInbound` checked against the key its sender id binds to. The unsigned
 *    door (a v3 sealed tick, opened later) and the unchecked blob traffic (`blobreq`, `blobhave`, `blobhas`) name a
 *    sender nobody has verified yet.
 *  - **fresh** — [PRESENCE_FRESH_MS] for every type, profiles included. Deliberately stricter than
 *    [isPresenceEvidence]'s 13 h profile window: on this plane other people's profiles are handed over all the
 *    time (`KeyExchange.serveKey`, custody re-serves on every new link), so only a fresh publish says its
 *    author is out there now.
 *
 * Presence only, like [isPresenceEvidence]: a frame that fails this is delivered, custodied and relayed
 * exactly as before.
 */
internal fun isRelayedRadioEvidence(
    wire: WireEnvelope,
    env: RelayEnvelope,
    fromNodeId: String,
    kind: TransportKind,
    now: Long,
): Boolean =
    kind != TransportKind.LoRa &&
        kind != TransportKind.DirectLink &&
        !ScopeSync.isSpoolSource(fromNodeId) &&
        env.senderId != fromNodeId &&
        wire.sig.isNotEmpty() &&
        !FrameType.isUnsignedPointToPoint(env.type) &&
        now - env.sentAt <= PRESENCE_FRESH_MS

/**
 * The peers the radio mesh is currently an indirect path to: author → the neighbour that last handed us one
 * of its frames, within [lingerMs] of [now]. The one read rule for every surface (Diagnostics, the Profile
 * status line), like `spoolPresentPeers`, so they cannot disagree.
 */
fun indirectPeers(
    heard: Map<String, RelayedHeard>,
    now: Long,
    lingerMs: Long = PRESENCE_LINGER_MS,
): Map<String, String> =
    buildMap {
        heard.forEach { (author, stamp) -> if (now - stamp.heardAt <= lingerMs) put(author, stamp.via) }
    }

/** Most authors [RelayedPresence] tracks at once. */
internal const val RELAYED_CAP = 256
