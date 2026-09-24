package app.getknit.knit.data

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import app.getknit.knit.data.blob.BlobDao
import app.getknit.knit.data.blob.BlobEntity
import app.getknit.knit.data.blob.BlobVerdictDao
import app.getknit.knit.data.blob.BlobVerdictEntity
import app.getknit.knit.data.blob.SavedFileDao
import app.getknit.knit.data.blob.SavedFileEntity
import app.getknit.knit.data.commons.CommonsDao
import app.getknit.knit.data.commons.CommonsEntity
import app.getknit.knit.data.commons.CommonsMemberEntity
import app.getknit.knit.data.commons.CommonsOutboxEntity
import app.getknit.knit.data.crypto.SqlCipherKey
import app.getknit.knit.data.draft.DraftDao
import app.getknit.knit.data.draft.DraftEntity
import app.getknit.knit.data.forward.ForwardDao
import app.getknit.knit.data.forward.ForwardEntity
import app.getknit.knit.data.group.GroupDao
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.message.MessageDao
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.message.MessageFtsEntity
import app.getknit.knit.data.peer.MetPeerDao
import app.getknit.knit.data.peer.MetPeerEntity
import app.getknit.knit.data.peer.PeerDao
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.data.ratchet.GroupKeySendEntity
import app.getknit.knit.data.ratchet.GroupRatchetDao
import app.getknit.knit.data.ratchet.GroupRecvChainEntity
import app.getknit.knit.data.ratchet.GroupRootDao
import app.getknit.knit.data.ratchet.GroupRootEntity
import app.getknit.knit.data.ratchet.GroupSendChainEntity
import app.getknit.knit.data.ratchet.GroupSkippedKeyEntity
import app.getknit.knit.data.ratchet.RatchetDao
import app.getknit.knit.data.ratchet.RatchetLocalEpochEntity
import app.getknit.knit.data.ratchet.RatchetRecvEpochEntity
import app.getknit.knit.data.ratchet.RatchetSessionEntity
import app.getknit.knit.data.ratchet.RatchetSkippedKeyEntity
import app.getknit.knit.data.reaction.ReactionDao
import app.getknit.knit.data.reaction.ReactionEntity
import app.getknit.knit.data.receipt.MessageReceiptDao
import app.getknit.knit.data.receipt.MessageReceiptEntity
import net.zetetic.database.sqlcipher.SQLiteGlobal
import net.zetetic.database.sqlcipher.driver.SQLCipherDriver

/**
 * The `@Database` version, as a top-level constant so the annotation and [KnitDatabase.SCHEMA_VERSION] read
 * one number (an annotation argument cannot name the class's own companion).
 */
internal const val KNIT_DB_SCHEMA_VERSION = 15

