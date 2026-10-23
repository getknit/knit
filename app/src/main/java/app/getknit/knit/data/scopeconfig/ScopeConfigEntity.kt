package app.getknit.knit.data.scopeconfig

import androidx.room3.Entity
import androidx.room3.PrimaryKey

/**
 * One issuer's scope config for one conversation at one version (`CTL_SCOPE_CONFIG`, `docs/SPOOL_PROTOCOL.md` §5):
 * the rows `ScopeConfigPolicy` reads the conversation's winner from. An issuer keeps its newest readable row and the
 * deferred ones its version runs ahead of this phone's clock with (`ScopeConfigPolicy.retained`), so the key carries the
 * version. [conversationId] is the peer's node id for a DM and the
 * group's id for a group — never a scope id, which rotates with a root; [issuer] is the authenticated sender of the
 * frame that carried it (this phone's own id for a config it issued). [spools] is a JSON list of canonical `wss://`
 * URLs, any of which may carry a `?k=` bearer token, so a dump redacts it. [receivedAt] is this phone's clock when the
 * row was written, for diagnostics only: the order is `(version, issuer)` and nothing else.
 *
 * Carried in a backup (`BackupTables`): the config has no ack (C-5-6), so a restored phone could never ask for it again.
 */
@Entity(tableName = "scope_configs", primaryKeys = ["conversationId", "issuer", "version"])
data class ScopeConfigEntity(
    val conversationId: String,
    val issuer: String,
    val groupId: String?,
    val spools: String,
    val maxFrames: Int,
    val ttlMs: Long,
    val maxBlob: Int,
    val version: Int,
    val receivedAt: Long,
)

/**
 * A node that has sent this phone a scope config, of any conversation and whatever became of it ([at], this phone's
 * clock at the first one): proof it runs a build that speaks `CTL_SCOPE_CONFIG`. Per peer rather than per scope, so a
 * conversation can tell when every member of it can follow a config off the shared default relay (ADR 2026-10.8kfx).
 * Carried in a backup for the same reason as `scope_configs`.
 */
@Entity(tableName = "spool_declared")
data class SpoolDeclaredEntity(
    @PrimaryKey val nodeId: String,
    val at: Long,
)
