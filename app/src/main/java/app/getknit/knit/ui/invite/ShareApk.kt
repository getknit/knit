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
//
// A single-APK (sideloaded) install shares its base APK directly. A Play App Bundle install is split
// into per-config APKs on disk; the base alone won't install (INSTALL_FAILED_MISSING_SPLIT), so we merge
// them into one universal, re-signed APK first (see ApkMerger.kt) and share that. Either way the receiver
// just taps one file and their OS installs it.

private const val APK_MIME = "application/vnd.android.package-archive"

/** No room in the cache dir to stage + merge a Play App Bundle install into one shareable APK. */
class ShareStorageException(message: String) : Exception(message)

/**
 * Prepares this app's own install as a single shareable APK and returns a FileProvider `content://`
 * [Uri] for it. Runs on [Dispatchers.IO]: a single-APK install is a quick copy; a Play App Bundle
 * (split) install is merged + re-signed (seconds — see [buildSharableSplitApk]), cached per version.
 *
 * May throw [ShareStorageException] (no space to merge) or a merge/sign failure; the caller toasts.
 */
suspend fun prepareKnitApk(context: Context): Uri = withContext(Dispatchers.IO) {
    val appInfo = context.applicationInfo
    val dest = if (appInfo.splitSourceDirs.isNullOrEmpty()) {
        copyBaseApk(context, File(appInfo.sourceDir))
    } else {
        buildSharableSplitApk(
            context,
            collectSplitPaths(appInfo.sourceDir, appInfo.splitSourceDirs),
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE.toLong(),
        )
    }
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", dest)
}

/** Copies the app's own base APK into a shareable cache file, refreshing when missing or stale. */
private fun copyBaseApk(context: Context, source: File): File {
    val dir = File(context.cacheDir, "apk").apply { mkdirs() }
    val dest = File(dir, "Knit-${BuildConfig.VERSION_NAME}.apk")
    // Refresh when missing or stale (an app update changes the base APK's length).
    if (!dest.exists() || dest.length() != source.length()) {
        source.copyTo(dest, overwrite = true)
    }
    return dest
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
