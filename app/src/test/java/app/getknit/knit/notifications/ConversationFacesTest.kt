package app.getknit.knit.notifications

import app.getknit.knit.data.BlobRepository
import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.group.GroupMembersStore
import app.getknit.knit.data.message.ConversationKind
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.identity.Alias
import app.getknit.knit.identity.NamePresenter
import app.getknit.knit.identity.PeerLabels
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The face a conversation wears outside the app, resolved from the conversation alone — the one resolver the
 * notification and the conversation shortcut share (ADR 2026-10.jbsa), so a refreshed shortcut and the next
 * notification cannot read apart.
 */
class ConversationFacesTest {
    private val groups = mockk<GroupRepository>()
    private val peers = mockk<PeerRepository>()
    private val blobs = mockk<BlobRepository>()
    private val faces = ConversationFaces(groups, peers, blobs, commonsTitle = { if (it == COMMONS) "Hilltop" else null })

    private val labels =
        PeerLabels.index(
            listOf(ALICE to "Alice", BOB to "Bob", CAROL to "Alice"),
            ME to "Me",
        )

    init {
        coEvery { peers.find(any()) } returns null
        coEvery { peers.find(ALICE) } returns PeerEntity(ALICE, name = "Alice", avatarHash = "alice-photo")
        coEvery { blobs.bytes("alice-photo") } returns ALICE_PHOTO
        coEvery { blobs.bytes("group-photo") } returns GROUP_PHOTO
    }

    private fun group(photoShownHash: String? = null) =
        GroupEntity(
            groupId = GROUP,
            name = "",
            members = GroupMembersStore.encode(listOf(ME, ALICE, BOB)),
            createdBy = ME,
            createdAt = 0L,
            photoShownHash = photoShownHash,
        )

    @Test
    fun aDmWearsItsPeersLabelAndAvatar() =
        runTest {
            val face = faces.resolve(ALICE, ME, labels)

            assertEquals(ConversationKind.DM, face.kind)
            // Two known Alices read apart (ADR 058), exactly as the notification names the sender.
            assertEquals(labels.labelFor(ALICE).text, face.title)
            assertTrue(face.title!!.startsWith("Alice ("))
            assertArrayEquals(ALICE_PHOTO, face.avatarBytes)
        }

    @Test
    fun aPhotoLessGroupIsTitledByItsOtherMembersAndCarriesThemAsFaces() =
        runTest {
            coEvery { groups.find(GROUP) } returns group()

            val face = faces.resolve(GROUP, ME, labels)

            assertNull(face.avatarBytes)
            assertEquals(listOf(ALICE, BOB), face.faces.map { it.nodeId })
            assertArrayEquals(ALICE_PHOTO, face.faces.first().avatarBytes)
            assertTrue(face.title!!.contains("Bob"))
        }

    @Test
    fun aGroupWithAShownPhotoCarriesNoFaces() =
        runTest {
            coEvery { groups.find(GROUP) } returns group(photoShownHash = "group-photo")

            val face = faces.resolve(GROUP, ME, labels)

            assertArrayEquals(GROUP_PHOTO, face.avatarBytes)
            assertTrue(face.faces.isEmpty())
        }

    @Test
    fun theRoomsLeaveTheirFaceToTheNotifierAndACommonsWearsItsRelaysName() =
        runTest {
            val nearby = faces.resolve(Conversations.NEARBY, ME, labels)
            val meshtastic = faces.resolve(Conversations.MESHTASTIC, ME, labels)
            val commons = faces.resolve(COMMONS, ME, labels)

            assertNull(nearby.title)
            assertNull(nearby.avatarBytes)
            assertNull("never a heard speaker's unverified name", meshtastic.title)
            assertEquals("Hilltop", commons.title)
            assertNull(commons.avatarBytes)
        }

    /**
     * ADR 2026-11.y4pc: the shade and the launcher keep a conversation's title after the app moves on, so it is drawn
     * from an index whose presenter hides a flagged name: the DM reads as the alias, and a flagged group name gives
     * way to the members' title.
     */
    @Test
    fun aHiddenNameOrGroupNameTitlesTheConversationAsTheAliasOrTheMembers() =
        runTest {
            val hiding =
                PeerLabels.index(
                    listOf(ALICE to "Alice", BOB to "Bob"),
                    ME to "Me",
                    presenter = NamePresenter { if (it == "Alice") "" else it },
                    groupPresenter = NamePresenter { "" },
                )
            assertEquals(Alias.aliasFor(ALICE), faces.resolve(ALICE, ME, hiding).title)

            coEvery { groups.find(GROUP) } returns group().copy(name = "Hidden club")
            assertEquals("Hidden club", faces.resolve(GROUP, ME, labels).title)
            val title = faces.resolve(GROUP, ME, hiding).title!!
            assertTrue("the members title it: $title", title.contains("Bob"))
        }

    private companion object {
        const val ME = "me"
        const val ALICE = "alice"
        const val BOB = "bob"
        const val CAROL = "carol"
        const val GROUP = Conversations.GROUP_ID_PREFIX + "climbing"
        const val COMMONS = Conversations.COMMONS_PREFIX + "abc"
        val ALICE_PHOTO = byteArrayOf(1, 2, 3)
        val GROUP_PHOTO = byteArrayOf(4, 5, 6)
    }
}
