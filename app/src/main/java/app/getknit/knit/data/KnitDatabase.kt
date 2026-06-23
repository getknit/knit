package app.getknit.knit.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import app.getknit.knit.data.message.MessageDao
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.peer.PeerDao
import app.getknit.knit.data.peer.PeerEntity

@Database(
    entities = [MessageEntity::class, PeerEntity::class],
    // v2: messages gained attachmentHash/attachmentMime/attachmentPath (destructive migration).
    version = 2,
    exportSchema = false,
)
abstract class KnitDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun peerDao(): PeerDao

    companion object {
        fun build(context: Context): KnitDatabase =
            Room.databaseBuilder(context, KnitDatabase::class.java, "knit.db")
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
