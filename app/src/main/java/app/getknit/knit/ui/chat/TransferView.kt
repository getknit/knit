package app.getknit.knit.ui.chat

import android.content.Context
import androidx.annotation.StringRes
import app.getknit.knit.R
import app.getknit.knit.data.FileTypes
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.message.TransferPhase
import app.getknit.knit.data.message.TransferRecord
import app.getknit.knit.transfer.TransferRefusal
import app.getknit.knit.transfer.TransferState
import app.getknit.knit.translate.TranslateDirection
import app.getknit.knit.ui.translate.TranslateTransferModels

/**
 * A direct-transfer row as the card draws it: the persisted [TransferRecord]'s facts overlaid with the
 * manager's live state when there is one. [interrupted] is the one thing neither holds on its own — a record
 * that never reached a terminal phase and has no live state behind it, which is what a process death
 * mid-transfer leaves. [retryable] means the sender's card offers Retry: [TransferRecord.retryable], and a file
 * the chat has checked it can still read.
 */
data class TransferView(
    val id: String,
    val outgoing: Boolean,
    val name: String,
    val size: Long,
    val mime: String?,
    val phase: TransferPhase,
    val bytes: Long,
    val savedUri: String?,
    val reason: Int?,
    val interrupted: Boolean,
    val retryable: Boolean = false,
    /** A bundle of translation models' pins (ADR 2026-11.ejn8), null for a file. */
    val models: List<Long>? = null,
    /** Of an incoming models offer: how many this phone lacks, already holds, and does not know (another version). */
    val modelsNew: Int = 0,
    val modelsHeld: Int = 0,
    val modelsUnknown: Int = 0,
) {
    /** An archive or executable: nothing on the device can look inside it, and the card says so before Accept. */
    val risky: Boolean get() = models == null && FileTypes.isRisky(mime, name)

    /** An open models offer from the other phone (ADR 2026-11.ejn8): the card says what it means for this one. */
    val incomingModelsOffer: Boolean get() = models != null && phase == TransferPhase.Offered && !outgoing && !interrupted

    /** An app package: never offered an "Open", so Knit is never the install source (the roadmap's rule). */
    val installable: Boolean get() = FileTypes.isInstallable(mime, name)
}

/** Where one pin of a models offer stands on this phone (ADR 2026-11.ejn8), as its card counts them. */
enum class ModelStanding { New, Held, Unknown }

/**
 * The card for a [MessageEntity.KIND_FILE_TRANSFER] row, or null when its body is not a record this build reads.
 * [sourceReadable] answers, by transfer id, whether the file a Retry would send was found readable. It is false
 * until the chat has looked, so the button never appears and then vanishes. [standing] reads each pin of a models
 * offer against this phone's own table and models; only the card reads it, and nothing is answered by it.
 */
fun transferViewFor(
    row: MessageEntity,
    live: Map<String, TransferState>,
    sourceReadable: (transferId: String) -> Boolean = { false },
    standing: (pin: Long) -> ModelStanding = { ModelStanding.Unknown },
): TransferView? {
    val record = TransferRecord.decode(row.body) ?: return null
    val state = live[record.id]
    val models = state?.models ?: record.models
    val standings = models.orEmpty().map(standing)
    return TransferView(
        id = record.id,
        outgoing = record.outgoing,
        name = record.name,
        size = record.size,
        mime = record.mime,
        phase = state?.phase ?: record.phase,
        bytes = state?.bytes ?: 0L,
        savedUri = state?.savedUri ?: record.savedUri,
        reason = state?.reason ?: record.reason,
        interrupted = state == null && !record.phase.terminal,
        retryable = record.retryable(live = state != null) && sourceReadable(record.id),
        models = models,
        modelsNew = standings.count { it == ModelStanding.New },
        modelsHeld = standings.count { it == ModelStanding.Held },
        modelsUnknown = standings.count { it == ModelStanding.Unknown },
    )
}

/**
 * What a transfer is called on its card and in the chat list: the file's name, or for a bundle of translation models
 * this phone's own words for it, read from its table rather than the label stored when it arrived (ADR 2026-11.ejn8).
 */
fun transferTitle(
    context: Context,
    view: TransferView,
): String {
    val pins = view.models ?: return view.name
    val known = pins.mapNotNull(TranslateDirection::ofPin)
    return if (known.size == pins.size && known.isNotEmpty()) {
        TranslateTransferModels.label(context, known)
    } else {
        context.resources.getQuantityString(R.plurals.translate_models_count, pins.size, pins.size)
    }
}

