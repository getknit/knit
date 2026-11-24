package app.getknit.knit.data

import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * #34: a voice note saved into shared storage through the platform's MediaStore, which the JVM tests replace with a
 * mock. On the emulator's own MediaProvider: the note lands in [GallerySaver.voiceFolder] as its own bytes, a second
 * save of the same name writes nothing, a deleted copy is written again, and a picture still lands in `Pictures/Knit`
 * through the insert the three saves share.
 *
 * Every name carries this run's own id, so a row a killed run left behind is never read as this run's, and every row
 * this run wrote is deleted after it.
 *
 *     ./gradlew :app:pixel7api33DebugAndroidTest \
 *         -Pandroid.testInstrumentationRunnerArguments.class=app.getknit.knit.data.GallerySaverInstrumentedTest
 */
class GallerySaverInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val saver = GallerySaver(context)
    private val run = UUID.randomUUID().toString().take(8)

    /** Every row this run wrote, found by its id whatever an assertion left undone. */
    @After
    fun tearDown() {
        for (collection in listOf(AUDIO, IMAGES)) {
            rowsNamedLike(collection, "%$run%").forEach { runCatching { context.contentResolver.delete(it, null, null) } }
        }
    }

    @Test
    fun aVoiceNoteLandsInItsFolderAsItsOwnBytesAndASecondSaveWritesNothing() {
        val note = note()
        val name = "knit-voice-test-$run.aac"

        assertEquals(GallerySaver.VoiceSave.Saved, runBlocking { saver.saveVoice(note, name, VoiceAudio.MIME) })
        val rows = rows(AUDIO, name, GallerySaver.voiceFolder)
        assertEquals("one row under ${GallerySaver.voiceFolder}", 1, rows.size)
        assertArrayEquals("the note's own bytes", note, read(rows.single()))

        assertEquals(GallerySaver.VoiceSave.AlreadySaved, runBlocking { saver.saveVoice(note, name, VoiceAudio.MIME) })
        assertEquals("the second save wrote no copy", 1, rows(AUDIO, name, GallerySaver.voiceFolder).size)
        assertEquals("nor one beside it under MediaStore's \" (1)\"", 0, rowsNamedLike(AUDIO, "knit-voice-test-$run (%").size)
    }

    @Test
    fun aCopyTheUserDeletedIsWrittenAgain() {
        val note = note()
        val name = "knit-voice-test-$run.aac"
        assertEquals(GallerySaver.VoiceSave.Saved, runBlocking { saver.saveVoice(note, name, VoiceAudio.MIME) })
        rows(AUDIO, name, GallerySaver.voiceFolder).forEach { context.contentResolver.delete(it, null, null) }

        assertEquals(GallerySaver.VoiceSave.Saved, runBlocking { saver.saveVoice(note, name, VoiceAudio.MIME) })
        assertEquals(1, rows(AUDIO, name, GallerySaver.voiceFolder).size)
    }

    @Test
    fun anythingButAudioIsRefusedAndWritesNoRow() {
        val name = "knit-voice-test-$run.aac"

        assertEquals(GallerySaver.VoiceSave.Failed, runBlocking { saver.saveVoice(note(), name, "image/png") })
        assertEquals(GallerySaver.VoiceSave.Failed, runBlocking { saver.saveVoice(ByteArray(0), name, VoiceAudio.MIME) })
        assertEquals(0, rows(AUDIO, name, GallerySaver.voiceFolder).size)
    }

    /** The picture save now goes through the insert the voice save shares: it still lands where it did. */
    @Test
    fun aPictureStillLandsInPicturesKnit() {
        val png = png()
        val hash = "test$run"

        assertEquals(true, runBlocking { saver.saveToPictures(png, hash, "image/png") })
        val rows = rows(IMAGES, "knit-$hash.png", "Pictures/Knit")
        assertEquals(1, rows.size)
        assertArrayEquals(png, read(rows.single()))
    }

    /** Two seconds of a tone through the phone's AAC encoder, framed as `VoiceRecorder` frames it. */
    private fun note(): ByteArray = AudioFixtures.adts(AudioFixtures.tone(rate = RATE, toneMs = NOTE_MS, totalMs = NOTE_MS), RATE)

    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(PNG_SIDE, PNG_SIDE, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    /** This app's rows in [collection] named [name] under [folder]. */
    private fun rows(
        collection: Uri,
        name: String,
        folder: String,
    ): List<Uri> =
        query(
            collection,
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
            arrayOf(name, "$folder/"),
        )

    private fun rowsNamedLike(
        collection: Uri,
        pattern: String,
    ): List<Uri> = query(collection, "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?", arrayOf(pattern))

    private fun query(
        collection: Uri,
        selection: String,
        args: Array<String>,
    ): List<Uri> {
        val found = mutableListOf<Uri>()
        context.contentResolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), selection, args, null)?.use { c ->
            while (c.moveToNext()) found += ContentUris.withAppendedId(collection, c.getLong(0))
        }
        return found
    }

    private fun read(uri: Uri): ByteArray = checkNotNull(context.contentResolver.openInputStream(uri)).use { it.readBytes() }

    private companion object {
        val AUDIO: Uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val IMAGES: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        const val RATE = 16_000
        const val NOTE_MS = 2_000
        const val PNG_SIDE = 4
    }
}
