package app.getknit.knit.ui.redact

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.getknit.knit.R
import app.getknit.knit.redact.FaceBox
import app.getknit.knit.redact.Redaction
import app.getknit.knit.ui.preview.KnitPreview
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The photo about to be sent, with its faces covered, before it goes (ADR 2026-10.whkk). It takes over the screen in
 * place of the chat, like the in-app camera (ADR 029). What the screen shows is what will be sent: a covered face is
 * drawn in the fill the photo will get, the average colour of what it hides, and an uncovered one as a dashed outline.
 *
 * Every edit is a call back to [redaction]'s owner, in the photo's pixels: [onTap] at a point (with the slop a finger
 * gets, converted to photo pixels), [onDraw] for a dragged rectangle, [onToggle] for a face chosen through
 * accessibility. A pinch zooms and pans the photo here and is not reported. Editing waits for the search to finish,
 * and for [sending] to end. Back is the caller's to map to [onCancel], as the camera's surface does.
 */
@Composable
@Suppress("LongParameterList") // the photo, its covers, the search and the four ways out of the screen
fun RedactReviewScreenContent(
    photo: ImageBitmap,
    redaction: Redaction,
    search: FaceSearch,
    onTap: (x: Float, y: Float, slop: Float) -> Unit,
    onDraw: (x0: Float, y0: Float, x1: Float, y1: Float) -> Unit,
    onToggle: (id: Int) -> Unit,
    onDone: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    sending: Boolean = false,
) {
    val editable = search != FaceSearch.Looking && !sending
    Box(modifier = modifier.fillMaxSize().background(Color.Black).testTag("screen_redact")) {
        PhotoWithCovers(photo, redaction, editable, onTap, onDraw, onToggle)
        Status(
            redaction = redaction,
            search = search,
            modifier = Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing),
        )
        Controls(
            sending = sending,
            done = editable,
            onDone = onDone,
            onCancel = onCancel,
            modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing),
        )
    }
}

