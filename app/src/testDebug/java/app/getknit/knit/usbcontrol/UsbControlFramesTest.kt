@file:Suppress("MagicNumber") // byte counts and offsets are the test's subject

package app.getknit.knit.usbcontrol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class UsbControlFramesTest {
    @Test
    fun aFrameIsItsBigEndianLengthThenTheJson() {
        // knit-cli's tests/test_frames.py pins the same bytes.
        val json = """{"id":1,"op":"status"}"""
        val expected = byteArrayOf(0, 0, 0, 0x16) + json.encodeToByteArray()
        assertArrayEquals(expected, UsbControlFrames.encode(json))
    }

    @Test
    fun framesSurviveAnySplitOfTheStream() {
        val jsons = listOf("""{"id":1,"op":"hello"}""", """{"event":"message","body":"héllo ✓"}""", """{"id":"x"}""")
        val stream = jsons.map(UsbControlFrames::encode).reduce(ByteArray::plus)
        for (chunk in listOf(1, 3, 7, stream.size)) {
            val deframer = UsbControlDeframer()
            val out = stream.toList().chunked(chunk).flatMap { deframer.feed(it.toByteArray()) }
            assertEquals("chunk $chunk", jsons, out)
            assertEquals(0, deframer.pending)
        }
    }

    @Test
    fun aFrameThatWouldEndOnAPacketBoundaryGetsOneTrailingSpace() {
        // 4 + 508 = 512: no short packet would end the transfer, so the body grows by one space.
        val json = "\"" + "a".repeat(506) + "\""
        val frame = UsbControlFrames.encode(json)
        assertEquals(4 + 509, frame.size)
        assertTrue(frame.size % UsbControlFrames.PACKET_ALIGN != 0)
        assertEquals(listOf("$json "), UsbControlDeframer().feed(frame))
    }

    @Test
    fun everyMultipleOfTheFullSpeedPacketIsPadded() {
        // 576 is a multiple of 64 but not of 512; 1024 is a high- and super-speed multiple; the cap pads too.
        for (total in listOf(64, 576, 1024, UsbControlFrames.HEADER + UsbControlFrames.MAX_FRAME - 4)) {
            val json = "\"" + "a".repeat(total - 4 - 2) + "\""
            val frame = UsbControlFrames.encode(json)
            assertEquals("total $total", total + 1, frame.size)
            assertEquals(listOf("$json "), UsbControlDeframer().feed(frame))
        }
    }

    @Test
    fun aFrameOffTheBoundaryIsNotPadded() {
        val json = "\"" + "a".repeat(505) + "\""
        assertEquals(4 + 507, UsbControlFrames.encode(json).size)
    }

    @Test
    fun readsFromAReusedBufferTakeOnlyTheirCountAndGrowPastTheFirstBuffer() {
        // The server feeds slices of one reused read buffer; bytes past `count` are the last read's leftovers.
        val big = "\"" + "b".repeat(40 * 1024) + "\""
        val stream = UsbControlFrames.encode(big) + UsbControlFrames.encode("{}")
        val scratch = ByteArray(16 * 1024)
        val deframer = UsbControlDeframer()
        val out = mutableListOf<String>()
        var at = 0
        while (at < stream.size) {
            scratch.fill('x'.code.toByte())
            val n = minOf(5_000, stream.size - at)
            stream.copyInto(scratch, 0, at, at + n)
            out += deframer.feed(scratch, 0, n)
            at += n
        }
        assertEquals(listOf(big, "{}"), out)
        assertEquals(0, deframer.pending)
        deframer.reset()
        assertEquals(listOf("{}"), deframer.feed(UsbControlFrames.encode("{}")))
    }

    @Test(expected = IOException::class)
    fun aZeroLengthFrameIsRefused() {
        UsbControlDeframer().feed(byteArrayOf(0, 0, 0, 0))
    }

    @Test(expected = IOException::class)
    fun aLengthOverTheCapIsRefusedBeforeItsBodyArrives() {
        UsbControlDeframer(max = 16).feed(byteArrayOf(0, 0, 0, 17))
    }

    @Test
    fun afterAResetTheNextFrameReadsCleanly() {
        val deframer = UsbControlDeframer(max = 16)
        runCatching { deframer.feed(byteArrayOf(0x7f, 0, 0, 0, 1, 2)) }
        deframer.reset()
        assertEquals(listOf("{}"), deframer.feed(UsbControlFrames.encode("{}")))
    }

    @Test(expected = IOException::class)
    fun anEmptyBodyIsNotEncoded() {
        UsbControlFrames.encode("")
    }

    @Test(expected = IOException::class)
    fun aBodyOverTheCapIsNotEncoded() {
        UsbControlFrames.encode("a".repeat(UsbControlFrames.MAX_FRAME + 1))
    }
}
