package app.getknit.knit.mesh

import app.getknit.knit.data.settings.CloneWatchSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloneWatchTest {
    /** The settings slice in memory: our stamp, the restore flag and the two banner stamps. */
    private class FakeSettings(
        published: Long,
        restore: Boolean = false,
    ) : CloneWatchSettings {
        override val profilePublishedAt = MutableStateFlow(published)
        override val restorePending = MutableStateFlow(restore)
        override val cloneSeenAt = MutableStateFlow(0L)
        override val cloneDismissedAt = MutableStateFlow(0L)

        override suspend fun setCloneSeenAt(value: Long) {
            cloneSeenAt.value = value
        }

        fun dismiss(at: Long) {
            cloneDismissedAt.value = at
        }
    }

    private class Rig(
        published: Long = 1_000,
        restore: Boolean = false,
        var now: Long = 5_000,
    ) {
        val settings = FakeSettings(published, restore)
        var detections = 0
        val watch = CloneWatch(settings, { now }) { detections++ }

        val visible: Boolean get() = settings.cloneSeenAt.value > settings.cloneDismissedAt.value
    }

    // --- the pure rule ---

    @Test
    fun aStampPastOursIsEvidenceAndOneAtOrBelowItIsNot() {
        assertTrue(CloneWatch.isEvidence(frameSentAt = 1_001, ourPublishedAt = 1_000, dismissedAt = 0, restorePending = false))
        assertFalse("our own current frame", CloneWatch.isEvidence(1_000, 1_000, 0, false))
        assertFalse("our own older frame re-served", CloneWatch.isEvidence(999, 1_000, 0, false))
    }

    @Test
    fun aRestoreInProgressSuppressesEvidence() {
        assertFalse(CloneWatch.isEvidence(frameSentAt = 9_999, ourPublishedAt = 1_000, dismissedAt = 0, restorePending = true))
    }

    @Test
    fun aStampAtOrBelowTheDismissalIsNotEvidenceButOnePastItIs() {
        assertFalse("re-served pre-dismissal frame", CloneWatch.isEvidence(2_000, 1_000, 2_000, false))
        assertTrue(CloneWatch.isEvidence(2_001, 1_000, 2_000, false))
    }

    // --- the watch ---

    @Test
    fun aTwinStampLightsTheBannerAndFloodsOnce() =
        runTest {
            val rig = Rig()
            rig.watch.onSelfProfile(sentAt = 1_001)
            assertTrue(rig.visible)
            assertEquals(5_000, rig.settings.cloneSeenAt.value)
            assertEquals(1, rig.detections)
        }

    @Test
    fun ourOwnReServedHistoryChangesNothing() =
        runTest {
            val rig = Rig()
            rig.watch.onSelfProfile(sentAt = 1_000)
            rig.watch.onSelfProfile(sentAt = 500)
            assertFalse(rig.visible)
            assertEquals(0, rig.detections)
        }

    @Test
    fun aRestorePendingPhoneIgnoresTheOldPhonesLaterStamps() =
        runTest {
            val rig = Rig(restore = true)
            rig.watch.onSelfProfile(sentAt = 1_001)
            assertFalse(rig.visible)
            assertEquals(0, rig.detections)
        }

    @Test
    fun aSecondSightingWhileLitRecordsButNeverFloodsAgain() =
        runTest {
            val rig = Rig()
            rig.watch.onSelfProfile(sentAt = 1_001)
            rig.now = 6_000
            rig.watch.onSelfProfile(sentAt = 1_002)
            assertEquals("the sighting moved", 6_000, rig.settings.cloneSeenAt.value)
            assertEquals("no ping-pong", 1, rig.detections)
        }

    @Test
    fun aDismissalHidesItUntilTheTwinPublishesAgain() =
        runTest {
            val rig = Rig()
            rig.watch.onSelfProfile(sentAt = 1_001)
            rig.settings.dismiss(at = 7_000)
            assertFalse(rig.visible)

            // The twin's pre-dismissal frame, re-served by a peer's custody: stays hidden.
            rig.now = 8_000
            rig.watch.onSelfProfile(sentAt = 6_000)
            assertFalse(rig.visible)
            assertEquals(1, rig.detections)

            // The twin is still there and published after the dismissal: back up, and one more flood.
            rig.watch.onSelfProfile(sentAt = 7_001)
            assertTrue(rig.visible)
            assertEquals(2, rig.detections)
        }

    @Test
    fun theSeenStampAlwaysLandsPastTheDismissalEvenOnAStuckClock() =
        runTest {
            val rig = Rig(now = 100)
            rig.settings.dismiss(at = 7_000)
            rig.watch.onSelfProfile(sentAt = 7_001)
            assertTrue("a clock behind the dismissal must not hide fresh evidence", rig.visible)
        }
}
