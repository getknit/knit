package app.getknit.knit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import android.provider.Settings
import app.getknit.knit.R
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.ui.preview.KnitPreview

/**
 * Mesh connectivity indicator shared by the chat list and chat screens (and future DM threads): a
 * colored dot plus a status label. [neighborCount] is the number of directly-connected mesh neighbors
 * (the radio-level reach, identical across conversations); [health] lets the row distinguish a genuine
 * "nobody nearby" from the radios being switched off or seized, so a user who turned Wi-Fi/Bluetooth
 * off (or is in airplane mode) gets an actionable hint instead of a bare "No mesh nodes connected".
 */
@Composable
fun ConnectionStatusRow(
    neighborCount: Int,
    health: TransportHealth,
    modifier: Modifier = Modifier,
) {
    val dotColor = when (health) {
        // Radios off is user-actionable, not a fault — a muted dot, not an alarming red one.
        TransportHealth.Unavailable -> MaterialTheme.colorScheme.outline
        TransportHealth.Degraded -> MaterialTheme.colorScheme.error
        TransportHealth.Healthy ->
            if (neighborCount > 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline
    }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color = dotColor, shape = CircleShape),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = connectionLabel(neighborCount, health),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun connectionLabel(count: Int, health: TransportHealth): String = when (health) {
    TransportHealth.Unavailable ->
        if (isAirplaneModeOn()) {
            stringResource(R.string.chat_connection_airplane)
        } else {
            stringResource(R.string.chat_connection_radio_off)
        }
    TransportHealth.Degraded -> stringResource(R.string.chat_connection_degraded)
    TransportHealth.Healthy ->
        if (count == 0) {
            stringResource(R.string.chat_connection_none)
        } else {
            pluralStringResource(R.plurals.chat_connection_count, count, count)
        }
}

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
fun ConnectionStatusRowConnectedPreview() = KnitPreview {
    ConnectionStatusRow(neighborCount = 5, health = TransportHealth.Healthy)
}

@Preview(showBackground = true)
@Composable
fun ConnectionStatusRowSinglePreview() = KnitPreview {
    ConnectionStatusRow(neighborCount = 1, health = TransportHealth.Healthy)
}

@Preview(showBackground = true)
@Composable
fun ConnectionStatusRowDisconnectedPreview() = KnitPreview {
    ConnectionStatusRow(neighborCount = 0, health = TransportHealth.Healthy)
}

@Preview(showBackground = true)
@Composable
fun ConnectionStatusRowRadioOffPreview() = KnitPreview {
    ConnectionStatusRow(neighborCount = 0, health = TransportHealth.Unavailable)
}

@Preview(showBackground = true)
@Composable
fun ConnectionStatusRowDegradedPreview() = KnitPreview {
    ConnectionStatusRow(neighborCount = 0, health = TransportHealth.Degraded)
}
