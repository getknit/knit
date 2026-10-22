package app.getknit.knit.ui.call

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.getknit.knit.R
import app.getknit.knit.call.CallManager
import app.getknit.knit.call.CallPhase
import app.getknit.knit.call.CallState
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.ui.components.Avatar
import app.getknit.knit.ui.preview.KnitPreview
import app.getknit.knit.ui.preview.PREVIEW_NOW
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.compose.getKoin

// The ongoing-call green, dark enough for white text (the ongoing notification is colorized with it too).
private val OngoingGreen = Color(0xFF137333)

/**
 * The app's screens, with a bar above them while a call is live (experimental, `CALLS_WIRED`): leaving [CallActivity]
 * keeps the call, and this is the way back to it from inside Knit, beside the ongoing notification. The bar sits under
 * the status bar, on the surface colour so the status icons stay legible, and the screens below it lose the
 * status-bar inset it took. [content] keeps its place in the tree whether the bar shows or not, so a call starting or
 * ending never rebuilds the screens under it.
 */
@Composable
fun CallBarHost(content: @Composable () -> Unit) {
    val koin = getKoin()
    // Absent outside the debug graph (a test's Koin): no calls, so never a bar.
    val calls = remember(koin) { koin.getOrNull<CallManager>() }
    val live =
        calls
            ?.state
            ?.collectAsState()
            ?.value
            ?.takeIf { it.phase != CallPhase.Ended }
    Column(Modifier.fillMaxSize()) {
        if (live != null) {
            Spacer(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsTopHeight(WindowInsets.statusBars)
                    .background(MaterialTheme.colorScheme.surface),
            )
            LiveCallBar(live, calls)
        }
        Box(
            Modifier
                .weight(1f)
                .then(if (live != null) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier),
        ) {
            content()
        }
    }
}

@Composable
private fun LiveCallBar(
    state: CallState,
    calls: CallManager,
) {
    val koin = getKoin()
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var avatarHash by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.peerId) {
        val peers = koin.getOrNull<PeerRepository>() ?: return@LaunchedEffect
        name = callerName(peers, state.peerId)
        avatarHash = peers.find(state.peerId)?.avatarHash
    }
    CallBar(
        state = state,
        name = name,
        avatarHash = avatarHash,
        onOpen = { context.startActivity(CallActivity.intent(context, state.id)) },
        onEnd = {
            // The app's scope, not the composition's: the bar leaves as the call ends, and must not cancel the ending.
            koin.get<CoroutineScope>().launch {
                if (state.phase == CallPhase.Alerting) calls.decline(state.id) else calls.hangUp()
            }
        },
    )
}

/** The bar itself: who, where the call stands, a tap anywhere to go back to it, and the red button to end it. */
@Composable
internal fun CallBar(
    state: CallState,
    name: String,
    avatarHash: String?,
    onOpen: () -> Unit,
    onEnd: () -> Unit,
    clock: () -> Long = System::currentTimeMillis,
) {
    val incoming = state.phase == CallPhase.Alerting
    Surface(onClick = onOpen, color = OngoingGreen, contentColor = Color.White, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(avatarHash = avatarHash, name = name, nodeId = state.peerId, size = 36.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (incoming) {
                        stringResource(
                            R.string.call_bar_incoming,
                        )
                    } else {
                        stringResource(R.string.call_bar_return, statusOf(state, clock))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            FilledIconButton(
                onClick = onEnd,
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = HangUpRed, contentColor = Color.White),
            ) {
                Icon(
                    Icons.Filled.CallEnd,
                    contentDescription = stringResource(if (incoming) R.string.call_action_decline else R.string.call_action_end_call),
                )
            }
        }
    }
}

@Composable
private fun PreviewBar(phase: CallPhase) =
    KnitPreview {
        CallBar(
            state =
                CallState(
                    id = "preview",
                    peerId = "hzwyqdbj74hu6gkurmcyxomaca",
                    outgoing = phase != CallPhase.Alerting,
                    phase = phase,
                    startedAt = PREVIEW_NOW - 90_000,
                    connectedAt = (PREVIEW_NOW - 83_000).takeIf { phase == CallPhase.Connected },
                ),
            name = "Ada Lovelace",
            avatarHash = null,
            onOpen = {},
            onEnd = {},
            clock = { PREVIEW_NOW },
        )
    }

@Preview(name = "Connected", widthDp = 411)
@Composable
fun CallBarConnectedPreview() = PreviewBar(CallPhase.Connected)

@Preview(name = "Ringing", widthDp = 411)
@Composable
fun CallBarRingingPreview() = PreviewBar(CallPhase.Ringing)

@Preview(name = "Incoming", widthDp = 411)
@Composable
fun CallBarIncomingPreview() = PreviewBar(CallPhase.Alerting)
