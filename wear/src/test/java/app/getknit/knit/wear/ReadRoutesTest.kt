package app.getknit.knit.wear

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadRoutesTest {
    @Test
    fun `a Classic or dual bond tries RFCOMM first, then LE twice`() {
        assertEquals(listOf(Via.Classic, Via.Le, Via.Le), ReadRoutes.plan(leOnly = false))
    }

    @Test
    fun `an LE-only device never tries RFCOMM`() {
        assertEquals(listOf(Via.Le, Via.Le), ReadRoutes.plan(leOnly = true))
    }

    @Test
    fun `a fast Classic miss skips the LE legs and a slow one keeps them`() {
        assertEquals(false, ReadRoutes.afterClassicMiss(0))
        // Literals, not the constant: a retuned line has to move this test with it.
        assertEquals(false, ReadRoutes.afterClassicMiss(1_999))
        assertEquals(true, ReadRoutes.afterClassicMiss(2_000))
        assertEquals(true, ReadRoutes.afterClassicMiss(8_000))
    }
}
