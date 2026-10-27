package app.getknit.knit.data.pin

import androidx.room3.Entity

/**
 * One message pinned to the top of its chat, as stored on this device (#42). Modelled on
 * [app.getknit.knit.data.reaction.ReactionEntity] so a later shared pin, an ADR 018-style control frame, can land
 * in this same table under the same last-writer-wins rule without another schema change.
 *
 * Keyed by [messageId] alone: a message is pinned or it is not, whoever pinned it. [pinnedBy] is the node id that
 * pinned it (today always this phone's own), and null is an unpin *tombstone*, not a deleted row: once pins are
 * shared, a null at a newer [updatedAt] must still beat a stale "pin" that arrives late, which a delete couldn't
 * do. The UI streams filter tombstones out (see [PinDao.observePinnedIn]).
 *
 * [updatedAt] is the pinning device's wall clock when the pin or unpin was made, and the comparator
 * [app.getknit.knit.data.PinRepository.apply] keeps the newer of. There is intentionally no foreign key to
 * `messages`, for the reactions' reason: a shared pin could arrive before the message it names. Every read joins
 * `messages`, so a pin whose message is gone from this phone shows nowhere, and the sweep reclaims it.
 *
 * Purely local today: never framed, never in custody, and no digest folds over it.
 */
@Entity(
    tableName = "pins",
    primaryKeys = ["messageId"],
)
data class PinEntity(
    val messageId: String,
    val pinnedBy: String?,
    val updatedAt: Long,
)
