package app.getknit.knit

import app.getknit.knit.identity.NodeId
import app.getknit.knit.legal.repoFile
import app.getknit.knit.mesh.bluetooth.AdvertAliases
import app.getknit.knit.mesh.bluetooth.AdvertAliases.ClaimOutcome
import app.getknit.knit.mesh.bluetooth.AdvertAliases.Resolution
import app.getknit.knit.mesh.bluetooth.AdvertAliases.Source
import app.getknit.knit.mesh.bluetooth.AdvertOrder
import app.getknit.knit.mesh.bluetooth.AdvertOrder.Form
import app.getknit.knit.mesh.bluetooth.BleAdvertPayload
import app.getknit.knit.mesh.bluetooth.BlePresenceTracker
import app.getknit.knit.mesh.bluetooth.BlindSightings
import app.getknit.knit.mesh.bluetooth.DialPlan
import app.getknit.knit.mesh.bluetooth.GattPayloads
import app.getknit.knit.mesh.bluetooth.GattPayloads.Outcome
import app.getknit.knit.mesh.bluetooth.GattTargets
import app.getknit.knit.mesh.bluetooth.form
import app.getknit.knit.mesh.crypto.BlindTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A stale GATT target gives no dial while its id holds a proven link ([GattTargets.withheld], the paired trial of
 * 2026-11-12): each case runs a resolved sighting through [BlindSightings.attribute] and [DialPlan.ordinaryCandidates],
 * then the target filter the transport's `dialAccepted` applies at decide time, with [GattPayloads] giving the read slot.
 */
class StaleGattTargetTest {
    private var now = 1_000_000L
    private var wall = 1_800_000_000_000L // mid-slot
    private val slot = BlindTag.slotOf(wall)
    private val alice = NodeId.derive("alice")
    private val noHeldDial: (BlePresenceTracker.Snapshot) -> Boolean = { false }

    /** A blinding phone airing a tag above every one heard here, so [AdvertOrder.iDial] has me dial each (past our edge). */
    private val mine = AdvertOrder.Mine(Form("zzzzzzzzzzzzzzzzzzzzzzzzzz", blinded = true))

    private val tag =
        NodeId.fromBytes(BlindTag.tag(ByteArray(BlindTag.KEY_BYTES) { 9 }, NodeId.toBytes(alice), BlindTag.slotOf(wall), 0))

    private fun aliases() = AdvertAliases<String>({ now }, { wall })

    private fun blinded(tag: String) = Form(tag, blinded = true)

