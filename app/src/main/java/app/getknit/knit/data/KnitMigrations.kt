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
     * v10 — the LoRa bridge's attribution columns and the index the chat window reads through, in one bump.
     * Six `messages` columns name who said a bridged Meshtastic post on the foreign mesh and how it reached
     * this pocket's board. `peers.loraNode` and `messages.originPeerId` hold the board a peer's latest
     * profile claims and the contact a heard post resolved to at ingest — frozen on the row, so a board
     * changing hands never re-attributes history. `peers.loraKey` and `messages.originSigned` hold the
     * Curve25519 key that claim advertises and what the post's XEdDSA signature proved when it landed. And
     * `messages` trades its `conversationId` index for two composites led by that same column.
     *
     * Null (and `0` for the flags) is the one honest value on every existing row: no message written before
     * this version can be a bridged post, no profile before it claimed a board or carried a key, and no post
     * had been matched or checked — so there is nothing to backfill and no ambiguity about what a null means.
     * The re-index moves no rows. `(conversationId, sentAt, id)` orders a thread in place, so the chat
     * screen's newest-first window needs no sort and stops at its LIMIT; `(conversationId, kind, senderId)`
     * covers the sender queries that screen now runs *live* for @-mention candidates, which unindexed would
     * walk a whole thread on every write. Dropping the single-column index costs nothing: it is a strict
     * prefix of both, so every `conversationId`-only lookup is still served.
     *
     * Additive but for that index swap; the SQL must stay byte-equivalent to what Room generates for
     * `app/schemas/**/10.json`.
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

                connection.execSQL("ALTER TABLE `peers` ADD COLUMN `loraNode` INTEGER DEFAULT NULL")
                connection.execSQL("ALTER TABLE `messages` ADD COLUMN `originPeerId` TEXT DEFAULT NULL")

                connection.execSQL("ALTER TABLE `peers` ADD COLUMN `loraKey` TEXT DEFAULT NULL")
                connection.execSQL("ALTER TABLE `messages` ADD COLUMN `originSigned` INTEGER NOT NULL DEFAULT 0")

                connection.execSQL("DROP INDEX IF EXISTS `index_messages_conversationId`")
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_messages_conversationId_sentAt_id` " +
                        "ON `messages` (`conversationId`, `sentAt`, `id`)",
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_messages_conversationId_kind_senderId` " +
                        "ON `messages` (`conversationId`, `kind`, `senderId`)",
                )
            }
        }

    /**
     * v10 → v11: one `drafts` table, holding the text left unsent in each thread's composer so that leaving
     * the chat screen keeps it. Keyed by the same conversation id the messages are, one row per thread, and
     * no row where nothing was left behind — so an upgrade creates an empty table and every existing thread
     * is correct on arrival, with nothing to backfill.
     *
     * Nothing else moves: no message, peer or custody row is touched, and no other table gains a column.
     * The SQL must stay byte-equivalent to what Room generates for `app/schemas/**/11.json`.
     */
    val MIGRATION_10_11 =
        object : Migration(10, 11) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `drafts` " +
                        "(`conversationId` TEXT NOT NULL, `text` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`conversationId`))",
                )
            }
        }

    /**
     * v11 → v12: one `messages_fts` virtual table — the FTS4 external-content index over `messages.body`
     * that app-wide search reads (`MessageDao.searchBodies`). Three parts, in this order. The virtual table
     * itself, which stores no text: an external-content FTS table keeps only tokens, keyed by the content
     * table's rowid. The four content-sync triggers Room generates for it, verbatim, so that every later
     * INSERT, UPDATE and DELETE on `messages` keeps the index in step — Room drops any
     * `room_fts_content_sync_*` trigger before a migration and re-creates its own after, so in production
     * these are idempotent, but under the migration-test harness (whose open delegate does neither) they
     * are what makes the migrated file behave like a fresh one. Then the `'rebuild'` command, which reads
     * every existing row of `messages` into the index so the messages a device already holds are
     * searchable on arrival; it is proportional to the text held and runs inside Room's migration
     * transaction, so a crash mid-way leaves the file at v11 and the upgrade runs again.
     *
     * No row of `messages` or any other table moves. The SQL must stay byte-equivalent to what Room
     * generates for `app/schemas/**/12.json` (`createSql` and `contentSyncTriggers`).
     */
    val MIGRATION_11_12 =
        object : Migration(11, 12) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `messages_fts` USING FTS4(`body` TEXT NOT NULL, " +
                        "tokenize=unicode61, content=`messages`)",
                )
                connection.execSQL(
                    "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_messages_fts_BEFORE_UPDATE BEFORE UPDATE ON " +
                        "`messages` BEGIN DELETE FROM `messages_fts` WHERE `docid`=OLD.`rowid`; END",
                )
                connection.execSQL(
                    "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_messages_fts_BEFORE_DELETE BEFORE DELETE ON " +
                        "`messages` BEGIN DELETE FROM `messages_fts` WHERE `docid`=OLD.`rowid`; END",
                )
                connection.execSQL(
                    "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_messages_fts_AFTER_UPDATE AFTER UPDATE ON " +
                        "`messages` BEGIN INSERT INTO `messages_fts`(`docid`, `body`) VALUES (NEW.`rowid`, NEW.`body`); END",
                )
                connection.execSQL(
                    "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_messages_fts_AFTER_INSERT AFTER INSERT ON " +
                        "`messages` BEGIN INSERT INTO `messages_fts`(`docid`, `body`) VALUES (NEW.`rowid`, NEW.`body`); END",
                )
                connection.execSQL("INSERT INTO `messages_fts`(`messages_fts`) VALUES('rebuild')")
            }
        }

    /**
     * v12 → v13: the commons' three tables — `commons`, `commons_outbox` (indexed by conversation, since
     * the heal loop reads one room's posts at a time and a leave purges one room's), `commons_members` —
     * all empty on arrival: a room exists only once the user pastes an invite, so there is nothing to
     * backfill and every existing thread is untouched. No column moves anywhere else. The SQL must stay
     * byte-equivalent to what Room generates for `app/schemas/**/13.json`.
     */
    val MIGRATION_12_13 =
        object : Migration(12, 13) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `commons` " +
                        "(`conversationId` TEXT NOT NULL, `spoolUrl` TEXT NOT NULL, `secret` BLOB NOT NULL, " +
                        "`name` TEXT, `joinedAt` INTEGER NOT NULL, PRIMARY KEY(`conversationId`))",
                )
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `commons_outbox` " +
                        "(`frameId` TEXT NOT NULL, `conversationId` TEXT NOT NULL, `sig` BLOB NOT NULL, " +
                        "`signed` BLOB NOT NULL, `sentAt` INTEGER NOT NULL, PRIMARY KEY(`frameId`))",
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_commons_outbox_conversationId` ON `commons_outbox` (`conversationId`)",
                )
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `commons_members` " +
                        "(`conversationId` TEXT NOT NULL, `nodeId` TEXT NOT NULL, `seenAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`conversationId`, `nodeId`))",
                )
            }
        }

    /**
     * v13 → v14: the `met_peers` table (the phones this one has been in radio range of — see
     * `MetPeerEntity`) and its `lastMetAt` index, empty on arrival: "met" is a live radio signal, so there
     * is nothing in the older tables to backfill it from (`peers` holds multi-hop profiles too). No column
     * moves anywhere else. The SQL must stay byte-equivalent to what Room generates for
     * `app/schemas/**/14.json`.
     */
    val MIGRATION_13_14 =
        object : Migration(13, 14) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `met_peers` " +
                        "(`nodeId` TEXT NOT NULL, `firstMetAt` INTEGER NOT NULL, `lastMetAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`nodeId`))",
                )
                connection.execSQL("CREATE INDEX IF NOT EXISTS `index_met_peers_lastMetAt` ON `met_peers` (`lastMetAt`)")
            }
        }

    /**
     * v14 → v15: the `saved_files` table (ADR 2026-09.7ad3), empty — a file saved before this build is asked
     * for once more, which is the honest answer since no grant to its copy was ever kept.
     */
    val MIGRATION_14_15 =
        object : Migration(14, 15) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `saved_files` " +
                        "(`hash` TEXT NOT NULL, `uri` TEXT NOT NULL, `savedAt` INTEGER NOT NULL, PRIMARY KEY(`hash`))",
                )
            }
        }

    /**
     * v15 → v16: the `peer_profiles` table (ADR 2026-09.g64k), empty — a peer's proof of key is kept from the next
     * profile of theirs this phone pins.
     */
    val MIGRATION_15_16 =
        object : Migration(15, 16) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `peer_profiles` " +
                        "(`nodeId` TEXT NOT NULL, `signed` BLOB NOT NULL, `sig` BLOB NOT NULL, `sentAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`nodeId`))",
                )
            }
        }

    /**
     * v16 → v17: one `messages.attachmentAnimated` flag — the attachment holds more than one frame, so the
     * previews say "GIF" rather than "Photo". Local only, derived from the bytes on both ends (`AnimatedImage`),
     * like MIGRATION_4_5's voice columns. The backfill is what SQL can see without the bytes: an attachment
     * still carried as `image/gif` is one a GIF sender never re-encoded. A GIF this app stored as animated
     * WebP before the column existed keeps reading "Photo" — its bytes sit sealed in `blobs` under keys no
     * migration should be decrypting on the open path. Additive only; the SQL must stay byte-equivalent to
     * what Room generates for `app/schemas/**/17.json`.
     */
    val MIGRATION_16_17 =
        object : Migration(16, 17) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE `messages` ADD COLUMN `attachmentAnimated` INTEGER NOT NULL DEFAULT 0")
                connection.execSQL("UPDATE `messages` SET `attachmentAnimated` = 1 WHERE `attachmentMime` = 'image/gif'")
            }
        }

    /**
     * v17 → v18: one `groups.photoShownHash` column — the group photo that renders, split from `photoHash`, which
     * now holds the photo this device decided on and advertises from the moment it hears of it (ADR
     * 2026-09.nxcq, #108). Backfilled from `photoHash`: until now a hash was stored only once its bytes were
     * local and screened, so every existing photo is one that renders. Additive only; the SQL must stay
     * byte-equivalent to what Room generates for `app/schemas/**/18.json`.
     */
    val MIGRATION_17_18 =
        object : Migration(17, 18) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE `groups` ADD COLUMN `photoShownHash` TEXT")
                connection.execSQL("UPDATE `groups` SET `photoShownHash` = `photoHash`")
            }
        }

    /**
     * v18 → v19: one `peers.lastSeenAt` column — when this phone last heard from the peer, so a stranger quiet for
     * a week stops adding an alias to a contact's label (ADR 2026-10.k8ge). Seeded from the newest stamp the row
     * already implies: its profile version, or the publish stamp of the signed profile kept as its proof of key
     * (`peer_profiles.sentAt`, refreshed every 12 h while the peer is live), whichever is later. A seed that runs
     * early only makes a stranger yield sooner, and a stranger that yields is still labelled apart; a contact
     * never reads this column. Additive only; the SQL must stay byte-equivalent to what Room generates for
     * `app/schemas/**/19.json`.
     */
    val MIGRATION_18_19 =
        object : Migration(18, 19) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE `peers` ADD COLUMN `lastSeenAt` INTEGER NOT NULL DEFAULT 0")
                connection.execSQL(
                    "UPDATE `peers` SET `lastSeenAt` = MAX(`updatedAt`, " +
                        "COALESCE((SELECT `sentAt` FROM `peer_profiles` WHERE `peer_profiles`.`nodeId` = `peers`.`nodeId`), 0))",
                )
            }
        }

    /**
     * v19 → v20: `messages.attachmentPlainHash` (indexed) and `messages.attachmentSourceHash` — the plaintext name
     * of a sealed attachment and the picker's own bytes for one of ours, so a forward reuses the identity a file
     * arrived under and a room copy of a picture held sealed is made locally (ADR 2026-10.mptm). Null on every
     * existing row: filling the plaintext name means decrypting the blob, which no migration should do on the open
     * path, so an older attachment learns it when it is next saved. Additive only; the SQL must stay
     * byte-equivalent to what Room generates for `app/schemas/**/20.json`.
     */
    val MIGRATION_19_20 =
        object : Migration(19, 20) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE `messages` ADD COLUMN `attachmentPlainHash` TEXT DEFAULT NULL")
                connection.execSQL("ALTER TABLE `messages` ADD COLUMN `attachmentSourceHash` TEXT DEFAULT NULL")
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_messages_attachmentPlainHash` ON `messages` (`attachmentPlainHash`)",
                )
            }
        }

    /**
     * v20 → v21: `ratchet_recv_epochs` and `ratchet_skipped_keys` gain `rootTag`, last in their keys — the root a
     * receive chain or skipped key was derived under, so an old era's chain drained under a kept `prevRoot` never
     * decides a new-era frame that reuses its epoch number after a reset (#135, ADR 2026-10.gcrd).
     *
     * The first table rebuild in this file, and it keeps no rows. A tag is an HKDF of the session root, which SQL
     * cannot compute, and an existing row's root is not knowable anyway: a chain opened under `prevRoot` or a race
     * loser's root is stored like one of the active root's, and those are exactly the stale rows #135 is about —
     * stamping them with the active root's tag would keep them live for up to two days. Both tables are transient
     * by design (never in a backup, swept 48 h after last use). Sessions and our epoch privs stay, so every frame
     * still opens by deriving its epoch afresh; what goes is the memory of indices already consumed, so a re-served
     * frame with no message row (a receipt, say) opens once more — what every replacement's purge has always done.
     * The SQL must stay byte-equivalent to what Room generates for `app/schemas/**/21.json`.
     */
    val MIGRATION_20_21 =
        object : Migration(20, 21) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL("DROP TABLE IF EXISTS `ratchet_recv_epochs`")
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ratchet_recv_epochs` (`peerId` TEXT NOT NULL, `epoch` INTEGER NOT NULL, " +
                        "`rootTag` INTEGER NOT NULL, `chainKey` BLOB NOT NULL, `next` INTEGER NOT NULL, " +
                        "`lastUsedAt` INTEGER NOT NULL, PRIMARY KEY(`peerId`, `epoch`, `rootTag`))",
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_ratchet_recv_epochs_lastUsedAt` ON `ratchet_recv_epochs` (`lastUsedAt`)",
                )
                connection.execSQL("DROP TABLE IF EXISTS `ratchet_skipped_keys`")
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ratchet_skipped_keys` (`peerId` TEXT NOT NULL, `epoch` INTEGER NOT NULL, " +
                        "`idx` INTEGER NOT NULL, `rootTag` INTEGER NOT NULL, `msgKey` BLOB NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`peerId`, `epoch`, `idx`, `rootTag`))",
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_ratchet_skipped_keys_createdAt` ON `ratchet_skipped_keys` (`createdAt`)",
                )
            }
        }

    /**
     * v21 → v22: everything the schema gained after 2.9.0, in one bump (a shipped migration can never be merged
     * away, so the unreleased churn is folded before it ships). All local, no wire change:
     *
     * - one `group_member_clocks` table — each founding member's leave and rejoin clocks per group, moved off the
     *   `sentAt` of the `leave:<group>:<member>` and `rejoin:<group>:<member>` status lines, which the retention sweep
     *   may drop while the group lives on (IOS-6, ADR 2026-09.v6fu). Filled from the lines a device holds at upgrade,
     *   for groups it still holds; the member is read off the line's id, the id the clocks were always keyed by. A
     *   line already swept is a clock already lost, and stays 0 — the reading it had.
     * - `messages.replyCheck` and `messages.replyToFlagged`, indexed `(replyToId, replyCheck)` — whether an inbound
     *   reply's quote was judged against the original it names, and this phone's verdict on the quoted words (ADR
     *   2026-10.gpa3). No backfill: a verified quote is rebuilt from its original with localized labels SQL cannot
     *   produce, so every existing reply starts unchecked and `QuoteVerifier.settleBacklog` judges those whose
     *   original is held on the next mesh start.
     * - `messages.attachmentFetchApproved` — the user's "Tap to download" on a large attachment only Bluetooth could
     *   fetch (ADR 2026-10.js2p), unapproved on every existing row — and an index on `messages.attachmentHash` for
     *   the delivery's per-hash read of every row naming an attachment.
     * - a short video's duration, upright size, inline poster and the poster's verdict on the message row (ADR
     *   2026-10.hmfe), null (and unflagged) on every existing row: nothing before this build sent a video.
     * - one `peer_badges` table — the supporter badges each peer's sealed profile last carried, verified when shown
     *   (docs/SUPPORTER_BADGE.md, ADR 2026-10.r99s). Empty: each fills as that peer's next sealed profile lands.
     * - one `attachment_holders` table — per (attachment hash, holder) proof, learnt over a short-range radio, that a
     *   DM photo's recipient holds its bytes, so the photo never goes up to a spool (docs/SPOOL_PROTOCOL.md C-9.5-8,
     *   ADR 2026-10.vy27). Empty: a photo already in custody proves itself on its next round, or uploads as before.
     *
     * The SQL must stay byte-equivalent to what Room generates for `app/schemas/**/22.json`.
     */
    val MIGRATION_21_22 =
        object : Migration(21, 22) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `group_member_clocks` (`groupId` TEXT NOT NULL, `memberId` TEXT NOT NULL, " +
                        "`leftAt` INTEGER NOT NULL, `rejoinedAt` INTEGER NOT NULL, PRIMARY KEY(`groupId`, `memberId`))",
                )
                connection.execSQL(
                    "INSERT INTO `group_member_clocks` (`groupId`, `memberId`, `leftAt`, `rejoinedAt`) " +
                        "SELECT groupId, memberId, MAX(leftAt), MAX(rejoinedAt) FROM (" +
                        "SELECT g.groupId AS groupId, substr(m.id, length('leave:' || g.groupId || ':') + 1) AS memberId, " +
                        "m.sentAt AS leftAt, 0 AS rejoinedAt FROM messages m JOIN groups g ON m.conversationId = g.groupId " +
                        "WHERE substr(m.id, 1, length('leave:' || g.groupId || ':')) = 'leave:' || g.groupId || ':' " +
                        "UNION ALL " +
                        "SELECT g.groupId, substr(m.id, length('rejoin:' || g.groupId || ':') + 1), 0, m.sentAt " +
                        "FROM messages m JOIN groups g ON m.conversationId = g.groupId " +
                        "WHERE substr(m.id, 1, length('rejoin:' || g.groupId || ':')) = 'rejoin:' || g.groupId || ':'" +
                        ") WHERE memberId != '' GROUP BY groupId, memberId",
                )
                connection.execSQL("ALTER TABLE `messages` ADD COLUMN `replyCheck` INTEGER NOT NULL DEFAULT 0")
                connection.execSQL("ALTER TABLE `messages` ADD COLUMN `replyToFlagged` INTEGER NOT NULL DEFAULT 0")
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_messages_replyToId_replyCheck` ON `messages` (`replyToId`, `replyCheck`)",
                )
                connection.execSQL("ALTER TABLE `messages` ADD COLUMN `attachmentFetchApproved` INTEGER NOT NULL DEFAULT 0")
                connection.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_attachmentHash` ON `messages` (`attachmentHash`)")
                connection.execSQL("ALTER TABLE `messages` ADD COLUMN `videoDurationMs` INTEGER")
                connection.execSQL("ALTER TABLE `messages` ADD COLUMN `videoWidth` INTEGER")
                connection.execSQL("ALTER TABLE `messages` ADD COLUMN `videoHeight` INTEGER")
                connection.execSQL("ALTER TABLE `messages` ADD COLUMN `videoPoster` TEXT")
                connection.execSQL("ALTER TABLE `messages` ADD COLUMN `videoPosterFlagged` INTEGER NOT NULL DEFAULT 0")
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `peer_badges` " +
                        "(`nodeId` TEXT NOT NULL, `position` INTEGER NOT NULL, `wrapper` BLOB NOT NULL, `r` BLOB NOT NULL, " +
                        "PRIMARY KEY(`nodeId`, `position`))",
                )
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `attachment_holders` " +
                        "(`hash` TEXT NOT NULL, `holderId` TEXT NOT NULL, `plane` INTEGER NOT NULL, `provedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`hash`, `holderId`))",
                )
            }
        }

    /**
     * v23 — `messages.voiceTranscript`: a voice note's words, written out on this phone when the person taps
     * Transcribe (ADR 2026-10.am8d). Local only, derived from the held bytes like `voicePeaks`; null on every existing
     * row. The SQL must stay byte-equivalent to what Room generates for `app/schemas/**/23.json`.
     */
    val MIGRATION_22_23 =
        object : Migration(22, 23) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE `messages` ADD COLUMN `voiceTranscript` TEXT DEFAULT NULL")
            }
        }

    /**
     * Lab only, and temporary: walks a development database stamped v27 back down to v22. Before the fold above, the
     * post-2.9.0 schema was numbered v22…v27 and the lab phones installed it at v27; Room throws at every open on a
     * `user_version` above the app's, but takes a registered descending path — and only one that never passes below
     * its target, so it lands on v23 directly. v27 *is* the folded v22 (Room hashes schema content, not the version),
     * so the body is v23's one step, [MIGRATION_22_23]. Delete it once every lab phone has opened its database on a
     * build that carries it. Not in [ALL], which is the upgrade chain.
     */
    val LAB_DOWNGRADE_27_23 =
        object : Migration(27, 23) {
            override suspend fun migrate(connection: SQLiteConnection) = MIGRATION_22_23.migrate(connection)
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
            MIGRATION_12_13,
            MIGRATION_13_14,
            MIGRATION_14_15,
            MIGRATION_15_16,
            MIGRATION_16_17,
            MIGRATION_17_18,
            MIGRATION_18_19,
            MIGRATION_19_20,
            MIGRATION_20_21,
            MIGRATION_21_22,
            MIGRATION_22_23,
        )

    /** What [KnitDatabase.build] registers: the upgrade chain plus the temporary lab downgrade. */
    val REGISTERED: Array<Migration> = ALL + LAB_DOWNGRADE_27_23
}
