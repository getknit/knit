@file:Suppress("MatchingDeclarationName") // the walkie transcript card, a live call's captions and the state they draw

package app.getknit.knit.ui.call

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.getknit.knit.R
import app.getknit.knit.call.CaptionStatus
import app.getknit.knit.call.Captions
import app.getknit.knit.call.SttModelState
import app.getknit.knit.call.TranscriptLine
import app.getknit.knit.ui.theme.LocalReduceMotion
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** What the transcript card draws: the session's bursts, and where the speech model stands on this phone. */
internal data class TranscriptUi(
    val lines: List<TranscriptLine>,
    val model: SttModelState,
    // What the model costs to fetch, and whether the route a fetch would take now is mobile data or under Data Saver.
    val modelBytes: Long,
    val metered: Boolean,
)

/**
 * The walkie session's transcript (docs/VOICE_CALLS.md C-3.11-9), in the call screen's card slot while the header's
 * diagnostics toggle is off: each burst from [name] as text while it plays, settled once it has ended, and each burst
 * spoken here as the model heard it from the microphone, before Opus — newest at the bottom — and while the speech
 * model is not on the
 * phone, what it costs, the button that fetches it and Dismiss ([onDismiss]: the offer is closed for good, the caller
 * draws no card while the model is absent, and the download stays on the Calls screen). Experimental, debug only:
 * a spoken line set beside the other phone's for the same burst shows what the codec cost the model. Nothing here
 * leaves the phone.
 */
@Composable
internal fun WalkieTranscript(
    ui: TranscriptUi,
    name: String,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit = {},
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val ready = ui.model == SttModelState.Ready
    val list = rememberLazyListState()
    val reduceMotion = LocalReduceMotion.current
    // The newest burst in view as it comes, as its words stream in and as its text lands — once the model is in. Before
    // that the panel at the top is what matters, and a short screen would scroll it away.
    LaunchedEffect(ui.lines.size, ui.lines.lastOrNull()?.state, ui.lines.lastOrNull()?.live, ready) {
        if (!ready || ui.lines.isEmpty()) return@LaunchedEffect
        val last = ui.lines.lastIndex
        if (reduceMotion) list.scrollToItem(last) else list.animateScrollToItem(last)
    }
    // The configuration's locale, read so a change of language redraws the times.
    val locale = LocalConfiguration.current.locales[0]
    val times = remember(locale, zone) { DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM).withLocale(locale).withZone(zone) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(top = 16.dp)) {
            Box(Modifier.padding(horizontal = 16.dp)) {
                Title(
                    stringResource(R.string.call_transcript_title),
                    stringResource(R.string.call_transcript_marker),
                    Icons.Outlined.ClosedCaption,
                )
            }
            // Everything under the title is one list, so a short screen scrolls the card rather than clipping it.
            LazyColumn(
                modifier = Modifier.weight(1f, fill = false),
                state = list,
                contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                transcriptItems(ui, name, ready, times, onDownload, onCancel, onDismiss)
            }
        }
    }
}

