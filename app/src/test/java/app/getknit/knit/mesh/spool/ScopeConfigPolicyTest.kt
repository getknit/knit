package app.getknit.knit.mesh.spool

import app.getknit.knit.mesh.protocol.ScopeConfigPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The receive rules of the scope-config ctl (`docs/SPOOL_PROTOCOL.md` §5): validation, the order, the bounds. */
class ScopeConfigPolicyTest {
    private fun payload(
        version: Int = 1,
        spools: List<String> = listOf(A),
        groupId: String? = null,
        maxFrames: Int = 400,
        ttlMs: Long = ScopeRegistry.DEFAULT_TTL_MS,
        maxBlob: Int = 65_536,
    ) = ScopeConfigPayload(groupId = groupId, spools = spools, maxFrames = maxFrames, ttlMs = ttlMs, maxBlob = maxBlob, version = version)

    private fun valid(
        payload: ScopeConfigPayload,
        issuer: String = "bob",
        conversationId: String = "bob",
        groupId: String? = null,
    ): ScopeConfig {
        val result = ScopeConfigPolicy.validate(payload, conversationId, groupId, issuer)
        check(result is ScopeConfigPolicy.Result.Valid) { "expected a valid config, got $result" }
        return result.config
    }

    private fun refusal(
        payload: ScopeConfigPayload,
        groupId: String? = null,
    ): ScopeConfigPolicy.Refusal? = (ScopeConfigPolicy.validate(payload, "c", groupId, "bob") as? ScopeConfigPolicy.Result.Refused)?.why

    @Test
    fun aConfigKeepsOnlyTheWssUrlsThisBuildCanDialCanonicalAndInOrder() {
        val config =
            valid(
                payload(
                    spools =
                        listOf(
                            "WSS://lax.spool.getknit.app/spool/v1",
                            "ws://cleartext.example/spool/v1",
                            "https://not-a-spool.example",
                            "wss://b.example/spool/v1?k=tok",
                            "wss://public-1.spool.getknit.app/spool/v1",
                            "wss://has space.example/spool/v1",
                            "wss://" + "x".repeat(ScopeConfigPolicy.MAX_URL_LENGTH),
                            "wss://",
                        ),
                ),
            )
        // The renamed house relay and its current name are one relay; the scheme is lowercased; the token survives.
        assertEquals(listOf("wss://public-1.spool.getknit.app/spool/v1", "wss://b.example/spool/v1?k=tok"), config.spools)
    }

    @Test
    fun aConfigWithNoDialableUrlTooManyUrlsOrANonPositiveNumberIsRefusedWhole() {
        assertEquals(ScopeConfigPolicy.Refusal.MALFORMED, refusal(payload(spools = listOf("ws://cleartext.example/spool/v1"))))
        assertEquals(ScopeConfigPolicy.Refusal.MALFORMED, refusal(payload(spools = emptyList())))
        val nine = (1..ScopeConfigPolicy.MAX_SPOOLS + 1).map { "wss://r$it.example/spool/v1" }
        assertEquals(ScopeConfigPolicy.Refusal.MALFORMED, refusal(payload(spools = nine)))
        assertEquals(8, valid(payload(spools = nine.take(ScopeConfigPolicy.MAX_SPOOLS))).spools.size)
        assertEquals(ScopeConfigPolicy.Refusal.MALFORMED, refusal(payload(version = 0)))
        assertEquals(ScopeConfigPolicy.Refusal.MALFORMED, refusal(payload(maxFrames = 0)))
        assertEquals(ScopeConfigPolicy.Refusal.MALFORMED, refusal(payload(ttlMs = -1L)))
        assertEquals(ScopeConfigPolicy.Refusal.MALFORMED, refusal(payload(maxBlob = 0)))
    }

    @Test
    fun boundsAboveTheSpecDefaultsAreClampedAndBoundsBelowThemStand() {
        val high = valid(payload(maxFrames = 10_000, ttlMs = 30L * 24 * 60 * 60_000L, maxBlob = 1 shl 24))
        assertEquals(ScopeRegistry.DEFAULT_MAX_FRAMES, high.maxFrames)
        assertEquals(48 * 60 * 60_000L, high.ttlMs)
        assertEquals(ScopeRegistry.DEFAULT_MAX_BLOB, high.maxBlob)
        val low = valid(payload(maxFrames = 100, ttlMs = 60_000L, maxBlob = 4096))
        assertEquals(Triple(100, 60_000L, 4096), Triple(low.maxFrames, low.ttlMs, low.maxBlob))
    }

