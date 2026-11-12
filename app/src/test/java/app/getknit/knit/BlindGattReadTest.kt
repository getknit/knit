package app.getknit.knit

import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.bluetooth.AdvertAliases
import app.getknit.knit.mesh.bluetooth.AdvertAliases.Resolution
import app.getknit.knit.mesh.bluetooth.AdvertAliases.Source
import app.getknit.knit.mesh.bluetooth.AdvertOrder.Form
import app.getknit.knit.mesh.bluetooth.BtLines
import app.getknit.knit.mesh.bluetooth.FormKeys
import app.getknit.knit.mesh.crypto.BlindTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reader's side of a blinded GATT payload (ADR 2026-11.89gg) beyond [app.getknit.knit.mesh.bluetooth.GattPayloads]:
 * the `bt gatt …` lines it adds show no tag and knit-ios `interop.py` books them as blinded, never clear, and rule 2 finds the
 * tag a node announced by its id ([AdvertAliases.announced], knit-ios `AdvertAliases.announced(by:)`).
 */
class BlindGattReadTest {
    private val tag = NodeId.fromBytes(BlindTag.tag(ByteArray(BlindTag.KEY_BYTES) { 3 }, ByteArray(NodeId.BYTES), slot = 1, restart = 0))
    private val fullTag = Regex("zzzz[a-z2-7]{22}")
    private val address = "AA:BB:CC:DD:EE:FF"
    private val id = NodeId.derive("alice")

    private fun lines(handle: String) =
        listOf(
            BtLines.gattReadBlinded(address, 0x83),
            BtLines.gattBlinded(address, id, BtLines.gattResolved(Resolution.Resolved(id, Source.Key), replayed = false)),
            BtLines.gattBlinded(address, handle, BtLines.gattResolved(Resolution.Unresolved, replayed = false)),
            BtLines.gattConfirmedTag(address, id),
            BtLines.gattForget(address, BtLines.GATT_FORGET_EDGE),
        )

    @Test
    fun theBlindedGattLinesShowNoTag() {
        val handle = FormKeys().keyFor(tag)
        assertTrue(fullTag.containsMatchIn(tag))
        lines(handle).forEach { assertFalse(it, fullTag.containsMatchIn(it)) }
    }

    @Test
    fun theBlindedGattLinesAreBookedByTheIosHarnessAsBlindedNeverAsClear() {
        // knit-ios `interop.py`'s GATT_READ_END, `_gatt_reads`' id capture, GATT_CONFIRMED and GATT_CONFIRMED_TAG, verbatim
        // (its A11 harness fix): a blinded read is charged with no node, and a blinded refund takes only a blinded charge, so
        // neither is ever booked as a clear one (`_charged_reads` deletes a clear charge by address and id).
        val readEnd = Regex("^bt gatt read (\\S+) (?:→ ([a-z2-7]{26})|failed \\(|stranger \\(|(blinded) \\()")
        val clearRead = Regex("bt gatt read (\\S+) → ([a-z2-7]{26})")
        val confirmed = Regex("^bt gatt confirmed (\\S+) → ([a-z2-7]{26})")
        val confirmedTag = Regex("^bt gatt confirmed (\\S+) \\(tag\\) → ([a-z2-7]{26})")
        val handle = FormKeys().keyFor(tag)
        lines(handle).forEach { line ->
            listOf(clearRead, confirmed).forEach { assertFalse("$it ~ $line", it.containsMatchIn(line)) }
        }
        val blindedRead = checkNotNull(readEnd.find(BtLines.gattReadBlinded(address, 0x83))) { "the blinded read ends a read" }
        assertEquals(address, blindedRead.groupValues[1])
        assertEquals("charged with no node", "", blindedRead.groupValues[2])
        assertEquals("blinded", blindedRead.groupValues[3])
        val refund = checkNotNull(confirmedTag.find(BtLines.gattConfirmedTag(address, id))) { "the blinded refund" }
        assertEquals(listOf(address, id), refund.groupValues.drop(1))
        (lines(handle) - BtLines.gattReadBlinded(address, 0x83)).forEach { assertFalse(it, readEnd.containsMatchIn(it)) }
        assertFalse("a clear refund is not a tag's", confirmedTag.containsMatchIn("bt gatt confirmed $address → $id"))
        // The clear lines keep their shapes.
        assertTrue(readEnd.containsMatchIn("bt gatt read $address → $id (psm 131)"))
        assertTrue(confirmed.containsMatchIn("bt gatt confirmed $address → $id"))
        assertEquals("bt gatt forget $address (hello $id)", BtLines.gattForget(address, "hello $id"))
        assertTrue(BtLines.gattForget(address, BtLines.GATT_FORGET_EDGE).endsWith("(edge)"))
    }

    @Test
    fun eachResolutionHasItsWord() {
        assertEquals("key", BtLines.gattResolved(Resolution.Resolved(id, Source.Key), replayed = false))
        assertEquals("replayed", BtLines.gattResolved(Resolution.Resolved(id, Source.Key), replayed = true))
        assertEquals("claim", BtLines.gattResolved(Resolution.Resolved(id, Source.Claim), replayed = false))
        assertEquals("hint", BtLines.gattResolved(Resolution.Resolved(id, Source.Hint), replayed = false))
        assertEquals("handle", BtLines.gattResolved(Resolution.Unresolved, replayed = false))
        assertEquals("own", BtLines.gattResolved(Resolution.Own, replayed = false))
    }

    @Test
    fun theTagANodeAnnouncedIsFoundByItsIdUntilItIsHeard() {
        val wall = 1_800_000_000_000L
        val aliases = AdvertAliases<String>({ 1_000_000L }, { wall })
        val bob = NodeId.derive("bob")
        val next = NodeId.fromBytes(BlindTag.tag(ByteArray(BlindTag.KEY_BYTES) { 9 }, NodeId.toBytes(bob), BlindTag.slotOf(wall), 0))
        assertNull(aliases.announced(by = bob))
        aliases.onTagNext("l1", bob, next)
        assertEquals(next, aliases.announced(by = bob))
        assertNull("by its node only", aliases.announced(by = id))
        assertEquals(Resolution.Resolved(bob, Source.Claim), aliases.resolve(Form(next, blinded = true)))
        assertNull("heard, it is the link's claim and no longer pending", aliases.announced(by = bob))
    }
}
