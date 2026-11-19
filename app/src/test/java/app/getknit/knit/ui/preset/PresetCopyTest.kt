package app.getknit.knit.ui.preset

import app.getknit.knit.R
import app.getknit.knit.data.settings.Preset
import app.getknit.knit.data.settings.PresetChange
import app.getknit.knit.di.FACE_REDACTION_WIRED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A card or the switch dialog never names a plane this phone or build lacks (ADR 2026-11.j8pf). */
class PresetCopyTest {
    @Test
    fun theDialogLeavesOutAwareOnAPhoneWithoutItAndTranscriptsWhereSpeechIsNotWired() {
        assertFalse(shownOn(PresetChange.Switch.WIFI_AWARE, awareSupported = false, sttWired = true))
        assertTrue(shownOn(PresetChange.Switch.WIFI_AWARE, awareSupported = true, sttWired = true))
        assertFalse(shownOn(PresetChange.Switch.TRANSCRIPTS, awareSupported = true, sttWired = false))
        assertTrue(shownOn(PresetChange.Switch.TRANSCRIPTS, awareSupported = false, sttWired = true))
        // Everything else, a switch added later included, is always named: it tells others about this phone.
        (PresetChange.Switch.entries - setOf(PresetChange.Switch.WIFI_AWARE, PresetChange.Switch.TRANSCRIPTS))
            .forEach { assertTrue(it.name, shownOn(it, awareSupported = false, sttWired = false)) }
    }

    @Test
    fun theCardsLeaveOutAPlaneThePhoneOrBuildLacksAndKeepTheRest() {
        assertFalse(R.string.preset_battery_aware in presetLines(Preset.BATTERY, awareSupported = false, internetPlane = true))
        assertTrue(R.string.preset_battery_aware in presetLines(Preset.BATTERY, awareSupported = true, internetPlane = true))
        assertEquals(
            listOf(R.string.preset_everyday_relays, R.string.preset_everyday_previews, R.string.preset_everyday_downloads),
            presetLines(Preset.EVERYDAY, awareSupported = true, internetPlane = true),
        )
        assertEquals(
            listOf(R.string.preset_everyday_downloads),
            presetLines(Preset.EVERYDAY, awareSupported = true, internetPlane = false),
        )
        assertTrue(R.string.preset_private_offline in presetLines(Preset.PRIVATE, awareSupported = true, internetPlane = true))
        val offline = presetLines(Preset.PRIVATE, awareSupported = true, internetPlane = false)
        assertFalse(R.string.preset_private_offline in offline)
        assertTrue(R.string.preset_private_cues in offline)
        assertTrue(R.string.preset_private_screenshots in offline)
        assertEquals(FACE_REDACTION_WIRED, R.string.preset_private_faces in offline)
    }
}
