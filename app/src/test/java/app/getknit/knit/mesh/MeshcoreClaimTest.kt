package app.getknit.knit.mesh

import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.crypto.MeshcoreBind
import app.getknit.knit.mesh.crypto.MeshcoreBindFixture
import app.getknit.knit.mesh.crypto.MessageContent
import app.getknit.knit.mesh.crypto.b64
import app.getknit.knit.mesh.crypto.sealBytes
import app.getknit.knit.mesh.protocol.ProfilePayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure rules of ADR 2026-11.gsqu's Stage 4 and 5b-1: what our sealed profile claims about MeshCore
 * ([MeshcoreClaimRule]), which contacts' MeshCore boards Stage 5 may teach ours ([MeshcoreBoardMap]), and which owed
 * sessions an audience-only mint need not seal ([MeshcoreFanOutRule]).
 */
class MeshcoreClaimTest {
    // --- The claim ---

    private val ours = NodeId.derive("ours")

    /** The board's report as stored: RFC 8032 TEST 1's key ([KEY_A]) and, by default, its signature over our bind. */
    private fun report(
        key: String? = KEY_A,
        sig: String? = MeshcoreBindFixture.sig(ours),
    ) = MeshcoreBoardReport(key, sig)

    private fun claim(
        board: MeshcoreBoardReport = report(),
        enabled: Boolean = true,
        dms: Boolean = true,
        reads: Boolean = true,
        node: String = ours,
    ) = MeshcoreClaimRule.claim(board, node, enabled, dms, reads)

    @Test
    fun theKeyIsClaimedOnlyWhileAllFourHold() {
        assertEquals(MeshcoreBoardClaim(KEY_A, MeshcoreBindFixture.sig(ours)), claim())
        assertNull("the plane off", claim(enabled = false))
        assertNull("DMs off", claim(dms = false))
        assertNull("no MeshCore board bound", claim(report(key = null, sig = null)))
        // Stage 4 binds no seam: whatever the settings hold, even a key a backup restored, nothing is claimed.
        assertNull("the seam unbound", claim(reads = false))
    }

    @Test
    fun aBoardReportThatIsNotAKeyIsNeverClaimed() {
        for (bad in listOf("", "QQ==", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "11qYAYKxCrfVS/7TyWQHOg7hcvPapiMlrwIaaPcHURp=")) {
            assertNull(bad, claim(report(key = bad)))
        }
    }

    /** Stage 5d: a key is claimed only beside the board's signature over our own bind. */
    @Test
    fun aKeyIsClaimedOnlyWithASignatureThatBindsOurNode() {
        assertNull("no signature yet: the board has not signed", claim(report(sig = null)))
        assertNull("a signature over another node's bind (a backup from another identity)", claim(node = NodeId.derive("other")))
        assertNull("another board's signature", claim(report(sig = MeshcoreBindFixture.sig(ours, ByteArray(32) { 7 }))))
        assertNull("not 64 bytes", claim(report(sig = b64(MeshcoreBindFixture.sigBytes(ours).copyOf(63)))))
        assertNull("a key the signature was not made under", claim(report(key = KEY_B)))
    }

    @Test
    fun aReportAndAClaimNameNeitherKeyNorSignature() {
        val sig = MeshcoreBindFixture.sig(ours)
        for (printed in listOf(report().toString(), checkNotNull(claim()).toString())) {
            assertFalse(printed, printed.contains(KEY_A.take(8)))
            assertFalse(printed, printed.contains(sig.take(8)))
        }
    }

    // --- The copies (Stage 5d, crypto B2) ---

    private val keyless =
        MessageContent(body = "", ctl = MessageContent.CTL_PROFILE, pr = ProfilePayload(name = "Ann", status = "", version = 9L))

    @Test
    fun aDirectContactsCopyCarriesTheKeyAndTheSignatureTogether() {
        val claimed = checkNotNull(claim())
        for (v3 in listOf(true, false)) {
            val copy = MeshcoreProfileCopy.of(keyless, claimed, toContact = true, v3 = v3)
            assertEquals(KEY_A, copy.content.pr?.meshcoreKey)
            assertEquals(claimed.sig, copy.content.pr?.meshcoreSig)
            assertFalse(copy.padMissed)
        }
    }

