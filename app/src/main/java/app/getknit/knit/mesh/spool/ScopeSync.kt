package app.getknit.knit.mesh.spool

import app.getknit.knit.mesh.CarriedFrame
import app.getknit.knit.mesh.ForwardStore
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.SPOOL_COVER_MS
import app.getknit.knit.mesh.crypto.scope.ScopeCrypto
import app.getknit.knit.mesh.crypto.scope.SpoolPow
import app.getknit.knit.mesh.isPresenceEvidence
import app.getknit.knit.mesh.protocol.ChatContent
import app.getknit.knit.mesh.protocol.CommonsPost
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * The local content-addressed blob store, as much of it as the Internet plane needs (spec §9.5).
 * Implemented over `BlobRepository`; kept as a seam so [ScopeSync] stays Android-free and testable.
 */
interface ScopeBlobs {
    suspend fun has(aHash: String): Boolean

    suspend fun bytes(aHash: String): ByteArray?

    suspend fun save(
        aHash: String,
        mime: String,
        bytes: ByteArray,
    )
}

/** Opens WebSocket sessions to spools. The one seam the OkHttp adapter implements. */
interface SpoolDialer {
    /** Connects to [url], or null when the socket could not be opened. */
    suspend fun dial(url: String): SpoolSocket?

    /**
     * Reads what the spool at [url] is running from its `GET /source` document (see [SpoolSoftware]), or
     * null when it serves none. Diagnostics only — nothing about the plane depends on the answer, which is
     * why the default is "we did not ask" and a fake spool need not serve it.
     */
    suspend fun fetchSoftware(url: String): SpoolSoftware? = null
}

/** An open spool socket: a [SpoolLink] to write to plus its inbound stream, closed when the socket dies. */
interface SpoolSocket : SpoolLink {
    val incoming: ReceiveChannel<ByteArray>

    /**
     * Why the socket ended, once [incoming] is closed — a protocol close code (`4001 auth`), a transport
     * failure, or null while it is still open. Without this the most common field failures are invisible:
     * a spool that rejects our token closes 4001 before saying anything, which otherwise looks exactly
     * like "not connected yet".
     */
    val closeReason: String? get() = null

    /**
     * How long the spool asked us to wait before dialling again (its `Retry-After`), or null when it
     * asked for nothing — which is every case but a transport-layer refusal. A hint, not a contract:
     * the reconnect loop takes it as a floor and keeps its own backoff and jitter on top.
     *
     * Defaulted so the hand-rolled fakes above the seam stay one-method objects.
     */
    val retryAfterMs: Long? get() = null
}

/**
 * One scope's convergence state at one spool: the two digests that must agree, plus the quarantine size.
 * [label] is what the scope is *of* — a DM peer's node id or a group id.
 *
 * [converged] is plain digest equality, so read it together with [retiring]: a retiring scope is drained
 * but never refilled (spec §3.1/§3.3), so it legitimately sits at `local > spool` and reports **false**
 * for the whole drain window. That is the scope working as designed, not divergence.
 *
 * [localCount] is the population our digest is folded over — custody-held **plus** [accountedCount], the
 * blobs the spool holds that our custody never will (§9.6). Counting them keeps `localCount ==
 * spoolCount` meaning "converged" once the aged band is accounted for, which is what the soak oracle and
 * the debug bridge read.
 *
 * [peerSeenAt] is the one field here that describes the **peer** rather than the spool, and the only
 * honest basis for saying this scope is a live path to anyone: everything else above is true of a scope
 * whose peer has not opened the app in a month, because a scope is derived from the pairwise ratchet root
 * and stays subscribed and converged regardless. Null until this peer has put a *recent* frame of its own
 * into the scope (ADR 2026-09.2ajk).
 */
class ScopeStatus(
    val scopeHex: String,
    val label: String,
    val localCount: Int,
    val spoolCount: Int,
    val converged: Boolean,
    val invalidCount: Int,
    val retiring: Boolean,
    val accountedCount: Int = 0,
    val peerSeenAt: Long? = null,
)

/**
 * One spool's live state, for the Diagnostics screen, the relay settings screen and the debug bridge.
 *
 * [connected] means the hello exchange completed on a socket that is still open — not that a socket
 * exists. The distinction is the whole difference between a relay and a route that swallows the
 * upgrade: a captive or filtered Wi-Fi the platform still calls validated holds a socket for the entire
 * connect timeout with nothing coming back, and counting that as connected made the plane, the chat
 * header and every coverage rule say "live" about a relay that had never answered (work item 50).
 *
 * [lastError] is the most recent `err` code this spool answered with — the difference between
 * "connected but idle" and "connected and refusing us", which is otherwise invisible and is exactly
 * what a field test needs (`quota`, `pow` and `rate` all present as a scope that simply never
 * converges) — or one of the client's own verdicts on it: [ScopeSync.UNREACHABLE] (no response of any
 * kind came back from the route, or the URL could not be dialled at all), [ScopeSync.NO_HELLO] (the
 * socket opened and the spool never said hello), [ScopeSync.OVERLONG_LISTING],
 * [SpoolConnection.UNRESPONSIVE], and `too_large` when its `maxRecord` could not carry our SUB.
 *
 * [dialFailures] counts the sessions in a row that ended without a completed hello, and is 0 the moment
 * one completes. A relay that has failed twelve dials running is in a different state from one that
 * missed a single reconnect, and the number is what tells a field log the two apart.
 *
 * [maxAttachBytes] is this spool's HELLO-advertised per-scope attachment budget, or **null** when it
 * advertised no attachment support at all (spec §7.3 — the three limits arrive together or not at all,
 * and a client must not send an attachment record to a spool that omitted them). Null is therefore not
 * "unknown" but "this spool carries frames only", which is what lets the UI say so before a send rather
 * than leaving a photo silently un-relayed. It is null while disconnected for the same reason a hello
 * has not happened yet.
 */
class SpoolStatus(
    val url: String,
    val connected: Boolean,
    val powBits: Int,
    val lastError: String?,
    val scopes: List<ScopeStatus>,
    val maxAttachBytes: Int? = null,
    // The commons this spool advertised in its HELLO (§7.4), null while disconnected or when it runs none —
    // what the relay row's Join reads.
    val commons: SpoolCommonsInfo? = null,
    // What this spool answered at `/source`, null until that lands and while disconnected: a build is a fact
    // about the live connection like every other field here, not a remembered one.
    val software: SpoolSoftware? = null,
    val dialFailures: Int = 0,
    /**
     * When this worker last began a dial, on the plane's clock (epoch ms), or null before its first. The
     * reconnect curve's own oracle: two consecutive readings are the wait the worker actually took, which
     * `dialFailures` alone cannot say. Diagnostics only.
     */
    val lastDialAt: Long? = null,
)

/**
 * The Internet plane: `docs/SPOOL_PROTOCOL.md`'s member half. A custody-plane sibling of
 * [app.getknit.knit.mesh.ForwardSync] — deliberately **not** a third `MeshTransport`, because that seam
 * is peer-addressed and radio-shaped while a scope has no neighbors (ADR 019).
 *
 * One multiplexed connection per configured spool, every scope subscribed on each, and the §9.1 heal
 * loop running per (spool, scope): compare the spool's digest against ours, LIST on mismatch, PULL what
 * we lack, PUSH what it lacks. Inbound blobs that survive §4.4 validation re-enter delivery through the
 * ordinary custody re-serve path, so flood-dedup, roster vetting, persistence, receipts and the onward
 * mesh relay are all unchanged — one Internet-connected member bridges a whole radio island in both
 * directions with zero new delivery semantics (§9.4).
 *
 * Pure: the socket, the custody store, identity, the carry-authentication gate and the delivery sink are
 * all injected, so the whole plane runs against an in-process fake spool in unit tests.
 */