@Database(
    entities = [
        MessageEntity::class, PeerEntity::class, ReactionEntity::class, BlobEntity::class,
        GroupEntity::class, BlobVerdictEntity::class, ForwardEntity::class,
        RatchetSessionEntity::class, RatchetLocalEpochEntity::class,
        RatchetRecvEpochEntity::class, RatchetSkippedKeyEntity::class,
        GroupSendChainEntity::class, GroupRecvChainEntity::class,
        GroupSkippedKeyEntity::class, GroupKeySendEntity::class,
        GroupRootEntity::class, MessageReceiptEntity::class, DraftEntity::class,
        MessageFtsEntity::class, CommonsEntity::class, CommonsOutboxEntity::class,
        CommonsMemberEntity::class, MetPeerEntity::class, SavedFileEntity::class,
    ],
    // v1: frozen launch baseline. The pre-1.0 alpha schema churn (the old destructive v2…v22 bumps that
    //     rode the wire/crypto breaks) is collapsed; docs/WIRE_COMPAT.md keeps the historical break record.
    //     From v1 on, every @Database bump ships a tested KnitMigrations entry — a missing one throws at open
    //     time (caught by KnitDatabaseMigrationTest), never a silent wipe of a user's messages/custody/pins.
    // v2: the ratchet schemes, one never-released bump — DM epoch-ratchet state (4 ratchet_* tables),
    //     group sender-key state (4 group_* tables: send/recv chains, skipped keys, the seed outbox),
    //     and the peers prekey columns (docs/FORWARD_SECRECY_RATCHET.md +
    //     docs/GROUP_FORWARD_SECRECY.md); migrated by KnitMigrations.MIGRATION_1_2.
    // v3: the spool plane's group scopes — one `group_roots` table holding the shared group root the group
    //     scope id and seal keys derive from (docs/SPOOL_PROTOCOL.md §3.2); no wire break, local state only,
    //     migrated by KnitMigrations.MIGRATION_2_3.
    // v4: one `messages.receivedVia` column — the DeliveryPlane code of the receipt that flipped the tick, so
    //     the ✓✓ can say the message got there over the Internet; migrated by KnitMigrations.MIGRATION_3_4.
    // v5: two `messages` columns describing a voice-note attachment — `voiceDurationMs` and the Base64
    //     `voicePeaks` waveform. Purely local presentation state derived from the audio by VoiceAudio on both
    //     the sending and receiving side, so voice notes need no wire field at all; null on every existing
    //     row, which is honest (a pre-upgrade voice note simply re-derives them when next played);
    //     migrated by KnitMigrations.MIGRATION_4_5.
    // v6: one `message_receipts` table — who has acked each message (the message-details screen's
    //     "delivered to / waiting on" split for a group send). Local bookkeeping only: the acker was always
    //     on the wire as the receipt's authenticated senderId, the tick's "≥1 recipient" semantic is
    //     unchanged, and no digest folds over it; migrated by KnitMigrations.MIGRATION_5_6.
    // v7: one `messages.arrivedAt` column — OUR clock when an inbound message was first persisted, so the
    //     message-details screen can say when it got here and not only when its author says they sent it.
    //     Local observation, no wire change; null on every message we authored and on every pre-upgrade row
    //     (deliberately un-backfilled); migrated by KnitMigrations.MIGRATION_6_7.
    // v8: two `messages` columns describing an arbitrary-FILE attachment — `attachmentName` and
    //     `attachmentSize` (ADR 2026-09.qq2r). Unlike v5's voice pair these do come off the wire, sealed on
    //     MessageContent, because a filename is the one thing about a file that is not a function of bytes
    //     both ends already hold; null on every existing row, which is correct (every older attachment is an
    //     image or a voice note and describes itself); migrated by KnitMigrations.MIGRATION_7_8.
    // v9: one `peers.openToChat` column — the peer's declared "open to chat" availability as its latest profile
    //     carried it (`ProfileContent.openToChat`, and the sealed `ProfilePayload.openToChat`). Off the wire,
    //     under the presentation LWW watermark; 0 on every existing row, which is correct (nobody has asserted
    //     it, and the wire elides the flag while off); migrated by KnitMigrations.MIGRATION_8_9.
    // v10: the LoRa bridge's attribution columns, plus the index the chat window reads through — one bump,
    //     four parts. Six `messages` columns attribute a **bridged Meshtastic post** — `originNode`,
    //     `originName`, `originChannel`, `originHops`, `originSnrDeci`, `originViaMqtt` (the LongFast
    //     bridge). A denormalized snapshot in the replyTo* mould, and for a stronger reason than that one:
    //     the speaker has no Knit identity and no peer row, so there is nothing on this device to resolve
    //     the name against, now or ever. `peers.loraNode` is the board a peer's latest profile says they
    //     hold (off the wire, `ProfileContent.loraNode`, under the presentation LWW watermark) and
    //     `messages.originPeerId` the contact a heard post resolved to at ingest, frozen on the row so a
    //     board changing hands never re-attributes history. `peers.loraKey` is the Curve25519 key that same
    //     profile advertises for the board and `messages.originSigned` what the post's XEdDSA signature
    //     proved, frozen beside `originPeerId`. Local only (a heard post is never framed), null / 0
    //     (`ORIGIN_UNSIGNED`) on every existing row — no message before this could be a bridged post and no
    //     profile had claimed a board or carried a key. Finally, and moving no rows at all, `messages`
    //     trades its `conversationId` index for composites over (conversationId, sentAt, id) and
    //     (conversationId, kind, senderId): the old index found a thread's rows but left SQLite sorting all
    //     of them, and the first composite orders them too, which is what lets the chat screen read a
    //     bounded newest-first window instead of the whole conversation. Migrated by
    //     KnitMigrations.MIGRATION_9_10.
    // v11: one `drafts` table — the text left unsent in a thread's composer, keyed by conversation id, so
    //     leaving the chat screen no longer throws it away, with `updatedAt` recording our own clock when
    //     the row was last written (the chat list compares it against the thread's newest message to decide
    //     whether the row reads "Draft: …"). Purely local: a draft is never framed, never enters custody,
    //     and no digest folds over it. It sits in this database rather than the settings DataStore because
    //     it is message text the user wrote, and the reason `messages` is encrypted at rest is the same
    //     reason the sentence they were still writing should be; migrated by KnitMigrations.MIGRATION_10_11.
    //
    //     `updatedAt` briefly had a v12 bump of its own, minted while this branch was unreleased and folded
    //     back in before it reached main (`testing.md`: keep the version count down while a branch is
    //     unreleased, because a shipped migration can never be merged away afterwards). The lab devices that
    //     had already taken v12 were walked back down by a temporary Migration(12, 11), removed once the
    //     fleet was on this schema; no released build ever held v12 (2.5.0 shipped v10).
    // v12: one `messages_fts` virtual table — an FTS4 external-content index over `messages.body` (tokenizer
    //     unicode61), the engine behind app-wide message search. It holds no text of its own: each body's
    //     tokens keyed by `messages.rowid`, kept in step by Room's four `room_fts_content_sync_messages_fts_*`
    //     triggers and backfilled once by `'rebuild'` in the migration, so every message a device already
    //     holds is searchable on arrival. Every read of it is bounded and index-served (ADR 2026-09.z58t).
    //     Local only — nothing about it crosses the wire — and it lives in this database rather than anywhere
    //     else for the drafts' reason (v11): an index of message text is message text. Invariant: `messages`
    //     has a TEXT primary key, so `VACUUM` may renumber its rowids; never VACUUM, and if one is ever
    //     needed follow it with `INSERT INTO messages_fts(messages_fts) VALUES('rebuild')`. Migrated by
    //     KnitMigrations.MIGRATION_11_12.
    // v13: the commons — a spool's shared room (docs/SPOOL_PROTOCOL.md §7.4), three tables and no column
    //     elsewhere: `commons` (the joined rooms: the invite secret each scope derives from, bound to the
    //     one relay that runs it), `commons_outbox` (our own posts as the exact signed bytes, because the
    //     seal is deterministic over them and a post is never in mesh custody — a room only some nodes are
    //     in can never fold into a digest every node must compute alike), and `commons_members` (who this
    //     device has seen there — the roster a commons has instead of a pinned one). All three in this
    //     database for the group roots' reason (v3): the secret is the room. Migrated by
    //     KnitMigrations.MIGRATION_12_13.
    // v14: one `met_peers` table — the phones this one has been within radio range of (the lifetime union
    //     of `MeshController.neighbors`, one row per node id with its first and latest sighting), the
    //     "people this phone has met" line of the Your mesh screen. Not a column on `peers`: that table is
    //     written for multi-hop profiles too and evicts oldest-profile-first, so it can neither say "met" nor
    //     keep a met contact counted. Local only — never framed, never in custody, no digest folds over it —
    //     and in this database rather than the DataStore because it is a list of node ids. Empty on arrival;
    //     the count starts at zero for everyone. Migrated by KnitMigrations.MIGRATION_13_14.
    // v15: one `saved_files` table — the document a received file was saved to, keyed by its blob hash, so
    //     the next tap on the bubble opens that copy rather than asking where to save it again (ADR
    //     2026-09.7ad3). Local only, and never in a backup: the URI names this phone's storage and the read
    //     grant behind it belongs to this install. In this database rather than the DataStore because a
    //     document URI usually spells out the file's name. Empty on arrival — a file saved before this asks
    //     once more. Migrated by KnitMigrations.MIGRATION_14_15.
    version = KNIT_DB_SCHEMA_VERSION,
    // Export the schema JSON to app/schemas/ (location set by the androidx.room Gradle plugin's
    // room { schemaDirectory(...) } in app/build.gradle.kts). Keeps the schema diffable in review and feeds
    // the migration test's MigrationTestHelper. Room also errors at compile time if an entity changes without
    // a version bump.
    exportSchema = true,
)
abstract class KnitDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao

    abstract fun peerDao(): PeerDao

    abstract fun reactionDao(): ReactionDao

    abstract fun blobDao(): BlobDao

    abstract fun groupDao(): GroupDao

    abstract fun blobVerdictDao(): BlobVerdictDao

    abstract fun forwardDao(): ForwardDao

    abstract fun ratchetDao(): RatchetDao

    abstract fun groupRatchetDao(): GroupRatchetDao

    abstract fun groupRootDao(): GroupRootDao

    abstract fun messageReceiptDao(): MessageReceiptDao

    abstract fun draftDao(): DraftDao

    abstract fun commonsDao(): CommonsDao

    abstract fun metPeerDao(): MetPeerDao

    abstract fun savedFileDao(): SavedFileDao

    companion object {
        /** The schema version the annotation above declares, for code that must compare against it. */
        const val SCHEMA_VERSION = KNIT_DB_SCHEMA_VERSION

        /** The live database's file name (`context.getDatabasePath(DB_NAME)`). */
        const val DB_NAME = "knit.db"

        /** SQLCipher's WAL pool ceiling: one writer and three readers, the framework SQLite's default. */
        private const val WAL_CONNECTION_POOL_SIZE = 4

        /**
         * Builds the encrypted database. [passphrase] is [app.getknit.knit.data.crypto.DatabaseKey]'s; the
         * driver is keyed with its raw-key form ([SqlCipherKey.raw], a copy the driver holds for the life of
         * the database), and a file still on the old passphrase key is moved onto it first
         * ([SqlCipherKey.upgrade]) — the caller keeps owning [passphrase]. The native `libsqlcipher.so` must be
         * loaded explicitly before the driver is constructed.
         *
         * [name] is the live [DB_NAME] for the app's one database; the backup export passes an absolute
         * path (which `getDatabasePath` hands back as is) to build the scratch copy it fills through the
         * same schema, identity hash and FTS triggers Room would create for the real one.
         */
        @Suppress("SpreadOperator") // vararg Room migrations API; a one-time DB-init copy
        fun build(
            context: Context,
            passphrase: ByteArray,
            name: String = DB_NAME,
        ): KnitDatabase {
            System.loadLibrary("sqlcipher")
            // SQLCipher's WAL pool defaults to ten connections, opened lazily while the pool holds its lock;
            // the framework's own default is four. A process-wide static, so set before the first open.
            SQLiteGlobal.setWALConnectionPoolSize(WAL_CONNECTION_POOL_SIZE)
            SqlCipherKey.upgrade(context.getDatabasePath(name), passphrase)
            return Room
                .databaseBuilder(context, KnitDatabase::class.java, name)
                // SQLCipher rides in as a SQLiteDriver, not the old SupportOpenHelperFactory: Room 3 deletes
                // `openHelperFactory` outright, and `setDriver` is the one seam left for a custom engine
                // (net.zetetic:sqlcipher-android 4.18.0 added SQLCipherDriver for exactly this). The hook and
                // error-handler args stay null, matching what SupportOpenHelperFactory(passphrase) passed.
                // It reports hasConnectionPool() = true, so Room opens ONE connection through it and lets
                // SQLCipher pool underneath — the invariant SessionTransactor's lock ordering rests on.
                .setDriver(SQLCipherDriver(SqlCipherKey.raw(passphrase), null, null))
                // Production migration posture: v1 is the frozen launch baseline, with NO destructive fallback.
                // Every schema change from here ships a tested KnitMigrations entry; a version bump with no
                // matching migration makes Room throw at open time (caught by KnitDatabaseMigrationTest) — a loud
                // failure in CI, never a silent wipe of a user's messages/custody/pins in production.
                .addMigrations(*KnitMigrations.ALL)
                .build()
        }
    }
}
