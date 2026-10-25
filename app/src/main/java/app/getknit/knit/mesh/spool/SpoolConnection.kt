package app.getknit.knit.mesh.spool

import app.getknit.knit.call.StunServers
import app.getknit.knit.mesh.crypto.scope.ScopeCrypto
import app.getknit.knit.mesh.crypto.scope.SpoolPow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The byte pipe under a [SpoolConnection] — one WebSocket, already open. Kept as a seam so the whole
 * record state machine stays pure and JVM-testable, and so the only file in the app that imports
 * `okhttp3` is the adapter that implements this (see `rules/mesh.md`).
 */
interface SpoolLink {
    /** Sends one binary WebSocket message; false when the socket is already gone. */
    fun send(bytes: ByteArray): Boolean

    /** Bytes handed to [send] and not yet written to the socket — what a cast checks so it never waits behind a blob. */
    fun queued(): Long = 0L

    fun close(
        code: Int,
        reason: String,
    )
}

/** A terminal response to a client-stamped `q`, or the connection dying under it. */
sealed interface SpoolReply {
    /** The spool's LIST answer: its live ids plus the tombstones we must not re-push. */
    class Listing(
        val blobIds: List<ByteArray>,
        val tombstones: List<ByteArray>,
    ) : SpoolReply

    /** `ok` — for a PULL, [missing] are the requested ids the spool no longer holds. */
    class Ok(
        val missing: List<ByteArray>,
    ) : SpoolReply

    /** `ahas` — one attachment's presence at this spool (§7.3). */
    class Presence(
        val total: Int,
        val bits: ByteArray,
        val dead: Boolean,
    ) : SpoolReply

    /**
     * `turncred` — one TURN credential (experimental, docs/VOICE_CALLS.md §2). A plain class with no [toString] of
     * its own fields, so [pass] can never reach a log line.
     */
    class Turn(
        val urls: List<String>,
        val user: String,
        val pass: String,
        val exp: Long,
    ) : SpoolReply {
        override fun toString(): String = "Turn(urls=$urls, exp=$exp)"
    }

    /**
     * `err` — [code] is from the append-only registry; unknown codes are terminal-generic. [msg] is the spool's one
     * word beside it where the code defines one (`ticket`'s reason, docs/VOICE_CALLS.md §2.6), bounded like [code].
     */
    class Failed(
        val code: String,
        val retryMs: Long?,
        val msg: String? = null,
    ) : SpoolReply

    /** The connection closed before the response arrived. */
    data object Closed : SpoolReply
}

/**
 * Where this connection stands with an entitlement ticket (experimental, docs/VOICE_CALLS.md §2.6): what the debug
 * bridge shows and what a TURN ask is gated on. Per connection — a spool binds an accepted ticket to the connection
 * that presented it (S-7.6-15), so a reconnect starts at [None].
 */
sealed interface SpoolTicketState {
    /** Nothing presented here, or the accepted ticket has lapsed at its `exp` on our clock. */
    data object None : SpoolTicketState

    /** An `ent` is on its way and nothing accepted is live. */
    data object Pending : SpoolTicketState

    /**
     * The spool accepted a ticket good until [exp] (Unix seconds) with flags [fl], and, when it sets them, its own
     * upload budget [ab] and per-attachment limit [am] in bytes (S-7.6-22, S-7.6-31).
     */
    data class Accepted(
        val exp: Long,
        val fl: Long,
        val ab: Long? = null,
        val am: Long? = null,
    ) : SpoolTicketState

    /** The spool refused the last ticket presented here with [reason], and nothing accepted is live. */
    data class Refused(
        val reason: String,
    ) : SpoolTicketState
}

/**
 * Whether this phone may upload attachments (`aput`) on a connection (C-7.6-24, knit-spool ADR 2026-10.h6pt): what the
 * push half of the attachment plane, the chat's "Nearby only" marker and the debug bridge all read. Only bit 1 of the
 * HELLO's `entGates` closes uploads; without it every connection is [Open], ticket or not. [word] is the bridge's
 * spelling: `open`, `locked:<reason>`, `spent`.
 */
sealed interface SpoolUploads {
    val word: String

    /** The files gate is off here, or this connection holds a live accepted ticket that grants files. */
    data object Open : SpoolUploads {
        override val word: String get() = "open"
    }

    /**
     * Gated, and no ticket here grants files: [MISSING] (none accepted), [EXPIRED] (the accepted one lapsed on our
     * clock), [FLAGS] (the accepted one lacks bit 1), [PENDING] (a files ticket is on its way, unanswered) — or the
     * word of the `err ticket` an `aput` drew here, which holds until a files ticket is next accepted.
     */
    data class Locked(
        val reason: String,
    ) : SpoolUploads {
        override val word: String get() = "locked:$reason"
    }

    /** The ticket's pseudonym has spent its day's uploads at this relay (`err ticket` `budget`). */
    data object Spent : SpoolUploads {
        override val word: String get() = "spent"
    }

    companion object {
        const val MISSING = "missing"
        const val EXPIRED = "expired"
        const val FLAGS = "flags"
        const val PENDING = "pending"

        /** The `aput` refusal that spends a pseudonym's day here, rather than locking the connection. */
        const val BUDGET = "budget"

        /**
         * The `aput` refusal that says only that one attachment is over the ticket's `am` (S-7.6-31): it locks nothing
         * and spends nothing (C-7.6-32), so it never becomes an upload state.
         */
        const val SIZE = "size"
    }
}

/**
 * What a PULL actually yielded: the blobs that arrived, the ids the spool no longer holds, and the ids
 * it answered with something unusable.
 *
 * [oversize] exists so a refusal at the record layer is still *accounted*. A blob we asked for and got a
 * junk answer for is a blob we can never hold, so if it were merely dropped it would stay in the spool's
 * digest and out of ours — permanent divergence and a re-pull every heal round, the exact failure §9.3's
 * invalid set is there to prevent. Ids we never asked for are not reported here: they were never pulled,
 * so quarantining them would hand the spool a way to evict genuine entries from a bounded set.
 */
class PullOutcome(
    val blobs: List<SpoolBlob>,
    val missing: List<ByteArray>,
    val oversize: List<ByteArray>,
)

