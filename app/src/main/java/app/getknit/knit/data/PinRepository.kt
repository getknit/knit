package app.getknit.knit.data

import androidx.room3.withWriteTransaction
import app.getknit.knit.data.pin.PinDao
import app.getknit.knit.data.pin.PinEntity
import app.getknit.knit.data.pin.PinnedMessage
import kotlinx.coroutines.flow.Flow

/**
 * Single source of truth for pinned messages (#42, ADR 2026-10.2r7e). Pins are local to this phone: nothing here is
 * framed, so a pin shows only on the phone that made it, a row exists only while its message is pinned, and an
 * unpin deletes it.
 */
class PinRepository(
    private val dao: PinDao,
    private val db: KnitDatabase,
) {
    /** Pins in one thread, newest pin first, each with its message — the pinned bar. */
    fun observePinnedIn(conversationId: String): Flow<List<PinnedMessage>> = dao.observePinnedIn(conversationId)

    /**
     * Puts [messageId] in the [pinned] state at [now], and does nothing when it is in that state already, so a second
     * tap that lands before the bar has caught up cannot undo the first. A thread holds at most [MAX_PER_CHAT] pins: a
     * pin past that unpins the thread's oldest, and the result says so, so the screen can tell the person which one
     * went. A message this phone does not hold is never pinned.
     *
     * The new stamp is at least one past the thread's newest pin, not just [now]: eviction compares stamps across
     * messages, and a pin made while the clock ran ahead would otherwise stay "newest" after the clock was put right,
     * so the pins made after it would go first. One write transaction covers the reads, the count and both writes.
     */
    suspend fun setPinned(
        messageId: String,
        pinned: Boolean,
        now: Long,
    ): PinOutcome {
        if (!pinned) return if (dao.delete(messageId) > 0) PinOutcome.Unpinned else PinOutcome.Unchanged
        return db.withWriteTransaction {
            if (dao.isPinned(messageId)) return@withWriteTransaction PinOutcome.Unchanged
            val conversationId = dao.conversationOf(messageId) ?: return@withWriteTransaction PinOutcome.Unchanged
            val stamp = dao.newestPinIn(conversationId)?.let { maxOf(now, it + 1) } ?: now
            dao.insert(PinEntity(messageId, pinnedAt = stamp))
            // Oldest first, and the new pin is strictly the newest, so it is never among the ones let go.
            val pins = dao.pinIdsIn(conversationId)
            val over = pins.take((pins.size - MAX_PER_CHAT).coerceAtLeast(0))
            over.forEach { dao.delete(it) }
            if (over.isEmpty()) PinOutcome.Pinned else PinOutcome.PinnedReplacingOldest
        }
    }

    /** Removes a deleted message's pin, since the pins table has no FK cascade. */
    suspend fun deleteForMessage(messageId: String) {
        dao.delete(messageId)
    }

    /**
     * Unpins every message [senderId] sent — run when they are blocked. Their bubbles disappear, and a pin the person
     * can no longer see or unpin must not keep taking one of the thread's [MAX_PER_CHAT] places.
     */
    suspend fun unpinFrom(senderId: String) = dao.deleteFromSender(senderId)

    /**
     * Reclaims pins whose message is gone (deleted with its thread, or trimmed by retention), since the table has no FK
     * cascade, and pins on a message from someone in [blocked] that a block path missed. Run once at startup, beside
     * the reactions' sweep (`MeshManager`).
     */
    suspend fun deleteOrphans(blocked: Set<String>) = dao.deleteOrphans(blocked)

    companion object {
        /** The most pins one thread holds, as WhatsApp allows; a fourth unpins the oldest. */
        const val MAX_PER_CHAT = 3
    }
}

/** What [PinRepository.setPinned] did. */
enum class PinOutcome {
    /** The message is pinned now. */
    Pinned,

    /** The message is pinned now, and the thread's oldest pin was unpinned to keep it at [PinRepository.MAX_PER_CHAT]. */
    PinnedReplacingOldest,

    /** The message was pinned and is not any more. */
    Unpinned,

    /** Nothing changed: the message was in that state already, or this phone does not hold it. */
    Unchanged,
}
