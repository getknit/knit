package app.getknit.knit.call

import app.getknit.knit.call.TalkQueue.Step
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A walkie burst streamed (docs/VOICE_CALLS.md §3.11): the Opus packets, the chunk's bytes, the receiver's order. */
class WalkieStreamTest {
    // ---- OpusPackets ----

    @Test
    fun aPacketsTocSaysHowLongItPlays() {
        // SILK wideband 20 ms (config 9), one frame: what the encoder makes at 16 kHz.
        assertEquals(20_000L, OpusPackets.durationUs(packet(config = 9)))
        // SILK 60 ms, two frames.
        assertEquals(120_000L, OpusPackets.durationUs(packet(config = 3, code = 1)))
        // CELT 2.5 ms, code 3 with four frames.
        assertEquals(10_000L, OpusPackets.durationUs(byteArrayOf(((16 shl 3) or 3).toByte(), 4)))
        // Hybrid 10 ms.
        assertEquals(10_000L, OpusPackets.durationUs(packet(config = 12)))
        assertEquals(0L, OpusPackets.durationUs(ByteArray(0)))
    }

    @Test
    fun packetsJoinBehindTheirLengthsAndSplitBack() {
        val packets = listOf(packet(size = 17, fill = 1), packet(size = 1, fill = 2), packet(size = 255, fill = 3))
        val joined = OpusPackets.join(packets + packet(size = 256) + ByteArray(0))
        // A packet past a byte's length, and an empty one, are not a speech rate's: dropped at the join.
        assertEquals(17 + 1 + 255 + 3, joined.size)
        assertEquals(packets.map { it.toList() }, OpusPackets.split(joined).map { it.toList() })
        // A length that runs off the end stops the split.
        assertEquals(1, OpusPackets.split(joined.copyOf(19)).size)
    }

    // ---- TalkChunker ----

    @Test
    fun aChunkGoesOutOnceItHoldsTheChunkLengthAndTheRestGoesLast() {
        val chunker = TalkChunker(chunkMs = 480)
        val packets = List(30) { packet(size = 20, fill = it.toByte()) }
        val out = packets.mapNotNull(chunker::add)
        // 20 ms packets: one chunk per 24.
        assertEquals(1, out.size)
        assertEquals(packets.take(24).map { it.toList() }, OpusPackets.split(out[0]).map { it.toList() })
        assertEquals(6, OpusPackets.split(chunker.flush()).size)
        assertEquals(600L, chunker.totalMs)
        assertEquals(0, chunker.flush().size)
    }

    // ---- TalkWire ----

    @Test
    fun aChunkRoundTripsThroughItsBytesWithItsTag() {
        val audio = OpusPackets.join(List(3) { packet(size = 20) })
        val bytes = TalkWire.encode(0xBEEF, TalkChunk(b = 300, q = 7, a = audio, e = true))
        // flags, tag, b (two varint bytes), q: six bytes ahead of the audio.
        assertEquals(6 + audio.size, bytes.size)
        val (tag, chunk) = TalkWire.decode(bytes)!!
        assertEquals(0xBEEF, tag)
        assertEquals(300, chunk.b)
        assertEquals(7, chunk.q)
        assertTrue(chunk.e)
        assertArrayEquals(audio, chunk.a)
        assertFalse(TalkWire.decode(TalkWire.encode(1, TalkChunk(0, 0, ByteArray(0))))!!.second.e)
        assertNull(TalkWire.decode(byteArrayOf(0, 1)))
        assertNull(TalkWire.decode(byteArrayOf(0, 1, 2, 0x80.toByte())))
    }

    @Test
    fun aCallsTagIsTheSameOnBothPhones() {
        // String.hashCode is specified, so the tag is a pure function of the call id: 16 bits of it.
        assertEquals("xbz1U6CpKTX-JPcI__p7eA".hashCode() and 0xFFFF, TalkWire.tagOf("xbz1U6CpKTX-JPcI__p7eA"))
    }

    // ---- TalkLink ----

