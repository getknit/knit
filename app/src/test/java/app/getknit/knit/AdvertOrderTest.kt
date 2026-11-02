package app.getknit.knit

import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.bluetooth.AdvertOrder
import app.getknit.knit.mesh.bluetooth.AdvertOrder.Form
import app.getknit.knit.mesh.crypto.BlindTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * [AdvertOrder] (ADR 2026-11.xp6d): who dials before a link once an advert may name a tag. The two-phone simulation
 * pairs every build a mixed mesh holds — a shipped build (clear, reads only ids), a reader with writing off (clear,
 * reads tags) and a blinding writer — over seeded ids and tags, and walks the rotation edges.
 */
class AdvertOrderTest {
    private enum class Build { Old, Reader, Writer }

    /** One phone: its real id and, for a writer, its tags oldest first (the last is the one on air). */
    private class Phone(
        val build: Build,
        val id: String,
        val tags: List<String> = emptyList(),
    ) {
        fun form(age: Int = 0): Form = if (build == Build.Writer) Form(tags[tags.size - 1 - age], blinded = true) else Form.clear(id)

        val current: Form get() = form()
        val previous: Form? get() = if (build == Build.Writer && tags.size > 1) form(1) else null

        /** Whether this phone's ordinary dial goes to [seen]. A shipped build reads bytes 1–16 as an id, flag unread. */
        fun dials(seen: Form): Boolean = if (build == Build.Old) id > seen.advertised else AdvertOrder.iDial(current, seen)
    }

    private val random = Random(285)

    private fun id(): String = NodeId.fromBytes(ByteArray(NodeId.BYTES).also(random::nextBytes))

    private fun tag(): String =
        NodeId.fromBytes(
            BlindTag.mint(
                ByteArray(BlindTag.KEY_BYTES).also(random::nextBytes),
                random.nextLong() and BlindTag.NONCE_MASK,
            ),
        )

    private fun phone(
        build: Build,
        tags: Int = 2,
    ) = Phone(build, id(), if (build == Build.Writer) List(tags) { tag() } else emptyList())

    /**
     * Whether [dialer]'s dial to [responder], made against the form it saw ([seen]), links: a shipped build expects the
     * dialed form back as the HELLO's id, so its dial to a tag fails on `WrongPeer`; a blinding responder refuses a
     * dialer that cannot read tags; any other responder refuses only a sighted dialer it should have dialed itself,
     * judged on the HELLO's `d` and `t` ([AdvertOrder.responderShouldHaveDialed]). A shipped responder keeps the
     * classic row, and never sights a writer's real id.
     */
    private fun lands(
        dialer: Phone,
        responder: Phone,
        seen: Form,
    ): Boolean {
        if (dialer.build == Build.Old && seen.blinded) return false
        if (responder.build == Build.Writer && dialer.build == Build.Old) return false
        return when (responder.build) {
            Build.Old -> {
                dialer.build == Build.Writer || responder.id < dialer.id
            }

            else -> {
                val told = if (dialer.build == Build.Old) null else dialer.current.advertised
                !AdvertOrder.responderShouldHaveDialed(
                    responder.current,
                    responder.previous,
                    dialed = seen.advertised.takeIf { dialer.build != Build.Old },
                    dialer = Form.fromHello(dialer.id, told),
                )
            }
        }
    }

    @Test
    fun theRuleFromOneSide() {
        val small = Form.clear("aaaa")
        val large = Form.clear("mmmm")
        val tagLow = Form("zzzzb", blinded = true)
        val tagHigh = Form("zzzzq", blinded = true)
        assertTrue("two clear ids: the larger dials", AdvertOrder.iDial(large, small))
        assertFalse(AdvertOrder.iDial(small, large))
        assertTrue("a blinding phone dials every clear advert", AdvertOrder.iDial(tagLow, large))
        assertFalse("a clear phone leaves a blinded advert to dial it", AdvertOrder.iDial(large, tagLow))
        assertTrue("two tags: the larger dials", AdvertOrder.iDial(tagHigh, tagLow))
        assertFalse(AdvertOrder.iDial(tagLow, tagHigh))
        assertFalse("equal forms never dial", AdvertOrder.iDial(large, large))
        assertFalse(AdvertOrder.iDial(tagLow, tagLow))
    }

