package app.getknit.knit.mesh.crypto

import app.getknit.knit.mesh.crypto.PresenceKeyPolicy.AdvanceCause
import app.getknit.knit.mesh.crypto.PresenceKeyPolicy.Due
import app.getknit.knit.mesh.crypto.PresenceKeyPolicy.Held
import app.getknit.knit.mesh.crypto.PresenceKeyPolicy.Own
import app.getknit.knit.mesh.crypto.PresenceKeyPolicy.Refusal
import app.getknit.knit.mesh.crypto.PresenceKeyPolicy.Verdict
import app.getknit.knit.mesh.protocol.PresenceKeyPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PresenceKeyPolicy] (ADR 2026-11.xp6d, "The presence key"): every branch of `due` in the brief's order, the generation
 * arithmetic, what a linked contact is owed, and what a received key does to the keys held for its sender in each era a
 * frame can open under. The engine's interleavings under its lock are `PresenceKeyEngineTest`'s.
 */
class PresenceKeyPolicyTest {
    private val now = 1_793_577_600_000L
    private val day = PresenceKeyPolicy.DAY_MS

    private fun key(seed: Int) = ByteArray(32) { (it + seed).toByte() }

    private fun own(
        generation: Long,
        activatedAt: Long?,
        audience: Set<String>,
        markSlot: Long? = null,
    ) = Own(
        generation,
        key(generation.toInt()),
        mintedAt = activatedAt ?: now,
        activatedAt = activatedAt,
        audience = audience,
        markSlot = markSlot,
    )

    private fun due(
        rows: List<Own>,
        direct: Set<String>,
        at: Long = now,
        allowed: Boolean = true,
    ) = PresenceKeyPolicy.due(rows, direct, at, allowed, BlindTag::slotOf, BlindTag.WINDOW_AHEAD_SLOTS)

    @Test
    fun noCurrentKeyMintsBoth() {
        assertEquals(Due.Mint, due(emptyList(), setOf("a")))
        // A next key alone (a crash between two writes) is no current key.
        assertEquals(Due.Mint, due(listOf(own(2, null, setOf("a"))), setOf("a")))
    }

    @Test
    fun anAudienceThatHoldsSomeoneWhoLeftIsFreshWhateverElseIsDue() {
        val current = own(1, now - 2 * day, setOf("a", "b"))
        val next = own(2, null, setOf("a", "b"))
        assertEquals(Due.Fresh, due(listOf(current, next), setOf("a")))
        // Only the next key's audience shrank (a contact grown into next, then removed): still fresh.
        assertEquals(Due.Fresh, due(listOf(own(1, now - 2 * day, setOf("a")), next), setOf("a")))
        // Ahead of the weekly turnover and of the clock rule, and never rate-limited.
        assertEquals(Due.Fresh, due(listOf(own(1, now - 8 * day, setOf("a", "b")), next), setOf("a"), allowed = false))
    }

    @Test
    fun aWeekAdvancesAndADayLessDoesNot() {
        val next = own(2, null, setOf("a"))
        assertEquals(Due.Advance(AdvanceCause.WEEKLY), due(listOf(own(1, now - PresenceKeyPolicy.WEEK_MS, setOf("a")), next), setOf("a")))
        assertEquals(Due.None, due(listOf(own(1, now - PresenceKeyPolicy.WEEK_MS + 1, setOf("a")), next), setOf("a")))
    }

    @Test
    fun aCorrectedClockAdvancesOnceThenIsQuiet() {
        // Activated while the clock ran two days ahead; the clock is now corrected.
        val rows = listOf(own(1, now + 2 * day, setOf("a")), own(2, null, setOf("a")))
        assertEquals(Due.Advance(AdvanceCause.CLOCK), due(rows, setOf("a")))
        // Inside the daily floor the clock rule is skipped: nothing else is due.
        assertEquals(Due.None, due(rows, setOf("a"), allowed = false))
        // The advance stamps the corrected clock: the next key is current, then a next key is minted, then nothing.
        val advanced = listOf(own(1, now + 2 * day, setOf("a")), own(2, now, setOf("a")))
        assertEquals(Due.MintNext, due(advanced, setOf("a")))
        assertEquals(Due.None, due(advanced + own(3, null, setOf("a")), setOf("a")))
        // A minute of NTP correction is inside the slack and never rotates.
        assertEquals(Due.None, due(listOf(own(1, now + 60_000L, setOf("a")), own(2, null, setOf("a"))), setOf("a")))
    }

