package app.getknit.knit.mesh

import app.getknit.knit.data.peer.PeerEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two pure rules of ADR 2026-11.gsqu's Stage 4: what our sealed profile claims about MeshCore ([MeshcoreClaimRule]),
 * and which contacts' MeshCore boards Stage 5 may teach ours ([MeshcoreBoardMap]).
 */
class MeshcoreClaimTest {
    // --- The claim ---

    @Test
    fun theKeyIsClaimedOnlyWhileAllFourHold() {
        assertEquals(KEY_A, MeshcoreClaimRule.claim(KEY_A, enabled = true, dms = true, readsMeshcoreDms = true))
        assertNull("the plane off", MeshcoreClaimRule.claim(KEY_A, enabled = false, dms = true, readsMeshcoreDms = true))
        assertNull("DMs off", MeshcoreClaimRule.claim(KEY_A, enabled = true, dms = false, readsMeshcoreDms = true))
        assertNull("no MeshCore board bound", MeshcoreClaimRule.claim(null, enabled = true, dms = true, readsMeshcoreDms = true))
        // Stage 4 binds no seam: whatever the settings hold, even a key a backup restored, nothing is claimed.
        assertNull("the seam unbound", MeshcoreClaimRule.claim(KEY_A, enabled = true, dms = true, readsMeshcoreDms = false))
    }

    @Test
    fun aBoardReportThatIsNotAKeyIsNeverClaimed() {
        for (bad in listOf("", "QQ==", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "11qYAYKxCrfVS/7TyWQHOg7hcvPapiMlrwIaaPcHURp=")) {
            assertNull(bad, MeshcoreClaimRule.claim(bad, enabled = true, dms = true, readsMeshcoreDms = true))
        }
    }

    // --- The board map ---

    private val me = "me"
    private val now = 100L * DAY

    private fun peer(
        id: String,
        key: String?,
        verified: Boolean = false,
        pinned: Boolean = true,
        version: Long = now - DAY,
        seen: Long = now - DAY,
    ) = PeerEntity(
        nodeId = id,
        pubKey = if (pinned) "pk-$id" else null,
        verified = verified,
        updatedAt = version,
        lastSeenAt = seen,
        meshcoreKey = key,
    )

    /**
     * The map over [peers], with [direct] standing in for `DirectContacts`' set: it has already left out commons posters'
     * automatic accepts, the blocked, the tag-blocked and us, so a test of those leaves them out of [direct] as it does.
     */
    private fun map(
        vararg peers: PeerEntity,
        direct: Set<String> = setOf("ann", "bea", "cat", "dan", "eve"),
        accepted: Set<String> = direct,
        ownKey: String? = null,
    ) = MeshcoreBoardMap.of(peers.toList(), me, direct, accepted, ownKey, now)

    @Test
    fun aContactsClaimIsOnTheMapWithItsRankAndVersion() {
        val held =
            map(
                peer("ann", KEY_A, verified = true, version = 7L),
                peer("bea", KEY_B),
                peer("cat", KEY_C),
                accepted = setOf("ann", "bea"),
            )
        assertEquals(setOf("ann", "bea", "cat"), held.keys)
        assertEquals(MeshcoreBoardHolder(KEY_A, MeshcoreRank.VERIFIED, 7L, contested = false), held["ann"])
        assertEquals(MeshcoreRank.ACCEPTED, held["bea"]?.rank)
        assertEquals("a direct contact not accepted nor verified wrote in the DM", MeshcoreRank.AUTHORED, held["cat"]?.rank)
    }

    @Test
    fun onlyDirectContactsWithAPinnedKeyAreOnTheMap() {
        // A stranger, a commons poster's automatic accept, a blocked or tag-blocked person: none is in the direct set.
        val held = map(peer("zed", KEY_A), peer("ann", KEY_B, pinned = false), direct = setOf("ann"))
        assertTrue("a claim outside the direct set, and a claim with no pinned key: $held", held.isEmpty())
    }

    @Test
    fun ourOwnNodeAndOurOwnBoardsKeyAreNotOnTheMap() {
        // Our own row (a re-served copy of our profile) and a contact whose stale or false claim names our board.
        val held = map(peer(me, KEY_C), peer("ann", KEY_A), peer("bea", KEY_B), direct = setOf(me, "ann", "bea"), ownKey = KEY_A)
        assertEquals(setOf("bea"), held.keys)
    }

    @Test
    fun aKeyTwoLiveContactsClaimIsMarkedContestedOnBoth() {
        val held = map(peer("ann", KEY_A, verified = true), peer("bea", KEY_A), peer("cat", KEY_B))
        assertTrue(checkNotNull(held["ann"]).contested)
        assertTrue("the verified one is not preferred: neither is taught", checkNotNull(held["bea"]).contested)
        assertFalse(checkNotNull(held["cat"]).contested)
    }

