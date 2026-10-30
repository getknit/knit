package app.getknit.knit.data.ratchet

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * One DM ratchet session per peer (crypto scheme v2 — docs/FORWARD_SECRECY_RATCHET.md). Columns mirror
 * `RatchetEngine.SessionState` one-to-one; the repository maps between them. Key material lives in the
 * SQLCipher-encrypted DB — same at-rest posture as carried ciphertext and attachment keys. Sessions die
 * with a DB wipe by design (the identity + prekeys in `identity.key` survive); the reset path recovers.
 */
@Entity(tableName = "ratchet_sessions")
data class RatchetSessionEntity(
    @PrimaryKey val peerId: String,
    val confirmed: Boolean,
    val weAreInitiator: Boolean,
    val root: ByteArray,
    val prevRoot: ByteArray?,
    val prevRootWeAreInitiator: Boolean,
    val prevRootExpiresAt: Long,
    /**
     * Set exactly while [prevRoot] is our own init, abandoned unconfirmed on a lost race: that init's own
     * `establishedAt` (rule C, ADR 2026-10.4mjm). Null on every row before v29. Added by `MIGRATION_28_29`, so the
     * column sits last in a migrated table and here in a fresh one; Room matches columns by name.
     */
    val prevRootOwnInitAt: Long? = null,
    val establishedAt: Long,
    val initEphPub: ByteArray?,
    val initPkid: Int,
    val peerInitEphPub: ByteArray?,
    val peerBasePub: ByteArray?,
    val peerBaseEpoch: Int,
    val sendEpoch: Int,
    val sendEpochPub: ByteArray?,
    val sendChainKey: ByteArray?,
    val sendCount: Int,
    val sendEpochStartedAt: Long,
    val sendEpochBaseEpoch: Int,
    val sendEpochExport: ByteArray?,
    val highestPeAcked: Int,
    val lastResetSentAt: Long,
    val updatedAt: Long,
)

/**
 * One of OUR epoch keypairs for [peerId]'s session — the peer DHs new epochs against [pub], so [priv]
 * must survive until the epoch is superseded-and-acknowledged. **Deleting these rows is the
 * forward-secrecy guarantee** (see `RatchetRepository.sweep`).
 */
@Entity(
    tableName = "ratchet_local_epochs",
    primaryKeys = ["peerId", "epoch"],
    indices = [Index("createdAt")],
)
data class RatchetLocalEpochEntity(
    val peerId: String,
    val epoch: Int,
    val priv: ByteArray,
    val pub: ByteArray,
    val createdAt: Long,
)

/**
 * One inbound epoch chain from [peerId]: the forward-only [chainKey] positioned at index [next]. The chain
 * needs no root once derived — which is what lets a superseded session root expire while its epochs keep
 * decrypting late custody re-serves — but [rootTag] names the root that derived it (`RatchetCrypto.rootTag`,
 * ADR 2026-10.gcrd): a peer's reset restarts its epoch numbering, so two eras can hold the same [epoch], and
 * only the matching root's candidate may read this row. The tag sits last in the key so the any-era lookup by
 * `(peerId, epoch)` runs on the key's index.
 */
@Entity(
    tableName = "ratchet_recv_epochs",
    primaryKeys = ["peerId", "epoch", "rootTag"],
    indices = [Index("lastUsedAt")],
)
data class RatchetRecvEpochEntity(
    val peerId: String,
    val epoch: Int,
    val rootTag: Long,
    val chainKey: ByteArray,
    val next: Int,
    val lastUsedAt: Long,
)

/**
 * A stored out-of-order message key (single-use; consumed on decrypt, swept after 48 h), tagged with the root
 * that derived it like [RatchetRecvEpochEntity.rootTag].
 */
@Entity(
    tableName = "ratchet_skipped_keys",
    primaryKeys = ["peerId", "epoch", "idx", "rootTag"],
    indices = [Index("createdAt")],
)
data class RatchetSkippedKeyEntity(
    val peerId: String,
    val epoch: Int,
    val idx: Int,
    val rootTag: Long,
    val msgKey: ByteArray,
    val createdAt: Long,
)
