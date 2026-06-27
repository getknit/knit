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
        GroupEntity::class, BlobVerdictEntity::class,
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
    version = 9,
    exportSchema = false,
)
abstract class KnitDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun peerDao(): PeerDao
    abstract fun reactionDao(): ReactionDao
    abstract fun blobDao(): BlobDao
    abstract fun groupDao(): GroupDao
    abstract fun blobVerdictDao(): BlobVerdictDao

    companion object {
        /**
         * Builds the encrypted database. [passphrase] is the SQLCipher key (see
         * [app.getknit.knit.data.crypto.DatabaseKey]); SQLCipher zeroes it once the DB is opened.
         * The native `libsqlcipher.so` must be loaded explicitly before the factory is created.
         */
        fun build(context: Context, passphrase: ByteArray): KnitDatabase {
            System.loadLibrary("sqlcipher")
            return Room.databaseBuilder(context, KnitDatabase::class.java, "knit.db")
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .fallbackToDestructiveMigration(dropAllTables = true)
                .addCallback(object : Callback() {
                    override fun onDestructiveMigration(db: SupportSQLiteDatabase) {
                        // The v6 destructive migration drops the rows that referenced on-disk images,
                        // so those plaintext files are now orphaned. Delete them — leaving decrypted
                        // images on disk would defeat the point of moving them into the encrypted DB.
                        purgeLegacyImageFiles(context)
                    }
                })
                .build()
        }

        /** Removes the pre-v6 plaintext avatar/attachment files (own + peer avatars, attachments, staging). */
        private fun purgeLegacyImageFiles(context: Context) {
            val files = context.filesDir
            files.listFiles { f -> f.name.startsWith("avatar-") && f.name.endsWith(".jpg") }
                ?.forEach { it.delete() }
            File(files, "avatar.jpg").delete() // legacy pre-content-addressing own avatar
            File(files, "attachments").deleteRecursively()
            context.cacheDir.listFiles { f ->
                (f.name.startsWith("avatar-") && f.name.endsWith(".jpg")) || f.name.startsWith("attach-")
            }?.forEach { it.delete() }
        }
    }
}
