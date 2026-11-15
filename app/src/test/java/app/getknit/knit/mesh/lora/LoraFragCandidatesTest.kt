package app.getknit.knit.mesh.lora

import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.link.FastFrameCodec
import app.getknit.knit.mesh.link.IdElision
import app.getknit.knit.mesh.lora.meshcore.MeshcoreTraits
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LoraFragCandidates], the MeshCore plane's reassembler on the public Knit key (ADR 2026-11.gsqu's Stage 5c-1 amendment,
 * the security design pass's B2, lora-reviewer finding 5): no part is ever kept over another, a forged part never costs
 * the honest one, another count is another entry, colliding ids from two authors both complete, and one radio cannot
 * flush the store inside its window. The signature that picks the combination is the caller's; here a "verifying"
 * combination is the honest frame's bytes. Plus [LoraAuthorIds]' two tiers.
 */
class LoraFragCandidatesTest {
    private var now = 0L
    private val drops = mutableListOf<LoraFragCandidates.Drop>()
    private var pick: (Int) -> Int = { 0 }
    private val store = LoraFragCandidates(now = { now }, timeoutMs = TIMEOUT_MS, pick = { pick(it) }) { drops += it }

    private val honest0 = ByteArray(152) { 1 }
    private val honest1 = ByteArray(40) { 2 }
    private val honest = honest0 + honest1

    /** A bound part 0 of [slice0] naming a part 1 of [names]. */
    private fun head(
        slice0: ByteArray,
        names: ByteArray = honest1,
        fragId: Int = 7,
    ) = FastFrameCodec.Fragment(fragId, 0, 2, slice0, FastFrameCodec.commitmentOf(names))

    /** A bound part 1 of [slice1] naming a part 0 of [names]. */
    private fun tail(
        slice1: ByteArray,
        names: ByteArray = honest0,
        fragId: Int = 7,
    ) = FastFrameCodec.Fragment(fragId, 1, 2, slice1, FastFrameCodec.commitmentOf(names))

    private fun junk(seed: Int) = ByteArray(9) { (seed * 31 + it).toByte() }

    private fun accept(frag: FastFrameCodec.Fragment) = store.accept(frag, CAPACITY, PER_PART)

    @Test
    fun aFakeHeadCopyingTheCommitmentAheadOfTheHonestOneNeverTakesItsPairing() {
        // Round 2's (a): behind a repeater a forger's short part 0, copying the honest commitment, lands first.
        assertEquals(emptyList<LoraFragCandidates.Combination>(), accept(head(junk(1))))
        assertEquals(emptyList<LoraFragCandidates.Combination>(), accept(head(honest0)))
        val pairing = accept(tail(honest1)).single()
        assertArrayEquals("the honest tail names the honest head alone", honest, pairing.frame)
    }

    @Test
    fun threeDistinctFakeHeadsFirstNeverCrowdTheHonestOneOut() {
        // Round 2's (b): three fake part-0s fill what used to be every slot.
        repeat(3) { accept(head(junk(it), names = junk(it + 10))) }
        accept(head(honest0))
        assertArrayEquals(honest, accept(tail(honest1)).single().frame)
        assertEquals("nothing refused or replaced", emptyList<LoraFragCandidates.Drop>(), drops)
    }

    @Test
    fun moreFakesThanOneRadioCanAirInTheWindowStillLeaveTheHonestPostWithAPinnedReplacement() {
        // Round 3: a side holds what this receiver can decode within the whole fragment window, so no radio input fills it
        // inside the window; past it (more than the window's worth, a test's) a random waiting part is replaced.
        val air = LoraAirtime(traits = MeshcoreTraits)
        val shortest = air.timeOnAirMs(LoraFragCandidates.SHORTEST_FRAGMENT_BYTES)
        val perPart = LoraFragCandidates.perPartFor(TIMEOUT_MS, shortest)
        assertEquals(((TIMEOUT_MS + shortest - 1) / shortest + 1).toInt(), perPart)
        assertEquals("the store's own entry figure", LoraFragCandidates.capacityFor(TIMEOUT_MS, shortest), perPart)
        assertEquals("the companion default (SF8 / 62.5 kHz) keeps the figure the ADR states", PER_PART, perPart)
        println("meshcore: a bound side keeps $perPart parts (shortest $shortest ms, a $TIMEOUT_MS-ms window)")
        store.accept(head(honest0), CAPACITY, perPart)
        // Replacement picks the newest waiting fake here, as a pinned source can; production's is SecureRandom.
        pick = { it - 1 }
        val fakes = perPart + 5
        repeat(fakes) { store.accept(head(junk(it), names = junk(it + 1_000)), CAPACITY, perPart) }
        assertEquals(
            "every fake past the cap replaced one, counted",
            fakes - (perPart - 1),
            drops.count {
                it ==
                    LoraFragCandidates.Drop.REPLACED
            },
        )
        assertArrayEquals(honest, store.accept(tail(honest1), CAPACITY, perPart).single().frame)
    }

