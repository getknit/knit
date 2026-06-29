package app.getknit.knit.data.forward

import app.getknit.knit.mesh.ForwardStore
import app.getknit.knit.mesh.protocol.ChatFrame
import app.getknit.knit.mesh.protocol.WireCodec

/**
 * [ForwardStore] backed by the encrypted `forward_store` table. Persists carried DM frames as canonical
 * CBOR and enforces the store's bounds so epidemic carry can't grow without limit:
 *
 *  - a per-message **TTL** ([ttlMs]) after which the TTL sweep reclaims a frame even if never acked
 *    (the only purge path for group/broadcast — which we don't carry — and the backstop for DMs);
 *  - a **per-sender quota** ([maxPerSender]) so one identity (Sybil is cheap on this mesh) can't fill
 *    the buffer with junk we'd re-offer to everyone;
 *  - a **global cap** ([maxRows]) with oldest-first, relayed-before-our-own eviction.
 */
class ForwardRepository(
    private val dao: ForwardDao,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val maxRows: Int = DEFAULT_MAX_ROWS,
    private val maxPerSender: Int = DEFAULT_MAX_PER_SENDER,
) : ForwardStore {

    override suspend fun store(frame: ChatFrame, origin: Int, now: Long) {
        val recipientId = frame.recipientId ?: return // only DMs are carried
        // Quota applies to traffic we relay for others, not our own outbox.
        if (origin == ForwardStore.ORIGIN_RELAY && dao.countBySender(frame.senderId) >= maxPerSender) return
        dao.insert(
            ForwardEntity(
                id = frame.id,
                recipientId = recipientId,
                senderId = frame.senderId,
                origin = origin,
                bytes = WireCodec.encode(frame),
                receivedAt = now,
                expiresAt = now + ttlMs,
            ),
        )
        val overflow = dao.count() - maxRows
        if (overflow > 0) dao.evictOldest(overflow)
    }

    override suspend fun liveFrames(now: Long): List<ChatFrame> =
        dao.liveBytes(now).mapNotNull { WireCodec.decode(it) as? ChatFrame }

    override suspend fun recipientOf(id: String): String? = dao.recipientOf(id)

    override suspend fun has(id: String): Boolean = dao.exists(id)

    override suspend fun remove(id: String) = dao.delete(id)

    override suspend fun sweepExpired(now: Long): Int = dao.deleteExpired(now)

    private companion object {
        /** Carry a DM for at most 24h before the TTL sweep reclaims it. */
        const val DEFAULT_TTL_MS = 24 * 60 * 60_000L

        /** Cap total carried frames; oldest relayed traffic is evicted first. */
        const val DEFAULT_MAX_ROWS = 500

        /** Cap carried frames per sender, so one identity can't monopolize the buffer. */
        const val DEFAULT_MAX_PER_SENDER = 100
    }
}
