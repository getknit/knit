package app.getknit.knit.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.toSize
import app.getknit.knit.R

/**
 * The chat's in-window layer: the bubble long-press menu ([MessageMenu]), the "more reactions" sheet
 * ([InWindowSheet]) and the confirmations they lead to ([InWindowAlertDialog]), drawn over the chat in its own
 * window instead of in a `Popup`, `ModalBottomSheet` or `AlertDialog`. Each of those is a window of its own:
 * opening one took focus from the composer, the soft keyboard closed, the chat reflowed under it and a menu
 * jumped after its bubble. Drawn here, focus never moves: the keyboard stays up and nothing behind changes size
 * (a text field inside the layer, like the sheet's search, takes the same keyboard over). The rest of the
 * screen dims (a menu keeps its bubble lit, drawn over the scrim and lifted into view when the menu has no room
 * beside it, as Signal does), a tap off the layer or Back closes it, and the
 * screen behind is hidden from TalkBack while it is open. One layer at a time: the newest replaces the last.
 */
@Stable
internal class MessageMenuHostState {
    internal var open by mutableStateOf<OpenLayer?>(null)
}

internal sealed interface LayerKind {
    /**
     * Placed by a bubble whose root-coordinate bounds [anchor] tracks, and drawn over the scrim from [bubble], the
     * bubble's own drawing recorded while the menu is open: lifted into the free space when the menu would cover it.
     */
    class Menu(
        val anchor: MutableState<Rect>,
        val bubble: GraphicsLayer,
    ) : LayerKind

    /** Rises from the bottom of the area the keyboard and bars leave. */
    data object Sheet : LayerKind

    /** Centred in that area. */
    data object Dialog : LayerKind
}

internal class OpenLayer(
    val token: Any,
    val kind: LayerKind,
    /** What TalkBack announces as the layer opens: no window opens, so nothing else would tell it. */
    val paneTitle: String,
    val onDismiss: () -> Unit,
    val content: @Composable () -> Unit,
)

internal val LocalMessageMenuHost = staticCompositionLocalOf<MessageMenuHostState?> { null }

/** The bubble bounds a menu anchors to: unclipped, in root coordinates, as a `Popup`'s anchor was. */
internal fun LayoutCoordinates.anchorBounds(): Rect = Rect(positionInRoot(), size.toSize())

/** Hosts [content] (the chat screen) with the in-window layer above it. */
@Composable
internal fun MessageMenuHost(content: @Composable () -> Unit) {
    val state = remember { MessageMenuHostState() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        val open = state.open
        Box(Modifier.fillMaxSize().then(if (open != null) Modifier.clearAndSetSemantics {} else Modifier)) {
            CompositionLocalProvider(LocalMessageMenuHost provides state) { content() }
        }
        when (val kind = open?.kind) {
            null -> Unit
            is LayerKind.Menu -> MenuLayer(open, kind, origin)
            LayerKind.Sheet -> SheetLayer(open)
            LayerKind.Dialog -> DialogLayer(open)
        }
    }
}

/** Shows [content] as [kind] in the host's layer for as long as this is composed; false outside a host. */
@Composable
private fun openLayer(
    kind: LayerKind,
    paneTitle: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
): Boolean {
    val host = LocalMessageMenuHost.current ?: return false
    val token = remember { Any() }
    val currentDismiss by rememberUpdatedState(onDismiss)
    val currentContent by rememberUpdatedState(content)
    DisposableEffect(host, kind, paneTitle) {
        host.open = OpenLayer(token, kind, paneTitle, onDismiss = { currentDismiss() }, content = { currentContent() })
        // A caller that leaves composition (a bubble scrolled away or deleted) takes its layer with it, never another's.
        onDispose { if (host.open?.token === token) host.open = null }
    }
    return true
}

/** The long-press menu of the bubble whose bounds [anchor] tracks. Outside a host (a preview) it shows nothing. */
@Composable
internal fun MessageMenu(
    anchor: MutableState<Rect>,
    bubble: GraphicsLayer,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val kind = remember(anchor, bubble) { LayerKind.Menu(anchor, bubble) }
    openLayer(kind, stringResource(R.string.chat_message_menu), onDismiss, content)
}

