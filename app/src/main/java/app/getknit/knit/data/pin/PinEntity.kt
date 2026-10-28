package app.getknit.knit.data.pin

import androidx.room3.Entity

/**
 * One message pinned to the top of its chat on this phone (#42, ADR 2026-10.2r7e). A row exists while the
 * message is pinned, and an unpin deletes it: pins never leave the phone, so there is nothing for a tombstone to
 * converge with.
 *
 * Keyed by [messageId] alone: a message is pinned or it is not. [pinnedAt] orders a chat's pins (newest first on the
 * bar) and picks the one a full chat lets go of. It is this phone's wall clock, but never below one past the thread's
 * newest pin ([app.getknit.knit.data.PinRepository.setPinned]), so a pin made later always sorts newer, even after a
 * clock that ran ahead has been put right.
 *
 * There is no foreign key to `messages`, as with `reactions`: every read joins `messages`, so a pin whose message is
 * gone shows nowhere, and the startup sweep ([PinDao.deleteOrphans]) reclaims the row.
 */
@Entity(
    tableName = "pins",
    primaryKeys = ["messageId"],
)
data class PinEntity(
    val messageId: String,
    val pinnedAt: Long,
)