    @Test
    fun aClearIdAboveTheTagStillLeavesTheDialToTheBlindingSide() {
        // About 2⁻²⁰ of real ids sort above a tag. Comparing strings there would have both sides dial and each
        // responder refuse the other's dial as out of order, for good.
        val zid = Form.clear("z".repeat(NodeId.LENGTH - 1) + "y")
        val tag = Form(tag(), blinded = true)
        assertTrue(zid.advertised > tag.advertised)
        assertTrue(AdvertOrder.iDial(tag, zid))
        assertFalse(AdvertOrder.iDial(zid, tag))
        assertFalse("the blinding dial passes the responder's check", AdvertOrder.responderShouldHaveDialed(zid, null, zid.advertised, tag))
    }

    @Test
    fun exactlyOneSideLinksEveryPairOfBuilds() {
        repeat(PAIRS) {
            for (a in Build.entries) {
                for (b in Build.entries) {
                    val pa = phone(a)
                    val pb = phone(b)
                    val ab = pa.dials(pb.current) && lands(pa, pb, pb.current)
                    val ba = pb.dials(pa.current) && lands(pb, pa, pa.current)
                    assertTrue("$a↔$b: exactly one dial links", ab != ba)
                    if (a != Build.Old && b != Build.Old) {
                        assertTrue("$a↔$b: exactly one side dials", pa.dials(pb.current) != pb.dials(pa.current))
                    }
                }
            }
        }
    }

    @Test
    fun aShippedBuildsDialToAZzzzTagIsTheOnlyDoubleDialAndItFails() {
        val old = Phone(Build.Old, "z".repeat(NodeId.LENGTH - 1) + "y")
        val writer = phone(Build.Writer)
        assertTrue("it reads the tag as a smaller id and dials it", old.dials(writer.current))
        assertFalse("WrongPeer: the HELLO names the writer's real id", lands(old, writer, writer.current))
        assertTrue("the writer's own dial links the pair", writer.dials(old.current) && lands(writer, old, old.current))
    }

    @Test
    fun aRotationEdgeCanMakeBothOrNeitherDialAndTheDialedFormKeepsEveryDialThatIsMade() {
        var both = 0
        var neither = 0
        var refusedOnCurrentForms = 0
        repeat(PAIRS * 4) {
            for (other in listOf(Build.Writer, Build.Reader)) {
                val a = phone(Build.Writer)
                val b = phone(other)
                // Each side may still see the other's previous form: its scan has not yet heard the rotation.
                val aSees = if (random.nextBoolean()) b.previous ?: b.current else b.current
                val bSees = if (random.nextBoolean()) a.previous ?: a.current else a.current
                val ab = a.dials(aSees)
                val ba = b.dials(bSees)
                if (aSees == b.current && bSees == a.current) assertTrue("fresh views: exactly one dials", ab != ba)
                if (ab && ba) both++
                if (!ab && !ba) neither++
                for ((dialer, responder, seen) in listOfNotNull(Triple(a, b, aSees).takeIf { ab }, Triple(b, a, bSees).takeIf { ba })) {
                    assertTrue("judged on the form it dialed, a dial made in order is admitted", lands(dialer, responder, seen))
                    val dialerForm = Form.fromHello(dialer.id, dialer.current.advertised)
                    if (AdvertOrder.iDial(responder.current, dialerForm)) refusedOnCurrentForms++
                }
            }
        }
        // Both dialing gives two links, settled by the responder's held-proven-link row (wiring); neither dialing
        // lasts until a scan hears the current form, after which exactly one dials (the fresh-view assertion above).
        assertTrue("a stale view can have both dial", both > 0)
        assertTrue("a stale view can have neither dial", neither > 0)
        assertTrue("judged on current forms alone, some good dials would be refused", refusedOnCurrentForms > 0)
    }

