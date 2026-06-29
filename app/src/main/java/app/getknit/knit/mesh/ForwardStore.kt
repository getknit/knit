package app.getknit.knit.mesh

import app.getknit.knit.mesh.protocol.ChatFrame

/**
 * Durable store of DM frames carried for store-and-forward, abstracted so [ForwardSync] stays free of
 * Android/Room (and unit-testable). The app's implementation is backed by the encrypted database (see
 * `ForwardRepository`); methods are `suspend` because they read/write it. Only DM [ChatFrame]s are ever
 * stored — they carry a cleartext recipient to deliver toward and an envelope signature to authenticate.
 */
interface ForwardStore {

    /**
     * Persists [frame] (already routing-reset by the caller) seen at [now], tagged [origin]
     * ([ORIGIN_SELF]/[ORIGIN_RELAY]). The implementation stamps a TTL and enforces the storage caps;
     * a frame whose id is already held is ignored.
     */
    suspend fun store(frame: ChatFrame, origin: Int, now: Long)

    /** Non-expired carried frames (at [now]), newest first, for re-offering to a freshly-joined neighbor. */
    suspend fun liveFrames(now: Long): List<ChatFrame>

    /** The carried frame [id]'s cleartext recipient, or null if not held. */
    suspend fun recipientOf(id: String): String?

    suspend fun has(id: String): Boolean

    suspend fun remove(id: String)

    /** Drops every frame whose TTL has elapsed by [now]; returns how many were removed. */
    suspend fun sweepExpired(now: Long): Int

    companion object {
        /** [store] origin: a frame relayed for others — shed first under cap pressure. */
        const val ORIGIN_RELAY = 0

        /** [store] origin: a frame this device authored — kept longest. */
        const val ORIGIN_SELF = 1
    }
}
