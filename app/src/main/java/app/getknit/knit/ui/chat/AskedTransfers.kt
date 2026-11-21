package app.getknit.knit.ui.chat

/**
 * One card per ask on the newer phone (ADR 2026-11.29m7): each outgoing update transfer is folded into the card of the
 * ask it answers, which then follows the transfer, rather than the ask's card saying it was sent while a second card
 * below it is still connecting. The two are tied by the request id the transfer's pin carried
 * ([app.getknit.knit.data.message.AppUpdateRecord.requestId]). Only this phone's own outgoing transfers fold, and only
 * into an ask in the same [rows]; a transfer whose ask is not there (an older page, a deleted card, a row written
 * before the id was kept) keeps its own card. Should one ask ever have two transfers, the newest is the one shown.
 */
internal fun foldAskedTransfers(rows: List<ChatRow>): List<ChatRow> {
    val asks = rows.mapNotNullTo(HashSet()) { it.updateRequest?.requestId }
    if (asks.isEmpty()) return rows
    val answered =
        rows
            .filter { row -> row.transfer?.let { it.outgoing && it.app?.requestId in asks } == true }
            .groupBy { checkNotNull(it.transfer?.app?.requestId) }
    if (answered.isEmpty()) return rows
    val folded = answered.values.flatten().mapTo(HashSet()) { it.id }
    return rows.mapNotNull { row ->
        val asked = row.updateRequest
        when {
            row.id in folded -> null
            asked != null -> answered[asked.requestId]?.maxBy { it.sentAt }?.let { row.copy(askedTransfer = it.transfer) } ?: row
            else -> row
        }
    }
}
