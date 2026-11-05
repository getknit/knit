package app.getknit.knit

import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.bluetooth.AdvertOrder
import app.getknit.knit.mesh.bluetooth.AwareCandidateTracker
import app.getknit.knit.mesh.bluetooth.BlePresenceTracker
import app.getknit.knit.mesh.bluetooth.BlindQueue
import app.getknit.knit.mesh.bluetooth.BlindSightings
import app.getknit.knit.mesh.bluetooth.BtLines
import app.getknit.knit.mesh.bluetooth.DialKeys
import app.getknit.knit.mesh.bluetooth.FormKeys
import app.getknit.knit.mesh.bluetooth.LinkEventStamps
import app.getknit.knit.mesh.bluetooth.ScanPlan
import app.getknit.knit.mesh.crypto.BlindTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reader's pure pieces around the transport's worker (ADR 2026-11.xp6d, step 5a): the handle an unresolved form is
 * keyed and logged under, the conflating hop to the worker, the link-event stamps that keep a late resolve from undoing
 * a link event, the published sets, and the `bt …` lines a tag's key can reach.
 */
class BlindReaderTest {
    private val tag = NodeId.fromBytes(BlindTag.tag(ByteArray(BlindTag.KEY_BYTES) { 3 }, slot = 1, restart = 0))
    private val other = NodeId.fromBytes(BlindTag.tag(ByteArray(BlindTag.KEY_BYTES) { 4 }, slot = 1, restart = 0))

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
}
