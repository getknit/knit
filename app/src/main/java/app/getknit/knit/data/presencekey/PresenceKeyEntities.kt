package app.getknit.knit.data.presencekey

import androidx.room3.Entity
import androidx.room3.PrimaryKey

/**
 * One generation of this phone's own presence key (ADR 2026-11.xp6d): the 32 bytes a blinded Bluetooth advert's tag is
 * a MAC under (`BlindTag`), and who was given it. At most three rows: the previous key, the current one (the newest
 * with [activatedAt] set) and the next one ([activatedAt] null: handed to contacts ahead, never aired yet).
 *
 * Local only, never in a backup (`BackupTables.TRANSIENT`): a restored phone mints afresh, and its generation numbers
 * stay above the old ones through the carried `presence_key_last_generation` setting. Keys are never logged.
 */
@Entity(tableName = "own_presence_keys")
data class OwnPresenceKeyEntity(
    @PrimaryKey val generation: Long,
    val key: ByteArray,
    /** The wall clock when the key was minted. Never the generation, which may run ahead of the clock. */
    val mintedAt: Long,
    /** The wall clock when the key became the one aired, or null while it is the next key. */
    val activatedAt: Long?,
    /** The node ids the key may be handed to, sorted and comma-joined (`PresenceKeyAudience`). */
    val audience: String,
    /**
     * The advertiser's high-water mark under this key (`TagSchedule.MarkStore`): the last (slot, restart) aired, so no
     * pair is aired twice. Null until the key is first aired.
     */
    val markSlot: Long? = null,
    val markRestart: Int? = null,
)

/**
 * A presence key a peer handed this phone over a link (`CTL_PRESENCE_KEY`, ADR 2026-11.xp6d): what lets this phone
 * resolve that peer's blinded advert. At most three generations per peer. [rootTag] is the ratchet root the key's frame
 * opened under (`RatchetCrypto.rootTag`, local storage only), which tells a new era (a restored sender) from a late copy.
 *
 * Local only, never in a backup (`BackupTables.TRANSIENT`): a backup must not carry the means to recognise every
 * contact, and a restore re-roots every session, so contacts hand their keys again. A row goes when its peer row does
 * (`PeerDao.deleteOrphanPresenceKeys`). Keys are never logged.
 */
@Entity(tableName = "peer_presence_keys", primaryKeys = ["nodeId", "generation"])
data class PeerPresenceKeyEntity(
    val nodeId: String,
    val generation: Long,
    val key: ByteArray,
    val receivedAt: Long,
    val rootTag: Long,
)

/**
 * A restore's session reset owed over a link (ADR 2026-11.xp6d, "Restore"): one row per direct contact the restore's
 * flooded resets do not cover (card-, QR- or accept-only, no DM thread, no shared group). [wire] is the reset's signed
 * `relay = false` frame as first sealed, null until sealed; [rootTag] the session root that seal committed (its init's
 * root), so a kept frame another reset has since replaced is never resent. Local only (`BackupTables.TRANSIENT`): it names
 * contacts a relay must never learn of, and it is about this phone's restore. Goes with the peer row.
 */
@Entity(tableName = "link_owed_resets")
data class LinkOwedResetEntity(
    @PrimaryKey val nodeId: String,
    val wire: ByteArray?,
    val rootTag: Long?,
    val owedSince: Long,
)

/** One key a peer handed us, without the key: what the debug bridge's `KEYS` lists ([PresenceKeyDao.peerKeyRows]). */
data class PeerKeyRow(
    val nodeId: String,
    val generation: Long,
    val receivedAt: Long,
)
