package app.getknit.knit.ui.chatlist

import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.getknit.knit.R
import app.getknit.knit.ui.preview.KnitPreview

/**
 * A snackbar-styled, tap-to-fix connectivity banner pinned at the top of the chat list. Tapping the banner
 * calls [onOpenSettings] (deep-links to the relevant system radio panel); [onDismiss], when non-null, renders a
 * close button — it is null for [RadioWarning.AllRadiosOff], which is not dismissible.
 */
@Composable
fun RadioWarningBanner(
    warning: RadioWarning,
    onOpenSettings: () -> Unit,
    onDismiss: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val critical = warning == RadioWarning.AllRadiosOff
    // Convention (see ConnectionStatus.kt): a fault is `error`; an actionable radio-off is a calmer container.
    val container = if (critical) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val onContainer = if (critical) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }
    val icon: ImageVector = when (warning) {
        RadioWarning.BluetoothOff -> Icons.Filled.BluetoothDisabled
        RadioWarning.WifiOff -> Icons.Filled.WifiOff
        RadioWarning.AllRadiosOff -> Icons.Filled.Warning
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = container,
        contentColor = onContainer,
        tonalElevation = 3.dp,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            // The clickable lives inside the Surface so the ripple is clipped to the rounded shape.
            modifier = Modifier
                .clickable(onClick = onOpenSettings)
                .semantics { testTag = "chatlist_radio_banner" }
                .padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = if (onDismiss != null) 4.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                text = bannerMessage(warning),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (onDismiss != null) {
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.semantics { testTag = "chatlist_radio_banner_dismiss" },
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.chatlist_radio_banner_dismiss),
                    )
                }
            }
        }
    }
}

@Composable
private fun bannerMessage(warning: RadioWarning): String = when (warning) {
    RadioWarning.BluetoothOff -> stringResource(R.string.chatlist_radio_banner_bluetooth_off)
    RadioWarning.WifiOff -> stringResource(R.string.chatlist_radio_banner_wifi_off)
    RadioWarning.AllRadiosOff ->
        if (isAirplaneModeOn()) {
            stringResource(R.string.chatlist_radio_banner_all_off_airplane)
        } else {
            stringResource(R.string.chatlist_radio_banner_all_off)
        }
}

/** Airplane-mode read from [Settings.Global] (no permission), mirroring ConnectionStatus.kt. */
@Composable
private fun isAirplaneModeOn(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return Settings.Global.getInt(resolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
}

@Preview(showBackground = true)
@Composable
fun RadioWarningBannerBluetoothPreview() = KnitPreview {
    RadioWarningBanner(RadioWarning.BluetoothOff, onOpenSettings = {}, onDismiss = {})
}

@Preview(showBackground = true)
@Composable
fun RadioWarningBannerWifiPreview() = KnitPreview {
    RadioWarningBanner(RadioWarning.WifiOff, onOpenSettings = {}, onDismiss = {})
}

@Preview(showBackground = true)
@Composable
fun RadioWarningBannerAllOffPreview() = KnitPreview {
    RadioWarningBanner(RadioWarning.AllRadiosOff, onOpenSettings = {}, onDismiss = null)
}
