package app.getknit.knit.ui.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import app.getknit.knit.data.message.AppUpdateRecord
import app.getknit.knit.update.UpdateHint
import app.getknit.knit.update.UpdateRequest

/**
 * Everything a DM's screen draws for a peer update (ADR 2026-11.29m7), in one parameter so the chat's content
 * composable takes one more argument rather than nine: the older phone's [hint], the newer phone's [request] band
 * (this phone's APK is [ownSize] bytes), and which update cards are [staged] or [installing]. The default draws nothing.
 */
data class PeerUpdateUi(
    val hint: UpdateHint? = null,
    val request: UpdateRequest? = null,
    val ownSize: Long = 0L,
    val staged: Set<String> = emptySet(),
    val installing: Set<String> = emptySet(),
    val onAsk: () -> Unit = {},
    val onDismiss: () -> Unit = {},
    val onSend: () -> Unit = {},
    val onNotNow: () -> Unit = {},
    val onInstall: (transferId: String, record: AppUpdateRecord) -> Unit = { _, _ -> },
)

/**
 * Opens Android's "Install unknown apps" screen for Knit, the one grant a peer update needs (ADR 2026-11.29m7). The
 * person turns it on there and asks again; nothing is resumed behind their back.
 */
fun openInstallGrant(context: Context) {
    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.fromParts("package", context.packageName, null))
    runCatching { context.startActivity(intent) }
}
