package app.getknit.knit.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import app.getknit.knit.data.message.MessageDao
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.peer.PeerDao
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.data.reaction.ReactionDao
import app.getknit.knit.data.reaction.ReactionEntity

@Database(
    entities = [MessageEntity::class, PeerEntity::class, ReactionEntity::class],
    // v2: messages gained attachmentHash/attachmentMime/attachmentPath (destructive migration).
    // v3: messages gained a mentions JSON column (destructive migration; app not yet public).
    // v4: added the reactions table (destructive migration; app not yet public).
    // v5: messages gained an indexed conversationId for 1:1 DMs (destructive migration; not public).
    version = 5,
    exportSchema = false,
)
abstract class KnitDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun peerDao(): PeerDao
    abstract fun reactionDao(): ReactionDao

    companion object {
        fun build(context: Context): KnitDatabase =
            Room.databaseBuilder(context, KnitDatabase::class.java, "knit.db")
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