@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod") // one gesture loop: a pinch, a drag or a tap, told apart as they go
private fun PhotoWithCovers(
    photo: ImageBitmap,
    redaction: Redaction,
    editable: Boolean,
    onTap: (Float, Float, Float) -> Unit,
    onDraw: (Float, Float, Float, Float) -> Unit,
    onToggle: (Int) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewWidth = constraints.maxWidth.toFloat()
        val viewHeight = constraints.maxHeight.toFloat()
        val fit = remember(photo, viewWidth, viewHeight) { ViewTransform.fit(photo.width, photo.height, viewWidth, viewHeight) }
        var transform by remember(fit) { mutableStateOf(fit) }
        var draft by remember { mutableStateOf<Pair<Offset, Offset>?>(null) }
        val tap by rememberUpdatedState(onTap)
        val draw by rememberUpdatedState(onDraw)
        val density = LocalDensity.current
        val tapSlop = with(density) { TAP_SLOP.toPx() }
        val photoLabel = stringResource(R.string.redact_photo)
        // The same fill the sent photo gets: Cover.fillColor over the same pixels.
        val pixels = remember(photo) { photo.toPixelMap() }
        val fills =
            remember(redaction, pixels) {
                redaction.covers.associate { cover ->
                    cover.id to
                        Color(cover.fillColor { x, y -> pixels[x, y].toArgb() })
                }
            }

        Canvas(
            modifier =
                Modifier
                    .fillMaxSize()
                    .testTag("redact_photo")
                    .semantics { contentDescription = photoLabel }
                    .pointerInput(fit, editable) {
                        if (!editable) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val start = down.position
                            var pinched = false
                            var dragging = false
                            do {
                                val event = awaitPointerEvent()
                                if (event.changes.count { it.pressed } > 1) pinched = true
                                if (pinched) {
                                    draft = null
                                    val centroid = event.calculateCentroid()
                                    if (centroid != Offset.Unspecified) {
                                        val pan = event.calculatePan()
                                        transform =
                                            transform
                                                .zoomedAround(
                                                    centroid.x,
                                                    centroid.y,
                                                    event.calculateZoom(),
                                                    fit.scale,
                                                    fit.scale * ViewTransform.MAX_ZOOM,
                                                ).panned(pan.x, pan.y)
                                                .clamped(photo.width, photo.height, viewWidth, viewHeight)
                                    }
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                } else {
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!dragging && (change.position - start).getDistance() > viewConfiguration.touchSlop) dragging = true
                                    if (dragging) {
                                        draft = start to change.position
                                        change.consume()
                                    }
                                }
                            } while (event.changes.any { it.pressed })
                            val t = transform
                            val drawn = draft
                            draft = null
                            // A pinch only moved the view; anything else was a drag or a tap.
                            if (!pinched) {
                                if (dragging && drawn != null) {
                                    val (a, b) = drawn
                                    draw(t.toPhotoX(a.x), t.toPhotoY(a.y), t.toPhotoX(b.x), t.toPhotoY(b.y))
                                } else {
                                    tap(t.toPhotoX(start.x), t.toPhotoY(start.y), tapSlop / t.scale)
                                }
                            }
                        }
                    },
        ) {
            val t = transform
            drawImage(
                image = photo,
                dstOffset = IntOffset(t.offsetX.roundToInt(), t.offsetY.roundToInt()),
                dstSize = IntSize((photo.width * t.scale).roundToInt(), (photo.height * t.scale).roundToInt()),
                filterQuality = FilterQuality.Medium,
            )
            val thin = 1.dp.toPx()
            val outline = 2.dp.toPx()
            val dashes = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
            for (cover in redaction.covers) {
                val topLeft = Offset(t.toViewX(cover.left), t.toViewY(cover.top))
                val size = Size(cover.width * t.scale, cover.height * t.scale)
                val corner = CornerRadius(cover.cornerRadius * t.scale)
                if (cover.covered) {
                    drawRoundRect(fills.getValue(cover.id), topLeft, size, corner)
                    drawRoundRect(Color.White.copy(alpha = OUTLINE_ALPHA), topLeft, size, corner, style = Stroke(thin))
                } else {
                    drawRoundRect(Color.Black, topLeft, size, corner, style = Stroke(outline + thin))
                    drawRoundRect(Color.White, topLeft, size, corner, style = Stroke(outline, pathEffect = dashes))
                }
            }
            draft?.let { (a, b) ->
                val topLeft = Offset(min(a.x, b.x), min(a.y, b.y))
                val size = Size(abs(b.x - a.x), abs(b.y - a.y))
                drawRect(Color.Black, topLeft, size, style = Stroke(outline + thin))
                drawRect(Color.White, topLeft, size, style = Stroke(outline))
            }
        }

        // One node per cover for TalkBack and tests, over the cover it names, never smaller than a touch target around
        // its centre, so a small face can still be found by touch. Semantics only: no pointer input, so a touch falls
        // through to the photo's own gestures.
        val coveredLabel = stringResource(R.string.redact_face_covered)
        val uncoveredLabel = stringResource(R.string.redact_face_uncovered)
        val minTarget = with(density) { MIN_TARGET.toPx() }
        redaction.covers.forEachIndexed { index, cover ->
            val label = stringResource(R.string.redact_face, index + 1)
            val t = transform
            val width = maxOf(cover.width * t.scale, minTarget)
            val height = maxOf(cover.height * t.scale, minTarget)
            val left = t.toViewX(cover.left) - (width - cover.width * t.scale) / 2
            val top = t.toViewY(cover.top) - (height - cover.height * t.scale) / 2
            Box(
                Modifier
                    .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                    .size(with(density) { width.toDp() }, with(density) { height.toDp() })
                    .testTag("redact_cover_${cover.id}")
                    .semantics {
                        contentDescription = label
                        stateDescription = if (cover.covered) coveredLabel else uncoveredLabel
                        role = Role.Button
                        if (editable) {
                            onClick {
                                onToggle(cover.id)
                                true
                            }
                        }
                    },
            )
        }
    }
}

