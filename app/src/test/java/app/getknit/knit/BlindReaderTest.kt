package app.getknit.knit

import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.bluetooth.AdvertAliases
import app.getknit.knit.mesh.bluetooth.AdvertAliases.ClaimOutcome
import app.getknit.knit.mesh.bluetooth.AdvertAliases.ContactKey
import app.getknit.knit.mesh.bluetooth.AdvertAliases.Resolution
import app.getknit.knit.mesh.bluetooth.AdvertOrder
import app.getknit.knit.mesh.bluetooth.AwareCandidateTracker
import app.getknit.knit.mesh.bluetooth.BlePresenceTracker
import app.getknit.knit.mesh.bluetooth.BlindQueue
import app.getknit.knit.mesh.bluetooth.BlindSightings
import app.getknit.knit.mesh.bluetooth.BtLines
import app.getknit.knit.mesh.bluetooth.DialKeys
import app.getknit.knit.mesh.bluetooth.DialPlan
import app.getknit.knit.mesh.bluetooth.FormKeys
import app.getknit.knit.mesh.bluetooth.LinkEventStamps
import app.getknit.knit.mesh.bluetooth.PresenceConfig
import app.getknit.knit.mesh.bluetooth.ScanPlan
import app.getknit.knit.mesh.crypto.BlindTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reader's pure pieces around the transport's worker (ADR 2026-11.xp6d, step 5a): the handle an unresolved form is
 * keyed and logged under, the conflating hop to the worker, the link-event stamps that keep a late resolve from undoing
 * a link event, the published sets, and the `bt …` lines a tag's key can reach. The Sybil cases at the end walk one claim
 * from the link that made it, through the answer the reader gives, to the dial decision and its release.
 */
class BlindReaderTest {
    private val tag = NodeId.fromBytes(BlindTag.tag(ByteArray(BlindTag.KEY_BYTES) { 3 }, slot = 1, restart = 0))
    private val other = NodeId.fromBytes(BlindTag.tag(ByteArray(BlindTag.KEY_BYTES) { 4 }, slot = 1, restart = 0))

    private val sybil = NodeId.derive("sybil")
    private val secondSybil = NodeId.derive("second sybil")
    private val stranger = NodeId.derive("stranger")
    private val contact = NodeId.derive("contact")
    private val contactKey = ByteArray(BlindTag.KEY_BYTES) { 5 }

    // In the expected-tag window, as `tag` and `other` are not: their slot is decades before WALL_MS.
    private val contactsTag = NodeId.fromBytes(BlindTag.tag(contactKey, BlindTag.slotOf(WALL_MS), restart = 0))

    // Any 26-char base32 run starting `zzzz`: a full tag in a line (security's grep for the trial).
    private val fullTag = Regex("zzzz[a-z2-7]{22}")

    @Test
    fun aFormsHandleIsStableShowsNoTagAndLivesWhereNoIdCan() {
        val keys = FormKeys()
        val key = keys.keyFor(tag)
        assertEquals(key, keys.keyFor(tag))
        assertTrue(key != keys.keyFor(other))
        assertTrue(FormKeys.isForm(key))
        assertNull("no node id parses from it", NodeId.toBytesOrNull(key))
        assertFalse(fullTag.containsMatchIn(key))
        // A clear advert carrying the tag's bytes is its own id: never the handle.
        assertFalse(FormKeys.isForm(tag))
    }

    @Test
    fun theHopKeepsTheLatestSightingPerFormAndCountsWhatItSheds() {
        val q = BlindQueue<Int>(cap = 2)
        assertEquals(BlindQueue.Offer.START, q.offer("a", 1))
        assertEquals(BlindQueue.Offer.QUEUED, q.offer("b", 2))
        assertEquals(BlindQueue.Offer.CONFLATED, q.offer("a", 3))
        assertEquals(BlindQueue.Offer.DROPPED, q.offer("c", 4))
        assertEquals(listOf(3, 2), q.take())
        assertEquals(BlindQueue.Offer.QUEUED, q.offer("c", 5))
        assertEquals(listOf(5), q.take())
        assertEquals(emptyList<Int>(), q.take())
        assertEquals("the drain ended: the next offer starts one", BlindQueue.Offer.START, q.offer("d", 6))
        assertEquals(1L, q.conflated)
        assertEquals(1L, q.dropped)
    }