    @Test
    fun aConfigNamesTheConversationItRodeInOrIsRefused() {
        // A DM config carries no group; a group config names the group its frame was sealed to, and no other.
        assertEquals(ScopeConfigPolicy.Refusal.WRONG_CONVERSATION, refusal(payload(groupId = "g-1")))
        assertEquals(ScopeConfigPolicy.Refusal.WRONG_CONVERSATION, refusal(payload(), groupId = "g-1"))
        assertEquals(ScopeConfigPolicy.Refusal.WRONG_CONVERSATION, refusal(payload(groupId = "g-2"), groupId = "g-1"))
        val group = valid(payload(groupId = "g-1"), conversationId = "g-1", groupId = "g-1")
        assertEquals("g-1", group.groupId)
        assertEquals("g-1", group.conversationId)
    }

    private fun decide(
        candidate: ScopeConfig,
        held: List<ScopeConfig>,
        now: Long = NOW,
    ): ScopeConfigPolicy.Decision = ScopeConfigPolicy.decide(candidate, held, now).decision

    @Test
    fun theWinnerIsTheHighestVersionAndATieGoesToTheHighestIssuer() {
        val bob = valid(payload(version = 3), issuer = "bob")
        val carol = valid(payload(version = 3, spools = listOf(B)), issuer = "carol")
        val alice = valid(payload(version = 4), issuer = "alice")
        assertEquals(carol, ScopeConfigPolicy.winner(listOf(bob, carol), NOW))
        assertEquals(carol, ScopeConfigPolicy.winner(listOf(carol, bob), NOW))
        assertEquals(alice, ScopeConfigPolicy.winner(listOf(bob, alice, carol), NOW))
        assertNull(ScopeConfigPolicy.winner(emptyList(), NOW))
    }

    @Test
    fun anIssuersRowIsReplacedOnlyByAConfigThatOutranksIt() {
        val v2 = valid(payload(version = 2))
        assertEquals(ScopeConfigPolicy.Decision.STORE, decide(v2, emptyList()))
        assertEquals(ScopeConfigPolicy.Decision.KEEP, decide(v2, listOf(v2)))
        assertEquals(ScopeConfigPolicy.Decision.KEEP, decide(valid(payload(version = 1)), listOf(v2)))
        val v3 = ScopeConfigPolicy.decide(valid(payload(version = 3)), listOf(v2), NOW)
        assertEquals(ScopeConfigPolicy.Plan(ScopeConfigPolicy.Decision.STORE, drop = listOf(v2)), v3)
        // Another issuer's higher row does not stop an issuer's own newer one: the winner is read across rows.
        val carol = valid(payload(version = 5), issuer = "carol")
        assertEquals(ScopeConfigPolicy.Decision.STORE, decide(valid(payload(version = 3)), listOf(v2, carol)))
    }

    @Test
    fun oneIssuersTwoConfigsUnderOneVersionSettleTheSameWayInEitherOrder() {
        val first = valid(payload(version = 2, spools = listOf(A)))
        val second = valid(payload(version = 2, spools = listOf(B)))
        val kept = if (ScopeConfigPolicy.outranks(first, second)) first else second
        // Whichever lands first, the one that outranks is the one held after both.
        for ((a, b) in listOf(first to second, second to first)) {
            val held = if (decide(b, listOf(a)) == ScopeConfigPolicy.Decision.STORE) b else a
            assertEquals(kept, held)
        }
    }

