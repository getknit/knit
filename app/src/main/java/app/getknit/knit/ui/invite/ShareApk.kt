package app.getknit.knit.ui.invite

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import app.getknit.knit.BuildConfig
import app.getknit.knit.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// Sharing the app to a phone that doesn't have Knit can't ride the mesh (that needs Knit on
// both ends). Instead we expose our own installed APK through a FileProvider and let the user fling
// it over an OS transport the receiver already has — Quick Share or Bluetooth — fully offline.

private const val APK_MIME = "application/vnd.android.package-archive"

/** The install was a Play App Bundle (split APKs); the base split alone won't install, so we refuse. */
class SplitApkException : Exception()

/**
 * Copies this app's own installed APK into a FileProvider-shareable cache file and returns a
 * `content://` [Uri] for it. The copy runs on [Dispatchers.IO] (the base APK is several MB).
 *
 * Throws [SplitApkException] for split (App Bundle) installs, where sharing only the base APK would
 * produce a file the receiver can't install.
 */
suspend fun prepareKnitApk(context: Context): Uri = withContext(Dispatchers.IO) {
    val appInfo = context.applicationInfo
    if (!appInfo.splitSourceDirs.isNullOrEmpty()) throw SplitApkException()

    val source = File(appInfo.sourceDir)
    val dir = File(context.cacheDir, "apk").apply { mkdirs() }
    val dest = File(dir, "Knit-${BuildConfig.VERSION_NAME}.apk")
    // Refresh when missing or stale (an app update changes the base APK's length).
    if (!dest.exists() || dest.length() != source.length()) {
        source.copyTo(dest, overwrite = true)
    }
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", dest)
}

/** Fires the system share sheet to send the prepared Knit APK [uri] to another app (Quick Share, Bluetooth, …). */
fun launchApkShareChooser(context: Context, uri: Uri) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = APK_MIME
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(send, context.getString(R.string.share_app_chooser_title))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chooser)
}

/** Explainer shown before the share sheet so the sender knows what to pick and the receiver knows to allow installing. */
@Composable
fun ShareKnitDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.share_app_dialog_title)) },
        text = { Text(stringResource(R.string.share_app_dialog_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.share_app_dialog_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}
