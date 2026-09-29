package app.getknit.knit.ui.contacts

import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.group.GroupMembersStore
import app.getknit.knit.ui.group
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [contactIds]' group half (#82): a group's members count only once the chat list would show the group as
 * a chat, by the same `Conversations.isAccepted` rule — so a stranger's unanswered invitation lends nobody.
 * And the per-peer view a contact removal reads (ADR 2026-09.adgd): [contactStanding] must agree with
 * [contactIds] exactly, and [groupsAcceptedOnlyThrough] names the groups a removal would otherwise demote.
 */
class ContactUniverseTest {
    private val ridge = group(groupId = "g-ridge", members = listOf("river", "me", "sky"), createdBy = "river")

    private fun contacts(
        groupSenders: Map<String, Set<String>> = mapOf("g-ridge" to setOf("river")),
        authored: Set<String> = emptySet(),
        accepted: Set<String> = emptySet(),
        verified: Set<String> = emptySet(),
        blocked: Set<String> = emptySet(),
    ) = contactIds(
        conversations = setOf("river", "g-ridge"),
        authored = authored,
        groups = listOf(ridge),
        groupSenders = groupSenders,
        accepted = accepted,
        verified = verified,
        blocked = blocked,
        me = "me",
    )

    @Test
    fun aRequestGroupsMembersAreNotContacts() {
        // River's own DM is unanswered too, so nothing makes River (or Sky) a contact.
        assertEquals(emptySet<String>(), contacts())
        assertEquals(emptySet<String>(), contacts(groupSenders = emptyMap()))
    }

    @Test
    fun acceptingTheGroupMakesItsMembersContacts() {
        assertEquals(setOf("river", "sky"), contacts(accepted = setOf("g-ridge")))
    }

    @Test
    fun postingInTheGroupMakesItsMembersContacts() {
        assertEquals(setOf("river", "sky"), contacts(authored = setOf("g-ridge")))
    }

    @Test
    fun aKnownPeerSpeakingInTheGroupMakesItsMembersContacts() {
        assertEquals(
            setOf("river", "sky", "val"),
            contacts(groupSenders = mapOf("g-ridge" to setOf("river", "val")), verified = setOf("val")),
        )
    }

    @Test
    fun aKnownPeerWhoIsOnlyAMemberAcceptsNothing() {
        // Sky is a verified contact in the roster but has not posted: membership alone is not a vouch.
        assertEquals(setOf("sky"), contacts(verified = setOf("sky")))
    }

    private fun signals(
        groups: List<GroupEntity> = listOf(ridge),
        groupSenders: Map<String, Set<String>> = mapOf("g-ridge" to setOf("river")),
        authored: Set<String> = emptySet(),
        accepted: Set<String> = emptySet(),
    ) = ContactSignals(
        conversations = setOf("river", "g-ridge") + authored,
        authored = authored,
        groups = groups,
        groupSenders = groupSenders,
        accepted = accepted,
    )

    /** One setting of every input [contactIds] reads. */
    private data class Combo(
        val accepted: Set<String>,
        val verified: Set<String>,
        val authored: Set<String>,
        val senders: Map<String, Set<String>>,
        val blocked: Set<String>,
        val groups: List<GroupEntity>,
    )

    @Test
    fun standingAgreesWithContactIdsForEveryCombinationOfSignals() {
        val acceptedSets = listOf(emptySet(), setOf("river"), setOf("g-ridge"), setOf("val", "g-ridge"))
        val verifiedSets = listOf(emptySet(), setOf("river"), setOf("val"))
        val authoredSets = listOf(emptySet(), setOf("river"), setOf("g-ridge"), setOf("sky"))
        val senderMaps = listOf(mapOf("g-ridge" to setOf("river")), mapOf("g-ridge" to setOf("val")), emptyMap())
        val blockedSets = listOf(emptySet(), setOf("river"))
        val groupSets = listOf(listOf(ridge), listOf(ridge.copy(left = true)), emptyList())
        val combos =
            acceptedSets.flatMap { a ->
                verifiedSets.flatMap { v ->
                    authoredSets.flatMap { au ->
                        senderMaps.flatMap { s -> blockedSets.flatMap { b -> groupSets.map { g -> Combo(a, v, au, s, b, g) } } }
                    }
                }
            }
        for (combo in combos) {
            val s = signals(combo.groups, combo.senders, combo.authored, combo.accepted)
            val ids = contactIds(s.conversations, s.authored, s.groups, s.groupSenders, s.accepted, combo.verified, combo.blocked, "me")
            for (peer in listOf("river", "sky", "val", "stranger")) {
                assertEquals("$peer under $combo", peer in ids, contactStanding(peer, s, combo.verified, combo.blocked).isContact)
            }
        }
        assertEquals(4 * 3 * 4 * 3 * 2 * 3, combos.size)
    }