@Composable
private fun Status(
    redaction: Redaction,
    search: FaceSearch,
    modifier: Modifier = Modifier,
) {
    val found = redaction.covers.any { it.found }
    val headline =
        when (search) {
            FaceSearch.Looking -> stringResource(R.string.redact_looking)
            FaceSearch.Unavailable if redaction.coveredCount == 0 -> stringResource(R.string.redact_unavailable)
            FaceSearch.Done if !found && redaction.coveredCount == 0 -> stringResource(R.string.redact_none_found)
            else -> pluralStringResource(R.plurals.redact_covered, redaction.coveredCount, redaction.coveredCount)
        }
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = SCRIM_ALPHA))
                .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (search == FaceSearch.Looking) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = headline,
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                textAlign = TextAlign.Center,
                // Spoken as it changes, so "Looking for faces…" is followed by what was found.
                modifier = Modifier.testTag("redact_status").semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (search != FaceSearch.Looking) {
            Text(
                text = stringResource(R.string.redact_hint),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = HINT_ALPHA),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Cancel and done, laid out as the camera's controls are; both meet the 48 dp touch target. */
@Composable
private fun Controls(
    sending: Boolean,
    done: Boolean,
    onDone: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalIconButton(onClick = onCancel, modifier = Modifier.size(48.dp).testTag("redact_cancel")) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_cancel), modifier = Modifier.size(24.dp))
        }
        FilledIconButton(onClick = onDone, enabled = done, modifier = Modifier.size(64.dp).testTag("redact_done")) {
            if (sending) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp, color = LocalContentColor.current)
            } else {
                Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.action_done), modifier = Modifier.size(32.dp))
            }
        }
    }
}

/** How far outside a cover a tap still lands on it: a finger's width, not a pixel's. */
private val TAP_SLOP = 12.dp

/** The smallest a cover's accessibility node gets: Material's touch target. */
private val MIN_TARGET = 48.dp

// Dark enough under the hint (white at [HINT_ALPHA]) to keep it above 4.5:1 over a white photo; 0.6 gave 4.3:1.
private const val SCRIM_ALPHA = 0.7f
private const val HINT_ALPHA = 0.8f
private const val OUTLINE_ALPHA = 0.6f

@Preview(name = "Faces covered", widthDp = 411, heightDp = 891)
@Composable
fun RedactReviewPreview() =
    KnitPreview {
        val photo = previewPhoto()
        val found = Redaction.of(photo.width, photo.height, PREVIEW_FACES)
        val redaction = found.toggle(found.covers.last().id).tap(photo.width * 0.86f, photo.height * 0.42f)
        RedactReviewScreenContent(photo, redaction, FaceSearch.Done, { _, _, _ -> }, { _, _, _, _ -> }, {}, {}, {})
    }

@Preview(name = "Looking", widthDp = 411, heightDp = 891)
@Composable
fun RedactReviewLookingPreview() =
    KnitPreview {
        val photo = previewPhoto()
        RedactReviewScreenContent(
            photo,
            Redaction(photo.width, photo.height),
            FaceSearch.Looking,
            { _, _, _ -> },
            { _, _, _, _ -> },
            {},
            {},
            {},
        )
    }

@Preview(name = "Unavailable", widthDp = 411, heightDp = 891)
@Composable
fun RedactReviewUnavailablePreview() =
    KnitPreview {
        val photo = previewPhoto()
        RedactReviewScreenContent(photo, Redaction(photo.width, photo.height), FaceSearch.Unavailable, { _, _, _ -> }, { _, _, _, _ -> }, {
        }, {}, {})
    }

/** Four figures against a sky, drawn rather than photographed: no real face ships in the sample data. */
@Composable
private fun previewPhoto(): ImageBitmap {
    val density = LocalDensity.current
    return remember {
        val image = ImageBitmap(PREVIEW_WIDTH, PREVIEW_HEIGHT)
        CanvasDrawScope().draw(
            density,
            LayoutDirection.Ltr,
            androidx.compose.ui.graphics
                .Canvas(image),
            Size(PREVIEW_WIDTH.toFloat(), PREVIEW_HEIGHT.toFloat()),
        ) {
            drawRect(Brush.verticalGradient(listOf(Color(0xFF7FB2E5), Color(0xFFD9C9A3))))
            for (face in PREVIEW_FACES) {
                drawRoundRect(
                    Color(0xFF3E4A5C),
                    Offset(face.left - face.width * 0.4f, face.bottom),
                    Size(
                        face.width * 1.8f,
                        PREVIEW_HEIGHT - face.bottom,
                    ),
                    CornerRadius(face.width * 0.5f),
                )
                drawOval(Color(0xFFC99A7A), Offset(face.left, face.top), Size(face.width, face.height))
            }
        }
        image
    }
}

private const val PREVIEW_WIDTH = 960
private const val PREVIEW_HEIGHT = 720
private val PREVIEW_FACES =
    listOf(
        FaceBox(150f, 260f, 90f, 115f, 0.93f),
        FaceBox(380f, 230f, 100f, 125f, 0.91f),
        FaceBox(610f, 280f, 85f, 105f, 0.88f),
        FaceBox(470f, 520f, 40f, 50f, 0.41f),
    )
