package app.getknit.knit.call

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A voice note's text on tap (ADR 2026-10.am8d): a short note is one whole stream, a long one runs a minute at a time
 * and its runs are joined, the text is saved once, a call comes first, and the model is let go when the queue drains.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceNoteTranscriberTest {
    private var calling = false
    private val saved = mutableListOf<Pair<String, String>>()

    /**
     * A model whose [n]th stream hears what [script] says for the samples it has been fed, and settles, at the end, on
     * its words. [onAdd] runs on every add, with the count of adds across every stream so far.
     */
    private inner class FakeSpeech(
        private val opens: Boolean = true,
        private val onAdd: (Int) -> Unit = {},
        private val script: (n: Int, fed: Int) -> Heard = { _, _ -> Heard("") },
    ) : SpeechToText {
        val streams = mutableListOf<FakeStream>()
        val continuous = mutableListOf<Boolean>()
        var releases = 0
        private var adds = 0

        // The most streams open at once: a real model call suspends, so a second note could open one beside the first.
        var mostOpen = 0
        private val openNow get() = streams.count { !it.closed }

        override suspend fun open(continuous: Boolean): SpeechStream? {
            this.continuous += continuous
            if (!opens) return null
            return FakeStream(streams.size).also {
                streams += it
                mostOpen = maxOf(mostOpen, openNow)
            }
        }

        override suspend fun release() {
            releases++
        }

        inner class FakeStream(
            private val n: Int,
        ) : SpeechStream {
            var fed = 0
            var finished = false
            var closed = false

            override suspend fun add(samples: FloatArray): Boolean {
                check(!closed)
                fed += samples.size
                onAdd(++adds)
                // Suspends as the model does, so another note's coroutine gets a turn mid-note.
                yield()
                return true
            }

            override suspend fun look(): Heard {
                check(!closed)
                return script(n, fed)
            }

            override suspend fun finish(): Heard {
                check(!closed)
                finished = true
                closed = true
                return Heard(script(n, fed).text)
            }

            override suspend fun close() {
                closed = true
            }
        }
    }

    private fun TestScope.transcriber(
        speech: SpeechToText,
        audio: FloatArray? = seconds(10),
    ): VoiceNoteTranscriber {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return VoiceNoteTranscriber(
            speech = speech,
            pcm = { _, _ -> audio },
            save = { hash, text -> saved += hash to text },
            callActive = { calling },
            scope = CoroutineScope(SupervisorJob() + dispatcher),
            dispatcher = dispatcher,
            clock = { 0L },
        )
    }

    private fun seconds(s: Int) = FloatArray(s * SttPolicy.SAMPLE_RATE)

    @Test
    fun aNoteUpToTheWholeLimitIsOneWholeStreamAndALongerOneIsSplit() {
        assertFalse(VoiceNotePolicy.continuous(VoiceNotePolicy.WHOLE_MAX_MS))
        assertTrue(VoiceNotePolicy.continuous(VoiceNotePolicy.WHOLE_MAX_MS + 1))
    }

    @Test
    fun aShortNoteIsReadWholeSavedOnceAndTheModelLetGo() =
        runTest {
            val speech = FakeSpeech(script = { _, fed -> if (fed < seconds(10).size) Heard("", "see you") else Heard("See you at six.") })
            val t = transcriber(speech)
            assertTrue(t.transcribe("h1", "k1"))
            assertEquals(NoteStt.Running(), t.state.value["h1"])
            advanceUntilIdle()
            assertEquals(listOf(false), speech.continuous)
            assertTrue(speech.streams.single().finished)
            assertEquals(seconds(10).size, speech.streams.single().fed)
            assertEquals(listOf("h1" to "See you at six."), saved)
            assertEquals(NoteStt.Done("See you at six."), t.state.value["h1"])
            assertEquals(1, speech.releases)
        }

    @Test
    fun aLongNoteRunsAMinuteAtATimeThroughPausesAndItsRunsJoinedInOrder() =
        runTest {
            // Every look finds a pause (all settled, nothing live): a call's captions would finish the run there, and on
            // a phone that lost the next sentence's first words. A note's run goes on to the minute.
            val speech = FakeSpeech(script = { n, fed -> Heard("Run ${n + 1}, ${fed / SttPolicy.SAMPLE_RATE} s.") })
            val t = transcriber(speech, audio = seconds(150))
            t.transcribe("h1", null)
            advanceUntilIdle()
            val minute = (CaptionPolicy.MAX_RUN_MS / 1_000).toInt()
            assertEquals(listOf(minute, minute, 150 - 2 * minute), speech.streams.map { it.fed / SttPolicy.SAMPLE_RATE })
            assertEquals(List(3) { true }, speech.continuous)
            assertTrue(speech.streams.all { it.closed })
            assertEquals("Run 1, 60 s. Run 2, 60 s. Run 3, 30 s.", saved.single().second)
        }

    @Test
    fun aRunIsFinishedAtTheMinuteNeverAtAPauseAndDroppedAfterThirtyQuietSeconds() {
        val paused = Heard("Which way to the falls?")
        assertEquals(RunStep.KEEP, VoiceNotePolicy.step(paused, CaptionPolicy.MAX_RUN_MS - 1))
        assertEquals(RunStep.SETTLE, VoiceNotePolicy.step(paused, CaptionPolicy.MAX_RUN_MS))
        assertEquals(RunStep.SETTLE, VoiceNotePolicy.step(Heard("Take the", "left fork"), CaptionPolicy.MAX_RUN_MS))
        assertEquals(RunStep.KEEP, VoiceNotePolicy.step(Heard(""), CaptionPolicy.QUIET_RUN_MS - 1))
        assertEquals(RunStep.DROP, VoiceNotePolicy.step(Heard(""), CaptionPolicy.QUIET_RUN_MS))
    }

    @Test
    fun aNoteWithNoSpeechIsSavedEmptySoItIsNotOfferedAgain() =
        runTest {
            val t = transcriber(FakeSpeech())
            t.transcribe("h1", null)
            advanceUntilIdle()
            assertEquals(listOf("h1" to ""), saved)
            assertEquals(NoteStt.Done(""), t.state.value["h1"])
        }

    @Test
    fun aSecondTapOnANoteStillQueuedIsIgnored() =
        runTest {
            val speech = FakeSpeech(script = { _, _ -> Heard("Hi.") })
            val t = transcriber(speech)
            assertTrue(t.transcribe("h1", null))
            assertFalse(t.transcribe("h1", null))
            advanceUntilIdle()
            assertEquals(1, speech.streams.size)
            assertEquals(1, saved.size)
        }

    @Test
    fun notesAreTranscribedOneAtATimeAndTheModelLetGoOnlyOnceTheQueueDrains() =
        runTest {
            val speech = FakeSpeech(script = { _, _ -> Heard("Hi.") })
            val t = transcriber(speech)
            t.transcribe("h1", null)
            t.transcribe("h2", null)
            advanceUntilIdle()
            assertEquals(listOf("h1", "h2"), saved.map { it.first })
            assertEquals("never two notes in the model at once", 1, speech.mostOpen)
            assertEquals(1, speech.releases)
        }

    @Test
    fun aTapDuringACallIsRefusedWithoutTouchingTheModel() =
        runTest {
            calling = true
            val speech = FakeSpeech()
            val t = transcriber(speech)
            assertFalse(t.transcribe("h1", null))
            advanceUntilIdle()
            assertEquals(NoteStt.Busy, t.state.value["h1"])
            assertTrue(speech.continuous.isEmpty())
            assertEquals(0, speech.releases)
            // Once the call is over the same tap runs.
            calling = false
            assertTrue(t.transcribe("h1", null))
            advanceUntilIdle()
            assertEquals(1, saved.size)
        }

    @Test
    fun aCallThatBeginsMidNoteStopsItSavesNothingAndKeepsTheModel() =
        runTest {
            val speech = FakeSpeech(onAdd = { adds -> if (adds == 3) calling = true }, script = { _, _ -> Heard("", "and") })
            val t = transcriber(speech)
            t.transcribe("h1", null)
            advanceUntilIdle()
            assertEquals(NoteStt.Busy, t.state.value["h1"])
            assertTrue(saved.isEmpty())
            val stream = speech.streams.single()
            assertTrue(stream.closed)
            assertFalse(stream.finished)
            // The call's own streams may be open on the model: it is not let go under them.
            assertEquals(0, speech.releases)
        }

    @Test
    fun aModelThatWillNotRunOrANoteThatWillNotDecodeFails() =
        runTest {
            val t = transcriber(FakeSpeech(opens = false))
            t.transcribe("h1", null)
            advanceUntilIdle()
            assertEquals(NoteStt.Failed, t.state.value["h1"])

            val undecodable = transcriber(FakeSpeech(), audio = null)
            undecodable.transcribe("h2", null)
            advanceUntilIdle()
            assertEquals(NoteStt.Failed, undecodable.state.value["h2"])
            assertTrue(saved.isEmpty())
            // A failed note may be tapped again.
            assertTrue(undecodable.transcribe("h2", null))
        }
}
