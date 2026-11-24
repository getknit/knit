package app.getknit.knit.ui.chat

import app.getknit.knit.mesh.sha256Hex
import app.getknit.knit.ui.chat.AttachmentExport.Companion.voiceFileName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * #34: a saved voice note's file name. Both inputs come off a peer's frame (the hash it named, the time its clock
 * claimed), so the name holds only digits and hex whatever they say, and the same note always gets the same name.
 */
class VoiceFileNameTest {
    private val hash = "3fa9c1d2e4b5a6978877665544332211ffeeddccbbaa99887766554433221100"
    private val utc = ZoneOffset.UTC
    private val sentAt = ZonedDateTime.of(2026, 11, 23, 14, 5, 12, 0, utc).toInstant().toEpochMilli()

    @Test
    fun aNoteIsNamedByItsTimeAndItsHashsFirstEightCharacters() {
        assertEquals("knit-voice-20261123-140512-3fa9c1d2.aac", voiceFileName(hash, sentAt, utc, "aac"))
    }

    @Test
    fun theTimeIsReadInThePhonesZone() {
        assertEquals(
            "knit-voice-20261123-160512-3fa9c1d2.aac",
            voiceFileName(hash, sentAt, ZoneId.of("Europe/Kyiv"), "aac"),
        )
    }

    @Test
    fun theExtensionIsTheOneHandedIn() {
        assertEquals("knit-voice-20261123-140512-3fa9c1d2.ogg", voiceFileName(hash, sentAt, utc, "ogg"))
    }

    @Test
    fun twoNotesSentInTheSameSecondGetTwoNames() {
        val other = "0badc0de" + hash.drop(8)
        assertNotEquals(voiceFileName(hash, sentAt, utc, "aac"), voiceFileName(other, sentAt, utc, "aac"))
    }

    /** A hash whose head is not lowercase hex never reaches the name: its own SHA-256 stands in. */
    @Test
    fun aHashThatIsNotHexIsNamedByItsOwnDigest() {
        for (odd in listOf("../../x/y/z", "3FA9C1D2E4B5", "ab", "", "3fa9c1d/")) {
            val name = voiceFileName(odd, sentAt, utc, "aac")
            assertEquals(
                "for \"$odd\"",
                "knit-voice-20261123-140512-${sha256Hex(odd.encodeToByteArray()).take(8)}.aac",
                name,
            )
        }
    }

    /** The sender's clock is unchecked: a time outside 2000..9999 drops the stamp rather than write a strange year. */
    @Test
    fun aTimeOutsideTheSaneYearsDropsTheStamp() {
        val lastOf1999 = ZonedDateTime.of(1999, 12, 31, 23, 59, 59, 0, utc).toInstant().toEpochMilli()
        val firstOf10000 = ZonedDateTime.of(10000, 1, 1, 0, 0, 0, 0, utc).toInstant().toEpochMilli()
        for (odd in listOf(0L, -1L, lastOf1999, firstOf10000, Long.MAX_VALUE, Long.MIN_VALUE)) {
            assertEquals("for $odd", "knit-voice-3fa9c1d2.aac", voiceFileName(hash, odd, utc, "aac"))
        }
    }

    @Test
    fun theSaneYearsKeepTheirStampToTheirEdges() {
        val firstOf2000 = ZonedDateTime.of(2000, 1, 1, 0, 0, 0, 0, utc).toInstant().toEpochMilli()
        val lastOf9999 = ZonedDateTime.of(9999, 12, 31, 23, 59, 59, 0, utc).toInstant().toEpochMilli()
        assertEquals("knit-voice-20000101-000000-3fa9c1d2.aac", voiceFileName(hash, firstOf2000, utc, "aac"))
        assertEquals("knit-voice-99991231-235959-3fa9c1d2.aac", voiceFileName(hash, lastOf9999, utc, "aac"))
    }

    /** Whatever the inputs, the name is digits, lowercase hex and the fixed words: nothing a path could hide in. */
    @Test
    fun theNameHoldsNoCharacterAPeerChose() {
        val shape = Regex("knit-voice-(\\d{8}-\\d{6}-)?[0-9a-f]{8}\\.aac")
        for (odd in listOf("a/b\\c:d*e?f\"g<h>i|j", "\u0000‮", hash)) {
            for (time in listOf(sentAt, Long.MIN_VALUE)) {
                val name = voiceFileName(odd, time, utc, "aac")
                assertTrue(name, shape.matches(name))
            }
        }
    }
}
