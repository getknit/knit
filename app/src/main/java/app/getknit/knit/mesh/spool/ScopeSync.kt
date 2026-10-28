package app.getknit.knit.mesh.spool

import app.getknit.knit.fieldlog.FieldEvent
import app.getknit.knit.fieldlog.FieldRecorder
import app.getknit.knit.mesh.CarriedFrame
import app.getknit.knit.mesh.ForwardStore
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.PRESENCE_LINGER_MS
import app.getknit.knit.mesh.SPOOL_COVER_MS
import app.getknit.knit.mesh.crypto.scope.ScopeCrypto
import app.getknit.knit.mesh.crypto.scope.SpoolPow
import app.getknit.knit.mesh.isPresenceEvidence
import app.getknit.knit.mesh.protocol.CommonsPost
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
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

    /** The stored length of [aHash] (its `A`), or null when it is not held: what a ticket's `am` is measured against. */
    suspend fun size(aHash: String): Int? = bytes(aHash)?.size

    /** Stores bytes already checked against [aHash], and screens them exactly as a radio pull does (#109). */
    suspend fun save(
        aHash: String,
        mime: String,
        bytes: ByteArray,
    )

    /**
     * Whether this device's own screening refused [aHash] (content filtering on, verdict explicit), so its
     * bytes were dropped on purpose and fetching them again would only drop them again. The default refuses
     * nothing.
     */
    suspend fun refused(aHash: String): Boolean = false
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
    /** The anchor this spool last answered for the scope matches ours; false until it has answered one. */
    val converged: Boolean,
    val invalidCount: Int,
    val retiring: Boolean,
    val accountedCount: Int = 0,
    val peerSeenAt: Long? = null,
    /** A pair scope (spec §3.5) — it shares its [label] with the DM scope that supersedes it. */
    val pair: Boolean = false,
    /**
     * A DM scope of our own reset that the peer has not answered (`Scope.resetInit`, ADR 2026-10.j67x): we push
     * into it, but the peer derives it only once it adopts the reset, so it is no coverage yet ([RelayReach]).
     */
    val pendingReset: Boolean = false,
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
    // The commons rooms this spool advertised in its HELLO (§7.4), null while disconnected or when it runs none:
    // what the relay row's Join and an invite's preview read, by the room's tag.
    val adverts: CommonsAdverts? = null,
    // The joined commons bound here that this spool runs, by conversation id: each one's own advertised name,
    // pinned bounds and `attach` (S-7.4-5). A stale room is not in it.
    val rooms: Map<String, SpoolCommonsInfo> = emptyMap(),
    // The joined commons bound here that this spool does not run, by conversation id: the invite was rotated
    // (its answers showed the room is not this spool's commons), or its HELLO does not advertise it. Kept across a
    // disconnect for the probe's half — what the spool said stays true of the invite we hold.
    val staleCommons: Set<String> = emptySet(),
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
    /**
     * What this connection's HELLO said about entitlement tickets and STUN (experimental, docs/VOICE_CALLS.md §2.6):
     * whether it takes tickets, the STUN URIs it advertised (as [SpoolConnection.stun] holds them), and where this
     * connection stands with a ticket. Facts about the live connection, so none while disconnected.
     */
    val ent: Boolean = false,
    val stun: List<String> = emptyList(),
    val ticket: SpoolTicketState = SpoolTicketState.None,
    /** Whether this connection's HELLO said the spool issues TURN credentials (`turn`, docs/VOICE_CALLS.md §2.1). */
    val turn: Boolean = false,
    /**
     * The TURN servers this connection's HELLO names, as entries in server order (`turnHosts`, S-7.6-48;
     * [SpoolConnection.turnHosts]): what the phone measures where [turnForUs] holds (C-7.6-52). Empty while
     * disconnected and for a spool that names none.
     */
    val turnHosts: List<String> = emptyList(),
    /**
     * Whether a `turnreq` from this phone would be answered here (S-7.6-16): the spool issues TURN, and either does not
     * gate it behind a ticket or this connection holds one with bit 0 of `fl` — [TurnAsk]'s rule without its scope
     * clause, which is per peer. What "Relay all calls" is offered on. False while disconnected.
     */
    val turnForUs: Boolean = false,
    /** This connection's HELLO cast bounds (docs/VOICE_CALLS.md §2.7), or null when the spool fans out no casts. */
    val cast: SpoolCastInfo? = null,
    /** This connection's HELLO sig bounds (§7.8), or null when the spool fans out no sigs. */
    val sig: SpoolSigInfo? = null,
    /** The `fl` bits this connection's HELLO gates behind a ticket (`SpoolHello.ticketGates`); 0 while disconnected. */
    val entGates: Long = 0L,
    /**
     * Whether this connection takes this phone's attachment uploads (C-7.6-24): what the chat's "Nearby only" marker
     * reads beside [maxAttachBytes], which stays the download capability. [SpoolUploads.Open] while disconnected,
     * where [maxAttachBytes] is null anyway.
     */
    val uploads: SpoolUploads = SpoolUploads.Open,
    /**
     * The most chunks an attachment of this phone's may have to go up here (C-7.6-32), from the live files ticket's `am`
     * ([SpoolConnection.uploadLimitChunks]), or null for no limit beyond [maxAttachBytes]. Null while disconnected.
     */
    val uploadLimitChunks: Int? = null,
    /**
     * What this relay's last hello in this process said about attachments, kept while it is down: whether it carried
     * them ([seenAttachments]) and which `fl` bits it gated ([seenGates]). Null before the first hello. What a photo
     * staged while the relay is down is sized by (`data/relay/FreeUploads`).
     */
    val seenAttachments: Boolean? = null,
    val seenGates: Long? = null,
)

/**
 * One spool's part in a call's TURN ask: its [host] for the log, the [connection] to ask (null when it is an `ent`
 * spool with no ticket and is skipped), and whether the ask rides a ticket — those go first.
 */
internal class TurnAsk(
    val host: String,
    val connection: SpoolConnection?,
    val ticketed: Boolean,
)

/**
 * One TURN credential a call's ask brought back: the [reply], and the TURN servers its spool names ([hosts], HELLO
 * `turnHosts` in server order, empty for a spool that names none) — what the call ranks it among (C-7.6-53). Kept in
 * the order the spools were asked in, ticketed first. Never logged: the reply is a credential.
 */
