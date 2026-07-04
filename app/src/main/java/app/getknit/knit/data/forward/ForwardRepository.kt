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
    ) {
        val env = frame.envelope
        val groupId = env.group?.id
        // Only broadcast-room *chat* is the ambient, higher-volume, shorter-lived class. Reactions, receipts,
        // and profiles share its null recipient/group shape but are higher-value metadata, so they must be
        // bucketed apart (by type) — else they'd inherit the broadcast quota/TTL and be starved or expire early.
        val isBroadcastChat = env.type == FrameType.CHAT && env.recipientId == null && groupId == null
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
                // Broadcast-room chat is ambient, higher-volume, no-ack chatter, so it gets a shorter TTL than an
                // addressed DM/group message or a carried metadata frame (reaction/receipt/profile keep the full
                // TTL, so they never expire before the message/peer they describe).
                expiresAt = now + if (isBroadcastChat) broadcastTtlMs else ttlMs,
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
        // which is correct: we converge on the newest-N regardless of arrival order.
        var evicted = trim(dao.countBySender(env.senderId) - maxPerSender) { dao.evictOldestBySender(env.senderId, it) }
        if (groupId != null) {
            evicted = trim(dao.countByGroup(groupId) - maxPerGroup) { dao.evictOldestByGroup(groupId, it) } || evicted
        }
        if (isBroadcastChat) {
            evicted = trim(dao.countBroadcast() - maxBroadcast) { dao.evictOldestBroadcast(it) } || evicted
        }
        evicted = trim(dao.count() - maxRows) { dao.evictOldest(it) } || evicted
        // Eviction removed ids we don't track individually here, so rebuild the fingerprint wholesale; otherwise
        // fold the single new id in incrementally (the hot path when no bucket is over quota).
        if (evicted) digest.setMessages(dao.allIds()) else digest.add(env.id)
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
        // the table (initialises it after a restart, and reconciles any drift from eviction).
        digest.setMessages(dao.allIds())
        return removed
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
