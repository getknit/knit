package app.getknit.knit.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
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
import app.getknit.knit.data.reaction.ReactionDao
import app.getknit.knit.data.reaction.ReactionEntity
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File

@Database(
    entities = [
        MessageEntity::class, PeerEntity::class, ReactionEntity::class, BlobEntity::class,
        GroupEntity::class, BlobVerdictEntity::class, ForwardEntity::class,
    ],
    // v2: messages gained attachmentHash/attachmentMime/attachmentPath (destructive migration).
    // v3: messages gained a mentions JSON column (destructive migration; app not yet public).
    // v4: added the reactions table (destructive migration; app not yet public).
    // v5: messages gained an indexed conversationId for 1:1 DMs (destructive migration; not public).
    // v6: images moved into the encrypted `blobs` table; messages dropped attachmentPath, peers
    //     swapped avatarPath for avatarHash. Destructive migration; the now-orphaned plaintext image
    //     files are purged on upgrade (see [purgeLegacyImageFiles]).
    // v7: added the groups table for group chat. Destructive migration; app not yet public.
    // v8: messages gained a `moderation` verdict column; added the `blob_verdicts` table caching
    //     on-device NSFW verdicts by content hash. Destructive migration; app not yet public.
    // v9: E2E encryption — messages gained attachmentKey, peers gained verified (pubKey now holds the
    //     pinned identity bundle). Destructive migration; clears pre-encryption plaintext history.
    // v10: nodeId is now the self-certifying hash of the keypair; peers gained a key-independent
    //      `deviceTag` for block-list continuity. Destructive migration; the prior device-derived
    //      nodeIds (and their pins) are invalidated by the identity change anyway.
    // v11: added the forward_store table for store-and-forward DM custody; messages gained a pendingKey
    //      flag (a DM saved before its recipient's key was known, retransmitted on key arrival).
    //      Destructive migration; app not yet public.
    // v12: forward_store now also carries group chat frames — recipientId became nullable and a
    //      nullable groupId column was added (exactly one is set; the broadcast room is never carried).
    //      Destructive migration; app not yet public.
    // v13: groups gained a `departed` roster-tombstone column (node ids that left, so a departure sticks
    //      against straggler re-broadcasts); messages gained a `kind` discriminator for the
    //      locally-generated "member left" status notice. Destructive migration; app not yet public.
    // v14: layered wire-format break. forward_store now stores the signed routing-envelope blob + its
    //      signature (the `bytes` column became `signed`, plus a new `sig` column); peers gained
    //      `protoVersion`/`capabilities` from the profile frame. Destructive migration (clears any
    //      old-format carried frames + pins); coordinated with the bumped mesh SERVICE_NAME.
    // v15: groups gained `photoHash`/`photoUpdatedAt` for the (last-writer-wins) group photo. Additive
    //      wire change (new nullable GroupInfo fields), so no SERVICE_NAME bump; destructive DB migration as
    //      usual (app not yet public).
    // v16: store-and-forward custody generalized to every floodable frame (reactions, receipts, group
    //      updates/leaves, profiles — not just chat); forward_store gained a `type` column so per-type
    //      policy (the broadcast quota + shorter TTL) doesn't starve the metadata frames that share the
    //      null recipient/group shape. No wire change; destructive DB migration as usual (app not yet public).
    // v17: EncEnvelope nonce/ct + WrappedKey.wk re-typed from base64 String to raw CBOR @ByteString,
    //      dropping the ~33% base64 tax every E2E DM/group frame paid. A *breaking* wire change (a field
    //      re-type), so SERVICE_NAME bumps in lockstep (v3 → v4) to partition old builds and this
    //      destructive wipe clears any custodied old-format frames. No schema-column change — the version
    //      bump is the coordinated wire/DB break the format requires (see docs/WIRE_COMPAT.md).
    // v18: forward_store gained a `sentAt` column (the originator's envelope timestamp). Custody eviction now
    //      trims each over-quota per-sender/group/broadcast bucket to its newest-N *by sentAt* (a frame-global
    //      key) and applies the quota to our own sends too, so every node converges on the identical carried
    //      id-set instead of a chatty originator (which bypassed the quota) permanently out-diverging capped
    //      carriers and churning the cue plane forever. No wire change; destructive DB migration as usual.
    // v19: forward_store gained a denormalized `attachmentHash` column so a carrier can custody the image blob a
    //      carried chat frame references (pull + hold it, pinned against GC while carried). E2E chat frames now
    //      also carry the (ciphertext) hash in cleartext so carriers — blind to the sealed content — can see it;
    //      additive wire change, no SERVICE_NAME bump (the column is local, the digest still folds only `id`).
    //      Destructive DB migration as usual (app not yet public).
    // v20: messages gained quoted-reply columns (replyToId/replyToAuthorId/replyToAuthor/replyToSnippet/
    //      replyToHasAttachment) snapshotting the message a reply quotes, so the quote renders even if the
    //      original never arrived. Additive wire change (a new nullable ReplyRef on ChatContent + the
    //      encrypted MessageContent), so no SERVICE_NAME bump; destructive DB migration as usual.
    // v21: nodeId widened to 128 bits — a new base32 SHA-256 derivation (was ~41-bit 8-char). A *breaking*
    //      identity/wire change (every node re-derives a different id from the same keypair), so the mesh
    //      SERVICE_NAME (.v6 → .v7) and BLE SERVICE_UUID (0xFE30 → 0xFE31) bump in lockstep to partition old
    //      builds at discovery, and this destructive wipe clears the now-stale pins + old-format custodied
    //      frames. No schema-column change — the version bump is the coordinated wire/DB break the format
    //      requires (see docs/WIRE_COMPAT.md). The keypair itself is untouched (it lives outside the DB).
    version = 21,
    exportSchema = false,
)
abstract class KnitDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao

    abstract fun peerDao(): PeerDao

    abstract fun reactionDao(): ReactionDao

    abstract fun blobDao(): BlobDao

    abstract fun groupDao(): GroupDao

    abstract fun blobVerdictDao(): BlobVerdictDao

    abstract fun forwardDao(): ForwardDao

    companion object {
        /**
         * Builds the encrypted database. [passphrase] is the SQLCipher key (see
         * [app.getknit.knit.data.crypto.DatabaseKey]); SQLCipher zeroes it once the DB is opened.
         * The native `libsqlcipher.so` must be loaded explicitly before the factory is created.
         */
        fun build(
            context: Context,
            passphrase: ByteArray,
        ): KnitDatabase {
            System.loadLibrary("sqlcipher")
            return Room
                .databaseBuilder(context, KnitDatabase::class.java, "knit.db")
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .fallbackToDestructiveMigration(dropAllTables = true)
                .addCallback(
                    object : Callback() {
                        override fun onDestructiveMigration(db: SupportSQLiteDatabase) {
                            // The v6 destructive migration drops the rows that referenced on-disk images,
                            // so those plaintext files are now orphaned. Delete them — leaving decrypted
                            // images on disk would defeat the point of moving them into the encrypted DB.
                            purgeLegacyImageFiles(context)
                        }
                    },
                ).build()
        }

        /** Removes the pre-v6 plaintext avatar/attachment files (own + peer avatars, attachments, staging). */
        private fun purgeLegacyImageFiles(context: Context) {
            val files = context.filesDir
            files
                .listFiles { f -> f.name.startsWith("avatar-") && f.name.endsWith(".jpg") }
                ?.forEach { it.delete() }
            File(files, "avatar.jpg").delete() // legacy pre-content-addressing own avatar
            File(files, "attachments").deleteRecursively()
            context.cacheDir
                .listFiles { f ->
                    (f.name.startsWith("avatar-") && f.name.endsWith(".jpg")) || f.name.startsWith("attach-")
                }?.forEach { it.delete() }
        }
    }
}
