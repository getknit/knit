package app.getknit.knit.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.getknit.knit.data.emoji.RecentReactions
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `recent reactions default to the classic six until the first pick`() =
        runTest {
            val store = newStore()
            assertEquals(RecentReactions.DEFAULTS, store.recentReactions.first())
        }

    @Test
    fun `recordReaction fronts the pick, dedupes, persists and caps`() =
        runTest {
            val store = newStore()
            store.recordReaction("🦄")
            assertEquals(listOf("🦄") + RecentReactions.DEFAULTS, store.recentReactions.first())
            store.recordReaction("😂")
            assertEquals(listOf("😂", "🦄", "👍", "❤️", "😮", "😢", "🙏"), store.recentReactions.first())
            repeat(13) { store.recordReaction("e$it") }
            val recents = store.recentReactions.first()
            assertEquals(RecentReactions.KEPT, recents.size)
            assertEquals("e12", recents.first())
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
    fun `link previews default off and round-trip on`() =
        runTest {
            val store = newStore()
            assertFalse(store.linkPreviewsEnabled.first())
            store.setLinkPreviewsEnabled(true)
            assertTrue(store.linkPreviewsEnabled.first())
            store.setLinkPreviewsEnabled(false)
            assertFalse(store.linkPreviewsEnabled.first())
        }

    @Test
    fun `open to chat defaults off and round-trips on`() =
        runTest {
            val store = newStore()
            assertFalse(store.openToChat.first())
            store.setOpenToChat(true)
            assertTrue(store.openToChat.first())
        }

    @Test
    fun `the open-to-chat cue state is written as one unit`() =
        runTest {
            val store = newStore()
            assertEquals(emptySet<String>(), store.openToChatNamed.first())
            assertEquals(0L, store.openToChatLastPostAt.first())
            store.setOpenToChatCueState(setOf("a|1", "a|2", "b|3"), 42L)
            assertEquals(setOf("a|1", "a|2", "b|3"), store.openToChatNamed.first())
            assertEquals(42L, store.openToChatLastPostAt.first())
        }

    @Test
    fun `mesh enabled defaults on and round-trips off then back on`() =
        runTest {
            val store = newStore()
            assertTrue(store.meshEnabled.first())
            store.setMeshEnabled(false)
            assertEquals(false, store.meshEnabled.first())
            store.setMeshEnabled(true)
            assertTrue(store.meshEnabled.first())
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

    @Test
    fun `the Internet plane is off with no spools until something configures it`() =
        runTest {
            val store = newStore()
            assertEquals(false, store.spoolEnabled.first())
            assertEquals(emptySet<String>(), store.spoolUrls.first())
        }

    @Test
    fun `default spools seed once and never come back after the user removes one`() =
        runTest {
            val store = newStore()
            val default = "wss://lax.spool.getknit.app/spool/v1"

            store.seedDefaultSpools(listOf(default))
            assertEquals(setOf(default), store.spoolUrls.first())
            // Seeding a spool must not switch the plane on — the two decisions are separate.
            assertEquals(false, store.spoolEnabled.first())

            store.removeSpoolUrl(default)
            store.seedDefaultSpools(listOf(default)) // every app start re-runs this
            assertEquals("a removed default must stay removed", emptySet<String>(), store.spoolUrls.first())
        }

    @Test
    fun `a relay is in use until the user parks it, and the plane still gates the lot`() =
        runTest {
            val store = newStore()
            val a = "wss://a.example/spool/v1"
            val b = "wss://b.example/spool/v1"
            store.addSpoolUrl(a)
            store.addSpoolUrl(b)

            // Nothing is parked by default, so a list that predates this setting keeps working unchanged.
            assertEquals(emptySet<String>(), store.disabledSpoolUrls.first())
            // ...but the master switch still decides whether any of them is dialled.
            assertEquals(emptySet<String>(), store.activeSpoolUrls.first())

            store.setSpoolEnabled(true)
            assertEquals(setOf(a, b), store.activeSpoolUrls.first())

            store.setSpoolUrlEnabled(a, false)
            assertEquals(setOf(a), store.disabledSpoolUrls.first())
            assertEquals("a parked relay must not be dialled", setOf(b), store.activeSpoolUrls.first())
            assertEquals("parking is not removing", setOf(a, b), store.spoolUrls.first())

            store.setSpoolUrlEnabled(a, true)
            assertEquals(setOf(a, b), store.activeSpoolUrls.first())
        }

    @Test
    fun `removing a parked relay forgets that it was parked`() =
        runTest {
            // Otherwise re-adding the same address later brings it back silently switched off, which reads
            // as the app ignoring the user.
            val store = newStore()
            val url = "wss://a.example/spool/v1"
            store.setSpoolEnabled(true)
            store.addSpoolUrl(url)
            store.setSpoolUrlEnabled(url, false)

            store.removeSpoolUrl(url)
            assertEquals(emptySet<String>(), store.disabledSpoolUrls.first())

            store.addSpoolUrl(url)
            assertEquals(setOf(url), store.activeSpoolUrls.first())
        }

    @Test
    fun `seeded defaults arrive in use`() =
        runTest {
            val store = newStore()
            val default = "wss://lax.spool.getknit.app/spool/v1"
            store.seedDefaultSpools(listOf(default))
            store.setSpoolEnabled(true)
            assertEquals(setOf(default), store.activeSpoolUrls.first())
        }

    @Test
    fun `seeding preserves a spool the user added and tolerates an empty default list`() =
        runTest {
            val store = newStore()
            store.addSpoolUrl("wss://mine.example/spool/v1")

            store.seedDefaultSpools(listOf("wss://lax.spool.getknit.app/spool/v1"))
            assertEquals(
                setOf("wss://mine.example/spool/v1", "wss://lax.spool.getknit.app/spool/v1"),
                store.spoolUrls.first(),
            )

            val bare = newStore()
            bare.seedDefaultSpools(emptyList())
            assertEquals(emptySet<String>(), bare.spoolUrls.first())
        }

    @Test
    fun `model load state defaults to nothing attempted, and round-trips per model`() =
        runTest {
            val store = newStore()
            assertEquals(ModelLoadState.NONE, store.modelLoadState("toxicity"))

            store.setModelLoadState("toxicity", ModelLoadState("16|rom", pendingSince = 1_700L, fails = 1))

            assertEquals(ModelLoadState("16|rom", 1_700L, 1), store.modelLoadState("toxicity"))
            // Keyed per model: latching the text classifier must not touch the image one.
            assertEquals(ModelLoadState.NONE, store.modelLoadState("nsfw"))
            assertEquals(ModelLoadState("16|rom", 1_700L, 1), store.observeModelLoad("toxicity").first())
        }
}
