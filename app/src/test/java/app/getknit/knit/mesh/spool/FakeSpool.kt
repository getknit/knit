package app.getknit.knit.mesh.spool

import app.getknit.knit.mesh.CarriedFrame
import app.getknit.knit.mesh.ForwardStore
import app.getknit.knit.mesh.crypto.scope.ScopeCrypto
import app.getknit.knit.mesh.crypto.scope.SpoolPow
import app.getknit.knit.mesh.protocol.ChatContent
import app.getknit.knit.mesh.protocol.CommonsPost
import app.getknit.knit.mesh.protocol.EncEnvelope
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.GroupInfo
import app.getknit.knit.mesh.protocol.GroupLeaveContent
import app.getknit.knit.mesh.protocol.GroupRatchetHeader
import app.getknit.knit.mesh.protocol.ProfileContent
import app.getknit.knit.mesh.protocol.RatchetHeader
import app.getknit.knit.mesh.protocol.ReactionContent
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * An in-process spool that models the reference daemon's observable v1 semantics: unprompted server
 * hello, SUB → one DIGEST per scope, LIST with tombstones, PULL truncated at `maxPull` with a `missing`
 * list, PUSH with content-address re-verification / tombstone refusal / oldest-first eviction, and
 * EVENT fan-out that excludes the uploader.
 *
 * The §7.3 attachment family is modelled too: presence bitmaps, first-write-wins at a position, the
 * per-scope byte quota with whole-attachment eviction, and `aget` truncation at `maxAget`. Set
 * [attachments] to false to model a v1 spool — one that advertises no attachment limits, and which the
 * client must therefore never send an attachment record to.
 *
 * It exists so the whole client plane — including the bidirectional heal loop — is exercised in plain
 * JVM tests. It is deliberately *not* a conformance oracle: `knit-spool`'s own suite is that, and the
 * byte-level record vectors are pinned by [SpoolRecordsTest] against `docs/SPOOL_PROTOCOL.md`.
 */

