package app.getknit.knit

import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.OwnPresenceKey
import app.getknit.knit.mesh.bluetooth.AdvertOrder
import app.getknit.knit.mesh.bluetooth.AdvertWriter
import app.getknit.knit.mesh.bluetooth.BleAdvertPayload
import app.getknit.knit.mesh.bluetooth.TagChoice
import app.getknit.knit.mesh.bluetooth.TagSchedule
import app.getknit.knit.mesh.crypto.BlindTag
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.security.SecureRandom
import java.util.Random

/**
 * [AdvertWriter] and [TagChoice] (ADR 2026-11.xp6d's writer, step 5b's W3, a blocking finding of the bluetooth and
 * security design passes): while blinding, no payload handed to any set carries the node id in bytes 1–16; a set never
 * carries two forms; nothing airs while a tag is pending; a write to an instance a rotation replaced lands nowhere; a
 * throwing mark store yields a random tag, never the id; an install after the radio went down starts nothing.
 */
class AdvertWriterTest {
    /**
     * A set that records every payload, and every call into one event list shared by all sets ([events]), so the order of
     * a swap shows and a write after retirement (`late update <name>`) is caught. Inert once retired, as the real one.
     */
    private class FakeSink(
        val name: String,
        private val events: MutableList<String>,
    ) : AdvertWriter.Sink {
        val payloads = ArrayList<ByteArray>()
        var stopped = 0
        var retired = false

        override fun update(serviceData: ByteArray) {
            if (retired) {
                events += "late update $name"
                return
            }
            events += "update $name"
            payloads += serviceData
        }

        override fun stop() {
            events += "stop $name"
            stopped++
        }

        override fun retire() {
            events += "retire $name"
            retired = true
            stopped++
        }

        /** The distinct bytes 1–16 this set was ever handed. */
        fun forms(): Set<String> = payloads.mapTo(HashSet()) { NodeId.fromBytes(it.copyOfRange(1, 1 + NodeId.BYTES)) }

        override fun toString() = name
    }

    private class SeededRandom(
        seed: Long,
    ) : SecureRandom() {
        private val inner = Random(seed)

        override fun nextBytes(bytes: ByteArray) = inner.nextBytes(bytes)
    }

    private class Store : TagSchedule.MarkStore {
        val marks = HashMap<Long, TagSchedule.Mark>()
        var failing = false

        override fun read(generation: Long): TagSchedule.Mark? = marks[generation]

        override fun write(
            generation: Long,
            mark: TagSchedule.Mark,
        ) {
            check(!failing) { "the generation is no longer the newest activated one" }
            marks[generation] = mark
        }
    }

    private val localId = NodeId.fromBytes(ByteArray(NodeId.BYTES) { (it * 7).toByte() })
    private val writer = AdvertWriter<FakeSink> { localId }
    private val sinks = ArrayList<FakeSink>()
    private val store = Store()
    private val schedule = TagSchedule(store, SeededRandom(3))
    private val wall = 1_800_000_000_000L
    private val key = OwnPresenceKey(4, ByteArray(BlindTag.KEY_BYTES) { 9 })

    private val events = ArrayList<String>()

    private fun sink(name: String) = FakeSink("$name${sinks.size}", events).also { sinks += it }

    private fun assertNoLateUpdate() = assertTrue("a retired set was written: $events", events.none { it.startsWith("late update") })

    private fun encode(
        form: AdvertOrder.Form,
        psm: Int,
    ): ByteArray =
        BleAdvertPayload.advert(
            form.advertised,
            0x0FL,
            0x1234L,
            psm,
            sideLive = true,
            gattPeers = false,
            aware = false,
            blinded = form.blinded,
        )

    private fun write(
        aired: AdvertWriter.Aired<FakeSink>,
        bytes: ByteArray,
    ) {
        aired.presence.update(bytes)
        aired.coded?.update(bytes)
    }

    private fun push() = writer.push(::encode, ::write)

    private fun install(
        token: Int,
        form: AdvertOrder.Form,
        psm: Int = 0x81,
    ): Boolean = writer.install(token, { AdvertWriter.Aired(sink("presence"), sink("coded"), form, psm) }, ::encode, ::write)

