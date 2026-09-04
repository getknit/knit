package app.getknit.knit.ui.profile

import app.getknit.knit.TextLimits
import app.getknit.knit.data.AvatarStore
import app.getknit.knit.data.BlobRepository
import app.getknit.knit.data.relay.RelayFacts
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.mesh.lora.LoraFacts
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Guards the "hold editable text locally, persist only on Save" pattern (see [ProfileViewModel]'s KDoc and
 * the AGENTS.md TextField gotcha): a naive refactor that binds the field straight to the DataStore flow
 * reintroduces the one-character-typing bug. These lock in the load / isDirty / normalize / save contract
 * that would catch it. Pure JVM — the only Android types ([Bitmap]/[Uri]) live in the avatar methods, which
 * these tests don't touch.
 */
class ProfileViewModelTest {
    private val settings = mockk<SettingsStore>(relaxed = true)
    private val identity = mockk<Identity>(relaxed = true)
    private val avatars = mockk<AvatarStore>(relaxed = true)
    private val blobs = mockk<BlobRepository>(relaxed = true)

    private val nameFlow = MutableStateFlow("Alice")
    private val statusFlow = MutableStateFlow("Hiking")
    private val avatarHashFlow = MutableStateFlow<String?>(null)
    private val filteringFlow = MutableStateFlow(true)
    private val openToChatFlow = MutableStateFlow(false)
    private val spoolEnabledFlow = MutableStateFlow(false)
    private val spoolUrlsFlow = MutableStateFlow(emptySet<String>())
    private val activeSpoolUrlsFlow = MutableStateFlow(emptySet<String>())

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { identity.nodeId() } returns "node-abc"
        every { settings.displayName } returns nameFlow
        every { settings.status } returns statusFlow
        every { settings.ownAvatarHash } returns avatarHashFlow
        every { settings.contentFilteringEnabled } returns filteringFlow
        every { settings.linkPreviewsEnabled } returns linkPreviewsFlow
        every { settings.openToChat } returns openToChatFlow
        every { settings.spoolEnabled } returns spoolEnabledFlow
        every { settings.spoolUrls } returns spoolUrlsFlow
        every { settings.activeSpoolUrls } returns activeSpoolUrlsFlow
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val linkPreviewsFlow = MutableStateFlow(false)

    /** Off until the user says otherwise, and a toggle writes straight through to the store. */
    @Test
    fun linkPreviewsMirrorTheStoreAndPersistOnToggle() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.linkPreviewsEnabled.collect {} }
            assertFalse(vm.linkPreviewsEnabled.value)
            linkPreviewsFlow.value = true
            assertTrue(vm.linkPreviewsEnabled.value)
            vm.setLinkPreviewsEnabled(false)
            coVerify { settings.setLinkPreviewsEnabled(false) }
        }

    private fun vm() = ProfileViewModel(settings, identity, avatars, blobs, MutableStateFlow(RelayFacts()), MutableStateFlow(LoraFacts()))

    /** The switch binds straight to the store (no keystroke lag to absorb) and persists on toggle, not on Save. */
    @Test
    fun openToChatMirrorsTheStoreAndPersistsOnToggle() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.openToChat.collect {} }
            advanceUntilIdle()
            assertFalse(vm.openToChat.value)
            openToChatFlow.value = true
            advanceUntilIdle()
            assertTrue(vm.openToChat.value)

            vm.setOpenToChat(false)
            advanceUntilIdle()
            coVerify { settings.setOpenToChat(false) }
        }

    @Test
    fun loadsPersistedProfileAndIsNotDirty() =
        runTest {
            val vm = vm()
            advanceUntilIdle()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.isDirty.collect {} }
            advanceUntilIdle()

            assertEquals("Alice", vm.displayName.value)
            assertEquals("Hiking", vm.status.value)
            assertFalse("freshly loaded profile is not dirty", vm.isDirty.value)
        }

    @Test
    fun editingNameMakesDirtyThenSavePersistsAndClearsDirty() =
        runTest {
            val vm = vm()
            advanceUntilIdle()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.isDirty.collect {} }
            val saves = mutableListOf<Unit>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.saved.collect { saves += it } }
            advanceUntilIdle()

            vm.setDisplayName("Bob")
            advanceUntilIdle()
            assertTrue("an edit that differs from the stored value is dirty", vm.isDirty.value)

            vm.save()
            advanceUntilIdle()
            coVerify { settings.setProfile("Bob", "Hiking") }
            assertFalse("saving snaps lastSaved to the new value, clearing dirty", vm.isDirty.value)
            assertEquals("save signals exactly once so the screen can pop", 1, saves.size)
        }

    @Test
    fun setDisplayNameCapsAtLimitWithoutNormalizing() =
        runTest {
            val vm = vm()
            advanceUntilIdle()

            vm.setDisplayName("x".repeat(100))
            assertEquals(TextLimits.DISPLAY_NAME, vm.displayName.value.length)

            // Held verbatim (a mid-word space isn't eaten on keystroke); normalization is deferred to save/commit.
            vm.setDisplayName("Bo b ")
            assertEquals("Bo b ", vm.displayName.value)
        }

    @Test
    fun commitDisplayNameNormalizesWhitespaceOnBlur() =
        runTest {
            val vm = vm()
            advanceUntilIdle()

            vm.setDisplayName("  Bo   b  ")
            vm.commitDisplayName()
            assertEquals("Bo b", vm.displayName.value)
        }

    @Test
    fun saveNormalizesBeforePersisting() =
        runTest {
            val vm = vm()
            advanceUntilIdle()

            vm.setDisplayName("  Bob  ")
            vm.setStatus("  on   a hike ")
            vm.save()
            advanceUntilIdle()

            coVerify { settings.setProfile("Bob", "on a hike") }
        }
}
