package app.getknit.knit

import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.bluetooth.AdvertAliases
import app.getknit.knit.mesh.bluetooth.AdvertAliases.ClaimOutcome
import app.getknit.knit.mesh.bluetooth.AdvertAliases.ContactKey
import app.getknit.knit.mesh.bluetooth.AdvertAliases.Resolution
import app.getknit.knit.mesh.bluetooth.AdvertAliases.Source
import app.getknit.knit.mesh.bluetooth.AdvertOrder
import app.getknit.knit.mesh.bluetooth.AdvertOrder.Form
import app.getknit.knit.mesh.crypto.BlindTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * [AdvertAliases] (ADR 2026-11.xp6d): resolve order, the HMAC budget, the caches, a contact's key replaced mid-tag,
 * and every claim rule of the 2026-11-02 design pass. The clock is a field the test moves; keys and nonces come from a
 * seeded [Random].
 */
class AdvertAliasesTest {
    private var now = 1_000_000L
    private val random = Random(285)

    private fun key(): ByteArray = ByteArray(BlindTag.KEY_BYTES).also(random::nextBytes)

    private fun tagUnder(key: ByteArray): String = NodeId.fromBytes(BlindTag.mint(key, random.nextLong() and BlindTag.NONCE_MASK))

    private fun blinded(tag: String) = Form(tag, blinded = true)

    private fun aliases(config: AdvertAliases.Config = AdvertAliases.Config()) = AdvertAliases<String>({ now }, config)

    private val alice = NodeId.derive("alice")
    private val bob = NodeId.derive("bob")
    private val mallory = NodeId.derive("mallory")
    private val aliceKey = key()
    private val bobKey = key()

    private fun resolved(
        id: String,
        source: Source,
    ) = Resolution.Resolved(id, source)

    @Test
    fun aClearAdvertIsItsOwnId() {
        assertEquals(resolved(alice, Source.Clear), aliases().resolve(Form.clear(alice)))
    }

    @Test
    fun aBlindedFormWithoutThePrefixIsMalformedBeforeAnyHmac() {
        val a = aliases()
        a.setKeys(mapOf(alice to ContactKey(aliceKey)))
        assertEquals(Resolution.Malformed, a.resolve(blinded(bob)))
        assertEquals(Resolution.Malformed, a.resolve(blinded("not a form")))
        assertEquals(2L, a.counters().malformed)
        assertEquals(0L, a.counters().hmacs)
    }

    @Test
    fun myOwnTagsAreOwnWithoutAnHmac() {
        val a = aliases()
        val mine = key()
        val current = tagUnder(mine)
        val previous = tagUnder(mine)
        a.setKeys(mapOf(alice to ContactKey(aliceKey)))
        a.setOwnTags(current, previous)
        assertEquals(Resolution.Own, a.resolve(blinded(current)))
        assertEquals(Resolution.Own, a.resolve(blinded(previous)))
        assertEquals(0L, a.counters().hmacs)
    }

    @Test
    fun aContactsKeyResolvesItsTagOnceThenTheCacheAnswers() {
        val a = aliases()
        a.setKeys(mapOf(alice to ContactKey(aliceKey), bob to ContactKey(bobKey)))
        val tag = tagUnder(bobKey)
        assertEquals(resolved(bob, Source.Key), a.resolve(blinded(tag)))
        val spent = a.counters().hmacs
        assertEquals(2L, spent)
        assertEquals(resolved(bob, Source.Key), a.resolve(blinded(tag)))
        assertEquals("a cache hit spends nothing", spent, a.counters().hmacs)
        a.setKeys(mapOf(alice to ContactKey(aliceKey.copyOf()), bob to ContactKey(bobKey.copyOf())))
        a.resolve(blinded(tag))
        assertEquals("the same keys again keep the cache", spent, a.counters().hmacs)
        assertEquals(3L, a.counters().resolvedByKey)
        assertEquals(2L, a.counters().keyCacheHits)
    }