@Suppress("LongParameterList") // the collaborator set is the seam list; every one is injected for testability
class ScopeSync(
    private val registry: ScopeRegistry,
    private val dialer: SpoolDialer,
    private val store: ForwardStore,
    private val selfId: suspend () -> String,
    // Configured spool URLs; empty (the plane switched off, or nothing configured) parks every worker.
    private val urls: suspend () -> List<String>,
    // The mesh's own carry gate — pinned sender key, signature valid. Reused rather than
    // re-implemented so a spool-delivered frame is authenticated by exactly the rule the radios use.
    private val canCarry: suspend (WireEnvelope, RelayEnvelope) -> Boolean,
    // The mesh bridge: `MeshRouter.handleInbound`. Dedup, delivery, custody, and relay live behind it.
    private val deliver: suspend (WireEnvelope, RelayEnvelope, String) -> Unit,
    // The local blob store, for attachments (§4.5/§9.5). Null switches the attachment half off
    // entirely — the frame plane is unaffected, which is what keeps this additive.
    private val blobs: ScopeBlobs? = null,
    // Fired once an attachment's bytes are in hand, so screening, the message row and the UI run the
    // same path a radio pull takes. `InboundPipeline::onObtained` in the app.
    private val onAttachmentObtained: suspend (String) -> Unit = {},
    // Whether the radios are still carrying an attachment we hold, so its bytes need not cross the
    // Internet this round (§9.5). A deferral, never a veto — see [AttachmentDeferPolicy]. The default
    // never defers, which is the pre-gate behaviour every existing test asserts.
    private val deferAttachment: suspend (Scope, ScopeAttachments.Ref) -> Boolean = { _, _ -> false },
    // The commons half (§7.4): the joined rooms' own posts and their member roster. Null switches it off —
    // no commons scope is ever derived without it, so the frame plane is unaffected.
    private val commons: CommonsStore? = null,
    // Whether the mesh has pinned a key for this node id — the one question `canCarry` folds into a bare
    // `false`. A commons post from a sender not pinned *yet* is deferred for the profile that is on its
    // way, never quarantined as a forgery.
    private val hasKey: suspend (String) -> Boolean = { true },
    // The commons door: a verified post, the chat content it carries, its conversation, the source. Posts
    // never go through [deliver] — that door relays onto the radios, and a commons post lives on its spool.
    private val deliverCommons: suspend (RelayEnvelope, ChatContent, String, String) -> Unit = { _, _, _, _ -> },
    // A node's frame (profile or post) was pulled from a commons: (conversationId, nodeId).
    private val onCommonsMember: suspend (String, String) -> Unit = { _, _ -> },
    // A commons refused our own profile as tombstoned — count-evicted inside the republish window — so a
    // fresh stamp is owed now rather than at the 12 h mark.
    private val onOwnProfileTombstoned: suspend () -> Unit = {},
    // The set of DM/pair peers a connected spool is currently a path to, at [presenceLingerMs] — fired
    // (on the caller's coroutine) only when it changes: a fresh stamp, a connect or disconnect, or the tick
    // that lets one lapse (ADR 2026-09.y5f3). `MeshManager` turns it into the LoRa plane's Internet cover
    // and `AckSync`'s spool route. Never a delivery gate — nothing here is.
    private val onPresenceChanged: (Set<String>) -> Unit = {},
    private val presenceLingerMs: Long = SPOOL_COVER_MS,
    // Whether the phone has a validated route to the Internet right now (`InternetGate.isOnline`). Read
    // before every dial: with no route a dial can only fail, and would count against a relay that did
    // nothing. The default never says no, which is every test and every build without the gate.
    private val online: () -> Boolean = { true },
    private val metrics: MeshMetrics = MeshMetrics(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val jitter: () -> Long = { Random.nextLong(RECONNECT_JITTER_MS) },
) {
    private val workers = ConcurrentHashMap<String, Worker>()
    private val sealCache = SealCache(SEAL_CACHE_MAX)

    @Volatile
    private var lastPresent: Set<String> = emptySet()

    @Volatile
    private var session: CoroutineScope? = null

    @Volatile
    private var supervisor: Job? = null

    @Volatile
    private var scopes: List<Scope> = emptyList()

    /** Starts the plane on the mesh session scope. Restart-safe, like the other services under `MeshManager`. */
    fun start(session: CoroutineScope) {
        if (supervisor?.isActive == true) return
        this.session = session
        supervisor =
            session.launch {
                while (isActive) {
                    reconcile()
                    delay(RECONCILE_INTERVAL_MS)
                }
            }
    }

    /** Stops every worker. The session scope's own cancellation stops the supervisor. */
    fun stop() {
        supervisor?.cancel()
        supervisor = null
        workers.values.forEach { it.stop() }
        workers.clear()
        scopes = emptyList()
        session = null
        republishPresence()
    }

    /**
     * The DM/pair peers a connected spool is currently a path to — [spoolPresentPeers] over [status], the
     * one rule the presence dot reads, at the mesh's shorter window (ADR 2026-09.y5f3). Synchronous and
     * cheap: `AckSync` asks it at a ride's deadline and `MeshManager` on every change.
     */
    fun presentPeers(
        now: Long,
        lingerMs: Long = presenceLingerMs,
    ): Set<String> = spoolPresentPeers(status(), now, lingerMs)

    /**
     * Pushes [wire] — a frame this device will **not** custody — straight into every non-retiring DM or pair
     * scope shared with [peerId], on every connected spool (§9.4 C-9.4-3, ADR 2026-09.y5f3). The one push
     * on this plane not sourced from custody, and it exists for exactly one frame: a room post's sealed
     * `relay = false` delivery tick, whose custody row per acker aa27 refused. The caller guarantees the
     * **signed** form — `ScopeCrypto.seal` takes the 64-byte signature, so ADR 059's unsigned tick cannot
     * ride here. Returns whether at least one spool accepted it; false leaves the caller its wire.
     */
    suspend fun pushDirect(
        peerId: String,
        env: RelayEnvelope,
        wire: WireEnvelope,
    ): Boolean {
        val targets = scopes.filter { it.peerId == peerId && !it.retiring }
        if (targets.isEmpty()) return false
        var any = false
        for (worker in workers.values) if (worker.pushDirect(targets, env, wire)) any = true
        return any
    }

    /** Fires [onPresenceChanged] when the present set differs from what was last published. */
    @Synchronized
    private fun republishPresence() {
        val current = if (workers.isEmpty()) emptySet() else presentPeers(clock())
        if (current == lastPresent) return
        lastPresent = current
        onPresenceChanged(current)
    }

    /**
     * Local custody changed (we originated or carried a frame): wake every worker so an eligible frame is
     * pushed now rather than at the next tick. Cheap and idempotent — the heal loop re-derives the diff.
     */
    fun onCustodyChanged() {
        workers.values.forEach { it.markAllDirty() }
    }

    /**
     * The validated default network became a different network: every worker that is not connected dials
     * again now instead of sitting out the rest of a backoff earned against the old route — leaving a
     * Wi-Fi that swallowed the socket recovers in a second, not at the 60 s ceiling. A worker with a live
     * session is left alone; if the old route died under it, the socket's own ping notices and the
     * ordinary reconnect runs on the new one. Never fired for the same network re-validating, and never a
     * reason to bypass a spool's `Retry-After` (see [Worker.runLoop]).
     */
    fun onRouteChanged() {
        workers.values.forEach { it.redialNow() }
    }

    fun status(): List<SpoolStatus> {
        val current = scopes
        return workers.values.map { it.status(current) }
    }

    /**
     * The scope table's inputs changed under us (a commons joined or left): re-derive it now instead of at
     * the next 15 s tick, so a freshly pasted invite is subscribed while the user is still looking at it.
     */
    fun onScopeTableChanged() {
        session?.launch { reconcile() }
    }

    /** Starts a worker per configured URL, stops the ones that fell out of the config, refreshes scopes. */
    private suspend fun reconcile() {
        val host = session ?: return
        val wanted = urls().toSet()
        workers.keys.filterNot { it in wanted }.forEach { workers.remove(it)?.stop() }
        if (wanted.isEmpty()) {
            scopes = emptyList()
            return
        }
        scopes = registry.scopes(clock())
        val live = scopes.mapTo(HashSet()) { it.idHex }
        wanted.forEach { url ->
            val worker = workers.computeIfAbsent(url) { Worker(it) }
            val changed = worker.adoptScopes(live)
            worker.ensureRunning(host)
            // Only a changed table wakes the heal loop — a scope that appeared needs its SUB now. An
            // unchanged one used to wake every worker every 15 s regardless, and each wake ran a full
            // custody round per scope: four times the 60 s tick, idle or converged, for nothing.
            if (changed) worker.wake()
        }
    }

    /** Every custody frame that may ride [scope], keyed by the blob id it deterministically seals to. */
    private suspend fun held(
        scope: Scope,
        frames: List<CarriedFrame>,
    ): Map<String, Held> {
        val me = selfId()
        return frames
            .filter { carried ->
                // The one asymmetric rule (§7.4): a commons takes anyone's profile in, but only our own out.
                if (scope.commonsId != null) {
                    ScopeFrames.pushableToCommons(carried.envelope, me, scope.id)
                } else {
                    ScopeFrames.eligibleFor(carried.envelope, me, scope)
                }
            }.associate { carried ->
                val sealed = sealCache.get(scope, carried)
                hex(sealed.blobId) to Held(sealed, carried)
            }
    }

    /** A local custody frame together with its sealed form for one scope. */
    private class Held(
        val sealed: ScopeFrames.Sealed,
        val carried: CarriedFrame,
    )

    /** What a pull round did: the ids the spool no longer had, and how many blobs the mesh door took. */
    private class Pulled(
        val gone: Set<String>,
        val accepted: Int,
    )

    /**
     * One spool: its connection, its per-scope digest anchors, and its own invalid set — §9.3 is
     * explicitly per-spool, so a garbage blob at one spool cannot poison the others.
     */
    @Suppress("TooManyFunctions", "LargeClass") // one small method per protocol step; the §7.4 door sits beside the §9.4 one on purpose
    private inner class Worker(
        val url: String,
    ) {
        private val spoolDigests = ConcurrentHashMap<String, Long>()
        private val spoolCounts = ConcurrentHashMap<String, Int>()
        private val localDigests = ConcurrentHashMap<String, Long>()
        private val localCounts = ConcurrentHashMap<String, Int>()
        private val invalid = ConcurrentHashMap<String, LinkedHashSet<String>>()
        private val accepted = ConcurrentHashMap<String, LinkedHashSet<String>>()

        // §9.6's accounted set, per scope: blob id hex → its digest contribution. Holds the fold value
        // rather than the id so a round costs no hex round-trip and a prune is a key removal. Unlike
        // [accepted] this SURVIVES a reconnect — that is the whole point of it (ADR 062).
        private val accounted = ConcurrentHashMap<String, LinkedHashMap<String, Long>>()
        private val stamps = ConcurrentHashMap<String, PowStamp>()

        // scopeHex → when a SUB for it may be tried again, after the spool refused it (`quota`: the spool
        // is at its `maxScopes`; `pow`: it wants a stamp we could not or would not mine). Without this the
        // refused scope is re-SUBbed every tick for as long as the spool stays full — and a `pow` refusal
        // re-mines 2^26 hashes each time, which is a battery attack that costs the spool one record.
        // Survives a reconnect: the spool's fullness is not our event either.
        private val refusedUntil = ConcurrentHashMap<String, Long>()

        // §9.5's quarantine, per scope: attachment hash → when it was quarantined. Timed rather than
        // pruned to the listing like [invalid], because presence is discovered by asking, never listed:
        // the entry expires with the scope TTL, which is also when the spool's poisoned copy is gone.
        private val invalidAttachments = ConcurrentHashMap<String, LinkedHashMap<String, Long>>()

        // A commons' bounds as the spool pinned them in its DIGEST (§7.4) — the truth this worker sizes
        // events and dead-on-arrival against, since what the scope table declared was only a guess.
        private val pinnedBounds = ConcurrentHashMap<String, ScopeBounds>()

        // Commons posts held back because their author is not pinned yet, per scope: blob id hex → how many
        // rounds it has waited. Bounded like the other sets; a post whose profile never comes is quarantined
        // after MAX_PARK_ROUNDS rather than re-pulled forever.
        private val parked = ConcurrentHashMap<String, LinkedHashMap<String, Int>>()

        // Own-profile blobs a commons has tombstoned that we have already asked for a re-stamp over.
        private val reportedTombstones = ConcurrentHashMap<String, LinkedHashSet<String>>()

        // Ids a commons has tombstoned that we still hold — evicted by the room's own count rule while our
        // custody or outbox keeps the frame for its own TTL. Left in the local fold they would keep the
        // room unconverged (and every round listing) until we happened to drop them; a tombstone is final.
        private val tombstonesSeen = ConcurrentHashMap<String, LinkedHashSet<String>>()

        // scopeHex → when this scope's own peer last put something recent into it (see [notePeerPresence]).
        // The ONLY thing on this plane that says anything about the peer rather than about the spool, and the
        // reason it exists: a scope is derived from the pairwise ratchet root, so it is subscribed and
        // converged whether or not its peer has been online this month. Survives a reconnect — losing the
        // socket is our event, not theirs. Since ADR 2026-09.y5f3 it is also what [presentPeers] reads for the
        // LoRa plane's Internet cover and `AckSync`'s spool route — still never a gate on anything inbound.
        private val peerSeenAt = ConcurrentHashMap<String, Long>()

        // Serialises a heal round against a direct push (ADR 2026-09.y5f3): a push that lands between a
        // round's `accountedFor` read and its LIST would be pulled straight back into custody — the one
        // outcome the direct push exists to avoid. Never held across a call back into the mesh that could
        // push (nothing on the inbound path does; `AckSync` pushes from its own flush coroutine).
        private val round = Mutex()

        // What the next round must look at. A scope is dirty when its spool anchor moved ([handleDigest]),
        // it took a delivery ([handleEvent], a pull) or a direct push ([pushDirect]); every scope is dirty
        // when local custody changed ([markAllDirty]) and on the 60 s tick, which covers a change with no
        // event of its own — a swept frame, a lapsed attachment deferral, a stamp ageing out. A clean scope
        // costs a round nothing: no custody read, no fold, no `ahave`.
        private val dirty: MutableSet<String> = ConcurrentHashMap.newKeySet()
        private val allDirty = AtomicBoolean(false)
        private val tickDue = AtomicBoolean(false)

        // The scope table as of the last reconcile: a change to it is what wakes this worker from there.
        @Volatile
        private var lastLive: Set<String> = emptySet()

        // scopeHex → attachment hash → when a round last found nothing left to do for it: fetched, pushed
        // whole, dead, or past its frame's TTL. Skipped until a timed round finds the entry older than
        // ATTACHMENT_RECHECK_MS (the spool may have evicted it since), and dropped with the connection.
        private val settledAttachments = ConcurrentHashMap<String, LinkedHashMap<String, Long>>()

        // scopeHex → the one pending re-look for an attachment a round could not finish (the spool has no
        // chunks yet, an upload half done, a deferral in force) — the old 15 s reconcile's cadence, paid
        // only by a scope that has such an attachment.
        private val attachmentRetries = ConcurrentHashMap<String, Job>()

        // Partially-received attachments, keyed "scopeHex|aHash". In memory by design (§9.5): the
        // plane persists nothing, and the spool's bitmap makes a restarted download cheap to resume.
        private val assemblies = ConcurrentHashMap<String, ScopeAttachments.Assembly>()
        private val wakeup = Channel<Unit>(Channel.CONFLATED)

        // A route change asking for the next dial now rather than at the end of the backoff (see
        // [onRouteChanged]). Conflated: two changes during one wait are one reason to dial.
        private val redial = Channel<Unit>(Channel.CONFLATED)

        @Volatile
        private var job: Job? = null

        @Volatile
        private var connection: SpoolConnection? = null

        // The most recent `err` code this spool answered with, for diagnostics. Cleared on a clean
        // connect so a stale refusal doesn't outlive the condition that caused it — and only then, so a
        // dial verdict (`unreachable`, `no_hello`) holds through the next attempt rather than flickering
        // back to "connecting" for the seconds the socket is being tried again.
        @Volatile
        private var lastError: String? = null

        // Sessions in a row that ended without a completed hello (see [SpoolStatus.dialFailures]).
        @Volatile
        private var dialFailures = 0

        // When the last dial began (see [SpoolStatus.lastDialAt]).
        @Volatile
        private var lastDialAt: Long? = null

        // What this spool answered at `/source` during the current session (§13 offer, [SpoolSoftware]).
        // Fetched once per handshake rather than remembered across them, because a redeploy is exactly what
        // drops the connection — so a build shown beside "connected" is the build that is connected.
        @Volatile
        private var software: SpoolSoftware? = null

        // A `Retry-After` the last dial came back with, in ms, consumed by the next backoff. Written and
        // read only by session()/runLoop(), which are the same coroutine.
        private var retryFloorMs = 0L

        // The fault *we* ended the last session on (see [SpoolConnection.fault]), carried through the next
        // one so [lastError] keeps saying it: a spool that answers the hello and nothing else would
        // otherwise read as connected for the minutes it takes to strike out again, and unresponsive for
        // the two seconds in between. Cleared once a session ends on the spool's terms instead.
        private var carriedFault: String? = null

        fun ensureRunning(host: CoroutineScope) {
            if (job?.isActive == true) return
            job = host.launch { runLoop() }
        }

        fun stop() {
            job?.cancel()
            job = null
            cancelAttachmentRetries()
            connection?.close(SpoolCloseCode.NORMAL, "stopping")
            connection = null
        }

        private fun cancelAttachmentRetries() {
            attachmentRetries.values.forEach { it.cancel() }
            attachmentRetries.clear()
        }

        fun wake() {
            wakeup.trySend(Unit)
        }

        /** [scopeHex] has something to look at: run a round for it now. */
        fun markDirty(scopeHex: String) {
            dirty += scopeHex
            wake()
        }

        /** Every scope has something to look at — local custody moved. */
        fun markAllDirty() {
            allDirty.set(true)
            wake()
        }

        /**
         * Takes the reconciled scope table: [forgetScopesNotIn] for what left, and whether the table differs
         * from the one this worker last adopted — the only reason a reconcile wakes the heal loop.
         */
        fun adoptScopes(live: Set<String>): Boolean {
            forgetScopesNotIn(live)
            dirty.retainAll(live)
            synchronized(settledAttachments) { settledAttachments.keys.retainAll(live) }
            val changed = live != lastLive
            lastLive = live
            return changed
        }

        /** Asks for the next dial now, unless this worker already has a spool talking to it. */
        fun redialNow() {
            if (connection?.isReady != true) redial.trySend(Unit)
        }

        /**
         * Drops every per-scope set for a scope that left the table — a commons left, a retiring DM scope
         * past its drain window. The sets are keyed by scope and otherwise never pruned, and for a commons
         * the accounted set is load-bearing the other way: a room left and rejoined on the same connection
         * would otherwise never re-pull what it had already accounted, and its history would stay gone. The
         * spool's own subscription outlives this (there is no `unsub` record); [mine] keeps its events out.
         */
        fun forgetScopesNotIn(live: Set<String>) {
            for (map in listOf(spoolDigests, spoolCounts, localDigests, localCounts, peerSeenAt, stamps, pinnedBounds, refusedUntil)) {
                map.keys.retainAll(live)
            }
            for (sets in listOf(invalid, accepted, reportedTombstones, tombstonesSeen)) {
                synchronized(sets) { sets.keys.retainAll(live) }
            }
            synchronized(accounted) { accounted.keys.retainAll(live) }
            synchronized(invalidAttachments) { invalidAttachments.keys.retainAll(live) }
            synchronized(parked) { parked.keys.retainAll(live) }
            // And the connection's own record, or a scope that comes back is never SUBbed again and its heal
            // round waits forever for a digest that was already answered before it left.
            connection?.retainSubscriptions(live)
        }

        fun status(all: List<Scope>): SpoolStatus =
            SpoolStatus(
                url = url,
                // The hello, not the socket: see [SpoolStatus.connected].
                connected = connection?.isReady == true,
                powBits = connection?.powBits ?: 0,
                lastError = lastError,
                dialFailures = dialFailures,
                lastDialAt = lastDialAt,
                // Gated on the whole capability, not the single field: §7.3's three limits arrive
                // together or not at all, and a partial set means we must send no attachment record.
                maxAttachBytes = connection?.limits?.takeIf { it.attachments }?.maxAttachBytes,
                commons = connection?.commons,
                software = connection?.let { software },
                scopes =
                    mine(all, connection).map { scope ->
                        ScopeStatus(
                            scopeHex = scope.idHex,
                            label = scope.label,
                            localCount = localCounts[scope.idHex] ?: 0,
                            spoolCount = spoolCounts[scope.idHex] ?: 0,
                            converged = spoolDigests[scope.idHex] == localDigests[scope.idHex],
                            invalidCount = invalid[scope.idHex]?.size ?: 0,
                            retiring = scope.retiring,
                            accountedCount = accountedFor(scope.idHex).size,
                            peerSeenAt = peerSeenAt[scope.idHex],
                        )
                    },
            )

        /**
         * Asks this spool what it is running (spec-external, [SpoolSoftware]) and files the answer against
         * the session that asked. The identity check is what keeps a straggling answer — a slow route, a
         * session that ended while the GET was in flight — off the *next* connection's row.
         */
        private suspend fun readSoftware(conn: SpoolConnection) {
            val answer = dialer.fetchSoftware(url)
            if (connection === conn) software = answer
        }

        private suspend fun runLoop() {
            var failures = 0
            while (currentCoroutineContext().isActive) {
                if (!online()) {
                    // No validated route: a dial can only fail, and would be counted against a relay that
                    // did nothing — the relay row already says the phone is offline. Wait for a new network
                    // (`onRouteChanged` → [redialNow]) or look again on a slow cadence: the same network
                    // re-validating (a captive login) fires no route change.
                    withTimeoutOrNull(OFFLINE_RECHECK_MS) { redial.receive() }
                    continue
                }
                val reached = session()
                failures = if (reached) 0 else failures + 1
                // Doubling to a minute for the first ten failures, then on to fifteen: a relay that is simply
                // gone used to be dialled ~1,440 times a day at the 60 s ceiling, each one a DNS lookup, a
                // TCP connect and a TLS handshake on a radio that could otherwise sleep.
                val wait = SpoolBackoffPolicy.waitMs(failures)
                // A spool refusing us at the transport layer (it is at its connection cap) says how long
                // to stay away. Honour it as a floor only: replacing the backoff would forget how long we
                // have already been failing, and dropping the jitter would re-synchronise every client one
                // full spool turned away in the same instant — the exact population it was trying to shed.
                val floor = retryFloorMs
                retryFloorMs = 0L
                if (floor > 0) {
                    // The spool's own ask is never shortened: a new network changes nothing about its load.
                    delay(maxOf(wait, floor) + jitter())
                } else if (withTimeoutOrNull(wait + jitter()) { redial.receive() } != null) {
                    // A new default network is a new situation: the failures counted against the old route
                    // say nothing about this one, so the backoff starts over as well.
                    failures = 0
                }
            }
        }

        /** One connection lifetime. Returns whether the handshake completed — that drives the backoff. */
        private suspend fun session(): Boolean {
            val host = this@ScopeSync.session ?: return false
            lastDialAt = clock()
            val socket = dialer.dial(url)
            if (socket == null) {
                lastError = UNREACHABLE
                dialFailures++
                return false
            }
            val conn = connect(socket)
            software = null
            connection = conn
            val pump = host.launch { for (bytes in socket.incoming) conn.onMessage(bytes) }
            // The socket dying before (or instead of) the spool's hello must resolve the handshake rather
            // than park this worker forever; so must a spool that opens the socket and then says nothing.
            // The wake is what ends the session *now* rather than at the next tick: the loop below is
            // otherwise asleep in `wakeup.receive()` for up to a minute after the socket is already gone.
            pump.invokeOnCompletion {
                conn.onClosed()
                wake()
            }
            val ready = withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS) { conn.awaitReady() } == true
            // A socket that opened and never carried a hello is dropped as our own verdict, through
            // `abort` like every client-side close (ADR 2026-09.amzn), so the backoff grows and the status
            // says so instead of reading "connecting" forever. Both guards matter: the dialer writes
            // `closeReason` before the pump's completion closes the connection, so `isOpen` alone would
            // relabel a transport death as this fault.
            if (!ready && conn.isOpen && socket.closeReason == null) conn.abort(NO_HELLO)
            if (ready) {
                dialFailures = 0
                // A route change that landed during this handshake asked for a dial that this hello has
                // now answered; left in the channel it would fire an immediate re-dial, with the backoff
                // reset, whenever this session ends — hours from now, against a route that may be fine.
                redial.tryReceive()
                // A completed hello is the whole condition behind a dial verdict, so it retires here — not
                // on an answered request like `unresponsive`, which an idle converged session never makes.
                retireFault(NO_HELLO)
                // A fresh handshake clears a stale refusal — unless the refusal is still in force: a fault
                // of our own carried from the last session, or a scope the spool refused and we parked.
                if (carriedFault == null && refusedUntil.values.none { it > clock() }) lastError = null
                // Off the session's own path: this is one diagnostics row, and a spool that is slow to serve
                // an HTTP route must not hold up the heal loop it answers records on.
                host.launch { readSoftware(conn) }
                subscribe(conn, mine(scopes, conn))
                republishPresence() // a connected spool is what makes a stamped peer count
                // The tick is a mark like any other, so a scope busy with events still gets its full pass and
                // a woken round can tell it was not the tick (the rig's clock is frozen, so a deadline could
                // not be computed from it). The anchors were cleared with the last session, so the SUB answers
                // mark every scope dirty and the first round follows them.
                val ticker =
                    host.launch {
                        while (isActive) {
                            delay(TICK_INTERVAL_MS)
                            tickDue.set(true)
                            wake()
                        }
                    }
                try {
                    while (pump.isActive && currentCoroutineContext().isActive) {
                        wakeup.receive()
                        // A conflated token is handed over without suspending; a round that marks its own
                        // scope again would otherwise run back to back and starve the pump and the ticker.
                        yield()
                        healAll(conn, timed = tickDue.getAndSet(false))
                    }
                } finally {
                    ticker.cancel()
                }
            }
            pump.cancel()
            return endSession(socket, conn, ready)
        }

        /**
         * The teardown half of [session]: files the diagnosis this connection produced, then drops every
         * per-connection set. Returns whether the session counted as reached — that drives the backoff.
         */
        private fun endSession(
            socket: SpoolSocket,
            conn: SpoolConnection,
            ready: Boolean,
        ): Boolean {
            conn.onClosed()
            // A close code is the only explanation we get for auth/version/abuse rejections, and the
            // handshake itself never fails "loudly" — record it before the socket is discarded.
            socket.closeReason?.let { lastError = it }
            // Unless we ended it ourselves: the spool echoes our close back as a bare `close 1000`, which
            // would bury the one diagnosis this connection produced. Read after the close reason on purpose.
            val fault = conn.fault
            if (fault != null) {
                lastError = fault
            } else if (lastError == carriedFault) {
                lastError = socket.closeReason // the carried fault is over: this session ended on the spool's terms
            }
            carriedFault = fault
            retryFloorMs = socket.retryAfterMs ?: 0L
            socket.close(SpoolCloseCode.NORMAL, "done")
            if (!ready) dialFailures++
            connection = null
            republishPresence() // and a disconnected one covers nobody
            spoolDigests.clear()
            pinnedBounds.clear()
            // The per-connection race guard goes; the §9.6 accounted set deliberately does NOT. Dropping
            // `accepted` is what lets a custody wipe re-converge by the ordinary route (everything still
            // live is simply re-pulled); dropping `accounted` only re-pulls what can never be held, which
            // is the reconnect storm ADR 062 is about.
            accepted.clear()
            // What a round settled was settled against THIS spool's chunk store; the next session asks again.
            synchronized(settledAttachments) { settledAttachments.clear() }
            cancelAttachmentRetries()
            // A session we had to abort is not "reached": the backoff must grow, or an unresponsive spool
            // is dialled again a second later and the whole strike budget spent on it once a minute.
            return ready && fault == null
        }

        private fun connect(socket: SpoolSocket) =
            SpoolConnection(
                url = url,
                link = socket,
                onDigest = { handleDigest(it) },
                onEvent = { handleEvent(it) },
                onScopeError = { scopeHex, code, retryMs -> handleScopeError(scopeHex, code, retryMs) },
            )

        /**
         * SUBs [wanted], as much of it as the spool can take. `maxScopes` is the spool's *total* scope
         * count (a new one past it is refused `quota`), so the cap here is a ceiling rather than the
         * guarantee — [refusedUntil] is what stops a refused scope being asked for again every tick. A
         * commons goes first: it is what a private relay exists for, and the one scope nobody else can
         * carry for us.
         */
        private suspend fun subscribe(
            conn: SpoolConnection,
            wanted: List<Scope>,
        ) {
            val now = clock()
            val room = (conn.limits?.maxScopes ?: Int.MAX_VALUE).coerceAtLeast(1) - conn.subscribedCount
            val batch =
                wanted
                    .filterNot { (refusedUntil[it.idHex] ?: 0L) > now }
                    .sortedBy { it.commonsId == null }
                    .take(room.coerceAtLeast(0))
            if (batch.isEmpty()) return
            val sent = conn.sub(batch.map { ScopeSub(scope = it.id, bounds = declaredBounds(it, conn), pow = stampFor(it, conn.powBits)) })
            // A SUB the record layer refused to send is a spool advertising a `maxRecord` too small to
            // carry it — and then too small to carry any blob either. Without this the relay reads as
            // connected forever while subscribing to nothing.
            if (!sent && conn.isOpen) conn.abort(SpoolErrCode.TOO_LARGE)
            if (sent) retireFault(SpoolErrCode.TOO_LARGE)
        }

        /**
         * Drops a carried fault (see [carriedFault]) once the condition behind it is demonstrably over:
         * a SUB that went through retires `too_large`, an answered request retires `unresponsive`.
         */
        private fun retireFault(fault: String) {
            if (carriedFault != fault) return
            carriedFault = null
            if (lastError == fault) lastError = null
        }

        /**
         * The scopes this worker carries: every scope, minus a commons bound to another relay, minus a
         * commons at a spool that advertised none — a SUB for it there would read as an unknown scope and
         * meet the §6.4 creation gates (PoW, the new-scope bucket) for a room that does not exist.
         */
        private fun mine(
            all: List<Scope>,
            conn: SpoolConnection?,
        ): List<Scope> = all.filter { it.belongsAt(url) && (it.commonsId == null || conn?.commons != null) }

        /**
         * What we declare at SUB. For a commons the spool ignores the declaration and pins its own (§7.4),
         * so we declare exactly what its HELLO advertised — then [SpoolConnection.inboundCap] and the pinned
         * truth agree and no honest event is dropped as oversize.
         */
        private fun declaredBounds(
            scope: Scope,
            conn: SpoolConnection,
        ): ScopeBounds {
            if (scope.commonsId == null) return scope.bounds
            val advertised = conn.commons ?: return scope.bounds
            return ScopeBounds(maxFrames = advertised.maxFrames, ttlMs = advertised.ttlMs, maxBlob = advertised.maxBlob)
        }

        /** The bounds to size against: the spool's pinned truth for a commons once a DIGEST has said, else what we declared. */
        private fun boundsFor(scope: Scope): ScopeBounds =
            if (scope.commonsId !=
                null
            ) {
                pinnedBounds[scope.idHex] ?: scope.bounds
            } else {
                scope.bounds
            }

        /**
         * One round: presence and subscriptions every time, then a heal for each scope that is dirty — or
         * every scope when [timed] (the 60 s tick) or when local custody moved. The marks are drained
         * BEFORE the custody read: a mark that lands after the drain keeps its token in the conflated wake
         * channel and is read next round; one that lands before it is covered by this read.
         */
        private suspend fun healAll(
            conn: SpoolConnection,
            timed: Boolean,
        ) {
            republishPresence() // every round, and the tick is one: that is what lets a stamp lapse out of the present set
            if (conn.answered) retireFault(SpoolConnection.UNRESPONSIVE)
            val current = mine(scopes, conn)
            // Sub-before-use is per connection, and the scope table changes as sessions are established.
            subscribe(conn, current.filterNot { conn.isSubscribed(it.idHex) })
            val all = allDirty.getAndSet(false) || timed
            val due = if (all) current.onEach { dirty.remove(it.idHex) } else current.filter { dirty.remove(it.idHex) }
            if (due.isEmpty()) return
            // One custody read serves every scope in the round (it used to be one per scope per round —
            // a full table decode, four times a minute, converged or not).
            val custody = store.liveFrames(clock())
            due.forEach { heal(conn, it, custody, timed) }
        }

        /** §9.1: one scope's heal round against this spool. A no-op while the two digests agree. */
        private suspend fun heal(
            conn: SpoolConnection,
            scope: Scope,
            custody: List<CarriedFrame>,
            timed: Boolean,
        ) = round.withLock { healLocked(conn, scope, custody, timed) }

        private suspend fun healLocked(
            conn: SpoolConnection,
            scope: Scope,
            custody: List<CarriedFrame>,
            timed: Boolean,
        ) {
            // The round's custody read serves both halves: the frames, and the attachments they
            // reference. Attachments are healed even when the frame digests already agree — they are
            // outside the digest by design (§6.5), so it can never signal them.
            val frames = if (scope.commonsId != null) commonsFrames(scope.commonsId, custody) else custody
            val dead = if (scope.commonsId != null) tombstonesSeen[scope.idHex].orEmpty() else emptySet()
            val local = held(scope, frames).filterKeys { it !in dead }
            // §9.6: fold the accounted band in beside what we hold, so a scope whose spool keeps blobs our
            // custody has aged out can still reach digest equality. Never both — an id that came back into
            // custody would otherwise XOR itself out of the fold and diverge us permanently.
            val accountedHere = accountedFor(scope.idHex).filterKeys { it !in local }
            val localFold =
                ScopeCrypto.scopeDigest(local.values.map { it.sealed.blobId }) xor
                    accountedHere.values.fold(0L) { acc, contribution -> acc xor contribution }
            localDigests[scope.idHex] = localFold
            localCounts[scope.idHex] = local.size + accountedHere.size
            val anchor = spoolDigests[scope.idHex] ?: return // the SUB hasn't been answered yet
            if (anchor == localFold) {
                if (scope.commonsId == null) healAttachments(conn, scope, frames, timed)
                return
            }
            reconcileListing(conn, scope, local, accountedHere)
            // Text only in this revision: a commons never exchanges attachment records, whatever its HELLO says.
            if (scope.commonsId == null) healAttachments(conn, scope, frames, timed)
        }

        /**
         * §9.1's list → diff → pull → push → re-anchor, for a scope whose two digests disagree. [local] and
         * [accountedHere] are the round's own view, computed before the LIST so the diff is against what the
         * fold compared.
         */
        private suspend fun reconcileListing(
            conn: SpoolConnection,
            scope: Scope,
            local: Map<String, Held>,
            accountedHere: Map<String, Long>,
        ) {
            val listing = conn.list(scope.id)
            if (listing == null) {
                // Unanswered on a live connection: ask again straight away, so a spool that went quiet
                // strikes out on the connection's silent-request rule in three request timeouts, not three
                // ticks. On a closed one the answer is instant and the session is already ending — the
                // pump's completion is the wake that ends it; marking here would spin ahead of it.
                if (conn.isOpen) markDirty(scope.idHex)
                return
            }
            if (!plausible(scope, listing)) return // the tick is the retry; asking again now would only spin
            val quarantined = invalid[scope.idHex].orEmpty()
            val spoolIds = listing.blobIds.associateBy { hex(it) }
            val tombstoned = listing.tombstones.mapTo(mutableSetOf()) { hex(it) }
            val learnt = noteListing(scope, local, spoolIds.keys, tombstoned, accountedHere.keys, quarantined)
            // Skip what we already processed on this connection, and what we have accounted for across
            // every connection. The scope TTL (48 h) deliberately outlives mesh custody (24 h), so a frame
            // we delivered and then swept still sits at the spool for another day: it is absent from
            // `local` forever, and without these two sets the heal round re-pulls it every tick for that
            // whole second day — silently, since `accept` short-circuits before the counters move.
            val processed = acceptedFor(scope.idHex)
            val wanted =
                spoolIds
                    .filterKeys { it !in local && it !in quarantined && it !in processed && it !in accountedHere }
                    .values
                    .toList()
            val pulled = pullMissing(conn, scope, wanted)
            val pushed = pushMissing(conn, scope, local, spoolIds.keys, tombstoned, quarantined)
            reanchor(scope, spoolIds, pulled.gone, pushed)
            // A round that moved the local view — frames pulled into custody, frames pushed, a tombstone
            // learnt for a frame we hold, an accounted or quarantined id the spool no longer lists — gets
            // one more look: the local fold above predates every one of those, and
            // the next round is what shows the anchor now agrees (the status reads converged from it). A
            // round that only found ids it may not take — quarantined, parked — does not, or it would spin;
            // the tick is that case's retry, as it always was.
            if (pulled.accepted > 0 || pushed.isNotEmpty() || learnt) markDirty(scope.idHex)
        }

        /**
         * What a listing teaches about the local view, folded before the pull: the tombstones of frames we
         * hold (a commons), and the accounted and quarantined ids the spool no longer lists. True when any
         * of it changed — the local fold a round computed before the LIST is then stale, and the round
         * re-marks its scope so the next one shows the anchor agrees.
         *
         * The listing is the scope's whole live set, so it is also the only chance to notice that an
         * accounted blob finally expired at the spool. Keeping it would leave our fold carrying an id the
         * spool no longer counts — permanent divergence, mirrored. Only what was accounted BEFORE this
         * listing was taken, though: the pump accounts a live event off the round's lock, and a burst of
         * pushes landing while the LIST was in flight would otherwise be pruned as "no longer listed" the
         * moment they were accounted. The invalid set the same way: an id the spool no longer lists can
         * never be re-pulled, so its quarantine has done its work — and keeping it would deny a blob a
         * member may yet re-push clean after the garbage copy expired.
         */
        @Suppress("LongParameterList") // the listing's three sets against the round's two; a holder would only relocate them
        private suspend fun noteListing(
            scope: Scope,
            local: Map<String, Held>,
            spoolIds: Set<String>,
            tombstoned: Set<String>,
            accountedBefore: Set<String>,
            quarantined: Set<String>,
        ): Boolean {
            var learnt = false
            if (scope.commonsId != null) {
                local.keys.filter { it in tombstoned }.forEach { if (remember(tombstonesSeen, scope.idHex, it)) learnt = true }
                noteOwnProfileTombstones(scope, local, tombstoned)
            }
            if (pruneAccounted(scope.idHex, spoolIds, accountedBefore)) learnt = true
            if (pruneInvalid(scope.idHex, spoolIds, quarantined)) learnt = true
            return learnt
        }

        /**
         * Whether a listing is one a conforming spool could have sent: bounded by count, not only by the
         * record cap. A live set fits the applied `maxFrames` and the tombstones §12's `max(2 × maxFrames,
         * 1024)`, while the 128 KiB record cap alone admits ~4000 ids — enough to churn the per-scope sets
         * every round and pin this worker in pull round trips. Refused whole rather than truncated: a
         * partial view would re-anchor us on it and re-list forever. The id threshold is the worker's own
         * tracking bound, which is what makes "a listing never exceeds what the sets can hold" true by
         * construction — and why it is not our *declared* `maxFrames`, which a newer member's SUB may
         * legitimately have raised at the spool (S-6.2-2).
         */
        private fun plausible(
            scope: Scope,
            listing: SpoolReply.Listing,
        ): Boolean {
            val fits =
                listing.blobIds.size <= accountBound(scope.idHex) &&
                    listing.tombstones.size <= tombstoneBound(boundsFor(scope).maxFrames)
            if (!fits) {
                metrics.onSpoolError()
                lastError = OVERLONG_LISTING
            }
            return fits
        }

        /**
         * Our profile was count-evicted from a busy commons (§7.4): its tombstone outlives the 12 h republish,
         * so the same bytes are refused for a day and a fresh stamp is owed now. Reported once per blob — the
         * old frame stays in custody (and so in `local`) until its own TTL, and every round would otherwise
         * ask for another stamp.
         */
        private suspend fun noteOwnProfileTombstones(
            scope: Scope,
            local: Map<String, Held>,
            tombstoned: Set<String>,
        ) {
            val fresh =
                local
                    .filter { (id, held) -> id in tombstoned && held.carried.envelope.type == FrameType.PROFILE }
                    .keys
                    .filter { remember(reportedTombstones, scope.idHex, it) }
            if (fresh.isNotEmpty()) onOwnProfileTombstoned()
        }

        /**
         * What this device pushes into a commons (§7.4): its own custodied profile — the frame that pins us
         * for every other member, kept live by the 12 h republish — and its own posts from the outbox. Never
         * custody at large: the room is not a mirror of everything this device carries.
         */
        private suspend fun commonsFrames(
            conversationId: String,
            custody: List<CarriedFrame>,
        ): List<CarriedFrame> {
            val me = selfId()
            val ownProfile = custody.filter { it.envelope.type == FrameType.PROFILE && it.envelope.senderId == me }
            return ownProfile + commons?.frames(conversationId).orEmpty()
        }

        /** Pulls the ids we lack, in `maxPull` batches (an overshoot is silently truncated, never an error). */
        private suspend fun pullMissing(
            conn: SpoolConnection,
            scope: Scope,
            ids: List<ByteArray>,
        ): Pulled {
            val gone = mutableSetOf<String>()
            var accepted = 0
            if (ids.isEmpty()) return Pulled(gone, accepted)
            val cap = (conn.limits?.maxPull ?: DEFAULT_MAX_PULL).coerceAtLeast(1)
            // A commons post pulled ahead of its author's profile in the same listing (the listing is
            // unordered) is held for one more try at the end of the round, once the profile has had its
            // chance to land — the common case for a backlog pull into a room someone else has been using.
            val deferred = mutableListOf<SpoolBlob>()
            ids.chunked(cap).forEach { batch ->
                val outcome = conn.pull(scope.id, batch) ?: return Pulled(gone, accepted)
                outcome.blobs.forEach { blob ->
                    when (accept(scope, blob.blobId, blob.data, Source.PULL)) {
                        Accept.PARKED -> deferred.add(blob)
                        Accept.DELIVERED -> accepted++
                        else -> Unit
                    }
                }
                outcome.missing.forEach { gone.add(hex(it)) }
                // An id we asked for and got an unusable answer for is quarantined, not merely dropped
                // (§9.3) — and deliberately not added to `gone`, since the spool still holds it and
                // `reanchor` must keep folding it into the digest we compare against.
                outcome.oversize.forEach { quarantine(scope, hex(it)) }
            }
            deferred.forEach { blob ->
                if (accept(scope, blob.blobId, blob.data, Source.PULL, retry = true) ==
                    Accept.DELIVERED
                ) {
                    accepted++
                }
            }
            return Pulled(gone, accepted)
        }

        /** Pushes what the spool lacks, skipping tombstones, quarantine, oversize and expired frames (§9.2). */
        private suspend fun pushMissing(
            conn: SpoolConnection,
            scope: Scope,
            local: Map<String, Held>,
            spoolIds: Set<String>,
            tombstoned: Set<String>,
            quarantined: Set<String>,
        ): List<ByteArray> {
            if (scope.retiring) return emptyList() // a retiring scope is drained, never refilled
            val pushed = mutableListOf<ByteArray>()
            for (entry in pushable(conn, scope, local, spoolIds, tombstoned, quarantined)) {
                val reply = conn.push(scope.id, entry.sealed.blobId, entry.sealed.blob, stampFor(scope, conn.powBits))
                if (reply is SpoolReply.Ok) {
                    pushed.add(entry.sealed.blobId)
                    metrics.onSpoolPushed()
                } else {
                    if (reply is SpoolReply.Failed) {
                        metrics.onSpoolError()
                        lastError = reply.code
                    }
                    // A per-blob refusal is worth stepping over; anything else (quota, a dead socket) ends
                    // the round.
                    if (reply !is SpoolReply.Failed || !survivable(reply)) break
                }
            }
            return pushed
        }

        /** The frames this spool lacks and will accept: not held, not tombstoned, not quarantined, live, in size. */
        private fun pushable(
            conn: SpoolConnection,
            scope: Scope,
            local: Map<String, Held>,
            spoolIds: Set<String>,
            tombstoned: Set<String>,
            quarantined: Set<String>,
        ): List<Held> {
            // The tighter of the two, not the spool's: pushing past the bound we declared at SUB is wrong
            // whatever the spool says it would accept.
            val bounds = boundsFor(scope)
            val maxBlob = minOf(conn.limits?.maxBlob ?: Int.MAX_VALUE, bounds.maxBlob)
            val now = clock()
            return local
                .filterKeys { it !in spoolIds && it !in tombstoned && it !in quarantined }
                .values
                .filterNot { ScopeFrames.deadOnArrival(it.carried.envelope, bounds.ttlMs, now) }
                .filter { it.sealed.blob.size <= maxBlob }
        }

        /**
         * The §9.4 exception (C-9.4-3, ADR 2026-09.y5f3): one frame this device does **not** custody, sealed
         * into each of [targets] this worker carries and pushed now. The frame is then *accounted* (§9.6) and
         * folded into the anchor exactly as a round's own push would be, so the next round neither re-pulls
         * it — into custody and onto the radios, the cost this path exists to avoid — nor lists on its
         * account. Every gate a round applies still applies: the §4.4 frame-set rule, the outward
         * dead-on-arrival guard, the size bound, a retiring scope. Returns whether any scope accepted it.
         */
        suspend fun pushDirect(
            targets: List<Scope>,
            env: RelayEnvelope,
            wire: WireEnvelope,
        ): Boolean =
            round.withLock {
                val conn = connection ?: return@withLock false
                val me = selfId()
                var any = false
                for (scope in mine(targets, conn)) {
                    if (pushDirectInto(conn, scope, me, env, wire)) {
                        any = true
                        // The next round folds the accounted band into the local digest, so the status reads
                        // converged now rather than at the 60 s tick — no LIST, the anchor already matches.
                        markDirty(scope.idHex)
                    }
                }
                any
            }

        /** One scope's share of [pushDirect]: every gate a round's push meets, then the push and its accounting. */
        private suspend fun pushDirectInto(
            conn: SpoolConnection,
            scope: Scope,
            me: String,
            env: RelayEnvelope,
            wire: WireEnvelope,
        ): Boolean {
            if (scope.retiring || !conn.isSubscribed(scope.idHex)) return false
            if (!ScopeFrames.eligibleFor(env, me, scope)) return false
            val bounds = boundsFor(scope)
            if (ScopeFrames.deadOnArrival(env, bounds.ttlMs, clock())) return false
            val sealed = ScopeFrames.seal(scope, wire.sig, wire.signed)
            if (sealed.blob.size > minOf(conn.limits?.maxBlob ?: Int.MAX_VALUE, bounds.maxBlob)) return false
            val reply = conn.push(scope.id, sealed.blobId, sealed.blob, stampFor(scope, conn.powBits))
            if (reply is SpoolReply.Failed) {
                metrics.onSpoolError()
                lastError = reply.code
            }
            if (reply !is SpoolReply.Ok) return false
            if (account(scope.idHex, hex(sealed.blobId), sealed.blobId)) metrics.onSpoolAccounted()
            // Fold into the anchor only where one exists — before the SUB is answered there is nothing to
            // fold into, and inventing one would be a digest the spool never sent.
            spoolDigests.computeIfPresent(scope.idHex) { _, fold -> fold xor ScopeCrypto.fnv64(sealed.blobId) }
            spoolCounts.computeIfPresent(scope.idHex) { _, count -> count + 1 }
            metrics.onSpoolPushed()
            return true
        }

        /**
         * Whether the rest of this round is worth attempting after a refused PUSH. A rate limit is worth
         * waiting out (the spool disconnects a persistent offender, so we spend the hint rather than the
         * strike budget); a quota refusal means the scope is full and the round is over.
         */
        private suspend fun survivable(reply: SpoolReply.Failed): Boolean =
            when (reply.code) {
                SpoolErrCode.RATE -> {
                    delay(reply.retryMs?.coerceIn(0L, MAX_RATE_WAIT_MS) ?: MAX_RATE_WAIT_MS)
                    true
                }

                SpoolErrCode.TOMBSTONED, SpoolErrCode.TOO_LARGE, SpoolErrCode.BAD_ID -> {
                    true
                }

                // per-blob, not fatal
                else -> {
                    false
                }
            }

        /**
         * Re-anchor after a heal round without a second round trip: the spool's live set, minus the ids it
         * reported gone, plus what we pushed. Exact because the digest is an XOR fold of per-id hashes —
         * and an unsolicited `digest` overwrites it the moment the spool knows better.
         */
        private fun reanchor(
            scope: Scope,
            spoolIds: Map<String, ByteArray>,
            gone: Set<String>,
            pushed: List<ByteArray>,
        ) {
            val remaining = spoolIds.filterKeys { it !in gone }.values
            var fold = ScopeCrypto.scopeDigest(remaining)
            pushed.forEach { fold = fold xor ScopeCrypto.fnv64(it) }
            spoolDigests[scope.idHex] = fold
            spoolCounts[scope.idHex] = remaining.size + pushed.size
        }

        /**
         * §9.5: fetch the attachments this scope's frames reference and we lack, and refill the ones we
         * hold and the spool lacks. Bounded per round so one 8 MiB image cannot starve every other
         * scope on this connection.
         *
         * Gated on the spool advertising the §7.3 limits. That gate is mechanical, not cosmetic: a spool
         * without attachment support *skips* an unknown record without answering, so an optimistic
         * `ahave` would sit until the 30 s request timeout — once per attachment, per scope, per round.
         */
        private suspend fun healAttachments(
            conn: SpoolConnection,
            scope: Scope,
            frames: List<CarriedFrame>,
            timed: Boolean,
        ) {
            val blobStore = blobs ?: return
            if (conn.limits?.attachments != true) return
            val quarantined = quarantinedAttachments(scope)
            val settled = settledAttachmentsFor(scope, recheckOlder = timed)
            val candidates =
                ScopeAttachments
                    .references(frames, scope, selfId())
                    .filterNot { it.aHash in quarantined || it.aHash in settled }
                    .mapNotNull { ref -> ScopeAttachments.hashBytes(ref.aHash)?.let { ref to it } }
                    .take(ATTACHMENT_SCAN_PER_ROUND)
            var handled = 0
            var pending = 0
            for ((ref, aHashBytes) in candidates) {
                if (handled == ATTACHMENTS_PER_ROUND) {
                    pending++
                    continue
                }
                val mine = blobStore.has(ref.aHash)
                // Deferring *before* the `ahave` is what makes the gate free: an attachment the radios
                // are still carrying costs no round trip at all this round, not merely no chunks. A
                // deferral also spends no round-trip budget, so it can never starve an attachment that
                // does need pushing.
                if (mine && deferAttachment(scope, ref)) {
                    metrics.onSpoolAttachmentDeferred()
                    pending++
                } else {
                    handled++
                    val aid = ScopeCrypto.attachmentId(scope.keys, scope.id, aHashBytes)
                    // A dead socket ends the round; there is nothing useful to try against it.
                    val presence = conn.ahave(scope.id, aid) ?: return
                    val done =
                        if (mine) {
                            pushAttachment(conn, scope, ref, aid, aHashBytes, presence, blobStore)
                        } else {
                            fetchAttachment(conn, scope, ref, aid, presence, blobStore)
                        }
                    if (done) settleAttachment(scope, ref.aHash) else pending++
                }
            }
            // Something is still in flight or still owed: look again on the old reconcile's cadence rather
            // than the tick's, and only for this scope. A settled scope schedules nothing.
            if (pending > 0) scheduleAttachmentRetry(scope)
        }

        /**
         * The attachments a round need not ask about: settled against this connection and, unless this is a
         * timed round finding the entry older than ATTACHMENT_RECHECK_MS, not due a second look. The spool
         * may evict what it held, and nothing on the wire says so — the recheck is how that is noticed.
         */
        private fun settledAttachmentsFor(
            scope: Scope,
            recheckOlder: Boolean,
        ): Set<String> =
            synchronized(settledAttachments) {
                val set = settledAttachments[scope.idHex] ?: return emptySet()
                if (recheckOlder) {
                    val horizon = clock() - ATTACHMENT_RECHECK_MS
                    set.values.removeAll { it <= horizon }
                }
                set.keys.toSet()
            }

        private fun settleAttachment(
            scope: Scope,
            aHash: String,
        ) {
            synchronized(settledAttachments) {
                val set = settledAttachments.getOrPut(scope.idHex) { LinkedHashMap() }
                set[aHash] = clock()
                while (set.size > BLOB_SET_MAX) set.remove(set.keys.first())
            }
        }

        private fun scheduleAttachmentRetry(scope: Scope) {
            val host = this@ScopeSync.session ?: return
            attachmentRetries.compute(scope.idHex) { _, existing ->
                if (existing?.isActive == true) {
                    existing
                } else {
                    host.launch {
                        delay(ATTACHMENT_RETRY_MS)
                        markDirty(scope.idHex)
                    }
                }
            }
        }

        /** Pulls the chunks we still lack and, once whole, verifies and stores the attachment. */
        @Suppress("LongParameterList") // the §9.5 step's inputs; a parameter object would only relocate them
        private suspend fun fetchAttachment(
            conn: SpoolConnection,
            scope: Scope,
            ref: ScopeAttachments.Ref,
            aid: ByteArray,
            presence: SpoolReply.Presence,
            blobStore: ScopeBlobs,
        ): Boolean {
            // Dead is final; no chunks yet is not — the peer may push them any moment, so that is pending.
            if (presence.dead) return true
            if (presence.total <= 0) return false
            val key = "${scope.idHex}|${ref.aHash}"
            val assembly = assemblyFor(scope, key, ref, presence) ?: return false
            if (!pullChunks(conn, scope, ref, aid, presence, assembly, key)) return false
            if (!assembly.isComplete()) return false
            assemblies.remove(key)
            // The decisive check: the bytes must hash to the address the frame named.
            val bytes = assembly.finish()
            if (bytes == null) {
                quarantineAttachment(scope, ref.aHash)
                return true
            }
            blobStore.save(ref.aHash, ref.mime ?: FALLBACK_MIME, bytes)
            metrics.onSpoolAttachmentPulled()
            onAttachmentObtained(ref.aHash)
            return true
        }

        /**
         * The in-flight buffer for one attachment: the existing one when it still agrees with the spool's
         * chunk count, else a fresh one. Null when the concurrency cap is reached (try again next round)
         * or the declared `total` is out of range — a member lying inside the AEAD, so quarantine.
         */
        private fun assemblyFor(
            scope: Scope,
            key: String,
            ref: ScopeAttachments.Ref,
            presence: SpoolReply.Presence,
        ): ScopeAttachments.Assembly? {
            assemblies[key]?.let { held -> if (held.total == presence.total) return held }
            if (assemblies.size >= MAX_ASSEMBLIES && !assemblies.containsKey(key)) return null
            val fresh = runCatching { ScopeAttachments.Assembly(ref.aHash, presence.total) }.getOrNull()
            if (fresh == null) quarantineAttachment(scope, ref.aHash) else assemblies[key] = fresh
            return fresh
        }

        /** Fetches the windows we still need. False means stop — a dead socket or an unusable chunk. */
        @Suppress("LongParameterList") // one hop below fetchAttachment; the same inputs, threaded once
        private suspend fun pullChunks(
            conn: SpoolConnection,
            scope: Scope,
            ref: ScopeAttachments.Ref,
            aid: ByteArray,
            presence: SpoolReply.Presence,
            assembly: ScopeAttachments.Assembly,
            key: String,
        ): Boolean {
            val maxAget = (conn.limits?.maxAget ?: DEFAULT_MAX_AGET).coerceAtLeast(1)
            val wanted = (0 until presence.total).filter { it !in assembly.held && ScopeAttachments.bitSet(presence.bits, it) }
            for (window in ScopeAttachments.windows(wanted, maxAget)) {
                val outcome = conn.aget(scope.id, aid, window.first, window.last - window.first + 1) ?: return false
                // A chunk outside the window we named, or over the structural chunk size, never reaches
                // `absorb` — so quarantine the aid here instead, exactly as `absorb` would (C-9.5-4).
                if (outcome.rejected) {
                    quarantineAttachment(scope, ref.aHash)
                    assemblies.remove(key)
                    return false
                }
                val chunks = outcome.chunks
                if (chunks.isEmpty()) return false // nothing served; the next round re-reads the bitmap
                for (chunk in chunks) {
                    if (!absorb(scope, ref, assembly, presence.total, chunk)) {
                        assemblies.remove(key)
                        return false
                    }
                }
            }
            return true
        }

        /** Opens one chunk and files it, or reports the failure that quarantines the whole attachment. */
        private fun absorb(
            scope: Scope,
            ref: ScopeAttachments.Ref,
            assembly: ScopeAttachments.Assembly,
            total: Int,
            chunk: SpoolAchunk,
        ): Boolean {
            val opened = runCatching { ScopeCrypto.openChunk(scope.keys, scope.id, chunk.data) }.getOrNull()
            if (opened == null || !inPosition(opened, ref, total, chunk)) {
                quarantineAttachment(scope, ref.aHash)
                return false
            }
            // `put` also refuses a duplicate, which is not a fault — only a genuinely bad chunk is.
            return assembly.put(opened.index, opened.data) || opened.index in assembly.held
        }

        /**
         * Whether a chunk's sealed header says what we asked for. The header is inside the AEAD, so this
         * catches a *member* replaying a chunk out of position — the only party able to produce one.
         */
        private fun inPosition(
            opened: ScopeCrypto.AttachChunk,
            ref: ScopeAttachments.Ref,
            total: Int,
            chunk: SpoolAchunk,
        ): Boolean = hex(opened.aHash) == ref.aHash && opened.total == total && opened.index == chunk.idx

        /** Uploads the chunks this spool's bitmap says it lacks, inside the scope's liveness window. */
        @Suppress("LongParameterList") // mirrors fetchAttachment
        private suspend fun pushAttachment(
            conn: SpoolConnection,
            scope: Scope,
            ref: ScopeAttachments.Ref,
            aid: ByteArray,
            aHashBytes: ByteArray,
            presence: SpoolReply.Presence,
            blobStore: ScopeBlobs,
        ): Boolean {
            // Every early return here is a reason no later round would act differently — settled, not pending.
            if (!worthPushing(scope, ref, presence)) return true
            val bytes = blobStore.bytes(ref.aHash) ?: return true
            if (bytes.isEmpty() || bytes.size > ScopeAttachments.MAX_ATTACHMENT_BYTES) return true
            val total = ScopeAttachments.chunkCount(bytes.size)
            // A spool holding a different chunk count for this id is serving another member's
            // disagreement; first write wins there, so adding ours would only collect `conflict`s.
            if (presence.total != 0 && presence.total != total) return true
            val stamp = stampFor(scope, conn.powBits)
            val missing = (0 until total).filter { presence.total == 0 || !ScopeAttachments.bitSet(presence.bits, it) }
            for (index in missing) {
                val sealed =
                    ScopeCrypto.sealChunk(scope.keys, scope.id, aHashBytes, index, total, ScopeAttachments.sliceAt(bytes, index))
                if (!putChunk(conn, scope, aid, index, total, sealed, stamp)) return false
            }
            return true
        }

        /** A retiring scope is drained, a dead attachment is gone, and §9.2 bars an aged-out frame's bytes. */
        private fun worthPushing(
            scope: Scope,
            ref: ScopeAttachments.Ref,
            presence: SpoolReply.Presence,
        ): Boolean = !scope.retiring && !presence.dead && ref.sentAt + scope.bounds.ttlMs > clock()

        /** Stores one sealed chunk; false means the rest of this attachment is not worth attempting. */
        @Suppress("LongParameterList") // the aput record's own field list
        private suspend fun putChunk(
            conn: SpoolConnection,
            scope: Scope,
            aid: ByteArray,
            index: Int,
            total: Int,
            sealed: ByteArray,
            stamp: PowStamp?,
        ): Boolean {
            val reply = conn.aput(scope.id, aid, index, total, ScopeCrypto.blobId(sealed), sealed, stamp)
            if (reply is SpoolReply.Ok) {
                metrics.onSpoolAttachmentPushed()
                return true
            }
            if (reply !is SpoolReply.Failed) return false
            metrics.onSpoolError()
            lastError = reply.code
            return survivable(reply)
        }

        private fun quarantineAttachment(
            scope: Scope,
            aHash: String,
        ) {
            val now = clock()
            val fresh =
                synchronized(invalidAttachments) {
                    val set = invalidAttachments.getOrPut(scope.idHex) { LinkedHashMap() }
                    // Remove-then-put so a re-quarantine moves to the young end and restarts its clock.
                    val previous = set.remove(aHash)
                    while (set.size >= BLOB_SET_MAX) set.remove(set.keys.first())
                    set[aHash] = now
                    previous == null || previous + boundsFor(scope).ttlMs <= now
                }
            if (fresh) metrics.onSpoolInvalid()
        }

        /**
         * The attachments still quarantined for [scope], dropping the ones whose horizon has passed. The
         * horizon is the scope TTL: a spool stamps an attachment's life at its first chunk on that clock
         * (S-6.5-4) and a bad chunk is permanent for that copy's life (S-6.5-7), so by `quarantinedAt +
         * ttlMs` the poisoned copy is gone and a re-upload — if a member ever makes one — is a fresh object.
         */
        private fun quarantinedAttachments(scope: Scope): Set<String> {
            val horizon = clock() - boundsFor(scope).ttlMs
            return synchronized(invalidAttachments) {
                val set = invalidAttachments[scope.idHex] ?: return emptySet()
                set.values.removeAll { it <= horizon }
                set.keys.toSet()
            }
        }

        private fun handleDigest(digest: SpoolDigest) {
            val scopeHex = hex(digest.scope)
            // Anchors are keyed by scope with no other bound, so a spool naming scopes we do not carry
            // would grow these maps without limit. We only ever asked about our own.
            val scope = scopes.firstOrNull { it.idHex == scopeHex } ?: return
            val value = ScopeCrypto.digestValue(digest.digest)
            val moved = spoolDigests.put(scopeHex, value) != value
            val grew = spoolCounts.put(scopeHex, digest.count) != digest.count
            // A commons SUB is answered with the bounds the spool pinned, not the ones we declared (§7.4) —
            // clamped like the HELLO's copy, since what is pinned here sizes what we track for the room.
            if (scope.commonsId != null) pinnedBounds[scopeHex] = digest.bounds.clamped()
            // Only a digest that says something new is a reason to heal: the unsolicited fan-out repeats the
            // anchor we already hold. An absent anchor (the SUB's answer on a fresh session) always is.
            if (moved || grew) markDirty(scopeHex)
        }

        /**
         * Live fan-out (§7.2). Unsolicited by design, so the correlation gate that bounds `pull` cannot
         * reach it and size is the only bound left: a frame over the `maxBlob` we declared for this scope
         * can never be one we would hold. An event that then fails validation is released and dropped,
         * never quarantined ([Source.EVENT]) — §9.3 is written for a *pulled* blob, and an id we never
         * pulled cannot drive the re-pull loop the invalid set exists to stop. If the spool really holds
         * it, the next listing names it and the pull path quarantines it properly.
         */
        private suspend fun handleEvent(event: SpoolEvent) {
            val scope = mine(scopes, connection).firstOrNull { it.idHex == hex(event.scope) } ?: return
            if (event.data.size > boundsFor(scope).maxBlob) return
            if (accept(scope, event.blobId, event.data, Source.EVENT) == Accept.DELIVERED) markDirty(scope.idHex)
        }

        private fun handleScopeError(
            scopeHex: String?,
            code: String,
            retryMs: Long?,
        ) {
            metrics.onSpoolError()
            lastError = code
            if (scopeHex == null) return
            // A refused stamp is stale (day rollover, or the spool raised its difficulty): drop it so the
            // next SUB/PUSH mines a fresh one instead of replaying the rejected counter forever.
            if (code == SpoolErrCode.POW) stamps.remove(scopeHex)
            // A refused SUB is parked, not retried every tick. The spool's own hint is honoured inside our
            // own window only — `coerceIn`, never `max`, or one absurd `retryMs` parks a scope for good.
            if (code == SpoolErrCode.QUOTA || code == SpoolErrCode.POW) {
                refusedUntil[scopeHex] = clock() + (retryMs ?: MIN_REFUSAL_PARK_MS).coerceIn(MIN_REFUSAL_PARK_MS, MAX_REFUSAL_PARK_MS)
            }
        }

        /**
         * §4.4 validation, then the mesh carry gate, then §9.4's bridge. A failure on the pull path
         * quarantines the id; on the event path it is merely released (see [refuse]).
         *
         * A blob arrives twice whenever a live `event` races the heal round that was already pulling it —
         * routine, since the pull set is computed before the events land. Re-delivering is *harmless*
         * (the router's SeenSet dedups), but it would re-run the AEAD and inflate the bridged count that
         * Diagnostics presents as "messages received via relays", so an accepted id is remembered for the
         * life of the connection. The memory is per (spool, scope) and dropped on reconnect, so a custody
         * wipe still re-converges by the ordinary route.
         *
         * The claim is taken *before* validation on purpose: the two racers run on different coroutines
         * (the pump and the worker), so claiming after would re-open double delivery. What makes the
         * early claim safe is that it is held only by a delivery — every other outcome releases it — and
         * that claiming never evicts: garbage therefore costs the guard nothing, however much of it a
         * spool sends.
         */
        private suspend fun accept(
            scope: Scope,
            blobId: ByteArray,
            data: ByteArray,
            source: Source,
            // The end-of-round second look at a parked post: it does not count against the park budget.
            retry: Boolean = false,
        ): Accept {
            val idHex = hex(blobId)
            if (!claim(scope.idHex, idHex)) return Accept.SKIPPED
            val outcome = acceptClaimed(scope, idHex, blobId, data, source, retry)
            if (outcome == Accept.DELIVERED) settle(scope.idHex) else release(scope.idHex, idHex)
            return outcome
        }

        @Suppress("LongParameterList") // the accept step's inputs, threaded once past the claim
        private suspend fun acceptClaimed(
            scope: Scope,
            idHex: String,
            blobId: ByteArray,
            data: ByteArray,
            source: Source,
            retry: Boolean,
        ): Accept {
            val opened = ScopeFrames.open(scope, selfId(), blobId, data) ?: return refuse(scope, idHex, source)
            if (scope.commonsId != null && opened.env.type == FrameType.COMMONS) {
                // The park budget is spent by pulls only: an event flood must not be able to burn it.
                val countPark = !retry && source == Source.PULL
                return acceptCommonsPost(scope, scope.commonsId, idHex, blobId, opened, source, countPark)
            }
            if (!canCarry(opened.wire, opened.env)) return refuse(scope, idHex, source)
            metrics.onSpoolPulled()
            // Stamped BEFORE delivery, still never a gate (ADR 2026-09.y5f3): the receipt answering the DM
            // that reveals this peer is originated inside [deliver], and the LoRa plane's Internet cover
            // has to already know the peer is here for that receipt to stay off the air.
            notePeerPresence(scope, opened.env)
            deliver(opened.wire, opened.env, SPOOL_SOURCE_PREFIX + url)
            metrics.onSpoolBridged()
            if (scope.commonsId != null) {
                // A member's profile (the only other frame a commons carries). Custody keeps it — it is a
                // profile — so the store-asks rule below would never account it, and our own push set holds
                // only our own; without this the room's digest could never match ours.
                onCommonsMember(scope.commonsId, opened.env.senderId)
                if (account(scope.idHex, idHex, blobId)) metrics.onSpoolAccounted()
                return Accept.DELIVERED
            }
            // §9.6. Delivery is done and it was worth doing — but if custody did not keep the frame, no
            // future round can ever fold this blob into `local`, so re-pulling it can only repeat this
            // work. Asking the store rather than re-deriving the rule is deliberate: "will custody hold
            // it" is the store's own dead-on-arrival + quota decision (`ForwardRepository.store`), and a
            // second copy of a convergence-critical TTL rule here is exactly how the two drift apart.
            if (!store.has(opened.env.id) && account(scope.idHex, idHex, blobId)) metrics.onSpoolAccounted()
            return Accept.DELIVERED
        }

        /**
         * The commons door (§7.4). A post is authenticated by exactly the carry gate every frame meets —
         * pinned key, signature byte-exact — but delivered through [deliverCommons], never
         * [deliver]: the router's door relays onto the radios and custodies, and a commons post does
         * neither. The one thing the gate cannot say is *why* it refused, so a sender we have not pinned
         * yet is asked about first and the post parked for the profile that is on its way — every member
         * keeps one live in the room — rather than quarantined for the life of the connection.
         */
        @Suppress("LongParameterList") // the commons door's inputs; a parameter object would only relocate them
        private suspend fun acceptCommonsPost(
            scope: Scope,
            conversationId: String,
            idHex: String,
            blobId: ByteArray,
            opened: ScopeFrames.Opened,
            source: Source,
            countPark: Boolean,
        ): Accept {
            val sender = opened.env.senderId
            if (!hasKey(sender)) {
                if (!countPark || park(scope.idHex, idHex)) return Accept.PARKED
                return refuse(scope, idHex, source)
            }
            val post = WireCodec.decodePayload<CommonsPost>(opened.env.payload)
            if (post == null || !canCarry(opened.wire, opened.env)) return refuse(scope, idHex, source)
            unpark(scope.idHex, idHex)
            metrics.onSpoolPulled()
            deliverCommons(opened.env, post.chat, conversationId, SPOOL_SOURCE_PREFIX + url)
            metrics.onSpoolBridged()
            onCommonsMember(conversationId, sender)
            // Never custodied, so never in `local`: accounted unconditionally, or the room never converges.
            if (account(scope.idHex, idHex, blobId)) metrics.onSpoolAccounted()
            return Accept.DELIVERED
        }

        /** Counts a park for [blobIdHex]; false once it has waited [MAX_PARK_ROUNDS] tries and should be given up on. */
        private fun park(
            scopeHex: String,
            blobIdHex: String,
        ): Boolean =
            synchronized(parked) {
                val waiting = parked.getOrPut(scopeHex) { LinkedHashMap() }
                while (waiting.size >= BLOB_SET_MAX) waiting.remove(waiting.keys.first())
                val tries = (waiting[blobIdHex] ?: 0) + 1
                waiting[blobIdHex] = tries
                tries <= MAX_PARK_ROUNDS
            }

        private fun unpark(
            scopeHex: String,
            blobIdHex: String,
        ) {
            synchronized(parked) { parked[scopeHex]?.remove(blobIdHex) }
        }

        /**
         * Records that [scope]'s own peer was recently on this plane, if [env] is evidence of that.
         *
         * Two conditions, and both matter. The frame must be authored by the peer the scope is *of* — our
         * own frames and (in a group scope) any other member's say nothing about this one. And it must pass
         * [isPresenceEvidence], because a spool holds blobs for 48 h and a client pulls whatever it lacks
         * whenever it next connects, so a scope yields old frames as a matter of course; without the age
         * rule, one backlog pull would mark a peer present that has not opened the app in a week.
         *
         * The stamp is *our* clock at the moment we accepted it, not the frame's `sentAt`, so a single
         * linger reads correctly downstream — the same shape as `LoraMeshTransport.lastHeardAt`.
         */
        private fun notePeerPresence(
            scope: Scope,
            env: RelayEnvelope,
        ) {
            val peer = scope.peerId ?: return
            if (env.senderId != peer) return
            val now = clock()
            if (!isPresenceEvidence(env, now)) return
            peerSeenAt[scope.idHex] = now
            republishPresence()
        }

        /**
         * A blob that failed validation or the carry gate. Quarantined only when *we pulled it* (spec
         * C-9.3-1): the invalid set exists to stop a re-pull loop, and an id we never asked for cannot
         * start one. Letting an unsolicited record into a bounded set would hand the spool a way to evict
         * the entries that matter — the same shape ADR 025 closed for `pull`.
         */
        private fun refuse(
            scope: Scope,
            idHex: String,
            source: Source,
        ): Accept {
            if (source == Source.PULL) quarantine(scope, idHex)
            return Accept.SKIPPED
        }

        private fun quarantine(
            scope: Scope,
            blobIdHex: String,
        ) {
            // Bounded like the accounted set, and for the same reason: a listing is refused past
            // `accountBound` (see [heal]), so what we can be asked to quarantine is what this can hold.
            if (remember(invalid, scope.idHex, blobIdHex, accountBound(scope.idHex))) metrics.onSpoolInvalid()
        }

        /**
         * Records [blobIdHex] under [scopeHex] in a bounded, oldest-first-evicting per-scope set. Returns
         * whether it was new — false means "already known", which is the skip signal for the invalid set
         * (§9.3: never re-pull, never re-count).
         */
        private fun remember(
            sets: ConcurrentHashMap<String, LinkedHashSet<String>>,
            scopeHex: String,
            blobIdHex: String,
            bound: Int = BLOB_SET_MAX,
        ): Boolean =
            synchronized(sets) {
                val set = sets.getOrPut(scopeHex) { LinkedHashSet() }
                while (set.size >= bound) set.remove(set.first())
                set.add(blobIdHex)
            }

        /**
         * Claims [blobIdHex] in the per-connection guard: false means another path already holds it.
         * Deliberately evicts nothing — a claim is not yet a delivery, and trimming here would let a
         * flood of garbage push genuine entries out before any of it was validated. [settle] trims once
         * the claim has become a delivery; [release] undoes a claim that did not.
         */
        private fun claim(
            scopeHex: String,
            blobIdHex: String,
        ): Boolean = synchronized(accepted) { accepted.getOrPut(scopeHex) { LinkedHashSet() }.add(blobIdHex) }

        private fun release(
            scopeHex: String,
            blobIdHex: String,
        ) {
            synchronized(accepted) { accepted[scopeHex]?.remove(blobIdHex) }
        }

        /**
         * Trims the guard to its bound, oldest first. Only ever removes settled deliveries: an in-flight
         * claim is by construction among the newest entries, and at most two can be in flight (the pump
         * and the worker are each serial), so a set over the bound has older entries than those to shed.
         */
        private fun settle(scopeHex: String) {
            synchronized(accepted) {
                val set = accepted[scopeHex] ?: return
                while (set.size > BLOB_SET_MAX) set.remove(set.first())
            }
        }

        /** A snapshot of [scopeHex]'s guard — copied under the lock, since the pump writes it concurrently. */
        private fun acceptedFor(scopeHex: String): Set<String> = synchronized(accepted) { accepted[scopeHex]?.toSet().orEmpty() }

        /**
         * Records [blobIdHex] as accounted for [scopeHex] (§9.6): folded into our local digest as if held,
         * and never pulled again. Bounded and oldest-first-evicting like [remember] — the bound is above a
         * full scope (`maxFrames` = 400), so eviction is the pathological case, not the ordinary one.
         * Returns whether it was new.
         */
        private fun account(
            scopeHex: String,
            blobIdHex: String,
            blobId: ByteArray,
        ): Boolean =
            synchronized(accounted) {
                val fold = accounted.getOrPut(scopeHex) { LinkedHashMap() }
                while (fold.size >= accountBound(scopeHex)) fold.remove(fold.keys.first())
                fold.put(blobIdHex, ScopeCrypto.fnv64(blobId)) == null
            }

        /**
         * The accounted set's ceiling. Every blob in a commons is accounted (none is ever held), so its
         * bound must clear the room's pinned `maxFrames` with headroom, or a full room churns the set and
         * re-pulls posts it already has; every other scope keeps the §12 suggestion.
         */
        private fun accountBound(scopeHex: String): Int = maxOf(BLOB_SET_MAX, (pinnedBounds[scopeHex]?.maxFrames ?: 0) + ACCOUNT_HEADROOM)

        /** A spool-pinned bound held to our own ceilings: a room we could never list whole is not one we can heal. */
        private fun ScopeBounds.clamped() =
            ScopeBounds(
                maxFrames = maxFrames.coerceIn(1, MAX_PINNED_FRAMES),
                ttlMs = ttlMs.coerceAtLeast(1L),
                maxBlob = maxBlob.coerceIn(1, MAX_INBOUND_RECORD),
            )

        /** §12.2's suggested tombstone count bound — the most a conforming spool lists for a scope. */
        private fun tombstoneBound(maxFrames: Int): Int = maxOf(2 * maxFrames, MIN_TOMBSTONE_BOUND)

        /** A snapshot of [scopeHex]'s accounted set — copied under the lock, since `accept` runs off the pump. */
        private fun accountedFor(scopeHex: String): Map<String, Long> = synchronized(accounted) { accounted[scopeHex]?.toMap().orEmpty() }

        /** Drops accounted ids the spool's listing no longer names — they expired or were evicted there. */
        private fun pruneAccounted(
            scopeHex: String,
            live: Set<String>,
            known: Set<String>,
        ): Boolean = synchronized(accounted) { accounted[scopeHex]?.keys?.removeAll { it in known && it !in live } == true }

        /** Drops quarantined ids the spool's listing no longer names: unlisted, they can never be re-pulled. */
        private fun pruneInvalid(
            scopeHex: String,
            live: Set<String>,
            known: Set<String>,
        ): Boolean = synchronized(invalid) { invalid[scopeHex]?.removeAll { it in known && it !in live } == true }

        /** A cached hashcash stamp for [scope], mined only when the spool demands one (§8). */
        private fun stampFor(
            scope: Scope,
            bits: Int,
        ): PowStamp? {
            // A commons exists from the spool's boot, so it is never an unknown scope and never gated (§7.4):
            // mining for it would spend a phone's battery on a stamp the spool ignores.
            if (bits <= 0 || scope.commonsId != null) return null
            val day = SpoolPow.utcDay(clock())
            stamps[scope.idHex]?.let { if (it.d == day) return it }
            val n = SpoolPow.stamp(scope.id, day, bits, POW_BUDGET) ?: return null
            return PowStamp(n = n, d = day).also { stamps[scope.idHex] = it }
        }
    }

    /**
     * Memoizes the deterministic seal per (scope, frame). Sealing is the only per-frame cost in a heal
     * round, and the result never changes — that is what makes the local blob-id set derivable on demand
     * instead of persisted, so this milestone needs no `forward_store` column and no DB migration.
     */
    private class SealCache(
        private val max: Int,
    ) : LinkedHashMap<String, ScopeFrames.Sealed>(INITIAL_CACHE_CAPACITY, LOAD_FACTOR, true) {
        @Synchronized
        fun get(
            scope: Scope,
            carried: CarriedFrame,
        ): ScopeFrames.Sealed = getOrPut("${scope.idHex}|${carried.envelope.id}") { ScopeFrames.seal(scope, carried.sig, carried.signed) }

        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ScopeFrames.Sealed>?): Boolean = size > max
    }

    /** How a blob reached [Worker.accept]: asked for by a `pull`, or pushed at us by a live `event`. */
    private enum class Source {
        PULL,
        EVENT,
    }

    /** What [Worker.accept] did with a blob. */
    private enum class Accept {
        /** Opened, authenticated and handed to a door. */
        DELIVERED,

        /** Already seen, quarantined, or refused — nothing more to do with it on this connection. */
        SKIPPED,

        /** A commons post whose author is not pinned yet: left unclaimed for a later try. */
        PARKED,
    }

    companion object {
        /** Marks a frame as spool-sourced inbound; it names no peer, so it excludes nobody from the relay. */
        const val SPOOL_SOURCE_PREFIX = "spool:"

        /**
         * Whether an inbound frame's source names a spool rather than a neighbouring node — i.e. it crossed
         * the Internet plane, not a radio. The delivery tick reads this to say *how* a message got there;
         * it is a presentation fact only, and nothing about carry, relay or convergence may depend on it.
         */
        fun isSpoolSource(fromNodeId: String): Boolean = fromNodeId.startsWith(SPOOL_SOURCE_PREFIX)

        private const val RECONCILE_INTERVAL_MS = 15_000L
        private const val TICK_INTERVAL_MS = 60_000L
        private const val HANDSHAKE_TIMEOUT_MS = 20_000L
        private const val RECONNECT_JITTER_MS = 750L

        /** How often a worker with no route looks again, between the route changes that wake it at once. */
        private const val OFFLINE_RECHECK_MS = 60_000L

        /** The re-look for an attachment a round could not finish — the cadence the old reconcile gave every scope. */
        private const val ATTACHMENT_RETRY_MS = 15_000L

        /** How long a settled attachment goes unasked-about before a timed round checks the spool still has it. */
        private const val ATTACHMENT_RECHECK_MS = 10 * 60_000L
        private const val MAX_RATE_WAIT_MS = 5_000L

        /** How long a scope the spool refused (`quota`, `pow`) waits before it is SUBbed again — the floor and the cap. */
        private const val MIN_REFUSAL_PARK_MS = 5 * 60_000L
        private const val MAX_REFUSAL_PARK_MS = 60 * 60_000L
        private const val DEFAULT_MAX_PULL = 64
        private const val DEFAULT_MAX_AGET = 32

        /** Attachments touched per (spool, scope) per round — one big image must not starve the rest. */
        private const val ATTACHMENTS_PER_ROUND = 4

        /**
         * Attachments *considered* per (spool, scope) per round. Larger than the round-trip budget
         * because a deferred one spends none of that budget but still costs two local reads — three
         * inside `AttachmentDeferPolicy.ACK_GRACE_MS`, where the grace asks a second question — so the
         * scan needs a bound of its own.
         */
        private const val ATTACHMENT_SCAN_PER_ROUND = 32

        /** Concurrent in-memory reassemblies per spool; each is bounded by `MAX_CHUNKS` (8 MiB). */
        private const val MAX_ASSEMBLIES = 2

        /** Used when a frame names an attachment but no mime; Coil sniffs the real format anyway. */
        private const val FALLBACK_MIME = "image/jpeg"
        private const val BLOB_SET_MAX = 512

        /** How many rounds a commons post waits for its author's profile before it is written off (§7.4). */
        private const val MAX_PARK_ROUNDS = 8

        /** Above a commons' pinned `maxFrames`, so a full room fits in the accounted set with room to spare. */
        private const val ACCOUNT_HEADROOM = 64
        private const val SEAL_CACHE_MAX = 2_048
        private const val INITIAL_CACHE_CAPACITY = 64
        private const val LOAD_FACTOR = 0.75f

        /**
         * [SpoolStatus.lastError] when nothing answered: the URL could not be dialled at all, or the route
         * returned no response of any kind (`OkHttpSpoolDialer`'s `failureReason` — a timeout, DNS, a
         * refused or reset connection, no route). A validated-but-dead Wi-Fi presents exactly this way.
         */
        const val UNREACHABLE = "unreachable"

        /**
         * [SpoolStatus.lastError] when the socket opened and the spool never said hello inside
         * [HANDSHAKE_TIMEOUT_MS]. Client-minted, and distinct from [SpoolConnection.UNRESPONSIVE] on
         * purpose: that one retires when a request is answered, this one the moment a hello completes.
         */
        const val NO_HELLO = "no_hello"

        /**
         * [SpoolStatus.lastError] when a spool's listing named more ids than a conforming one can hold
         * for the scope (§12.2), so the round was refused rather than anchored on it. Client-minted, like
         * [UNREACHABLE]: no spool ever says this about itself.
         */
        const val OVERLONG_LISTING = "overlong_list"

        /** The floor of §12.2's tombstone count bound, `max(2 × maxFrames, 1024)`. */
        private const val MIN_TOMBSTONE_BOUND = 1024

        /** Hashcash budget per stamp: ~67 M hashes, comfortably above the spec's 20-bit recommendation. */
        private const val POW_BUDGET = 1L shl 26
    }
}
