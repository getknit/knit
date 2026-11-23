package app.getknit.knit.ui.devview

import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.mesh.lora.HeardRadio
import app.getknit.knit.mesh.lora.LoraTraceEvent
import app.getknit.knit.ui.devview.InspectFormat.ABSENT
import app.getknit.knit.ui.devview.InspectFormat.bytes
import app.getknit.knit.ui.devview.InspectFormat.delta
import app.getknit.knit.ui.devview.InspectFormat.span
import app.getknit.knit.ui.devview.InspectFormat.stamp
import java.util.Locale

/** What the LoRa trace says about one row: its [record], or null when the book holds none, and the [book]'s own facts. */
class LoraTraceView(
    val record: LoraTraceRecord?,
    val book: LoraTraceBookFacts,
)

/**
 * The message inspector's `lora` group (ADR 2026-10.s7wg, its slice-3 amendment): what the LoRa plane noted about the row,
 * oldest first, in log notation. Pure, so `MessageInspectionTest` pins every row.
 *
 * A Heard's `from` is the radio's claim, the board that aired the packet (for a re-aired frame, the relayer), never the
 * author, and never resolved to a contact. Its board rx time is on the board's own clock. A room post's payload tag is
 * compared by the book and never shown.
 */
internal object LoraTraceRows {
    fun group(
        message: MessageEntity,
        view: LoraTraceView?,
    ): InspectGroup? {
        view ?: return null
        val record = view.record
        if (record == null) {
            if (!carriedByLora(message)) return null
            return InspectGroup(TITLE, absent(view.book))
        }
        return InspectGroup(TITLE, rows(record))
    }

    /** A row the trace would be expected to hold: one that reached us over LoRa, or a bridged room's. */
    private fun carriedByLora(m: MessageEntity): Boolean =
        m.receivedVia == DeliveryPlane.LoRa.code || Conversations.isBridgedRoom(m.conversationId)

    /** The book's own facts beside the absent mark, never a guessed cause. */
    private fun absent(book: LoraTraceBookFacts): List<InspectRow> =
        listOf(
            InspectRow("lora_trace", ABSENT),
            InspectRow("lora_trace on since", stamp(book.onSince)),
            InspectRow("lora_trace evicted", book.evicted.toString()),
        )

    private fun rows(record: LoraTraceRecord): List<InspectRow> =
        buildList {
            val first = record.events.firstOrNull()?.atMs
            val firstTag = record.events.firstNotNullOfOrNull { it as? LoraTraceEvent.Heard }?.payloadTag
            add(InspectRow("lora_trace", if (record.originated) "originated here" else "heard only"))
            first?.let { add(InspectRow("first noted", stamp(it))) }
            val counts = HashMap<String, Int>()
            record.events.forEach { event ->
                val kind = kindOf(event)
                val n = (counts[kind] ?: 0) + 1
                counts[kind] = n
                add(InspectRow("$kind $n", "${delta(event.atMs - (first ?: event.atMs))} · ${describe(event, firstTag)}"))
            }
            if (record.heardMore > 0) add(InspectRow("heard (not kept)", record.heardMore.toString()))
            if (record.differentPayload > 0) add(InspectRow("heard, different payload", record.differentPayload.toString()))
            if (record.requeues > 0) add(InspectRow("requeued (not kept)", record.requeues.toString()))
            if (record.dropped > 0) add(InspectRow("events (not kept)", record.dropped.toString()))
            if (record.heardOnOwn > 0) add(InspectRow("heard under our own id (not kept)", record.heardOnOwn.toString()))
        }

    private fun kindOf(event: LoraTraceEvent): String =
        when (event) {
            is LoraTraceEvent.Heard -> "heard"
            is LoraTraceEvent.Queued -> "queued"
            is LoraTraceEvent.Aired -> "aired"
            is LoraTraceEvent.Refused -> "refused"
            is LoraTraceEvent.Requeued -> "requeued"
            is LoraTraceEvent.Ended -> "ended"
        }