    @Test
    fun aReorderedHonestTailBesideFakeTailsStillPairsWhenItsHeadLands() {
        // Round 2's (d): the honest part 1 first, fake tails beside it (one copying the honest head's hash).
        accept(tail(junk(1)))
        accept(tail(honest1))
        accept(tail(junk(2), names = honest0))
        assertArrayEquals(honest, accept(head(honest0)).single().frame)
    }

    @Test
    fun twoAuthorsWhoseIdsCollideEachPairTheirOwn() {
        val bob0 = ByteArray(152) { 3 }
        val bob1 = ByteArray(30) { 4 }
        accept(head(honest0))
        accept(head(bob0, names = bob1))
        assertArrayEquals(bob0 + bob1, accept(tail(bob1, names = bob0)).single().frame)
        assertArrayEquals(honest, accept(tail(honest1)).single().frame)
        assertEquals("nothing is left", 0, store.size())
    }

    @Test
    fun oneRadioCannotFlushAnHonestEntryInsideItsWindow() {
        val shortest = LoraAirtime(traits = MeshcoreTraits).timeOnAirMs(LoraFragCandidates.SHORTEST_FRAGMENT_BYTES)
        val capacity = LoraFragCandidates.capacityFor(TIMEOUT_MS, shortest)
        val flood = (TIMEOUT_MS / shortest).toInt()
        println("meshcore: the shortest bound part is $shortest ms at the default preset, so the store holds $capacity entries")
        assertTrue("more than the old store held ($flood)", flood > LoraMeshTransport.FRAG_CAP)
        store.accept(head(honest0), capacity, PER_PART)
        repeat(flood) { i ->
            now += shortest
            store.accept(tail(byteArrayOf(9), fragId = 1_000 + i), capacity, PER_PART)
        }
        assertEquals("nothing evicted", emptyList<LoraFragCandidates.Drop>(), drops)
        assertArrayEquals(honest, store.accept(tail(honest1), capacity, PER_PART).single().frame)
        // Past the capacity the oldest goes, counted.
        val small = LoraFragCandidates(now = { now }, timeoutMs = TIMEOUT_MS) { drops += it }
        small.accept(head(honest0, fragId = 7), 2, PER_PART)
        small.accept(head(honest0, fragId = 8), 2, PER_PART)
        small.accept(head(honest0, fragId = 9), 2, PER_PART)
        assertEquals(listOf(LoraFragCandidates.Drop.OVERFLOW), drops)
        assertEquals(emptyList<LoraFragCandidates.Combination>(), small.accept(tail(honest1, fragId = 7), 2, PER_PART))
    }

    @Test
    fun theWholeStoreKeepsItsByteBudget() {
        // Many radios' worth of full-size fakes: the oldest entries go before the slices pass MAX_BYTES.
        val big = ByteArray(152) { 5 }
        val entries = LoraFragCandidates.MAX_BYTES / big.size + 10
        repeat(entries) { i -> store.accept(head(big, names = junk(i), fragId = i), LoraFragCandidates.MAX_CAPACITY, PER_PART) }
        assertTrue("evicted by bytes", drops.count { it == LoraFragCandidates.Drop.OVERFLOW } >= 10)
        assertTrue(store.size() * big.size <= LoraFragCandidates.MAX_BYTES)
    }

    @Test
    fun aJunkPartThatOpenedTheEntryEarlyAgesOutAloneNeverTheHonestPost() {
        // A part opened the entry 50 s before the honest part 0; each candidate ages out on its own arrival.
        accept(head(junk(1), names = junk(2)))
        now += 50_000
        accept(head(honest0))
        now += 15_000
        assertArrayEquals(honest, accept(tail(honest1)).single().frame)
        assertEquals("only the junk aged out", listOf(LoraFragCandidates.Drop.TIMEOUT), drops)
    }