    @Test
    fun aSightingCapturedBeforeItsPeersLastLinkEventIsLateAndReachesNoFurtherThanPresence() {
        // The ordering contract (AdvertAliases' KDoc): a link down at t1, then a sighting stamped t0 < t1 resolves.
        val stamps = LinkEventStamps()
        val id = "bbb"
        stamps.onLinkEvent(id, at = 3_500)
        val t0 = 3_200L
        assertTrue(stamps.late(id, t0))
        assertFalse("a sighting after the event is on time", stamps.late(id, 3_600))
        assertFalse("another peer's event is not this one's", stamps.late("ccc", t0))
        // What readBlind derives from it: presence and RSSI only, no address, no boost, Aware, side or link notes.
        assertEquals(
            BlindSightings.Reach(devices = false, reach = false, linkNotes = false),
            BlindSightings.reach(true, !stamps.late(id, t0), true),
        )
        // Presence takes the later of the two stamps.
        val presence = BlePresenceTracker()
        presence.onSighting(sighting(id, -60), now = 3_000)
        presence.onSighting(sighting(id, -40), now = t0)
        assertEquals(800L, presence.snapshots(4_000).single().lastSeenAgoMs)
    }

    @Test
    fun aLateSightingNeverReplacesTheLatestForm() {
        val presence = BlePresenceTracker()
        presence.onSighting(sighting("bbb", -60), now = 2_000)
        presence.onSighting(
            sighting("bbb", -60, advertised = tag, blinded = true, suppressor = BlePresenceTracker.Suppressor.None),
            now = 1_000,
        )
        val snap = presence.snapshots(2_000).single()
        assertEquals("bbb", snap.advertised)
        assertEquals(BlePresenceTracker.Suppressor.Linked, snap.suppressor)
    }

    @Test
    fun aFormsHistoryCarriesToTheIdItResolvesTo() {
        val presence = BlePresenceTracker()
        val handle = FormKeys().keyFor(tag)
        presence.onSighting(sighting(handle, -60, advertised = tag, blinded = true), now = 0)
        presence.onSighting(sighting(handle, -60, advertised = tag, blinded = true), now = 5_000)
        presence.rekey(handle, "bbb")
        presence.onSighting(sighting("bbb", -60, advertised = tag, blinded = true), now = 6_000)
        val snap = presence.snapshots(6_000).single()
        assertEquals("bbb", snap.nodeId)
        assertEquals("a contact first heard unresolved keeps its dwell", 6_000L, snap.dwellMs)
    }

    @Test
    fun aRekeyedFormCarriesTheSuppressorThatResolvedIt() {
        val presence = BlePresenceTracker()
        val handle = FormKeys().keyFor(tag)
        presence.onSighting(
            sighting(handle, -60, advertised = tag, blinded = true, suppressor = BlePresenceTracker.Suppressor.None),
            now = 0,
        )
        presence.rekey(handle, "bbb", BlePresenceTracker.Suppressor.Proven)
        assertEquals(BlePresenceTracker.Suppressor.Proven, presence.snapshots(0).single().suppressor)
    }

    @Test
    fun aHandleTheCapLetsGoIsHandedToItsOwnerWithItsEntries() {
        val evicted = ArrayList<String>()
        val keys = FormKeys(cap = 2) { evicted += it }
        val first = keys.keyFor(tag)
        keys.keyFor(other)
        keys.keyFor(NodeId.fromBytes(BlindTag.tag(ByteArray(BlindTag.KEY_BYTES) { 5 }, slot = 1, restart = 0)))
        assertEquals("the least recently used, so its owner forgets its entries with it", listOf(first), evicted)
        assertNull(keys.existing(tag))
        assertEquals("the cap bounds the table", 2, keys.size)
        keys.clear()
        assertEquals(0, keys.size)
    }