/** A bottom sheet in the chat's window; a [ModalBottomSheet] outside a host. [content] sizes itself. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InWindowSheet(
    paneTitle: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val tagged: @Composable () -> Unit = { Box(modifier) { content() } }
    if (!openLayer(LayerKind.Sheet, paneTitle, onDismiss, tagged)) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            modifier = modifier,
        ) { content() }
    }
}

/** An [AlertDialog] drawn in the chat's window; the platform one outside a host. */
@Composable
internal fun InWindowAlertDialog(
    // What TalkBack announces as it opens: the title's own text.
    paneTitle: String,
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
) {
    val body: @Composable () -> Unit = {
        Column(Modifier.padding(DIALOG_PADDING)) {
            ProvideTextStyle(MaterialTheme.typography.headlineSmall) {
                Box(Modifier.semantics { heading() }) { title() }
            }
            Spacer(Modifier.height(16.dp))
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                ProvideTextStyle(MaterialTheme.typography.bodyMedium) { text() }
            }
            Spacer(Modifier.height(24.dp))
            Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                dismissButton()
                confirmButton()
            }
        }
    }
    if (!openLayer(LayerKind.Dialog, paneTitle, onDismissRequest, body)) {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            confirmButton = confirmButton,
            dismissButton = dismissButton,
            title = title,
            text = text,
        )
    }
}

/** The fade every layer opens with, the dimmed screen behind it, and its Back and tap-off-to-close. */
@Composable
private fun rememberLayerFade(open: OpenLayer): Animatable<Float, *> {
    BackHandler(onBack = open.onDismiss)
    val fade = remember(open.token) { Animatable(0f) }
    LaunchedEffect(open.token) { fade.animateTo(1f, tween(durationMillis = FADE_MS)) }
    return fade
}

/** Taps off the layer's own surface close it; TalkBack gets the same as a "Close" action. */
@Composable
private fun Modifier.closesOnTap(open: OpenLayer): Modifier {
    val closeLabel = stringResource(R.string.action_close)
    return pointerInput(open.token) { detectTapGestures { open.onDismiss() } }
        .semantics {
            paneTitle = open.paneTitle
            onClick(label = closeLabel) {
                open.onDismiss()
                true
            }
        }
}

/** Eats taps on a layer's surface that no control inside took, so they don't reach [closesOnTap] behind. */
private fun Modifier.keepsTaps(): Modifier = pointerInput(Unit) { detectTapGestures {} }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SheetLayer(open: OpenLayer) {
    val fade = rememberLayerFade(open)
    val scrim = MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM_ALPHA)
    Box(
        Modifier
            .fillMaxSize()
            .drawBehind { drawRect(scrim.copy(alpha = scrim.alpha * fade.value)) }
            .closesOnTap(open)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = BottomSheetDefaults.ContainerColor,
            tonalElevation = BottomSheetDefaults.Elevation,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = fade.value
                        translationY = size.height * (1f - fade.value) * SHEET_RISE
                    }.keepsTaps(),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BottomSheetDefaults.DragHandle()
                open.content()
            }
        }
    }
}

@Composable
private fun DialogLayer(open: OpenLayer) {
    val fade = rememberLayerFade(open)
    val scrim = MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM_ALPHA)
    Box(
        Modifier
            .fillMaxSize()
            .drawBehind { drawRect(scrim.copy(alpha = scrim.alpha * fade.value)) }
            .closesOnTap(open)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = AlertDialogDefaults.shape,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation,
            modifier =
                Modifier
                    .widthIn(min = 280.dp, max = 560.dp)
                    .graphicsLayer { alpha = fade.value }
                    .keepsTaps(),
        ) { open.content() }
    }
}