class FakeSpool(
    private val maxPull: Int = 64,
    private val maxFrames: Int = 400,
    private val maxBlob: Int = 65_536,
    private val powBits: Int = 0,
    // Version the server advertises; a value below SPOOL_RECORD_VERSION exercises the no-overlap close.
    private val version: Int = SPOOL_RECORD_VERSION,
    // false = a v1 spool: no attachment limits in HELLO, and any attachment record is silently skipped
    // (never answered), which is exactly the stall a conforming client must avoid provoking. Mutable so a
    // scenario can grow the support mid-run: the limits are read per dial, so [dropSockets] re-advertises.
    var attachments: Boolean = true,
    private val maxAget: Int = 32,
    private val maxAChunk: Int = 49_221,
    private val maxAttachBytes: Int = 16_777_216,
    // The commons this spool runs (§7.4): its scope id and what the HELLO advertises about it. Null = no
    // commons, the HELLO omits the field, and a SUB for one is an ordinary unknown scope.
    commons: Pair<ByteArray, SpoolCommonsInfo>? = null,
    // The rooms a spool that reads `rooms` runs (§7.4, S-7.4-1): each scope id and what its entry advertises. Set, the
    // HELLO lists them all by tag and repeats the first as `commons`, and [commons] is not read; null = a spool that
    // predates the list, advertising [commons] alone.
    rooms: List<Pair<ByteArray, SpoolCommonsInfo>>? = null,
    // The daemon's store rule for an ordinary scope: a SUB's declared bounds are applied (last writer wins) and
    // echoed in its DIGEST. Off by default, where the DIGEST carries this spool's fixed defaults; a commons always
    // answers with its pinned bounds whatever the SUB declared (S-7.4-2).
    private val echoesDeclaredBounds: Boolean = false,
    // The spool-wide scope cap the HELLO advertises, and — separately — the count past which a SUB for a
    // scope not yet held is refused `quota` (null = never), the way the daemon's store enforces it.
    private val maxScopes: Int = 64,
    private val scopeQuota: Int? = null,
    // `retryMs` on a quota refusal, when the spool has an opinion about when to come back.
    private val quotaRetryMs: Long? = null,
    // Overrides the HELLO's `maxRecord` (default `maxBlob + 512`) — small enough and a SUB will not fit.
    private val maxRecord: Int? = null,
    // Hostile listing shape: this many synthetic ids appended to every LIST answer's live set / tombstones.
    private val listingPadding: Int = 0,
    private val tombstonePadding: Int = 0,
    // A spool that turns every stamp down (a difficulty the client cannot meet, a day it will not take).
    private val rejectStamps: Boolean = false,
    // Issues TURN credentials (experimental, docs/VOICE_CALLS.md §2): HELLO carries `turn` and a TURNREQ on a
    // connection holding a SUB is answered. false = an older spool, which skips a TURNREQ unanswered.
    private val turn: Boolean = false,
    // Takes entitlement tickets (experimental, docs/VOICE_CALLS.md §2.6): HELLO carries `ent`, an ENT is answered by
    // [entAnswer], and each verb [entGates] names needs an accepted ticket with its flag (S-7.6-16, S-7.6-22, S-7.7-6).
    private val ent: Boolean = false,
    // The `entGates` HELLO carries beside `ent` (knit-spool ADR 2026-10.h6pt); null omits it, which a client reads as 9
    // (TURN and cast), the gates a key turned on before the field existed. Never sent without [ent].
    private val entGates: Long? = null,
    // The daemon's `SPOOL_TICKET_ATTACH_BYTES`: a pseudonym's upload budget for its day under the files gate, capped
    // further by its ticket's `ab`.
    private val ticketAttachBytes: Long = 134_217_728L,
    // The credential-free STUN list HELLO advertises; null omits the field.
    private val stun: List<String>? = null,
    // Fans out casts (experimental, docs/VOICE_CALLS.md §2.7): HELLO carries these bounds, and a CAST from a subscriber
    // goes to every other subscriber of its scope and is never stored. With [ent], only on a scope some subscriber's
    // accepted ticket carries the cast flag for. Null = a spool without casts, which skips a CAST unanswered.
    private val cast: SpoolCastInfo? = null,
    // The TURN servers HELLO names beside `turn` (S-7.6-48), as entries in server order; null omits the field, as a
    // spool from before it did. Each server's credential carries `turn:<entry>?transport=udp` alone (S-7.6-50), and a
    // TURNREQ naming no listed entry is answered from the first (S-7.6-49).
    private val turnHosts: List<String>? = null,
    // Fans out sigs (§7.8): HELLO carries these bounds, and a SIG from a subscriber goes to every other subscriber of its
    // scope, whatever the tickets (S-7.8-4), never into a commons (S-7.8-3), and is never stored or answered. Null = a
    // spool without sigs, which skips a SIG unanswered.
    private val sig: SpoolSigInfo? = null,
) : SpoolDialer {
    // Settable, so a test can play the operator changing it — a rotated invite reverted, a restart without the
    // room's setting. A HELLO reads it as a connection opens and a DIGEST as it is answered: change it, then
    // [dropSockets], as a daemon restart would.
    @Volatile
    var commons: Pair<ByteArray, SpoolCommonsInfo>? = commons

    // As [commons], for a spool that lists its rooms: change it, then [dropSockets].
    @Volatile
    var rooms: List<Pair<ByteArray, SpoolCommonsInfo>>? = rooms

    /** Attachment records refused `err malformed` because they named a room whose entry said no `attach` (S-7.4-5). */
    val refusedRoomAttachments: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private class Attachment(
        val total: Int,
        val arrivedAt: Int,
    ) {
        val chunks = HashMap<Int, ByteArray>()
        val cids = HashMap<Int, String>()
        val bytes: Int get() = chunks.values.sumOf { it.size }
    }

    private class ScopeState {
        val live = LinkedHashMap<String, ByteArray>()
        val tombstones = LinkedHashSet<String>()
        val attachments = LinkedHashMap<String, Attachment>()
        val attachTombstones = LinkedHashSet<String>()
    }

    private val scopes = ConcurrentHashMap<String, ScopeState>()

    // What the latest SUB for each scope declared ([echoesDeclaredBounds]).
    private val declaredBounds = ConcurrentHashMap<String, ScopeBounds>()

    /** The bounds the latest SUB for [scopeHex] declared, or null when none has — what a client asked, not what was pinned. */
    fun declaredFor(scopeHex: String): ScopeBounds? = declaredBounds[scopeHex]

    private val sockets = mutableListOf<FakeSocket>()

    /** Every PUSH the spool accepted, in order — the outbound-side assertion hook. */
    val pushed = mutableListOf<String>()

    /** Every blob id a PULL asked for — how a test proves the client stopped re-requesting something. */
    val pulled = mutableListOf<String>()

    /** SUB stamps the spool was handed, by scope hex — lets a test assert PoW actually rode along. */
    val stamps = ConcurrentHashMap<String, PowStamp>()

    /** Every SUB entry, in order, as (scope hex, whether it carried a stamp) — proves when a stamp went out. */
    val subStamped: MutableList<Pair<String, Boolean>> = java.util.Collections.synchronizedList(mutableListOf())

    /** `err pow` answers, by scope hex: the daemon's §6.4 gate on a scope it does not hold. */
    val powRefusals = ConcurrentHashMap<String, Int>()

    /** Sheds [scopeHex] as the daemon's store may (S-6.2-8): the next request for it meets the creation gates. */
    fun forget(scopeHex: String) {
        scopes.remove(scopeHex)
    }

    // The daemon's unknown-scope gate (§6.4, S-6.4-2): with PoW on, a scope this spool does not hold needs a
    // stamp that verifies. Not its day window — this spool keeps no clock — and not its per-IP bucket.
    private fun powRefused(
        scope: ByteArray,
        pow: PowStamp?,
    ): Boolean {
        val scopeHex = hex(scope)
        // A commons exists from the spool's boot, so it is never an unknown scope (the daemon's rule too).
        if (powBits <= 0 || scopes.containsKey(scopeHex) || roomFor(scopeHex) != null) return false
        if (pow != null && !rejectStamps && SpoolPow.verify(scope, pow.d, pow.n, powBits)) return false
        powRefusals.merge(scopeHex, 1, Int::plus)
        return true
    }

    /** Every scope any connection ever SUBbed, by hex — proves a scope was (or was never) asked for here. */
    val subscribedScopes: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** How many times each scope (hex) was SUBbed, refusals included — proves a refused scope is not re-asked every tick. */
    val subAttempts = ConcurrentHashMap<String, Int>()

    /** Every `q`-correlated request received while [muted] — what a silent spool swallowed. */
    val swallowed = mutableListOf<String>()

    @Volatile
    private var muted = false

    @Volatile
    private var blackholed = false

    /**
     * From now on every dial is a route that swallows the socket — work item 50's validated-but-dead
     * Wi-Fi (ADR 2026-09.vej5): the socket opens, nothing ever comes back, and after [BLACKHOLE_MS] it
     * dies with the dialer's `unreachable` verdict, the way OkHttp's connect timeout ends it. On the wall
     * clock, since the mesh-in-a-box lab is its user; [unblackhole] restores the relay for the next dial.
     */
    fun blackhole() {
        blackholed = true
    }

    fun unblackhole() {
        blackholed = false
    }

    /**
     * From now on the spool completes the handshake and answers SUBs, and swallows every correlated
     * request after that — the spool that "accepts and never answers" (§7.3's stall, made permanent).
     */
    fun mute() {
        muted = true
    }

    /** The spool finds its voice again: every request from here on is answered as usual. */
    fun unmute() {
        muted = false
    }

    override suspend fun dial(url: String): SpoolSocket {
        if (blackholed) return BlackHoleSocket()
        val socket = FakeSocket()
        synchronized(sockets) { sockets.add(socket) }
        val listed = rooms
        socket.emit(
            SpoolCodec.encode(
                SpoolHello(
                    t = SpoolRecordType.HELLO,
                    v = version,
                    min = version,
                    limits =
                        SpoolLimits(
                            maxBlob = maxBlob,
                            maxRecord = maxRecord ?: (maxBlob + 512),
                            maxScopes = maxScopes,
                            maxPull = maxPull,
                            maxFramesCap = maxFrames,
                            maxTtlMs = 7 * 24 * 60 * 60_000L,
                            maxAttachBytes = maxAttachBytes.takeIf { attachments },
                            maxAChunk = maxAChunk.takeIf { attachments },
                            maxAget = maxAget.takeIf { attachments },
                        ),
                    powBits = powBits,
                    commons = if (listed != null) listed.firstOrNull()?.second else commons?.second,
                    turn = true.takeIf { turn },
                    ent = true.takeIf { ent },
                    stun = stun,
                    cast = cast,
                    entGates = entGates.takeIf { ent },
                    turnHosts = turnHosts?.takeIf { turn },
                    sig = sig,
                    rooms =
                        listed?.map { (id, info) ->
                            SpoolCommonsRoom(ScopeCrypto.commonsTag(id), info.name, info.maxFrames, info.ttlMs, info.maxBlob, info.attach)
                        },
                ),
            ),
        )
        return socket
    }

    /** What this spool pins for the room whose scope is [scopeHex], or null when it runs no such room. */
    private fun roomFor(scopeHex: String): SpoolCommonsInfo? {
        val listed = rooms
        return if (listed != null) {
            listed.firstOrNull { hex(it.first) == scopeHex }?.second
        } else {
            commons?.takeIf { hex(it.first) == scopeHex }?.second
        }
    }

    /** The room's own cap for a commons, the spool-wide one for everything else. */
    private fun maxFramesFor(scopeHex: String): Int = roomFor(scopeHex)?.maxFrames ?: maxFrames

    /**
     * Says something the client never asked for. The one hostile primitive on this otherwise honest
     * fake: everything else here models the reference daemon, and a test that needs a spool to *lie*
     * needs to state the lie itself rather than have it hidden in the fake's normal answers.
     */
    fun gossip(record: ByteArray) {
        synchronized(sockets) { sockets.toList() }.forEach { it.emit(record) }
    }

    /**
     * Kills every open socket, the way Doze, a Wi-Fi flip or a spool restart does. The client reconnects
     * on its own backoff onto a *fresh* connection — which is what drops every per-connection set.
     */
    fun dropSockets() {
        synchronized(sockets) { sockets.toList() }.forEach { it.close(1006, "dropped") }
    }

    /**
     * Ages a blob out (S-6.2-1): gone from the live set, tombstoned, and every subscriber told with a
     * fresh digest (S-7.2-3). The digest is not optional dressing — without it no client would ever look
     * again, since its own anchor still folds the blob in.
     */
    fun expire(
        scopeHex: String,
        blobIdHex: String,
    ) {
        val state = scopes[scopeHex] ?: return
        state.live.remove(blobIdHex)
        state.tombstones.add(blobIdHex)
        gossip(SpoolCodec.encode(digestRecord(unhex(scopeHex))))
    }

    // Scopes whose SUB answers are held back ([holdSubscriptions]), and the subscriptions parked meanwhile.
    private val heldSubScopes = mutableSetOf<String>()
    private val parkedSubs = mutableListOf<Pair<FakeSocket, ByteArray>>()

    /**
     * From now on a SUB for [scopeHex] is taken but not answered: no digest, and no live events for it on that
     * socket — a spool slow to answer one scope. A client's heal round for a scope returns early until its SUB is
     * answered, so this holds that scope's pulls back while every other scope runs; a scenario uses it to fix the
     * order two scopes' pulls land in. [releaseSubscriptions] answers the parked SUBs.
     */
    @Synchronized
    fun holdSubscriptions(scopeHex: String) {
        heldSubScopes.add(scopeHex)
    }

    /** Answers every SUB [holdSubscriptions] parked for [scopeHex], and stops holding new ones. */
    @Synchronized
    fun releaseSubscriptions(scopeHex: String) {
        heldSubScopes.remove(scopeHex)
        val due = parkedSubs.filter { hex(it.second) == scopeHex }
        parkedSubs.removeAll(due)
        due.forEach { (socket, scope) -> socket.completeSub(scope) }
    }

    /**
     * Says the current digest again, unprompted (S-7.2-3 style) — the cue that makes a client look at the
     * live set without anything having changed at the spool, e.g. after [plantGarbage].
     */
    fun announce(scopeHex: String) {
        gossip(SpoolCodec.encode(digestRecord(unhex(scopeHex))))
    }

    /** The `digest` the spool would answer a SUB for [scope] with — shared by SUB and [expire]. */
    private fun digestRecord(scope: ByteArray): SpoolDigest {
        val scopeHex = hex(scope)
        val state = scopes.getOrPut(scopeHex) { ScopeState() }
        val cap = maxFramesFor(scopeHex)
        // The commons answers with the bounds the operator pinned, whatever the SUB declared (§7.4).
        val bounds =
            roomFor(scopeHex)
                ?.let { ScopeBounds(maxFrames = it.maxFrames, ttlMs = it.ttlMs, maxBlob = it.maxBlob) }
                ?: declaredBounds[scopeHex]?.takeIf { echoesDeclaredBounds }
                ?: ScopeBounds(maxFrames = maxFrames, ttlMs = ScopeRegistry.DEFAULT_TTL_MS, maxBlob = maxBlob)
        return SpoolDigest(
            t = SpoolRecordType.DIGEST,
            scope = scope,
            digest = ScopeCrypto.digestBytes(ScopeCrypto.scopeDigest(state.live.keys.map(::unhex))),
            count = state.live.size,
            full = state.live.size >= cap,
            bounds = bounds,
        )
    }

    /** Plants a blob the spool folds into its digest but no member can open — the §9.3 divergence trap. */
    fun plantGarbage(
        scopeHex: String,
        data: ByteArray,
    ): String {
        val id = hex(sha256(data))
        scopes.getOrPut(scopeHex) { ScopeState() }.live[id] = data
        return id
    }

    /** Every `aput` the spool accepted, as "aidHex:index" — the outbound attachment assertion hook. */
    val chunksPut = mutableListOf<String>()

    /** Every `aget` window the spool was asked for, as "aidHex:from+n" — proves the client stopped asking. */
    val chunkGets = mutableListOf<String>()

    /** Every `ahave` the spool answered, as aidHex — proves a deferral costs no round trip at all. */
    val presenceAsks = mutableListOf<String>()

    /** Records this spool was sent but does not implement; must stay empty against a v1 spool. */
    val skippedRecords = mutableListOf<String>()

    /** How many TURN credentials this spool issued. */
    var turnIssued = 0
        private set

    /** How many of [turnIssued] rode a ticket — the daemon's `knit_spool_turn_issued_ticketed_total`. */
    var turnIssuedTicketed = 0
        private set

    /** The `host` every TURNREQ named, in order (null for one that named none). */
    val turnHostsAsked: MutableList<String?> = Collections.synchronizedList(mutableListOf())

    /** The `msg` of every `err ticket` a TURNREQ got. */
    val turnRefusals: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** Every CAST this spool was sent, as scope hex — carried or dropped, the daemon's `knit_spool_casts_total`. */
    val casts: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** Copies of casts handed to subscribers, the daemon's `knit_spool_casts_fanned_total`. */
    @Volatile
    var castsFanned = 0
        private set

    /** Every SIG this spool was sent, as scope hex — carried or dropped. */
    val sigs: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** Copies of sigs handed to subscribers. */
    @Volatile
    var sigsFanned = 0
        private set

    /** Every ENT this spool was sent, in order. */
    val ents: MutableList<SpoolEnt> = Collections.synchronizedList(mutableListOf())

    /** What an ENT is answered with: null accepts its claims, a word refuses it `err ticket` with that `msg`. */
    @Volatile
    var entAnswer: (SpoolEnt) -> String? = { null }

    /** While true, an ENT is recorded and its answer held until [releaseEnts] — a spool slow to judge a ticket. */
    @Volatile
    var holdEnts = false

    private val heldEnts = mutableListOf<() -> Unit>()

    /** Answers every ENT held by [holdEnts], in order. */
    fun releaseEnts() {
        val held = synchronized(this) { heldEnts.toList().also { heldEnts.clear() } }
        held.forEach { it() }
    }

    /** Every APUT this spool was sent, stored or refused, as "aidHex:index" — proves a client never tried. */
    val aputs: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /**
     * Refuses `size` to every `aput` whose `total` is over this many chunks, under the files gate, whatever this
     * connection's ticket says — the spool reading an `am` the client did not see (a chunk of the same `aid` charged on
     * another connection, S-7.6-31's "every chunk"). Null leaves only the ticket's own `am`.
     */
    @Volatile
    var refuseSizeOver: Int? = null

    /** The `msg` of every `err ticket` an APUT got under the files gate. */
    val aputRefusals: MutableList<String> = Collections.synchronizedList(mutableListOf())

    // Pseudonym hex → bytes charged today: the daemon's `UploadBudgets`, memory only like its own.
    private val uploadBudgets = ConcurrentHashMap<String, Long>()

    /** What the pseudonym in [ticket] has been charged for its stored chunks, or 0. */
    fun uploadCharged(ticket: HeldTicket): Long = uploadBudgets[psHex(ticket.tk)] ?: 0L

    // The `fl` bits a connection told this HELLO needs a ticket for — the daemon's `Hello.ticketGates`.
    private val gates: Long get() = if (ent) entGates ?: SpoolTicket.LEGACY_GATES else 0L

    /** How many chunks the spool holds for [aidHex] — the simplest "did the upload land" assertion. */
    fun chunkCount(
        scopeHex: String,
        aidHex: String,
    ): Int =
        scopes[scopeHex]
            ?.attachments
            ?.get(aidHex)
            ?.chunks
            ?.size ?: 0

    /** Drops one chunk so a test can model a spool that only ever half-received an attachment. */
    fun dropChunk(
        scopeHex: String,
        aidHex: String,
        index: Int,
    ) {
        scopes[scopeHex]?.attachments?.get(aidHex)?.let {
            it.chunks.remove(index)
            it.cids.remove(index)
        }
    }

    /** Replaces a stored chunk with bytes no member can open — the attachment analogue of [plantGarbage]. */
    fun corruptChunk(
        scopeHex: String,
        aidHex: String,
        index: Int,
    ) {
        scopes[scopeHex]?.attachments?.get(aidHex)?.let {
            val garbage = ByteArray(120) { i -> (i * 3 + 1).toByte() }
            it.chunks[index] = garbage
            it.cids[index] = hex(sha256(garbage))
        }
    }

    fun liveIds(scopeHex: String): Set<String> =
        scopes[scopeHex]
            ?.live
            ?.keys
            ?.toSet()
            .orEmpty()

    /** A socket nothing answers: silent for [BLACKHOLE_MS], then closed as `unreachable`. */
    private class BlackHoleSocket : SpoolSocket {
        private val channel = Channel<ByteArray>(Channel.UNLIMITED)

        @Volatile
        private var dead = false

        init {
            Thread {
                Thread.sleep(BLACKHOLE_MS)
                dead = true
                channel.close()
            }.apply { isDaemon = true }.start()
        }

        override val incoming: ReceiveChannel<ByteArray> get() = channel
        override val closeReason: String? get() = if (dead) ScopeSync.UNREACHABLE else null

        override fun send(bytes: ByteArray): Boolean = !dead

        override fun close(
            code: Int,
            reason: String,
        ) {
            channel.close()
        }
    }

    private inner class FakeSocket : SpoolSocket {
        private val channel = Channel<ByteArray>(Channel.UNLIMITED)
        private val subscribed = mutableSetOf<String>()

        // The claims an accepted ENT bound to this connection (S-7.6-15), and its pseudonym's hex; a refused one leaves
        // them in place.
        private var ticket: SpoolTicket? = null
        private var ticketPs: String? = null

        // The gates this connection's HELLO named, snapshotted at the dial as the daemon snapshots them.
        private val connGates = gates

        private fun gated(flag: Long): Boolean = connGates and flag != 0L

        @Volatile
        private var open = true

        override val incoming: ReceiveChannel<ByteArray> get() = channel

        fun emit(bytes: ByteArray) {
            channel.trySend(bytes)
        }

        fun deliverEvent(
            scope: ByteArray,
            blobId: String,
            data: ByteArray,
        ) {
            if (hex(scope) !in subscribed) return
            emit(SpoolCodec.encode(SpoolEvent(t = SpoolRecordType.EVENT, scope = scope, blobId = unhex(blobId), data = data)))
        }

        override fun send(bytes: ByteArray): Boolean {
            if (!open) return false
            if (swallow(bytes)) return true
            handle(bytes)
            return true
        }

        /** A muted spool takes every correlated request and answers none — true when this one was eaten. */
        private fun swallow(bytes: ByteArray): Boolean {
            val type = SpoolCodec.peekType(bytes)
            if (!muted || type in UNSWALLOWED) return false
            synchronized(swallowed) { swallowed.add(type.orEmpty()) }
            return true
        }

        override fun close(
            code: Int,
            reason: String,
        ) {
            if (!open) return
            open = false
            synchronized(sockets) { sockets.remove(this) }
            channel.close()
        }

        // Locked on the spool, not the socket: the scope state is shared by every socket, and the mesh-in-a-box
        // lab drives two real nodes at it from Dispatchers.Default. Nothing in here suspends.
        private fun handle(bytes: ByteArray) =
            synchronized(this@FakeSpool) {
                handleLocked(bytes)
            }

        @Suppress("CyclomaticComplexMethod") // a flat dispatch over the record registry
        private fun handleLocked(bytes: ByteArray) {
            when (SpoolCodec.peekType(bytes)) {
                SpoolRecordType.HELLO -> {}

                // the client's answer; nothing to do
                SpoolRecordType.SUB -> {
                    SpoolCodec.decode<SpoolSub>(bytes)?.let(::onSub)
                }

                SpoolRecordType.LIST -> {
                    SpoolCodec.decode<SpoolList>(bytes)?.let(::onList)
                }

                SpoolRecordType.PULL -> {
                    SpoolCodec.decode<SpoolPull>(bytes)?.let(::onPull)
                }

                SpoolRecordType.PUSH -> {
                    SpoolCodec.decode<SpoolPush>(bytes)?.let(::onPush)
                }

                SpoolRecordType.AHAVE -> {
                    if (attachments) SpoolCodec.decode<SpoolAhave>(bytes)?.let(::onAhave) else skip(SpoolRecordType.AHAVE)
                }

                SpoolRecordType.AGET -> {
                    if (attachments) SpoolCodec.decode<SpoolAget>(bytes)?.let(::onAget) else skip(SpoolRecordType.AGET)
                }

                SpoolRecordType.APUT -> {
                    if (attachments) SpoolCodec.decode<SpoolAput>(bytes)?.let(::onAput) else skip(SpoolRecordType.APUT)
                }

                SpoolRecordType.TURNREQ -> {
                    if (turn) SpoolCodec.decode<SpoolTurnReq>(bytes)?.let(::onTurnReq) else skip(SpoolRecordType.TURNREQ)
                }

                SpoolRecordType.ENT -> {
                    if (ent) SpoolCodec.decode<SpoolEnt>(bytes)?.let(::onEnt) else skip(SpoolRecordType.ENT)
                }

                SpoolRecordType.CAST -> {
                    if (cast != null) SpoolCodec.decode<SpoolCast>(bytes)?.let(::onCast) else skip(SpoolRecordType.CAST)
                }

                SpoolRecordType.SIG -> {
                    if (sig != null) SpoolCodec.decode<SpoolSig>(bytes)?.let(::onSig) else skip(SpoolRecordType.SIG)
                }

                else -> {}
            }
        }

        private fun onSub(sub: SpoolSub) {
            sub.subs.forEach { entry ->
                val scopeHex = hex(entry.scope)
                subAttempts.merge(scopeHex, 1, Int::plus)
                // The daemon's store rule: a scope it does not yet hold, past the cap, is refused `quota`
                // with the SUB's `q` and the scope — the un-correlated, scoped `err` of §7.2.
                val quota = scopeQuota
                if (quota != null && !scopes.containsKey(scopeHex) && scopes.size >= quota) {
                    emit(
                        SpoolCodec.encode(
                            SpoolErr(t = SpoolRecordType.ERR, code = SpoolErrCode.QUOTA, scope = entry.scope, retryMs = quotaRetryMs),
                        ),
                    )
                    return@forEach
                }
                subStamped.add(scopeHex to (entry.pow != null))
                if (powRefused(entry.scope, entry.pow)) {
                    emit(SpoolCodec.encode(SpoolErr(t = SpoolRecordType.ERR, code = SpoolErrCode.POW, scope = entry.scope)))
                    return@forEach
                }
                entry.pow?.let { stamps[scopeHex] = it }
                declaredBounds[scopeHex] = entry.bounds
                // Check-and-park under the spool's monitor: `handle` runs every record inside synchronized(this@FakeSpool),
                // the monitor [holdSubscriptions] and [releaseSubscriptions] take, so a release cannot slip between them.
                if (scopeHex in heldSubScopes) {
                    parkedSubs.add(this to entry.scope)
                    return@forEach
                }
                completeSub(entry.scope)
            }
        }

        /** Takes the subscription and answers it with the scope's digest. */
        fun completeSub(scope: ByteArray) {
            val scopeHex = hex(scope)
            subscribed.add(scopeHex)
            subscribedScopes.add(scopeHex)
            scopes.getOrPut(scopeHex) { ScopeState() }
            emit(SpoolCodec.encode(digestFor(scope)))
        }

        private fun onList(list: SpoolList) {
            val state = scopes[hex(list.scope)] ?: ScopeState()
            emit(
                SpoolCodec.encode(
                    SpoolList(
                        t = SpoolRecordType.LIST,
                        q = list.q,
                        scope = list.scope,
                        blobIds = state.live.keys.map(::unhex) + synthetic(listingPadding, salt = 1),
                        tombstones = state.tombstones.map(::unhex) + synthetic(tombstonePadding, salt = 2),
                    ),
                ),
            )
        }

        /** [n] well-formed ids nobody can pull — a hostile listing's padding. */
        private fun synthetic(
            n: Int,
            salt: Int,
        ): List<ByteArray> = List(n) { i -> sha256(byteArrayOf(salt.toByte(), (i shr 8).toByte(), i.toByte())) }

        private fun onPull(pull: SpoolPull) {
            val state = scopes[hex(pull.scope)] ?: ScopeState()
            val missing = mutableListOf<ByteArray>()
            // The daemon truncates an overshoot rather than erroring; ids past the cap appear in neither
            // the blobs nor `missing`, so a client that overshoots must re-pull the remainder.
            pull.blobIds.take(maxPull).forEach { id ->
                pulled.add(hex(id))
                val data = state.live[hex(id)]
                if (data == null) {
                    missing.add(id)
                } else {
                    emit(SpoolCodec.encode(SpoolBlob(t = SpoolRecordType.BLOB, scope = pull.scope, blobId = id, data = data)))
                }
            }
            emit(SpoolCodec.encode(SpoolOk(t = SpoolRecordType.OK, q = pull.q, missing = missing.ifEmpty { null })))
        }

        private fun onPush(push: SpoolPush) {
            val scopeHex = hex(push.scope)
            if (powRefused(push.scope, push.pow)) {
                emit(SpoolCodec.encode(SpoolErr(t = SpoolRecordType.ERR, code = SpoolErrCode.POW, q = push.q, scope = push.scope)))
                return
            }
            val state = scopes.getOrPut(scopeHex) { ScopeState() }
            val idHex = hex(push.blobId)
            val refusal =
                when {
                    !sha256(push.data).contentEquals(push.blobId) -> SpoolErrCode.BAD_ID
                    push.data.size > maxBlob -> SpoolErrCode.TOO_LARGE
                    idHex in state.tombstones -> SpoolErrCode.TOMBSTONED
                    else -> null
                }
            if (refusal != null) {
                emit(SpoolCodec.encode(SpoolErr(t = SpoolRecordType.ERR, code = refusal, q = push.q, scope = push.scope)))
                return
            }
            val fresh = state.live.put(idHex, push.data) == null
            if (fresh) pushed.add(idHex)
            while (state.live.size > maxFramesFor(scopeHex)) {
                val eldest = state.live.keys.first()
                state.live.remove(eldest)
                state.tombstones.add(eldest)
            }
            emit(SpoolCodec.encode(SpoolOk(t = SpoolRecordType.OK, q = push.q)))
            // Live fan-out to every *other* subscriber; a duplicate push never re-fans.
            if (fresh) {
                synchronized(sockets) { sockets.toList() }
                    .filterNot { it === this }
                    .forEach { it.deliverEvent(push.scope, idHex, push.data) }
            }
        }

        private fun onEnt(record: SpoolEnt) {
            ents.add(record)
            if (holdEnts) {
                heldEnts.add { synchronized(this@FakeSpool) { answerEnt(record) } }
                return
            }
            answerEnt(record)
        }

        private fun answerEnt(record: SpoolEnt) {
            val word = entAnswer(record)
            if (word != null) {
                emit(SpoolCodec.encode(SpoolErr(t = SpoolRecordType.ERR, code = SpoolErrCode.TICKET, q = record.q, msg = word)))
                return
            }
            ticket = SpoolCodec.decode<SpoolTicket>(record.tk)
            ticketPs = psHex(record.tk)
            emit(SpoolCodec.encode(SpoolOk(t = SpoolRecordType.OK, q = record.q)))
        }

        private fun onTurnReq(req: SpoolTurnReq) {
            val held = ticket
            if (gated(SpoolTicket.FL_TURN)) {
                val refusal =
                    when {
                        held == null -> "missing"
                        held.fl and SpoolTicket.FL_TURN == 0L -> "flags"
                        else -> null
                    }
                if (refusal != null) {
                    turnRefusals.add(refusal)
                    emit(SpoolCodec.encode(SpoolErr(t = SpoolRecordType.ERR, code = SpoolErrCode.TICKET, q = req.q, msg = refusal)))
                    return
                }
            }
            // A TURN ticket rides the issue whether or not `turn` is gated (h6pt: it still waives the subscription gate).
            if (held != null && held.fl and SpoolTicket.FL_TURN != 0L) turnIssuedTicketed++
            turnIssued++
            turnHostsAsked.add(req.host)
            val server = turnHosts?.let { listed -> req.host?.takeIf { it in listed } ?: listed.first() }
            emit(
                SpoolCodec.encode(
                    SpoolTurnCred(
                        t = SpoolRecordType.TURNCRED,
                        q = req.q,
                        urls = listOf(server?.let { "turn:$it?transport=udp" } ?: "turn:spool.test:3478?transport=udp"),
                        user = "1800000000:fake",
                        pass = "fake-pass",
                        exp = 1_800_000_000L,
                    ),
                ),
            )
        }

        /**
         * The daemon's `handleCast`: never answered, never stored. Dropped unless this connection subscribed the scope,
         * the data fits, and — on a ticketed spool — some subscriber of the scope holds a cast-flagged ticket; then
         * relayed unchanged to every other subscriber.
         */
        private fun onCast(record: SpoolCast) {
            val scopeHex = hex(record.scope)
            casts.add(scopeHex)
            val bounds = cast ?: return
            if (scopeHex !in subscribed || record.data.size > bounds.maxBytes) return
            val others = synchronized(sockets) { sockets.toList() }.filter { scopeHex in it.subscribed }
            if (gated(SpoolTicket.FL_CAST) && others.none { (it.ticket?.fl ?: 0L) and SpoolTicket.FL_CAST != 0L }) return
            val relayed = SpoolCodec.encode(SpoolCast(t = SpoolRecordType.CAST, scope = record.scope, data = record.data))
            others.filterNot { it === this }.forEach {
                it.emit(relayed)
                castsFanned++
            }
        }

        /**
         * The daemon's sig handling (S-7.8-3): never answered, never stored, no ticket asked. Dropped unless this
         * connection subscribed the scope, the data fits and the scope is no commons; then relayed unchanged to every
         * other subscriber.
         */
        private fun onSig(record: SpoolSig) {
            val scopeHex = hex(record.scope)
            sigs.add(scopeHex)
            val bounds = sig ?: return
            if (scopeHex !in subscribed || record.data.size > bounds.maxBytes || roomFor(scopeHex) != null) return
            val relayed = SpoolCodec.encode(SpoolSig(t = SpoolRecordType.SIG, scope = record.scope, data = record.data))
            synchronized(sockets) { sockets.toList() }.filter { it !== this && scopeHex in it.subscribed }.forEach {
                it.emit(relayed)
                sigsFanned++
            }
        }

        /** A v1 spool skips an unknown record without answering — the stall a client must never provoke. */
        private fun skip(type: String) {
            skippedRecords.add(type)
        }

        /** S-7.4-5: an attachment record naming a room whose entry said no `attach` is answered `err malformed`. */
        private fun refusesRoomAttachment(
            scope: ByteArray,
            q: Long,
        ): Boolean {
            val room = roomFor(hex(scope)) ?: return false
            if (room.attach) return false
            refusedRoomAttachments.add(hex(scope))
            emit(SpoolCodec.encode(SpoolErr(t = SpoolRecordType.ERR, code = SpoolErrCode.MALFORMED, q = q, scope = scope)))
            return true
        }

        private fun onAhave(ahave: SpoolAhave) {
            val aidHex = hex(ahave.aid)
            presenceAsks.add(aidHex)
            if (refusesRoomAttachment(ahave.scope, ahave.q)) return
            val state = scopes.getOrPut(hex(ahave.scope)) { ScopeState() }
            val held = state.attachments[aidHex]
            val dead = aidHex in state.attachTombstones
            emit(
                SpoolCodec.encode(
                    SpoolAhas(
                        t = SpoolRecordType.AHAS,
                        q = ahave.q,
                        scope = ahave.scope,
                        aid = ahave.aid,
                        total = if (dead || held == null) 0 else held.total,
                        bits = if (dead || held == null) ByteArray(0) else ScopeAttachments.bitmap(held.chunks.keys, held.total),
                        dead = dead,
                    ),
                ),
            )
        }

        private fun onAget(aget: SpoolAget) {
            chunkGets.add("${hex(aget.aid)}:${aget.from}+${aget.n}")
            if (refusesRoomAttachment(aget.scope, aget.q)) return
            val held = scopes[hex(aget.scope)]?.attachments?.get(hex(aget.aid))
            if (held != null) {
                val last = minOf(aget.from + minOf(aget.n, maxAget), held.total)
                for (index in aget.from until last) {
                    val data = held.chunks[index] ?: continue
                    emit(
                        SpoolCodec.encode(
                            SpoolAchunk(
                                t = SpoolRecordType.ACHUNK,
                                scope = aget.scope,
                                aid = aget.aid,
                                idx = index,
                                total = held.total,
                                cid = unhex(held.cids.getValue(index)),
                                data = data,
                            ),
                        ),
                    )
                }
            }
            emit(SpoolCodec.encode(SpoolOk(t = SpoolRecordType.OK, q = aget.q)))
        }

        /**
         * The daemon's `filesAdmitted` (S-7.6-22, S-7.6-31): where `files` is gated, only this connection's own live files
         * ticket admits an upload, of an attachment within its `am`, while its pseudonym has room for the chunk's charge.
         * A refusal is `err ticket` with the record's `q` and `scope`, recorded, and stores nothing. Returns the pseudonym
         * to charge, or null to stop.
         */
        private fun filesAdmitted(aput: SpoolAput): String? {
            if (!gated(SpoolTicket.FL_FILES)) return ""
            val held = ticket
            val ps = ticketPs
            val reason =
                when {
                    held == null || ps == null -> "missing"

                    held.fl and SpoolTicket.FL_FILES == 0L -> "flags"

                    // S-7.6-31, after the ticket words and before the budget: the whole attachment's `total` against
                    // `⌊am / aChunkBytes⌋`, on every chunk, a re-sent one included. Nothing stored or charged.
                    held.am?.let { aput.total > it / ScopeCrypto.ATTACH_CHUNK_BYTES } == true -> "size"

                    refuseSizeOver?.let { aput.total > it } == true -> "size"

                    (uploadBudgets[ps] ?: 0L) +
                        chunkCharge(
                            aput.data.size,
                        ) > minOf(held.ab ?: ticketAttachBytes, ticketAttachBytes) -> "budget"

                    else -> return ps
                }
            aputRefusals.add(reason)
            emit(
                SpoolCodec.encode(
                    SpoolErr(t = SpoolRecordType.ERR, code = SpoolErrCode.TICKET, q = aput.q, scope = aput.scope, msg = reason),
                ),
            )
            return null
        }

        @Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod") // one guard per §6.5 rejection reason
        private fun onAput(aput: SpoolAput) {
            aputs.add("${hex(aput.aid)}:${aput.idx}")
            if (refusesRoomAttachment(aput.scope, aput.q)) return
            // The daemon's order: `not_subscribed` before the files gate, the gate before PoW and the store.
            if (hex(aput.scope) !in subscribed) {
                emit(
                    SpoolCodec.encode(
                        SpoolErr(t = SpoolRecordType.ERR, code = SpoolErrCode.NOT_SUBSCRIBED, q = aput.q, scope = aput.scope),
                    ),
                )
                return
            }
            val payer = filesAdmitted(aput) ?: return
            if (powRefused(aput.scope, aput.pow)) {
                emit(SpoolCodec.encode(SpoolErr(t = SpoolRecordType.ERR, code = SpoolErrCode.POW, q = aput.q, scope = aput.scope)))
                return
            }
            val state = scopes.getOrPut(hex(aput.scope)) { ScopeState() }
            val aidHex = hex(aput.aid)
            val cidHex = hex(aput.cid)
            val refusal =
                when {
                    !sha256(aput.data).contentEquals(aput.cid) -> SpoolErrCode.BAD_ID

                    aput.data.size > maxAChunk -> SpoolErrCode.TOO_LARGE

                    aidHex in state.attachTombstones -> SpoolErrCode.TOMBSTONED

                    state.attachments[aidHex]?.let { it.total != aput.total } == true -> SpoolErrCode.CONFLICT

                    state.attachments[aidHex]
                        ?.cids
                        ?.get(aput.idx)
                        ?.let { it != cidHex } == true -> SpoolErrCode.CONFLICT

                    else -> null
                }
            if (refusal != null) {
                emit(SpoolCodec.encode(SpoolErr(t = SpoolRecordType.ERR, code = refusal, q = aput.q, scope = aput.scope)))
                return
            }
            val held = state.attachments.getOrPut(aidHex) { Attachment(aput.total, state.attachments.size) }
            val fresh = held.chunks.put(aput.idx, aput.data) == null
            if (fresh) chunksPut.add("$aidHex:${aput.idx}")
            held.cids[aput.idx] = cidHex
            // Whole-attachment, oldest-first eviction once the scope's byte budget is exceeded (§6.5).
            while (state.attachments.values.sumOf { it.bytes } > maxAttachBytes) {
                val victim =
                    state.attachments.entries
                        .filter { it.key != aidHex }
                        .minByOrNull { it.value.arrivedAt }
                if (victim == null) {
                    state.attachments.remove(aidHex)
                    emit(SpoolCodec.encode(SpoolErr(t = SpoolRecordType.ERR, code = SpoolErrCode.QUOTA, q = aput.q, scope = aput.scope)))
                    return
                }
                state.attachments.remove(victim.key)
                state.attachTombstones.add(victim.key)
            }
            // Charged only once held: a duplicate or a refused chunk costs its uploader nothing.
            if (fresh && payer.isNotEmpty()) uploadBudgets.merge(payer, chunkCharge(aput.data.size), Long::plus)
            emit(SpoolCodec.encode(SpoolOk(t = SpoolRecordType.OK, q = aput.q)))
        }

        private fun digestFor(scope: ByteArray): SpoolDigest = digestRecord(scope)
    }
}