    @Test
    fun aStrangersTagIsCachedNegativeUntilItsTtlOrAKeyChange() {
        val a = aliases()
        a.setKeys(mapOf(alice to ContactKey(aliceKey)))
        val tag = tagUnder(bobKey)
        assertEquals(Resolution.Unresolved, a.resolve(blinded(tag)))
        assertEquals(Resolution.Unresolved, a.resolve(blinded(tag)))
        assertEquals("the second answer came from the negative cache", 1L, a.counters().hmacs)
        now += AdvertAliases.Config().negativeTtlMs
        a.resolve(blinded(tag))
        assertEquals("swept again after the ttl", 2L, a.counters().hmacs)
        a.setKeys(mapOf(alice to ContactKey(aliceKey), bob to ContactKey(bobKey)))
        assertEquals("a new key clears the negative cache", resolved(bob, Source.Key), a.resolve(blinded(tag)))
        assertEquals(3L, a.counters().unresolved)
    }

    @Test
    fun overBudgetIsCountedAndNotCachedNegative() {
        val a = aliases(AdvertAliases.Config(hmacBudgetPerSecond = 2))
        a.setKeys(mapOf(alice to ContactKey(aliceKey), bob to ContactKey(bobKey)))
        val stranger = tagUnder(key())
        val bobs = tagUnder(bobKey)
        assertEquals(Resolution.Unresolved, a.resolve(blinded(stranger)))
        assertEquals(Resolution.OverBudget, a.resolve(blinded(bobs)))
        assertEquals(1L, a.counters().overBudget)
        now += 1_000
        assertEquals("not cached negative: the next second resolves it", resolved(bob, Source.Key), a.resolve(blinded(bobs)))
    }

    @Test
    fun aKeySetLargerThanTheBudgetStillResolvesATagASecond() {
        val keys = List(4) { NodeId.derive("c$it") to key() }
        val a = aliases(AdvertAliases.Config(hmacBudgetPerSecond = 2))
        a.setKeys(keys.associate { (id, k) -> id to ContactKey(k) })
        assertEquals(resolved(keys[3].first, Source.Key), a.resolve(blinded(tagUnder(keys[3].second))))
        assertEquals(Resolution.OverBudget, a.resolve(blinded(tagUnder(keys[2].second))))
    }

    @Test
    fun aKeyThatArrivesMidTagKeepsTheTagOnAirResolvedUntilASuccessorTagIsHeard() {
        val a = aliases()
        a.setKeys(mapOf(bob to ContactKey(bobKey)))
        val airing = tagUnder(bobKey)
        val bobNext = key()
        // Bob's next generation lands while he still airs a tag minted under the old key, and an idle phone may keep
        // that tag for hours: no fixed overlap, only hearing a tag under the new key retires the old one.
        now += 5_000
        a.setKeys(mapOf(bob to ContactKey(bobNext)))
        assertEquals(resolved(bob, Source.Key), a.resolve(blinded(airing)))
        now += 6 * 3_600_000L
        a.setKeys(mapOf(bob to ContactKey(bobNext)))
        assertEquals("hours later, the same set again, the old key still answers", resolved(bob, Source.Key), a.resolve(blinded(airing)))
        assertEquals(resolved(bob, Source.Key), a.resolve(blinded(tagUnder(bobNext))))
        assertEquals("a successor tag heard: the old key is retired", Resolution.Unresolved, a.resolve(blinded(airing)))
    }

    @Test
    fun aReplacedKeyNobodyRetiresLastsOneKeyGeneration() {
        val a = aliases()
        a.setKeys(mapOf(bob to ContactKey(bobKey)))
        val airing = tagUnder(bobKey)
        a.setKeys(mapOf(bob to ContactKey(key())))
        now += AdvertAliases.Config().previousKeyMaxMs - 1
        assertEquals(resolved(bob, Source.Key), a.resolve(blinded(airing)))
        now += 1
        assertEquals(Resolution.Unresolved, a.resolve(blinded(airing)))
        assertEquals(7 * 24 * 3_600_000L, AdvertAliases.Config().previousKeyMaxMs)
    }

    @Test
    fun anExplicitPreviousKeyIsTriedUntilItsExpiry() {
        val a = aliases()
        val bobNext = key()
        a.setKeys(mapOf(bob to ContactKey(bobNext, previous = bobKey, previousUntilMs = now + 10_000)))
        val old = tagUnder(bobKey)
        assertEquals(resolved(bob, Source.Key), a.resolve(blinded(old)))
        now += 10_000
        assertEquals(Resolution.Unresolved, a.resolve(blinded(old)))
    }