    /**
     * No version is refused: the frame's chain advances on a refusal, so it would be final, and the clock it would be
     * judged on differs between phones. A receiver with no rows takes the next config, and so does one whose rows are
     * far more than 64 behind it — the bound the first draft had.
     */
    @Test
    fun noVersionIsRefusedAndAReceiverFarBehindOrEmptyTakesTheNext() {
        val minute = ScopeConfigPolicy.minutesOf(NOW)
        for (version in listOf(1, minute, minute + 1_000, Int.MAX_VALUE)) assertNull(refusal(payload(version = version)))
        val next = valid(payload(version = minute))
        assertEquals(ScopeConfigPolicy.Decision.STORE, decide(next, emptyList()))
        val farBehind = valid(payload(version = 3), issuer = "carol")
        assertEquals(ScopeConfigPolicy.Decision.STORE, decide(next, listOf(farBehind)))
        assertEquals(next, ScopeConfigPolicy.winner(listOf(farBehind, next), NOW))
    }

    /**
     * A version ahead of this phone's clock is held and not read: the issuer's readable row stays the winner beside it,
     * and the deferred one takes over once the clock reaches it, so a receiver whose clock is 20 minutes slow reads,
     * once corrected, exactly what a correct clock reads.
     */
    @Test
    fun aDeferredRowWaitsBesideTheReadableOneAndWinsWhenTheClockPassesIt() {
        val minute = ScopeConfigPolicy.minutesOf(NOW)
        val slow = NOW - 20 * 60_000L
        val readable = valid(payload(version = minute - 30, spools = listOf(A)))
        val ahead = valid(payload(version = minute, spools = listOf(B)))
        assertFalse(ScopeConfigPolicy.eligible(ahead, slow))
        val plan = ScopeConfigPolicy.decide(ahead, listOf(readable), slow)
        assertEquals("held beside, nothing dropped", ScopeConfigPolicy.Plan(ScopeConfigPolicy.Decision.STORE), plan)
        assertEquals(readable, ScopeConfigPolicy.winner(listOf(readable, ahead), slow))
        // The clock comes back: the deferred row is the winner, and the next write drops the rows it supersedes.
        assertTrue(ScopeConfigPolicy.eligible(ahead, NOW))
        assertEquals(ahead, ScopeConfigPolicy.winner(listOf(readable, ahead), NOW))
        val onTop = valid(payload(version = minute + 1))
        assertEquals(listOf(readable, ahead), ScopeConfigPolicy.decide(onTop, listOf(readable, ahead), NOW + 60_000L).drop)
        // Eligible from the first instant of the minute its version minus the slack names, and not before.
        val at = ScopeConfigPolicy.eligibleAt(ahead)
        assertTrue(ScopeConfigPolicy.eligible(ahead, at))
        assertFalse(ScopeConfigPolicy.eligible(ahead, at - 1))
    }

    @Test
    fun aGriefersIntMaxRowIsHeldAndNeverWins() {
        val minute = ScopeConfigPolicy.minutesOf(NOW)
        val honest = valid(payload(version = minute), issuer = "alice")
        val griefer = valid(payload(version = Int.MAX_VALUE, spools = listOf(B)), issuer = "zed")
        assertEquals(ScopeConfigPolicy.Decision.STORE, decide(griefer, listOf(honest)))
        assertEquals(honest, ScopeConfigPolicy.winner(listOf(honest, griefer), NOW))
        assertEquals(honest, ScopeConfigPolicy.winner(listOf(honest, griefer), NOW + 100L * 365 * 24 * 60 * 60_000L))
        // The griefer's own later honest config is still read: its far row waits beside it and never takes over.
        val zedHonest = valid(payload(version = minute + 1), issuer = "zed")
        assertEquals(ScopeConfigPolicy.Decision.STORE, decide(zedHonest, listOf(honest, griefer)))
        assertEquals(zedHonest, ScopeConfigPolicy.winner(listOf(honest, griefer, zedHonest), NOW))
    }

    @Test
    fun anIssuerKeepsItsBestReadableRowAndItsLowestDeferredOnes() {
        val minute = ScopeConfigPolicy.minutesOf(NOW)
        val readable = (1..3).map { valid(payload(version = minute - it)) }
        val deferred = (1..ScopeConfigPolicy.MAX_DEFERRED_PER_ISSUER + 2).map { valid(payload(version = minute + 100 + it)) }
        val kept = ScopeConfigPolicy.retained(readable + deferred.reversed(), NOW)
        assertEquals(listOf(readable.first()) + deferred.take(ScopeConfigPolicy.MAX_DEFERRED_PER_ISSUER), kept)
        // A deferred row past the kept ones is not news.
        assertEquals(ScopeConfigPolicy.Decision.KEEP, decide(deferred.last(), kept))
    }

