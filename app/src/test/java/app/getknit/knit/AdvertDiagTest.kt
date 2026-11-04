package app.getknit.knit

import app.getknit.knit.identity.NodeId
import app.getknit.knit.legal.repoFile
import app.getknit.knit.mesh.BleRotation
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.OwnPresenceKey
import app.getknit.knit.mesh.bluetooth.AdvertOrder
import app.getknit.knit.mesh.bluetooth.AdvertRotation
import app.getknit.knit.mesh.bluetooth.AdvertStatus
import app.getknit.knit.mesh.bluetooth.AdvertSwitch
import app.getknit.knit.mesh.bluetooth.AdvertWriter
import app.getknit.knit.mesh.bluetooth.BlePresenceTracker
import app.getknit.knit.mesh.bluetooth.ForcedRotation
import app.getknit.knit.mesh.bluetooth.ForcedRotation.Refusal
import app.getknit.knit.mesh.bluetooth.PsmRotation
import app.getknit.knit.mesh.bluetooth.TagChoice
import app.getknit.knit.mesh.bluetooth.TagSchedule
import app.getknit.knit.mesh.crypto.BlindTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The debug bridge's view of the blinded advert (ADR 2026-11.xp6d's step-6 amendment): [AdvertStatus] never carries a
 * tag, its hex, a key generation, a rotation cause or a tag source (every reply is mirrored to logcat with a time), hides
 * the PSM while blinded, never marks a handle linked and reports `iDial` as [AdvertOrder] decides it; [ForcedRotation]'s
 * refusal rule; [AdvertSwitch]'s verdict; `FORCED` counted; and the transport's `dialedByMe` dropped wherever a link's
 * form is (the wiring, pinned on the source as [BleEndOfWindowTest] pins its own).
 */
class AdvertDiagTest {
    private class Sink : AdvertWriter.Sink {
        override fun update(serviceData: ByteArray) = Unit

        override fun stop() = Unit

        override fun retire() = Unit
    }

    private class Store : TagSchedule.MarkStore {
        val marks = HashMap<Long, TagSchedule.Mark>()

        override fun read(generation: Long): TagSchedule.Mark? = marks[generation]

        override fun write(
            generation: Long,
            mark: TagSchedule.Mark,
        ) {
            marks[generation] = mark
        }
    }

    private val wall = 1_800_000_123_000L
    private val generation = 1_799_000_456_789L // a generation is minted from the wall clock: distinct from every time here
    private val key = OwnPresenceKey(generation, ByteArray(BlindTag.KEY_BYTES) { 5 })
    private val localId = NodeId.fromBytes(ByteArray(NodeId.BYTES) { (it * 3).toByte() })
    private val contactId = NodeId.fromBytes(ByteArray(NodeId.BYTES) { (it + 40).toByte() })
    private val clearId = NodeId.fromBytes(ByteArray(NodeId.BYTES) { (it + 90).toByte() })
    private val schedule = TagSchedule(Store())

    /** A tag the schedule chose under [key] for the slot of [wall]: a known tag under a known generation. */
    private val myTag = NodeId.fromBytes(BlindTag.tag(key.key, BlindTag.slotOf(wall), 0))
    private val contactTag = NodeId.fromBytes(BlindTag.tag(ByteArray(BlindTag.KEY_BYTES) { 6 }, BlindTag.slotOf(wall), 1))
    private val strangerTag = NodeId.fromBytes(BlindTag.tag(ByteArray(BlindTag.KEY_BYTES) { 7 }, BlindTag.slotOf(wall), 2))
    private val handle = "~zzzz…3"

    private fun sighting(
        nodeId: String,
        advertised: String,
        blinded: Boolean,
    ) = BlePresenceTracker.Snapshot(
        nodeId = nodeId,
        protoVersion = 1,
        capabilities = 0L,
        psm = 0x85,
        digestCue = 0,
        smoothedRssi = -60.0,
        dwellMs = 20_000,
        lastSeenAgoMs = 100,
        advertised = advertised,
        blinded = blinded,
    )

    private val sightings =
        listOf(
            sighting(contactId, contactTag, blinded = true), // a contact resolved by its key
            sighting(handle, strangerTag, blinded = true), // an unresolved stranger
            sighting(clearId, clearId, blinded = false), // an old build
        )

