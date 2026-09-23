package app.getknit.knit.ui.chat

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContract
import app.getknit.knit.mesh.transferExtForMime

/** Everything a save needs about the attachment the user tapped, carried from the bubble to the picker. */
data class PendingSave(
    val hash: String,
    val key: String?,
    val name: String?,
    val mime: String?,
)

/** A received file written to the document the user picked, with the type to open it under. */
data class SavedFile(
    val uri: Uri,
    val mime: String?,
)

/** The MIME filter that means "any file", for both the document picker and a save with no better type. */
const val ANY_MIME = "*/*"

/**
 * `ACTION_CREATE_DOCUMENT` with the type **and** the suggested filename chosen per call.
 *
 * `ActivityResultContracts.CreateDocument` fixes its MIME type at construction and takes only a name as its
 * input, which is the wrong shape here: every received file has its own type, and a launcher remembered once
 * per screen would have to pick one for all of them. So this is its two-argument sibling and nothing more.
 *
 * The name is the sender's, already normalized at the decode boundary
 * ([app.getknit.knit.mesh.protocol.AttachmentName]) — it cannot be a path, and the picker treats it as a
 * suggestion the user is free to change either way.
 */
class CreateNamedDocument : ActivityResultContract<PendingSave, Uri?>() {
    override fun createIntent(
        context: Context,
        input: PendingSave,
    ): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.mime ?: ANY_MIME)
            .putExtra(Intent.EXTRA_TITLE, input.name ?: fallbackName(input))

    override fun parseResult(
        resultCode: Int,
        intent: Intent?,
    ): Uri? = intent.takeIf { resultCode == Activity.RESULT_OK }?.data

    /**
     * A name for a file whose sender gave it none — an attachment from a build that predates the sealed
     * name, or one whose name did not survive normalization. Content-addressed, so it is at least stable and
     * unique, with an extension derived from the type rather than guessed.
     */
    private fun fallbackName(input: PendingSave): String =
        "knit-${input.hash.take(HASH_PREFIX)}.${transferExtForMime(input.mime ?: ANY_MIME)}"

    private companion object {
        const val HASH_PREFIX = 8
    }
}

/**
 * Whether the document [uri] is still there and still ours to read. A query, not an open: opening a cloud
 * provider's document can start a download just to answer yes. A moved or deleted copy comes back empty or
 * throws, and so does one whose grant was pruned (the platform keeps a bounded number). Blocking; call it
 * off the main thread.
 */
internal fun ContentResolver.documentExists(uri: Uri): Boolean =
    runCatching {
        query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { it.moveToFirst() } == true
    }.getOrDefault(false)

/** The type the provider reports for [uri], or null when it names none or cannot be asked. Blocking. */
internal fun ContentResolver.typeOf(uri: Uri): String? = runCatching { getType(uri) }.getOrNull()