    private fun describe(
        event: LoraTraceEvent,
        firstTag: Int?,
    ): String {
        val board = event.board.name.lowercase(Locale.ROOT)
        return when (event) {
            is LoraTraceEvent.Heard -> heard(board, event, firstTag)
            is LoraTraceEvent.Queued -> queued(board, event)
            is LoraTraceEvent.Aired -> aired(board, event)
            is LoraTraceEvent.Refused -> "$board ${event.reason.name}"
            is LoraTraceEvent.Requeued -> "$board ${event.reason.name}"
            is LoraTraceEvent.Ended -> ended(board, event)
        }
    }

    private fun heard(
        board: String,
        e: LoraTraceEvent.Heard,
        firstTag: Int?,
    ): String =
        buildList {
            add("$board ${e.route.name.lowercase(Locale.ROOT)} slot ${e.slot}")
            add("from ${e.from ?: ABSENT} (${if (e.fromVouched) "vouched by our board" else "radio's claim"})")
            e.packetId?.let { add(hex("pkt", it)) }
            addAll(radio(e.radio, e.atMs))
            if (e.parts > 1) add("parts ${e.parts}")
            if (e.notes.isNotEmpty()) add(e.notes.joinToString(",") { it.name })
            // Which copy differs, never the tag itself: the first Heard may be the forger's, so the reader weighs each by
            // its vouched mark.
            if (firstTag != null && e.payloadTag != null && e.payloadTag != firstTag) add("payload differs from the first heard")
        }.joinToString(SEP)

    private fun radio(
        r: HeardRadio,
        atMs: Long,
    ): List<String> =
        buildList {
            add("snr ${r.snrDeci?.let { String.format(Locale.ROOT, "%.1f dB", it / DECI) } ?: ABSENT}")
            add("rssi ${r.rssi?.let { "$it dBm" } ?: ABSENT}")
            add(hops(r))
            if (r.viaMqtt) add("via mqtt")
            add(if (r.live) "live" else "not live")
            r.boardRxMs?.let { add("board rx ${delta(it - atMs)} (board clock)") }
        }

    private fun hops(r: HeardRadio): String =
        when {
            r.direct -> "hops $ABSENT (direct, count not carried)"
            r.hopStart != null && r.hopLimit != null -> "hops ${r.hops ?: ABSENT} (start ${r.hopStart}, limit ${r.hopLimit})"
            else -> "hops ${r.hops ?: ABSENT}"
        }

    private fun queued(
        board: String,
        e: LoraTraceEvent.Queued,
    ): String =
        buildList {
            add("$board ${e.route.name.lowercase(Locale.ROOT)}")
            add("parts ${e.parts}")
            add(bytes(e.bytes))
            add("to ${e.to ?: "channel"}")
        }.joinToString(SEP)

    private fun aired(
        board: String,
        e: LoraTraceEvent.Aired,
    ): String =
        buildList {
            add("$board slot ${e.slot} part ${e.part + 1}/${e.parts}")
            if (e.handles.isNotEmpty()) add(e.handles.joinToString(",") { hex("handle", it) })
            add("booked ${span(e.bookedMs)}")
            if (e.heldForAir) add("held for air")
            if (e.heldForBand) add("held for band")
        }.joinToString(SEP)

    private fun ended(
        board: String,
        e: LoraTraceEvent.Ended,
    ): String =
        buildList {
            add("$board ${e.outcome.name}")
            if (e.late) add("late")
            e.nak?.let { add("nak $it") }
            e.refundedMs?.let { add("refunded ${span(it)}") }
        }.joinToString(SEP)

    private fun hex(
        label: String,
        id: Long,
    ): String = String.format(Locale.ROOT, "%s 0x%08x", label, id and PACKET_MASK)

    private const val TITLE = "lora"
    private const val SEP = " · "
    private const val DECI = 10.0
    private const val PACKET_MASK = 0xFFFF_FFFFL
}