    private fun writerAiring(form: AdvertOrder.Form): AdvertWriter<Sink> {
        val writer = AdvertWriter<Sink>(wallMs = { wall }) { localId }
        val clear = AdvertWriter.Aired(Sink(), Sink(), AdvertOrder.Form.clear(localId), 0x81)
        writer.up(pendingTag = false, clear = clear, encode = ::payload, write = { _, _ -> })
        if (form.blinded) {
            val token = writer.begin()
            assertTrue(writer.install(token, { AdvertWriter.Aired(Sink(), Sink(), form, 0x9A) }, ::payload) { _, _ -> })
        }
        return writer
    }

    private fun payload(
        form: AdvertOrder.Form,
        psm: Int,
    ) = app.getknit.knit.mesh.bluetooth.BleAdvertPayload
        .advert(form.advertised, 0x0FL, 0L, psm, sideLive = false, gattPeers = false, aware = false, blinded = form.blinded)

    private fun status(
        writer: AdvertWriter<Sink>,
        mine: AdvertOrder.Form,
        rotation: AdvertRotation = AdvertRotation(),
        linkedIds: Set<String> = setOf(contactId, clearId),
    ) = AdvertStatus.of(
        aired = writer.aired(),
        radioUp = writer.isUp(),
        pending = writer.pending(),
        blinding = mine.blinded,
        extendedAdvertising = true,
        keyHeld = true,
        turn = rotation.view(),
        mine = mine,
        sightings = sightings,
        linkedIds = linkedIds,
        links =
            listOf(
                AdvertStatus.Link(contactId, proven = true, dialedByMe = true, tagAware = true),
                AdvertStatus.Link(clearId, proven = false, dialedByMe = false, tagAware = false),
            ),
    )

    /**
     * Every cause and tag-source name, matched as printed (an enum's `toString`). None collides with a legitimate value:
     * the reply's values are ids, handles, `clear` / `tag`, numbers and booleans, and its keys are lower camel case.
     */
    private val enumNames =
        (AdvertRotation.Cause.entries + TagChoice.Source.entries + BleRotation.entries).map { it.name }.distinct()

    /** Words whose presence in any key or value, any case, would mean a cause, a source or a key's age got in. */
    private val forbiddenWords = listOf("cause", "source", "generation", "resolvable", "stale")

    private fun hex(tag: String) = NodeId.toBytes(tag).joinToString("") { "%02x".format(it) }

    @Test
    fun theStatusCarriesNoTagNoGenerationNoCauseAndNoSource() {
        // The tag on the air came from the schedule under a known generation; a key change then aired a KEY_MOVED tag
        // (and its rotation counted FRESH): none of it may reach a reply.
        val slotted = TagChoice.choose(schedule, key, key, announced = false, wallMs = wall)
        assertEquals(TagChoice.Source.SLOTTED, slotted.source)
        assertEquals(myTag, slotted.form.advertised)
        val moved = TagChoice.choose(schedule, key, OwnPresenceKey(generation + 1, key.key), announced = false, wallMs = wall)
        assertEquals(TagChoice.Source.KEY_MOVED, moved.source)
        val metrics = MeshMetrics().also { it.onBleRotation(BleRotation.FRESH) }
        assertEquals(1L, metrics.snapshot().bluetooth.bleRotationsByCause[BleRotation.FRESH])

        for (form in listOf(slotted.form, moved.form)) {
            val rotation = AdvertRotation().also { it.onRotated(wall) }
            val s = status(writerAiring(form), form, rotation)
            val printed = listOf(s.toString(), s.fields().toString())
            val secrets =
                listOf(myTag, form.advertised, contactTag, strangerTag, hex(myTag), hex(form.advertised), hex(contactTag)) +
                    listOf(generation.toString(), (generation + 1).toString()) +
                    enumNames
            for (text in printed) {
                for (secret in secrets) assertFalse("`$secret` in $text", secret in text)
                for (word in forbiddenWords) assertFalse("`$word` in $text", text.contains(word, ignoreCase = true))
                // No prefix of a tag past `zzzz`.
                for (tag in listOf(form.advertised, contactTag, strangerTag)) assertFalse(text, tag.take(5) in text)
            }
            assertEquals("tag", s.form)
            assertNull("no PSM while blinded: a PSM sequence ties one tag to the next", s.psm)
            assertEquals(2L, s.epoch) // the clear bring-up, then the rotation
            assertEquals(wall, s.rotatedAtMs)
            assertEquals((BlindTag.slotOf(wall) + 1) * BlindTag.SLOT_MS, s.nextEdgeAtMs)
        }
    }

