package app.getknit.knit.ui.voice

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.getknit.knit.R
import app.getknit.knit.call.NoteStt
import app.getknit.knit.call.SttModelState
import app.getknit.knit.data.VoiceAudio
import app.getknit.knit.data.relay.AttachmentWait
import app.getknit.knit.mesh.ArrivingFile
import app.getknit.knit.ui.call.mb
import app.getknit.knit.ui.chat.ArrivalIndicator
import app.getknit.knit.ui.chat.VoiceSpeechView
import app.getknit.knit.ui.chat.arrivalText
import app.getknit.knit.ui.chat.attachmentWaitHint
import app.getknit.knit.ui.preview.KnitPreview
import app.getknit.knit.ui.theme.KnitMotion

/*
 * The voice-note surfaces: the waveform, the bubble a received/sent note renders as, and the review row the
 * composer shows while a just-recorded note is staged. They live here rather than in `ChatScreen.kt` because
 * that file is already long and none of this is chat-specific — a voice note draws the same anywhere.
 */

/** Bars drawn when a note's waveform hasn't been derived yet — a flat, obviously-inert placeholder. */
private val FLAT_WAVEFORM = FloatArray(VoiceAudio.PEAK_COUNT) { 0.12f }

/**
 * The waveform: one bar per peak, filled up to [progress] and dimmed beyond it, with a tap anywhere seeking
 * to that fraction. Bars are drawn from the *stored* peaks, so the same note looks identical on every device
 * that holds it.
 *
 * A minimum bar height is enforced: a silent passage would otherwise draw nothing at all and the waveform
 * would appear to have holes in it rather than quiet parts.
 */
@Composable
fun VoiceWaveform(
    peaks: FloatArray?,
    progress: Float,
    playedColor: Color,
    remainingColor: Color,
    modifier: Modifier = Modifier,
    onSeek: ((Float) -> Unit)? = null,
) {
    val bars = peaks ?: FLAT_WAVEFORM
    // Playback position arrives on a poll, so the fill used to advance a whole bar at a time. Easing it
    // makes the same positions read as a note playing rather than as a progress bar being written to. A
    // seek animates too, which is the right answer: the jump is deliberate, and seeing it travel is how the
    // user knows where they came from.
    val filled by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = KnitMotion.fastEffects(),
        label = "voiceProgress",
    )
    Canvas(
        modifier =
            modifier
                .height(WAVEFORM_HEIGHT)
                .then(
                    if (onSeek == null) {
                        Modifier
                    } else {
                        Modifier.pointerInput(bars) {
                            detectTapGestures { offset ->
                                onSeek((offset.x / size.width.toFloat()).coerceIn(0f, 1f))
                            }
                        }
                    },
                ),
    ) {
        val count = bars.size
        val slot = size.width / count
        val barWidth = (slot * BAR_WIDTH_FRACTION).coerceAtLeast(1f)
        val playedBars = filled * count
        for (i in 0 until count) {
            val height = (bars[i].coerceIn(0f, 1f) * size.height).coerceAtLeast(size.height * MIN_BAR_FRACTION)
            val left = i * slot + (slot - barWidth) / 2f
            drawRoundRect(
                color = if (i < playedBars) playedColor else remainingColor,
                topLeft = Offset(left, (size.height - height) / 2f),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
        }
    }
}

/**
 * A voice note in a chat bubble: play/pause, the waveform, and the elapsed-or-total time.
 *
 * Mirrors `AttachmentImage`'s three-state shape. [ready] false means the bytes are still being pulled, and
 * the row shows the same spinner-and-explanation an undelivered photo does — a voice note is fetched by the
 * identical content-addressed machinery, so it arrives on exactly the same terms.
 *
 * There is no hidden/flagged state, unlike an image: no on-device model can screen speech, so a voice note
 * carries no verdict to act on (see `docs/CONTENT_MODERATION.md`).
 *
 * [onLongClick] is wired here as well as on the bubble because this row consumes taps itself, and a
 * long-press that landed on it would otherwise never reach the bubble's reaction picker.
 */