    @Test
    fun aStaleAdvertiserMarkAdvancesUnderTheSameDailyFloor() {
        val slot = BlindTag.slotOf(now)
        val atEdge = listOf(own(1, now - day, setOf("a"), markSlot = slot + BlindTag.WINDOW_AHEAD_SLOTS), own(2, null, setOf("a")))
        assertEquals(Due.None, due(atEdge, setOf("a")))
        val stale = listOf(own(1, now - day, setOf("a"), markSlot = slot + BlindTag.WINDOW_AHEAD_SLOTS + 1), own(2, null, setOf("a")))
        assertEquals(Due.Advance(AdvanceCause.MARK), due(stale, setOf("a")))
        assertEquals(Due.None, due(stale, setOf("a"), allowed = false))
    }

    @Test
    fun noNextKeyMintsOne() {
        assertEquals(Due.MintNext, due(listOf(own(1, now, setOf("a"))), setOf("a")))
    }

    @Test
    fun aNewContactGrowsIntoTheNextKeyAndIntoTheCurrentOnlyWithinADayOfItsActivation() {
        val young = listOf(own(1, now - day + 1, setOf("a")), own(2, null, setOf("a")))
        assertEquals(Due.Grow(toNext = setOf("b"), toCurrent = setOf("b")), due(young, setOf("a", "b")))
        // Aired for a day: a contact accepted now never gets the key whose tags went out before it (security N3).
        val aired = listOf(own(1, now - day, setOf("a")), own(2, null, setOf("a")))
        assertEquals(Due.Grow(toNext = setOf("b"), toCurrent = emptySet()), due(aired, setOf("a", "b")))
        // A clock set back far past the activation is no young key either.
        val behind = listOf(own(1, now + 2 * day, setOf("a")), own(2, null, setOf("a")))
        assertEquals(Due.Grow(toNext = setOf("b"), toCurrent = emptySet()), due(behind, setOf("a", "b"), allowed = false))
        // Already in next: nothing to grow, whatever the current key's audience says.
        assertEquals(Due.None, due(listOf(own(1, now - 3 * day, setOf("a")), own(2, null, setOf("a", "b"))), setOf("a", "b")))
    }

    @Test
    fun aGenerationIsTheLargerOfTheNextNumberAndTheClockAndNeverOverflows() {
        assertEquals(now, PresenceKeyPolicy.nextGeneration(0L, now))
        assertEquals(now + 6, PresenceKeyPolicy.nextGeneration(now + 5, now))
        assertEquals(1L, PresenceKeyPolicy.nextGeneration(0L, -5L))
        assertNull(PresenceKeyPolicy.nextGeneration(Long.MAX_VALUE, now))
    }

    @Test
    fun aRestoredGenerationMoreThanAYearAheadIsClampedToTheClock() {
        val year = PresenceKeyPolicy.CLAMP_AHEAD_MS
        assertNull(PresenceKeyPolicy.clampRestored(now + year, now))
        assertEquals(now, PresenceKeyPolicy.clampRestored(now + year + 1, now))
        // A clock so far ahead the bound overflows clamps nothing.
        assertNull(PresenceKeyPolicy.clampRestored(Long.MAX_VALUE, Long.MAX_VALUE - 1))
        assertEquals(5L, PresenceKeyPolicy.clampRestored(Long.MAX_VALUE, 5L))
    }

    @Test
    fun aPeerIsOwedOnlyTheKeysWhoseAudienceHoldsIt() {
        val keys =
            PresenceKeyPolicy.keysOf(
                listOf(own(1, now - 3 * day, setOf("a")), own(2, now - day, setOf("a")), own(3, null, setOf("a", "b"))),
            )
        assertEquals(listOf(2L, 3L), PresenceKeyPolicy.owed("a", keys).map { it.generation })
        assertEquals("joined after the current key aired: next only", listOf(3L), PresenceKeyPolicy.owed("b", keys).map { it.generation })
        assertTrue(PresenceKeyPolicy.owed("stranger", keys).isEmpty())
        assertEquals("the previous key is never owed", 1L, keys.previous?.generation)
    }

