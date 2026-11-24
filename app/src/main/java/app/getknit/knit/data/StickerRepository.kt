package app.getknit.knit.data

import androidx.room3.withWriteTransaction
import app.getknit.knit.data.sticker.Sticker
import app.getknit.knit.data.sticker.StickerDao
import app.getknit.knit.data.sticker.StickerEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The composer's sticker tray (ADR 2026-11.rk6d): the stickers the user saved, and the ones this phone sent lately.
 * Local to this phone: nothing here is framed, a saved row exists only while the sticker is saved, and removing it
 * deletes the row. A saved sticker names the blob it was saved from and keeps it from the blob collection
 * ([BlobRepository]'s reference rules count the table); removing the last reference lets the bytes go.
 */
class StickerRepository(
    private val dao: StickerDao,
    private val blobs: BlobRepository,
    private val db: KnitDatabase,
) {
    /** Saved stickers whose bytes this phone holds, newest saved first. */
    fun observeSaved(): Flow<List<Sticker>> = dao.observeSaved()

    /** The stickers [me] sent lately, one per picture, newest first, at most [RECENT_LIMIT]. */
    fun observeRecent(me: String): Flow<List<Sticker>> = dao.observeRecent(me, RECENT_LIMIT)

    /** The plain hashes of the saved stickers, for the message menu's Save / Remove choice. */
    fun observeSavedHashes(): Flow<Set<String>> = dao.observeSavedHashes().map { it.toSet() }

    /**
     * Saves [sticker] at [now], and does nothing when it is saved already, so a second tap that lands before the menu
     * has caught up cannot fail. The tray holds at most [MAX_SAVED]: a save past that is refused, and the result says so,
     * so the screen can ask the person to remove one; unlike a pin, a saved sticker is never let go behind their back.
     * One write transaction covers the reads and the insert.
     */
    suspend fun save(
        sticker: Sticker,
        now: Long,
    ): StickerSaveOutcome =
        db.withWriteTransaction {
            when {
                dao.isSaved(sticker.plainHash) -> {
                    StickerSaveOutcome.AlreadySaved
                }

                dao.count() >= MAX_SAVED -> {
                    StickerSaveOutcome.Full
                }

                else -> {
                    dao.insert(
                        StickerEntity(
                            plainHash = sticker.plainHash,
                            blobHash = sticker.blobHash,
                            blobKey = sticker.blobKey,
                            mime = sticker.mime,
                            animated = sticker.animated,
                            savedAt = now,
                        ),
                    )
                    StickerSaveOutcome.Saved
                }
            }
        }

    /**
     * Removes the saved sticker [plainHash], then lets its bytes go unless something else still names them — the message
     * that brought them, most often ([BlobRepository.deleteIfUnreferenced]). True when a row was removed.
     */
    suspend fun remove(plainHash: String): Boolean {
        val blobHash: String =
            db.withWriteTransaction<String?> {
                val hash = dao.blobHashOf(plainHash)
                if (hash != null) dao.delete(plainHash)
                hash
            } ?: return false
        blobs.deleteIfUnreferenced(blobHash)
        return true
    }

    companion object {
        /** The most stickers the tray saves: a few screens of the grid, and a bound on the bytes the table keeps. */
        const val MAX_SAVED = 120

        /** How many recently sent stickers the tray offers. */
        const val RECENT_LIMIT = 24
    }
}

/** What [StickerRepository.save] did. */
enum class StickerSaveOutcome {
    /** The sticker is saved now. */
    Saved,

    /** It was saved already; nothing changed. */
    AlreadySaved,

    /** The tray is full ([StickerRepository.MAX_SAVED]); nothing changed. */
    Full,
}
