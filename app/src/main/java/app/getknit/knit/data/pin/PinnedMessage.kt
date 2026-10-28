package app.getknit.knit.data.pin

import androidx.room3.Embedded
import app.getknit.knit.data.message.MessageEntity

/**
 * A pin with the message it names — what the pinned bar under a chat's title draws ([PinDao.observePinnedIn]).
 * [pinnedAt] is the pin's stored stamp ([PinEntity.pinnedAt]), which orders a chat's pins newest first.
 */
data class PinnedMessage(
    @Embedded val message: MessageEntity,
    val pinnedAt: Long,
)
