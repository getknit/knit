package app.getknit.knit.mesh

import app.getknit.knit.mesh.crypto.MessageContent
import app.getknit.knit.mesh.crypto.MessageContentV2
import app.getknit.knit.mesh.protocol.FrameId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The compact copy's shed (ADR 2026-11.gsqu, Stage 1a.4): a copy past its sink's budget drops its inline acks, as
 * Crypto 4 always had it, and now names the LoRa-held ones among them so the manager gives them back to the coalescer —
 * a standalone compact ✓✓ then takes them to a peer who hears only the board, where before the shed lost them to it.
 */
class CompactCopyTest {
    private val held = List(MAX_INLINE_ACKS) { FrameId.new() }

    @Test
    fun aCopyThatFitsKeepsItsAcksAndGivesNothingBack() {
        val content = MessageContent(body = "meet at 8", acks = held)
        val copy = checkNotNull(compactCopyOf(content, LORA_DIRECT_PLAINTEXT_BYTES, loraHeld = held))
        assertArrayEquals(MessageContentV2.encodeOrNull(content), copy.plaintext)
        assertTrue(copy.giveBack.isEmpty())
    }

    @Test
    fun aShedCopyGivesBackTheLoraHeldAcksItDropped() {
        val room = List(2) { FrameId.new() } // a broadcast room tick riding the signed copy: never the coalescer's
        val content = MessageContent(body = "x".repeat(LORA_DIRECT_PLAINTEXT_BYTES - 40), acks = held + room)
        assertTrue("past the budget with its acks", checkNotNull(MessageContentV2.encodeOrNull(content)).size > LORA_DIRECT_PLAINTEXT_BYTES)
        val copy = checkNotNull(compactCopyOf(content, LORA_DIRECT_PLAINTEXT_BYTES, loraHeld = held))
        assertArrayEquals("shed", MessageContentV2.encodeOrNull(content.copy(acks = null)), copy.plaintext)
        assertEquals("the coalescer's ids, in the order they rode", held, copy.giveBack)
    }

    @Test
    fun theBudgetIsTheSinksNotAConstant() {
        val content = MessageContent(body = "hello there", acks = held)
        val whole = checkNotNull(MessageContentV2.encodeOrNull(content)).size
        assertTrue(checkNotNull(compactCopyOf(content, whole, loraHeld = held)).giveBack.isEmpty())
        assertEquals(
            "one byte short of a smaller board's budget sheds them",
            held,
            checkNotNull(compactCopyOf(content, whole - 1, held)).giveBack,
        )
    }

    @Test
    fun aReceiptAndACopyWithNoAcksAreNeverShed() {
        val tick = MessageContent(body = "", ctl = MessageContent.CTL_RECEIPT, acks = held)
        val tiny = checkNotNull(compactCopyOf(tick, budgetBytes = 1, loraHeld = held))
        assertArrayEquals(MessageContentV2.encodeOrNull(tick), tiny.plaintext)
        assertTrue(tiny.giveBack.isEmpty())
        val long = MessageContent(body = "y".repeat(LORA_DIRECT_PLAINTEXT_BYTES))
        assertTrue(
            "too long, it goes as it is: the plane counts it",
            checkNotNull(compactCopyOf(long, LORA_DIRECT_PLAINTEXT_BYTES)).giveBack.isEmpty(),
        )
    }

    @Test
    fun aShedCopyWithNoLoraHeldAcksGivesNothingBack() {
        val content = MessageContent(body = "x".repeat(LORA_DIRECT_PLAINTEXT_BYTES - 40), acks = held)
        assertTrue(checkNotNull(compactCopyOf(content, LORA_DIRECT_PLAINTEXT_BYTES)).giveBack.isEmpty())
    }
}
