package app.getknit.knit.ui

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

/** Knit's Play Store listing — the "Share Knit" link, and the rate target when Play installed the app. */
const val PLAY_LISTING_URL = "https://play.google.com/store/apps/details?id=app.getknit.knit"

/** The public source repository — the rate target for non-Play installs (F-Droid / sideload: a GitHub star). */
const val REPO_URL = "https://github.com/getknit/knit"

/** The issue tracker — where the review prompt's "not really" branch sends private feedback. */
const val ISSUES_URL = "https://github.com/getknit/knit/issues"

/** Opens [url] in the user's browser. Swallows ActivityNotFoundException if nothing can handle it. */
fun openUrl(
    context: Context,
    url: String,
) {
    val intent =
        Intent(Intent.ACTION_VIEW, url.toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

/**
 * Opens the system share sheet with [text] (e.g. a link), titled [chooserTitle], so the user can send
 * it to any app. Swallows ActivityNotFoundException if nothing can handle it.
 */
fun shareText(
    context: Context,
    text: String,
    chooserTitle: String,
) {
    val send =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
    val chooser =
        Intent
            .createChooser(send, chooserTitle)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(chooser) }
}