/**
 * What an AGET yielded. [rejected] means at least one answer was outside the window we asked for or over
 * the structural chunk size — the attachment's `aid` must be quarantined per §9.5 (C-9.5-4), exactly as
 * it is when a chunk fails its AEAD.
 */
class AgetOutcome(
    val chunks: List<SpoolAchunk>,
    val rejected: Boolean,
)

/**
 * The client half of `docs/SPOOL_PROTOCOL.md` §7 over one connection: hello negotiation, the monotonic
 * per-connection `q`, request/response correlation, the per-connection subscription set, and the
 * routing of server-initiated records. Pure and transport-agnostic — it only ever touches a [SpoolLink].
 *
 * Three v1 behaviors are load-bearing and easy to get wrong, so they are handled here rather than in
 * callers: **the spool speaks first** (its unprompted hello opens the conversation and we answer with
 * nothing but the chosen version); **sub-before-use is per connection**, so a reconnect must re-`sub`
 * every scope even though the spool still holds them; and **`blob` records carry no `q`**, so they are
 * attributed to the single in-flight PULL for their scope.
 *
 * Records are consumed one at a time by [onMessage] from a single reader coroutine; requests may be
 * issued concurrently, so the correlation table and subscription set are concurrent structures.
 */
class SpoolConnection(
    val url: String,
    private val link: SpoolLink,
    private val onDigest: suspend (SpoolDigest) -> Unit,
    private val onEvent: suspend (SpoolEvent) -> Unit,
    // Errors that don't correlate to an outstanding `q` — a scoped SUB refusal (pow/quota) or a
    // connection-scoped fault. The scope is hex, or null when the error is connection-wide.
    private val onScopeError: suspend (scopeHex: String?, code: String, retryMs: Long?) -> Unit = { _, _, _ -> },
    // The spool answered a ticket [present]ed here: `ok` ([SpoolReply.Ok]) or `err` ([SpoolReply.Failed]). Runs on the
    // reader after this connection's own state has moved, so it must not wait on anything the reader serves.
    private val onTicketAnswer: suspend (HeldTicket, SpoolReply) -> Unit = { _, _ -> },
    // What a ticket's `exp` and an accepted ticket's lapse are read against (epoch ms).
    private val clock: () -> Long = { System.currentTimeMillis() },
    // A cast another member sent (docs/VOICE_CALLS.md §2.7). Never answered, never a request, no `q`.
    private val onCast: suspend (SpoolCast) -> Unit = {},
) {
    /**
     * One outstanding `q`. For the two requests answered by un-`q`'d records (`pull` → `blob`*,
     * `aget` → `achunk`*, spec B-7.1-8) it also carries **what the request named**, and that is the only
     * bound on this plane the client actually controls: a spool cannot inflate an id list we composed
     * ourselves. One slot per named id, consumed on arrival, is simultaneously the unsolicited-record
     * check, the duplicate filter and the cap on list length.
     *
     * Concurrency: the mutable state here is written only on the reader coroutine (`onMessage`) and
     * seeded on the requesting coroutine *before* the entry is published into [pending]. The terminal
     * handlers remove the entry from [pending] **before** completing [reply], so by the time the
     * requester resumes, no handler can still reach it. Reading these lists on the timeout path would
     * break that and reintroduce a data race — don't.
     */
    private class Pending(
        val scopeHex: String?,
        // The aid this AHAVE named, so its `ahas` is checked against the question and not only the `q`.
        // Separate from `chunksFor` on purpose: naming an aid here must not make the entry a chunk collector.
        val aidHex: String? = null,
        // A TURNREQ, which names no scope (S-7.6-4): only a `turncred` may complete it, and a `turncred` only it.
        val turn: Boolean = false,
        // An ENT, and the ticket it presented: its `ok` binds those claims to this connection, its `err` refuses them.
        val ticket: HeldTicket? = null,
        // The ids this PULL named, in hex. Drained by `claimBlob`, so its initial size is the hard cap
        // on [blobs] — and an entry that named none (LIST, PUSH, AHAVE, APUT) collects nothing at all,
        // which is what the old `collectsBlobs` flag said less directly.
        private val wantedBlobs: MutableSet<String> = mutableSetOf(),
        // The aid this AGET named: routing, not a bound — two attachments in one scope can legitimately
        // be in flight at once.
        private val chunksFor: String? = null,
        // The index window this AGET named, `from until from + n`. A range rather than a set, so nothing
        // is allocated on the strength of the caller's `n`.
        private val chunkWindow: IntRange = IntRange.EMPTY,
        private val filledChunks: MutableSet<Int> = mutableSetOf(),
        val reply: CompletableDeferred<SpoolReply> = CompletableDeferred(),
        val blobs: MutableList<SpoolBlob> = mutableListOf(),
        val chunks: MutableList<SpoolAchunk> = mutableListOf(),
        // Ids this PULL named that came back unusable, for §9.3 to account. Bounded by `wantedBlobs`.
        val oversize: MutableList<ByteArray> = mutableListOf(),
        var rejectedChunk: Boolean = false,
    ) {
        // The ids as named, kept whole: `wantedBlobs` is drained by claiming, so it cannot answer
        // "did this request mention that id" once a blob for it has arrived.
        private val namedBlobs: Set<String> = wantedBlobs.toSet()

        /** True at most once per id this PULL named — an unsolicited id, or a repeat, is false. */
        fun claimBlob(idHex: String): Boolean = wantedBlobs.remove(idHex)

        /** Whether this request named [idHex] at all, however its blob then fared. */
        fun wasRequested(idHex: String): Boolean = idHex in namedBlobs

        /** Whether this entry is the collector for [aidHex] at all, however the chunk then fares. */
        fun collectsChunksFor(aidHex: String): Boolean = aidHex == chunksFor

        /** Whether [idx] is one this AGET actually named. */
        fun inWindow(idx: Int): Boolean = idx in chunkWindow

        /** True at most once per index, so a repeat is dropped rather than buffered twice. */
        fun claimChunk(idx: Int): Boolean = filledChunks.add(idx)
    }

    private val nextQ = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, Pending>()

    // Consecutive requests that timed out unanswered. Any terminal reply resets it; at
    // [MAX_SILENT_REQUESTS] the connection is dropped as [UNRESPONSIVE] rather than left to burn
    // 30 s per request, per scope, per round on a spool that completed the handshake and then went quiet.
    private val silent = AtomicInteger(0)

    // Scope hex -> the bounds *we* declared for it at SUB. Kept rather than a bare id set because those
    // bounds are the one `maxBlob` on this connection the spool did not choose (see [inboundCap]).
    private val subscriptions = ConcurrentHashMap<String, ScopeBounds>()
    private val handshake = CompletableDeferred<Boolean>()

    /** The spool's advertised caps, available once [awaitReady] returns true. */
    @Volatile
    var limits: SpoolLimits? = null
        private set

    /** The spool's PoW difficulty; 0 disables it. Read after [awaitReady]. */
    @Volatile
    var powBits: Int = 0
        private set

    /**
     * The commons rooms this spool runs (§7.4), or null when it advertises none — read after [awaitReady]. Each
     * room's bounds are what the spool will pin regardless of what a SUB declares, clamped to our own ceilings like
     * [limits]; a spool cannot talk us into a bigger inbound record by advertising one on a room.
     */
    @Volatile
    var adverts: CommonsAdverts? = null
        private set

    /**
     * Whether this spool issues TURN credentials (HELLO `turn`; experimental, docs/VOICE_CALLS.md §2.1) — read after
     * [awaitReady]. [turnCredential] is refused without it: a spool that never advertised it skips the record.
     */
    @Volatile
    var turn: Boolean = false
        private set

    /**
     * Whether this spool takes entitlement tickets (HELLO `ent`; experimental, docs/VOICE_CALLS.md §2.6) — read after
     * [awaitReady]. [present] sends nothing without it. Which verbs need one is [gates], not this.
     */
    @Volatile
    var ent: Boolean = false
        private set

    /**
     * The `fl` bits this connection needs a ticket for (`SpoolHello.ticketGates`, S-7.6-13), from its own HELLO and
     * fixed for its life: bit 0 gates `turnreq`, bit 1 `aput`, bit 3 casts (C-7.6-30). 0 before [awaitReady].
     */
    @Volatile
    var gates: Long = 0L
        private set

    /**
     * The credential-free STUN URIs this spool advertised (HELLO `stun`, C-7.6-20), held to [StunServers]' rule and
     * cap — what a call gathers from whether or not any spool issues it TURN. Empty before [awaitReady] and when none.
     */
    @Volatile
    var stun: List<String> = emptyList()
        private set

    /**
     * This spool's cast bounds (HELLO `cast`; experimental, docs/VOICE_CALLS.md §2.7), clamped like [limits], or null
     * when it fans out none — read after [awaitReady]. [castOut] sends nothing without it.
     */
    @Volatile
    var cast: SpoolCastInfo? = null
        private set

    // The advertised cast budget, kept on our side because the spool's refusals are silent. Built at the hello.
    @Volatile
    private var castBudget: CastBudget? = null

    // The ticket the spool last accepted here, until its `exp` lapses on our clock or a later one replaces it, and its
    // pseudonym — what an upload here is charged to (S-7.6-22).
    @Volatile
    private var accepted: SpoolTicketState.Accepted? = null

    @Volatile
    private var acceptedPseudonym: TicketPseudonym? = null

    // The most recent ENT still unanswered — what a TURN ask waits on — and the word the last refusal carried.
    @Volatile
    private var presenting: Pending? = null

    @Volatile
    private var refusal: String? = null

    @Volatile
    private var negotiated = false

    @Volatile
    private var closed = false

    /**
     * Why *we* dropped this connection, or null while it is open or the spool ended it: [UNRESPONSIVE],
     * or a [SpoolErrCode] the caller aborted on. The worker reads it after the session to (a) report it
     * — an OkHttp socket echoes our own close back as `close 1000`, which would otherwise be the only
     * story — and (b) back off, since a session that we had to abort is not one worth dialling again in
     * a second.
     */
    @Volatile
    var fault: String? = null
        private set

    /** Whether the socket is still ours to write to — false once either side has closed it. */
    val isOpen: Boolean get() = !closed

    @Volatile
    private var ready = false

    /**
     * Whether the hello exchange completed on a socket that is still open — what "connected" means to
     * everything that reads a spool's status. A socket exists from the dial, but a route that swallows
     * the upgrade (a captive Wi-Fi the platform still calls validated) holds one for the whole connect
     * timeout without a byte coming back; only the spool's hello proves there is a spool at the end of it.
     */
    val isReady: Boolean get() = ready && !closed

    /**
     * Whether this spool has answered a `q`-correlated request on this connection — a `digest` does not
     * count, since a spool that answers the SUB and then nothing else is exactly the one [UNRESPONSIVE]
     * is for. What lets the worker retire a carried fault once the spool is demonstrably talking again.
     */
    @Volatile
    var answered: Boolean = false
        private set

    /** How many scopes are SUBbed on this connection — what a `maxScopes` cap is measured against. */
    val subscribedCount: Int get() = subscriptions.size

    /** Suspends until the hello exchange settles: true when the connection is usable. */
    suspend fun awaitReady(): Boolean = handshake.await()

    fun isSubscribed(scopeHex: String): Boolean = scopeHex in subscriptions.keys

    /**
     * Forgets every subscription not in [live], so a scope that left the table and comes back is SUBbed
     * again and answered with a fresh `digest`. The spool keeps its side of the subscription (there is no
     * `unsub` record) and a repeat SUB is an ordinary re-declaration (S-6.2-2); only this record moves.
     */
    fun retainSubscriptions(live: Set<String>) {
        subscriptions.keys.retainAll(live)
    }

    /**
     * Subscribes [subs] and declares their bounds. Deliberately **not** awaited: the spool answers with
     * one `digest` per scope (which carries no `q`) or a scoped `err`, so the digests arrive through
     * [onDigest] and a refusal through [onScopeError], which also un-marks the scope here.
     */
    fun sub(subs: List<ScopeSub>): Boolean {
        if (subs.isEmpty()) return true
        val q = nextQ.getAndIncrement()
        subs.forEach { subscriptions[hex(it.scope)] = it.bounds }
        val sent = send(SpoolCodec.encode(SpoolSub(t = SpoolRecordType.SUB, q = q, subs = subs)))
        if (!sent) subs.forEach { subscriptions.remove(hex(it.scope)) }
        return sent
    }

    /** Requests the id exchange behind a digest mismatch. Null when the connection died under it. */
    suspend fun list(scope: ByteArray): SpoolReply.Listing? {
        val reply =
            request(Pending(hex(scope))) { q -> SpoolCodec.encode(SpoolList(t = SpoolRecordType.LIST, q = q, scope = scope)) }
        return reply as? SpoolReply.Listing
    }

    /**
     * Pulls [blobIds] (the caller batches at the spool's `maxPull`; an overshoot is silently truncated
     * and the remainder must be re-pulled). Null when the pull failed or the connection died.
     */
    suspend fun pull(
        scope: ByteArray,
        blobIds: List<ByteArray>,
    ): PullOutcome? {
        val entry = Pending(hex(scope), wantedBlobs = blobIds.mapTo(mutableSetOf(), ::hex))
        val ok =
            request(entry) { q ->
                SpoolCodec.encode(SpoolPull(t = SpoolRecordType.PULL, q = q, scope = scope, blobIds = blobIds))
            } as? SpoolReply.Ok ?: return null
        return PullOutcome(blobs = entry.blobs.toList(), missing = ok.missing, oversize = entry.oversize.toList())
    }

    /** Stores one sealed blob. Returns the terminal reply so the caller can react to `tombstoned`/`rate`. */
    suspend fun push(
        scope: ByteArray,
        blobId: ByteArray,
        data: ByteArray,
        pow: PowStamp? = null,
    ): SpoolReply =
        request(Pending(hex(scope))) { q ->
            SpoolCodec.encode(
                SpoolPush(t = SpoolRecordType.PUSH, q = q, scope = scope, blobId = blobId, data = data, pow = pow),
            )
        }

    /**
     * Asks what this spool holds for one attachment (§7.3). Callers must gate on
     * [SpoolLimits.attachments] first: a spool without attachment support skips the record silently,
     * and this would then block until the request timeout.
     */
    suspend fun ahave(
        scope: ByteArray,
        aid: ByteArray,
    ): SpoolReply.Presence? {
        val reply =
            request(Pending(hex(scope), aidHex = hex(aid))) { q ->
                SpoolCodec.encode(SpoolAhave(t = SpoolRecordType.AHAVE, q = q, scope = scope, aid = aid))
            }
        return reply as? SpoolReply.Presence
    }

    /**
     * Presents [ticket] (experimental, docs/VOICE_CALLS.md §2.6, C-7.6-19): one `ent`, **not awaited** — the answer
     * moves [ticketState] and reaches `onTicketAnswer` on the reader, and a TURN ask waits for it on its own. False,
     * and nothing sent, when this spool never advertised `ent`, before the hello, once closed, or when [ticket]'s
     * `aud` is not this connection's host.
     *
     * That last check is a privacy rule, not an optimisation: the ticket carries the holder's daily pseudonym, and a
     * spool it was not issued for would learn it for nothing (ADR 2026-10.xkp4). It is made here, against the URL this
     * connection dialled, so no caller can get past it. A refused `ent` is never a strike and never aborts.
     */
    fun present(ticket: HeldTicket): Boolean {
        if (!ent || !isReady || ticket.claims.aud != SpoolUrl.hostname(url)) return false
        val entry = Pending(scopeHex = null, ticket = ticket)
        val q = nextQ.getAndIncrement()
        pending[q] = entry
        presenting = entry
        val sent = send(SpoolCodec.encode(SpoolEnt(t = SpoolRecordType.ENT, q = q, tk = ticket.tk, sig = ticket.sig)))
        if (!sent) {
            pending.remove(q, entry)
            if (presenting === entry) presenting = null
        }
        return sent
    }

    /** Where this connection stands with a ticket; an accepted one lapses at its `exp` on our clock. */
    fun ticketState(): SpoolTicketState {
        liveTicket()?.let { return it }
        if (presenting != null) return SpoolTicketState.Pending
        return refusal?.let { SpoolTicketState.Refused(it) } ?: SpoolTicketState.None
    }

    /**
     * Whether a TURN ask here rides a ticket: an `ent` spool where the live ticket, or the one on its way, grants TURN.
     * What C-7.6-21 asks on every call, subscribed or not; where bit 0 of [gates] is set, a `turnreq` without it is
     * certain to be refused (S-7.6-16). Where it is not, the ticket still waives the subscription gate (knit-spool ADR
     * 2026-10.h6pt), so it counts on any `ent` spool.
     */
    val ticketedTurn: Boolean
        get() = ent && (turnTicketLive() || presenting?.ticket?.grantsTurn == true)

    private fun liveTicket(): SpoolTicketState.Accepted? = accepted?.takeIf { clock() / MS_PER_S <= it.exp }

    private fun turnTicketLive(): Boolean = liveTicket()?.let { it.fl and SpoolTicket.FL_TURN != 0L } == true

    private fun castTicketLive(): Boolean = liveTicket()?.let { it.fl and SpoolTicket.FL_CAST != 0L } == true

    private fun filesTicketLive(): Boolean = liveTicket()?.let { it.fl and SpoolTicket.FL_FILES != 0L } == true

    /** Whether this connection's HELLO gates [flag] (C-7.6-30): a verb whose bit is not set needs no ticket here. */
    fun gated(flag: Long): Boolean = gates and flag != 0L

    /**
     * Whether this connection may take this phone's uploads, as far as the connection knows (C-7.6-24): [SpoolUploads.Open]
     * where bit 1 is not gated or the live accepted ticket grants files, else [SpoolUploads.Locked] — [SpoolUploads.PENDING]
     * while a files ticket is unanswered, because the spool judges an `aput` after the `ent` sent before it, and the
     * answer may be a refusal. A spent budget and a refused `aput` are `ScopeSync`'s to add: they outlive or narrow
     * what this connection holds.
     */
    fun uploads(): SpoolUploads {
        if (!gated(SpoolTicket.FL_FILES) || filesTicketLive()) return SpoolUploads.Open
        if (presenting?.ticket?.grantsFiles == true) return SpoolUploads.Locked(SpoolUploads.PENDING)
        val held = accepted
        val reason =
            when {
                held == null -> SpoolUploads.MISSING
                clock() / MS_PER_S > held.exp -> SpoolUploads.EXPIRED
                else -> SpoolUploads.FLAGS
            }
        return SpoolUploads.Locked(reason)
    }

    /**
     * The most chunks an attachment of this phone's may have to go up here (C-7.6-32): `⌊am / aChunkBytes⌋` of the live
     * accepted files ticket where `aput` is gated and that ticket sets `am`, else null — no limit beyond the spool's own.
     * Ungated, a spool ignores `am`, and so does this.
     */
    fun uploadLimitChunks(): Int? =
        liveTicket()?.takeIf { gated(SpoolTicket.FL_FILES) && it.fl and SpoolTicket.FL_FILES != 0L }?.am?.let(SpoolTicket::chunksOf)

    /** The pseudonym an upload here is charged to: the live accepted ticket's, while it grants files; else null. */
    fun uploadPseudonym(): TicketPseudonym? = acceptedPseudonym?.takeIf { filesTicketLive() }

    /**
     * Whether this phone's own ticket unlocks casts here: an `ent` spool where the live ticket, or the one on its way,
     * carries the cast flag. A pending one counts, because the spool handles this connection's records in order: an
     * `ent` sent before a cast is judged before it.
     */
    val ticketedCast: Boolean
        get() = ent && (castTicketLive() || presenting?.ticket?.grantsCast == true)

    /**
     * Whether a cast for [scopeHex] would leave here at all, short of the budget and the queue: casts advertised, the
     * hello done, the scope subscribed, and where bit 3 of [gates] is set a cast ticket held here or by the peer
     * ([peerTicketed], from its walkie signal). The spool makes the real ticket decision; this only spares it casts it would drop.
     */
    fun canCast(
        scopeHex: String,
        peerTicketed: Boolean,
    ): Boolean = cast != null && isReady && isSubscribed(scopeHex) && (!gated(SpoolTicket.FL_CAST) || ticketedCast || peerTicketed)

    /**
     * Sends one cast (experimental, docs/VOICE_CALLS.md §2.7): no `q`, no [pending] entry, no answer awaited, no strike.
     * False, and nothing sent, when [canCast] does not hold, when [data] is over the advertised `maxBytes`, when the
     * advertised rate is spent, or while more than [CAST_QUEUE_LIMIT] bytes are queued on the socket. A chunk that
     * would wait behind a blob is better lost than late.
     */
    fun castOut(
        scope: ByteArray,
        data: ByteArray,
        peerTicketed: Boolean,
    ): Boolean {
        val info = cast ?: return false
        if (!canCast(hex(scope), peerTicketed) || data.size > info.maxBytes) return false
        if (link.queued() > CAST_QUEUE_LIMIT || castBudget?.take() != true) return false
        return send(SpoolCodec.encode(SpoolCast(t = SpoolRecordType.CAST, scope = scope, data = data)))
    }

    /**
     * Asks for one TURN credential (experimental, docs/VOICE_CALLS.md §2.2): [SpoolReply.Turn], or [SpoolReply.Failed]
     * (`not_subscribed`, `rate` + `retryMs`, `internal`, `ticket`), or [SpoolReply.Closed] — including when this spool
     * never advertised `turn` (C-7.6-3) and when it stays silent past [timeoutMs]. A call is waiting on the answer, so
     * the wait is short and a silence is no strike against the connection: the sync plane's requests own that counter.
     *
     * Where bit 0 of [gates] is set (S-7.6-16) it first waits, inside the same [timeoutMs], for a ticket still on its
     * way, and asks nothing unless the live one grants TURN: a `turnreq` without one is certain to be refused, and
     * spends the spool's per-IP bucket that a ticketed ask from the same address may need (ADR 2026-10.xkp4). An `ent`
     * spool that leaves bit 0 out is asked like an unticketed one (C-7.6-30).
     */
    suspend fun turnCredential(timeoutMs: Long): SpoolReply {
        if (!turn) return SpoolReply.Closed
        val reply =
            withTimeoutOrNull(timeoutMs) {
                if (gated(SpoolTicket.FL_TURN)) {
                    // A live ticket that grants TURN is enough; only without one is a newer `ent` worth waiting for.
                    if (!turnTicketLive()) presenting?.reply?.await()
                    if (!turnTicketLive()) return@withTimeoutOrNull SpoolReply.Closed
                }
                request(Pending(scopeHex = null, turn = true), timeoutMs = timeoutMs, strike = false) { q ->
                    SpoolCodec.encode(SpoolTurnReq(t = SpoolRecordType.TURNREQ, q = q))
                }
            } ?: SpoolReply.Closed
        // The spool holds no live ticket for us, whatever our clock says: asking again before a new `ent` is accepted
        // would only be refused again.
        if (reply is SpoolReply.Failed && reply.code == SpoolErrCode.TICKET) {
            accepted = null
            acceptedPseudonym = null
        }
        // An `ok` completes whatever `q` it names, so anything but a credential is a failure here.
        return if (reply is SpoolReply.Turn || reply is SpoolReply.Failed) reply else SpoolReply.Closed
    }

    /**
     * Fetches up to [n] chunks from [from]; an overshoot of `maxAget` is truncated, never refused.
     *
     * The window kept for filtering is the range this request *named*, not the sparse set of indices the
     * caller still lacks: a conforming spool serves every index it holds in the range, including ones
     * already in hand, and `ScopeSync.absorb` already tolerates those duplicates.
     */
    suspend fun aget(
        scope: ByteArray,
        aid: ByteArray,
        from: Int,
        n: Int,
    ): AgetOutcome? {
        val entry = Pending(hex(scope), chunksFor = hex(aid), chunkWindow = from until from + n)
        val reply =
            request(entry) { q ->
                SpoolCodec.encode(SpoolAget(t = SpoolRecordType.AGET, q = q, scope = scope, aid = aid, from = from, n = n))
            }
        if (reply !is SpoolReply.Ok) return null
        return AgetOutcome(chunks = entry.chunks.toList(), rejected = entry.rejectedChunk)
    }

    /** Stores one sealed attachment chunk. The terminal reply carries `conflict`/`quota`/`tombstoned`. */
    @Suppress("LongParameterList") // the record's own field list; a parameter object would only relocate it
    suspend fun aput(
        scope: ByteArray,
        aid: ByteArray,
        idx: Int,
        total: Int,
        cid: ByteArray,
        data: ByteArray,
        pow: PowStamp? = null,
    ): SpoolReply =
        request(Pending(hex(scope))) { q ->
            SpoolCodec.encode(
                SpoolAput(
                    t = SpoolRecordType.APUT,
                    q = q,
                    scope = scope,
                    aid = aid,
                    idx = idx,
                    total = total,
                    cid = cid,
                    data = data,
                    pow = pow,
                ),
            )
        }

    /** Consumes one inbound record. Malformed bytes are dropped — a client never closes on them. */
    @Suppress("ReturnCount", "CyclomaticComplexMethod") // a flat dispatch over the record registry; nesting it would read worse
    suspend fun onMessage(bytes: ByteArray) {
        when (SpoolCodec.peekType(bytes)) {
            SpoolRecordType.HELLO -> onHello(bytes)
            SpoolRecordType.DIGEST -> SpoolCodec.decode<SpoolDigest>(bytes)?.let { onDigest(it) }
            SpoolRecordType.EVENT -> SpoolCodec.decode<SpoolEvent>(bytes)?.let { onEvent(it) }
            SpoolRecordType.BLOB -> SpoolCodec.decode<SpoolBlob>(bytes)?.let(::onBlob)
            SpoolRecordType.LIST -> SpoolCodec.decode<SpoolList>(bytes)?.let(::onListing)
            SpoolRecordType.OK -> SpoolCodec.decode<SpoolOk>(bytes)?.let { onOk(it) }
            SpoolRecordType.AHAS -> SpoolCodec.decode<SpoolAhas>(bytes)?.let(::onAhas)
            SpoolRecordType.ACHUNK -> SpoolCodec.decode<SpoolAchunk>(bytes)?.let(::onAchunk)
            SpoolRecordType.TURNCRED -> SpoolCodec.decode<SpoolTurnCred>(bytes)?.let(::onTurnCred)
            SpoolRecordType.CAST -> SpoolCodec.decode<SpoolCast>(bytes)?.let { onCast(it) }
            SpoolRecordType.ERR -> SpoolCodec.decode<SpoolErr>(bytes)?.let { onErr(it) }
            else -> Unit // unknown `t`: additive evolution — ignore, never close
        }
    }

    /** The socket is gone: fail every outstanding request and forget the per-connection subscriptions. */
    fun onClosed() {
        closed = true
        if (!handshake.isCompleted) handshake.complete(false)
        subscriptions.clear()
        pending.keys.toList().forEach { q -> pending.remove(q)?.reply?.complete(SpoolReply.Closed) }
    }

    fun close(
        code: Int,
        reason: String,
    ) {
        link.close(code, reason)
        onClosed()
    }

    /** Drops the connection for a reason of our own (see [fault]); a no-op once it is already gone. */
    fun abort(code: String) {
        if (closed) return
        fault = code
        close(SpoolCloseCode.NORMAL, code)
    }

    private suspend fun onHello(bytes: ByteArray) {
        if (negotiated) return // a post-negotiation hello is in-band noise; ignore rather than tear down
        val hello = SpoolCodec.decode<SpoolHello>(bytes)
        val theirMax = hello?.v ?: 0
        val theirMin = hello?.min ?: theirMax
        if (hello == null || SPOOL_RECORD_VERSION !in theirMin..theirMax) {
            // No overlap: the spool would close 4002 the moment we answered; say so ourselves.
            close(SpoolCloseCode.VERSION, "no version overlap")
            return
        }
        limits = hello.limits?.clamped()
        adverts = CommonsAdverts.of(hello.commons?.clamped(limits), hello.rooms?.map { it.tag to it.info().clamped(limits) })
        turn = hello.turn == true
        ent = hello.ent == true
        gates = hello.ticketGates
        // A hostile list is bounded here, before anything holds it: four URLs a call can actually use, or none.
        stun = StunServers.pick(hello.stun.orEmpty())
        cast = hello.cast?.clamped(limits)
        castBudget = cast?.let { CastBudget(it.rate, clock) }
        // A difficulty beyond what a phone will attempt is refused outright rather than mined for: the
        // work is spent per scope on every round that re-subscribes, so believing an absurd one is a
        // remote battery denial that costs the spool a single integer.
        powBits = (hello.powBits ?: 0).coerceAtMost(SpoolPow.MAX_BITS)
        negotiated = true
        val sent = send(SpoolCodec.encode(SpoolHello(t = SpoolRecordType.HELLO, v = SPOOL_RECORD_VERSION)))
        ready = sent
        handshake.complete(sent)
    }

    private fun onBlob(blob: SpoolBlob) {
        // BLOB carries no `q`, so it is attributed by scope *and* by whether a PULL actually named the
        // id. The claim succeeds once per named id, so an id we never asked for, a second copy of one
        // already served, and a flood of either all fall out of the same check — and `blobs` can never
        // outgrow the caller's own request list.
        val scopeHex = hex(blob.scope)
        val idHex = hex(blob.blobId)
        for (entry in pending.values) {
            if (entry.scopeHex != scopeHex || !entry.claimBlob(idHex)) continue
            // Over the bound we ourselves declared at SUB, this is not an answer to what we asked for.
            // Deliberately not `limits.maxBlob`: the spool advertised that in its own hello, so trusting
            // it would let the attacker set its own budget. Report it so §9.3 still accounts the id.
            if (blob.data.size > inboundCap(scopeHex)) entry.oversize.add(blob.blobId) else entry.blobs.add(blob)
            return
        }
    }

    private fun onAhas(ahas: SpoolAhas) {
        // Correlated on `q`, but checked against the question: an answer about some other scope or
        // attachment is not an answer to ours, however well its `q` matches. Dropped rather than failed,
        // so it counts as the silence it is — a spool that answers every question wrongly should reach
        // the strike counter, not keep the connection alive for free.
        val entry = answered(ahas.q, hex(ahas.scope), hex(ahas.aid)) ?: return
        entry.reply.complete(SpoolReply.Presence(total = ahas.total, bits = ahas.bits, dead = ahas.dead))
    }

    private fun onTurnCred(cred: SpoolTurnCred) {
        // A TURNREQ names no scope, so [answered] cannot vouch for it; the marker does instead. A `turncred` for a
        // `q` that asked something else is no answer to it, and is dropped as the silence it is.
        val entry = pending[cred.q]?.takeIf { it.turn } ?: return
        if (!pending.remove(cred.q, entry)) return
        replied()
        entry.reply.complete(SpoolReply.Turn(urls = cred.urls, user = cred.user, pass = cred.pass, exp = cred.exp))
    }

    private fun onAchunk(chunk: SpoolAchunk) {
        // ACHUNK carries no `q` either; the same rule, one level finer. A sealed chunk's size is
        // structural rather than tunable (§4.5 pins it at `1 + nonce + header + 48 KiB + tag`), so
        // nothing legitimate is a byte larger and there is no advertised number to be tempted by.
        val scopeHex = hex(chunk.scope)
        val aidHex = hex(chunk.aid)
        for (entry in pending.values) {
            if (entry.scopeHex != scopeHex || !entry.collectsChunksFor(aidHex)) continue
            // Outside the window we named, or oversize: the spool answered with something other than what
            // was asked for, which §9.5 (C-9.5-4) quarantines the whole `aid` for. A merely *repeated*
            // index is not that — it is dropped, since the window already bounds how many can land.
            if (!entry.inWindow(chunk.idx) || chunk.data.size > ScopeCrypto.SEALED_CHUNK_BYTES) {
                entry.rejectedChunk = true
            } else if (entry.claimChunk(chunk.idx)) {
                entry.chunks.add(chunk)
            }
            return
        }
    }

    private fun onListing(listing: SpoolList) {
        // Ids are `bstr32` by definition (§12.1), and every one of these is hexed and held by the caller —
        // so drop the malformed ones here rather than letting an arbitrary-length byte string through.
        // The reply's scope is checked like `ahas`'s: a listing for another scope, applied to ours, would
        // re-anchor us on a set we never asked about.
        val entry = answered(listing.q, hex(listing.scope)) ?: return
        entry.reply.complete(
            SpoolReply.Listing(listing.blobIds.orEmpty().filter(::isId), listing.tombstones.orEmpty().filter(::isId)),
        )
    }

    /**
     * Takes the outstanding entry for [q] off the table if the reply is about what it asked — [scopeHex],
     * and for an AHAVE [aidHex] too. Null (and the entry left waiting) when it is not: a mismatched reply
     * is no reply. Match-then-remove is atomic on the entry, so a second, matching record still completes it.
     */
    private fun answered(
        q: Long,
        scopeHex: String,
        aidHex: String? = null,
    ): Pending? {
        val entry = pending[q] ?: return null
        if (entry.scopeHex != scopeHex || (aidHex != null && entry.aidHex != aidHex)) return null
        return if (pending.remove(q, entry)) entry.also { replied() } else null
    }

    private fun replied() {
        silent.set(0)
        answered = true
    }

    private fun isId(bytes: ByteArray): Boolean = bytes.size == SPOOL_ID_BYTES

    private suspend fun onOk(ok: SpoolOk) {
        // `missing` answers *this* request, so ids it never named are not ours to act on: `ScopeSync`
        // folds them out of the spool's digest anchor, which an invented id would then corrupt.
        val entry = pending.remove(ok.q) ?: return
        replied()
        val ticket = entry.ticket
        // The claims bind to this connection (S-7.6-15), replacing any accepted before — and the state moves before the
        // reply completes, so a TURN ask waiting on it reads the ticket it waited for.
        if (ticket != null) {
            settleTicket(entry) {
                accepted = SpoolTicketState.Accepted(ticket.claims.exp, ticket.claims.fl, ticket.claims.ab, ticket.claims.am)
                acceptedPseudonym = ticket.pseudonym
                refusal = null
            }
        }
        val reply = SpoolReply.Ok(ok.missing.orEmpty().filter { entry.wasRequested(hex(it)) })
        entry.reply.complete(reply)
        if (ticket != null) onTicketAnswer(ticket, reply)
    }

    /** Settles [entry], the answered ENT: [apply] moves the state, and the entry stops being the one a TURN ask awaits. */
    private inline fun settleTicket(
        entry: Pending,
        apply: () -> Unit,
    ) {
        apply()
        if (presenting === entry) presenting = null
    }

    private suspend fun onErr(err: SpoolErr) {
        val entry = err.q?.let { pending.remove(it) }
        if (entry != null) {
            replied()
            val reply = SpoolReply.Failed(err.code.take(SPOOL_ERR_CODE_MAX), err.retryMs, err.msg?.take(SPOOL_ERR_CODE_MAX))
            // A refused ENT leaves an accepted ticket in place (S-7.6-15) and is never a strike: only the word moves.
            val ticket = entry.ticket
            if (ticket != null) settleTicket(entry) { refusal = reply.msg ?: reply.code }
            entry.reply.complete(reply)
            if (ticket != null) onTicketAnswer(ticket, reply)
            return
        }
        // A ticket refusal whose request is gone (it timed out; an `aput`'s carries its `q` and `scope`) answers no
        // SUB: a SUB is never refused `ticket`, so reading it as one would drop a subscribed scope for nothing.
        if (err.code == SpoolErrCode.TICKET) return
        // No correlation: a scoped SUB refusal (pow/quota) or a connection-scoped fault. A refused
        // scope is not subscribed, whatever we optimistically recorded when the SUB went out.
        val scopeHex = err.scope?.let(::hex)
        if (scopeHex != null) subscriptions.remove(scopeHex)
        onScopeError(scopeHex, err.code.take(SPOOL_ERR_CODE_MAX), err.retryMs)
    }

    private suspend fun request(
        entry: Pending,
        timeoutMs: Long = REQUEST_TIMEOUT_MS,
        // Whether a silence counts toward [MAX_SILENT_REQUESTS]. Off only for a request whose short wait is the
        // caller's own impatience rather than evidence about the spool (a TURN credential a call is waiting on).
        strike: Boolean = true,
        encode: (Long) -> ByteArray,
    ): SpoolReply {
        val q = nextQ.getAndIncrement()
        pending[q] = entry
        if (!send(encode(q))) {
            pending.remove(q)
            return SpoolReply.Closed
        }
        // A spool that accepts a record and never answers must not wedge the heal loop: time out, drop the
        // correlation, and let the next round re-derive the diff (a re-PUSH is byte-identical, a re-PULL is
        // idempotent, so nothing is lost by giving up on a response). Three such silences in a row and
        // the connection itself is given up on — otherwise a spool that goes quiet after the handshake
        // costs 30 s per request, serially, across every scope on it.
        return try {
            withTimeoutOrNull(timeoutMs) { entry.reply.await() }
                ?: SpoolReply.Closed.also {
                    if (strike && silent.incrementAndGet() >= MAX_SILENT_REQUESTS) abort(UNRESPONSIVE)
                }
        } finally {
            // Also on a cancelled caller (a hung-up call), which would otherwise leave its entry in the table.
            // A no-op once a terminal handler already removed it.
            pending.remove(q, entry)
        }
    }

    /**
     * The largest `blob` we will hold for [scopeHex]: the `maxBlob` **we** declared for it at SUB, which
     * is also the size `ScopeSync.pushable` refuses to exceed on the way out. What we will not send, we
     * will not accept — and unlike `limits.maxBlob`, it is not a number the spool chose.
     */
    private fun inboundCap(scopeHex: String): Int = subscriptions[scopeHex]?.maxBlob ?: ScopeRegistry.DEFAULT_MAX_BLOB

    private fun send(bytes: ByteArray): Boolean {
        if (closed) return false
        val cap = limits?.maxRecord
        if (cap != null && bytes.size > cap) return false // never hand the spool a record it must refuse
        return link.send(bytes)
    }

    companion object {
        /** [fault] when the spool stopped answering requests it had accepted (see [MAX_SILENT_REQUESTS]). */
        const val UNRESPONSIVE = "unresponsive"

        /** Consecutive unanswered requests before the connection is dropped as [UNRESPONSIVE]. */
        const val MAX_SILENT_REQUESTS = 3

        /** How long a `q`-correlated request waits for its terminal `ok`/`err`/`list` before giving up. */
        const val REQUEST_TIMEOUT_MS = 30_000L

        /** Socket bytes queued past which a cast is skipped rather than sent behind them: about one attachment chunk. */
        const val CAST_QUEUE_LIMIT = 64L * 1024

        /** The highest cast rate believed from a hello; knit-spool refuses to start above it. */
        const val MAX_CAST_RATE = 100

        /** A cast budget's burst, as a multiple of its rate (docs/VOICE_CALLS.md §2.7). */
        const val CAST_BURST_FACTOR = 4

        private const val MS_PER_S = 1_000L

        /**
         * A `pull` batch's ceiling. Every value here is one the *spool* advertised, so treating them as
         * hints rather than facts is the whole lesson of this file: at 256 ids of [ScopeRegistry
         * .DEFAULT_MAX_BLOB] each, a pull's worst-case buffer is 16 MiB, and the spec only suggests 64.
         */
        private const val MAX_PULL_CEILING = 256

        /** Two maximal attachments' worth — well past any honest `maxAttachBytes` (§12.2 suggests 16 MiB). */
        private const val MAX_ATTACH_BYTES_CEILING = 4 * ScopeAttachments.MAX_ATTACHMENT_BYTES

        /**
         * HELLO limits are the spool's own claim about itself, taken verbatim until now — a hostile one
         * could advertise `Int.MAX_VALUE` and set its own budget for every check written against them.
         * Clamping on ingest is the one place that says *these numbers are a hint*.
         *
         * Only the upper end matters here. A spool that declares an unusably *small* `maxRecord` is
         * caught one step later: below our 12-byte hello reply the handshake completes false, and below
         * a SUB the worker aborts the session as `too_large` (`ScopeSync.subscribe`) — either way it backs off.
         */

        private fun SpoolLimits.clamped() =
            SpoolLimits(
                maxBlob = maxBlob.coerceAtMost(MAX_INBOUND_RECORD),
                maxRecord = maxRecord.coerceAtMost(MAX_INBOUND_RECORD),
                maxScopes = maxScopes,
                maxPull = maxPull.coerceIn(1, MAX_PULL_CEILING),
                maxFramesCap = maxFramesCap,
                maxTtlMs = maxTtlMs,
                // Null-preserving: `attachments` is all-three-or-none, and collapsing one to a default
                // would silently claim a v1 spool speaks §7.3.
                maxAttachBytes = maxAttachBytes?.coerceAtMost(MAX_ATTACH_BYTES_CEILING),
                maxAChunk = maxAChunk?.coerceAtMost(ScopeCrypto.SEALED_CHUNK_BYTES),
                maxAget = maxAget?.coerceIn(1, ScopeAttachments.MAX_CHUNKS),
            )

        /** A spool's cast bounds, held to what one record can carry and to a rate no honest spool exceeds. */
        private fun SpoolCastInfo.clamped(limits: SpoolLimits?) =
            SpoolCastInfo(
                maxBytes = maxBytes.coerceIn(0, limits?.maxRecord ?: MAX_INBOUND_RECORD),
                rate = rate.coerceIn(0, MAX_CAST_RATE),
            )

        /** The room's pinned bounds (§7.4), held to the same ceilings as the spool-wide caps they sit under. */
        private fun SpoolCommonsInfo.clamped(limits: SpoolLimits?) =
            SpoolCommonsInfo(
                name = name?.takeIf { it.isNotBlank() },
                // A pinned `maxFrames` sizes the accounted set and the listing threshold `ScopeSync` derives
                // from it, so an absurd one is a claim like every other HELLO number.
                maxFrames = maxFrames.coerceIn(1, MAX_PINNED_FRAMES),
                ttlMs = ttlMs.coerceAtLeast(1L),
                maxBlob = maxBlob.coerceIn(1, limits?.maxBlob ?: MAX_INBOUND_RECORD),
                attach = attach,
            )
    }
}

/**
 * A spool's advertised cast budget, kept here because the spool drops an over-budget cast without a word: [rate] a
 * second with a burst of four times that, exactly the bucket the spool runs. A rate of 0 refuses every take.
 */
private class CastBudget(
    private val rate: Int,
    private val clock: () -> Long,
) {
    private val burst = (rate * SpoolConnection.CAST_BURST_FACTOR).toDouble()
    private var tokens = burst
    private var lastMs = clock()

    @Synchronized
    fun take(): Boolean {
        val now = clock()
        tokens = minOf(burst, tokens + (now - lastMs) * rate / MS_PER_SECOND)
        lastMs = now
        if (tokens < 1.0) return false
        tokens -= 1.0
        return true
    }

    private companion object {
        const val MS_PER_SECOND = 1_000.0
    }
}