    @Test
    fun aCandidateAgesOutAtTheTimeout() {
        accept(head(honest0))
        now += TIMEOUT_MS + 1
        assertEquals(emptyList<LoraFragCandidates.Combination>(), accept(tail(honest1)))
        assertEquals(listOf(LoraFragCandidates.Drop.TIMEOUT), drops)
    }

    @Test
    fun theCapacityIsSizedByAirtimeBetweenItsBounds() {
        assertEquals(LoraFragCandidates.MIN_CAPACITY, LoraFragCandidates.capacityFor(TIMEOUT_MS, 60_000))
        assertEquals(TIMEOUT_MS.toInt() / 300 + 1, LoraFragCandidates.capacityFor(TIMEOUT_MS, 300))
        assertEquals(LoraFragCandidates.MAX_CAPACITY, LoraFragCandidates.capacityFor(TIMEOUT_MS, 1))
        assertEquals("a zero airtime is never a division", LoraFragCandidates.MAX_CAPACITY, LoraFragCandidates.capacityFor(TIMEOUT_MS, 0))
    }

    @Test
    fun theBoundFormRoundTripsAndBindsBothWays() {
        val frame = ByteArray(250) { (it * 3).toByte() }
        val (p0, p1) = checkNotNull(FastFrameCodec.fragmentBound(frame, CAP, fragId = 0x1234))
        assertTrue(p0.size <= CAP && p1.size <= CAP)
        val h = checkNotNull(FastFrameCodec.parseBoundFragment(p0))
        val t = checkNotNull(FastFrameCodec.parseBoundFragment(p1))
        assertEquals(0x1234, h.fragId)
        assertArrayEquals("part 0 names part 1", FastFrameCodec.commitmentOf(t.payload), h.commitment)
        assertArrayEquals("part 1 names part 0", FastFrameCodec.commitmentOf(h.payload), t.commitment)
        assertArrayEquals(frame, h.payload + t.payload)
        assertEquals("one that fits one packet is not split", null, FastFrameCodec.fragmentBound(ByteArray(CAP), CAP, 1))
        val most = 2 * (CAP - FastFrameCodec.FRAG_HEADER_BYTES - FastFrameCodec.COMMITMENT_BYTES)
        assertEquals(2, FastFrameCodec.fragmentBound(ByteArray(most), CAP, 1)?.size)
        assertEquals("nor one past two", null, FastFrameCodec.fragmentBound(ByteArray(most + 1), CAP, 1))
        assertEquals("never read as an unbound part", null, FastFrameCodec.parseFragment(p0))
        assertEquals("a part with no slice", null, FastFrameCodec.parseBoundFragment(p1.copyOf(12)))
    }

    // --- LoraAuthorIds ---

    private fun id(
        prefix: ByteArray,
        fill: Int,
    ): String = NodeId.fromBytes(prefix + ByteArray(NodeId.BYTES - prefix.size) { fill.toByte() })

    @Test
    fun aPrefixResolvesAgainstContactsFirstAndThePinnedOnlyOnTheirTier() {
        val prefix = byteArrayOf(1, 2, 3, 4)
        val contact = id(prefix, 1)
        // Four identities ground to share the contact's prefix: more than IdElision takes, but none of them a contact.
        val ground = (2..5).map { id(prefix, it) }
        val ids = LoraAuthorIds(accepted = listOf(contact), pinned = ground + contact)
        assertEquals(listOf(contact), ids.resolve(prefix, LoraAuthorIds.Tier.ACCEPTED).map(NodeId::fromBytes))
        assertEquals(
            "the pinned tier is everyone else",
            ground.toSet(),
            ids.resolve(prefix, LoraAuthorIds.Tier.PINNED).map(NodeId::fromBytes).toSet(),
        )
        assertTrue(ground.size >= IdElision.MAX_CANDIDATES)
        assertEquals(
            "a stranger's prefix resolves to nobody",
            emptyList<ByteArray>(),
            ids.resolve(byteArrayOf(9, 9, 9, 9), LoraAuthorIds.Tier.PINNED),
        )
        assertEquals("only a 4-byte prefix", emptyList<ByteArray>(), ids.resolve(prefix.copyOf(3), LoraAuthorIds.Tier.ACCEPTED))
    }

    private companion object {
        const val TIMEOUT_MS = LoraMeshTransport.FRAG_TIMEOUT_MS
        const val CAPACITY = 64
        const val CAP = 164

        /** A side's cap in the tests that do not size it: the default preset's figure. */
        const val PER_PART = 177
    }
}
