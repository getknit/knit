package app.getknit.knit.data.forward

import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.group.GroupMembersStore
import app.getknit.knit.data.group.toFoundingInfo
import app.getknit.knit.data.group.toGroupInfo
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.mesh.protocol.GroupInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The custody group gate ([ForwardRepository.carriesGroup], ADR 2026-10.7mqm): custody carries a frame naming a group
 * only when the frame's members and departed derive to the group's id and its sender is one of the members. Every
 * honest shape is built the way the app builds it, from a group row through [toGroupInfo], so a change to the mapper
 * or to what a leave or a rejoin writes to the row is caught here before a group's frames stop being carried.
 */
class CustodyGroupGateTest {
    private val founding = listOf("alice", "bob", "carol")
    private val groupId = Conversations.groupIdFor(founding)

    /** The row as `GroupRepository` holds it: [members] the effective roster, [departed] the recorded leaves. */
    private fun row(
        members: List<String>,
        departed: List<String> = emptyList(),
        left: Boolean = false,
    ) = GroupEntity(
        groupId = groupId,
        name = "",
        members = GroupMembersStore.encode(members),
        createdBy = "alice",
        createdAt = 0L,
        left = left,
        departed = GroupMembersStore.encode(departed),
    )

    @Test
    fun aMembersFrameFromTheGroupItCreatedIsCarried() {
        // ContactsViewModel.createGroup's row: the founding roster, nobody departed.
        assertTrue(ForwardRepository.carriesGroup(row(founding).toGroupInfo(), "alice"))
        assertTrue(ForwardRepository.carriesGroup(row(founding).toGroupInfo(), "carol"))
    }

    @Test
    fun aFrameSentAfterADepartureCarriesTheDepartedAndIsCarried() {
        // recordDeparture moved bob from members to departed; members + departed is still the founding roster.
        assertTrue(ForwardRepository.carriesGroup(row(listOf("alice", "carol"), departed = listOf("bob")).toGroupInfo(), "carol"))
    }

    @Test
    fun aMemberWhoMissedTheLeaveStillListsTheLeaverAndIsCarried() {
        assertTrue(ForwardRepository.carriesGroup(row(founding).toGroupInfo(), "alice"))
    }

    @Test
    fun theLeaversOwnFramesAfterLeavingStillNameThemAMember() {
        // GroupRepository.leave only marks the row left: our own id stays in members, so a group reaction sent from a
        // left group (ChatViewModel.react builds its GroupInfo with no `left` check) passes the gate. Its pre-leave
        // frames, re-served from custody, were built from the same roster.
        assertTrue(ForwardRepository.carriesGroup(row(founding, left = true).toGroupInfo(), "bob"))
    }

    @Test
    fun aRejoinFromEitherSideIsCarried() {
        // ADR 2026-09.v6fu: the rejoiner re-creates the group, a fresh row with the founding roster; a member who
        // recorded the leave and then the rejoin holds bob in members again.
        assertTrue(ForwardRepository.carriesGroup(row(founding).toGroupInfo(), "bob"))
        assertTrue(ForwardRepository.carriesGroup(row(listOf("alice", "carol", "bob")).toGroupInfo(), "alice"))
    }

    @Test
    fun theSeedsFoundingRosterDerivesToo() {
        // A seed's GroupKeyPayload.group rides inside a sealed DM and never names a group on the envelope, but it is the
        // same founding roster, so the gate would pass it if it did.
        assertTrue(ForwardRepository.carriesGroup(row(listOf("alice", "carol"), departed = listOf("bob")).toFoundingInfo(), "alice"))
    }

    @Test
    fun aStrangerNamingTheGroupIsNotCarried() {
        assertFalse(ForwardRepository.carriesGroup(row(founding).toGroupInfo(), "mallory"))
    }

    @Test
    fun aDepartedSenderListedAsDepartedIsNotCarried() {
        // Nobody sends this: a leaver's own frames list them as a member. Only another node could, under their id.
        assertFalse(ForwardRepository.carriesGroup(row(listOf("alice", "carol"), departed = listOf("bob")).toGroupInfo(), "bob"))
    }

    @Test
    fun aRosterThatDoesNotDeriveToTheIdIsNotCarried() {
        val honest = row(founding).toGroupInfo()
        assertFalse("a member smuggled in", ForwardRepository.carriesGroup(honest.copy(members = founding + "mallory"), "mallory"))
        assertFalse("a member hidden", ForwardRepository.carriesGroup(honest.copy(members = listOf("alice", "bob")), "alice"))
        assertFalse(
            "someone else's group's id under the sender's own roster",
            ForwardRepository.carriesGroup(GroupInfo(id = groupId, members = listOf("mallory", "alice"), createdBy = "mallory"), "mallory"),
        )
    }

    @Test
    fun aPre230FrameAfterADepartureIsNotCarried() {
        // The residual ADR 2026-10.7mqm records: 2.0.0–2.2.3 send the effective roster with no `departed` (4c280ecd1).
        // Delivery still takes it through vetRoster's within-pin arm; custody lets it go.
        assertFalse(
            ForwardRepository.carriesGroup(GroupInfo(id = groupId, members = listOf("alice", "carol"), createdBy = "alice"), "alice"),
        )
    }
}