    /**
     * Whether the transport dials a sighting of [heardTag] that [a] resolves now: the ordinary candidates over the links
     * [held] (each proven), then `dialAccepted`'s filter on the target, read in [readSlot] (against [nowSlot]) and heard
     * at [address], with [linkAddresses] the held proven links' addresses. [rule] false leaves the filter out, as the
     * code before it did.
     */
    @Suppress("LongParameterList") // one decision's inputs
    private fun dialed(
        a: AdvertAliases<String>,
        held: Map<String, String>,
        readSlot: Long?,
        nowSlot: Long = slot + 1,
        rule: Boolean = true,
        heardTag: String = tag,
        address: String = "phone",
        linkAddresses: Set<String> = emptySet(),
    ): Boolean {
        val attribution = checkNotNull(BlindSightings.attribute(a.resolve(blinded(heardTag)), "~handle", replayed = false))
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
                advertised = heardTag,
                blinded = true,
                suppressor = attribution.suppressor,
            )
        assertTrue("the order has me dial it", AdvertOrder.iDial(mine.current, snap.form))
        val linked = held.keys
        return DialPlan
            .ordinaryCandidates(mine, listOf(snap), linked, emptySet(), noHeldDial, linked) { held[it] }
            .any { !rule || !GattTargets.withheld(readSlot, nowSlot, it.nodeId in linked, address in linkAddresses) }
    }

    @Test
    fun aLinkedPeerResolvedByAHintIsNotDialedAtAStaleGattTarget() {
        // The trial's lone-hint case: she dialed in telling the tag; a hint suppresses nothing (ADR 2026-11.xp6d).
        val a = aliases()
        assertEquals(ClaimOutcome.Hinted, a.onAnswered("l", tag, alice))
        assertEquals(Resolution.Resolved(alice, Source.Hint), a.resolve(blinded(tag)))
        assertTrue("the answer alone holds nothing off", dialed(a, mapOf(alice to "l"), readSlot = slot, rule = false))
        assertFalse("read before the edge, linked: no dial", dialed(a, mapOf(alice to "l"), readSlot = slot))
    }

    @Test
    fun aLinkedPeerResolvedByAnEndedClaimIsNotDialedAtAStaleGattTarget() {
        // The trial's dead-claim case with no yield: her new link told no tag, so the claim names the ended link.
        val a = aliases()
        assertEquals(ClaimOutcome.Pending, a.onTagNext("old", alice, tag))
        assertEquals(Resolution.Resolved(alice, Source.Claim), a.resolve(blinded(tag)))
        a.onLinkEnded("old")
        a.onAnswered("new", null, alice)
        assertTrue("the ended claim holds nothing off", dialed(a, mapOf(alice to "new"), readSlot = slot, rule = false))
        assertFalse(dialed(a, mapOf(alice to "new"), readSlot = slot))
    }

    @Test
    fun anUnlinkedPeerIsStillDialedAtItsStaleTag() {
        val a = aliases()
        a.onTagNext("old", alice, tag)
        a.resolve(blinded(tag))
        a.onLinkEnded("old")
        assertTrue("no proven link under the id: its responder honours the dialed form", dialed(a, emptyMap(), readSlot = slot))
        assertFalse("an unproven link is no proven one", GattTargets.withheld(slot, slot + 1, false, atProvenLinkAddress = false))
    }

    @Test
    fun aPayloadReadInTheCurrentSlotDialsAsBefore() {
        val a = aliases()
        a.onAnswered("l", tag, alice)
        assertTrue("the re-read releases it", dialed(a, mapOf(alice to "l"), readSlot = slot + 1))
        assertTrue("before the edge, nothing is stale", dialed(a, mapOf(alice to "l"), readSlot = slot, nowSlot = slot))
        assertTrue("a service-data advert has no read slot", dialed(a, mapOf(alice to "l"), readSlot = null))
    }

    @Test
    fun theReadSlotIsTheBlindedReadsOwnAndRule4sReReadMovesIt() {
        val payloads = GattPayloads(GattPayloads.Configuration(), wallMs = { wall }, jitterSeed = 0L)
        val blindedPayload =
            BleAdvertPayload.Parsed(tag, capabilities = 0xFFF, digestCue = 0, psm = 0x81, flags = BleAdvertPayload.FLAG_BLINDED)
        assertTrue(payloads.begin("phone", 0))
        payloads.finish("phone", Outcome.Read(blindedPayload), 0)
        assertEquals(slot, payloads.readSlot(of = "phone"))
        assertEquals("a cached advert carries it", slot, payloads.heard("phone").readSlot)
        wall = (slot + 1) * BlindTag.SLOT_MS + 1_000
        assertEquals("past the edge, inside rule 4's slack: still the old slot", slot, payloads.heard("phone").readSlot)
        wall = (slot + 1) * BlindTag.SLOT_MS + 65_000
        assertTrue("rule 4 forgets it", payloads.heard("phone").pastEdge)
        assertTrue(payloads.begin("phone", 100_000))
        payloads.finish("phone", Outcome.Read(blindedPayload), 100_000)
        assertEquals("the re-read is the current slot's, the same tag included", slot + 1, payloads.readSlot(of = "phone"))
        val clear = BleAdvertPayload.Parsed(alice, capabilities = 0xFF, digestCue = 0, psm = 0x81)
        assertTrue(payloads.begin("other", 200_000))
        payloads.finish("other", Outcome.Read(clear), 200_000)
        assertNull("a clear payload has none", payloads.readSlot(of = "other"))
        assertNull(payloads.heard("other").readSlot)
    }

    @Test
    fun anUnresolvedHandleAtAProvenLinksAddressIsWithheld() {
        // The reviewer's case: she rotated at the edge and dialed in from the same address inside rule 4's lag, telling
        // her new tag; the address is spared, so its old payload stays, and no claim names the old tag.
        val a = aliases()
        val newTag =
            NodeId.fromBytes(BlindTag.tag(ByteArray(BlindTag.KEY_BYTES) { 9 }, NodeId.toBytes(alice), BlindTag.slotOf(wall) + 1, 0))
        assertEquals(ClaimOutcome.Hinted, a.onAnswered("new", newTag, alice))
        assertEquals(Resolution.Unresolved, a.resolve(blinded(tag)))
        val atLink = setOf("phone")
        assertTrue("keyed on the id alone, the handle is dialed", dialed(a, mapOf(alice to "new"), readSlot = slot))
        assertFalse("at the link's address: withheld", dialed(a, mapOf(alice to "new"), readSlot = slot, linkAddresses = atLink))
        assertFalse("in any slot", dialed(a, mapOf(alice to "new"), readSlot = slot, nowSlot = slot, linkAddresses = atLink))
    }

    @Test
    fun aLapsedClaimAtAProvenLinksAddressIsWithheld() {
        val a = aliases()
        a.onTagNext("old", alice, tag)
        a.resolve(blinded(tag))
        a.onLinkEnded("old")
        a.onAnswered("new", null, alice)
        now += AdvertAliases.Config().claimLifetimeMs
        assertEquals("the claim lapsed: a handle", Resolution.Unresolved, a.resolve(blinded(tag)))
        assertFalse(dialed(a, mapOf(alice to "new"), readSlot = slot, linkAddresses = setOf("phone")))
    }

    @Test
    fun aGattTargetAtAnotherAddressOrAServiceDataTargetAtTheLinksAddressFollowsTheOrdinaryRules() {
        val a = aliases()
        assertEquals(Resolution.Unresolved, a.resolve(blinded(tag)))
        val held = mapOf(alice to "new")
        val atLink = setOf("phone")
        assertTrue("another address: an unresolved form is dialed", dialed(a, held, slot, address = "elsewhere", linkAddresses = atLink))
        assertTrue("no read slot: not this rule's", dialed(a, held, readSlot = null, linkAddresses = atLink))
    }

    @Test
    fun aClockSetBackBelowTheReadsSlotStillWithholdsALinkedPeersTarget() {
        val a = aliases()
        a.onAnswered("l", tag, alice)
        assertFalse(dialed(a, mapOf(alice to "l"), readSlot = slot, nowSlot = slot - 1))
    }

    @Test
    fun aSybilsHintWithholdsOurDialToTheStrangerOnlyUntilRule4sReRead() {
        // Mallory, proven and linked, hints a stranger's tag: past the edge our dial to the stranger's device waits.
        val stranger = NodeId.derive("stranger")
        val strangers =
            NodeId.fromBytes(BlindTag.tag(ByteArray(BlindTag.KEY_BYTES) { 4 }, NodeId.toBytes(stranger), BlindTag.slotOf(wall), 0))
        val mallory = NodeId.derive("mallory")
        val a = aliases()
        a.onAnswered("m", strangers, mallory)
        val held = mapOf(mallory to "m")
        val payloads = GattPayloads(GattPayloads.Configuration(), wallMs = { wall }, jitterSeed = 0L)
        val payload =
            BleAdvertPayload.Parsed(strangers, capabilities = 0xFFF, digestCue = 0, psm = 0x81, flags = BleAdvertPayload.FLAG_BLINDED)
        assertTrue(payloads.begin("stranger", 0))
        payloads.finish("stranger", Outcome.Read(payload), 0)
        val readIn = payloads.readSlot(of = "stranger")
        assertTrue("inside the slot the dial stands", dialed(a, held, readIn, nowSlot = slot, heardTag = strangers, address = "stranger"))
        assertFalse("past the edge it waits", dialed(a, held, readIn, nowSlot = slot + 1, heardTag = strangers, address = "stranger"))
        wall = (slot + 1) * BlindTag.SLOT_MS + 65_000
        assertEquals("the stranger's address is unspared", listOf("stranger"), payloads.forgetPastEdge(emptySet()))
        assertTrue(payloads.begin("stranger", 100_000))
        payloads.finish("stranger", Outcome.Read(payload), 100_000)
        val reRead = payloads.readSlot(of = "stranger")
        assertTrue("the re-read ends it", dialed(a, held, reRead, nowSlot = slot + 1, heardTag = strangers, address = "stranger"))
        // The dial reaches the stranger and proves her id: the liar's hint goes.
        assertTrue(a.onDialProved(blinded(strangers), stranger))
        assertEquals(Resolution.Unresolved, a.resolve(blinded(strangers)))
    }

    private val transport = repoFile("app/src/main/java/app/getknit/knit/mesh/bluetooth/BluetoothMeshTransport.kt").readText()

    /** The whitespace-stripped body of the transport's private function [name], up to the next member. */
    private fun inside(name: String) =
        transport
            .substringAfter("private fun $name(")
            .split(Regex("\n\n    (?=\\S)"))
            .first()
            .filterNot(Char::isWhitespace)

    @Test
    fun everyDialPathTakesItsTargetThroughTheRule() {
        // No host rig reaches the transport's wiring, so pins on its source: a dropped argument reads as a pass otherwise.
        assertTrue("staleGatt(snap.nodeId,heard.gattReadSlot,heard.device.address)" in inside("dialAccepted"))
        assertTrue("staleGatt(snap.nodeId,heard.gattReadSlot,heard.device.address)" in inside("staleTarget"))
        assertTrue("dialAccepted(snap)" in inside("dialTarget"))
        assertTrue("dialAccepted(snap)" in inside("initiateTo"))
        assertTrue("staleGatt(key,gattReadSlot,address)" in inside("isBoostTrigger"))
        assertTrue("filterNot(::staleTarget)" in inside("floorScan"))
        val rule = inside("staleGatt")
        assertTrue(rule, "provenLinked=links[key]?.proven==true" in rule)
        assertTrue(rule, "atProvenLinkAddress=linkAddresses.entries.any{(link,at)->at==address&&link.proven}" in rule)
        assertTrue("HeardDevice(device,form,parsed.linkSeal,gattReadSlot)" in inside("sight"))
        // The decide-time clock: a slot stamped at the sighting would miss the trial's first dial, 0.5 s past our rotation.
        assertTrue(rule, "BlindTag.slotOf(System.currentTimeMillis())" in rule)
        assertTrue("isBoostTrigger(form,key,suppressor,device.address,gattReadSlot)" in inside("sight"))
        // The read slot's path from the payload to the dial target: each hop defaults to null, which withholds nothing.
        assertTrue("gattReadSlot=readSlot" in inside("offerGattBlind"))
        assertTrue("gattReadSlot=item.gattReadSlot" in inside("readBlind"))
        assertTrue("readEnd=false,heard.readSlot)" in inside("onScanResult"))
        assertTrue("readEnd=true,gattPayloads.readSlot(address))" in inside("maybeReadGatt"))
    }
}
