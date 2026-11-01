package app.getknit.knit.data.peer

/**
 * A peer and the photo its newest profile advertises ([PeerEntity.advertisedAvatar]): the `(nodeId, hash)` projection
 * the database re-arm wants a face by (ADR 2026-11.4ckq). A Room POJO projection, not an entity: no schema.
 */
data class PeerAdvertised(
    val nodeId: String,
    val hash: String,
)
