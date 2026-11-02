package app.getknit.knit.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import app.getknit.knit.R

/**
 * Opens the system picker behind this launcher, or says in a toast that the phone has none. Every document and
 * photo pick goes through it: `OpenDocument`, `CreateDocument` and `CreateNamedDocument` open the documents UI,
 * and `PickVisualMedia` falls back to `ACTION_OPEN_DOCUMENT` on a phone without the photo picker.
 *
 * `launch` throws `ActivityNotFoundException` when no activity answers the intent, as on a phone whose Files app
 * (DocumentsUI) has been disabled or removed. Thrown from a tap or a collector on the main thread, it crashed the
 * app: 2.10.1's direct-transfer picker on a Redmi A5 running Android 15, from Play's crash reports. The
 * activity-result registry drops the refused launch before it rethrows, so a result that turns up for it later
 * never reaches the callback, and the next launch opens as usual.
 *
 * Returns false when nothing took it, so a caller holding state for the result can drop it.
 */
fun <I> ActivityResultLauncher<I>.launchOrSay(
    context: Context,
    input: I,
): Boolean =
    try {
        launch(input)
        true
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.picker_missing, Toast.LENGTH_LONG).show()
        false
    }