// knit-spool `ScopeStore.chunkCharge`: a chunk costs at least 512 bytes of budget.
private fun chunkCharge(size: Int): Long = maxOf(size, 512).toLong()

// The one claim the client's `SpoolTicket` leaves out, read here as the daemon keys its budgets.
@Serializable
private class FakeTicketPs(
    val ps: ByteArray? = null,
)

private fun psHex(tk: ByteArray): String? = SpoolCodec.decode<FakeTicketPs>(tk)?.ps?.let(::hex)

/** Parses the lowercase-hex display form back to bytes. Test-side inverse of [hex]. */
fun unhex(value: String): ByteArray = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

/** An in-memory [ForwardStore] with the frame-global expiry rule the real repository enforces. */
class FakeCustody(
    private val ttlMs: Long = 24 * 60 * 60_000L,
) : ForwardStore {
    private val rows = ConcurrentHashMap<String, CarriedFrame>()
    private val swept = ConcurrentHashMap.newKeySet<String>()

    /**
     * Drops [id] and refuses it from then on — the real store's answer once a frame passes its
     * frame-global expiry. Models the 24 h custody sweep while a 48 h scope still holds the blob.
     */
    fun sweep(id: String) {
        rows.remove(id)
        swept.add(id)
    }

    override suspend fun store(
        frame: CarriedFrame,
        origin: Int,
        now: Long,
    ): Boolean {
        if (frame.envelope.id in swept) return false
        if (frame.envelope.sentAt + ttlMs < now) return false
        rows.putIfAbsent(frame.envelope.id, frame)
        return true
    }

    /** How many times the plane read the whole table — what an idle converged relay must not run up. */
    var liveFramesReads = 0
        private set

    override suspend fun liveFrames(now: Long): List<CarriedFrame> {
        liveFramesReads++
        return rows.values.filter { it.envelope.sentAt + ttlMs >= now }
    }

    override suspend fun liveIds(now: Long): List<String> = liveFrames(now).map { it.envelope.id }

    override suspend fun recipientOf(id: String): String? = rows[id]?.envelope?.recipientId

    override suspend fun has(id: String): Boolean = rows.containsKey(id)

    override suspend fun remove(id: String) {
        rows.remove(id)
    }

    override suspend fun sweepExpired(now: Long): Int = 0

    override suspend fun attachmentsNeedingFetch(now: Long): Map<String, String> = emptyMap()
}