    @Test
    fun noTagReachesReachableOrNearButAnAwareFlaggedOneWakesTheAwareSets() {
        val presence = BlePresenceTracker()
        val aware = AwareCandidateTracker()
        val handle = FormKeys().keyFor(tag)
        presence.onSighting(sighting(handle, -50, advertised = tag, blinded = true), now = 0)
        presence.onSighting(sighting("ccc", -50), now = 0)
        aware.note(handle, aware = true, oneM = true, now = 0)
        aware.note("ccc", aware = true, oneM = true, now = 0)
        val linked = Peer("ddd", 1, 0L)
        val published = ScanPlan.published(presence.snapshots(0), listOf(linked)) { aware.sets(0, it, emptyMap()) }
        assertEquals(setOf("ccc", "ddd"), published.reachable.map { it.nodeId }.toSet())
        assertEquals(setOf("ccc", "ddd"), published.near.map { it.nodeId }.toSet())
        assertEquals("the unresolved tag wakes a dormant Aware plane", setOf(handle, "ccc"), published.aware.near)
        assertTrue(handle in published.aware.heard)
    }

    @Test
    fun everyLineThatNamesADialsPeerShowsNoTag() {
        val keys = FormKeys()
        val handle = keys.keyFor(tag)
        val snap = snapshot(handle, tag)
        val dialKey = DialKeys.dialKey(snap, keys::keyFor, "AA:BB:CC:DD:EE:FF")
        val lines =
            listOf(
                BtLines.initiating(handle, 0x83, -60, "idle", 0, " via=1m"),
                BtLines.lonelyDial(handle, 180_000, -60, 15_000),
                BtLines.connectFailed(handle, "HANDSHAKE", 900, "idle", 0, 1, 10_000),
                BtLines.connectFailed(dialKey, "TIMEOUT", 12_000, "idle", 0, 2, 20_000),
                BtLines.promote(listOf(handle), emptyList(), setOf(dialKey, handle), "idle"),
                BtLines.coldHunt(handle, 30_000, -99),
                BtLines.coldHuntOver(handle, "backoff"),
                BtLines.coldFast(handle, 30_000, -99),
                "inFlight=${setOf(dialKey)} awareNear=${setOf(handle)}",
                snap.toString(),
                listOf(snap).toString(),
                AdvertOrder.Form(tag, blinded = true).toString(),
                BtLines.fieldPeer(handle),
            )
        lines.forEach { assertFalse(it, fullTag.containsMatchIn(it)) }
        // Clear ids stay whole, so the harnesses' `bt initiating to <id>` and `bt connect ok <id>` keep matching.
        val id = NodeId.derive("alice")
        assertTrue(BtLines.initiating(id, 0x83, -60, "idle", 0, "").startsWith("bt initiating to $id (psm "))
        assertTrue(BtLines.connectOk(id, 300).startsWith("bt connect ok $id durMs="))
        assertEquals(id, BtLines.fieldPeer(id))
    }

    @Test
    fun aContactsKeyOutranksASybilsClaimSoOurDialToTheContactStands() {
        val reader = Reader()
        reader.aliases.setKeys(mapOf(contact to ContactKey(contactKey)))
        val told = reader.link(sybil)
        assertEquals(ClaimOutcome.Contradicted, reader.aliases.onTagNext(told, sybil, contactsTag))
        val answered = reader.link(secondSybil)
        val dialed = AdvertOrder.Form(other, blinded = true)
        assertEquals(
            "a tag dial answered with the contact's tag",
            ClaimOutcome.Contradicted,
            reader.aliases.onDialed(answered, dialed, contactsTag, secondSybil),
        )
        reader.hear(contactsTag)
        assertEquals("both Sybils held, the contact is dialed", listOf(contact), reader.dialsTo(contactsTag))
        // A key's answer is held off only by the contact's own proof, so a link naming its id without one gains nothing.
        reader.link(contact)
        reader.unproven += contact
        assertEquals("an unproven link under the contact's id", listOf(contact), reader.dialsTo(contactsTag))
        reader.unproven -= contact
        assertEquals("the contact's proven link", emptyList<String>(), reader.dialsTo(contactsTag))
    }

