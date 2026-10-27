package app.getknit.knit.data

import androidx.room3.withWriteTransaction
import app.getknit.knit.data.pin.PinDao
import app.getknit.knit.data.pin.PinEntity
import app.getknit.knit.data.pin.PinnedMessage
import kotlinx.coroutines.flow.Flow

/**
 * Single source of truth for pinned messages (#42). Pins are local to this phone for now: nothing here is framed,
 * so a pin shows only on the phone that made it. [apply] owns the last-writer-wins rule the reactions use, so a
 * later shared pin can land through it unchanged.
 */
class PinRepository(
    private val dao: PinDao,
    private val db: KnitDatabase,
) {
    /** Live pins in one thread, newest pin first, each with its message — the pinned bar. */
    fun observePinnedIn(conversationId: String): Flow<List<PinnedMessage>> = dao.observePinnedIn(conversationId)

    /**
     * Applies a pin or an unpin (a [PinEntity.pinnedBy] of null), keeping the stored row when this one is not
     * strictly newer — [ReactionRepository.apply]'s guard, so out-of-order pins and unpins converge on the newest.
     * Returns whether the message's pinned state changed.
     */
    suspend fun apply(pin: PinEntity): Boolean =
        db.withWriteTransaction {
            val current = dao.find(pin.messageId)
            if (current != null && pin.updatedAt <= current.updatedAt) return@withWriteTransaction false
            dao.upsert(pin)
            (current?.pinnedBy != null) != (pin.pinnedBy != null)
        }

    /**
     * Pins [messageId] for [me] at [now], or unpins it when it is pinned already — the long-press menu's one
     * action. A thread holds at most [MAX_PER_CHAT] pins: a pin past that unpins the thread's oldest, and the
     * result says so, so the screen can tell the person which one went. A message this phone does not hold is
     * left alone.
     *
     * The tap must take effect even when this phone's clock has gone back since the stored stamp, so the new stamp
     * is one past the stored one at least. One write transaction covers the read, the count and both writes, so a
     * second tap racing this one can neither double-count nor resurrect what it just unpinned.
     */
    suspend fun toggle(
        messageId: String,
        me: String,
        now: Long,
    ): PinOutcome =
        db.withWriteTransaction {
            val conversationId = dao.conversationOf(messageId) ?: return@withWriteTransaction PinOutcome.Unchanged
            val current = dao.find(messageId)
            val stamp = maxOf(now, (current?.updatedAt ?: Long.MIN_VALUE) + 1)
            if (current?.pinnedBy != null) {
                dao.upsert(PinEntity(messageId, pinnedBy = null, updatedAt = stamp))
                return@withWriteTransaction PinOutcome.Unpinned
            }
            dao.upsert(PinEntity(messageId, pinnedBy = me, updatedAt = stamp))
            val live = dao.livePinIdsIn(conversationId)
            val over = live.filter { it != messageId }.take((live.size - MAX_PER_CHAT).coerceAtLeast(0))
            over.forEach { old ->
                val stored = dao.find(old)?.updatedAt ?: Long.MIN_VALUE
                dao.upsert(PinEntity(old, pinnedBy = null, updatedAt = maxOf(stamp, stored + 1)))
            }
            if (over.isEmpty()) PinOutcome.Pinned else PinOutcome.PinnedReplacingOldest
        }

    /** Removes a deleted message's pin, since the pins table has no FK cascade. */
    suspend fun deleteForMessage(messageId: String) = dao.deleteForMessage(messageId)

    /**
     * Reclaims pins whose message is gone — deleted with its thread, or trimmed by retention — since the table has
     * no FK cascade. Pins younger than [ORPHAN_GRACE_MILLIS] are kept, as reactions are
     * ([ReactionRepository.deleteOrphans]), so a shared pin that arrives ahead of its message will survive the sweep.
     */
    suspend fun deleteOrphans(now: Long) = dao.deleteOrphansOlderThan(now - ORPHAN_GRACE_MILLIS)

    companion object {
        /** The most pins one thread holds, as WhatsApp allows; a fourth unpins the oldest. */
        const val MAX_PER_CHAT = 3

        // The reactions' grace (ReactionRepository): a day comfortably exceeds any out-of-order gap.
        private const val ORPHAN_GRACE_MILLIS = 24L * 60 * 60 * 1000
    }
}

/** What [PinRepository.toggle] did. */
enum class PinOutcome {
    /** The message is pinned now. */
    Pinned,

    /** The message is pinned now, and the thread's oldest pin was unpinned to keep it at [PinRepository.MAX_PER_CHAT]. */
    PinnedReplacingOldest,

    /** The message was pinned and is not any more. */
    Unpinned,

    /** Nothing changed: this phone does not hold the message. */
    Unchanged,
}
