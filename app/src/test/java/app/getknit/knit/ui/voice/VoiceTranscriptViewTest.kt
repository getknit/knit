package app.getknit.knit.ui.voice

import app.getknit.knit.call.Heard
import app.getknit.knit.call.NoteStt
import app.getknit.knit.call.SttModelState
import app.getknit.knit.ui.chat.VoiceSpeechView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What a voice bubble shows of its note's text (ADR 2026-10.am8d), from the row, this process's work and the model. */
class VoiceTranscriptViewTest {
    private fun speech(
        model: SttModelState = SttModelState.Ready,
        notes: Map<String, NoteStt> = emptyMap(),
    ) = VoiceSpeechView(model, notes, modelBytes = 142_000_000)

    @Test
    fun nothingIsDrawnWhereSpeechIsOffOrTheBytesAreNotHere() {
        assertNull(voiceTranscriptView(stored = "Hi.", hash = "h1", ready = true, speech = null))
        assertNull(voiceTranscriptView(stored = null, hash = "h1", ready = false, speech = speech()))
        assertNull(voiceTranscriptView(stored = null, hash = null, ready = true, speech = speech()))
    }

    @Test
    fun theRowsStoredTextWinsOverEverythingElse() {
        val running = speech(notes = mapOf("h1" to NoteStt.Running(Heard("a", "b"))))
        assertEquals(VoiceTranscriptView.Text("Hi."), voiceTranscriptView("Hi.", "h1", true, running))
        assertEquals(VoiceTranscriptView.Text(""), voiceTranscriptView("", "h1", true, speech()))
    }

    @Test
    fun thisProcesssWorkOnTheNoteShowsUntilTheRowHasTheText() {
        fun view(note: NoteStt) = voiceTranscriptView(null, "h1", true, speech(notes = mapOf("h1" to note)))
        assertEquals(VoiceTranscriptView.Running("So the plan", "is"), view(NoteStt.Running(Heard(" So the plan ", "is "))))
        assertEquals(VoiceTranscriptView.Text("Done."), view(NoteStt.Done("Done.")))
        assertEquals(VoiceTranscriptView.Busy, view(NoteStt.Busy))
        assertEquals(VoiceTranscriptView.Failed, view(NoteStt.Failed))
    }

    @Test
    fun anUntouchedNoteOffersTranscribeOrShowsTheModelComingDown() {
        assertEquals(VoiceTranscriptView.Offer, voiceTranscriptView(null, "h1", true, speech()))
        assertEquals(VoiceTranscriptView.Offer, voiceTranscriptView(null, "h1", true, speech(model = SttModelState.Absent)))
        assertEquals(
            VoiceTranscriptView.Downloading(10, 100),
            voiceTranscriptView(null, "h1", true, speech(model = SttModelState.Downloading(10, 100))),
        )
    }
}