private fun LazyListScope.transcriptItems(
    ui: TranscriptUi,
    name: String,
    ready: Boolean,
    times: DateTimeFormatter,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!ready) {
        item(key = "model") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ModelPanel(ui, onDownload, onCancel, onDismiss)
                if (ui.lines.isNotEmpty()) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    } else if (ui.lines.isEmpty()) {
        item(key = "empty") {
            Text(
                stringResource(R.string.call_transcript_empty, name.ifBlank { stringResource(R.string.call_transcript_other_phone) }),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    items(ui.lines, key = { "${if (it.mine) "s" else "h"}${it.burst}" }) { line ->
        val who =
            if (line.mine) {
                stringResource(
                    R.string.call_transcript_you,
                )
            } else {
                name.ifBlank { stringResource(R.string.call_transcript_them) }
            }
        Line(line, who, times.format(Instant.ofEpochMilli(line.heardAt)))
    }
}

@Composable
private fun Line(
    line: TranscriptLine,
    who: String,
    time: String,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            time,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(TimeWidth).padding(top = 3.dp),
        )
        Column(Modifier.weight(1f)) {
            // Who spoke; a line of this phone's own is the microphone's audio, before Opus.
            Text(
                if (line.mine) stringResource(R.string.call_transcript_mine, who) else who,
                style = MaterialTheme.typography.labelMedium,
                color = if (line.mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Said(line)
        }
    }
}

@Composable
private fun Said(line: TranscriptLine) {
    val state = line.state
    // A line still listening or finishing carries the words so far; a settled one, none.
    val streaming = state is TranscriptLine.State.Listening || state is TranscriptLine.State.Pending
    when {
        state is TranscriptLine.State.Text -> {
            // Read out as it lands, for a user who cannot hear the burst; their own words they know.
            Text(
                state.text,
                style = MaterialTheme.typography.bodyLarge,
                modifier = if (line.mine) Modifier else Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }

        // The words so far, while the burst is still coming or being finished: dimmed, since they may yet change, and
        // never read out — the settled text is, once.
        streaming && line.words.isNotBlank() -> {
            Streaming(line, MaterialTheme.typography.bodyLarge)
        }

        else -> {
            Text(
                noteOf(state, line.mine),
                style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A line's words while they still come: what the model has closed at a pause ([TranscriptLine.settled]) as it will
 * stay, then its guess at the rest, dimmed, since that may yet change. Never read out: the line is, once settled.
 */
@Composable
private fun Streaming(
    line: TranscriptLine,
    style: TextStyle,
) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        buildAnnotatedString {
            append(line.settled)
            if (line.live.isNotBlank()) {
                if (line.settled.isNotBlank()) append(" ")
                withStyle(SpanStyle(color = dim)) { append(line.live) }
            }
            withStyle(SpanStyle(color = dim)) { append("…") }
        },
        style = style,
    )
}

// What a line without text says.
@Composable
private fun noteOf(
    state: TranscriptLine.State,
    mine: Boolean,
): String =
    when (state) {
        TranscriptLine.State.Listening -> {
            stringResource(
                if (mine) R.string.call_transcript_recording else R.string.call_transcript_listening,
            )
        }

        TranscriptLine.State.Pending -> {
            stringResource(R.string.call_transcript_pending)
        }

        TranscriptLine.State.NoSpeech -> {
            stringResource(R.string.call_transcript_no_speech)
        }

        TranscriptLine.State.NoModel -> {
            stringResource(R.string.call_transcript_no_model)
        }

        TranscriptLine.State.Failed -> {
            stringResource(R.string.call_transcript_failed)
        }

        is TranscriptLine.State.Text -> {
            state.text
        }
    }

/**
 * Where the speech model stands, with the one action that moves it on and, beside a Download, Dismiss: only the offer
 * closes, since once the model is in the Calls screen's switch is the way out.
 */
@Composable
private fun ModelPanel(
    ui: TranscriptUi,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    // What the model would do here, before its cost, and what the button beside Download says.
    offer: String = stringResource(R.string.call_transcript_offer),
    dismiss: String = stringResource(R.string.call_transcript_dismiss),
) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (val model = ui.model) {
            // The card draws no panel then.
            SttModelState.Ready -> {}

            SttModelState.Absent -> {
                Text(
                    stringResource(R.string.call_transcript_model_absent, offer, mb(context, ui.modelBytes)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (ui.metered) MeteredNote()
                OfferButtons(stringResource(R.string.call_transcript_download), onDownload, onDismiss, dismiss)
            }

            is SttModelState.Downloading -> {
                LinearProgressIndicator(
                    progress = { if (model.total > 0) (model.done.toFloat() / model.total).coerceIn(0f, 1f) else 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.call_transcript_downloading, mb(context, model.done), mb(context, model.total)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.call_settings_speech_model_cancel)) }
                }
            }

            is SttModelState.Failed -> {
                Text(
                    stringResource(R.string.call_settings_speech_model_failed, model.reason),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                if (ui.metered) MeteredNote()
                OfferButtons(stringResource(R.string.call_settings_speech_model_retry), onDownload, onDismiss, dismiss)
            }
        }
    }
}

@Composable
private fun MeteredNote() {
    Text(
        stringResource(R.string.call_settings_speech_model_metered),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun OfferButtons(
    label: String,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
    dismiss: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = onDownload, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
            Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Text(label, Modifier.padding(start = ButtonDefaults.IconSpacing))
        }
        TextButton(onClick = onDismiss, modifier = Modifier.testTag("walkie_transcript_dismiss")) { Text(dismiss) }
    }
}

/** What a live call's captions draw: the call's lines and where they stand, and where the speech model stands. */
internal data class CaptionsUi(
    val captions: Captions,
    val model: SttModelState,
    val modelBytes: Long,
    val metered: Boolean,
    // The call is on the earpiece: the screen is dark at the ear, so captions are read on the loudspeaker or a headset.
    val earpiece: Boolean = false,
)

/**
 * A live call's captions (docs/VOICE_CALLS.md C-3.7-5), in the call screen's card slot while the CC button is on: what
 * [name] says, large, as it plays — the words the model has settled as they will stay and its guess at the rest dimmed
 * — newest at the bottom, a call's last few dozen lines behind it. Unlike the walkie's transcript it carries no speaker
 * and no times: a live call is one other person, and this phone's own voice is not captioned. While the model is not
 * on the phone it offers the download, and Not now ([onOff]) turns the captions off; a note says when they stopped
 * because the phone could not keep up or the model would not run, and on the earpiece, that they read better on the
 * loudspeaker. Nothing here leaves the phone.
 */
@Composable
internal fun LiveCaptions(
    ui: CaptionsUi,
    name: String,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onOff: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ready = ui.model == SttModelState.Ready
    val lines = ui.captions.lines
    val note = captionNote(ui, ready)?.let { stringResource(it) }
    val list = rememberLazyListState()
    val reduceMotion = LocalReduceMotion.current
    LaunchedEffect(lines.size, lines.lastOrNull()?.state, lines.lastOrNull()?.words, ready) {
        if (!ready || lines.isEmpty()) return@LaunchedEffect
        if (reduceMotion) list.scrollToItem(lines.lastIndex) else list.animateScrollToItem(lines.lastIndex)
    }
    val transcript = TranscriptUi(lines, ui.model, ui.modelBytes, ui.metered)
    Surface(
        modifier = modifier.fillMaxWidth().testTag("live_captions"),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(top = 16.dp)) {
            Box(Modifier.padding(horizontal = 16.dp)) {
                Title(
                    stringResource(R.string.call_captions_title),
                    stringResource(R.string.call_transcript_marker),
                    Icons.Outlined.ClosedCaption,
                )
            }
            LazyColumn(
                modifier = Modifier.weight(1f, fill = false),
                state = list,
                contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                note?.let { note ->
                    item(key = "note") { Note(note, error = ui.captions.status != CaptionStatus.ON) }
                }
                if (!ready) {
                    item(key = "model") {
                        ModelPanel(
                            transcript,
                            onDownload,
                            onCancel,
                            onOff,
                            offer =
                                stringResource(
                                    R.string.call_captions_offer,
                                    name.ifBlank { stringResource(R.string.call_transcript_other_phone) },
                                ),
                            dismiss = stringResource(R.string.call_captions_not_now),
                        )
                    }
                } else if (lines.isEmpty() && ui.captions.status == CaptionStatus.ON) {
                    item(key = "empty") {
                        Text(
                            stringResource(
                                R.string.call_captions_empty,
                                name.ifBlank { stringResource(R.string.call_transcript_other_phone) },
                            ),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(lines, key = { "c${it.burst}" }) { line -> Caption(line) }
            }
        }
    }
}

@Composable
private fun Caption(line: TranscriptLine) {
    val style = MaterialTheme.typography.titleLarge
    when (val state = line.state) {
        // Read out as it lands, for a user who cannot hear it; the words still coming never are.
        is TranscriptLine.State.Text -> {
            Text(state.text, style = style, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }

        else -> {
            Streaming(line, style)
        }
    }
}

// What the captions say above their lines: why they stopped, else that the earpiece hides them.
@StringRes
private fun captionNote(
    ui: CaptionsUi,
    ready: Boolean,
): Int? =
    when (ui.captions.status) {
        CaptionStatus.BEHIND -> R.string.call_captions_behind
        CaptionStatus.FAILED -> R.string.call_captions_failed
        CaptionStatus.ON -> if (ready && ui.earpiece) R.string.call_captions_earpiece else null
    }

@Composable
private fun Note(
    text: String,
    error: Boolean,
) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** "44 MB": whole megabytes (10^6 bytes, as a download size is read). */
internal fun mb(
    context: Context,
    bytes: Long,
): String = context.getString(R.string.call_megabytes, (bytes + BYTES_PER_MB / 2) / BYTES_PER_MB)

private const val BYTES_PER_MB = 1_000_000L
private val TimeWidth = 64.dp