    @Test
    fun aPeersLinkIsDirectThroughTheMeshOrNone() {
        assertEquals(TalkLink.DIRECT, TalkLink.of("p", neighbours = setOf("p", "r"), radioPath = true))
        assertEquals(TalkLink.MESH, TalkLink.of("p", neighbours = setOf("r"), radioPath = true))
        // Linked to others, with no fresh sign the peer is behind them: nothing this phone can see.
        assertEquals(TalkLink.NONE, TalkLink.of("p", neighbours = setOf("r"), radioPath = false))
    }

    /** §2.7: a relay that would cast to the peer is its link only where the radios have none, as `sendCallTalk` sends. */
    @Test
    fun aRelayIsThePeersLinkOnlyWhereTheRadiosHaveNone() {
        assertEquals(TalkLink.SPOOL, TalkLink.of("p", neighbours = emptySet(), radioPath = false, cast = true))
        assertEquals(TalkLink.SPOOL, TalkLink.of("p", neighbours = setOf("r"), radioPath = false, cast = true))
        assertEquals(TalkLink.DIRECT, TalkLink.of("p", neighbours = setOf("p"), radioPath = true, cast = true))
        assertEquals(TalkLink.MESH, TalkLink.of("p", neighbours = setOf("r"), radioPath = true, cast = true))
        assertEquals(TalkLink.NONE, TalkLink.of("p", neighbours = emptySet(), radioPath = false, cast = false))
    }

    // ---- TalkWire reports (C-3.11-10) ----

    @Test
    fun aReportRoundTripsAndIsNeverReadAsAChunk() {
        val bytes = TalkWire.encodeReport(0xBEEF, TalkReport(b = 300, radio = 4, relay = 9))
        val (tag, report) = TalkWire.decodeReport(bytes)!!
        assertEquals(0xBEEF, tag)
        assertEquals(300, report.b)
        assertEquals(4, report.radio)
        assertEquals(9, report.relay)
        // The chunk decoder refuses it, so a report never opens a burst in the player.
        assertNull(TalkWire.decode(bytes))
        // And a chunk is never read as a report, its last one included.
        assertNull(TalkWire.decodeReport(TalkWire.encode(1, TalkChunk(b = 0, q = 0, a = byteArrayOf(1), e = true))))
        // Trailing bytes or a cut varint are not one.
        assertNull(TalkWire.decodeReport(bytes + 0))
        assertNull(TalkWire.decodeReport(bytes.copyOf(bytes.size - 1) + 0x80.toByte()))
    }

    // ---- TalkQueue ----

    @Test
    fun aBurstPlaysInOrderOnceTwoChunksAreInEvenIfTheyCameReversed() {
        val queue = TalkQueue(prebufferMs = 400, gapMs = 500, endMs = 4_000)
        assertEquals(Step.Idle, queue.poll(0))
        assertTrue(queue.offer(chunk(q = 1), at = 10))
        // One chunk is not enough to start on: wait out the prebuffer.
        assertEquals(Step.Wait(400), queue.poll(10))
        assertTrue(queue.offer(chunk(q = 0), at = 20))
        assertPlay(queue.poll(20), q = 0, first = true, last = false)
        assertPlay(queue.poll(20), q = 1, first = false, last = false)
        assertTrue(queue.offer(chunk(q = 2, end = true), at = 300))
        assertPlay(queue.poll(300), q = 2, first = false, last = true)
        assertEquals(Step.Idle, queue.poll(300))
    }

    @Test
    fun aLoneFirstChunkStartsWhenThePrebufferRunsOut() {
        val queue = TalkQueue(prebufferMs = 400, gapMs = 500, endMs = 4_000)
        queue.offer(chunk(q = 0), at = 0)
        assertEquals(Step.Wait(1), queue.poll(399))
        assertPlay(queue.poll(400), q = 0, first = true, last = false)
        // A burst's last chunk alone starts it at once.
        val short = TalkQueue(prebufferMs = 400, gapMs = 500, endMs = 4_000)
        short.offer(chunk(q = 0, end = true), at = 0)
        assertPlay(short.poll(0), q = 0, first = true, last = true)
    }

