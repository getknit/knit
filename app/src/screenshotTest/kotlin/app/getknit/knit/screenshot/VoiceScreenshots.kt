package app.getknit.knit.screenshot

import androidx.compose.runtime.Composable
import app.getknit.knit.ui.voice.VoiceNoteArrivingPreview
import app.getknit.knit.ui.voice.VoiceNoteBubblePreview
import app.getknit.knit.ui.voice.VoiceNoteLoadingPreview
import app.getknit.knit.ui.voice.VoiceNoteTranscribeOfferPreview
import app.getknit.knit.ui.voice.VoiceNoteTranscribedPreview
import app.getknit.knit.ui.voice.VoiceNoteTranscribingPreview
import app.getknit.knit.ui.voice.VoiceRecordingBarLockedPreview
import app.getknit.knit.ui.voice.VoiceRecordingBarPreview
import com.android.tools.screenshot.PreviewTest

// Screenshot tests over the previews in `ui.voice`.

@PreviewTest
@ComponentShots
@Composable
fun VoiceNoteBubble() = VoiceNoteBubblePreview()

@PreviewTest
@ComponentShots
@Composable
fun VoiceNoteLoading() = VoiceNoteLoadingPreview()

@PreviewTest
@ComponentShots
@Composable
fun VoiceNoteArriving() = VoiceNoteArrivingPreview()

@PreviewTest
@ComponentShots
@Composable
fun VoiceRecordingBar() = VoiceRecordingBarPreview()

@PreviewTest
@ComponentShots
@Composable
fun VoiceRecordingBarLocked() = VoiceRecordingBarLockedPreview()

@PreviewTest
@ComponentShots
@Composable
fun VoiceNoteTranscribed() = VoiceNoteTranscribedPreview()

@PreviewTest
@ComponentShots
@Composable
fun VoiceNoteTranscribing() = VoiceNoteTranscribingPreview()

@PreviewTest
@ComponentShots
@Composable
fun VoiceNoteTranscribeOffer() = VoiceNoteTranscribeOfferPreview()