    @Test
    fun aClearAdvertShowsItsPsmAndPeersReadTheirOrderAndLinks() {
        val clear = AdvertOrder.Form.clear(localId)
        val s = status(writerAiring(clear), clear)
        assertEquals("clear", s.form)
        assertEquals(0x81, s.psm)
        assertEquals(1L, s.epoch)
        assertNull("nothing blinded on the air: no edge", s.nextEdgeAtMs)
        val byPeer = s.peers.associateBy { it.peer }
        assertEquals(setOf(contactId, handle, clearId), byPeer.keys)
        assertTrue(byPeer.getValue(contactId).resolved && byPeer.getValue(contactId).linked)
        assertFalse("a handle is never resolved", byPeer.getValue(handle).resolved)
        assertTrue(byPeer.getValue(clearId).resolved && byPeer.getValue(clearId).linked)
        // iDial is AdvertOrder's on my current form against theirs: a clear phone never dials a tag.
        for (p in s.peers) {
            val theirs = sightings.single { it.nodeId == p.peer }.let { AdvertOrder.Form(it.advertised, it.blinded) }
            assertEquals(p.peer, AdvertOrder.iDial(clear, theirs), p.iDial)
        }
        assertFalse(byPeer.getValue(contactId).iDial)
        assertEquals(localId > clearId, byPeer.getValue(clearId).iDial)
    }

    @Test
    fun aHandleIsNeverLinkedEvenWhenTheLinkedSetNamesIt() {
        val mine = AdvertOrder.Form(myTag, blinded = true)
        val s = status(writerAiring(mine), mine, linkedIds = setOf(handle, contactId))
        val stranger = s.peers.single { it.peer == handle }
        assertFalse(stranger.linked)
        assertFalse(stranger.resolved)
        // While blinding I dial every clear advert, and between two tags the larger dials.
        assertTrue(s.peers.single { it.peer == clearId }.iDial)
        assertEquals(myTag > contactTag, s.peers.single { it.peer == contactId }.iDial)
        assertEquals(myTag > strangerTag, stranger.iDial)
    }

    @Test
    fun aSightingKeyedByItsTagIsShownRedacted() {
        val mine = AdvertOrder.Form(myTag, blinded = true)
        val leaky = listOf(sighting(strangerTag, strangerTag, blinded = true))
        val s =
            AdvertStatus.of(
                writerAiring(mine).aired(),
                true,
                false,
                true,
                true,
                true,
                AdvertRotation().view(),
                mine,
                leaky,
                setOf(strangerTag),
                emptyList(),
            )
        val peer = s.peers.single()
        assertEquals("~zzzz…", peer.peer)
        assertFalse(peer.resolved || peer.linked)
        assertFalse(strangerTag in s.fields().toString())
    }

    @Test
    fun theFieldsKeepTheNamesTheHarnessesRead() {
        val mine = AdvertOrder.Form(myTag, blinded = true)
        val f = status(writerAiring(mine), mine).fields()
        // Exactly these: a new field fails here until a review has asked whether a dated reply may carry it.
        assertEquals(
            listOf(
                "extendedAdvertising",
                "keyHeld",
                "radioUp",
                "pending",
                "blinding",
                "form",
                "epoch",
                "rotatedAtMs",
                "nextEdgeAtMs",
                "announced",
                "holding",
                "psm",
                "peers",
                "links",
            ),
            f.keys.toList(),
        )
        for (key in f.keys) for (word in forbiddenWords) assertFalse(key, key.contains(word, ignoreCase = true))

        @Suppress("UNCHECKED_CAST")
        val peer = (f.getValue("peers") as List<Map<String, Any?>>).first()
        assertEquals(setOf("peer", "resolved", "blinded", "iDial", "linked"), peer.keys)

        @Suppress("UNCHECKED_CAST")
        val link = (f.getValue("links") as List<Map<String, Any?>>).first()
        assertEquals(setOf("nodeId", "proven", "dialedByMe", "tagAware"), link.keys)
    }

    // --- ADVERT_ROTATE's refusal rule ---