    @Test
    fun aRemovedContactStopsResolvingAtOnce() {
        val a = aliases()
        a.setKeys(mapOf(bob to ContactKey(bobKey)))
        val tag = tagUnder(bobKey)
        assertEquals(resolved(bob, Source.Key), a.resolve(blinded(tag)))
        a.setKeys(emptyMap())
        assertEquals(Resolution.Unresolved, a.resolve(blinded(tag)))
    }

    @Test
    fun aDialerBelievesTheResponderOnlyWhenItToldTheTagDialed() {
        val a = aliases()
        val tag = tagUnder(key())
        assertEquals(ClaimOutcome.Ignored, a.onDialed("l1", blinded(tag), told = null, provenId = mallory))
        val clearT = a.onDialed("l1", blinded(tag), told = mallory, provenId = mallory)
        assertEquals("a clear responder's t is no tag", ClaimOutcome.Ignored, clearT)
        assertEquals(Resolution.Unresolved, a.resolve(blinded(tag)))
        assertEquals(ClaimOutcome.Recorded, a.onDialed("l1", blinded(tag), told = tag, provenId = alice))
        assertEquals(resolved(alice, Source.Claim), a.resolve(blinded(tag)))
        assertEquals(1L, a.counters().resolvedByClaim)
    }

    @Test
    fun aDifferentTAfterATagDialIsARotationEdgeHeldPending() {
        val a = aliases()
        val dialed = tagUnder(key())
        val rotated = tagUnder(key())
        // Alice rotated between my sighting and my dial: her HELLO tells her new tag, not the one I dialed.
        assertEquals(ClaimOutcome.Pending, a.onDialed("l1", blinded(dialed), told = rotated, provenId = alice))
        assertEquals("the dialed tag is not taken for hers", Resolution.Unresolved, a.resolve(blinded(dialed)))
        assertEquals("the told tag becomes her claim once heard", resolved(alice, Source.Claim), a.resolve(blinded(rotated)))
    }

    @Test
    fun aHeldLinksClaimNeverLapsesUnheardAndLapsesAfterItsEnd() {
        val a = aliases()
        val tag = tagUnder(key())
        val next = tagUnder(key())
        a.onDialed("l1", blinded(tag), tag, alice)
        a.onTagNext("l2", bob, next)
        val lifetime = AdvertAliases.Config().claimLifetimeMs
        // Deep suspend: nothing heard for three lifetimes while both links stand.
        now += 3 * lifetime
        assertEquals("unheard but held: still hers", resolved(alice, Source.Claim), a.resolve(blinded(tag)))
        assertEquals("an announced tag outlives the hold too", resolved(bob, Source.Claim), a.resolve(blinded(next)))
        a.onLinkEnded("l1")
        now += lifetime - 1
        assertEquals(resolved(alice, Source.Claim), a.resolve(blinded(tag)))
        now += 1
        assertEquals("990 s after the link ended", Resolution.Unresolved, a.resolve(blinded(tag)))
    }

    @Test
    fun aDialToAnAnnouncedTagTakesTheNewTag() {
        val a = aliases()
        val old = tagUnder(key())
        val next = tagUnder(key())
        assertEquals(ClaimOutcome.Recorded, a.onDialed("l1", blinded(old), told = old, provenId = alice))
        assertEquals(ClaimOutcome.Pending, a.onTagNext("l1", alice, next))
        // The link drops; we redial the old tag while Alice has rotated: her HELLO tells the new one.
        assertEquals(ClaimOutcome.Recorded, a.onDialed("l2", blinded(old), told = next, provenId = alice))
        assertEquals(resolved(alice, Source.Claim), a.resolve(blinded(next)))
        assertEquals("one claim per id: the old tag is replaced", Resolution.Unresolved, a.resolve(blinded(old)))
    }

    @Test
    fun aKeyOutranksAClaimAndContradictsIt() {
        val a = aliases()
        a.setKeys(mapOf(bob to ContactKey(bobKey)))
        val bobs = tagUnder(bobKey)
        assertEquals(
            "a tag dial proving another id than the key's records nothing",
            ClaimOutcome.Contradicted,
            a.onDialed("l1", blinded(bobs), bobs, mallory),
        )
        assertEquals(ClaimOutcome.Redundant, a.onDialed("l2", blinded(bobs), bobs, bob))
        assertEquals("a hint the key contradicts is not held", ClaimOutcome.Contradicted, a.onAnswered("l3", bobs, mallory))
        assertEquals(ClaimOutcome.Contradicted, a.onTagNext("l3", mallory, bobs))
        assertEquals(resolved(bob, Source.Key), a.resolve(blinded(bobs)))
        assertEquals(3L, a.counters().claimsDropped)
    }