    @Test
    fun aCommonsAcceptedOrTagBlockedClaimantNeverContestsAContact() {
        // Both claim Ann's key; DirectContacts left them out (a commons poster's mark, a blocked device tag), so they
        // contest nothing and are not on the map: an invite holder cannot block, or take, a real contact's board.
        val held = map(peer("ann", KEY_A), peer("poster", KEY_A), peer("tagged", KEY_A), direct = setOf("ann"))
        assertEquals(setOf("ann"), held.keys)
        assertFalse(checkNotNull(held["ann"]).contested)
    }

    @Test
    fun aDeadIdentitysOldClaimNeverContestsItsSuccessor() {
        // Ann wiped her phone 40 days ago: her old identity's row still holds K, and nothing will ever clear it.
        val dead = peer("ann", KEY_A, version = now - 40 * DAY, seen = now - 40 * DAY)
        val successor = peer("bea", KEY_A)
        val held = map(dead, successor)
        assertEquals("the dead claimant leaves beside a live one", setOf("bea"), held.keys)
        assertFalse(checkNotNull(held["bea"]).contested)
        // Heard from a day ago with a profile last edited 35 days ago: live, so contested — a version moves only on an
        // edit, and two senders' versions are two clocks. A newer version alone never decides.
        val unedited = peer("ann", KEY_A, version = now - 35 * DAY, seen = now - DAY)
        assertTrue(checkNotNull(map(unedited, successor)["bea"]).contested)
        assertTrue(checkNotNull(map(unedited, successor)["ann"]).contested)
    }

    @Test
    fun liveIsHeardWithinTheBoundAndNothingElse() {
        val bound = MeshcoreBoardMap.LIVE_BOUND_MS
        val rival = peer("bea", KEY_A)
        // Heard exactly at the bound: live, so contested.
        val atBound = peer("ann", KEY_A, seen = now - bound)
        assertTrue(checkNotNull(map(atBound, rival)["ann"]).contested)
        // One millisecond past it: not live, and it leaves beside the live one.
        val pastBound = peer("ann", KEY_A, seen = now - bound - 1)
        assertEquals(setOf("bea"), map(pastBound, rival).keys)
        assertFalse(checkNotNull(map(pastBound, rival)["bea"]).contested)
        // A newest-possible version does not make a silent claimant live: `lastSeenAt` alone decides.
        val silentButNew = peer("ann", KEY_A, version = now, seen = now - bound - 1)
        assertEquals(setOf("bea"), map(silentButNew, rival).keys)
    }

    @Test
    fun twoDeadClaimantsOfOneKeyBothStayContested() {
        val held = map(peer("ann", KEY_A, seen = now - 40 * DAY), peer("bea", KEY_A, seen = now - 50 * DAY))
        assertEquals("with none live, every claimant stays", setOf("ann", "bea"), held.keys)
        assertTrue(held.values.all { it.contested })
    }

    @Test
    fun aLiveHolderWithAnOldProfileStaysContestedAgainstAFreshRival() {
        // Ann holds K and has not edited her profile for 35 days, but was heard yesterday; Bea claims K today.
        val holder = peer("ann", KEY_A, verified = true, version = now - 35 * DAY, seen = now - DAY)
        val fresh = peer("bea", KEY_A, version = now, seen = now)
        val held = map(holder, fresh)
        assertEquals(setOf("ann", "bea"), held.keys)
        assertTrue("neither is taught", held.values.all { it.contested })
    }

    @Test
    fun aStoredKeyThatFailsTheCheckIsNotOnTheMap() {
        // Only the sealed writer stores one, already checked; the map checks again rather than trust the column.
        val held = map(peer("ann", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="), peer("bea", "QQ=="), peer("cat", null))
        assertTrue(held.toString(), held.isEmpty())
    }

    @Test
    fun neitherAnEntryNorAPeerRowNamesTheKeyWhenPrinted() {
        val entry = checkNotNull(map(peer("ann", KEY_A))["ann"])
        assertFalse(entry.toString(), entry.toString().contains(KEY_A.take(8)))
        val row = peer("ann", KEY_A).toString()
        assertFalse(row, row.contains(KEY_A.take(8)))
        assertTrue(row, row.contains("meshcoreKey=set"))
        assertTrue(peer("ann", null).toString().contains("meshcoreKey=unset"))
    }

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000

        // RFC 8032 §7.1's public keys, TESTs 1, 2 and 3: full-order points, as a MeshCore board's are.
        const val KEY_A = "11qYAYKxCrfVS/7TyWQHOg7hcvPapiMlrwIaaPcHURo="
        const val KEY_B = "PUAXw+hDiVqStwqnTRt+vJyYLM8uxJaMwM1V8Sr0Zgw="
        const val KEY_C = "/FHNjmIYoaONpH7QAjDwWAgW7RO6MwOsXeuRFUiQgCU="
    }
}
