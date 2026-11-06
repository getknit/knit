package app.getknit.knit

import app.getknit.knit.identity.NodeId
import app.getknit.knit.legal.repoFile
import app.getknit.knit.mesh.BleRotation
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.OwnPresenceKey
import app.getknit.knit.mesh.bluetooth.AdvertAliases
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
import java.lang.reflect.Modifier

/**
 * The debug bridge's view of the blinded advert (ADR 2026-11.xp6d's step-6 amendment, and the maintainer's 2026-11-04
 * call that debug replies carry the diagnostics): [AdvertStatus] reports the generations, the last install's cause, tag
 * source, `resolvable` and `stale`, the PSM and which link a handle is, and never a tag, its hex or a prefix past `zzzz`;
 * `iDial` as [AdvertOrder] decides it; [ForcedRotation]'s refusal rule; [AdvertSwitch]'s verdict; `FORCED` counted; and
 * the transport's `dialedByMe` dropped wherever a link's form is (the wiring, pinned on the source as [BleEndOfWindowTest]
 * pins its own).
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
    private val generation = 1_799_000_456_789L
    private val key = OwnPresenceKey(generation, ByteArray(BlindTag.KEY_BYTES) { 5 })
    private val localId = NodeId.fromBytes(ByteArray(NodeId.BYTES) { (it * 3).toByte() })
    private val contactId = NodeId.fromBytes(ByteArray(NodeId.BYTES) { (it + 40).toByte() })
    private val clearId = NodeId.fromBytes(ByteArray(NodeId.BYTES) { (it + 90).toByte() })
    private val strangerId = NodeId.fromBytes(ByteArray(NodeId.BYTES) { (it + 120).toByte() })
    private val schedule = TagSchedule(Store()) { localId }

    /** [owner]'s tag under [key] for the slot of [wall]. */
    private fun tagOf(
        key: ByteArray,
        owner: String,
        restart: Int,
    ): String = NodeId.fromBytes(BlindTag.tag(key, NodeId.toBytes(owner), BlindTag.slotOf(wall), restart))

    /** A tag the schedule chose under [key] for the slot of [wall]: a known tag under a known generation. */
    private val myTag = tagOf(key.key, localId, 0)
    private val contactTag = tagOf(ByteArray(BlindTag.KEY_BYTES) { 6 }, contactId, 1)
    private val strangerTag = tagOf(ByteArray(BlindTag.KEY_BYTES) { 7 }, strangerId, 2)
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

    private val links =
        listOf(
            AdvertStatus.Link(contactId, proven = true, dialedByMe = true, tagAware = true, form = AdvertOrder.Form(contactTag, true)),
            AdvertStatus.Link(clearId, proven = false, dialedByMe = false, tagAware = false, form = AdvertOrder.Form.clear(clearId)),
        )

    private fun writerAiring(form: AdvertOrder.Form): AdvertWriter<Sink> {
        val writer = AdvertWriter<Sink>(wallMs = { wall }) { localId }
        val clear = AdvertWriter.Aired(Sink(), Sink(), AdvertOrder.Form.clear(localId), 0x81)
        writer.up(pendingTag = false, clear = clear, encode = ::payload, write = { _, _ -> })
        if (form.blinded) {
            val token = writer.begin()
            assertTrue(writer.install(token, { AdvertWriter.Aired(Sink(), Sink(), form, 0x9A) }, ::payload, write = { _, _ -> }))
        }
        return writer
    }

    private fun payload(
        form: AdvertOrder.Form,
        psm: Int,
    ) = app.getknit.knit.mesh.bluetooth.BleAdvertPayload
        .advert(form.advertised, 0x0FL, 0L, psm, sideLive = false, gattPeers = false, aware = false, blinded = form.blinded)

    @Suppress("LongParameterList")
    private fun status(
        writer: AdvertWriter<Sink>,
        mine: AdvertOrder.Form,
        rotation: AdvertRotation = AdvertRotation(),
        install: AdvertStatus.Install? = null,
        links: List<AdvertStatus.Link> = this.links,
        aliases: AdvertAliases.Counters = AdvertAliases<String>({ 0L }, { wall }).counters(),
        formHandles: Int = 0,
    ) = AdvertStatus.of(
        aired = writer.aired(),
        radioUp = writer.isUp(),
        pending = writer.pending(),
        blinding = mine.blinded,
        extendedAdvertising = true,
        keyHeld = true,
        keyGeneration = generation,
        install = install,
        turn = rotation.view(),
        mine = mine,
        aliases = aliases,
        formHandles = formHandles,
        sightings = sightings,
        links = links,
    )

    /** What the transport's `afterInstall` keeps for a blinded install made from [choice], stamped [installs]. */
    private fun installOf(
        cause: AdvertRotation.Cause,
        choice: TagChoice.Choice,
        installs: Long = 2,
    ) = AdvertStatus.Install(installs, cause, choice.generation, choice.source, choice.resolvable, choice.stale)

    private fun hex(tag: String) = NodeId.toBytes(tag).joinToString("") { "%02x".format(it) }

    /** The standing limit: no tag, no tag's hex, no prefix of one past `zzzz`, in either printed form. */
    private fun assertNoTag(
        s: AdvertStatus,
        vararg tags: String,
    ) {
        for (text in listOf(s.toString(), s.fields().toString())) {
            for (tag in tags) {
                assertFalse("a tag in $text", tag in text)
                assertFalse("a tag's hex in $text", hex(tag) in text)
                assertFalse("a tag's prefix past zzzz in $text", tag.take(5) in text)
            }
        }
    }

    @Test
    fun aSlottedInstallReportsItsCauseSourceAndGenerationsAndNoTag() {
        val slotted = TagChoice.choose(schedule, key, key, announced = false, wallMs = wall)
        assertEquals(TagChoice.Source.SLOTTED, slotted.source)
        assertEquals(myTag, slotted.form.advertised)
        val rotation = AdvertRotation().also { it.onRotated(wall) }
        val s =
            status(
                writerAiring(slotted.form),
                slotted.form,
                rotation,
                installOf(AdvertRotation.Cause.SLOT, slotted),
            )
        assertEquals("tag", s.form)
        assertEquals(generation, s.keyGeneration)
        assertEquals(generation, s.airedGeneration)
        assertEquals("SLOT", s.lastCause)
        assertEquals("SLOTTED", s.source)
        assertEquals(true, s.resolvable)
        assertEquals(false, s.stale)
        assertEquals("the PSM, blinded too: a debug read", 0x9A, s.psm)
        assertEquals(2L, s.epoch) // the clear bring-up, then the rotation
        assertEquals(wall, s.rotatedAtMs)
        assertEquals((BlindTag.slotOf(wall) + 1) * BlindTag.SLOT_MS, s.nextEdgeAtMs)
        val f = s.fields()
        assertEquals(generation, f["keyGeneration"])
        assertEquals("SLOTTED", f["source"])
        assertNoTag(s, myTag, contactTag, strangerTag)
    }

    @Test
    fun aKeyMovedInstallReportsARandomUnresolvableTagUnderNoGeneration() {
        val moved = TagChoice.choose(schedule, key, OwnPresenceKey(generation + 1, key.key), announced = false, wallMs = wall)
        assertEquals(TagChoice.Source.KEY_MOVED, moved.source)
        val s =
            status(
                writerAiring(moved.form),
                moved.form,
                AdvertRotation().also { it.onRotated(wall) },
                installOf(AdvertRotation.Cause.FRESH, moved),
            )
        assertEquals("tag", s.form)
        assertEquals(generation, s.keyGeneration)
        assertNull("a random tag was made under no generation", s.airedGeneration)
        assertEquals("FRESH", s.lastCause)
        assertEquals("KEY_MOVED", s.source)
        assertEquals(false, s.resolvable)
        assertEquals(false, s.stale)
        assertNoTag(s, moved.form.advertised, myTag, contactTag, strangerTag)
    }

    @Test
    fun aClearInstallHasACauseAndNoTagFacts() {
        val clear = AdvertOrder.Form.clear(localId)
        val s = status(writerAiring(clear), clear, install = AdvertStatus.Install(1, AdvertRotation.Cause.FLIP, null, null, null, null))
        assertEquals("clear", s.form)
        assertEquals(0x81, s.psm)
        assertEquals(1L, s.epoch)
        assertEquals("FLIP", s.lastCause)
        assertNull(s.source)
        assertNull(s.resolvable)
        assertNull(s.stale)
        assertNull(s.airedGeneration)
        assertNull("nothing blinded on the air: no edge", s.nextEdgeAtMs)
        assertNull("nothing installed by a rotation yet", status(writerAiring(clear), clear).lastCause)
        assertNoTag(s, contactTag, strangerTag)
    }

    @Test
    fun anInstallsFactsAreReportedOnlyWithTheSnapshotTheyDescribe() {
        // The clear bring-up stamped 1 on the main thread; its worker task has not recorded yet, so the facts held are an
        // earlier install's (stamped 0 here: a tag's), or a rotation's stamped 2 against a snapshot stamped 1.
        val slotted = TagChoice.choose(schedule, key, key, announced = false, wallMs = wall)
        val clear = AdvertOrder.Form.clear(localId)
        for (stale in listOf(installOf(AdvertRotation.Cause.SLOT, slotted, installs = 0), installOf(AdvertRotation.Cause.SLOT, slotted))) {
            val s = status(writerAiring(clear), clear, install = stale)
            assertEquals(1L, s.epoch)
            assertEquals("clear", s.form)
            for (field in listOf(s.lastCause, s.source, s.resolvable, s.stale, s.airedGeneration)) {
                assertNull("in flight: ${stale.installs} against 1", field)
            }
            assertEquals("the key's own generation is no install's", generation, s.keyGeneration)
        }
        // The same facts against the snapshot they describe are reported.
        val tagged = status(writerAiring(slotted.form), slotted.form, install = installOf(AdvertRotation.Cause.SLOT, slotted))
        assertEquals("SLOT", tagged.lastCause)
        assertEquals(generation, tagged.airedGeneration)
    }

    @Test
    fun aLateBringUpTaskNeverUndoesALaterInstall() {
        // `upClear` stamps on the main thread and records on the worker later; a worker rotation in between records first.
        val rotated = AdvertStatus.Install(5, AdvertRotation.Cause.FLIP, generation, TagChoice.Source.SLOTTED, true, false)
        assertFalse(
            "a later install was recorded: the clear task leaves its facts, turn and own tags",
            AdvertStatus.Install.mayRecord(rotated, 4),
        )
        assertFalse("the same install twice", AdvertStatus.Install.mayRecord(rotated, 5))
        assertTrue("nothing later: the clear bring-up records", AdvertStatus.Install.mayRecord(rotated, 6))
        assertTrue("nothing recorded yet", AdvertStatus.Install.mayRecord(null, 1))
    }

    @Test
    fun peersReadTheirOrderAndWhichLinkTheyAre() {
        val clear = AdvertOrder.Form.clear(localId)
        val s = status(writerAiring(clear), clear)
        val byPeer = s.peers.associateBy { it.peer }
        assertEquals(setOf(contactId, handle, clearId), byPeer.keys)
        assertTrue(byPeer.getValue(contactId).resolved && byPeer.getValue(contactId).linked)
        assertEquals(contactId, byPeer.getValue(contactId).linkedAs)
        assertFalse("a handle is never resolved", byPeer.getValue(handle).resolved)
        assertFalse("no link has the stranger's form", byPeer.getValue(handle).linked)
        assertTrue(byPeer.getValue(clearId).resolved && byPeer.getValue(clearId).linked)
        // iDial is AdvertOrder's on my current form against theirs: a clear phone never dials a tag.
        for (p in s.peers) {
            val theirs = sightings.single { it.nodeId == p.peer }.let { AdvertOrder.Form(it.advertised, it.blinded) }
            assertEquals(p.peer, AdvertOrder.iDial(clear, theirs), p.iDial)
        }
        assertFalse(byPeer.getValue(contactId).iDial)
        assertEquals(localId > clearId, byPeer.getValue(clearId).iDial)
        assertEquals(listOf(true, false), s.links.map { it.blinded })
    }

    @Test
    fun aHandleIsLinkedAsTheLinkWhoseFormIsItsTag() {
        // A stranger linked before its tag resolved: the link's known form is the handle's tag.
        val mine = AdvertOrder.Form(myTag, blinded = true)
        val strangerLink =
            AdvertStatus.Link(strangerId, proven = true, dialedByMe = false, tagAware = true, form = AdvertOrder.Form(strangerTag, true))
        val s = status(writerAiring(mine), mine, links = links + strangerLink)
        val stranger = s.peers.single { it.peer == handle }
        assertFalse(stranger.resolved)
        assertTrue(stranger.linked)
        assertEquals(strangerId, stranger.linkedAs)
        // While blinding I dial every clear advert, and between two tags the larger dials.
        assertTrue(s.peers.single { it.peer == clearId }.iDial)
        assertEquals(myTag > contactTag, s.peers.single { it.peer == contactId }.iDial)
        assertEquals(myTag > strangerTag, stranger.iDial)
        assertNoTag(s, myTag, contactTag, strangerTag)
    }

    @Test
    fun aSightingKeyedByItsTagIsShownRedacted() {
        val mine = AdvertOrder.Form(myTag, blinded = true)
        val leaky = listOf(sighting(strangerTag, strangerTag, blinded = true))
        val s =
            AdvertStatus.of(
                aired = writerAiring(mine).aired(),
                radioUp = true,
                pending = false,
                blinding = true,
                extendedAdvertising = true,
                keyHeld = true,
                keyGeneration = generation,
                install = null,
                turn = AdvertRotation().view(),
                mine = mine,
                aliases = AdvertAliases<String>({ 0L }, { wall }).counters(),
                formHandles = 0,
                sightings = leaky,
                links = emptyList(),
            )
        val peer = s.peers.single()
        assertEquals("~zzzz…", peer.peer)
        assertFalse(peer.resolved || peer.linked)
        assertNoTag(s, strangerTag, myTag)
    }

    @Test
    fun theFieldsKeepTheNamesTheHarnessesRead() {
        val mine = AdvertOrder.Form(myTag, blinded = true)
        val f = status(writerAiring(mine), mine).fields()
        // Exactly these: a new field fails here until it has been reviewed (still never a key or a tag).
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
                "keyGeneration",
                "airedGeneration",
                "lastCause",
                "source",
                "resolvable",
                "stale",
                "nextEdgeAtMs",
                "announced",
                "holding",
                "psm",
                "aliases",
                "peers",
                "links",
            ),
            f.keys.toList(),
        )

        @Suppress("UNCHECKED_CAST")
        val peer = (f.getValue("peers") as List<Map<String, Any?>>).first()
        assertEquals(listOf("peer", "resolved", "blinded", "iDial", "linked", "linkedAs"), peer.keys.toList())

        @Suppress("UNCHECKED_CAST")
        val link = (f.getValue("links") as List<Map<String, Any?>>).first()
        assertEquals(listOf("nodeId", "proven", "dialedByMe", "tagAware", "blinded"), link.keys.toList())
    }

    @Test
    fun everyCountReachesTheReplyUnderItsOwnName() {
        // Every field a distinct value: a trial reading a swapped column would read the wrong table.
        val ctor = AdvertAliases.Counters::class.java.declaredConstructors.single { !it.isSynthetic }
        val distinct =
            ctor.parameterTypes.mapIndexed<Class<*>, Any> { i, type ->
                if (type == Long::class.javaPrimitiveType) i + 1L else i + 1
            }
        val counts = ctor.newInstance(*distinct.toTypedArray()) as AdvertAliases.Counters
        val mine = AdvertOrder.Form(myTag, blinded = true)
        val reply = status(writerAiring(mine), mine, aliases = counts).fields().getValue("aliases") as Map<*, *>
        for (field in AdvertAliases.Counters::class.java.declaredFields.filter { !Modifier.isStatic(it.modifiers) }) {
            field.isAccessible = true
            assertEquals(field.name, field.get(counts), reply[field.name])
        }
    }

    @Test
    fun theAliasesFieldCarriesEveryCountAndNoKeyOrTag() {
        val contactKey = ByteArray(BlindTag.KEY_BYTES) { 6 }
        val reader = AdvertAliases<String>({ 1_000L }, { wall })
        reader.setKeys(mapOf(contactId to AdvertAliases.ContactKey(contactKey)))
        reader.onAnswered("answered", strangerTag, strangerId)
        val announced = tagOf(ByteArray(BlindTag.KEY_BYTES) { 8 }, clearId, 3)
        reader.onTagNext("announced", clearId, announced)
        val mine = AdvertOrder.Form(myTag, blinded = true)
        val s = status(writerAiring(mine), mine, aliases = reader.counters(), formHandles = 7)

        @Suppress("UNCHECKED_CAST")
        val aliases = s.fields().getValue("aliases") as Map<String, Number>
        // By reflection: a count added to Counters fails here until the reply carries it.
        val counted =
            AdvertAliases.Counters::class.java.declaredFields
                .filter { !Modifier.isStatic(it.modifiers) }
                .map { it.name }
        assertEquals((counted + "formHandles").toSet(), aliases.keys)
        assertEquals(1, aliases["hints"])
        assertEquals(2, aliases["heldLinks"])
        assertEquals(1, aliases["lastTagNext"])
        assertEquals(1, aliases["pending"])
        assertEquals(1, aliases["tableContacts"])
        assertEquals(7, aliases["formHandles"])
        assertNoTag(s, myTag, contactTag, strangerTag, announced)
        val printed = s.toString() + s.fields()
        assertFalse("a key's hex in $printed", contactKey.joinToString("") { "%02x".format(it) } in printed)
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

    @Test
    fun theReadersCountsAreReadInTheStatusTaskOnTheWorker() {
        // AdvertAliases is confined to the worker: a read off it races every table the counts walk.
        val read = transport.substringAfter("private suspend fun advertStatus()").substringBefore("\n        }\n")
        assertTrue("the read opens on the worker", read.startsWith(": AdvertStatus =\n        withContext(worker) {"))
        assertTrue("the counts are in the task", "aliases = aliases.counters()" in read)
        assertTrue("the handles are in the task", "formHandles = formKeys.size" in read)
        assertEquals("no other read of the counts", 1, Regex("""aliases\.counters\(\)""").findAll(transport).count())
    }

    @Test
    fun bothInstallsSetMineUnderTheWritersLockAndALateBringUpTaskStandsDown() {
        // A clear bring-up (main) and a rotation (worker) each set `mine` inside the writer's stamp callback, so the two
        // land in the order they stamped; set after the call returns, a clear bring-up could overwrite a later tag's.
        val upClear = transport.substringAfter("private fun upClear()").substringBefore("\n    }\n")
        val rotate = transport.substringAfter("private fun rotate(").substringBefore("\n    }\n")
        for ((name, body, set) in listOf(
            Triple("upClear", upClear, "mine.set(AdvertOrder.Mine.clear(localNodeId))"),
            Triple("rotate", rotate, "mine.set(AdvertOrder.Mine(form, previous))"),
        )) {
            val callback = body.substringAfter("stamped = it").substringBefore("}")
            assertTrue("$name sets mine inside the stamp callback", set in callback)
            assertEquals("$name sets mine once", 1, body.split(set).size - 1)
        }
        assertFalse(
            "afterInstall leaves mine to the callback",
            "mine.set" in transport.substringAfter("private fun afterInstall(").substringBefore("\n    }\n"),
        )
        // The deferred bring-up task stands down when a later install was recorded (the pure rule: Install.mayRecord).
        assertTrue(
            "upClear's late task is guarded",
            "if (stamped == 0L || !AdvertStatus.Install.mayRecord(lastInstall, stamped)) return@launch" in upClear,
        )
    }
}