@Composable
fun VoiceNoteBubble(
    ready: Boolean,
    durationMs: Int?,
    peaks: FloatArray?,
    positionMs: Int?,
    playing: Boolean,
    accent: Color,
    onToggle: () -> Unit,
    onSeek: (Float) -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    // Which plane can still bring the bytes while [ready] is false (the placeholder's second line).
    wait: AttachmentWait = AttachmentWait.Nearby,
    // The blob's hash, which keys the placeholder's spinner-to-hourglass settle.
    hash: String? = null,
    // How far the bytes have got while they stream in over a nearby link (#115); null while none is carrying them.
    arrival: ArrivingFile? = null,
    // The note's text and its control (ADR 2026-10.am8d); null where speech is off, which draws the row alone.
    transcript: VoiceTranscriptView? = null,
    onTranscribe: () -> Unit = {},
) {
    if (!ready) {
        Row(
            modifier = modifier.width(BUBBLE_WIDTH).padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArrivalIndicator(key = hash, arrival = arrival, size = 20.dp, color = accent)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = arrival?.let { arrivalText(it) } ?: stringResource(R.string.chat_voice_loading),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                attachmentWaitHint(wait, arrival)?.let { hint ->
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("chat_attachment_wait"),
                    )
                }
            }
        }
        return
    }

    val total = durationMs ?: 0
    val position = positionMs ?: 0
    // Before playback starts the bubble shows the note's *length*; during playback it counts up. That is the
    // convention every voice-note UI uses, and it means the number is never uselessly 0:00.
    val shown = if (position > 0) position else total
    val progress = if (total > 0) position.toFloat() / total else 0f
    val label = stringResource(R.string.chat_voice_desc, formatDuration(shown))

    if (transcript == null) {
        VoiceNoteRow(label, shown, progress, playing, accent, peaks, onToggle, onSeek, onLongClick, modifier)
        return
    }
    Column(modifier = modifier.width(BUBBLE_WIDTH), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        VoiceNoteRow(label, shown, progress, playing, accent, peaks, onToggle, onSeek, onLongClick)
        VoiceTranscript(transcript, onTranscribe)
    }
}