/**
 * Builds a v2-sealed DM chat frame — the only shape a DM scope carries (spec §4.4). [attachmentHash]
 * fills the cleartext reference an E2E frame has carried since DB v19, which is what the attachment
 * plane keys off.
 */
fun dmFrame(
    id: String,
    from: String,
    to: String,
    sentAt: Long = 1_000L,
    body: ByteArray = byteArrayOf(1, 2, 3),
    attachmentHash: String? = null,
    // Null by default, which is what this build emits since ADR 035: a sealed frame names the hash and
    // nothing else. Pass a value explicitly to model a frame from an OLDER peer that still sends one.
    attachmentMime: String? = null,
    // The cleartext ratchet header; a reset is one carrying an init and `FLAG_RESET` (ADR 2026-10.j67x).
    header: RatchetHeader = RatchetHeader(se = 1, ek = ByteArray(32) { 7 }, pe = 0, n = 0),
): CarriedFrame {
    val enc =
        EncEnvelope(
            v = EncEnvelope.VERSION_RATCHET,
            nonce = ByteArray(12) { it.toByte() },
            ct = body,
            keys = emptyList(),
            r = header,
        )
    val env =
        RelayEnvelope(
            type = FrameType.CHAT,
            id = id,
            senderId = from,
            sentAt = sentAt,
            recipientId = to,
            payload = WireCodec.encodePayload(ChatContent(enc = enc, attachmentHash = attachmentHash, attachmentMime = attachmentMime)),
        )
    val signed = WireCodec.encodeEnvelope(env)
    return CarriedFrame(envelope = env, sig = ByteArray(ScopeCrypto.SIG_BYTES) { id.hashCode().toByte() }, signed = signed)
}

