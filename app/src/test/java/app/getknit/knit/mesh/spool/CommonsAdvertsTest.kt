package app.getknit.knit.mesh.spool

import app.getknit.knit.mesh.crypto.scope.ScopeCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** How a HELLO's `rooms` and `commons` resolve to one room's advertisement (spec §7.4, C-7.4-17). */
class CommonsAdvertsTest {
    private fun secret(seed: Int) = ByteArray(32) { ((it * 7 + seed) and 0xFF).toByte() }

    private val homeId = ScopeCrypto.commonsScopeId(secret(10))
    private val teamId = ScopeCrypto.commonsScopeId(secret(11))
    private val goneId = ScopeCrypto.commonsScopeId(secret(12))

    private fun tag(id: ByteArray) = ScopeCrypto.commonsTag(id)

    private fun tagHex(id: ByteArray) = hex(tag(id))

    private val home = SpoolCommonsInfo("Home", maxFrames = 500, ttlMs = 86_400_000L, maxBlob = 65_536)
    private val team = SpoolCommonsInfo(null, maxFrames = 200, ttlMs = 3_600_000L, maxBlob = 32_768, attach = true)

    @Test
    fun aListedRoomIsFoundByItsTagWithItsOwnAdvertisement() {
        val adverts = CommonsAdverts.of(home, listOf(tag(homeId) to home, tag(teamId) to team))!!

        val listed = adverts.advertFor(tagHex(teamId))
        assertTrue(listed is CommonsAdvert.Listed)
        assertSame(team, listed.info)
        assertSame(home, adverts.advertForScope(homeId).info)
        assertEquals(2, adverts.count)
        assertTrue(adverts.runsAny)
    }

    @Test
    fun aTagMissingFromTheListIsAbsentAndCommonsIsIgnored() {
        val adverts = CommonsAdverts.of(home, listOf(tag(homeId) to home))!!

        // `commons` repeats rooms[0]; a room the list does not name is not run there, whatever `commons` says.
        assertSame(CommonsAdvert.Absent, adverts.advertForScope(goneId))
        assertNull(adverts.advertForScope(goneId).info)
    }

    @Test
    fun aTagListedTwiceMatchesNeitherEntry() {
        val adverts = CommonsAdverts.of(null, listOf(tag(homeId) to home, tag(homeId) to team, tag(teamId) to team))!!

        assertSame(CommonsAdvert.Absent, adverts.advertForScope(homeId))
        assertTrue(adverts.advertForScope(teamId) is CommonsAdvert.Listed)
    }

    @Test
    fun aTagOfTheWrongWidthMatchesNothing() {
        val adverts = CommonsAdverts.of(null, listOf(tag(homeId).copyOf(7) to home, ScopeCrypto.commonsScopeId(secret(10)) to team))!!

        assertSame(CommonsAdvert.Absent, adverts.advertForScope(homeId))
        assertEquals(0, adverts.count)
        assertFalse(adverts.runsAny)
    }

    @Test
    fun aSpoolThatPredatesTheListAdvertisesOneRoomWithoutSayingWhich() {
        val adverts = CommonsAdverts.of(home, rooms = null)!!

        for (id in listOf(homeId, teamId, goneId)) {
            val advert = adverts.advertForScope(id)
            assertTrue(advert is CommonsAdvert.Sole)
            assertSame(home, advert.info)
        }
        assertEquals(1, adverts.count)
    }

    @Test
    fun aHelloWithNoRoomAdvertisesNone() {
        assertNull(CommonsAdverts.of(null, rooms = null))
        val empty = CommonsAdverts.of(home, emptyList())!!
        assertFalse(empty.runsAny)
        assertSame(CommonsAdvert.Absent, empty.advertForScope(homeId))
    }
}