    @Test
    fun aKeyThatArrivesLaterDropsTheClaimItContradicts() {
        val a = aliases()
        val bobs = tagUnder(bobKey)
        assertEquals(ClaimOutcome.Recorded, a.onDialed("l1", blinded(bobs), bobs, mallory))
        a.setKeys(mapOf(bob to ContactKey(bobKey)))
        assertEquals(resolved(bob, Source.Key), a.resolve(blinded(bobs)))
        assertEquals(1L, a.counters().claimsDropped)
        a.setKeys(emptyMap())
        assertEquals("the claim is gone, not shadowed", Resolution.Unresolved, a.resolve(blinded(bobs)))
    }

    @Test
    fun twoIdsClaimingOneTagVoidBothAndAThirdIsRefused() {
        val a = aliases()
        val tag = tagUnder(key())
        assertEquals(ClaimOutcome.Recorded, a.onDialed("l1", blinded(tag), tag, alice))
        assertEquals(ClaimOutcome.Contested, a.onDialed("l2", blinded(tag), tag, mallory))
        assertEquals(Resolution.Unresolved, a.resolve(blinded(tag)))
        assertEquals(ClaimOutcome.Contested, a.onDialed("l3", blinded(tag), tag, alice))
        assertEquals(ClaimOutcome.Contested, a.onTagNext("l4", bob, tag))
        assertEquals(Resolution.Unresolved, a.resolve(blinded(tag)))
        assertEquals(4L, a.counters().claimsVoided)
        now += AdvertAliases.Config().claimLifetimeMs
        assertEquals("the void lasts as long as the tag could", ClaimOutcome.Recorded, a.onDialed("l5", blinded(tag), tag, alice))
    }

    @Test
    fun aLinkAndAnIdEachHoldOneClaim() {
        val a = aliases()
        val t1 = tagUnder(key())
        val t2 = tagUnder(key())
        val t3 = tagUnder(key())
        val t4 = tagUnder(key())
        a.onDialed("l1", blinded(t1), t1, alice)
        a.onTagNext("l1", alice, t2)
        assertEquals(resolved(alice, Source.Claim), a.resolve(blinded(t2)))
        assertEquals("the new claim replaced the old one", Resolution.Unresolved, a.resolve(blinded(t1)))
        // The link rule alone: the same link now proves another id, so only "one per link" can drop alice's claim.
        a.onDialed("l1", blinded(t4), t4, bob)
        assertEquals("the link's new claim replaced its old one", Resolution.Unresolved, a.resolve(blinded(t2)))
        assertEquals(resolved(bob, Source.Claim), a.resolve(blinded(t4)))
        // The id rule alone: the same id on another link.
        a.onDialed("l2", blinded(t3), t3, bob)
        assertEquals("the id's new claim replaced its old one", Resolution.Unresolved, a.resolve(blinded(t4)))
        assertEquals(resolved(bob, Source.Claim), a.resolve(blinded(t3)))
    }

    @Test
    fun aLinksNewAnnouncementReplacesItsPendingOneWhateverIdItNames() {
        val a = aliases()
        val first = tagUnder(key())
        val second = tagUnder(key())
        a.onTagNext("l1", alice, first)
        a.onTagNext("l1", mallory, second)
        assertEquals("the link's earlier announcement is gone", Resolution.Unresolved, a.resolve(blinded(first)))
        assertEquals(resolved(mallory, Source.Claim), a.resolve(blinded(second)))
    }

    @Test
    fun aClaimOutlivesItsLinkUntilTheTagWouldHaveExpired() {
        val a = aliases()
        val tag = tagUnder(key())
        a.onDialed("l1", blinded(tag), tag, alice)
        a.onLinkEnded("l1")
        now += AdvertAliases.Config().claimLifetimeMs - 1
        assertEquals(resolved(alice, Source.Claim), a.resolve(blinded(tag)))
        now += 1
        assertEquals(Resolution.Unresolved, a.resolve(blinded(tag)))
        assertEquals(990_000L, AdvertAliases.Config().claimLifetimeMs)
    }