    @Test
    fun aMissingChunkIsWaitedForThenSkipped() {
        val queue = TalkQueue(prebufferMs = 400, gapMs = 500, endMs = 4_000)
        queue.offer(chunk(q = 0), at = 0)
        queue.offer(chunk(q = 2), at = 0)
        assertPlay(queue.poll(0), q = 0, first = true, last = false)
        assertEquals(Step.Wait(500), queue.poll(100))
        // It shows up inside the gap: played in its place.
        queue.offer(chunk(q = 1), at = 200)
        assertPlay(queue.poll(200), q = 1, first = false, last = false)
        assertPlay(queue.poll(200), q = 2, first = false, last = false)
        // q 3 never comes; q 4 does.
        queue.offer(chunk(q = 4, end = true), at = 300)
        assertEquals(Step.Wait(500), queue.poll(300))
        assertPlay(queue.poll(800), q = 4, first = false, last = true)
        assertEquals(1, queue.skipped)
        // Too late: its burst is over.
        assertFalse(queue.offer(chunk(q = 3), at = 900))
    }

    @Test
    fun aBurstWhoseLastChunkIsLostClosesAfterTheSilence() {
        val queue = TalkQueue(prebufferMs = 400, gapMs = 500, endMs = 4_000)
        queue.offer(chunk(q = 0), at = 0)
        queue.offer(chunk(q = 1), at = 0)
        assertPlay(queue.poll(0), q = 0, first = true, last = false)
        assertPlay(queue.poll(0), q = 1, first = false, last = false)
        assertEquals(Step.Wait(4_000), queue.poll(0))
        val closing = queue.poll(4_000) as Step.Play
        assertTrue(closing.last)
        assertTrue(closing.cutShort)
        assertEquals(0, closing.audio.size)
        assertEquals(Step.Idle, queue.poll(4_000))
        // The chunks lost after the last that came are none the queue skipped: it counts the burst cut short instead.
        assertEquals(0, queue.skipped)
        assertEquals(1, queue.cutShort)
    }

    @Test
    fun aBurstThatEndsOnItsLastChunkIsNotCutShort() {
        val queue = TalkQueue(prebufferMs = 400, gapMs = 500, endMs = 4_000)
        queue.offer(chunk(q = 0), at = 0)
        queue.offer(chunk(q = 1, end = true), at = 0)
        assertPlay(queue.poll(0), q = 0, first = true, last = false)
        val last = queue.poll(0) as Step.Play
        assertTrue(last.last)
        assertFalse(last.cutShort)
        assertEquals(0, queue.cutShort)
    }

    @Test
    fun burstsPlayInTheOrderTheyWereSpokenAndADuplicateIsRefused() {
        val queue = TalkQueue(prebufferMs = 400, gapMs = 500, endMs = 4_000)
        queue.offer(chunk(b = 1, q = 0), at = 0)
        queue.offer(chunk(b = 1, q = 1, end = true), at = 0)
        queue.offer(chunk(b = 0, q = 0, end = true), at = 0)
        assertFalse(queue.offer(chunk(b = 0, q = 0, end = true), at = 0))
        assertPlay(queue.poll(0), b = 0, q = 0, first = true, last = true)
        assertPlay(queue.poll(0), b = 1, q = 0, first = true, last = false)
        assertPlay(queue.poll(0), b = 1, q = 1, first = false, last = true)
        assertFalse(queue.offer(chunk(b = 0, q = 1), at = 0))
    }

    private fun assertPlay(
        step: Step,
        q: Int,
        first: Boolean,
        last: Boolean,
        b: Int = 0,
    ) {
        assertTrue("$step is not a play", step is Step.Play)
        step as Step.Play
        assertEquals(b, step.burst)
        assertArrayEquals(audio(b, q), step.audio)
        assertEquals(first, step.first)
        assertEquals(last, step.last)
    }

    private fun chunk(
        b: Int = 0,
        q: Int,
        end: Boolean = false,
    ) = TalkChunk(b = b, q = q, a = audio(b, q), e = end)

    // A chunk's audio, distinct per (burst, chunk), so a play names which one it was.
    private fun audio(
        b: Int,
        q: Int,
    ) = OpusPackets.join(listOf(packet(size = 4, fill = (b * 16 + q).toByte())))

    /** An Opus packet of [size] bytes whose TOC says [config] and frame-count [code]; the rest is [fill]. */
    private fun packet(
        config: Int = 9,
        code: Int = 0,
        size: Int = 20,
        fill: Byte = 0,
    ) = ByteArray(size) { fill }.also { it[0] = ((config shl 3) or code).toByte() }
}
