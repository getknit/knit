package app.getknit.knit.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Mirrors `ShareInboxTest`: the single-shot deep-link route handoff from a notification tap. */
class RouteInboxTest {
    @Test
    fun consumeReturnsOfferedRouteThenEmpties() {
        val inbox = RouteInbox()

        inbox.offer("chat/nearby")
        assertEquals("chat/nearby", inbox.pending.value)

        assertEquals("chat/nearby", inbox.consume())
        assertNull(inbox.pending.value)
    }

    @Test
    fun consumeIsSingleShot() {
        val inbox = RouteInbox()
        inbox.offer("chat/peer-1")

        assertEquals("chat/peer-1", inbox.consume())
        assertNull(inbox.consume())
    }

    @Test
    fun blankRoutesAreIgnored() {
        val inbox = RouteInbox()

        inbox.offer("")
        inbox.offer("   ")

        assertNull(inbox.pending.value)
        assertNull(inbox.consume())
    }

    @Test
    fun clearDropsPendingRoute() {
        val inbox = RouteInbox()
        inbox.offer("chat/nearby")

        inbox.clear()

        assertNull(inbox.pending.value)
        assertNull(inbox.consume())
    }
}
