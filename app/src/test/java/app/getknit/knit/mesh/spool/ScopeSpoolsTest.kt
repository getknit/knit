package app.getknit.knit.mesh.spool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The union rule (ADR 2026-10.8kfx): what a scope is synced at, which relays a config may make this phone dial, and when
 * a config is due. Above all, a phone where every home is `public-1` issues nothing and syncs exactly as before.
 */
class ScopeSpoolsTest {
    private val p1 = PublicSpools.LEGACY_URL
    private val p2 = PublicSpools.urlOf("public-2.spool.getknit.app")
    private val p3 = PublicSpools.urlOf("public-3.spool.getknit.app")
    private val mine = "wss://relay.example.org/spool/v1?k=t"

    @Test
    fun aDmWithNoConfigSyncsAtPublicOneTheHomeAndTheOwnRelays() {
        assertEquals(
            setOf(p1, mine),
            ScopeSpools.forDm(null, peerDeclared = true, home = "public-1.spool.getknit.app", own = setOf(mine), parked = emptySet()),
        )
        assertEquals(
            setOf(p1, p2),
            ScopeSpools.forDm(null, peerDeclared = false, home = "public-2.spool.getknit.app", own = emptySet(), parked = emptySet()),
        )
    }

    @Test
    fun aDmConfigNamingBothHomesDropsPublicOneOnceThePeerDeclared() {
        val config = listOf(p2, p3)
        assertEquals(
            setOf(p2, p3),
            ScopeSpools.forDm(config, peerDeclared = true, home = "public-2.spool.getknit.app", own = emptySet(), parked = emptySet()),
        )
        assertEquals(
            setOf(p1, p2, p3),
            ScopeSpools.forDm(config, peerDeclared = false, home = "public-2.spool.getknit.app", own = emptySet(), parked = emptySet()),
        )
    }

    @Test
    fun aConfigCannotMakeThisPhoneDialARelayItDidNotChoose() {
        val config =
            listOf(
                "wss://evil.example/spool/v1",
                "wss://public-2.spool.getknit.app:443/spool/v1",
                "WSS://public-3.spool.getknit.app/spool/v1",
                mine,
            )
        assertEquals(setOf(p3, mine), ScopeSpools.forDm(config, peerDeclared = true, home = null, own = setOf(mine), parked = emptySet()))
        assertEquals(setOf(p3), ScopeSpools.forDm(config, peerDeclared = true, home = null, own = emptySet(), parked = emptySet()))
    }

    @Test
    fun aParkedRelayIsNeverDialledWhateverNamesIt() {
        assertEquals(
            setOf(p2),
            ScopeSpools.forDm(null, peerDeclared = false, home = "public-2.spool.getknit.app", own = emptySet(), parked = setOf(p1)),
        )
        assertEquals(setOf(p1), ScopeSpools.forGroup(listOf(p3), allDeclared = false, own = emptySet(), parked = setOf(p3)))
    }

    @Test
    fun aGroupLivesOnItsOwnRelayAndNotOnTheMembersHomes() {
        assertEquals(setOf(p1), ScopeSpools.forGroup(null, allDeclared = true, own = emptySet(), parked = emptySet()))
        assertEquals(setOf(p3, mine), ScopeSpools.forGroup(listOf(p3), allDeclared = true, own = setOf(mine), parked = emptySet()))
        assertEquals(setOf(p1, p3), ScopeSpools.forGroup(listOf(p3), allDeclared = false, own = emptySet(), parked = emptySet()))
    }

    @Test
    fun theOwnRelaysLeaveEveryDirectoryRelayOut() {
        assertEquals(setOf(mine), ScopeSpools.own(setOf(p1, p2, mine)))
    }

    @Test
    fun aPhoneHomedOnPublicOneIssuesNothingAndAnyOtherHomeIssuesOnce() {
        assertFalse(ScopeSpools.mustIssueDm(null, "public-1.spool.getknit.app"))
        assertTrue(ScopeSpools.mustIssueDm(null, "public-2.spool.getknit.app"))
        assertFalse(ScopeSpools.mustIssueDm(listOf(p2, p3), "public-2.spool.getknit.app"))
        assertTrue(ScopeSpools.mustIssueDm(listOf(p2, p3), "public-1.spool.getknit.app"))
    }

    @Test
    fun aReissueSwapsTheOldHomeForTheNewAndKeepsThePeers() {
        assertEquals(listOf(p2, p1), ScopeSpools.dmSpoolsAfter(null, previous = null, home = "public-2.spool.getknit.app"))
        assertEquals(
            listOf(p3, p2),
            ScopeSpools.dmSpoolsAfter(listOf(p1, p2), previous = "public-1.spool.getknit.app", home = "public-3.spool.getknit.app"),
        )
    }

    @Test
    fun aGroupMovesOnlyWhenItsDirectoryRelayDrainsOrGoes() {
        val directory =
            listOf(
                PublicSpool("public-1.spool.getknit.app", PublicSpool.OPEN, 1),
                PublicSpool("public-3.spool.getknit.app", PublicSpool.DRAINING, 5),
            )
        assertFalse(ScopeSpools.groupMustMove(null, directory))
        assertTrue(ScopeSpools.groupMustMove(listOf(p3), directory))
        assertTrue(ScopeSpools.groupMustMove(listOf(p2), directory))
        assertFalse(ScopeSpools.groupMustMove(listOf(mine), directory))
    }
}
