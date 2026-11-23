@file:OptIn(ExperimentalCoroutinesApi::class) // UnconfinedTestDispatcher / setMain / advanceUntilIdle are experimental kotlinx APIs

package app.getknit.knit.ui.group

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.data.AvatarStore
import app.getknit.knit.data.BlobRepository
import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.draft.DraftRepository
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.message.GroupFace
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.data.settings.ChatMutes
import app.getknit.knit.data.settings.FakeChatMutes
import app.getknit.knit.identity.Identity
import app.getknit.knit.mesh.FakeMeshController
import app.getknit.knit.mesh.Peer
import app.getknit.knit.notifications.MuteFor
import app.getknit.knit.ui.directoryOf
import app.getknit.knit.ui.group
import app.getknit.knit.ui.peer
import app.getknit.knit.ui.util.CropRect
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GroupDetailsViewModelTest {
    private val groupId = "g-1"
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val groups = mockk<GroupRepository>(relaxed = true)
    private val drafts = mockk<DraftRepository>(relaxed = true)
    private val peers = mockk<PeerRepository>(relaxed = true)
    private val mesh = FakeMeshController()
    private val avatars = mockk<AvatarStore>(relaxed = true)
    private val blobs = mockk<BlobRepository>(relaxed = true)
    private val identity = mockk<Identity>(relaxed = true)

    private val groupFlow = MutableStateFlow<GroupEntity?>(null)
    private val peersFlow = MutableStateFlow(emptyList<PeerEntity>())

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { identity.nodeId() } returns "me"
        every { groups.observeGroup(groupId) } returns groupFlow
        every { peers.observeDirectory() } returns peersFlow.map { directoryOf(it) }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm(mutes: ChatMutes? = null) =
        GroupDetailsViewModel(groupId, groups, drafts, peers, mesh, avatars, blobs, identity, context, mutes = mutes)

    @Test
    fun rosterOrdersSelfFirstThenOnlineThenAlphabetical() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            peersFlow.value = listOf(peer("a", name = "Alice"), peer("b", name = "Bob"), peer("c", name = "Carol"))
            groupFlow.value = group(groupId = groupId, members = listOf("me", "a", "b", "c"), name = "Trip")
            mesh.neighbors.value = setOf(Peer("b")) // only Bob is online
            advanceUntilIdle()

            val order =
                vm.state.value.members
                    .map { it.nodeId }
            // self, then online (b), then the rest alphabetical (Alice < Carol).
            assertEquals(listOf("me", "b", "a", "c"), order)
            assertTrue(
                vm.state.value.members
                    .first()
                    .isSelf,
            )
        }

    @Test
    fun headerFacesFollowNodeIdNotPresenceAndLeaveSelfOut() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            peersFlow.value = listOf(peer("z", name = "Zed", avatarHash = "hz"), peer("a", name = "Alice"))
            groupFlow.value = group(groupId = groupId, members = listOf("me", "z", "a"), name = "Trip")
            mesh.neighbors.value = setOf(Peer("z")) // Zed is online, so the list puts him first
            advanceUntilIdle()

            val state = vm.state.value
            assertEquals(listOf("me", "z", "a"), state.members.map { it.nodeId })
            assertEquals(listOf(GroupFace("a", "Alice", null), GroupFace("z", "Zed", "hz")), state.faces)
        }

    @Test
    fun namedGroupUsesTheStoredName() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            groupFlow.value = group(groupId = groupId, members = listOf("me", "a"), name = "Weekend Trip")
            advanceUntilIdle()

            assertEquals("Weekend Trip", vm.state.value.title)
        }

    @Test
    fun existsFlipsFalseOnceTheGroupRowIsGone() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            groupFlow.value = group(groupId = groupId, members = listOf("me", "a"))
            advanceUntilIdle()
            assertTrue(vm.state.value.exists)

            groupFlow.value = null // left/deleted elsewhere
            advanceUntilIdle()
            assertFalse(vm.state.value.exists)
        }

    @Test
    fun renameGuardsEmptyAndFloodsAValidRename() =
        runTest {
            coEvery { groups.find(groupId) } returns group(groupId = groupId, members = listOf("me", "a"))
            val vm = vm()

            vm.renameGroup("   ")
            advanceUntilIdle()
            assertEquals(0, mesh.sentGroupUpdates.size)

            vm.renameGroup("New Name")
            advanceUntilIdle()
            coVerify { groups.upsert(any()) }
            assertEquals(1, mesh.sentGroupUpdates.size)
            assertEquals("New Name", mesh.sentGroupUpdates.single().name)
        }

    /** A group photo the explicit-image screen refused changes nothing, sends nothing, and says so (ADR 2026-10.9rh6). */
    @Test
    fun aRefusedGroupPhotoChangesNothingAndIsSignalled() =
        runTest {
            coEvery { groups.find(groupId) } returns group(groupId = groupId, members = listOf("me", "a"))
            coEvery { avatars.loadForCrop(any()) } returns Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
            coEvery { avatars.saveOwnAvatar(any(), any<CropRect>()) } returns null
            val vm = vm()
            val refusals = mutableListOf<Unit>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.photoRefused.collect { refusals += it } }

            vm.pickGroupPhoto(mockk<Uri>())
            advanceUntilIdle()
            vm.confirmGroupPhoto(scale = 1f, offset = Offset.Zero, diameter = 200f)
            advanceUntilIdle()

            assertEquals(1, refusals.size)
            coVerify(exactly = 0) { groups.upsert(any()) }
            assertTrue(mesh.sentGroupUpdates.isEmpty())
        }

    @Test
    fun leaveSendsLeaveTombstonesAndSignalsLeft() =
        runTest {
            val vm = vm()
            val lefts = mutableListOf<Unit>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.left.collect { lefts += it } }

            vm.leaveGroup()
            advanceUntilIdle()

            assertEquals(listOf(groupId), mesh.sentGroupLeaves)
            coVerify { groups.leave(groupId) }
            assertEquals(1, lefts.size)
        }

    @Test
    fun theGroupsMuteIsShownChangedAndGoesWhenTheGroupIsLeft() =
        runTest {
            // Always, never a timed span: a live timed mute re-checks the wall clock every minute, which the virtual
            // one never moves, so `advanceUntilIdle` would never come back. `ChatMuteTest` pins the lapse.
            val mutes = FakeChatMutes(mapOf("g-other" to ChatMutes.ALWAYS))
            val vm = vm(mutes)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.mutedUntil.collect {} }
            assertTrue(vm.canMute)
            assertNull(vm.mutedUntil.value)

            vm.mute(MuteFor.ALWAYS)
            advanceUntilIdle()
            assertEquals(ChatMutes.ALWAYS, vm.mutedUntil.value)

            vm.leaveGroup()
            advanceUntilIdle()
            assertEquals("only the left group's mute goes", mapOf("g-other" to ChatMutes.ALWAYS), mutes.state.value)
            assertNull(vm.mutedUntil.value)
        }
}
