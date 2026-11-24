package app.getknit.knit.ui.chat

import app.getknit.knit.data.VoiceAudio
import app.getknit.knit.ui.chat.AttachmentExport.Companion.voiceSaveType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #34: the type a saved voice note is written under. The row's MIME is the sender's, and the MIME and extension a save
 * writes decide how MediaStore scans the file and which app opens it, so only a fixed list of audio formats gets
 * through, each under Knit's own MIME and extension.
 */
class VoiceSaveTypeTest {
    @Test
    fun aRecordedNoteIsSavedAsAac() {
        assertEquals(AttachmentExport.VoiceType(VoiceAudio.MIME, "aac"), voiceSaveType(VoiceAudio.MIME))
        assertEquals(AttachmentExport.VoiceType(VoiceAudio.MIME, "aac"), voiceSaveType(null))
    }

    @Test
    fun aPickedFileKeepsItsFormatUnderKnitsOwnNameForIt() {
        assertEquals(AttachmentExport.VoiceType("audio/mp4", "m4a"), voiceSaveType("audio/x-m4a"))
        assertEquals(AttachmentExport.VoiceType("audio/mpeg", "mp3"), voiceSaveType("audio/mp3"))
        assertEquals(AttachmentExport.VoiceType("audio/ogg", "ogg"), voiceSaveType("audio/opus"))
        assertEquals(AttachmentExport.VoiceType("audio/x-wav", "wav"), voiceSaveType("audio/wav"))
    }

    /** The sender's spelling of a listed type is read without its case or parameters, never written. */
    @Test
    fun aListedTypeIsReadWithoutItsCaseOrParameters() {
        assertEquals(AttachmentExport.VoiceType("audio/ogg", "ogg"), voiceSaveType("Audio/OGG; codecs=opus"))
    }

    /** A playlist, MIDI or any other audio type the list does not name is refused, never saved under its own type. */
    @Test
    fun anyOtherAudioTypeIsRefused() {
        val odd = listOf("audio/x-mpegurl", "audio/mpegurl", "audio/x-scpls", "audio/midi", "audio/x-ms-wma", "audio/", "audio/aac/../x")
        for (odd in odd) {
            assertNull(odd, voiceSaveType(odd))
        }
    }
}
