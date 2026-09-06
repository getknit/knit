package app.getknit.knit.data

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import app.getknit.knit.data.blob.BlobDao
import app.getknit.knit.data.blob.BlobEntity
import app.getknit.knit.data.blob.BlobVerdictDao
import app.getknit.knit.data.blob.BlobVerdictEntity
import app.getknit.knit.data.forward.ForwardDao
import app.getknit.knit.data.forward.ForwardEntity
import app.getknit.knit.data.group.GroupDao
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.message.MessageDao
import app.getknit.knit.data.message.MessageEntity
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
import net.zetetic.database.sqlcipher.driver.SQLCipherDriver

@Database(
    entities = [
        MessageEntity::class, PeerEntity::class, ReactionEntity::class, BlobEntity::class,
        GroupEntity::class, BlobVerdictEntity::class, ForwardEntity::class,
        RatchetSessionEntity::class, RatchetLocalEpochEntity::class,
        RatchetRecvEpochEntity::class, RatchetSkippedKeyEntity::class,
        GroupSendChainEntity::class, GroupRecvChainEntity::class,
        GroupSkippedKeyEntity::class, GroupKeySendEntity::class,
        GroupRootEntity::class, MessageReceiptEntity::class,
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
    version = 10,
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

    companion object {
        /**
         * Builds the encrypted database. [passphrase] is the SQLCipher key (see
         * [app.getknit.knit.data.crypto.DatabaseKey]); the driver holds the array for the life of the
         * database — nothing zeroes it, so that class stays its owner. The native
         * `libsqlcipher.so` must be loaded explicitly before the driver is constructed.
         */
        @Suppress("SpreadOperator") // vararg Room migrations API; a one-time DB-init copy
        fun build(
            context: Context,
            passphrase: ByteArray,
        ): KnitDatabase {
            System.loadLibrary("sqlcipher")
            return Room
                .databaseBuilder(context, KnitDatabase::class.java, "knit.db")
                // SQLCipher rides in as a SQLiteDriver, not the old SupportOpenHelperFactory: Room 3 deletes
                // `openHelperFactory` outright, and `setDriver` is the one seam left for a custom engine
                // (net.zetetic:sqlcipher-android 4.18.0 added SQLCipherDriver for exactly this). The hook and
                // error-handler args stay null, matching what SupportOpenHelperFactory(passphrase) passed.
                // It reports hasConnectionPool() = true, so Room opens ONE connection through it and lets
                // SQLCipher pool underneath — the invariant SessionTransactor's lock ordering rests on.
                .setDriver(SQLCipherDriver(passphrase, null, null))
                // Production migration posture: v1 is the frozen launch baseline, with NO destructive fallback.
                // Every schema change from here ships a tested KnitMigrations entry; a version bump with no
                // matching migration makes Room throw at open time (caught by KnitDatabaseMigrationTest) — a loud
                // failure in CI, never a silent wipe of a user's messages/custody/pins in production.
                .addMigrations(*KnitMigrations.ALL)
                .build()
        }
    }
}