    @Suppress("LongParameterList")
    private fun refusal(
        radioUp: Boolean = true,
        pending: Boolean = false,
        blinding: Boolean = true,
        airedBlinded: Boolean = true,
        settleLeftMs: Long = 0,
        announced: Boolean = false,
        holding: Boolean = false,
        retiredServerOpen: Boolean = false,
        sinceInstallMs: Long? = PsmRotation.GRACE_MS,
        formSentRecently: Boolean = false,
    ) = ForcedRotation.refusal(
        radioUp,
        pending,
        blinding,
        airedBlinded,
        settleLeftMs,
        announced,
        holding,
        retiredServerOpen,
        sinceInstallMs,
        formSentRecently,
    )

    @Test
    fun aForcedRotationRunsOnlyWithATagOnTheAirAndNothingInItsWay() {
        assertNull(refusal())
        assertNull("no install yet this process", refusal(sinceInstallMs = null))
        assertEquals(Refusal.RADIO_DOWN, refusal(radioUp = false))
        assertEquals(Refusal.PENDING, refusal(pending = true))
        assertEquals("the switch is off", Refusal.CLEAR, refusal(blinding = false))
        // A deferred flip to on: blinding already, the clear form still on the air. A forced rotation would skip its settle.
        assertEquals(Refusal.CLEAR, refusal(airedBlinded = false))
        assertEquals(Refusal.SETTLING, refusal(settleLeftMs = 1))
        assertEquals(Refusal.ANNOUNCED, refusal(announced = true))
        assertEquals(Refusal.HOLDING, refusal(holding = true))
        assertEquals(Refusal.GRACE, refusal(retiredServerOpen = true))
        assertEquals("a second call inside 30 s", Refusal.GRACE, refusal(sinceInstallMs = PsmRotation.GRACE_MS - 1))
        assertEquals(30_000L, PsmRotation.GRACE_MS)
        // 30 s to 2 min after a rotation that told a linked peer: its post-rotation send would be throttled, and that peer
        // would keep the old form as the link's. Checked after the grace.
        assertEquals(Refusal.THROTTLED, refusal(formSentRecently = true))
        assertEquals(Refusal.GRACE, refusal(sinceInstallMs = 1, formSentRecently = true))
        // The order: the radio first, then what is on the air, then what would be spent.
        assertEquals(Refusal.RADIO_DOWN, refusal(radioUp = false, pending = true, blinding = false))
        assertEquals(Refusal.CLEAR, refusal(blinding = false, settleLeftMs = 5, announced = true, retiredServerOpen = true))
    }

    @Test
    fun aSecondRotationInsideTheReceiversLimitIsThrottledForALinkThatWasTold() {
        // The sends' own record is what the refusal reads (`TagNextSends.sentWithin`, on the worker).
        val sends =
            app.getknit.knit.mesh.bluetooth
                .TagNextSends<String>()
        val kind = app.getknit.knit.mesh.bluetooth.TagNextSends.Kind.CURRENT
        assertFalse("nothing sent yet", sends.sentWithin(wall))
        sends.decide("peer", kind, eligible = false, hasKey = true, wallMs = wall, announceDueAt = null)
        assertFalse("an ineligible link was told nothing", sends.sentWithin(wall))
        sends.decide("peer", kind, eligible = true, hasKey = true, wallMs = wall, announceDueAt = null)
        val limit = app.getknit.knit.mesh.bluetooth.TagNextSends.MIN_INTERVAL_MS
        assertTrue(sends.sentWithin(wall + PsmRotation.GRACE_MS))
        assertTrue(sends.sentWithin(wall + limit - 1))
        assertFalse(sends.sentWithin(wall + limit))
        sends.forget("peer")
        assertFalse("an ended link no longer counts", sends.sentWithin(wall + 1))
    }

    @Test
    fun aForcedRotationIsCountedAsItsOwnCause() {
        assertEquals("FORCED", AdvertRotation.Cause.FORCED.name)
        val metrics = MeshMetrics()
        metrics.onBleRotation(BleRotation.valueOf(AdvertRotation.Cause.FORCED.name))
        assertEquals(mapOf(BleRotation.FORCED to 1L), metrics.snapshot().bluetooth.bleRotationsByCause)
    }

    // --- ADVERT's switch wait ---

