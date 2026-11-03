package app.getknit.knit.data

import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.group.GroupMembersStore
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.ui.contacts.contactIds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The name-clash fade's never-stale set (ADR 2026-10.k8ge). An anchor missing a contact is the one mistake that
 * matters — a quiet contact beside a fresh namesake would read suffixed while the newcomer read plain — so the
 * rule is pinned against the contact rule itself, over random universes, rather than case by case.
 */
class LabelAnchorsTest {
    @Test
    fun everyContactIsAnAnchor() {
        val rng = Random(3)
        val people = (0 until 12).map { "p$it" }
        repeat(500) {
            fun some(): Set<String> = people.filterTo(HashSet()) { rng.nextInt(4) == 0 }
            val groups =
                (0 until rng.nextInt(4)).map { i ->
                    GroupEntity(
                        groupId = "g-$i",
                        name = "",
                        members = GroupMembersStore.encode((some() + "me").toList()),
                        createdBy = "me",
                        createdAt = 1L,
                        left = rng.nextInt(3) == 0,
                    )
                }
            val groupIds = groups.map { group -> group.groupId }.toSet()
            val conversations = some() + groupIds + Conversations.NEARBY
            val authored = some().filterTo(HashSet()) { rng.nextBoolean() } + groupIds.filter { rng.nextBoolean() }
            val accepted = some() + groupIds.filter { rng.nextBoolean() }
            val verified = some()
            val groupSenders = groups.associate { group -> group.groupId to some() }
            val contacts = contactIds(conversations, authored, groups, groupSenders, accepted, verified, emptySet(), "me")
            val anchors = labelAnchors(accepted, authored, groups) + verified
            assertTrue("$contacts ⊄ $anchors", anchors.containsAll(contacts))
        }
    }

    @Test
    fun groupAndRoomIdsAreNotAnchorsAndALeftGroupLendsNobody() {
        val active = GroupEntity("g-a", "", GroupMembersStore.encode(listOf("me", "m1")), "me", 1L)
        val left = GroupEntity("g-l", "", GroupMembersStore.encode(listOf("me", "m2")), "me", 1L, left = true)
        val anchors = labelAnchors(setOf("p1", "g-a"), setOf("p2", Conversations.NEARBY), listOf(active, left))
        assertEquals(setOf("p1", "p2", "me", "m1"), anchors)
    }

    @Test
    fun currentReadsOnceWhenNothingHoldsTheSharedValue() =
        runTest {
            val anchors =
                LabelAnchors(MutableStateFlow(setOf("p1")), MutableStateFlow(emptySet()), MutableStateFlow(emptyList()), backgroundScope)
            assertEquals(setOf("p1"), anchors.current())
            assertEquals(setOf("p1"), anchors.observe().first())
        }
}
