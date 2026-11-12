package app.getknit.knit

import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.bluetooth.AdvertAliases
import app.getknit.knit.mesh.bluetooth.AdvertAliases.ClaimOutcome
import app.getknit.knit.mesh.bluetooth.AdvertAliases.Resolution
import app.getknit.knit.mesh.bluetooth.AdvertAliases.Source
import app.getknit.knit.mesh.bluetooth.AdvertOrder
import app.getknit.knit.mesh.bluetooth.AdvertOrder.Form
import app.getknit.knit.mesh.bluetooth.BlePresenceTracker
import app.getknit.knit.mesh.bluetooth.BlindSightings
import app.getknit.knit.mesh.bluetooth.DialPlan
import app.getknit.knit.mesh.bluetooth.form
import app.getknit.knit.mesh.crypto.BlindTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An ended claim's yield to its own id's hint (`AdvertAliases.claimAnswer`, ADR 2026-11.89gg's amendment of 2026-11-12,
 * after knit-ios `AdvertAliasesClaimTests+Yield.swift` and `BlindReaderTests`' Sybil case): carried from the resolver
 * through [BlindSightings.attribute] to [DialPlan.ordinaryCandidates], against the links held when the dial is decided.
 */
class ClaimYieldTest {
    private var now = 1_000_000L
    private val wall = 1_800_000_000_000L
    private val alice = NodeId.derive("alice")
    private val mallory = NodeId.derive("mallory")
    private val stranger = NodeId.derive("stranger")
    private val noHeldDial: (BlePresenceTracker.Snapshot) -> Boolean = { false }

    /** A tag [owner] could air in slot [slotOffset] from the wall clock's; no key of ours lists it. */
    private fun tag(
        owner: String,
        slotOffset: Long = 0,
    ): String =
        NodeId.fromBytes(
            BlindTag.tag(ByteArray(BlindTag.KEY_BYTES) { 7 }, NodeId.toBytes(owner), BlindTag.slotOf(wall) + slotOffset, 0),
        )

    private fun aliases() = AdvertAliases<String>({ now }, { wall })

    private fun blinded(tag: String) = Form(tag, blinded = true)

    /** A blinding phone airing a tag above every one these tests hear, so [AdvertOrder.iDial] has me dial each. */
    private val mine = AdvertOrder.Mine(Form("zzzzzzzzzzzzzzzzzzzzzzzzzz", blinded = true))

    /** [tag] claimed by [id]'s link [link] as Android records it: a `TAG_NEXT`, promoted to its claim when first heard. */
    private fun AdvertAliases<String>.claimByAnnouncement(
        link: String,
        id: String,
        tag: String,
    ) {
        assertEquals(ClaimOutcome.Pending, onTagNext(link, id, tag))
        assertEquals(Resolution.Resolved(id, Source.Claim), resolve(blinded(tag)))
    }

    /**
     * Whether a sighting of [tag] that [a] resolves now is dialed, with [held] the link held under each id and the links'
     * ids [linked] (each proven).
     */
    private fun dialed(
        a: AdvertAliases<String>,
        tag: String,
        held: Map<String, String>,
    ): Boolean {
        val attribution = checkNotNull(BlindSightings.attribute(a.resolve(blinded(tag)), "~handle", replayed = false))
        val snap =
            BlePresenceTracker.Snapshot(
                nodeId = attribution.key,
                protoVersion = 1,
                capabilities = 0L,
                psm = 100,
                digestCue = 0,
                smoothedRssi = -60.0,
                dwellMs = 15_000,
                lastSeenAgoMs = 0,
                advertised = tag,
                blinded = true,
                suppressor = attribution.suppressor,
            )
        assertTrue("the order has me dial it", AdvertOrder.iDial(mine.current, snap.form))
        val linked = held.keys
        return DialPlan.ordinaryCandidates(mine, listOf(snap), linked, emptySet(), noHeldDial, linked) { held[it] }.isNotEmpty()
    }

    private fun claimant(
        a: AdvertAliases<String>,
        tag: String,
    ): Any? = (a.resolve(blinded(tag)) as Resolution.Resolved).claimant

    @Test
    fun aPeerThatDialsBackInTellingItsClaimedTagIsNotRedialedWhileItsNewLinkIsHeld() {
        // The device trial's failure: the claim belongs to the link that ended; she dialed back in telling the same tag.
        val a = aliases()
        val t = tag(alice)
        a.claimByAnnouncement("old", alice, t)
        a.onLinkEnded("old")
        assertEquals(ClaimOutcome.Hinted, a.onAnswered("new", t, alice))
        val resolution = a.resolve(blinded(t))
        assertEquals("still a claim's answer, for her id", Resolution.Resolved(alice, Source.Claim), resolution)
        assertSame("the ended claim yields its claimant to the held link", "new", (resolution as Resolution.Resolved).claimant)
        assertEquals(
            BlePresenceTracker.Suppressor.Claim("new"),
            BlindSightings.attribute(resolution, "~handle", replayed = false)?.suppressor,
        )
        assertEquals("counted as a claim's answer", 0L, a.counters().resolvedByHint)
        assertTrue("her new link holds the dial off", !dialed(a, t, held = mapOf(alice to "new")))
    }

    @Test
    fun onceTheNewLinkEndsTheEndedClaimAnswersAgainAndTheDialGoes() {
        val a = aliases()
        val t = tag(alice)
        a.claimByAnnouncement("old", alice, t)
        a.onLinkEnded("old")
        a.onAnswered("new", t, alice)
        a.onLinkEnded("new")
        assertSame("the hint went with its link", "old", claimant(a, t))
        assertTrue(dialed(a, t, held = emptyMap()))
        now += AdvertAliases.Config().claimLifetimeMs
        assertEquals("and the claim lapses as it would have", Resolution.Unresolved, a.resolve(blinded(t)))
    }

    @Test
    fun anotherIdsHintOnTheTagDoesNotTakeTheClaim() {
        val a = aliases()
        val t = tag(alice)
        a.claimByAnnouncement("old", alice, t)
        a.onLinkEnded("old")
        a.onAnswered("m", t, mallory)
        assertSame("chosen by id, never by the hint's count", "old", claimant(a, t))
        assertTrue("her own new link told no tag: the dial goes", dialed(a, t, held = mapOf(alice to "new", mallory to "m")))
        // Her own id's hint beside Mallory's still takes it.
        a.onAnswered("new", t, alice)
        assertSame("new", claimant(a, t))
    }

    @Test
    fun aHintOnAnotherTagDoesNotTakeTheClaim() {
        val a = aliases()
        val t = tag(alice)
        a.claimByAnnouncement("old", alice, t)
        a.onLinkEnded("old")
        assertEquals(ClaimOutcome.Hinted, a.onAnswered("new", tag(alice, slotOffset = 1), alice))
        assertSame("old", claimant(a, t))
        assertTrue(dialed(a, t, held = mapOf(alice to "new")))
    }

    @Test
    fun aClaimWhoseLinkIsStillHeldKeepsItsOwnClaimant() {
        val a = aliases()
        val t = tag(alice)
        a.claimByAnnouncement("old", alice, t)
        a.onAnswered("new", t, alice)
        assertSame("its link is held: nothing to yield", "old", claimant(a, t))
        assertTrue(!dialed(a, t, held = mapOf(alice to "old")))
    }

    @Test
    fun aPlainHintStillSuppressesNothing() {
        val a = aliases()
        val t = tag(alice)
        a.onAnswered("new", t, alice)
        val resolution = a.resolve(blinded(t))
        assertEquals(Resolution.Resolved(alice, Source.Hint), resolution)
        assertEquals(BlePresenceTracker.Suppressor.None, BlindSightings.attribute(resolution, "~handle", replayed = false)?.suppressor)
        assertTrue("a hint holds no dial off, its link held or not", dialed(a, t, held = mapOf(alice to "new")))
    }

    @Test
    fun aSybilThatRelinksTellingTheClaimedTagHoldsOffOurDialOnlyWhileItsNewLinkIsHeld() {
        // The residual the yield accepts (knit-ios ADR 2026-11.sv5t's "forged hint"): Mallory announced a stranger's tag
        // on her proven link, so it became her claim; she relinks telling it. Our dial to it reaches the stranger, whose
        // proof would void her.
        val a = aliases()
        val strangers = tag(stranger)
        a.claimByAnnouncement("m1", mallory, strangers)
        a.onLinkEnded("m1")
        assertTrue("her link ended: the dial goes", dialed(a, strangers, held = emptyMap()))
        a.onAnswered("m2", strangers, mallory)
        assertTrue("held off while her new link is held", !dialed(a, strangers, held = mapOf(mallory to "m2")))
        a.onLinkEnded("m2")
        assertTrue("its link ended: the ended claim answers again and the dial goes", dialed(a, strangers, held = emptyMap()))
    }
}