    @Suppress("LongParameterList")
    private fun verdict(
        want: Boolean,
        markStore: Boolean = true,
        seen: Boolean = true,
        radioUp: Boolean = true,
        pending: Boolean = false,
        airedBlinded: Boolean? = want,
        blinding: Boolean = want,
        extendedAdvertising: Boolean? = true,
        timedOut: Boolean = false,
    ) = AdvertSwitch.verdict(markStore, want, seen, radioUp, pending, airedBlinded, blinding, extendedAdvertising, timedOut)

    @Test
    fun theSwitchIsAppliedOnlyOnceTheFormOnTheAirIsWhatTheWriterShouldAir() {
        assertEquals(AdvertSwitch.Result(true), verdict(want = false))
        assertEquals(AdvertSwitch.Result(true), verdict(want = true))
        assertNull("the transport has not collected it yet", verdict(want = false, seen = false))
        assertEquals(AdvertSwitch.Result(false, AdvertSwitch.Why.TIMEOUT), verdict(want = false, seen = false, timedOut = true))
        assertEquals(AdvertSwitch.Result(false, AdvertSwitch.Why.RADIO_DOWN), verdict(want = true, radioUp = false))
        assertNull("the flip has not installed yet", verdict(want = false, airedBlinded = true))
        assertEquals(AdvertSwitch.Result(false, AdvertSwitch.Why.TIMEOUT), verdict(want = false, airedBlinded = true, timedOut = true))
        assertNull(verdict(want = true, pending = true))
        assertEquals(AdvertSwitch.Result(false, AdvertSwitch.Why.PENDING), verdict(want = true, pending = true, timedOut = true))
        // Asked on, but the writer cannot blind here: clear on the air is what it should air, and the reason is named.
        assertEquals(
            AdvertSwitch.Result(false, AdvertSwitch.Why.NO_EXTENDED),
            verdict(want = true, airedBlinded = false, blinding = false, extendedAdvertising = null),
        )
        assertEquals(
            AdvertSwitch.Result(false, AdvertSwitch.Why.NO_EXTENDED),
            verdict(want = true, airedBlinded = false, blinding = false, extendedAdvertising = false),
        )
        assertEquals(
            AdvertSwitch.Result(false, AdvertSwitch.Why.NO_MARK_STORE),
            verdict(want = true, airedBlinded = false, blinding = false),
        )
        // With no mark store the transport never collects the switch, so it is answered at once, never a 6 s timeout.
        for (want in listOf(true, false)) {
            assertEquals(
                AdvertSwitch.Result(false, AdvertSwitch.Why.NO_MARK_STORE),
                verdict(want = want, markStore = false, seen = false, airedBlinded = false, blinding = false),
            )
        }
    }

    // --- the transport's wiring, on its source ---

    private val transport = repoFile("app/src/main/java/app/getknit/knit/mesh/bluetooth/BluetoothMeshTransport.kt").readText()

    @Test
    fun dialedByMeIsDroppedWhereverALinksFormIs() {
        // Keyed by link identity: a replaced link (registerLink's prev) and a torn-down one (teardownLink) both drop it, so
        // no successor reports its predecessor's role.
        // (A sighting's put that lost a race with the teardown is undone with `linkForms.remove(it)`: no link end.)
        val removals = Regex("""linkForms\.remove\((\w+)\)""").findAll(transport).map { it.groupValues[1] }.toSet()
        assertEquals("registerLink's prev, teardownLink's fl, the sighting's undo", setOf("prev", "fl", "it"), removals)
        for (arg in listOf("prev", "fl")) assertTrue("dialedByMe.remove($arg)", "dialedByMe.remove($arg)" in transport)
        assertTrue("only a dial registers as dialed", "if (dialStartedAt != null) dialedByMe.add(framed)" in transport)
        // The diag lambdas are a debug build's alone, and go at stop.
        assertTrue("if (BuildConfig.DEBUG) bindDebugDiags()" in transport)
        assertTrue("AdvertDiag.status = ::advertStatus" in transport.substringAfter("private fun bindDebugDiags()"))
        assertTrue("unbindDebugDiags()" in transport.substringAfter("override fun stop()").substringBefore("tearDownRadio()"))
        assertTrue("AdvertDiag.status = null" in transport.substringAfter("private fun unbindDebugDiags()"))
    }
}