    @Test
    fun theDialedFormIsHonouredOnlyForMyCurrentOrPreviousForm() {
        val writer = phone(Build.Writer, tags = 3)
        val gone = writer.form(2)
        assertEquals(writer.current, AdvertOrder.dialedForm(writer.current, writer.previous, writer.current.advertised))
        assertEquals(writer.previous, AdvertOrder.dialedForm(writer.current, writer.previous, writer.previous!!.advertised))
        assertNull("two rotations back", AdvertOrder.dialedForm(writer.current, writer.previous, gone.advertised))
        assertNull("not mine", AdvertOrder.dialedForm(writer.current, writer.previous, tag()))
        assertNull("no d", AdvertOrder.dialedForm(writer.current, writer.previous, null))
        // Not honoured: judged against my current form.
        val dialer = Form("zzzz" + "a".repeat(NodeId.LENGTH - 4), blinded = true)
        assertEquals(
            AdvertOrder.iDial(writer.current, dialer),
            AdvertOrder.responderShouldHaveDialed(writer.current, writer.previous, gone.advertised, dialer),
        )
    }

    @Test
    fun theDialersFormComesFromItsHello() {
        assertEquals(Form.clear("abc"), Form.fromHello("abc", null))
        assertEquals("a t equal to the id is a clear advert", Form.clear("abc"), Form.fromHello("abc", "abc"))
        assertEquals(Form("zzzzq", blinded = true), Form.fromHello("abc", "zzzzq"))
    }

    @Test
    fun theLonelyDialGoesOnlyWhereTheOrderLeavesTheDialToThePeer() {
        repeat(PAIRS) {
            for (a in listOf(Build.Reader, Build.Writer)) {
                for (b in Build.entries) {
                    val pa = phone(a)
                    val pb = phone(b)
                    assertEquals(!pa.dials(pb.current), AdvertOrder.lonelyMayDial(pa.current, pb.current))
                }
            }
        }
        assertFalse("never my own form", AdvertOrder.lonelyMayDial(Form.clear("abc"), Form.clear("abc")))
        val reader = phone(Build.Reader)
        val writer = phone(Build.Writer)
        assertTrue("a lonely reader dials a writer by tag", AdvertOrder.lonelyMayDial(reader.current, writer.current))
        // As for every lonely dial (ADR 2026-09.hj4a): a responder that sights the dialer refuses it, judged on `d`
        // and `t`, and its own ordinary dial is on the way; one that does not sight it admits it unsighted.
        assertFalse("a writer that sights the reader refuses it", lands(reader, writer, writer.current))
        assertTrue("and dials it itself", writer.dials(reader.current) && lands(writer, reader, reader.current))
        assertFalse("a writer already dials a reader", AdvertOrder.lonelyMayDial(writer.current, reader.current))
    }

    @Test
    fun aShippedBuildsLonelyDialPicksAWritersTagAndFails() {
        // The shipped rule (ADR 2026-09.hj4a): alone 180 s, dial a sighted larger id. A tag sorts above it.
        val old = phone(Build.Old)
        val writer = phone(Build.Writer)
        assertTrue("the shipped lonely rule picks the tag", writer.current.advertised > old.id)
        assertFalse("WrongPeer: the HELLO names the writer's real id", lands(old, writer, writer.current))
        assertTrue("the writer dials the old phone instead", writer.dials(old.current) && lands(writer, old, old.current))
    }

    @Test
    fun theAwareChaseTakesEveryForeignIdWhileBlinding() {
        val peers = setOf("aaa", "mmm", "zzz")
        assertEquals(setOf("aaa"), AdvertOrder.initiable("mmm", blinding = false, peers))
        assertEquals(setOf("aaa", "zzz"), AdvertOrder.initiable("mmm", blinding = true, peers))
    }

    private companion object {
        const val PAIRS = 200
    }
}