    private fun tagForm(choice: TagChoice.Choice) = choice.form

    private fun clearAired() = AdvertWriter.Aired(sink("presence0"), sink("coded0"), AdvertOrder.Form.clear(localId), 0x80)

    /** Every payload any set was handed, with the snapshot it named. */
    private fun allPayloads() = sinks.flatMap { it.payloads }

    private fun assertNoIdWhileBlinded() {
        for (p in allPayloads()) {
            val parsed = BleAdvertPayload.parse(p)!!
            if (parsed.blinded) assertNotEquals("a blinded payload never names the id", localId, parsed.nodeId)
        }
    }

    private fun assertNoFormChangedInPlace() {
        for (s in sinks) assertTrue("$s carried ${s.forms().size} forms", s.forms().size <= 1)
    }

    @Test
    fun aBlindingBringUpAirsNothingUntilItsTagIsInstalled() {
        val token = writer.up(pendingTag = true, clear = null, ::encode, ::write)
        assertFalse("a push while the tag is pending writes nothing", push())
        assertTrue(writer.pushWanted())
        val choice = TagChoice.choose(schedule, key, key, announced = false, wallMs = wall)
        assertTrue(install(token, tagForm(choice)))
        assertFalse(writer.pending())
        val presence = writer.presence()!!
        assertEquals("the install pushed once", 1, presence.payloads.size)
        val parsed = BleAdvertPayload.parse(presence.payloads.single())!!
        assertTrue(parsed.blinded)
        assertEquals(0, parsed.digestCue)
        assertArrayEquals(BlindTag.tag(key.key, BlindTag.slotOf(wall), 0), NodeId.toBytes(parsed.nodeId))
        assertEquals(0x81, parsed.psm)
        assertNoIdWhileBlinded()
    }

    @Test
    fun aThrowingMarkStoreYieldsARandomTagNeverTheId() {
        store.failing = true // a fresh pair demoted the generation between the feed and the write
        val token = writer.up(pendingTag = true, clear = null, ::encode, ::write)
        val choice = TagChoice.choose(schedule, key, key, announced = false, wallMs = wall)
        assertEquals(TagChoice.Source.MARK_FAILED, choice.source)
        assertFalse(choice.resolvable)
        assertTrue(install(token, tagForm(choice)))
        val parsed = BleAdvertPayload.parse(writer.presence()!!.payloads.single())!!
        assertTrue(parsed.blinded)
        assertTrue("a prefixed random tag", BlindTag.hasPrefix(NodeId.toBytes(parsed.nodeId)))
        assertNoIdWhileBlinded()
    }

    @Test
    fun aBlindedSnapshotRefusesAPayloadThatNamesTheId() {
        val token = writer.up(pendingTag = true, clear = null, ::encode, ::write)
        val form = TagChoice.choose(schedule, key, key, announced = false, wallMs = wall).form
        // An encoder that builds from the node id, as `readvertise` did before the writer: refused, counted, nothing aired.
        val fromId = { _: AdvertOrder.Form, psm: Int -> encode(AdvertOrder.Form.clear(localId), psm) }
        // Installed (the snapshot is the tag's), but the payload built from the id never reaches a set.
        assertTrue(writer.install(token, { AdvertWriter.Aired(sink("p"), sink("c"), form, 0x81) }, fromId, ::write))
        assertEquals(1L, writer.clearRefused)
        assertFalse(writer.push(fromId, ::write))
        assertEquals(2L, writer.clearRefused)
        assertTrue(allPayloads().isEmpty())
    }

