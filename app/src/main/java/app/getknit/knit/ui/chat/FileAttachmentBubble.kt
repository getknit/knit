package app.getknit.knit.ui.chat

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.getknit.knit.R
import app.getknit.knit.data.FileTypes
import app.getknit.knit.data.relay.AttachmentWait

/**
 * The bubble for an arbitrary-file attachment (ADR 2026-09.qq2r): a type icon, the sender's own filename,
 * and a size, with a tap that opens it — through the system's document picker the first time, and from the
 * saved copy after that (ADR 2026-09.7ad3).
 *
 * There is no thumbnail, deliberately. A preview means decoding attacker-supplied bytes with a
 * format-specific decoder, and `PdfRenderer` additionally wants a *seekable* file descriptor, which the
 * encrypted blob store cannot hand it without either a proxy descriptor or the plaintext temp file ADR 029
 * refused. A named, sized, typed row says everything the recipient needs in order to decide; previews can
 * arrive later against exactly this shape.
 *
 * [flagged] draws a warning rather than a blur — there is no image to hide — but it is not nothing either.
 * It means the recipient's own decoder read these bytes as an explicit image despite the type the file
 * claims, which is worth saying before they save it under whatever name the sender chose.
 */
@Composable
fun FileAttachmentBubble(
    name: String?,
    mime: String?,
    declaredSize: Long?,
    heldBytes: Int?,
    ready: Boolean,
    flagged: Boolean,
    onOpen: () -> Unit,
    onLongClick: () -> Unit,
    // Which plane can still bring the bytes while [ready] is false (the placeholder's second line).
    wait: AttachmentWait = AttachmentWait.Nearby,
) {
    val context = LocalContext.current
    val label = name ?: stringResource(R.string.chat_file_unnamed)
    // Bytes we hold beat the size the sender declared, the moment we hold any: one is measured, the other is
    // a claim. Before the blob lands the claim is all there is, which is what it is carried for.
    val sizeBytes = heldBytes?.toLong() ?: declaredSize
    val warning = if (flagged) stringResource(R.string.chat_file_flagged) else null
    val waiting = if (ready) null else attachmentWaitHint(wait)
    val detail =
        listOfNotNull(
            sizeBytes?.let { Formatter.formatShortFileSize(context, it) },
            FileTypes.extensionOf(name).uppercase().ifEmpty { null },
            if (ready) null else stringResource(R.string.chat_file_loading),
        ).joinToString(SEPARATOR)

    // The card paints its own container, so it has to carry the matching content colour too. Without
    // this the ripple, the filename and the icon all inherit the *bubble's* content colour — which on
    // an outgoing message is onPrimaryContainer, a role belonging to a surface this card doesn't draw.
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .padding(vertical = 2.dp)
                    .width(BUBBLE_WIDTH)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .combinedClickable(
                        enabled = ready,
                        // A tap opens the file (saving it first, the first time); a risky one is only ever saved.
                        onClickLabel =
                            stringResource(if (FileTypes.isRisky(mime, name)) R.string.chat_file_save else R.string.chat_file_open),
                        onClick = onOpen,
                        onLongClick = onLongClick,
                    ).padding(10.dp)
                    // One node, one sentence: the icon is decorative and the lines are halves of the same label,
                    // so TalkBack reads "report.pdf, 1.4 MB · PDF" rather than walking three separate nodes.
                    .clearAndSetSemantics {
                        contentDescription = listOfNotNull(label, detail.ifEmpty { null }, waiting, warning).joinToString(", ")
                    },
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(ICON_SLOT),
            ) {
                if (ready) {
                    Icon(
                        fileIconFor(mime, name),
                        contentDescription = null,
                    )
                } else {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
            Column(
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(start = 10.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (detail.isNotEmpty()) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (waiting != null) {
                    Text(
                        text = waiting,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("chat_attachment_wait"),
                    )
                }
                if (warning != null) {
                    Text(
                        text = warning,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * The icon for a file, from its MIME first and its extension second. Coarse on purpose — a per-format icon
 * set would be a maintenance surface with no reader benefit, and the filename beside it already carries the
 * specifics. Anything unrecognised gets the generic sheet rather than a guess.
 *
 * Shared with the composer's staged preview so a file looks the same before and after it is sent.
 */
internal fun fileIconFor(
    mime: String?,
    name: String?,
): ImageVector {
    val type = mime.orEmpty().lowercase()
    val ext = FileTypes.extensionOf(name)
    return when {
        type == "application/pdf" || ext == "pdf" -> Icons.Filled.PictureAsPdf
        type.startsWith("video/") -> Icons.Filled.Videocam
        type.startsWith("audio/") -> Icons.Filled.MusicNote
        ext in SHEET_EXTENSIONS || type.contains("spreadsheet") -> Icons.Filled.TableChart
        ext in ARCHIVE_EXTENSIONS || type.contains("zip") || type.contains("compressed") -> Icons.Filled.Archive
        type.startsWith("text/") || ext in DOCUMENT_EXTENSIONS || type.contains("document") -> Icons.Filled.Description
        else -> Icons.AutoMirrored.Filled.InsertDriveFile
    }
}

private const val SEPARATOR = " · "
private val BUBBLE_WIDTH = 220.dp
private val ICON_SLOT = 36.dp

private val SHEET_EXTENSIONS = setOf("csv", "tsv", "xls", "xlsx", "ods", "numbers")
private val ARCHIVE_EXTENSIONS = setOf("zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "xz")
private val DOCUMENT_EXTENSIONS = setOf("doc", "docx", "odt", "rtf", "md", "txt", "epub", "pages")