    @Test
    fun aGroupRequestLendsNoStanding() {
        // River has posted, but nobody we know has: the group is a request, so Sky has no binding group.
        val standing = contactStanding("sky", signals(), verified = emptySet(), blocked = emptySet())
        assertEquals(emptyList<Any>(), standing.bindingGroups)
        assertFalse(standing.isContact)
    }

    @Test
    fun anAcceptedGroupBindsItsMembersButLendsThemNoSignalOfTheirOwn() {
        val standing = contactStanding("sky", signals(accepted = setOf("g-ridge")), verified = emptySet(), blocked = emptySet())
        assertEquals(listOf("g-ridge"), standing.bindingGroups.map { it.groupId })
        assertFalse("a group is not a signal a removal can clear", standing.ownSignals)
        assertTrue(standing.isContact)
    }

    @Test
    fun aGroupWeLeftBindsNobody() {
        val s = signals(groups = listOf(ridge.copy(left = true)), accepted = setOf("g-ridge"))
        assertEquals(emptyList<Any>(), contactStanding("sky", s, verified = emptySet(), blocked = emptySet()).bindingGroups)
    }

    @Test
    fun ownSignalsAreTheAcceptTheVerificationAndADmWeWroteIn() {
        val s = signals(authored = setOf("river"), accepted = setOf("val"))
        assertTrue(contactStanding("river", s, verified = emptySet(), blocked = emptySet()).authoredDm)
        assertTrue(contactStanding("val", s, verified = emptySet(), blocked = emptySet()).accepted)
        assertTrue(contactStanding("sky", s, verified = setOf("sky"), blocked = emptySet()).verified)
    }

    @Test
    fun aBlockedPeerIsNeverAContactWhateverTheirSignals() {
        val standing = contactStanding("river", signals(accepted = setOf("river", "g-ridge")), setOf("river"), setOf("river"))
        assertTrue(standing.ownSignals)
        assertFalse(standing.isContact)
    }

    @Test
    fun aGroupAcceptedOnlyBecauseThePeerSpokeInItIsNamed() {
        // River is a contact (we wrote in River's DM) and the only known sender in the group.
        val s = signals(authored = setOf("river"))
        assertEquals(listOf("g-ridge"), groupsAcceptedOnlyThrough("river", s, verified = emptySet()))
    }

    @Test
    fun aGroupWeWroteInOrAcceptedIsNotNamed() {
        assertEquals(emptyList<String>(), groupsAcceptedOnlyThrough("river", signals(authored = setOf("river", "g-ridge")), emptySet()))
        assertEquals(emptyList<String>(), groupsAcceptedOnlyThrough("river", signals(accepted = setOf("river", "g-ridge")), emptySet()))
    }

    @Test
    fun aGroupAnotherKnownPeerSpokeInIsNotNamed() {
        val s = signals(groupSenders = mapOf("g-ridge" to setOf("river", "val")), accepted = setOf("river"))
        assertEquals(emptyList<String>(), groupsAcceptedOnlyThrough("river", s, verified = setOf("val")))
    }

    @Test
    fun aGroupThePeerLeftButSpokeInIsStillNamed() {
        // River has since left the roster, yet their old posts are what keeps the group out of requests.
        val s = signals(groups = listOf(ridge.copy(members = GroupMembersStore.encode(listOf("me", "sky")))), accepted = setOf("river"))
        assertEquals(listOf("g-ridge"), groupsAcceptedOnlyThrough("river", s, verified = emptySet()))
    }

    @Test
    fun aGroupWeLeftIsNotNamed() {
        val s = signals(groups = listOf(ridge.copy(left = true)), accepted = setOf("river"))
        assertEquals(emptyList<String>(), groupsAcceptedOnlyThrough("river", s, verified = emptySet()))
    }
}
