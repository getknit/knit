package app.getknit.knit.ui.chat

import app.getknit.knit.data.message.AppUpdateRecord
import app.getknit.knit.data.message.TransferPhase
import app.getknit.knit.data.message.UpdateRequestPhase
import app.getknit.knit.data.message.UpdateRequestRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** The newer phone's thread shows one card per ask (ADR 2026-11.29m7): the transfer Send started folds into it. */
class AskedTransfersTest {
    private fun row(
        id: String,
        sentAt: Long,
        ask: String? = null,
        transfer: TransferView? = null,
        mine: Boolean = false,
    ) = ChatRow(
        id = id,
        body = "",
        mine = mine,
        senderName = "Alex",
        senderNodeId = "alex",
        avatarHash = null,
        sentAt = sentAt,
        received = true,
        transfer = transfer,
        updateRequest = ask?.let { UpdateRequestRecord(it, 21_400_900L, UpdateRequestPhase.Sent) },
    )

    private fun view(
        id: String,
        requestId: String?,
        outgoing: Boolean = true,
        phase: TransferPhase = TransferPhase.Connecting,
    ) = TransferView(
        id = id,
        outgoing = outgoing,
        name = "Knit.apk",
        size = 259_000_000L,
        mime = null,
        phase = phase,
        bytes = 0L,
        savedUri = null,
        reason = null,
        interrupted = false,
        app = AppUpdateRecord(21_400_900L, "00", requestId),
    )

    @Test
    fun theTransferSendStartedBecomesTheAsksCardAndHasNoneOfItsOwn() {
        val sending = view("t1", "r1")
        val rows = listOf(row("upd:alex:r1", 1, ask = "r1"), row("xfer:t1", 2, transfer = sending, mine = true), row("m", 3))
        val folded = foldAskedTransfers(rows)
        assertEquals(listOf("upd:alex:r1", "m"), folded.map { it.id })
        assertSame(sending, folded.first().askedTransfer)
    }

    @Test
    fun onlyThisPhonesOwnTransferAnsweringThatAskFolds() {
        val rows =
            listOf(
                row("upd:alex:r1", 1, ask = "r1"),
                // The asker's side of a transfer, another ask's, a row from before the id was kept, and a plain file.
                row("xfer:in", 2, transfer = view("in", "r1", outgoing = false)),
                row("xfer:other", 3, transfer = view("other", "r2"), mine = true),
                row("xfer:old", 4, transfer = view("old", null), mine = true),
                row("xfer:file", 5, transfer = view("file", null).copy(app = null), mine = true),
            )
        val folded = foldAskedTransfers(rows)
        assertEquals(rows.map { it.id }, folded.map { it.id })
        assertNull(folded.first().askedTransfer)
    }

    @Test
    fun aTransferWhoseAskIsNotInTheRowsKeepsItsOwnCard() {
        val rows = listOf(row("xfer:t1", 2, transfer = view("t1", "r1"), mine = true))
        assertEquals(rows, foldAskedTransfers(rows))
    }

    @Test
    fun twoTransfersForOneAskShowTheNewestAndNeither() {
        val older = view("t1", "r1", phase = TransferPhase.Failed)
        val newer = view("t2", "r1")
        val rows =
            listOf(
                row("upd:alex:r1", 1, ask = "r1"),
                row("xfer:t1", 2, transfer = older, mine = true),
                row("xfer:t2", 3, transfer = newer, mine = true),
            )
        // In either order: the newest by time, not the first or last match.
        listOf(rows, rows.reversed()).forEach { order ->
            val folded = foldAskedTransfers(order)
            assertEquals(listOf("upd:alex:r1"), folded.map { it.id })
            assertSame(newer, folded.single().askedTransfer)
        }
    }
}
