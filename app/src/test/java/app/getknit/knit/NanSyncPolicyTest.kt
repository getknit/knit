package app.getknit.knit

import app.getknit.knit.mesh.wifiaware.NanSyncPolicy
import app.getknit.knit.mesh.wifiaware.NanSyncPolicy.PeerFacts
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [NanSyncPolicy] — the pure "is a NAN sync owed?" folds, and the digest/bulk split they guard. */
class NanSyncPolicyTest {
    /** A [PeerFacts] with every axis off; each test flips only what it exercises. */
    private fun peer(
        initiator: Boolean = false,
        linked: Boolean = false,
        discovered: Boolean = false,
        digestWanted: Boolean = false,
        bulkWanted: Boolean = false,
        lingerReachable: Boolean = false,
        corroborated: Boolean = false,
    ) = PeerFacts(initiator, linked, discovered, digestWanted, bulkWanted, lingerReachable, corroborated)

    @Test
    fun aBulkOnlyMarkRaisesAnInitiateButTripsNoRecoverySite() {
        // The load-bearing invariant: a pending image (bytes ride the BLE fallback) is worth an initiate but
        // must never run the wedge clock or churn subscribe re-arm.
        val f = listOf(peer(initiator = true, bulkWanted = true, lingerReachable = true, corroborated = true))
        assertTrue("admission is bulk-aware", NanSyncPolicy.initiateOwed(f))
        assertTrue(NanSyncPolicy.initiateOwedToReachable(f))
        assertFalse("Tier-1 owed clock is digest-pure", NanSyncPolicy.anyReachableSyncOwed(f))
        assertFalse("Tier-2 owed clock is digest-pure", NanSyncPolicy.anySyncOwed(f))
        assertFalse("subscribe re-arm is digest-pure", NanSyncPolicy.needsRediscovery(f))
        assertFalse("ICM relight is digest-pure", NanSyncPolicy.needsIcmRelight(f))
        assertFalse("fast-tick cadence is digest-pure", NanSyncPolicy.anyInitiatorDigestOwed(f))
    }

    @Test
    fun aDigestMarkReachesBothAdmissionAndRecovery() {
        val f = listOf(peer(initiator = true, digestWanted = true, lingerReachable = true, corroborated = true))
        assertTrue(NanSyncPolicy.initiateOwed(f))
        assertTrue(NanSyncPolicy.anyReachableSyncOwed(f))
        assertTrue(NanSyncPolicy.anySyncOwed(f))
        assertTrue(NanSyncPolicy.anyInitiatorDigestOwed(f))
    }

    @Test
    fun tier2NeedsCorroborationButTier1DoesNot() {
        val uncorroborated = listOf(peer(digestWanted = true, lingerReachable = true, corroborated = false))
        assertTrue("Tier-1 fires uncorroborated", NanSyncPolicy.anyReachableSyncOwed(uncorroborated))
        assertFalse("Tier-2 needs corroboration", NanSyncPolicy.anySyncOwed(uncorroborated))
    }

    @Test
    fun aWalkedAwayPeerIsStillInitiateOwedButRunsNoWedgeClock() {
        // digest differs but not reachable → the watchdog must NOT count it (the 180 s self-kill with no peer
        // present bug), yet an initiate is still nominally owed (a cheap no-op if it turns out gone).
        val f = listOf(peer(initiator = true, digestWanted = true, lingerReachable = false, corroborated = false))
        assertFalse("owed clock excludes the unreachable peer", NanSyncPolicy.anyReachableSyncOwed(f))
        assertTrue(NanSyncPolicy.initiateOwed(f))
        assertFalse("the expensive session-cycle gate needs reachability", NanSyncPolicy.initiateOwedToReachable(f))
    }

    @Test
    fun needsRediscoveryIsTrueWhenBlindAndForAnUndiscoveredOwedInitiatorPeer() {
        assertTrue("blind (no cue targets) re-fires discovery", NanSyncPolicy.needsRediscovery(emptyList()))
        assertTrue(NanSyncPolicy.needsRediscovery(listOf(peer(initiator = true, digestWanted = true))))
        assertFalse(
            "a discovered peer needs no re-arm",
            NanSyncPolicy.needsRediscovery(listOf(peer(initiator = true, digestWanted = true, discovered = true))),
        )
        assertFalse(
            "a linked peer needs no re-arm",
            NanSyncPolicy.needsRediscovery(listOf(peer(initiator = true, digestWanted = true, linked = true))),
        )
    }

    @Test
    fun needsIcmRelightIsTheResponderSideMirror() {
        assertTrue(
            "smaller-side (responder) owed peer relights ICM",
            NanSyncPolicy.needsIcmRelight(listOf(peer(initiator = false, digestWanted = true))),
        )
        assertFalse(
            "the initiator side is handled by needsRediscovery",
            NanSyncPolicy.needsIcmRelight(listOf(peer(initiator = true, digestWanted = true))),
        )
    }

    @Test
    fun anyInitiatorDigestOwedHasNoLinkedGate() {
        // Faithful to the inline rediscoverDelayMs fold: it gates only on initiator + digestWanted, not !linked.
        assertTrue(NanSyncPolicy.anyInitiatorDigestOwed(listOf(peer(initiator = true, digestWanted = true, linked = true))))
        assertFalse(NanSyncPolicy.anyInitiatorDigestOwed(listOf(peer(initiator = false, digestWanted = true))))
    }
}
