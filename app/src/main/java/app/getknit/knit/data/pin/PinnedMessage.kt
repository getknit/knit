package app.getknit.knit.data.pin

import androidx.room3.Embedded
import app.getknit.knit.data.message.MessageEntity

/**
 * A live pin with the message it names — what the pinned bar under a chat's title draws ([PinDao.observePinnedIn]).
 * [pinnedAt] is the pin's stored last-writer-wins clock ([PinEntity.updatedAt]), which orders a chat's pins newest
 * first and picks the one a full chat lets go of.
 */
data class PinnedMessage(
    @Embedded val message: MessageEntity,
    val pinnedBy: String,
    val pinnedAt: Long,
)