class TurnIssue(
    val reply: SpoolReply.Turn,
    val hosts: List<String>,
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
    // The relays dialled whatever the scopes name (`ScopeSpools.base`: the person's own relays and the home); empty (the
    // plane switched off, or nothing configured) parks every worker. Each scope's own relays ([Scope.spools]) join them.
    private val urls: suspend () -> List<String>,
    // The mesh's own carry gate — pinned sender key, signature valid. Reused rather than
    // re-implemented so a spool-delivered frame is authenticated by exactly the rule the radios use.
    private val canCarry: suspend (WireEnvelope, RelayEnvelope) -> Boolean,
    // The mesh bridge: `MeshRouter.handleInbound`. Dedup, delivery, custody, and relay live behind it.
    private val deliver: suspend (WireEnvelope, RelayEnvelope, String) -> Unit,
    // The local blob store, for attachments (§4.5/§9.5). Null switches the attachment half off
    // entirely — the frame plane is unaffected, which is what keeps this additive.
    private val blobs: ScopeBlobs? = null,
    // Fired once an attachment's bytes are in hand and [ScopeBlobs.save] has screened them, so the adopt
    // checks, the message row and the UI run the same path a radio pull takes. `InboundPipeline::onObtained`
    // in the app.
    private val onAttachmentObtained: suspend (String) -> Unit = {},
    // Whether the radios are still carrying an attachment we hold, so its bytes need not cross the
    // Internet this round (§9.5). A deferral, never a veto — see [AttachmentDeferPolicy]. The default
    // never defers, which is the pre-gate behaviour every existing test asserts.
    private val deferAttachment: suspend (Scope, ScopeAttachments.Ref) -> Boolean = { _, _ -> false },
    // Whether a DM attachment we hold is one its recipient provably holds, over a short-range radio, so it never
    // crosses the Internet at all (§9.5, C-9.5-6's exception; [AttachmentDeferPolicy.proved], ADR 2026-10.vy27). The
    // default proves nothing, which is the pre-proof behaviour every existing test asserts.
    private val provedAttachment: suspend (Scope, ScopeAttachments.Ref) -> Boolean = { _, _ -> false },
    // Whether a DM attachment of ours is inside its proof round, asked only on a connection whose uploads are locked:
    // there the lock would settle it first and wake the ticket refresher for an upload the proof may yet spare
    // ([AttachmentDeferPolicy.holdsForProof], ADR 2026-10.vy27). The default holds nothing.
    private val holdsForProof: suspend (Scope, ScopeAttachments.Ref) -> Boolean = { _, _ -> false },
    // The commons half (§7.4): the joined rooms' own posts and their member roster. Null switches it off —
    // no commons scope is ever derived without it, so the frame plane is unaffected.
    private val commons: CommonsStore? = null,
    // Whether the mesh has pinned a key for this node id — the one question `canCarry` folds into a bare
    // `false`. A commons post from a sender not pinned *yet* is deferred for the profile that is on its
    // way, never quarantined as a forgery.
    private val hasKey: suspend (String) -> Boolean = { true },
    // The commons door: a verified post (a chat or a reaction), its decoded content, its conversation, the source.
    // Posts never go through [deliver] — that door relays onto the radios, and a commons post lives on its spool.
    private val deliverCommons: suspend (RelayEnvelope, CommonsPost, String, String) -> Unit = { _, _, _, _ -> },
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
    // The two `delay`s a calendar-only transition waits on: the timed round ([TICK_INTERVAL_MS]) and the
    // re-look at an attachment a round could not finish ([ATTACHMENT_RETRY_MS]). A jumped clock cannot move
    // a `delay`, so the multi-node JVM harness (`mesh/lab`) shortens them for the scenario that waits on one;
    // production wiring takes the defaults.
    private val tickIntervalMs: Long = TICK_INTERVAL_MS,
    private val attachmentRetryMs: Long = ATTACHMENT_RETRY_MS,
    // The entitlement tickets this device holds (experimental, docs/VOICE_CALLS.md §2.6, ADR 2026-10.xkp4): each
    // connection to an `ent` spool presents its host's ticket right after the hello, and again the moment a different
    // one is imported. Null presents nothing, for the tests that do not ask. Every build passes the store, release
    // included: there nothing imports into it (the import doors and the refresher sit behind `ANYWHERE_WIRED`), so a
    // release phone holds no ticket and is locked out of uploads on a files-gated spool, as an unticketed one is.
    private val tickets: SpoolTickets? = null,
    // A relay URL where an upload of this phone's was just settled as locked `missing` or `expired` behind the files
    // gate (C-7.6-27): the ticket refresher's reason to ask now rather than on its schedule, and a free key's only one.
    // Called on the worker's coroutine, possibly once per attachment in a round; the receiver de-duplicates.
    private val onUploadWanted: (String) -> Unit = {},
    // Where the plane says what it did with a ticket and a TURN ask (the app's `ScopeSync` logcat tag). Never handed
    // a ticket, a credential or a token: hosts, outcome words and counts only.
    private val log: (String) -> Unit = {},
    // The field log (ADR 2026-10.a239): each spool's hello, session end, failed dial and new `err` code, under the
    // relay's pseudonym. Its 5-minute sample reads [status] from the session's side.
    private val field: FieldRecorder = FieldRecorder.NONE,
    // The relays a worker runs for, whenever the set changes ([reconcile]): the ticket refresher's "relays in use".
    private val onRelaysChanged: (Set<String>) -> Unit = {},
    // The TURN servers this phone would measure (C-7.6-52), whenever the set changes: the union of `turnHosts` over the
    // connected spools that would issue it TURN ([SpoolStatus.turnForUs]), each entry once. Fired from a worker's
    // session as its hello and ticket settle and as it ends, never on a timer.
    private val onTurnHosts: (List<String>) -> Unit = {},
) {
    private val workers = ConcurrentHashMap<String, Worker>()
    private val sealCache = SealCache(SEAL_CACHE_MAX)

    // Relay host name → the ticket pseudonym that has spent its day's uploads there (`err ticket` `budget`, C-7.6-24).
    // Here rather than on a worker, whose per-dial state goes with every session, so the mark survives a reconnect and
    // a re-presented ticket with the same pseudonym; an accepted files ticket with another pseudonym clears it. Memory
    // only, and never the verdict journal: `SpoolTicketPolicy` would read a stored word as "never present again" and
    // take TURN and casts away from the same ticket. A process restart forgets it, which costs one refused chunk.
    private val spentUploads = ConcurrentHashMap<String, TicketPseudonym>()

    @Volatile
    private var lastPresent: Set<String> = emptySet()

    @Volatile
    private var lastRelays: Set<String> = emptySet()

    @Volatile
    private var session: CoroutineScope? = null

    @Volatile
    private var supervisor: Job? = null

    @Volatile
    private var scopes: List<Scope> = emptyList()

    /**
     * Serializes [reconcile]: derivations now run on every input event as well as the poll, and two in
     * flight could adopt tables in the wrong order — the older one's `forgetScopesNotIn` dropping the anchors
     * a newer scope had just earned. Events that land while one runs collapse into one queued re-derivation
     * ([reconcileQueued]), which starts only after the running one and so reads the inputs it may have missed.
     */
    private val reconcileLock = Mutex()
    private val reconcileQueued = AtomicBoolean(false)

    /** Starts the plane on the mesh session scope. Restart-safe, like the other services under `MeshManager`. */
    fun start(session: CoroutineScope) {
        if (supervisor?.isActive == true) return
        this.session = session
        supervisor =
            session.launch {
                while (isActive) {
                    reconcileLock.withLock { reconcile() }
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
        publishRelays(emptySet())
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

    /**
     * TURN credentials for a call with [peerId] (experimental, docs/VOICE_CALLS.md §2, C-7.6-12): one from each of
     * at most [MAX_TURN_SPOOLS] connected spools that advertised `turn` and would issue one here — the daemon's own
     * gate is any subscription (S-7.6-4), so a spool that has answered the SUB of a live scope shared with the peer,
     * the spools the call would be relayed beside anyway. A spool where this connection holds a ticket that grants TURN
     * (accepted, or on its way) is asked on every call whether or not the peer's scope is there (C-7.6-21), and goes
     * **first**: it is the one a payer's relay is on, and the cap must not crowd it out. A spool whose HELLO gates TURN
     * (bit 0 of `entGates`) with no such ticket here is not asked at all — S-7.6-16 would refuse it, from the per-IP
     * bucket (ADR 2026-10.xkp4); one that leaves bit 0 out is asked as an unticketed spool (C-7.6-30). Asked in
     * parallel, each for at most [TURN_ASK_MS], on the connection directly and never under a worker's round lock: a
     * call is waiting. A refusal or a silence just leaves that spool out.
     */
    suspend fun turnCredentials(peerId: String): List<SpoolReply.Turn> = turnCredentials(peerId) { null }.map { it.reply }

    /**
     * [turnCredentials], each spool that names its TURN servers asked for the entry [pick] ranks best of its list
     * (C-7.6-51), and each credential returned beside that list, in asking order. A spool that names none is asked as
     * before, with no `host`.
     */
    suspend fun turnCredentials(
        peerId: String,
        pick: (List<String>) -> String?,
    ): List<TurnIssue> {
        val replies =
            coroutineScope {
                turnAsks(peerId)
                    .take(MAX_TURN_SPOOLS)
                    .map { (ask, conn) ->
                        async {
                            val host = conn.turnHosts.takeIf { it.isNotEmpty() }?.let(pick)
                            Triple(ask, conn, host) to conn.turnCredential(TURN_ASK_MS, host)
                        }
                    }.awaitAll()
            }
        for ((asked, reply) in replies) logTurn(asked.first, asked.second, asked.third, reply)
        return replies.mapNotNull { (asked, reply) -> (reply as? SpoolReply.Turn)?.let { TurnIssue(it, asked.second.turnHosts) } }
    }

    /**
     * One credential for the TURN server [entry] names (C-7.6-54's next server): from the first spool, in asking order,
     * that would issue a call with [peerId] TURN and lists [entry] in its `turnHosts`, asked for at most [TURN_ASK_MS].
     * Null when none lists it or the one asked gives none.
     */
    suspend fun turnCredentialFor(
        peerId: String,
        entry: String,
    ): TurnIssue? {
        val (ask, conn) = turnAsks(peerId).firstOrNull { (_, conn) -> entry in conn.turnHosts } ?: return null
        val reply = conn.turnCredential(TURN_ASK_MS, entry)
        logTurn(ask, conn, entry, reply)
        return (reply as? SpoolReply.Turn)?.let { TurnIssue(it, conn.turnHosts) }
    }

    // The spools a call with [peerId] would ask for TURN, ticketed first (C-7.6-12, C-7.6-21); a skipped one is logged.
    private fun turnAsks(peerId: String): List<Pair<TurnAsk, SpoolConnection>> {
        val hexes = scopes.filter { it.peerId == peerId && !it.retiring }.map { it.idHex }
        val asks = workers.values.mapNotNull { it.turnAsk(hexes) }
        asks.filter { it.connection == null }.forEach { log("${it.host}: TURN ask skipped, no ticket (ent)") }
        return asks
            .mapNotNull { ask -> ask.connection?.let { ask to it } }
            .sortedByDescending { it.first.ticketed }
    }

    // A TURN ask's line: the spool's host, and the server it named by its slot in that spool's list, never by its own.
    private fun logTurn(
        ask: TurnAsk,
        conn: SpoolConnection,
        named: String?,
        reply: SpoolReply,
    ) {
        val slot = named?.let { " for slot ${conn.turnHosts.indexOf(it) + 1} of ${conn.turnHosts.size}" }.orEmpty()
        log("${ask.host}: TURN ask${if (ask.ticketed) " (ticketed)" else ""}$slot: ${describeTurn(reply)}")
    }

    /** The TURN servers this phone would measure now (C-7.6-52): [SpoolStatus.turnHosts] where [SpoolStatus.turnForUs]. */
    fun turnHosts(): List<String> = workers.values.flatMap { it.measuredTurnHosts() }.distinct()

    @Volatile
    private var lastTurnHosts: List<String> = emptyList()

    /** Fires [onTurnHosts] when [turnHosts] differs from what was last published. */
    @Synchronized
    private fun publishTurnHosts() {
        val current = turnHosts()
        if (current == lastTurnHosts) return
        lastTurnHosts = current
        onTurnHosts(current)
    }

    /**
     * Whether a cast to [peerId] would leave through at least one connected spool (experimental,
     * docs/VOICE_CALLS.md §2.7): one whose hello advertised `cast`, that has answered the SUB of the peer's current
     * DM scope, and — where its HELLO gates casts — where this phone holds a cast ticket or the peer does ([peerTicketed], from
     * its walkie signal). Lock-free and synchronous, so the walkie can ask before it seals and spends a ratchet step.
     */
    fun canCast(
        peerId: String,
        peerTicketed: Boolean,
    ): Boolean {
        val scope = castScope(peerId) ?: return false
        return workers.values.any { it.canCast(scope, peerTicketed) }
    }

    /**
     * Whether a cast to [peerId] would reach it (docs/VOICE_CALLS.md §2.7, C-3.11-8's notice): [canCast] on a spool the
     * peer's own recent traffic reached within [lingerMs] (its `peerSeenAt`, as `spoolPresentPeers` reads it). A spool
     * fans a cast out only to the scope's live subscribers, so one the peer was never seen on carries it to nobody we
     * know of. What the call screen labels a relay path by; never a gate on the cast itself, since a quiet subscriber
     * leaves no evidence.
     */
    fun castCovers(
        peerId: String,
        peerTicketed: Boolean,
        lingerMs: Long = PRESENCE_LINGER_MS,
    ): Boolean {
        val scope = castScope(peerId) ?: return false
        val now = clock()
        return workers.values.any { it.canCast(scope, peerTicketed) && it.peerSeenWithin(scope, now, lingerMs) }
    }

    /**
     * Whether this phone holds a cast ticket on some connected spool that fans out casts — what its walkie signal
     * tells the peer, so a free phone knows its casts will be carried there.
     */
    fun castTicketed(): Boolean = workers.values.any { it.castTicketed() }

    /**
     * Casts one signed frame to [peerId] through every connected spool that would carry it (docs/VOICE_CALLS.md §2.7)
     * and returns how many took it. Sealed once: the same frame cast to two spools is the same bytes, and the peer's
     * router drops the second copy by frame id. The frame must be in the cast set, outward. Never under a worker's
     * round lock and never a request: a walkie chunk is live or lost, and a heal round must not hold one back.
     */
    suspend fun cast(
        peerId: String,
        peerTicketed: Boolean,
        env: RelayEnvelope,
        sig: ByteArray,
        signed: ByteArray,
    ): Int {
        val scope = castScope(peerId) ?: return 0
        if (!ScopeFrames.castEligible(env, selfId(), scope, inbound = false)) return 0
        val data = ScopeFrames.sealCast(scope, sig, signed)
        return workers.values.count { it.castOut(scope, data, peerTicketed) }
    }

    /** The peer's current DM scope, the one a cast rides: never a retiring one, never the pair scope. */
    private fun castScope(peerId: String): Scope? = scopes.firstOrNull { it.peerId == peerId && !it.retiring && !it.pair }

    /**
     * Sends one signed frame of [conversationId]'s as a sig through every connected spool that would carry it (§7.8) and
     * returns how many took it: one whose hello advertised `sig` and that has answered the SUB of the conversation's
     * current scope (C-7.8-9, C-7.8-10). Sealed once, for [cast]'s reason; the receiver keeps the first copy by frame id
     * (C-7.8-15). The frame must be in the signal set, outward (C-7.8-13), and no ticket enters into it (S-7.8-4). Never
     * under a worker's round lock and never a request: a typing cue is live or lost.
     */
    suspend fun sig(
        conversationId: String,
        env: RelayEnvelope,
        sig: ByteArray,
        signed: ByteArray,
    ): Int {
        val scope = sigScope(conversationId) ?: return 0
        if (!ScopeFrames.sigEligible(env, selfId(), scope, inbound = false)) return 0
        val data = ScopeFrames.sealSig(scope, sig, signed)
        return workers.values.count { it.sigOut(scope, data) }
    }

    /**
     * The scope a sig of [conversationId]'s rides: the DM peer's current scope or the group's, never a retiring one, a
     * pair scope or a commons (C-7.8-13). A DM's conversation id is its peer's node id, which no group id can equal.
     */
    private fun sigScope(conversationId: String): Scope? =
        scopes.firstOrNull {
            !it.retiring && !it.pair && it.commonsId == null && (it.peerId == conversationId || it.groupId == conversationId)
        }

    /**
     * The credential-free STUN URIs every connected spool advertised in its HELLO (C-7.6-20), in worker order and
     * without repeats. Not capped here: the call picks from these and what its TURN servers reduce to
     * (`call/StunServers`). Lock-free and synchronous — the hellos are already in memory.
     */
    fun stunUrls(): List<String> = workers.values.flatMap { it.stunUrls() }.distinct()

    /** Whether some connected spool would answer this phone's `turnreq` now ([SpoolStatus.turnForUs]). Lock-free. */
    fun turnAvailable(): Boolean = workers.values.any { it.turnForUs() }

    private fun describeTurn(reply: SpoolReply): String =
        when (reply) {
            is SpoolReply.Turn -> "credential, ${reply.urls.size} url(s)"
            is SpoolReply.Failed -> "refused ${reply.code}${reply.msg?.let { "/$it" }.orEmpty()}"
            else -> "no answer"
        }

    /** Fires [onRelaysChanged] when the worker set differs from what was last published. */
    @Synchronized
    private fun publishRelays(current: Set<String>) {
        if (current == lastRelays) return
        lastRelays = current
        onRelaysChanged(current)
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
     * A scope-table input landed: re-derive the table now instead of on the [RECONCILE_INTERVAL_MS] poll,
     * so a freshly pasted invite is subscribed while the user is still looking at it and a session that
     * just confirmed finds its scope at once. Every input with an event calls this (ADR 2026-09.dcah): a
     * DM session confirmed, replaced or forgotten (`RatchetSessions.rootChanges`), a pair peer named,
     * evicted, lapsed or pinned (`IntroSync.onPairsChanged`), a group root minted or adopted, a commons
     * joined or left, and the relay list edited (`MeshController.refreshRelays`). Cheap and idempotent: an
     * unchanged table wakes nobody, so a spurious call costs the derivation and nothing else.
     */
    fun onScopeTableChanged() {
        val host = session ?: return
        // One queued derivation absorbs every event that lands before it starts; it clears the flag as it
        // takes the lock, so an event during its run queues the next one rather than being lost.
        if (!reconcileQueued.compareAndSet(false, true)) return
        host.launch {
            reconcileLock.withLock {
                reconcileQueued.set(false)
                reconcile()
            }
        }
    }

    /**
     * Derives the scope table, then runs a worker per relay it needs — the [urls] base and every scope's own relays
     * ([Scope.spools]) — and stops the ones no longer needed.
     */
    private suspend fun reconcile() {
        val host = session ?: return
        val base = urls().toSet()
        val table = if (base.isEmpty()) emptyList() else registry.scopes(clock())
        val wanted = base + table.flatMap { it.spools.orEmpty() }
        workers.keys.filterNot { it in wanted }.forEach { workers.remove(it)?.stop() }
        // A removed worker's stop() cancels its session before the teardown's republish, and with the relays
        // switched off no round is left to run one: withdraw the cover the removed spools gave here (#196).
        republishPresence()
        publishRelays(wanted)
        if (wanted.isEmpty()) {
            scopes = emptyList()
            return
        }
        scopes = table
        metrics.onSpoolTableDerived()
        wanted.forEach { url ->
            val worker = workers.computeIfAbsent(url) { Worker(it) }
            val changed = worker.adoptScopes(scopes)
            worker.ensureRunning(host)
            // Only a changed table wakes the heal loop — a scope that appeared needs its SUB now. An
            // unchanged one used to wake every worker on every reconcile regardless, and each wake ran a
            // full custody round per scope: four times the 60 s tick, idle or converged, for nothing.
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

    /** What one `aput` came to ([Worker.putChunk]). */
    private enum class ChunkPut {
        /** Stored, or refused in a way that leaves the next chunk worth trying. */
        NEXT,

        /** The rest of this attachment is not worth attempting on this connection now. */
        STOP,

        /** The spool refused it `size` (S-7.6-31): too large for this relay under this ticket. */
        TOO_LARGE,
    }

    /** An attachment a round passed by without a round trip, and how much of a later look it still owes. */
    private enum class LocalPass(
        val owed: Int,
    ) {
        /** Our own screening refused it (#109): settled, owed nothing. */
        REFUSED(0),

        /** The radios still carry it (§9.5's push-half deferral): owed a look once that lapses. */
        DEFERRED(1),

        /**
         * Ours, in a DM whose recipient provably holds the bytes over a short-range radio (C-9.5-6's exception, ADR
         * 2026-10.vy27): settled, owed nothing, and never pushed — a timed recheck only proves it again.
         */
        PROVED(0),

        /**
         * Ours to push, on a connection that may not take this phone's uploads (C-7.6-24): settled, owed nothing, and
         * looked at again only when a files ticket is accepted here or the connection is replaced.
         */
        LOCKED(0),

        /**
         * Ours to push, but larger than this connection's files ticket lets one attachment be (`am`, C-7.6-32), or the
         * spool already said so (`size`): settled, owed nothing, never `aput`. Looked at again when a files ticket is
         * accepted here, whose `am` may differ, or the connection is replaced. Uploads stay open for the rest.
         */
        TOO_LARGE(0),

        /** Someone else's commons attachment that landed since the room's rows were read: nothing left to push or fetch. */
        LANDED(0),
    }

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

        // The scopes this spool has asked a stamp for (`err pow` on a request that carried none) and not yet
        // taken one for. A spool demands a stamp only for a scope it does not hold (S-6.4-2), so a stamp goes
        // out only after a demand: mining one for every scope on every SUB spent a stamp per scope at each
        // process start and each UTC day, for spools that already held them all (device-observed 2026-10-12).
        private val stampDemanded: MutableSet<String> = ConcurrentHashMap.newKeySet()

        // The scopes whose latest SUB carried a stamp: a SUB's refusal names no request (§7.2), so this is how
        // [handleScopeError] tells a demand from a stamp the spool turned down.
        private val stampedSubs: MutableSet<String> = ConcurrentHashMap.newKeySet()

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

        // Joined commons this spool showed it does not run ([markStale]): its operator rotated the invite, so the
        // id we derive is an ordinary scope there now. Reported, so the room can say why it went quiet and refuse
        // new posts, and subscribed again only as one probe per connection ([reprobing]) — a SUB on every round
        // would keep a junk scope alive on the operator's relay, and none at all would leave a room locked after
        // the operator fixed a slip (a restart without the room's setting, a reverted invite) until the app restarts.
        // Not in [forgetScopesNotIn]'s list on purpose: leaving [mine] is what being stale does.
        private val staleCommons: MutableSet<String> = ConcurrentHashMap.newKeySet()

        // The stale commons this connection re-subscribes once to look again: filled from [staleCommons] when a
        // hello completes, emptied as each answer comes. Still reported stale meanwhile, so the room does not
        // flicker open; a DIGEST with the HELLO's pinned bounds clears the verdict, a probe echo renews it.
        private val reprobing: MutableSet<String> = ConcurrentHashMap.newKeySet()

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

        // scopeHex → what this worker pushes into it ([pushShape]) as of the last adopted table.
        private var lastShapes: Map<String, String> = emptyMap()

        // scopeHex → attachment hash → when a round last found nothing left to do for it: fetched, pushed
        // whole, dead, or past its frame's TTL. Skipped until a timed round finds the entry older than
        // ATTACHMENT_RECHECK_MS (the spool may have evicted it since), and dropped with the connection.
        private val settledAttachments = ConcurrentHashMap<String, LinkedHashMap<String, Long>>()

        // scopeHex → the attachments of ours a round settled without asking because this connection may not take
        // our uploads ([LocalPass.LOCKED]). Guarded by [settledAttachments]' lock; what [reopenUploads] un-settles
        // when a files ticket is accepted, so they go up without waiting for the hourly recheck.
        private val lockedUploads = HashMap<String, MutableSet<String>>()

        // scopeHex → the attachments of ours a round settled as larger than this connection's ticket admits
        // ([LocalPass.TOO_LARGE]), and the hashes the spool itself refused `size` for here, whatever we computed. Both
        // guarded by [settledAttachments]' lock, and both undone by [reopenUploads]: the next files ticket's `am` may
        // take them.
        private val tooLargeUploads = HashMap<String, MutableSet<String>>()
        private val sizeRefused = LinkedHashSet<String>()

        // The word of the `err ticket` an `aput` drew on this connection (`missing`, `expired`, `flags`, or `budget`
        // without a pseudonym to mark): uploads stay locked here until a files ticket is next accepted. Reset with
        // every session, since the next connection presents afresh.
        @Volatile
        private var uploadRefusal: String? = null

        // What the last hello said about attachments ([SpoolStatus.seenAttachments]); kept across sessions.
        @Volatile
        private var seenAttachments: Boolean? = null

        @Volatile
        private var seenGates: Long? = null

        /** Keeps what [conn]'s hello said about attachments past this session, for a photo staged while it is down. */
        private fun rememberHello(conn: SpoolConnection) {
            seenAttachments = conn.limits?.attachments == true
            seenGates = conn.gates
            // Each stale commons gets one look per connection, in the SUB that follows: a slip its operator has fixed
            // unlocks the room.
            reprobing.clear()
            reprobing.addAll(staleCommons)
        }

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

        // The ticket presented on the current connection, so a store change that is not a new import (an answer's
        // verdict, a clear) never sends the same bytes twice on one connection. Reset with every session.
        @Volatile
        private var presented: HeldTicket? = null
        private val presentLock = Mutex()

        // The most recent `err` code this spool answered with, for diagnostics. Cleared on a clean
        // connect so a stale refusal doesn't outlive the condition that caused it — and only then, so a
        // dial verdict (`unreachable`, `no_hello`) holds through the next attempt rather than flickering
        // back to "connecting" for the seconds the socket is being tried again.
        @Volatile
        private var lastError: String? = null

        // Sessions in a row that ended without a completed hello (see [SpoolStatus.dialFailures]).
        @Volatile
        private var dialFailures = 0

        // The last `err` this session's field log line named, so a refusal repeated every round is one line (an edge).
        @Volatile
        private var errLogged: String? = null

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
         * Takes the reconciled scope table: [forgetScopesNotIn] for what left this relay, and whether the scopes that
         * belong here differ from the ones this worker last adopted — the only reason a reconcile wakes the heal loop.
         * A scope whose relays moved onto this one (ADR 2026-10.8kfx) is a change here as much as a new one.
         */
        fun adoptScopes(all: List<Scope>): Boolean {
            val table = all.filter { it.belongsAt(url) }
            val live = table.mapTo(HashSet()) { it.idHex }
            forgetScopesNotIn(live)
            dirty.retainAll(live)
            synchronized(settledAttachments) {
                settledAttachments.keys.retainAll(live)
                lockedUploads.keys.retainAll(live)
                tooLargeUploads.keys.retainAll(live)
            }
            // A scope that stayed but now takes a different push set — a live DM scope that became the retiring
            // one our reset rides, or the reset's ephemeral cleared on the answer (ADR 2026-10.j67x) — is healed
            // now: the round that ran on the custody change may have judged the reset against the old table, and
            // nothing else marks the scope before the 60 s tick.
            val shapes = table.associate { it.idHex to pushShape(it) }
            val reshaped = shapes.filter { (id, shape) -> lastShapes[id]?.let { it != shape } == true }.keys
            dirty += reshaped
            val changed = live != lastLive || reshaped.isNotEmpty()
            lastLive = live
            lastShapes = shapes
            return changed
        }

        /** What decides a scope's push set beyond its id: retiring, and the pending reset it takes. */
        private fun pushShape(scope: Scope): String = "${scope.retiring}:${scope.resetInit?.let { hex(it) }.orEmpty()}"

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
            stampDemanded.retainAll(live)
            stampedSubs.retainAll(live)
            synchronized(invalidAttachments) { invalidAttachments.keys.retainAll(live) }
            synchronized(parked) { parked.keys.retainAll(live) }
            // And the connection's own record, or a scope that comes back is never SUBbed again and its heal
            // round waits forever for a digest that was already answered before it left.
            connection?.retainSubscriptions(live)
        }

        /**
         * Whether, and how, this worker's live connection may be asked for a TURN credential, or null when it is no
         * candidate at all: no completed hello that carried `turn`, or (without a ticket) no answered SUB for one of
         * [hexes] — a digest arrived, so the spool counts the subscription. A ticket that grants TURN, accepted or
         * on its way, qualifies on its own (C-7.6-21). A spool that gates TURN (bit 0 of `entGates`) without one is a
         * [TurnAsk] with no connection: a skip worth saying, since S-7.6-16 would only refuse it. One that gates only
         * other verbs is asked like an unticketed spool (C-7.6-30). Lock-free like [status]; the connection is
         * concurrency-safe for requests.
         */
        fun turnAsk(hexes: Collection<String>): TurnAsk? {
            val conn = connection?.takeIf { it.isReady && it.turn } ?: return null
            val host = SpoolUrl.host(url)
            return when {
                conn.ticketedTurn -> TurnAsk(host, conn, ticketed = true)
                conn.gated(SpoolTicket.FL_TURN) -> TurnAsk(host, connection = null, ticketed = false)
                hexes.any(spoolDigests::containsKey) -> TurnAsk(host, conn, ticketed = false)
                else -> null
            }
        }

        /** The STUN URIs this worker's live connection advertised; none while it has no completed hello. */
        fun stunUrls(): List<String> = connection?.takeIf { it.isReady }?.stun.orEmpty()

        /** The live connection's `turnHosts` where it would issue this phone TURN ([turnForUs]); else none. */
        fun measuredTurnHosts(): List<String> = connection?.takeIf { turnForUs() }?.turnHosts.orEmpty()

        /** [SpoolStatus.turnForUs] for this worker's live connection. */
        fun turnForUs(): Boolean =
            connection?.takeIf { it.isReady && it.turn }?.let { it.ticketedTurn || !it.gated(SpoolTicket.FL_TURN) } == true

        /**
         * Whether this worker's live connection would take a cast for [scope]: the scope belongs here and its SUB was
         * answered (a digest arrived, so the spool counts the subscription), and the connection's own rule holds —
         * casts advertised, and where its HELLO gates casts a cast ticket here or the peer's. Lock-free like [turnAsk].
         */
        fun canCast(
            scope: Scope,
            peerTicketed: Boolean,
        ): Boolean {
            val conn = connection ?: return false
            return scope.belongsAt(url) && spoolDigests.containsKey(scope.idHex) && conn.canCast(scope.idHex, peerTicketed)
        }

        /** Whether [scope]'s own peer put something recent into it on this spool within [lingerMs] of [now]. */
        fun peerSeenWithin(
            scope: Scope,
            now: Long,
            lingerMs: Long,
        ): Boolean = peerSeenAt[scope.idHex]?.let { now - it <= lingerMs } == true

        /** Whether this phone's own ticket unlocks casts on this worker's live connection. */
        fun castTicketed(): Boolean = connection?.takeIf { it.isReady && it.cast != null }?.ticketedCast == true

        /**
         * Whether this worker's live connection would take a sig for [scope]: the scope belongs here, its SUB was answered,
         * and the hello advertised sigs ([SpoolConnection.canSig]). Lock-free like [canCast].
         */
        fun canSig(scope: Scope): Boolean {
            val conn = connection ?: return false
            return scope.belongsAt(url) && spoolDigests.containsKey(scope.idHex) && conn.canSig(scope.idHex)
        }

        /** Sends one sealed sig for [scope] on the live connection; false when it would not leave here. */
        fun sigOut(
            scope: Scope,
            data: ByteArray,
        ): Boolean {
            val conn = connection ?: return false
            return canSig(scope) && conn.sigOut(scope.id, data)
        }

        /** Sends one sealed cast for [scope] on the live connection; false when it would not leave here. */
        fun castOut(
            scope: Scope,
            data: ByteArray,
            peerTicketed: Boolean,
        ): Boolean {
            val conn = connection ?: return false
            return canCast(scope, peerTicketed) && conn.castOut(scope.id, data, peerTicketed)
        }

        /**
         * Presents this host's ticket on [conn] if one is held and presentable and it is not the ticket already
         * presented on this connection — so an import shows up at once (and replaces the accepted one), while a
         * refusal or an answer's own verdict write never re-sends the same bytes on the same connection (C-7.6-19).
         * The connection itself refuses a spool that never advertised `ent` and a ticket for another host.
         */
        private suspend fun presentTicket(conn: SpoolConnection) {
            val store = tickets ?: return
            if (!conn.ent || !conn.isReady) return
            // The session and the store watch both call this; one at a time, or both could send the same bytes.
            presentLock.withLock {
                val ticket = store.presentable(url) ?: return
                if (presented?.sameAs(ticket) == true) return
                presented = ticket
                if (conn.present(ticket)) log("${SpoolUrl.host(url)}: ticket presented (exp ${ticket.claims.exp})")
            }
        }

        /** The spool answered a ticket this worker presented: apply the refusal policy and say what came back. */
        private suspend fun ticketAnswered(
            ticket: HeldTicket,
            reply: SpoolReply,
        ) {
            val host = SpoolUrl.host(url)
            val failed = reply as? SpoolReply.Failed
            when {
                failed == null -> {
                    log("$host: ticket accepted (exp ${ticket.claims.exp}, fl ${ticket.claims.fl})")
                    tickets?.answered(url, ticket, refusal = null)
                    if (ticket.grantsFiles) reopenUploads(ticket)
                }

                failed.code == SpoolErrCode.TICKET -> {
                    val word = failed.msg ?: failed.code
                    log("$host: ticket refused: $word")
                    tickets?.answered(url, ticket, refusal = word)
                }

                // `rate`, `internal`: no verdict on the ticket. The next connection presents it again.
                else -> {
                    log("$host: ticket not answered: ${failed.code}")
                }
            }
        }

        /**
         * Whether [conn] may take this phone's uploads (C-7.6-24): the connection's own answer ([SpoolConnection.uploads])
         * narrowed by what this plane learned from the spool — an `aput` it refused here, and a pseudonym whose day is
         * spent at this relay ([spentUploads]). Lock-free; read before every push and by [status].
         */
        fun uploadsOn(conn: SpoolConnection): SpoolUploads {
            val own = conn.uploads()
            val refusal = uploadRefusal
            return when {
                !conn.gated(SpoolTicket.FL_FILES) -> own
                refusal == SpoolUploads.BUDGET -> SpoolUploads.Spent
                refusal != null -> SpoolUploads.Locked(refusal)
                own == SpoolUploads.Open && spentUploads[spentKey()]?.let { it == conn.uploadPseudonym() } == true -> SpoolUploads.Spent
                else -> own
            }
        }

        // The spent mark's key: the host name a ticket's `aud` names, so it is the relay the ticket was issued for.
        private fun spentKey(): String = SpoolUrl.hostname(url) ?: url

        /**
         * An `aput` here drew `err ticket` [word] (C-7.6-24): `budget` spends the connection's ticket pseudonym at this
         * relay for its day, anything else locks this connection until a files ticket is next accepted. Nothing is
         * retried: the attachment is settled by the caller, and the next ones are settled without asking.
         */
        private fun uploadRefused(
            conn: SpoolConnection,
            word: String?,
        ) {
            val reason = word ?: SpoolUploads.MISSING
            val pseudonym = conn.uploadPseudonym()
            if (reason == SpoolUploads.BUDGET && pseudonym != null) {
                spentUploads[spentKey()] = pseudonym
            } else {
                uploadRefusal = reason
            }
            log("${SpoolUrl.host(url)}: uploads refused: $reason")
        }

        /**
         * The spool accepted [ticket], which grants files: the refusal lock lifts, and unless its pseudonym is the one
         * spent here today, uploads open again on this connection — a spent mark for another pseudonym goes, and every
         * attachment a round settled as [LocalPass.LOCKED] is looked at on the next round rather than an hour later.
         */
        private fun reopenUploads(ticket: HeldTicket) {
            // The refusal lock was this connection's ticket's; an accepted files ticket replaces it either way.
            uploadRefusal = null
            val pseudonym = ticket.pseudonym
            val key = spentKey()
            if (pseudonym != null && spentUploads[key] == pseudonym) return
            if (pseudonym != null) spentUploads.remove(key)
            synchronized(settledAttachments) {
                for ((scopeHex, hashes) in lockedUploads) settledAttachments[scopeHex]?.keys?.removeAll(hashes)
                lockedUploads.clear()
                // A new ticket's `am` may take what the last one did not (C-7.6-32).
                for ((scopeHex, hashes) in tooLargeUploads) settledAttachments[scopeHex]?.keys?.removeAll(hashes)
                tooLargeUploads.clear()
                sizeRefused.clear()
            }
            markAllDirty()
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
                adverts = connection?.takeIf { it.isReady }?.adverts,
                rooms = advertisedRooms(all),
                software = connection?.let { software },
                ent = connection?.takeIf { it.isReady }?.ent == true,
                stun = stunUrls(),
                ticket = connection?.takeIf { it.isReady }?.ticketState() ?: SpoolTicketState.None,
                turn = connection?.takeIf { it.isReady }?.turn == true,
                turnHosts = connection?.takeIf { it.isReady }?.turnHosts.orEmpty(),
                turnForUs = turnForUs(),
                cast = connection?.takeIf { it.isReady }?.cast,
                sig = connection?.takeIf { it.isReady }?.sig,
                entGates = connection?.takeIf { it.isReady }?.gates ?: 0L,
                uploads = connection?.takeIf { it.isReady }?.let(::uploadsOn) ?: SpoolUploads.Open,
                uploadLimitChunks = connection?.takeIf { it.isReady }?.uploadLimitChunks(),
                seenAttachments = seenAttachments,
                seenGates = seenGates,
                staleCommons = staleRooms(all),
                scopes =
                    mine(all, connection).map { scope ->
                        ScopeStatus(
                            scopeHex = scope.idHex,
                            label = scope.label,
                            localCount = localCounts[scope.idHex] ?: 0,
                            spoolCount = spoolCounts[scope.idHex] ?: 0,
                            // An anchor we hold from the spool that matches ours. Unknown on both sides — a
                            // scope derived before its SUB is answered, or between sessions (the anchors go
                            // with the connection) — is not agreement: the lab's `awaitScope` and the row read
                            // "converged" for a scope no relay had ever heard of once the table started
                            // deriving on events (ADR 2026-09.dcah) rather than after a poll the dial beat.
                            converged = spoolDigests[scope.idHex]?.let { it == localDigests[scope.idHex] } == true,
                            invalidCount = invalid[scope.idHex]?.size ?: 0,
                            retiring = scope.retiring,
                            accountedCount = accountedFor(scope.idHex).size,
                            peerSeenAt = peerSeenAt[scope.idHex],
                            pair = scope.pair,
                            pendingReset = scope.resetInit != null,
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
            val dialedAt = clock()
            lastDialAt = dialedAt
            val socket = dialer.dial(url) ?: return unreached(dialedAt)
            errLogged = null
            val conn = connect(socket)
            software = null
            presented = null
            uploadRefusal = null
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
            val readyAt = clock()
            // A socket that opened and never carried a hello is dropped as our own verdict, through
            // `abort` like every client-side close (ADR 2026-09.amzn), so the backoff grows and the status
            // says so instead of reading "connecting" forever. Both guards matter: the dialer writes
            // `closeReason` before the pump's completion closes the connection, so `isOpen` alone would
            // relabel a transport death as this fault.
            if (!ready && conn.isOpen && socket.closeReason == null) conn.abort(NO_HELLO)
            if (ready) {
                rememberHello(conn)
                // Paired with the dialer's "spool socket failed" line: together they bound when a frame could reach
                // the spool (a call offer queued in between went out on the next hello, possibly after its end).
                log("${SpoolUrl.host(url)}: connected (hello ${readyAt - dialedAt} ms after the dial)")
                fieldUp(conn, readyAt - dialedAt)
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
                val ticketWatch = presentAndWatchTickets(conn, host)
                subscribe(conn, mine(scopes, conn))
                republishPresence() // a connected spool is what makes a stamped peer count
                // The tick is a mark like any other, so a scope busy with events still gets its full pass and
                // a woken round can tell it was not the tick (the rig's clock is frozen, so a deadline could
                // not be computed from it). The anchors were cleared with the last session, so the SUB answers
                // mark every scope dirty and the first round follows them.
                val ticker =
                    host.launch {
                        while (isActive) {
                            delay(tickIntervalMs)
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
                    ticketWatch?.cancel()
                    log("${SpoolUrl.host(url)}: disconnected after ${(clock() - dialedAt) / MS_PER_S} s")
                }
            }
            pump.cancel()
            val reached = endSession(socket, conn, ready)
            fieldEnd(socket, conn, ready, dialedAt, readyAt)
            return reached
        }

        /**
         * C-7.6-19: the ticket goes right after the hello, ahead of the first SUB, and again whenever a different one
         * is imported while this connection lives; only an `ent` spool is watched for one. Each time, the TURN servers
         * this hello names become measurable once its ticket is on its way (C-7.6-52). The watch, on [host], or null.
         */
        private suspend fun presentAndWatchTickets(
            conn: SpoolConnection,
            host: CoroutineScope,
        ): Job? {
            presentTicket(conn)
            publishTurnHosts()
            val store = tickets?.takeIf { conn.ent } ?: return null
            return host.launch {
                store.changes.collect {
                    presentTicket(conn)
                    publishTurnHosts()
                }
            }
        }

        /** The dial [session] began at [dialedAt] got no socket at all: the URL could not be dialled. Never reached. */
        private fun unreached(dialedAt: Long): Boolean {
            lastError = UNREACHABLE
            dialFailures++
            field.record(FieldEvent.SpoolFail(relayKey, SpoolVerdict.UNREACHABLE, clock() - dialedAt, dialFailures))
            return false
        }

        /** The field log's line for a completed hello, [helloMs] after its dial: what the hello offered. */
        private fun fieldUp(
            conn: SpoolConnection,
            helloMs: Long,
        ) = field.record(
            FieldEvent.SpoolUp(
                relay = relayKey,
                helloMs = helloMs,
                fails = dialFailures,
                powBits = conn.powBits,
                attachments = conn.limits?.attachments == true,
                ent = conn.ent,
                turn = conn.turn,
                cast = conn.cast != null,
            ),
        )

        /**
         * The field log's line for a session [endSession] just filed: a dial that never reached a hello, or a session
         * that did. Its reason is how this connection ended — our fault, else the spool's close, else nothing said —
         * never [lastError], which can be an older refusal still standing.
         */
        private fun fieldEnd(
            socket: SpoolSocket,
            conn: SpoolConnection,
            ready: Boolean,
            dialedAt: Long,
            readyAt: Long,
        ) {
            val (reason, code) = SpoolVerdict.of(conn.fault ?: socket.closeReason)
            field.record(
                if (ready) {
                    FieldEvent.SpoolDown(relayKey, reason, clock() - readyAt, ours = conn.fault != null, code = code)
                } else {
                    FieldEvent.SpoolFail(relayKey, reason, clock() - dialedAt, dialFailures, code, socket.retryAfterMs)
                },
            )
        }

        /** The relay as the field log keys it: its URL without the `?k=` token (a pseudonym is made of it). */
        private val relayKey get() = SpoolUrl.redact(url)

        /**
         * Files an `err` [code] the spool answered (or one of our own verdicts on what it sent): the counter, the status
         * row's [lastError], and a field log line when it differs from the last one this session logged.
         */
        private fun refused(code: String) {
            metrics.onSpoolError()
            lastError = code
            if (code == errLogged) return
            errLogged = code
            field.record(FieldEvent.SpoolErr(relayKey, SpoolVerdict.of(code).first))
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
            if (ready) publishTurnHosts() // nor names a TURN server to measure (C-7.6-52)
            spoolDigests.clear()
            pinnedBounds.clear()
            // The per-connection race guard goes; the §9.6 accounted set deliberately does NOT. Dropping
            // `accepted` is what lets a custody wipe re-converge by the ordinary route (everything still
            // live is simply re-pulled); dropping `accounted` only re-pulls what can never be held, which
            // is the reconnect storm ADR 062 is about.
            accepted.clear()
            // What a round settled was settled against THIS spool's chunk store; the next session asks again.
            synchronized(settledAttachments) {
                settledAttachments.clear()
                lockedUploads.clear()
                tooLargeUploads.clear()
                sizeRefused.clear()
            }
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
                onTicketAnswer = { ticket, reply -> ticketAnswered(ticket, reply) },
                clock = clock,
                onCast = { handleCast(it) },
                onSig = { handleSig(it) },
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
            val subs =
                batch.map { scope ->
                    val pow = stampFor(scope, conn.powBits)
                    if (pow != null) stampedSubs.add(scope.idHex) else stampedSubs.remove(scope.idHex)
                    ScopeSub(scope = scope.id, bounds = declaredBounds(scope, conn), pow = pow)
                }
            val sent = conn.sub(subs)
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
         * How [conn]'s HELLO speaks of the commons [scope] (C-7.4-17): by its tag in `rooms`, as the one room a spool
         * that predates `rooms` advertises, or not at all. Absent before any HELLO, and for any other scope.
         */
        private fun advertOf(
            scope: Scope,
            conn: SpoolConnection?,
        ): CommonsAdvert = scope.commonsTag?.let { conn?.adverts?.advertFor(it) } ?: CommonsAdvert.Absent

        /**
         * The scopes this worker carries: every scope that belongs at this relay ([Scope.belongsAt]), minus a
         * commons its HELLO does not advertise (C-7.4-11) — a SUB for it there would read as an unknown scope and
         * meet the §6.4 creation gates (PoW, the new-scope bucket) for a room that does not exist — and minus a
         * commons this spool has shown it no longer runs ([staleCommons]), but for the one look a new connection
         * takes at it ([reprobing]).
         */
        private fun mine(
            all: List<Scope>,
            conn: SpoolConnection?,
        ): List<Scope> =
            all.filter {
                it.belongsAt(url) &&
                    (it.commonsId == null || (advertOf(it, conn).info != null && (it.idHex !in staleCommons || it.idHex in reprobing)))
            }

        /**
         * The joined commons bound here that this spool does not run, by conversation id: one its answers marked
         * stale ([markStale]), or any a completed HELLO does not advertise — its tag missing from `rooms`, or no room
         * advertised at all (C-7.4-17).
         */
        private fun staleRooms(all: List<Scope>): Set<String> {
            val conn = connection?.takeIf { it.isReady }
            return all
                .filter {
                    it.commonsId != null &&
                        it.belongsAt(url) &&
                        ((conn != null && advertOf(it, conn).info == null) || it.idHex in staleCommons)
                }.mapNotNullTo(HashSet()) { it.commonsId }
        }

        /** The joined commons bound here that this spool runs, by conversation id, each with its own advertisement. */
        private fun advertisedRooms(all: List<Scope>): Map<String, SpoolCommonsInfo> {
            val conn = connection?.takeIf { it.isReady } ?: return emptyMap()
            return all
                .filter { it.belongsAt(url) && it.idHex !in staleCommons }
                .mapNotNull { scope -> scope.commonsId?.let { room -> advertOf(scope, conn).info?.let { room to it } } }
                .toMap()
        }

        /**
         * A commons this spool answered as no commons (§7.4): its DIGEST echoed what we declared rather than the
         * bounds its HELLO pinned (S-7.4-2), or it asked a creation gate of us (S-7.4-3). Either way the invite we
         * hold is not the one this spool runs — the operator rotated it.
         */
        private fun markStale(scope: Scope) {
            reprobing.remove(scope.idHex)
            if (staleCommons.add(scope.idHex)) {
                log("${SpoolUrl.host(url)}: ${scope.label} is no longer run there: the invite changed")
            }
            wake()
        }

        /** A stale commons whose probe this spool answered as the room it runs: the operator fixed the slip. */
        private fun clearStale(scope: Scope) {
            reprobing.remove(scope.idHex)
            if (staleCommons.remove(scope.idHex)) {
                log("${SpoolUrl.host(url)}: ${scope.label} is run there again")
                wake()
            }
        }

        /**
         * The `ttlMs` a commons SUB declares: the HELLO's, one millisecond short. The spool running the room
         * substitutes its pinned bounds (S-7.4-2), so its DIGEST echoes the HELLO's own; a stale id is an
         * ordinary scope that applies what we declared, and echoes this. [markStale] reads the difference.
         */
        private fun probeTtl(advertised: SpoolCommonsInfo): Long =
            if (advertised.ttlMs > PROBE_SHORTFALL_MS) advertised.ttlMs - PROBE_SHORTFALL_MS else advertised.ttlMs

        /**
         * Whether [digest], the answer to a commons SUB, came from a spool that does not run that room: anything but the
         * TTL its HELLO pinned (S-7.4-2) is what an ordinary scope applying our [probeTtl] echoes. Only a spool that
         * predates `rooms` is judged this way ([CommonsAdvert.Sole]): one that lists the room by its tag has said so
         * already. No verdict with a TTL too short to probe.
         */
        private fun answeredAsNoCommons(
            scope: Scope,
            digest: SpoolDigest,
        ): Boolean {
            val advertised = (advertOf(scope, connection) as? CommonsAdvert.Sole)?.info ?: return false
            return advertised.ttlMs > PROBE_SHORTFALL_MS && digest.bounds.ttlMs != advertised.ttlMs
        }

        /**
         * What we declare at SUB. For a commons the spool ignores the declaration and pins its own (§7.4),
         * so we declare what its HELLO advertised for the room — then [SpoolConnection.inboundCap] and the pinned
         * truth agree and no honest event is dropped as oversize. At a spool that predates `rooms` the TTL goes a
         * millisecond short: the probe that tells the room from a stale id ([probeTtl]).
         */
        private fun declaredBounds(
            scope: Scope,
            conn: SpoolConnection,
        ): ScopeBounds =
            when (val advert = advertOf(scope, conn)) {
                is CommonsAdvert.Listed -> advert.info.let { ScopeBounds(it.maxFrames, it.ttlMs, it.maxBlob) }
                is CommonsAdvert.Sole -> advert.info.let { ScopeBounds(it.maxFrames, probeTtl(it), it.maxBlob) }
                CommonsAdvert.Absent -> scope.bounds
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
                healAttachments(conn, scope, frames, timed)
                return
            }
            reconcileListing(conn, scope, local, accountedHere)
            healAttachments(conn, scope, frames, timed)
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
            if (!fits) refused(OVERLONG_LISTING)
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
            // A retiring scope is drained, never refilled (C-3.1-3) — but for our own unanswered reset into the
            // scope its peer is still on (C-3.1-5, ADR 2026-10.j67x).
            if (scope.retiring && scope.resetInit == null) return emptyList()
            val me = selfId()
            val pushed = mutableListOf<ByteArray>()
            val refill =
                pushable(conn, scope, local, spoolIds, tombstoned, quarantined).filter {
                    ScopeFrames.mayRefill(it.carried.envelope, me, scope)
                }
            for (entry in refill) {
                val pow = stampFor(scope, conn.powBits)
                val reply = conn.push(scope.id, entry.sealed.blobId, entry.sealed.blob, pow)
                if (reply is SpoolReply.Ok) {
                    pushed.add(entry.sealed.blobId)
                    metrics.onSpoolPushed()
                } else {
                    if (reply is SpoolReply.Failed && !stampDemand(scope.idHex, reply, pow)) refused(reply.code)
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
            val pow = stampFor(scope, conn.powBits)
            val reply = conn.push(scope.id, sealed.blobId, sealed.blob, pow)
            if (reply is SpoolReply.Failed && !stampDemand(scope.idHex, reply, pow)) refused(reply.code)
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
            // S-7.4-5: a spool answers attachment records naming a room `err malformed` unless its HELLO said so for it.
            if (scope.commonsId != null && advertOf(scope, conn).info?.attach != true) return
            val (candidates, fetchOnly) = attachmentCandidates(scope, frames, timed)
            var handled = 0
            var pending = 0
            for ((ref, aHashBytes) in candidates) {
                if (handled == ATTACHMENTS_PER_ROUND) {
                    pending++
                    continue
                }
                val mine = blobStore.has(ref.aHash)
                val passed = passLocally(conn, scope, ref, mine, blobStore, theirs = ref.aHash in fetchOnly)
                if (passed != null) {
                    pending += passed.owed
                } else {
                    handled++
                    // A dead socket ends the round; there is nothing useful to try against it.
                    val done = exchangeAttachment(conn, scope, ref, aHashBytes, mine, ref.aHash in fetchOnly, blobStore) ?: return
                    if (!settledAfter(conn, scope, ref.aHash, mine, done)) pending++
                }
            }
            // Something is still in flight or still owed: look again on the old reconcile's cadence rather
            // than the tick's, and only for this scope. A settled scope schedules nothing.
            if (pending > 0) scheduleAttachmentRetry(scope)
        }

        /**
         * The attachments a round may work for [scope], newest of each kind first and at most [ATTACHMENT_SCAN_PER_ROUND],
         * with the hashes among them that are only ever fetched: ours from [frames], and — in a commons — every other
         * member's from our record of the room (C-7.4-6: each member pushes its own). Quarantined and settled ones are
         * left out of both, the room's read included, so its limit counts live candidates.
         */
        private suspend fun attachmentCandidates(
            scope: Scope,
            frames: List<CarriedFrame>,
            timed: Boolean,
        ): Pair<List<Pair<ScopeAttachments.Ref, ByteArray>>, Set<String>> {
            val skip = quarantinedAttachments(scope) + settledAttachmentsFor(scope, recheckOlder = timed)
            val ours = ScopeAttachments.references(frames, scope, selfId())
            val theirs = scope.commonsId?.let { commonsTheirs(scope, it, ours, skip) }.orEmpty()
            val candidates =
                (ours + theirs)
                    .filterNot { it.aHash in skip }
                    .mapNotNull { ref -> ScopeAttachments.hashBytes(ref.aHash)?.let { ref to it } }
                    .take(ATTACHMENT_SCAN_PER_ROUND)
            return candidates to theirs.mapTo(HashSet()) { it.aHash }
        }

        /**
         * One attachment's round trip: `ahave`, then the push of one we hold or the fetch of one we lack. True when it is
         * done with, null when the socket died. Someone else's commons attachment the spool still lacks past
         * [ABSENT_GRACE_MS] counts as done too ([fetchOnly]): the room's relay is its only way here and its author may
         * never put it (uploads locked, over its `am`, gone), so asking every 15 s would only spend round trips, and the
         * timed recheck asks again. A fresh post keeps the short retry — its author's chunks are usually on their way.
         */
        @Suppress("LongParameterList") // the round trip's inputs, threaded once from the loop
        private suspend fun exchangeAttachment(
            conn: SpoolConnection,
            scope: Scope,
            ref: ScopeAttachments.Ref,
            aHashBytes: ByteArray,
            mine: Boolean,
            fetchOnly: Boolean,
            blobStore: ScopeBlobs,
        ): Boolean? {
            val aid = ScopeCrypto.attachmentId(scope.keys, scope.id, aHashBytes)
            val presence = conn.ahave(scope.id, aid) ?: return null
            if (mine) return pushAttachment(conn, scope, ref, aid, aHashBytes, presence, blobStore)
            return fetchAttachment(conn, scope, ref, aid, presence, blobStore) ||
                (fetchOnly && presence.total <= 0 && clock() - ref.sentAt > ABSENT_GRACE_MS)
        }

        /**
         * The attachments other members' posts in the commons [conversationId] name and this device lacks, inside the
         * room's pinned window (spec §7.4). A received post is never held, so they come from its rows rather than from
         * a frame; one of [ours] (a hash both sides posted) stays ours, to push. [skip] (settled, quarantined) is left
         * out in the read itself, so its limit counts only what this round could act on.
         */
        private suspend fun commonsTheirs(
            scope: Scope,
            conversationId: String,
            ours: List<ScopeAttachments.Ref>,
            skip: Set<String>,
        ): List<ScopeAttachments.Ref> {
            val store = commons ?: return emptyList()
            val own = ours.mapTo(HashSet()) { it.aHash }
            return store
                .attachmentRefs(
                    conversationId,
                    selfId(),
                    since = clock() - boundsFor(scope).ttlMs,
                    skip = skip + own,
                    limit = ATTACHMENT_SCAN_PER_ROUND,
                ).filterNot { it.aHash in own }
        }

        /**
         * Whether a round can pass [ref] by without a round trip — decided *before* the `ahave`, which is what
         * makes both gates free: no round trip at all this round, not merely no chunks, and none of the
         * round-trip budget, so neither can starve an attachment that does need one. Null when it needs one.
         *
         * - **Refused** (#109): a photo our own screening refused and dropped is not fetched back only to be
         *   dropped again (the radios skip it the same way). Settled like a fetched one, so it leaves the scan
         *   window until the timed recheck asks again.
         * - **Proved**: an attachment we hold whose DM recipient provably holds the bytes (C-9.5-6's exception): no
         *   `ahave`, no `aput`, settled for good — the peers parting changes nothing, so it goes ahead of the gates that
         *   might otherwise owe it a later look.
         * - **Held for proof**: ours, on a connection that may not take this phone's uploads, while its DM recipient is
         *   still answering the holder query ([holdsForProof]): deferred, owed a look, and not yet locked — locking it
         *   would wake the ticket refresher for an upload the proof may yet spare. The round's end lets it lock.
         * - **Locked** (C-7.6-24): ours to push, on a connection that may not take this phone's uploads ([uploadsOn]):
         *   no `ahave`, no `aput`, and no retry — settled until a files ticket is accepted here. Fetching is never
         *   locked, so an attachment we lack goes on to the round trip as before.
         * - **Too large** (C-7.6-32): ours to push, over the chunks this connection's files ticket admits ([tooLargeFor]):
         *   no `ahave`, no `aput`, settled until a files ticket is accepted here. It locks nothing else.
         * - **Deferred**: the radios are still carrying an attachment we hold, so its push waits (§9.5).
         * - **Landed**: someone else's commons attachment ([theirs]) that arrived since the room's rows were read — never
         *   ours to push (C-7.4-6), and nothing left to fetch.
         */
        @Suppress("LongParameterList") // the gates' inputs: the attachment, whose it is, and where it would go
        private suspend fun passLocally(
            conn: SpoolConnection,
            scope: Scope,
            ref: ScopeAttachments.Ref,
            mine: Boolean,
            blobStore: ScopeBlobs,
            theirs: Boolean = false,
        ): LocalPass? =
            when {
                mine && theirs -> {
                    LocalPass.LANDED
                }

                !mine && blobStore.refused(ref.aHash) -> {
                    metrics.onSpoolAttachmentRefused()
                    settleAttachment(scope, ref.aHash)
                    LocalPass.REFUSED
                }

                mine && provedAttachment(scope, ref) -> {
                    metrics.onSpoolAttachmentProved()
                    settleAttachment(scope, ref.aHash)
                    LocalPass.PROVED
                }

                mine -> {
                    heldOrLocked(conn, scope, ref) ?: sizedOrDeferred(conn, scope, ref, blobStore)
                }

                else -> {
                    null
                }
            }

        /**
         * [passLocally]'s gates for a connection that takes none of this phone's uploads: held for its proof round, else
         * locked. Null when uploads are open, or opened since the caller looked, so the open connection's gates run.
         */
        private suspend fun heldOrLocked(
            conn: SpoolConnection,
            scope: Scope,
            ref: ScopeAttachments.Ref,
        ): LocalPass? =
            when {
                uploadsOn(conn) == SpoolUploads.Open -> {
                    null
                }

                holdsForProof(scope, ref) -> {
                    metrics.onSpoolAttachmentDeferred()
                    LocalPass.DEFERRED
                }

                settleLocked(conn, scope, ref.aHash) -> {
                    LocalPass.LOCKED
                }

                else -> {
                    null
                }
            }

        /** [passLocally]'s gates for an upload [conn] would take: too large for its ticket, or deferred (§9.5). */
        private suspend fun sizedOrDeferred(
            conn: SpoolConnection,
            scope: Scope,
            ref: ScopeAttachments.Ref,
            blobStore: ScopeBlobs,
        ): LocalPass? =
            when {
                tooLargeFor(conn, ref.aHash, blobStore) -> {
                    settleTooLarge(conn, scope, ref.aHash)
                    LocalPass.TOO_LARGE
                }

                deferAttachment(scope, ref) -> {
                    metrics.onSpoolAttachmentDeferred()
                    LocalPass.DEFERRED
                }

                else -> {
                    null
                }
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

        /**
         * Settles [aHash] after its round trip, or says it is still owed: [done], or ours and refused by the files gate
         * on the way up — settled as locked until a files ticket reopens uploads here.
         */
        private fun settledAfter(
            conn: SpoolConnection,
            scope: Scope,
            aHash: String,
            mine: Boolean,
            done: Boolean,
        ): Boolean =
            when {
                done -> {
                    settleAttachment(scope, aHash)
                    true
                }

                mine && uploadsOn(conn) != SpoolUploads.Open && settleLocked(conn, scope, aHash) -> {
                    true
                }

                else -> {
                    false
                }
            }

        /**
         * Settles [aHash] as an upload [conn] would not take ([LocalPass.LOCKED]), for [reopenUploads] to undo; false,
         * settling nothing, when uploads opened since the caller looked. A ticket's answer lands on the reader while a
         * round runs: [reopenUploads] moves the state before it takes this lock, so the re-read here either sees it open
         * or settles first and is un-settled by it — never a locked entry left behind after the reopen ran.
         */
        private fun settleLocked(
            conn: SpoolConnection,
            scope: Scope,
            aHash: String,
        ): Boolean {
            val uploads =
                synchronized(settledAttachments) {
                    val uploads = uploadsOn(conn)
                    if (uploads == SpoolUploads.Open) return false
                    settleAttachment(scope, aHash)
                    val set = lockedUploads.getOrPut(scope.idHex) { LinkedHashSet() }
                    set += aHash
                    if (set.size > BLOB_SET_MAX) set.remove(set.first())
                    uploads
                }
            metrics.onSpoolAttachmentLocked()
            // An upload waits for a files ticket this phone may be able to fetch (C-7.6-27): no ticket, or one that has
            // lapsed. `pending` is one on its way, `flags` one no fetch would change, and a spent day stays spent.
            val reason = (uploads as? SpoolUploads.Locked)?.reason
            if (reason == SpoolUploads.MISSING || reason == SpoolUploads.EXPIRED) onUploadWanted(url)
            return true
        }

        /**
         * Whether [aHash], ours, is too large to go up on [conn] (C-7.6-32): the spool refused it `size` here, or its
         * `total = ⌈|A| / aChunkBytes⌉` is over [SpoolConnection.uploadLimitChunks]. False with no limit, and for a blob
         * no longer held, which the push itself settles.
         */
        private suspend fun tooLargeFor(
            conn: SpoolConnection,
            aHash: String,
            blobStore: ScopeBlobs,
        ): Boolean {
            if (synchronized(settledAttachments) { aHash in sizeRefused }) return true
            val limit = conn.uploadLimitChunks() ?: return false
            val size = blobStore.size(aHash) ?: return false
            return ScopeAttachments.chunkCount(size) > limit
        }

        /** Settles [aHash] as too large for [conn] ([LocalPass.TOO_LARGE]), for [reopenUploads] to undo. */
        private fun settleTooLarge(
            conn: SpoolConnection,
            scope: Scope,
            aHash: String,
        ) {
            synchronized(settledAttachments) {
                settleAttachment(scope, aHash)
                val set = tooLargeUploads.getOrPut(scope.idHex) { LinkedHashSet() }
                set += aHash
                if (set.size > BLOB_SET_MAX) set.remove(set.first())
            }
            log("${SpoolUrl.host(url)}: an attachment is over this ticket's limit (${conn.uploadLimitChunks()} chunks); not uploaded")
        }

        private fun scheduleAttachmentRetry(scope: Scope) {
            val host = this@ScopeSync.session ?: return
            attachmentRetries.compute(scope.idHex) { _, existing ->
                if (existing?.isActive == true) {
                    existing
                } else {
                    host.launch {
                        delay(attachmentRetryMs)
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
            // C-7.6-32, again here: a ticket accepted since [passLocally] looked may carry an `am` this is over.
            if (conn.uploadLimitChunks()?.let { total > it } == true) {
                settleTooLarge(conn, scope, ref.aHash)
                return true
            }
            val stamp = stampFor(scope, conn.powBits)
            val missing = (0 until total).filter { presence.total == 0 || !ScopeAttachments.bitSet(presence.bits, it) }
            for (index in missing) {
                val sealed =
                    ScopeCrypto.sealChunk(scope.keys, scope.id, aHashBytes, index, total, ScopeAttachments.sliceAt(bytes, index))
                val put = putChunk(conn, scope, aid, index, total, sealed, stamp)
                if (put == ChunkPut.STOP) return false
                // The spool's own `size` (S-7.6-31): too large for this relay, whatever we computed. Settled, never a
                // lock, and nothing marked spent (C-7.6-32).
                if (put == ChunkPut.TOO_LARGE) {
                    synchronized(settledAttachments) {
                        sizeRefused += ref.aHash
                        if (sizeRefused.size > BLOB_SET_MAX) sizeRefused.remove(sizeRefused.first())
                    }
                    settleTooLarge(conn, scope, ref.aHash)
                    return true
                }
            }
            return true
        }

        /** A retiring scope is drained, a dead attachment is gone, and §9.2 bars an aged-out frame's bytes. */
        private fun worthPushing(
            scope: Scope,
            ref: ScopeAttachments.Ref,
            presence: SpoolReply.Presence,
        ): Boolean = !scope.retiring && !presence.dead && ref.sentAt + boundsFor(scope).ttlMs > clock()

        /**
         * Stores one sealed chunk: [ChunkPut.NEXT] when the next one is worth trying, [ChunkPut.STOP] when the rest of this
         * attachment is not, or [ChunkPut.TOO_LARGE] when the spool refused it `size` (S-7.6-31).
         */
        @Suppress("LongParameterList") // the aput record's own field list
        private suspend fun putChunk(
            conn: SpoolConnection,
            scope: Scope,
            aid: ByteArray,
            index: Int,
            total: Int,
            sealed: ByteArray,
            stamp: PowStamp?,
        ): ChunkPut {
            val reply = conn.aput(scope.id, aid, index, total, ScopeCrypto.blobId(sealed), sealed, stamp)
            if (reply is SpoolReply.Ok) {
                metrics.onSpoolAttachmentPushed()
                return ChunkPut.NEXT
            }
            if (reply !is SpoolReply.Failed) return ChunkPut.STOP
            // The files gate (S-7.6-22), ahead of [survivable], which frame PUSH shares: no strike, no error to show —
            // the connection's upload state says it — and no further chunk on this connection until it reopens. `size`
            // is the one word that is about this attachment alone (C-7.6-32).
            if (reply.code == SpoolErrCode.TICKET) {
                if (reply.msg == SpoolUploads.SIZE) return ChunkPut.TOO_LARGE
                uploadRefused(conn, reply.msg)
                return ChunkPut.STOP
            }
            if (stampDemand(scope.idHex, reply, stamp)) return ChunkPut.STOP
            refused(reply.code)
            return if (survivable(reply)) ChunkPut.NEXT else ChunkPut.STOP
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
            if (answeredAsNoCommons(scope, digest)) {
                markStale(scope)
                return
            }
            if (scope.commonsId != null) clearStale(scope)
            // A digest is the spool holding the scope, so it asks no stamp for it again until it sheds it.
            stampDemanded.remove(scopeHex)
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

        /**
         * A cast another member sent (docs/VOICE_CALLS.md §2.7): opened, held to the cast set, authenticated by
         * [canCarry] exactly as a pulled blob is (pinned key, signature), then handed to [deliver] as a frame for this
         * phone alone — `ttl = 0`, so no neighbour is flooded with it. That is the whole path: a cast is never claimed,
         * accounted, quarantined, folded or counted as presence, and a failure is dropped and forgotten. It is never
         * listed, so nothing can drive a re-pull, and a spool that never advertised casts has its casts ignored.
         */
        private suspend fun handleCast(cast: SpoolCast) {
            val conn = connection ?: return
            val maxBytes = conn.cast?.maxBytes ?: return
            if (cast.data.size > maxBytes) return
            val scope = mine(scopes, conn).firstOrNull { it.idHex == hex(cast.scope) } ?: return
            val opened = ScopeFrames.openCast(scope, selfId(), cast.data) ?: return
            if (!canCarry(opened.wire, opened.env)) return
            deliver(opened.wire, opened.env, SPOOL_SOURCE_PREFIX + url)
        }

        /**
         * A sig another member sent (§7.8): [handleCast]'s path under the signal set (C-7.8-13, C-7.8-14). Opened, held to
         * the set, authenticated by [canCarry], then handed to [deliver] as a frame for this phone alone (`ttl = 0`). It is
         * never claimed, accounted, quarantined, folded or counted as presence, and one that fails is dropped and
         * forgotten; the router marks nothing seen for a frame it then refuses. A spool that never advertised sigs has its
         * sigs ignored.
         */
        private suspend fun handleSig(sig: SpoolSig) {
            val conn = connection ?: return
            val maxBytes = conn.sig?.maxBytes ?: return
            if (sig.data.size > maxBytes) return
            val scope = mine(scopes, conn).firstOrNull { it.idHex == hex(sig.scope) } ?: return
            val opened = ScopeFrames.openSig(scope, selfId(), sig.data) ?: return
            if (!canCarry(opened.wire, opened.env)) return
            deliver(opened.wire, opened.env, SPOOL_SOURCE_PREFIX + url)
        }

        private fun handleScopeError(
            scopeHex: String?,
            code: String,
            retryMs: Long?,
        ) {
            // A creation gate is never asked for a commons (S-7.4-3): one asked for ours means the room is gone.
            val commons = scopeHex?.let { hex -> scopes.firstOrNull { it.idHex == hex && it.commonsId != null } }
            if (commons != null && (code == SpoolErrCode.POW || code == SpoolErrCode.QUOTA)) {
                markStale(commons)
                return
            }
            if (scopeHex != null && code == SpoolErrCode.POW && demanded(scopeHex, stamped = scopeHex in stampedSubs)) return
            refused(code)
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
            if (sender == selfId()) {
                // Our own frame pulled back: a reaction our newer one superseded in the outbox, which the spool keeps
                // to its TTL, or anything of ours after a leave and rejoin. It is accounted, never applied (our rows
                // hold its effect or a newer one; a rejoin does not bring our own posts back) — and never parked: we
                // pin no key for ourselves, so it would wait for a profile that never pins and then be quarantined,
                // and our own room would never read converged.
                if (account(scope.idHex, idHex, blobId)) metrics.onSpoolAccounted()
                return Accept.DELIVERED
            }
            if (!hasKey(sender)) {
                if (!countPark || park(scope.idHex, idHex)) return Accept.PARKED
                return refuse(scope, idHex, source)
            }
            val post = WireCodec.decodePayload<CommonsPost>(opened.env.payload)
            if (post == null || !canCarry(opened.wire, opened.env)) return refuse(scope, idHex, source)
            unpark(scope.idHex, idHex)
            metrics.onSpoolPulled()
            deliverCommons(opened.env, post, conversationId, SPOOL_SOURCE_PREFIX + url)
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

        /**
         * Whether a refused PUSH or APUT was the spool asking for a stamp the request did not carry — then
         * the scope is marked [stampDemanded] and the next round sends one, with no error raised. A refusal
         * of a stamp that went out is stale or short (the spool's day or difficulty moved): the stamp is
         * dropped, so the next request mines afresh, and the refusal stands as one.
         */
        private fun stampDemand(
            scopeHex: String,
            reply: SpoolReply.Failed,
            sent: PowStamp?,
        ): Boolean {
            if (reply.code != SpoolErrCode.POW) return false
            if (sent != null) {
                stamps.remove(scopeHex)
                return false
            }
            if (stampDemanded.add(scopeHex)) wake()
            return true
        }

        /**
         * A SUB's `err pow`: true when it was the spool's first demand for a stamp the SUB did not carry, so
         * the next round re-SUBs with one, unparked. A refused stamp, or a demand we already could not meet
         * (mining ran out of budget), falls through to the ordinary refusal — parked, the stamp dropped.
         */
        private fun demanded(
            scopeHex: String,
            stamped: Boolean,
        ): Boolean {
            if (stamped || !stampDemanded.add(scopeHex)) return false
            wake()
            return true
        }

        /** A cached hashcash stamp for [scope], mined only once the spool demanded one ([stampDemanded], §8). */
        private fun stampFor(
            scope: Scope,
            bits: Int,
        ): PowStamp? {
            // A commons exists from the spool's boot, so it is never an unknown scope and never gated (§7.4):
            // mining for it would spend a phone's battery on a stamp the spool ignores.
            if (bits <= 0 || scope.commonsId != null || scope.idHex !in stampDemanded) return null
            val now = clock()
            stamps[scope.idHex]?.let { if (SpoolPow.reusable(it.d, now)) return it }
            val day = SpoolPow.utcDay(now)
            val started = System.nanoTime()
            val n = SpoolPow.stamp(scope.id, day, bits, POW_BUDGET)
            val tookMs = (System.nanoTime() - started) / NS_PER_MS
            log("${SpoolUrl.host(url)}: mined a $bits-bit stamp in $tookMs ms${if (n == null) ", none found" else ""}")
            if (n == null) return null
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
         * the Internet plane, not a radio. The delivery tick reads this to say *how* a message got there,
         * and `MeshRouter`'s overhear suppression reads it to leave a spool copy out of the count — a relay
         * holding the frame says nothing about which radio neighbour heard it (ADR 2026-09.dcah, #84).
         * Nothing about carry or convergence may depend on it.
         */
        fun isSpoolSource(fromNodeId: String): Boolean = fromNodeId.startsWith(SPOOL_SOURCE_PREFIX)

        /**
         * The scope-table poll. Since ADR 2026-09.dcah every input with an event re-derives the table through
         * [onScopeTableChanged]; this is the net under the transitions only the calendar makes — a retiring DM
         * or group root's drain window closing, a pair scope's grace lapsing, a swept root. It was 15 s when
         * it was the only way a new scope was found.
         */
        private const val RECONCILE_INTERVAL_MS = 60_000L
        internal const val TICK_INTERVAL_MS = 60_000L

        /** At most this many spools are asked for a TURN credential per call (C-7.6-12: at most two TURN servers). */
        const val MAX_TURN_SPOOLS = 2

        /** How long a call waits on one spool's `turncred` before dialling without it. */
        const val TURN_ASK_MS = 3_000L
        private const val HANDSHAKE_TIMEOUT_MS = 20_000L
        private const val RECONNECT_JITTER_MS = 750L

        /** How often a worker with no route looks again, between the route changes that wake it at once. */
        private const val OFFLINE_RECHECK_MS = 60_000L

        /** The re-look for an attachment a round could not finish — the cadence the old reconcile gave every scope. */
        internal const val ATTACHMENT_RETRY_MS = 15_000L

        /** How long a settled attachment goes unasked-about before a timed round checks the spool still has it. */
        private const val ATTACHMENT_RECHECK_MS = 10 * 60_000L

        /**
         * How long after its post someone else's commons attachment the spool lacks keeps the short retry before it is
         * left to [ATTACHMENT_RECHECK_MS]: an author pushes its post and its chunks in the same round, so a reader
         * often asks first, and two minutes covers a slow upload without asking for a lost one all day.
         */
        private const val ABSENT_GRACE_MS = 2 * 60_000L

        /** How far short of the HELLO's TTL a commons SUB declares its own: the probe [answeredAsNoCommons] reads. */
        private const val PROBE_SHORTFALL_MS = 1L
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

        private const val MS_PER_S = 1_000L

        private const val NS_PER_MS = 1_000_000L

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
