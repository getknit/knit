package app.getknit.knit.wear

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadRoutesTest {
    @Test
    fun `a Classic or dual bond tries RFCOMM first, then LE twice`() {
        assertEquals(listOf(Via.Classic, Via.Le, Via.Le), ReadRoutes.plan(leOnly = false, lastGood = null))
        assertEquals(listOf(Via.Classic, Via.Le, Via.Le), ReadRoutes.plan(leOnly = false, lastGood = Via.Classic))
    }

    @Test
    fun `a watch whose last good read was LE leads with LE and keeps RFCOMM last`() {
        assertEquals(listOf(Via.Le, Via.Le, Via.Classic), ReadRoutes.plan(leOnly = false, lastGood = Via.Le))
    }

    @Test
    fun `an LE-only device never tries RFCOMM`() {
        assertEquals(listOf(Via.Le, Via.Le), ReadRoutes.plan(leOnly = true, lastGood = null))
        assertEquals(listOf(Via.Le, Via.Le), ReadRoutes.plan(leOnly = true, lastGood = Via.Classic))
    }
}
