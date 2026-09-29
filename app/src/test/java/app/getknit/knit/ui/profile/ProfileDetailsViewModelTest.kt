@file:OptIn(ExperimentalCoroutinesApi::class) // UnconfinedTestDispatcher / setMain / advanceUntilIdle are experimental kotlinx APIs

package app.getknit.knit.ui.profile

import android.content.Context
import app.getknit.knit.contacts.ContactRemover
import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.peer.MetPeerEntity
import app.getknit.knit.data.peer.MetPeerRepository
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.mesh.FakeMeshController
import app.getknit.knit.mesh.PRESENCE_LINGER_MS
import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.crypto.VerifyPayload
import app.getknit.knit.mesh.spool.ScopeStatus
import app.getknit.knit.mesh.spool.SpoolStatus
import app.getknit.knit.ui.Reach
import app.getknit.knit.ui.directoryOf
import app.getknit.knit.ui.group
import app.getknit.knit.ui.peer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
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

/**
 * Covers the flow-derived state projection and the QR-scan verify logic. The identity fields
 * (`safetyNumber`/`myQrPayload`) are set on [Dispatchers.IO] in the VM's init and are covered separately by
 * `SafetyNumberTest`; these tests assert only the deterministic, flow-driven surface — presence/block/key
 * state and `onScanned` — none of which depends on that background load resolving.
 */