/** The sentence for a refusal, as a string resource id. */
fun transferRefusalMessage(refusal: TransferRefusal): Int =
    when (refusal) {
        TransferRefusal.NoWifiDirect -> R.string.chat_transfer_no_wifi_direct
        TransferRefusal.WifiOff -> R.string.chat_transfer_wifi_off
        TransferRefusal.Permission -> R.string.chat_transfer_permission
        TransferRefusal.Busy -> R.string.chat_transfer_busy
        TransferRefusal.Unreadable -> R.string.chat_transfer_unreadable
        TransferRefusal.TooLarge -> R.string.chat_transfer_too_large
        TransferRefusal.NotNearby -> R.string.chat_transfer_peer_not_nearby
        TransferRefusal.NoSession -> R.string.chat_transfer_no_session
        TransferRefusal.NoSpace -> R.string.chat_transfer_no_space
        TransferRefusal.Hotspot -> R.string.chat_transfer_hotspot
        TransferRefusal.Background -> R.string.chat_transfer_background
        TransferRefusal.Gone -> R.string.chat_transfer_gone
        TransferRefusal.CannotHost -> R.string.chat_transfer_cannot_host
    }

/**
 * A direct-transfer row's one-line stand-in in the chat list, or null when the row's body is not a record
 * this build reads.
 *
 * These rows are the one status-notice kind the list shows. Everything else with a `kind` is a notice *about*
 * the thread — a rename, a key change — whose sender is the event's subject rather than an author. A transfer
 * is the opposite: one of the two people offered a file and the other answered, which is a conversation, and
 * on a quiet thread it is the most consequential thing in it.
 */
fun transferPreview(
    context: Context,
    row: MessageEntity,
    live: Map<String, TransferState>,
): String? {
    val view = transferViewFor(row, live) ?: return null
    return context.getString(transferPreviewText(view), transferTitle(context, view))
}

/**
 * Which sentence a transfer row gets, as a format string taking the file name.
 *
 * Written from the reader's side rather than the author's, which is why the list must not put its "You: "
 * prefix in front of one: the sender's own row for a refused offer reads "They declined clip.mp4", and
 * "You: They declined clip.mp4" would name the wrong person twice over. Most states read the same from both
 * ends; the ones that do not are the ones where the two people did different things.
 */
@StringRes
fun transferPreviewText(view: TransferView): Int =
    when {
        // A record left non-terminal by a process death outranks whatever phase it froze at: the chat list is
        // exactly where a transfer that says "Sending…" three days on would go unnoticed.
        view.interrupted -> {
            R.string.chat_list_preview_transfer_interrupted
        }

        view.phase == TransferPhase.Offered -> {
            pick(view, R.string.chat_list_preview_transfer_offered, R.string.chat_list_preview_transfer_incoming)
        }

        view.phase == TransferPhase.Connecting -> {
            pick(
                view,
                R.string.chat_list_preview_transfer_connecting_send,
                R.string.chat_list_preview_transfer_connecting_receive,
            )
        }

        view.phase == TransferPhase.Transferring -> {
            pick(view, R.string.chat_list_preview_transfer_sending, R.string.chat_list_preview_transfer_receiving)
        }

        view.phase == TransferPhase.Done -> {
            pick(view, R.string.chat_list_preview_transfer_sent, R.string.chat_list_preview_transfer_received)
        }

        view.phase == TransferPhase.Declined -> {
            pick(view, R.string.chat_list_preview_transfer_declined, R.string.chat_list_preview_transfer_declined_by_you)
        }

        view.phase == TransferPhase.Expired -> {
            R.string.chat_list_preview_transfer_expired
        }

        view.phase == TransferPhase.Cancelled -> {
            R.string.chat_list_preview_transfer_cancelled
        }

        else -> {
            pick(view, R.string.chat_list_preview_transfer_failed_send, R.string.chat_list_preview_transfer_failed_receive)
        }
    }

/** [mine] when this phone offered the file, [theirs] when the other end did. */
@StringRes
private fun pick(
    view: TransferView,
    @StringRes mine: Int,
    @StringRes theirs: Int,
): Int = if (view.outgoing) mine else theirs
