package app.getknit.knit.mesh

import app.getknit.knit.data.forward.ForwardRepository
import app.getknit.knit.data.message.AttachmentFetchRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A pull-through's deadline (#195, ADR 2026-10.2drc): a custody window from the latest row, when every row is a DM's. */
class PullThroughGateTest {
    private val ttl = ForwardRepository.DEFAULT_TTL_MS

    private fun row(
        sentAt: Long,
        dm: Boolean,
        own: Boolean = false,
    ) = AttachmentFetchRow("h", "s", null, approved = false, own = own, sentAt = sentAt, dm = dm, conversationId = "s")

    @Test
    fun aDmsAttachmentIsPulledThroughForACustodyWindowFromItsLatestRow() {
        assertEquals(1_000L + ttl, PullThroughGate.until(listOf(row(1_000L, dm = true))))
        assertEquals(
            "ours count too, and the latest wins",
            5_000L + ttl,
            PullThroughGate.until(listOf(row(1_000L, dm = true), row(5_000L, dm = true, own = true))),
        )
    }

    @Test
    fun aGroupOrARoomRowNamingTheHashMeansNoDeadline() {
        assertNull("a group's or a room's row", PullThroughGate.until(listOf(row(0L, dm = false))))
        assertNull("one among DMs is enough", PullThroughGate.until(listOf(row(1_000L, dm = true), row(0L, dm = false))))
        assertNull("no row, nothing to judge", PullThroughGate.until(emptyList()))
    }
}
