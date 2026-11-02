package app.getknit.knit.contacts

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.IdentitySource
import app.getknit.knit.mesh.spool.CommonsMember
import app.getknit.knit.mesh.spool.CommonsStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Who the presence key goes to (ADR 2026-11.xp6d): the people the user chose — an accept, a verification, a DM thread
 * they wrote in — and nobody someone else's act adds: no group co-member, no commons poster, no one blocked.
 */
class DirectContactsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun ids(
        accepted: Set<String> = emptySet(),
        commonsAccepts: Set<String> = emptySet(),
        verified: Set<String> = emptySet(),
        authored: Set<String> = emptySet(),
        blocked: Set<String> = emptySet(),
        blockedByTag: Set<String> = emptySet(),
    ) = directContactIds(accepted, commonsAccepts, verified, authored, blocked, blockedByTag, ME)

    @Test
    fun eachOfTheUsersOwnSignalsMakesADirectContact() {
        assertEquals(setOf(ANN), ids(accepted = setOf(ANN)))
        assertEquals(setOf(BEN), ids(verified = setOf(BEN)))
        assertEquals(setOf(CAL), ids(authored = setOf(CAL)))
    }

    @Test
    fun aGroupIdAndAGroupThreadMakeNobodyADirectContact() {
        // A group accepted or written in is a "g-" conversation id, never a person: its members get nothing from it.
        assertEquals(emptySet<String>(), ids(accepted = setOf(GROUP), authored = setOf(GROUP)))
    }

    @Test
    fun aCommonsAcceptAloneIsNotADirectContact() {
        assertEquals(emptySet<String>(), ids(accepted = setOf(ANN), commonsAccepts = setOf(ANN)))
    }

    @Test
    fun aCommonsPosterTheUserVerifiedOrWroteToIsADirectContact() {
        assertEquals(setOf(ANN), ids(accepted = setOf(ANN), commonsAccepts = setOf(ANN), verified = setOf(ANN)))
        assertEquals(setOf(ANN), ids(accepted = setOf(ANN), commonsAccepts = setOf(ANN), authored = setOf(ANN)))
    }

    @Test
    fun aBlockOutranksEverySignal() {
        val all = setOf(ANN, BEN)
        assertEquals(setOf(BEN), ids(accepted = all, verified = all, authored = all, blocked = setOf(ANN)))
        assertEquals(setOf(ANN), ids(accepted = all, verified = all, authored = all, blockedByTag = setOf(BEN)))
    }

    @Test
    fun weAreNeverOurOwnDirectContact() {
        assertEquals(emptySet<String>(), ids(accepted = setOf(ME), verified = setOf(ME), authored = setOf(ME)))
    }

    @Test
    fun readJudgesTheStoresAsTheyStandAndResolvesABlockedDeviceTag() =
        runTest {
            val settings = mockk<SettingsStore>()
            val peers = mockk<PeerRepository>()
            val messages = mockk<MessageRepository>()
            val identity = mockk<IdentitySource>()
            coEvery { identity.nodeId() } returns ME
            every { settings.acceptedConversations } returns MutableStateFlow(setOf(ANN, BEN, GROUP))
            every { settings.commonsAccepts } returns MutableStateFlow(setOf(BEN))
            every { settings.blockedNodeIds } returns MutableStateFlow(emptySet())
            every { settings.blockedDeviceTags } returns MutableStateFlow(setOf("tag-cal"))
            coEvery { peers.verifiedNodeIds() } returns listOf(CAL, DEE)
            coEvery { peers.nodeIdsWithDeviceTags(setOf("tag-cal")) } returns listOf(CAL)
            every { peers.observeVerifiedNodeIds() } returns flowOf(listOf(CAL, DEE))
            coEvery { messages.conversationsIAuthoredIn(ME) } returns listOf(GROUP)
            every { messages.observeConversationsIAuthoredIn(ME) } returns flowOf(listOf(GROUP))
            coEvery { settings.commonsAcceptsBackfilled() } returns true
            val commons = mockk<CommonsStore>()

            val direct = DirectContacts(settings, peers, messages, commons, identity)
            // Ann accepted; Ben's accept is the commons'; Cal is verified but his device tag is blocked; Dee verified.
            assertEquals(setOf(ANN, DEE), direct.read())
            assertEquals(setOf(ANN, DEE), direct.observe().first())
            coVerify(exactly = 0) { commons.allMembers() }
        }

    /**
     * An install upgraded from a build that accepted commons posters with no mark (Ann), beside a card import (Ben). The
     * reader marks them before it judges anyone, whether or not this build shows the commons (security review,
     * 2026-11-02).
     */
    private inner class Upgraded(
        scope: TestScope,
    ) {
        val settings =
            SettingsStore(PreferenceDataStoreFactory.create(scope = scope.backgroundScope) { File(tmp.root, "s.preferences_pb") })
        val commons = mockk<CommonsStore>()
        val direct: DirectContacts

        init {
            val peers = mockk<PeerRepository>()
            coEvery { peers.verifiedNodeIds() } returns emptyList()
            coEvery { peers.nodeIdsWithDeviceTags(any()) } returns emptyList()
            every { peers.observeVerifiedNodeIds() } returns flowOf(emptyList())
            val messages = mockk<MessageRepository>()
            coEvery { messages.conversationsIAuthoredIn(ME) } returns emptyList()
            every { messages.observeConversationsIAuthoredIn(ME) } returns flowOf(emptyList())
            val identity = mockk<IdentitySource>()
            coEvery { identity.nodeId() } returns ME
            coEvery { commons.allMembers() } returns listOf(CommonsMember("c-room", ANN, 1L))
            direct = DirectContacts(settings, peers, messages, commons, identity)
        }

        suspend fun acceptBoth() {
            settings.accept(ANN)
            settings.accept(BEN)
        }
    }

    @Test
    fun readMarksAnUnmarkedCommonsAcceptBeforeJudgingAndOnlyOnce() =
        runTest {
            val rig = Upgraded(this)
            rig.acceptBoth()
            assertEquals(setOf(BEN), rig.direct.read())
            assertTrue(rig.settings.commonsAcceptsBackfilled())
            assertEquals(setOf(BEN), rig.direct.read())
            coVerify(exactly = 1) { rig.commons.allMembers() }
        }

    @Test
    fun observeMarksAnUnmarkedCommonsAcceptBeforeItsFirstValue() =
        runTest {
            val rig = Upgraded(this)
            rig.acceptBoth()
            assertEquals(setOf(BEN), rig.direct.observe().first())
        }

    private companion object {
        const val ME = "aaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val ANN = "bbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val BEN = "cccccccccccccccccccccccccc"
        const val CAL = "dddddddddddddddddddddddddd"
        const val DEE = "eeeeeeeeeeeeeeeeeeeeeeeeee"
        const val GROUP = "g-ridge"
    }
}