    @Test
    fun aKeyThatArrivesAfterASybilsClaimFreesOurDialToTheContact() {
        val reader = Reader()
        val link = reader.link(sybil)
        assertEquals(ClaimOutcome.Pending, reader.aliases.onTagNext(link, sybil, contactsTag))
        reader.hear(contactsTag)
        assertEquals("no key yet, so it is a stranger's tag", emptyList<String>(), reader.dialsTo(contactsTag))
        reader.aliases.setKeys(mapOf(contact to ContactKey(contactKey)))
        reader.hear(contactsTag)
        assertEquals("the Sybil's link still held", listOf(contact), reader.dialsTo(contactsTag))
        assertEquals("the claim is dropped, not shadowed", 0, reader.aliases.counters().claims)
    }

    @Test
    fun aStrangersTagClaimedByTagNextHoldsOffOurDialOnlyWhileTheLinkIsHeld() {
        claimHoldsOffOurDialOnlyWhileTheLinkIsHeld { link -> aliases.onTagNext(link, sybil, tag) }
    }

    @Test
    fun aStrangersTagToldInATagDialsAnswerHoldsOffOurDialOnlyWhileTheLinkIsHeld() {
        // The Sybil's own tag dialed, another's told: the rotation edge's path, so the told tag is held as announced.
        claimHoldsOffOurDialOnlyWhileTheLinkIsHeld { link ->
            aliases.onDialed(link, AdvertOrder.Form(other, blinded = true), tag, sybil)
        }
    }

    /**
     * The residual ADR 2026-11.xp6d accepts, pinned as a cost and not as an outcome anyone wants: no key of ours names a
     * stranger's tag, so a linked Sybil's claim holds off the dial for as long as its link is held. [claim] makes it.
     */
    private fun claimHoldsOffOurDialOnlyWhileTheLinkIsHeld(claim: Reader.(Any) -> ClaimOutcome) {
        val reader = Reader()
        reader.hear(tag)
        val handle = reader.handle(tag)
        assertEquals("heard, unresolved: the order calls for the dial", listOf(handle), reader.dialsTo(tag))
        assertEquals(ClaimOutcome.Pending, reader.claim(reader.link(sybil)))
        assertEquals("announced and not yet heard, it is no claim yet", 0, reader.aliases.counters().claims)
        assertEquals(1, reader.aliases.counters().pending)
        assertEquals("so the dial stands", listOf(handle), reader.dialsTo(tag))
        reader.hear(tag)
        assertEquals("the bounded cost: held off while the claimant's link is held", emptyList<String>(), reader.dialsTo(tag))
        reader.endLink(sybil)
        // Attributed to the liar's id, but a dial goes by the form: to the address airing the tag.
        assertEquals("the link's end lifts it at once, with no new sighting", listOf(sybil), reader.dialsTo(tag))
        reader.hear(tag)
        assertEquals("the claim outlives the link for presence, not to hold off a dial", listOf(sybil), reader.dialsTo(tag))
    }