@Composable
private fun VoiceNoteRow(
    label: String,
    shown: Int,
    progress: Float,
    playing: Boolean,
    accent: Color,
    peaks: FloatArray?,
    onToggle: () -> Unit,
    onSeek: (Float) -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.width(BUBBLE_WIDTH).padding(vertical = 2.dp).semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PlayButton(playing = playing, accent = accent, onClick = onToggle, onLongClick = onLongClick)
        VoiceWaveform(
            peaks = peaks,
            progress = progress,
            playedColor = accent,
            remainingColor = accent.copy(alpha = INACTIVE_ALPHA),
            onSeek = { fraction -> onSeek(fraction) },
            modifier = Modifier.weight(1f),
        )
        Text(
            text = formatDuration(shown),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // The row already announces the duration as part of its own description; letting TalkBack read
            // the digits again would say the length twice.
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

/**
 * What a voice bubble shows of its note's text (ADR 2026-10.am8d), from [voiceTranscriptView]: the words, the words so
 * far while the model runs, or the control that asks for them.
 */
sealed interface VoiceTranscriptView {
    /** The note's words; empty when the model heard no speech. */
    data class Text(
        val text: String,
    ) : VoiceTranscriptView

    /** The model is on it: [settled] will stay, [live] may still change. */
    data class Running(
        val settled: String,
        val live: String,
    ) : VoiceTranscriptView

    /** Not transcribed yet: the Transcribe control. */
    data object Offer : VoiceTranscriptView

    /** The speech model is coming down: how far. */
    data class Downloading(
        val done: Long,
        val total: Long,
    ) : VoiceTranscriptView

    /** A call holds the model: try once it is over. */
    data object Busy : VoiceTranscriptView

    /** The model would not run on this note. */
    data object Failed : VoiceTranscriptView
}

/**
 * The bubble's text slot for the note under [hash], or null to draw none: speech is off ([speech] null), or the bytes
 * are not here yet ([ready]). The row's stored text wins; then this process's work on the note; then the model's own
 * download; else the offer. Pure.
 */
fun voiceTranscriptView(
    stored: String?,
    hash: String?,
    ready: Boolean,
    speech: VoiceSpeechView?,
): VoiceTranscriptView? {
    if (speech == null || hash == null || !ready) return null
    if (stored != null) return VoiceTranscriptView.Text(stored)
    return when (val note = speech.notes[hash]) {
        is NoteStt.Running -> {
            VoiceTranscriptView.Running(note.heard.settled.trim(), note.heard.live.trim())
        }

        is NoteStt.Done -> {
            VoiceTranscriptView.Text(note.text)
        }

        NoteStt.Busy -> {
            VoiceTranscriptView.Busy
        }

        NoteStt.Failed -> {
            VoiceTranscriptView.Failed
        }

        null -> {
            when (val model = speech.model) {
                is SttModelState.Downloading -> VoiceTranscriptView.Downloading(model.done, model.total)
                else -> VoiceTranscriptView.Offer
            }
        }
    }
}

@Composable
private fun VoiceTranscript(
    view: VoiceTranscriptView,
    onTranscribe: () -> Unit,
) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    when (view) {
        is VoiceTranscriptView.Text -> {
            if (view.text.isBlank()) {
                Note(stringResource(R.string.chat_voice_no_speech))
            } else {
                Text(
                    view.text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("chat_voice_transcript"),
                )
            }
        }

        is VoiceTranscriptView.Running -> {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // On the first line, not the middle of a long one.
                CircularProgressIndicator(modifier = Modifier.padding(top = 4.dp).size(14.dp), strokeWidth = 2.dp)
                Text(
                    buildAnnotatedString {
                        append(view.settled)
                        if (view.live.isNotBlank()) {
                            if (view.settled.isNotBlank()) append(" ")
                            withStyle(SpanStyle(color = dim)) { append(view.live) }
                        }
                        if (view.settled.isBlank() && view.live.isBlank()) {
                            withStyle(SpanStyle(color = dim)) { append(stringResource(R.string.chat_voice_transcribing)) }
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("chat_voice_transcribing"),
                )
            }
        }

        is VoiceTranscriptView.Downloading -> {
            Note(stringResource(R.string.chat_voice_model_downloading, mb(view.done), mb(view.total)))
        }

        VoiceTranscriptView.Offer -> {
            TranscribeButton(R.string.chat_voice_transcribe, onTranscribe)
        }

        VoiceTranscriptView.Busy -> {
            Note(stringResource(R.string.chat_voice_transcribe_busy))
            TranscribeButton(R.string.chat_voice_transcribe, onTranscribe)
        }

        VoiceTranscriptView.Failed -> {
            Note(stringResource(R.string.chat_voice_transcribe_failed))
            TranscribeButton(R.string.chat_voice_transcribe_retry, onTranscribe)
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun TranscribeButton(
    label: Int,
    onClick: () -> Unit,
) {
    // No start padding: the icon lines up with the play button and the text above, as part of the bubble's column.
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(start = 0.dp, end = 12.dp),
        modifier = Modifier.heightIn(min = 48.dp).testTag("chat_voice_transcribe"),
    ) {
        Icon(Icons.Filled.ClosedCaption, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(label))
    }
}

/**
 * The offer a Transcribe tap makes while the speech model is not on the phone (ADR 2026-10.am8d): its size, a word
 * when the route is metered, and the same download the Calls screen starts.
 */
@Composable
fun SpeechModelOfferDialog(
    modelBytes: Long,
    metered: Boolean,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_voice_model_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.chat_voice_model_body, mb(modelBytes)))
                if (metered) Text(stringResource(R.string.call_settings_speech_model_metered))
            }
        },
        confirmButton = { TextButton(onClick = onDownload) { Text(stringResource(R.string.call_settings_speech_model_download)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_voice_model_not_now)) } },
        modifier = Modifier.testTag("chat_voice_model_offer"),
    )
}

/**
 * The just-recorded note, staged in the composer for review. Deliberately the same play/waveform/duration
 * row the bubble uses plus a discard control, so what the user auditions is visibly the thing that will be
 * sent — and so a voice note can still carry text or a reply quote, because it stages through exactly the
 * path a photo does.
 */