@Composable
private fun MenuLayer(
    open: OpenLayer,
    kind: LayerKind.Menu,
    origin: Offset,
) {
    val fade = rememberLayerFade(open)
    val scrim = MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM_ALPHA)
    val insets = WindowInsets.safeDrawing
    val spacingPx = with(LocalDensity.current) { 8.dp.roundToPx() }
    val closeLabel = stringResource(R.string.action_close)
    // Where the menu was placed, so a tap on its own padding is not read as a tap off it, and how far the bubble's
    // drawing is lifted to make room for it (written by the layout, read by the draw).
    val menuBounds = remember { mutableStateOf(IntRect.Zero) }
    var bubbleDy by remember { mutableIntStateOf(0) }
    val anchor = kind.anchor.value.translate(-origin)
    Layout(
        content = { Box(Modifier.graphicsLayer { alpha = fade.value }) { open.content() } },
        modifier =
            Modifier
                .fillMaxSize()
                .drawBehind {
                    drawRect(scrim.copy(alpha = scrim.alpha * fade.value))
                    translate(anchor.left, anchor.top + bubbleDy) { drawLayer(kind.bubble) }
                }.pointerInput(open.token) {
                    detectTapGestures { tap -> if (!menuBounds.value.contains(tap.round())) open.onDismiss() }
                }.semantics {
                    paneTitle = open.paneTitle
                    onClick(label = closeLabel) {
                        open.onDismiss()
                        true
                    }
                },
    ) { measurables, constraints ->
        val placeable = measurables.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
        // Keep the menu and the bubble off the keyboard, the bars and any cut-out: place them inside the safe area.
        val left = insets.getLeft(this, layoutDirection)
        val top = insets.getTop(this)
        val area =
            IntSize(
                (constraints.maxWidth - left - insets.getRight(this, layoutDirection)).coerceAtLeast(0),
                (constraints.maxHeight - top - insets.getBottom(this)).coerceAtLeast(0),
            )
        val inArea =
            IntRect(
                anchor.left.toInt() - left,
                anchor.top.toInt() - top,
                anchor.right.toInt() - left,
                anchor.bottom.toInt() - top,
            )
        val placed = menuPlacement(inArea, area, IntSize(placeable.width, placeable.height), spacingPx)
        bubbleDy = placed.bubbleDy
        layout(constraints.maxWidth, constraints.maxHeight) {
            val x = placed.menu.x + left
            val y = placed.menu.y + top
            menuBounds.value = IntRect(x, y, x + placeable.width, y + placeable.height)
            placeable.place(x, y)
        }
    }
}

/** Where the menu goes and how far (in px, + down) the bubble is drawn from where it sits. */
internal data class MenuPlacement(
    val menu: IntOffset,
    val bubbleDy: Int,
)

/**
 * The menu beside its bubble without covering it, the way Signal lifts a long-pressed message: above the bubble
 * where it fits, else below, both with the bubble left in place. When neither fits (the keyboard is up and the menu is
 * seven rows), the bubble is drawn moved, as little as possible, so the two stack inside the [area]: the menu above or
 * below it, whichever moves it less. A bubble and menu taller together than the area fall back to [pickerPosition]
 * with the bubble in place, the menu kept on screen over part of it. Coordinates are the area's.
 */
internal fun menuPlacement(
    bubble: IntRect,
    area: IntSize,
    menu: IntSize,
    spacing: Int,
): MenuPlacement {
    val x = pickerPosition(bubble, area, menu, spacing).x
    val above = bubble.top - spacing - menu.height
    if (above >= 0) return MenuPlacement(IntOffset(x, above), 0)
    if (bubble.bottom + spacing + menu.height <= area.height) return MenuPlacement(IntOffset(x, bubble.bottom + spacing), 0)
    if (menu.height + spacing + bubble.height > area.height) return MenuPlacement(pickerPosition(bubble, area, menu, spacing), 0)
    // Menu on top of the area, the bubble pushed down under it; or the bubble pulled up, the menu under it at the bottom.
    val down = menu.height + spacing - bubble.top
    val up = (area.height - menu.height - spacing) - bubble.bottom
    return if (kotlin.math.abs(down) <= kotlin.math.abs(up)) {
        MenuPlacement(IntOffset(x, 0), down)
    } else {
        MenuPlacement(IntOffset(x, area.height - menu.height), up)
    }
}

private const val FADE_MS = 120
private const val SCRIM_ALPHA = 0.32f

// How far below its resting place, as a share of its height, the sheet starts its rise.
private const val SHEET_RISE = 0.25f
private val DIALOG_PADDING = 24.dp
