package app.getknit.knit.data.forward

import app.getknit.knit.mesh.CarriedFrame
import app.getknit.knit.mesh.ForwardStore
import app.getknit.knit.mesh.StoreDigest
import app.getknit.knit.mesh.protocol.ChatContent
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.WireCodec

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
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val broadcastTtlMs: Long = DEFAULT_BROADCAST_TTL_MS,
    private val maxRows: Int = DEFAULT_MAX_ROWS,
    private val maxPerSender: Int = DEFAULT_MAX_PER_SENDER,
    private val maxPerGroup: Int = DEFAULT_MAX_PER_GROUP,
    private val maxBroadcast: Int = DEFAULT_MAX_BROADCAST,
) : ForwardStore {
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
        dao.insert(
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
            ),
        )
        // Enforce each bounded bucket by evicting its *oldest-by-sentAt* rows rather than refusing the new one,
        // and apply the quotas to our own sends (ORIGIN_SELF) too. sentAt is a frame-global key, so every node —
        // originator and every carrier — keeps the identical newest-N set and their content digests converge.
        // The old scheme (refuse-when-full, and never cap our own outbox) let an originator that authored more
        // than a quota's worth of custodial frames hold rows a capped carrier could never accept, so the
        // cue-plane digests never matched and the mesh churned NDPs forever — observed with a 117-frame sender
        // against the 100 per-sender quota. Trim order is fixed (sender, group, broadcast, global) so it's
        // identical on every node. A just-stored frame older than the bucket's newest-N is evicted here too,
        // which is correct: we converge on the newest-N regardless of arrival order. Counts and evictions are
        // live-filtered (`expiresAt >= now`): buckets mix TTL classes, so an expired-unswept row can be newer
        // by sentAt than a live one — counting it would evict a live frame on the unswept node only, and which
        // rows are evicted would then depend on the local sweep phase, not a frame-global rule (work item #8).
        var evicted = trim(dao.countBySender(env.senderId, now) - maxPerSender) { dao.evictOldestBySender(env.senderId, it, now) }
        if (groupId != null) {
            evicted = trim(dao.countByGroup(groupId, now) - maxPerGroup) { dao.evictOldestByGroup(groupId, it, now) } || evicted
        }
        if (isBroadcastChat) {
            evicted = trim(dao.countBroadcast(now) - maxBroadcast) { dao.evictOldestBroadcast(it, now) } || evicted
        }
        evicted = trim(dao.count(now) - maxRows) { dao.evictOldest(it, now) } || evicted
        // Eviction removed ids we don't track individually here, so rebuild the fingerprint wholesale; otherwise
        // fold the single new id in incrementally (the hot path when no bucket is over quota).
        if (evicted) rebuildDigest(now) else digest.add(env.id, expiresAt, now)
        return true
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
        dao.delete(id)
        digest.remove(id)
    }

    override suspend fun sweepExpired(now: Long): Int {
        val removed = dao.deleteExpired(now)
        // Runs at startup, periodically, and on heartbeat — also our reliable point to (re)sync the digest to
        // the table (initialises it after a restart, and reconciles any drift from eviction). Digest-neutral by
        // construction: every row the delete reclaimed was already outside the live fold, so the sweep is pure
        // storage GC and its per-node tick phase can no longer open a divergence window (work item #8).
        rebuildDigest(now)
        return removed
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
