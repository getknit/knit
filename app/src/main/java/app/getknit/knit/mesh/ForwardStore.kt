package app.getknit.knit.mesh

import app.getknit.knit.mesh.protocol.RelayEnvelope

/**
 * A chat frame carried for store-and-forward: the decoded routing [envelope] plus the immutable,
 * re-floodable core — the frame [signed] bytes and their [sig]. The throwaway routing counters
 * (ttl/hops) are deliberately NOT stored: a fresh [app.getknit.knit.mesh.protocol.WireEnvelope] wrapper
 * is stamped around [signed]/[sig] each time the frame is re-served, so it re-floods with a full hop
 * budget. A plain class (it holds [ByteArray]s); identity is the frame id ([envelope].id).
 */
class CarriedFrame(
    val envelope: RelayEnvelope,
    val sig: ByteArray,
    val signed: ByteArray,
)

/**
 * Durable store of chat frames carried for store-and-forward, abstracted so [ForwardSync] stays free of
 * Android/Room (and unit-testable). The app's implementation is backed by the encrypted database (see
 * `ForwardRepository`); methods are `suspend` because they read/write it. DM, group, and broadcast-room
 * chat frames are stored (all signed, so a carrier authenticates without decrypting); a DM carries a
 * cleartext recipient to deliver toward, a group its roster, and a broadcast neither (offered to all).
 */
interface ForwardStore {

    /**
     * Persists [frame] seen at [now], tagged [origin] ([ORIGIN_SELF]/[ORIGIN_RELAY]). The implementation
     * stamps a TTL and enforces the storage caps; a frame whose id is already held is ignored.
     */
    suspend fun store(frame: CarriedFrame, origin: Int, now: Long)

    /** Non-expired carried frames (at [now]), newest first, for re-offering to a freshly-joined neighbor. */
    suspend fun liveFrames(now: Long): List<CarriedFrame>

    /** Ids of the non-expired carried frames (at [now]) — advertised to a neighbor for the data-path id-diff. */
    suspend fun liveIds(now: Long): List<String>

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
