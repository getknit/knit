package app.getknit.knit.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What [TraceText] leaves in a printed trace, which is all a Play report keeps: the frame format `scripts/trace-text.py`
 * reads, frames any parser takes for a method, and a text that outlives the wrappers a crash arrives in.
 */
class TraceTextTest {
    private val shape = "3 reads, the same bytes every time: 654 B {hybridPriv:[i×147 sigPriv:[i×154]@274 !@654"

    @Test
    fun `a frame is the documented format`() {
        val frame = TraceText.frames("567 B {i×2_").single()
        assertEquals("knit.TraceText", frame.className)
        assertEquals("t567_20B_20_7bi_c3_972_5f", frame.methodName)
        assertEquals("TraceText", frame.fileName)
        assertEquals(1, frame.lineNumber)
    }

    @Test
    fun `a long text splits over numbered frames that read back whole`() {
        val text = shape.repeat(5)
        val frames = TraceText.frames(text)

        assertTrue(frames.size > 1)
        assertEquals((1..frames.size).toList(), frames.map { it.lineNumber })
        frames.forEach { assertTrue(it.methodName, Regex("""t\w{1,${TraceText.CHUNK_CHARS}}""").matches(it.methodName)) }
        assertEquals(text, carriedText(TraceText.carry(RuntimeException(), text).stackTraceToString()))
    }

    @Test
    fun `a text past the cap keeps its head`() {
        val frames = TraceText.frames("a".repeat(TraceText.CHUNK_CHARS * TraceText.MAX_FRAMES) + "tail")
        assertEquals(TraceText.MAX_FRAMES, frames.size)
        assertEquals(TraceText.MAX_FRAMES, frames.last().lineNumber)
        assertFalse(frames.any { it.methodName.contains("tail") })
    }

    @Test
    fun `the frames go ahead of the real ones and leave them as they were`() {
        val real = throwableWith("boom").stackTrace.toList()
        val thrown = TraceText.carry(throwableWith("boom"), shape)
        val carried = TraceText.frames(shape)

        assertEquals(carried + real, thrown.stackTrace.toList())
    }

    @Test
    fun `the text outlives the wrappers a crash arrives in`() {
        val inner = TraceText.carry(IllegalStateException("not minting"), shape)
        // Koin's way: each wrapper is thrown from the same stack, so printing trims the causes down to what differs.
        val outer = RuntimeException("wrapper", RuntimeException("wrapper", inner))
        val printed = outer.stackTraceToString()

        assertTrue(printed, printed.contains(" more"))
        assertEquals(shape, carriedText(printed))
    }

    @Test
    fun `a crash report keeps the frames where the redactor takes the message apart`() {
        val report = CrashRedactor.redact(TraceText.carry(IllegalStateException(shape), shape).stackTraceToString())
        val message = report.lineSequence().first()

        assertFalse(message, message.contains("×"))
        assertEquals(shape, carriedText(report))
    }
}