    @Test
    fun aMalformedPayloadIsRefusedAndAWellFormedOneListsLowestFirst() {
        assertNull(PresenceKeyPolicy.keysIn(PresenceKeyPayload(generation = 1L, key = ByteArray(31))))
        assertNull(PresenceKeyPolicy.keysIn(PresenceKeyPayload(generation = 0L, key = key(1))))
        assertNull(PresenceKeyPolicy.keysIn(PresenceKeyPayload(generation = 1L, key = key(1), nextGeneration = 2L)))
        assertNull(PresenceKeyPolicy.keysIn(PresenceKeyPayload(generation = 1L, key = key(1), nextKey = key(2))))
        assertNull(PresenceKeyPolicy.keysIn(PresenceKeyPayload(generation = 2L, key = key(1), nextGeneration = 2L, nextKey = key(2))))
        assertNull(PresenceKeyPolicy.keysIn(PresenceKeyPayload(generation = 2L, key = key(1), nextGeneration = 3L, nextKey = ByteArray(5))))
        assertEquals(listOf(5L, 6L), PresenceKeyPolicy.keysIn(PresenceKeyPayload(5L, key(1), 6L, key(2)))?.map { it.first })
    }

    @Test
    fun theSameEraAddsANewGenerationIgnoresAnEqualOneAndRefusesAConflictOrAnOlderOne() {
        val held = listOf(Held(10, key(10), TAG_A), Held(11, key(11), TAG_A))
        val added = PresenceKeyPolicy.receive(held, listOf(11L to key(11), 12L to key(12)), TAG_A, active = true) as Verdict.Store
        assertFalse(added.replaced)
        assertEquals(1, added.added)
        assertEquals(listOf(12L, 11L, 10L), added.rows.map { it.generation })
        assertEquals(Verdict.NoOp, PresenceKeyPolicy.receive(held, listOf(10L to key(10), 11L to key(11)), TAG_A, active = true))
        assertEquals(
            Refusal.CONFLICT,
            (PresenceKeyPolicy.receive(held, listOf(11L to key(99)), TAG_A, active = true) as Verdict.Refused).why,
        )
        assertEquals(Refusal.OLDER, (PresenceKeyPolicy.receive(held, listOf(9L to key(9)), TAG_A, active = true) as Verdict.Refused).why)
    }

    @Test
    fun aNewActiveRootReplacesEveryHeldKeyEvenWithALowerGeneration() {
        // B3: a sender restored after its clock ran ahead hands lower generations under the root its reset made.
        val held = listOf(Held(500, key(5), TAG_A), Held(501, key(6), TAG_A))
        val replaced = PresenceKeyPolicy.receive(held, listOf(20L to key(20), 21L to key(21)), TAG_B, active = true) as Verdict.Store
        assertTrue(replaced.replaced)
        assertEquals(listOf(21L, 20L), replaced.rows.map { it.generation })
        assertTrue(replaced.rows.all { it.rootTag == TAG_B })
    }

    @Test
    fun aDrainNeverStartsAnEraOrMovesATag() {
        val held = listOf(Held(20, key(20), TAG_B))
        // A late frame of the old era, lower: refused, never a replacement.
        assertEquals(Refusal.OLDER, (PresenceKeyPolicy.receive(held, listOf(10L to key(10)), TAG_A, active = false) as Verdict.Refused).why)
        // Newer: added under the held keys' tag, not the drain's.
        val added = PresenceKeyPolicy.receive(held, listOf(30L to key(30)), TAG_A, active = false) as Verdict.Store
        assertFalse(added.replaced)
        assertTrue(added.rows.all { it.rootTag == TAG_B })
        // An equal generation and key from the drain is the silent no-op.
        assertEquals(Verdict.NoOp, PresenceKeyPolicy.receive(held, listOf(20L to key(20)), TAG_A, active = false))
    }

