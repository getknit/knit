package app.getknit.knit.ui.chat

import android.annotation.SuppressLint
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.text.format.Formatter
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.getknit.knit.R
import app.getknit.knit.data.ByteArrayMediaSource
import app.getknit.knit.data.relay.AttachmentWait
import app.getknit.knit.mesh.ArrivingFile
import app.getknit.knit.mesh.protocol.VideoInfo
import app.getknit.knit.ui.preview.KnitPreview
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import java.util.Base64

// The video bubble's width, matched to a picture's so a thread of both lines up; its height follows the video's own
// aspect, capped so a portrait clip does not fill the screen (ADR 2026-10.hmfe).
private val VIDEO_WIDTH = 220.dp
private val VIDEO_MAX_HEIGHT = 300.dp
private const val PORTRAIT = 9f / 16f
private const val LANDSCAPE = 16f / 9f
private const val MS_PER_S = 1000L
private const val S_PER_MIN = 60L
private const val POSITION_TICK_MS = 200L
private const val TAG = "VideoBubbles"

/** "0:42" / "1:01" for a duration in milliseconds: what a video badge and the player's clock read. */
internal fun formatVideoDuration(ms: Long): String {
    val seconds = (ms.coerceAtLeast(0) + MS_PER_S / 2) / MS_PER_S
    return "%d:%02d".format(seconds / S_PER_MIN, seconds % S_PER_MIN)
}

/** The width-to-height ratio a video's bubble is drawn at: its own when it says, else portrait. */
private fun videoAspect(
    width: Int?,
    height: Int?,
): Float {
    val w = width?.takeIf { it > 0 } ?: return PORTRAIT
    val h = height?.takeIf { it > 0 } ?: return PORTRAIT
    return (w.toFloat() / h).coerceIn(PORTRAIT, LANDSCAPE)
}

/** [VIDEO_WIDTH] wide at [aspect], unless that is taller than [VIDEO_MAX_HEIGHT]: then that tall, and narrower. */
private fun Modifier.videoSize(aspect: Float): Modifier {
    val height = (VIDEO_WIDTH / aspect).coerceAtMost(VIDEO_MAX_HEIGHT)
    return size(width = height * aspect, height = height)
}

/** Decoded once per poster string: the row keeps it base64 so its data class compares by value. */
@Composable
private fun rememberPoster(poster: String?): ByteArray? =
    remember(poster) { poster?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }?.takeIf { it.isNotEmpty() } }

/** While a picked video is re-encoded: how far along, and a way out. */
@Composable
internal fun VideoEncodingTile(
    progress: Float,
    onCancel: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().testTag("chat_video_encoding"),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Filled.Videocam, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.chat_video_encoding, (progress * 100).toInt().coerceIn(0, 100)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            }
            TextButton(onClick = onCancel) { Text(stringResource(R.string.chat_video_cancel)) }
        }
    }
}

