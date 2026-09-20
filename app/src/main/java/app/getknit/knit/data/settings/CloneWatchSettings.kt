package app.getknit.knit.data.settings

import kotlinx.coroutines.flow.Flow

/**
 * The slice of [SettingsStore] the same-identity clone watch reads and writes (`mesh/CloneWatch`, ADR
 * 2026-09.ypcc): our own publish stamp and restore state, which bound the evidence, and the two stamps
 * behind the "also active on another phone" banner. A seam like [InboundSettings], so the watch is tested
 * on a plain JVM without a Preferences DataStore. [SettingsStore] implements it verbatim.
 *
 * `setCloneDismissedAt` is deliberately not here: a dismissal is the user's act, written by the screens
 * through [SettingsStore] directly, never by the mesh.
 */
interface CloneWatchSettings {
    /** See [SettingsStore.profilePublishedAt] — the stamp every profile frame this phone minted carries. */
    val profilePublishedAt: Flow<Long>

    /** See [SettingsStore.restorePending] — true until the first start after a restore bumped the stamp. */
    val restorePending: Flow<Boolean>

    /** Wall-clock time of the newest clone evidence, 0 = never. Phone-local; a backup never carries it. */
    val cloneSeenAt: Flow<Long>

    /** Wall-clock time of the user's last dismissal, 0 = never. The banner shows while `cloneSeenAt` beats it. */
    val cloneDismissedAt: Flow<Long>

    suspend fun setCloneSeenAt(value: Long)
}
