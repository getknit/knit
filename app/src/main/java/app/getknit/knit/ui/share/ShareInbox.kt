package app.getknit.knit.ui.share

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * Text, an image, and/or an arbitrary file handed to Knit from another app's share sheet (an `ACTION_SEND`
 * intent). Each stream is kept as the [String] form of the URI the sender put in `EXTRA_STREAM` — re-parsing that
 * string with `Uri.parse` preserves the temporary read grant (the grant is keyed to the (uid, uri) pair, not a
 * particular `Uri` instance), and keeping it a plain string makes [ShareInbox] JVM-unit-testable.
 *
 * The URI is the sender's word, not a checked one: it may name Knit's own files. Nothing here reads it; the chat
 * asks [OutsideUris] before it does (#284).
 *
 * [imageUri] and [fileUri] are kept apart rather than folded into one field because they land in different
 * places: an image can be attached anywhere, while a file is only offered in DMs and groups (ADR
 * 2026-09.qq2r), so the destination has to be able to tell them apart and say so if it cannot take one.
 *
 * [imageType] is the type the share declared for [imageUri] (an `image/` or `video/` one). It names the failure
 * when Knit cannot read the item at all: without a grant the provider will not even type it for Knit, so the
 * share's own word is the only thing that still calls it a video (#268).
 *
 * [ownFile] is a file Knit made itself, handed over as bytes from inside the process (#287). It is the only
 * field no intent can fill: `MainActivity` never sets it.
 */
data class SharedContent(
    val text: String?,
    val imageUri: String?,
    val fileUri: String? = null,
    val imageType: String? = null,
    val ownFile: OwnFile? = null,
) {
    /** No text worth a draft and nothing to attach. */
    val isEmpty: Boolean
        get() = text.isNullOrBlank() && listOfNotNull(imageUri, fileUri, ownFile).isEmpty()

    /** True when the payload is a file and nothing a room could take, so only a DM or a group can receive it. */
    val fileOnly: Boolean
        get() = text.isNullOrBlank() && imageUri == null && (fileUri != null || ownFile != null)
}

/**
 * A file Knit rendered itself, a field log or a crash report from Diagnostics, carried into a chat as bytes
 * (#287). Diagnostics' share sheet serves these through Knit's own `FileProvider`, and the chat refuses every URI
 * on Knit's own provider because another app can name one just as well (ADR 2026-11.vdx4). Bytes handed over in
 * process carry no such doubt: only Knit's own code builds one, and it is never parsed from an intent.
 *
 * Not a data class: generated equality on a [ByteArray] compares identities, which reads as a value and is not.
 */
class OwnFile(
    val name: String,
    val mime: String,
    val bytes: ByteArray,
)

/**
 * A process-scoped, single-shot handoff for a payload arriving via the system share sheet. The
 * receiving [app.getknit.knit.MainActivity] parses the intent and [offer]s the payload here;
 * `KnitApp` observes [pending] to route to the share-target picker, and the destination
 * [app.getknit.knit.ui.chat.ChatScreen] [consume]s it into its draft. A holder is needed because
 * Compose navigation routes can't cleanly carry a `Uri` or long text.
 */
class ShareInbox {
    private val _pending = MutableStateFlow<SharedContent?>(null)
    val pending: StateFlow<SharedContent?> = _pending.asStateFlow()

    /** Stage a payload from the share sheet. Fully-empty payloads are ignored. */
    fun offer(content: SharedContent) {
        if (content.isEmpty) return
        _pending.value = content
    }

    /** Take the staged payload (if any) and clear it, so only the first reader consumes it. */
    fun consume(): SharedContent? = _pending.getAndUpdate { null }

    /** Drop any staged payload, e.g. when the user abandons the share-target picker. */
    fun clear() {
        _pending.value = null
    }
}