/** The staged video above the field: its poster and length, the way the bubble will draw them. */
@Composable
internal fun StagedVideoTile(video: VideoInfo) {
    val poster = video.poster.takeIf { it.isNotEmpty() }
    Box(
        modifier =
            Modifier
                .height(72.dp)
                .aspectRatio(videoAspect(video.width, video.height))
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (poster != null) {
            AsyncImage(
                model = poster,
                contentDescription = stringResource(R.string.chat_attachment_preview_desc),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        DurationBadge(video.durationMs, Modifier.align(Alignment.BottomStart))
    }
}

/**
 * A video in a bubble (ADR 2026-10.hmfe). Its poster — carried inside the message — shows from the first moment, so
 * a video waiting on "Tap to download" (ADR 2026-10.js2p) or still on its way already looks like itself:
 *
 * - [awaitsTap] with nothing arriving: the poster, the length and the declared size, and a tap downloads it;
 * - fetching: the poster dimmed under the arrival ring;
 * - held: the poster with a play button, and a tap plays it.
 *
 * A [flagged] video (its frames, or its poster) is hidden behind a tap like a flagged picture.
 */
@Composable
internal fun VideoBubble(
    hash: String,
    poster: String?,
    durationMs: Long?,
    width: Int?,
    height: Int?,
    ready: Boolean,
    flagged: Boolean,
    awaitsTap: Boolean,
    declaredSize: Long?,
    wait: AttachmentWait,
    arrival: ArrivingFile?,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
    onLongClick: () -> Unit,
) {
    val context = LocalContext.current
    val posterBytes = rememberPoster(poster)
    var revealed by remember(hash) { mutableStateOf(false) }
    val hidden = flagged && !revealed
    val duration = durationMs?.let(::formatVideoDuration)
    val playLabel = stringResource(R.string.chat_video_play, duration.orEmpty())
    val tapping = awaitsTap && arrival == null && !ready
    Box(
        modifier =
            Modifier
                .padding(vertical = 2.dp)
                .videoSize(videoAspect(width, height))
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black)
                .combinedClickable(
                    onClickLabel = if (ready && !hidden) playLabel else null,
                    role = Role.Button,
                    onClick = {
                        when {
                            hidden -> revealed = true
                            ready -> onPlay()
                            tapping -> onDownload()
                        }
                    },
                    onLongClick = onLongClick,
                ).testTag("chat_video_bubble"),
        contentAlignment = Alignment.Center,
    ) {
        if (posterBytes != null) {
            AsyncImage(
                model = posterBytes,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().then(if (hidden) Modifier.blur(24.dp) else Modifier),
            )
        }
        when {
            hidden -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.VisibilityOff, contentDescription = null, tint = Color.White)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.moderation_video_hidden),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }

            ready -> {
                Surface(shape = CircleShape, color = Color.Black.copy(alpha = 0.55f)) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.padding(10.dp).size(32.dp),
                    )
                }
            }

            tapping && declaredSize != null -> {
                Surface(shape = RoundedCornerShape(16.dp), color = Color.Black.copy(alpha = 0.6f)) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(Icons.Filled.Download, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Text(
                            Formatter.formatShortFileSize(context, declaredSize),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White,
                        )
                    }
                }
            }

            else -> {
                Surface(shape = RoundedCornerShape(16.dp), color = Color.Black.copy(alpha = 0.6f)) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        ArrivalIndicator(key = hash, arrival = arrival, size = 24.dp, stopped = wait.stopped)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            arrival?.let { arrivalText(it) } ?: attachmentWaitTitle(wait) ?: stringResource(R.string.chat_video_loading),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            textAlign = TextAlign.Center,
                        )
                        attachmentWaitHint(wait, arrival)?.let { hint ->
                            Text(
                                hint,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.8f),
                                textAlign = TextAlign.Center,
                                maxLines = 3,
                            )
                        }
                    }
                }
            }
        }
        if (!hidden && durationMs != null) DurationBadge(durationMs, Modifier.align(Alignment.BottomStart))
    }
}

