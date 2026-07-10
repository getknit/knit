package app.getknit.knit.ui.donate

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.getknit.knit.R
import app.getknit.knit.ui.openUrl
import app.getknit.knit.ui.preview.KnitPreview
import app.getknit.knit.ui.shareText

/**
 * "Support Knit" screen: Knit is funded entirely by tips, so this links out to the maintainer's
 * donation platforms, plus a "Share Knit" row that opens the share sheet with the Play Store link.
 * Reached from the chat-list overflow menu. Purely static — no ViewModel.
 *
 * Platforms are data-driven via [DONATION_PLATFORMS]; adding another (Buy Me a Coffee, etc.) is a
 * single list entry with nothing else to change.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DonateScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    Scaffold(
        modifier = Modifier.testTag("screen_donate"),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                title = { Text(stringResource(R.string.donate_title)) },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
        ) {
            Icon(
                Icons.Filled.Favorite,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.donate_blurb),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(24.dp))
            DONATION_PLATFORMS.forEach { platform ->
                PlatformRow(
                    nameRes = platform.nameRes,
                    icon = platform.icon,
                    onClick = { openUrl(context, platform.url) },
                )
            }
            val shareMessage = stringResource(R.string.share_knit_text, PLAY_STORE_URL)
            val shareChooserTitle = stringResource(R.string.share_knit_chooser_title)
            PlatformRow(
                nameRes = R.string.share_knit_menu,
                icon = Icons.Filled.Share,
                onClick = { shareText(context, shareMessage, shareChooserTitle) },
            )
        }
    }
}

private const val PLAY_STORE_URL = "https://play.google.com/store/apps/details?id=app.getknit.knit"

private data class DonationPlatform(
    @param:StringRes val nameRes: Int,
    val icon: ImageVector,
    val url: String,
)

private val DONATION_PLATFORMS =
    listOf(
        DonationPlatform(R.string.donate_kofi, Icons.Filled.Coffee, "https://ko-fi.com/zaventh"),
        // Add Buy Me a Coffee etc. here later — nothing else changes.
    )

@Composable
private fun PlatformRow(
    @StringRes nameRes: Int,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Spacer(Modifier.width(16.dp))
        Text(
            text = stringResource(nameRes),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Preview(showBackground = true)
@Composable
fun PlatformRowPreview() =
    KnitPreview {
        val platform = DONATION_PLATFORMS.first()
        PlatformRow(nameRes = platform.nameRes, icon = platform.icon, onClick = {})
    }

// DonateScreen takes no ViewModel (LocalContext is only touched inside a click lambda), so the whole
// screen previews directly without extracting a stateless content composable.
@Preview(showBackground = true)
@Composable
fun DonateScreenPreview() =
    KnitPreview {
        DonateScreen(onBack = {})
    }