/** Builds a v2 group-form chat frame — the sender-key header, no DM header (spec §4.4's group rule). */
fun groupChatFrame(
    id: String,
    from: String,
    groupId: String,
    members: List<String>,
    sentAt: Long = 1_000L,
    body: ByteArray = byteArrayOf(4, 5, 6),
    attachmentHash: String? = null,
    // Null by default, which is what this build emits since ADR 035: a sealed frame names the hash and
    // nothing else. Pass a value explicitly to model a frame from an OLDER peer that still sends one.
    attachmentMime: String? = null,
): CarriedFrame =
    carried(
        id,
        RelayEnvelope(
            type = FrameType.CHAT,
            id = id,
            senderId = from,
            sentAt = sentAt,
            group = GroupInfo(id = groupId, members = members, createdBy = members.first()),
            payload =
                WireCodec.encodePayload(
                    ChatContent(
                        attachmentHash = attachmentHash,
                        attachmentMime = attachmentMime,
                        enc =
                            EncEnvelope(
                                v = EncEnvelope.VERSION_RATCHET,
                                nonce = ByteArray(12) { it.toByte() },
                                ct = body,
                                keys = emptyList(),
                                g = GroupRatchetHeader(se = 1, n = 0),
                            ),
                    ),
                ),
        ),
    )

