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