    /** Two tags, the smaller first. */
    private fun twoTags(): Pair<String, String> {
        val x = tagUnder(key())
        val y = tagUnder(key())
        return if (x < y) x to y else y to x
    }

    @Test
    fun anHonestHintResolvesAndWantsNoDial() {
        val a = aliases()
        val (mine, alices) = twoTags()
        // Alice dialed me, so under the antisymmetric rule I would not have dialed her, clear or blinded.
        assertEquals(ClaimOutcome.Hinted, a.onAnswered("l1", alices, alice))
        assertEquals(resolved(alice, Source.Hint), a.resolve(blinded(alices)))
        assertFalse(AdvertOrder.iDial(Form.clear(bob), blinded(alices)))
        assertFalse(AdvertOrder.iDial(blinded(mine), blinded(alices)))
        assertEquals(1L, a.counters().resolvedByHint)
        assertEquals("a clear dialer leaves no hint", ClaimOutcome.Ignored, a.onAnswered("l2", bob, bob))
        a.onLinkEnded("l1")
        assertEquals("a link's end takes its hint", Resolution.Unresolved, a.resolve(blinded(alices)))
    }

    @Test
    fun aRotationUnderAHintIsHealedByTheResendAsAClaim() {
        val a = aliases()
        val (dialers, mine) = twoTags()
        // Dialer D linked to me (R) on its tag; I then rotate to a tag that sorts above D's, so the order has me dial D.
        assertEquals(ClaimOutcome.Hinted, a.onAnswered("l1", dialers, alice))
        assertEquals(resolved(alice, Source.Hint), a.resolve(blinded(dialers)))
        assertTrue("a hint never suppresses this dial", AdvertOrder.iDial(blinded(mine), blinded(dialers)))
        // D refuses that dial with no reply and resends its current tag over the held link.
        assertEquals(ClaimOutcome.Pending, a.onTagNext("l1", alice, dialers))
        val healed = a.resolve(blinded(dialers))
        assertEquals("heard again: now a claim, which the wiring lets suppress the dial", resolved(alice, Source.Claim), healed)
    }

    @Test
    fun aForgedHintLeavesTheDialStandingAndItsProofVoidsTheLiar() {
        val a = aliases()
        val (alices, mine) = twoTags()
        val strangers = tagUnder(key())
        // Mallory dials me and names Alice's tag; she also holds a claim from an earlier link.
        a.onDialed("l0", blinded(strangers), strangers, mallory)
        assertEquals(ClaimOutcome.Hinted, a.onAnswered("l1", alices, mallory))
        assertEquals(resolved(mallory, Source.Hint), a.resolve(blinded(alices)))
        assertTrue("a hint never suppresses this dial", AdvertOrder.iDial(blinded(mine), blinded(alices)))
        // The dial reaches the device airing Alice's tag, which proves Alice.
        assertEquals(ClaimOutcome.Recorded, a.onDialed("l2", blinded(alices), alices, alice))
        assertEquals(resolved(alice, Source.Claim), a.resolve(blinded(alices)))
        assertEquals("the liar loses every claim", Resolution.Unresolved, a.resolve(blinded(strangers)))
        assertEquals(1L, a.counters().proofMismatches)
        assertEquals(2L, a.counters().claimsVoided)
        assertFalse("a clear dial proves nothing about a tag", a.onDialProved(Form.clear(alice), alice))
    }

    @Test
    fun twoDialsToOneTagProvingTwoIdsAreAContestNotALie() {
        val a = aliases()
        val tag = tagUnder(key())
        a.onDialed("l1", blinded(tag), tag, alice)
        a.onAnswered("l9", tag, bob)
        assertEquals(ClaimOutcome.Contested, a.onDialed("l2", blinded(tag), tag, mallory))
        assertEquals("no hint resolves a void tag", Resolution.Unresolved, a.resolve(blinded(tag)))
        assertEquals("the unproven hint was the lie", 1L, a.counters().proofMismatches)
    }

    @Test
    fun hintsThatDisagreeResolveNothing() {
        val a = aliases()
        val tag = tagUnder(key())
        a.onAnswered("l1", tag, alice)
        a.onAnswered("l2", tag, bob)
        assertEquals(Resolution.Unresolved, a.resolve(blinded(tag)))
    }