/**
 * Builds a signed `groupleave`. Deliberately mirrors `MeshManager.sendGroupLeave`: the group id rides in
 * the PAYLOAD and `RelayEnvelope.group` stays null, which is the case the group frame-set rule has to
 * read specially.
 */
fun groupLeaveFrame(
    id: String,
    from: String,
    groupId: String,
    sentAt: Long = 1_000L,
): CarriedFrame =
    carried(
        id,
        RelayEnvelope(
            type = FrameType.GROUP_LEAVE,
            id = id,
            senderId = from,
            sentAt = sentAt,
            payload = WireCodec.encodePayload(GroupLeaveContent(groupId)),
        ),
    )

/** Builds a `groupupdate` (the roster rides in `group`; no per-type content, as MeshManager sends it). */
fun groupUpdateFrame(
    id: String,
    from: String,
    groupId: String,
    members: List<String>,
    sentAt: Long = 1_000L,
    photoHash: String? = null,
): CarriedFrame =
    carried(
        id,
        RelayEnvelope(
            type = FrameType.GROUP_UPDATE,
            id = id,
            senderId = from,
            sentAt = sentAt,
            group = GroupInfo(id = groupId, members = members, createdBy = members.first(), photoHash = photoHash),
            payload = ByteArray(0),
        ),
    )

