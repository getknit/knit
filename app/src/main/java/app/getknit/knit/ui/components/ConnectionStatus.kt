package app.getknit.knit.ui.components

import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.SensorsOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.getknit.knit.R
import app.getknit.knit.data.relay.RelayPlane
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.mesh.lora.LoraPlane
import app.getknit.knit.ui.preview.KnitPreview
import app.getknit.knit.ui.theme.KnitMotion
import app.getknit.knit.ui.theme.knitColors
import kotlinx.coroutines.delay

/**
 * Connectivity indicator shared by the chat list and chat screens: a colored dot, a status label, and —
 * only once the Internet plane is armed — a trailing cloud glyph, then — only once a LoRa board is bound —
 * a radio-waves glyph for the board.
 *
 * [neighborCount] is the number of directly-connected mesh neighbors (the radio-level reach, identical
 * across conversations); [health] lets the row distinguish a genuine "nobody nearby" from the radios being
 * switched off or seized, so a user who turned Wi-Fi/Bluetooth off (or is in airplane mode) gets an
 * actionable hint instead of a bare "No mesh nodes connected".
 *
 * The two planes divide the row rather than sharing it: the **dot** is always the radios, the **glyph** is
 * always the Internet, and the **label** answers the one question both planes bear on — whether anything
 * can be reached at all. So [relay] adds words only when it changes that answer (radios dark but relays
 * carrying); when both planes work the mesh count stands unchanged and the glyph reports the Internet on
 * its own, which is what keeps a second plane from turning a one-line subtitle into two.
 *
 * [relay] is deliberately the whole-device [RelayPlane], not this thread's `RelayReach` — per-conversation
 * coverage is already spoken by the chat's relay notice, and repeating it in the header would state the
 * same fact twice on one screen. [lora] follows the same rules exactly: the board glyph reports the LoRa
 * plane on its own and never touches the label — a board needs this phone's Bluetooth, so "radios off but
 * LoRa live" cannot happen, and a LoRa-heard peer already counts in the mesh line.
 */
