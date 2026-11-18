package app.getknit.knit.mesh.sim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class WorkloadTest {
    @Test
    fun groupChatsMoveNoDmOrRoomPost() {
        val without = schedule(Friends.Group)
        val with = schedule(Friends.Group, groupMeanMs = GROUP_MEAN_MS)
        assertTrue("no group post", with.any { it is SimEvent.Group })
        assertEquals(without, with.filterNot { it is SimEvent.Group })
        assertTrue("a group post with them off", without.none { it is SimEvent.Group })
    }

    @Test
    fun aMeanOfZeroTurnsItsKindOff() {
        val noDms = schedule(Friends.Group, dmMeanMs = 0, groupMeanMs = GROUP_MEAN_MS)
        assertTrue(noDms.none { it is SimEvent.Dm })
        assertTrue(noDms.any { it is SimEvent.Room } && noDms.any { it is SimEvent.Group })
        val noRoom = schedule(Friends.Group, roomMeanMs = 0)
        assertTrue(noRoom.none { it is SimEvent.Room })
        assertTrue(noRoom.any { it is SimEvent.Dm })
    }

    @Test
    fun onlyAPartyOfThreeOrMoreHasAChatAndEachOfItsMembersPostsThere() {
        val posts = schedule(Friends.Group, groupMeanMs = GROUP_MEAN_MS).filterIsInstance<SimEvent.Group>()
        val parties = Workload.parties(GROUPS, Friends.Group)
        for ((p, party) in parties.withIndex()) {
            val theirs = posts.filter { it.party == p }
            if (party.size < Workload.GROUP_CHAT_MIN) {
                assertTrue("party $p of ${party.size} has a chat", theirs.isEmpty())
            } else {
                assertEquals("party $p: who posted", party.toSet(), theirs.map { it.from }.toSet())
            }
        }
        assertTrue("a post from outside its party", posts.all { it.from in parties[it.party] })
    }

    @Test
    fun aCircleIsOneParty() {
        val circles = Friends.Circle(listOf(listOf(0, 1), listOf(2, 3)), awayShare = 0.5)
        assertEquals(listOf(listOf(0, 1, 2, 3, 4), listOf(5, 6, 7, 8, 9)), Workload.parties(GROUPS, circles))
        val posts = schedule(circles, groupMeanMs = GROUP_MEAN_MS).filterIsInstance<SimEvent.Group>()
        assertEquals(setOf(0, 1), posts.map { it.party }.toSet())
    }

    @Test
    fun eachMemberPostsAtItsMeanRate() {
        val hours = 20L
        val posts = schedule(Friends.Group, groupMeanMs = GROUP_MEAN_MS, durationMs = hours * HOUR_MS).filterIsInstance<SimEvent.Group>()
        val members = Workload.parties(GROUPS, Friends.Group).filter { it.size >= Workload.GROUP_CHAT_MIN }.sumOf { it.size }
        val expected = members * hours * HOUR_MS / GROUP_MEAN_MS.toDouble()
        assertTrue("${posts.size} posts against $expected", abs(posts.size - expected) <= 0.1 * expected)
    }

    @Test
    fun theBacklogFollowsTheOutingsMixAndStaysInEachParty() {
        val parties = Workload.parties(GROUPS, Friends.Group)
        val backlog = Workload.backlog(SEED, parties, BACKLOG, dmMeanMs = 20_000, roomMeanMs = 60_000, groupMeanMs = 30_000)
        assertEquals(backlog, Workload.backlog(SEED, parties, BACKLOG, dmMeanMs = 20_000, roomMeanMs = 60_000, groupMeanMs = 30_000))
        val partyOf = parties.withIndex().flatMap { (p, party) -> party.map { it to p } }.toMap()
        // Everyone sends its share; a walker alone has nobody to DM and no chat, so it only posts in the room.
        assertEquals(GROUPS.flatten().size * BACKLOG, backlog.size)
        assertEquals(backlog.size, backlog.map { it.body }.distinct().size)
        for (event in backlog) {
            when (event) {
                is SimEvent.Dm -> {
                    assertEquals("a DM outside the party", partyOf[event.from], partyOf[event.to])
                }

                is SimEvent.Group -> {
                    assertEquals(partyOf[event.from], event.party)
                }

                is SimEvent.Room -> {}
            }
        }
        assertTrue("the lone walker DMed or posted in a chat", backlog.filter { it.from == LONE }.all { it is SimEvent.Room })
        // In a party with a chat, the odds are 1/20 : 1/60 : 1/30 of a second, so half DMs, a sixth room, a third group.
        val chatted = backlog.filter { parties[partyOf.getValue(it.from)].size >= Workload.GROUP_CHAT_MIN }
        val dmShare = chatted.count { it is SimEvent.Dm }.toDouble() / chatted.size
        val groupShare = chatted.count { it is SimEvent.Group }.toDouble() / chatted.size
        assertTrue("DMs $dmShare of the backlog", abs(dmShare - 0.5) <= 0.05)
        assertTrue("group posts $groupShare of the backlog", abs(groupShare - 1.0 / 3) <= 0.05)
        // Without group chats, the backlog has no group posts either.
        assertTrue(Workload.backlog(SEED, parties, BACKLOG, 20_000, 60_000, groupMeanMs = 0).none { it is SimEvent.Group })
        assertTrue(Workload.backlog(SEED, parties, backlog = 0, 20_000, 60_000, 30_000).isEmpty())
    }

    private fun schedule(
        friends: Friends,
        dmMeanMs: Long = 20_000,
        roomMeanMs: Long = 60_000,
        groupMeanMs: Long = 0,
        durationMs: Long = HOUR_MS,
    ) = Workload.schedule(SEED, GROUPS, durationMs, dmMeanMs, roomMeanMs, friends, groupMeanMs)

    private companion object {
        const val SEED = 11L
        const val HOUR_MS = 3_600_000L
        const val GROUP_MEAN_MS = 30_000L
        const val BACKLOG = 600
        const val LONE = 9

        // A three, a pair, a four and a walker alone.
        val GROUPS = listOf(listOf(0, 1, 2), listOf(3, 4), listOf(5, 6, 7, 8), listOf(9))
    }
}
