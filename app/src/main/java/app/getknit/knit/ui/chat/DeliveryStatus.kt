package app.getknit.knit.ui.chat

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import app.getknit.knit.R
import app.getknit.knit.data.message.ConversationKind
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.mesh.LoraRadioReached

/**
 * How far one of *our* messages has got, folded out of the three independent columns that record it:
 * [MessageEntity.pendingKey] (saved locally, never sealed — we don't hold the recipient's key yet),
 * [MessageEntity.received] (a delivery receipt came back), and nothing at all in between — except, for a DM
 * whose compact LoRa copy their board acked, the in-memory [LoraRadioReached] the second [of] folds in.
 *
 * A received message has no status of its own — delivery isn't ours to report — so [deliveryLabel]
 * takes `mine` and describes *arrival* instead for the other direction.
 */
enum class DeliveryStatus {
    /** Written locally but never sent: the recipient's public key hasn't reached us yet. */
    Pending,

    /** Flooded, no receipt back yet (or a broadcast/group send, which never gets one). */
    Sent,

    /**
     * A DM whose compact LoRa copy their own board acked, with no receipt back yet (ADR 040's 2026-10-31 amendment):
     * their **radio** has it, their phone has not said so. Never a group's, a room post's or a reaction's.
     */
    RadioReached,

    /** A delivery receipt came back. */
    Delivered,

    ;

    companion object {
        /** The status the stored row implies. Order matters: `pendingKey` outranks the (always false) tick. */
        fun of(message: MessageEntity): DeliveryStatus =
            when {
                message.pendingKey -> Pending
                message.received -> Delivered
                else -> Sent
            }

        /**
         * [of], with the LoRa plane's word that their board acked the DM ([reached], `MeshController.loraRadioReached`)
         * folded in: it lifts only a DM's [Sent] to [RadioReached]. A receipt always wins, so an entry that outlived its
         * ✓✓ changes nothing, and a group or room row never reads it.
         */
        fun of(
            message: MessageEntity,
            reached: LoraRadioReached?,
        ): DeliveryStatus {
            val stored = of(message)
            val dm = Conversations.kindFor(message.conversationId) == ConversationKind.DM
            return if (stored == Sent && reached != null && dm) RadioReached else stored
        }

        /**
         * The bubble's status, which reads booleans off its row rather than a [MessageEntity]: [received] first, then
         * [radioReached]. The bubble never shows [Pending].
         */
        fun ofTick(
            received: Boolean,
            radioReached: Boolean,
        ): DeliveryStatus =
            when {
                received -> Delivered
                radioReached -> RadioReached
                else -> Sent
            }
    }
}

/**
 * A resolved delivery line: the string resource plus any format arguments it takes. A plain `@StringRes`
 * no longer suffices now that the group form carries a count, and keeping the arguments beside the id (
 * rather than formatting here) leaves [deliveryLabel] a pure function the JVM tests can assert on
 * directly. Format it with [resolve].
 */
data class DeliveryText(
    @param:StringRes val resId: Int,
    val args: List<Any> = emptyList(),
)

/** Formats a [DeliveryText] against the current resources. */
@Composable
@Suppress("SpreadOperator") // stringResource takes varargs; args stays a List so a DeliveryText compares by value
fun DeliveryText.resolve(): String = stringResource(resId, *args.toTypedArray())

/**
 * The user-facing line for a message's delivery [status] on [plane]. The single definition shared by the
 * chat bubble's tick (an accessibility description — the tick itself is icon-only), the chat-list row, and
 * the message-details screen (where it is visible text), so the three can't drift apart.
 *
 * The plane only qualifies a delivery that actually happened: a [DeliveryStatus.Sent] message has no
 * receipt yet, so there is no plane to name. For an inbound message the frame's own arrival plane is the
 * evidence ([MessageEntity.receivedVia]); the Internet and LoRa planes are named, and every phone radio
 * reads as "nearby" — see [DeliveryPlane].
 *
 * [delivered]/[total] are the per-recipient counts for a **group** message we sent (`message_receipts`
 * against the effective roster, ADR 036). Given both, the line reports the ratio instead of a bare
 * "Delivered", because the tick itself cannot: `received` flips on the *first* member's ack, so ✓✓ means
 * "at least one", and on a group that is exactly the thing worth being precise about. Three rules keep it
 * honest:
 *
 * - **A zero count is not reported.** A message acked before this device recorded ackers is ticked with no
 *   rows at all; "Delivered to 0 of 3" would contradict its own tick. It falls back to plain "Delivered",
 *   the same "predates the table" rule the details screen's split uses.
 * - **The count outranks the plane.** A group's stored plane is first-evidence-wins across the whole
 *   roster, so naming it would describe whichever member happened to ack first — the ratio is the more
 *   useful of the two, and saying both is a mouthful in a tick description.
 * - **Absent counts change nothing.** Callers with no roster to hand (the chat list, every DM and
 *   broadcast message) omit them and get exactly today's wording.
 */
fun deliveryLabel(
    status: DeliveryStatus,
    plane: DeliveryPlane,
    mine: Boolean,
    delivered: Int? = null,
    total: Int? = null,
): DeliveryText =
    when {
        !mine -> {
            arrivalText(plane)
        }

        status == DeliveryStatus.Pending -> {
            DeliveryText(R.string.chat_status_pending_key)
        }

        status == DeliveryStatus.Delivered && delivered != null && delivered > 0 && total != null && total > 0 -> {
            DeliveryText(R.string.chat_status_delivered_count, listOf(delivered, total))
        }

        status == DeliveryStatus.Delivered -> {
            deliveredText(plane)
        }

        // Their radio, never their phone: what the board's ack proves and all it proves.
        status == DeliveryStatus.RadioReached -> {
            DeliveryText(R.string.chat_status_lora_radio_reached)
        }

        else -> {
            DeliveryText(R.string.chat_status_sent)
        }
    }

