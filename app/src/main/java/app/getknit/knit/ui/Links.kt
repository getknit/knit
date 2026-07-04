package app.getknit.knit.ui

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

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
