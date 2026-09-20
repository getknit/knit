package app.getknit.knit.mesh

import android.util.Log
import app.getknit.knit.data.settings.CloneWatchSettings
import kotlinx.coroutines.flow.first

/**
 * Notices that this identity is running on another phone (work item #80, ADR 2026-09.ypcc) — a backup
 * restored twice — from evidence already on the inbound path, with no wire change.
 *
 * The evidence is a `profile` frame under our own node id that this phone never minted. Only our own key
 * can sign one (`InboundPipeline.verifierBundle` checks a self frame against our own bundle), and every
 * profile frame this phone ever published carries [CloneWatchSettings.profilePublishedAt] as its id and
 * `sentAt` (`MeshManager.currentProfileEnvelope`; `nextPublishStamp` only ever moves up). So a self profile
 * whose `sentAt` is **past** our stamp came from a phone holding the same key — a twin. Everything at or
 * below the stamp is our own history, re-served by a peer's custody after a wipe, and never evidence.
 *
 * Why the stamp and not a chat frame. A DM-form frame under our id that we hold no record of looks like
 * the same proof, but the record is the database, and a `DatabaseKey` wipe empties custody and messages
 * while the DataStore — and the stamp — survive: every send of the last day, re-served by a peer, would
 * then be a false twin. The stamp lives in the file that survives, so it has no such hole. The one cost is
 * latency on the far side: the twin sees *us* only once we publish a stamp newer than its own, which is
 * why a detection floods our profile once ([onDetected] → `MeshManager.broadcastProfile`) — the other
 * phone then sees a newer stamp within a contact, and its own flood finds us already lit, so nothing
 * bounces (the transition fires once per not-visible → visible edge, never while the banner is up).
 *
 * The evidence is gated on [CloneWatchSettings.restorePending]: until `MeshManager.finishRestore` bumps
 * the stamp past everything the old phone published, the backup's stale stamp would make the old phone's
 * last republishes look like a twin. After a dismissal only a frame stamped past
 * [CloneWatchSettings.cloneDismissedAt] re-raises the banner — a twin still publishing brings it back, a
 * custody re-serve of what it published before the user wiped it does not.
 */
class CloneWatch(
    private val settings: CloneWatchSettings,
    private val clock: () -> Long,
    /** The not-visible → visible edge, once: the profile counter-flood. Runs on the pipeline's coroutine. */
    private val onDetected: suspend () -> Unit,
) {
    /** A verified `profile` frame under our own node id arrived carrying [sentAt] as its publish stamp. */
    suspend fun onSelfProfile(sentAt: Long) {
        val dismissedAt = settings.cloneDismissedAt.first()
        val evidence =
            isEvidence(
                frameSentAt = sentAt,
                ourPublishedAt = settings.profilePublishedAt.first(),
                dismissedAt = dismissedAt,
                restorePending = settings.restorePending.first(),
            )
        if (!evidence) return
        val seenAt = settings.cloneSeenAt.first()
        val wasVisible = seenAt > dismissedAt
        // Strictly past both marks, so the banner lands even against a clock that stood still or slipped.
        settings.setCloneSeenAt(maxOf(clock(), seenAt + 1, dismissedAt + 1))
        if (wasVisible) return
        Log.w(TAG, "this identity is also active on another phone (stamp $sentAt past ours); re-flooding our profile")
        onDetected()
    }

    companion object {
        private const val TAG = "CloneWatch"

        /**
         * The pure rule: [frameSentAt] is proof of a twin when it is past everything this phone published
         * ([ourPublishedAt]), past the user's last dismissal ([dismissedAt], 0 when never), and the phone is
         * not still inside a restore's first start ([restorePending]). Raw `sentAt`, never clamped — a
         * far-future stamp can only come from our own key.
         */
        fun isEvidence(
            frameSentAt: Long,
            ourPublishedAt: Long,
            dismissedAt: Long,
            restorePending: Boolean,
        ): Boolean = !restorePending && frameSentAt > ourPublishedAt && frameSentAt > dismissedAt
    }
}
