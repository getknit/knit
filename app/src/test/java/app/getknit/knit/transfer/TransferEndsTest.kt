package app.getknit.knit.transfer

import app.getknit.knit.data.message.TransferPhase
import app.getknit.knit.mesh.protocol.TransferPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which ended transfers let their source go (ADR 2026-10.whkk): only an outgoing one no Retry will read again, the
 * mirror of `TransferRecord.retryable`. A covered photo's copy is deleted on that answer.
 */
class TransferEndsTest {
    private val ends = TransferEnds(FakeTransferFiles(), models = null)

    private fun state(
        phase: TransferPhase,
        outgoing: Boolean = true,
        reason: Int? = null,
    ) = TransferState("t1", "bob", outgoing, "photo.jpg", 10L, "image/jpeg", phase, reason = reason)

    @Test
    fun anOutgoingTransferThatArrivedOrThatItsReceiverDeclinedLetsItsSourceGo() {
        assertEquals(SOURCE, ends.unretryableSource(state(TransferPhase.Done), SOURCE))
        assertEquals(SOURCE, ends.unretryableSource(state(TransferPhase.Declined, reason = TransferPayload.REASON_USER), SOURCE))
    }

    @Test
    fun aTransferARetryMayStillReadKeepsItsSource() {
        for (phase in TransferPhase.entries.filter { it != TransferPhase.Done && it != TransferPhase.Declined }) {
            assertNull("$phase", ends.unretryableSource(state(phase), SOURCE))
        }
        assertNull(ends.unretryableSource(state(TransferPhase.Declined, reason = TransferPayload.REASON_CONNECTION), SOURCE))
        assertNull(ends.unretryableSource(state(TransferPhase.Declined), SOURCE))
    }

    @Test
    fun anIncomingTransferOrOneWithNoSourceLetsNothingGo() {
        assertNull(ends.unretryableSource(state(TransferPhase.Done, outgoing = false), SOURCE))
        assertNull(ends.unretryableSource(state(TransferPhase.Done), null))
    }

    private companion object {
        const val SOURCE = "file:///cache/covered-transfers/u/photo.jpg"
    }
}