    @Test
    fun aSybilThatRelinksHoldsOffNoDialAndThatDialsProofVoidsItsClaim() {
        val reader = Reader()
        val first = reader.link(sybil)
        assertEquals(ClaimOutcome.Pending, reader.aliases.onTagNext(first, sybil, tag))
        reader.hear(tag)
        assertEquals("the claimant's own link held", emptyList<String>(), reader.dialsTo(tag))
        reader.link(sybil)
        reader.aliases.onLinkEnded(first)
        assertEquals("another link under the same proven id, no new sighting", listOf(sybil), reader.dialsTo(tag))
        reader.hear(tag)
        assertEquals("the old claim, heard again", listOf(sybil), reader.dialsTo(tag))
        // That dial reaches the address airing the tag, and its owner proves another id.
        val owners = reader.link(stranger)
        val dialed = AdvertOrder.Form(tag, blinded = true)
        assertEquals(ClaimOutcome.Recorded, reader.aliases.onDialed(owners, dialed, tag, stranger))
        val counters = reader.aliases.counters()
        assertEquals("the liar's claim is voided", 1L, counters.claimsVoided)
        assertEquals(1L, counters.proofMismatches)
        val answer = reader.aliases.resolve(dialed) as Resolution.Resolved
        assertEquals(stranger, answer.nodeId)
        assertSame("the owner's link holds the claim now", owners, answer.claimant)
        reader.hear(tag)
        assertFalse("the owner's sighting is held off by its own link", stranger in reader.dialsTo(tag))
        // The entry heard under the liar's id is not rewritten by the proof; it stops being a candidate when it lingers out.
        reader.now += PresenceConfig().reachableLingerMs + 1
        reader.hear(tag)
        assertEquals("nothing left to dial", emptyList<String>(), reader.dialsTo(tag))
    }

    @Test
    fun aRelinkedSybilThatAnnouncesAgainIsBackToTheBoundedCost() {
        // A relink opens a gap, it does not end the cost: the rate limit is per link, so the new link may claim at once,
        // and the dial goes only if it lands inside that gap. Past it this is the residual again, one tag per link.
        val reader = Reader()
        val first = reader.link(sybil)
        assertEquals(ClaimOutcome.Pending, reader.aliases.onTagNext(first, sybil, tag))
        reader.hear(tag)
        val second = reader.link(sybil)
        reader.aliases.onLinkEnded(first)
        assertEquals("the gap", listOf(sybil), reader.dialsTo(tag))
        assertEquals(ClaimOutcome.Pending, reader.aliases.onTagNext(second, sybil, tag))
        reader.hear(tag)
        assertEquals("its new link's own claim", emptyList<String>(), reader.dialsTo(tag))
        reader.endLink(sybil)
        assertEquals("and that claim goes the same way", listOf(sybil), reader.dialsTo(tag))
    }

    @Test
    fun twoSybilIdsClaimingOneTagVoidEachOtherAndNothingHoldsOffOurDial() {
        val reader = Reader()
        assertEquals(ClaimOutcome.Pending, reader.aliases.onTagNext(reader.link(sybil), sybil, tag))
        reader.hear(tag)
        assertEquals("one claimant", emptyList<String>(), reader.dialsTo(tag))
        assertEquals(ClaimOutcome.Contested, reader.aliases.onTagNext(reader.link(secondSybil), secondSybil, tag))
        assertEquals(Resolution.Unresolved, reader.aliases.resolve(AdvertOrder.Form(tag, blinded = true)))
        reader.hear(tag)
        assertEquals("both links still held", listOf(reader.handle(tag)), reader.dialsTo(tag))
    }

    @Test
    fun aSybilOnOneLinkHoldsOneClaimSoItHoldsOffOnePhoneAtATime() {
        val reader = Reader()
        val link = reader.link(sybil)
        assertEquals(ClaimOutcome.Pending, reader.aliases.onTagNext(link, sybil, tag))
        assertEquals(ClaimOutcome.RateLimited, reader.aliases.onTagNext(link, sybil, other))
        reader.hear(tag)
        reader.hear(other)
        assertEquals("the one it announced", emptyList<String>(), reader.dialsTo(tag))
        assertEquals("the one the rate limit refused", listOf(reader.handle(other)), reader.dialsTo(other))
        reader.now += AdvertAliases.Config().tagNextMinIntervalMs
        assertEquals(ClaimOutcome.Pending, reader.aliases.onTagNext(link, sybil, other))
        reader.hear(other)
        reader.hear(tag)
        assertEquals("the newer claim", emptyList<String>(), reader.dialsTo(other))
        assertEquals("the claim it replaced", listOf(reader.handle(tag)), reader.dialsTo(tag))
        assertEquals(1, reader.aliases.counters().claims)
    }

