package app.getknit.knit.data.forward

import app.getknit.knit.mesh.CarriedFrame
import app.getknit.knit.mesh.ForwardStore
import app.getknit.knit.mesh.StoreDigest
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

    override suspend fun store(frame: CarriedFrame, origin: Int, now: Long) {
        val env = frame.envelope
        val groupId = env.group?.id
        // Only broadcast-room *chat* is the ambient, higher-volume, shorter-lived class. Reactions, receipts,
        // and profiles share its null recipient/group shape but are higher-value metadata, so they must be
        // bucketed apart (by type) — else they'd inherit the broadcast quota/TTL and be starved or expire early.
        val isBroadcastChat = env.type == FrameType.CHAT && env.recipientId == null && groupId == null
        // Quotas apply to traffic we relay for others, not our own outbox.
        if (origin == ForwardStore.ORIGIN_RELAY) {
            if (dao.countBySender(env.senderId) >= maxPerSender) return
            if (groupId != null && dao.countByGroup(groupId) >= maxPerGroup) return
            if (isBroadcastChat && dao.countBroadcast() >= maxBroadcast) return
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
                receivedAt = now,
                // Broadcast-room chat is ambient, higher-volume, no-ack chatter, so it gets a shorter TTL than an
                // addressed DM/group message or a carried metadata frame (reaction/receipt/profile keep the full
                // TTL, so they never expire before the message/peer they describe).
                expiresAt = now + if (isBroadcastChat) broadcastTtlMs else ttlMs,
            ),
        )
        digest.add(env.id)
        val overflow = dao.count() - maxRows
        if (overflow > 0) {
            dao.evictOldest(overflow)
            digest.setMessages(dao.allIds()) // eviction removed unknown ids → rebuild wholesale
        }
    }

    override suspend fun liveFrames(now: Long): List<CarriedFrame> =
        dao.liveRows(now).mapNotNull { row ->
            WireCodec.decodeEnvelope(row.signed)?.let { CarriedFrame(it, row.sig, row.signed) }
        }

    override suspend fun liveIds(now: Long): List<String> = dao.liveIds(now)

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
        const val DEFAULT_MAX_ROWS = 500

        /** Cap carried frames per sender, so one identity can't monopolize the buffer. */
        const val DEFAULT_MAX_PER_SENDER = 100

        /** Cap carried frames per group, so one busy group can't monopolize the buffer. */
        const val DEFAULT_MAX_PER_GROUP = 100

        /** Cap carried broadcast-room frames, so ambient chatter can't crowd out addressed messages. */
        const val DEFAULT_MAX_BROADCAST = 100
    }
}
