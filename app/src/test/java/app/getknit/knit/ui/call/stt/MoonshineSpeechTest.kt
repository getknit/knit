package app.getknit.knit.ui.call.stt

import ai.moonshine.voice.TranscriberOption
import ai.moonshine.voice.Transcript
import app.getknit.knit.call.Heard
import app.getknit.knit.call.SttArch
import app.getknit.knit.call.SttFile
import app.getknit.knit.call.SttModel
import app.getknit.knit.call.SttModelState
import app.getknit.knit.call.WalkiePolicy
import app.getknit.knit.moderation.FakeModelLoadJournal
import app.getknit.knit.moderation.ModelLoadGuard
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import ai.moonshine.voice.TranscriptLine as MoonshineLine

/**
 * The walkie transcript's speech model over a fake Moonshine runtime (C-3.11-9): one lease per model on disk and line
 * mode, streams that never load a model again only to find their handle gone, a failed load retried once the call ends,
 * a removed model let go at once, the names a call listens for, and the words split into settled and live.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MoonshineSpeechTest {
    /** The runtime's handles and streams, and any call that reached a handle already freed. */
    private class FakeRuntime : MoonshineRuntime {
        var failLoads = 0
        val loads = mutableListOf<List<TranscriberOption>>()
        val freed = mutableListOf<Int>()
        val freedStreams = mutableListOf<Pair<Int, Int>>()
        val keyterms = mutableListOf<String>()
        val callsOnFreed = mutableListOf<String>()
        var heard: (final: Boolean) -> Transcript? = { transcript() }
        private var nextHandle = 1
        private var nextStream = 1

        override fun load(
            dir: String,
            arch: SttArch,
            options: List<TranscriberOption>,
        ): Int {
            loads += options
            if (failLoads > 0) {
                failLoads--
                return -1
            }
            return nextHandle++
        }

        override fun free(handle: Int) {
            freed += handle
        }

        override fun describe(error: Int): String = "error $error"

        override fun setKeyterms(
            handle: Int,
            terms: String,
        ): Int = live(handle, "setKeyterms").also { keyterms += terms }

        override fun createStream(handle: Int): Int = live(handle, "createStream").let { nextStream++ }

        override fun startStream(
            handle: Int,
            stream: Int,
        ): Int = live(handle, "startStream")

        override fun stopStream(
            handle: Int,
            stream: Int,
        ): Int = live(handle, "stopStream")

        override fun freeStream(
            handle: Int,
            stream: Int,
        ) {
            live(handle, "freeStream")
            freedStreams += handle to stream
        }

        override fun addAudio(
            handle: Int,
            stream: Int,
            samples: FloatArray,
        ): Int = live(handle, "addAudio")

        override fun transcribe(
            handle: Int,
            stream: Int,
            final: Boolean,
        ): Transcript? = heard(final).also { live(handle, "transcribe") }

        // OK, noting a call on a handle the runtime has already freed.
        private fun live(
            handle: Int,
            call: String,
        ): Int {
            if (handle in freed) callsOnFreed += "$call on $handle"
            return MoonshineRuntime.OK
        }
    }

    private class FakeFiles : SttModelFiles {
        override val model =
            SttModel(
                id = "small",
                baseUrl = "https://example.test/small/",
                files = listOf(SttFile("encoder.ort", 1, "00")),
                arch = SttArch.SMALL_STREAMING,
            )
        override var generation = 0
        override val state = MutableStateFlow<SttModelState>(SttModelState.Ready)

        override fun paths(): Map<String, File>? =
            model.files.associate { it.name to File("/models/small", it.name) }.takeIf { state.value == SttModelState.Ready }
    }

    private val runtime = FakeRuntime()
    private val files = FakeFiles()

    private fun TestScope.speech() =
        MoonshineSpeech(files, ModelLoadGuard(FakeModelLoadJournal(), { null }, STAMP), backgroundScope, runtime = runtime)
            .also { runCurrent() }

    @Test
    fun aBurstsWordsComeSettledAndLiveThenWholeAtItsEnd() =
        runTest {
            runtime.heard = { final ->
                if (final) transcript("Meet me" to true, "at the north gate" to true) else transcript("Meet me" to true, "at the" to false)
            }
            val stream = present(speech().open(continuous = false))

            assertTrue(stream.add(FloatArray(8_000)))
            assertEquals(Heard("Meet me", "at the"), stream.look())
            assertEquals(Heard("Meet me at the north gate"), stream.finish())
            // The load's probe stream, then the burst's.
            assertEquals(listOf(1 to 1, 1 to 2), runtime.freedStreams)
        }

    @Test
    fun aWalkieBurstIsOneLineAndALiveCallSplitsAtPauses() =
        runTest {
            val speech = speech()
            speech.open(continuous = false)
            speech.open(continuous = true)

            val whole = runtime.loads[0].associate { it.name to it.value }
            assertEquals("0", whole["vad_threshold"])
            assertEquals("${MoonshineSpeech.MAX_LINE_S}", whole["vad_max_segment_duration"])
            val split = runtime.loads[1].associate { it.name to it.value }
            assertFalse("vad_threshold" in split)
            assertEquals("false", split["log_output_text"])
        }

    // A burst runs no longer than the stretch Moonshine advises for one line: the length is held at the sender, since
    // the detector cuts nothing with its threshold at 0.
    @Test
    fun aWholeBurstFitsInOneLine() {
        assertTrue(WalkiePolicy.MAX_BURST_MS <= MoonshineSpeech.MAX_LINE_S * 1_000L)
    }

    @Test
    fun newFilesBuildANewModelAndFreeTheOld() =
        runTest {
            val speech = speech()
            speech.open(continuous = false)
            files.generation++
            speech.open(continuous = false)
            runCurrent()

            assertEquals(2, runtime.loads.size)
            assertEquals(listOf(1), runtime.freed)
        }

    // The walkie and a live call build the model differently, so one replaces the other's; the stream still open on
    // the one freed fails rather than load it back to reach a handle that is gone (pre-fix: a third load, held 2 min).
    @Test
    fun aStreamOnAReplacedModelFailsWithoutLoadingItAgain() =
        runTest {
            val speech = speech()
            val walkie = present(speech.open(continuous = false))
            present(speech.open(continuous = true))
            runCurrent()
            assertEquals(listOf(1), runtime.freed)

            assertFalse(walkie.add(FloatArray(1_600)))
            assertNull(walkie.look())
            walkie.close()

            assertEquals(2, runtime.loads.size)
            assertEquals(emptyList<String>(), runtime.callsOnFreed)
        }

    // Pre-fix, a load that failed once (a phone short of memory mid-call) spent the lease for the life of the process.
    @Test
    fun aFailedLoadIsTriedAgainOnceTheCallEnds() =
        runTest {
            runtime.failLoads = 1
            val speech = speech()

            assertNull(speech.open(continuous = false))
            assertNull(speech.open(continuous = false))
            assertEquals("spent until the call ends", 1, runtime.loads.size)

            speech.release()

            present(speech.open(continuous = false))
            assertEquals(2, runtime.loads.size)
        }

    @Test
    fun aRemovedModelIsLetGoAtOnce() =
        runTest {
            val speech = speech()
            present(speech.open(continuous = false))
            assertTrue(speech.resident)

            files.state.value = SttModelState.Absent
            runCurrent()

            assertFalse(speech.resident)
            assertEquals(listOf(1), runtime.freed)
            assertNull(speech.open(continuous = false))
        }

    @Test
    fun theNamesACallListensForAreGivenWhenTheyChange() =
        runTest {
            val speech = speech()
            speech.keyterms = listOf("Ada", "Oisín")
            speech.open(continuous = false)
            speech.open(continuous = false)
            speech.keyterms = emptyList()
            speech.open(continuous = false)

            assertEquals(listOf("Ada,Oisín", ""), runtime.keyterms)
        }

    @Test
    fun aRefusedTranscriptFailsTheStreamAndClosesIt() =
        runTest {
            val stream = present(speech().open(continuous = false))
            runtime.heard = { null }

            assertNull(stream.look())
            assertFalse(stream.add(FloatArray(1_600)))
            assertEquals("the probe's, then the burst's, once", listOf(1 to 1, 1 to 2), runtime.freedStreams)
        }

    private companion object {
        const val STAMP = "16|test"

        fun <T> present(value: T?): T = value ?: throw AssertionError("expected a stream")

        fun transcript(vararg lines: Pair<String, Boolean>) =
            Transcript().apply {
                this.lines =
                    lines.map { (text, complete) ->
                        MoonshineLine().apply {
                            this.text = text
                            isComplete = complete
                        }
                    }
            }
    }
}