    @Test
    fun noSetEverCarriesTwoFormsAndAReplacedOneGetsNothingMore() {
        writer.up(pendingTag = false, clear = clearAired(), ::encode, ::write)
        push() // a cue change: the clear form in place, on the clear sets
        val clearPresence = writer.presence()!!
        // The switch flips on: a rotation, fresh sets with a tag.
        val flip = writer.begin()
        assertFalse("a push mid-rotation writes nothing", push())
        assertTrue(install(flip, TagChoice.choose(schedule, key, key, false, wall).form))
        assertTrue(clearPresence.retired)
        assertFalse(writer.isCurrent(clearPresence))
        val firstTagPresence = writer.presence()!!
        // The swap retired the old sets before the new ones' first write.
        val retiredAt = events.indexOf("retire $clearPresence")
        val firstWrite = events.indexOf("update $firstTagPresence")
        assertTrue("retire before the new set's first update: $events", retiredAt in 0 until firstWrite)
        push()
        push()
        // The slot edge: fresh sets again.
        val slot = writer.begin()
        assertTrue(install(slot, TagChoice.choose(schedule, key, key, false, wall + BlindTag.SLOT_MS).form))
        assertTrue(firstTagPresence.retired)
        push()
        assertNoFormChangedInPlace()
        assertNoIdWhileBlinded()
        assertNoLateUpdate()
        assertEquals("the old clear set got nothing after its retirement", 2, clearPresence.payloads.size)
    }

    @Test
    fun anInstallAfterTheRadioWentDownStartsNothingAndOpensNothing() {
        val token = writer.up(pendingTag = true, clear = null, ::encode, ::write)
        writer.down() // adapter off while the worker chose the tag
        var prepared = false
        val installed =
            writer.install(token, {
                prepared = true
                AdvertWriter.Aired(sink("p"), sink("c"), AdvertOrder.Form.clear(localId), 1)
            }, ::encode, ::write)
        assertFalse(installed)
        assertFalse("nothing opened for a stale rotation", prepared)
        writer.abandon(token)
        assertFalse(push())
        assertTrue(allPayloads().isEmpty())
    }

    @Test
    fun aBringUpWhoseRotationFailsNeverRestartsTheLastSessionsForm() {
        val first = writer.up(pendingTag = true, clear = null, ::encode, ::write)
        assertTrue(install(first, TagChoice.choose(schedule, key, key, false, wall).form))
        val before = writer.presence()!!.payloads.size
        writer.down()
        val again = writer.up(pendingTag = true, clear = null, ::encode, ::write)
        writer.abandon(again) // the bring-up's rotation threw
        assertFalse(push())
        assertEquals("the stopped set from before the toggle is not restarted with its old tag", before, writer.presence()!!.payloads.size)
    }

    @Test
    fun aCodedPowerChangeRetiresTheOldCodedSetUnderTheSameForm() {
        val token = writer.up(pendingTag = true, clear = null, ::encode, ::write)
        assertTrue(install(token, TagChoice.choose(schedule, key, key, false, wall).form))
        val oldCoded = writer.coded()!!
        val newCoded = sink("coded-medium")
        writer.replaceCoded(newCoded, ::encode, ::write)
        assertTrue(oldCoded.retired)
        assertTrue(writer.isCurrent(newCoded))
        assertEquals(1, newCoded.payloads.size)
        assertNoLateUpdate()
        assertNoFormChangedInPlace()
    }

    @Test
    fun noKeyAirsARandomPrefixedTag() {
        val choice = TagChoice.choose(schedule, held = null, live = null, announced = false, wallMs = wall)
        assertEquals(TagChoice.Source.NO_KEY, choice.source)
        assertNull(choice.generation)
        assertTrue(choice.form.blinded)
        assertTrue(BlindTag.hasPrefix(NodeId.toBytes(choice.form.advertised)))
        assertTrue("no mark written for a random tag", store.marks.isEmpty())
    }

    @Test
    fun aKeyTheEngineMovedPastAirsNothingUnderItAndDropsItsReservation() {
        val reserved = schedule.reserve(key.key, key.generation, wall)
        val fresh = OwnPresenceKey(5, ByteArray(BlindTag.KEY_BYTES) { 2 })
        // At the edge, the engine already holds generation 5; the feed's hop has not delivered it yet.
        val choice = TagChoice.choose(schedule, held = key, live = fresh, announced = true, wallMs = wall + BlindTag.SLOT_MS)
        assertEquals(TagChoice.Source.KEY_MOVED, choice.source)
        assertNotEquals(NodeId.fromBytes(reserved.tag), choice.form.advertised)
        assertNull("the reservation went unaired", schedule.take(key.generation, wall + BlindTag.SLOT_MS))
    }

