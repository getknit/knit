package app.getknit.knit.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Round-trips [SettingsStore] over a real Preferences DataStore backed by a temp file. The DataStore's
 * internal actor is launched in [TestScope.backgroundScope] so `runTest` doesn't hang waiting for it to
 * complete. No Android framework is needed — `PreferenceDataStoreFactory` is pure JVM.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val counter = AtomicInteger()

    // A fresh store (own file) per call, so tests never share DataStore state.
    private fun TestScope.newStore(): SettingsStore {
        val file = File(tmp.root, "settings-${counter.incrementAndGet()}.preferences_pb")
        return SettingsStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { file })
    }

    @Test
    fun `name and status default to empty`() =
        runTest {
            val store = newStore()
            assertEquals("", store.displayName.first())
            assertEquals("", store.status.first())
        }

    @Test
    fun `setProfile persists both name and status`() =
        runTest {
            val store = newStore()
            store.setProfile(name = "Ada", status = "hello mesh")
            assertEquals("Ada", store.displayName.first())
            assertEquals("hello mesh", store.status.first())
        }

    @Test
    fun `individual name and status setters persist`() =
        runTest {
            val store = newStore()
            store.setDisplayName("Grace")
            store.setStatus("offline")
            assertEquals("Grace", store.displayName.first())
            assertEquals("offline", store.status.first())
        }

    @Test
    fun `block adds node id and device tag, unblock removes both`() =
        runTest {
            val store = newStore()
            store.block("node-a", deviceTag = "tag-a")
            store.block("node-b", deviceTag = null)

            assertEquals(setOf("node-a", "node-b"), store.blockedNodeIds.first())
            assertEquals(setOf("tag-a"), store.blockedDeviceTags.first())

            store.unblock("node-a", deviceTag = "tag-a")
            assertEquals(setOf("node-b"), store.blockedNodeIds.first())
            assertTrue(store.blockedDeviceTags.first().isEmpty())
        }

    @Test
    fun `last-read watermarks are keyed per conversation and read back as a stripped map`() =
        runTest {
            val store = newStore()
            store.setLastReadAt("nearby", 100L)
            store.setLastReadAt("node-x", 250L)

            assertEquals(mapOf("nearby" to 100L, "node-x" to 250L), store.lastReadAll.first())
            assertEquals(250L, store.lastReadAt("node-x").first())
            assertEquals(0L, store.lastReadAt("never-read").first())
        }

    @Test
    fun `own avatar hash sets and clears back to null`() =
        runTest {
            val store = newStore()
            assertNull(store.ownAvatarHash.first())
            store.setOwnAvatarHash("abc123")
            assertEquals("abc123", store.ownAvatarHash.first())
            store.clearOwnAvatarHash()
            assertNull(store.ownAvatarHash.first())
        }

    @Test
    fun `content filtering defaults on and can be toggled off`() =
        runTest {
            val store = newStore()
            assertTrue(store.contentFilteringEnabled.first())
            store.setContentFilteringEnabled(false)
            assertEquals(false, store.contentFilteringEnabled.first())
        }

    @Test
    fun `profile version and avatar timestamp round-trip`() =
        runTest {
            val store = newStore()
            assertEquals(0L, store.profileVersion.first())
            store.setProfileVersion(7L)
            store.setAvatarUpdatedAt(4242L)
            assertEquals(7L, store.profileVersion.first())
            assertEquals(4242L, store.avatarUpdatedAt.first())
        }

    @Test
    fun `recordReviewAttempt stamps the time and increments the lifetime count`() =
        runTest {
            val store = newStore()
            store.recordReviewAttempt(now = 1_000L)
            store.recordReviewAttempt(now = 2_000L)
            assertEquals(2_000L, store.reviewLastAttemptAt.first())
            assertEquals(2L, store.reviewAttemptCount.first())
        }

    @Test
    fun `clearReviewState resets engagement, attempt time, and count`() =
        runTest {
            val store = newStore()
            store.setReviewEngagementStartedAt(500L)
            store.recordReviewAttempt(now = 1_000L)

            store.clearReviewState()

            assertEquals(0L, store.reviewEngagementStartedAt.first())
            assertEquals(0L, store.reviewLastAttemptAt.first())
            assertEquals(0L, store.reviewAttemptCount.first())
        }
}