class ProfileDetailsViewModelTest {
    private val nodeId = "peer-1"
    private val peers = mockk<PeerRepository>(relaxed = true)
    private val mesh = FakeMeshController()
    private val settings = mockk<SettingsStore>(relaxed = true)
    private val identity = mockk<Identity>(relaxed = true)
    private val groups = mockk<GroupRepository>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)
    private val metPeers = mockk<MetPeerRepository>(relaxed = true)
    private val messages = mockk<MessageRepository>(relaxed = true)
    private val remover = mockk<ContactRemover>(relaxed = true)

    private val peersFlow = MutableStateFlow(emptyList<PeerEntity>())
    private val blockedFlow = MutableStateFlow(emptySet<String>())

    // A finite stand-in for `RelayStatusRepository.statuses` (an infinite poller in production).
    private val spoolsFlow = MutableStateFlow(emptyList<SpoolStatus>())

    // Both must be stubbed even though the mocks are relaxed: a relaxed `Flow` return never emits, and the
    // "in common" pre-combine feeds the main `state` combine — leave either unstubbed and `state` stalls
    // at its initial value with no failure that names the cause.
    private val sharedGroupsFlow = MutableStateFlow(emptyList<GroupEntity>())
    private val metFlow = MutableStateFlow<MetPeerEntity?>(null)

    // The contact standing's inputs (ADR 2026-09.adgd) — stubbed for the same reason: the Remove offer
    // pre-combines them into the main `state`, and an unstubbed one stalls every test here.
    private val dmThreadsFlow = MutableStateFlow(emptyList<String>())
    private val authoredFlow = MutableStateFlow(emptyList<String>())
    private val allGroupsFlow = MutableStateFlow(emptyList<GroupEntity>())
    private val groupSendersFlow = MutableStateFlow(emptyMap<String, Set<String>>())
    private val acceptedFlow = MutableStateFlow(emptySet<String>())

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { identity.nodeId() } returns "me"
        every { identity.publicKeyBundle() } returns "MYBUNDLE"
        every { peers.observeDirectory() } returns peersFlow.map { directoryOf(it) }
        every { settings.blockedNodeIds } returns blockedFlow
        every { groups.observeGroupsWith(nodeId) } returns sharedGroupsFlow
        every { metPeers.observe(nodeId) } returns metFlow
        every { context.getString(any()) } returns UNNAMED_GROUP
        every { messages.observeConversations(any()) } returns dmThreadsFlow
        every { messages.observeConversationsIAuthoredIn(any()) } returns authoredFlow
        every { messages.observeGroupSenders(any()) } returns groupSendersFlow
        every { groups.observeGroups() } returns allGroupsFlow
        every { settings.acceptedConversations } returns acceptedFlow
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm() =
        ProfileDetailsViewModel(
            nodeId,
            peers,
            groups,
            metPeers,
            mesh,
            settings,
            identity,
            messages,
            remover,
            spoolsFlow,
            context,
            clock = { NOW },
        )

    @Test
    fun stateReflectsProfilePresenceBlockAndKeyState() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            peersFlow.value = listOf(peer(nodeId, name = "Ada", pubKey = "PBUNDLE", verified = true))
            mesh.neighbors.value = setOf(Peer(nodeId))
            blockedFlow.value = setOf(nodeId)
            advanceUntilIdle()

            val s = vm.state.value
            assertEquals("Ada", s.displayName)
            assertEquals("in the neighbor set → online", Reach.Direct, s.reach)
            assertTrue("id is in the blocked set", s.isBlocked)
            assertTrue("a pinned pubKey → hasKey", s.hasKey)
            assertTrue("peer.verified true → verified", s.verified)
        }

    /**
     * The field report: a contact reachable only through an Internet relay sat under "Reachable via relay"
     * on Diagnostics while their profile said Offline. The profile now sorts by the same three tiers —
     * a radio's own sighting beats a carried frame, which beats a bare profile row.
     */
    @Test
    fun presenceClimbsFromKnownThroughRelayToDirect() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            peersFlow.value = listOf(peer(nodeId, name = "Ada"))
            advanceUntilIdle()
            assertEquals("a profile row alone is not presence", Reach.Known, vm.state.value.reach)

            // Heard over a long-range plane (a LoRa board carried its frames): relay reach, not nearby.
            mesh.reachable.value = setOf(Peer(nodeId))
            advanceUntilIdle()
            assertEquals(Reach.Relay, vm.state.value.reach)

            // A short-range radio saw the peer itself.
            mesh.neighbors.value = setOf(Peer(nodeId))
            advanceUntilIdle()
            assertEquals(Reach.Direct, vm.state.value.reach)

            mesh.neighbors.value = emptySet()
            mesh.reachable.value = emptySet()
            advanceUntilIdle()
            assertEquals(Reach.Known, vm.state.value.reach)
        }

    /**
     * The Internet plane is a path to a peer only when that peer has itself pushed something recent into
     * the scope we share (ADR 2026-09.2ajk): a converged scope on a connected spool proves nothing about
     * a phone that has sat in a drawer for a month, and a stamp older than the linger has gone quiet.
     */
    @Test
    fun aSpoolScopeItsPeerRecentlyPushedToIsRelayReach() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            peersFlow.value = listOf(peer(nodeId, name = "Ada"))

            spoolsFlow.value = listOf(spool(scope(nodeId, peerSeenAt = NOW - 60_000L)))
            advanceUntilIdle()
            assertEquals(Reach.Relay, vm.state.value.reach)

            // Connected and converged, but the peer has never pushed: a scope is not its peer.
            spoolsFlow.value = listOf(spool(scope(nodeId)))
            advanceUntilIdle()
            assertEquals(Reach.Known, vm.state.value.reach)

            // Seen, but longer ago than the linger.
            spoolsFlow.value = listOf(spool(scope(nodeId, peerSeenAt = NOW - PRESENCE_LINGER_MS - 1)))
            advanceUntilIdle()
            assertEquals(Reach.Known, vm.state.value.reach)

            // A recent stamp on a spool we have lost the socket to is not a path right now.
            spoolsFlow.value = listOf(spool(scope(nodeId, peerSeenAt = NOW), connected = false))
            advanceUntilIdle()
            assertEquals(Reach.Known, vm.state.value.reach)

            // A drained rotation carries nothing new (spec §3.1/§3.3).
            spoolsFlow.value = listOf(spool(scope(nodeId, peerSeenAt = NOW, retiring = true)))
            advanceUntilIdle()
            assertEquals(Reach.Known, vm.state.value.reach)
        }

    @Test
    fun openToChatMirrorsThePeerRow() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            peersFlow.value = listOf(peer(nodeId, name = "Ada", openToChat = true))
            advanceUntilIdle()
            assertTrue(vm.state.value.openToChat)
            peersFlow.value = listOf(peer(nodeId, name = "Ada"))
            advanceUntilIdle()
            assertFalse(vm.state.value.openToChat)
        }

    /**
     * The bound board arrives as the raw node number and is shown as `!hex` — the form the radio room writes
     * under a heard post, so the two can be read against each other. A profile that names no board says
     * nothing rather than zero.
     */
    @Test
    fun theBoundBoardRendersAsAMeshtasticNodeLabel() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            peersFlow.value = listOf(peer(nodeId, name = "Ada", loraNode = 0x1234abcdL))
            advanceUntilIdle()
            assertEquals("!1234abcd", vm.state.value.loraNodeLabel)

            peersFlow.value = listOf(peer(nodeId, name = "Ada"))
            advanceUntilIdle()
            assertNull(vm.state.value.loraNodeLabel)
        }

    @Test
    fun onScannedMatchingPinnedKeyMarksVerified() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            peersFlow.value = listOf(peer(nodeId, pubKey = "PBUNDLE"))
            advanceUntilIdle()

            vm.onScanned(VerifyPayload.encode(nodeId, "PBUNDLE"))
            advanceUntilIdle()

            assertEquals(VerifyScanResult.MATCH, vm.scanResult.value)
            coVerify { peers.setVerified(nodeId, true) }
        }

    @Test
    fun onScannedWrongKeyReportsMismatchAndDoesNotVerify() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            peersFlow.value = listOf(peer(nodeId, pubKey = "PBUNDLE"))
            advanceUntilIdle()

            vm.onScanned(VerifyPayload.encode(nodeId, "SOMEONE-ELSES-KEY"))
            advanceUntilIdle()

            assertEquals(VerifyScanResult.MISMATCH, vm.scanResult.value)
            coVerify(exactly = 0) { peers.setVerified(nodeId, true) }
        }

    @Test
    fun acceptPersistsThePeersConversationSoTappingMessageClearsAnyRequest() =
        runTest {
            val vm = vm()

            vm.accept()
            advanceUntilIdle()

            // A DM's conversationId is the peer's node id, so accepting adds exactly that.
            coVerify { settings.accept(nodeId) }
        }

    @Test
    fun blockUsesTheCapturedDeviceTagSoItSticksAcrossAKeyReset() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            peersFlow.value = listOf(PeerEntity(nodeId = nodeId, deviceTag = "tag-1"))
            advanceUntilIdle()

            vm.block()
            advanceUntilIdle()

            coVerify { settings.block(nodeId, "tag-1") }
            assertFalse("scan result starts empty", vm.scanResult.value == VerifyScanResult.MATCH)
        }

    private fun spool(
        vararg scopes: ScopeStatus,
        connected: Boolean = true,
    ) = SpoolStatus(
        url = "wss://spool.example/spool/v1",
        connected = connected,
        powBits = 0,
        lastError = null,
        scopes = scopes.toList(),
    )

    private fun scope(
        label: String,
        peerSeenAt: Long? = null,
        retiring: Boolean = false,
    ) = ScopeStatus(
        scopeHex = "00",
        label = label,
        localCount = 1,
        spoolCount = 1,
        converged = true,
        invalidCount = 0,
        retiring = retiring,
        peerSeenAt = peerSeenAt,
    )

    /**
     * Groups in common and the met stamps reach the state through their own pre-combine, so a change to
     * either relights the screen without the peer's profile row moving.
     */
    @Test
    fun sharedGroupsAndMetStampsReachTheState() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            sharedGroupsFlow.value = listOf(group("g1", members = listOf("me", nodeId), name = "Trail Crew"))
            metFlow.value = MetPeerEntity(nodeId = nodeId, firstMetAt = 1_000L, lastMetAt = 2_000L)
            advanceUntilIdle()

            val shared = vm.state.value.inCommon
            assertEquals(listOf("Trail Crew"), shared.groups.map { it.title })
            assertEquals(listOf("g1"), shared.groups.map { it.groupId })
            assertEquals(1_000L, shared.firstMetAt)
            assertEquals(2_000L, shared.lastMetAt)
        }

    /** Never met, nothing shared: both halves come back empty, so both sections hide themselves. */
    @Test
    fun nothingInCommonIsEmptyRatherThanAbsent() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            peersFlow.value = listOf(peer(nodeId, name = "Ada"))
            advanceUntilIdle()

            val shared = vm.state.value.inCommon
            assertEquals(emptyList<Any>(), shared.groups)
            assertNull(shared.firstMetAt)
            assertNull(shared.lastMetAt)
        }

    // --- Remove contact (ADR 2026-09.adgd) ---

    /** Collects `state` for the test's lifetime and returns the VM. */
    private fun TestScope.collecting(): ProfileDetailsViewModel {
        val vm = vm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        return vm
    }

    @Test
    fun aStrangerIsNotOfferedRemove() =
        runTest {
            val vm = collecting()
            peersFlow.value = listOf(peer(nodeId, name = "Ada"))
            dmThreadsFlow.value = listOf(nodeId) // they wrote to us; we never answered
            advanceUntilIdle()
            assertEquals(ContactRemoval.NotOffered, vm.state.value.removal)
        }

    @Test
    fun anAcceptedPeerIsOfferedAFullRemove() =
        runTest {
            val vm = collecting()
            acceptedFlow.value = setOf(nodeId)
            advanceUntilIdle()
            assertEquals(ContactRemoval.Removes(keptBy = emptyList(), clearsVerification = false), vm.state.value.removal)
        }

    @Test
    fun aDmWeWroteInIsEnoughToOfferRemove() =
        runTest {
            val vm = collecting()
            dmThreadsFlow.value = listOf(nodeId)
            authoredFlow.value = listOf(nodeId)
            advanceUntilIdle()
            assertTrue(vm.state.value.removal is ContactRemoval.Removes)
        }

    @Test
    fun aVerifiedPeersRemoveSaysTheVerificationGoesToo() =
        runTest {
            val vm = collecting()
            peersFlow.value = listOf(peer(nodeId, name = "Ada", pubKey = "PBUNDLE", verified = true))
            advanceUntilIdle()
            assertEquals(ContactRemoval.Removes(keptBy = emptyList(), clearsVerification = true), vm.state.value.removal)
        }

    @Test
    fun anAcceptedGroupKeepsItsCoMemberAndIsNamedInTheConfirm() =
        runTest {
            val vm = collecting()
            val trail = group("g-trail", members = listOf("me", nodeId), name = "Trail Crew")
            allGroupsFlow.value = listOf(trail)
            acceptedFlow.value = setOf(nodeId, "g-trail")
            advanceUntilIdle()
            val removal = vm.state.value.removal as ContactRemoval.Removes
            assertEquals(listOf("Trail Crew"), removal.keptBy.map { it.title })
        }

    @Test
    fun aContactOnlyThroughAGroupGetsTheExplanationInstead() =
        runTest {
            val vm = collecting()
            allGroupsFlow.value = listOf(group("g-trail", members = listOf("me", nodeId), name = "Trail Crew"))
            authoredFlow.value = listOf("g-trail") // we posted in the group, never in their DM
            advanceUntilIdle()
            val removal = vm.state.value.removal as ContactRemoval.GroupsOnly
            assertEquals(listOf("g-trail"), removal.groups.map { it.groupId })
        }

    @Test
    fun aGroupStillInRequestsIsInCommonButNeitherKeepsNorOffers() =
        runTest {
            val vm = collecting()
            val invite = group("g-invite", members = listOf("me", nodeId), name = "Invite")
            sharedGroupsFlow.value = listOf(invite)
            allGroupsFlow.value = listOf(invite)
            groupSendersFlow.value = mapOf("g-invite" to setOf(nodeId)) // a stranger's post: still a request
            advanceUntilIdle()
            assertEquals(
                listOf("g-invite"),
                vm.state.value.inCommon.groups
                    .map { it.groupId },
            )
            assertEquals(ContactRemoval.NotOffered, vm.state.value.removal)
        }

    @Test
    fun aBlockedPeerIsNotOfferedRemove() =
        runTest {
            val vm = collecting()
            acceptedFlow.value = setOf(nodeId)
            blockedFlow.value = setOf(nodeId)
            advanceUntilIdle()
            assertEquals(ContactRemoval.NotOffered, vm.state.value.removal)
        }

    @Test
    fun ourOwnProfileIsNeverOfferedRemove() =
        runTest {
            coEvery { identity.nodeId() } returns nodeId
            val vm = collecting()
            acceptedFlow.value = setOf(nodeId)
            advanceUntilIdle()
            assertEquals(ContactRemoval.NotOffered, vm.state.value.removal)
        }

    @Test
    fun removedFiresOnlyOnceTheRemoverHasFinished() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            coEvery { remover.remove(nodeId) } coAnswers { gate.await() }
            val vm = collecting()
            var fired = 0
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.removed.collect { fired++ } }

            vm.removeContact()
            advanceUntilIdle()
            assertEquals("still writing", 0, fired)

            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(1, fired)
            coVerify(exactly = 1) { remover.remove(nodeId) }
        }

    private companion object {
        /** Comfortably past every presence window, so an unset stamp can never read as recent. */
        const val NOW = 100L * 60 * 60_000L
        const val UNNAMED_GROUP = "Unnamed group"
    }
}
