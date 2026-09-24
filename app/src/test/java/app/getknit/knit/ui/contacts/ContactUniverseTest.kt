package app.getknit.knit.ui.contacts

import app.getknit.knit.ui.group
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [contactIds]' group half (#82): a group's members count only once the chat list would show the group as
 * a chat, by the same `Conversations.isAccepted` rule — so a stranger's unanswered invitation lends nobody.
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
}