    @Test
    fun anAnnouncedRotationAirsExactlyTheReservedTag() {
        val reserved = schedule.reserve(key.key, key.generation, wall)
        val choice = TagChoice.choose(schedule, key, key, announced = true, wallMs = wall + BlindTag.SLOT_MS)
        assertEquals(TagChoice.Source.ANNOUNCED, choice.source)
        assertEquals(NodeId.fromBytes(reserved.tag), choice.form.advertised)
    }

    @Test
    fun anUnannouncedRotationPassesAReservationByUnaired() {
        val reserved = schedule.reserve(key.key, key.generation, wall)
        val choice = TagChoice.choose(schedule, key, key, announced = false, wallMs = wall + BlindTag.SLOT_MS)
        assertEquals(TagChoice.Source.SLOTTED, choice.source)
        assertNotEquals("the reserved slot's mark is spent: the next restart airs", NodeId.fromBytes(reserved.tag), choice.form.advertised)
        assertTrue(choice.resolvable)
    }

    @Test
    fun aRandomTagIsNeverWrittenInPlaceOverTheResolvableOne() {
        // A bring-up with no key airs a random tag; the first key then rotates onto fresh sets, never an in-place update.
        val token = writer.up(pendingTag = true, clear = null, ::encode, ::write)
        assertTrue(install(token, TagChoice.choose(schedule, null, null, false, wall).form))
        val randomSet = writer.presence()!!
        val restart = writer.begin()
        assertTrue(install(restart, TagChoice.choose(schedule, key, key, false, wall).form))
        assertTrue(randomSet.retired)
        assertNoFormChangedInPlace()
        assertNoIdWhileBlinded()
        assertNoLateUpdate()
    }

    @Test
    fun aMarkStoreReadFailureIsNotAClearAdvert() {
        val failingRead =
            object : TagSchedule.MarkStore {
                override fun read(generation: Long): TagSchedule.Mark? = throw IOException("decrypt")

                override fun write(
                    generation: Long,
                    mark: TagSchedule.Mark,
                ) = Unit
            }
        val choice = TagChoice.choose(TagSchedule(failingRead, SeededRandom(1)), key, key, false, wall)
        assertEquals(TagChoice.Source.MARK_FAILED, choice.source)
        assertTrue(choice.form.blinded)
        assertNotEquals(localId, choice.form.advertised)
    }

    @Test
    fun everyInstallIsStampedAndOneReadGivesTheFormAndPsmTogether() {
        // The debug bridge's `epoch` and `rotatedAtMs` (step 6): a clear bring-up and each install count one; a Coded power
        // change replaces the snapshot but keeps its stamp.
        var now = wall
        val stamped = AdvertWriter<FakeSink>(wallMs = { now }) { localId }
        assertNull(stamped.aired())
        stamped.up(pendingTag = false, clear = clearAired(), ::encode, ::write)
        assertEquals(1L, stamped.aired()!!.installs)
        assertEquals(wall, stamped.aired()!!.installedAtMs)
        now += 5_000
        val form = TagChoice.choose(schedule, key, key, false, wall).form
        assertTrue(stamped.install(stamped.begin(), { AdvertWriter.Aired(sink("p"), sink("c"), form, 0x9C) }, ::encode, ::write))
        val aired = stamped.aired()!!
        assertEquals(2L, aired.installs)
        assertEquals(wall + 5_000, aired.installedAtMs)
        assertEquals(form, aired.form)
        assertEquals(0x9C, aired.psm)
        assertFalse("never the tag in its string form", form.advertised in aired.toString())
        now += 5_000
        stamped.replaceCoded(sink("c2"), ::encode, ::write)
        assertEquals(2L, stamped.aired()!!.installs)
        assertEquals(wall + 5_000, stamped.aired()!!.installedAtMs)
        // A stale install (the radio went down first) stamps nothing.
        val token = stamped.begin()
        stamped.down()
        assertFalse(stamped.install(token, { AdvertWriter.Aired(sink("p"), sink("c"), form, 0x9D) }, ::encode, ::write))
        assertEquals(2L, stamped.aired()!!.installs)
    }
}