@Composable
fun ConnectionStatusRow(
    neighborCount: Int,
    health: TransportHealth,
    modifier: Modifier = Modifier,
    relay: RelayPlane = RelayPlane.Off,
    lora: LoraPlane = LoraPlane.Off,
) {
    val plane = settled(relay, live = RelayPlane.Live, down = RelayPlane.Down, graceMs = RELAY_DOWN_GRACE_MS)
    val board = settled(lora, live = LoraPlane.Live, down = LoraPlane.Down, graceMs = LORA_DOWN_GRACE_MS)
    val dotTarget =
        when (health) {
            // Radios off is user-actionable, not a fault — a muted dot, not an alarming red one.
            TransportHealth.Unavailable -> {
                MaterialTheme.colorScheme.outline
            }

            TransportHealth.Degraded -> {
                MaterialTheme.colorScheme.error
            }

            // Discovers only on screen (ADR 2026-09.535d) — a rule of the OS, not a fault, so it draws as
            // Healthy does: positive with peers, muted without.
            TransportHealth.Healthy, TransportHealth.ForegroundOnly -> {
                if (neighborCount > 0) MaterialTheme.knitColors.positive else MaterialTheme.colorScheme.outline
            }
        }
    // The dot changes meaning whenever a peer appears or the radios go dark. Crossfading it is what turns
    // "the header is different now" into "something just happened" — the change is easy to miss otherwise,
    // because nothing else on the row moves. `settled()` above already absorbs relay flap, so this only ever
    // animates a change that is real.
    val dotColor by animateColorAsState(targetValue = dotTarget, animationSpec = KnitMotion.effects(), label = "meshDot")
    val label = connectionLabel(neighborCount, health, plane)
    val planeDescription =
        when (plane) {
            RelayPlane.Off -> null
            RelayPlane.Down -> stringResource(R.string.chat_connection_relay_down_desc)
            RelayPlane.Live -> stringResource(R.string.chat_connection_relay_live_desc)
        }
    val boardDescription =
        when (board) {
            LoraPlane.Off -> null
            LoraPlane.Down -> stringResource(R.string.chat_connection_lora_down_desc)
            LoraPlane.Live -> stringResource(R.string.chat_connection_lora_live_desc)
        }
    // One semantics node for the whole row: dot, label and glyphs are views of a single status, and left
    // unmerged TalkBack stops on each in turn (and on a glyph with nothing to say). Each armed plane appends
    // its own clause, in the order its glyph is drawn.
    val description =
        listOfNotNull(planeDescription, boardDescription).fold(label) { spoken, clause ->
            stringResource(R.string.chat_connection_desc, spoken, clause)
        }
    Row(
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(8.dp)
                    .background(color = dotColor, shape = CircleShape),
        )
        Spacer(Modifier.width(6.dp))
        // "3 people nearby" → "2 people nearby" is a one-word edit that the eye reads as a glitch when it
        // happens between frames. The row is clearAndSetSemantics above, so none of the animation here adds
        // a semantics node or changes a syllable of what TalkBack says.
        val labelEnter = KnitMotion.enterFade()
        val labelExit = KnitMotion.exitFade()
        AnimatedContent(
            targetState = label,
            transitionSpec = { labelEnter togetherWith labelExit },
            label = "connectionLabel",
        ) { current ->
            Text(
                text = current,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // A glyph appearing at all is a settings change (the plane being armed or a board bound), which
        // happens on another screen — so it is deliberately NOT an AnimatedVisibility. Animating it would
        // also mean the glyph flipping to its struck-through form as it faded away. What DOES change while
        // this row is on screen is the tint, and that is what PlaneGlyph eases.
        if (plane != RelayPlane.Off) {
            Spacer(Modifier.width(6.dp))
            // Struck-through glyph for Down rather than only a paler tint: at 14dp a tint change alone is
            // the kind of distinction that disappears for a color-blind reader, and this row has no room
            // to spell the state out in words.
            PlaneGlyph(
                imageVector = if (plane == RelayPlane.Live) Icons.Outlined.Cloud else Icons.Outlined.CloudOff,
                live = plane == RelayPlane.Live,
            )
        }
        if (board != LoraPlane.Off) {
            Spacer(Modifier.width(6.dp))
            // The board, after the Internet: same tint rule, same struck-through Down glyph.
            PlaneGlyph(
                imageVector = if (board == LoraPlane.Live) Icons.Outlined.Sensors else Icons.Outlined.SensorsOff,
                live = board == LoraPlane.Live,
            )
        }
    }
}

/**
 * One armed plane's 14dp glyph. Decorative — [ConnectionStatusRow] speaks for the whole row — with its
 * live/down tint eased so a plane recovering reads as the same glyph brightening rather than as a new one.
 */
@Composable
private fun PlaneGlyph(
    imageVector: ImageVector,
    live: Boolean,
) {
    val tint by animateColorAsState(
        targetValue = if (live) MaterialTheme.knitColors.positive else MaterialTheme.colorScheme.outline,
        animationSpec = KnitMotion.effects(),
        label = "planeTint",
    )
    Icon(
        imageVector = imageVector,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(14.dp),
    )
}

@Composable
private fun connectionLabel(
    count: Int,
    health: TransportHealth,
    plane: RelayPlane,
): String {
    val relaying = plane == RelayPlane.Live
    return when (health) {
        TransportHealth.Unavailable -> {
            when {
                relaying -> stringResource(R.string.chat_connection_relay_no_radios)
                isAirplaneModeOn() -> stringResource(R.string.chat_connection_airplane)
                else -> stringResource(R.string.chat_connection_radio_off)
            }
        }

        TransportHealth.Degraded -> {
            if (relaying) {
                stringResource(R.string.chat_connection_relay_no_radios)
            } else {
                stringResource(R.string.chat_connection_degraded)
            }
        }

        // Peers we hold stay held (their links need no location); with none, name the rule rather than
        // "nobody nearby", which would blame the surroundings for what the OS is doing.
        TransportHealth.ForegroundOnly -> {
            when {
                count > 0 -> pluralStringResource(R.plurals.chat_connection_count, count, count)
                relaying -> stringResource(R.string.chat_connection_relay_only)
                else -> stringResource(R.string.chat_connection_foreground_only)
            }
        }

        TransportHealth.Healthy -> {
            when {
                // Both planes up: the mesh count stands and the glyph carries the Internet by itself.
                count > 0 -> pluralStringResource(R.plurals.chat_connection_count, count, count)

                relaying -> stringResource(R.string.chat_connection_relay_only)

                else -> stringResource(R.string.chat_connection_none)
            }
        }
    }
}

/**
 * [value], with its fall from [live] to [down] held back by [graceMs].
 *
 * A relay that merely reconnects reports zero live spools for a poll or two — `RelayStatusRepository`
 * re-reads on a 5s ticker and `ScopeSync` resets to a 1s backoff after a completed handshake — and a glyph
 * that strikes itself out and heals on every reconnect reads as a fault rather than as the steady coverage
 * it is. A relay that is genuinely gone backs off toward a minute, so it crosses the window and dims. The
 * board needs a longer window ([LORA_DOWN_GRACE_MS]): its session reconnects on a 5 s-and-up backoff, and
 * writing the Knit channel reboots it on purpose — ~10 s of reboot plus a reconnect and a handshake that
 * the user was told about and should not read as a fault.
 *
 * Only the live -> down edge waits. Reaching live is good news and applies at once, and off is a settings
 * change rather than a flap.
 */
@Composable
private fun <T> settled(
    value: T,
    live: T,
    down: T,
    graceMs: Long,
): T {
    var settled by remember { mutableStateOf(value) }
    LaunchedEffect(value) {
        if (value == down && settled == live) delay(graceMs)
        settled = value
    }
    return settled
}

private const val RELAY_DOWN_GRACE_MS = 12_000L
private const val LORA_DOWN_GRACE_MS = 45_000L

/**
 * Whether airplane mode is currently on, read from [Settings.Global] (no permission needed). Read at
 * composition — accurate whenever [TransportHealth.Unavailable] arrives, since toggling airplane mode
 * flips the radios and so re-drives health, recomposing this row.
 */
@Composable
private fun isAirplaneModeOn(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return Settings.Global.getInt(resolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
}

@Preview(showBackground = true)
@Composable
fun ConnectionStatusRowConnectedPreview() =
    KnitPreview {
        ConnectionStatusRow(neighborCount = 5, health = TransportHealth.Healthy)
    }

@Preview(showBackground = true)
@Composable
fun ConnectionStatusRowSinglePreview() =
    KnitPreview {
        ConnectionStatusRow(neighborCount = 1, health = TransportHealth.Healthy)
    }

@Preview(showBackground = true)
@Composable
fun ConnectionStatusRowDisconnectedPreview() =
    KnitPreview {
        ConnectionStatusRow(neighborCount = 0, health = TransportHealth.Healthy)
    }

@Preview(showBackground = true)
@Composable
fun ConnectionStatusRowRadioOffPreview() =
    KnitPreview {
        ConnectionStatusRow(neighborCount = 0, health = TransportHealth.Unavailable)
    }

@Preview(showBackground = true)
@Composable
fun ConnectionStatusRowDegradedPreview() =
    KnitPreview {
        ConnectionStatusRow(neighborCount = 0, health = TransportHealth.Degraded)
    }

@Preview(showBackground = true)
@Composable
fun ConnectionStatusRowBothPlanesPreview() =
    KnitPreview {
        ConnectionStatusRow(neighborCount = 3, health = TransportHealth.Healthy, relay = RelayPlane.Live)
    }

@Preview(showBackground = true)
@Composable
fun ConnectionStatusRowRelayOnlyPreview() =
    KnitPreview {
        ConnectionStatusRow(neighborCount = 0, health = TransportHealth.Healthy, relay = RelayPlane.Live)
    }

@Preview(showBackground = true)
@Composable
fun ConnectionStatusRowRelayWithRadiosOffPreview() =
    KnitPreview {
        ConnectionStatusRow(neighborCount = 0, health = TransportHealth.Unavailable, relay = RelayPlane.Live)
    }

@Preview(showBackground = true)
@Composable
fun ConnectionStatusRowRelayDownPreview() =
    KnitPreview {
        ConnectionStatusRow(neighborCount = 2, health = TransportHealth.Healthy, relay = RelayPlane.Down)
    }

@Preview(showBackground = true)
@Composable
fun ConnectionStatusRowLoraLivePreview() =
    KnitPreview {
        ConnectionStatusRow(neighborCount = 1, health = TransportHealth.Healthy, lora = LoraPlane.Live)
    }

@Preview(showBackground = true)
@Composable
fun ConnectionStatusRowLoraDownPreview() =
    KnitPreview {
        ConnectionStatusRow(neighborCount = 0, health = TransportHealth.Healthy, lora = LoraPlane.Down)
    }

@Preview(showBackground = true)
@Composable
fun ConnectionStatusRowThreePlanesPreview() =
    KnitPreview {
        ConnectionStatusRow(
            neighborCount = 3,
            health = TransportHealth.Healthy,
            relay = RelayPlane.Live,
            lora = LoraPlane.Live,
        )
    }