    @Test
    fun theNextVersionIsTheLaterOfOnePastTheWinnerAndTheMinuteAndSaturates() {
        val minute = ScopeConfigPolicy.minutesOf(NOW)
        assertEquals(minute, ScopeConfigPolicy.nextVersion(null, NOW))
        assertEquals(minute, ScopeConfigPolicy.nextVersion(valid(payload(version = 3)), NOW))
        assertEquals(minute + 2, ScopeConfigPolicy.nextVersion(valid(payload(version = minute + 1)), NOW))
        assertEquals(Int.MAX_VALUE, ScopeConfigPolicy.nextVersion(valid(payload(version = Int.MAX_VALUE)), NOW))
        assertEquals(0, ScopeConfigPolicy.minutesOf(ScopeConfigPolicy.VERSION_EPOCH_MS))
        assertEquals(0, ScopeConfigPolicy.minutesOf(Long.MIN_VALUE))
        assertEquals(1, ScopeConfigPolicy.minutesOf(ScopeConfigPolicy.VERSION_EPOCH_MS + 60_000L))
        assertEquals(Int.MAX_VALUE, ScopeConfigPolicy.minutesOf(Long.MAX_VALUE))
    }

    /**
     * An issuer whose own rows were restored stale (or lost) issues on top of nothing it remembers, yet outranks the
     * config of its own a peer still holds once a minute has passed: its next version is the clock's.
     */
    @Test
    fun anIssuerRestoredStaleStillOutranksWhatItIssuedBefore() {
        val before = valid(payload(version = ScopeConfigPolicy.nextVersion(null, NOW), spools = listOf(A)))
        val later = NOW + 2 * 60_000L
        val arrived = valid(payload(version = ScopeConfigPolicy.nextVersion(null, later), spools = listOf(B)))
        assertEquals(ScopeConfigPolicy.Decision.STORE, decide(arrived, listOf(before), later))
        assertEquals(listOf(B), ScopeConfigPolicy.winner(listOf(before, arrived), later)?.spools)
    }

    /** An issuer 20 minutes slow still issues (nothing checks its clock on the way out), at a version that loses. */
    @Test
    fun anIssuerWhoseClockIsSlowStillIssuesAndLosesToTheLaterConfig() {
        val slow = NOW - 20 * 60_000L
        val peers = valid(payload(version = ScopeConfigPolicy.minutesOf(NOW), spools = listOf(A)), issuer = "alice")
        // On the slow phone the peer's row is deferred, so it steps from what it can read: nothing.
        val readable = ScopeConfigPolicy.winner(listOf(peers), slow)
        assertNull(readable)
        val mine = valid(payload(version = ScopeConfigPolicy.nextVersion(readable, slow)), issuer = "bob")
        assertNull(refusal(payload(version = mine.version)))
        assertEquals(peers, ScopeConfigPolicy.winner(listOf(peers, mine), NOW))
    }

    @Test
    fun aGroupIssuerMustBeACurrentMemberWhoSealedItAfterTheirLastLeave() {
        val roster = listOf("alice", "bob")
        assertTrue(ScopeConfigPolicy.groupIssuerAdmitted("bob", roster, sentAt = 10L, leftAt = 0L))
        assertFalse(
            "departed: not in the effective roster",
            ScopeConfigPolicy.groupIssuerAdmitted("carol", roster, sentAt = 10L, leftAt = 0L),
        )
        // Carol left at 50 and came back; a config she sealed before the leave stays refused, one after it lands.
        val back = roster + "carol"
        assertFalse(ScopeConfigPolicy.groupIssuerAdmitted("carol", back, sentAt = 40L, leftAt = 50L))
        assertFalse(ScopeConfigPolicy.groupIssuerAdmitted("carol", back, sentAt = 50L, leftAt = 50L))
        assertTrue(ScopeConfigPolicy.groupIssuerAdmitted("carol", back, sentAt = 60L, leftAt = 50L))
    }

    private companion object {
        const val A = "wss://a.example/spool/v1"
        const val B = "wss://b.example/spool/v1"

        /** 2026-10-23T12:00Z. */
        const val NOW = 1_792_756_800_000L
    }
}