/** How a message we received got here — the plane is named only when it is worth a word. */
private fun arrivalText(plane: DeliveryPlane): DeliveryText =
    DeliveryText(
        when (plane) {
            DeliveryPlane.Internet -> R.string.chat_status_arrived_internet
            DeliveryPlane.DirectLink -> R.string.chat_status_arrived_link
            DeliveryPlane.LoRa -> R.string.chat_status_arrived_lora
            else -> R.string.chat_status_arrived_nearby
        },
    )

/** How our acked message got there, by the plane its first receipt crossed. */
private fun deliveredText(plane: DeliveryPlane): DeliveryText =
    DeliveryText(
        when (plane) {
            DeliveryPlane.Internet -> R.string.chat_status_delivered_internet
            DeliveryPlane.DirectLink -> R.string.chat_status_delivered_link
            DeliveryPlane.LoRa -> R.string.chat_status_delivered_lora
            else -> R.string.chat_status_delivered
        },
    )

/**
 * The glyph that says which plane carried a message — painted ahead of the ✓✓ on our acked sends and on
 * its own on an arrival: a globe for an Internet relay, two arrows for a contact's direct link (ADR 2026-10.a5yj — not
 * the globe: a link between two phones on one network never leaves it), the radio-waves mark for LoRa, and nothing for
 * a phone radio (the plain case needs no ornament). Pair it with [deliveryLabel] — the glyph never carries
 * the words; [planeTag] names its test tag.
 */
fun planeGlyph(plane: DeliveryPlane): ImageVector? =
    when (plane) {
        DeliveryPlane.Internet -> Icons.Filled.Public
        DeliveryPlane.DirectLink -> Icons.Filled.SwapVert
        DeliveryPlane.LoRa -> Icons.Filled.Sensors
        else -> null
    }

/**
 * The glyph ahead of the tick on one of *our* messages at [status]: the plane its receipt crossed once
 * [DeliveryStatus.Delivered], the LoRa mark while it has only [DeliveryStatus.RadioReached] their board, and nothing
 * otherwise. The single definition the bubble and the message-details screen share; [tickGlyphTag] names its tag.
 */
fun tickGlyph(
    status: DeliveryStatus,
    plane: DeliveryPlane,
): ImageVector? =
    when (status) {
        DeliveryStatus.Delivered -> planeGlyph(plane)
        DeliveryStatus.RadioReached -> planeGlyph(DeliveryPlane.LoRa)
        else -> null
    }

/**
 * The test tag of [tickGlyph]: `chat_tick_<plane>` on a delivery, `chat_tick_lora_reached` on the middle state — apart
 * from `chat_tick_lora`, which says the ✓✓ came back over LoRa.
 */
fun tickGlyphTag(
    status: DeliveryStatus,
    plane: DeliveryPlane,
): String? =
    when (status) {
        DeliveryStatus.Delivered -> planeTag(plane)?.let { "chat_tick_$it" }
        DeliveryStatus.RadioReached -> "chat_tick_lora_reached"
        else -> null
    }

/**
 * The plane a message's row shows: [DeliveryPlane.DirectLink] when a contact's direct link carried its file (ADR
 * 2026-10.a5yj), else [messagePlane], the one its frame or its receipt crossed. [linkHolders] are who a link proved holds
 * the file (`BlobRepository.observeLinkHolders`): for one we received, us — the bytes reached us over a link; for one of
 * ours, the thread's [conversationId], which is the peer in a DM, once its answer to our holder query came over a link
 * — a group sender asks no member for proof, since no member's holding speaks for another. That answer's plane is the
 * link's for a photo no relay would take, which only the link could carry; one the peer got elsewhere and confirmed
 * over a link reads as the link's too, the first proof standing. The proof is keyed by the file, so a picture forwarded
 * under its sealed identity (ADR 2026-10.mptm) shows the link on every row naming it.
 */
fun shownPlane(
    messagePlane: DeliveryPlane,
    mine: Boolean,
    me: String?,
    conversationId: String,
    linkHolders: Set<String>?,
): DeliveryPlane {
    val holders = linkHolders ?: return messagePlane
    val viaLink = if (mine) conversationId in holders else me != null && me in holders
    return if (viaLink) DeliveryPlane.DirectLink else messagePlane
}

/** The test-tag suffix of [plane]'s glyph (`chat_tick_<tag>` / `chat_arrived_<tag>`); null when it paints none. */
fun planeTag(plane: DeliveryPlane): String? =
    when (plane) {
        DeliveryPlane.Internet -> "relay"
        DeliveryPlane.DirectLink -> "link"
        DeliveryPlane.LoRa -> "lora"
        else -> null
    }

/**
 * The tick glyph for one of *our* messages at [status]: a clock while it can't be sent yet, one check once
 * it's flooded — still one while only their radio has it, with [tickGlyph] ahead of it — and two once a receipt
 * is back. The single definition shared by the bubble, the chat-list row and the message-details screen, so they
 * can't drift apart. Pair it with [deliveryLabel] for the words — the glyph alone never carries the meaning.
 */
fun deliveryIcon(status: DeliveryStatus): ImageVector =
    when (status) {
        DeliveryStatus.Pending -> Icons.Filled.Schedule
        DeliveryStatus.Sent, DeliveryStatus.RadioReached -> Icons.Filled.Done
        DeliveryStatus.Delivered -> Icons.Filled.DoneAll
    }
