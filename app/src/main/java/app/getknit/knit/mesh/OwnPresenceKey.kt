package app.getknit.knit.mesh

/**
 * This phone's current presence key as the key engine published it (ADR 2026-11.xp6d, step 5b): the key the Bluetooth
 * plane derives its advert tags from ([PresenceKeyEngine.ownKey], forwarded by `MeshManager` through
 * [MeshTransport.onOwnPresenceKey]). Two are equal exactly when they name the same generation with the same key bytes,
 * so a feed that re-publishes an unchanged key is no change; nothing else about a key row (its mark, its audience)
 * takes part.
 *
 * Never logged: [toString] shows the generation alone, like `PresenceKeyRepository.ContactKey` and
 * `PresenceKeyPolicy.Own`. Treat [key] as read-only; it is the engine's row, not a copy.
 */
class OwnPresenceKey(
    val generation: Long,
    val key: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is OwnPresenceKey && other.generation == generation && other.key.contentEquals(key)

    override fun hashCode(): Int = generation.hashCode()

    override fun toString(): String = "OwnPresenceKey(g=$generation)"
}