@Composable
fun VoiceNotePreview(
    durationMs: Int?,
    peaks: FloatArray?,
    positionMs: Int?,
    playing: Boolean,
    onToggle: () -> Unit,
    onSeek: (Float) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val total = durationMs ?: 0
    val position = positionMs ?: 0
    val shown = if (position > 0) position else total
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.fillMaxWidth().testTag("chat_voice_staged"),
    ) {
        Row(
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PlayButton(
                playing = playing,
                accent = MaterialTheme.colorScheme.primary,
                onClick = onToggle,
                onLongClick = null,
            )
            VoiceWaveform(
                peaks = peaks,
                progress = if (total > 0) position.toFloat() / total else 0f,
                playedColor = MaterialTheme.colorScheme.primary,
                remainingColor = MaterialTheme.colorScheme.primary.copy(alpha = INACTIVE_ALPHA),
                onSeek = onSeek,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = formatDuration(shown),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clearAndSetSemantics {},
            )
            Box(
                modifier =
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onClear, role = Role.Button),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.chat_voice_remove),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The left half of the composer while recording: a level-driven dot, the elapsed counter, and the way out —
 * "slide to cancel" while the button is still held, or an explicit ✕ once the recording is locked hands-free.
 *
 * It replaces the *text field* only, inside the field container it shares with the inline mic. The mic stays
 * composed for the whole press, because it owns the hold gesture and a composable that leaves composition has
 * its `pointerInput` cancelled — which is why stopping is not this row's job either: that control lives in
 * the trailing slot beside the field, where the thumb is.
 */
@Composable
fun VoiceRecordingBar(
    elapsedMs: Long,
    amplitude: Float,
    locked: Boolean,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.testTag("chat_voice_recording"),
    ) {
        Row(
            // A full field's worth of height, not whatever the content happens to measure: the bar shares the
            // field container with the mic and has to hold that container at the same height the text field
            // leaves it at, both while the button is held and after it locks. Sizing to content instead let
            // the bar sit low in the container while held (the container floors at 48dp; a 32dp bar
            // bottom-aligned inside it puts the dot and the counter 8dp below centre) and then jump taller
            // on lock, when the ✕ replaced the slide-to-cancel label.
            modifier = Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // The dot swells with the input level, so the user can see the mic is actually hearing them —
            // the one thing a static "Recording…" label can't tell them.
            // Animated, not raw: the amplitude arrives off a ViewModel ticker in visible steps, and a dot
            // that jumps between sizes reads as a stutter rather than as a level meter. The spring is the
            // fast spatial one, so it still tracks a shout closely enough to be believed.
            val dot by animateDpAsState(
                targetValue = (8f + amplitude.coerceIn(0f, 1f) * 6f).dp,
                animationSpec = KnitMotion.fastSpatial(),
                label = "voiceLevel",
            )
            val recordingLabel = stringResource(R.string.chat_voice_recording)
            Box(
                modifier = Modifier.size(16.dp).semantics { contentDescription = recordingLabel },
                contentAlignment = Alignment.Center,
            ) {
                Surface(color = MaterialTheme.colorScheme.error, shape = CircleShape) {
                    Box(Modifier.size(dot))
                }
            }
            Text(
                text = formatDuration(elapsedMs.toInt()),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            if (locked) {
                Box(
                    modifier =
                        Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onCancel, role = Role.Button)
                            .testTag("chat_voice_cancel"),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.chat_voice_cancel),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            } else {
                Text(
                    text = "‹ ${stringResource(R.string.chat_voice_slide_to_cancel)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Ends a locked, hands-free recording. It takes the trailing slot beside the field — the send/attach
 * button's — so the control the user reaches for is the one already under their thumb.
 */
@Composable
fun VoiceStopButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(R.string.chat_voice_record_stop)
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier =
            modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable(onClick = onClick, role = Role.Button, onClickLabel = label)
                .testTag("chat_voice_stop"),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Stop, contentDescription = label, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun PlayButton(
    playing: Boolean,
    accent: Color,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    val label = stringResource(if (playing) R.string.chat_voice_pause else R.string.chat_voice_play)
    Surface(
        shape = CircleShape,
        color = accent,
        contentColor = MaterialTheme.colorScheme.surface,
        modifier =
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .then(
                    if (onLongClick == null) {
                        Modifier.clickable(onClick = onClick, role = Role.Button, onClickLabel = label)
                    } else {
                        Modifier.combinedClickable(
                            role = Role.Button,
                            onClickLabel = label,
                            onClick = onClick,
                            onLongClick = onLongClick,
                        )
                    },
                ).testTag("chat_voice_play"),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = label,
                modifier = Modifier.size(22.dp),
                tint = LocalContentColor.current,
            )
        }
    }
}

/** mm:ss, the only format a voice note ever needs (the recorder caps at five minutes). */
fun formatDuration(millis: Int): String {
    // Round to the nearest second rather than truncating: a 6.9 s note reading "0:06" looks like the last
    // second was lost, and the counter would sit on 0:00 for a whole second at the start of playback.
    val totalSeconds = (millis.coerceAtLeast(0) + MILLIS_PER_SECOND / 2) / MILLIS_PER_SECOND
    return "%d:%02d".format(totalSeconds / SECONDS_PER_MINUTE, totalSeconds % SECONDS_PER_MINUTE)
}

private const val MILLIS_PER_SECOND = 1000
private const val SECONDS_PER_MINUTE = 60
private const val INACTIVE_ALPHA = 0.3f
private const val BAR_WIDTH_FRACTION = 0.55f
private const val MIN_BAR_FRACTION = 0.08f
private val WAVEFORM_HEIGHT = 28.dp
private val BUBBLE_WIDTH = 220.dp

/** A plausible speech envelope for the previews — quiet floor, syllable-ish peaks. */
private fun previewPeaks(): FloatArray {
    val floor = 0.2f
    val swing = 0.7f
    val syllablesPerBar = 5f
    return FloatArray(VoiceAudio.PEAK_COUNT) { i ->
        floor + swing * kotlin.math.abs(kotlin.math.sin(i / syllablesPerBar))
    }
}

@Preview(showBackground = true)
@Composable
fun VoiceNoteBubblePreview() =
    KnitPreview {
        VoiceNoteBubble(
            ready = true,
            durationMs = 7_400,
            peaks = previewPeaks(),
            positionMs = 2_100,
            playing = true,
            accent = MaterialTheme.colorScheme.primary,
            onToggle = {},
            onSeek = {},
            onLongClick = {},
        )
    }

@Preview(showBackground = true)
@Composable
fun VoiceNoteLoadingPreview() =
    KnitPreview {
        VoiceNoteBubble(
            ready = false,
            durationMs = null,
            peaks = null,
            positionMs = null,
            playing = false,
            accent = MaterialTheme.colorScheme.primary,
            onToggle = {},
            onSeek = {},
            onLongClick = {},
        )
    }

/** A voice note whose bytes are streaming in over a nearby link: the ring and how much has crossed (#115). */
@Preview(showBackground = true)
@Composable
fun VoiceNoteArrivingPreview() =
    KnitPreview {
        VoiceNoteBubble(
            ready = false,
            durationMs = null,
            peaks = null,
            positionMs = null,
            playing = false,
            accent = MaterialTheme.colorScheme.primary,
            onToggle = {},
            onSeek = {},
            onLongClick = {},
            arrival = ArrivingFile("preview", bytes = 38_000, total = 61_000),
        )
    }

@Preview(showBackground = true)
@Composable
fun VoiceRecordingBarPreview() =
    KnitPreview {
        VoiceRecordingBar(elapsedMs = 3_200, amplitude = 0.6f, locked = false, onCancel = {})
    }

@Preview(showBackground = true)
@Composable
fun VoiceRecordingBarLockedPreview() =
    KnitPreview {
        VoiceRecordingBar(elapsedMs = 12_000, amplitude = 0.3f, locked = true, onCancel = {})
    }

/** A voice note written out on tap (ADR 2026-10.am8d). */
@Preview(showBackground = true)
@Composable
fun VoiceNoteTranscribedPreview() =
    KnitPreview {
        VoiceNoteBubble(
            ready = true,
            durationMs = 7_400,
            peaks = previewPeaks(),
            positionMs = null,
            playing = false,
            accent = MaterialTheme.colorScheme.primary,
            onToggle = {},
            onSeek = {},
            onLongClick = {},
            transcript = VoiceTranscriptView.Text("Running ten minutes late, save me a seat by the window."),
        )
    }

/** A voice note the model is on: the settled words, then its guess at the rest. */
@Preview(showBackground = true)
@Composable
fun VoiceNoteTranscribingPreview() =
    KnitPreview {
        VoiceNoteBubble(
            ready = true,
            durationMs = 41_000,
            peaks = previewPeaks(),
            positionMs = null,
            playing = false,
            accent = MaterialTheme.colorScheme.primary,
            onToggle = {},
            onSeek = {},
            onLongClick = {},
            transcript = VoiceTranscriptView.Running("So the plan for Saturday is", "we meet at"),
        )
    }

/** A voice note not yet transcribed: the Transcribe control under the row. */
@Preview(showBackground = true)
@Composable
fun VoiceNoteTranscribeOfferPreview() =
    KnitPreview {
        VoiceNoteBubble(
            ready = true,
            durationMs = 7_400,
            peaks = previewPeaks(),
            positionMs = null,
            playing = false,
            accent = MaterialTheme.colorScheme.primary,
            onToggle = {},
            onSeek = {},
            onLongClick = {},
            transcript = VoiceTranscriptView.Offer,
        )
    }
