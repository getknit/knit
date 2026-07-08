package app.getknit.knit.data.forward

import androidx.room.withTransaction
import app.getknit.knit.data.KnitDatabase
import app.getknit.knit.mesh.CarriedFrame
import app.getknit.knit.mesh.ForwardStore
import app.getknit.knit.mesh.StoreDigest
import app.getknit.knit.mesh.protocol.ChatContent
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.Protocol
import app.getknit.knit.mesh.protocol.WireCodec
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [ForwardStore] backed by the encrypted `forward_store` table. Persists carried DM, group, and
 * broadcast-room chat frames as canonical CBOR (signed blob + signature) and enforces the store's bounds
 * so epidemic carry can't grow without limit:
 *
 *  - a per-message **TTL** ([ttlMs] for DMs/groups, the shorter [broadcastTtlMs] for the ambient
 *    broadcast room) after which the sweep reclaims a frame even if never acked (the *only* purge path
 *    for group/broadcast messages — no reliable ack — and the backstop for DMs whose receipt never arrives);
 *  - a **per-sender quota** ([maxPerSender]) so one identity (Sybil is cheap on this mesh) can't fill
 *    the buffer with junk we'd re-offer to everyone;
 *  - a **per-group quota** ([maxPerGroup]) so one busy group can't monopolize the buffer either;
 *  - a **broadcast quota** ([maxBroadcast]) so ambient broadcast chatter can't crowd out addressed messages;
 *  - a **global cap** ([maxRows]) with oldest-first, relayed-before-our-own eviction.
 *
 * Every bound is evaluated over **live** rows only, and the digest folds only live ids — an expired-but-unswept
 * row is per-node state (sweep ticks phase independently), so letting it into any rule would de-converge the
 * carried sets; the sweep is pure storage GC (work item #8).
 */
class ForwardRepository(
    private val dao: ForwardDao,
    // Content digest of the carried set; kept in lockstep with the table so the transport can cue it and skip
    // no-op syncs. Maintained here (the one place that sees every mutation) rather than in the pure ForwardSync.
    private val digest: StoreDigest,
    private val db: KnitDatabase,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val broadcastTtlMs: Long = DEFAULT_BROADCAST_TTL_MS,
    private val maxRows: Int = DEFAULT_MAX_ROWS,
    private val maxPerSender: Int = DEFAULT_MAX_PER_SENDER,
    private val maxPerGroup: Int = DEFAULT_MAX_PER_GROUP,
    private val maxBroadcast: Int = DEFAULT_MAX_BROADCAST,
) : ForwardStore {
    // Serializes store/remove/sweepExpired so each in-memory [digest] update commits atomically with its
    // forward_store row write: a Room transaction can't enroll the in-memory digest, and a concurrent sweep's
    // wholesale rebuild would otherwise clobber an incremental add (leaving a live row absent from the digest
    // and breaking StoreDigest's `current() == liveFingerprint` invariant). These run off the inbound collector
    // too — own sends on viewModelScope / the notification scope, the sweep on the 10-min prune loop — so it is
    // real contention, not belt-and-suspenders. Held OUTER to db.withTransaction; the reverse deadlocks on
    // SQLCipher's single connection (a coroutine holding the connection while waiting on the mutex, vs. one
    // holding the mutex while waiting to BEGIN).
    private val mutex = Mutex()

    override suspend fun store(
        frame: CarriedFrame,
        origin: Int,
        now: Long,
    ): Boolean {
        val env = frame.envelope
        val groupId = env.group?.id
        // Only broadcast-room *chat* is the ambient, higher-volume, shorter-lived class. Reactions, receipts,
        // and profiles share its null recipient/group shape but are higher-value metadata, so they must be
        // bucketed apart (by type) — else they'd inherit the broadcast quota/TTL and be starved or expire early.
        val isBroadcastChat = env.type == FrameType.CHAT && env.recipientId == null && groupId == null
        // Broadcast-room chat is ambient, higher-volume, no-ack chatter, so it gets a shorter TTL than an
        // addressed DM/group message or a carried metadata frame (reaction/receipt/profile keep the full
        // TTL, so they never expire before the message/peer they describe). Expiry is keyed off the
        // frame-global sentAt (the originator's signed, node-identical clock), NOT local receivedAt, so every
        // node — originator and every carrier — expires the same frame at the same absolute instant. Keying it
        // off local arrival let a late joiner hold a frame long after the originator swept it; a still-live
        // peer then re-served it via the id-diff and it re-stored with a fresh full TTL, so broadcast/group
        // frames (bounded only by TTL — no ack) never died mesh-wide and the digests churned. Same
        // convergence rule as the sentAt-ordered quota eviction below.
        val expiresAt = env.sentAt + if (isBroadcastChat) broadcastTtlMs else ttlMs
        // Dead on arrival: a frame past its frame-global expiry is one every node has already dropped (or never
        // held). Persisting it would plant an expired row that the live-only content digest refuses to fold, so
        // it would be pure invisible residue — and the refusal is also what stops a skewed-clock peer's re-serve
        // from resurrecting a swept frame. Refuse so the caller skips the follow-on work (blob custody).
        if (expiresAt < now) return false
        // Future-dated cap — the upper complement of the dead-on-arrival guard above. `sentAt` is the sender's
        // self-attested, unverifiable clock AND the frame-global eviction key, so a frame future-dated past a
        // generous skew window would be un-sweepable (its expiry is far off) and the newest-by-`sentAt` in every
        // bucket, letting a handful of Sybil identities win every quota eviction and permanently displace honest
        // custody mesh-wide. Refuse it. Every node compares against its own `now` exactly like the DOA guard, so
        // an honest frame (sentAt ≈ now) passes on every node and only the attacker's future window closes.
        if (env.sentAt > now + Protocol.MAX_FUTURE_SKEW_MS) return false
        // Denormalize the image content hash so the carrier can pull+hold the blob and pin it against GC. This
        // decode is best-effort metadata ONLY — it must never gate the insert: a null (non-chat, no attachment,
        // or an unreadable payload) still stores the frame, so `forward_store` ids stay byte-identical mesh-wide
        // and the content digest converges (the digest folds only `id`, never this column).
        val attachmentHash =
            if (env.type == FrameType.CHAT) {
                WireCodec.decodePayload<ChatContent>(env.payload)?.attachmentHash
            } else {
                null
            }
        val row =
            ForwardEntity(
                id = env.id,
                recipientId = env.recipientId,
                groupId = groupId,
                senderId = env.senderId,
                type = env.type,
                origin = origin,
                signed = frame.signed,
                sig = frame.sig,
                sentAt = env.sentAt,
                receivedAt = now,
                attachmentHash = attachmentHash,
                expiresAt = expiresAt,
            )
        // The row insert + quota eviction (atomic under withTransaction) and the in-memory digest update are one
        // critical section (mutex). The digest update runs AFTER commit but still under the lock, so a rolled-back
        // transaction never leaves the digest ahead of the table and no concurrent store/sweep interleaves between
        // the commit and the update.
        mutex.withLock {
            val didEvict = db.withTransaction { insertAndTrim(row, isBroadcastChat, now) }
            // Eviction removed ids we don't track individually here, so rebuild the fingerprint wholesale;
            // otherwise fold the single new id in incrementally (the hot path when no bucket is over quota).
            if (didEvict) rebuildDigest(now) else digest.add(row.id, row.expiresAt, now)
        }
        return true
    }

    /**
     * Inserts [row] and trims each over-quota bucket to its newest-N by the frame-global sentAt, in the fixed
     * order (sender, group, broadcast, global) so every node keeps the identical set; returns whether any
     * eviction ran. Runs inside the caller's [db] transaction.
     *
     * Evict a bucket's *oldest-by-sentAt* rows rather than refusing the new one, and apply the quotas to our own
     * sends (ORIGIN_SELF) too. sentAt is a frame-global key, so every node — originator and every carrier — keeps
     * the identical newest-N set and their content digests converge. The old scheme (refuse-when-full, and never
     * cap our own outbox) let an originator that authored more than a quota's worth of custodial frames hold rows
     * a capped carrier could never accept, so the cue-plane digests never matched and the mesh churned NDPs
     * forever — observed with a 117-frame sender against the 100 per-sender quota. A just-stored frame older than
     * the bucket's newest-N is evicted here too, which is correct: we converge on the newest-N regardless of
     * arrival order. Counts and evictions are live-filtered (`expiresAt >= now`): buckets mix TTL classes, so an
     * expired-unswept row can be newer by sentAt than a live one — counting it would evict a live frame on the
     * unswept node only, and which rows are evicted would then depend on the local sweep phase, not a frame-global
     * rule (work item #8).
     */
    private suspend fun insertAndTrim(
        row: ForwardEntity,
        isBroadcastChat: Boolean,
        now: Long,
    ): Boolean {
        dao.insert(row)
        val senderId = row.senderId
        val groupId = row.groupId
        var evicted = trim(dao.countBySender(senderId, now) - maxPerSender) { dao.evictOldestBySender(senderId, it, now) }
        if (groupId != null) {
            evicted = trim(dao.countByGroup(groupId, now) - maxPerGroup) { dao.evictOldestByGroup(groupId, it, now) } || evicted
        }
        if (isBroadcastChat) {
            evicted = trim(dao.countBroadcast(now) - maxBroadcast) { dao.evictOldestBroadcast(it, now) } || evicted
        }
        return trim(dao.count(now) - maxRows) { dao.evictOldest(it, now) } || evicted
    }

    /** Evicts [over] rows via [evict] when a bucket exceeds its quota; returns whether anything was removed. */
    private suspend fun trim(
        over: Int,
        evict: suspend (Int) -> Unit,
    ): Boolean {
        if (over <= 0) return false
        evict(over)
        return true
    }

    override suspend fun liveFrames(now: Long): List<CarriedFrame> =
        dao.liveRows(now).mapNotNull { row ->
            WireCodec.decodeEnvelope(row.signed)?.let { CarriedFrame(it, row.sig, row.signed) }
        }

    override suspend fun liveIds(now: Long): List<String> = dao.liveIds(now)

    override suspend fun attachmentHashesNeedingFetch(): List<String> = dao.attachmentHashesNeedingFetch()

    override suspend fun recipientOf(id: String): String? = dao.recipientOf(id)

    override suspend fun has(id: String): Boolean = dao.exists(id)

    override suspend fun remove(id: String) {
        // Single delete needs no transaction, but the mutex keeps the digest.remove atomic with it and serial
        // against a concurrent store/sweep (see [mutex]).
        mutex.withLock {
            dao.delete(id)
            digest.remove(id)
        }
    }

    override suspend fun sweepExpired(now: Long): Int =
        mutex.withLock {
            val removed = dao.deleteExpired(now)
            // Runs at startup, periodically, and on heartbeat — also our reliable point to (re)sync the digest to
            // the table (initialises it after a restart, and reconciles any drift from eviction). Digest-neutral by
            // construction: every row the delete reclaimed was already outside the live fold, so the sweep is pure
            // storage GC and its per-node tick phase can no longer open a divergence window (work item #8).
            rebuildDigest(now)
            removed
        }

    /** Re-syncs the digest to the table's live `(id, expiresAt)` set — the only rebuild source. */
    private suspend fun rebuildDigest(now: Long) {
        digest.setMessages(dao.liveIdExpiries(now).map { it.id to it.expiresAt }, now)
    }

    private companion object {
        /** Carry a DM/group message for at most 24h before the TTL sweep reclaims it. */
        const val DEFAULT_TTL_MS = 24 * 60 * 60_000L

        /** Carry a broadcast-room message for a shorter window (ambient, higher-volume, no ack to purge it). */
        const val DEFAULT_BROADCAST_TTL_MS = 6 * 60 * 60_000L

        /** Cap total carried frames; oldest relayed traffic is evicted first. */
        const val DEFAULT_MAX_ROWS = 1_000

        /** Cap carried frames per sender, so one identity can't monopolize the buffer. */
        const val DEFAULT_MAX_PER_SENDER = 200

        /** Cap carried frames per group, so one busy group can't monopolize the buffer. */
        const val DEFAULT_MAX_PER_GROUP = 200

        /** Cap carried broadcast-room frames, so ambient chatter can't crowd out addressed messages. */
        const val DEFAULT_MAX_BROADCAST = 200
    }
}