    @Test
    fun aNonContactsCopyCarriesNeitherTheKeyNorTheSignatureAndIsOneLength() {
        val claimed = checkNotNull(claim())
        for (v3 in listOf(true, false)) {
            val keyed = MeshcoreProfileCopy.of(keyless, claimed, toContact = true, v3 = v3).content
            val copy = MeshcoreProfileCopy.of(keyless, claimed, toContact = false, v3 = v3)
            assertNull("v3=$v3", copy.content.pr?.meshcoreKey)
            assertNull("v3=$v3: the signature alone would tie our node to the board's adverts", copy.content.pr?.meshcoreSig)
            assertFalse(copy.padMissed)
            assertEquals(
                "v3=$v3",
                keyed.sealBytes(v3).plaintext.size,
                copy.content
                    .sealBytes(v3)
                    .plaintext.size,
            )
        }
    }

    @Test
    fun unclaimedEveryCopyIsTheBaseCopy() {
        for (toContact in listOf(true, false)) {
            assertEquals(keyless, MeshcoreProfileCopy.of(keyless, claim = null, toContact = toContact, v3 = true).content)
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

    /**
     * Stage 5d (security N2): a proof never breaks a tie. One board bound to two identities — a board handed on, a second
     * Knit identity, anyone on the default PIN — signs both binds, both verify, and the key is still contested on both.
     * A statement of the rule, not a guard: the map never sees a proof (ingest stores only proven keys and no signature),
     * so no implementation of it could let one break a tie, and this held before 5d too.
     */
    @Test
    fun twoProvenClaimantsOfOneKeyAreBothContestedAndNeitherTaught() {
        val ann = NodeId.derive("ann-proven")
        val bea = NodeId.derive("bea-proven")
        for (id in listOf(ann, bea)) {
            assertTrue("$id's bind verifies", MeshcoreBind.verifies(id, KEY_A, MeshcoreBindFixture.sig(id)))
        }
        val held = map(peer(ann, KEY_A, verified = true), peer(bea, KEY_A), direct = setOf(ann, bea))
        assertEquals(setOf(ann, bea), held.keys)
        assertTrue(held.values.all { it.contested })
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

    // --- The fan-out (Stage 5b-1) ---

    private fun era(
        version: Long,
        key: String? = KEY_A,
        direct: Set<String> = setOf("ann", "ben"),
        visible: String = "Alice",
    ) = SealedProfileEra(version, key, direct, visible)

    private fun spares(
        peer: String,
        held: SealedProfileEra?,
        next: SealedProfileEra,
        claimMinted: Boolean = true,
    ) = MeshcoreFanOutRule.spares(peer, held, next, claimMinted)

    @Test
    fun anAudienceOnlyMintSparesExactlyThePeersWhoseAnswerDidNotChange() {
        val held = era(1, direct = setOf("ann", "ben"))
        // Ben blocked, Cid accepted: their answers changed, Ann's (a contact) and Dee's (a stranger) did not.
        val next = era(2, direct = setOf("ann", "cid"))
        val spared = listOf("ann", "ben", "cid", "dee").filter { spares(it, held, next) }
        assertEquals(listOf("ann", "dee"), spared)
    }

    @Test
    fun aMintThatMovedAnythingElseSparesNobody() {
        val held = era(1)
        val peers = listOf("ann", "ben", "dee")
        val moved =
            mapOf(
                "a visible field" to era(2, visible = "Alice Again"),
                "the key changed" to era(2, key = KEY_B),
                "the key dropped" to era(2, key = null),
            )
        for ((what, next) in moved) assertTrue(what, peers.none { spares(it, held, next) })
        assertTrue("the key gained", peers.none { spares(it, era(1, key = null), era(2)) })
        assertTrue("unclaimed at both", peers.none { spares(it, era(1, key = null), era(2, key = null)) })
        assertTrue("not the claim's own mint", peers.none { spares(it, held, era(2), claimMinted = false) })
    }

    @Test
    fun aPeerWhoseLastCopyIsNotKnownIsSealed() {
        assertFalse("nothing recorded, or its era gone, or an intro since", spares("ann", null, era(2)))
        assertFalse("the version it holds is not older", spares("ann", era(2), era(2)))
        assertFalse(spares("ann", era(3), era(2)))
    }

    private fun owedAll(
        ids: List<String>,
        held: SealedProfileEra,
        sealedAt: Long = NOW - 1,
    ) = ids.map { OwedSeal(it, held, sealedAt) }

    private fun plan(
        owed: List<OwedSeal>,
        next: SealedProfileEra,
        now: Long = NOW,
        claimMinted: Boolean = true,
    ) = MeshcoreFanOutRule.plan(owed, next, claimMinted, now, STALE)

    @Test
    fun anAudienceOnlyMintSealsTheChangedPeersAndSparesTheRest() {
        val peers = (1..10).map { "p$it" }
        // p3 accepted and p2 blocked: the only answers that changed.
        val plan = plan(owedAll(peers, era(1, direct = setOf("p1", "p2"))), era(2, direct = setOf("p1", "p3")))
        assertEquals(setOf("p2", "p3"), plan.seal)
        assertEquals(peers.toSet() - setOf("p2", "p3"), plan.spare)
        assertEquals(0, plan.stale)
    }

    @Test
    fun theStaleBoundIsHalfOpenAndAFutureStampIsStale() {
        val held = era(1, direct = setOf("p1"))
        val next = era(2, direct = setOf("p1"))
        val cases =
            mapOf(
                "exactly the bound ago" to (NOW - STALE to true),
                "a millisecond inside it" to (NOW - STALE + 1 to false),
                "sealed now" to (NOW to false),
                "a millisecond in the future" to (NOW + 1 to true),
            )
        for ((what, case) in cases) {
            val (sealedAt, stale) = case
            val plan = plan(listOf(OwedSeal("p1", held, sealedAt)), next)
            assertEquals(what, if (stale) 1 else 0, plan.stale)
            assertEquals(what, if (stale) setOf("p1") else emptySet<String>(), plan.seal)
            assertEquals(what, if (stale) emptySet<String>() else setOf("p1"), plan.spare)
        }
    }

    @Test
    fun aStaleCopyIsSealedBesideTheChangedPeerAndOnlyThose() {
        val held = era(1, direct = setOf("p1", "p2", "p3", "p4"))
        val next = era(2, direct = setOf("p1", "p2", "p3", "p4", "p5"))
        val owed =
            listOf(
                OwedSeal("p1", held, sealedAt = NOW - 2 * DAY),
                OwedSeal("p2", held, sealedAt = NOW - 1),
                OwedSeal("p3", held, sealedAt = NOW - 1),
                OwedSeal("p4", held, sealedAt = NOW + DAY),
                OwedSeal("p5", held, sealedAt = NOW - 1),
                OwedSeal("p6", held, sealedAt = NOW - 1),
            )
        val plan = plan(owed, next)
        assertEquals("dark past the TTL, a clock moved back, and the changed peer", setOf("p1", "p4", "p5"), plan.seal)
        assertEquals(setOf("p2", "p3", "p6"), plan.spare)
        assertEquals(2, plan.stale)
    }

    @Test
    fun aMintThatCanSpareNobodySealsEveryOwedSession() {
        val peers = (1..10).map { "p$it" }
        val held = era(1)
        assertEquals("a visible field moved", peers.toSet(), plan(owedAll(peers, held), era(2, visible = "Al")).seal)
        assertEquals("not the claim's mint", peers.toSet(), plan(owedAll(peers, held), era(2), claimMinted = false).seal)
        assertEquals("nothing known", peers.toSet(), plan(peers.map { OwedSeal(it, null, NOW) }, era(2)).seal)
        assertTrue(plan(owedAll(peers, held), era(2, visible = "Al")).spare.isEmpty())
    }

    /**
     * The invariant the rule exists to keep, over random runs of mints, intros, restarts and time against the same loop
     * `MeshManager.broadcastSealedProfileLocked` runs ([FanOutModel]): after every fan-out, each confirmed peer holds a
     * copy whose key presence and visible fields are the current version's for it — never a stale key, never a missing
     * one — and an audience-only mint seals exactly the peers whose answer changed and those whose copy is stale. Each run
     * has its own seed, named in every failure with the step and its kind.
     */
    @Test
    fun noRunOfMintsLeavesAPeerHoldingTheWrongCopy() {
        repeat(200) { run ->
            val seed = 11L * 1000 + run
            val random = java.util.Random(seed)
            val model = FanOutModel()
            model.fanOut("seed $seed, start")
            repeat(30) { step ->
                val kind = model.step(random)
                model.fanOut("seed $seed, step $step ($kind)")
            }
        }
    }

    /** One sender's sealed profile state and five peers', and what each peer actually holds. */
    private class FanOutModel {
        val peers = listOf("ann", "ben", "cid", "dee", "eve")
        var version = 0L
        var key: String? = KEY_A
        var direct = setOf("ann", "ben")
        var visible = "v0"
        var claimVersion = 0L
        var now = NOW

        /** The manager's record: the version recorded for each peer, and when the copy it holds was sealed. */
        val record = HashMap<String, Pair<Long, Long>>()
        val eras = HashMap<Long, SealedProfileEra>()
        val outside = HashSet<String>()

        /** What each peer actually holds: the newest copy it was sealed. */
        val holds = HashMap<String, SealedProfileEra>()

        fun current() = SealedProfileEra(version, key, direct, visible)

        /** One random event; returns its kind for the failure label. */
        fun step(random: java.util.Random): String =
            when (random.nextInt(8)) {
                // The contact set moves: an accept, a block, a removal. Unclaimed, it mints nothing.
                0, 1, 2 -> {
                    val who = peers[random.nextInt(peers.size)]
                    direct = if (who in direct) direct - who else direct + who
                    if (key != null) claimVersion = ++version
                    "contact $who"
                }

                3 -> {
                    visible = "v${random.nextInt(4)}"
                    version++
                    "visible edit"
                }

                4 -> {
                    key = listOf(KEY_A, KEY_B, null)[random.nextInt(3)]
                    claimVersion = ++version
                    "claim $key"
                }

                // The intro: a copy of the current version outside the fan-out.
                5 -> {
                    val who = peers[random.nextInt(peers.size)]
                    holds[who] = current()
                    outside += who
                    "intro $who"
                }

                6 -> {
                    now += random.nextInt(2 * STALE.toInt())
                    "time"
                }

                else -> {
                    record.clear()
                    eras.clear()
                    outside.clear()
                    "restart"
                }
            }

        fun fanOut(label: String) {
            val next = current()
            eras.putIfAbsent(version, next)
            // Every peer's last copy known, all at one older version: the case the sealed set is exact for.
            val prior = peers.map { record[it]?.first }.distinct().singleOrNull()
            val was = prior?.takeIf { p -> peers.none { it in outside } && p < version }?.let(eras::get)
            val owed = peers.filter { record[it]?.first != version || it in outside }
            val judged =
                owed.map {
                    val known = record[it]?.takeIf { _ -> it !in outside }
                    OwedSeal(it, known?.let { r -> eras[r.first] }, known?.second ?: 0L)
                }
            val plan = MeshcoreFanOutRule.plan(judged, next, claimVersion == version, now, STALE)
            assertEquals("$label: every owed session sealed or spared", owed.toSet(), plan.seal + plan.spare)
            assertTrue("$label: sealed and spared", plan.seal.none { it in plan.spare })
            if (was != null && isAudienceOnly(was)) {
                val changed = peers.filter { was.keyFor(it) != next.keyFor(it) }
                val stale = peers.filter { now - record.getValue(it).second !in 0 until STALE }
                assertEquals("$label: the sealed set", (changed + stale).toSet(), plan.seal)
            }
            for (peer in owed) {
                // A spare keeps the time the copy it holds was sealed.
                record[peer] = version to if (peer in plan.seal) now else record.getValue(peer).second
                if (peer in plan.seal) {
                    holds[peer] = next
                    outside.remove(peer)
                }
            }
            for (peer in peers) {
                val copy = checkNotNull(holds[peer]) { "$label: $peer holds nothing" }
                assertEquals("$label: $peer's key", next.keyFor(peer), copy.keyFor(peer))
                assertEquals("$label: $peer's fields", next.visible, copy.visible)
            }
        }

        private fun isAudienceOnly(was: SealedProfileEra): Boolean =
            claimVersion == version && key != null && was.key == key && was.visible == visible
    }

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000

        const val STALE = DAY

        /** The plan's wall clock, past every stale bound a case subtracts. */
        const val NOW = 100L * DAY

        // RFC 8032 §7.1's public keys, TESTs 1, 2 and 3: full-order points, as a MeshCore board's are.
        const val KEY_A = "11qYAYKxCrfVS/7TyWQHOg7hcvPapiMlrwIaaPcHURo="
        const val KEY_B = "PUAXw+hDiVqStwqnTRt+vJyYLM8uxJaMwM1V8Sr0Zgw="
        const val KEY_C = "/FHNjmIYoaONpH7QAjDwWAgW7RO6MwOsXeuRFUiQgCU="
    }
}