/**
 * Builds a cleartext `profile`. Mirrors `MeshManager.currentProfileEnvelope`: it addresses nobody
 * (`recipientId` and `group` both null), [sentAt] is the publish stamp rather than the profile version,
 * and the version travels in the payload. The `pubKey` is left null — the frame-set rule never reads it
 * (self-certification is `InboundPipeline.canCarry`'s job, deliberately not re-implemented in
 * `ScopeFrames`), so a fixture that omits it still exercises the rule exactly.
 */
fun profileFrame(
    id: String,
    from: String,
    sentAt: Long = 1_000L,
    version: Long = 1L,
    name: String = "peer",
): CarriedFrame =
    carried(
        id,
        RelayEnvelope(
            type = FrameType.PROFILE,
            id = id,
            senderId = from,
            sentAt = sentAt,
            payload = WireCodec.encodePayload(ProfileContent(name = name, status = "", version = version)),
        ),
    )

/**
 * Builds a commons post (spec §7.4): a `commons` frame naming [scope], addressed to nobody, carrying a
 * cleartext [ChatContent] inside what will be the room's seal. Like [profileFrame] the signature is a
 * fixture — authentication is the injected carry gate's job, not the frame-set rule's.
 */
fun commonsFrame(
    id: String,
    from: String,
    scope: ByteArray,
    sentAt: Long = 1_000L,
    body: String = "hello room",
    // An attachment the post names, with its type and its own key beside it (§7.4).
    attachmentHash: String? = null,
    attachmentMime: String? = attachmentHash?.let { "image/webp" },
    attachmentKey: String? = attachmentHash?.let { "a2V5" },
): CarriedFrame =
    carried(
        id,
        RelayEnvelope(
            type = FrameType.COMMONS,
            id = id,
            senderId = from,
            sentAt = sentAt,
            payload =
                WireCodec.encodePayload(
                    CommonsPost(
                        scope = scope,
                        chat =
                            ChatContent(
                                body = body,
                                attachmentHash = attachmentHash,
                                attachmentMime = attachmentMime,
                                attachmentKey = attachmentKey,
                            ),
                    ),
                ),
        ),
    )

