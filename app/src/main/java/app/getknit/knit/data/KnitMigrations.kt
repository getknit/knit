package app.getknit.knit.data

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * The registry of tested schema migrations applied in [KnitDatabase.build].
 *
 * **v1 is the frozen launch baseline.** There is no destructive fallback: from v1 onward every `@Database`
 * version bump MUST add a [Migration] here — a missing one makes Room throw at open time (caught by
 * `KnitDatabaseMigrationTest`) instead of silently wiping user data. So this is the single place production
 * migrations live: keep it in lockstep with `@Database(version = …)` and the checked-in
 * `app/schemas/**/<version>.json`, using the driver-based `migrate(SQLiteConnection)` override (matching
 * the `KnitDatabaseMigrationTest` harness), and fill in a migration-test case per bump.
 */
object KnitMigrations {
    /**
     * v2 — the ratchet schemes (docs/FORWARD_SECRECY_RATCHET.md + docs/GROUP_FORWARD_SECRECY.md, one
     * never-released bump): four `ratchet_*` DM-session tables, four `group_*` sender-key tables
     * (send/recv chains, skipped keys, the seed outbox), and the peer's published-prekey columns.
     * Additive only; the SQL must stay byte-equivalent to what Room generates for
     * `app/schemas/**/2.json` (validated by `runMigrationsAndValidate`).
     */
    val MIGRATION_1_2 =
        object : Migration(1, 2) {
            @Suppress("LongMethod") // a flat list of CREATE TABLE/INDEX statements; splitting would obscure the schema
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ratchet_sessions` (" +
                        "`peerId` TEXT NOT NULL, `confirmed` INTEGER NOT NULL, `weAreInitiator` INTEGER NOT NULL, " +
                        "`root` BLOB NOT NULL, `prevRoot` BLOB, `prevRootWeAreInitiator` INTEGER NOT NULL, " +
                        "`prevRootExpiresAt` INTEGER NOT NULL, `establishedAt` INTEGER NOT NULL, `initEphPub` BLOB, " +
                        "`initPkid` INTEGER NOT NULL, `peerInitEphPub` BLOB, `peerBasePub` BLOB, " +
                        "`peerBaseEpoch` INTEGER NOT NULL, `sendEpoch` INTEGER NOT NULL, `sendEpochPub` BLOB, " +
                        "`sendChainKey` BLOB, `sendCount` INTEGER NOT NULL, `sendEpochStartedAt` INTEGER NOT NULL, " +
                        "`sendEpochBaseEpoch` INTEGER NOT NULL, `sendEpochExport` BLOB, `highestPeAcked` INTEGER NOT NULL, " +
                        "`lastResetSentAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`peerId`))",
                )
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ratchet_local_epochs` (" +
                        "`peerId` TEXT NOT NULL, `epoch` INTEGER NOT NULL, `priv` BLOB NOT NULL, `pub` BLOB NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`peerId`, `epoch`))",
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_ratchet_local_epochs_createdAt` ON `ratchet_local_epochs` (`createdAt`)",
                )
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ratchet_recv_epochs` (" +
                        "`peerId` TEXT NOT NULL, `epoch` INTEGER NOT NULL, `chainKey` BLOB NOT NULL, `next` INTEGER NOT NULL, " +
                        "`lastUsedAt` INTEGER NOT NULL, PRIMARY KEY(`peerId`, `epoch`))",
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_ratchet_recv_epochs_lastUsedAt` ON `ratchet_recv_epochs` (`lastUsedAt`)",
                )
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ratchet_skipped_keys` (" +
                        "`peerId` TEXT NOT NULL, `epoch` INTEGER NOT NULL, `idx` INTEGER NOT NULL, `msgKey` BLOB NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`peerId`, `epoch`, `idx`))",
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_ratchet_skipped_keys_createdAt` ON `ratchet_skipped_keys` (`createdAt`)",
                )
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `group_send_chains` (" +
                        "`groupId` TEXT NOT NULL, `epoch` INTEGER NOT NULL, `seed` BLOB NOT NULL, " +
                        "`chainKey` BLOB NOT NULL, `count` INTEGER NOT NULL, `mintedAt` INTEGER NOT NULL, " +
                        "`export` BLOB NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`groupId`, `epoch`))",
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_group_send_chains_mintedAt` ON `group_send_chains` (`mintedAt`)",
                )
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `group_recv_chains` (" +
                        "`groupId` TEXT NOT NULL, `senderId` TEXT NOT NULL, `epoch` INTEGER NOT NULL, " +
                        "`mintedAt` INTEGER NOT NULL, `chainKey` BLOB NOT NULL, `next` INTEGER NOT NULL, " +
                        "`lastUsedAt` INTEGER NOT NULL, PRIMARY KEY(`groupId`, `senderId`, `epoch`, `mintedAt`))",
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_group_recv_chains_lastUsedAt` ON `group_recv_chains` (`lastUsedAt`)",
                )
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `group_skipped_keys` (" +
                        "`groupId` TEXT NOT NULL, `senderId` TEXT NOT NULL, `epoch` INTEGER NOT NULL, " +
                        "`mintedAt` INTEGER NOT NULL, `idx` INTEGER NOT NULL, `msgKey` BLOB NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`groupId`, `senderId`, `epoch`, `mintedAt`, `idx`))",
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_group_skipped_keys_createdAt` ON `group_skipped_keys` (`createdAt`)",
                )
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `group_key_sends` (" +
                        "`groupId` TEXT NOT NULL, `memberId` TEXT NOT NULL, `sentEpoch` INTEGER NOT NULL, " +
                        "`sentAt` INTEGER NOT NULL, `ackedEpoch` INTEGER NOT NULL, `ackedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`groupId`, `memberId`))",
                )
                connection.execSQL("ALTER TABLE `peers` ADD COLUMN `prekeyId` INTEGER")
                connection.execSQL("ALTER TABLE `peers` ADD COLUMN `prekeyPub` TEXT")
                connection.execSQL("ALTER TABLE `peers` ADD COLUMN `prekeySig` TEXT")
                connection.execSQL("ALTER TABLE `peers` ADD COLUMN `prekeyProfileAt` INTEGER")
            }
        }

    /**
     * v3 — the spool plane's group scopes (docs/SPOOL_PROTOCOL.md §3.2): one `group_roots` table holding
     * the shared group root the group scope id and seal keys derive from, the retiring lineage's drain
     * window, and the two idempotent stamps (mint grace, re-mint due). Purely local state — the wire
     * change that accompanies it (`GroupKeyPayload.gr`) is additive and breaks nothing. Additive only;
     * the SQL must stay byte-equivalent to what Room generates for `app/schemas/**/3.json`.
     */
    val MIGRATION_2_3 =
        object : Migration(2, 3) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `group_roots` (" +
                        "`groupId` TEXT NOT NULL, `root` BLOB, `version` INTEGER NOT NULL, `minter` TEXT NOT NULL, " +
                        "`prevRoot` BLOB, `prevVersion` INTEGER NOT NULL, `prevExpiresAt` INTEGER NOT NULL, " +
                        "`firstEligibleAt` INTEGER NOT NULL, `remintDueAt` INTEGER NOT NULL, PRIMARY KEY(`groupId`))",
                )
            }
        }

    /**
     * v4 — the delivery tick's plane: one `messages.receivedVia` column holding the `DeliveryPlane` code the
     * receipt that flipped `received` arrived on (the globe beside the ✓✓ marks the Internet plane). Purely
     * local presentation state — no wire change, and an upgraded device's already-acked messages default to
     * 0 = `DeliveryPlane.Unknown`, which is honest: the plane wasn't recorded when they were acked, and the
     * UI shows nothing for it. Additive only; the SQL must stay byte-equivalent to what Room generates for
     * `app/schemas/**/4.json`.
     */
    val MIGRATION_3_4 =
        object : Migration(3, 4) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE `messages` ADD COLUMN `receivedVia` INTEGER NOT NULL DEFAULT 0")
            }
        }

    /**
     * v5 — voice notes: two `messages` columns describing a voice-note attachment, `voiceDurationMs` and the
     * Base64 `voicePeaks` waveform. Purely local presentation state — both are derived from the audio bytes
     * themselves by [app.getknit.knit.data.VoiceAudio], on the sender at ingest and on the recipient once the
     * blob lands, so nothing about a voice note travels on the wire that an image didn't already. Existing
     * rows get null, which is correct rather than merely tolerable: a message that predates this column has
     * no voice attachment, and the derivation re-runs for any that somehow does. Additive only; the SQL must
     * stay byte-equivalent to what Room generates for `app/schemas/**/5.json`.
     */
    val MIGRATION_4_5 =
        object : Migration(4, 5) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE `messages` ADD COLUMN `voiceDurationMs` INTEGER DEFAULT NULL")
                connection.execSQL("ALTER TABLE `messages` ADD COLUMN `voicePeaks` TEXT DEFAULT NULL")
            }
        }

    /**
     * v6 — per-recipient delivery: one `message_receipts` table recording which node's receipt flipped
     * a message's tick, so the message-details screen can name the members a group send has reached and
     * the ones it hasn't. Purely local bookkeeping — the acker was always on the wire as the receipt's
     * authenticated `senderId`, the tick's "≥1 recipient received it" semantic is unchanged, and no
     * content digest folds over this table. Existing messages get no rows, which is what the UI's
     * "already ✓✓ but no rows = predates the feature, show no roster" fallback expects: we never observed
     * who acked them and must not invent it. Additive only; the SQL must stay byte-equivalent to what Room
     * generates for `app/schemas/**/6.json`.
     */
    val MIGRATION_5_6 =
        object : Migration(5, 6) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `message_receipts` (" +
                        "`messageId` TEXT NOT NULL, `ackerNodeId` TEXT NOT NULL, `notedAt` INTEGER NOT NULL, " +
                        "`via` INTEGER NOT NULL, PRIMARY KEY(`messageId`, `ackerNodeId`))",
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_message_receipts_messageId` ON `message_receipts` (`messageId`)",
                )
            }
        }

    /**
     * v7 — local arrival time: one `messages.arrivedAt` column holding **our** clock at the moment an inbound
     * message was first persisted, so the message-details screen can answer "when did this get here" and not
     * only "when does its author say they sent it". The gap between it and the frame-global `sentAt` is the
     * store-and-forward latency, which nothing else records. Purely local observation — no wire field, no ctl
     * value, no capability bit, and no content digest folds over it, so a node that never learns the value
     * simply shows nothing. Existing rows get null and are deliberately **not** backfilled, the same argument
     * MIGRATION_5_6 makes for un-acked receipts: we never observed when those messages landed, and inventing
     * a plausible number is worse than saying nothing. Null is also the honest value for every message we
     * authored — only the inbound path stamps it. Additive only; the SQL must stay byte-equivalent to what
     * Room generates for `app/schemas/**/7.json`.
     */
    val MIGRATION_6_7 =
        object : Migration(6, 7) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE `messages` ADD COLUMN `arrivedAt` INTEGER DEFAULT NULL")
            }
        }

    /**
     * v8 — arbitrary-file attachments (ADR 2026-09.qq2r): `messages.attachmentName` and
     * `messages.attachmentSize`, the two facts a file bubble needs that an image bubble reads off the pixels.
     * Both are null for every existing row, which is the right answer — every attachment that predates this
     * version is an image or a voice note, and both describe themselves. Unlike the voice columns
     * MIGRATION_4_5 added, these two do arrive off the wire (sealed on `MessageContent`), so a row written by
     * an older build simply has nothing to put in them. Additive only; the SQL must stay byte-equivalent to
     * what Room generates for `app/schemas/**/8.json`.
     */
    val MIGRATION_7_8 =
        object : Migration(7, 8) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE `messages` ADD COLUMN `attachmentName` TEXT DEFAULT NULL")
                connection.execSQL("ALTER TABLE `messages` ADD COLUMN `attachmentSize` INTEGER DEFAULT NULL")
            }
        }

    /**
     * v9 — the "open to chat" profile flag: one `peers.openToChat` column, the peer's declared availability as
     * its latest profile carried it. `NOT NULL DEFAULT 0` because a flag nobody has asserted is off, which is
     * also what the wire says (the field is elided while false), so every existing row reads correctly without
     * a backfill. Additive only; the SQL must stay byte-equivalent to what Room generates for
     * `app/schemas/**/9.json`.
     */
    val MIGRATION_8_9 =
        object : Migration(8, 9) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE `peers` ADD COLUMN `openToChat` INTEGER NOT NULL DEFAULT 0")
            }
        }

    /**
     * v10 — the bridged Meshtastic post's attribution: six `messages` columns naming who said it on the
     * foreign mesh and how it reached this pocket's board. Null (and `0` for the flag) on every existing row,
     * which is exactly right — no message written before this version can be a bridged post, so there is
     * nothing to backfill and no ambiguity about what a null means. Additive only; the SQL must stay
     * byte-equivalent to what Room generates for `app/schemas/**/10.json`.
     */
    val MIGRATION_9_10 =
        object : Migration(9, 10) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE `messages` ADD COLUMN `originNode` INTEGER DEFAULT NULL")
                connection.execSQL("ALTER TABLE `messages` ADD COLUMN `originName` TEXT DEFAULT NULL")
                connection.execSQL("ALTER TABLE `messages` ADD COLUMN `originChannel` TEXT DEFAULT NULL")
                connection.execSQL("ALTER TABLE `messages` ADD COLUMN `originHops` INTEGER DEFAULT NULL")
                connection.execSQL("ALTER TABLE `messages` ADD COLUMN `originSnrDeci` INTEGER DEFAULT NULL")
                connection.execSQL("ALTER TABLE `messages` ADD COLUMN `originViaMqtt` INTEGER NOT NULL DEFAULT 0")
            }
        }

    /**
     * v11 — the bound-board claim and the heard-post match: `peers.loraNode`, the Meshtastic node number a
     * peer's latest profile says they hold, and `messages.originPeerId`, the contact a heard radio post
     * resolved to at ingest. Null on every existing row — no profile before this version claimed a board, and
     * no post could have been matched. Additive only; the SQL must stay byte-equivalent to what Room generates
     * for `app/schemas/**/11.json`.
     */
    val MIGRATION_10_11 =
        object : Migration(10, 11) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE `peers` ADD COLUMN `loraNode` INTEGER DEFAULT NULL")
                connection.execSQL("ALTER TABLE `messages` ADD COLUMN `originPeerId` TEXT DEFAULT NULL")
            }
        }

    /**
     * v12 — signature-backed attribution for heard radio posts: `peers.loraKey`, the Curve25519 key a peer's
     * latest profile advertises for their board, and `messages.originSigned`, what a heard post's signature
     * proved at ingest. Null and 0 (`MessageEntity.ORIGIN_UNSIGNED`) on every existing row — no profile before
     * this version carried a key and no post was checked. Additive only; the SQL must stay byte-equivalent to
     * what Room generates for `app/schemas/**/12.json`.
     */
    val MIGRATION_11_12 =
        object : Migration(11, 12) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE `peers` ADD COLUMN `loraKey` TEXT DEFAULT NULL")
                connection.execSQL("ALTER TABLE `messages` ADD COLUMN `originSigned` INTEGER NOT NULL DEFAULT 0")
            }
        }

    /** All migrations, applied by Room in order. */
    val ALL: Array<Migration> =
        arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
            MIGRATION_10_11,
            MIGRATION_11_12,
        )
}