    @Test
    fun aDrainOpenWithNothingHeldStoresUnderItsOwnTagAndTheNextActiveOpenReplacesIt() {
        val stored = PresenceKeyPolicy.receive(emptyList(), listOf(500L to key(5)), TAG_A, active = false) as Verdict.Store
        assertTrue(stored.rows.all { it.rootTag == TAG_A })
        val replaced = PresenceKeyPolicy.receive(stored.rows, listOf(20L to key(20)), TAG_B, active = true) as Verdict.Store
        assertTrue(replaced.replaced)
        assertEquals(listOf(20L), replaced.rows.map { it.generation })
    }

    @Test
    fun aPeerKeepsItsThreeNewestGenerations() {
        val held = listOf(Held(1, key(1), TAG_A), Held(2, key(2), TAG_A), Held(3, key(3), TAG_A))
        val store = PresenceKeyPolicy.receive(held, listOf(4L to key(4), 5L to key(5)), TAG_A, active = true) as Verdict.Store
        assertEquals(listOf(5L, 4L, 3L), store.rows.map { it.generation })
    }

    @Test
    fun aKeptLinkResetIsResentOnlyWhileItsRootAndPrekeyStandAndALostOneIsMarkedAgain() {
        val step = PresenceKeyPolicy::linkReset
        assertEquals(PresenceKeyPolicy.LinkReset.RESEND, step(TAG_A, TAG_A, true, true))
        assertEquals("another reset of ours replaced it", PresenceKeyPolicy.LinkReset.SUPERSEDED, step(TAG_A, TAG_B, true, true))
        assertEquals("no session at all now: never resend", PresenceKeyPolicy.LinkReset.SUPERSEDED, step(TAG_A, null, false, false))
        assertEquals("the peer's prekey moved on", PresenceKeyPolicy.LinkReset.SEAL, step(TAG_A, TAG_A, true, false))
        assertEquals("nothing kept, nothing pending", PresenceKeyPolicy.LinkReset.SEAL, step(null, null, false, false))
        assertEquals("sealed and lost before it was kept", PresenceKeyPolicy.LinkReset.REMARK, step(null, TAG_A, true, true))
        assertEquals("lost, and on a prekey the peer left", PresenceKeyPolicy.LinkReset.SEAL, step(null, TAG_A, true, false))
    }

    /**
     * Step 5b's `TAG_NEXT` exclusion: after a fresh pair, the previous key's audience less the direct contacts — the
     * leaver; after the next advance (the previous key now one that never named them), no one; a leaver who is a direct
     * contact again, no longer.
     */
    @Test
    fun theDepartedAreThoseAKeptKeyNamesWhoAreNoLongerDirectContacts() {
        val previous = own(1, now - day, setOf("alice", "bob", "carol"))
        val current = own(2, now, setOf("alice", "carol"))
        val next = own(3, null, setOf("alice", "carol"))
        assertEquals(setOf("bob"), PresenceKeyPolicy.departed(listOf(previous, current, next), setOf("alice", "carol")))
        assertEquals(
            "bob is a contact again",
            emptySet<String>(),
            PresenceKeyPolicy.departed(listOf(previous, current, next), setOf("alice", "bob", "carol")),
        )
        val advanced = listOf(current, next.copy(activatedAt = now + 7 * day), own(4, null, setOf("alice", "carol")))
        assertEquals(
            "the generation bob left under went",
            emptySet<String>(),
            PresenceKeyPolicy.departed(advanced, setOf("alice", "carol")),
        )
        assertEquals(emptySet<String>(), PresenceKeyPolicy.departed(emptyList(), setOf("alice")))
    }

    @Test
    fun tagNextGoesToNoOneBlockedOrDepartedAndToNoOneBeforeBothAreRead() {
        val hears = PresenceKeyPolicy::hearsTagNext
        assertTrue("a stranger still hears it (the ADR's accepted cost)", hears("stranger", setOf("mallory"), setOf("bob")))
        assertFalse("blocked", hears("mallory", setOf("mallory"), setOf("bob")))
        assertFalse("departed", hears("bob", setOf("mallory"), setOf("bob")))
        assertFalse("the block list not read yet", hears("stranger", null, emptySet()))
        assertFalse("the engine's first pass not run yet", hears("stranger", emptySet(), null))
    }

    private companion object {
        const val TAG_A = 0x0A0AL
        const val TAG_B = 0x0B0BL
    }
}
