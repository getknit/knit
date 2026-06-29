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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.getknit.knit.R
import app.getknit.knit.ui.preview.KnitPreview

/**
 * Mesh connectivity indicator shared by the chat list and chat screens (and future DM threads): a
 * colored dot plus "Connected to N mesh nodes" / "No mesh nodes connected". [neighborCount] is the
 * number of directly-connected mesh neighbors (the radio-level reach, identical across conversations).
 */
@Composable
fun ConnectionStatusRow(
    neighborCount: Int,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(
                    color = if (neighborCount > 0) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                    shape = CircleShape,
                ),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = connectionLabel(neighborCount),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun connectionLabel(count: Int): String =
    if (count == 0) {
        stringResource(R.string.chat_connection_none)
    } else {
        pluralStringResource(R.plurals.chat_connection_count, count, count)
    }

@Preview(showBackground = true)
@Composable
fun ConnectionStatusRowConnectedPreview() = KnitPreview {
    ConnectionStatusRow(neighborCount = 5)
}

@Preview(showBackground = true)
@Composable
fun ConnectionStatusRowSinglePreview() = KnitPreview {
    ConnectionStatusRow(neighborCount = 1)
}

@Preview(showBackground = true)
@Composable
fun ConnectionStatusRowDisconnectedPreview() = KnitPreview {
    ConnectionStatusRow(neighborCount = 0)
}