    private fun snapshot(
        key: String,
        advertised: String,
    ) = BlePresenceTracker.Snapshot(
        nodeId = key,
        protoVersion = 1,
        capabilities = 0L,
        psm = 0x83,
        digestCue = 0,
        smoothedRssi = -60.0,
        dwellMs = 0,
        lastSeenAgoMs = 0,
        advertised = advertised,
        blinded = true,
        suppressor = BlePresenceTracker.Suppressor.None,
    )

    private fun sighting(
        key: String,
        rssi: Int,
        advertised: String = key,
        blinded: Boolean = false,
        suppressor: BlePresenceTracker.Suppressor = BlePresenceTracker.Suppressor.Linked,
    ) = BlePresenceTracker.Sighting(
        nodeId = key,
        rssiDbm = rssi,
        protoVersion = 1,
        capabilities = 0L,
        psm = 0x83,
        digestCue = 0,
        advertised = advertised,
        blinded = blinded,
        suppressor = suppressor,
    )

    /**
     * A blinding phone's reader for the Sybil cases: the real [AdvertAliases], [BlindSightings], [BlePresenceTracker],
     * [FormKeys] and [DialPlan], on a virtual clock. Only what is private to `BluetoothMeshTransport` is mirrored: [hear]
     * stands in for `readBlind` with `rekeyForm`'s presence calls and `sight`'s `onSighting`, [links] for its `links`,
     * [unproven] for `provenLinks`, and [dialsTo] for `ordinaryCandidates`.
     */
    private inner class Reader {
        var now = 1_000_000L
        val aliases = AdvertAliases<Any>({ now }, { WALL_MS })
        val unproven = HashSet<String>()
        private val links = HashMap<String, Any>()
        private val presence = BlePresenceTracker()
        private val formKeys = FormKeys()
        private val mine = AdvertOrder.Mine(AdvertOrder.Form(TAG_ABOVE_ALL, blinded = true))

        fun handle(tag: String): String = formKeys.keyFor(tag)

        /** A new link held under [id], replacing any other. Its end is the caller's: [endLink], or `onLinkEnded` on a replaced one. */
        fun link(id: String): Any = Any().also { links[id] = it }

        fun endLink(id: String) {
            links.remove(id)?.let(aliases::onLinkEnded)
        }

        fun hear(tag: String) {
            val resolution = aliases.resolve(AdvertOrder.Form(tag, blinded = true))
            val attribution = checkNotNull(BlindSightings.attribute(resolution, handle(tag), replayed = false))
            when (attribution.history) {
                BlindSightings.History.CARRY -> {
                    presence.rekey(handle(tag), attribution.key, attribution.suppressor)
                }

                BlindSightings.History.DROP -> {
                    presence.forget(handle(tag))
                }

                BlindSightings.History.NONE -> {}
            }
            presence.onSighting(
                sighting(attribution.key, -60, advertised = tag, blinded = true, suppressor = attribution.suppressor),
                now,
            )
        }

        /** The per-peer keys a dial to [tag] is a candidate under now: empty when nothing would dial it. */
        fun dialsTo(tag: String): List<String> =
            DialPlan
                .ordinaryCandidates(mine, presence.snapshots(now), links.keys, emptySet(), links.keys - unproven, links::get)
                .filter { it.advertised == tag }
                .map { it.nodeId }
    }

    private companion object {
        const val WALL_MS = 1_800_000_000_000L

        /** Sorts above every tag, so the order has this phone dial each one. */
        const val TAG_ABOVE_ALL = "zzzzzzzzzzzzzzzzzzzzzzzzzz"
    }
}
