package app.getknit.knit.contacts

import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.draft.DraftRepository
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.mesh.MeshController
import app.getknit.knit.notifications.Notifier
import app.getknit.knit.ui.group
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/**
 * The removal sequence on plain JVM (ADR 2026-09.adgd): each of our own contact signals is cleared, the
 * explicit accept last, a group whose acceptance rested on the peer alone is accepted first, and nothing the
 * mesh converges on — the peer row, the session, custody, the block list — is touched.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactRemoverTest {
    private val settings = mockk<SettingsStore>(relaxed = true)
    private val peers = mockk<PeerRepository>(relaxed = true)
    private val messages = mockk<MessageRepository>(relaxed = true)
    private val groups = mockk<GroupRepository>(relaxed = true)
    private val drafts = mockk<DraftRepository>(relaxed = true)
    private val mesh = mockk<MeshController>(relaxed = true)
    private val notifier = mockk<Notifier>(relaxed = true)
    private val identity = mockk<Identity>(relaxed = true)

    // River is a contact three ways (accepted, verified, a DM we wrote in) and the only known peer who has
    // posted in Ridge, a group we neither accepted nor wrote in — so Ridge is a chat only because of River.
    private val ridge = group(groupId = "g-ridge", members = listOf("river", "me", "sky"), createdBy = "river")
    private val accepted = MutableStateFlow(setOf(RIVER))
    private val senders = MutableStateFlow(mapOf("g-ridge" to setOf(RIVER)))

    @Before
    fun setUp() {
        coEvery { identity.nodeId() } returns ME
        every { messages.observeConversations(any()) } returns flowOf(listOf(RIVER, "g-ridge"))
        every { messages.observeConversationsIAuthoredIn(ME) } returns flowOf(listOf(RIVER))
        every { messages.observeGroupSenders(any()) } returns senders
        every { groups.observeGroups() } returns flowOf(listOf(ridge))
        every { settings.acceptedConversations } returns accepted
        every { settings.blockedNodeIds } returns MutableStateFlow(emptySet())
        coEvery { peers.verifiedNodeIds() } returns listOf(RIVER)
    }

    private fun remover() = ContactRemover(settings, peers, messages, groups, drafts, mesh, notifier, identity)

    @Test
    fun clearsEveryOwnSignalAfterPinningTheGroupAndTheAcceptLast() =
        runTest {
            remover().remove(RIVER)

            coVerifyOrder {
                settings.accept("g-ridge")
                mesh.cancelIntro(RIVER)
                notifier.forgetConversation(RIVER)
                messages.deleteByConversation(RIVER)
                drafts.clear(RIVER)
                peers.setVerified(RIVER, false)
                settings.unaccept(RIVER)
            }
        }

    @Test
    fun aGroupThatStandsWithoutThePeerIsNotAccepted() =
        runTest {
            // Val, a verified peer, has posted in Ridge too: it stays a chat whoever is removed.
            senders.value = mapOf("g-ridge" to setOf(RIVER, "val"))
            coEvery { peers.verifiedNodeIds() } returns listOf(RIVER, "val")

            remover().remove(RIVER)

            coVerify(exactly = 0) { settings.accept(any()) }
            coVerify { settings.unaccept(RIVER) }
        }

    @Test
    fun nothingTheMeshConvergesOnIsTouched() =
        runTest {
            remover().remove(RIVER)

            // The peer row stays (the key pin), and no other mesh call goes out — no reset, no session drop.
            coVerify { peers.verifiedNodeIds() }
            coVerify { peers.setVerified(RIVER, false) }
            confirmVerified(peers)
            coVerify { mesh.cancelIntro(RIVER) }
            confirmVerified(mesh)
            // The group rows are read, never written; the block list is read, never written.
            verify { groups.observeGroups() }
            confirmVerified(groups)
            coVerify(exactly = 0) { settings.block(any(), any()) }
            coVerify(exactly = 0) { settings.unblock(any(), any()) }
        }

    @Test
    fun removingOurselvesDoesNothing() =
        runTest {
            remover().remove(ME)

            coVerify(exactly = 0) { settings.unaccept(any()) }
            coVerify(exactly = 0) { messages.deleteByConversation(any()) }
            coVerify(exactly = 0) { peers.setVerified(any(), any()) }
            confirmVerified(mesh)
        }

    @Test
    fun aRemovalOnceWritingRunsToTheEndWhenItsCallerIsCancelled() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            coEvery { mesh.cancelIntro(RIVER) } coAnswers { gate.await() }

            val job = launch { remover().remove(RIVER) }
            runCurrent()
            job.cancel() // the screen left mid-removal
            gate.complete(Unit)
            advanceUntilIdle()

            coVerify { messages.deleteByConversation(RIVER) }
            coVerify { settings.unaccept(RIVER) }
        }

    private companion object {
        const val ME = "me"
        const val RIVER = "river"
    }
}
