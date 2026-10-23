package app.getknit.knit.mesh.spool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    private val listed = setOf("public-1.spool.getknit.app", "public-2.spool.getknit.app", "public-3.spool.getknit.app")

    private fun dm(
        config: List<String>?,
        peerDeclared: Boolean,
        home: String?,
        own: Set<String> = emptySet(),
        parked: Set<String> = emptySet(),
        listed: Set<String> = this.listed,
        public: Boolean = true,
    ) = ScopeSpools.forDm(config, peerDeclared, home, own, parked, listed, public)

    private fun group(
        config: List<String>?,
        allDeclared: Boolean,
        own: Set<String> = emptySet(),
        parked: Set<String> = emptySet(),
        public: Boolean = true,
    ) = ScopeSpools.forGroup(config, allDeclared, own, parked, listed, public)

    @Test
    fun aDmWithNoConfigSyncsAtPublicOneTheHomeAndTheOwnRelays() {
        assertEquals(setOf(p1, mine), dm(null, peerDeclared = true, home = "public-1.spool.getknit.app", own = setOf(mine)))
        assertEquals(setOf(p1, p2), dm(null, peerDeclared = false, home = "public-2.spool.getknit.app"))
    }

    @Test
    fun aDmConfigNamingBothHomesDropsPublicOneOnceThePeerDeclared() {
        val config = listOf(p2, p3)
        assertEquals(setOf(p2, p3), dm(config, peerDeclared = true, home = "public-2.spool.getknit.app"))
        assertEquals(setOf(p1, p2, p3), dm(config, peerDeclared = false, home = "public-2.spool.getknit.app"))
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
        assertEquals(setOf(p3, mine), dm(config, peerDeclared = true, home = null, own = setOf(mine)))
        assertEquals(setOf(p3), dm(config, peerDeclared = true, home = null))
    }

    @Test
    fun aDirectoryRelayTheDirectoryNoLongerListsIsNotDialledForAConfig() {
        assertEquals(
            setOf(p2),
            dm(listOf(p2, p3), peerDeclared = true, home = "public-2.spool.getknit.app", listed = listed - "public-3.spool.getknit.app"),
        )
    }

    @Test
    fun aParkedRelayIsNeverDialledWhateverNamesIt() {
        assertEquals(setOf(p2), dm(null, peerDeclared = false, home = "public-2.spool.getknit.app", parked = setOf(p1)))
        assertEquals(setOf(p1), group(listOf(p3), allDeclared = false, parked = setOf(p3)))
    }

    @Test
    fun aPhoneOffThePublicRelaysDialsOnlyItsOwnWhateverAConfigNames() {
        assertEquals(
            setOf(mine),
            dm(listOf(p2, p3), peerDeclared = true, home = "public-2.spool.getknit.app", own = setOf(mine), public = false),
        )
        assertEquals(emptySet<String>(), group(null, allDeclared = false, public = false))
        assertEquals(setOf(mine), ScopeSpools.base(setOf(mine), "public-2.spool.getknit.app"))
        assertFalse(ScopeSpools.publicOn(setOf(mine)))
    }

    @Test
    fun theBaseIsTheOwnRelaysAndTheHomeInPlaceOfTheSeededPublicOne() {
        assertEquals(setOf(mine, p2), ScopeSpools.base(setOf(p1, mine), "public-2.spool.getknit.app"))
        assertEquals(setOf(p1), ScopeSpools.base(setOf(p1), "public-1.spool.getknit.app"))
        // Before a home is settled, the house relay stands in for it.
        assertEquals(setOf(p1), ScopeSpools.base(setOf(p1), null))
    }

    @Test
    fun aGroupLivesOnItsOwnRelayAndNotOnTheMembersHomes() {
        assertEquals(setOf(p1), group(null, allDeclared = true))
        assertEquals(setOf(p3, mine), group(listOf(p3), allDeclared = true, own = setOf(mine)))
        assertEquals(setOf(p1, p3), group(listOf(p3), allDeclared = false))
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
    fun aDmConfigNamesTheHomeAndThePeersOwnHome() {
        // A peer that never issued is looked for where a build before homes lives.
        assertEquals(listOf(p2, p1), ScopeSpools.dmSpools(null, home = "public-2.spool.getknit.app", listed))
        // The peer's own config names its home first, whatever else it named.
        assertEquals(listOf(p2, p3), ScopeSpools.dmSpools(listOf(p3, p1, mine), home = "public-2.spool.getknit.app", listed))
        // A move names the new home and never the old one.
        assertEquals(listOf(p1, p3), ScopeSpools.dmSpools(listOf(p3, p2), home = "public-1.spool.getknit.app", listed))
        // Both on one home: one relay.
        assertEquals(listOf(p3), ScopeSpools.dmSpools(listOf(p3, p2), home = "public-3.spool.getknit.app", listed))
    }

    @Test
    fun aPeerHomeTheDirectoryDroppedOrNoPublicRelayFallsBackToPublicOne() {
        assertEquals(
            listOf(p2, p1),
            ScopeSpools.dmSpools(listOf(p3), home = "public-2.spool.getknit.app", listed - "public-3.spool.getknit.app"),
        )
        assertEquals(listOf(p2, p1), ScopeSpools.dmSpools(listOf(mine), home = "public-2.spool.getknit.app", listed))
        assertEquals(listOf(p2, p1), ScopeSpools.dmSpools(emptyList(), home = "public-2.spool.getknit.app", listed))
    }

    @Test
    fun anOldHomeThatDrainsIsStaleUnlessItIsTheirsOrOurs() {
        val draining =
            listOf(
                PublicSpool("public-1.spool.getknit.app", PublicSpool.OPEN, 1),
                PublicSpool("public-2.spool.getknit.app", PublicSpool.DRAINING, 1),
                PublicSpool("public-3.spool.getknit.app", PublicSpool.OPEN, 1),
            )
        // Moved from public-2 onto public-3, which the config already named as the peer's home.
        assertTrue(ScopeSpools.dmNamesStale(listOf(p2, p3), home = "public-3.spool.getknit.app", peerHome = p3, draining))
        // The peer still lives on the draining relay: that is the peer's to move.
        assertFalse(ScopeSpools.dmNamesStale(listOf(p3, p2), home = "public-3.spool.getknit.app", peerHome = p2, draining))
        // Nowhere open to go: our own draining home stays named.
        assertFalse(ScopeSpools.dmNamesStale(listOf(p2, p1), home = "public-2.spool.getknit.app", peerHome = p1, draining))
        // A relay of the person's own is never a directory relay to go stale.
        assertFalse(ScopeSpools.dmNamesStale(listOf(p3, mine), home = "public-3.spool.getknit.app", peerHome = p1, draining))
        assertFalse(ScopeSpools.dmNamesStale(null, home = "public-3.spool.getknit.app", peerHome = p1, draining))
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

    @Test
    fun aGroupMovesToTheHomeUnlessTheHomeIsNoBetter() {
        val directory =
            listOf(
                PublicSpool("public-1.spool.getknit.app", PublicSpool.OPEN, 1),
                PublicSpool("public-3.spool.getknit.app", PublicSpool.DRAINING, 5),
            )
        assertEquals(listOf(p1), ScopeSpools.groupMoveTo(listOf(p3), "public-1.spool.getknit.app", directory))
        assertNull(ScopeSpools.groupMoveTo(listOf(p1), "public-1.spool.getknit.app", directory))
        // Nothing open but a draining home: the group stays rather than moving onto another draining relay forever.
        assertNull(ScopeSpools.groupMoveTo(listOf(p3), "public-3.spool.getknit.app", directory))
    }

    @Test
    fun thePlacementReadsEachConversationsWinnerAndDeclarations() {
        fun config(
            id: String,
            spools: List<String>,
        ) = ScopeConfig(id, null, "issuer", spools, 400, 1L, 1, 1)
        val placement =
            ScopePlacement(
                configs = mapOf("peer" to config("peer", listOf(p2, p3)), "g1" to config("g1", listOf(p3))),
                declared = setOf("peer", "m1"),
                home = "public-2.spool.getknit.app",
                active = setOf(p1, mine),
                parked = emptySet(),
                directory = listed.map { PublicSpool(it, PublicSpool.OPEN, 1) },
            )
        assertEquals(setOf(p2, p3, mine), placement.forDm("peer"))
        assertEquals(setOf(p1, p2, mine), placement.forDm("stranger"))
        assertEquals(setOf(p3, mine), placement.forGroup("g1", setOf("me", "m1"), self = "me"))
        assertEquals(setOf(p1, p3, mine), placement.forGroup("g1", setOf("me", "m1", "m2"), self = "me"))
    }
}
