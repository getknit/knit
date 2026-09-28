@file:OptIn(ExperimentalCoroutinesApi::class) // UnconfinedTestDispatcher / setMain / advanceUntilIdle are experimental kotlinx APIs

package app.getknit.knit.ui.profile

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.viewModelScope
import app.getknit.knit.TextLimits
import app.getknit.knit.data.AvatarStore
import app.getknit.knit.data.BlobRepository
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.ui.util.CropRect
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
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
 * that would catch it. Pure JVM — the avatar cases mock the only Android types ([Bitmap]/[Uri]) they touch,
 * and pin that a confirmed photo is persisted on the application scope, so leaving the screen can't cancel
 * it (issue #26).
 */
class ProfileViewModelTest {
    private val settings = mockk<SettingsStore>(relaxed = true)
    private val identity = mockk<Identity>(relaxed = true)
    private val avatars = mockk<AvatarStore>(relaxed = true)
    private val blobs = mockk<BlobRepository>(relaxed = true)

    private val nameFlow = MutableStateFlow("Alice")
    private val statusFlow = MutableStateFlow("Hiking")
    private val avatarHashFlow = MutableStateFlow<String?>(null)
    private val openToChatFlow = MutableStateFlow(false)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { identity.nodeId() } returns "node-abc"
        every { settings.displayName } returns nameFlow
        every { settings.status } returns statusFlow
        every { settings.ownAvatarHash } returns avatarHashFlow
        every { settings.openToChat } returns openToChatFlow
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm(appScope: CoroutineScope = CoroutineScope(UnconfinedTestDispatcher())) =
        ProfileViewModel(settings, identity, avatars, blobs, appScope)

    /** Picks a (mocked) image so a crop is pending, as the photo picker's result does. */
    private fun ProfileViewModel.stageCrop() {
        val bitmap = mockk<Bitmap>()
        every { bitmap.width } returns 400
        every { bitmap.height } returns 400
        coEvery { avatars.loadForCrop(any()) } returns bitmap
        pickAvatar(mockk<Uri>())
    }

    /**
     * Issue #26: Save stays greyed out for a photo (it batches only the name and status), so the user backs
     * out — and on `viewModelScope` that cancelled a write still encoding, losing the photo. The write lives
     * on the application scope now, and lands the hash with its republish stamp in one edit.
     */
    @Test
    fun aConfirmedPhotoIsSavedEvenWhenTheScreenIsLeftMidWrite() =
        runTest {
            val encoding = CompletableDeferred<String>()
            coEvery { avatars.saveOwnAvatar(any(), any<CropRect>()) } coAnswers { encoding.await() }
            avatarHashFlow.value = "old-hash"
            val vm = vm(appScope = this)
            advanceUntilIdle()
            vm.stageCrop()
            advanceUntilIdle()

            vm.confirmCrop(scale = 1f, offset = Offset.Zero, diameter = 200f)
            advanceUntilIdle()
            vm.viewModelScope.cancel() // Back pops the screen while the photo is still encoding
            encoding.complete("new-hash")
            advanceUntilIdle()

            coVerify(exactly = 1) { settings.setOwnAvatar("new-hash", any()) }
            coVerify(exactly = 0) { settings.setOwnAvatarHash(any()) }
            coVerify(exactly = 0) { settings.setAvatarUpdatedAt(any()) }
            coVerify { blobs.deleteIfUnreferenced("old-hash") }
        }

    /** The screen's only sign a photo change landed: nothing else on it moves (#26). */
    @Test
    fun aPhotoChangeIsSignalledOnceItLands() =
        runTest {
            coEvery { avatars.saveOwnAvatar(any(), any<CropRect>()) } returns "new-hash"
            val vm = vm(appScope = this)
            val changes = mutableListOf<PhotoChange>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.photoChanged.collect { changes += it } }
            advanceUntilIdle()
            vm.stageCrop()
            advanceUntilIdle()

            vm.confirmCrop(scale = 1f, offset = Offset.Zero, diameter = 200f)
            advanceUntilIdle()
            assertEquals(listOf(PhotoChange.UPDATED), changes)
            assertEquals("the crop is dismissed", null, vm.cropTarget.value)
        }

    @Test
    fun clearingThePhotoDropsTheHashWithItsStampAndReclaimsTheBlob() =
        runTest {
            avatarHashFlow.value = "old-hash"
            val vm = vm(appScope = this)
            val changes = mutableListOf<PhotoChange>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.photoChanged.collect { changes += it } }
            advanceUntilIdle()

            vm.clearAvatar()
            vm.viewModelScope.cancel() // leaving at once must not undo it either
            advanceUntilIdle()

            coVerify(exactly = 1) { settings.setOwnAvatar(null, any()) }
            coVerify { blobs.deleteIfUnreferenced("old-hash") }
            assertEquals(listOf(PhotoChange.REMOVED), changes)
        }

    @Test
    fun clearingWithNoPhotoWritesNothing() =
        runTest {
            val vm = vm(appScope = this)
            advanceUntilIdle()

            vm.clearAvatar()
            advanceUntilIdle()

            coVerify(exactly = 0) { settings.setOwnAvatar(any(), any()) }
        }

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