/** A member's reaction in a commons (C-7.4-13): a `commons` frame carrying a [ReactionContent] in place of a chat. */
fun commonsReactionFrame(
    id: String,
    from: String,
    scope: ByteArray,
    messageId: String,
    emoji: String?,
    sentAt: Long = 1_000L,
): CarriedFrame =
    carried(
        id,
        RelayEnvelope(
            type = FrameType.COMMONS,
            id = id,
            senderId = from,
            sentAt = sentAt,
            payload = WireCodec.encodePayload(CommonsPost(scope = scope, reaction = ReactionContent(messageId, emoji))),
        ),
    )

private fun carried(
    id: String,
    env: RelayEnvelope,
) = CarriedFrame(
    envelope = env,
    sig = ByteArray(ScopeCrypto.SIG_BYTES) { id.hashCode().toByte() },
    signed = WireCodec.encodeEnvelope(env),
)

/** How long a black-holed socket stays silent before the fake dialer gives up on it. */
private const val BLACKHOLE_MS = 500L

/**
 * What a muted spool still handles: the handshake and the SUB (so it reads as connected), and casts and sigs, which
 * are never answered, so a silent spool has nothing to swallow there.
 */
private val UNSWALLOWED = setOf(SpoolRecordType.HELLO, SpoolRecordType.SUB, SpoolRecordType.CAST, SpoolRecordType.SIG)