    @Test
    fun anAnnouncedTagResolvesOnlyOnceHeardThenReplacesTheLinksClaim() {
        val a = aliases()
        val old = tagUnder(key())
        val next = tagUnder(key())
        a.onDialed("l1", blinded(old), old, alice)
        assertEquals(ClaimOutcome.Pending, a.onTagNext("l1", alice, next))
        assertEquals("the old tag still resolves before the rotation", resolved(alice, Source.Claim), a.resolve(blinded(old)))
        assertEquals("first heard: it becomes the claim", resolved(alice, Source.Claim), a.resolve(blinded(next)))
        assertEquals(Resolution.Unresolved, a.resolve(blinded(old)))
    }

    @Test
    fun myOwnTagIsNeverAClaim() {
        val a = aliases()
        val mine = tagUnder(key())
        a.setOwnTags(mine, null)
        assertEquals(ClaimOutcome.Ignored, a.onDialed("l1", blinded(mine), mine, mallory))
        assertEquals(ClaimOutcome.Ignored, a.onTagNext("l1", mallory, mine))
        assertEquals(Resolution.Own, a.resolve(blinded(mine)))
    }

    @Test
    fun theClaimTableHasACapAndKeepsWhatItHolds() {
        val a = aliases(AdvertAliases.Config(maxClaims = 2))
        val tags = List(3) { tagUnder(key()) }
        assertEquals(ClaimOutcome.Recorded, a.onDialed("l0", blinded(tags[0]), tags[0], alice))
        assertEquals(ClaimOutcome.Recorded, a.onDialed("l1", blinded(tags[1]), tags[1], bob))
        assertEquals(ClaimOutcome.Full, a.onDialed("l2", blinded(tags[2]), tags[2], mallory))
        assertEquals(resolved(alice, Source.Claim), a.resolve(blinded(tags[0])))
        assertEquals(1L, a.counters().claimsRefusedFull)
    }

    @Test
    fun aSpotCheckIsRateLimitedAndVoidsALiarsClaims() {
        val a = aliases()
        val honest = tagUnder(key())
        val stolen = tagUnder(key())
        // Claims learned by announcement, which no dial of ours proved: Mallory announces a stranger's tag as hers.
        a.onTagNext("l1", alice, honest)
        a.resolve(blinded(honest))
        now += 1
        a.onTagNext("l2", mallory, stolen)
        a.resolve(blinded(stolen))
        val first = present(a.nextSpotCheck())
        assertEquals(honest, first.tag)
        assertNull("one per interval", a.nextSpotCheck())
        a.onSpotCheck(first, provenId = null)
        assertEquals("no reply is consistent with an honest claim", resolved(alice, Source.Claim), a.resolve(blinded(honest)))
        now += AdvertAliases.Config().spotCheckIntervalMs
        val second = present(a.nextSpotCheck())
        assertEquals(stolen, second.tag)
        a.onSpotCheck(second, provenId = bob)
        assertEquals(Resolution.Unresolved, a.resolve(blinded(stolen)))
        assertEquals("the tag stays open for its owner", ClaimOutcome.Recorded, a.onDialed("l3", blinded(stolen), stolen, bob))
        now += AdvertAliases.Config().spotCheckIntervalMs
        assertNull("each claim is offered once, and a dial-proven claim never", a.nextSpotCheck())
        assertEquals(1L, a.counters().proofMismatches)
    }

    @Test
    fun countersAndSpotChecksNeverPrintAKeyOrATag() {
        val a = aliases()
        a.setKeys(mapOf(bob to ContactKey(bobKey)))
        val tag = tagUnder(key())
        a.resolve(blinded(tagUnder(bobKey)))
        a.onTagNext("l1", alice, tag)
        a.resolve(blinded(tag))
        val printed = a.counters().toString() + a.nextSpotCheck().toString()
        assertFalse(printed.contains(tag))
        assertFalse(printed.contains(tag.substring(8)))
        assertFalse(printed.contains(bobKey.joinToString("") { "%02x".format(it) }))
        assertTrue(printed.contains("SpotCheck("))
    }

    private fun <T : Any> present(value: T?): T {
        assertNotNull(value)
        return checkNotNull(value)
    }
}
