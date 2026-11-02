package app.getknit.knit.mesh.link

import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.protocol.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who must prove the node id its link HELLO claims ([LinkClaims], ADR 2026-11.264w): an id is proof-capable from its
 * stored profile's bit or a proof this run, each gain tells every plane once, a downgraded profile releases the id, and
 * the three counters move only through the claims.
 */
class LinkClaimsTest {
    private val metrics = MeshMetrics()
    private val lines = mutableListOf<String>()
    private val claims = LinkClaims(metrics, log = { lines += it })
    private val told = mutableListOf<String>()
    private val released = mutableListOf<String>()
    private val displaced = mutableListOf<String>()

    init {
        claims.onCapable { told += it }
        claims.onReleased { released += it }
        claims.onDisplaced { displaced += it }
    }

    @Test
    fun aStoredProfileWithTheBitMakesTheIdProofCapableAndTellsThePlanesOnce() {
        assertFalse(claims.isProofCapable(X))

        claims.profileCapabilities(X, Protocol.LOCAL_CAPABILITIES)
        claims.profileCapabilities(X, Protocol.LOCAL_CAPABILITIES)

        assertTrue(claims.isProofCapable(X))
        assertEquals(listOf(X), told)
    }

    @Test
    fun aProfileWithoutTheBitIsNotProofCapable() {
        claims.profileCapabilities(X, Protocol.LOCAL_CAPABILITIES and Protocol.CAP_LINK_PROOF.inv())
        claims.profileCapabilities(Y, null)

        assertFalse(claims.isProofCapable(X))
        assertFalse(claims.isProofCapable(Y))
        assertEquals(emptyList<String>(), told)
    }

    @Test
    fun aDowngradedProfileReleasesTheIdUnlessItProvedItselfThisRun() {
        claims.profileCapabilities(X, Protocol.CAP_LINK_PROOF)
        claims.profileCapabilities(Y, Protocol.CAP_LINK_PROOF)
        claims.proven(Y, "bt")

        claims.profileCapabilities(X, 0L)
        claims.profileCapabilities(Y, 0L)

        assertFalse(claims.isProofCapable(X))
        assertTrue("a proof this run still counts", claims.isProofCapable(Y))
        assertEquals(listOf(X), released)
    }

    @Test
    fun aProofCountsMakesTheIdProofCapableAndTellsThePlanesEveryTime() {
        claims.proven(X, "bt")
        claims.proven(X, "nan")

        assertTrue(claims.isProofCapable(X))
        assertEquals("R3 runs on every proof", listOf(X, X), told)
        assertEquals(2L, metrics.frames().linksProven)
        assertEquals(emptyList<String>(), displaced)
    }

    @Test
    fun aDisplacementIsReportedFromTheProofOrFromAClaimClosedForAProvenId() {
        claims.proven(X, "bt", displaced = true)
        claims.claimClosed(Y, "nan") // not proven: an R4 close by a profile, not a displacement
        claims.proven(Z, "link")
        claims.claimClosed(Z, "bt") // another plane's squatter, closed after Z proved itself

        assertEquals(listOf(X, Z), displaced)
        assertEquals("X's replaced link, Y's and Z's closes", 3L, metrics.frames().linkClaimRefused)
    }

    /**
     * A proven link that takes an unproven link's place on its own radio closes a claim, and counts it once (contract
     * revision 6, as knit-ios counts it): no plane's listener sees that link, each skipping the proven one now held.
     */
    @Test
    fun aSameRadioDisplacementCountsOneClosedClaim() {
        claims.proven(X, "bt")
        assertEquals("a proof that replaced nothing closes no claim", 0L, metrics.frames().linkClaimRefused)

        claims.proven(Y, "bt", displaced = true)

        val frames = metrics.frames()
        assertEquals(1L, frames.linkClaimRefused)
        assertEquals(2L, frames.linksProven)
        assertEquals(0L, frames.linkProofFailed)
    }

    @Test
    fun theSeedTellsThePlanesOfEachNewIdOnly() {
        claims.profileCapabilities(X, Protocol.CAP_LINK_PROOF)
        told.clear()

        claims.seed(listOf(X, Y, Z))

        assertEquals(listOf(Y, Z), told)
        assertTrue(listOf(X, Y, Z).all(claims::isProofCapable))
    }

    @Test
    fun refusalsAndFailuresCountAndLogTheClaimedIdOnce() {
        claims.refused(X, "bt", "unproven hello")
        claims.proofFailed(Y, "nan", "signature")
        claims.proofFailed(null, "bt", "no nonce")

        val frames = metrics.frames()
        assertEquals(1L, frames.linkClaimRefused)
        assertEquals(2L, frames.linkProofFailed)
        assertEquals(0L, frames.linksProven)
        assertEquals(3, lines.size)
        assertTrue(lines[0].contains(X) && lines[0].contains("unproven hello"))
        assertTrue(lines[1].contains(Y) && lines[1].contains("signature"))
    }

    @Test
    fun aClosedListenerIsToldNothingMore() {
        val late = mutableListOf<String>()
        val handle = claims.onCapable { late += it }
        claims.proven(X, "bt")
        handle.close()
        claims.proven(Y, "bt")

        assertEquals(listOf(X), late)
    }

    @Test
    fun theIdsProvenThisRunAreBoundedOldestOut() {
        val ids = (0..LinkClaims.MAX_PROVEN).map { NodeId.derive("churn-$it") }
        ids.forEach { claims.proven(it, "bt") }

        assertFalse("the oldest is forgotten", claims.isProofCapable(ids.first()))
        assertTrue(claims.isProofCapable(ids[1]))
        assertTrue(claims.isProofCapable(ids.last()))
    }

    private companion object {
        val X = NodeId.derive("claims-x")
        val Y = NodeId.derive("claims-y")
        val Z = NodeId.derive("claims-z")
    }
}
