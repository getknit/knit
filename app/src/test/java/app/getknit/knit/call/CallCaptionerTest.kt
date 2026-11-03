package app.getknit.knit.call

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A live call's captions (C-3.7-5): the peer's audio goes to a stream split at pauses, each pause makes a settled line,
 * a quiet call shows nothing, and a phone that falls behind stops rather than lag.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallCaptionerTest {
    private val now = 1_000L
    private var modelReady = true
    private var switchedOn = true

    /**
     * A model whose streams hear what [script] says for the samples each has been fed — a look's [Heard] — and settle,
     * at the end, on its words.
     */
    private inner class FakeSpeech(
        private val opens: Boolean = true,
        private val script: (Int) -> Heard = { Heard("", "") },
    ) : SpeechToText {
        val streams = mutableListOf<FakeStream>()
        val continuous = mutableListOf<Boolean>()
        var releases = 0

        override suspend fun open(continuous: Boolean): SpeechStream? {
            this.continuous += continuous
            return if (opens) FakeStream().also { streams += it } else null
        }

        override suspend fun release() {
            releases++
        }

        inner class FakeStream : SpeechStream {
            val adds = mutableListOf<Int>()
            var looks = 0
            var finished = false
            var closed = false
            val fed get() = adds.sum()

            override suspend fun add(samples: FloatArray): Boolean {
                check(!closed)
                adds += samples.size
                return true
            }

            override suspend fun look(): Heard {
                check(!closed)
                looks++
                return script(fed)
            }

            override suspend fun finish(): Heard {
                check(!closed)
                finished = true
                closed = true
                return Heard(script(fed).text)
            }

            override suspend fun close() {
                closed = true
            }
        }
    }

    private fun TestScope.captioner(speech: SpeechToText): CallCaptioner {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return CallCaptioner(
            speech = speech,
            ready = { modelReady },
            scope = CoroutineScope(SupervisorJob() + dispatcher),
            enabled = { switchedOn },
            dispatcher = dispatcher,
            clock = { now },
        )
    }

    private val CallCaptioner.lines get() = captions.value.lines

    @Test
    fun aLookSaysToSettleAtAPauseKeepWhileTalkingAndLetAQuietRunGo() {
        assertEquals(RunStep.SETTLE, CaptionPolicy.step(Heard("Hello there."), runMs = 2_000))
        assertEquals(RunStep.KEEP, CaptionPolicy.step(Heard("Hello there.", "and"), runMs = 2_000))
        assertEquals(RunStep.KEEP, CaptionPolicy.step(Heard("", "Hello"), runMs = 2_000))
        assertEquals(RunStep.KEEP, CaptionPolicy.step(Heard(""), runMs = CaptionPolicy.QUIET_RUN_MS - 1))
        assertEquals(RunStep.DROP, CaptionPolicy.step(Heard(""), runMs = CaptionPolicy.QUIET_RUN_MS))
        // Talking on with no pause: cut where it stands once the run is long.
        assertEquals(RunStep.SETTLE, CaptionPolicy.step(Heard("so", "and then"), runMs = CaptionPolicy.MAX_RUN_MS))
        assertEquals(RunStep.SETTLE, CaptionPolicy.step(Heard("", "and then"), runMs = CaptionPolicy.MAX_RUN_MS))
    }

    @Test
    fun wordsShowAsTheyComeAndAPauseSettlesThemIntoALine() =
        runTest {
            // Talking for the first second, then a pause the detector has closed.
            val speech = FakeSpeech(script = { fed -> if (fed < 24_000) Heard("Hello", "there") else Heard("Hello there.") })
            val c = captioner(speech)
            c.begin()
            val tap = c.source()
            tap.pcm(pcm(ms = 600), 48_000, 1)
            advanceUntilIdle()
            val line = c.lines.single()
            assertEquals(TranscriptLine.State.Listening, line.state)
            assertEquals("Hello", line.settled)
            assertEquals("there", line.live)
            tap.pcm(pcm(ms = 1_000), 48_000, 1)
            advanceUntilIdle()
            assertEquals(TranscriptLine.State.Text("Hello there."), c.lines.single().state)
            assertTrue(speech.streams.single().finished)
            assertEquals(listOf(true), speech.continuous)
            // The next audio is a fresh run, with a stream of its own.
            tap.pcm(pcm(ms = 600), 48_000, 1)
            advanceUntilIdle()
            assertEquals(2, speech.streams.size)
            assertEquals(listOf(0, 1), c.lines.map { it.burst })
        }

    @Test
    fun aQuietCallShowsNothingAndItsRunsAreLetGo() =
        runTest {
            val speech = FakeSpeech()
            val c = captioner(speech)
            c.begin()
            val tap = c.source()
            repeat((CaptionPolicy.QUIET_RUN_MS / 1_000 + 1).toInt()) {
                tap.pcm(pcm(ms = 1_000), 48_000, 1)
                advanceUntilIdle()
            }
            assertTrue(c.lines.isEmpty())
            val first = speech.streams.first()
            assertTrue(first.closed)
            assertTrue(!first.finished)
        }

    @Test
    fun switchedOffTheOpenRunSettlesAndNoAudioIsTaken() =
        runTest {
            val speech = FakeSpeech(script = { Heard("", "Hello") })
            val c = captioner(speech)
            c.begin()
            val tap = c.source()
            tap.pcm(pcm(ms = 600), 48_000, 1)
            advanceUntilIdle()
            switchedOn = false
            tap.pcm(pcm(ms = 600), 48_000, 1)
            advanceUntilIdle()
            assertEquals(TranscriptLine.State.Text("Hello"), c.lines.single().state)
            val stream = speech.streams.single()
            assertEquals(9_600, stream.fed)
            assertTrue(stream.finished)
            switchedOn = true
            tap.pcm(pcm(ms = 600), 48_000, 1)
            advanceUntilIdle()
            assertEquals(2, speech.streams.size)
        }

    @Test
    fun aSupersededSourceIsIgnored() =
        runTest {
            val speech = FakeSpeech(script = { Heard("", "Hello") })
            val c = captioner(speech)
            c.begin()
            val dropped = c.source()
            val replacement = c.source()
            dropped.pcm(pcm(ms = 600), 48_000, 1)
            advanceUntilIdle()
            assertTrue(speech.streams.isEmpty())
            replacement.pcm(pcm(ms = 600), 48_000, 1)
            advanceUntilIdle()
            assertEquals(9_600, speech.streams.single().fed)
        }

    @Test
    fun aPhoneFallingBehindStopsTheCaptionsAndKeepsWhatTheyHad() =
        runTest {
            val speech = FakeSpeech(script = { Heard("", "So the plan") })
            val c = captioner(speech)
            c.begin()
            val tap = c.source()
            tap.pcm(pcm(ms = 600), 48_000, 1)
            advanceUntilIdle()
            // More audio waiting than the model may lag by, with its stream already open.
            tap.pcm(pcm(ms = CaptionPolicy.BEHIND_MS.toInt() + 1_000), 48_000, 1)
            advanceUntilIdle()
            assertEquals(CaptionStatus.BEHIND, c.captions.value.status)
            assertEquals(TranscriptLine.State.Text("So the plan"), c.lines.single().state)
            assertTrue(speech.streams.single().closed)
            tap.pcm(pcm(ms = 600), 48_000, 1)
            advanceUntilIdle()
            assertEquals(1, speech.streams.size)
            // Off and on again is a fresh try.
            switchedOn = false
            tap.pcm(pcm(ms = 10), 48_000, 1)
            switchedOn = true
            assertEquals(CaptionStatus.ON, c.captions.value.status)
            tap.pcm(pcm(ms = 600), 48_000, 1)
            advanceUntilIdle()
            assertEquals(2, speech.streams.size)
        }

    @Test
    fun whatCameWhileTheModelLoadedIsNoBacklog() =
        runTest {
            val speech = FakeSpeech(script = { Heard("", "Hi") })
            val c = captioner(speech)
            c.begin()
            // The first feed carries all of it: the stream opens on it.
            c.source().pcm(pcm(ms = CaptionPolicy.BEHIND_MS.toInt() + 1_000), 48_000, 1)
            advanceUntilIdle()
            assertEquals(CaptionStatus.ON, c.captions.value.status)
            assertEquals("Hi", c.lines.single().live)
        }

    @Test
    fun aModelThatWillNotRunStopsTheCaptions() =
        runTest {
            val c = captioner(FakeSpeech(opens = false))
            c.begin()
            c.source().pcm(pcm(ms = 600), 48_000, 1)
            advanceUntilIdle()
            assertEquals(CaptionStatus.FAILED, c.captions.value.status)
        }

    @Test
    fun withNoModelTheAudioIsDropped() =
        runTest {
            modelReady = false
            val speech = FakeSpeech()
            val c = captioner(speech)
            c.begin()
            c.source().pcm(pcm(ms = 600), 48_000, 1)
            advanceUntilIdle()
            assertTrue(speech.streams.isEmpty())
            assertEquals(CaptionStatus.ON, c.captions.value.status)
        }

    @Test
    fun theCallsEndSettlesTheOpenRunThenLetsTheModelGoAndTheNextCallStartsAfresh() =
        runTest {
            val speech = FakeSpeech(script = { Heard("", "Bye") })
            val c = captioner(speech)
            c.begin()
            val tap = c.source()
            tap.pcm(pcm(ms = 600), 48_000, 1)
            advanceUntilIdle()
            c.end()
            advanceUntilIdle()
            assertEquals(TranscriptLine.State.Text("Bye"), c.lines.single().state)
            assertTrue(speech.streams.single().finished)
            assertEquals(1, speech.releases)
            // The ended call's source takes nothing.
            tap.pcm(pcm(ms = 600), 48_000, 1)
            advanceUntilIdle()
            assertEquals(1, speech.streams.size)
            c.begin()
            assertTrue(c.lines.isEmpty())
        }

    private fun pcm(
        ms: Int,
        rate: Int = 48_000,
    ): ByteBuffer {
        val samples = rate / 1_000 * ms
        return ByteBuffer.allocateDirect(samples * 2).order(ByteOrder.nativeOrder()).apply {
            val shorts = asShortBuffer()
            for (i in 0 until samples) shorts.put(((i % 48) * 100 - 2_400).toShort())
        }
    }
}