@Composable
private fun DurationBadge(
    durationMs: Long,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = Color.Black.copy(alpha = 0.6f),
        modifier = modifier.padding(6.dp),
    ) {
        Text(
            formatVideoDuration(durationMs),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * Plays a held video fullscreen (ADR 2026-10.hmfe): its plaintext read into memory by [load] and handed to the
 * platform `MediaPlayer` through [ByteArrayMediaSource] — the voice note's path, so a decrypted video is never a file.
 * A tap on the picture pauses and resumes; the slider seeks; Save files it under `Movies/Knit`.
 */
@Composable
internal fun FullscreenVideoPlayer(
    load: suspend () -> ByteArray?,
    width: Int?,
    height: Int?,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    var bytes by remember { mutableStateOf<ByteArray?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        bytes = load()
        if (bytes == null) failed = true
    }
    val player = remember { MediaPlayer() }
    var prepared by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableFloatStateOf(0f) }
    var duration by remember { mutableFloatStateOf(0f) }
    var surface by remember { mutableStateOf<Surface?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            runCatching { player.release() }
            surface?.release()
        }
    }
    LaunchedEffect(bytes, surface) {
        val data = bytes ?: return@LaunchedEffect
        val target = surface ?: return@LaunchedEffect
        if (prepared) return@LaunchedEffect
        runCatching {
            player.setAudioAttributes(
                AudioAttributes
                    .Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build(),
            )
            player.setDataSource(ByteArrayMediaSource(data))
            player.setSurface(target)
            player.setOnCompletionListener { playing = false }
            player.setOnErrorListener { _, _, _ ->
                failed = true
                true
            }
            player.setOnPreparedListener {
                duration = it.duration.toFloat()
                prepared = true
                it.start()
                playing = true
            }
            player.prepareAsync()
        }.onFailure {
            Log.w(TAG, "video playback failed: ${it.javaClass.simpleName}")
            failed = true
        }
    }
    LaunchedEffect(prepared, playing) {
        while (prepared && playing) {
            position = runCatching { player.currentPosition.toFloat() }.getOrDefault(position)
            delay(POSITION_TICK_MS)
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black).testTag("chat_video_player")) {
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).apply {
                        surfaceTextureListener =
                            object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(
                                    texture: SurfaceTexture,
                                    w: Int,
                                    h: Int,
                                ) {
                                    surface = surfaceOf(texture)
                                }

                                override fun onSurfaceTextureSizeChanged(
                                    texture: SurfaceTexture,
                                    w: Int,
                                    h: Int,
                                ) = Unit

                                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                                    surface?.release()
                                    surface = null
                                    return true
                                }

                                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                            }
                    }
                },
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .aspectRatio(videoAspect(width, height))
                        .clickable(enabled = prepared) {
                            if (player.isPlaying) {
                                player.pause()
                                playing = false
                            } else {
                                player.start()
                                playing = true
                            }
                        },
            )
            if (failed) {
                Text(
                    stringResource(R.string.chat_video_unplayable),
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            Row(
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.chat_video_close),
                        tint = Color.White,
                    )
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onSave, enabled = bytes != null) {
                    Text(stringResource(R.string.chat_video_save), color = Color.White)
                }
            }
            if (prepared) {
                Row(
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = {
                            if (player.isPlaying) {
                                player.pause()
                                playing = false
                            } else {
                                player.start()
                                playing = true
                            }
                        },
                    ) {
                        Icon(
                            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = stringResource(if (playing) R.string.chat_video_pause else R.string.chat_video_resume),
                            tint = Color.White,
                        )
                    }
                    Text(formatVideoDuration(position.toLong()), color = Color.White, style = MaterialTheme.typography.labelSmall)
                    Slider(
                        value = position,
                        valueRange = 0f..duration.coerceAtLeast(1f),
                        onValueChange = {
                            position = it
                            player.seekTo(it.toInt())
                        },
                        modifier =
                            Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp)
                                .semantics { contentDescription = formatVideoDuration(position.toLong()) },
                    )
                    Text(formatVideoDuration(duration.toLong()), color = Color.White, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

/**
 * The player's output surface. Released by its owner — when the texture goes, and in the player's dispose effect —
 * which lint's Recycle check cannot follow across the composable's state.
 */
@SuppressLint("Recycle")
private fun surfaceOf(texture: SurfaceTexture): Surface = Surface(texture)

private val PREVIEW_INFO = VideoInfo(durationMs = 42_000, width = 480, height = 854, poster = ByteArray(0))

@Preview
@Composable
fun VideoEncodingTilePreview() {
    KnitPreview { VideoEncodingTile(progress = 0.42f, onCancel = {}) }
}

@Preview
@Composable
fun VideoBubbleHeldPreview() {
    KnitPreview {
        VideoBubble(
            hash = "h",
            poster = null,
            durationMs = PREVIEW_INFO.durationMs,
            width = PREVIEW_INFO.width,
            height = PREVIEW_INFO.height,
            ready = true,
            flagged = false,
            awaitsTap = false,
            declaredSize = 3_000_000,
            wait = AttachmentWait.Nearby,
            arrival = null,
            onPlay = {},
            onDownload = {},
            onLongClick = {},
        )
    }
}

@Preview
@Composable
fun VideoBubbleTapToDownloadPreview() {
    KnitPreview {
        VideoBubble(
            hash = "h",
            poster = null,
            durationMs = PREVIEW_INFO.durationMs,
            width = PREVIEW_INFO.width,
            height = PREVIEW_INFO.height,
            ready = false,
            flagged = false,
            awaitsTap = true,
            declaredSize = 3_000_000,
            wait = AttachmentWait.Nearby,
            arrival = null,
            onPlay = {},
            onDownload = {},
            onLongClick = {},
        )
    }
}
